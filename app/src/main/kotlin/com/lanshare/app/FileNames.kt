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

    fun contentDisposition(name: String): String {
        val fallback = safe(name).replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
    }
}
