/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.paging.PagingSource
import androidx.room.Room
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.ime.clipboard.ClipboardSearchInputConnection
import com.osfans.trime.ime.clipboard.ClipboardSearchPasteGate
import com.osfans.trime.ime.clipboard.ClipboardSearchSession

/** Exercises the actual Room query in an isolated database without touching clipboard history. */
internal object ClipboardSearchProbe {
    fun verifyConnection(instrumentation: Instrumentation) {
        var outcome: Result<Unit>? = null
        lateinit var connection: ClipboardSearchInputConnection
        val updates = mutableListOf<ClipboardSearchInputConnection.QueryUpdate>()
        var forwarded = 0
        instrumentation.runOnMainSync {
            outcome = runCatching {
                val target = object : View(instrumentation.targetContext) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        forwarded++
                        return true
                    }
                }
                connection = ClipboardSearchInputConnection(target, updates::add)
                fun snapshot() = connection.getExtractedText(null, 0)?.text?.toString()
                check(connection.commitText("\u4e2d", 1))
                check(connection.setComposingText("wen", 1))
                check(connection.setComposingText("\u6587", 1))
                check(connection.finishComposingText())
                check(snapshot() == "\u4e2d\u6587")
                check(connection.commitText("\uD83D\uDE00", 1))
                check(!connection.setSelection(3, 3)) { "Selection split a surrogate pair" }
                check(!connection.setComposingRegion(3, 4)) { "Composition split a surrogate pair" }
                check(connection.deleteSurroundingText(1, 0))
                check(snapshot() == "\u4e2d\u6587") { "Unit deletion split a supplementary character" }
                check(connection.commitText("\uD83D\uDE00", 1))
                check(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
                check(snapshot() == "\u4e2d\u6587") { "Key deletion split a supplementary character" }
                check(!connection.commitText("\uD83D", 1)) { "Unpaired surrogate was accepted" }
                check(connection.setSelection(0, 2))
                val limit = "a".repeat(ClipboardSearchInputConnection.MAX_QUERY_UNITS)
                check(connection.commitText(limit, 1))
                check(!connection.commitText("x", 1) && snapshot() == limit) { "Search limit was exceeded" }
                check(connection.setSelection(0, 1) && connection.commitText("b", 1))
                check(snapshot()?.length == limit.length) { "Same-size replacement at the limit failed" }
                check(forwarded == 0)
            }
        }
        checkNotNull(outcome).getOrThrow()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            outcome = runCatching {
                check(updates.last().text.length == ClipboardSearchInputConnection.MAX_QUERY_UNITS)
                check(connection.accepts(updates.last()))
                check(connection.setSelection(0, 1) && connection.commitText("z", 1))
                check(!connection.accepts(updates.last())) { "Superseded query update stayed valid" }
                val updateCount = updates.size
                // Keep the public interface reference as an asynchronous caller could.
                val retained: android.view.inputmethod.InputConnection = connection
                val snapshot = retained.getTextBeforeCursor(512, 0)?.toString()
                connection.dispose()
                check(!connection.accepts(updates.last()))
                check(!retained.commitText("late", 1))
                check(!retained.setComposingText("late", 1))
                check(!retained.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
                check(retained.getExtractedText(null, 0) == null && updates.size == updateCount && forwarded == 0)
                check(snapshot is String)
            }
        }
        checkNotNull(outcome).getOrThrow()

        lateinit var concurrent: ClipboardSearchInputConnection
        var concurrentUpdates = 0
        instrumentation.runOnMainSync {
            concurrent = ClipboardSearchInputConnection(View(instrumentation.targetContext)) { concurrentUpdates++ }
        }
        val firstWrite = java.util.concurrent.CountDownLatch(1)
        val continueAfterClose = java.util.concurrent.CountDownLatch(1)
        val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                check(concurrent.commitText("worker", 1))
                firstWrite.countDown()
                check(continueAfterClose.await(10, java.util.concurrent.TimeUnit.SECONDS))
                check(!concurrent.commitText("late", 1))
                check(concurrent.getExtractedText(null, 0) == null)
            } catch (failure: Throwable) {
                workerFailure.set(failure)
                firstWrite.countDown()
            }
        }
        worker.start()
        check(firstWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.runOnMainSync { concurrent.dispose() }
        val afterClose = concurrentUpdates
        continueAfterClose.countDown()
        worker.join(10_000)
        check(!worker.isAlive)
        workerFailure.get()?.let { throw it }
        instrumentation.waitForIdleSync()
        check(concurrentUpdates == afterClose) { "A queued update ran after search closed" }

        lateinit var duringCallback: ClipboardSearchInputConnection
        val callbackFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var callbackRan = false
        instrumentation.runOnMainSync {
            duringCallback = ClipboardSearchInputConnection(View(instrumentation.targetContext)) { update ->
                try {
                    callbackRan = true
                    val closer = Thread { duringCallback.dispose() }
                    closer.start()
                    closer.join(5_000)
                    check(!closer.isAlive) { "Query callback held the connection lock" }
                    check(!duringCallback.accepts(update)) { "Disposed search update stayed valid" }
                } catch (failure: Throwable) {
                    callbackFailure.set(failure)
                }
            }
            check(duringCallback.commitText("probe", 1))
        }
        instrumentation.waitForIdleSync()
        check(callbackRan)
        callbackFailure.get()?.let { throw it }

        instrumentation.runOnMainSync {
            outcome = runCatching {
                val session = ClipboardSearchSession()
                val gate = ClipboardSearchPasteGate()
                check(session.begin(10, 20, privateMode = false, composing = false))
                session.ready(10, 20, false)
                val ticket = checkNotNull(session.changed())
                check(session.restore(10, 21, privateMode = false, results = true))
                session.restored(10, 21, false)
                val local = ClipboardSearchInputConnection(View(instrumentation.targetContext)) { }
                val rejected = object : InputConnectionWrapper(local, false) {
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = false
                }
                var navigation = 0
                fun attempt(connection: InputConnection?): Boolean = gate.dispatch(
                    isCurrent = { session.acceptsResult(ticket, 10, false) },
                    send = { connection?.commitText("direct", 1) == true },
                    consume = { session.consume(ticket, 10, false, rowExists = true) },
                    afterDispatch = { navigation++ },
                )
                try {
                    check(!attempt(null) && session.acceptsResult(ticket, 10, false))
                    check(!attempt(rejected) && session.acceptsResult(ticket, 10, false))
                    check(attempt(local) && navigation == 1)
                    check(!attempt(local) && navigation == 1)
                } finally {
                    local.dispose()
                }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }

    suspend fun verify(context: Context) {
        val database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()
        try {
            val dao = database.databaseDao()
            dao.insert(DatabaseBean(text = "中文 100%_ok", time = 1))
            dao.insert(DatabaseBean(text = "中文 100Xok", time = 3))
            dao.insert(DatabaseBean(text = "pinned 中文", time = 2, pinned = true))
            dao.insert(DatabaseBean(text = null, time = 4))
            suspend fun texts(source: PagingSource<Int, DatabaseBean>): List<String?> {
                val result = source.load(PagingSource.LoadParams.Refresh(null, 16, false))
                return (result as PagingSource.LoadResult.Page<Int, DatabaseBean>).data.map { it.text }
            }
            check(texts(dao.searchBeans("中文")) == listOf("pinned 中文", "中文 100Xok", "中文 100%_ok"))
            check(texts(dao.searchBeans("%_")) == listOf("中文 100%_ok"))
            check(texts(dao.searchBeans("100")) == listOf("中文 100Xok", "中文 100%_ok"))
            check(texts(dao.searchBeans("not found")).isEmpty())
            check(texts(dao.allBeans()) == listOf("pinned 中文", null, "中文 100Xok", "中文 100%_ok"))
        } finally {
            database.close()
        }
    }
}
