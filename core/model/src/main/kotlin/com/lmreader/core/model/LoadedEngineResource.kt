package com.lmreader.core.model

enum class InferenceEngineKind { SEG, OCR, TRANSLATION }

/** A real retained model/session, distinct from the bounded page preprocessing cache. */
data class LoadedEngineResource(
    val id: String,
    val kind: InferenceEngineKind,
    val name: String,
    val backend: String,
    val inUse: Boolean = false,
)
