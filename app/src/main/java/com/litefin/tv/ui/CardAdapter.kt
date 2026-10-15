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
        v.layoutParams.width = w + (6 * d).toInt()
        v.findViewById<View>(R.id.frame).layoutParams.height = h
        return VH(v, w, h)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.item = item
        holder.name.text = item.name
        holder.sub.text = if (item.played && item.type != "Series" && item.type != "Season") "✓ " + item.subtitle else item.subtitle
        holder.image.loadUrl(Api.imageUrl(item, holder.w), holder.w, holder.h)
        val frac = if (item.runTimeTicks > 0) item.positionTicks.toFloat() / item.runTimeTicks else 0f
        holder.progress.layoutParams.width = (holder.w * frac).toInt()
        holder.progress.visibility = if (frac > 0f) View.VISIBLE else View.GONE
        onBind?.invoke(position)
    }

    inner class VH(v: View, val w: Int, val h: Int) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val progress: View = v.findViewById(R.id.progress)
        var item: Item? = null

        init {
            v.setOnClickListener { item?.let(onClick) }
            if (onLongClick != null) v.setOnLongClickListener { item?.let(onLongClick); true }
        }
    }
}
