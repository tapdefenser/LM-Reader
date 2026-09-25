// LM-Reader — 构建入口。
// 模块划分依据 docs/开发文档.md 第 15.2 节「模块边界」。
// 初期用少量模块 + 包边界，契约稳定后再拆细；不一次建十个空模块。

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LM-Reader"

// 应用宿主与装配
include(":app")

// 纯 Kotlin/JVM：领域模型与结构扫描（可脱离设备跑单元测试）
include(":core:model")
include(":core:index")

// Android 平台层
include(":core:database")
include(":core:storage")
