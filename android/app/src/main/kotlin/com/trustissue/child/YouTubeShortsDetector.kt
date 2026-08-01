package com.trustissue.child

import java.security.MessageDigest
import java.util.Locale

/**
 * Pure classifier for a snapshot of YouTube's Accessibility tree.
 *
 * YouTube's internal view hierarchy is not a public API, so the detector uses
 * multiple independent signals and deliberately ignores a lone "Shorts"
 * navigation label. The service supplies only an in-memory snapshot and never
 * persists the labels that were observed.
 */
object YouTubeShortsDetector {
    data class Node(
        val viewId: String = "",
        val text: String = "",
        val contentDescription: String = "",
        val className: String = "",
        val scrollable: Boolean = false,
        val ancestorViewIds: String = ""
    )

    data class Detection(
        val isShorts: Boolean,
        val confidence: Int,
        val fingerprint: String?,
        val signals: Signals
    )

    data class Signals(
        val nodeCount: Int = 0,
        val viewIdCount: Int = 0,
        val scrollableCount: Int = 0,
        val strongPlayer: Boolean = false,
        val componentCount: Int = 0,
        val genericIdCount: Int = 0,
        val shortsLabel: Boolean = false,
        val actionCount: Int = 0,
        val verticalPager: Boolean = false
    )

    private val strongPlayerIds = listOf(
        "reel_player_page",
        "reel_player_fragment",
        "reel_player_container",
        "reel_recycler",
        "reel_watch",
        "shorts_player",
        "shorts_video",
        "shorts_pager"
    )

    private val reelComponentIds = listOf(
        "reel_player",
        "reel_video",
        "reel_metadata",
        "reel_channel",
        "reel_footer",
        "reel_action",
        "shorts_player",
        "shorts_video"
    )

    private val identityIds = listOf(
        "video_title",
        "reel_title",
        "shorts_title",
        "channel_name",
        "channel_title",
        "author",
        "creator",
        "video_metadata",
        "reel_video_metadata"
    )

    private val actionKinds = mapOf(
        "like" to listOf("like", "dislike"),
        "comments" to listOf("comment"),
        "share" to listOf("share"),
        "remix" to listOf("remix"),
        "sound" to listOf("sound", "audio"),
        "subscribe" to listOf("subscribe", "subscription")
    )

    fun detect(nodes: List<Node>): Detection {
        if (nodes.isEmpty()) {
            return Detection(false, 0, null, Signals())
        }

        val ids = nodes.map { normalize(it.viewId) }.filter(String::isNotEmpty)
        val labels = nodes.flatMap { node ->
            listOf(normalize(node.text), normalize(node.contentDescription))
        }.filter(String::isNotEmpty)
        val searchable = ids + labels

        val strongPlayer = ids.any { id ->
            strongPlayerIds.any(id::contains)
        }
        val reelComponents = reelComponentIds.count { component ->
            ids.any { it.contains(component) }
        }
        val genericReelIds = ids.count { id ->
            id.contains("reel_") || id.contains("shorts_")
        }.coerceAtMost(4)
        val shortsLabel = labels.any { label ->
            label == "shorts" ||
                label.startsWith("shorts ") ||
                label.contains("youtube shorts")
        }
        val actions = actionKinds.count { (_, aliases) ->
            searchable.any { value -> aliases.any(value::contains) }
        }
        val verticalPagerSignal = nodes.any { node ->
            node.scrollable &&
                normalize(node.className).let { className ->
                    className.contains("recyclerview") ||
                        className.contains("viewpager")
                }
        }

        var confidence = 0
        if (strongPlayer) confidence += 6
        confidence += reelComponents.coerceAtMost(3)
        confidence += genericReelIds
        if (shortsLabel) confidence += 2
        confidence += actions.coerceAtMost(4)
        if (verticalPagerSignal) confidence += 1

        val isShorts =
            strongPlayer ||
                (reelComponents >= 2 && actions >= 2) ||
                (genericReelIds >= 2 && actions >= 3) ||
                (shortsLabel && verticalPagerSignal && actions >= 4)
        return Detection(
            isShorts = isShorts,
            confidence = confidence,
            fingerprint = if (isShorts) contentFingerprint(nodes) else null,
            signals = Signals(
                nodeCount = nodes.size,
                viewIdCount = ids.size,
                scrollableCount = nodes.count { it.scrollable },
                strongPlayer = strongPlayer,
                componentCount = reelComponents,
                genericIdCount = genericReelIds,
                shortsLabel = shortsLabel,
                actionCount = actions,
                verticalPager = verticalPagerSignal
            )
        )
    }

    private fun contentFingerprint(nodes: List<Node>): String? {
        val identity = linkedSetOf<String>()
        for (node in nodes) {
            val id = normalize(node.viewId)
            val identityScope = "$id ${normalize(node.ancestorViewIds)}"
            if (identityIds.none(identityScope::contains)) continue
            val value = normalize(node.text).ifEmpty {
                normalize(node.contentDescription)
            }
            if (value.length < 3 || isGenericControl(value)) continue
            identity += "${id.substringAfterLast('/')}:$value"
        }
        if (identity.isEmpty()) return null
        val stable = identity.sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun isGenericControl(value: String): Boolean {
        if (value == "shorts" || value.matches(Regex("[\\d\\s.,km+]+"))) {
            return true
        }
        return actionKinds.values.flatten().any { value == it }
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .take(180)
    }
}
