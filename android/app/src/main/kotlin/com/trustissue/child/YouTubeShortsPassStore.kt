package com.trustissue.child

import android.content.Context
import android.os.SystemClock

/**
 * Stores only an opaque fingerprint and a short-lived elapsed-realtime stamp.
 * No YouTube labels, titles, creator names, or screen content are persisted.
 */
object YouTubeShortsPassStore {
    private const val prefsName = "trustissue_youtube_shorts_pass"
    private const val fingerprintKey = "fingerprint"
    private const val grantedAtElapsedKey = "grantedAtElapsedMs"
    private const val grantedAtItemSequenceKey = "grantedAtItemSequence"

    fun grant(
        context: Context,
        fingerprint: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        itemSequence: Long = 0L
    ): Boolean {
        if (fingerprint.isBlank()) return false
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(fingerprintKey, fingerprint)
            .putLong(grantedAtElapsedKey, nowElapsedMs)
            .putLong(grantedAtItemSequenceKey, itemSequence)
            .commit()
    }

    fun read(
        context: Context,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): YouTubeShortsPassPolicy.Pass? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val fingerprint = prefs.getString(fingerprintKey, "").orEmpty()
        val grantedAt = prefs.getLong(grantedAtElapsedKey, -1L)
        val grantedAtItemSequence = prefs.getLong(grantedAtItemSequenceKey, 0L)
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = fingerprint,
            grantedAtElapsedMs = grantedAt,
            grantedAtItemSequence = grantedAtItemSequence
        )
        return if (
            fingerprint.isNotBlank() &&
            !YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = null,
                pass = pass,
                shortsPagerScrolled = false,
                nowElapsedMs = nowElapsedMs
            )
        ) {
            pass
        } else {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

/**
 * Instagram Reels gets an isolated pass store so a pass can never cross
 * between YouTube and Instagram.
 */
object InstagramReelsPassStore {
    private const val prefsName = "trustissue_instagram_reels_pass"
    private const val fingerprintKey = "fingerprint"
    private const val grantedAtElapsedKey = "grantedAtElapsedMs"
    private const val grantedAtItemSequenceKey = "grantedAtItemSequence"

    fun grant(
        context: Context,
        fingerprint: String,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        itemSequence: Long = 0L
    ): Boolean {
        if (fingerprint.isBlank()) return false
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(fingerprintKey, fingerprint)
            .putLong(grantedAtElapsedKey, nowElapsedMs)
            .putLong(grantedAtItemSequenceKey, itemSequence)
            .commit()
    }

    fun read(
        context: Context,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): YouTubeShortsPassPolicy.Pass? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val fingerprint = prefs.getString(fingerprintKey, "").orEmpty()
        val grantedAt = prefs.getLong(grantedAtElapsedKey, -1L)
        val grantedAtItemSequence = prefs.getLong(grantedAtItemSequenceKey, 0L)
        val pass = YouTubeShortsPassPolicy.Pass(
            fingerprint = fingerprint,
            grantedAtElapsedMs = grantedAt,
            grantedAtItemSequence = grantedAtItemSequence
        )
        return if (
            fingerprint.isNotBlank() &&
            !YouTubeShortsPassPolicy.shouldBlock(
                currentFingerprint = null,
                pass = pass,
                shortsPagerScrolled = false,
                nowElapsedMs = nowElapsedMs
            )
        ) {
            pass
        } else {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
