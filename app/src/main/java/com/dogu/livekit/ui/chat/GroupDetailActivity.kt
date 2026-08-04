package com.dogu.livekit.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dogu.livekit.R
import com.dogu.livekit.data.local.entity.GroupEntity
import com.dogu.livekit.data.repository.GroupRepository
import com.dogu.livekit.data.local.prefs.SessionPreferences
import com.dogu.livekit.databinding.ActivityGroupDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    @Inject
    lateinit var sessionPreferences: SessionPreferences

    private var groupId: String? = null
    private lateinit var adapter: GroupMemberAdapter
    private var currentGroup: GroupEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("groupId")
        if (groupId == null) {
            finish()
            return
        }

        setupUI()
        loadGroupDetails()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        adapter = GroupMemberAdapter { memberId ->
            showMemberOptions(memberId)
        }
        binding.membersRecyclerView.adapter = adapter
    }

    private fun loadGroupDetails() {
        lifecycleScope.launch {
            // Önce yerelden yükle
            val localGroup = groupRepository.getGroup(groupId!!)
            localGroup?.let { updateUI(it) }

            // Sunucudan güncelle
            groupRepository.syncGroupDetailsFromServer(groupId!!).onSuccess {
                updateUI(it)
            }
        }
    }

    private fun updateUI(group: GroupEntity) {
        currentGroup = group
        binding.groupNameText.text = group.name
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(group.createdAt))
        binding.groupInfoText.text = "Kuruluş: $dateStr • Kurucu: ${group.owner}"
        
        val members = group.members.split(",").filter { it.isNotBlank() }
        binding.membersCountTitle.text = "${members.size} KATILIMCI"
        
        adapter.submitList(members.map { MemberItem(it, it == group.owner) })
    }

    private fun showMemberOptions(memberId: String) {
        val myId = sessionPreferences.getCurrentIdentity()
        val group = currentGroup ?: return
        
        // Sadece yöneticiysen ve tıkladığın kişi sen değilsen menü aç
        if (group.owner == myId && memberId != myId) {
            val options = arrayOf("Yöneticilik Ver")
            AlertDialog.Builder(this)
                .setTitle(memberId)
                .setItems(options) { _, which ->
                    if (which == 0) {
                        confirmAdminTransfer(memberId)
                    }
                }
                .show()
        }
    }

    private fun confirmAdminTransfer(newAdmin: String) {
        AlertDialog.Builder(this)
            .setTitle("Yönetici Devret")
            .setMessage("$newAdmin kullanıcısını yeni yönetici yapmak istediğinize emin misiniz? Bu işlemden sonra yetkileriniz devredilecektir.")
            .setPositiveButton("EVET") { _, _ ->
                performAdminTransfer(newAdmin)
            }
            .setNegativeButton("HAYIR", null)
            .show()
    }

    private fun performAdminTransfer(newAdmin: String) {
        lifecycleScope.launch {
            groupRepository.transferAdmin(groupId!!, newAdmin).onSuccess {
                Toast.makeText(this@GroupDetailActivity, "Yöneticilik devredildi", Toast.LENGTH_SHORT).show()
                loadGroupDetails() // UI'yı yenile
            }.onFailure {
                Toast.makeText(this@GroupDetailActivity, "Hata: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class MemberItem(val identity: String, val isAdmin: Boolean)

    private class GroupMemberAdapter(private val onLongClick: (String) -> Unit) : 
        ListAdapter<MemberItem, GroupMemberAdapter.ViewHolder>(MemberDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_member, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.nameTv.text = item.identity
            holder.adminBadge.visibility = if (item.isAdmin) View.VISIBLE else View.GONE
            
            holder.itemView.setOnLongClickListener {
                onLongClick(item.identity)
                true
            }
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameTv: TextView = view.findViewById(R.id.memberName)
            val adminBadge: TextView = view.findViewById(R.id.adminBadge)
        }
    }

    private class MemberDiffCallback : DiffUtil.ItemCallback<MemberItem>() {
        override fun areItemsTheSame(oldItem: MemberItem, newItem: MemberItem) = oldItem.identity == newItem.identity
        override fun areContentsTheSame(oldItem: MemberItem, newItem: MemberItem) = oldItem == newItem
    }
}
