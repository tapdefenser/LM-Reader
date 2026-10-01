package com.lmreader.ui.queue

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Accounts decoded bitmaps and ready OCR text; lowering the limit never evicts an in-use page. */
class TranslationCacheBudget(initialLimit: Long) {
    private val mutex = Mutex()
    private val changed = MutableStateFlow(0L)
    private val mutableBytes = MutableStateFlow(0L)
    val bytes = mutableBytes.asStateFlow()
    @Volatile private var limit = initialLimit
    fun setLimit(bytes: Long) { require(bytes >= 32L * 1_048_576); limit = bytes; changed.update { it + 1 } }
    suspend fun tryAcquire(bytes: Long): Lease? = mutex.withLock {
        require(bytes > 0)
        if(mutableBytes.value + bytes > limit) null
        else { mutableBytes.value += bytes; Lease(bytes) }
    }
    suspend fun acquire(bytes: Long): Lease {
        require(bytes > 0)
        while (true) {
            val revision = changed.value
            val granted = mutex.withLock {
                if (mutableBytes.value + bytes <= limit) { mutableBytes.value += bytes; true } else false
            }
            if (granted) return Lease(bytes)
            changed.first { it != revision }
        }
    }
    inner class Lease internal constructor(private var reserved: Long) {
        suspend fun shrink(bytes: Long) = mutex.withLock {
            require(bytes in 0..reserved)
            mutableBytes.value -= reserved - bytes; reserved = bytes; changed.update { it + 1 }
        }
        suspend fun release() = shrink(0)
    }
}
