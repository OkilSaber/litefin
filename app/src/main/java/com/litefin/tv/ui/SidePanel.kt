package com.litefin.tv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.litefin.tv.R

class PanelItem(val label: String, val icon: Int = 0, val checked: Boolean = false)

/**
 * Right-side panel used for menus and choices instead of system dialogs. Added on top of the
 * activity content, so no extra window. Enters in 180 ms ease-out (slide + fade), leaves faster
 * (120 ms): slow where the user reads, fast where the system responds.
 */
class SidePanel private constructor(private val activity: BaseActivity, inheritedFocus: View?) {
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private val page = content.getChildAt(0) as ViewGroup
    private val root = LayoutInflater.from(activity).inflate(R.layout.side_panel, content, false)
    private val panel = root.findViewById<View>(R.id.panel)
    private val scrim = root.findViewById<View>(R.id.panel_scrim)
    private val previousFocus = inheritedFocus ?: activity.currentFocus
    private val slide = 48 * activity.resources.displayMetrics.density
    private var closing = false

    private fun open(title: String, subtitle: String?, items: List<PanelItem>, focus: Int, onPick: (Int) -> Unit) {
        root.findViewById<TextView>(R.id.panel_title).text = title
        root.findViewById<TextView>(R.id.panel_subtitle).apply { text = subtitle; gone(subtitle == null) }
        val list = root.findViewById<LinearLayout>(R.id.panel_items)
        val inflater = LayoutInflater.from(activity)
        items.forEachIndexed { i, item ->
            val row = inflater.inflate(R.layout.panel_row, list, false)
            row.findViewById<TextView>(R.id.row_label).text = item.label
            if (item.icon != 0) row.findViewById<ImageView>(R.id.row_icon).apply { setImageResource(item.icon); gone(false) }
            row.findViewById<View>(R.id.row_check).gone(!item.checked)
            row.setOnClickListener { close { onPick(i) } }
            // Keep the D-pad inside the panel.
            row.setOnKeyListener { _, code, event ->
                event.action == KeyEvent.ACTION_DOWN && when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    KeyEvent.KEYCODE_DPAD_UP -> i == 0
                    KeyEvent.KEYCODE_DPAD_DOWN -> i == items.lastIndex
                    else -> false
                }
            }
            list.addView(row)
        }
        scrim.setOnClickListener { close() }

        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        content.addView(root)
        activity.panel = this
        panel.translationX = slide
        panel.alpha = 0f
        scrim.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(180).setInterpolator(EASE_OUT).start()
        scrim.animate().alpha(1f).setDuration(180).setInterpolator(EASE_OUT).start()
        list.getChildAt(focus.coerceIn(0, maxOf(0, items.lastIndex)))?.requestFocus()
    }

    /** Removes the panel immediately (no animation): used when another panel replaces it. */
    private fun dismissNow(): View? {
        closing = true
        panel.animate().cancel()
        scrim.animate().cancel()
        content.removeView(root)
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (activity.panel === this) activity.panel = null
        return previousFocus
    }

    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(120).start()
        panel.animate().translationX(slide).alpha(0f).setDuration(120).setInterpolator(EASE_OUT).withEndAction {
            content.removeView(root)
            page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (activity.panel === this) activity.panel = null
            previousFocus?.requestFocus()
            then?.invoke()
        }.start()
    }

    companion object {
        fun show(activity: BaseActivity, title: String, subtitle: String? = null, items: List<PanelItem>,
                 focus: Int = 0, onPick: (Int) -> Unit) {
            val inherited = activity.panel?.dismissNow()
            SidePanel(activity, inherited).open(title, subtitle, items, focus, onPick)
        }
    }
}
