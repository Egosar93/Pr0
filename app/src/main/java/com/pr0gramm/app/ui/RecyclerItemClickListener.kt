package com.pr0gramm.app.ui


import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import rx.Observable
import rx.subjects.PublishSubject

class RecyclerItemClickListener(private val recyclerView: RecyclerView) {
    private val mGestureDetector: GestureDetector

    private val touchListener = Listener()
    private val scrollListener = ScrollListener()

    private val itemClickedSubject = PublishSubject.create<View>()
    private val itemLongClickedSubject = PublishSubject.create<View>()
    private val itemLongClickEndedSubject = PublishSubject.create<Unit>()

    private var longClickEnabled: Boolean = false
    private var longPressTriggered: Boolean = false

    val itemClicked get() = itemClickedSubject as Observable<View>
    val itemLongClicked get() = itemLongClickedSubject as Observable<View>
    val itemLongClickEnded get() = itemLongClickEndedSubject as Observable<Unit>

    init {
        mGestureDetector = GestureDetector(recyclerView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (longClickEnabled) {
                    val childView = recyclerView.findChildViewUnder(e.x, e.y)
                    if (childView != null) {
                        longPressTriggered = true
                        itemLongClickedSubject.onNext(childView)
                    }
                }
            }
        })

        recyclerView.addOnItemTouchListener(touchListener)
        recyclerView.addOnScrollListener(scrollListener)
    }

    fun enableLongClick(enabled: Boolean) {
        longClickEnabled = enabled
        longPressTriggered = longPressTriggered and enabled
    }

    private inner class Listener : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(view: RecyclerView, e: MotionEvent): Boolean {
            val childView = view.findChildViewUnder(e.x, e.y)

            if (childView != null && mGestureDetector.onTouchEvent(e)) {
                itemClickedSubject.onNext(childView)
            }

            // a long press might have been triggered between the last touch event
            // and the current. we use this info to start tracking the current touch
            // if that happened.
            val intercept = longClickEnabled && longPressTriggered
                    && e.action != MotionEvent.ACTION_DOWN

            longPressTriggered = false

            if (intercept) {
                // actually this click right now might have triggered the long press
                onTouchEvent(view, e)
            }

            return intercept
        }

        override fun onTouchEvent(view: RecyclerView, motionEvent: MotionEvent) {
            when (motionEvent.action) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_POINTER_UP ->
                    itemLongClickEndedSubject.onNext(Unit)
            }
        }
    }

    private inner class ScrollListener : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING, RecyclerView.SCROLL_STATE_SETTLING ->
                    recyclerView.removeOnItemTouchListener(touchListener)

                RecyclerView.SCROLL_STATE_IDLE -> {
                    // ensure that the listener is only added once
                    recyclerView.removeOnItemTouchListener(touchListener)
                    recyclerView.addOnItemTouchListener(touchListener)
                }
            }
        }
    }
}
