package tv.tvpt.client.ui

import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.tvpt.client.data.Api
import tv.tvpt.client.data.PlayRequest
import tv.tvpt.client.data.SettingsStore
import tv.tvpt.client.data.WatchEntry
import tv.tvpt.client.data.WatchHistoryStore
import androidx.compose.ui.graphics.Shape
import tv.tvpt.client.ui.components.FocusSurface
import tv.tvpt.client.ui.components.DownloadIcon
import tv.tvpt.client.ui.components.GhostButton
import tv.tvpt.client.ui.components.PillButton
import tv.tvpt.client.ui.components.requestFocusWithRetry
import tv.tvpt.client.ui.detail.DetailScreen
import tv.tvpt.client.ui.downloads.DownloadsScreen
import tv.tvpt.client.ui.home.HomeScreen
import tv.tvpt.client.ui.library.LibraryScreen
import tv.tvpt.client.ui.more.MoreScreen
import tv.tvpt.client.ui.player.PlayerScreen
import tv.tvpt.client.ui.search.SearchScreen
import tv.tvpt.client.ui.settings.SettingsScreen
import tv.tvpt.client.ui.streams.StreamsScreen
import tv.tvpt.client.ui.theme.TvptBackground
import tv.tvpt.client.ui.theme.TvptPrimary
import tv.tvpt.client.ui.theme.TvptSurfaceHigh
import tv.tvpt.client.ui.theme.TvptTextSecondary

private val DPAD_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Search("搜索", Icons.Filled.Search),
    Library("片库", Icons.Filled.List),
    // qBittorrent 的下载列表（后端代理），图标是自绘的（core icons 没有 Download）
    Downloads("下载", DownloadIcon),
    Settings("设置", Icons.Filled.Settings),
}

/** 片库点进去时片源页顶部那行副标题。 */
private fun episodeLabel(e: WatchEntry): String =
    if (e.type == "movie") {
        "电影片源"
    } else {
        "第 ${e.season} 季 第 ${e.episode} 集" +
            if (e.episodeName.isBlank()) "" else " · ${e.episodeName}"
    }

/** 极简导航栈：Tab 页 → 详情 → 片源 → 播放器，返回键逐层出栈。 */
sealed interface Screen {
    data class Tabbed(val tab: Tab) : Screen
    data class Detail(val type: String, val tmdbId: Int) : Screen
    /** 首页某一行的"更多"：同一个 feed，分页往下加载。 */
    data class More(val feed: String, val title: String) : Screen
    data class Streams(val play: PlayRequest, val subtitle: String) : Screen
    data class Player(
        val url: String,
        val title: String,
        val backdrop: String? = null,
        /** 带上片子的身份，播放器才能把进度记进播放历史。 */
        val play: PlayRequest? = null,
        val startPositionMs: Long = 0,
    ) : Screen
}

@Composable
fun AppRoot(
    initialQuery: String? = null,
    onQueryConsumed: () -> Unit = {},
    initialPlay: Pair<String, PlayRequest>? = null,
    onPlayConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    // 注意 initial 不能用 DEFAULT_BASE_URL：设置里存的是局域网地址时，首帧到
    // DataStore 读完之间有一个窗口期，这期间发出去的请求会打到默认域名上
    // （模拟器/内网环境直接 DNS 失败）。宁可先空一帧。
    val baseUrlState by store.baseUrl.collectAsState(initial = null)
    val lang by store.lang.collectAsState(initial = "zh-CN")
    val baseUrl = baseUrlState
    if (baseUrl == null) {
        // 设置还没读出来：给一帧深色底，避免用错地址发请求
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(TvptBackground)
        )
        return
    }

    // 电视上最容易踩的坑：左侧导航和内容区是两列，但没有 y 轴重叠的可聚焦节点，
    // D-pad 右/左在它们之间找不到落点，焦点会卡在导航里出不去。
    // 这里显式用 focusProperties 把两边缝起来。
    val railEntry = remember { FocusRequester() }
    val contentEntry = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }

    val history = remember { WatchHistoryStore(context) }
    // null = 还没读出来（DataStore 是异步的）。调试入口要靠它判断续播位置，
    // 所以必须能区分"空历史"和"还没读到"。
    val watchEntriesState by history.entries.collectAsState(initial = null)
    val watchEntries = watchEntriesState ?: emptyList()
    val scope = rememberCoroutineScope()

    val stack = remember { mutableStateListOf<Screen>(Screen.Tabbed(Tab.Home)) }
    fun push(s: Screen) = stack.add(s)
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // 退出确认：只有"首页 + 栈底"再按返回才问一句，其它情况一律先在应用内退。
    var exitConfirm by remember { mutableStateOf(false) }
    // 下载页点播放时的提示（解析磁力/等元数据要一两秒，不能干等一片黑）
    var playingNotice by remember { mutableStateOf<String?>(null) }
    // 用户是否已经按过遥控器：导航栏"移动即切换"只对真实按键生效，程序自己
    // 聚焦（启动/深链）不该把页面拨走
    var userMovedKeys by remember { mutableStateOf(false) }

    // ---- 待机幻灯片（动态壁纸）----
    // 首页剧照攒成一个池子，2 分钟没人按键就把它们铺满屏幕轮播。
    // 播放器那条分支在下面直接 return 了，所以"播放中"天然不会触发。
    var wallpaperFrames by remember { mutableStateOf<List<tv.tvpt.client.ui.wallpaper.WallpaperFrame>>(emptyList()) }
    var lastInputAt by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var wallpaperOn by remember { mutableStateOf(false) }

    // 返回键。之前只有详情/片源/播放器各自挂了 BackHandler，Tab 页（首页、搜索、
    // 片库、下载、设置）和"更多"页按返回会直接 finish 掉 Activity——用户感受就是
    // "不管在哪按返回都退出软件"。这里在根上补一个：先出栈，再回首页，最后才确认退出。
    androidx.activity.compose.BackHandler(enabled = !exitConfirm) {
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            (stack.lastOrNull() as? Screen.Tabbed)?.tab != Tab.Home -> {
                stack.clear()
                stack.add(Screen.Tabbed(Tab.Home))
            }
            else -> exitConfirm = true
        }
    }

    // 启动时给焦点一个着落点：否则首帧没有任何节点被选中，遥控器按第一下
    // 之前屏幕上没有任何"我在这儿"的提示。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { railEntry.requestFocus() }
    }

    var pending by remember { mutableStateOf(initialQuery) }
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            stack.clear()
            stack.add(Screen.Tabbed(Tab.Search))
            pending = initialQuery
        }
    }

    // 调试用：带 play_url 启动时直接进播放器
    LaunchedEffect(initialPlay, watchEntriesState) {
        val play = initialPlay ?: return@LaunchedEffect
        // 等历史读出来再决定从哪儿续播，否则首帧拿到的是空历史
        if (watchEntriesState == null) return@LaunchedEffect
        stack.clear()
        stack.add(Screen.Tabbed(Tab.Home))
        // 带 play_tmdb 时走真实的续播/记录逻辑
        val resume = watchEntries.firstOrNull {
            it.type == play.second.type &&
                it.tmdbId == play.second.tmdbId &&
                it.season == play.second.season &&
                it.episode == play.second.episode &&
                !it.finished
        }
        stack.add(
            Screen.Player(
                url = play.first,
                title = play.second.title,
                backdrop = Api.imageUrl(baseUrl, play.second.backdrop),
                play = play.second.takeIf { it.tmdbId > 0 },
                startPositionMs = resume?.positionMs ?: 0L,
            )
        )
        onPlayConsumed()
    }

    val top = stack.last()

    if (top is Screen.Player) {
        val play = top.play
        // 播放器上分两行显示：主标题是片名（+第几集），副标题才是种子发布名。
        // 之前整串发布名当标题，字又大，屏幕上就是一堵墙。
        val mediaTitle = play?.let { p ->
            if (p.title.isBlank()) {
                null
            } else {
                buildString {
                    append(p.title)
                    if (p.season > 0 && p.episode > 0) {
                        append("　第 ${p.season} 季 第 ${p.episode} 集")
                        if (p.episodeName.isNotBlank()) append(" · ${p.episodeName}")
                    }
                }
            }
        }
        PlayerScreen(
            url = top.url,
            title = mediaTitle ?: top.title,
            // 发布名和片名一样（中文种子常见）就不重复显示
            subtitle = top.title.takeIf {
                mediaTitle != null && it != play?.title && it != mediaTitle
            }.orEmpty(),
            backdrop = top.backdrop,
            startPositionMs = top.startPositionMs,
            onProgress = { pos, dur ->
                // 没有身份的播放（调试入口）不记历史，免得污染片库
                if (play != null && play.tmdbId > 0) {
                    scope.launch {
                        history.record(
                            WatchEntry(
                                type = play.type,
                                tmdbId = play.tmdbId,
                                imdbId = play.imdbId,
                                title = play.title,
                                poster = play.poster,
                                backdrop = play.backdrop,
                                season = play.season,
                                episode = play.episode,
                                episodeName = play.episodeName,
                                positionMs = pos,
                                durationMs = dur,
                                updatedAt = System.currentTimeMillis(),
                                releaseName = play.releaseName,
                            )
                        )
                    }
                }
            },
            onExit = { pop() },
        )
        return
    }

    // 空闲计时。进来先重置一次：从播放器退出来时用户刚按过键，不该马上进幻灯片。
    LaunchedEffect(Unit) {
        lastInputAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            if (!wallpaperOn && !exitConfirm && playingNotice == null &&
                wallpaperFrames.isNotEmpty() &&
                android.os.SystemClock.elapsedRealtime() - lastInputAt >= 2 * 60 * 1000L
            ) {
                wallpaperOn = true
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(TvptBackground)
            .onPreviewKeyEvent { ev ->
                lastInputAt = android.os.SystemClock.elapsedRealtime()
                // 幻灯片盖着的时候，第一下按键只负责"醒过来"，不能漏给下面
                if (wallpaperOn) {
                    wallpaperOn = false
                    return@onPreviewKeyEvent true
                }
                if (ev.key in DPAD_KEYS) userMovedKeys = true
                // 导航栏→内容区：Compsoe 的 2D 焦点搜索在这两列之间找不到落点
                // （y 轴没有重叠），所以在这里显式接管右键。（KeyDown 已被焦点
                // 搜索吃掉，能拿到的是 KeyUp，故不按事件类型过滤。）
                if (ev.key == Key.DirectionRight && railFocused) {
                    runCatching { contentEntry.requestFocus() }.isSuccess
                } else {
                    false
                }
            }
    ) {
        if (top is Screen.Tabbed) {
            NavRail(
                onFocusChange = { railFocused = it },
                current = top.tab,
                onSelect = { tab ->
                    stack.clear()
                    stack.add(Screen.Tabbed(tab))
                },
                // 遥控器在侧栏上下移动即切换页面，不用再按 OK。快速扫过多个
                // 图标时中间那些不该各发一次请求，所以这里先去抖 140ms。
                onFocusSelect = { tab ->
                    if (tab != top.tab) {
                        stack.clear()
                        stack.add(Screen.Tabbed(tab))
                    }
                },
                railEntry = railEntry,
                userMovedKeys = { userMovedKeys },
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (top) {
                is Screen.Tabbed -> when (top.tab) {
                    Tab.Home -> HomeScreen(
                        baseUrl = baseUrl,
                        lang = lang,
                        entryRequester = contentEntry,
                        railEntry = railEntry,
                        onOpen = { item -> push(Screen.Detail(item.type, item.tmdbId)) },
                        onMore = { feed, title -> push(Screen.More(feed, title)) },
                        onLoaded = { items ->
                            // 待机幻灯片的素材：用剧照（16:9 铺满才不糊），没有就用海报
                            wallpaperFrames = items.mapNotNull { it0 ->
                                val url = Api.imageUrl(
                                    baseUrl,
                                    it0.backdrop.ifBlank { it0.poster },
                                ) ?: return@mapNotNull null
                                tv.tvpt.client.ui.wallpaper.WallpaperFrame(
                                    imageUrl = url,
                                    title = it0.title,
                                    subtitle = listOfNotNull(
                                        it0.year.takeIf { y -> y > 0 }?.toString(),
                                        it0.rating.takeIf { r -> r > 0 }?.let { "★ %.1f".format(it) },
                                    ).joinToString("　·　"),
                                )
                            }
                        },
                    )
                    Tab.Search -> SearchScreen(
                        baseUrl = baseUrl,
                        lang = lang,
                        initialQuery = pending,
                        onQueryConsumed = { pending = null; onQueryConsumed() },
                        entryRequester = contentEntry,
                        railEntry = railEntry,
                    ) { item ->
                        push(Screen.Detail(item.type, item.tmdbId))
                    }
                    Tab.Library -> LibraryScreen(
                        entries = watchEntries,
                        baseUrl = baseUrl,
                        onOpen = { entry ->
                            // 直接带着上次的位置去片源列表：选一条源就续播
                            push(Screen.Streams(entry.toPlayRequest(), episodeLabel(entry)))
                        },
                        onClear = { scope.launch { history.clear() } },
                    )
                    Tab.Downloads -> DownloadsScreen(
                        baseUrl = baseUrl,
                        entryRequester = contentEntry,
                        railEntry = railEntry,
                        onPlay = { item ->
                            // 下载页直接播：qB 里只有 infohash，让后端用 magnet
                            // 重新解析一次拿播放直链（种子已在 qB 里，毫秒级）。
                            scope.launch {
                                playingNotice = "正在准备播放…"
                                val entry = watchEntries.firstOrNull {
                                    it.releaseName.isNotBlank() && sameRelease(it.releaseName, item.name)
                                }
                                val series = entry?.let { it.season > 0 } ?: false
                                Api.playTorrent(
                                    baseUrl = baseUrl,
                                    hash = item.hash,
                                    name = item.name,
                                    series = series,
                                    season = entry?.season ?: 0,
                                    episode = entry?.episode ?: 0,
                                    cid = entry?.imdbId.orEmpty(),
                                ).fold(
                                    onSuccess = { url ->
                                        playingNotice = null
                                        push(
                                            Screen.Player(
                                                url = url,
                                                title = entry?.title ?: item.name,
                                                backdrop = entry?.let {
                                                    Api.imageUrl(baseUrl, it.backdrop.ifBlank { it.poster })
                                                },
                                                play = entry?.toPlayRequest(),
                                                startPositionMs = entry?.positionMs ?: 0L,
                                            )
                                        )
                                    },
                                    onFailure = { playingNotice = "打不开这个任务：${it.message}" },
                                )
                            }
                        },
                    )
                    Tab.Settings -> SettingsScreen(
                        store = store,
                        currentBaseUrl = baseUrl,
                        currentLang = lang,
                        entry = contentEntry,
                        railEntry = railEntry,
                    )
                }
                is Screen.More -> MoreScreen(
                    baseUrl = baseUrl,
                    lang = lang,
                    feed = top.feed,
                    title = top.title,
                    onBack = { pop() },
                    onOpen = { item -> push(Screen.Detail(item.type, item.tmdbId)) },
                )
                is Screen.Detail -> DetailScreen(
                    baseUrl = baseUrl,
                    lang = lang,
                    type = top.type,
                    tmdbId = top.tmdbId,
                    onBack = { pop() },
                    resume = watchEntries.firstOrNull {
                        it.type == top.type && it.tmdbId == top.tmdbId
                    },
                    onPlay = { req ->
                        push(
                            Screen.Streams(
                                play = req,
                                subtitle = if (req.type == "movie") {
                                    "电影片源"
                                } else {
                                    "第 ${req.season} 季 第 ${req.episode} 集" +
                                        if (req.episodeName.isBlank()) "" else " · ${req.episodeName}"
                                },
                            )
                        )
                    },
                )
                is Screen.Streams -> StreamsScreen(
                    baseUrl = baseUrl,
                    play = top.play,
                    subtitle = top.subtitle,
                    onBack = { pop() },
                    onQueued = { release ->
                        // 片库要能看到"我加进下载但还没看"的片子，所以在本地先记
                        // 一条 queued 记录（进度沿用已有的，不覆盖）。
                        if (top.play.tmdbId > 0) {
                            scope.launch {
                                history.record(
                                    WatchEntry(
                                        type = top.play.type,
                                        tmdbId = top.play.tmdbId,
                                        imdbId = top.play.imdbId,
                                        title = top.play.title,
                                        poster = top.play.poster,
                                        backdrop = top.play.backdrop,
                                        season = top.play.season,
                                        episode = top.play.episode,
                                        episodeName = top.play.episodeName,
                                        updatedAt = System.currentTimeMillis(),
                                        releaseName = release,
                                        queued = true,
                                    )
                                )
                            }
                        }
                    },
                    onPlay = { url, label ->
                        // 同一部片、同一集才续播；换集就从头开始
                        val resume = watchEntries.firstOrNull {
                            it.type == top.play.type &&
                                it.tmdbId == top.play.tmdbId &&
                                it.season == top.play.season &&
                                it.episode == top.play.episode &&
                                !it.finished
                        }
                        push(
                            Screen.Player(
                                url = url,
                                title = label,
                                backdrop = Api.imageUrl(baseUrl, top.play.backdrop),
                                // 记下发布名：以后从「下载」页点同一个种子，能靠它
                                // 找回这是哪部片、看到第几分钟
                                play = top.play.copy(releaseName = label),
                                startPositionMs = resume?.positionMs ?: 0L,
                            )
                        )
                    },
                )
                is Screen.Player -> Unit
            }
        }
    }

    if (exitConfirm) {
        ExitConfirm(
            onCancel = { exitConfirm = false },
            onExit = {
                exitConfirm = false
                (context as? android.app.Activity)?.finish()
            },
        )
    }
    playingNotice?.let { text ->
        NoticeOverlay(text) { playingNotice = null }
    }
    if (wallpaperOn) {
        tv.tvpt.client.ui.wallpaper.BackdropWallpaper(
            frames = wallpaperFrames,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 两个按钮的确认框（退出应用）。 */
@Composable
private fun ExitConfirm(onCancel: () -> Unit, onExit: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocusWithRetry() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xC0000000))
            // 浮层要把返回键吃掉，否则会漏到下面的页面里
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onCancel(); true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(520.dp)
                .background(Color(0xF2101014), RoundedCornerShape(18.dp))
                .padding(28.dp),
        ) {
            Text("退出 tvpt？", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("正在播放的片源会立刻停止，下载任务不受影响。", color = TvptTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("取消", focusRequester = cancelFocus, onClick = onCancel)
                PillButton("退出应用", onClick = onExit)
            }
        }
    }
}

/** 轻量提示层：下载页点播放后要等后端解析，先把"正在准备"说清楚。 */
@Composable
private fun NoticeOverlay(text: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onDismiss(); true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Color(0xF2101014), RoundedCornerShape(14.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp),
        )
    }
}

/** 种子名和发布名经常只是大小写/扩展名不同，宽松比一下。 */
private fun sameRelease(releaseName: String, torrentName: String): Boolean {
    val a = releaseName.lowercase().trim()
    val b = torrentName.lowercase().trim()
    if (a.isEmpty() || b.isEmpty()) return false
    return b.contains(a) || a.contains(b)
}

/** 图标导航栏，版式参考 Stremio：窄条、图标居中、选中项高亮 + 左侧紫色指示条。 */
@Composable
private fun NavRail(
    onFocusChange: (Boolean) -> Unit,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onFocusSelect: (Tab) -> Unit,
    railEntry: FocusRequester,
    // 用 lambda 而不是 Boolean：焦点回调发生在按键的同一帧里，重组还没跑，
    // 传值会读到旧值（表现就是"按了下键，焦点到了搜索图标但页面不切"）
    userMovedKeys: () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    // 同一个 tab 的连续焦点事件合并；换 tab 才重新计时
    var pending by remember { mutableStateOf<Tab?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Column(
        Modifier
            .width(96.dp)
            .fillMaxHeight()
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "tvpt",
            color = TvptPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 22.dp),
        )
        Tab.entries.forEachIndexed { index, t ->
            NavItem(
                tab = t,
                selected = t == current,
                focusRequester = if (index == 0) railEntry else null,
                onFocused = {
                    if (userMovedKeys() && t != current && pending != t) {
                        pending = t
                        job?.cancel()
                        job = scope.launch {
                            kotlinx.coroutines.delay(140)
                            onFocusSelect(t)
                        }
                    }
                },
                onClick = { onSelect(t) },
            )
        }
    }
}

@Composable
private fun NavItem(
    tab: Tab,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)

    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .width(64.dp)
            .height(54.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = shape,
        focusedScale = 1.06f,
        ringWidth = 2.dp,
        ringInset = 2.dp,
        onFocusChanged = { if (it) onFocused() },
    ) { focused ->
        // 侧边指示条：当前所在标签才显示，和 Stremio 的侧栏一致
        if (selected && !focused) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .width(3.dp)
                    .height(22.dp)
                    .background(TvptPrimary, RoundedCornerShape(2.dp))
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .background(
                    when {
                        focused -> Color.White
                        selected -> Color.White.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    },
                    shape,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = when {
                    focused -> Color.Black
                    selected -> Color.White
                    else -> TvptTextSecondary
                },
                modifier = Modifier.size(23.dp),
            )
        }
    }
}

@Composable
private fun Placeholder(title: String, hint: String) {
    Column(
        Modifier.fillMaxSize().padding(start = 48.dp, top = 56.dp),
    ) {
        Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(hint, color = TvptTextSecondary, fontSize = 15.sp)
    }
}
