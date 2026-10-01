package com.lmreader.ui.reader.translation

/** A rejected swipe keeps its destination until saving succeeds, discarding, or cancellation. */
internal class DraftNavigationGate {
    private var pending: (() -> Unit)? = null
    val waiting get() = pending != null
    fun request(dirty: Boolean, busy: Boolean, action: () -> Unit): Boolean {
        if (busy || waiting) return false
        if (dirty) { pending = action; return false }
        action(); return true
    }
    fun cancel() { pending = null }
    fun resume() { val action = pending; pending = null; action?.invoke() }
}
