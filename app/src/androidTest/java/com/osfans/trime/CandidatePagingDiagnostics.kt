package com.osfans.trime

import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.window.BaseUnrolledCandidateWindow

/** Test-only bounded numeric diagnostics; all calls on the UI thread. */
internal class CandidatePagingDiagnostics(
    private val list: RecyclerView,
    private val adapter: PagingCandidateViewAdapter,
    private val window: BaseUnrolledCandidateWindow,
) {
    private var consumedY = 0L
    private var scrollEvents = 0
    private val listener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) { consumedY += dy.toLong(); scrollEvents++ }
        }
    }
    fun attach() { check(Looper.myLooper() == Looper.getMainLooper()); list.addOnScrollListener(listener) }
    fun detach() { check(Looper.myLooper() == Looper.getMainLooper()); list.removeOnScrollListener(listener) }

    private fun field(name: String): String = try {
        BaseUnrolledCandidateWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(window)?.toString() ?: "null"
    } catch (e: ReflectiveOperationException) { "unavailable:${e.javaClass.simpleName}" }

    fun snapshot(): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        val manager = checkNotNull(list.layoutManager)
        data class Row(val position: Int, val global: Int, val top: Int, val bottom: Int, val coherent: Boolean)
        val rows = (0 until list.childCount).mapNotNull { i ->
            val child = list.getChildAt(i)
            val top = manager.getDecoratedTop(child)
            val bottom = manager.getDecoratedBottom(child)
            if (child.visibility != View.VISIBLE || bottom <= list.paddingTop || top >= list.height - list.paddingBottom ||
                manager.getDecoratedRight(child) <= list.paddingLeft || manager.getDecoratedLeft(child) >= list.width - list.paddingRight
            ) return@mapNotNull null
            val position = list.getChildAdapterPosition(child)
            val holder = list.getChildViewHolder(child) as? CandidateViewHolder ?: return@mapNotNull null
            val item = if (position in 0 until adapter.itemCount) adapter.peek(position) else null
            Row(position, holder.idx, top, bottom, item != null && item.index == holder.idx && item.snapshot == holder.snapshot)
        }.sortedBy { it.position }
        val loaded = adapter.snapshot().items
        return "children=${list.childCount} visible=${rows.size} first=${rows.firstOrNull()} last=${rows.lastOrNull()} " +
            "loadedCount=${loaded.size} loadedFirst=${loaded.firstOrNull()?.index} loadedLast=${loaded.lastOrNull()?.index} " +
            "itemCount=${adapter.itemCount} total=${adapter.total} offset=${adapter.offset} " +
            "scrollOffset=${list.computeVerticalScrollOffset()} scrollExtent=${list.computeVerticalScrollExtent()} " +
            "scrollRange=${list.computeVerticalScrollRange()} canBefore=${list.canScrollVertically(-1)} " +
            "canAfter=${list.canScrollVertically(1)} scrollState=${list.scrollState} consumedY=$consumedY events=$scrollEvents " +
            "laidOut=${list.isLaidOut} computing=${list.isComputingLayout} requested=${list.isLayoutRequested} " +
            "pendingUpdates=${list.hasPendingAdapterUpdates()} pendingIndex=${field("pendingScrollIndex")} " +
            "pendingPosition=${field("pendingScrollPosition")} restoring=${field("restoringViewport")} " +
            "bookmark=${field("hasVisibleBookmark")} remembered=${field("rememberedIndex")}"
    }
}
