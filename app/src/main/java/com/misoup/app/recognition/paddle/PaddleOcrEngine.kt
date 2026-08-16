package com.misoup.app.recognition.paddle

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.misoup.app.recognition.paddle.detect.DetPostprocess
import com.misoup.app.recognition.paddle.detect.DetPreprocess
import com.misoup.app.recognition.paddle.dict.DictMapper
import com.misoup.app.recognition.paddle.model.AssetModelInstaller
import com.misoup.app.recognition.paddle.model.ModelBundle
import com.misoup.app.recognition.paddle.model.PaddleModels
import com.misoup.app.recognition.paddle.rec.ClsLogic
import com.misoup.app.recognition.paddle.rec.RecPreprocess
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PaddleOCR PP-OCRv4 mobile 离线引擎（模块 04，T03/T04/T07）
 *
 * 流程：det（长边 640）→ 行裁剪 → cls 方向校正 → rec 识别（CTC + 字典）→ 行排序组装。
 * - [OrtSession] 单实例 + [sessionLock]（防并发创建）；识别在 [Dispatchers.Default]
 * - 模型存放 filesDir/models，首用自愈：assets 有内置模型则自动复制，仍缺失 → 明确错误
 * - ⚠️ ONNX Runtime 依赖 Android 环境：本类全部推理路径（OrtSession.run / Bitmap 变换）
 *   需模拟器/真机验证；JVM 单测覆盖其调用的纯逻辑（DetPreprocess/DetPostprocess/
 *   DictMapper/ClsLogic/LineAssembler）
 */
class PaddleOcrEngine(
    private val context: Context,
    private val modelsDir: File = PaddleModels.modelsDir(context.filesDir),
) : OcrEngine {

    @Volatile
    private var ort: OrtSessions? = null
    private val sessionLock = Any()

    private class OrtSessions(
        val env: OrtEnvironment,
        val det: OrtSession,
        val rec: OrtSession,
        val cls: OrtSession,
        val dict: DictMapper,
    )

    /** 模型健康度（T08）：缺失/损坏 → [ModelBundle.valid]=false，含中文原因 */
    override fun health(): ModelBundle = ModelBundle.resolve(modelsDir)

    /**
     * 整图识别：det → 逐行 cls+rec → 阅读顺序组装。
     * 失败抛 [OcrEngineException]（调用方转 Result.failure）；模型缺失 → 明确错误 + 引导下载。
     */
    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        require(bitmap.width > 0 && bitmap.height > 0) { "输入位图非法: ${bitmap.width}x${bitmap.height}" }
        val sessions = ensureReady()          // 模型缺失/损坏 → 抛异常（T08）
        val boxes = detectLines(sessions, bitmap)
        val lines = boxes.map { box ->
            val crop = cropLine(bitmap, box) ?: return@map null
            val angle = ClsLogic.rotationDegrees(classify(sessions, crop))
            val oriented = if (angle == ClsLogic.ROTATE_180) rotate180(crop) else crop
            OcrLine(text = recognizeText(sessions, oriented), box = box)
        }.filterNotNull()
        val elapsed = SystemClock.elapsedRealtime() - started
        LineAssembler.assemble(lines, elapsed)
    }

    // ---------- 模型就绪（T01 首用复制 + T08 容错） ----------

    /** 懒加载 OrtEnvironment/OrtSession（单实例 + synchronized）；失败抛可读异常 */
    private fun ensureReady(): OrtSessions {
        ort?.let { return it }
        synchronized(sessionLock) {
            ort?.let { return it }
            installBundledModels()                       // assets → filesDir/models（幂等）
            val bundle = ModelBundle.resolve(modelsDir)
            if (!bundle.valid) {
                throw OcrEngineException(
                    kind = OcrFailureKind.MODEL_MISSING,
                    message = buildMissingMessage(bundle)
                )
            }
            ort = loadSessions(bundle)
            return ort!!
        }
    }

    /** assets/models → filesDir/models 首次复制（幂等：目标已达标则跳过） */
    private fun installBundledModels() {
        AssetModelInstaller.install(
            openAsset = { name ->
                runCatching { context.assets.open("models/$name") }.getOrNull()
            },
            modelsDir = modelsDir,
        )
    }

    /** T08：缺失 → 明确错误 + 引导下载；损坏 → 建议重下 */
    private fun buildMissingMessage(bundle: ModelBundle): String = buildString {
        append("OCR 模型不可用（engine=${PaddleModels.MODEL_TAG}）")
        if (bundle.missing.isNotEmpty()) append("；缺失: ${bundle.missing.joinToString("/")}")
        if (bundle.corrupt.isNotEmpty()) append("；损坏: ${bundle.corrupt.joinToString("/")}")
        append("。请在设置页「模型下载」拉取，或将模型放入 assets/models/ 后重装。")
    }

    /** 加载三件套 Session + 字典（⚠️ 真机验证项） */
    private fun loadSessions(bundle: ModelBundle): OrtSessions {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        return OrtSessions(
            env = env,
            det = env.createSession(bundle.det!!.absolutePath, options),
            rec = env.createSession(bundle.rec!!.absolutePath, options),
            cls = env.createSession(bundle.cls!!.absolutePath, options),
            dict = DictMapper.fromText(bundle.dict!!.readText()),
        )
    }

    // ---------- det（T03；⚠️ 真机验证项） ----------

    private fun detectLines(s: OrtSessions, bitmap: Bitmap): List<Box> {
        val spec = DetPreprocess.scaleForLongEdge(bitmap.width, bitmap.height)
        // 精确缩放 + 右下零填充到 32 对齐
        val scaled = Bitmap.createScaledBitmap(bitmap, spec.scaledWidth, spec.scaledHeight, true)
        val padded = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(padded).drawBitmap(scaled, 0f, 0f, null)
        val tensor = bitmapToNchw(
            padded, DetPreprocess.MEAN, DetPreprocess.STD,
            floatArrayOf(spec.width.toFloat(), spec.height.toFloat())
        )
        val shape = longArrayOf(1, 3, spec.height.toLong(), spec.width.toLong())
        OnnxTensor.createTensor(s.env, tensor, shape).use { input ->
            val output = s.det.run(mapOf("x" to input))
            output.use { r ->
                val scoreMap = (r.get(0) as ai.onnxruntime.OnnxTensor).floatBuffer
                val scores = FloatArray(scoreMap.remaining())
                scoreMap.get(scores)
                // 模型输出 [1,1,H,W] 已上采样到输入尺寸
                return DetPostprocess.scoreMapToBoxes(
                    scores = scores,
                    mapW = spec.width,
                    mapH = spec.height,
                    spec = spec,
                    origW = bitmap.width,
                    origH = bitmap.height,
                )
            }
        }
    }

    // ---------- cls + rec（T04；⚠️ 真机验证项） ----------

    /** 方向分类：输出 2 类 [0°, 180°] softmax → 0/180 */
    private fun classify(s: OrtSessions, crop: Bitmap): FloatArray {
        val resized = resizeTo(crop, 192, RecPreprocess.REC_HEIGHT)
        val tensor = bitmapToNchw(resized, RecPreprocess.REC_MEAN, RecPreprocess.REC_STD)
        val shape = longArrayOf(1, 3, RecPreprocess.REC_HEIGHT.toLong(), 192L)
        OnnxTensor.createTensor(s.env, tensor, shape).use { input ->
            val output = s.cls.run(mapOf("x" to input))
            output.use { r ->
                val buf = (r.get(0) as ai.onnxruntime.OnnxTensor).floatBuffer
                val logits = FloatArray(buf.remaining())
                buf.get(logits)
                return ClsLogic.softmax(logits)
            }
        }
    }

    /** 行识别：rec 输出 [1, T, C] → CTC greedy 解码 → 字典映射文本 */
    private fun recognizeText(s: OrtSessions, crop: Bitmap): String {
        val spec = RecPreprocess.resizeDims(crop.width, crop.height)
        val resized = resizeTo(crop, spec.width, spec.height)
        val tensor = bitmapToNchw(resized, RecPreprocess.REC_MEAN, RecPreprocess.REC_STD)
        val shape = longArrayOf(1, 3, RecPreprocess.REC_HEIGHT.toLong(), spec.width.toLong())
        OnnxTensor.createTensor(s.env, tensor, shape).use { input ->
            val output = s.rec.run(mapOf("x" to input))
            output.use { r ->
                val t = r.get(0) as ai.onnxruntime.OnnxTensor
                val info = t.info
                val shapeArr = info.shape
                val timeSteps = shapeArr.getOrNull(1)?.toInt() ?: 0
                val classes = shapeArr.getOrNull(2)?.toInt() ?: 0
                val buf = t.floatBuffer
                val probs = FloatArray(buf.remaining())
                buf.get(probs)
                return s.dict.decode(probs, timeSteps, classes)
            }
        }
    }

    // ---------- 位图工具（⚠️ 真机验证项） ----------

    /** 行裁剪（外扩 2px 容错），越界夹取 */
    private fun cropLine(bitmap: Bitmap, box: Box): Bitmap? {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = (box.right + 2).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + 2).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun resizeTo(src: Bitmap, w: Int, h: Int): Bitmap =
        if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)

    private fun rotate180(src: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** ARGB 位图 → NCHW float32（mean/std 归一化）；宽高与 shape 不符时拉伸 */
    private fun bitmapToNchw(
        bitmap: Bitmap,
        mean: FloatArray,
        std: FloatArray,
        dims: FloatArray = floatArrayOf(bitmap.width.toFloat(), bitmap.height.toFloat()),
    ): FloatBuffer {
        val w = dims[0].toInt()
        val h = dims[1].toInt()
        val pixels = IntArray(w * h)
        val src = if (bitmap.width == w && bitmap.height == h) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in pixels.indices) {
            val p = pixels[i]
            buf.put(((p shr 16 and 0xFF) - mean[0]) / std[0])   // R
            buf.put(((p shr 8 and 0xFF) - mean[1]) / std[1])    // G
            buf.put(((p and 0xFF) - mean[2]) / std[2])          // B
        }
        buf.rewind()
        return buf
    }
}

/** 引擎抽象（模块 04 文档 OcrEngine；JVM 测试注入假引擎） */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun health(): ModelBundle
}

/** 引擎失败分类（T08 容错提示文案） */
enum class OcrFailureKind { MODEL_MISSING, INFERENCE }

class OcrEngineException(
    val kind: OcrFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
