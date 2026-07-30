package com.dogu.livekit.ui

import com.dogu.livekit.encryption.EncryptionManager
import com.dogu.livekit.encryption.KeyManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.dogu.livekit.R
import com.dogu.livekit.call.CallManager
import com.dogu.livekit.hardware.AudioManagerCompat
import com.dogu.livekit.logging.Logger
import com.dogu.livekit.pref.SessionPreferences
import com.dogu.livekit.util.ImageUtils
import com.dogu.livekit.util.KeyboardUtils
import com.dogu.livekit.util.PermissionUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.firebase.messaging.FirebaseMessaging
import com.dogu.livekit.data.AppDatabase
import com.dogu.livekit.data.entity.CallLogEntity
import com.dogu.livekit.worker.HeartbeatWorker
import com.dogu.livekit.worker.UserSyncWorker
import androidx.work.*
import java.util.concurrent.TimeUnit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.flow.collect
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.livekit.android.LiveKit
import kotlin.time.Duration.Companion.milliseconds

import androidx.activity.viewModels
import com.dogu.livekit.viewmodel.AuthViewModel
import com.dogu.livekit.viewmodel.CallViewModel
import com.dogu.livekit.viewmodel.ContactsViewModel
import com.dogu.livekit.viewmodel.HistoryViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val contactsViewModel: ContactsViewModel by viewModels()
    private val callViewModel: CallViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()

    @Inject
    lateinit var sessionPreferences: SessionPreferences

    @Inject
    lateinit var userRepository: com.dogu.livekit.data.repository.UserRepository

    private lateinit var identityEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var rememberMeCheckBox: CheckBox
    private lateinit var leaveButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var speakerButton: MaterialButton
    private lateinit var switchCameraButton: MaterialButton
    private lateinit var addParticipantButton: MaterialButton
    private lateinit var cameraToggleButton: MaterialButton
    private lateinit var statusTextView: TextView
    private lateinit var currentUserTextView: TextView
    private lateinit var homePanel: View
    private lateinit var contactsPanel: View
    private lateinit var profilePanel: View
    private lateinit var dynamicContactsContainer: LinearLayout
    private lateinit var callControls: View
    private lateinit var uiContainer: View
    private lateinit var currentProfilePhotoImg: ImageView
    private lateinit var contactsCountBadge: TextView
    private lateinit var contactSearchEditText: EditText
    private var cachedContactsArray: org.json.JSONArray? = null

    private lateinit var historyPanel: View
    private lateinit var historyRecyclerView: androidx.recyclerview.widget.RecyclerView
    private val historyAdapter = CallLogAdapter()

    private lateinit var settingsMenuPanel: View
    private lateinit var accountSettingsPanel: View
    private lateinit var themeSettingsPanel: View
    private lateinit var accountUsernameTextView: TextView
    private lateinit var themeDarkCheck: ImageView
    private lateinit var themeLightCheck: ImageView
    private lateinit var connectionStatusBadge: TextView
    private var isAppOffline: Boolean = false

    private val selectedParticipants = mutableSetOf<String>()
    private lateinit var groupCallFab: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

    private lateinit var remoteVideosRecyclerView: androidx.recyclerview.widget.RecyclerView
    private val videoAdapter = VideoAdapter()

    private var isMicMuted = false
    private var isSpeakerOn = true

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
            Toast.makeText(this, "İzinler gerekli", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Kaydedilmiş tema tercihini setContentView'dan ÖNCE uygula
        if (sessionPreferences.isDarkTheme()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottom_navigation)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.header_layout)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (CallManager.room != null || callControls.visibility == View.VISIBLE) {
                    leaveRoom(false)
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
        
        startBackgroundWorkers()
        performInitialSync()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            authViewModel.authState.collect { state ->
                when (state) {
                    is AuthViewModel.AuthState.Loading -> showStatus("İşlem yapılıyor...", 10000)
                    is AuthViewModel.AuthState.Success -> {
                        currentUserTextView.text = state.identity
                        homePanel.visibility = View.GONE
                        val successMsg = if (state.isOnline) "Giriş Başarılı!" else "Çevrimdışı giriş başarılı"
                        showStatus(successMsg)
                        updateConnectionStatusBadge(state.isOnline)
                        loadOwnProfilePhoto()
                        navigateToContacts()
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
                        homePanel.visibility = View.VISIBLE
                        contactsPanel.visibility = View.GONE
                        profilePanel.visibility = View.GONE
                        showStatus("Çıkış yapıldı")
                    }
                    is AuthViewModel.AuthEvent.AccountDeleted -> {
                        homePanel.visibility = View.VISIBLE
                        showStatus("Hesap silindi")
                    }
                    is AuthViewModel.AuthEvent.PasswordChanged -> {
                        showStatus("Şifre değiştirildi")
                    }
                    is AuthViewModel.AuthEvent.PhotoUpdated -> {
                        showStatus("Fotoğraf güncellendi")
                        loadOwnProfilePhoto()
                    }
                    is AuthViewModel.AuthEvent.Error -> {
                        Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            contactsViewModel.contacts.collect { contacts ->
                renderContactsListFromEntities(contacts)
            }
        }

        lifecycleScope.launch {
            historyViewModel.history.collect { logs ->
                historyAdapter.submitList(logs)
            }
        }

        lifecycleScope.launch {
            callViewModel.isMicMuted.collect { muted ->
                muteButton.apply {
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
                cameraToggleButton.apply {
                    if (on) {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue_alpha)
                        setIconResource(R.drawable.ic_videocam_on)
                    } else {
                        backgroundTintList = ContextCompat.getColorStateList(context, R.color.danger_red)
                        setIconResource(R.drawable.ic_videocam_off)
                    }
                }
                switchCameraButton.isEnabled = on
                switchCameraButton.alpha = if (on) 1.0f else 0.5f
                val identity = sessionPreferences.getCurrentIdentity() ?: "Ben"
                videoAdapter.setCameraEnabled("$identity (Sen)", on)
                if (on) attachLocalVideoTrack()
            }
        }

        lifecycleScope.launch {
            callViewModel.isSpeakerOn.collect { on ->
                speakerButton.apply {
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
    }

    private fun renderContactsListFromEntities(entities: List<com.dogu.livekit.data.entity.UserEntity>) {
        dynamicContactsContainer.removeAllViews()
        val query = contactSearchEditText.text.toString().lowercase().trim()
        val myIdentity = sessionPreferences.getCurrentIdentity()?.lowercase() ?: ""
        
        var count = 0
        entities.forEach { user ->
            if (user.identity.lowercase() == myIdentity) return@forEach
            if (query.isNotEmpty() && !user.identity.lowercase().contains(query)) return@forEach
            
            // Uygulama genelinde bağlantı yoksa herkesi çevrimdışı göster
            val effectiveOnline = if (isAppOffline) false else user.isOnline
            addUserButton(user.identity, effectiveOnline, user.profilePhoto, user.currentRoom)
            count++
        }
        
        contactsCountBadge.text = count.toString()
        contactsCountBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun startBackgroundWorkers() {
        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            15, TimeUnit.MINUTES
        ).setBackoffCriteria(
            BackoffPolicy.LINEAR,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        ).build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "livekit_heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            heartbeatRequest
        )

        val syncRequest = PeriodicWorkRequestBuilder<UserSyncWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "user_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun performInitialSync() {
        lifecycleScope.launch {
            try {
                val result = userRepository.fetchUsers()
                if (result.isSuccess) {
                    val serverUsers = result.getOrNull() ?: org.json.JSONArray()
                    userRepository.syncUsers(serverUsers)
                    triggerAutoSync()
                }
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
        // Her ihtimale karşı tüm arama bildirimlerini temizle
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(com.dogu.livekit.MyFirebaseMessagingService.CALL_NOTIFICATION_ID)

        if (intent?.getBooleanExtra("start_call", false) == true) {
            val url = intent.getStringExtra("url")
            val token = intent.getStringExtra("token")
            // YENİ: IncomingCallActivity zaten şifreyi çözüp buraya düz metin olarak
            // yolluyor (bu, sadece cihaz içi bir Intent — hiç ağdan geçmiyor).
            val roomKey = intent.getStringExtra("room_key")

            // ÖNEMLİ BUG FIX: Intent içindeki "arama başlat" bayrağını temizliyoruz.
            // Yoksa tema değişikliği gibi Activity'nin yeniden oluşturulduğu (recreate)
            // durumlarda uygulama tekrar arama yapmaya çalışır.
            intent.putExtra("start_call", false)

            if (url != null && token != null) {
                lifecycleScope.launch {
                    connectToRoom(url, token, true, roomKey)
                }
            }
        }
    }

    private fun bindUI() {
        identityEditText = findViewById(R.id.identityEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        rememberMeCheckBox = findViewById(R.id.rememberMeCheckBox)
        rememberMeCheckBox.isChecked = true
        leaveButton = findViewById(R.id.leaveButton)
        muteButton = findViewById(R.id.muteButton)
        speakerButton = findViewById(R.id.speakerButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        addParticipantButton = findViewById(R.id.addParticipantButton)
        statusTextView = findViewById(R.id.statusTextView)
        currentUserTextView = findViewById(R.id.currentUserTextView)
        homePanel = findViewById(R.id.home_panel)
        contactsPanel = findViewById(R.id.contacts_panel)
        profilePanel = findViewById(R.id.profile_panel)
        dynamicContactsContainer = findViewById(R.id.dynamic_contacts_container)
        callControls = findViewById(R.id.call_controls)
        uiContainer = findViewById(R.id.ui_container)
        currentProfilePhotoImg = findViewById(R.id.currentProfilePhotoImg)
        contactsCountBadge = findViewById(R.id.contactsCountBadge)
        contactSearchEditText = findViewById(R.id.contactSearchEditText)
        settingsMenuPanel = findViewById(R.id.settingsMenuPanel)
        accountSettingsPanel = findViewById(R.id.accountSettingsPanel)
        themeSettingsPanel = findViewById(R.id.themeSettingsPanel)
        accountUsernameTextView = findViewById(R.id.accountUsernameTextView)
        themeDarkCheck = findViewById(R.id.themeDarkCheck)
        themeLightCheck = findViewById(R.id.themeLightCheck)
        connectionStatusBadge = findViewById(R.id.connectionStatusBadge)

        historyPanel = findViewById(R.id.history_panel)
        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        historyRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        remoteVideosRecyclerView = findViewById(R.id.remoteVideosRecyclerView)

        remoteVideosRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, 2).apply {
                spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val count = videoAdapter.itemCount
                        return VideoAdapter.getSpanSizeForPosition(count, position)
                    }
                }
            }
            adapter = videoAdapter
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        findViewById<Button>(R.id.registerButton).setOnClickListener { authOnServer("register") }
        findViewById<Button>(R.id.loginButton).setOnClickListener { authOnServer("login") }
        leaveButton.setOnClickListener { leaveRoom(false) }
        cameraToggleButton = findViewById(R.id.cameraToggleButton)
        cameraToggleButton.setOnClickListener { toggleCamera() }
        muteButton.setOnClickListener { toggleMute() }
        speakerButton.setOnClickListener { toggleSpeaker() }
        switchCameraButton.setOnClickListener { switchCamera() }
        addParticipantButton.setOnClickListener { showAddParticipantDialog() }

        // Profil Fotoğrafı değiştirme butonuna global launcherları bağladık
        findViewById<View>(R.id.editProfilePhotoFab).setOnClickListener {
            showImageSourceDialog(photoPickerLauncherInternal, cameraLauncherInternal)
        }

        // Rehber arama kutusu: yazdıkça listeyi filtrele
        contactSearchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderContactsList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Ayarlar: Hesap satırına tıklayınca Hesap alt panelini göster
        findViewById<View>(R.id.settingsAccountRow).setOnClickListener {
            accountUsernameTextView.text = "Kullanıcı adı: ${sessionPreferences.getCurrentIdentity() ?: "-"}"
            settingsMenuPanel.visibility = View.GONE
            accountSettingsPanel.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.accountBackRow).setOnClickListener {
            accountSettingsPanel.visibility = View.GONE
            settingsMenuPanel.visibility = View.VISIBLE
        }

        // Ayarlar: Tema satırına tıklayınca Tema alt panelini göster
        findViewById<View>(R.id.settingsThemeRow).setOnClickListener {
            refreshThemeCheckmarks()
            settingsMenuPanel.visibility = View.GONE
            themeSettingsPanel.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.themeBackRow).setOnClickListener {
            themeSettingsPanel.visibility = View.GONE
            settingsMenuPanel.visibility = View.VISIBLE
        }

        findViewById<View>(R.id.themeDarkOption).setOnClickListener { applyThemeChoice(isDark = true) }
        findViewById<View>(R.id.themeLightOption).setOnClickListener { applyThemeChoice(isDark = false) }

        bottomNav.setOnItemSelectedListener { item ->
            if (!sessionPreferences.isLoggedIn()) {
                Toast.makeText(this, "Bu özelliği kullanmak için önce giriş yapmalısın", Toast.LENGTH_SHORT).show()
                return@setOnItemSelectedListener false
            }

            when (item.itemId) {
                R.id.nav_contacts -> {
                    homePanel.visibility = View.GONE
                    contactsPanel.visibility = View.VISIBLE
                    profilePanel.visibility = View.GONE
                    historyPanel.visibility = View.GONE
                    refreshContacts()
                    true
                }
                R.id.nav_history -> {
                    homePanel.visibility = View.GONE
                    contactsPanel.visibility = View.GONE
                    profilePanel.visibility = View.GONE
                    historyPanel.visibility = View.VISIBLE
                    loadHistory()
                    true
                }
                R.id.nav_profile -> {
                    homePanel.visibility = View.GONE
                    contactsPanel.visibility = View.GONE
                    historyPanel.visibility = View.GONE
                    profilePanel.visibility = View.VISIBLE
                    // Ayarlar sekmesine her girişte ana listeye dön (Hesap/Tema alt panelinde kalmasın)
                    accountSettingsPanel.visibility = View.GONE
                    themeSettingsPanel.visibility = View.GONE
                    settingsMenuPanel.visibility = View.VISIBLE
                    loadOwnProfilePhoto()
                    true
                }
                else -> false
            }
        }

        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            performLogout()
        }

        findViewById<Button>(R.id.deleteAccountButton).setOnClickListener {
            confirmAccountDeletion()
        }

        findViewById<Button>(R.id.changePasswordButton).setOnClickListener {
            showChangePasswordDialog()
        }

        groupCallFab = findViewById(R.id.groupCallFab)
        groupCallFab.setOnClickListener { startGroupCall() }
    }

    private fun refreshThemeCheckmarks() {
        val isDark = sessionPreferences.isDarkTheme()
        themeDarkCheck.setImageResource(
            if (isDark) android.R.drawable.checkbox_on_background else android.R.drawable.checkbox_off_background
        )
        themeLightCheck.setImageResource(
            if (!isDark) android.R.drawable.checkbox_on_background else android.R.drawable.checkbox_off_background
        )
    }

    private fun applyThemeChoice(isDark: Boolean) {
        if (sessionPreferences.isDarkTheme() == isDark) return // zaten seçili, bir şey yapma

        sessionPreferences.setDarkTheme(isDark)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        // Yeni renk paletinin (values / values-night) uygulanması için Activity'nin yeniden çizilmesi lazım
        recreate()
    }

    // Profil fotoğrafını sunucudan çekip Ayarlar ekranındaki ImageView'a basar.
    // Daha önce bu fonksiyon hiç yoktu: fotoğraf sadece yüklendiği anda RAM'de gösteriliyordu,
    // uygulama yeniden açıldığında ya da sekme değiştirilip geri dönüldüğünde kayboluyordu.
    private fun loadOwnProfilePhoto() {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        lifecycleScope.launch {
            val result = userRepository.fetchUsers()
            if (result.isSuccess) {
                val usersArray = result.getOrNull() ?: return@launch
                for (i in 0 until usersArray.length()) {
                    val user = usersArray.getJSONObject(i)
                    if (user.optString("identity") == identity) {
                        val photoBase64 = user.optString("profilePhoto", "")
                        if (photoBase64.isNotEmpty()) {
                            val bitmap = ImageUtils.base64ToBitmap(photoBase64)
                            if (bitmap != null) {
                                runOnUiThread {
                                    currentProfilePhotoImg.setImageBitmap(bitmap)
                                    currentProfilePhotoImg.setPadding(0, 0, 0, 0)
                                    currentProfilePhotoImg.imageTintList = null
                                }
                            }
                        }
                        break
                    }
                }
            } else {
                Logger.e("Profil fotoğrafı yüklenemedi: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun performLogout(message: String = "Oturum kapatıldı") {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        authViewModel.logout(identity)
    }

    private fun confirmAccountDeletion() {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hesabı Sil")
            .setMessage("'$identity' kullanıcısını hem bu telefondan hem de sunucudan kalıcı olarak silmek istediğine emin misin?")
            .setPositiveButton("SİL") { _, _ -> authViewModel.deleteAccount(identity) }
            .setNegativeButton("VAZGEÇ", null)
            .show()
    }

    private fun showChangePasswordDialog() {
        val identity = sessionPreferences.getCurrentIdentity() ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val oldPassEt = dialogView.findViewById<EditText>(R.id.oldPasswordEt)
        val newPassEt = dialogView.findViewById<EditText>(R.id.newPasswordEt)
        val confirmPassEt = dialogView.findViewById<EditText>(R.id.confirmPasswordEt)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("DEĞİŞTİR") { _, _ ->
                val oldPass = oldPassEt.text.toString().trim()
                val newPass = newPassEt.text.toString().trim()
                val confirmPass = confirmPassEt.text.toString().trim()

                if (oldPass.isEmpty() || newPass.isEmpty()) {
                    Toast.makeText(this, "Alanlar boş bırakılamaz", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPass != confirmPass) {
                    Toast.makeText(this, "Yeni şifreler eşleşmiyor", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                authViewModel.changePassword(identity, oldPass, newPass)
            }
            .setNegativeButton("VAZGEÇ", null)
            .show()
    }

    private fun loadSession() {
        val savedIdentity = sessionPreferences.getRememberedIdentity()
        val savedPassword = sessionPreferences.getRememberedPassword()

        if (savedIdentity.isNotEmpty()) {
            identityEditText.setText(savedIdentity)
            passwordEditText.setText(savedPassword)
            rememberMeCheckBox.isChecked = true
        }

        if (sessionPreferences.isLoggedIn() && savedIdentity.isNotEmpty()) {
            currentUserTextView.text = savedIdentity
            homePanel.visibility = View.GONE
            updateConnectionStatusBadge(true)
            navigateToContacts()
            loadOwnProfilePhoto() // uygulama kapatılıp açıldığında profil fotoğrafını da geri yükle
        } else {
            currentUserTextView.text = "Misafir"
            homePanel.visibility = View.VISIBLE
        }
    }

    private fun navigateToContacts() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.selectedItemId = R.id.nav_contacts
    }

    private fun refreshContacts() {
        selectedParticipants.clear()
        updateGroupCallFab()
        contactsViewModel.refreshContacts()
    }

    private fun updateConnectionStatusBadge(isOnline: Boolean) {
        val previousState = isAppOffline
        isAppOffline = !isOnline

        // Durum değiştiyse listeyi tekrar render et (butonların aktifleşmesi/pasifleşmesi için)
        if (previousState != isAppOffline) {
            renderContactsListFromEntities(contactsViewModel.contacts.value)
        }

        if (!sessionPreferences.isLoggedIn()) {
            connectionStatusBadge.visibility = View.GONE
            return
        }
        connectionStatusBadge.visibility = View.VISIBLE
        if (isOnline) {
            connectionStatusBadge.text = "ÇEVRİMİÇİ"
            connectionStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.success_green)
            )
        } else {
            connectionStatusBadge.text = "ÇEVRİMDIŞI"
            connectionStatusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.danger_red)
            )
        }
    }

    private fun loadHistory() {
        // historyViewModel.history already observed in observeViewModel
    }

    private fun renderContactsList(query: String) {
        dynamicContactsContainer.removeAllViews()
        val usersArray = cachedContactsArray ?: return
        val currentIdentity = sessionPreferences.getCurrentIdentity()?.trim()
        val totalCount = if (usersArray.length() > 0) usersArray.length() - 1 else 0
        contactsCountBadge.text = "$totalCount Kullanıcı"

        val trimmedQuery = query.trim().lowercase()
        var addedCount = 0
        for (i in 0 until usersArray.length()) {
            val user = usersArray.getJSONObject(i)
            val identity = user.getString("identity").trim()
            var isOnline = user.optBoolean("isOnline", false)
            val photo = user.optString("profilePhoto", "")
            val rawRoom = user.optString("currentRoom", "")
            val currentRoom = if (rawRoom == "null" || rawRoom.isEmpty()) null else rawRoom

            if (identity == currentIdentity) continue
            if (trimmedQuery.isNotEmpty() && !identity.lowercase().contains(trimmedQuery)) continue

            // Eğer uygulama çevrimdışıysa, kimseden anlık bilgi alamayacağımız için 
            // herkesi çevrimdışı gösteriyoruz.
            if (isAppOffline) isOnline = false

            addUserButton(identity, isOnline, photo, currentRoom)
            addedCount++
        }

        if (addedCount == 0) {
            val tv = TextView(this@MainActivity).apply {
                text = when {
                    usersArray.length() == 0 -> "Sunucuda kimse yok."
                    trimmedQuery.isNotEmpty() -> "\"$query\" ile eşleşen kimse yok."
                    else -> "Senden başka kimse yok."
                }
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_gray))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 32.dpToPx() }
            }
            dynamicContactsContainer.addView(tv)
        }
    }

    private fun addUserButton(identity: String, isOnline: Boolean, photoBase64: String? = null, currentRoom: String? = null) {
        val view = layoutInflater.inflate(R.layout.item_contact, dynamicContactsContainer, false)

        val avatarImg = view.findViewById<ImageView>(R.id.contactAvatar)
        val nameTv = view.findViewById<TextView>(R.id.contactName)
        val statusDot = view.findViewById<View>(R.id.statusDot)
        val statusTv = view.findViewById<TextView>(R.id.contactStatus)
        val selectCb = view.findViewById<CheckBox>(R.id.contactSelectCb)
        val callBtn = view.findViewById<MaterialButton>(R.id.contactCallBtn)

        nameTv.text = identity

        if (!photoBase64.isNullOrEmpty()) {
            val bitmap = ImageUtils.base64ToBitmap(photoBase64)
            if (bitmap != null) {
                avatarImg.setImageBitmap(bitmap)
                avatarImg.imageTintList = null
                avatarImg.setPadding(0, 0, 0, 0)
            } else {
                setDefaultAvatar(avatarImg)
            }
        } else {
            setDefaultAvatar(avatarImg)
        }

        // Sadece Çevrimiçiyse ve odası geçerli bir değerse "Görüşmede" sayılır
        val isInCall = isOnline && !currentRoom.isNullOrEmpty() && currentRoom != "null"

        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                isInCall -> ContextCompat.getColor(this, R.color.danger_red)
                isOnline -> ContextCompat.getColor(this, R.color.success_green)
                else -> ContextCompat.getColor(this, R.color.text_gray)
            }
        )

        statusTv.text = when {
            isInCall -> "Görüşmede"
            isOnline -> "Çevrimiçi"
            else -> "Çevrimdışı"
        }
        statusTv.setTextColor(statusDot.backgroundTintList!!.defaultColor)

        selectCb.isEnabled = !isInCall
        selectCb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedParticipants.add(identity)
            else selectedParticipants.remove(identity)
            updateGroupCallFab()
        }

        val canCall = isOnline && !isInCall && !isAppOffline
        callBtn.text = when {
            isInCall -> "MEŞGUL"
            !isOnline || isAppOffline -> "PASİF"
            else -> "ARA"
        }
        callBtn.setIconResource(android.R.drawable.ic_menu_call)
        callBtn.backgroundTintList = ContextCompat.getColorStateList(this,
            if (!canCall) R.color.text_gray else R.color.accent_blue)
        
        callBtn.isEnabled = canCall
        callBtn.alpha = if (!canCall) 0.5f else 1f

        callBtn.setOnClickListener {
            startCall(identity)
        }

        dynamicContactsContainer.addView(view)
    }

    private fun showAddParticipantDialog() {
        val currentRoom = CallManager.room
        if (currentRoom == null) {
            Toast.makeText(this, "Aktif bir görüşme yok.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(this, R.style.TransparentBottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_add_participant, null)
        dialog.setContentView(view)

        val searchEt = view.findViewById<EditText>(R.id.participantSearchEditText)
        val container = view.findViewById<LinearLayout>(R.id.dialog_participants_container)

        lifecycleScope.launch {
            val result = userRepository.fetchUsers()
            if (result.isFailure) {
                Toast.makeText(this@MainActivity, "Kullanıcı listesi alınamadı.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val usersArray = result.getOrNull() ?: return@launch
            val myIdentity = sessionPreferences.getCurrentIdentity()
            val alreadyInThisCall = currentRoom.remoteParticipants.values
                .mapNotNull { it.identity?.value }
                .toSet()

            // Filtrelenmiş listeyi tutalım
            val filteredUsers = mutableListOf<JSONObject>()
            for (i in 0 until usersArray.length()) {
                val user = usersArray.getJSONObject(i)
                val uIdentity = user.getString("identity")
                if (uIdentity == myIdentity || uIdentity in alreadyInThisCall) continue
                filteredUsers.add(user)
            }

            renderAddParticipantList(container, filteredUsers, "", dialog)

            searchEt.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderAddParticipantList(container, filteredUsers, s?.toString() ?: "", dialog)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        dialog.show()
    }

    private fun renderAddParticipantList(
        container: LinearLayout,
        users: List<JSONObject>,
        query: String,
        dialog: BottomSheetDialog
    ) {
        container.removeAllViews()
        val trimmedQuery = query.trim().lowercase()

        var count = 0
        for (user in users) {
            val identity = user.getString("identity")
            if (trimmedQuery.isNotEmpty() && !identity.lowercase().contains(trimmedQuery)) continue

            val isOnline = user.optBoolean("isOnline", false)
            val photo = user.optString("profilePhoto", "")
            val rawRoom = user.optString("currentRoom", "")
            val currentRoom = if (rawRoom == "null" || rawRoom.isEmpty()) null else rawRoom

            addParticipantItemView(container, identity, isOnline, photo, currentRoom, dialog)
            count++
        }

        if (count == 0) {
            val tv = TextView(this).apply {
                text = if (trimmedQuery.isEmpty()) "Davet edilecek kimse yok." else "Sonuç bulunamadı."
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_gray))
                setPadding(0, 32.dpToPx(), 0, 0)
            }
            container.addView(tv)
        }
    }

    private fun addParticipantItemView(
        container: LinearLayout,
        identity: String,
        isOnline: Boolean,
        photoBase64: String?,
        currentRoom: String?,
        dialog: BottomSheetDialog
    ) {
        val view = layoutInflater.inflate(R.layout.item_contact, container, false)
        val avatarImg = view.findViewById<ImageView>(R.id.contactAvatar)
        val nameTv = view.findViewById<TextView>(R.id.contactName)
        val statusDot = view.findViewById<View>(R.id.statusDot)
        val statusTv = view.findViewById<TextView>(R.id.contactStatus)
        val selectCb = view.findViewById<CheckBox>(R.id.contactSelectCb)
        val callBtn = view.findViewById<MaterialButton>(R.id.contactCallBtn)

        selectCb.visibility = View.GONE
        nameTv.text = identity

        if (!photoBase64.isNullOrEmpty()) {
            val bitmap = ImageUtils.base64ToBitmap(photoBase64)
            if (bitmap != null) {
                avatarImg.setImageBitmap(bitmap)
                avatarImg.imageTintList = null
                avatarImg.setPadding(0, 0, 0, 0)
            } else setDefaultAvatar(avatarImg)
        } else setDefaultAvatar(avatarImg)

        // Sadece Çevrimiçiyse ve odası geçerli bir değerse "Görüşmede" sayılır
        val isInCall = isOnline && !currentRoom.isNullOrEmpty() && currentRoom != "null"
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                isInCall -> ContextCompat.getColor(this, R.color.danger_red)
                isOnline -> ContextCompat.getColor(this, R.color.success_green)
                else -> ContextCompat.getColor(this, R.color.text_gray)
            }
        )
        statusTv.text = when {
            isInCall -> "Görüşmede"
            isOnline -> "Çevrimiçi"
            else -> "Çevrimdışı"
        }
        statusTv.setTextColor(statusDot.backgroundTintList!!.defaultColor)

        val isInviteable = isOnline && !isInCall
        callBtn.text = if (isInCall) "MEŞGUL" else "DAVET ET"
        callBtn.isEnabled = isInviteable
        callBtn.alpha = if (isInviteable) 1f else 0.5f
        callBtn.backgroundTintList = ContextCompat.getColorStateList(this, 
            if (isInviteable) R.color.accent_blue else R.color.text_gray)

        callBtn.setOnClickListener {
            inviteParticipantToCurrentCall(identity)
            dialog.dismiss()
        }

        container.addView(view)
    }

    // Seçilen kişiyi, YENİ bir oda açmadan, ŞU AN İÇİNDE OLDUĞUMUZ görüşmeye davet eder.
    // "room" parametresini mevcut oda ismiyle vererek sunucunun yeni bir oda üretmesini engelliyoruz.
    private fun inviteParticipantToCurrentCall(targetIdentity: String) {
        val myIdentity = sessionPreferences.getCurrentIdentity() ?: return
        val currentRoomName = CallManager.room?.name ?: return
        // YENİ: davet edilen kişi, görüşmedeki HERKESLE AYNI oda anahtarına sahip olmalı
        // (yoksa onun sesini/görüntüsünü çözemez). Bu yüzden CallManager'da sakladığımız
        // mevcut anahtarı, yeni kişinin genel anahtarıyla tekrar şifreliyoruz.
        val roomKey = CallManager.currentRoomKey

        lifecycleScope.launch {
            val encryptedKeysJson = if (roomKey != null) {
                buildEncryptedKeysForTargets(targetIdentity, roomKey)
            } else {
                "{}" // görüşme E2EE'siz başladıysa şifrelenecek bir şey yok
            }

            val result = userRepository.fetchToken(myIdentity, targetIdentity, currentRoomName, encryptedKeysJson)
            if (result.isSuccess) {
                showStatus("$targetIdentity davet edildi, bekleniyor...")
            } else {
                Toast.makeText(this@MainActivity, "Davet gönderilemedi.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateGroupCallFab() {
        if (selectedParticipants.isNotEmpty()) {
            groupCallFab.show()
            groupCallFab.text = "${selectedParticipants.size} KİŞİ İLE GRUP ARA"
        } else {
            groupCallFab.hide()
        }
    }

    private fun startGroupCall() {
        if (selectedParticipants.isEmpty()) return

        val targets = selectedParticipants.joinToString(",")
        startCall(targets) // Mevcut startCall'u virgülle ayrılmış liste ile çağırıyoruz
        selectedParticipants.clear()
        updateGroupCallFab()
    }


    private fun checkAndRequestPermissions() {
        if (!PermissionUtils.hasAllPermissions(this)) {
            requestPermissionLauncher.launch(PermissionUtils.getNeededPermissions())
        }
    }


    private fun setDefaultAvatar(imageView: ImageView) {
        imageView.setImageResource(R.drawable.ic_person)
        imageView.setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
        imageView.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_blue))
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showStatus(message: String, duration: Long = 3000L) {
        runOnUiThread {
            statusTextView.text = message
            statusTextView.visibility = View.VISIBLE
            statusTextView.animate()
                .alpha(1f)
                .translationYBy(-20f)
                .setDuration(300)
                .withEndAction {
                    lifecycleScope.launch {
                        delay(duration.milliseconds)
                        statusTextView.animate()
                            .alpha(0f)
                            .translationYBy(20f)
                            .setDuration(300)
                            .withEndAction {
                                statusTextView.visibility = View.GONE
                            }
                    }
                }
        }
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
        val resized = ImageUtils.resizeBitmap(bitmap, 200)
        val base64 = ImageUtils.bitmapToBase64(resized)

        val identity = sessionPreferences.getCurrentIdentity()
        if (identity != null) {
            authViewModel.updatePhoto(identity, base64)
            // UI updates (like setting the bitmap) will happen in observeViewModel via loadOwnProfilePhoto
            // or we could optimistically set it here if we want instant feedback.
            // But let's follow the MVVM event pattern.
            if (homePanel.visibility == View.VISIBLE) {
                homePanel.visibility = View.GONE
                navigateToContacts()
            }
        }
    }

    private fun authOnServer(mode: String) {
        val identity = identityEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (identity.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result else "NO_TOKEN"
            val publicKey = KeyManager.getOrCreatePublicKeyBase64()
            authViewModel.login(identity, password, fcmToken, mode, publicKey, rememberMeCheckBox.isChecked)
        }
    }

    private var callTimeoutJob: kotlinx.coroutines.Job? = null

    private fun startCall(target: String) {
        val identity = sessionPreferences.getCurrentIdentity() ?: "Misafir"
        if (identity == "Misafir") {
            Toast.makeText(this, "Önce giriş yapmalısın", Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Arama Türü")
            .setMessage("Nasıl aramak istiyorsun?")
            .setPositiveButton("Görüntülü Arama") { _, _ -> startCallInternal(target, true) }
            .setNegativeButton("Sesli Arama") { _, _ -> startCallInternal(target, false) }
            .setCancelable(true)
            .show()
    }

    private fun startCallInternal(target: String, useVideo: Boolean) {
        val identity = sessionPreferences.getCurrentIdentity() ?: "Misafir"

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(com.dogu.livekit.MyFirebaseMessagingService.CALL_NOTIFICATION_ID)

        KeyboardUtils.hideKeyboard(this)
        val callMsg = if (target.contains(",")) "Grup araması yapılıyor..." else "$target aranıyor..."
        showStatus(callMsg, 20000)

        runOnUiThread {
            uiContainer.visibility = View.GONE
            findViewById<View>(R.id.bottom_navigation).visibility = View.GONE
            callControls.visibility = View.VISIBLE
            leaveButton.isEnabled = true
        }

        callTimeoutJob?.cancel()
        callTimeoutJob = lifecycleScope.launch {
            delay(20000.milliseconds)
            val room = CallManager.room
            if (room == null || room.state == io.livekit.android.room.Room.State.CONNECTING ||
                room.remoteParticipants.isEmpty()) {
                Toast.makeText(this@MainActivity, "Kimse yanıt vermedi.", Toast.LENGTH_LONG).show()
                leaveRoom(true)
            } else {
                Logger.d("Zaman aşımı doldu ama odaya en az bir kişi katılmış, görüşmeye devam ediliyor.")
            }
        }

        lifecycleScope.launch {
            try {
                val roomKey = EncryptionManager.generateRoomKey()
                val encryptedKeysJson = buildEncryptedKeysForTargets(target, roomKey)

                val result = userRepository.fetchToken(identity, target, null, encryptedKeysJson)
                if (result.isSuccess) {
                    val json = result.getOrNull()!!

                    // Arama kaydını kaydet
                    userRepository.saveCallLog(target, "OUTGOING")

                    val busyTargetsArray = json.optJSONArray("busyTargets")
                    if (busyTargetsArray != null && busyTargetsArray.length() > 0) {
                        val busyNames = (0 until busyTargetsArray.length()).map { busyTargetsArray.getString(it) }
                        Toast.makeText(
                            this@MainActivity,
                            "${busyNames.joinToString(", ")} şu an aktif bir görüşmede",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    connectToRoom(json.getString("url"), json.getString("token"), useVideo, roomKey)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Bilinmeyen hata"
                    Toast.makeText(this@MainActivity, "Arama başarısız: $errorMsg", Toast.LENGTH_LONG).show()
                    leaveRoom(true)
                }
            } catch (e: Exception) {
                Logger.e("startCall hatası: ${e.message}")
                Toast.makeText(this@MainActivity, "Bağlantı sırasında bir hata oluştu: ${e.message}", Toast.LENGTH_LONG).show()
                leaveRoom(true)
            }
        }
    }

    // YENİ: Verilen virgüllü hedef listesi için, oda anahtarını her birinin KENDİ
    // genel anahtarıyla ayrı ayrı şifreleyip { "identity": "şifreliMetin" } JSON'u üretir.
    // Genel anahtarı henüz kayıtlı olmayan (örn. hiç login olmamış) hedefler atlanır —
    // o kişi için o an E2EE kurulamaz, aramaya yine de devam edilir.
    private suspend fun buildEncryptedKeysForTargets(targetCsv: String, roomKey: String): String =
        withContext(Dispatchers.Default) {
            val targets = targetCsv.split(",").map { it.trim() }.toSet()
            val obj = JSONObject()

            val usersResult = userRepository.fetchUsers()
            if (usersResult.isSuccess) {
                val usersArray = usersResult.getOrNull()!!
                for (i in 0 until usersArray.length()) {
                    val user = usersArray.getJSONObject(i)
                    val uid = user.getString("identity")
                    if (uid !in targets) continue

                    val pubKey = user.optString("publicKey", "")
                    // GEÇİCİ TEŞHİS LOGU: hedefin genel anahtarı gerçekten var mı?
                    Logger.d("E2EE_DEBUG: hedef=$uid, publicKey uzunluğu=${pubKey.length}")
                    if (pubKey.isEmpty()) continue // hedefin genel anahtarı yoksa şifreleyemeyiz

                    val encrypted = KeyManager.encryptForPublicKey(pubKey, roomKey.toByteArray(Charsets.UTF_8))
                    obj.put(uid, encrypted)
                    Logger.d("E2EE_DEBUG: $uid için şifrelenmiş anahtar üretildi, uzunluk=${encrypted.length}")
                }
            } else {
                Logger.e("E2EE_DEBUG: fetchUsers başarısız oldu!")
            }
            Logger.d("E2EE_DEBUG: sonuç JSON = $obj")
            obj.toString()
        }



    private var controlsHideJob: kotlinx.coroutines.Job? = null

    private fun setupCallUIInteractions() {
        val rootLayout = findViewById<View>(R.id.fragment_container)

        // Ekrana tıklandığında kontrolleri göster/gizle
        rootLayout.setOnClickListener {
            toggleControlsVisibility()
        }

        // RecyclerView (videoların olduğu alan) tıklandığında da çalışsın
        remoteVideosRecyclerView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                rootLayout.performClick()
            }
            false
        }
    }

    private fun toggleControlsVisibility() {
        // Sadece video alanı görünürse (yani aramadaysak) kontrolleri göster/gizle
        if (remoteVideosRecyclerView.visibility != View.VISIBLE) return

        if (callControls.visibility == View.VISIBLE) {
            hideControls()
        } else {
            showControlsWithTimeout()
        }
    }

    private fun showControlsWithTimeout() {
        controlsHideJob?.cancel()

        runOnUiThread {
            callControls.animate().alpha(1f).setDuration(300).withStartAction {
                callControls.visibility = View.VISIBLE
            }.start()
        }

        controlsHideJob = lifecycleScope.launch {
            delay(5000.milliseconds) // 5 saniye sonra gizle
            hideControls()
        }
    }

    private fun hideControls() {
        runOnUiThread {
            callControls.animate().alpha(0f).setDuration(300).withEndAction {
                callControls.visibility = View.GONE
            }.start()
        }
    }

    private suspend fun connectToRoom(url: String, token: String, useVideo: Boolean, roomKey: String?) {
        LiveKit.init(applicationContext)
        Logger.e("CONNECTING TO ROOM: $url with Token: ${token.substring(0, 15)}...")

        val myIdentity = sessionPreferences.getCurrentIdentity() ?: "Ben"
        val localIdentity = "$myIdentity (Sen)"

        // AdaptiveStream'i kapatalım ki her track gelsin
        // YENİ: roomKey null ise (örn. hedefin henüz genel anahtarı yoksa) E2EE'siz
        // bağlanıyoruz — yoksa dinamik, o göreve özel anahtarla şifreliyoruz.
        val roomOptions = io.livekit.android.RoomOptions(
            adaptiveStream = false,
            dynacast = false,
            e2eeOptions = roomKey?.let { EncryptionManager.getE2EEOptions(it) }
        )

        // YENİ: bu odanın anahtarını CallManager'da tutuyoruz ki "Kullanıcı Ekle" ile
        // davet edeceğimiz kişiye AYNI anahtarı tekrar şifreleyip yollayabilelim.
        CallManager.currentRoomKey = roomKey
        val newRoom = CallManager.connect(
            this,
            url,
            token,
            useVideo,
            null,
            null,
            roomOptions
        )

        runOnUiThread {
            remoteVideosRecyclerView.visibility = View.VISIBLE
            AudioManagerCompat.setSpeakerphoneOn(this, true)
            isSpeakerOn = true
            isMicMuted = false

            speakerButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue_alpha)
            speakerButton.setIconResource(R.drawable.ic_speaker_on)

            muteButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue_alpha)
            muteButton.setIconResource(R.drawable.ic_mic_on)

            switchCameraButton.isEnabled = useVideo
            switchCameraButton.alpha = if (useVideo) 1.0f else 0.5f
            switchCameraButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_blue_alpha)
            switchCameraButton.setIconResource(R.drawable.ic_camera_switch)
            callViewModel.setCameraState(useVideo)
            // isCameraCurrentlyOn was removed, using callViewModel state
            showStatus("Bağlanıyor...", 5000)
            setupCallUIInteractions()
            showControlsWithTimeout()
        }

        // Remote trackları ve olayları dinle
        lifecycleScope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        val participant = event.participant
                        Logger.e("Track Subscribed: ${participant.identity?.value}, Track Type: ${track::class.java.simpleName}")
                        if (track is VideoTrack) {
                            runOnUiThread {
                                val identity = participant.identity?.value ?: "Bilinmeyen"
                                if (videoAdapter.addTrack(identity, track)) {
                                    // Başlangıçta sessize alınmışsa siyah ekranı göster
                                    if (event.publication.muted) {
                                        videoAdapter.setCameraEnabled(identity, false)
                                    }
                                    remoteVideosRecyclerView.post {
                                        remoteVideosRecyclerView.requestLayout()
                                        videoAdapter.notifyDataSetChanged()
                                    }
                                }
                                showStatus("Yeni katılımcı bağlandı: ${participant.identity?.value}")
                            }
                        }
                    }
                    is RoomEvent.TrackMuted -> {
                        val participant = event.participant
                        val isLocal = participant is io.livekit.android.room.participant.LocalParticipant
                        val identity = if (isLocal) localIdentity else participant.identity?.value ?: ""
                        if (identity.isNotEmpty() && event.publication.kind == Track.Kind.VIDEO) {
                            runOnUiThread { videoAdapter.setCameraEnabled(identity, false) }
                        }
                    }
                    is RoomEvent.TrackUnmuted -> {
                        val participant = event.participant
                        val isLocal = participant is io.livekit.android.room.participant.LocalParticipant
                        val identity = if (isLocal) localIdentity else participant.identity?.value ?: ""
                        if (identity.isNotEmpty() && event.publication.kind == Track.Kind.VIDEO) {
                            runOnUiThread { videoAdapter.setCameraEnabled(identity, true) }
                        }
                    }
                    is RoomEvent.TrackPublished -> {
                        // Birisi yeni bir track paylaştığında otomatik abone ol
                        val pub = event.publication as? io.livekit.android.room.track.RemoteTrackPublication
                        pub?.setSubscribed(true)
                    }
                    is RoomEvent.ParticipantConnected -> {
                        val participant = event.participant
                        Logger.e("PARTICIPANT JOINED: ${participant.identity?.value}. Remote Count: ${newRoom.remoteParticipants.size}")
                        showStatus("Katılımcı girdi: ${participant.identity?.value}")
                    }
                    is RoomEvent.TrackE2EEStateEvent -> {
                        Logger.d("${event.participant.identity?.value} E2EE durumu: ${event.state}")
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        val participant = event.participant
                        val identity = participant.identity?.value ?: ""
                        runOnUiThread {
                            Logger.e("Track Unsubscribed: $identity")
                            if (identity.isNotEmpty()) {
                                videoAdapter.removeTrack(identity)
                            }
                        }
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val participant = event.participant
                        val identity = participant.identity?.value ?: ""
                        Logger.e("EVENT: ParticipantDisconnected -> $identity. Kalan Remote: ${newRoom.remoteParticipants.size}")
                        
                        runOnUiThread {
                            if (identity.isNotEmpty()) {
                                videoAdapter.removeTrack(identity)
                            }

                            // Sadece hiç kimse kalmadıysa odayı kapat
                            if (newRoom.remoteParticipants.isEmpty()) {
                                Logger.e("Odada kimse kalmadı, ayrılıyoruz.")
                                leaveRoom(true)
                            } else {
                                showStatus("$identity görüşmeden ayrıldı.")
                            }
                        }
                    }
                    is RoomEvent.Disconnected -> {
                        Logger.e("EVENT: Room Disconnected (Oda bağlantısı tamamen koptu)")
                        runOnUiThread {
                            leaveRoom(true)
                            showStatus("Görüşme sunucu tarafından sonlandırıldı.")
                        }
                    }
                    is RoomEvent.DataReceived -> {
                        val message = String(event.data)
                        val participantName = event.participant?.identity?.value ?: "Biri"
                        Logger.d("DataReceived: $message from $participantName")

                        if (message == "REJECTED") {
                            withContext(Dispatchers.Main) {
                                if (newRoom.remoteParticipants.isEmpty()) {
                                    callTimeoutJob?.cancel()
                                    leaveRoom(true)
                                } else {
                                    showStatus("$participantName aramayı reddetti.")
                                }
                            }
                        } else if (message.startsWith("LEFT_CALL:")) {
                            val whoLeft = message.substringAfter("LEFT_CALL:")
                            Logger.e("Kullanıcı ayrılma mesajı yolladı: $whoLeft. Kalan: ${newRoom.remoteParticipants.size}")
                            
                            withContext(Dispatchers.Main) {
                                // Eğer mesajı atan kişi remoteParticipants listesindeyse ve 
                                // listede başka kimse yoksa (veya sadece o varsa) hemen çıkalım.
                                // Bazı durumlarda ParticipantDisconnected mesajdan sonra gelir.
                                val remoteSize = newRoom.remoteParticipants.size
                                val senderSid = event.participant?.sid
                                
                                val shouldLeave = if (remoteSize == 0) true
                                else if (remoteSize == 1 && senderSid != null) {
                                    // Eğer odadaki tek kişi mesajı atan kişiyse
                                    newRoom.remoteParticipants.values.any { it.sid == senderSid }
                                } else false

                                if (shouldLeave) {
                                    Logger.e("Son katılımcı da ayrıldı (mesaj yoluyla), çıkılıyor...")
                                    leaveRoom(true)
                                } else {
                                    showStatus("$whoLeft görüşmeden ayrıldı.")
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // Zaten odada olan tracklar varsa (Garantili ekleme)
        lifecycleScope.launch {
            // İlk 15 saniye boyunca periyodik kontrol
            repeat(8) { attempt ->
                delay(if (attempt == 0) 500.milliseconds else 2000.milliseconds)
                Logger.e("Checking for existing participants (Attempt ${attempt + 1})... Count: ${newRoom.remoteParticipants.size}")

                newRoom.remoteParticipants.values.forEach { participant ->
                    Logger.d("Checking Participant: ${participant.identity?.value}")

                    participant.trackPublications.values.forEach { pub ->
                        if (pub is io.livekit.android.room.track.RemoteTrackPublication) {
                            if (!pub.subscribed) {
                                Logger.d("Yeniden girişte abone olunuyor: ${participant.identity?.value} - ${pub.sid}")
                                pub.setSubscribed(true)
                            }

                            val track = pub.track as? VideoTrack
                            if (track != null) {
                                runOnUiThread {
                                    val identity = participant.identity?.value ?: "Bilinmeyen"
                                    if (videoAdapter.addTrack(identity, track)) {
                                        if (pub.muted) {
                                            videoAdapter.setCameraEnabled(identity, false)
                                        }
                                        Logger.e("Existing Track added for: $identity")
                                        remoteVideosRecyclerView.post {
                                            remoteVideosRecyclerView.requestLayout()
                                            videoAdapter.notifyDataSetChanged()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        runOnUiThread {
            uiContainer.visibility = View.GONE
            findViewById<View>(R.id.bottom_navigation).visibility = View.GONE
            callControls.visibility = View.VISIBLE
            leaveButton.isEnabled = true
            muteButton.isEnabled = true

            // Yerel videoyu kesin olarak görünür yapalım
            if (useVideo) {
                val localParticipant = newRoom.localParticipant

                lifecycleScope.launch(Dispatchers.Main) {
                    var retryCount = 0
                    var localTrack: LocalVideoTrack? = null

                    while (retryCount < 20 && localTrack == null) {
                        localTrack = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                        if (localTrack == null) {
                            delay(200.milliseconds)
                            retryCount++
                        }
                    }

                    if (localTrack != null) {
                        // Local videoyu RecyclerView'a ekle
                        videoAdapter.addTrack(localIdentity, localTrack)

                        // Kamera başlangıçta açıksa siyah overlay'i kapat
                        videoAdapter.setCameraEnabled(localIdentity, callViewModel.isCameraOn.value)

                        remoteVideosRecyclerView.post {
                            remoteVideosRecyclerView.requestLayout()
                            videoAdapter.notifyDataSetChanged()
                        }
                    } else {
                        Logger.e("Local video track bulunamadı.")
                    }
                }
            }
        }
    }

    private fun leaveRoom(forced: Boolean = false) {
        callTimeoutJob?.cancel()
        controlsHideJob?.cancel()
        AudioManagerCompat.setSpeakerphoneOn(this, false)

        // UI'ı anında temizleyelim
        runOnUiThread {
            callControls.animate().cancel()
            callControls.visibility = View.GONE
            callControls.alpha = 1f
            remoteVideosRecyclerView.visibility = View.GONE
            uiContainer.visibility = View.VISIBLE
            findViewById<View>(R.id.bottom_navigation).visibility = View.VISIBLE
            switchCameraButton.isEnabled = false
            
            // Tıklama dinleyicisini kaldır ki boş alana basınca geri gelmesin
            findViewById<View>(R.id.fragment_container).setOnClickListener(null)
        }

        lifecycleScope.launch {
            if (!forced) {
                try {
                    val identity = sessionPreferences.getCurrentIdentity() ?: "Biri"
                    CallManager.publishData("LEFT_CALL:$identity")
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                CallManager.disconnect(this@MainActivity)
                videoAdapter.clear()
                
                // Anlık durum güncellemesi: Odadan çıktığımızı sunucuya hemen bildir
                sessionPreferences.getCurrentIdentity()?.let { id ->
                    lifecycleScope.launch { userRepository.sendHeartbeat(id) }
                }

                if (!forced) showStatus("Görüşmeden ayrıldın")
                else showStatus("Görüşme sonlandırıldı")
            }
        }
    }

    private fun toggleMute() {
        callViewModel.toggleMic()
    }

    private fun toggleSpeaker() {
        callViewModel.toggleSpeaker(this)
    }

    private fun toggleCamera() {
        callViewModel.toggleCamera()
    }

    private fun switchCamera() {
        val room = CallManager.room ?: return
        // Çok net: Sadece yerel katılımcının, sadece kamerasını hedefliyoruz
        val localParticipant = room.localParticipant
        val videoTrack = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack

        if (videoTrack != null) {
            lifecycleScope.launch {
                try {
                    videoTrack.switchCamera()
                    withContext(Dispatchers.Main) {
                        showStatus("Kameran Değiştirildi")
                    }
                } catch (e: Exception) {
                    Logger.e("Switch Error: ${e.message}")
                }
            }
        } else {
            Toast.makeText(this, "Kendi kameran aktif değil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachLocalVideoTrack() {
        lifecycleScope.launch(Dispatchers.Main) {
            val localParticipant = CallManager.room?.localParticipant ?: return@launch
            var retryCount = 0
            var localTrack: LocalVideoTrack? = null

            while (retryCount < 20 && localTrack == null) {
                localTrack = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                if (localTrack == null) {
                    delay(200.milliseconds)
                    retryCount++
                }
            }

            if (localTrack != null) {
                val identity = sessionPreferences.getCurrentIdentity() ?: "Ben"
                val localIdentity = "$identity (Sen)"

                // Eğer local track zaten adapter'da varsa tekrar eklemeyecek.
                videoAdapter.addTrack(localIdentity, localTrack)

                // Kamera açık olduğu için siyah ekranı kaldır
                videoAdapter.setCameraEnabled(localIdentity, true)

                remoteVideosRecyclerView.post {
                    remoteVideosRecyclerView.requestLayout()
                    videoAdapter.notifyDataSetChanged()
                }
            } else {
                Logger.e("Kamera açıldı ama local track alınamadı.")
            }
        }
    }

    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var heartbeatFailCount = 0

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatFailCount = 0
        heartbeatJob = lifecycleScope.launch {
            while (true) {
                val identity = sessionPreferences.getCurrentIdentity()
                if (identity != null) {
                    val result = userRepository.sendHeartbeat(identity)
                    withContext(Dispatchers.Main) {
                        if (result.isFailure) {
                            val errorMsg = result.exceptionOrNull()?.message ?: ""
                            if (errorMsg.contains("401") || errorMsg.contains("404")) {
                                android.util.Log.d("MainActivity", "Session lost (401/404), triggering silent sign in...")
                                silentSignIn(identity)
                            } else {
                                heartbeatFailCount++
                                if (heartbeatFailCount >= 3) {
                                    android.util.Log.e("MainActivity", "Heartbeat failed $heartbeatFailCount times: $errorMsg")
                                    updateConnectionStatusBadge(false)
                                }
                            }
                        } else {
                            if (isAppOffline) {
                                android.util.Log.d("MainActivity", "Heartbeat recovered! Refreshing contacts...")
                                refreshContacts() // Server geri geldiğinde hemen listeyi tazele
                            }
                            heartbeatFailCount = 0
                            updateConnectionStatusBadge(true)
                        }
                    }
                }
                delay(2000.milliseconds)
            }
        }
    }

    private fun triggerAutoSync() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result else "NO_TOKEN"
            lifecycleScope.launch {
                userRepository.syncUnsyncedUsers(fcmToken)
            }
        }
    }

    private fun silentSignIn(identity: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result else "NO_TOKEN"
            lifecycleScope.launch {
                userRepository.restoreCurrentUserOnServer(identity, fcmToken)
            }
        }
    }

    /**
     * Sunucu resetlendiğinde (401 hatası alındığında) aktif kullanıcıyı
     * sessizce sunucuya geri yükler.
     */
    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var refreshFailCount = 0

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        refreshFailCount = 0
        autoRefreshJob = lifecycleScope.launch {
            while (true) {
                if (!CallManager.isBusy() && sessionPreferences.isLoggedIn()) {
                    withContext(Dispatchers.Main) {
                        refreshContacts()
                    }
                }
                delay(5000.milliseconds) 
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Beklemeden ilk sinyalleri gönder
        sessionPreferences.getCurrentIdentity()?.let {
            lifecycleScope.launch {
                userRepository.sendHeartbeat(it)
                refreshContacts()
            }
        }

        startHeartbeat()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        heartbeatJob?.cancel()
        autoRefreshJob?.cancel()

        // Sadece görüşmede değilsek ve gerçekten çıkıyorsak offline gönder
        if (!CallManager.isBusy()) {
            sessionPreferences.getCurrentIdentity()?.let { identity ->
                lifecycleScope.launch { userRepository.sendOffline(identity) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.disconnect(this)
    }

    // --- INNER CLASSES ---

    private inner class CallLogAdapter : androidx.recyclerview.widget.ListAdapter<CallLogEntity, CallLogAdapter.LogViewHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<CallLogEntity>() {
            override fun areItemsTheSame(oldItem: CallLogEntity, newItem: CallLogEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: CallLogEntity, newItem: CallLogEntity) = oldItem == newItem
        }
    ) {
        inner class LogViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.logTypeIcon)
            val name: TextView = view.findViewById(R.id.logTargetName)
            val time: TextView = view.findViewById(R.id.logTime)
            val typeText: TextView = view.findViewById(R.id.logTypeText)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LogViewHolder {
            val view = layoutInflater.inflate(R.layout.item_call_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val log = getItem(position)
            holder.name.text = log.target
            
            val sdf = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
            holder.time.text = sdf.format(java.util.Date(log.timestamp))

            when (log.type) {
                "INCOMING" -> {
                    holder.icon.setImageResource(android.R.drawable.sym_call_incoming)
                    holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.success_green))
                    holder.typeText.text = "Gelen"
                }
                "OUTGOING" -> {
                    holder.icon.setImageResource(android.R.drawable.sym_call_outgoing)
                    holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                    holder.typeText.text = "Giden"
                }
                "MISSED" -> {
                    holder.icon.setImageResource(android.R.drawable.sym_call_missed)
                    holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.danger_red))
                    holder.typeText.text = "Kaçan"
                }
                "REJECTED" -> {
                    holder.icon.setImageResource(android.R.drawable.sym_call_missed)
                    holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.text_gray))
                    holder.typeText.text = "Reddedildi"
                }
            }
            holder.typeText.setTextColor(holder.icon.imageTintList!!.defaultColor)
        }
    }
}

//dosya düzeni, local hafızayı kullanmak.. serveri yeniden başlatıp uygulamaya girdiğimde log out yapması lazım yapmıyor.