package moe.ouom.neriplayer.core.player.runtime.stats

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.*
import org.junit.Test

class PlaybackFeedbackSessionTest {
    private fun song(id: Long = 1) = SongItem(id, "Song", "Singer", "Netease", 0, 180_000, null)

    @Test fun requestWithoutAudibleProgressDoesNotReportAStart() {
        val starts = mutableListOf<String>()
        val tracker = PlaybackStatsTracker({ it.id.toString() }, nowElapsedMs = { 0L }, onPlayStarted = { _, id -> starts += id })
        tracker.onSongChanged(song())
        tracker.onPlayingChanged(true)
        assertTrue(starts.isEmpty())
        tracker.reportPlayStarted(song())
        tracker.reportPlayStarted(song())
        assertEquals(1, starts.size)
        tracker.onTrackEnded()
        tracker.reportPlayStarted(song())
        assertEquals(2, starts.size)
        assertNotEquals(starts[0], starts[1])
    }

    @Test fun periodicFlushPreservesCumulativeScrobbleDurationAndSingleStart() {
        var now = 0L
        val starts = mutableListOf<String>()
        val tracker = PlaybackStatsTracker({ it.id.toString() }, nowElapsedMs = { now }, onPlayStarted = { _, id -> starts += id })
        tracker.onSongChanged(song())
        tracker.onPlayingChanged(true)
        tracker.reportPlayStarted(song())
        now = 15_000
        assertEquals(15_000L, tracker.flushPeriodic()!!.listenedMs)
        now = 30_000
        val counted = tracker.flushPeriodic()!!
        assertEquals(30_000L, counted.playListenedMs)
        assertEquals(1, counted.playCountIncrement)
        assertEquals(starts.single(), counted.playSessionId)
        tracker.onPlayingChanged(false)
        tracker.onPlayingChanged(true)
        assertEquals(1, starts.size)
    }

    @Test fun nextSongGetsNewStartSession() {
        var now = 0L
        val starts = mutableListOf<String>()
        val tracker = PlaybackStatsTracker({ it.id.toString() }, nowElapsedMs = { now }, onPlayStarted = { _, id -> starts += id })
        tracker.onSongChanged(song())
        tracker.onPlayingChanged(true)
        tracker.reportPlayStarted(song())
        now = 35_000
        val prior = tracker.onSongChanged(song(2))!!
        tracker.onPlayingChanged(true)
        tracker.reportPlayStarted(song(2))
        assertEquals(2, starts.size)
        assertNotEquals(starts[0], starts[1])
        assertEquals(starts[0], prior.playSessionId)
    }
}
