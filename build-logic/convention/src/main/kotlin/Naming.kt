import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * 由 Gradle 模块路径推导默认 Android namespace。
 *
 * `:core:model`   -> `com.lmreader.core.model`
 * `:core:storage` -> `com.lmreader.core.storage`
 *
 * 模块可在自己的 build.gradle.kts 的 `android { namespace = ... }` 中覆盖。
 */
internal fun Project.defaultNamespace(): String =
    "com.lmreader" + path.split(":")
        .filter { it.isNotEmpty() && it != ":" }
        .joinToString("") { "." + it.replace('-', '_').replace('.', '_') }

/**
 * 版本目录访问器。
 *
 * 约定插件是普通 Kotlin 类（不是预编译脚本插件），不会获得 Gradle 生成的
 * `libs` 类型安全访问器，因此这里显式从 VersionCatalogsExtension 取用。
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** 最低 Android 8.0 / API 26（开发文档 15.1「初始兼容目标」）。 */
internal const val MIN_SDK = 26

/** 编译目标紧随 AndroidX 要求推进（LM-translator 已验证 compileSdk 37 可用）。 */
internal const val COMPILE_SDK = 37

/**
 * targetSdk 与 compileSdk 有意不同步。
 *
 * 编译需要新 SDK 才能消费新版 AndroidX；targetSdk 决定运行时行为开关，
 * 按开发文档 17.P5 单独审查，不与组件升级混合。
 */
internal const val TARGET_SDK = 36

/** 所有模块统一使用 JVM 17 字节码，保证 Android 模块可消费纯 JVM 模块。 */
internal const val JVM_TARGET = 17
