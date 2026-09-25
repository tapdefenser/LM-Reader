// build-logic — 约定插件的独立构建。
// 独立 includeBuild 使 AGP/Kotlin 插件只在这里解析一次，各模块不再重复声明版本。

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
