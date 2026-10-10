package com.lanshare.app

import org.junit.Assert.*
import org.junit.Test

class FileNamesTest {
    @Test fun duplicatesKeepExtensionAndIncrementSequence() {
        val existing = mutableSetOf("example.apk", "example (1).apk")
        val next = FileNames.available("example.apk", existing::contains)
        assertEquals("example (2).apk", next)
        existing.add(next)
        assertEquals("example (3).apk", FileNames.available("example.apk", existing::contains))
        assertEquals("README (1)", FileNames.available("README") { it == "README" })
    }

    @Test fun longUnicodeNamesRemainAccessibleThroughSafeName() {
        val name = "😀".repeat(116) + ".txt"
        val next = FileNames.available(name) { it == name }
        assertEquals(next, FileNames.safe(next))
        assertTrue(next.endsWith(" (1).txt"))
        assertTrue(next.codePointCount(0, next.length) <= 120)
    }
}
