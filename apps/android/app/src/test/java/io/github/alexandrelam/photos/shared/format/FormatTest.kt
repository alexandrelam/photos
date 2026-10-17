package io.github.alexandrelam.photos.shared.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun durations() {
        assertEquals("0:14", Format.duration(14_000))
        assertEquals("1:02", Format.duration(62_000))
        assertEquals("1:00:05", Format.duration(3_605_000))
    }

    @Test
    fun ago() {
        val now = 10_000_000_000L
        assertEquals("just now", Format.ago(now - 10_000, now))
        assertEquals("5 min ago", Format.ago(now - 5 * 60_000, now))
        assertEquals("2 h ago", Format.ago(now - 2 * 3_600_000, now))
        assertEquals("3 days ago", Format.ago(now - 3 * 86_400_000L, now))
    }
}
