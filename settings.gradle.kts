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
        google { content { excludeGroup("com.qualcomm.qti") } }
        mavenCentral()
        // JitPack：只为阅读器的图片引擎 subsampling-scale-image-view 而加。
        //
        // 为什么必须是这个坐标而不是自己写：Mihon 的 ReaderPageImageView 里**没有任何
        // 变换数学**——它只设属性（setMinimumScaleType / setCropBorders /
        // setDoubleTapZoomStyle）、读 getPanRemaining、调 animateScaleAndCenter。
        // 缩放、平移、分块解码、裁白边、双击焦点、宽图自动放大全在这个库里。
        // 自己写达不到一致的手感，而大图内存也要自己兜（本库做分块解码）。
        //
        // 该坐标只在 JitPack 上（com.github.mihonapp 是 Mihon 团队维护的 SSIV fork，
        // 相比上游多了 setCropBorders）。
        maven {
            url = uri("https://jitpack.io")
            content {
                // 收窄这个仓库的适用范围：只有这个 group 会走 JitPack，
                // 其余依赖继续只从 google()/mavenCentral() 取，避免供应链面被放大。
                includeGroup("com.github.mihonapp")
            }
        }
    }
}

rootProject.name = "LM-Reader"

// 应用宿主与装配
include(":app")

// 纯 Kotlin/JVM：领域模型与结构扫描（可脱离设备跑单元测试）
include(":core:model")
include(":core:api")
include(":core:workflow")
include(":core:index")

// Android 平台层
include(":core:database")
include(":core:storage")
include(":core:vision")
include(":core:translation")
