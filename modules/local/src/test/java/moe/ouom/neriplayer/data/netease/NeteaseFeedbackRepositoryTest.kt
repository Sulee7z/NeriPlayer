package moe.ouom.neriplayer.data.netease

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NeteaseFeedbackRepositoryTest {
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var client: NeteaseClient
    private val enabled = MutableStateFlow(true)
    private fun song() = SongItem(42, "Song", "Singer", "Netease", 0, 180_000, null,
        channelId = "netease", audioId = "42")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("netease_feedback_receipts", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("netease-feedback-pending.json").delete()
        context.filesDir.resolve("netease-feedback-pending.json.bak").delete()
        settings = mock(SettingsRepository::class.java)
        client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(settings.settingFlow(AutoSettingsSchema.playback.neteaseScrobbleEnabled)).thenReturn(enabled)
        `when`(settings.settingFlow(AutoSettingsSchema.playback.neteaseAutoSyncLikeEnabled)).thenReturn(flowOf(true))
        `when`(client.notifyPlayStart(42L, null, "account-A")).thenReturn("{\"code\":200}")
        `when`(client.scrobble(42L, 30, null, "playend", "account-A")).thenReturn("{\"code\":200}")
        `when`(client.likeSongForAccount(42L, false, "account-A")).thenReturn("{\"code\":200}")
    }

    @Test fun `start precedes play and repeated durable statistics events do not upload twice`() = runTest {
        val feedback = NeteaseFeedbackRepository(context, backgroundScope, settings, client, { "account-A" }, { false })
        feedback.notifyStart(song(), "session")
        feedback.notifyCounted(song(), "session", 30_000, "event")
        feedback.notifyCounted(song(), "session", 30_000, "event")
        runCurrent()
        val order = inOrder(client)
        order.verify(client).notifyPlayStart(42L, null, "account-A")
        order.verify(client).scrobble(42L, 30, null, "playend", "account-A")
        verify(client, times(1)).scrobble(42L, 30, null, "playend", "account-A")
    }

    @Test fun `switching accounts discards an old accounts queued events`() = runTest {
        var account = "account-A"
        val feedback = NeteaseFeedbackRepository(context, backgroundScope, settings, client, { account }, { false })
        feedback.notifyStart(song(), "session")
        account = "account-B"
        runCurrent()
        verify(client, never()).notifyPlayStart(42L, null, "account-A")
    }

    @Test fun `disabled reporting and short listens never upload play events`() = runTest {
        enabled.value = false
        val feedback = NeteaseFeedbackRepository(context, backgroundScope, settings, client, { "account-A" }, { false })
        feedback.notifyStart(song(), "session")
        feedback.notifyCounted(song(), "session", 1_000, "event")
        runCurrent()
        verify(client, never()).notifyPlayStart(42L, null, "account-A")
        verify(client, never()).scrobble(42L, 30, null, "playend", "account-A")
    }

    @Test fun `rapid like unlike keeps final local state`() = runTest {
        val feedback = NeteaseFeedbackRepository(context, backgroundScope, settings, client, { "account-A" }, { false })
        feedback.notifyFavorite(song(), true)
        feedback.notifyFavorite(song(), false)
        runCurrent()
        verify(client, never()).likeSongForAccount(42L, true, "account-A")
        verify(client, times(1)).likeSongForAccount(42L, false, "account-A")
    }
}
