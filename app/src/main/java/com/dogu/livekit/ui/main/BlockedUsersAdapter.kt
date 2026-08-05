package com.dogu.livekit.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dogu.livekit.R
import com.dogu.livekit.data.local.entity.UserEntity
import com.google.android.material.button.MaterialButton

class BlockedUsersAdapter(
    private var users: List<UserEntity>,
    private val onUnblockClick: (UserEntity) -> Unit
) : RecyclerView.Adapter<BlockedUsersAdapter.BlockedViewHolder>() {

    class BlockedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.contactName)
        val actionBtn: MaterialButton = view.findViewById(R.id.contactCallBtn)
        val statusDot: View = view.findViewById(R.id.statusDot)
        val statusTv: TextView = view.findViewById(R.id.contactStatus)
        val selectCb: CheckBox = view.findViewById(R.id.contactSelectCb)
        val chatBtn: View = view.findViewById(R.id.contactChatBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockedViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return BlockedViewHolder(view)
    }

    override fun onBindViewHolder(holder: BlockedViewHolder, position: Int) {
        val user = users[position]
        val context = holder.itemView.context
        holder.name.text = user.identity
        holder.statusDot.visibility = View.GONE
        holder.statusTv.visibility = View.GONE
        holder.selectCb.visibility = View.GONE
        holder.chatBtn.visibility = View.GONE

        holder.actionBtn.text = "KALDIR"
        holder.actionBtn.backgroundTintList = ContextCompat.getColorStateList(context, R.color.success_green)
        holder.actionBtn.setOnClickListener {
            onUnblockClick(user)
        }
    }

    override fun getItemCount() = users.size

    fun updateData(newUsers: List<UserEntity>) {
        users = newUsers
        notifyDataSetChanged()
    }
}
