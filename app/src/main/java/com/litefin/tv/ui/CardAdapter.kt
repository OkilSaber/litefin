package com.litefin.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.Item

/** Poster (2:3) or wide (16:9) cards, used by home rows and library grids. */
class CardAdapter(
    private val onClick: (Item) -> Unit,
    private val onLongClick: ((Item) -> Unit)? = null,
    private val onBind: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<CardAdapter.VH>() {

    val items = ArrayList<Item>()

    fun submit(list: List<Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Item>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position].isWide) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.card, parent, false)
        val d = parent.resources.displayMetrics.density
        val w = ((if (viewType == 1) 224 else 124) * d).toInt()
        val h = ((if (viewType == 1) 126 else 186) * d).toInt()
        v.layoutParams.width = w + (10 * d).toInt()
        v.findViewById<View>(R.id.frame).apply { layoutParams.height = h; clipRounded() }
        return VH(v, w, h)
    }

    companion object {
        /** Full height (with margins) of a card of this kind, measured once so rows can have a fixed height. */
        fun cardHeight(parent: ViewGroup, wide: Boolean): Int {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.card, parent, false)
            val d = parent.resources.displayMetrics.density
            v.findViewById<View>(R.id.frame).layoutParams.height = ((if (wide) 126 else 186) * d).toInt()
            v.findViewById<TextView>(R.id.name).text = "Ag"
            v.findViewById<TextView>(R.id.sub).text = "Ag"
            val width = View.MeasureSpec.makeMeasureSpec(((if (wide) 234 else 134) * d).toInt(), View.MeasureSpec.EXACTLY)
            v.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            return v.measuredHeight + lp.topMargin + lp.bottomMargin
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.item = item
        holder.name.text = item.name
        holder.sub.text = item.subtitle
        holder.image.loadUrl(Api.imageUrl(item, holder.w), holder.w, holder.h)
        bindProgress(holder.itemView, item, holder.w)
        onBind?.invoke(position)
    }

    inner class VH(v: View, val w: Int, val h: Int) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        var item: Item? = null

        init {
            v.focusStyle(name)
            v.setOnClickListener { item?.let(onClick) }
            if (onLongClick != null) v.setOnLongClickListener { item?.let(onLongClick); true }
        }
    }
}
