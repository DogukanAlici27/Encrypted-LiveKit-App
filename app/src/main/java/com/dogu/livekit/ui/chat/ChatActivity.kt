package com.dogu.livekit.ui.chat

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
    
    private lateinit var adapter: ChatAdapter
    private var recipient: String? = null

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
        if (recipient == null) {
            finish()
            return
        }

        binding.chatRecipientTitle.text = recipient
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
        binding.headerProfileCard.setOnClickListener { showProfilePreview() }

        // Selection header clicks
        binding.closeMessageSelectionBtn.setOnClickListener { adapter.clearSelection() }
        binding.deleteMessagesBtn.setOnClickListener { confirmDeleteSelectedMessages() }

        observeRecipient()
        observeMessages()
        viewModel.markAsRead(recipient!!)

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
            onMessageLongClick = { message -> adapter.toggleSelection(message.id) },
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
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()
        if (text.isNotEmpty() && recipient != null) {
            viewModel.sendMessage(recipient!!, text)
            binding.messageEditText.text.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        recipient?.let { viewModel.markAsRead(it) }
    }
}
