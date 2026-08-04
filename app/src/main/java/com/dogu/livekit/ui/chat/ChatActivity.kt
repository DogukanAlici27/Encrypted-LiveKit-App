package com.dogu.livekit.ui.chat

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dogu.livekit.R
import com.dogu.livekit.core.util.ImageUtils
import com.dogu.livekit.data.local.entity.MessageEntity
import com.dogu.livekit.data.repository.UserRepository
import com.dogu.livekit.databinding.ActivityChatBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private val callViewModel: com.dogu.livekit.ui.call.CallViewModel by viewModels()
    
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var sessionPreferences: com.dogu.livekit.data.local.prefs.SessionPreferences
    
    private lateinit var adapter: ChatAdapter
    private var recipient: String? = null
    private var groupId: String? = null
    private var groupName: String? = null
    private var groupMembers: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Immediate refresh of user status
        callViewModel.refreshUsers()

        ViewCompat.setOnApplyWindowInsetsListener(binding.chatHeader) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.chatInputLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            // Klavye (ime) açıksa onun yüksekliğini, kapalıysa sistem çubuklarını (nav bar) kullan
            val bottomPadding = if (ime.bottom > 0) ime.bottom else systemBars.bottom
            view.updatePadding(bottom = bottomPadding)
            insets
        }

        recipient = intent.getStringExtra("recipient")
        groupId = intent.getStringExtra("groupId")
        groupName = intent.getStringExtra("groupName")

        if (recipient == null && groupId == null) {
            finish()
            return
        }

        if (groupId != null) {
            binding.chatRecipientTitle.text = groupName ?: "Grup"
            binding.chatRecipientStatus.visibility = View.GONE
            setDefaultAvatar() // Gruplar için şimdilik varsayılan ikon
            
            // Katılımcıları çek ve başlığa ekle
            viewModel.getGroup(groupId!!) { group ->
                group?.let {
                    val membersList = it.members.split(",").filter { m -> m.isNotBlank() }
                    groupMembers = membersList
                    if (membersList.isNotEmpty()) {
                        val showCount = 2
                        val displayed = membersList.take(showCount).joinToString(", ")
                        val suffix = if (membersList.size > showCount) ", ..." else ""
                        binding.chatRecipientTitle.text = "${it.name} ($displayed$suffix)"
                    }
                }
            }
        } else {
            binding.chatRecipientTitle.text = recipient
            observeRecipient()
        }

        setupAdapter()
        binding.chatRecyclerView.adapter = adapter
        (binding.chatRecyclerView.layoutManager as LinearLayoutManager).stackFromEnd = true
        
        // Klavye açıldığında listeyi en alta kaydır
        binding.chatRecyclerView.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                val lastPos = adapter.itemCount - 1
                if (lastPos >= 0) {
                    binding.chatRecyclerView.postDelayed({
                        binding.chatRecyclerView.smoothScrollToPosition(lastPos)
                    }, 100)
                }
            }
        }

        binding.backButton.setOnClickListener { finish() }
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.btnCallHeader.setOnClickListener { startCall() }
        binding.headerProfileCard.setOnClickListener { 
            if (groupId == null) showProfilePreview() 
        }

        // Selection header clicks
        binding.closeMessageSelectionBtn.setOnClickListener { adapter.clearSelection() }
        binding.deleteMessagesBtn.setOnClickListener { confirmDeleteSelectedMessages() }

        observeMessages()
        if (groupId != null) {
            viewModel.markGroupAsRead(groupId!!)
        } else {
            viewModel.markAsRead(recipient!!)
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    adapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupAdapter() {
        adapter = ChatAdapter(
            onMessageLongClick = { message -> 
                if (message.isMine) {
                    showMyMessageOptionsMenu(message)
                } else {
                    adapter.toggleSelection(message.id)
                }
            },
            onSelectionChanged = { count ->
                if (count > 0) {
                    binding.standardChatHeader.visibility = View.GONE
                    binding.selectionChatHeader.visibility = View.VISIBLE
                    binding.messageSelectionCountText.text = getString(R.string.message_selection_count, count)
                } else {
                    binding.selectionChatHeader.visibility = View.GONE
                    binding.standardChatHeader.visibility = View.VISIBLE
                }
            }
        )
    }

    private fun showMyMessageOptionsMenu(message: MessageEntity) {
        val options = arrayOf("Bilgi", "Seç", "Sil")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (message.remoteId != null) {
                            showMessageInfoDialog(message)
                        } else {
                            Toast.makeText(this, "Mesaj henüz sunucuya ulaşmadı", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> adapter.toggleSelection(message.id)
                    2 -> confirmDeleteSingleMessage(message)
                }
            }
            .show()
    }

    private fun confirmDeleteSingleMessage(message: MessageEntity) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Mesajı Sil")
            .setMessage("Bu mesajı silmek istediğinize emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                viewModel.deleteMessage(message.id)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showMessageInfoDialog(message: MessageEntity) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.TransparentBottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_message_info, null)
        dialog.setContentView(view)

        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.statusRecyclerView)
        val titleText = view.findViewById<TextView>(R.id.dialogTitle)
        val contentText = view.findViewById<TextView>(R.id.messageContentPreview)

        titleText.text = "Mesaj Bilgisi"
        contentText.text = message.content

        val infoAdapter = MessageInfoAdapter()
        recyclerView.adapter = infoAdapter

        viewModel.getMessageStatus(message.remoteId!!) { result ->
            result.onSuccess { json ->
                val statusList = mutableListOf<MessageStatusItem>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val userId = keys.next()
                    val userStatus = json.getJSONObject(userId)
                    statusList.add(MessageStatusItem(
                        userId = userId,
                        deliveredTime = userStatus.optLong("delivered", 0),
                        readTime = userStatus.optLong("read", 0)
                    ))
                }
                runOnUiThread {
                    infoAdapter.submitList(statusList)
                }
            }.onFailure {
                runOnUiThread {
                    Toast.makeText(this, "Bilgiler alınamadı", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }
  private fun confirmDeleteSelectedMessages() {
        val selectedMessages = adapter.getSelectedMessages()
        if (selectedMessages.isEmpty()) return

        val allMine = selectedMessages.all { it.isMine }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Mesajları Sil")
            .setMessage("${selectedMessages.size} adet mesajı silmek istediğinize emin misiniz?")
            .setNeutralButton("İptal", null)
            .setPositiveButton("Benden Sil") { _, _ ->
                selectedMessages.forEach { msg ->
                    viewModel.deleteMessage(msg.id)
                }
                adapter.clearSelection()
            }

        if (allMine) {
            builder.setNegativeButton("Herkesten Sil") { _, _ ->
                selectedMessages.forEach { msg ->
                    viewModel.deleteMessageForEveryone(msg)
                }
                adapter.clearSelection()
            }
        }

        builder.show()
    }

    private fun observeRecipient() {
        lifecycleScope.launch {
            viewModel.getUser(recipient!!).collect { user ->
                user?.let { u ->
                    binding.chatRecipientStatus.visibility = View.VISIBLE
                    binding.chatRecipientStatus.text = if (u.isOnline) "Çevrimiçi" else "Çevrimdışı"
                    binding.chatRecipientStatus.setTextColor(
                        if (u.isOnline) androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.wa_status_blue)
                        else androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.text_gray)
                    )

                    u.profilePhoto?.let { photoBase64 ->
                        if (photoBase64.isNotEmpty()) {
                            val bitmap = ImageUtils.base64ToBitmap(photoBase64)
                            if (bitmap != null) {
                                binding.recipientAvatar.setImageBitmap(bitmap)
                                binding.recipientAvatar.setPadding(0, 0, 0, 0)
                                binding.recipientAvatar.imageTintList = null
                            }
                        } else {
                            setDefaultAvatar()
                        }
                    } ?: setDefaultAvatar()
                } ?: setDefaultAvatar()
            }
        }
    }

    private fun setDefaultAvatar() {
        binding.recipientAvatar.setImageResource(R.drawable.ic_person)
        val padding = (6 * resources.displayMetrics.density).toInt()
        binding.recipientAvatar.setPadding(padding, padding, padding, padding)
        binding.recipientAvatar.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, R.color.accent_blue)
        )
    }

    private fun showProfilePreview() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .create()
        val view = layoutInflater.inflate(R.layout.dialog_profile_preview, null)
        dialog.setView(view)
        
        val previewAvatar = view.findViewById<ImageView>(R.id.previewAvatar)
        val previewName = view.findViewById<TextView>(R.id.previewName)
        
        previewName.text = recipient
        
        lifecycleScope.launch {
            val user = userRepository.fetchLocalUser(recipient!!)
            user?.profilePhoto?.let { photoBase64 ->
                if (photoBase64.isNotEmpty()) {
                    val bitmap = ImageUtils.base64ToBitmap(photoBase64)
                    if (bitmap != null) {
                        previewAvatar.setImageBitmap(bitmap)
                        previewAvatar.setPadding(0, 0, 0, 0)
                        previewAvatar.imageTintList = null
                    }
                }
            }
        }
        
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            if (groupId != null) {
                viewModel.getGroupMessages(groupId!!).collect { messages ->
                    adapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                    viewModel.markGroupAsRead(groupId!!)
                    
                    // Okunmamış mesajları sunucuya "okundu" olarak raporla
                    messages.forEach { msg ->
                        if (!msg.isMine && !msg.isRead && msg.remoteId != null) {
                            viewModel.reportMessageRead(msg.remoteId)
                        }
                    }
                }
            } else {
                viewModel.getMessages(recipient!!).collect { messages ->
                    adapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            binding.chatRecyclerView.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                    
                    // Mesajlar geldikçe eğer okunmamış ve karşıdan gelen mesaj varsa okundu yap
                    val unreadIncoming = messages.any { !it.isMine && !it.isRead }
                    if (unreadIncoming) {
                        viewModel.markAsRead(recipient!!)
                        // 1-1 için de raporla
                        messages.forEach { msg ->
                            if (!msg.isMine && !msg.isRead && msg.remoteId != null) {
                                viewModel.reportMessageRead(msg.remoteId)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()
        if (text.isNotEmpty()) {
            if (groupId != null) {
                viewModel.sendGroupMessage(groupId!!, text)
            } else if (recipient != null) {
                viewModel.sendMessage(recipient!!, text)
            }
            binding.messageEditText.text.clear()
        }
    }

    private fun startCall() {
        val target = if (groupId != null) {
            val myId = sessionPreferences.getCurrentIdentity()
            groupMembers.filter { it != myId }.joinToString(",")
        } else {
            recipient
        }

        if (!target.isNullOrEmpty()) {
            val intent = android.content.Intent(this, com.dogu.livekit.ui.main.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("action_start_call", true)
                putExtra("target", target)
                putExtra("is_video", true)
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        recipient?.let { viewModel.markAsRead(it) }
    }
}
