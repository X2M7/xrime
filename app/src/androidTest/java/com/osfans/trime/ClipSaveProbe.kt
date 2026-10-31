// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.room.withTransaction
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.ClipboardSuggestionState
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.db.DatabaseDao
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ui.main.ClipEditActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Room writes and dialog lifecycle; only newly inserted UUID-tagged rows are removed. */
internal object ClipSaveProbe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var passed = false
        var checks = 0
        val editErrors = AtomicInteger()
        val errorObserver = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= Log.ERROR &&
                    (message.startsWith("Unable to save clipboard entry") || message.startsWith("Unable to open clipboard entry"))
                ) {
                    editErrors.incrementAndGet()
                }
            }
        }
        Timber.plant(errorObserver)
        fun <T> main(block: () -> T): T {
            var outcome: Result<T>? = null
            instrumentation.runOnMainSync { outcome = runCatching(block) }
            return checkNotNull(outcome).getOrThrow()
        }
        fun editor(type: String, id: Int) = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ClipEditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ClipEditActivity.CLIP_TYPE, type)
                .putExtra(ClipEditActivity.BEAN_ID, id),
        ) as ClipEditActivity
        suspend fun eventually(label: String = "unspecified", diagnostic: suspend () -> String = { "" }, block: suspend () -> Boolean) {
            try {
                withTimeout(15_000) { while (!block()) delay(50) }
            } catch (failure: TimeoutCancellationException) {
                val state = kotlinx.coroutines.withTimeoutOrNull(2_000) { diagnostic() } ?: "diagnostic timed out"
                throw IllegalStateException("Timed out during $label: $state", failure)
            }
        }
        fun checkPassed(name: String) {
            checks++
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS $checks: $name\n") })
        }
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("selected_input_method", Settings.Secure.getString(instrumentation.targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
                },
            )
            ClipboardSearchProbe.verifyConnection(instrumentation)
            checkPassed("clipboard search connection is local and cleans up")
            val clipboard = main { instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
            val previousClip = main { clipboard.primaryClip?.let(::ClipData) }
            check(previousClip != null || Build.VERSION.SDK_INT >= 28) { "A pre-existing clipboard is required for a reversible API 21 test" }
            val listening = AppPrefs.defaultInstance().clipboard.clipboardListening
            val previousListening = listening.getValue()
            val ownClipboard = mutableSetOf<String>()
            val suggestion = AtomicReference<ClipboardSuggestionState.Update<DatabaseBean>?>(null)
            val published = AtomicReference<ClipboardSuggestionState.Update<DatabaseBean>?>(null)
            val listener = ClipboardHelper.OnClipboardUpdateListener { update ->
                suggestion.set(update)
                if (update.bean != null) published.set(update)
            }
            main { listening.setValue(true); ClipboardHelper.addOnUpdateListener(listener) }
            try {
            runBlocking {
                withTimeout(180_000) {
                    ClipboardSearchProbe.verify(instrumentation.targetContext)
                    checkPassed("clipboard literal search, ordering and empty results")
                    for ((type, helper, prefix) in listOf(
                        Triple(ClipEditActivity.FROM_CLIPBOARD, ClipboardHelper, "clb"),
                        Triple(ClipEditActivity.FROM_COLLECTION, CollectionHelper, "clt"),
                    )) {
                        val db = helper.javaClass.getDeclaredField("${prefix}Db").apply { isAccessible = true }.get(helper) as Database
                        val daoField = helper.javaClass.getDeclaredField("${prefix}Dao").apply { isAccessible = true }
                        val dao = daoField.get(helper) as DatabaseDao
                        val cached = helper.javaClass.getDeclaredField("lastBean").apply { isAccessible = true }
                        val previousCache = main { cached.get(helper) }
                        val isClipboard = type == ClipEditActivity.FROM_CLIPBOARD
                        val owned = mutableListOf<Int>()
                        var active: ClipEditActivity? = null
                        suspend fun insert(text: String): DatabaseBean {
                            val row = dao.insert(DatabaseBean(text = text, pinned = true))
                            return checkNotNull(dao.get(row)).also { owned += it.id }
                        }
                        suspend fun seedSuggestion(bean: DatabaseBean): ClipboardSuggestionState.Update<DatabaseBean>? {
                            if (!isClipboard) {
                                val text = checkNotNull(bean.text)
                                var observed: Long? = null
                                var sent: Long? = null
                                var stableSince = SystemClock.elapsedRealtime()
                                eventually("real collection cache") {
                                    val epoch = PrivacyModeState.observeOrdinaryPersistence()
                                    if (epoch != observed) {
                                        observed = epoch
                                        stableSince = SystemClock.elapsedRealtime()
                                    }
                                    if (epoch != null && epoch != sent && SystemClock.elapsedRealtime() - stableSince >= 300) {
                                        sent = epoch
                                        CollectionHelper.addNewBean(text)
                                    }
                                    val value = cached.get(helper) as? DatabaseBean
                                    epoch != null && sent == epoch && PrivacyModeState.isOrdinaryEpoch(epoch) &&
                                        value?.let { it.id == bean.id && it.text == text } == true
                                }
                                return null
                            }
                            val text = checkNotNull(bean.text)
                            ownClipboard.add(text)
                            suggestion.set(null)
                            published.set(null)
                            val initialEpoch = PrivacyModeState.observeOrdinaryPersistence()
                            var observedEpoch: Long? = null
                            var sentEpoch: Long? = null
                            var stableSince = SystemClock.elapsedRealtime()
                            eventually("real clipboard suggestion", {
                                val update = suggestion.get()
                                val found = dao.find(text)
                                val registered = ClipboardHelper::class.java.getDeclaredField("listenerRegistered")
                                    .apply { isAccessible = true }.getBoolean(ClipboardHelper)
                                "rowMatches=${found?.id == bean.id}, updatePresent=${update != null}, " +
                                    "updateMatches=${update?.bean?.let { it.id == bean.id && it.text == text } == true}, " +
                                    "current=${update?.let(ClipboardHelper::isCurrentSuggestion)}, " +
                                    "updateEpoch=${update?.privacyEpoch}, initialEpoch=$initialEpoch, " +
                                    "currentEpoch=${PrivacyModeState.observeOrdinaryPersistence()}, private=${PrivacyModeState.active}, " +
                                    "listening=${listening.getValue()}, registered=$registered, " +
                                    "clipboardMatches=${main { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text }}"
                            }) {
                                val epoch = PrivacyModeState.observeOrdinaryPersistence()
                                if (epoch != observedEpoch) {
                                    observedEpoch = epoch
                                    stableSince = SystemClock.elapsedRealtime()
                                }
                                if (epoch != null && epoch != sentEpoch && SystemClock.elapsedRealtime() - stableSince >= 300) {
                                    sentEpoch = epoch
                                    main { clipboard.setPrimaryClip(ClipData.newPlainText("xrime clip save probe", text)) }
                                }
                                val update = suggestion.get()
                                update != null && update.bean?.let { it.id == bean.id && it.text == bean.text } == true &&
                                    update.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) == true &&
                                    ClipboardHelper.isCurrentSuggestion(update)
                            }
                            return checkNotNull(suggestion.get())
                        }
                        try {
                            suspend fun verifySuccessfulSaves() {
                                for (background in listOf(false, true)) {
                                    val token = UUID.randomUUID().toString()
                                    val bean = insert("initial-$token")
                                    val expected = "saved-$token-\u4f60\u597d-\uD842\uDFB7"
                                    val activity = editor(type, bean.id).also { active = it }
                                    val field = main { activity.findViewById<EditText>(R.id.clip_edit_text) }
                                    val ok = main { activity.findViewById<View>(R.id.clip_edit_ok) }
                                    val beforeEpoch = PrivacyModeState.observeOrdinaryPersistence()
                                    suspend fun state(): String {
                                        val rowSaved = dao.get(bean.id)?.text == expected
                                        val ui = main {
                                            val cache = cached.get(helper) as? DatabaseBean
                                            "fieldLoaded=${field.text.toString() == bean.text}, okEnabled=${ok.isEnabled}, " +
                                                "cacheMatches=${cache?.text == expected}, finishing=${activity.isFinishing}, destroyed=${activity.isDestroyed}"
                                        }
                                        val listening = AppPrefs.defaultInstance().clipboard.clipboardListening.getValue()
                                        val registered = if (type == ClipEditActivity.FROM_CLIPBOARD) {
                                            ClipboardHelper::class.java.getDeclaredField("listenerRegistered").apply { isAccessible = true }.getBoolean(ClipboardHelper)
                                        } else null
                                        return "type=$type, rowSaved=$rowSaved, $ui, listening=$listening, registered=$registered, " +
                                            "privacyActive=${PrivacyModeState.active}, initialEpoch=$beforeEpoch, currentEpoch=${PrivacyModeState.observeOrdinaryPersistence()}"
                                    }
                                    eventually("$type initial load", ::state) { main { field.text.toString() == bean.text && ok.isEnabled } }
                                    val initialSuggestion = seedSuggestion(bean)
                                    val collectionEpoch = if (isClipboard) null else checkNotNull(PrivacyModeState.observeOrdinaryPersistence())
                                    val entered = CompletableDeferred<Unit>()
                                    val release = CompletableDeferred<Unit>()
                                    val blocked = launch(Dispatchers.Default) {
                                        db.withTransaction {
                                            entered.complete(Unit)
                                            release.await()
                                        }
                                    }
                                    try {
                                        entered.await()
                                        main {
                                            field.setText(expected)
                                            ok.performClick()
                                            field.setText("duplicate-$token")
                                            ok.performClick()
                                        }
                                        delay(300)
                                        check(main { !activity.isFinishing }) { "Editor closed before its database write completed" }
                                        check(main { !ok.isEnabled }) { "Duplicate save was not disabled" }
                                        check(main { (cached.get(helper) as DatabaseBean).text == bean.text }) { "Uncommitted text reached the cache" }
                                        if (background) {
                                            main { activity.finish() }
                                            eventually { main { activity.isDestroyed } }
                                        }
                                    } finally {
                                        withContext(NonCancellable) {
                                            release.complete(Unit)
                                            blocked.join()
                                            db.withTransaction { }
                                        }
                                    }
                                    eventually("$type database save", ::state) { dao.get(bean.id)?.text == expected }
                                    if (isClipboard) {
                                        eventually("$type current suggestion or editor boundary", ::state) {
                                            published.get()?.bean?.let { it.id == bean.id && it.text == expected } == true ||
                                                initialSuggestion?.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) != true
                                        }
                                        val accepted = published.get()
                                        if (accepted?.bean?.text != expected) {
                                            check(initialSuggestion?.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) != true) {
                                                "An unchanged ordinary suggestion was not refreshed"
                                            }
                                        }
                                    } else {
                                        eventually("$type cache refresh or editor boundary", ::state) {
                                            main { (cached.get(helper) as? DatabaseBean)?.text == expected } ||
                                                !PrivacyModeState.isOrdinaryEpoch(checkNotNull(collectionEpoch))
                                        }
                                    }
                                    eventually("$type activity destruction", ::state) { main { activity.isDestroyed } }
                                    checkPassed("$type: ${if (background) "save survives dialog destruction" else "save awaits commit and ignores duplicate click"}")
                                    active = null
                                }
                            }
                            verifySuccessfulSaves()

                            suspend fun verifyRemovedRowFailure() {
                                val missing = insert("missing-${UUID.randomUUID()}")
                                val failed = editor(type, missing.id).also { active = it }
                                val field = main { failed.findViewById<EditText>(R.id.clip_edit_text) }
                                val ok = main { failed.findViewById<View>(R.id.clip_edit_ok) }
                                eventually { main { field.text.toString() == missing.text && ok.isEnabled } }
                                seedSuggestion(missing)
                                dao.delete(missing.id)
                                main {
                                    field.setText("unsaved edit")
                                    ok.performClick()
                                }
                                eventually { main { ok.isEnabled && !failed.isFinishing } }
                                check(main { field.text.toString() == "unsaved edit" })
                                check(main { field.error?.toString() == failed.getString(R.string.clipboard_entry_removed) })
                                if (isClipboard) {
                                    check(published.get()?.bean?.text != "unsaved edit") { "Failed write published the draft" }
                                } else {
                                    check(main { (cached.get(helper) as? DatabaseBean)?.let { it.id == missing.id && it.text == missing.text } == true }) {
                                        "Failed write changed the cache"
                                    }
                                }
                                check(dao.get(missing.id) == null)
                                main { failed.findViewById<View>(R.id.clip_edit_cancel).performClick() }
                                eventually { main { failed.isDestroyed } }
                                active = null
                                checkPassed("$type: removed-row failure retains editor text and cache")
                            }
                            verifyRemovedRowFailure()

                            suspend fun verifyRemovedBetweenReadAndUpdate() {
                                val missing = insert("update-race-${UUID.randomUUID()}")
                                val activity = editor(type, missing.id).also { active = it }
                                val field = main { activity.findViewById<EditText>(R.id.clip_edit_text) }
                                val ok = main { activity.findViewById<View>(R.id.clip_edit_ok) }
                                eventually { main { field.text.toString() == missing.text && ok.isEnabled } }
                                seedSuggestion(missing)
                                val readExisting = AtomicBoolean()
                                val affectedRows = AtomicInteger(-1)
                                daoField.set(
                                    helper,
                                    object : DatabaseDao by dao {
                                        override suspend fun get(id: Int): DatabaseBean? = dao.get(id).also {
                                            if (id == missing.id && it != null) readExisting.set(true)
                                        }

                                        override suspend fun updateText(id: Int, newText: String?): Int {
                                            if (id == missing.id) {
                                                check(readExisting.get()) { "The save did not read the existing row before the deletion race" }
                                                dao.delete(id)
                                            }
                                            return dao.updateText(id, newText).also {
                                                if (id == missing.id) affectedRows.set(it)
                                            }
                                        }
                                    },
                                )
                                try {
                                    main {
                                        field.setText("unsaved raced edit")
                                        ok.performClick()
                                    }
                                    eventually { main { ok.isEnabled && !activity.isFinishing && field.error != null } }
                                    check(readExisting.get() && affectedRows.get() == 0) { "The real DAO did not return zero rows after a successful read" }
                                    check(main { field.text.toString() == "unsaved raced edit" })
                                    check(main { field.error?.toString() == activity.getString(R.string.clipboard_entry_removed) })
                                    if (isClipboard) {
                                        check(published.get()?.bean?.text != "unsaved raced edit") { "A zero-row update published the draft" }
                                    } else {
                                        check(main { (cached.get(helper) as? DatabaseBean)?.let { it.id == missing.id && it.text == missing.text } == true }) {
                                            "A zero-row update published edited text into the cache"
                                        }
                                    }
                                    check(dao.get(missing.id) == null)
                                    main { activity.findViewById<View>(R.id.clip_edit_cancel).performClick() }
                                    eventually { main { activity.isDestroyed } }
                                    active = null
                                    checkPassed("$type: deletion after get returns zero-row NotFound without losing draft or cache")
                                } finally {
                                    daoField.set(helper, dao)
                                }
                            }
                            verifyRemovedBetweenReadAndUpdate()

                            suspend fun verifySupersededLoad() {
                                val first = insert("first-${UUID.randomUUID()}")
                                val second = insert("second-${UUID.randomUUID()}")
                                val reading = CompletableDeferred<Unit>()
                                val releaseRead = CompletableDeferred<Unit>()
                                daoField.set(
                                    helper,
                                    object : DatabaseDao by dao {
                                        override suspend fun get(id: Int): DatabaseBean? {
                                            if (id == first.id) {
                                                reading.complete(Unit)
                                                releaseRead.await()
                                            }
                                            return dao.get(id)
                                        }
                                    },
                                )
                                try {
                                    val loading = editor(type, first.id).also { active = it }
                                    reading.await()
                                    check(main { !loading.findViewById<View>(R.id.clip_edit_ok).isEnabled })
                                    check(main { !loading.findViewById<View>(R.id.clip_edit_text).isEnabled })
                                    main {
                                        instrumentation.callActivityOnNewIntent(
                                            loading,
                                            Intent().putExtra(ClipEditActivity.CLIP_TYPE, type)
                                                .putExtra(ClipEditActivity.BEAN_ID, second.id),
                                        )
                                    }
                                    eventually { main { loading.findViewById<EditText>(R.id.clip_edit_text).text.toString() == second.text } }
                                    releaseRead.complete(Unit)
                                    delay(200)
                                    check(main { loading.findViewById<EditText>(R.id.clip_edit_text).text.toString() == second.text })
                                    main {
                                        loading.findViewById<EditText>(R.id.clip_edit_text).setText("cancelled edit")
                                        loading.findViewById<View>(R.id.clip_edit_cancel).performClick()
                                    }
                                    eventually { main { loading.isDestroyed } }
                                    active = null
                                    check(dao.get(first.id)?.text == first.text && dao.get(second.id)?.text == second.text)
                                    checkPassed("$type: loading disables edits, new intent supersedes old read, cancel preserves both rows")
                                } finally {
                                    releaseRead.complete(Unit)
                                    daoField.set(helper, dao)
                                }
                            }
                            verifySupersededLoad()

                            suspend fun verifySupersededMissingLoad() {
                                val first = insert("stale-missing-${UUID.randomUUID()}")
                                val second = insert("current-${UUID.randomUUID()}")
                                val reading = CompletableDeferred<Unit>()
                                val releaseRead = CompletableDeferred<Unit>()
                                val readReturned = CompletableDeferred<Unit>()
                                val delayedFirst = AtomicBoolean()
                                daoField.set(
                                    helper,
                                    object : DatabaseDao by dao {
                                        override suspend fun get(id: Int): DatabaseBean? {
                                            if (id == first.id && delayedFirst.compareAndSet(false, true)) {
                                                reading.complete(Unit)
                                                val value = withContext(NonCancellable) {
                                                    releaseRead.await()
                                                    dao.delete(id)
                                                    dao.get(id)
                                                }
                                                readReturned.complete(Unit)
                                                return value
                                            }
                                            return dao.get(id)
                                        }
                                    },
                                )
                                try {
                                    val activity = editor(type, first.id).also { active = it }
                                    reading.await()
                                    main {
                                        instrumentation.callActivityOnNewIntent(
                                            activity,
                                            Intent().putExtra(ClipEditActivity.CLIP_TYPE, type)
                                                .putExtra(ClipEditActivity.BEAN_ID, second.id),
                                        )
                                    }
                                    val field = main { activity.findViewById<EditText>(R.id.clip_edit_text) }
                                    val ok = main { activity.findViewById<View>(R.id.clip_edit_ok) }
                                    eventually { main { field.text.toString() == second.text && ok.isEnabled } }
                                    seedSuggestion(second)
                                    main { field.setText("current draft must survive stale NotFound") }
                                    releaseRead.complete(Unit)
                                    readReturned.await()
                                    delay(200)
                                    check(main { !activity.isFinishing && ok.isEnabled && field.text.toString() == "current draft must survive stale NotFound" })
                                    check(main {
                                        val cache = cached.get(helper) as? DatabaseBean
                                        field.error == null && if (isClipboard) {
                                            cache == null || (cache.id == second.id && cache.text == second.text)
                                        } else cache?.let { it.id == second.id && it.text == second.text } == true
                                    })
                                    check(dao.get(first.id) == null && dao.get(second.id)?.text == second.text)
                                    main {
                                        instrumentation.callActivityOnNewIntent(
                                            activity,
                                            Intent().putExtra(ClipEditActivity.CLIP_TYPE, type)
                                                .putExtra(ClipEditActivity.BEAN_ID, first.id),
                                        )
                                    }
                                    eventually { main { activity.isDestroyed } }
                                    active = null
                                    check(main {
                                        val cache = cached.get(helper) as? DatabaseBean
                                        if (isClipboard) cache == null || (cache.id == second.id && cache.text == second.text)
                                        else cache?.let { it.id == second.id && it.text == second.text } == true
                                    })
                                    checkPassed("$type: stale missing read cannot close the new draft, while a current missing read exits normally")
                                } finally {
                                    releaseRead.complete(Unit)
                                    daoField.set(helper, dao)
                                }
                            }
                            verifySupersededMissingLoad()
                        } finally {
                            withContext(NonCancellable) {
                                active?.let { activity ->
                                    main { if (!activity.isFinishing) activity.finish() }
                                    eventually { main { activity.isDestroyed } }
                                }
                                daoField.set(helper, dao)
                                owned.forEach { dao.delete(it) }
                                if (!isClipboard) main { cached.set(helper, previousCache) }
                            }
                        }
                    }
                }
            }
            } finally {
                main {
                    ClipboardHelper.removeOnUpdateListener(listener)
                    listening.setValue(false)
                    val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (current != null && current in ownClipboard) {
                        if (previousClip != null) clipboard.setPrimaryClip(previousClip)
                        else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    }
                    listening.setValue(previousListening)
                }
            }
            check(editErrors.get() == 0) { "A normal clipboard edit outcome emitted an error log" }
            passed = true
            result.putString("stream", "PASS: $checks real clipboard/collection save and lifecycle checks; only owned rows removed\n")
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
        } finally {
            Timber.uproot(errorObserver)
        }
        result.putInt("clip_edit_error_count", editErrors.get())
        result.putBoolean("passed", passed)
        result.putInt("checks", checks)
        instrumentation.finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
