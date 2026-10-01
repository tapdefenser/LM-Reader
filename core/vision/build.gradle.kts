plugins { alias(libs.plugins.lmreader.android.library) }

android {
    ndkVersion = "28.2.13676358"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources { noCompress += listOf("tflite", "onnx") }
    // Hexagon loads its DSP skeletons from a filesystem directory.
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.litert)
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.qnn)
    implementation(libs.qnn.runtime)
    implementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val verifyVisionModels by tasks.registering {
    val folder = layout.projectDirectory.dir("src/main/assets/vision/models")
    val files = listOf("seg.tflite", "det.onnx", "rec.onnx", "ko.onnx", "rec.txt", "ko.txt").map { folder.file(it).asFile }
    inputs.files(files)
    doLast {
        check(files.all { it.isFile }) { "本地模型尚未准备：请在仓库根目录运行 python tools/fetch_vision_models.py" }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyVisionModels) }
