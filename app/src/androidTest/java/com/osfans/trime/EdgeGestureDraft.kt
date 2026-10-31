/* Test-only draft.  Kept outside the project pending build coordination. */
package com.osfans.trime

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** System-dispatched pointers only: no direct child dispatch or performClick. */
internal class EdgeGestureDraft(private val instrumentation: Instrumentation) {
    enum class Axis { HORIZONTAL, VERTICAL }
    private var downTime: Long? = null
    private var point = 0f to 0f

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun send(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { check(downTime == null); downTime = now }
        val event = MotionEvent.obtain(checkNotNull(downTime), now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        point = x to y
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = null
    }

    fun cancelOutstandingPointer() {
        if (downTime != null) send(MotionEvent.ACTION_CANCEL, point.first, point.second)
    }

    private fun visibleBounds(view: View): Rect = main {
        val rect = Rect()
        check(view.isShown && view.getGlobalVisibleRect(rect))
        val root = view.rootView
        val screen = IntArray(2)
        val window = IntArray(2)
        root.getLocationOnScreen(screen)
        root.getLocationInWindow(window)
        rect.offset(screen[0] - window[0], screen[1] - window[1])
        check(rect.width() >= 24 && rect.height() >= 24)
        rect
    }

    /** direction -1 = start/top, +1 = end/bottom. */
    suspend fun drag(list: RecyclerView, axis: Axis, direction: Int, held: Boolean) {
        require(direction == -1 || direction == 1)
        val r = visibleBounds(list)
        val cx = r.exactCenterX(); val cy = r.exactCenterY()
        val length = if (axis == Axis.HORIZONTAL) r.width() else r.height()
        // Stay in the actual clipped list bounds, away from system gestures.
        val travel = length * .30f * -direction
        val startX = if (axis == Axis.HORIZONTAL) cx - travel / 2 else cx
        val startY = if (axis == Axis.VERTICAL) cy - travel / 2 else cy
        val endX = if (axis == Axis.HORIZONTAL) cx + travel / 2 else cx
        val endY = if (axis == Axis.VERTICAL) cy + travel / 2 else cy
        try {
            send(MotionEvent.ACTION_DOWN, startX, startY)
            for (i in 1..8) {
                delay(if (held) 24 else 5)
                send(MotionEvent.ACTION_MOVE, startX + (endX - startX) * i / 8, startY + (endY - startY) * i / 8)
            }
            if (held) delay(300)
            send(MotionEvent.ACTION_UP, endX, endY)
        } finally { cancelOutstandingPointer() }
        withTimeout(10_000) { while (!main { list.scrollState == RecyclerView.SCROLL_STATE_IDLE && !list.isComputingLayout && !list.hasPendingAdapterUpdates() }) delay(25) }
    }

    suspend fun atEdge(
        list: RecyclerView,
        axis: Axis,
        direction: Int,
        observe: ((Int, String) -> Unit)? = null,
        isFinalEdge: suspend (Boolean) -> Boolean,
    ) {
        repeat(50) { attempt ->
            val edge = main { if (axis == Axis.HORIZONTAL) !list.canScrollHorizontally(direction) else !list.canScrollVertically(direction) }
            // Paging loads in response to scrolling. A loaded-page boundary is not
            // necessarily the final edge; let the caller check its load state.
            if (isFinalEdge(edge)) return
            if (edge) delay(100)
            main { observe?.invoke(attempt, "before") }
            drag(list, axis, direction, held = false)
            main { observe?.invoke(attempt, "after") }
        }
        error("List did not reach the requested edge")
    }

    suspend fun tap(view: View) {
        val rect = visibleBounds(view)
        try {
            send(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
            delay(60)
            send(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
        } finally { cancelOutstandingPointer() }
    }
}
