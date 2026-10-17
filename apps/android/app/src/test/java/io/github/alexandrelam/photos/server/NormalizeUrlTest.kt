package io.github.alexandrelam.photos.server

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeUrlTest {
    @Test
    fun addsSchemeAndDropsTrailingSlash() {
        assertEquals("http://192.168.1.20:8080", normalizeUrl(" 192.168.1.20:8080/ "))
        assertEquals("https://nas.tail1234.ts.net", normalizeUrl("https://nas.tail1234.ts.net//"))
        assertEquals("", normalizeUrl("  "))
    }
}
