package com.dogu.livekit.ui.main

import android.content.res.ColorStateList
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
import com.dogu.livekit.data.local.entity.CallLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogAdapter : ListAdapter<CallLogEntity, CallLogAdapter.LogViewHolder>(DiffCallback()) {

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.logTypeIcon)
        val name: TextView = view.findViewById(R.id.logTargetName)
        val time: TextView = view.findViewById(R.id.logTime)
        val typeText: TextView = view.findViewById(R.id.logTypeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = getItem(position)
        val context = holder.itemView.context
        holder.name.text = log.target

        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        holder.time.text = sdf.format(Date(log.timestamp))

        when (log.type) {
            "INCOMING" -> {
                holder.icon.setImageResource(android.R.drawable.sym_call_incoming)
                holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.success_green))
                holder.typeText.text = "Gelen"
            }
            "OUTGOING" -> {
                holder.icon.setImageResource(android.R.drawable.sym_call_outgoing)
                holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_blue))
                holder.typeText.text = "Giden"
            }
            "MISSED" -> {
                holder.icon.setImageResource(android.R.drawable.sym_call_missed)
                holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.danger_red))
                holder.typeText.text = "Kaçan"
            }
            "REJECTED" -> {
                holder.icon.setImageResource(android.R.drawable.sym_call_missed)
                holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_gray))
                holder.typeText.text = "Reddedildi"
            }
            "BLOCKED_CALL" -> {
                holder.icon.setImageResource(android.R.drawable.ic_delete)
                holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.danger_red))
                holder.typeText.text = "Engellendi"
            }
        }
        holder.typeText.setTextColor(holder.icon.imageTintList!!.defaultColor)
    }

    private class DiffCallback : DiffUtil.ItemCallback<CallLogEntity>() {
        override fun areItemsTheSame(oldItem: CallLogEntity, newItem: CallLogEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CallLogEntity, newItem: CallLogEntity) = oldItem == newItem
    }
}
