package com.dogu.livekit.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dogu.livekit.R
import java.text.SimpleDateFormat
import java.util.*

data class MessageStatusItem(
    val userId: String,
    val deliveredTime: Long,
    val readTime: Long
)

class MessageInfoAdapter : ListAdapter<MessageStatusItem, MessageInfoAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val userIdText: TextView = view.findViewById(R.id.userIdText)
        private val deliveredTimeText: TextView = view.findViewById(R.id.deliveredTimeText)
        private val readTimeText: TextView = view.findViewById(R.id.readTimeText)
        private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun bind(item: MessageStatusItem) {
            userIdText.text = item.userId
            
            deliveredTimeText.text = if (item.deliveredTime > 0) {
                "İletildi: ${sdf.format(Date(item.deliveredTime))}"
            } else {
                "İletilmedi"
            }

            readTimeText.text = if (item.readTime > 0) {
                "Okundu: ${sdf.format(Date(item.readTime))}"
            } else {
                "Okunmadı"
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MessageStatusItem>() {
        override fun areItemsTheSame(oldItem: MessageStatusItem, newItem: MessageStatusItem) = oldItem.userId == newItem.userId
        override fun areContentsTheSame(oldItem: MessageStatusItem, newItem: MessageStatusItem) = oldItem == newItem
    }
}
