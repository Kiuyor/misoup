package com.misoup.app.ui.screens.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.misoup.app.settings.mine.AboutInfo

/**
 * 关于（模块 10 T06）：版本号 / GitHub / GPL-3.0 / 隐私政策（链接+摘要）/ 捐赠（爱发电）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    versionName: String = "",
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text(AboutInfo.APP_NAME, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "版本 ${versionName.ifBlank { "0.1.0" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = { uriHandler.openUri(AboutInfo.GITHUB_URL) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("GitHub 开源仓库（GPL-3.0）") }

            OutlinedButton(
                onClick = { uriHandler.openUri(AboutInfo.LICENSE_URL) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开源许可：${AboutInfo.LICENSE_NAME}") }

            // ---- 隐私政策 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("隐私政策", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "摘要（全文见 PRIVACY.md）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AboutInfo.privacySummaryLines().forEach { line ->
                        Text("· $line", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { uriHandler.openUri(AboutInfo.PRIVACY_URL) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("查看完整隐私政策") }
                }
            }

            // ---- 捐赠 ----
            OutlinedButton(
                onClick = { uriHandler.openUri(AboutInfo.DONATE_URL) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("捐赠支持（爱发电）") }
        }
    }
}
