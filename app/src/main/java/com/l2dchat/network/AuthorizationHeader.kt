package com.l2dchat.network

internal fun bearerAuthorizationValue(token: String?): String? {
    val value = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (value.startsWith("Bearer ", ignoreCase = true)) value else "Bearer $value"
}
