package com.lmreader.core.vision

import org.json.JSONArray

/** A registered provider can claim zero nodes; only execution events prove use. */
internal fun hardwareNodeCount(profile: String, provider: String): Int {
    val events = JSONArray(profile)
    return (0 until events.length()).count { index ->
        val event = events.optJSONObject(index)
        event?.optString("cat") == "Node" && event.optJSONObject("args")?.optString("provider") == provider
    }
}
