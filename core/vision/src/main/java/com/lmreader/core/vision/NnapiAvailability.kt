package com.lmreader.core.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import android.os.Build

internal object NnapiAvailability {
    // Device inventory is stable within a process; avoid repeating driver queries
    // for every detector/recognizer instance in the OCR pool.
    val unavailableReason: String? by lazy {
        when {
            Build.VERSION.SDK_INT < 29 -> "系统不支持 NNAPI 硬件枚举，需要 Android 10 或以上"
            OrtProvider.NNAPI !in OrtEnvironment.getAvailableProviders() -> "ONNX Runtime 未提供 NNAPI 后端"
            else -> try {
                System.loadLibrary("lmreader_vision")
                nativeUnavailableReason()
            } catch (failure: LinkageError) { "NNAPI 检查运行库不可用：${failure.message}" }
        }
    }
    private external fun nativeUnavailableReason(): String?
}
