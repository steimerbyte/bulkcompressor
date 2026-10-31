package com.steimerbyte.bulkcompressor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.job.CompressionJob
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind

/**
 * Dateiliste. RecyclerView statt einer LinearLayout mit tausend
 * TextViews: es existieren immer nur die sichtbaren Zeilen, und es wird
 * kein Vorschaubild geladen - die Galerie-Ordner koennen riesig sein.
 */
class MediaAdapter(
    private val onToggle: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.ItemHolder>() {

    private val items = ArrayList<MediaItem>(0)
    private val selected = HashSet<String>()

    class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mark: TextView = view.findViewById(R.id.row_mark)
        val name: TextView = view.findViewById(R.id.row_name)
        val meta: TextView = view.findViewById(R.id.row_meta)
        val size: TextView = view.findViewById(R.id.row_size)
    }

    fun submit(newItems: List<MediaItem>, selectedIds: Set<String>) {
        items.clear()
        items.addAll(newItems)
        selected.clear()
        selected.addAll(selectedIds)
        notifyDataSetChanged()
    }

    fun setSelected(id: String, isSelected: Boolean) {
        if (isSelected) selected.add(id) else selected.remove(id)
        val index = items.indexOfFirst { it.docId == id }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_row, parent, false)
        return ItemHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ItemHolder, position: Int) {
        val item = items[position]
        val isSelected = selected.contains(item.docId)
        holder.mark.text = if (isSelected) "■" else "□"
        holder.mark.setTextColor(
            if (isSelected) 0xFFFFFFFF.toInt() else 0xFF6B6B6B.toInt()
        )
        holder.name.text = item.displayName
        holder.meta.text = buildString {
            append(if (item.kind == MediaKind.VIDEO) "VIDEO" else "BILD")
            if (item.lastModified > 0) {
                append(" · ")
                append(DATE_FORMAT.format(java.util.Date(item.lastModified)))
            }
        }
        holder.size.text = CompressionJob.formatSize(item.sizeBytes)
        holder.itemView.setOnClickListener { onToggle(item) }
    }

    companion object {
        private val DATE_FORMAT = java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.GERMANY)
    }
}
