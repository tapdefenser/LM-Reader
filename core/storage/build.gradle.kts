plugins {
    alias(libs.plugins.lmreader.android.library)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:api"))
    // 存储层要驱动结构扫描（StructureScanner/ScanRequest）与元数据解析
    // （ComicInfoParser），因此显式依赖 core:index；仍不依赖 core:database，
    // 落库通过 core:model 的仓储接口完成（开发文档 15.2 的模块边界）。
    api(project(":core:index"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // 真机排障用的 instrumentation 测试（SafDiagnosticTest）。
    // 只依赖 androidx.test 的 core/junit：本机 Gradle 缓存里只有这两个可用版本，
    // 不引入 espresso 等无关依赖，避免把测试栈变成新的构建风险。
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
