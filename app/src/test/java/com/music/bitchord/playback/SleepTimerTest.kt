package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {

    @Test
    fun customSliderSpansOneStepToTwelveHours() {
        assertEquals(SleepTimer.STEP_MINUTES, SleepTimer.minutesAt(0f))
        assertEquals(SleepTimer.MAX_MINUTES, SleepTimer.minutesAt(1f))
        // Dragged past either end, it stays inside the range.
        assertEquals(SleepTimer.STEP_MINUTES, SleepTimer.minutesAt(-0.5f))
        assertEquals(SleepTimer.MAX_MINUTES, SleepTimer.minutesAt(1.5f))
    }

    @Test
    fun customSliderLandsOnWholeSteps() {
        for (i in 0..100) {
            assertEquals(0, SleepTimer.minutesAt(i / 100f) % SleepTimer.STEP_MINUTES)
        }
    }

    @Test
    fun fractionOfIsTheInverseOfMinutesAt() {
        for (minutes in SleepTimer.STEP_MINUTES..SleepTimer.MAX_MINUTES step SleepTimer.STEP_MINUTES) {
            assertEquals(minutes, SleepTimer.minutesAt(SleepTimer.fractionOf(minutes)))
        }
    }

    @Test
    fun albumTimerWaitsWhileTheNextTrackIsOnTheSameAlbum() {
        SleepTimer.startAfterAlbum("blue")
        SleepTimer.promoteIfAlbumEnds(isLastInQueue = false, repeatOne = false, nextAlbum = "blue")
        assertEquals("blue", SleepTimer.afterAlbum.value)
        assertEquals(false, SleepTimer.afterTrack.value)
        SleepTimer.cancel()
    }

    @Test
    fun albumTimerBecomesAfterThisSongOnTheLastTrack() {
        for ((last, repeatOne, next) in listOf(
            Triple(false, false, "other"),
            Triple(false, false, null),
            Triple(true, false, null),
            Triple(false, true, "blue"),
        )) {
            SleepTimer.startAfterAlbum("blue")
            SleepTimer.promoteIfAlbumEnds(isLastInQueue = last, repeatOne = repeatOne, nextAlbum = next)
            assertEquals(null, SleepTimer.afterAlbum.value)
            assertEquals(true, SleepTimer.afterTrack.value)
            SleepTimer.cancel()
        }
    }

    @Test
    fun aTrackWithNoAlbumHasNoKey() {
        assertEquals(null, SleepTimer.albumKey(" ", null))
        assertEquals("blue", SleepTimer.albumKey(" Blue ", "id"))
        assertEquals("id", SleepTimer.albumKey(null, "id"))
    }
}
