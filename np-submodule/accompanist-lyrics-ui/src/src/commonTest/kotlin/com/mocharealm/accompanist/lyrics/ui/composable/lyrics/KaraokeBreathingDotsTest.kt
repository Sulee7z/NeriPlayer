package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeBreathingDotsTest {

    @Test
    fun `resolveBreathingDotsBreatheScale keeps intro handoff anchored at minimum scale`() {
        assertEquals(
            0.8f,
            resolveBreathingDotsBreatheScale(
                timeInPhaseMs = 0f,
                phaseDurationMs = 600f,
                preferredCycleDurationMs = 3000
            ),
            0.0001f
        )
    }

    @Test
    fun `resolveBreathingDotsBreatheScale reaches full scale before pre-exit on short interlude`() {
        assertEquals(
            1f,
            resolveBreathingDotsBreatheScale(
                timeInPhaseMs = 600f,
                phaseDurationMs = 600f,
                preferredCycleDurationMs = 3000
            ),
            0.0001f
        )
    }

    @Test
    fun `resolveBreathingDotsBreatheScale reaches full scale before pre-exit on long interlude`() {
        assertEquals(
            1f,
            resolveBreathingDotsBreatheScale(
                timeInPhaseMs = 5_000f,
                phaseDurationMs = 5_000f,
                preferredCycleDurationMs = 3_000
            ),
            0.0001f
        )
    }
}
