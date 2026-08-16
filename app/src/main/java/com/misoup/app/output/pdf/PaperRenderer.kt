package com.misoup.app.output.pdf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * PDF 渲染与打印（父任务书 T03 / tasks T03 渲染打印部分）。
 *
 * 链路（模块 08 定稿）：
 * ```
 * HTML(模板+题目) → 写 cache/paper.html → WebView 加载(file://) →
 *   onPageFinished 注入 renderMathInElement(safeRenderMath) → 等待 500ms →
 *   PrintManager.print(错题卷, WebView.createPrintDocumentAdapter(), attrs)
 * ```
 * 用户在弹出的系统打印面板选「保存为 PDF」即得 PDF（模块验收路径）。
 *
 * 公式兜底（双保险）：
 * 1. 模板内 `safeRenderMath()`：try/catch + throwOnError:false，KaTeX 渲染失败显示 `$...$` 原样不崩；
 * 2. KaTeX 资源缺失（assets/katex 未下载，T02 ⏸）时 `renderMathInElement` 不存在 → 跳过渲染，原样显示。
 *
 * ⚠️ 依赖 WebView/PrintManager，需模拟器或真机验证（JVM 单测不覆盖）。
 */
class PaperRenderer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 渲染注入后等待毫秒数（KaTeX 排版稳定） */
    companion object {
        const val RENDER_SETTLE_MS = 500L
        const val OUT_FILE_NAME = "paper.html"
    }

    /**
     * 渲染并触发打印。
     * @param html 完整 HTML（PdfTemplate.render 产物；图片为 file:// 绝对路径）
     * @param jobName 打印任务名（如「错题卷」）
     * @param onDone 打印流程结束回调（true=已触发打印；false=资源缺失失败）
     */
    fun print(html: String, jobName: String, onDone: (Boolean) -> Unit = {}) {
        val outDir = File(context.cacheDir, "misoup_export").apply { mkdirs() }
        val outFile = File(outDir, OUT_FILE_NAME)
        if (!runCatching { outFile.writeText(html) }.isSuccess) {
            onDone(false)
            return
        }

        // 本地模板 + 本地图片：允许 file:// 页面读取 file:// 资源
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = false   // 收紧：仅同源 file 资源

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 1. 注入公式渲染（模板内 safeRenderMath 为兜底，此处显式再触发一次）
                view?.evaluateJavascript(
                    "if (typeof safeRenderMath === 'function') safeRenderMath();",
                    null
                )
                // 2. 等待 KaTeX 排版稳定
                mainHandler.postDelayed({
                    triggerPrint(webView, jobName, onDone)
                }, RENDER_SETTLE_MS)
            }
        }

        webView.loadUrl("file://${outFile.absolutePath}")
    }

    private fun triggerPrint(webView: WebView, jobName: String, onDone: (Boolean) -> Unit) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            onDone(false)
            webView.destroy()
            return
        }
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, webView.createPrintDocumentAdapter(jobName), attrs)
        onDone(true)
        // 打印面板关闭后 WebView 由系统持有适配器；延迟销毁防崩溃
        mainHandler.postDelayed({ webView.destroy() }, 60_000L)
    }
}
