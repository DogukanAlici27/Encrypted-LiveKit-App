package com.dogu.livekit.ui.main

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
import com.dogu.livekit.core.util.ImageUtils
import com.dogu.livekit.data.local.entity.GroupEntity
import com.dogu.livekit.data.local.entity.MessageEntity
import com.dogu.livekit.data.local.entity.UserEntity
import java.text.SimpleDateFormat
import java.util.*

class MessageListAdapter(
    private val myIdentity: String,
    private val onChatClick: (String) -> Unit,
    private val onGroupChatClick: (String, String) -> Unit,
    private val onLongClick: (String, Boolean) -> Unit
) : ListAdapter<MessageEntity, MessageListAdapter.ViewHolder>(DiffCallback()) {

    private var users: List<UserEntity> = emptyList()
    private var groups: List<GroupEntity> = emptyList()

    fun setUserData(newUsers: List<UserEntity>) {
        users = newUsers
        notifyDataSetChanged()
    }

    fun setGroupData(newGroups: List<GroupEntity>) {
        groups = newGroups
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = getItem(position)
        if (message.groupId != null) {
            val group = groups.find { it.id == message.groupId }
            holder.bindGroup(message.groupId!!, message, group)
        } else {
            val otherParty = if (message.sender.equals(myIdentity, ignoreCase = true)) message.recipient else message.sender
            val user = users.find { it.identity.equals(otherParty, ignoreCase = true) }
            holder.bind(otherParty, message, user)
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.contactName)
        private val lastMessageText: TextView = view.findViewById(R.id.contactStatus)
        private val avatarImg: ImageView = view.findViewById(R.id.contactAvatar)
        private val timeText: TextView = view.findViewById(R.id.lastSeenText)
        private val muteImg: ImageView = view.findViewById(R.id.muteStatusImg)
        private val statusDot: View = view.findViewById(R.id.statusDot)
        private val callBtn: View = view.findViewById(R.id.contactCallBtn)
        private val chatBtn: View = view.findViewById(R.id.contactChatBtn)
        private val selectCb: View = view.findViewById(R.id.contactSelectCb)
        private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(otherParty: String, lastMessage: MessageEntity, user: UserEntity?) {
            nameText.text = otherParty
            lastMessageText.text = if (lastMessage.isMine) "Siz: ${lastMessage.content}" else lastMessage.content
            timeText.text = sdf.format(Date(lastMessage.timestamp))
            timeText.visibility = View.VISIBLE
            statusDot.visibility = View.GONE
            callBtn.visibility = View.GONE
            chatBtn.visibility = View.GONE
            selectCb.visibility = View.GONE
            
            muteImg.visibility = if (user?.isMuted == true) View.VISIBLE else View.GONE

            if (user?.profilePhoto?.isNotEmpty() == true) {
                val bitmap = ImageUtils.base64ToBitmap(user.profilePhoto)
                if (bitmap != null) {
                    avatarImg.setImageBitmap(bitmap)
                    avatarImg.setPadding(0, 0, 0, 0)
                } else {
                    setDefaultAvatar()
                }
            } else {
                setDefaultAvatar()
            }

            itemView.setOnClickListener { onChatClick(otherParty) }
            itemView.setOnLongClickListener {
                onLongClick(otherParty, user?.isMuted ?: false)
                true
            }
        }

        fun bindGroup(groupId: String, lastMessage: MessageEntity, group: GroupEntity?) {
            val baseName = group?.name ?: "Grup"
            
            // Ana listede parantez içindeki isimleri kaldırıyoruz, sadece "Grup:" belirteci ekliyoruz
            nameText.text = "Grup: $baseName"
            
            lastMessageText.text = if (lastMessage.isMine) "Siz: ${lastMessage.content}" else "${lastMessage.sender}: ${lastMessage.content}"
            timeText.text = sdf.format(Date(lastMessage.timestamp))
            timeText.visibility = View.VISIBLE
            statusDot.visibility = View.GONE
            callBtn.visibility = View.GONE
            chatBtn.visibility = View.GONE
            selectCb.visibility = View.GONE
            muteImg.visibility = View.GONE

            avatarImg.setImageResource(R.drawable.ic_people)
            val padding = (4 * itemView.resources.displayMetrics.density).toInt()
            avatarImg.setPadding(padding, padding, padding, padding)
            avatarImg.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(itemView.context, R.color.accent_blue)
            )

            itemView.setOnClickListener { onGroupChatClick(groupId, group?.name ?: "Grup") }
        }

        private fun setDefaultAvatar() {
            avatarImg.setImageResource(R.drawable.ic_person)
            val padding = (4 * itemView.resources.displayMetrics.density).toInt()
            avatarImg.setPadding(padding, padding, padding, padding)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            if (oldItem.groupId != null || newItem.groupId != null) {
                return oldItem.groupId == newItem.groupId
            }
            return (oldItem.sender == newItem.sender && oldItem.recipient == newItem.recipient) ||
                   (oldItem.sender == newItem.recipient && oldItem.recipient == newItem.sender)
        }
            
        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity) = 
            oldItem.content == newItem.content && oldItem.timestamp == newItem.timestamp
    }
}
