package biz.lungo.wifiscanner.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import biz.lungo.wifiscanner.R
import biz.lungo.wifiscanner.data.WiFi

class WiFiTrackedAdapter(private val onRemoveListener: OnRemoveListener): RecyclerView.Adapter<ViewHolder>() {

    var items: MutableList<WiFi> = mutableListOf()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wifi_tracked, parent, false)
        return ViewHolder(view, onRemoveListener)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as ViewHolder).bind(items[position])
    }

    override fun getItemCount() = items.size

    fun removeNetwork(wifi: WiFi) {
        removeItem(items.indexOf(wifi))
    }

    private fun removeItem(position: Int) {
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    class ViewHolder(itemView: View, private val onRemoveListener: OnRemoveListener): RecyclerView.ViewHolder(itemView) {
        private val tvName = itemView.findViewById<TextView>(R.id.name)
        private val btnRemove = itemView.findViewById<TextView>(R.id.btnRemove)

        fun bind(item: WiFi) {
            tvName.text = item.name
            btnRemove.setOnClickListener {
                onRemoveListener.onRemove(item)
            }
        }
    }

    interface OnRemoveListener {
        fun onRemove(wifi: WiFi)
    }
}