package moe.ouom.neriplayer.platform.netease.identity

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.*
import org.junit.Test

class NeteaseSongIdentityTest {
    private fun song() = SongItem(12, "Song", "Singer", "Album", 0, 180_000, null)

    @Test fun coverDoesNotDefinePlatformIdentity() {
        assertNull(resolveNeteaseSongIdOrNull(song().copy(channelId = "qq", coverUrl = "https://p1.music.126.net/a.jpg")))
        assertNull(resolveNeteaseSongIdOrNull(song().copy(coverUrl = "https://p1.music.126.net/a.jpg")))
    }

    @Test fun nativeAudioIdTakesPrecedenceOverLocalNumericId() {
        assertEquals(42L, resolveNeteaseSongIdOrNull(song().copy(channelId = "netease", audioId = "42")))
    }

    @Test fun negativeLocalIdDoesNotHideExplicitMetadataMatch() {
        assertEquals(42L, resolveNeteaseSongIdOrNull(song().copy(id = -1, matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")))
    }

    @Test fun matchingTitleWithWrongArtistIsNotAccepted() {
        val response = """{"code":200,"result":{"songs":[{"id":1,"name":"Song","ar":[{"name":"Another Singer"}],"dt":180000}]}}"""
        assertNull(matchNeteaseSearchSongId(response, "Song", "Singer", 180_000))
    }

    @Test fun bothArtistResponseFormatsAreSupported() {
        for (field in listOf("ar", "artists")) {
            val response = """{"code":200,"result":{"songs":[{"id":42,"name":"Song","$field":[{"name":"Singer"}],"duration":180000}]}}"""
            assertEquals(42L, matchNeteaseSearchSongId(response, "Song", "Singer", 180_000))
            assertNull(matchNeteaseSearchSongId(response, "Song", "Singer", 250_000))
        }
    }
}
