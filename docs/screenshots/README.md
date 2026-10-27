# 界面截图

都是真机/模拟器实拍，不是设计稿。

| 目录 | 环境 | 内容 |
| --- | --- | --- |
| `01-features/` | Android TV 11 模拟器，1920×1080 / 320dpi（= 960×540dp） | 第二批功能：下载管理、分类「更多」、稍后观看、字幕、缓存 |
| `02-ui-review/` | 同上 | 逐屏走查时的界面：首页/搜索/详情/片源/播放器/错误页/设置 |
| `03-tv-device/` | 海信 VIDAA（Android 11，1080p）真机 | 真机联调：4K HDR10+ HEVC 播放、OSD、片库续播 |

生成方式是 `python scripts/export-screenshots.py`：把 `.shots/` 里的 PNG（1920×1080，
单张 1~2MB）压成 1280 宽 JPEG（q=85），30 张共 2.4MB。`.shots/` 本身不进仓库。

## 01-features

| 文件 | 说明 |
| --- | --- |
| `01-home-with-downloads-tab.jpg` | 首页，左侧导航多了「下载」图标（第二个起第 4 个） |
| `02-downloads-list.jpg` | 下载页：文件名 / 站点徽章 / 状态 / 体积 / 进度条 / ↓↑ 速度，右侧「继续」「删除」 |
| `03-downloads-focus-actions.jpg` | 焦点落在「暂停」按钮上（白底） |
| `04-downloads-resumed.jpg` | 点「继续」后：状态变「下载中」、速度 784KB/s、顶部绿色提示 |
| `05-downloads-delete-confirm.jpg` | 删除确认：只删种子 / 删种子+删文件 / 取消 |
| `06-home-more-card.jpg` | 分类行末尾的「更多」卡片（白描边 = 焦点） |
| `07-more-page.jpg` | 「更多」页：4 列网格，副标题显示「共 20 部 · 第 1 / 500 页」 |
| `08-more-page-loaded.jpg` | 滚到底自动加载后：「共 40 部 · 第 2 / 500 页」 |
| `09-stream-actions.jpg` | 点片源弹底部操作条：立即播放 / 稍后观看（加入下载） / 取消 |
| `10-queued-for-download.jpg` | 选「稍后观看」后的绿色提示 |
| `11-subtitle-panel.jpg` | 播放中按「↓」打开字幕面板：关闭字幕 / 中文 / English |
| `12-subtitle-chinese-on.jpg` | 选中中文字幕后画面底部出现字幕「这是中文字幕测试 第一条」 |
| `13-settings-image-cache.jpg` | 设置页：保存 / 测试连接 / 清理缓存 + 「图片缓存：14 MB」 |

## 02-ui-review

| 文件 | 说明 |
| --- | --- |
| `01-home.jpg` | 首页：hero + 六行分类（最热/最新/国产剧/美剧/动漫/最新剧集） |
| `02-home-row-focus.jpg` | 焦点在第二行，整行被顶到固定高度，行标题和卡片标题都完整 |
| `03-home-last-row.jpg` | 最后一行，尾部留白不再是一大片空白 |
| `04-search.jpg` | 搜索「繁花」：4 列结果网格，首张带白描边 |
| `05-detail-series.jpg` | 剧集详情：左海报右信息 + 「▶ 播放第 1 集」+ 选集行 |
| `06-stream-list.jpg` | 片源列表：分辨率 / 标题 / 做种 / 体积 |
| `07-detail-movie.jpg` | 电影详情 |
| `08-player-osd.jpg` | 播放器 OSD：左上标题+时间，底部进度条 + 状态点 + 操作提示 |
| `09-player-error.jpg` | 错误页：剧照打底 + 半透明卡片（模拟器解不了 HEVC 10bit 时的提示） |
| `10-settings-input.jpg` | 设置页：焦点在输入框（紫框），软键盘不自动弹出 |

## 03-tv-device

| 文件 | 说明 |
| --- | --- |
| `01-home.jpg` | 真机首页 |
| `02-home-focus.jpg` | 真机焦点态（放大 + 3dp 白描边 + 标题提亮） |
| `03-stream-list.jpg` | 真机片源列表，2160P 排第一 |
| `04-startup-backdrop.jpg` | 起播阶段：剧照打底 + 「正在起播…」（不再是黑屏） |
| `05-playback-4k-hevc.jpg` | 4K HDR10+ HEVC 10bit 实际播放画面（含片源内嵌字幕） |
| `06-player-osd.jpg` | 真机 OSD，1080p 下字号与对齐正常 |
| `07-library-continue-watching.jpg` | 片库「继续观看」：蜘蛛侠 · 剩 143 分钟 + 进度条 |
