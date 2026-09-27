package com.lmreader.core.model

/**
 * 阅读模式（开发文档 12「阅读方向」与「连续模式」，选项集对齐 Mihon `ReadingMode.kt`）。
 *
 * 与 Mihon 的一个有意差异：Mihon 用 `DEFAULT`（0x00）作为"跟随全局默认"的哨兵值，
 * 因此它的枚举里有一个既不是方向也不是类型的成员。本项目改为**哨兵为 null**：
 * 全局默认必须是具体模式（见 [ReaderSettings.readingMode]），而漫画级覆盖用可空列
 * 表达"未覆盖"。这样"未解析的哨兵值"不会渗进阅读器，也省掉一类
 * `IllegalStateException("Preference value must be resolved")`。
 *
 * [direction] 与 [isContinuous] 是阅读器选择布局的唯一依据，UI 不得自己再判一遍。
 */
enum class ReadingMode(
    val label: String,
    val direction: ReadingDirection,
    val continuous: Boolean,
) {
    /** Mihon: `Paged (left to right)`。 */
    LEFT_TO_RIGHT("从左到右翻页", ReadingDirection.HORIZONTAL, continuous = false),

    /** Mihon: `Paged (right to left)`。日式漫画默认，也是本项目默认。 */
    RIGHT_TO_LEFT("从右到左翻页", ReadingDirection.HORIZONTAL, continuous = false),

    /** Mihon: `Paged (vertical)`。整页吸附的竖向翻页，不是连续滚动。 */
    VERTICAL("竖向翻页", ReadingDirection.VERTICAL, continuous = false),

    /** Mihon: `Long strip`。条漫连续滚动，页与页之间无间隔。 */
    WEBTOON("条漫", ReadingDirection.VERTICAL, continuous = true),

    /** Mihon: `Long strip with gaps`。同上，但页与页之间留间隔。 */
    CONTINUOUS_VERTICAL("条漫（带间隔）", ReadingDirection.VERTICAL, continuous = true),
    ;

    /** 上一页在屏幕上的方向提示，供设置界面展示；翻页动作本身仍由阅读器决定。 */
    val isRightToLeft: Boolean get() = this == RIGHT_TO_LEFT

    companion object {
        /** 默认阅读模式：日式漫画从右向左（开发文档 12 节表格，与 Mihon 默认一致）。 */
        val DEFAULT: ReadingMode = RIGHT_TO_LEFT

        /**
         * 从持久化名称解析；无法识别时回退 [DEFAULT] 而不是抛异常。
         *
         * 容错读取的理由：偏好值可能来自旧版本或被外部改写，此时"用默认模式打开"
         * 远好于"打开阅读器就崩溃"。
         */
        fun fromStorage(stored: String?): ReadingMode =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

/** 翻页轴。点按区域与翻页动作按它分派。 */
enum class ReadingDirection { HORIZONTAL, VERTICAL }

/**
 * 点按区域布局（开发文档 12「点按」，选项集对齐 Mihon `ReaderPreferences.TapZones`）。
 *
 * 枚举顺序**就是 Mihon 的索引顺序**（`DEFAULT=0 … DISABLED=5`），因为这个值将来要
 * 与 Mihon 的 `reader_navigation_mode_pager` 之类配置互通时不至于错位。
 *
 * [DEFAULT] 不是"某个具体布局"，而是"跟随阅读模式"：竖向模式给 L 形，其余给左右两栏
 * （Mihon `PagerConfig.defaultNavigation()`）。解析由 [com.lmreader.core.model.NavigationRegions] 负责。
 */
enum class TapZones(val label: String, val millisLabel: String) {
    DEFAULT("默认", "跟随阅读模式"),
    L_SHAPED("L 形", "L shaped"),
    KINDLISH("Kindle 式", "Kindle-ish"),
    EDGE("边缘", "Edge"),
    RIGHT_AND_LEFT("左右两栏", "Right and Left"),
    DISABLED("禁用", "Disabled"),
    ;

    companion object {
        fun fromStorage(stored: String?): TapZones =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

/**
 * 点按区域反转（Mihon `TappingInvertMode`，开发文档 12「点按」）。
 *
 * 与 Mihon 的**有意差异**：Mihon 用两个独立布尔位 `shouldInvertHorizontal` /
 * `shouldInvertVertical` 表达四种组合，于是 `NONE` 与"两个位都为 false"在语义上重叠。
 * 本项目直接建模成三个正交开关，镜像变换由 [NavigationRegions] 读这三个位执行。
 */
enum class TapInvert(val label: String) {
    NONE("不反转"),
    HORIZONTAL("水平反转"),
    VERTICAL("垂直反转"),
    BOTH("水平与垂直都反转"),
    ;

    /** 归一化矩形是否需要按 x 轴中线镜像。 */
    val invertsHorizontal: Boolean get() = this == HORIZONTAL || this == BOTH

    /** 归一化矩形是否需要按 y 轴中线镜像。 */
    val invertsVertical: Boolean get() = this == VERTICAL || this == BOTH

    companion object {
        fun fromStorage(stored: String?): TapInvert =
            entries.firstOrNull { it.name == stored } ?: NONE
    }
}

/**
 * 放大后的起始可见位置（Mihon `ZoomStart`）。
 *
 * 它**不改变缩放比例**，只决定放大后窗口落在图像的哪一侧——宽图在初始缩放下本来就
 * 溢出屏幕，左右两种默认值让读者先看到该先看的那一半。
 */
enum class ZoomStart(val label: String) {
    AUTOMATIC("自动"),
    LEFT("左侧"),
    RIGHT("右侧"),
    CENTER("居中"),
    ;

    /**
     * 解析"自动"（Mihon `PagerConfig.zoomTypeFromPreference`）：
     * 从左到右看左侧、从右到左看右侧、竖向看图心。
     */
    fun resolve(mode: ReadingMode): ZoomStart = when (this) {
        AUTOMATIC -> when (mode) {
            ReadingMode.LEFT_TO_RIGHT -> LEFT
            ReadingMode.RIGHT_TO_LEFT -> RIGHT
            else -> CENTER
        }

        else -> this
    }

    companion object {
        fun fromStorage(stored: String?): ZoomStart =
            entries.firstOrNull { it.name == stored } ?: AUTOMATIC
    }
}

/**
 * 图像适配方式（Mihon `ImageScaleType`，开发文档 12「缩放」）。
 *
 * 注意 Mihon 把这一项存成 **1 基**整数（`index + 1`），而枚举本身是 0 基——这是它设置
 * 代码里一个出名的错位点。本项目按名称持久化，不存在这个坑。
 */
enum class ImageScaleType(val label: String) {
    FIT_SCREEN("适合页面"),
    STRETCH("拉伸填满"),
    FIT_WIDTH("适合宽度"),
    FIT_HEIGHT("适合高度"),
    ORIGINAL_SIZE("原始尺寸"),
    SMART_FIT("智能适配"),
    ;

    companion object {
        fun fromStorage(stored: String?): ImageScaleType =
            entries.firstOrNull { it.name == stored } ?: FIT_SCREEN
    }
}

/**
 * 屏幕方向（Mihon `ReaderOrientation`）。
 *
 * 同样是可空哨兵而不是 `DEFAULT` 成员：`null` 表示跟随系统。
 */
enum class ReaderOrientation(val label: String) {
    FREE("跟随系统"),
    PORTRAIT("竖屏（可倒转）"),
    LANDSCAPE("横屏（可倒转）"),
    LOCKED_PORTRAIT("锁定竖屏"),
    LOCKED_LANDSCAPE("锁定横屏"),
    REVERSE_PORTRAIT("倒置竖屏"),
    ;

    companion object {
        fun fromStorage(stored: String?): ReaderOrientation? =
            entries.firstOrNull { it.name == stored }
    }
}

/**
 * 阅读背景色（Mihon `pref_reader_theme_key`）。
 *
 * 数值与 Mihon 保持一致（WHITE=0、BLACK=1、GRAY=2、AUTO=3），便于将来配置互通；
 * 但按名称持久化。
 */
enum class ReaderTheme(val label: String) {
    WHITE("白色"),
    BLACK("黑色"),
    GRAY("灰色"),
    AUTO("跟随系统"),
    ;

    companion object {
        fun fromStorage(stored: String?): ReaderTheme =
            entries.firstOrNull { it.name == stored } ?: BLACK
    }
}

/**
 * 滚动时隐藏菜单的灵敏度（Mihon `ReaderHideThreshold`）。
 *
 * [thresholdPx] 是**像素阈值**，而标签说的是"灵敏度"——两者方向相反：
 * "最高"灵敏度是最小的阈值（5px，一动就隐藏）。这个反向命名照搬 Mihon，
 * 因为 UI 文案要对得上；实现读 [thresholdPx] 即可。
 */
enum class ReaderHideThreshold(val label: String, val thresholdPx: Int) {
    HIGHEST("最高", 5),
    HIGH("高", 13),
    LOW("低", 31),
    LOWEST("最低", 47),
    ;

    companion object {
        /** 默认 LOW = 31px，与 Mihon 一致。 */
        val DEFAULT: ReaderHideThreshold = LOW

        fun fromStorage(stored: String?): ReaderHideThreshold =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

/**
 * 阅读器设置的完整快照。
 *
 * 为什么用一个数据类而不是散落的偏好键：阅读器有约六十项设置，其中绝大多数会同时被
 * 布局、手势与绘制读取。把它们聚成一个不可变快照后，阅读器拿到的是**一次一致的读取**，
 * 而不是"读到一半设置变了导致布局与手势不一致"。Mihon 用可变的 `ViewerConfig` 加监听器
 * 达到同样目的，本项目用 Compose 的状态提升更自然。
 *
 * 字段默认值全部对齐 Mihon 的默认值；每组都注明了 Mihon 的偏好键，便于对照。
 */
data class ReaderSettings(
    /** Mihon `pref_default_reading_mode_key`，默认 `RIGHT_TO_LEFT`。 */
    val readingMode: ReadingMode = ReadingMode.DEFAULT,
    /** Mihon `pref_default_orientation_type_key`，默认 `FREE`（null 即跟随系统）。 */
    val orientation: ReaderOrientation? = null,
    /** Mihon `reader_navigation_mode_pager` / `reader_navigation_mode_webtoon`，默认 `DEFAULT`。 */
    val pagerTapZones: TapZones = TapZones.DEFAULT,
    val webtoonTapZones: TapZones = TapZones.DEFAULT,
    /** Mihon `reader_tapping_inverted` / `reader_tapping_inverted_webtoon`，默认 `NONE`。 */
    val pagerTapInvert: TapInvert = TapInvert.NONE,
    val webtoonTapInvert: TapInvert = TapInvert.NONE,
    /** Mihon `pref_image_scale_type_key`，默认 `FIT_SCREEN`。 */
    val imageScaleType: ImageScaleType = ImageScaleType.FIT_SCREEN,
    /** Mihon `pref_zoom_start_key`，默认 `AUTOMATIC`。 */
    val zoomStart: ZoomStart = ZoomStart.AUTOMATIC,
    /** Mihon `pref_enable_transitions_key`，默认 true。 */
    val pageTransitions: Boolean = true,
    /** Mihon `pref_double_tap_anim_speed`，默认 500ms。 */
    val doubleTapAnimMillis: Int = 500,
    /** Mihon `reader_volume_keys` / `reader_volume_keys_inverted`，默认 false。 */
    val volumeKeys: Boolean = false,
    val volumeKeysInverted: Boolean = false,
    /** Mihon `reader_long_tap`，默认 true。 */
    val longTapActions: Boolean = true,
    /** Mihon `crop_borders` / `crop_borders_webtoon`，默认 false。 */
    val cropBorders: Boolean = false,
    val cropBordersWebtoon: Boolean = false,
    /** Mihon `navigate_pan`，默认 true。分页专属。 */
    val panWideImages: Boolean = true,
    /** Mihon `landscape_zoom`，默认 true。分页专属，且仅对"适合页面"生效。 */
    val landscapeZoom: Boolean = true,
    /** Mihon `webtoon_side_padding`，默认 0，范围 0..25。 */
    val webtoonSidePadding: Int = 0,
    /** Mihon `pref_enable_double_tap_zoom_webtoon`，默认 true。 */
    val webtoonDoubleTapZoom: Boolean = true,
    /** Mihon `webtoon_disable_zoom_out`，默认 false。 */
    val webtoonDisableZoomOut: Boolean = false,
    /** Mihon `reader_hide_threshold`，默认 `LOW`。 */
    val hideThreshold: ReaderHideThreshold = ReaderHideThreshold.DEFAULT,
    /** Mihon `pref_reader_theme_key`，默认 `BLACK`。 */
    val theme: ReaderTheme = ReaderTheme.BLACK,
    /** Mihon `pref_show_page_number_key`，默认 true。 */
    val showPageNumber: Boolean = true,
    /** Mihon `fullscreen`，默认 true。 */
    val fullscreen: Boolean = true,
    /** Mihon `pref_keep_screen_on_key`，默认 false。 */
    val keepScreenOn: Boolean = false,
    /**
     * 章与章之间是否插入过渡页。
     *
     * 默认 true：过渡页就是夹在中间的一张"图"，读者往前或往后都会经过它，
     * 因此从上一章末页到下一章首页需要翻两次。关掉它两章直接相接，一次翻页就过去。
     *
     * 它与翻页逻辑**无关**——只是"组装列表时插不插这一项"。这也是为什么它可以随手开关：
     * 列表里少一项而已，不涉及任何"跨章事件"。
     */
    val showChapterTransitions: Boolean = true,
    /**
     * 预载页数预算。
     *
     * 语义照用户的要求：从当前页往后数，凑够这么多页就停；**章节过渡页也算一页**。
     * 因此预算 9 而下一章只有 2 页时会继续要再下一章，直到预算用完或没有更多章节。
     * 0 表示不预载（窗口里只剩当前章）。
     */
    val preloadPages: Int = PRELOAD_PAGES_DEFAULT,
    /** Mihon `reader_navigation_overlay_on_start`，默认 false。点按区域遮罩层。 */
    val showTapZoneOverlayOnStart: Boolean = false,
    /** 首次进入是否自动显示一次点按区域遮罩（Mihon `reader_navigation_overlay_new_user` 的一次性闩锁）。 */
    val showTapZoneOverlayOnce: Boolean = true,
    /** Mihon `pref_show_reading_mode`，默认 true。 */
    val showReadingMode: Boolean = true,
    /** Mihon `pref_grayscale` / `pref_inverted_colors`，默认 false。 */
    val grayscale: Boolean = false,
    val invertedColors: Boolean = false,
    /** Mihon `pref_custom_brightness_key`，默认 false；关闭时窗口亮度不被干预。 */
    val customBrightness: Boolean = false,
    /**
     * Mihon `custom_brightness_value`，默认 0，范围 -75..100。
     *
     * 三段语义见 `ReaderDisplayEffects`：0 不干预、正值设窗口亮度、负值叠暗化层。
     */
    val customBrightnessValue: Int = 0,
) {
    /**
     * 当前模式下实际生效的点按区域布局。
     *
     * 分派依据是**连续模式**而不是方向：Mihon 给 Pager 用 `reader_navigation_mode_pager`、
     * 给 Webtoon 用 `reader_navigation_mode_webtoon`，而"竖向分页"属于 Pager。
     * 所以「竖向分页」用分页那一套，「条漫」用条漫那一套。
     */
    val tapZones: TapZones
        get() = if (readingMode.continuous) webtoonTapZones else pagerTapZones

    /** 当前模式下实际生效的反转方式；分派依据同 [tapZones]。 */
    val tapInvert: TapInvert
        get() = if (readingMode.continuous) webtoonTapInvert else pagerTapInvert

    /** 当前模式下实际生效的裁白边开关（Mihon 对条漫用独立的偏好键）。 */
    val effectiveCropBorders: Boolean
        get() = if (readingMode.continuous) cropBordersWebtoon else cropBorders

    companion object {
        /** [preloadPages] 的默认预算：一章的量级，够盖住一次翻页的等待。 */
        const val PRELOAD_PAGES_DEFAULT = 9

        /** [preloadPages] 的上下限；0 表示不预载。 */
        const val PRELOAD_PAGES_MIN = 0
        const val PRELOAD_PAGES_MAX = 60
    }
}

/**
 * 归一化点按区域矩形（0..1，原点在左上）。
 *
 * 用归一化坐标的理由：区域定义必须与屏幕尺寸、分辨率、旋转无关，否则每台设备都要
 * 重新推导一遍边界。命中检测时把触点也归一化再比较。
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /**
     * `RectF.contains` 的语义：左闭右开、上闭下开。
     *
     * 之所以不写成 `left <= x && x <= right`：相邻区域共享边界时（例如 0.33 处），
     * 闭区间会让一个点同时落在两个区域里，命中结果取决于区域顺序——这是隐蔽的非确定性。
     */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    /**
     * 按中线镜像。水平反转交换左右、垂直反转交换上下，两者都开即 180° 点对称
     * （Mihon `RectF.invert`）。
     */
    fun invert(invert: TapInvert): NormalizedRect = when {
        invert.invertsHorizontal && invert.invertsVertical ->
            NormalizedRect(1f - right, 1f - bottom, 1f - left, 1f - top)

        invert.invertsVertical ->
            NormalizedRect(left, 1f - bottom, right, 1f - top)

        invert.invertsHorizontal ->
            NormalizedRect(1f - right, top, 1f - left, bottom)

        else -> this
    }
}

/** 点按落在哪个动作上。 */
enum class TapAction {
    /** 显示/隐藏控制栏。 */
    MENU,

    /** 上一页（阅读顺序上的上一页，不一定在屏幕左侧）。 */
    PREVIOUS,

    /** 下一页。 */
    NEXT,

    /** 向左平移：缩放后宽图优先平移，平移不动了才翻页。 */
    PAN_LEFT,

    /** 向右平移，语义同 [PAN_LEFT]。 */
    PAN_RIGHT,
}

/**
 * 点按区域布局表。
 *
 * 矩形数值全部照搬 Mihon 的 `viewer/navigation` 包下各布局类，包括它的一个易错点：
 * **命中顺序即列表顺序**，所以交叠区域由顺序决定，不能重排。
 *
 * 另一个必须照搬的行为：未命中任何区域一律返回 [TapAction.MENU]。Mihon 里
 * `ViewerNavigation.getAction` 的 fallback 是 MENU，而 `DisabledNavigation` 是空列表，
 * 所以"禁用点按"实际等于"整屏都是菜单区"——不是"没有反应"。
 */
object NavigationRegions {

    /** L 形：上方整条与中部左侧是上一页，下方整条与中部右侧是下一页。 */
    val L_SHAPED: List<Pair<NormalizedRect, TapAction>> = listOf(
        NormalizedRect(0f, 0.33f, 0.33f, 0.66f) to TapAction.PREVIOUS,
        NormalizedRect(0f, 0f, 1f, 0.33f) to TapAction.PREVIOUS,
        NormalizedRect(0.66f, 0.33f, 1f, 0.66f) to TapAction.NEXT,
        NormalizedRect(0f, 0.66f, 1f, 1f) to TapAction.NEXT,
    )

    /** Kindle 式：上三分之一整条是菜单，下三分之二左右分栏。 */
    val KINDLISH: List<Pair<NormalizedRect, TapAction>> = listOf(
        NormalizedRect(0.33f, 0.33f, 1f, 1f) to TapAction.NEXT,
        NormalizedRect(0f, 0.33f, 0.33f, 1f) to TapAction.PREVIOUS,
    )

    /** 边缘：左右两条是下一页，底部中间一小块是上一页。 */
    val EDGE: List<Pair<NormalizedRect, TapAction>> = listOf(
        NormalizedRect(0f, 0f, 0.33f, 1f) to TapAction.NEXT,
        NormalizedRect(0.33f, 0.66f, 0.66f, 1f) to TapAction.PREVIOUS,
        NormalizedRect(0.66f, 0f, 1f, 1f) to TapAction.NEXT,
    )

    /** 左右两栏：中间三分之一是菜单区。 */
    val RIGHT_AND_LEFT: List<Pair<NormalizedRect, TapAction>> = listOf(
        NormalizedRect(0f, 0f, 0.33f, 1f) to TapAction.PAN_LEFT,
        NormalizedRect(0.66f, 0f, 1f, 1f) to TapAction.PAN_RIGHT,
    )

    /** 禁用：空表，于是每次点击都落到菜单分支（与 Mihon 一致）。 */
    val DISABLED: List<Pair<NormalizedRect, TapAction>> = emptyList()

    /**
     * 解析最终生效的区域表。
     *
     * [TapZones.DEFAULT] 的分支与 Mihon 一致：竖向分页给 L 形，其余给左右两栏。
     * 条漫复用同一套矩形（Mihon `WebtoonConfig` 也这么做），区别只在动作映射。
     */
    fun resolve(zones: TapZones, mode: ReadingMode): List<Pair<NormalizedRect, TapAction>> =
        when (zones) {
            TapZones.DEFAULT -> if (mode.direction == ReadingDirection.VERTICAL) L_SHAPED else RIGHT_AND_LEFT
            TapZones.L_SHAPED -> L_SHAPED
            TapZones.KINDLISH -> KINDLISH
            TapZones.EDGE -> EDGE
            TapZones.RIGHT_AND_LEFT -> RIGHT_AND_LEFT
            TapZones.DISABLED -> DISABLED
        }

    /**
     * 命中检测。
     *
     * @param x 归一化横坐标 0..1
     * @param y 归一化纵坐标 0..1
     * @return 命中的动作；未命中或区域表为空时返回 [TapAction.MENU]（与 Mihon 一致）。
     */
    fun hitTest(
        zones: TapZones,
        invert: TapInvert,
        mode: ReadingMode,
        x: Float,
        y: Float,
    ): TapAction {
        for ((rect, action) in resolve(zones, mode)) {
            if (rect.invert(invert).contains(x, y)) return action
        }
        return TapAction.MENU
    }
}
