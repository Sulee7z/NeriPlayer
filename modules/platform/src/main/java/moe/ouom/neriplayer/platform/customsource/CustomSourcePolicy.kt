package moe.ouom.neriplayer.platform.customsource

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun selectCustomSourceQuality(requested: String, supported: List<String>): String? {
    if (supported.isEmpty()) return requested
    if (requested in supported) return requested
    val order = listOf("128k", "320k", "flac", "flac24bit", "master")
    val rank = order.indexOf(requested).takeIf { it >= 0 } ?: 1
    return order.take(rank + 1).asReversed().firstOrNull { it in supported }
        ?: order.firstOrNull { it in supported }
}

internal fun validatedCustomSourceUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() && it.length <= 2048 && it != "null" } ?: return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return value
}
