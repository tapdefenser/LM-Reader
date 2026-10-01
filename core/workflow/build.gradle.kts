plugins { alias(libs.plugins.lmreader.jvm.library) }
dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
