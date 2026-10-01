plugins {
    alias(libs.plugins.lmreader.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.mockwebserver)
}
