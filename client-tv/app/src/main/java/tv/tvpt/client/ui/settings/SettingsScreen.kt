package tv.tvpt.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.ImageCache
import tv.tvpt.client.data.SettingsStore
import tv.tvpt.client.ui.components.GhostButton
import tv.tvpt.client.ui.theme.TvptTextSecondary

@Composable
fun SettingsScreen(
    store: SettingsStore,
    currentBaseUrl: String,
    currentLang: String,
    entry: FocusRequester,
    railEntry: FocusRequester,
) {
    var text by remember(currentBaseUrl) { mutableStateOf(currentBaseUrl) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val saveBtn = remember { FocusRequester() }
    val testBtn = remember { FocusRequester() }
    val cacheBtn = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 图片磁盘缓存占用：进页面算一次，清理后再算一次
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ImageCache.sizeBytes(ctx)
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 48.dp)) {
        Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("服务器地址") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            // 导航栏按右键落到这里（entry），左键回导航栏；向下才是按钮。
            // 不显式接的话，右键会按"水平方向最近"挑中下面的「保存」，
            // 用户想改地址却先撞到按钮上。
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .focusRequester(entry)
                .focusProperties { left = railEntry; down = saveBtn }
                // 电视输入框一聚焦就弹软键盘，键盘会把 D-pad 全吃掉（按"下"
                // 只是在键盘里换字母）。改成聚焦时收键盘、按 OK 才调出来。
                .onFocusChanged { if (it.isFocused) keyboard?.hide() }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (ev.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                keyboard?.show()
                                true
                            }

                            Key.DirectionDown -> {
                                keyboard?.hide()
                                runCatching { saveBtn.requestFocus() }.isSuccess
                            }

                            else -> false
                        }
                    }
                },
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GhostButton(
                "保存",
                focusRequester = saveBtn,
                modifier = Modifier.focusProperties {
                    up = entry
                    left = railEntry
                    down = saveBtn
                    right = testBtn
                },
            ) {
                scope.launch { store.setBaseUrl(text); status = "已保存" }
            }
            GhostButton(
                "测试连接",
                focusRequester = testBtn,
                modifier = Modifier.focusProperties {
                    up = entry
                    left = saveBtn
                    right = cacheBtn
                    down = testBtn
                },
            ) {
                scope.launch {
                    status = "测试中…"
                    status = Api.health(text).fold(
                        onSuccess = { "连接正常：$it" },
                        onFailure = { "连接失败：${it.message}" },
                    )
                }
            }
            GhostButton(
                "清理缓存",
                focusRequester = cacheBtn,
                modifier = Modifier.focusProperties {
                    up = entry
                    left = testBtn
                    down = cacheBtn
                },
            ) {
                scope.launch {
                    ImageCache.clear(ctx)
                    cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ImageCache.sizeBytes(ctx)
                    }
                    status = "图片缓存已清理"
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(status, color = TvptTextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "图片缓存：" + (cacheBytes?.let { ImageCache.humanSize(it) } ?: "计算中…") +
                "（海报/剧照的本地缓存，最多 300MB）",
            color = TvptTextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(26.dp))
        Text(
            "语言：$currentLang　·　画面与音轨由片源决定",
            color = TvptTextSecondary,
            fontSize = 13.sp,
        )
    }
}
