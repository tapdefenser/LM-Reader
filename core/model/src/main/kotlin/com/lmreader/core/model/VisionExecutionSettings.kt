package com.lmreader.core.model

enum class OcrBackend {
    AUTO, QNN, NNAPI, CPU;

    val fallbackOrder: List<OcrBackend> get() = when (this) {
        AUTO -> listOf(QNN, NNAPI, CPU)
        QNN -> listOf(QNN, CPU)
        NNAPI -> listOf(NNAPI, CPU)
        CPU -> listOf(CPU)
    }
}

/** Concurrency counts simultaneous model sessions, rather than CPU threads in a session. */
data class VisionExecutionSettings(
    val segConcurrency: Int = 0,
    val ocrConcurrency: Int = 0,
    val segGpu: Boolean = true,
    val ocrBackend: OcrBackend = OcrBackend.CPU,
) {
    init { require(segConcurrency in 0..8 && ocrConcurrency in 0..8) }
}
