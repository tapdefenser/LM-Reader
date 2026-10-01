package com.lmreader.core.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import android.os.Build
import android.system.Os
import java.io.File

/** Registration belongs to the process, not an individual model or OCR worker. */
internal object QnnAvailability {
    private var inventory: Result<List<OrtEpDevice>>? = null

    @Synchronized
    fun devices(models: VisionModels): List<OrtEpDevice> {
        val result = inventory ?: runCatching {
            check(Build.VERSION.SDK_INT >= 27) { "QNN 需要 Android 8.1 或以上" }
            check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "QNN 需要 ARM64 设备" }
            // MediaTek and Kirin use NNAPI. Avoid asking Qualcomm's plugin to
            // initialize hardware on devices without its public FastRPC bridge.
            check(File("/vendor/lib64/libcdsprpc.so").isFile) { "设备未提供高通 FastRPC 驱动" }
            val directory = models.nativeLibraryDirectory
            val library = File(directory, "libonnxruntime_providers_qnn.so")
            check(library.isFile) { "QNN 运行库尚未解压" }
            // FastRPC loads libQnnHtpV*Skel.so outside the app linker namespace.
            Os.setenv("ADSP_LIBRARY_PATH", "$directory;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp", true)
            val environment = OrtEnvironment.getEnvironment()
            environment.registerExecutionProviderLibrary("QNNExecutionProvider", library.absolutePath)
            environment.epDevices.filter { it.epName == "QNNExecutionProvider" }
        }.also { inventory = it }
        return result.getOrThrow()
    }
}
