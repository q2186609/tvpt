package tv.tvpt.client.ui.player

import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.requestFocusWithRetry
import tv.tvpt.client.ui.theme.TvptTextSecondary
import tv.tvpt.client.ui.theme.TvptTextTertiary
import tv.tvpt.client.ui.components.PillButton
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 播放器：Media3 ExoPlayer 直接吃 tvpt 的 /dl 流（302 → 206）。
 *
 * 三件事是这个页面存在的理由：
 *  1. 起播前不给人看黑屏——先铺剧照，服务端把首尾分片下好、真正能出画面了再切；
 *  2. 遥控器能用——OK 暂停/继续，左右快退快进 10 秒，返回先收 OSD 再退出；
 *  3. 出错说人话——按错误码给"这台电视解不了 / 连不上 / 分片没下到"。
 *
 * OSD 是自己画的，没有用 PlayerView 自带的控制条：那套 UI 是手机时代的
 * 样子（灰色进度条、齿轮），和 Stremio 的观感差得远，而且在电视上焦点
 * 行为也不可控（控制条里的按钮会抢 D-pad 焦点）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    /** 片源发布名（长串那种）。作为标题下面的小字，别跟主标题抢地方。 */
    subtitle: String = "",
    backdrop: String? = null,
    /** 上次看到的位置（续播）。0 表示从头播。 */
    startPositionMs: Long = 0,
    /** 每 5 秒 + 退出时回调一次，用于写播放历史。 */
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler { onExit() }

    var errorText by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var preparing by remember { mutableStateOf(true) }
    var waited by remember { mutableStateOf(0) }
    var ready by remember(url, attempt) { mutableStateOf(false) }
    // 首帧渲染完成之前，PlayerView 的 SurfaceView 是一整块不透明黑。用剧照
    // 盖在它上面，直到真的有画面才撤，这样"点播→出画面"中间不会闪黑。
    var firstFrame by remember(url, attempt) { mutableStateOf(false) }

    // OSD 状态
    var osdVisible by remember { mutableStateOf(false) }
    var osdUntil by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    // 字幕：片源内嵌的文字轨（MKV 里的 SRT/ASS 都算），选中即切换
    var subtitles by remember { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    // 按下键打开的设置面板（字幕 + 画面）
    var panelOpen by remember { mutableStateOf(false) }
    var subtitlesOff by remember { mutableStateOf(false) }
    // 画面模式：默认全屏。全屏 = 填满屏幕不留黑边（宽银幕片源会裁掉两侧一点点），
    // 原画 = 保持片源原始比例（宽银幕上下有黑边，但画面完整）。
    var fullscreen by remember { mutableStateOf(true) }
    // 字幕面板当前选中项（0 = 关闭字幕，1.. = 各条轨道）。放在这一层是因为
    // 浮层里的焦点节点在真机上不可靠：面板刚出现时 requestFocus 会落空，
    // 按键落到播放器根节点上没人处理。根节点一定有焦点，交给它算 index 最稳。
    var panelIndex by remember { mutableStateOf(0) }
    val panelRows = buildPanelRows(subtitles, fullscreen)
    // 续播提示：进来先闪一句"已从 12:34 继续"，5 秒后自己消失
    var resumeHint by remember { mutableStateOf(startPositionMs > 0) }

    // 返回键分两级：OSD 打开时先收 OSD（电视上的通用习惯），再退到片源列表
    BackHandler(enabled = osdVisible) { osdVisible = false }

    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    // rememberUpdatedState：回调换了 lambda 也不会漏掉最新那个
    val progressSink by rememberUpdatedState(onProgress)

    LaunchedEffect(errorText) {
        if (errorText != null) {
            delay(120)
            runCatching { retryFocus.requestFocus() }
        }
    }

    fun pokeOsd() {
        osdVisible = true
        osdUntil = SystemClock.elapsedRealtime() + 5000
    }

    // 到点自动收 OSD；任何按键都会把 deadline 往后推
    LaunchedEffect(osdUntil) {
        if (osdUntil <= 0L) return@LaunchedEffect
        val left = osdUntil - SystemClock.elapsedRealtime()
        if (left > 0) delay(left)
        if (SystemClock.elapsedRealtime() >= osdUntil) osdVisible = false
    }

    val probeClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // 探测片源是否已经可以起播。
    //
    // 必须在 IO 线程发请求：LaunchedEffect 跑在 Main 上，直接 execute() 会抛
    // NetworkOnMainThreadException，被 runCatching 吞掉后永远拿到 0，界面就
    // 卡在「正在准备片源」不动。
    //
    // 两类"拿不到大小"要分开处理：服务端还没下到首尾分片（占位片 95KB 或
    // 非 2xx）属于正常，继续等；网络层抛 IOException 才是真连不上。
    LaunchedEffect(url, attempt) {
        preparing = true
        waited = 0
        var spent = 0
        var networkFails = 0
        lateinit var lastError: String
        while (spent < 180) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(url).head().build()
                    probeClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) 0L
                        else resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                }
            }
            val size = result.getOrDefault(0L)
            if (size > 4L * 1024 * 1024) {
                ready = true
                preparing = false
                return@LaunchedEffect
            }
            val ioError = result.exceptionOrNull() as? java.io.IOException
            if (ioError != null) {
                lastError = ioError.message ?: "网络错误"
                networkFails++
                if (networkFails >= 5) {
                    preparing = false
                    errorText = "连不上片源：$lastError。检查电视和宿主机是否同一个网络。"
                    return@LaunchedEffect
                }
            } else {
                networkFails = 0
            }
            waited = spent
            delay(3000)
            spent += 3
        }
        preparing = false
        errorText = "片源准备超时：种子还没有可播的数据（可能没人做种）。可以先换一条片源试试。"
    }

    val player = if (ready) {
        remember(url, attempt) {
            val dataSource = DefaultHttpDataSource.Factory()
                .setUserAgent("tvpt-tv/0.1")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                // 服务端边下边播时，某次 read 可能要等到下一个分片落地才开始
                // 返回字节（内部 wait_timeout 90s）。客户端读超时必须比它长，
                // 否则正常缓冲会被误判成"连不上"，播到一半突然报网络错误。
                .setReadTimeoutMs(120_000)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(url))
                    // 续播：prepare 之前 seek 是允许的，ExoPlayer 会直接从目标
                    // 位置起播，不用先从头下完
                    if (startPositionMs > 60_000L) seekTo(startPositionMs)
                    prepare()
                    playWhenReady = true
                }
        }
    } else {
        null
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 只关心文字轨。一条都没解出来时列表为空，面板会显示"这条片源没有内嵌字幕"。
                val list = mutableListOf<SubtitleOption>()
                tracks.groups.forEach { g ->
                    if (g.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            val lang = f.language.orEmpty()
                            val label = f.label?.takeIf { it.isNotBlank() }
                                ?: languageName(lang).takeIf { it.isNotBlank() }
                                ?: "字幕 ${list.size + 1}"
                            list.add(SubtitleOption(label, lang, g, i))
                        }
                    }
                }
                subtitles = list
                android.util.Log.d("tvpt-sub", "tracks: " + list.joinToString { "${it.label}/${it.language}#${it.index}" })
            }

            override fun onPlayerError(error: PlaybackException) {
                // 按错误码给一句能行动的话：绝大多数电视上播不了都是编码问题，
                // 直接弹 ERROR_CODE_xxx 只会让人来问"这是什么意思"。
                errorText = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                    -> "这个片源这台电视解不了（多是 HEVC 10bit / 杜比视界）。换一条 1080p H.264 的片源试试。"

                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    -> "连不上片源服务器。确认这台电视和宿主机在同一个网络，代理/证书都没被拦。"

                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    -> "片源服务器返回了错误。种子可能还没下到要播的分片，稍后重试。"

                    else -> "播放失败：${error.errorCodeName}\n${error.cause?.message?.take(200) ?: ""}"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            // 退出时先记进度再释放：这是最可靠的一次落盘（用户按返回直接走人）
            runCatching {
                val d = player.duration
                if (d > 0) progressSink(player.currentPosition.coerceAtLeast(0L), d)
            }
            player.removeListener(listener)
            player.stop()
            player.release()
        }
    }

    // 播放历史：每 5 秒落一次盘，退出时再补一次。
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(5000)
            val d = p.duration
            if (d > 0) progressSink(p.currentPosition.coerceAtLeast(0L), d)
        }
    }

    // 位置/时长/缓冲状态：OSD 打开时每 400ms 刷一次就够了（10Hz 的进度条在
    // 3 米外看不出差别，刷太快纯属浪费合成）。
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            position = max(0L, player.currentPosition)
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            delay(400)
        }
    }

    // 有画面之后把焦点交给 Compose 根节点，遥控器按键才会走下面这个 onPreviewKeyEvent
    LaunchedEffect(firstFrame) {
        if (firstFrame) {
            pokeOsd()
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(resumeHint) {
        if (resumeHint) {
            delay(6000)
            resumeHint = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 面板开着时，方向键和 OK 全部由面板逻辑接管（下面直接 return，
                // 不能再往下走一遍「下键 = 打开面板」，否则选中项会被打回第一项）。
                if (panelOpen) {
                    val selectable = panelRows.filter { it.selectable }
                    val last = (selectable.size - 1).coerceAtLeast(0)
                    // 这里必须直接 return：以前写成普通 if 块，按键处理完还会往下走，
                    // 被下面那个「下键=打开面板」再吃一遍，选中项每次都被打回第一项，
                    // 面板里除了第一项根本选不动。
                    return@onPreviewKeyEvent when (ev.key) {
                        Key.DirectionUp -> {
                            panelIndex = (panelIndex - 1).coerceAtLeast(0)
                            true
                        }

                        Key.DirectionDown -> {
                            panelIndex = (panelIndex + 1).coerceAtMost(last)
                            true
                        }

                        Key.DirectionCenter, Key.Enter -> {
                            val row = selectable.getOrNull(panelIndex)
                            if (row != null) {
                                when (row.kind) {
                                    PanelKind.Subtitle -> {
                                        player?.let { applySubtitle(it, row.option) }
                                        subtitlesOff = row.option == null
                                    }

                                    PanelKind.Screen -> fullscreen = row.fullscreen
                                    // 分组标题 / 说明行不可选，正常走不到这里
                                    PanelKind.Header, PanelKind.Note -> Unit
                                }
                            }
                            panelOpen = false
                            true
                        }

                        Key.Back -> {
                            panelOpen = false
                            true
                        }

                        else -> true
                    }
                }
                val p = player ?: return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (p.isPlaying) p.pause() else p.play()
                        pokeOsd()
                        true
                    }

                    Key.DirectionLeft -> {
                        p.seekTo(max(0L, p.currentPosition - 10_000))
                        pokeOsd()
                        true
                    }

                    Key.DirectionRight -> {
                        p.seekTo(p.currentPosition + 10_000)
                        pokeOsd()
                        true
                    }

                    Key.DirectionUp -> {
                        pokeOsd()
                        true
                    }

                    // 下键打开设置面板（字幕 + 画面），OSD 里也写了这条提示
                    Key.DirectionDown -> {
                        panelOpen = true
                        osdVisible = false
                        // 光标落在当前生效的那一项上：关着字幕就停在「关闭字幕」，
                        // 开着就停在正在用的那条轨；没有字幕轨就落到「画面」组
                        val selectable = panelRows.filter { it.selectable }
                        val active = selectable.indexOfFirst { row ->
                            row.kind == PanelKind.Subtitle &&
                                (if (subtitlesOff) row.option == null else row.option != null)
                        }
                        panelIndex = if (active >= 0) {
                            active
                        } else {
                            selectable.indexOfFirst { it.kind == PanelKind.Screen }.coerceAtLeast(0)
                        }
                        true
                    }

                    Key.Back -> {
                        if (panelOpen) {
                            panelOpen = false
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
    ) {
        // 底层：剧照。没有播放器（准备中）、播放器被收起（出错）时露出来的
        // 都是这一层，所以它必须一直在。
        BackdropLayer(backdrop)

        if (player != null && errorText == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        // 画面层不能抢焦点：它一抢走，Compose 根节点的
                        // onPreviewKeyEvent 就收不到遥控器按键（字幕面板、
                        // 快进、暂停全部失灵，只有 PlayerView 自己在响应）。
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        // 自己画 OSD：自带控制条是手机样式，而且在电视上会抢焦点
                        useController = false
                        resizeMode = if (fullscreen) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                // factory 只跑一次：切换画面模式必须在这里改，否则按了没反应
                update = { view ->
                    view.resizeMode = if (fullscreen) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
            )
        }

        // 覆盖层：真正的画面出来之前一直盖在最上面。PlayerView 一旦挂上就是
        // 一整块不透明黑，所以这里要把剧照再铺一遍——只放在底层是不够的。
        //
        // 只看 firstFrame 不够：4K HDR 片源在真机上会出现 onRenderedFirstFrame
        // 已经回调、但 Surface 还在等解码器填数据的十几秒，屏幕上就是"上下露着
        // 剧照、中间一块黑"。所以起播阶段（位置还停在开头）只要还在缓冲就继续
        // 盖着，多盖一会儿的代价远小于让人对着黑块发呆。
        val startingUp = !firstFrame || (buffering && position < 10_000L)
        if (errorText == null && startingUp) {
            if (player != null) BackdropLayer(backdrop)

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 56.dp, bottom = 56.dp),
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        color = Color(0x8CFFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        // 首帧回调了但还在填缓冲：这就是真机上那段"黑块"时间
                        firstFrame -> "正在缓冲…"
                        preparing -> "正在准备片源…" + if (waited > 0) "（已等待 ${waited} 秒）" else ""
                        else -> "正在起播…"
                    },
                    color = Color.White,
                    fontSize = 16.sp,
                )
                if (preparing) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "种子要先下到开头和结尾的分片才能起播，冷门片源可能要 1~2 分钟",
                        color = Color(0xCCFFFFFF),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillButton("返回换一条片源", onClick = onExit)
                    }
                }
            }
        }

        // 缓冲中：给个不打扰的小转圈（Stremio 也是居中细环）。
        // 起播阶段已经有剧照 + 文案了，不用再叠一个圈。
        if (buffering && errorText == null && !startingUp) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).height(44.dp),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 3.dp,
            )
        }

        // 设置面板（按下键打开）：字幕 + 画面
        if (panelOpen && player != null) {
            PlaybackPanel(rows = panelRows, selected = panelIndex)
        }

        // 自绘 OSD
        if (osdVisible && errorText == null && player != null && firstFrame) {
            Osd(
                title = title,
                subtitle = subtitle,
                position = position,
                duration = duration,
                playing = playing,
                resumeFrom = if (resumeHint) startPositionMs else 0L,
            )
        }

        errorText?.let { msg ->
            // 半透明压暗而不是全黑：背后的剧照留着，出错时画面不至于"死黑"
            Box(Modifier.fillMaxSize().background(Color(0xA6000000))) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 900.dp)
                        .background(Color(0xD90A0A0C), RoundedCornerShape(18.dp))
                        .padding(horizontal = 56.dp, vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title, color = Color(0x99FFFFFF), fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 80.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PillButton("重试", focusRequester = retryFocus) {
                            errorText = null
                            attempt++
                        }
                        PillButton("返回片源列表") { onExit() }
                    }
                }
            }
        }
    }
}

/** 播放器 OSD：左上角标题，底部进度条 + 时间 + 操作提示。 */
@Composable
private fun BoxScope.Osd(
    title: String,
    subtitle: String = "",
    position: Long,
    duration: Long,
    playing: Boolean,
    resumeFrom: Long = 0L,
) {
    // 上下两条渐变，只压住标题和进度条附近，不挡画面
    Box(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(180.dp)
            .background(
                Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))
            )
    )
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(240.dp)
            .background(
                // 压得比之前更实：片源常带内嵌字幕，白字幕正好落在提示行附近，
                // 渐变太浅时两行白字糊在一起。
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xA6000000), Color(0xF20A0A0C))
                )
            )
    )

    Column(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 56.dp, end = 56.dp, top = 36.dp),
    ) {
        // 主标题只放片名（+第几集），17sp 一行；发布名那种「[凡人修仙传].A.Record…
        // 2160p.WEB-DL.H265」的长串放到下面一行 11sp，两行各干各的事，
        // 不会出现大字顶到屏幕边缘、还压住片头字幕的情况。
        Text(
            title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = Color(0x8CFFFFFF),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.5f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (duration > 0) "${formatTime(position)} / ${formatTime(duration)}" else formatTime(position),
            color = Color(0xCCFFFFFF),
            fontSize = 14.sp,
        )
    }

    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(start = 56.dp, end = 56.dp, bottom = 40.dp),
    ) {
        if (resumeFrom > 0) {
            Text(
                "已从 ${formatTime(resumeFrom)} 继续播放",
                color = Color(0xB3FFFFFF),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(Color(0x59FFFFFF), RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Color.White, RoundedCornerShape(3.dp)),
            )
            // 进度圆点：远看也能定位
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .padding(end = 0.dp)
                        .height(13.dp)
                        .widthIn(min = 13.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(if (playing) Color(0xFF7BD88F) else Color(0xFFFFC15E), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (playing) "播放中" else "已暂停",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(14.dp))
            Text("·", color = Color(0x80FFFFFF), fontSize = 13.sp)
            Spacer(Modifier.width(14.dp))
            Text(
                "OK 暂停/继续 · ←/→ 快退快进 10 秒 · ↓ 字幕/画面 · 返回键退出",
                color = Color(0xB3FFFFFF),
                fontSize = 13.sp,
            )
        }
    }
}

/** 秒 → mm:ss / h:mm:ss */
private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 整屏剧照 + 压暗渐变，播放器没画面时用它兜底。 */
@Composable
private fun BackdropLayer(backdrop: String?) {
    if (backdrop == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C)))
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(backdrop)
            .crossfade(200).size(1600, 900).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x99000000), Color(0xCC000000), Color(0xE6000000)),
                )
            )
    )
}

/** 一条内嵌字幕轨。group/index 是 ExoPlayer 选择轨道时需要的定位。 */
private data class SubtitleOption(
    val label: String,
    val language: String,
    val group: Tracks.Group,
    val index: Int,
)

/** 切换字幕：opt = null 表示关闭文字轨。 */
private fun applySubtitle(player: Player, opt: SubtitleOption?) {
    player.trackSelectionParameters = if (opt == null) {
        player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    } else {
        player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setOverrideForType(TrackSelectionOverride(opt.group.mediaTrackGroup, opt.index))
            .build()
    }
    android.util.Log.d(
        "tvpt-sub",
        "applied: label=${opt?.label} idx=${opt?.index} off=${opt == null}",
    )
}

/** ISO 639-1/2 的语言码折成中文名，认不出来的原样返回。 */
private fun languageName(code: String): String {
    if (code.isBlank()) return ""
    return when (code.lowercase().substringBefore('-').substringBefore('_')) {
        "zh", "chi", "zho" -> "中文"
        "en", "eng" -> "English"
        "ja", "jpn" -> "日本語"
        "ko", "kor" -> "한국어"
        "fr", "fra", "fre" -> "Français"
        "de", "deu", "ger" -> "Deutsch"
        "es", "spa" -> "Español"
        "ru", "rus" -> "Русский"
        else -> code
    }
}

/** 面板行的种类：分组标题和说明行不可选，只有字幕行和画面行能按。 */
private enum class PanelKind { Header, Note, Subtitle, Screen }

/**
 * 面板的一行。字幕行带 option（null = 关闭字幕），画面行带 fullscreen。
 * 用扁平列表而不是嵌套结构：选中索引只数可选行，遥控器的上下键逻辑就很简单。
 */
private data class PanelRow(
    val label: String,
    val kind: PanelKind,
    val hint: String = "",
    val selectable: Boolean = true,
    val option: SubtitleOption? = null,
    val fullscreen: Boolean = false,
)

/** 组装面板：字幕组（关闭字幕 + 各条内嵌轨）+ 画面组（全屏 / 原画）。 */
private fun buildPanelRows(subs: List<SubtitleOption>, fullscreen: Boolean): List<PanelRow> {
    val rows = mutableListOf<PanelRow>()
    rows += PanelRow(label = "字幕", kind = PanelKind.Header, selectable = false)
    if (subs.isEmpty()) {
        rows += PanelRow(
            label = "这条片源没有内嵌字幕",
            kind = PanelKind.Note,
            selectable = false,
        )
    } else {
        rows += PanelRow(label = "关闭字幕", kind = PanelKind.Subtitle, option = null)
        subs.forEach { opt ->
            rows += PanelRow(
                label = opt.label,
                kind = PanelKind.Subtitle,
                hint = opt.language,
                option = opt,
            )
        }
    }
    rows += PanelRow(label = "画面", kind = PanelKind.Header, selectable = false)
    rows += PanelRow(
        label = "全屏",
        kind = PanelKind.Screen,
        hint = if (fullscreen) "使用中" else "填满屏幕",
        fullscreen = true,
    )
    rows += PanelRow(
        label = "原画",
        kind = PanelKind.Screen,
        hint = if (fullscreen) "保持比例" else "使用中",
        fullscreen = false,
    )
    return rows
}

/**
 * 播放设置面板：右侧浮层，「字幕」和「画面」两组。
 *
 * 纯展示组件——按键由 PlayerScreen 根节点处理（见上面 panelIndex 的注释）。
 * 面板本身不接焦点：电视上的浮层焦点在真机上很难拿稳，交给根节点最可靠。
 */
@Composable
private fun PlaybackPanel(
    rows: List<PanelRow>,
    selected: Int,
) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 40.dp)
                .width(340.dp)
                .background(Color(0xF2101014), RoundedCornerShape(16.dp))
                .padding(vertical = 18.dp, horizontal = 16.dp),
        ) {
            // 渲染时数一遍可选行，和第几项选中对得上
            var ordinal = -1
            rows.forEach { row ->
                when {
                    row.kind == PanelKind.Header -> {
                        if (ordinal >= 0) Spacer(Modifier.height(12.dp))
                        Text(
                            row.label,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                        )
                    }

                    row.kind == PanelKind.Note -> Text(
                        row.label,
                        color = TvptTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                    )

                    else -> {
                        ordinal++
                        PanelItem(
                            label = row.label,
                            hint = row.hint,
                            selected = ordinal == selected,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "上下选择 · OK 确认 · 返回键收起",
                color = TvptTextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun PanelItem(
    label: String,
    selected: Boolean,
    hint: String = "",
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (selected) Color.White else Color(0x14FFFFFF), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint.isNotBlank() && hint != label) {
            Text(
                hint,
                color = if (selected) Color(0x99000000) else TvptTextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}
