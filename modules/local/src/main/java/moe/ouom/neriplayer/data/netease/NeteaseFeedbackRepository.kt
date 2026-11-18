package moe.ouom.neriplayer.data.netease

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.identity.NeteaseSongIdentityResolver
import org.json.JSONObject
import java.io.IOException
import java.io.File

/** Serializes start/play and like/unlike without blocking playback or local commits. */
class NeteaseFeedbackRepository(
    context: Context,
    scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val client: NeteaseClient,
    private val accountKey: () -> String,
    private val isFavorite: (SongItem) -> Boolean
) {
    private data class Event(
        val song: SongItem, val sessionId: String, val account: String,
        val seconds: Int? = null, val eventId: String? = null, val like: Boolean? = null,
        val createdAt: Long = System.currentTimeMillis()
    )
    private data class Started(val account: String, val songId: Long)
    private val queue = Channel<Event>(256)
    private val identities = NeteaseSongIdentityResolver(client)
    private val started = LinkedHashMap<String, Started>()
    private val receipts = context.getSharedPreferences("netease_feedback_receipts", Context.MODE_PRIVATE)
    private val delivered = LinkedHashSet(receipts.getStringSet("events", emptySet()).orEmpty())
    private val journal = AtomicFile(File(context.filesDir, "netease-feedback-pending.json"))
    private val gson = Gson()
    private data class State(
        val pending: List<Event> = emptyList(),
        val started: Map<String, Started> = emptyMap(),
        val delivered: Set<String> = emptySet()
    )

    init {
        scope.launch {
            val pending = mutableListOf<Event>()
            try {
                if (journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()) {
                    val state = journal.openRead().bufferedReader().use { gson.fromJson(it, State::class.java) }
                    pending.addAll(state.pending.take(256))
                    started.putAll(state.started.entries.toList().takeLast(128).associate { it.toPair() })
                    delivered.addAll(state.delivered.toList().takeLast(512))
                }
            } catch (error: Exception) {
                NPLogger.w("NeteaseFeedback", "网易云回写队列读取失败，保留原文件", error)
                return@launch
            }
            while (true) {
                val event = withTimeoutOrNull(60_000L) { queue.receive() }
                if (event != null) {
                    if (pending.size >= 256) {
                        NPLogger.w("NeteaseFeedback", "网易云持久回写队列已满，保留已有事件")
                    } else pending.add(event)
                    try { persist(pending) } catch (error: Exception) {
                        NPLogger.w("NeteaseFeedback", "回写队列保存失败，保留内存事件等待重试", error)
                        continue
                    }
                }
                while (pending.isNotEmpty()) {
                    val next = pending.first()
                    try {
                        if (System.currentTimeMillis() - next.createdAt <= 24 * 60 * 60 * 1000L) process(next)
                        persist(pending.drop(1))
                        pending.removeAt(0)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        NPLogger.w("NeteaseFeedback", "网易云回写失败，保留队列稍后重试", error)
                        // Keep event order: a play event cannot overtake its startplay.
                        break
                    }
                }
            }
        }
    }

    fun notifyStart(song: SongItem, sessionId: String) = submit(Event(song, sessionId, accountKey()))

    fun notifyCounted(song: SongItem, sessionId: String, listenedMs: Long, eventId: String) {
        if (listenedMs < 30_000L || sessionId.isBlank()) return
        submit(Event(song, sessionId, accountKey(), seconds = (listenedMs / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), eventId = eventId))
    }

    fun notifyFavorite(song: SongItem, like: Boolean) =
        submit(Event(song, "", accountKey(), like = like))

    private fun submit(event: Event) {
        val song = event.song
        val metadata = SongItem(song.id, song.name.take(1024), song.artist.take(1024), song.album.take(1024),
            song.albumId, song.durationMs, null, channelId = song.channelId, audioId = song.audioId,
            mediaUri = song.mediaUri, localFileName = song.localFileName, localFilePath = song.localFilePath,
            sourceStableKey = song.sourceStableKey, subAudioId = song.subAudioId,
            matchedLyricSource = song.matchedLyricSource, matchedSongId = song.matchedSongId,
            originalName = song.originalName?.take(1024), originalArtist = song.originalArtist?.take(1024))
        if (event.account.isBlank() || !queue.trySend(event.copy(song = metadata)).isSuccess) {
            if (event.account.isNotBlank()) NPLogger.w("NeteaseFeedback", "网易云回写队列已满")
        }
    }

    private suspend fun process(event: Event) {
        if (event.account.isBlank() || event.account != accountKey() || !client.hasLogin()) return
        val setting = if (event.like != null) AutoSettingsSchema.playback.neteaseAutoSyncLikeEnabled
            else AutoSettingsSchema.playback.neteaseScrobbleEnabled
        if (!settings.settingFlow(setting).first()) return
        if (event.like != null) {
            // A rapid toggle may supersede an earlier queued mutation.
            if (isFavorite(event.song) != event.like) return
            val id = identities.resolve(event.song) ?: return
            if (event.account != accountKey() || isFavorite(event.song) != event.like) return
            retry(event) { client.likeSongForAccount(id, event.like, event.account) }
            return
        }
        if (event.seconds == null) {
            if (started.containsKey(event.sessionId)) return
            val id = identities.resolve(event.song) ?: return
            if (event.account != accountKey()) return
            retry(event) { client.notifyPlayStart(id, expectedAccountKey = event.account) }
            if (started.size >= 128) started.remove(started.keys.first())
            started[event.sessionId] = Started(event.account, id)
            return
        }
        val receipt = "${event.account}:${event.sessionId}"
        if (receipt in delivered) return
        val start = started[event.sessionId]?.takeIf { it.account == event.account } ?: return
        retry(event) { client.scrobble(start.songId, event.seconds, expectedAccountKey = event.account) }
        delivered.add(receipt)
        while (delivered.size > 512) delivered.remove(delivered.first())
        if (!receipts.edit().putStringSet("events", delivered.toSet()).commit()) {
            throw IOException("听歌上报回执保存失败")
        }
    }

    private suspend fun retry(event: Event, request: () -> String) {
        for (attempt in 0..2) {
            if (event.account != accountKey() || !client.hasLogin()) throw IOException("网易云账号已变化")
            val setting = if (event.like == null) AutoSettingsSchema.playback.neteaseScrobbleEnabled
                else AutoSettingsSchema.playback.neteaseAutoSyncLikeEnabled
            if (!settings.settingFlow(setting).first()) return
            if (event.like != null && isFavorite(event.song) != event.like) return
            try {
                val response = JSONObject(request())
                if (response.optInt("code") != 200) throw IOException("网易云接口返回 ${response.optInt("code")}")
                return
            } catch (error: IOException) {
                if (attempt == 2) throw error
                delay(1_000L shl attempt)
            }
        }
    }

    private fun persist(pending: List<Event>) {
        val output = journal.startWrite()
        try {
            output.write(gson.toJson(State(pending.toList(), started.toMap(), delivered.toSet())).toByteArray())
            journal.finishWrite(output)
        } catch (error: Throwable) { journal.failWrite(output); throw error }
    }
}

object NeteaseFavoriteMutationListener {
    @Volatile private var listener: ((SongItem, Boolean) -> Unit)? = null
    fun bind(listener: (SongItem, Boolean) -> Unit) { this.listener = listener }
    fun emit(song: SongItem, like: Boolean) { listener?.invoke(song, like) }
}
