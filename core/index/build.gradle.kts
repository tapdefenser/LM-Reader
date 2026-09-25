plugins {
    alias(libs.plugins.lmreader.jvm.library)
}

dependencies {
    api(project(":core:model"))
}
