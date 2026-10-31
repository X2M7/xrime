/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import java.security.MessageDigest

/** In-memory identity only: clipboard events revoke an earlier choice before admission. */
internal class ClipboardRepeatState {
    internal data class Selection(val id: Int, val digest: String, val generation: Long)
    internal sealed interface Snapshot {
        data object Unavailable : Snapshot
        data class Chosen(val selection: Selection) : Snapshot
    }

    private var generation = 0L
    private var enabled = false
    private var selection: Selection? = null
    private var explicitSelection = false
    private var previousGeneration = 0L
    private var previousSelection: Selection? = null
    private var previousExplicit = false
    private var pendingAccepted: Selection? = null
    private var priorAliases = emptySet<Long>()
    private val aliases = linkedSetOf<Long>()
    private val rowMutations = mutableMapOf<Int, Int>()
    private var allMutations = 0

    @Synchronized fun setEnabled(value: Boolean) {
        generation++
        enabled = value
        selection = null
        explicitSelection = false
        previousSelection = null
        pendingAccepted = null
        aliases.clear()
        priorAliases = emptySet()
    }

    @Synchronized fun invalidate(): Long {
        previousGeneration = generation
        previousSelection = selection
        previousExplicit = explicitSelection
        pendingAccepted = null
        priorAliases = aliases.toSet()
        aliases.clear()
        generation++
        selection = null
        explicitSelection = false
        return generation
    }

    @Synchronized fun accepted(event: Long, id: Int, digest: String) {
        if (!enabled || id <= 0 || allMutations != 0 || id in rowMutations) return
        if ((generation == event || event in aliases) && !explicitSelection) {
            selection = Selection(id, digest, generation)
        } else if ((event == previousGeneration || event in priorAliases) && !previousExplicit) {
            // This is not visible to callers until a matching duplicate is proven.
            pendingAccepted = Selection(id, digest, previousGeneration)
        }
    }

    /** Reconcile only a verified repeat delivery of the immediately preceding platform event. */
    @Synchronized fun coalesce(previous: Long, current: Long, verifiedDigest: String): Boolean {
        if (!enabled || current != generation || previous != previousGeneration || allMutations != 0) return false
        aliases.addAll(priorAliases)
        aliases.add(previous)
        while (aliases.size > 64) aliases.remove(aliases.first())
        val old = previousSelection ?: pendingAccepted?.takeIf { it.digest == verifiedDigest }
        if (old != null && old.id !in rowMutations) {
            selection = old.copy(generation = current)
            explicitSelection = previousExplicit
        }
        previousSelection = null
        pendingAccepted = null
        return true
    }

    @Synchronized fun event(): Long = generation

    @Synchronized fun choose(event: Long, id: Int, digest: String): Boolean {
        if (!enabled || event != generation || id <= 0 || allMutations != 0 || id in rowMutations) return false
        selection = Selection(id, digest, generation)
        explicitSelection = true
        previousSelection = null
        pendingAccepted = null
        return true
    }

    @Synchronized fun snapshot(): Snapshot = when {
        !enabled -> Snapshot.Unavailable
        selection != null -> Snapshot.Chosen(checkNotNull(selection))
        else -> Snapshot.Unavailable
    }

    @Synchronized fun accepts(candidate: Selection): Boolean {
        val current = selection ?: return false
        return enabled && allMutations == 0 && candidate.id !in rowMutations &&
            (candidate.generation == generation || candidate.generation in aliases) &&
            current.id == candidate.id && current.digest == candidate.digest && current.generation == generation
    }

    @Synchronized fun beginRowMutation(id: Int) {
        rowMutations[id] = (rowMutations[id] ?: 0) + 1
        if (selection == null || selection?.id == id || previousSelection?.id == id) invalidate()
    }

    @Synchronized fun endRowMutation(id: Int) {
        val count = checkNotNull(rowMutations[id])
        if (count == 1) rowMutations.remove(id) else rowMutations[id] = count - 1
    }

    @Synchronized fun beginAllMutation(invalidateEvent: Boolean = true) {
        allMutations++
        if (invalidateEvent || selection != null || previousSelection != null || pendingAccepted != null) invalidate()
    }

    @Synchronized fun endAllMutation() {
        check(allMutations > 0)
        allMutations--
    }
}

internal fun clipboardRepeatDigest(text: String): String {
    val bytes = ByteArray(text.length * 2)
    text.forEachIndexed { index, char ->
        bytes[index * 2] = (char.code ushr 8).toByte()
        bytes[index * 2 + 1] = char.code.toByte()
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
