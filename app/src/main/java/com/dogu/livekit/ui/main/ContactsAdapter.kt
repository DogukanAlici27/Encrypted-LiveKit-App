package com.dogu.livekit.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dogu.livekit.R
import com.dogu.livekit.core.util.ImageUtils
import com.dogu.livekit.data.local.entity.UserEntity
import com.google.android.material.button.MaterialButton

class ContactsAdapter(
    private val onCallClick: (UserEntity) -> Unit,
    private val onLongClick: (UserEntity) -> Unit,
    private val onSelectionChanged: (UserEntity, Boolean) -> Unit,
    private val onChatClick: (UserEntity) -> Unit,
    private val isSelected: (String) -> Boolean,
    private val showSelection: Boolean = true // Yeni: Seçim modunu aç/kapat
) : ListAdapter<UserEntity, ContactsAdapter.ContactViewHolder>(UserDiffCallback()) {

    var isOffline: Boolean = false

    inner class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatarImg: ImageView = view.findViewById(R.id.contactAvatar)
        private val nameTv: TextView = view.findViewById(R.id.contactName)
        private val statusDot: View = view.findViewById(R.id.statusDot)
        private val statusTv: TextView = view.findViewById(R.id.contactStatus)
        private val muteImg: ImageView = view.findViewById(R.id.muteStatusImg)
        private val selectCb: CheckBox = view.findViewById(R.id.contactSelectCb)
        private val chatBtn: MaterialButton = view.findViewById(R.id.contactChatBtn)
        private val callBtn: MaterialButton = view.findViewById(R.id.contactCallBtn)

        fun bind(user: UserEntity) {
            nameTv.text = user.identity
            val context = itemView.context
            
            muteImg.visibility = View.GONE

            val photoBase64 = user.profilePhoto
            if (!photoBase64.isNullOrEmpty()) {
                val bitmap = ImageUtils.base64ToBitmap(photoBase64)
                if (bitmap != null) {
                    avatarImg.setImageBitmap(bitmap)
                    avatarImg.imageTintList = null
                    avatarImg.setPadding(0, 0, 0, 0)
                } else setDefaultAvatar()
            } else setDefaultAvatar()

            val userOnline = if (isOffline) false else user.isOnline
            val currentRoom = user.currentRoom
            val isInCall = userOnline && !currentRoom.isNullOrEmpty() && currentRoom != "null"

            statusDot.backgroundTintList = ColorStateList.valueOf(
                when {
                    isInCall -> ContextCompat.getColor(context, R.color.danger_red)
                    userOnline -> ContextCompat.getColor(context, R.color.success_green)
                    else -> ContextCompat.getColor(context, R.color.text_gray)
                }
            )

            statusTv.text = when {
                isInCall -> "Görüşmede"
                userOnline -> "Çevrimiçi"
                else -> "Çevrimdışı"
            }
            statusTv.setTextColor(statusDot.backgroundTintList!!.defaultColor)

            if (showSelection) {
                selectCb.visibility = View.VISIBLE
                chatBtn.visibility = View.GONE
                callBtn.visibility = View.GONE
                
                selectCb.isEnabled = !isInCall
                selectCb.setOnCheckedChangeListener(null)
                selectCb.isChecked = isSelected(user.identity)
                selectCb.setOnCheckedChangeListener { _, isChecked ->
                    onSelectionChanged(user, isChecked)
                }

                val toggleSelection = {
                    if (!isInCall) {
                        selectCb.isChecked = !selectCb.isChecked
                    }
                }
                avatarImg.setOnClickListener { toggleSelection() }
                itemView.findViewById<View>(R.id.textContainer).setOnClickListener { toggleSelection() }
            } else {
                selectCb.visibility = View.GONE
                chatBtn.visibility = View.VISIBLE
                callBtn.visibility = View.VISIBLE
                
                avatarImg.setOnClickListener { /* Normal tık */ }
                itemView.findViewById<View>(R.id.textContainer).setOnClickListener { /* Normal tık */ }
            }

            val canCall = !isInCall && !isOffline
            callBtn.text = when {
                isInCall -> "MEŞGUL"
                isOffline -> "PASİF"
                else -> "ARA"
            }
            callBtn.setIconResource(android.R.drawable.ic_menu_call)
            callBtn.backgroundTintList = ContextCompat.getColorStateList(context,
                if (!canCall) R.color.text_gray else R.color.accent_blue)

            callBtn.isEnabled = canCall
            callBtn.alpha = if (!canCall) 0.5f else 1f

            callBtn.setOnClickListener { onCallClick(user) }
            chatBtn.setOnClickListener { onChatClick(user) }
            itemView.setOnLongClickListener {
                onLongClick(user)
                true
            }
        }

        private fun setDefaultAvatar() {
            val context = itemView.context
            avatarImg.setImageResource(R.drawable.ic_person)
            val padding = (10 * context.resources.displayMetrics.density).toInt()
            avatarImg.setPadding(padding, padding, padding, padding)
            avatarImg.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_blue))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class UserDiffCallback : DiffUtil.ItemCallback<UserEntity>() {
        override fun areItemsTheSame(oldItem: UserEntity, newItem: UserEntity) = oldItem.identity == newItem.identity
        override fun areContentsTheSame(oldItem: UserEntity, newItem: UserEntity) = oldItem == newItem
    }
}
