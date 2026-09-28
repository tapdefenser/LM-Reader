plugins {
    alias(libs.plugins.lmreader.android.application)
}

android {
    defaultConfig {
        // 资源前缀，避免与依赖库资源冲突。
        resourcePrefix = "lmreader_"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        // 启动图标与窗口主题的资源名由平台约定固定：`ic_launcher` 必须叫这个名字，
        // 主题习惯用大写驼峰。它们无法满足 `lmreader_` 前缀，但也不会与依赖库冲突
        // （库的图标不会被合并进宿主）。关掉这一条，而不是为过 lint 把资源改名成
        // 不合平台约定的形式。
        disable += "ResourceName"
        // 其余 lint 问题继续让构建失败：这类检查（例如属性转义、Android 14
        // 部分照片访问）恰恰是"看起来能跑但会在真机上出问题"的那一类。
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:index"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // 阅读器图片引擎：缩放/平移/分块解码/裁白边由它实现。
    // Mihon 的非 WebGPU 阅读路径用的就是同一个 fork（com.github.mihonapp）。
    // 它含 native 代码（libssiv_crop.so），APK 会带四种 ABI 的该库。
    implementation(libs.subsampling.scale.image.view)

    // 阅读器的章节编排是纯逻辑（项列表组装、跨章提升、下标重定位），
    // 出错时不会崩溃、只会让读者在章末撞墙或跳页，因此必须用单测把结构钉住。
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
