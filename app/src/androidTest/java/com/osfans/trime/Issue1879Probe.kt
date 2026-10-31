/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.osfans.trime.core.RimeApi
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Real system pointer across the quick-bar state transition (issue #1879). */
internal object Issue1879Probe {
    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var failure: Throwable? = null
        var host: Activity? = null
        var session: RimeSession? = null
        var service: TrimeInputMethodService? = null
        var schema: String? = null
        var ascii: Boolean? = null
        var pointerDownAt: Long? = null
        var pointerX = 0f
        var pointerY = 0f
        val selectedTheme = ThemeManager.prefs.selectedTheme
        val oldTheme = selectedTheme.getValue()
        val hadTheme = selectedTheme.sharedPreferences.contains(selectedTheme.key)
        val oldThemeRaw = selectedTheme.sharedPreferences.getString(selectedTheme.key, null)
        val hideBar = AppPrefs.defaultInstance().keyboard.hideInputBar
        val oldHideBar = hideBar.getValue()
        val hadHideBar = hideBar.sharedPreferences.contains(hideBar.key)
        val popupMode = AppPrefs.defaultInstance().candidates.mode
        val oldPopupMode = popupMode.getValue()
        val hadPopupMode = popupMode.sharedPreferences.contains(popupMode.key)
        val oldPopupRaw = popupMode.sharedPreferences.getString(popupMode.key, null)
        val swipeTravel = AppPrefs.defaultInstance().keyboard.swipeTravel
        val oldSwipeTravel = swipeTravel.getValue()
        val hadSwipeTravel = swipeTravel.sharedPreferences.contains(swipeTravel.key)
        val clipboardPrefs = AppPrefs.defaultInstance().clipboard
        val oldListening = clipboardPrefs.clipboardListening.getValue()
        val oldSuggestion = clipboardPrefs.clipboardSuggestion.getValue()
        val oldLimit = clipboardPrefs.clipboardLimit.getValue()
        val hadListening = clipboardPrefs.clipboardListening.sharedPreferences.contains(clipboardPrefs.clipboardListening.key)
        val hadSuggestion = clipboardPrefs.clipboardSuggestion.sharedPreferences.contains(clipboardPrefs.clipboardSuggestion.key)
        val hadLimit = clipboardPrefs.clipboardLimit.sharedPreferences.contains(clipboardPrefs.clipboardLimit.key)
        var clipboard: ClipboardManager? = null
        var oldClip: ClipData? = null
        var clipboardFixtureId: Int? = null
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            instrumentation.runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        suspend fun until(label: String, condition: () -> Boolean) {
            try { withTimeout(30_000) { while (!main(condition)) delay(25) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw IllegalStateException("Timed out waiting for $label", error) }
        }
        suspend fun api(block: suspend RimeApi.() -> Unit) {
            var value: Result<Unit>? = null
            val job = main { checkNotNull(service).postRimeJob { value = runCatching { block() } } }
            job.join()
            check(!job.isCancelled)
            checkNotNull(value).getOrThrow()
        }
        fun pointer(action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) { pointerDownAt = now; pointerX = x; pointerY = y }
            val event = MotionEvent.obtain(checkNotNull(pointerDownAt), now, action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pointerDownAt = null
        }
        fun centre(view: View): Pair<Float, Float> {
            val location = IntArray(2).also(view::getLocationOnScreen)
            return (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
        }
        try {
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            main { hideBar.setValue(false); popupMode.setValue(PopupCandidatesMode.DISABLED); swipeTravel.setValue(60) }
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(300_000) { checkNotNull(session).runOnReady { } }
                check(ThemeManager.selectTheme("trime") == "trime")
            }
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            host = activity
            val editor = main {
                EditText(activity).apply {
                    id = View.generateViewId()
                    activity.setContentView(this)
                    requestFocus()
                    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                }
            }
            runBlocking {
                until("physical editor and visible input view") {
                    if (editor.hasWindowFocus()) (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0)
                    service = input()?.di?.direct?.instance<TrimeInputMethodService>()
                        ?.takeIf { it.currentInputEditorInfo?.fieldId == editor.id }
                    service != null && editor.hasFocus() && editor.hasWindowFocus() &&
                        ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                api {
                    schema = selectedSchemaId()
                    ascii = getRuntimeOption("ascii_mode")
                    clearComposition()
                    check(selectSchema("luna_pinyin_simp"))
                    setRuntimeOption("ascii_mode", false)
                }
                val root = main { checkNotNull(input()) }
                val bar = root.di.direct.instance<InputBarDelegate>()
                val candidate = root.di.direct.instance<CompactCandidateDelegate>()
                val manager = root.di.direct.instance<BoardWindowManager>()
                val always = InputBarDelegate::class.java.getDeclaredField("alwaysUi\$delegate").apply { isAccessible = true }
                    .get(bar).let { it as Lazy<*> }.value as AlwaysUi
                val primary = AlwaysUi::class.java.getDeclaredField("leftMostButton").apply { isAccessible = true }.get(always) as ToolButton
                fun currentWindow(): Any? = BoardWindowManager::class.java.getDeclaredField("currentWindow").apply { isAccessible = true }.get(manager)
                suspend fun awaitCandidateDraw() {
                    val drawn = AtomicBoolean(false)
                    val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.set(true) }
                    main { candidate.view.viewTreeObserver.addOnDrawListener(listener); candidate.view.invalidate() }
                    try { until("candidate drawing frame") { drawn.get() && candidate.view.isShown } }
                    finally { main { if (candidate.view.viewTreeObserver.isAlive) candidate.view.viewTreeObserver.removeOnDrawListener(listener) } }
                }
                until("ordinary toolbar") { bar.view.displayedChild == 0 && primary.isShown && primary.width > 0 && primary.height > 0 }
                val pos = main { centre(primary) }
                val primaryBounds = main {
                    val location = IntArray(2).also(primary::getLocationOnScreen)
                    Rect(location[0], location[1], location[0] + primary.width, location[1] + primary.height)
                }
                pointer(MotionEvent.ACTION_DOWN, pos.first, pos.second)
                until("toolbar actual down") { primary.isPressed }
                pointer(MotionEvent.ACTION_UP, pos.first, pos.second)
                until("ordinary toolbar opens options") { currentWindow() is SwitchOptionWindow }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: ordinary primary toolbar action\n") })
                main { manager.attachWindow(KeyboardWindow) }
                until("toolbar restored") { bar.view.displayedChild == 0 && primary.isShown }

                suspend fun showAgain() {
                    main { (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, 0) }
                    var previous: Rect? = null
                    var stableSince = 0L
                    until("IME restored after ordinary hide") {
                        val current = if (input() === root && editor.hasFocus() && editor.hasWindowFocus() &&
                            ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true && primary.isShown
                        ) {
                            val xy = IntArray(2).also(primary::getLocationOnScreen)
                            Rect(xy[0], xy[1], xy[0] + primary.width, xy[1] + primary.height)
                        } else null
                        val now = SystemClock.uptimeMillis()
                        if (current == null || current != previous) stableSince = now
                        previous = current
                        current != null && now - stableSince >= 500
                    }
                }
                val hide = always.hideKeyboardButton
                until("ordinary hide button visible") { hide.isShown && hide.width > 0 && hide.height > 0 }
                val normalHidePoint = main { centre(hide) }
                pointer(MotionEvent.ACTION_DOWN, normalHidePoint.first, normalHidePoint.second)
                pointer(MotionEvent.ACTION_UP, normalHidePoint.first, normalHidePoint.second)
                until("ordinary hide click") { ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
                showAgain()
                val normalSwipePoint = main { centre(hide) }
                val swipeDistance = root.resources.displayMetrics.density * 80f
                pointer(MotionEvent.ACTION_DOWN, normalSwipePoint.first, normalSwipePoint.second)
                until("ordinary hide swipe down reached button") { hide.isPressed }
                pointer(MotionEvent.ACTION_MOVE, normalSwipePoint.first, normalSwipePoint.second + swipeDistance)
                pointer(MotionEvent.ACTION_UP, normalSwipePoint.first, normalSwipePoint.second + swipeDistance)
                until("ordinary hide swipe") { ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
                showAgain()
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: ordinary hide click and swipe\n") })

                // The real system gesture begins on the old button before the candidate arrives.
                pointer(MotionEvent.ACTION_DOWN, pos.first, pos.second)
                until("held toolbar down") { primary.isPressed }
                api { check(processKey('a'.code)) }
                until("renderable candidates replace toolbar") {
                    bar.view.displayedChild == 1 && candidate.view.isShown && candidate.view.childCount > 0 && candidate.view.getChildAt(0).isShown
                }
                awaitCandidateDraw()
                pointer(MotionEvent.ACTION_UP, pos.first, pos.second)
                delay(250)
                check(main { currentWindow() !is SwitchOptionWindow && bar.view.displayedChild == 1 && editor.text.isEmpty() }) {
                    "Old toolbar action executed after candidates replaced it"
                }
                // A stale callback is exercised separately, regardless of whether Android cancels the held target.
                main { primary.performClick() }
                delay(100)
                check(main { currentWindow() !is SwitchOptionWindow && bar.view.displayedChild == 1 }) { "Stale toolbar callback escaped candidate state" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: held primary gesture and stale callback after candidate frame\n") })

                // The hide button has an independent callback, so verify its held gesture separately.
                api { clearComposition() }
                until("toolbar hide control restored") { bar.view.displayedChild == 0 && hide.isShown && hide.width > 0 && hide.height > 0 }
                val hidePoint = main { centre(hide) }
                pointer(MotionEvent.ACTION_DOWN, hidePoint.first, hidePoint.second)
                until("held hide button down") { hide.isPressed }
                api { check(processKey('a'.code)) }
                until("candidates replace hide button") {
                    bar.view.displayedChild == 1 && candidate.view.isShown && candidate.view.childCount > 0 && candidate.view.getChildAt(0).isShown
                }
                awaitCandidateDraw()
                pointer(MotionEvent.ACTION_UP, hidePoint.first, hidePoint.second)
                delay(250)
                check(main {
                    editor.hasFocus() && ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                        bar.view.displayedChild == 1 && currentWindow() !is SwitchOptionWindow
                }) { "Stale hide-button gesture changed the visible keyboard" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: held hide click after candidate frame\n") })

                api { clearComposition() }
                until("toolbar restored before stale hide swipe") { bar.view.displayedChild == 0 && hide.isShown }
                val staleSwipe = main { centre(hide) }
                pointer(MotionEvent.ACTION_DOWN, staleSwipe.first, staleSwipe.second)
                until("held hide swipe down") { hide.isPressed }
                api { check(processKey('a'.code)) }
                until("candidates replace swipe target") {
                    bar.view.displayedChild == 1 && candidate.view.isShown && candidate.view.childCount > 0 && candidate.view.getChildAt(0).isShown
                }
                awaitCandidateDraw()
                pointer(MotionEvent.ACTION_MOVE, staleSwipe.first, staleSwipe.second + swipeDistance)
                pointer(MotionEvent.ACTION_UP, staleSwipe.first, staleSwipe.second + swipeDistance)
                main { hide.onSwipe?.invoke(KeyBehavior.SWIPE_DOWN) }
                delay(250)
                check(main {
                    editor.hasFocus() && ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                        bar.view.displayedChild == 1 && currentWindow() !is SwitchOptionWindow
                }) { "Stale hide-button swipe changed the visible keyboard" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: held hide swipe after candidate frame\n") })

                api { clearComposition() }
                until("toolbar before clipboard suggestion") { bar.view.displayedChild == 0 }
                val clipboardManager = activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard = clipboardManager
                val fixture = "issue1879-" + java.util.UUID.randomUUID().toString().take(8)
                main {
                    oldClip = clipboardManager.primaryClip
                    clipboardPrefs.clipboardLimit.setValue(1000)
                    clipboardPrefs.clipboardListening.setValue(true)
                    clipboardPrefs.clipboardSuggestion.setValue(true)
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("issue1879 fixture", fixture))
                }
                until("clipboard suggestion displayed") {
                    ClipboardHelper.lastBean?.text == fixture && always.currentState == AlwaysUi.State.Clipboard && always.clipboardUi.suggestionView.isShown
                }
                clipboardFixtureId = checkNotNull(ClipboardHelper.lastBean).id
                val suggestion = always.clipboardUi.suggestionView
                val suggestionPoint = main { centre(suggestion) }
                pointer(MotionEvent.ACTION_DOWN, suggestionPoint.first, suggestionPoint.second)
                until("held clipboard suggestion") { suggestion.isPressed }
                api { check(processKey('a'.code)) }
                until("candidates replace clipboard suggestion") {
                    bar.view.displayedChild == 1 && candidate.view.isShown && candidate.view.childCount > 0
                }
                awaitCandidateDraw()
                pointer(MotionEvent.ACTION_UP, suggestionPoint.first, suggestionPoint.second)
                main { suggestion.performClick(); suggestion.performLongClick(); always.clipboardUi.dismiss.performClick() }
                delay(250)
                check(main {
                    editor.text.isEmpty() && editor.hasWindowFocus() && bar.view.displayedChild == 1 &&
                        always.currentState == AlwaysUi.State.Clipboard && currentWindow() !is SwitchOptionWindow
                }) { "A stale clipboard suggestion executed after candidates appeared" }
                api { clearComposition() }
                until("ordinary clipboard suggestion restored") { bar.view.displayedChild == 0 && suggestion.isShown }
                val currentSuggestion = main { centre(suggestion) }
                pointer(MotionEvent.ACTION_DOWN, currentSuggestion.first, currentSuggestion.second)
                pointer(MotionEvent.ACTION_UP, currentSuggestion.first, currentSuggestion.second)
                until("ordinary clipboard suggestion commits") { editor.text.toString() == fixture }
                main { editor.text.clear() }
                api { check(processKey('a'.code)) }
                until("candidates after clipboard control") { bar.view.displayedChild == 1 && candidate.view.isShown && candidate.view.childCount > 0 }
                awaitCandidateDraw()
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: ordinary clipboard suggestion and stale click/long-click/dismiss\n") })

                val (candidatePoint, candidateText) = main {
                    val child = candidate.view.getChildAt(0)
                    val holder = candidate.view.getChildViewHolder(child) as CandidateViewHolder
                    check(holder.idx == 0 && holder.text.isNotEmpty())
                    val location = IntArray(2).also(child::getLocationOnScreen)
                    val overlap = Rect(location[0], location[1], location[0] + child.width, location[1] + child.height)
                    check(overlap.intersect(primaryBounds)) { "Candidate does not overlap the original toolbar target" }
                    (overlap.exactCenterX() to overlap.exactCenterY()) to holder.text
                }
                pointer(MotionEvent.ACTION_DOWN, candidatePoint.first, candidatePoint.second)
                pointer(MotionEvent.ACTION_UP, candidatePoint.first, candidatePoint.second)
                until("actual visible candidate selected") { editor.text.toString() == candidateText && currentWindow() !is SwitchOptionWindow }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: primary and hide gestures across candidate frame, stale callback refused and real candidate selected\n") })
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else checkNotNull(failure).addSuppressed(error)
                }
            }
            cleanup { if (pointerDownAt != null) pointer(MotionEvent.ACTION_CANCEL, pointerX, pointerY) }
            cleanup {
                if (session != null && schema != null && ascii != null) runBlocking {
                    checkNotNull(session).runOnReady { clearComposition(); check(selectSchema(checkNotNull(schema))); setRuntimeOption("ascii_mode", checkNotNull(ascii)) }
                }
            }
            cleanup { host?.let { main { it.finish() } } }
            cleanup {
                main { clipboardPrefs.clipboardListening.setValue(false) }
                clipboardFixtureId?.let { runBlocking { ClipboardHelper.delete(it) } }
                main {
                    clipboard?.let { manager ->
                        val previousClip = oldClip
                        if (previousClip == null) manager.clearPrimaryClip() else manager.setPrimaryClip(previousClip)
                    }
                    clipboardPrefs.clipboardLimit.setValue(oldLimit)
                    clipboardPrefs.clipboardSuggestion.setValue(oldSuggestion)
                    clipboardPrefs.clipboardListening.setValue(oldListening)
                    val edit = clipboardPrefs.clipboardListening.sharedPreferences.edit()
                    if (!hadListening) edit.remove(clipboardPrefs.clipboardListening.key)
                    if (!hadSuggestion) edit.remove(clipboardPrefs.clipboardSuggestion.key)
                    if (!hadLimit) edit.remove(clipboardPrefs.clipboardLimit.key)
                    check(edit.commit())
                }
            }
            cleanup {
                main { hideBar.setValue(oldHideBar); popupMode.setValue(oldPopupMode); swipeTravel.setValue(oldSwipeTravel) }
                val edit = hideBar.sharedPreferences.edit()
                if (!hadHideBar) edit.remove(hideBar.key)
                if (!hadSwipeTravel) edit.remove(swipeTravel.key)
                if (hadPopupMode) edit.putString(popupMode.key, checkNotNull(oldPopupRaw)) else edit.remove(popupMode.key)
                check(edit.commit())
            }
            cleanup {
                runBlocking { ThemeManager.selectTheme(oldTheme) }
                val edit = selectedTheme.sharedPreferences.edit()
                if (hadTheme) edit.putString(selectedTheme.key, checkNotNull(oldThemeRaw)) else edit.remove(selectedTheme.key)
                check(edit.commit())
            }
            cleanup { if (session != null) main { RimeDaemon.destroySession(javaClass.name) } }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", failure?.let { "FAIL: ${it.stackTraceToString()}\n" } ?: "PASS: real quick-bar transition\n")
        instrumentation.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
