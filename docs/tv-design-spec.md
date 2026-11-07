# 电视端视觉规范（10-foot UI）

> 适用：`ui-tv`（11 个页面 + 8 个组件）、`ui-media` 的播放器控制层、`data` 的投屏接收页、`ui-kit` 里被电视端用到的公共组件。
> 不适用：手机端页面（手机走 `ProomSpace` / `ProomSp` 那套，见文末「两套 token 的边界」）。
> 焦点环与焦点行为见另一份 `tv-focus-spec.md`，本文只管「尺寸」。

---

## 0. 一句话原则

**电视上所有尺寸只有档位，没有「这次先这样」的值。**
写下一个非档位数字（13dp、21dp、34dp、40sp），三米外没人看得出它精确到 1dp，
但它在代码里会变成下一次复制粘贴的源头 —— 这正是当前混乱的成因。

---

## 1. 画布与安全区

| 项 | 值 | 说明 |
|---|---|---|
| 基准画布 | 960×540dp | 1080p@2x / 4K@4x 都折算到这里，由 `ProomRoot` 整体缩放 density |
| 左右安全区 | `TvLayout.insetX` = 48dp | 5% 过扫描。**页面级左右边距只有这一个值** |
| 上下安全区 | `TvLayout.insetY` = 27dp | 上下过扫描比左右小 |
| 顶部栏高 | 64dp | 含在安全区内，见 `TvRootHeaderSpec.height` |
| 可聚焦目标下限 | `TvLayout.minTouch` = 48dp | 低于 48 的可点元素视为 bug（手机端 44 不算） |

页面内容**必须**贴着 48dp 左右边距排版。空态文案、错误提示也不许缩到 32/26/20/12dp 自成一套。

上下安全区由壳统一让位：`MainActivity` 的 `barTop = max(系统栏, TvLayout.insetY)`。
页面自己**不要**再叠一层 —— 叠两次会把顶栏压到 90dp，首屏直接少一排内容。
例外只有播放器（沉浸式，自己吃 `insetY`）。

---

## 2. 间距：4dp 栅格，8 档

```kotlin
TvSpace.s4  = 4.dp    // 图标与文字之间、标签内边距
TvSpace.s8  = 8.dp    // 紧密元素（角标内边距、缩略图与文字）
TvSpace.s12 = 12.dp   // 列表行内部、文本块之间
TvSpace.s16 = 16.dp   // 卡片之间的 gap、chip 水平内边距、区块内元素间距
TvSpace.s20 = 20.dp   // 标题与其内容
TvSpace.s24 = 24.dp   // 区块之间（同页主要分节）
TvSpace.s32 = 32.dp   // 大区块之间、页面底部留白
TvSpace.s48 = 48.dp   // 安全区边距、超大留白
```

规则：

1. **只有这 8 档。** 5/6/7/9/10/11/13/14/18/22/26/30 一律不许出现。
2. 同一层级（例如「所有区块之间」）必须用同一个值。当前项目里区块间距有 8/12/14/18/20/26 六种，这是最刺眼的一处。
3. 间距从 `TvSpace` 取，不从 `ProomSpace` 取 —— 后者 `md=14`、`xl=26` 不是 4 的倍数，是本项目历史包袱。

---

## 3. 字阶：8 级，不许裸 sp

| 档位 | 值 | 用途 |
|---|---|---|
| `ProomType.display` | 40/48 | 全屏页 Hero 大标题、详情页片名 |
| `ProomType.headline` | 30/38 | 页面标题（`TvPageHeader` 默认） |
| `ProomType.title` | 24/31 | 区块标题（「继续观看」「我的收藏」） |
| `ProomType.body` | 18/26 | 正文、简介、按钮文字、搜索框输入 |
| `ProomType.cardTitle` | 17/23 | 卡片标题（两行定高） |
| `ProomType.meta` | 15/21 | 年份/时长/副标题/进度时间 |
| `ProomType.label` | 13/18 | 角标、序号、副标题最弱一级 |
| `ProomType.eyebrow` | 13/18 + 3sp 字距 | 全大写小标（「继续观看」上方那种） |

规则：

1. 电视端**禁止** `fontSize = N.sp`。唯一的例外是「随容器高度算出来的展示型字号」（Hero 标题、频道台标水印），且必须 `coerceIn` 到档位区间。
2. 电视端**禁止** `ProomSp()`。它的输入值（11/13/14/15/16/18/34）本身不成体系，输出又和 `TvType` 档位错位，同一屏会出现「18sp 来自 body」和「18sp 来自 ProomSp(15)」两种来源。`ProomSp` 只留给手机/共用组件。
3. 标题不允许跳级：区块标题就是 `title`(24)，不许某页用 30、某页用 13。
4. 字重只有三档：Normal(正文/次要)、Medium(卡片标题)、SemiBold(标题/大标题)。不要用 Bold 造层级。

---

## 4. 图标：4 档

```kotlin
TvIcon.inline  = 16.dp   // 跟在文字里的行内图标
TvIcon.control = 20.dp   // 控件内部的图标（搜索框放大镜、播放键图形、清除）
TvIcon.toolbar = 24.dp   // 工具条/导航图标（顶部栏、播放器控制层）
TvIcon.display = 32.dp   // 展示型大图标（空态插画、头像）
```

规则：

1. 图标尺寸写档位，不写像素。当前项目有 14/16/18/19/20/21/22/23/26/30/34 十一档，21 与 23 的差异**在电视上不存在视觉意义**。
2. 图标尺寸必须与容器尺寸成对：48dp 圆钮 → `control`(20)；56dp → `toolbar`(24)；72dp 主键 → 28~32（取 `display`）。
3. 线性图标的线宽固定 **1.8dp**（画布坐标），不要 1.2/1.5/1.6/1.7/1.9 混用；`StrokeCap.Round` 统一。
4. 图标不允许拉伸：`Icon` 只给 `size`，不给 `fillMaxWidth`。

---

## 5. 控件尺寸：3 档

```kotlin
TvSize.control      = 48.dp   // 常规控件、工具按钮、列表图标钮、搜索框高
TvSize.videoControl = 52.dp   // 视频控制层次键（浮在画面上，要压得住背景）
TvSize.primary      = 64.dp   // 视频控制层主键，**同屏只有一个**
```

| 组件 | 规范值 | 备注 |
|---|---|---|
| 顶部栏 | 64dp 高 | 图标钮 48dp，图标 24dp |
| 搜索框 | 56dp 高 | 输入区左右 `s12`，图标 20dp |
| 图标按钮 | 48×48 | 图标 20dp，圆环内收 |
| 视频控制层次键 | 52×52 | 图标 26dp；底栏一排 6 颗，56 会撑，40 又太小 |
| 视频控制层主键 | 64×64 | 图标 30dp，金/青实心 |
| 投屏胶囊内主键 | 56×56 | 紧凑容器里退化一档，见 5.1 |
| 列表行 | 56dp 高 | 上下 `s12` 内边距 |
| 集号格子 | 48dp 高 | 低于 48 视为 bug |

### 5.1 主键与次键

- 主键直径 ≥ 次键 + 8dp。等大或更小 = 没有主操作。
- 同一个容器里只能有一个主键，且主键唯一使用实心强调色（金 = 播放/内容，青 = 投屏设备）。
- 紧凑容器（投屏胶囊、Now Playing 条）里主键可退一档到 56，但**仍然必须比同容器次键大 8dp**。

---

## 6. 圆角：5 档

```kotlin
ProomShape.xs = 4.dp    // 进度条、2~4dp 高的细条
ProomShape.sm = 10.dp   // 角标、chip、小缩略图
ProomShape.md = 14.dp   // 内容卡（海报、16:9 缩略图、面板行）
ProomShape.lg = 22.dp   // 大面板、浮层
RoundedCornerShape(50)  // 胶囊按钮
```

规则：禁止 `RoundedCornerShape(1/2/3/6.dp)`；也禁止把间距 token 当半径用（`RoundedCornerShape(ProomSpace.md)` 这种写法出现过 4 次，语义错位）。

### 6.1 同一个「选项块」只有一种样子

chip（分类筛选、热门词、搜索历史、音乐分类）曾经在四个页面是四种形状：
浏览页 50% 胶囊、直播页 50% 胶囊、搜索页 14dp、我的页 10dp。**现在统一为**：

| 项 | 值 |
|---|---|
| 圆角 | `ProomShape.sm`（10dp） |
| 高度 | `TvLayout.minTouch`（48dp），不许用 44 |
| 水平内边距 | `TvSpace.s16` |
| 行内 gap | `TvSpace.s8` |
| 选中 | `accentSoft` 底 + 1dp `accent` 描边 |
| 聚焦 | 白色 2dp 外扩环，**不缩放**（行内 gap 只有 8dp，放大 6% 会顶到邻居） |

胶囊（`RoundedCornerShape(50)`）只留给**导航/操作按钮**：页面返回、设置、立即播放、空态里的「去设置」。
判断方法：点下去会**换页或执行动作**的用胶囊；点下去只是**改变列表内容**的用 chip。

### 6.2 输入框也只有一种样子

三个输入框曾经是三种形状：搜索页 50% 胶囊、顶栏搜索框 14dp、地址框 10dp。

| 项 | 值 |
|---|---|
| 圆角 | `ProomShape.sm`（10dp） |
| 最小高度 | `TvSize.control`（48dp） |
| 水平内边距 | `TvSpace.s16` |
| 底色 | `surface`（聚焦时抬到 `surface2`） |
| 描边 | 1dp `line`，不随焦点变色 |
| 聚焦 | 白 2dp 环（`tvFocusRing`），形状与框一致 |

---

## 7. 图片与卡片

### 7.1 只有三种比例

| 用途 | 比例 | token |
|---|---|---|
| 影视海报、收藏卡 | 2 : 3.05 | `TvRatio.poster` |
| 剧集缩略图、直播台标、频道格、继续观看 | 16 : 9 | `TvRatio.wide` |
| 音乐封面 | 1 : 1 | `TvRatio.square` |

骨架屏**必须**与真实内容同比例（现在骨架用 2:3、内容用 2:3.05，加载完成会跳 2dp 高度，整排卡片抖一下）。

### 7.2 卡片宽度由列数反算，不写死

```kotlin
fun shelfItemWidth(avail: Dp, columns: Int, gap: Dp = TvSpace.s16): Dp =
    (avail - gap * (columns - 1)) / columns
```

| 页面 | 列数 | 说明 |
|---|---|---|
| 影视书架/搜索/收藏（海报） | 6 | 960-96-5×16 = 784 → 每张 130.7dp |
| 音乐（方图） | 6 | 同上 |
| 直播（16:9） | 5 | 960-96-4×16 = 800 → 每个 160dp |
| 继续观看（16:9 横卡） | 6 | `maxWidth × 0.16`（约 160dp），一屏 5~6 张可横滑 |
| 我的-继续观看 / 我的-收藏 | 6 | 都用同一张 16:9 横卡；收藏**不用海报**——海报 198dp 高，和记录行同屏会把片名压到底部状态条下面 |

禁止在页面里手写 126/130/132/136/148/150/176/180 —— 现在同一个「卡片宽度」概念有 8 个数字。

### 7.3 图片上的文字

- 压暗遮罩：底部 `transparent → black 0.72`，从 55% 开始。
- 图上角标：`ProomType.label` + `ProomShape.sm` + 内边距 `s8 × s4`，距卡片边 `s8`。

---

## 8. 页面骨架

| 位置 | 值 |
|---|---|
| 顶部栏 64dp + 内容区从 64dp 开始 |
| 页面标题（有返回条时）`headline` 30sp，距顶部 `s20` |
| 第一个区块距标题 `s24` |
| 区块标题 → 内容 `s16` |
| 区块之间 `s24` |
| 列表末尾额外留白 `s48`（避免最后一行贴底被切） |

### 8.2 「我的」是三段式，一屏不许空着

```
[返回] 我的                         [从手机导入配置] [设置]
继续观看  6 条                                    ← 16:9 宽卡，一屏 5~6 张
我的收藏  1 部                                    ← 同一张 16:9 宽卡；为空时退化成一条
                                                    88dp 提示条 +「去片库挑几部」
────────────────────────────────────────────────
● NAS 192.168.0.11:8899 │ 配置 v1c97f3b·11个源·113个频道 │ 缓存 0 B │ 版本 0.15.0
```

- 前两段都可能没有内容，第三段（设备与内容条）**永远在**，并且是**常驻贴底**（`align(BottomCenter)`，
  56dp 高、不可聚焦、列表 `contentPadding.bottom` 让出它的高度）—— 这一页之所以能"一屏填满"，
  靠的就是它兜底；曾经只有一条竖排播放记录，下面 60% 全是黑。
- 两块货架 + 状态条 = 488dp < 540dp，**不用滚动**。加第三排内容前先算高度。
- 状态条不可聚焦：这一页的焦点只能落在能播的卡片上，走到底再穿过一条信息带才能回顶栏是纯负担。

### 8.3 空态只有一种样子

「没有内容」的页面（我的-无收藏、音乐-未配置、直播-无源、搜索-无结果）统一：
`s48` 上留白 → 居中 → `title` 24sp 一行标题 → `s12` → `meta` 15sp 一行说明（`dim`，居中换行）→
可选一个胶囊按钮。**不许**用一句话 18sp 正文顶在最上面（曾经「我的」和「音乐」是两种写法）。

---

## 9. 颜色语义（摘要）

- 金 `accent`：主操作、已选中、进度、评分。**不是**焦点色。
- 红 `danger`：只给破坏性操作（清空、恢复出厂、删除）。行尾动作文字用它，
  页面上所有动作都是金色时，「恢复」看起来和「同步」是同一档风险。
- 青 `cast`：投屏设备通道。手机遥控页与电视接收页共用。
- 白 `text/dim/muted`：三级文字。
- `surface / surface2 / line`：三层明度分隔，不靠描边。
- 焦点：白环 + 柔光，见 `tv-focus-spec.md`。焦点**不得**改变元素色相。
- **占位图一律中性色**。没有封面/台标时用 `surface2 → card` 的明度渐变；
  不许按标题/频道名 hash 出色相（直播页曾经 20 个格子 20 种颜色，像一半封面加载失败）。
  占位上的水印字号可以随格子高度算，但要 `coerceIn` 到 `title..display` 档位区间。

---

## 10. 两套 token 的边界

| | 电视 | 手机 |
|---|---|---|
| 字阶 | `ProomType`（= `TvType`） | `ProomType`（= phone 档） |
| 间距 | `TvSpace` | `ProomSpace` |
| 字号修正 | 不需要，禁止 `ProomSp` | 需要时用 `ProomSp` |
| 图标 | `TvIcon` | 无强制档位 |

`ui-media` / `ui-kit` 里手机与电视共用的组件，用 `LocalProomTv` 分支，各自取各自的档位，不在调用点写魔法数。

### 10.1 已知陷阱（踩过，别再踩）

| 陷阱 | 现象 | 正确写法 |
|---|---|---|
| `verticalScroll` 里给文字列加 `weight(1f)` 或套 `BoxWithConstraints { Column(width(maxWidth)) }` | 40sp 片名整行消失，页面上直接从元信息那行开始 | 宽度已知就写死宽度（`maxWidth - 海报宽 - gap - 2*inset`），别让文字列参与权重测量 |
| 同上容器里给 `Text` 传 `style = ProomType.display` | 片名盒子量出来 58dp 却一个字都看不见（`lineHeightStyle` 把文字裁在盒子外） | 档位照取，但用参数传：`fontSize = ProomType.display.fontSize`、`lineHeight = ...` |
| 页面首帧 `requestFocus` | 焦点系统的 bringIntoView 把页面滚掉 50dp 左右，第一屏标题正好滑到顶栏底下 | 焦点请求成功后 `scrollState.scrollTo(0)` |
| `BoxWithConstraints` 的 `maxWidth` 进了嵌套 `Row`/`Column` | 编译期报「cannot be called with an implicit receiver」 | 在 `BoxWithConstraints` 直接内容里先算成变量再往下传 |
| `TextStyle(style = ...)` | 没有这个参数 | `textStyle = ProomType.body.copy(color = ...)` |
| 给 N 行文字留高度时写 `lineHeight * N` | 明明留了两行，永远只出一行、末尾带省略号（片库 6 列的「千古镜鉴2·背阴…」） | `textBlockHeight(style, lines)`：includeFontPadding 还要吃 2dp，差这点高度 Compose 就提前一行收尾 |
| 定高 `Box` 里再给 `Text` 加 `padding(top)` | 副标题只剩「行高 − padding」，13sp 字被压掉小半个（直播卡副标题） | 间距算进外层高度：`height(textBlockHeight(label) + TvSpace.s4)` |
| 把顶部渐隐遮罩 `drawWithContent` 写在 `verticalScroll` **之后** | 遮罩跟着内容一起滚走，等于没画；海报依然被硬切一条「半张海报」贴在返回按钮下面 | 遮罩画在 `verticalScroll` 外层（`fillMaxSize().drawWithContent{}.verticalScroll()`），坐标系才固定在视口上 |
| 渐隐遮罩常亮 | 首屏片名正好压在这条带子上，比下面的字灰一档，像没加载完 | `alpha` 跟随滚动量：`(scrollState.value / 160f).coerceIn(0f, 1f)`，静止时 0 |
| 给 `ProomColor.surface` 再 `.copy(alpha = 0.94f)` | 底色从「白 5.5%」变成「白 94%」，整块白底把白字吞掉（「我的」底部状态条只剩四个标签） | `surface`/`surface2`/`line` 本身就是**带 alpha 的白**，要改透明度只能换层（`surface` → `surface2`），或者 `background(bg).background(surface)` 叠 |
| 换线路时顺手 `epIndex = 0` | 「详情页点第 2 集 → 播出来是第 1 集」：采集站第 2 集的直链过期，播放器自动换线路，顺带把集数清零；标题栏又老实显示「第 1 集」，用户只会认为选集按钮坏了 | 换**源**不换**集**：`epIndex = epIndex.coerceIn(0, lineEpCount(next) - 1)`，只有新线路集数不够时才回收。**三处**都要改：`onPlayerError` 自动换线、起播超时换线、抽屉里手动选线路 |

**Hero 的 0.65**：`height = maxHeight * 0.65f`，且**不算** `topInset`。
0.68 时「Hero + 区块标题 + 一整排卡片标题」正好塞进 540dp，但卡片标题落在
y=508~532dp —— 离屏幕底只剩 8dp，电视一过扫描就切字。0.65 让出 16dp，
标题底落到 516dp、留 24dp 安全区。
代价是 Hero 内高少了 16dp：文字列底部留白 s24→s32、圆点下边距 s24→s16，
两边各让 8dp，圆点才不会被按钮下沿压住。改这三者之前先量 `uiautomator dump`。

---

## 11. 可执行的检查（提交前跑一遍）

```bash
cd native
# 1) 电视端裸字号（应只剩 Hero/台标这类 coerceIn 的值）
grep -rn "fontSize = [0-9]" --include='*.kt' ui-tv data | grep -v coerce
# 2) 电视端 ProomSp（应为空）
grep -rn "ProomSp(" --include='*.kt' ui-tv data | grep -v import
# 3) 非档位间距：只看 padding / spacedBy / offset 的参数
#    （高度、绘制幅度不算 —— 进度条 4dp、波形条 9/11dp 是元素自身尺寸）
grep -rnE "(padding|spacedBy|offset)[A-Za-z]*\([^)]*\b(2|5|6|7|9|11|13|14|18|22|26|30)\.dp" \
  --include='*.kt' ui-tv
# 4) 非档位圆角
grep -rn "RoundedCornerShape([0-9.]*dp)" --include='*.kt' ui-tv data | grep -vE "(4|10|14|22)\.dp"
# 5) 图标尺寸越档
grep -rn "\.size([0-9]*\.dp)" --include='*.kt' ui-tv | grep -vE "(16|20|24|32|48|56|72)\.dp"
# 6) 低可点目标
grep -rn "\.height(4[0-7]\.dp)" --include='*.kt' ui-tv
```

上面 6 条全部为 0，才算「规范落地」。
