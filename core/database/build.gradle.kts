plugins {
    alias(libs.plugins.lmreader.android.library.room)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    // 复用 core:index 的自然序键：卡片排序必须与章节排序用同一套规则，
    // 否则同一部作品在列表里的位置与它第一章的顺序会互相矛盾（开发文档 1.3）。
    implementation(project(":core:index"))
    implementation(libs.kotlinx.serialization.json)
}
