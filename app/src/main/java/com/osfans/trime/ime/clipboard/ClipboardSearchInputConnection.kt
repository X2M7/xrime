/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.osfans.trime.data.db.MAX_CLIPBOARD_SEARCH_UNITS
import java.util.concurrent.atomic.AtomicLong

/** Isolated, bounded search text. The private mutable editor never escapes this connection. */
internal class ClipboardSearchInputConnection(
    target: View,
    private val onQueryChanged: (QueryUpdate) -> Unit,
) : InputConnection {
    internal data class QueryUpdate(val session: Long, val revision: Long, val text: String)

    companion object {
        const val MAX_QUERY_UNITS = MAX_CLIPBOARD_SEARCH_UNITS
        private val nextSession = AtomicLong()
    }

    private val lock = Any()
    private val session = nextSession.incrementAndGet().also { check(it > 0) }
    private var revision = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val delegate = BaseInputConnection(target, true)
    private val text: Editable = checkNotNull(delegate.editable)
    private var closed = false
    private var pendingUpdate = false

    private val publish = Runnable {
        val update = synchronized(lock) {
            pendingUpdate = false
            if (closed) null else QueryUpdate(session, revision, text.toString())
        }
        // Never invoke UI code while holding the connection lock. A search controller must
        // revalidate the update before displaying it, querying, or delivering a result.
        if (update != null) onQueryChanged(update)
    }

    fun accepts(update: QueryUpdate): Boolean = synchronized(lock) {
        !closed && update.session == session && update.revision == revision
    }

    private fun changed(previous: String) {
        if (text.toString() == previous) return
        check(revision < Long.MAX_VALUE)
        revision++
        if (!pendingUpdate) {
            pendingUpdate = true
            handler.post(publish)
        }
    }

    private fun boundary(index: Int): Boolean = index in 0..text.length &&
        !(index > 0 && index < text.length && Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index]))

    private fun wellFormed(value: CharSequence): Boolean {
        if (value.length > MAX_QUERY_UNITS) return false
        var i = 0
        while (i < value.length) {
            when {
                Character.isHighSurrogate(value[i]) -> {
                    if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(value[i]) -> return false
                else -> i++
            }
        }
        return true
    }

    private fun selectedRange(): Pair<Int, Int>? {
        val a = Selection.getSelectionStart(text)
        val b = Selection.getSelectionEnd(text)
        if (!boundary(a) || !boundary(b)) return null
        return minOf(a, b) to maxOf(a, b)
    }

    private fun replacementFits(value: CharSequence): Boolean {
        if (!wellFormed(value)) return false
        val (a, b) = selectedRange() ?: return false
        val composingStart = BaseInputConnection.getComposingSpanStart(text)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(text)
        val hasComposition = composingStart >= 0 && composingEnd >= 0
        val start = if (hasComposition) minOf(composingStart, composingEnd) else a
        val end = if (hasComposition) maxOf(composingStart, composingEnd) else b
        return boundary(start) && boundary(end) && text.length.toLong() - (end - start) + value.length <= MAX_QUERY_UNITS
    }

    private fun boundedCopy(value: CharSequence?): String? {
        if (value == null) return null
        val size = value.length
        if (size > MAX_QUERY_UNITS) return null
        return try {
            buildString(size) { for (i in 0 until size) append(value[i]) }.takeIf(::wellFormed)
        } catch (_: IndexOutOfBoundsException) {
            null // A mutable input changed while it was being copied.
        }
    }

    private fun alignSelection() {
        val start = Selection.getSelectionStart(text).coerceIn(0, text.length)
        val end = Selection.getSelectionEnd(text).coerceIn(0, text.length)
        delegate.setSelection(if (boundary(start)) start else start + 1, if (boundary(end)) end else end + 1)
    }

    override fun commitText(value: CharSequence?, newCursorPosition: Int): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val safe = boundedCopy(value) ?: return@synchronized false
        if (!replacementFits(safe)) return@synchronized false
        val previous = text.toString()
        delegate.commitText(safe, newCursorPosition).also { alignSelection(); changed(previous) }
    }

    override fun setComposingText(value: CharSequence?, newCursorPosition: Int): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val safe = boundedCopy(value) ?: return@synchronized false
        if (!replacementFits(safe)) return@synchronized false
        val previous = text.toString()
        delegate.setComposingText(safe, newCursorPosition).also { alignSelection(); changed(previous) }
    }

    override fun setSelection(start: Int, end: Int): Boolean = synchronized(lock) {
        !closed && boundary(start) && boundary(end) && delegate.setSelection(start, end)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = synchronized(lock) {
        !closed && boundary(start) && boundary(end) && delegate.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean = synchronized(lock) { !closed && delegate.finishComposingText() }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = synchronized(lock) {
        if (closed || beforeLength < 0 || afterLength < 0) return@synchronized false
        val selection = selectedRange() ?: return@synchronized false
        val compStart = BaseInputConnection.getComposingSpanStart(text)
        val compEnd = BaseInputConnection.getComposingSpanEnd(text)
        val a = if (compStart >= 0 && compEnd >= 0) minOf(selection.first, compStart, compEnd) else selection.first
        val b = if (compStart >= 0 && compEnd >= 0) maxOf(selection.second, compStart, compEnd) else selection.second
        if (!boundary(a) || !boundary(b)) return@synchronized false
        var left = (a.toLong() - beforeLength).coerceAtLeast(0).toInt()
        var right = (b.toLong() + afterLength).coerceAtMost(text.length.toLong()).toInt()
        if (!boundary(left)) left--
        if (!boundary(right)) right++
        val previous = text.toString()
        delegate.deleteSurroundingText(a - left, right - b).also { changed(previous) }
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = synchronized(lock) {
        if (closed || beforeLength < 0 || afterLength < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@synchronized false
        val previous = text.toString()
        delegate.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { changed(previous) }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = synchronized(lock) {
        if (closed || !clipboardSearchKeyAllowed(event.keyCode, event.metaState)) return@synchronized false
        if (event.action == KeyEvent.ACTION_UP) return@synchronized true
        if (event.action != KeyEvent.ACTION_DOWN) return@synchronized false
        val (a, b) = selectedRange() ?: return@synchronized false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                val previous = text.toString()
                if (a != b) text.delete(a, b) else if (a > 0) text.delete(Character.offsetByCodePoints(text, a, -1), a)
                changed(previous)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val position = if (a != b) a else if (a == 0) 0 else Character.offsetByCodePoints(text, a, -1)
                delegate.setSelection(position, position)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val position = if (a != b) b else if (b == text.length) b else Character.offsetByCodePoints(text, b, 1)
                delegate.setSelection(position, position)
            }
            KeyEvent.KEYCODE_ENTER -> true
            else -> {
                val point = event.unicodeChar
                if (!Character.isValidCodePoint(point) || Character.isISOControl(point) || point == 0) false
                else commitText(String(Character.toChars(point)), 1)
            }
        }
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = synchronized(lock) {
        if (closed || length < 0) null else delegate.getTextBeforeCursor(length, flags)?.toString()
    }
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = synchronized(lock) {
        if (closed || length < 0) null else delegate.getTextAfterCursor(length, flags)?.toString()
    }
    override fun getSelectedText(flags: Int): CharSequence? = synchronized(lock) {
        if (closed) null else delegate.getSelectedText(flags)?.toString()
    }
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = synchronized(lock) {
        if (closed) null else ExtractedText().apply {
            text = this@ClipboardSearchInputConnection.text.toString()
            selectionStart = Selection.getSelectionStart(this@ClipboardSearchInputConnection.text)
            selectionEnd = Selection.getSelectionEnd(this@ClipboardSearchInputConnection.text)
        }
    }
    override fun getCursorCapsMode(reqModes: Int): Int = synchronized(lock) { if (closed) 0 else delegate.getCursorCapsMode(reqModes) }
    override fun beginBatchEdit(): Boolean = synchronized(lock) { !closed && delegate.beginBatchEdit() }
    override fun endBatchEdit(): Boolean = synchronized(lock) { !closed && delegate.endBatchEdit() }
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun performEditorAction(actionCode: Int): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): Handler? = null
    override fun closeConnection() = dispose()

    /** Callbacks are posted to the main thread and must be revalidated by their consumer. */
    fun dispose() = synchronized(lock) {
        if (!closed) {
            closed = true
            handler.removeCallbacks(publish)
            pendingUpdate = false
            text.clear()
            text.clearSpans()
        }
    }
}
