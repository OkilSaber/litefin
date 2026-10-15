package com.litefin.tv.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal row for D-pad use: left/right never leave the row (at its ends, or when the next
 * card isn't laid out yet because keys are pressed quickly), they scroll it instead.
 */
class RowLayoutManager(context: Context) : LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {
    // Rows are rebound with notifyDataSetChanged: skip the default fade/move animations on every card.
    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        view.itemAnimator = null
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null
        val card = findContainingItemView(focused) ?: return null
        val next = getPosition(card) + if (direction == View.FOCUS_RIGHT) 1 else -1
        if (next < 0 || next >= itemCount) return focused
        findViewByPosition(next)?.let { return it }
        scrollToPosition(next)
        card.post { findViewByPosition(next)?.requestFocus() }
        return focused
    }
}
