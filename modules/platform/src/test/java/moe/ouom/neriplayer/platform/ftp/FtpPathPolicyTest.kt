package moe.ouom.neriplayer.platform.ftp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FtpPathPolicyTest {
    @Test fun validFilenameWhitespaceAndDotsArePreserved() {
        assertEquals("/music/ song..mp3 ", FtpClient.joinPath("/music", " song..mp3 "))
    }
    @Test(expected = IOException::class) fun parentTraversalIsRejected() {
        FtpClient.joinPath("/music", "../other.mp3")
    }
    @Test(expected = IllegalArgumentException::class) fun commandInjectionIsRejected() {
        FtpClient.normalizePath("/music\r\nDELE file")
    }
}
