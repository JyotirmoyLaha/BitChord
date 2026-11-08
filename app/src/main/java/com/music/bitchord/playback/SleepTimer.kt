package com.music.bitchord.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/**
 * Sleep timer. Holds a deadline; [PlaybackService] watches it and pauses
 * playback once it passes.
 *
 * A deadline rather than a countdown: nothing has to tick for the timer to
 * stay accurate, so it survives the player UI being dismissed, and any
 * observer can work out how long is left for itself. elapsedRealtime, not
 * wall clock, so changing the system time can't cut a timer short.
 */
object SleepTimer {

    /** Deadline on [SystemClock.elapsedRealtime], or null when no timer is set. */
    val deadline = MutableStateFlow<Long?>(null)

    /** The preset that was chosen, so the picker can tick it. Null when off. */
    val minutes = MutableStateFlow<Int?>(null)

    /**
     * Pause when the current track ends instead of after a fixed wait.
     *
     * Deliberately not expressed as a deadline of "duration minus position":
     * seeking, crossfade and a queue that reorders itself would all leave that
     * number wrong, whereas the track ending is an event the player reports.
     */
    val afterTrack = MutableStateFlow(false)

    /**
     * The album to finish before pausing, or null when that isn't what is set.
     *
     * This only waits for the album's last track to begin, and then becomes
     * [afterTrack], so the crossfade and Automix fade-outs that already handle
     * the sleep timer need no second path of their own.
     */
    val afterAlbum = MutableStateFlow<String?>(null)

    /** Durations offered in the picker. */
    val PRESETS = listOf(15, 30, 45, 60)

    /** The custom picker moves in steps this size, from one step up to [MAX_MINUTES]. */
    const val STEP_MINUTES = 5

    /** Longest custom duration: twelve hours. */
    const val MAX_MINUTES = 720

    /** The custom duration a slider at [fraction] (0..1) of its travel stands for. */
    fun minutesAt(fraction: Float): Int {
        val raw = STEP_MINUTES + fraction.coerceIn(0f, 1f) * (MAX_MINUTES - STEP_MINUTES)
        return (raw / STEP_MINUTES).roundToInt() * STEP_MINUTES
    }

    /** Where [minutes] sits along the custom slider, the inverse of [minutesAt]. */
    fun fractionOf(minutes: Int): Float =
        (minutes - STEP_MINUTES).toFloat() / (MAX_MINUTES - STEP_MINUTES)

    /** Whether any kind of timer is currently armed. */
    val isRunning: Boolean get() = deadline.value != null || afterTrack.value || afterAlbum.value != null

    fun start(minutes: Int) {
        afterTrack.value = false
        afterAlbum.value = null
        this.minutes.value = minutes
        deadline.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    /** Pause once the track playing right now finishes. */
    fun startAfterTrack() {
        minutes.value = null
        deadline.value = null
        afterAlbum.value = null
        afterTrack.value = true
    }

    /** Pause once the album identified by [album] (see [albumKey]) finishes. */
    fun startAfterAlbum(album: String) {
        minutes.value = null
        deadline.value = null
        afterTrack.value = false
        afterAlbum.value = album
    }

    /**
     * What makes two tracks the same album, or null when a track has no album.
     * The name leads because the id is missing from more sources than it is.
     */
    fun albumKey(albumName: String?, albumId: String?): String? =
        albumName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: albumId?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Turns an album timer into "after this song" once the current track is the
     * album's last: nothing follows it, or what follows is from another album.
     * With repeat-one on the album can never end, so the song is the end.
     */
    fun promoteIfAlbumEnds(isLastInQueue: Boolean, repeatOne: Boolean, nextAlbum: String?) {
        val album = afterAlbum.value ?: return
        if (repeatOne || isLastInQueue || nextAlbum != album) startAfterTrack()
    }

    fun cancel() {
        afterAlbum.value = null
        minutes.value = null
        deadline.value = null
        afterTrack.value = false
    }

    /** How long is left, or null when no timer is running. */
    fun remainingMs(): Long? =
        deadline.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
}
