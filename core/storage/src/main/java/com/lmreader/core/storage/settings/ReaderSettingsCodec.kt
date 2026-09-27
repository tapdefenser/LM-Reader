package com.lmreader.core.storage.settings

import com.lmreader.core.model.ImageScaleType
import com.lmreader.core.model.ReaderHideThreshold
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReaderTheme
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.TapInvert
import com.lmreader.core.model.TapZones
import com.lmreader.core.model.ZoomStart

/**
 * [ReaderSettings] 的行文本编解码。
 *
 * 独立成对象而不是留在 [ReaderPreferences] 里的理由：本类是纯函数（除协程与 SDK 之外
 * 没有任何 Android 依赖），因此可以在普通 JVM 单元测试里穷举地面往返一致性；
 * 而 [ReaderPreferences] 本身要 `Context` 与 DataStore，测起来代价高得多。
 *
 * 格式：每行 `键=值`，只写与默认值不同的字段；因此默认设置编码为空字符串。
 * 所有字段名刻意取短且稳定——它们是持久化契约，改名等于丢用户设置。
 */
internal object ReaderSettingsCodec {

    fun encode(settings: ReaderSettings): String {
        val defaults = ReaderSettings()
        val lines = ArrayList<String>(32)

        fun put(key: String, value: String?, default: String?) {
            if (value != null && value != default) lines += "$key=$value"
        }

        fun putFlag(key: String, value: Boolean, default: Boolean) {
            // 与默认值不同时必须写出**真实布尔值**。若只写"与默认相反"的那一侧
            // （例如只在 true 时写 true），那么默认值从 true 改成 false 后，旧数据里
            // "用户显式关闭"这一事实就无法与"从未设置"区分，用户的关闭会被静默撤销。
            put(key, value.toString(), default.toString())
        }

        put(KEY_MODE, settings.readingMode.name, defaults.readingMode.name)
        // 方向是可空哨兵：null 表示跟随系统，因此没有"默认字符串"可比较，只要非空就写。
        put(KEY_ORIENTATION, settings.orientation?.name, null)
        put(KEY_PAGER_ZONES, settings.pagerTapZones.name, defaults.pagerTapZones.name)
        put(KEY_WEBTOON_ZONES, settings.webtoonTapZones.name, defaults.webtoonTapZones.name)
        put(KEY_PAGER_INVERT, settings.pagerTapInvert.name, defaults.pagerTapInvert.name)
        put(KEY_WEBTOON_INVERT, settings.webtoonTapInvert.name, defaults.webtoonTapInvert.name)
        put(KEY_SCALE_TYPE, settings.imageScaleType.name, defaults.imageScaleType.name)
        put(KEY_ZOOM_START, settings.zoomStart.name, defaults.zoomStart.name)
        put(KEY_THEME, settings.theme.name, defaults.theme.name)
        put(KEY_HIDE_THRESHOLD, settings.hideThreshold.name, defaults.hideThreshold.name)
        put(KEY_DOUBLE_TAP_ANIM, settings.doubleTapAnimMillis.toString(), defaults.doubleTapAnimMillis.toString())
        put(KEY_WEBTOON_PADDING, settings.webtoonSidePadding.toString(), defaults.webtoonSidePadding.toString())
        put(
            KEY_BRIGHTNESS_VALUE,
            settings.customBrightnessValue.toString(),
            defaults.customBrightnessValue.toString(),
        )

        putFlag(KEY_TRANSITIONS, settings.pageTransitions, defaults.pageTransitions)
        putFlag(KEY_VOLUME_KEYS, settings.volumeKeys, defaults.volumeKeys)
        putFlag(KEY_VOLUME_INVERTED, settings.volumeKeysInverted, defaults.volumeKeysInverted)
        putFlag(KEY_LONG_TAP, settings.longTapActions, defaults.longTapActions)
        putFlag(KEY_CROP, settings.cropBorders, defaults.cropBorders)
        putFlag(KEY_CROP_WEBTOON, settings.cropBordersWebtoon, defaults.cropBordersWebtoon)
        putFlag(KEY_PAN_WIDE, settings.panWideImages, defaults.panWideImages)
        putFlag(KEY_LANDSCAPE_ZOOM, settings.landscapeZoom, defaults.landscapeZoom)
        putFlag(KEY_WEBTOON_DOUBLE_TAP, settings.webtoonDoubleTapZoom, defaults.webtoonDoubleTapZoom)
        putFlag(KEY_WEBTOON_NO_ZOOM_OUT, settings.webtoonDisableZoomOut, defaults.webtoonDisableZoomOut)
        putFlag(KEY_SHOW_PAGE_NUMBER, settings.showPageNumber, defaults.showPageNumber)
        putFlag(KEY_FULLSCREEN, settings.fullscreen, defaults.fullscreen)
        putFlag(KEY_KEEP_SCREEN_ON, settings.keepScreenOn, defaults.keepScreenOn)
        putFlag(KEY_ALWAYS_TRANSITION, settings.showChapterTransitions, defaults.showChapterTransitions)
        put(KEY_PRELOAD_PAGES, settings.preloadPages.toString(), defaults.preloadPages.toString())
        putFlag(KEY_OVERLAY_ON_START, settings.showTapZoneOverlayOnStart, defaults.showTapZoneOverlayOnStart)
        putFlag(KEY_OVERLAY_ONCE, settings.showTapZoneOverlayOnce, defaults.showTapZoneOverlayOnce)
        putFlag(KEY_SHOW_READING_MODE, settings.showReadingMode, defaults.showReadingMode)
        putFlag(KEY_GRAYSCALE, settings.grayscale, defaults.grayscale)
        putFlag(KEY_INVERTED, settings.invertedColors, defaults.invertedColors)
        putFlag(KEY_CUSTOM_BRIGHTNESS, settings.customBrightness, defaults.customBrightness)

        return lines.joinToString("\n")
    }

    /**
     * 解码。无法识别的键忽略、无法识别的值回退该字段默认值。
     *
     * 任何输入都不能让阅读器打不开——最坏情况是用默认设置打开，而不是崩溃。
     */
    fun decode(stored: String?): ReaderSettings {
        if (stored.isNullOrBlank()) return ReaderSettings()
        val fields = HashMap<String, String>()
        for (line in stored.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            fields[line.substring(0, separator)] = line.substring(separator + 1)
        }
        if (fields.isEmpty()) return ReaderSettings()

        val defaults = ReaderSettings()
        return ReaderSettings(
            readingMode = fields[KEY_MODE]?.let(ReadingMode::fromStorage) ?: defaults.readingMode,
            orientation = fields[KEY_ORIENTATION]?.let(ReaderOrientation::fromStorage),
            pagerTapZones = fields[KEY_PAGER_ZONES]?.let(TapZones::fromStorage) ?: defaults.pagerTapZones,
            webtoonTapZones = fields[KEY_WEBTOON_ZONES]?.let(TapZones::fromStorage) ?: defaults.webtoonTapZones,
            pagerTapInvert = fields[KEY_PAGER_INVERT]?.let(TapInvert::fromStorage) ?: defaults.pagerTapInvert,
            webtoonTapInvert = fields[KEY_WEBTOON_INVERT]?.let(TapInvert::fromStorage) ?: defaults.webtoonTapInvert,
            imageScaleType = fields[KEY_SCALE_TYPE]?.let(ImageScaleType::fromStorage) ?: defaults.imageScaleType,
            zoomStart = fields[KEY_ZOOM_START]?.let(ZoomStart::fromStorage) ?: defaults.zoomStart,
            theme = fields[KEY_THEME]?.let(ReaderTheme::fromStorage) ?: defaults.theme,
            hideThreshold = fields[KEY_HIDE_THRESHOLD]?.let(ReaderHideThreshold::fromStorage)
                ?: defaults.hideThreshold,
            doubleTapAnimMillis = fields[KEY_DOUBLE_TAP_ANIM]?.toIntOrNull()
                ?.coerceIn(DOUBLE_TAP_ANIM_MIN, DOUBLE_TAP_ANIM_MAX)
                ?: defaults.doubleTapAnimMillis,
            webtoonSidePadding = fields[KEY_WEBTOON_PADDING]?.toIntOrNull()
                ?.coerceIn(WEBTOON_PADDING_MIN, WEBTOON_PADDING_MAX)
                ?: defaults.webtoonSidePadding,
            customBrightnessValue = fields[KEY_BRIGHTNESS_VALUE]?.toIntOrNull()
                ?.coerceIn(CUSTOM_BRIGHTNESS_MIN, CUSTOM_BRIGHTNESS_MAX)
                ?: defaults.customBrightnessValue,
            pageTransitions = readFlag(fields, KEY_TRANSITIONS, defaults.pageTransitions),
            volumeKeys = readFlag(fields, KEY_VOLUME_KEYS, defaults.volumeKeys),
            volumeKeysInverted = readFlag(fields, KEY_VOLUME_INVERTED, defaults.volumeKeysInverted),
            longTapActions = readFlag(fields, KEY_LONG_TAP, defaults.longTapActions),
            cropBorders = readFlag(fields, KEY_CROP, defaults.cropBorders),
            cropBordersWebtoon = readFlag(fields, KEY_CROP_WEBTOON, defaults.cropBordersWebtoon),
            panWideImages = readFlag(fields, KEY_PAN_WIDE, defaults.panWideImages),
            landscapeZoom = readFlag(fields, KEY_LANDSCAPE_ZOOM, defaults.landscapeZoom),
            webtoonDoubleTapZoom = readFlag(fields, KEY_WEBTOON_DOUBLE_TAP, defaults.webtoonDoubleTapZoom),
            webtoonDisableZoomOut = readFlag(fields, KEY_WEBTOON_NO_ZOOM_OUT, defaults.webtoonDisableZoomOut),
            showPageNumber = readFlag(fields, KEY_SHOW_PAGE_NUMBER, defaults.showPageNumber),
            fullscreen = readFlag(fields, KEY_FULLSCREEN, defaults.fullscreen),
            keepScreenOn = readFlag(fields, KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
            showChapterTransitions = readFlag(
                fields,
                KEY_ALWAYS_TRANSITION,
                defaults.showChapterTransitions,
            ),
            preloadPages = fields[KEY_PRELOAD_PAGES]?.toIntOrNull()
                ?.coerceIn(ReaderSettings.PRELOAD_PAGES_MIN, ReaderSettings.PRELOAD_PAGES_MAX)
                ?: defaults.preloadPages,
            showTapZoneOverlayOnStart = readFlag(fields, KEY_OVERLAY_ON_START, defaults.showTapZoneOverlayOnStart),
            showTapZoneOverlayOnce = readFlag(fields, KEY_OVERLAY_ONCE, defaults.showTapZoneOverlayOnce),
            showReadingMode = readFlag(fields, KEY_SHOW_READING_MODE, defaults.showReadingMode),
            grayscale = readFlag(fields, KEY_GRAYSCALE, defaults.grayscale),
            invertedColors = readFlag(fields, KEY_INVERTED, defaults.invertedColors),
            customBrightness = readFlag(fields, KEY_CUSTOM_BRIGHTNESS, defaults.customBrightness),
        )
    }

    /**
     * 键不存在表示"用户没设置过"，用当前默认值；键存在则解析真实布尔值，
     * 解析不出来同样回退默认值。
     */
    private fun readFlag(fields: Map<String, String>, key: String, default: Boolean): Boolean =
        when (fields[key]) {
            null -> default
            "true" -> true
            "false" -> false
            else -> default
        }

    // 下列键名是**持久化契约**：改名等于丢用户设置。
    private const val KEY_MODE = "mode"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_PAGER_ZONES = "pagerZones"
    private const val KEY_WEBTOON_ZONES = "webtoonZones"
    private const val KEY_PAGER_INVERT = "pagerInvert"
    private const val KEY_WEBTOON_INVERT = "webtoonInvert"
    private const val KEY_SCALE_TYPE = "scaleType"
    private const val KEY_ZOOM_START = "zoomStart"
    private const val KEY_THEME = "theme"
    private const val KEY_HIDE_THRESHOLD = "hideThreshold"
    private const val KEY_DOUBLE_TAP_ANIM = "doubleTapAnim"
    private const val KEY_WEBTOON_PADDING = "webtoonPadding"
    private const val KEY_TRANSITIONS = "transitions"
    private const val KEY_VOLUME_KEYS = "volumeKeys"
    private const val KEY_VOLUME_INVERTED = "volumeInverted"
    private const val KEY_LONG_TAP = "longTap"
    private const val KEY_CROP = "crop"
    private const val KEY_CROP_WEBTOON = "cropWebtoon"
    private const val KEY_PAN_WIDE = "panWide"
    private const val KEY_LANDSCAPE_ZOOM = "landscapeZoom"
    private const val KEY_WEBTOON_DOUBLE_TAP = "webtoonDoubleTap"
    private const val KEY_WEBTOON_NO_ZOOM_OUT = "webtoonNoZoomOut"
    private const val KEY_SHOW_PAGE_NUMBER = "showPageNumber"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    private const val KEY_ALWAYS_TRANSITION = "alwaysTransition"
    /**
     * 键名沿用 `alwaysTransition`：它是这个开关最早的持久化契约（当时的语义是"是否插入
     * 过渡项"，中间一度被解释为"到达后停不停"，现在回到"是否插入"）。因此老用户显式
     * 设过的值一直有意义，不需要迁移。
     */
    private const val KEY_PRELOAD_PAGES = "preloadPages"
    private const val KEY_OVERLAY_ON_START = "overlayOnStart"
    private const val KEY_OVERLAY_ONCE = "overlayOnce"
    private const val KEY_SHOW_READING_MODE = "showReadingMode"
    private const val KEY_GRAYSCALE = "grayscale"
    private const val KEY_INVERTED = "inverted"
    private const val KEY_CUSTOM_BRIGHTNESS = "customBrightness"
    private const val KEY_BRIGHTNESS_VALUE = "brightnessValue"

    /** Mihon 的 `pref_double_tap_anim_speed` 取值是 1 / 250 / 500，这里放宽成区间防护。 */
    private const val DOUBLE_TAP_ANIM_MIN = 0
    private const val DOUBLE_TAP_ANIM_MAX = 2000

    /** Mihon `WEBTOON_PADDING_MIN` / `WEBTOON_PADDING_MAX`。 */
    private const val WEBTOON_PADDING_MIN = 0
    private const val WEBTOON_PADDING_MAX = 25

    /** Mihon `custom_brightness_value` 的范围 -75..100。 */
    private const val CUSTOM_BRIGHTNESS_MIN = -75
    private const val CUSTOM_BRIGHTNESS_MAX = 100
}
