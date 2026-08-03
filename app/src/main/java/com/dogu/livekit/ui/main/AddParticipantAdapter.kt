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

class AddParticipantAdapter(
    private val onInviteClick: (UserEntity) -> Unit
) : ListAdapter<UserEntity, AddParticipantAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarImg: ImageView = view.findViewById(R.id.contactAvatar)
        val nameTv: TextView = view.findViewById(R.id.contactName)
        val statusDot: View = view.findViewById(R.id.statusDot)
        val statusTv: TextView = view.findViewById(R.id.contactStatus)
        val selectCb: CheckBox = view.findViewById(R.id.contactSelectCb)
        val inviteBtn: MaterialButton = view.findViewById(R.id.contactCallBtn)

        fun bind(user: UserEntity) {
            nameTv.text = user.identity
            selectCb.visibility = View.GONE
            val context = itemView.context

            val photoBase64 = user.profilePhoto
            if (!photoBase64.isNullOrEmpty()) {
                val bitmap = ImageUtils.base64ToBitmap(photoBase64)
                if (bitmap != null) {
                    avatarImg.setImageBitmap(bitmap)
                    avatarImg.imageTintList = null
                    avatarImg.setPadding(0, 0, 0, 0)
                } else setDefaultAvatar()
            } else setDefaultAvatar()

            val isInCall = user.isOnline && !user.currentRoom.isNullOrEmpty() && user.currentRoom != "null"
            statusDot.backgroundTintList = ColorStateList.valueOf(
                when {
                    isInCall -> ContextCompat.getColor(context, R.color.danger_red)
                    user.isOnline -> ContextCompat.getColor(context, R.color.success_green)
                    else -> ContextCompat.getColor(context, R.color.text_gray)
                }
            )
            statusTv.text = when {
                isInCall -> "Görüşmede"
                user.isOnline -> "Çevrimiçi"
                else -> "Çevrimdışı"
            }
            statusTv.setTextColor(statusDot.backgroundTintList!!.defaultColor)

            val isInviteable = !isInCall
            inviteBtn.text = if (isInCall) "MEŞGUL" else "DAVET ET"
            inviteBtn.isEnabled = isInviteable
            inviteBtn.alpha = if (isInviteable) 1f else 0.5f
            inviteBtn.backgroundTintList = ContextCompat.getColorStateList(context,
                if (isInviteable) R.color.accent_blue else R.color.text_gray)

            inviteBtn.setOnClickListener { onInviteClick(user) }
        }

        private fun setDefaultAvatar() {
            val context = itemView.context
            avatarImg.setImageResource(R.drawable.ic_person)
            val padding = (10 * context.resources.displayMetrics.density).toInt()
            avatarImg.setPadding(padding, padding, padding, padding)
            avatarImg.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_blue))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<UserEntity>() {
        override fun areItemsTheSame(oldItem: UserEntity, newItem: UserEntity) = oldItem.identity == newItem.identity
        override fun areContentsTheSame(oldItem: UserEntity, newItem: UserEntity) = oldItem == newItem
    }
}
