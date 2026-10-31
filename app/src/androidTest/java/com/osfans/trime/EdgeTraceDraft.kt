/* Test-only event metadata, never user text. Remove in finally. */
package com.osfans.trime

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

internal class EdgeTraceDraft(private val list: RecyclerView) : AutoCloseable {
    private val events = ArrayList<String>()
    private val touch = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (events.size < 256) events += "touch=${e.actionMasked} x=${e.x.toInt()} y=${e.y.toInt()} state=${rv.scrollState}"
            return false
        }
    }
    private val scroll = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (events.size < 256) events += "scroll_state=$newState"
        }
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (events.size < 256) events += "scrolled=$dx,$dy"
        }
    }
    init { list.addOnItemTouchListener(touch); list.addOnScrollListener(scroll) }
    fun snapshot(): List<String> = events.toList()
    override fun close() { list.removeOnItemTouchListener(touch); list.removeOnScrollListener(scroll) }
}
