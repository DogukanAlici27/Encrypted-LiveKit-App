package com.dogu.livekit.ui.main

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.dogu.livekit.R
import com.dogu.livekit.core.hardware.AudioManagerCompat
import com.dogu.livekit.core.logging.Logger
import com.dogu.livekit.core.encryption.KeyManager
import com.dogu.livekit.core.util.PermissionUtils
import com.dogu.livekit.core.util.showStatus
import com.dogu.livekit.data.local.prefs.SessionPreferences
import com.dogu.livekit.data.local.entity.UserEntity
import com.dogu.livekit.databinding.ActivityMainBinding
import com.dogu.livekit.domain.call.CallManager
import com.dogu.livekit.ui.auth.AuthViewModel
import com.dogu.livekit.ui.call.CallEvent
import com.dogu.livekit.ui.call.CallViewModel
import com.dogu.livekit.ui.call.VideoAdapter
import com.dogu.livekit.ui.chat.ChatActivity
import com.dogu.livekit.ui.chat.ChatViewModel
import com.dogu.livekit.ui.contacts.ContactsViewModel
import com.dogu.livekit.ui.history.HistoryViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import androidx.recyclerview.widget.RecyclerView

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authViewModel: AuthViewModel by viewModels()
    private val contactsViewModel: ContactsViewModel by viewModels()
    private val callViewModel: CallViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()

    @Inject
    lateinit var sessionPreferences: SessionPreferences

    private lateinit var messageListAdapter: MessageListAdapter
    private val historyAdapter = CallLogAdapter()
    private val contactsAdapter = ContactsAdapter(
        onCallClick = { user -> startCall(user.identity) },
        onLongClick = { user -> showBlockUserDialog(user.identity) },
        onSelectionChanged = { user, isChecked ->
            if (isChecked) selectedParticipants.add(user.identity)
            else selectedParticipants.remove(user.identity)
            updateGroupCallFab()
        },
        onChatClick = { user -> openChat(user.identity) }
    )

    private var isAppOffline: Boolean = false
    private val selectedParticipants = mutableSetOf<String>()
    private var controlsHideJob: kotlinx.coroutines.Job? = null
    private val videoAdapter = VideoAdapter()

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
            Toast.makeText(this, "İzinler gerekli", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        try {
            if (sessionPreferences.isDarkTheme()) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }

            enableEdgeToEdge()
            setContentView(binding.root)

            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = systemBars.bottom)
                insets
            }

            ViewCompat.setOnApplyWindowInsetsListener(binding.headerLayout) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(top = systemBars.top)
                insets
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (CallManager.room != null || binding.callControls.visibility == View.VISIBLE) {
                        callViewModel.leaveRoom(false)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })

            bindUI()
            observeViewModel()
            loadSession()
            checkAndRequestPermissions()
            handleIntent(intent)
            
            // Initial Sync
            performInitialSync()
        } catch (e: Exception) {
            Logger.e("MainActivity onCreate CRASH!", e)
            finish()
        }
    }

    private fun observeViewModel() {
        observeAuthState()
        observeContacts()
        observeCall()
        observeMessages()
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            chatViewModel.getLastMessages().collect { messages ->
                messageListAdapter.submitList(messages)
            }
        }

        lifecycleScope.launch {
            contactsViewModel.contacts.collect { users ->
                messageListAdapter.setUserData(users)
            }
        }
    }

    private fun openChat(identity: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("recipient", identity)
        }
        startActivity(intent)
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            authViewModel.authState.collect { state ->
                when (state) {
                    is AuthViewModel.AuthState.Loading -> showStatus(getString(R.string.loading), 10000)
                    is AuthViewModel.AuthState.Success -> {
                        binding.currentUserTextView.text = state.identity
                        binding.homePanel.visibility = View.GONE
                        val successMsg = if (state.isOnline) "Giriş Başarılı!" else "Çevrimdışı giriş başarılı"
                        showStatus(successMsg)
                        updateConnectionStatusBadge(state.isOnline)
                        callViewModel.loadOwnProfilePhoto()
                        navigateToContacts()
                        contactsViewModel.syncBlocksFromServer()
                        callViewModel.startHeartbeat()
                        callViewModel.startAutoRefresh()
                    }
                    is AuthViewModel.AuthState.Error -> {
                        showStatus(state.message)
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launch {
            authViewModel.event.collect { event ->
                when (event) {
                    is AuthViewModel.AuthEvent.LogoutSuccess -> {
                        binding.homePanel.visibility = View.VISIBLE
                        binding.contactsPanel.visibility = View.GONE
                        binding.profilePanel.visibility = View.GONE
                        showStatus("Çıkış yapıldı")
                        callViewModel.stopHeartbeat()
                    }
                    is AuthViewModel.AuthEvent.AccountDeleted -> {
                        binding.homePanel.visibility = View.VISIBLE
                        showStatus("Hesap silindi")
                    }
                    is AuthViewModel.AuthEvent.PasswordChanged -> {
                        showStatus("Şifre değiştirildi")
                    }
                    is AuthViewModel.AuthEvent.PhotoUpdated -> {
                        showStatus("Fotoğraf güncellendi")
                        callViewModel.loadOwnProfilePhoto()
                    }
                    is AuthViewModel.AuthEvent.Error -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun observeContacts() {
        lifecycleScope.launch {
            contactsViewModel.contacts.collect { contacts ->
                val myIdentity = sessionPreferences.getCurrentIdentity()?.lowercase() ?: ""
                val query = binding.contactSearchEditText.text.toString().lowercase().trim()
                
                val filtered = contacts.filter { user ->
                    user.identity.lowercase() != myIdentity &&
                    !user.isBlocked &&
                    (query.isEmpty() || user.identity.lowercase().contains(query))
                }
                
                contactsAdapter.submitList(filtered)
                binding.contactsCountBadge.text = filtered.size.toString()
                binding.contactsCountBadge.visibility = if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            contactsViewModel.blockedUsers.collect { blocked ->
                binding.blockedRecyclerView.adapter = BlockedUsersAdapter(blocked) { user ->
                    contactsViewModel.toggleBlockUser(user.identity, false)
                    showStatus("${user.identity} engeli kaldırıldı")
                }
            }
        }

        lifecycleScope.launch {
            historyViewModel.history.collect { logs ->
                historyAdapter.submitList(logs)
            }
        }
    }

    private fun observeCall() {
        lifecycleScope.launch {
            callViewModel.isMicMuted.collect { muted ->
                binding.muteButton.apply {
                    if (muted) {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.danger_red)
                        setIconResource(R.drawable.ic_mic_off)
                    } else {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue_alpha)
                        setIconResource(R.drawable.ic_mic_on)
                    }
                }
            }
        }

        lifecycleScope.launch {
            callViewModel.isCameraOn.collect { on ->
                binding.cameraToggleButton.apply {
                    if (on) {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue_alpha)
                        setIconResource(R.drawable.ic_videocam_on)
                    } else {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.danger_red)
                        setIconResource(R.drawable.ic_videocam_off)
                    }
                }
                binding.switchCameraButton.isEnabled = on
                binding.switchCameraButton.alpha = if (on) 1.0f else 0.5f
                val identity = sessionPreferences.getCurrentIdentity() ?: "Ben"
                videoAdapter.setCameraEnabled("$identity (Sen)", on)
            }
        }

        lifecycleScope.launch {
            callViewModel.isSpeakerOn.collect { on ->
                binding.speakerButton.apply {
                    if (on) {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue_alpha)
                        setIconResource(R.drawable.ic_speaker_on)
                    } else {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.bg_input)
                        setIconResource(R.drawable.ic_speaker_off)
                    }
                }
            }
        }

        lifecycleScope.launch {
            callViewModel.profilePhoto.collect { bitmap ->
                if (bitmap != null) {
                    binding.currentProfilePhotoImg.setImageBitmap(bitmap)
                    binding.currentProfilePhotoImg.setPadding(0, 0, 0, 0)
                    binding.currentProfilePhotoImg.imageTintList = null
                }
            }
        }

        lifecycleScope.launch {
            callViewModel.events.collect { event ->
                when (event) {
                    is CallEvent.Status -> showStatus(event.message, event.duration)
                    is CallEvent.Error -> {
                        showStatus(event.message)
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                    is CallEvent.TrackAdded -> {
                        if (videoAdapter.addTrack(event.identity, event.track)) {
                            videoAdapter.setCameraEnabled(event.identity, !event.isMuted)
                            binding.remoteVideosRecyclerView.post {
                                binding.remoteVideosRecyclerView.requestLayout()
                                videoAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                    is CallEvent.TrackRemoved -> {
                        videoAdapter.removeTrack(event.identity)
                        videoAdapter.notifyDataSetChanged()
                    }
                    is CallEvent.TrackMuted -> {
                        videoAdapter.setCameraEnabled(event.identity, !event.isMuted)
                        videoAdapter.notifyDataSetChanged()
                    }
                    is CallEvent.CallStarted -> {
                        binding.uiContainer.visibility = View.GONE
                        binding.bottomNavigation.visibility = View.GONE
                        binding.callControls.visibility = View.VISIBLE
                        binding.leaveButton.isEnabled = true
                        binding.muteButton.isEnabled = true
                        binding.remoteVideosRecyclerView.visibility = View.VISIBLE
                        setupCallUIInteractions()
                        showControlsWithTimeout()
                    }
                    is CallEvent.Connect -> {
                        lifecycleScope.launch {
                            callViewModel.connectToRoom(this@MainActivity, event.url, event.token, event.useVideo, event.roomKey)
                        }
                    }
                    is CallEvent.CallEnded -> {
                        runOnUiThread {
                            binding.uiContainer.visibility = View.VISIBLE
                            binding.bottomNavigation.visibility = View.VISIBLE
                            binding.callControls.visibility = View.GONE
                            binding.remoteVideosRecyclerView.visibility = View.GONE
                            videoAdapter.clear()
                            videoAdapter.notifyDataSetChanged()
                            hideControls()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun performInitialSync() {
        lifecycleScope.launch {
            try {
                contactsViewModel.refreshContacts()
                contactsViewModel.syncBlocksFromServer()
                callViewModel.loadOwnProfilePhoto()
                Logger.d("✅ Initial sync tamamlandı")
            } catch (e: Exception) {
                Logger.e("❌ Initial sync hatası", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(com.dogu.livekit.service.MyFirebaseMessagingService.CALL_NOTIFICATION_ID)

        if (intent?.getBooleanExtra("start_call", false) == true) {
            val url = intent.getStringExtra("url")
            val token = intent.getStringExtra("token")
            val roomKey = intent.getStringExtra("room_key")
            val isVideo = intent.getBooleanExtra("is_video", true)

            intent.putExtra("start_call", false)

            if (url != null && token != null) {
                lifecycleScope.launch {
                    callViewModel.connectToRoom(this@MainActivity, url, token, isVideo, roomKey)
                }
            }
        }
    }

    private fun bindUI() {
        setupRecyclerViews()
        setupClickListeners()
        setupSearch()
        setupNavigation()
    }

    private fun setupRecyclerViews() {
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        binding.contactsRecyclerView.adapter = contactsAdapter

        val myId = sessionPreferences.getCurrentIdentity() ?: ""
        messageListAdapter = MessageListAdapter(
            myId,
            onChatClick = { identity -> openChat(identity) },
            onLongClick = { identity, isMuted -> showConversationOptionsDialog(identity, isMuted) }
        )
        binding.messagesRecyclerView.adapter = messageListAdapter

        binding.remoteVideosRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val count = videoAdapter.itemCount
                        return VideoAdapter.getSpanSizeForPosition(count, position)
                    }
                }
            }
            adapter = videoAdapter
        }
    }

    private fun setupClickListeners() {
        binding.registerButton.setOnClickListener { authOnServer("register") }
        binding.loginButton.setOnClickListener { authOnServer("login") }
        binding.leaveButton.setOnClickListener { callViewModel.leaveRoom(true) }
        binding.cameraToggleButton.setOnClickListener { callViewModel.toggleCamera() }
        binding.muteButton.setOnClickListener { callViewModel.toggleMic() }
        binding.speakerButton.setOnClickListener { callViewModel.toggleSpeaker(this) }
        binding.switchCameraButton.setOnClickListener { callViewModel.switchCamera() }
        binding.addParticipantButton.setOnClickListener { showAddParticipantDialog() }

        binding.clearHistoryBtn.setOnClickListener {
            showModernConfirmDialog(
                "Geçmişi Temizle",
                "Tüm arama geçmişini silmek istediğinize emin misiniz?",
                "TEMİZLE"
            ) {
                historyViewModel.clearHistory()
            }
        }

        binding.editProfilePhotoFab.setOnClickListener {
            showImageSourceDialog(photoPickerLauncherInternal, cameraLauncherInternal)
        }

        // Settings clicks
        binding.settingsAccountRow.setOnClickListener {
            val identity = sessionPreferences.getCurrentIdentity() ?: "-"
            binding.accountUsernameTextView.text = getString(R.string.account_username_label, identity)
            binding.settingsMenuPanel.visibility = View.GONE
            binding.accountSettingsPanel.visibility = View.VISIBLE
        }
        binding.accountBackRow.setOnClickListener {
            binding.accountSettingsPanel.visibility = View.GONE
            binding.settingsMenuPanel.visibility = View.VISIBLE
        }
        binding.settingsThemeRow.setOnClickListener {
            refreshThemeCheckmarks()
            binding.settingsMenuPanel.visibility = View.GONE
            binding.themeSettingsPanel.visibility = View.VISIBLE
        }
        binding.themeBackRow.setOnClickListener {
            binding.themeSettingsPanel.visibility = View.GONE
            binding.settingsMenuPanel.visibility = View.VISIBLE
        }
        binding.settingsBlockedRow.setOnClickListener {
            binding.settingsMenuPanel.visibility = View.GONE
            binding.blockedSettingsPanel.visibility = View.VISIBLE
        }
        binding.blockedBackRow.setOnClickListener {
            binding.blockedSettingsPanel.visibility = View.GONE
            binding.settingsMenuPanel.visibility = View.VISIBLE
        }

        binding.themeDarkOption.setOnClickListener { applyThemeChoice(isDark = true) }
        binding.themeLightOption.setOnClickListener { applyThemeChoice(isDark = false) }

        binding.logoutButton.setOnClickListener { performLogout() }
        binding.changePasswordButton.setOnClickListener { showChangePasswordDialog() }
        binding.deleteAccountButton.setOnClickListener { confirmAccountDeletion() }
        binding.groupCallFab.setOnClickListener { startGroupCall() }
    }

    private fun setupSearch() {
        binding.contactSearchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                contactsViewModel.contacts.value.let { contacts ->
                    val myIdentity = sessionPreferences.getCurrentIdentity()?.lowercase() ?: ""
                    val query = s?.toString()?.lowercase()?.trim() ?: ""
                    val filtered = contacts.filter { user ->
                        user.identity.lowercase() != myIdentity &&
                        !user.isBlocked &&
                        (query.isEmpty() || user.identity.lowercase().contains(query))
                    }
                    contactsAdapter.submitList(filtered)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (!sessionPreferences.isLoggedIn()) {
                Toast.makeText(this, "Bu özelliği kullanmak için önce giriş yapmalısın", Toast.LENGTH_SHORT).show()
                return@setOnItemSelectedListener false
            }

            when (item.itemId) {
                R.id.nav_contacts -> {
                    showPanel(binding.contactsPanel)
                    refreshContacts()
                    true
                }
                R.id.nav_messages -> {
                    showPanel(binding.messagesPanel)
                    true
                }
                R.id.nav_history -> {
                    showPanel(binding.historyPanel)
                    true
                }
                R.id.nav_profile -> {
                    showPanel(binding.profilePanel)
                    binding.accountSettingsPanel.visibility = View.GONE
                    binding.themeSettingsPanel.visibility = View.GONE
                    binding.settingsMenuPanel.visibility = View.VISIBLE
                    callViewModel.loadOwnProfilePhoto()
                    true
                }
                else -> false
            }
        }
    }

    private fun showPanel(panel: View) {
        binding.homePanel.visibility = View.GONE
        binding.contactsPanel.visibility = View.GONE
        binding.messagesPanel.visibility = View.GONE
        binding.historyPanel.visibility = View.GONE
        binding.profilePanel.visibility = View.GONE
        panel.visibility = View.VISIBLE
    }

    private fun refreshThemeCheckmarks() {
        val isDark = sessionPreferences.isDarkTheme()
        binding.themeDarkCheck.setImageResource(
            if (isDark) android.R.drawable.checkbox_on_background else android.R.drawable.checkbox_off_background
        )
        binding.themeLightCheck.setImageResource(
            if (!isDark) android.R.drawable.checkbox_on_background else android.R.drawable.checkbox_off_background
        )
    }

    private fun applyThemeChoice(isDark: Boolean) {
        if (sessionPreferences.isDarkTheme() == isDark) return
        sessionPreferences.setDarkTheme(isDark)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    private fun performLogout() {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        showModernConfirmDialog(
            "Oturumu Kapat",
            "Hesabınızdan çıkış yapmak istediğinize emin misiniz?",
            "ÇIKIŞ YAP"
        ) {
            authViewModel.logout(identity)
        }
    }

    private fun confirmAccountDeletion() {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        showModernConfirmDialog(
            "Hesabı Sil",
            "'$identity' kullanıcısını hem bu telefondan hem de sunucudan kalıcı olarak silmek istediğine emin misin?",
            "KALICI OLARAK SİL"
        ) {
            authViewModel.deleteAccount(identity)
        }
    }

    private fun showChangePasswordDialog() {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .create()
        val view = layoutInflater.inflate(R.layout.dialog_change_password, null)
        dialog.setView(view)

        val oldPassEt = view.findViewById<EditText>(R.id.oldPasswordEt)
        val newPassEt = view.findViewById<EditText>(R.id.newPasswordEt)
        val confirmPassEt = view.findViewById<EditText>(R.id.confirmPasswordEt)

        view.findViewById<View>(R.id.btnSavePass).setOnClickListener {
            val oldPass = oldPassEt.text.toString().trim()
            val newPass = newPassEt.text.toString().trim()
            val confirmPass = confirmPassEt.text.toString().trim()

            if (oldPass.isEmpty() || newPass.isEmpty()) {
                Toast.makeText(this, "Alanlar boş bırakılamaz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass != confirmPass) {
                Toast.makeText(this, "Yeni şifreler eşleşmiyor", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            authViewModel.changePassword(identity, oldPass, newPass)
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnCancelPass).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun loadSession() {
        val savedIdentity = sessionPreferences.getRememberedIdentity()
        val savedPassword = sessionPreferences.getRememberedPassword()

        if (savedIdentity.isNotEmpty()) {
            binding.identityEditText.setText(savedIdentity)
            binding.passwordEditText.setText(savedPassword)
            binding.rememberMeCheckBox.isChecked = true
        }

        if (sessionPreferences.isLoggedIn() && savedIdentity.isNotEmpty()) {
            binding.currentUserTextView.text = savedIdentity
            binding.homePanel.visibility = View.GONE
            updateConnectionStatusBadge(true)
            navigateToContacts()
            callViewModel.loadOwnProfilePhoto()
        } else {
            binding.currentUserTextView.text = getString(R.string.guest)
            binding.homePanel.visibility = View.VISIBLE
        }
    }

    private fun navigateToContacts() {
        binding.bottomNavigation.selectedItemId = R.id.nav_contacts
        lifecycleScope.launch {
            callViewModel.startHeartbeat()
            refreshContacts()
        }
    }

    private fun refreshContacts() {
        selectedParticipants.clear()
        updateGroupCallFab()
        contactsViewModel.refreshContacts()
    }

    private fun updateConnectionStatusBadge(isOnline: Boolean) {
        isAppOffline = !isOnline
        contactsAdapter.isOffline = isAppOffline
        contactsAdapter.notifyDataSetChanged()

        if (!sessionPreferences.isLoggedIn()) {
            binding.connectionStatusBadge.visibility = View.GONE
            return
        }
        binding.connectionStatusBadge.visibility = View.VISIBLE
        if (isOnline) {
            binding.connectionStatusBadge.text = "ÇEVRİMİÇİ"
            binding.connectionStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.success_green)
            )
        } else {
            binding.connectionStatusBadge.text = "ÇEVRİMDIŞI"
            binding.connectionStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.danger_red)
            )
        }
    }

    private fun showContactOptionsDialog(user: UserEntity) {
        val options = arrayOf(
            if (user.isBlocked) "Engeli Kaldır" else "Engelle",
            if (user.isMuted) "Bildirimlerin Sesini Aç" else "Bildirimleri Sessize Al"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(user.identity)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (user.isBlocked) contactsViewModel.toggleBlockUser(user.identity, false)
                        else showBlockUserDialog(user.identity)
                    }
                    1 -> chatViewModel.toggleMute(user.identity, user.isMuted)
                }
            }
            .show()
    }

    private fun showBlockUserDialog(identity: String) {
        val user = contactsViewModel.contacts.value.find { it.identity == identity }
        val isBlocked = user?.isBlocked == true
        
        val title = if (isBlocked) "Engeli Kaldır" else "Kişiyi Engelle"
        val message = if (isBlocked) "$identity kullanıcısının engelini kaldırmak istiyor musunuz?" 
                      else "$identity kullanıcısını engellemek istiyor musunuz? Engellenen kişiler sizi arayamaz ve rehberinizde görünmez."
        val buttonText = if (isBlocked) "Kaldır" else "Engelle"

        showModernConfirmDialog(title, message, buttonText) {
            contactsViewModel.toggleBlockUser(identity, !isBlocked)
            showStatus(if (isBlocked) "$identity engeli kaldırıldı" else "$identity engellendi")
        }
    }

    private fun showConversationOptionsDialog(identity: String, isMuted: Boolean) {
        val dialog = BottomSheetDialog(this, R.style.TransparentBottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_options, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.sheetTitle).text = identity
        
        val opt1 = view.findViewById<View>(R.id.option1)
        view.findViewById<TextView>(R.id.option1Text).text = "Mesajları Sil"
        view.findViewById<ImageView>(R.id.option1Icon).setImageResource(R.drawable.ic_delete)
        opt1.setOnClickListener {
            dialog.dismiss()
            confirmDeleteConversation(identity)
        }

        val opt2 = view.findViewById<View>(R.id.option2)
        view.findViewById<TextView>(R.id.option2Text).text = if (isMuted) "Bildirimlerin Sesini Aç" else "Bildirimleri Sessize Al"
        view.findViewById<ImageView>(R.id.option2Icon).setImageResource(R.drawable.ic_notifications_off)
        opt2.setOnClickListener {
            dialog.dismiss()
            chatViewModel.toggleMute(identity, isMuted)
        }

        dialog.show()
    }

    private fun confirmDeleteConversation(identity: String) {
        showModernConfirmDialog(
            "Konuşmayı Sil",
            "$identity ile olan tüm mesajları silmek istediğinize emin misiniz?",
            "SİL"
        ) {
            chatViewModel.deleteConversation(identity)
            showStatus("Konuşma silindi")
        }
    }

    private fun showModernConfirmDialog(title: String, message: String, positiveBtnText: String, onPositive: () -> Unit) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .create()
        val view = layoutInflater.inflate(R.layout.layout_modern_confirm_dialog, null)
        dialog.setView(view)
        
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message
        
        val btnPos = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPositive)
        btnPos.text = positiveBtnText
        btnPos.setOnClickListener {
            onPositive()
            dialog.dismiss()
        }
        
        view.findViewById<View>(R.id.btnNegative).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showAddParticipantDialog() {
        val currentRoom = CallManager.room ?: return
        val dialog = BottomSheetDialog(this, R.style.TransparentBottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_add_participant, null)
        dialog.setContentView(view)

        val searchEt = view.findViewById<EditText>(R.id.participantSearchEditText)
        val recyclerView = view.findViewById<RecyclerView>(R.id.participantsRecyclerView)
        val adapter = AddParticipantAdapter { user ->
            inviteParticipantToCurrentCall(user.identity)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            val contacts = contactsViewModel.contacts.value
            val myIdentity = sessionPreferences.getCurrentIdentity()
            val alreadyInThisCall = currentRoom.remoteParticipants.values
                .mapNotNull { it.identity?.value }
                .toSet()

            val filteredUsers = contacts.filter {
                it.identity != myIdentity && !alreadyInThisCall.contains(it.identity)
            }

            adapter.submitList(filteredUsers)

            searchEt.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.lowercase()?.trim() ?: ""
                    adapter.submitList(filteredUsers.filter { query.isEmpty() || it.identity.lowercase().contains(query) })
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        dialog.show()
    }

    private fun inviteParticipantToCurrentCall(targetIdentity: String) {
        callViewModel.inviteParticipantToCurrentCall(targetIdentity)
    }

    private fun updateGroupCallFab() {
        if (selectedParticipants.isNotEmpty()) {
            binding.groupCallFab.show()
            binding.groupCallFab.text = "${selectedParticipants.size} KİŞİ İLE GRUP ARA"
        } else {
            binding.groupCallFab.hide()
        }
    }

    private fun startGroupCall() {
        if (selectedParticipants.isEmpty()) return
        val targets = selectedParticipants.joinToString(",")
        callViewModel.startCall(targets, true)
        selectedParticipants.clear()
        updateGroupCallFab()
    }

    private fun checkAndRequestPermissions() {
        if (!PermissionUtils.hasAllPermissions(this)) {
            requestPermissionLauncher.launch(PermissionUtils.getNeededPermissions())
        }
    }

    private fun showStatus(message: String, duration: Long = 3000L) {
        binding.statusTextView.showStatus(message, duration, lifecycleScope)
    }

    private val photoPickerLauncherInternal = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSelectedPhoto(it) }
    }

    private val cameraLauncherInternal = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { handlePhotoBitmap(it) }
    }

    private fun showImageSourceDialog(picker: androidx.activity.result.ActivityResultLauncher<String>, camera: androidx.activity.result.ActivityResultLauncher<Void?>) {
        val options = arrayOf("Galeriden Seç", "Kamera ile Çek")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Profil Fotoğrafı")
            .setItems(options) { _, which ->
                if (which == 0) picker.launch("image/*")
                else camera.launch(null)
            }
            .show()
    }

    private fun handleSelectedPhoto(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            bitmap?.let { handlePhotoBitmap(it) }
        } catch (_: Exception) {
            Toast.makeText(this, "Fotoğraf işlenemedi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePhotoBitmap(bitmap: android.graphics.Bitmap) {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        val resized = com.dogu.livekit.core.util.ImageUtils.resizeBitmap(bitmap, 200)
        val base64 = com.dogu.livekit.core.util.ImageUtils.bitmapToBase64(resized)
        authViewModel.updatePhoto(identity, base64)
    }

    private fun authOnServer(mode: String) {
        val identity = binding.identityEditText.text.toString().trim()
        val password = binding.passwordEditText.text.toString().trim()
        val isRemembered = binding.rememberMeCheckBox.isChecked

        if (identity.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result else "NO_TOKEN"
            val publicKey = KeyManager.getOrCreatePublicKeyBase64()
            authViewModel.login(identity, password, fcmToken, mode, publicKey, isRemembered)
        }
    }

    private fun startCall(target: String) {
        if (!sessionPreferences.isLoggedIn()) {
            Toast.makeText(this, "Önce giriş yapmalısın", Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Arama Türü")
            .setMessage("Nasıl aramak istiyorsun?")
            .setPositiveButton("Görüntülü Arama") { _, _ -> callViewModel.startCall(target, true) }
            .setNegativeButton("Sesli Arama") { _, _ -> callViewModel.startCall(target, false) }
            .setCancelable(true)
            .show()
    }

    private fun setupCallUIInteractions() {
        val rootLayout = findViewById<View>(R.id.fragment_container)
        rootLayout.setOnClickListener { toggleControlsVisibility() }
        binding.remoteVideosRecyclerView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) rootLayout.performClick()
            false
        }
    }

    private fun toggleControlsVisibility() {
        if (binding.remoteVideosRecyclerView.visibility != View.VISIBLE) return
        if (binding.callControls.visibility == View.VISIBLE) hideControls()
        else showControlsWithTimeout()
    }

    private fun showControlsWithTimeout() {
        controlsHideJob?.cancel()
        binding.callControls.animate().alpha(1f).setDuration(300).withStartAction {
            binding.callControls.visibility = View.VISIBLE
        }.start()

        controlsHideJob = lifecycleScope.launch {
            delay(5000.milliseconds)
            hideControls()
        }
    }

    private fun hideControls() {
        binding.callControls.animate().alpha(0f).setDuration(300).withEndAction {
            binding.callControls.visibility = View.GONE
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (sessionPreferences.isLoggedIn()) {
            callViewModel.startHeartbeat()
            callViewModel.startAutoRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        callViewModel.stopHeartbeat()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
