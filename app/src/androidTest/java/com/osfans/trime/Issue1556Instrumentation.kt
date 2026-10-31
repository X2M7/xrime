/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.SymbolHistory
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.symbol.LiquidLayout
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.File
import java.util.UUID

/** #1556: real system pointers at list boundaries, using only a disposable fixture. */
class Issue1556Instrumentation : Instrumentation() {
    private var liquidOnly = false
    override fun onCreate(arguments: Bundle?) {
        liquidOnly = arguments?.getString("section") == "liquid"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        var failure: Throwable? = null
        var activity: Activity? = null
        var session: RimeSession? = null
        var service: TrimeInputMethodService? = null
        var oldSchema: String? = null
        var oldAscii: Boolean? = null
        var restoreTheme: (() -> Unit)? = null
        var source: File? = null
        var artifact: File? = null
        var ownsSource = false
        var ownsArtifact = false
        var history: File? = null
        var historyExisted = false
        var historyBytes: ByteArray? = null
        val gesture = EdgeGestureDraft(this)
        fun <T> main(block: () -> T): T {
            var value: Result<T>? = null
            runOnMainSync { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
            .filterIsInstance<InputView>().firstOrNull { it.isShown }
        suspend fun until(label: String, check: () -> Boolean) {
            try { withTimeout(30_000) { while (!main(check)) delay(25) } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw IllegalStateException("Timed out: $label", e) }
        }
        suspend fun api(block: suspend RimeApi.() -> Unit) {
            var result: Result<Unit>? = null
            val job = main { checkNotNull(service).postRimeJob { result = runCatching { block() } } }
            withTimeout(30_000) { job.join() }
            check(!job.isCancelled)
            checkNotNull(result).getOrThrow()
        }
        fun pointerHitsRow(list: RecyclerView, row: View, visible: Rect): Boolean {
            val screen = IntArray(2)
            val window = IntArray(2)
            row.rootView.getLocationOnScreen(screen)
            row.rootView.getLocationInWindow(window)
            val listScreen = IntArray(2)
            list.getLocationOnScreen(listScreen)
            return list.findChildViewUnder(
                visible.exactCenterX() + screen[0] - window[0] - listScreen[0],
                visible.exactCenterY() + screen[1] - window[1] - listScreen[1],
            ) === row
        }
        fun visibleItem(list: RecyclerView, predicate: (View) -> Boolean): View? {
            val bounds = Rect()
            if (!list.getGlobalVisibleRect(bounds)) return null
            return (0 until list.childCount).asSequence().map(list::getChildAt).firstOrNull { row ->
                val visible = Rect()
                row.isShown && row.getGlobalVisibleRect(visible) && visible.width() == row.width &&
                    visible.height() == row.height && bounds.contains(visible) && predicate(row) &&
                    pointerHitsRow(list, row, visible)
            }
        }
        fun rowText(row: View): String = descendants(row).filterIsInstance<TextView>()
            .firstOrNull { it.isShown && it.text.isNotEmpty() }?.text?.toString().orEmpty()
        fun phase(name: String) = sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
        try {
            waitForIdleSync()
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(File(checkNotNull(targetContext.getExternalFilesDir(null)), "runtime-audit-dedicated").isFile)
            val selected = ThemeManager.prefs.selectedTheme
            val original = selected.getValue()
            val hadPreference = selected.sharedPreferences.contains(selected.key)
            val originalRaw = selected.sharedPreferences.getString(selected.key, null)
            restoreTheme = {
                runBlocking { check(ThemeManager.selectTheme(original) == original) }
                val edit = selected.sharedPreferences.edit()
                if (hadPreference) edit.putString(selected.key, checkNotNull(originalRaw)) else edit.remove(selected.key)
                check(edit.commit())
            }
            val themeId = "edge1556-${UUID.randomUUID().toString().replace("-", "")}.trime"
            source = File(DataManager.userDataDir, "$themeId.yaml")
            artifact = File(DataManager.stagingDir, "$themeId.yaml")
            check(!checkNotNull(artifact).exists() && checkNotNull(source).createNewFile())
            ownsSource = true
            ownsArtifact = true
            val base = targetContext.assets.open("shared/trime.yaml").bufferedReader().use { it.readText() }
            checkNotNull(source).writeText(LiquidFixtureDraft.theme(base))
            history = TrimeApplication.getInstance().directBootAwareContext.filesDir.resolve(SymbolHistory.FILE_NAME)
            historyExisted = checkNotNull(history).exists()
            historyBytes = if (historyExisted) checkNotNull(history).readBytes() else null
            session = main { RimeDaemon.createSession(javaClass.name) }
            runBlocking {
                withTimeout(120_000) { checkNotNull(session).runOnReady { } }
                ThemeManager.init(targetContext.resources.configuration)
                check(ThemeManager.selectTheme(themeId) == themeId)
            }
            val host = startActivitySync(Intent(targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            val imm = host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            val editor = main { EditText(host).apply { id = View.generateViewId(); host.setContentView(this); requestFocus(); imm.showSoftInput(this, 0) } }
            runBlocking {
                until("editor and visible IME") {
                    if (editor.hasWindowFocus()) imm.showSoftInput(editor, 0)
                    service = input()?.di?.direct?.instance<TrimeInputMethodService>()
                        ?.takeIf { it.currentInputEditorInfo?.fieldId == editor.id }
                    service != null && editor.hasFocus() && editor.hasWindowFocus() &&
                        ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                var previousBounds: Rect? = null
                var stableSince = 0L
                until("drawn and stable IME") {
                    val view = input()
                    val bounds = Rect()
                    val current = if (view != null && view.isShown && view.isLaidOut && view.getGlobalVisibleRect(bounds)) bounds else null
                    val now = SystemClock.uptimeMillis()
                    if (current == null || current != previousBounds) stableSince = now
                    previousBounds = current
                    current != null && now - stableSince >= 500
                }
                api {
                    oldSchema = selectedSchemaId()
                    oldAscii = getRuntimeOption("ascii_mode")
                    clearComposition()
                    check(selectSchema("luna_pinyin_simp"))
                    setRuntimeOption("ascii_mode", false)
                }
                val root = main { checkNotNull(input()) }
                val manager = main { root.di.direct.instance<BoardWindowManager>() }
                val compact = main { root.di.direct.instance<CompactCandidateDelegate>() }
                var owner = main { checkNotNull(service).editorToken }
                fun candidateState(): String = main {
                    val currentService = checkNotNull(service)
                    val selectionUpdates = TrimeInputMethodService::class.java.getDeclaredField("cursorUpdateIndex")
                        .apply { isAccessible = true }.getInt(currentService)
                    val native = RimeDaemon.readCached {
                        "schema=${statusCached.schemaId} ascii=${statusCached.isAsciiMode} composing=${statusCached.isComposing}"
                    }
                    "owner=${currentService.isCurrentEditor(owner)} focus=${editor.hasFocus()} " +
                        "windowFocus=${editor.hasWindowFocus()} updates=$selectionUpdates " +
                        "snapshotValid=${compact.adapter.snapshot.isValid} count=${compact.adapter.itemCount} " +
                        "hideBar=${AppPrefs.defaultInstance().keyboard.hideInputBar.getValue()} " +
                        "popup=${AppPrefs.defaultInstance().candidates.mode.getValue()} native=[$native]"
                }

                suspend fun awaitStableOwner(requireCandidates: Boolean) {
                    var stableOwner = -1L
                    var ownerStableSince = 0L
                    until("candidate editor owner stable") {
                        val currentService = checkNotNull(service)
                        val currentOwner = currentService.editorToken
                        val now = SystemClock.uptimeMillis()
                        if (currentOwner != stableOwner) {
                            stableOwner = currentOwner
                            ownerStableSince = now
                        }
                        editor.hasFocus() && editor.hasWindowFocus() && currentService.isCurrentEditor(currentOwner) &&
                            (!requireCandidates || (compact.adapter.snapshot.isValid && compact.adapter.itemCount > 0)) &&
                            now - ownerStableSince >= 500
                    }
                    owner = main { checkNotNull(service).editorToken }
                }

                suspend fun candidate(direction: Int, held: Boolean, control: Boolean = false) {
                    main { manager.attachWindow(KeyboardWindow); editor.setText("") }
                    awaitStableOwner(requireCandidates = false)
                    val beforeInput = candidateState()
                    api { clearComposition(); "ni".forEach { check(processKey(it.code)) } }
                    val afterInput = candidateState()
                    try {
                        until("candidate snapshot") { compact.adapter.snapshot.isValid && compact.adapter.itemCount > 0 }
                    } catch (e: Throwable) {
                        sendStatus(0, Bundle().apply {
                            putString("stream", "TRACE candidate setup before=[$beforeInput] after=[$afterInput] timeout=[${candidateState()}]\n")
                        })
                        throw e
                    }
                    awaitStableOwner(requireCandidates = true)
                    val window = main { FlexboxUnrolledCandidateWindow(root.di).also { manager.attachWindow(it) } }
                    until("expanded list") { manager.isAttached(window) && window.adapter.snapshot == compact.adapter.snapshot && window.adapter.itemCount > 0 }
                    val list = main { checkNotNull(manager.view.findViewById<UnrolledCandidateLayout>(R.id.unrolled_candidate_view)).recyclerView }
                    until("candidate list overflows") { list.isShown && list.isLaidOut && !list.hasPendingAdapterUpdates() && list.canScrollVertically(1) }
                    val trace = main { EdgeTraceDraft(list) }
                    val pagingDiagnostics = main { CandidatePagingDiagnostics(list, window.adapter, window) }
                    val pagingSamples = ArrayDeque<String>()
                    var latestLoadStates: CombinedLoadStates? = null
                    val loadListener: (CombinedLoadStates) -> Unit = { latestLoadStates = it }
                    main { window.adapter.addLoadStateListener(loadListener) }
                    try {
                        if (latestLoadStates == null) {
                            val initialLoadStates = withTimeout(10_000) { window.adapter.loadStateFlow.first() }
                            main { if (latestLoadStates == null) latestLoadStates = initialLoadStates }
                        }
                        main { pagingDiagnostics.attach() }
                    } catch (e: Throwable) {
                        main { window.adapter.removeLoadStateListener(loadListener); trace.close() }
                        throw e
                    }
                    var expectedText: String? = null
                    var expectedIndex: Int? = null
                    var displayedCandidates: List<Pair<Int, String>> = emptyList()
                    fun windowState(): String = main {
                        val current = BoardWindowManager::class.java.getDeclaredField("currentWindow")
                            .apply { isAccessible = true }.get(manager)
                        "attached=${manager.isAttached(window)} currentWindow=${current?.javaClass?.simpleName} " +
                            "ownerExpected=$owner ownerNow=${checkNotNull(service).editorToken} " +
                            "ownerValid=${checkNotNull(service).isCurrentEditor(owner)} " +
                            "viewShown=${list.isShown} scroll=${list.scrollState} compactValid=${compact.adapter.snapshot.isValid} " +
                            "candidateValid=${window.adapter.snapshot.isValid} snapshotsMatch=${window.adapter.snapshot == compact.adapter.snapshot} " +
                            "scrollBefore=${list.canScrollVertically(-1)} scrollAfter=${list.canScrollVertically(1)} " +
                            "load=$latestLoadStates offset=${window.adapter.offset} total=${window.adapter.total} " +
                            "first=${window.adapter.snapshot().items.firstOrNull()?.index} last=${window.adapter.snapshot().items.lastOrNull()?.index}"
                    }
                    var beforeDrag = "not reached"
                    try {
                        if (!control) {
                            gesture.atEdge(list, EdgeGestureDraft.Axis.VERTICAL, direction, observe = { attempt, stage ->
                                if (pagingSamples.size == 12) pagingSamples.removeFirst()
                                pagingSamples.addLast("attempt=$attempt stage=$stage ${pagingDiagnostics.snapshot()} load=$latestLoadStates")
                            }) { physicalEdge ->
                                if (direction < 0) {
                                    main {
                                        val sourceState = latestLoadStates?.source
                                        physicalEdge && sourceState?.refresh is LoadState.NotLoading &&
                                            (sourceState.prepend as? LoadState.NotLoading)?.endOfPaginationReached == true
                                    }
                                } else {
                                    val boundary = main {
                                        val last = window.adapter.snapshot().items.lastOrNull()?.index
                                        val manager = list.layoutManager
                                        val finalRowVisible = last != null && manager != null && (0 until list.childCount).any { index ->
                                            val child = list.getChildAt(index)
                                            val holder = list.getChildViewHolder(child) as? CandidateViewHolder
                                            holder?.idx == last && holder.snapshot == window.adapter.snapshot &&
                                                manager.getDecoratedBottom(child) <= list.height - list.paddingBottom &&
                                                manager.getDecoratedTop(child) >= list.paddingTop
                                        }
                                        if (finalRowVisible) last + 1 to window.adapter.snapshot else null
                                    }
                                    var count: Int? = null
                                    if (boundary != null) api { count = getCandidates(boundary.first, 48, boundary.second).size }
                                    boundary != null && count == 0
                                }
                            }
                        }
                        beforeDrag = windowState()
                        val beforeOffset = main { list.computeVerticalScrollOffset() }
                        gesture.drag(list, EdgeGestureDraft.Axis.VERTICAL, if (control) 1 else direction, held)
                        val expected = main {
                            check(manager.isAttached(window) && checkNotNull(service).isCurrentEditor(owner))
                            if (!control && direction < 0) check(!list.canScrollVertically(direction))
                            if (!control && direction > 0) check(list.computeVerticalScrollOffset() == beforeOffset)
                            val row = checkNotNull(visibleItem(list) { view -> list.getChildViewHolder(view) is CandidateViewHolder })
                            val holder = list.getChildViewHolder(row) as CandidateViewHolder
                            check(holder.snapshot == compact.adapter.snapshot && holder.text.isNotEmpty())
                            val rowBounds = Rect()
                            check(row.getGlobalVisibleRect(rowBounds))
                            val rootScreen = IntArray(2)
                            val rootWindow = IntArray(2)
                            row.rootView.getLocationOnScreen(rootScreen)
                            row.rootView.getLocationInWindow(rootWindow)
                            rowBounds.offset(rootScreen[0] - rootWindow[0], rootScreen[1] - rootWindow[1])
                            val listLocation = IntArray(2)
                            list.getLocationOnScreen(listLocation)
                            val hit = list.findChildViewUnder(
                                rowBounds.exactCenterX() - listLocation[0],
                                rowBounds.exactCenterY() - listLocation[1],
                            )
                            check(hit === row) {
                                val hitIndex = hit?.let { (list.getChildViewHolder(it) as? CandidateViewHolder)?.idx }
                                "Candidate target does not match pointer: bounds=$rowBounds listScreen=${listLocation.toList()} " +
                                    "expectedIndex=${holder.idx} hitIndex=$hitIndex"
                            }
                            expectedIndex = holder.idx
                            displayedCandidates = window.adapter.snapshot().items.map { it.index to it.candidate.text }
                            row to holder.text
                        }
                        expectedText = expected.second
                        gesture.tap(expected.first)
                        until("candidate click after edge") { editor.text.toString() == expected.second }
                        delay(300)
                        check(main { editor.text.toString() == expected.second })
                        phase("candidate edge=$direction held=$held control=$control")
                    } catch (e: Throwable) {
                        if (!control && direction > 0 && e.message == "List did not reach the requested edge") {
                            val beforeHint = main { pagingDiagnostics.snapshot() }
                            main {
                                val positions = (0 until list.childCount).map { list.getChildAdapterPosition(list.getChildAt(it)) }
                                    .filter { it in 0 until window.adapter.itemCount }
                                if (positions.isNotEmpty()) {
                                    window.adapter.accessVisibleRange(positions.min(), positions.max(), window.adapter.snapshot)
                                }
                            }
                            delay(1_000)
                            val afterHint = main { pagingDiagnostics.snapshot() }
                            val boundary = main {
                                val last = window.adapter.snapshot().items.lastOrNull()?.index
                                last?.let { it + 1 to window.adapter.snapshot }
                            }
                            var nextCount: Int? = null
                            var nextError: String? = null
                            try {
                                if (boundary != null) api { nextCount = getCandidates(boundary.first, 48, boundary.second).size }
                            } catch (diagnosticError: Throwable) {
                                nextError = diagnosticError.javaClass.simpleName
                                e.addSuppressed(diagnosticError)
                            }
                            sendStatus(0, Bundle().apply {
                                putString("stream", "DIAGNOSTIC ONLY explicit paging hint before=[$beforeHint] after=[$afterHint] nativeNextIndex=${boundary?.first} nativeNextCount=$nextCount nativeError=$nextError\n")
                            })
                        }
                        val editorState = main {
                            val actual = editor.text.toString()
                            val expected = expectedText
                            "actualLength=${actual.length} expectedLength=${expected?.length} " +
                                "equals=${expected != null && actual == expected} " +
                                "startsWith=${expected != null && actual.startsWith(expected)} " +
                                "endsWith=${expected != null && actual.endsWith(expected)} " +
                                "expectedIndex=$expectedIndex matchingIndices=${displayedCandidates.filter { it.second == actual }.map { it.first }} " +
                                "selection=${editor.selectionStart},${editor.selectionEnd}"
                        }
                        sendStatus(0, Bundle().apply {
                            putString("stream", "TRACE candidate before=[$beforeDrag] after=[${windowState()}] input=[${candidateState()}] editor=[$editorState] paging=$pagingSamples events=${main { trace.snapshot() }}\n")
                        })
                        throw e
                    } finally { main { window.adapter.removeLoadStateListener(loadListener); pagingDiagnostics.detach(); trace.close() } }
                }
                if (!liquidOnly) {
                    candidate(1, held = false, control = true)
                    for (direction in listOf(-1, 1)) for (held in listOf(false, true)) candidate(direction, held)
                }
                api { clearComposition() }

                owner = main { checkNotNull(service).editorToken }
                val liquid = main { root.di.direct.instance<LiquidWindow>() }
                main { manager.attachWindow(LiquidWindow); liquid.setDataByIndex(0) }
                until("liquid window") { manager.isAttached(liquid) && descendants(manager.view).filterIsInstance<LiquidLayout>().any() }
                val layout = main { descendants(manager.view).filterIsInstance<LiquidLayout>().single() }
                suspend fun liquidCase(tabs: Boolean, direction: Int, held: Boolean, control: Boolean = false) {
                    main { liquid.setDataByIndex(0) }
                    val list = if (tabs) layout.tabsUi.root else layout.recyclerView
                    until("liquid initial data") { manager.isAttached(liquid) && list.isShown && list.isLaidOut && !list.hasPendingAdapterUpdates() && (list.adapter?.itemCount ?: 0) > 0 }
                    val trace = main { EdgeTraceDraft(list) }
                    var expectedIndex: Int? = null
                    try {
                        val axis = if (tabs) EdgeGestureDraft.Axis.HORIZONTAL else EdgeGestureDraft.Axis.VERTICAL
                        if (!control) gesture.atEdge(list, axis, direction) { physicalEdge -> physicalEdge }
                        gesture.drag(list, axis, if (control) 1 else direction, held)
                        until("liquid item animation settled") { list.itemAnimator?.isRunning != true && !list.hasPendingAdapterUpdates() }
                        val item = main {
                            check(manager.isAttached(liquid) && checkNotNull(service).isCurrentEditor(owner))
                            val row = checkNotNull(visibleItem(list) { rowText(it).startsWith("EDGE") && rowText(it) != "EDGEINDEX" })
                            val label = rowText(row)
                            val index = label.removePrefix("EDGE").toInt()
                            check(index in 0 until LiquidFixtureDraft.tabCount)
                            val rowBounds = Rect()
                            check(row.getGlobalVisibleRect(rowBounds))
                            val rootScreen = IntArray(2)
                            val rootWindow = IntArray(2)
                            row.rootView.getLocationOnScreen(rootScreen)
                            row.rootView.getLocationInWindow(rootWindow)
                            rowBounds.offset(rootScreen[0] - rootWindow[0], rootScreen[1] - rootWindow[1])
                            val listLocation = IntArray(2)
                            list.getLocationOnScreen(listLocation)
                            val hit = list.findChildViewUnder(rowBounds.exactCenterX() - listLocation[0], rowBounds.exactCenterY() - listLocation[1])
                            check(hit === row) { "Liquid target does not match pointer: bounds=$rowBounds listScreen=${listLocation.toList()}" }
                            expectedIndex = index
                            row to index
                        }
                        gesture.tap(item.first)
                        until("liquid selected target") {
                            manager.isAttached(liquid) && liquid.currentDataType == LiquidKeyboard.DataType.SINGLE &&
                                (0 until layout.recyclerView.childCount).any { rowText(layout.recyclerView.getChildAt(it)) == LiquidFixtureDraft.tagSymbol(item.second) }
                        }
                        phase("liquid ${if (tabs) "tabs" else "body"} edge=$direction held=$held control=$control")
                    } catch (e: Throwable) {
                        val state = main {
                            val matches = expectedIndex?.let { index ->
                                (0 until layout.recyclerView.childCount).count { rowText(layout.recyclerView.getChildAt(it)) == LiquidFixtureDraft.tagSymbol(index) }
                            }
                            "attached=${manager.isAttached(liquid)} owner=${checkNotNull(service).isCurrentEditor(owner)} " +
                                "dataType=${liquid.currentDataType} expectedIndex=$expectedIndex matchingRows=$matches " +
                                "visibleRows=${layout.recyclerView.childCount}"
                        }
                        sendStatus(0, Bundle().apply { putString("stream", "TRACE liquid state=[$state]: ${main { trace.snapshot() }}\n") })
                        throw e
                    } finally { main { trace.close() } }
                }
                liquidCase(true, 1, held = false, control = true)
                liquidCase(false, 1, held = false, control = true)
                for (direction in listOf(-1, 1)) for (held in listOf(false, true)) liquidCase(true, direction, held)
                for (held in listOf(false, true)) liquidCase(false, 1, held)
            }
        } catch (error: Throwable) { failure = error }
        finally {
            fun cleanup(block: () -> Unit) { try { block() } catch (e: Throwable) { if (failure == null) failure = e else failure.addSuppressed(e) } }
            cleanup { gesture.cancelOutstandingPointer() }
            cleanup { activity?.let { main { it.finish() } } }
            cleanup {
                if (session != null && oldSchema != null && oldAscii != null) runBlocking {
                    withTimeout(30_000) { checkNotNull(session).runOnReady { clearComposition(); check(selectSchema(checkNotNull(oldSchema))); setRuntimeOption("ascii_mode", checkNotNull(oldAscii)) } }
                }
            }
            cleanup { restoreTheme?.invoke() }
            cleanup { if (session != null) main { RimeDaemon.destroySession(javaClass.name) } }
            cleanup { source?.let { if (ownsSource && it.exists()) check(it.delete()) } }
            cleanup { artifact?.let { if (ownsArtifact && it.exists()) check(it.delete()) } }
            cleanup {
                history?.let { file ->
                    if (historyExisted) check(file.exists() && file.readBytes().contentEquals(checkNotNull(historyBytes))) { "symbol history changed" }
                    else if (file.exists()) { check(file.length() == 0L); check(file.delete()) }
                }
            }
        }
        report.putBoolean("passed", failure == null)
        report.putString("stream", failure?.stackTraceToString() ?: "PASS: #1556 edge interactions on this device\n")
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
