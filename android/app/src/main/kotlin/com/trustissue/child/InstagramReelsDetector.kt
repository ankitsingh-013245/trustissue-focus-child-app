package com.trustissue.child

import java.security.MessageDigest
import java.util.Locale

/**
 * Pure classifier for Instagram's Reels accessibility surface.
 *
 * Instagram calls Reels "clips" in many internal view IDs. A lone Reels
 * navigation label is deliberately insufficient because that label is also
 * present on the normal home feed.
 */
object InstagramReelsDetector {
    data class Node(
        val viewId: String = "",
        val text: String = "",
        val contentDescription: String = "",
        val className: String = "",
        val scrollable: Boolean = false,
        val selected: Boolean = false,
        val ancestorViewIds: String = ""
    )

    data class Detection(
        val isReels: Boolean,
        val confidence: Int,
        val fingerprint: String?,
        val signals: Signals
    )

    data class Signals(
        val nodeCount: Int = 0,
        val viewIdCount: Int = 0,
        val scrollableCount: Int = 0,
        val selectedCount: Int = 0,
        val strongPlayer: Boolean = false,
        val componentCount: Int = 0,
        val genericIdCount: Int = 0,
        val reelsLabel: Boolean = false,
        val selectedReelsLabel: Boolean = false,
        val actionCount: Int = 0,
        val verticalPager: Boolean = false,
        val structuralOnly: Boolean = false
    )

    private val strongPlayerIds = listOf(
        "clips_viewer",
        "clips_player",
        "clips_video",
        "clips_media",
        "clips_pager",
        "clips_view_pager",
        "reels_viewer",
        "reels_player",
        "reels_pager",
        "reels_view_pager"
    )

    private val reelsComponentIds = listOf(
        "clips_video",
        "clips_media",
        "clips_author",
        "clips_user",
        "clips_caption",
        "clips_audio",
        "clips_action",
        "reels_video",
        "reels_author",
        "reels_caption",
        "reels_audio",
        "reels_action"
    )

    private val identityIds = listOf(
        "clips_caption",
        "clips_author",
        "clips_user",
        "clips_username",
        "clips_audio",
        "reels_caption",
        "reels_author",
        "reels_user",
        "reels_username",
        "reels_audio",
        "media_caption",
        "channel_name",
        "username",
        "user_name",
        "author",
        "creator",
        "owner"
    )

    private val actionKinds = mapOf(
        "like" to listOf("like", "unlike"),
        "comments" to listOf("comment"),
        "share" to listOf("share", "send"),
        "audio" to listOf("audio", "use audio", "original audio"),
        "follow" to listOf("follow", "following"),
        "more" to listOf("more options", "more")
    )

    fun detect(
        nodes: List<Node>,
        structuralOnly: Boolean = false
    ): Detection {
        if (nodes.isEmpty()) {
            return Detection(
                isReels = false,
                confidence = 0,
                fingerprint = null,
                signals = Signals(structuralOnly = structuralOnly)
            )
        }

        val ids = nodes.map { normalize(it.viewId) }.filter(String::isNotEmpty)
        val labels = nodes.flatMap { node ->
            listOf(normalize(node.text), normalize(node.contentDescription))
        }.filter(String::isNotEmpty)
        val searchable = ids + labels

        val strongPlayer = ids.any { id ->
            strongPlayerIds.any(id::contains)
        }
        val components = reelsComponentIds.count { component ->
            ids.any { it.contains(component) }
        }
        val genericReelsIds = ids.count { id ->
            id.contains("clips_") ||
                id.contains("reels_")
        }.coerceAtMost(4)
        val reelsLabel = labels.any(::isReelsLabel)
        val selectedReelsLabel = nodes.any { node ->
            val nodeLabels =
                listOf(normalize(node.text), normalize(node.contentDescription))
            nodeLabels.any(::isReelsLabel) &&
                (
                    node.selected ||
                        nodeLabels.any { it.contains("selected") }
                    )
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
        confidence += components.coerceAtMost(3)
        confidence += genericReelsIds
        if (reelsLabel) confidence += 2
        if (selectedReelsLabel) confidence += 3
        confidence += actions.coerceAtMost(4)
        if (verticalPagerSignal) confidence += 1

        val isReels =
            strongPlayer ||
                (components >= 2 && actions >= 2) ||
                (genericReelsIds >= 2 && actions >= 3) ||
                (selectedReelsLabel && verticalPagerSignal) ||
                (
                    structuralOnly &&
                        (components >= 2 || genericReelsIds >= 2)
                    )
        return Detection(
            isReels = isReels,
            confidence = confidence,
            fingerprint = if (isReels) contentFingerprint(nodes) else null,
            signals = Signals(
                nodeCount = nodes.size,
                viewIdCount = ids.size,
                scrollableCount = nodes.count { it.scrollable },
                selectedCount = nodes.count { it.selected },
                strongPlayer = strongPlayer,
                componentCount = components,
                genericIdCount = genericReelsIds,
                reelsLabel = reelsLabel,
                selectedReelsLabel = selectedReelsLabel,
                actionCount = actions,
                verticalPager = verticalPagerSignal,
                structuralOnly = structuralOnly
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
        if (
            value == "reels" ||
            value.matches(Regex("[\\d\\s.,km+]+"))
        ) {
            return true
        }
        return actionKinds.values.flatten().any { value == it }
    }

    private fun isReelsLabel(label: String): Boolean {
        return label == "reels" ||
            label.startsWith("reels ") ||
            label.startsWith("reels,") ||
            label.contains("instagram reels")
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .take(180)
    }
}
