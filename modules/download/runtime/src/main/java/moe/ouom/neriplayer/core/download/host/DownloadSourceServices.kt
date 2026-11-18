package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.bilibili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository

interface DownloadSourceServices {
    val customSourceManager: moe.ouom.neriplayer.platform.customsource.CustomSourceManager? get() = null
    val qqMusicApi: moe.ouom.neriplayer.platform.qq.api.QQMusicApi? get() = null
    val kugouApi: moe.ouom.neriplayer.platform.kugou.api.KugouApi? get() = null
    val kuwoApi: moe.ouom.neriplayer.platform.kuwo.api.KuwoApi? get() = null
    val subsonicRepository: SubsonicRepository? get() = null
    val neteaseClient: NeteaseClient
    val biliClient: BiliClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    val youtubeMusicDownloadPlaybackRepository: YouTubeMusicPlaybackRepository
}
