package com.trustissue.child

/**
 * A motivation is selected once for each block attempt and passed to both the
 * instant overlay and the Activity. It never rotates while that gate is open,
 * so the hand-off remains visually stable.
 */
object GateMotivation {
    const val illustrationAsset =
        "flutter_assets/assets/images/focus_session.png"

    private val quotes = listOf(
        "The next right choice is the one that matters.",
        "Protect this minute. Your future gets the benefit.",
        "A small pause now keeps the bigger promise intact.",
        "Stay with the plan you made for yourself.",
        "Focus is choosing what deserves this moment.",
        "Let the urge pass. Keep the progress."
    )

    fun indexFor(packageName: String, nonce: Long): Int {
        val mixed = 31L * packageName.hashCode().toLong() + nonce
        return Math.floorMod(mixed, quotes.size.toLong()).toInt()
    }

    fun quote(index: Int): String {
        return quotes[Math.floorMod(index, quotes.size)]
    }
}
