package moe.ouom.neriplayer.platform.customsource

import org.junit.Assert.*
import org.junit.Test

class CustomSourcePolicyTest {
    @Test fun unsupportedQualityFallsBackToDeclaredQuality() {
        assertEquals("128k", selectCustomSourceQuality("flac", listOf("128k")))
        assertEquals("flac24bit", selectCustomSourceQuality("flac24bit", listOf("320k", "flac24bit")))
    }

    @Test fun supportedQualityIsPreserved() {
        assertEquals("flac", selectCustomSourceQuality("flac", listOf("128k", "flac")))
        assertNull(selectCustomSourceQuality("flac", listOf("unsupported")))
    }

    @Test fun onlyHttpMediaUrlsAreAccepted() {
        assertNull(validatedCustomSourceUrl("null"))
        assertNull(validatedCustomSourceUrl("file:///etc/passwd"))
        assertNull(validatedCustomSourceUrl("javascript:alert(1)"))
        assertNull(validatedCustomSourceUrl("https://user:password@example.com/a.mp3"))
        assertEquals("https://example.com/a.mp3", validatedCustomSourceUrl(" https://example.com/a.mp3 "))
    }
}
