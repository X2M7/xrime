/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.util.WeakHashSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.systemservices.clipboardManager
import timber.log.Timber

object ClipboardHelper :
    ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: Database
    private lateinit var clbDao: DatabaseDao

    internal fun interface OnClipboardUpdateListener {
        fun onUpdate(update: ClipboardSuggestionState.Update<DatabaseBean>)
    }

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListenersLock = Any()
    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    internal fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        synchronized(onUpdateListenersLock) { onUpdateListeners.add(listener) }
    }

    internal fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        synchronized(onUpdateListenersLock) { onUpdateListeners.remove(listener) }
    }

    private val clipPref = AppPrefs.defaultInstance().clipboard

    private val enabledPref = clipPref.clipboardListening
    private val listenerRegistrationLock = Any()
    private val privateCopyTickets = mutableSetOf<PrivateCopyHistoryTicket>()

    @Volatile
    private var listenerRegistered = false

    private val delayedPrivateClipboardQuarantine = DelayedPrivateClipboardQuarantine()
    private val imageEvents = ClipboardImageEventGeneration()
    private val repeatState = ClipboardRepeatState()
    private data class RepeatEventIdentity(val timestamp: Long, val digest: String, val epoch: Long, val event: Long)
    private val repeatIdentityLock = Any()
    private var lastRepeatIdentity: RepeatEventIdentity? = null
    private val suggestionState = ClipboardSuggestionState<DatabaseBean> { lastBean = it }

    private fun setClipboardListening(enabled: Boolean) {
        var cleared: ClipboardSuggestionState.Update<DatabaseBean>? = null
        synchronized(listenerRegistrationLock) {
            if (enabled == listenerRegistered) return
            if (enabled) {
                clipboardManager.addPrimaryClipChangedListener(this)
                suggestionState.setEnabled(true)
                listenerRegistered = true
                repeatState.setEnabled(true)
                imageEvents.enable()
            } else {
                repeatState.setEnabled(false)
                privateCopyTickets.forEach(PrivateCopyHistoryTicket::cancel)
                imageEvents.disable()
                listenerRegistered = false
                cleared = suggestionState.setEnabled(false)
                clipboardManager.removePrimaryClipChangedListener(this)
            }
        }
        cleared?.let(::notifySuggestionChanged)
    }

    @Keep
    private val enabledListener = PreferenceDelegate.OnChangeListener<Boolean> { _, value ->
        setClipboardListening(value)
    }

    private val limitPref = clipPref.clipboardLimit

    @Keep
    private val limitListener = PreferenceDelegate.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    // Accessed inside mutex: observe preference changes without repeatedly compiling regexes.
    private val clipboardRules = ClipboardRulesCache()

    @Volatile
    var lastBean: DatabaseBean? = null

    private fun notifySuggestionChanged(update: ClipboardSuggestionState.Update<DatabaseBean>) {
        val listeners = synchronized(onUpdateListenersLock) { onUpdateListeners.toList() }
        listeners.forEach { it.onUpdate(update) }
    }

    internal fun isCurrentSuggestion(update: ClipboardSuggestionState.Update<DatabaseBean>): Boolean =
        suggestionState.isCurrent(update)

    private fun clearCapturedSuggestion(admission: Long) {
        suggestionState.clear(admission)?.let(::notifySuggestionChanged)
    }

    /** Drop in-memory clipboard content and tell active input bars to hide their suggestion. */
    fun clearTransientSuggestion() {
        repeatState.invalidate()
        notifySuggestionChanged(suggestionState.clear())
    }

    internal fun repeatEvent(): Long = repeatState.event()
    internal fun invalidateRepeat(): Long = repeatState.invalidate()
    internal fun repeatSnapshot(): ClipboardRepeatState.Snapshot = repeatState.snapshot()
    internal fun repeatAccepts(selection: ClipboardRepeatState.Selection): Boolean = repeatState.accepts(selection)
    internal fun chooseForRepeat(event: Long, id: Int, text: String): Boolean {
        if (text.length > 65536) return false
        val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: return false
        val digest = clipboardRepeatDigest(text)
        return PrivacyModeState.claimIfOrdinaryEpoch(epoch) { repeatState.choose(event, id, digest) }
    }

    /**
     * Publish app-owned sensitive content and suppress the resulting local callback on API 21+.
     * Android's standard sensitive marker is also attached where ClipDescription supports it.
     */
    fun setSensitivePrimaryClip(
        manager: ClipboardManager,
        clip: ClipData,
    ) {
        if (listenerRegistered) {
            AppOwnedSensitiveClipboard.setPrimaryClip(manager, clip)
        } else {
            AppOwnedSensitiveClipboard.discardPending()
            manager.setPrimaryClip(SensitiveClipboardCompat.markSensitive(clip))
        }
    }

    /**
     * Snapshot the current private clipboard boundary before process privacy is released.
     * Only a SHA-256 identity, plus the platform timestamp where available, is retained.
     */
    fun quarantineCurrentPrimaryClip() {
        val timestampsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val clip = try {
            clipboardManager.primaryClip
        } catch (failure: RuntimeException) {
            if (failure is kotlinx.coroutines.CancellationException || failure is PersistencePermitExpiredException) throw failure
            delayedPrivateClipboardQuarantine.arm(
                fingerprint = null,
                timestamp = null,
                timestampsSupported = timestampsSupported,
            )
            return
        }
        if (clip == null) {
            delayedPrivateClipboardQuarantine.clear()
            return
        }
        delayedPrivateClipboardQuarantine.arm(
            fingerprint = ClipboardContentIdentity.sha256OrNull(clip),
            timestamp = clip.timestampOrNull(),
            timestampsSupported = timestampsSupported,
        )
    }

    private fun ClipData.timestampOrNull(): Long? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ClipboardReadSafety.readOrNull { description.timestamp }?.takeIf { it > 0L }
    } else {
        null
    }

    private fun publishCapturedBean(
        bean: DatabaseBean,
        permit: Long,
        admission: Long,
        replaceId: Int? = null,
    ) {
        var update: ClipboardSuggestionState.Update<DatabaseBean>? = null
        PrivacyModeState.publishIfOrdinary(permit) {
            val epoch = checkNotNull(PrivacyModeState.observeOrdinaryPersistence())
            update = suggestionState.publish(admission, bean, epoch) { replaceId == null || lastBean?.id == replaceId }
        }
        update?.let(::notifySuggestionChanged)
    }

    fun init(
        context: Context,
        persistHistory: Boolean = true,
    ) {
        clbDb =
            (
                if (persistHistory) {
                    Room.databaseBuilder(context, Database::class.java, "clipboard.db")
                } else {
                    Room.inMemoryDatabaseBuilder(context, Database::class.java)
                }
                )
                .addMigrations(Database.MIGRATION_3_4, Database.MIGRATION_4_5, Database.MIGRATION_5_6)
                .build()
        clbDao = clbDb.databaseDao()
        try {
            ClipboardImageHistory.initialize(context, clbDb, persistHistory)
        } catch (_: Exception) {
            // A storage failure must not prevent the existing text clipboard from starting.
            Timber.w("Image history storage is unavailable")
        }
        launch {
            try {
                ClipboardImageHistory.recoverAndEnable()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Timber.w("Image history recovery is unavailable")
            }
        }
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allBeans() = clbDao.allBeans()

    fun searchBeans(query: String): androidx.paging.PagingSource<Int, DatabaseBean> {
        require(query.length <= MAX_CLIPBOARD_SEARCH_UNITS) { "Clipboard search query exceeds limit" }
        return if (query.isEmpty()) clbDao.allBeans() else clbDao.searchBeans(query)
    }

    internal fun acquirePrivateCopyTicket(): PrivateCopyHistoryTicket? = synchronized(listenerRegistrationLock) {
        if (!listenerRegistered || com.osfans.trime.TrimeApplication.getInstance().isDirectBootMode) return@synchronized null
        PrivateCopyHistoryTicket().also(privateCopyTickets::add)
    }

    internal fun releasePrivateCopyTicket(ticket: PrivateCopyHistoryTicket) {
        ticket.cancel()
        synchronized(listenerRegistrationLock) { privateCopyTickets.remove(ticket) }
    }

    /** Store an explicitly selected text value without reading or writing Android's clipboard. */
    internal suspend fun savePrivateCopy(text: String, epoch: Long, ticket: PrivateCopyHistoryTicket, repeatEvent: Long, allowed: () -> Boolean): Boolean {
        if (!listenerRegistered || text.isEmpty() || text.length > 65536) return false
        val permit = clipboardPersistenceGate.acquire(isSensitive = false, observedEpoch = epoch) ?: return false
        try {
            return mutex.withLock {
                val rules = clipboardRules.get(clipPref.clipboardCompareRules.getValue(), clipPref.clipboardOutputRules.getValue())
                    ?: return@withLock false
                if (!rules.accepts(text)) return@withLock false
                val existing = clbDao.find(text)
                val now = System.currentTimeMillis()
                val saved = clbDb.withTransaction {
                    clipboardPersistenceGate.guardTransaction(permit) {
                        if (!listenerRegistered || !PrivacyModeState.claimIfOrdinaryEpoch(epoch) { allowed() && ticket.claimWrite() }) return@guardTransaction false
                        if (existing != null) {
                            clbDao.updateTime(existing.id, now)
                        } else {
                            clbDao.insert(DatabaseBean(text = text, time = now))
                        }
                        true
                    }
                }
                if (saved) {
                    removeOutdated()
                    updateItemCount()
                    val id = clbDao.find(text)?.id
                    val digest = clipboardRepeatDigest(text)
                    if (id != null) PrivacyModeState.publishIfOrdinary(permit) {
                        if (listenerRegistered && allowed()) repeatState.accepted(repeatEvent, id, digest)
                    }
                }
                saved
            }
        } finally {
            clipboardPersistenceGate.release(permit)
        }
    }

    suspend fun pin(id: Int) = clbDao.updatePinned(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinned(id, false)

    private suspend fun <T> withRepeatRowMutation(id: Int, block: suspend () -> T): T {
        repeatState.beginRowMutation(id)
        try { return block() } finally { repeatState.endRowMutation(id) }
    }

    private suspend fun <T> withRepeatAllMutation(invalidateEvent: Boolean = true, block: suspend () -> T): T {
        repeatState.beginAllMutation(invalidateEvent)
        try { return block() } finally { repeatState.endAllMutation() }
    }

    /** Missing rows are a normal edit outcome; only a committed single-row update is published. */
    suspend fun updateText(
        id: Int,
        text: String,
    ): TextUpdateResult {
        val permit = clipboardPersistenceGate.acquire(isSensitive = false)
            ?: throw PersistencePermitExpiredException()
        try {
            return withRepeatRowMutation(id) { mutex.withLock {
                val existing = clbDao.get(id) ?: return@withLock TextUpdateResult.NOT_FOUND
                val updated = existing.copy(text = text)
                val outcome = clbDb.withTransaction {
                    clipboardPersistenceGate.guardTransaction(permit) {
                        when (val affected = clbDao.updateText(id, text)) {
                            0 -> TextUpdateResult.NOT_FOUND
                            1 -> TextUpdateResult.SAVED
                            else -> error("Unexpected edited row count: $affected")
                        }
                    }
                }
                if (outcome == TextUpdateResult.SAVED) {
                    val admission = suggestionState.admit()
                    if (admission != null) publishCapturedBean(updated, permit, admission, replaceId = id)
                }
                outcome
            } }
        } finally {
            clipboardPersistenceGate.release(permit)
        }
    }

    suspend fun delete(id: Int) {
        withRepeatRowMutation(id) {
            clbDao.delete(id)
            updateItemCount()
        }
    }

    suspend fun deleteAll(skipUnpinned: Boolean = true) {
        withRepeatAllMutation {
            if (skipUnpinned) {
                clbDao.deleteAllUnpinned()
            } else {
                clbDao.deleteAll()
            }
            updateItemCount()
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    /**
     * 此方法设置监听剪贴板变化，如有新的剪贴内容，就启动选定的剪贴板管理器
     *
     * 比较规则去除后为空或完整匹配输出规则的内容不会保存。规则错误时不保存，直到修正。
     */
    override fun onPrimaryClipChanged() {
        val repeatEvent = repeatState.invalidate()
        // Invalidate prior image reads even for disabled, empty or sensitive clipboard events.
        val imageEvent = imageEvents.advance()
        val admission = suggestionState.admit() ?: run {
            AppOwnedSensitiveClipboard.discardPending()
            return
        }
        val observedEpoch = PrivacyModeState.observeOrdinaryPersistence()
        if (observedEpoch == null) {
            AppOwnedSensitiveClipboard.discardPending()
            clearCapturedSuggestion(admission)
            return
        }
        // Snapshot the event and privacy epoch before the only system clipboard read.
        var imageAuthorization = imageEvents.acquireAuthorization(imageEvent) {
            listenerRegistered && PrivacyModeState.isOrdinaryEpoch(observedEpoch)
        }
        try {
            val clip = try {
                clipboardManager.primaryClip
            } catch (failure: RuntimeException) {
                if (failure is kotlinx.coroutines.CancellationException || failure is PersistencePermitExpiredException) throw failure
                AppOwnedSensitiveClipboard.discardPending()
                clearCapturedSuggestion(admission)
                return
            } ?: run {
                    AppOwnedSensitiveClipboard.discardPending()
                    delayedPrivateClipboardQuarantine.clear()
                    return
                }
            val fingerprint = ClipboardContentIdentity.sha256OrNull(clip)
            val appOwnedSensitive = AppOwnedSensitiveClipboard.consumeIfOwned(fingerprint)
            val delayedPrivate =
                delayedPrivateClipboardQuarantine.shouldSuppress(
                    fingerprint = fingerprint,
                    timestamp = clip.timestampOrNull(),
                )
            if (appOwnedSensitive || delayedPrivate) {
                clearCapturedSuggestion(admission)
                return
            }
            if (ClipboardReadSafety.sensitiveOrUnreadable(clip)) {
                clearCapturedSuggestion(admission)
                return
            }
            // The platform can deliver the same clipboard update more than once.
            // Every callback first revoked the old selection. Restore it only after
            // proving this is the same ordinary, bounded text event in the same epoch.
            val repeatTimestamp = clip.timestampOrNull()
            val repeatDigest = ClipboardReadSafety.readOrNull {
                if (clip.itemCount != 1) return@readOrNull null
                val item = clip.getItemAt(0)
                if (item.uri != null || item.intent != null) return@readOrNull null
                val value = item.text ?: return@readOrNull null
                if (value.length > 65536) return@readOrNull null
                val text = value.toString()
                if (text.length > 65536) null else clipboardRepeatDigest(text)
            }
            if (repeatTimestamp != null && repeatDigest != null) {
                PrivacyModeState.claimIfOrdinaryEpoch(observedEpoch) {
                    synchronized(repeatIdentityLock) {
                        val previous = lastRepeatIdentity
                        if (previous != null && previous.timestamp == repeatTimestamp && previous.digest == repeatDigest &&
                            previous.epoch == observedEpoch
                        ) repeatState.coalesce(previous.event, repeatEvent, repeatDigest)
                        lastRepeatIdentity = RepeatEventIdentity(repeatTimestamp, repeatDigest, observedEpoch, repeatEvent)
                    }
                    true
                }
            }
            val imageCapture = ClipboardImageHistory.captureOrNull()
            val imageAuth = imageAuthorization
            if (imageCapture != null && imageAuth != null && mayContainImageUri(clip)) {
                // tryStart takes ownership, including when its pre-scheduling slot limit rejects.
                imageAuthorization = null
                try {
                    imageCapture.tryStart(this, clip, imageAuth, onResult = {}, onFailure = {})
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A scheduling failure must not interrupt the independent text history path.
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timestamp = ClipboardReadSafety.readOrNull { clip.description.timestamp } ?: return
                if (timestamp == lastClipTimestamp) return
                lastClipTimestamp = timestamp
            } else {
                val timestamp = System.currentTimeMillis()
                val hash = clip.hashCode()
                if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
                lastClipTimestamp = timestamp
                lastClipHash = hash
            }
            val permit = clipboardPersistenceGate.acquire(
                isSensitive = false,
                observedEpoch = observedEpoch,
            ) ?: run {
                clearCapturedSuggestion(admission)
                return
            }
            launch {
                try {
                    mutex.withLock {
                        val bean = DatabaseBean.fromClipData(clip) ?: return@withLock
                        val text = bean.text ?: return@withLock
                        val rules = clipboardRules.get(
                            clipPref.clipboardCompareRules.getValue(),
                            clipPref.clipboardOutputRules.getValue(),
                        ) ?: run {
                            Timber.w("Clipboard rules are invalid; skipping capture")
                            clearCapturedSuggestion(admission)
                            return@withLock
                        }
                        if (!rules.accepts(text)) {
                            return@withLock
                        }
                        clbDao.find(text)?.let { existing ->
                            val refreshed =
                                clbDb.withTransaction {
                                    clipboardPersistenceGate.guardTransaction(permit) {
                                        clbDao.updateTime(existing.id, bean.time)
                                        existing.copy(time = bean.time)
                                    }
                                }
                            publishCapturedBean(refreshed, permit, admission)
                            if (text.length <= 65536) {
                                val digest = clipboardRepeatDigest(text)
                                PrivacyModeState.publishIfOrdinary(permit) {
                                    if (listenerRegistered) repeatState.accepted(repeatEvent, refreshed.id, digest)
                                }
                            }
                            return@withLock
                        }
                        val insertedBean =
                            clbDb.withTransaction {
                                clipboardPersistenceGate.guardTransaction(permit) {
                                    val rowId = clbDao.insert(bean)
                                    val id = rowId.toInt()
                                    clbDao.get(rowId) ?: bean.copy(id = id)
                                }
                            }
                        removeOutdated()
                        updateItemCount()
                        publishCapturedBean(insertedBean, permit, admission)
                        if (text.length <= 65536) {
                            val digest = clipboardRepeatDigest(text)
                            PrivacyModeState.publishIfOrdinary(permit) {
                                if (listenerRegistered) repeatState.accepted(repeatEvent, insertedBean.id, digest)
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: PersistencePermitExpiredException) {
                    // Room rolled the transaction back; never publish the rejected content.
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                }
            }.invokeOnCompletion {
                // Completion handlers also run when a coroutine is cancelled before its body starts.
                clipboardPersistenceGate.release(permit)
            }
        } finally {
            imageAuthorization?.close()
        }
    }

    /** A cheap, defensive filter; actual MIME, URI and permission checks run in the worker. */
    private fun mayContainImageUri(clip: ClipData): Boolean = try {
        clip.itemCount == 1 && clip.getItemAt(0).let { it.uri != null && it.intent == null }
    } catch (failure: RuntimeException) {
        if (failure is kotlinx.coroutines.CancellationException || failure is PersistencePermitExpiredException) throw failure
        false
    }

    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        if (unpinned.size > limit) {
            val outdated =
                unpinned
                    .sortedBy { it.id }
                    .getOrNull(unpinned.size - limit)
            withRepeatAllMutation(invalidateEvent = false) { clbDao.deletedUnpinnedEarlierThan(outdated?.time ?: System.currentTimeMillis()) }
        }
    }
}
