package com.litefin.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litefin.tv.R
import com.litefin.tv.api.Api
import com.litefin.tv.api.Person

/** Cast cards: photo, real name, role. Display only (focusable so the row can be scrolled). */
class PersonAdapter(private val people: List<Person>) : RecyclerView.Adapter<PersonAdapter.VH>() {
    override fun getItemCount() = people.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.card, parent, false)
        val d = parent.resources.displayMetrics.density
        val w = (104 * d).toInt()
        val h = (156 * d).toInt()
        v.layoutParams.width = w + (6 * d).toInt()
        v.findViewById<View>(R.id.frame).layoutParams.height = h
        return VH(v, w, h)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = people[position]
        holder.name.text = p.name
        holder.sub.text = p.role ?: when (p.type) {
            "Director" -> "Réalisation"
            "Writer" -> "Scénario"
            "Producer" -> "Production"
            else -> ""
        }
        holder.image.loadUrl(Api.image(p.id, "Primary", p.imageTag, holder.w), holder.w, holder.h)
    }

    class VH(v: View, val w: Int, val h: Int) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)

        init {
            v.findViewById<View>(R.id.progress).visibility = View.GONE
        }
    }
}
