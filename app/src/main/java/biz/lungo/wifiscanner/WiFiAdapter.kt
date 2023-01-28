package biz.lungo.wifiscanner

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox

class WiFiAdapter(private val storage: Storage, private val checkboxListener: CheckboxListener): RecyclerView.Adapter<WiFiAdapter.WiFiViewHolder>() {

    private var items: Set<WiFi> = emptySet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WiFiViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wifi, parent, false)
        return WiFiViewHolder(view, checkboxListener)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: WiFiViewHolder, position: Int) {
        val item = items.elementAt(position)
        val checked = storage.isChecked(item)
        holder.bind(item, checked)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setNetworks(items: Set<WiFi>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun notifyItemChanged(item: WiFi) {
        notifyItemChanged(items.indexOf(item))
    }

    class WiFiViewHolder(
        itemView: View,
        private val checkboxListener: CheckboxListener
    ): RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.name)
        private val level: TextView = itemView.findViewById(R.id.level)
        private val checkbox: MaterialCheckBox = itemView.findViewById(R.id.checkbox)

        fun bind(item: WiFi, isChecked: Boolean) {
            name.text = item.name
            level.text = itemView.context.getString(R.string.db_format, item.level.toString())
            checkbox.isChecked = isChecked
            checkbox.setOnCheckedChangeListener { view, checked ->
                if (view.isPressed) checkboxListener.onCheckedChange(checked, item)
            }
        }
    }

    interface CheckboxListener {
        fun onCheckedChange(checked: Boolean, item: WiFi)
    }
}