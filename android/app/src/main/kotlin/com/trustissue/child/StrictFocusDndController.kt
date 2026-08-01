package com.trustissue.child

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** Keeps Android Do Not Disturb active only for an active Strict Focus session. */
object StrictFocusDndController {
    private const val prefsName = "strict_focus_dnd_state"
    private const val managedKey = "managed"
    private const val previousFilterKey = "previousFilter"
    private const val changedFilterKey = "changedFilter"
    private const val changedPolicyKey = "changedPolicy"

    private const val previousPolicyPrefix = "previousPolicy"
    private const val appliedPolicyPrefix = "appliedPolicy"

    private val strictVisualEffects: Int
        get() = NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or
            NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS or
            NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
            NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR or
            NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE or
            NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT or
            NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST

    fun hasPolicyAccess(context: Context): Boolean {
        return runCatching {
            notificationManager(context).isNotificationPolicyAccessGranted
        }.getOrDefault(false)
    }

    /**
     * Applies Alarms-only DND and hides visual effects from intercepted
     * notifications. Notifications are not deleted; Android can show them
     * again after the Strict rule is released.
     */
    @Synchronized
    fun activate(context: Context): Boolean {
        val appContext = context.applicationContext
        val manager = notificationManager(appContext)
        if (!manager.isNotificationPolicyAccessGranted) {
            log(appContext, "W", "STRICT_DND_ACCESS_MISSING")
            return false
        }

        val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val appOwnedRule = usesAppOwnedRule(appContext)
        val alreadyManaged = prefs.getBoolean(managedKey, false)
        val currentFilter = runCatching { manager.currentInterruptionFilter }
            .getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        val currentPolicy = runCatching { manager.notificationPolicy }.getOrElse { error ->
            log(
                appContext,
                "E",
                "STRICT_DND_POLICY_READ_FAILED error=${error.javaClass.simpleName}"
            )
            return false
        }

        if (!alreadyManaged) {
            val editor = prefs.edit()
                .putBoolean(managedKey, true)
                .putInt(previousFilterKey, currentFilter)
                .putBoolean(changedFilterKey, false)
                .putBoolean(changedPolicyKey, false)
            putPolicy(editor, previousPolicyPrefix, StoredPolicy.from(currentPolicy))
            if (!editor.commit()) {
                log(appContext, "E", "STRICT_DND_STATE_SAVE_FAILED")
                return false
            }
        }

        val previousPolicy = readPolicy(prefs, previousPolicyPrefix)
        val basePolicy = if (!appOwnedRule && alreadyManaged) {
            previousPolicy?.toPlatformPolicy() ?: currentPolicy
        } else {
            currentPolicy
        }
        val strictPolicy = copyWithVisualEffects(
            basePolicy,
            basePolicy.suppressedVisualEffects or strictVisualEffects
        )
        val plannedFilterChange = appOwnedRule ||
            (currentFilter != NotificationManager.INTERRUPTION_FILTER_ALARMS &&
                currentFilter != NotificationManager.INTERRUPTION_FILTER_NONE)

        // Persist ownership before changing Android state. If the process dies
        // between the system call and the next disk write, startup cleanup can
        // still restore the original filter and policy.
        val intentEditor = prefs.edit()
            .putBoolean(changedPolicyKey, true)
            .putBoolean(
                changedFilterKey,
                prefs.getBoolean(changedFilterKey, false) || plannedFilterChange
            )
        putPolicy(intentEditor, appliedPolicyPrefix, StoredPolicy.from(strictPolicy))
        if (!intentEditor.commit()) {
            if (!alreadyManaged) clearState(prefs)
            log(appContext, "E", "STRICT_DND_INTENT_SAVE_FAILED")
            return false
        }

        var policyAppliedByThisCall = false
        var filterAppliedByThisCall = false
        return runCatching {
            manager.setNotificationPolicy(strictPolicy)
            policyAppliedByThisCall = true

            // Save the policy as normalized by Android so cleanup can detect
            // whether the user changed it during the session.
            val appliedPolicy = manager.notificationPolicy
            val appliedEditor = prefs.edit()
            putPolicy(appliedEditor, appliedPolicyPrefix, StoredPolicy.from(appliedPolicy))
            check(appliedEditor.commit()) { "Applied DND policy could not be saved" }

            if (appOwnedRule) {
                // Target-35+ apps contribute an app-owned DND rule. Calling
                // this even when another rule is already restrictive ensures
                // this app's own Strict rule is active.
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                filterAppliedByThisCall = true
            } else if (plannedFilterChange) {
                // Never weaken an existing Total-silence filter to Alarms-only.
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                filterAppliedByThisCall = true
            }

            log(
                appContext,
                "I",
                "STRICT_DND_ACTIVE previous=${prefs.getInt(previousFilterKey, currentFilter)} " +
                    "visualEffects=${appliedPolicy.suppressedVisualEffects} " +
                    "appOwnedRule=$appOwnedRule"
            )
            true
        }.getOrElse { error ->
            if (!alreadyManaged) {
                rollbackFailedActivation(
                    manager = manager,
                    appOwnedRule = appOwnedRule,
                    previousFilter = currentFilter,
                    previousPolicy = previousPolicy,
                    filterApplied = filterAppliedByThisCall,
                    policyApplied = policyAppliedByThisCall
                )
                clearState(prefs)
            }
            log(
                appContext,
                "E",
                "STRICT_DND_ACTIVATE_FAILED error=${error.javaClass.simpleName}"
            )
            false
        }
    }

    /** Releases only the DND state owned by Strict Focus. */
    @Synchronized
    fun release(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(managedKey, false)) return true

        val manager = notificationManager(appContext)
        if (!manager.isNotificationPolicyAccessGranted) {
            // Keep the marker so a later app resume can finish cleanup after
            // the user grants access again.
            log(appContext, "W", "STRICT_DND_RELEASE_PENDING access=false")
            return false
        }

        return runCatching {
            val appOwnedRule = usesAppOwnedRule(appContext)
            val previousFilter = prefs.getInt(
                previousFilterKey,
                NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val currentFilter = manager.currentInterruptionFilter
            val changedFilter = prefs.getBoolean(changedFilterKey, false)
            val changedPolicy = prefs.getBoolean(changedPolicyKey, false)
            val previousPolicy = readPolicy(prefs, previousPolicyPrefix)
            val appliedPolicy = readPolicy(prefs, appliedPolicyPrefix)
            val currentPolicy = runCatching { manager.notificationPolicy }.getOrNull()

            check(appOwnedRule || !changedPolicy || currentPolicy != null) {
                "Current DND policy could not be read for safe restoration"
            }

            if (appOwnedRule) {
                // For target-35+ apps, ALL deactivates this app's implicit rule;
                // it does not disable another app's or the user's DND rule.
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } else {
                if (
                    changedFilter &&
                    currentFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS
                ) {
                    // Restore only while the filter still matches what we applied.
                    manager.setInterruptionFilter(restorableFilter(previousFilter))
                }
                if (
                    changedPolicy &&
                    previousPolicy != null &&
                    currentPolicy != null &&
                    policyStillOwnedByStrict(currentPolicy, appliedPolicy)
                ) {
                    manager.setNotificationPolicy(previousPolicy.toPlatformPolicy())
                }
            }

            check(clearState(prefs)) { "Strict DND cleanup state could not be saved" }
            log(
                appContext,
                "I",
                "STRICT_DND_RELEASED previous=$previousFilter current=$currentFilter"
            )
            true
        }.getOrElse { error ->
            log(
                appContext,
                "E",
                "STRICT_DND_RELEASE_FAILED error=${error.javaClass.simpleName}"
            )
            false
        }
    }

    @Synchronized
    fun reconcile(context: Context): Boolean {
        val strictSessionActive =
            TrackerConfig.isStudyModeEnabled(context) &&
                TrackerConfig.isStrictFocusModeEnabled(context)
        return if (strictSessionActive) activate(context) else release(context)
    }

    private fun rollbackFailedActivation(
        manager: NotificationManager,
        appOwnedRule: Boolean,
        previousFilter: Int,
        previousPolicy: StoredPolicy?,
        filterApplied: Boolean,
        policyApplied: Boolean
    ) {
        if (filterApplied) {
            runCatching {
                manager.setInterruptionFilter(
                    if (appOwnedRule) {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    } else {
                        restorableFilter(previousFilter)
                    }
                )
            }
        }
        if (!appOwnedRule && policyApplied && previousPolicy != null) {
            runCatching { manager.setNotificationPolicy(previousPolicy.toPlatformPolicy()) }
        }
    }

    private fun copyWithVisualEffects(
        policy: NotificationManager.Policy,
        visualEffects: Int
    ): NotificationManager.Policy {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NotificationManager.Policy(
                policy.priorityCategories,
                policy.priorityCallSenders,
                policy.priorityMessageSenders,
                visualEffects,
                policy.priorityConversationSenders
            )
        } else {
            NotificationManager.Policy(
                policy.priorityCategories,
                policy.priorityCallSenders,
                policy.priorityMessageSenders,
                visualEffects
            )
        }
    }

    private fun policyStillOwnedByStrict(
        current: NotificationManager.Policy,
        applied: StoredPolicy?
    ): Boolean {
        if (applied == null) return false
        val currentStored = StoredPolicy.from(current)
        return currentStored.priorityCategories == applied.priorityCategories &&
            currentStored.priorityCallSenders == applied.priorityCallSenders &&
            currentStored.priorityMessageSenders == applied.priorityMessageSenders &&
            currentStored.priorityConversationSenders == applied.priorityConversationSenders &&
            (currentStored.suppressedVisualEffects and strictVisualEffects) ==
                (applied.suppressedVisualEffects and strictVisualEffects)
    }

    private fun notificationManager(context: Context): NotificationManager {
        return context.getSystemService(NotificationManager::class.java)
    }

    private fun usesAppOwnedRule(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 35 && context.applicationInfo.targetSdkVersion >= 35
    }

    private fun restorableFilter(filter: Int): Int {
        return when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> filter
            else -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
    }

    private fun putPolicy(
        editor: SharedPreferences.Editor,
        prefix: String,
        policy: StoredPolicy
    ) {
        editor
            .putBoolean("${prefix}Present", true)
            .putInt("${prefix}PriorityCategories", policy.priorityCategories)
            .putInt("${prefix}PriorityCallSenders", policy.priorityCallSenders)
            .putInt("${prefix}PriorityMessageSenders", policy.priorityMessageSenders)
            .putInt("${prefix}SuppressedVisualEffects", policy.suppressedVisualEffects)
            .putInt(
                "${prefix}PriorityConversationSenders",
                policy.priorityConversationSenders
            )
    }

    private fun readPolicy(prefs: SharedPreferences, prefix: String): StoredPolicy? {
        if (!prefs.getBoolean("${prefix}Present", false)) return null
        return StoredPolicy(
            priorityCategories = prefs.getInt("${prefix}PriorityCategories", 0),
            priorityCallSenders = prefs.getInt(
                "${prefix}PriorityCallSenders",
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            ),
            priorityMessageSenders = prefs.getInt(
                "${prefix}PriorityMessageSenders",
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            ),
            suppressedVisualEffects = prefs.getInt(
                "${prefix}SuppressedVisualEffects",
                0
            ),
            priorityConversationSenders = prefs.getInt(
                "${prefix}PriorityConversationSenders",
                noPriorityConversations()
            )
        )
    }

    private fun clearState(prefs: SharedPreferences): Boolean {
        return prefs.edit().clear().commit()
    }

    private fun noPriorityConversations(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NotificationManager.Policy.CONVERSATION_SENDERS_NONE
        } else {
            0
        }
    }

    private fun log(context: Context, level: String, message: String) {
        TrustIssueDebugLog.append(context, "StrictFocusDnd", level, message)
    }

    private data class StoredPolicy(
        val priorityCategories: Int,
        val priorityCallSenders: Int,
        val priorityMessageSenders: Int,
        val suppressedVisualEffects: Int,
        val priorityConversationSenders: Int
    ) {
        fun toPlatformPolicy(): NotificationManager.Policy {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                NotificationManager.Policy(
                    priorityCategories,
                    priorityCallSenders,
                    priorityMessageSenders,
                    suppressedVisualEffects,
                    priorityConversationSenders
                )
            } else {
                NotificationManager.Policy(
                    priorityCategories,
                    priorityCallSenders,
                    priorityMessageSenders,
                    suppressedVisualEffects
                )
            }
        }

        companion object {
            fun from(policy: NotificationManager.Policy): StoredPolicy {
                return StoredPolicy(
                    priorityCategories = policy.priorityCategories,
                    priorityCallSenders = policy.priorityCallSenders,
                    priorityMessageSenders = policy.priorityMessageSenders,
                    suppressedVisualEffects = policy.suppressedVisualEffects,
                    priorityConversationSenders = if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ) {
                        policy.priorityConversationSenders
                    } else {
                        0
                    }
                )
            }
        }
    }
}
