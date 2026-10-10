package com.lanshare.app

import java.net.URLEncoder

/** Preserve Unicode while excluding path separators, protocol delimiters and control characters. */
object FileNames {
    fun safe(value: String): String {
        val cleaned = value.replace(Regex("[\\p{Cc}\\p{Cf}\\\\/:*?\"<>|]"), "_")
            .trim().trimEnd('.')
        val end = cleaned.offsetByCodePoints(0, minOf(120, cleaned.codePointCount(0, cleaned.length)))
        return cleaned.substring(0, end).trimEnd(' ', '.').ifBlank { "file" }
    }

    fun available(value: String, exists: (String) -> Boolean): String {
        val name = safe(value)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var candidate = name
        var index = 1
        while (exists(candidate)) {
            val suffix = " (${index++})$extension"
            val length = maxOf(1, 120 - suffix.codePointCount(0, suffix.length))
            val end = stem.offsetByCodePoints(0, minOf(length, stem.codePointCount(0, stem.length)))
            candidate = stem.substring(0, end) + suffix
        }
        return candidate
    }

    fun contentDisposition(name: String): String {
        val fallback = safe(name).replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
    }
}
