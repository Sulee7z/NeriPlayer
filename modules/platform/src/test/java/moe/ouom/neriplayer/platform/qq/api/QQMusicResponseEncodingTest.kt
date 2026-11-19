package moe.ouom.neriplayer.platform.qq.api

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class QQMusicResponseEncodingTest {
    private fun api(json: String, encoding: String): QQMusicApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(json.toByteArray(charset(encoding))
                    .toResponseBody("application/json;charset=$encoding".toMediaType()))
                .build()
        }.build()
        return QQMusicApi(client)
    }

    @Test fun hotPlaylistNamesAndCreatorsRespectLegacyGb2312Response() = runTest {
        val api = api("""{"code":0,"data":{"list":[{"dissid":"123","dissname":"甜度爆表","listennum":12,"creator":{"name":"两颗西柚"}}]}}""", "GB2312")
        val playlist = api.getHotPlaylists().single()
        assertEquals("甜度爆表", playlist.title)
        assertEquals("两颗西柚", playlist.creator)
    }

    @Test fun utf8PlaylistDetailsKeepJapaneseAndChineseText() = runTest {
        val api = api("""{"code":0,"cdlist":[{"dissname":"今日私享・音楽","songnum":1,"songlist":[{"songmid":"song1","songname":"星空の旅","singer":[{"name":"歌手"}],"albumname":"我的专辑","interval":180}]}]}""", "UTF-8")
        val detail = api.getPlaylistDetail(123)
        assertEquals("今日私享・音楽", detail.title)
        assertEquals("星空の旅", detail.songs.single().songName)
        assertEquals("歌手", detail.songs.single().singer.single().name)
        assertEquals("我的专辑", detail.songs.single().albumName)
    }
}
