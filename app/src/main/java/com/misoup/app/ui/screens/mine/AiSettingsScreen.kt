package com.misoup.app.ui.screens.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.AppGraph
import com.misoup.app.recognition.visionllm.EndpointPresets
import com.misoup.app.recognition.visionllm.LlmConfig
import com.misoup.app.recognition.visionllm.VisionLlmClient
import com.misoup.app.settings.ai.PrefsStrategySettingsStore
import com.misoup.app.settings.ai.RecognitionStrategy

/**
 * AI 增强设置（模块 10 T02）：模型配置（引导+BYOK）/ 识别策略三档 / 限速滑块
 *
 * 视觉与文本模型共用同一份配置（工程现状：文本通道复用 05 配置仓库）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    vm: AiSettingsViewModel = viewModel(factory = aiViewModelFactory()),
) {
    val state by vm.state.collectAsState()
    val uriHandler = LocalUriHandler.current

    // 草稿字段（随 state.config 变化同步；保存时回写）
    var baseUrl by remember { mutableStateOf(state.config.baseUrl) }
    var model by remember { mutableStateOf(state.config.model) }
    var apiKey by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(state.config.enabled) }
    var rateLimit by remember { mutableStateOf(state.config.rateLimit) }
    LaunchedEffect(state.config) {
        baseUrl = state.config.baseUrl
        model = state.config.model
        enabled = state.config.enabled
        rateLimit = state.config.rateLimit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 增强") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 开关 + 状态 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI 增强（视觉识别/变式/答疑）", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.ready) "已配置：${state.config.baseUrl}" else "未配置完整（需端点 + Key + 模型名）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            // ---- 端点预设 ----
            Text("端点预设（一键填入）", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EndpointPresets.all.forEach { preset ->
                    OutlinedButton(
                        onClick = {
                            vm.applyPreset(preset)
                            baseUrl = preset.baseUrl
                            model = preset.defaultModel
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(preset.displayName, maxLines = 1)
                    }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // ---- BYOK 手填 ----
            Text("自定义端点（BYOK）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL（https://…）") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名（如 glm-4v-flash）") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (state.hasApiKey) "API Key（已保存，输入可替换）" else "API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 引导：当前预设带注册页 → 「获取免费 Key」跳转
            val currentPreset = EndpointPresets.byBaseUrl(state.config.baseUrl)
            currentPreset?.registerUrl?.let { url ->
                OutlinedButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("获取免费 Key（${currentPreset.displayName}）")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.save(
                            state.config.copy(
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                                apiKey = apiKey,
                                enabled = enabled,
                                rateLimit = rateLimit,
                            )
                        )
                        apiKey = ""
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存配置") }
                OutlinedButton(onClick = {
                    vm.applyPreset(EndpointPresets.CUSTOM)
                    baseUrl = ""
                    model = ""
                }, modifier = Modifier.weight(1f)) { Text("清空端点") }
                OutlinedButton(onClick = { vm.clearApiKey() }, modifier = Modifier.weight(1f)) {
                    Text("清除 Key")
                }
            }

            // ---- 连通测试 ----
            Button(
                onClick = { vm.testConnection() },
                enabled = !state.testing && state.ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("测试中…")
                } else {
                    Text("连通测试（发送最小请求，不含图片）")
                }
            }
            state.pingResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()

            // ---- 识别策略三档 ----
            Text("识别策略", style = MaterialTheme.typography.titleSmall)
            Text(
                "隐私模式：离线优先/仅离线时不会把题目图片发送到云端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RecognitionStrategy.entries.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.strategy == s,
                        onClick = { vm.setStrategy(s) }
                    )
                    Column {
                        Text(s.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            s.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // ---- 限速滑块 ----
            Text("限速：${rateLimit} req/10s（免费通道保护）", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = rateLimit.toFloat(),
                onValueChange = { rateLimit = it.toInt() },
                onValueChangeFinished = {
                    vm.setRateLimit(rateLimit)
                    vm.save(state.config.copy(rateLimit = rateLimit))
                },
                valueRange = LlmConfig.MIN_RATE_LIMIT.toFloat()..LlmConfig.MAX_RATE_LIMIT.toFloat(),
                steps = LlmConfig.MAX_RATE_LIMIT - LlmConfig.MIN_RATE_LIMIT - 1
            )
            Text(
                "默认 3 req/10s；调高可提升吞吐，但可能触发免费通道限流",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** AI 设置工厂（AppGraph.llmConfig + 策略存储 + 真实 ping 客户端） */
@Composable
private fun aiViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            val pingClient = VisionLlmClient<String>(
                okHttp = VisionLlmClient.defaultHttpClient(),
                cfg = { AppGraph.llmConfig!!.load() },
                imageToDataUrl = { it }
            )
            AiSettingsViewModel(
                repo = AppGraph.llmConfig!!,
                strategyStore = PrefsStrategySettingsStore(context),
                pingAction = { pingClient.ping() },
            )
        }
    }
}
