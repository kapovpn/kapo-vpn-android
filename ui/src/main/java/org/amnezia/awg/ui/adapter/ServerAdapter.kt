package org.amnezia.awg.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ItemServerBinding
import org.amnezia.awg.model.Server

class ServerAdapter(
    private var items: List<Any>,
    private var selectedId: Int,
    private val onServerClick: (Server) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERVER = 1
    }

    fun setSelected(id: Int) { selectedId = id; notifyDataSetChanged() }
    fun updateList(newItems: List<Any>) { items = newItems; notifyDataSetChanged() }

    override fun getItemViewType(p: Int) = if (items[p] is String) TYPE_HEADER else TYPE_SERVER
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_region_header, parent, false)
            HeaderViewHolder(v)
        } else {
            ServerViewHolder(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(items[position] as String)
            is ServerViewHolder -> holder.bind(items[position] as Server)
        }
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(region: String) { itemView.findViewById<TextView>(R.id.tvRegion).text = region }
    }

    inner class ServerViewHolder(private val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(server: Server) {
            b.tvFlag.text    = server.flag
            b.tvCity.text    = server.city
            b.tvCountry.text = "${server.country} · ${server.code}"

            val isSelected = server.id == selectedId

            if (!server.active) {
                b.tvComingSoon.visibility = View.VISIBLE
                b.tvChain.visibility      = View.GONE
                b.pingLayout.visibility   = View.GONE
                b.tvCity.alpha  = 0.4f
                b.tvFlag.alpha  = 0.4f
                b.root.alpha    = 0.55f
                b.activeBar.visibility = View.GONE
                b.ivCheck.visibility   = View.GONE
                b.root.isClickable     = false
            } else {
                b.tvComingSoon.visibility = View.GONE
                b.tvChain.visibility      = View.GONE   // flight-path removed: clean rows per mockup
                b.pingLayout.visibility   = View.VISIBLE
                b.tvCity.alpha  = 1f
                b.tvFlag.alpha  = 1f
                b.root.alpha    = 1f
                b.root.isClickable = true

                // Chain description
                b.tvChain.text = "YOU ──▶ 🇮🇸 AWG ──▶ INTERNET"

                val pingColor = when {
                    server.ping < 35  -> R.color.green_ink
                    server.ping < 120 -> R.color.amber_ink
                    else              -> R.color.coral_ink
                }
                b.tvPing.text = "${server.ping}ms"
                b.tvPing.setTextColor(b.root.context.getColor(pingColor))

                val loadColor = when {
                    server.load < 40 -> R.color.green
                    server.load < 65 -> R.color.amber
                    else             -> R.color.coral
                }
                b.loadBar.progress = server.load
                b.loadBar.progressTintList = android.content.res.ColorStateList.valueOf(
                    b.root.context.getColor(loadColor)
                )

                if (isSelected) {
                    b.activeBar.visibility = View.VISIBLE
                    b.ivCheck.visibility   = View.VISIBLE
                    b.root.setBackgroundResource(R.drawable.bg_server_row_selected)
                } else {
                    b.activeBar.visibility = View.GONE
                    b.ivCheck.visibility   = View.GONE
                    b.root.setBackgroundResource(R.drawable.bg_server_row)
                }

                b.root.setOnClickListener { onServerClick(server) }
            }
        }
    }
}
