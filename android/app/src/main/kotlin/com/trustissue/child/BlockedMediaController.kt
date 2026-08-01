package com.trustissue.child

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Gives the blocked app time to pause naturally after losing foreground, then
 * briefly requests audio focus only if playback is still active. It does not
 * send a global media-key event, because that can permanently pause unrelated
 * music such as Spotify.
 */
class BlockedMediaController(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var temporaryFocusRequest: AudioFocusRequest? = null
    private var holdFocusUntilRelease = false
    private val releaseTemporaryFocus = Runnable { releaseFocus() }
    private val requestFallbackFocus = Runnable { requestTransientFocus() }

    fun scheduleAudioFocusFallback(): Boolean {
        holdFocusUntilRelease = false
        handler.removeCallbacks(requestFallbackFocus)
        handler.removeCallbacks(releaseTemporaryFocus)
        releaseFocus()
        handler.postDelayed(requestFallbackFocus, FOREGROUND_SETTLE_MS)
        return true
    }

    /**
     * Holds transient audio focus while an in-place blocking overlay is visible.
     * Releasing the focus lets the foreground media app resume naturally.
     */
    fun holdAudioFocus(): Boolean {
        holdFocusUntilRelease = true
        handler.removeCallbacks(requestFallbackFocus)
        handler.removeCallbacks(releaseTemporaryFocus)
        releaseFocus()
        requestTransientFocus()
        return true
    }

    private fun requestTransientFocus() {
        runCatching {
            val audioManager =
                appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!audioManager.isMusicActive) return@runCatching
            handler.removeCallbacks(releaseTemporaryFocus)
            releaseFocus()
            val focusRequest = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { }
                .build()
            temporaryFocusRequest = focusRequest
            val focusGranted =
                audioManager.requestAudioFocus(focusRequest) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (focusGranted && !holdFocusUntilRelease) {
                handler.postDelayed(releaseTemporaryFocus, FOCUS_HOLD_MS)
            } else {
                if (!focusGranted) {
                    temporaryFocusRequest = null
                }
            }
        }
    }

    fun release() {
        holdFocusUntilRelease = false
        handler.removeCallbacks(requestFallbackFocus)
        handler.removeCallbacks(releaseTemporaryFocus)
        releaseFocus()
    }

    private fun releaseFocus() {
        val request = temporaryFocusRequest ?: return
        val audioManager =
            appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { audioManager.abandonAudioFocusRequest(request) }
        temporaryFocusRequest = null
    }

    private companion object {
        const val FOREGROUND_SETTLE_MS = 350L
        const val FOCUS_HOLD_MS = 850L
    }
}
