package com.dogu.livekit.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dogu.livekit.R
import com.dogu.livekit.data.local.entity.MessageEntity
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    private val onMessageLongClick: (MessageEntity) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<MessageEntity, ChatAdapter.MessageViewHolder>(DiffCallback()) {

    private val selectedMessageIds = mutableSetOf<Long>()
    var isSelectionMode = false
        private set

    fun toggleSelection(messageId: Long) {
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId)
        } else {
            selectedMessageIds.add(messageId)
        }
        isSelectionMode = selectedMessageIds.isNotEmpty()
        onSelectionChanged(selectedMessageIds.size)
        notifyDataSetChanged()
    }

    fun getSelectedMessageIds(): Set<Long> = selectedMessageIds

    fun getSelectedMessages(): List<MessageEntity> {
        return currentList.filter { selectedMessageIds.contains(it.id) }
    }

    fun clearSelection() {
        selectedMessageIds.clear()
        isSelectionMode = false
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == 1) R.layout.item_message_mine else R.layout.item_message_other
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        holder.bind(message, selectedMessageIds.contains(message.id))
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isMine) 1 else 0
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val messageText: TextView = view.findViewById(R.id.messageText)
        private val timeText: TextView = view.findViewById(R.id.timeText)
        private val senderNameText: TextView? = view.findViewById(R.id.senderNameText)
        private val statusIcon: ImageView? = view.findViewById(R.id.statusIcon)
        private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: MessageEntity, isSelected: Boolean) {
            messageText.text = message.content
            timeText.text = sdf.format(Date(message.timestamp))

            if (senderNameText != null) {
                if (message.groupId != null && !message.isMine) {
                    senderNameText.visibility = View.VISIBLE
                    senderNameText.text = message.sender
                } else {
                    senderNameText.visibility = View.GONE
                }
            }
            
            itemView.setBackgroundColor(
                if (isSelected) ContextCompat.getColor(itemView.context, R.color.accent_blue_alpha)
                else android.graphics.Color.TRANSPARENT
            )

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(message.id)
                }
            }

            itemView.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }

            statusIcon?.let { icon ->
                if (message.isRead) {
                    icon.setImageResource(R.drawable.ic_double_tick)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(itemView.context, R.color.wa_status_blue)
                    )
                } else {
                    icon.setImageResource(R.drawable.ic_single_tick)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(itemView.context, R.color.white)
                    )
                }
                icon.alpha = 0.8f
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity) = 
            oldItem.id == newItem.id && 
            oldItem.content == newItem.content && 
            oldItem.isRead == newItem.isRead && 
            oldItem.timestamp == newItem.timestamp
    }
}
