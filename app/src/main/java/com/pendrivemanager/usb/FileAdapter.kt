package com.pendrivemanager.usb

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pendrivemanager.usb.fs.ExFatEntry

class FileAdapter(
    private var files: List<ExFatEntry>,
    private val onItemClick: (ExFatEntry) -> Unit,
    private val onMoreClick: (ExFatEntry) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    inner class FileViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.imgIcon)
        val name: TextView = itemView.findViewById(R.id.txtName)
        val meta: TextView = itemView.findViewById(R.id.txtMeta)
        val more: ImageView = itemView.findViewById(R.id.btnMore)
    }

    fun updateData(newFiles: List<ExFatEntry>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        holder.meta.text = if (file.isDirectory) "Folder" else formatSize(file.dataLength)
        holder.icon.setImageResource(
            if (file.isDirectory) android.R.drawable.ic_menu_agenda
            else android.R.drawable.ic_menu_save
        )
        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.more.setOnClickListener { onMoreClick(file) }
    }

    override fun getItemCount(): Int = files.size

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }
}
