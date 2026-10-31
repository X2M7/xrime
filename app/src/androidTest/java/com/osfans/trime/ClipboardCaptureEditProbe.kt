// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.ClipboardSuggestionState
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.db.TextUpdateResult
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.ui.main.EditorProbeActivity
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Disposable emulator-only capture, real edit dialog and visible clipboard list. */
internal object ClipboardCaptureEditProbe {
    @SuppressLint("PrivateApi")
    private fun roots(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        return (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>).toList()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var checks = 0
        fun phase(message: String) {
            checks++
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS $checks: $message\n") })
        }
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun input(): InputView? = roots().asSequence().flatMap(::descendants).filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun shownText(text: String): Boolean = input()?.let { view ->
            descendants(view).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == text }
        } == true
        val outcome = runCatching {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
            val context = instrumentation.targetContext
            val prefs = AppPrefs.defaultInstance().clipboard
            val previousListening = prefs.clipboardListening.getValue()
            val previousSuggestion = prefs.clipboardSuggestion.getValue()
            val previousTimeout = prefs.clipboardSuggestionTimeout.getValue()
            val clipboard = main { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
            val previousClip = main { clipboard.primaryClip?.let(::ClipData) }
            // On API 21 an empty clipboard cannot be restored through the public API.
            check(previousClip != null) { "A pre-existing clipboard is required for a reversible API 21 test" }
            val db = ClipboardHelper::class.java.getDeclaredField("clbDb").apply { isAccessible = true }.get(ClipboardHelper) as Database
            val dao = db.databaseDao()
            val collectionDb = CollectionHelper::class.java.getDeclaredField("cltDb").apply { isAccessible = true }.get(CollectionHelper) as Database
            val collectionDao = collectionDb.databaseDao()
            val collectionCache = CollectionHelper::class.java.getDeclaredField("lastBean").apply { isAccessible = true }
            val previousCollectionCache = collectionCache.get(CollectionHelper)
            check(runBlocking { dao.getAllUnpinned().size } < prefs.clipboardLimit.getValue()) {
                "No spare clipboard history slot; test would evict existing history"
            }
            val suffix = UUID.randomUUID().toString().replace("-", "")
            val original = "capture$suffix"
            val sameGeneration = "same$suffix"
            val boundarySave = "boundary$suffix"
            val edited = "edited$suffix"
            val update = AtomicReference<ClipboardSuggestionState.Update<DatabaseBean>?>(null)
            val listener = ClipboardHelper.OnClipboardUpdateListener { item ->
                if (item.bean?.text == original || item.bean?.text == sameGeneration) update.set(item)
            }
            var host: Activity? = null
            var editor: ClipEditActivity? = null
            var rowId: Int? = null
            var collectionId: Int? = null
            try {
                main {
                    prefs.clipboardListening.setValue(true)
                    prefs.clipboardSuggestion.setValue(true)
                    prefs.clipboardSuggestionTimeout.setValue(100)
                    ClipboardHelper.addOnUpdateListener(listener)
                }
                val activity = instrumentation.startActivitySync(Intent(context, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                host = activity
                val field = main {
                    EditText(activity).apply {
                        setSingleLine(true)
                        activity.setContentView(this)
                        requestFocus()
                    }
                }
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                runBlocking {
                    suspend fun until(label: String, condition: suspend () -> Boolean) {
                        withTimeout(30_000) { while (!condition()) delay(50) }
                        phase(label)
                    }
                    until("editor focus") { main { field.hasWindowFocus() && field.hasFocus() && field.isAttachedToWindow } }
                    main { imm.showSoftInput(field, 0) }
                    until("real IME visible") { main { input() != null && !PrivacyModeState.active } }
                    main { clipboard.setPrimaryClip(ClipData.newPlainText("xrime capture probe", original)) }
                    until("system clipboard captured and rendered") {
                        val captured = update.get()
                        val row = dao.find(original)
                        rowId = row?.id ?: rowId
                        captured != null && row != null && captured.bean?.id == row.id && captured.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) == true &&
                            ClipboardHelper.isCurrentSuggestion(captured) && main { shownText(original.take(42)) }
                    }
                    val id = checkNotNull(rowId)
                    check(ClipboardHelper.updateText(id, sameGeneration) == TextUpdateResult.SAVED)
                    until("same-generation update rendered") {
                        val current = update.get()
                        dao.get(id)?.text == sameGeneration && current != null && current.bean?.text == sameGeneration &&
                            current.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) == true &&
                            ClipboardHelper.isCurrentSuggestion(current) && main { shownText(sameGeneration.take(42)) }
                    }
                    val oldUpdate = checkNotNull(update.get())
                    coroutineScope {
                        val entered = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        val blocker = launch(Dispatchers.IO) {
                            db.withTransaction { entered.complete(Unit); release.await() }
                        }
                        try {
                            withTimeout(10_000) { entered.await() }
                            val lock = checkNotNull(PrivacyModeState::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(PrivacyModeState))
                            val permits = PrivacyModeState::class.java.getDeclaredField("persistencePermits").apply { isAccessible = true }
                            fun permitIds(): Set<*> = synchronized(lock) {
                                (permits.get(PrivacyModeState) as Map<*, *>).keys.toSet()
                            }
                            val before = permitIds()
                            val saving = async(Dispatchers.IO) { ClipboardHelper.updateText(id, boundarySave) }
                            until("ordinary save owns a persistence permit") { (permitIds() - before).isNotEmpty() }
                            main {
                                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                                imm.restartInput(field)
                            }
                            until("real password editor entered privacy mode") { PrivacyModeState.active }
                            release.complete(Unit)
                            check(withTimeout(20_000) { saving.await() } == TextUpdateResult.SAVED)
                            check(dao.get(id)?.text == boundarySave)
                            check(!ClipboardHelper.isCurrentSuggestion(oldUpdate) ||
                                oldUpdate.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) != true)
                            until("in-flight save stays hidden across a real private editor boundary") {
                                main { !shownText(boundarySave.take(42)) && !shownText(sameGeneration.take(42)) }
                            }
                        } finally {
                            release.complete(Unit)
                            blocker.join()
                        }
                    }
                    main {
                        field.inputType = InputType.TYPE_CLASS_TEXT
                        imm.restartInput(field)
                    }
                    until("ordinary editor restored") { !PrivacyModeState.active }
                    val edit = instrumentation.startActivitySync(
                        Intent(context, ClipEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(ClipEditActivity.CLIP_TYPE, ClipEditActivity.FROM_CLIPBOARD)
                            .putExtra(ClipEditActivity.BEAN_ID, id),
                    ) as ClipEditActivity
                    editor = edit
                    val editField = main { edit.findViewById<EditText>(R.id.clip_edit_text) }
                    val ok = main { edit.findViewById<View>(R.id.clip_edit_ok) }
                    until("real edit dialog loaded") { main { editField.text.toString() == boundarySave && ok.isEnabled } }
                    main { editField.setText(edited); check(ok.performClick()) }
                    until("dialog save committed") { dao.get(id)?.text == edited && main { edit.isDestroyed } }
                    editor = null
                    check(oldUpdate.privacyEpoch?.let(PrivacyModeState::isOrdinaryEpoch) != true) { "Original suggestion unexpectedly retained its editor epoch" }
                    main { field.requestFocus(); imm.showSoftInput(field, 0) }
                    until("old suggestion does not revive after editor change") { main { input() != null && !shownText(sameGeneration.take(42)) } }
                    main { checkNotNull(input()).di.direct.instance<BoardWindowManager>().attachWindow(ClipboardWindow(checkNotNull(input()).di)) }
                    until("saved entry shown by real clipboard list") { main { shownText(edited) } }

                    val collectionOriginal = "collection$suffix"
                    val collectionSame = "collection-same$suffix"
                    val collectionEdited = "collection-edited$suffix"
                    val inserted = collectionDao.insert(DatabaseBean(text = collectionOriginal, pinned = true))
                    collectionId = inserted.toInt()
                    val collectionEpoch = checkNotNull(PrivacyModeState.observeOrdinaryPersistence())
                    CollectionHelper.addNewBean(collectionOriginal)
                    until("collection real cache established") {
                        val cached = collectionCache.get(CollectionHelper) as? DatabaseBean
                        PrivacyModeState.isOrdinaryEpoch(collectionEpoch) && cached?.id == collectionId && cached.text == collectionOriginal
                    }
                    check(CollectionHelper.updateText(checkNotNull(collectionId), collectionSame) == TextUpdateResult.SAVED)
                    until("collection same-generation cache refreshed") {
                        val cached = collectionCache.get(CollectionHelper) as? DatabaseBean
                        PrivacyModeState.isOrdinaryEpoch(collectionEpoch) && cached?.id == collectionId && cached.text == collectionSame &&
                            collectionDao.get(checkNotNull(collectionId))?.text == collectionSame
                    }
                    val collectionEdit = instrumentation.startActivitySync(
                        Intent(context, ClipEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(ClipEditActivity.CLIP_TYPE, ClipEditActivity.FROM_COLLECTION)
                            .putExtra(ClipEditActivity.BEAN_ID, checkNotNull(collectionId)),
                    ) as ClipEditActivity
                    editor = collectionEdit
                    val collectionField = main { collectionEdit.findViewById<EditText>(R.id.clip_edit_text) }
                    val collectionOk = main { collectionEdit.findViewById<View>(R.id.clip_edit_ok) }
                    until("real collection editor loaded") { main { collectionField.text.toString() == collectionSame && collectionOk.isEnabled } }
                    main { collectionField.setText(collectionEdited); check(collectionOk.performClick()) }
                    until("collection editor committed") {
                        collectionDao.get(checkNotNull(collectionId))?.text == collectionEdited && main { collectionEdit.isDestroyed }
                    }
                    editor = null
                    main { field.requestFocus(); imm.showSoftInput(field, 0) }
                    until("collection host editor restored") { main { input() != null } }
                    main { checkNotNull(input()).di.direct.instance<BoardWindowManager>().attachWindow(ClipboardWindow(checkNotNull(input()).di, 1)) }
                    until("saved entry shown by real collection list") { main { shownText(collectionEdited) } }
                }
            } finally {
                main {
                    editor?.finish()
                    host?.finish()
                    ClipboardHelper.removeOnUpdateListener(listener)
                    prefs.clipboardListening.setValue(false)
                    // Restore only if no other actor replaced our test clipboard.
                    if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == original) clipboard.setPrimaryClip(previousClip)
                }
                runBlocking {
                    rowId?.let { dao.delete(it) }
                    collectionId?.let { collectionDao.delete(it) }
                }
                main {
                    collectionCache.set(CollectionHelper, previousCollectionCache)
                    prefs.clipboardSuggestionTimeout.setValue(previousTimeout)
                    prefs.clipboardSuggestion.setValue(previousSuggestion)
                    prefs.clipboardListening.setValue(previousListening)
                }
            }
        }
        outcome.exceptionOrNull()?.let { result.putString("stream", "FAIL: ${it.stackTraceToString()}") }
        result.putBoolean("passed", outcome.isSuccess)
        result.putInt("checks", checks)
        instrumentation.finish(if (outcome.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
