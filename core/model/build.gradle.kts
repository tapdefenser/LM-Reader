plugins {
    alias(libs.plugins.lmreader.jvm.library)
}

dependencies {
    // 仓储契约返回 Flow，因此协程核心是 core:model 的 API 依赖而不是实现细节。
    api(libs.kotlinx.coroutines.core)
}
