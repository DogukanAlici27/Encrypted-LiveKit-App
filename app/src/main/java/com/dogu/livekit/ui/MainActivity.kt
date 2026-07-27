package com.dogu.livekit.ui
import com.dogu.livekit.encryption.EncryptionManager
import com.dogu.livekit.encryption.KeyManager
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.dogu.livekit.network.UserRepository
import com.dogu.livekit.pref.SessionPreferences
import com.dogu.livekit.util.ImageUtils
import com.dogu.livekit.util.KeyboardUtils
import com.dogu.livekit.util.PermissionUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.firebase.messaging.FirebaseMessaging
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.livekit.android.LiveKit


class MainActivity : AppCompatActivity() {

    private lateinit var identityEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var rememberMeCheckBox: CheckBox
    private lateinit var leaveButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var speakerButton: MaterialButton
    private lateinit var switchCameraButton: MaterialButton
    private lateinit var addParticipantButton: MaterialButton
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

    private lateinit var settingsMenuPanel: View
    private lateinit var accountSettingsPanel: View
    private lateinit var themeSettingsPanel: View
    private lateinit var accountUsernameTextView: TextView
    private lateinit var themeDarkCheck: ImageView
    private lateinit var themeLightCheck: ImageView

    private var isRegisteredNow = false
    private val selectedParticipants = mutableSetOf<String>()
    private lateinit var groupCallFab: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

    private lateinit var localVideoRenderer: SurfaceViewRenderer
    private lateinit var remoteVideosRecyclerView: androidx.recyclerview.widget.RecyclerView
    private val videoAdapter = VideoAdapter()

    private lateinit var sessionPreferences: SessionPreferences

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
        // Kaydedilmiş tema tercihini setContentView'dan ÖNCE uygula, yoksa yanlış tema ile
        // çizilip sonra flaş yaparak değişir.
        val savedThemeIsDark = SessionPreferences(this).isDarkTheme()
        AppCompatDelegate.setDefaultNightMode(
            if (savedThemeIsDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

        sessionPreferences = SessionPreferences(this)

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
        loadSession()
        checkAndRequestPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Her ihtimale karşı tüm arama bildirimlerini temizle
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(com.dogu.livekit.MyFirebaseMessagingService.CALL_NOTIFICATION_ID)

        if (intent?.getBooleanExtra("start_call", false) == true) {
            val url = intent.getStringExtra("url")
            val token = intent.getStringExtra("token")
            // YENİ: IncomingCallActivity zaten şifreyi çözüp buraya düz metin olarak
            // yolluyor (bu, sadece cihaz içi bir Intent — hiç ağdan geçmiyor).
            val roomKey = intent.getStringExtra("room_key")
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
        localVideoRenderer = findViewById(R.id.localVideoRenderer)
        localVideoRenderer.setZOrderMediaOverlay(true) // Diğer SurfaceView'ların üstünde kalması için
        remoteVideosRecyclerView = findViewById(R.id.remoteVideosRecyclerView)

        remoteVideosRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, 2).apply {
                spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val count = videoAdapter.itemCount
                        return when {
                            count <= 3 -> 2 // 1, 2 veya 3 kişi varken herkes tam genişlik (alt alta)
                            else -> 1 // 4+ kişi varken yan yana (grid)
                        }
                    }
                }
            }
            adapter = videoAdapter
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        findViewById<Button>(R.id.registerButton).setOnClickListener { authOnServer("register") }
        findViewById<Button>(R.id.loginButton).setOnClickListener { authOnServer("login") }
        leaveButton.setOnClickListener { leaveRoom(false) }
        findViewById<View>(R.id.refreshCallButton).setOnClickListener { manualRefreshTracks() }
        muteButton.setOnClickListener { toggleMute() }
        speakerButton.setOnClickListener { toggleSpeaker() }
        switchCameraButton.setOnClickListener { switchCamera() }
        addParticipantButton.setOnClickListener { showAddParticipantDialog() }

        val photoPickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleSelectedPhoto(it) }
        }

        val cameraLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()) { bitmap ->
            bitmap?.let { handlePhotoBitmap(it) }
        }

        findViewById<View>(R.id.editProfilePhotoFab).setOnClickListener {
            showImageSourceDialog(photoPickerLauncher, cameraLauncher)
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
            if (item.itemId != R.id.nav_home && !sessionPreferences.isLoggedIn()) {
                Toast.makeText(this, "Bu özelliği kullanmak için önce giriş yapmalısın", Toast.LENGTH_SHORT).show()
                return@setOnItemSelectedListener false
            }

            when (item.itemId) {
                R.id.nav_home -> {
                    if (sessionPreferences.isLoggedIn()) {
                        // Giriş yapmışsa home panelini gösterme, rehbere at
                        navigateToContacts()
                        false
                    } else {
                        homePanel.visibility = View.VISIBLE
                        contactsPanel.visibility = View.GONE
                        profilePanel.visibility = View.GONE
                        true
                    }
                }
                R.id.nav_contacts -> {
                    homePanel.visibility = View.GONE
                    contactsPanel.visibility = View.VISIBLE
                    profilePanel.visibility = View.GONE
                    refreshContacts()
                    true
                }
                R.id.nav_profile -> {
                    homePanel.visibility = View.GONE
                    contactsPanel.visibility = View.GONE
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
            val result = UserRepository.fetchUsers()
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
        sessionPreferences.logout()
        if (!rememberMeCheckBox.isChecked) {
            identityEditText.setText("")
            passwordEditText.setText("")
        }
        currentUserTextView.text = "Giriş Yapılmadı"
        homePanel.visibility = View.VISIBLE
        contactsPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.selectedItemId = R.id.nav_home
        rememberMeCheckBox.isChecked = true
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

        lifecycleScope.launch {
            val result = UserRepository.fetchUsers()
            if (result.isSuccess) {
                cachedContactsArray = result.getOrNull() ?: org.json.JSONArray()
                renderContactsList(contactSearchEditText.text?.toString().orEmpty())
            } else {
                Logger.e("Rehber çekilemedi: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun renderContactsList(query: String) {
        dynamicContactsContainer.removeAllViews()
        val usersArray = cachedContactsArray ?: return
        val currentIdentity = sessionPreferences.getCurrentIdentity()
        val totalCount = if (usersArray.length() > 0) usersArray.length() - 1 else 0
        contactsCountBadge.text = "$totalCount Kullanıcı"

        val trimmedQuery = query.trim().lowercase()
        var addedCount = 0
        for (i in 0 until usersArray.length()) {
            val user = usersArray.getJSONObject(i)
            val identity = user.getString("identity")
            val isOnline = user.optBoolean("isOnline", false)
            val photo = user.optString("profilePhoto", "")
            val rawRoom = user.optString("currentRoom", "")
            val currentRoom = if (rawRoom == "null" || rawRoom.isEmpty()) null else rawRoom

            if (identity.trim() == currentIdentity?.trim()) continue
            if (trimmedQuery.isNotEmpty() && !identity.lowercase().contains(trimmedQuery)) continue

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
                setDefaultAvatar(avatarImg, identity)
            }
        } else {
            setDefaultAvatar(avatarImg, identity)
        }

        val isInCall = !currentRoom.isNullOrEmpty()

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

        callBtn.text = if (isInCall) "MEŞGUL" else "ARA"
        callBtn.setIconResource(android.R.drawable.ic_menu_call)
        callBtn.backgroundTintList = ContextCompat.getColorStateList(this,
            if (isInCall) R.color.text_gray else R.color.accent_blue)
        // YENİ: Artık başkasının görüşmesine rehberden zorla dalınamıyor.
        // Meşgul kişiler için buton tamamen devre dışı — sadece "MEŞGUL" bilgisini gösteriyor.
        // Onun yerine, aktif bir görüşme içindeki kişiler "Kullanıcı Ekle" ile
        // müsait (görüşmede olmayan) birini davet edebiliyor (bkz. showAddParticipantDialog).
        callBtn.isEnabled = !isInCall
        callBtn.alpha = if (isInCall) 0.5f else 1f

        callBtn.setOnClickListener {
            startCall(identity)
        }

        dynamicContactsContainer.addView(view)
    }

    // YENİ: Aktif görüşme sırasında "Kullanıcı Ekle" butonuna basılınca açılan akış.
    // Sadece ŞU AN görüşmede OLMAYAN kişileri listeler — böylece birini zaten
    // meşgulken tekrar davet edip karışıklık çıkarmıyoruz.
    private fun showAddParticipantDialog() {
        val currentRoom = CallManager.room
        if (currentRoom == null) {
            Toast.makeText(this, "Aktif bir görüşme yok.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = UserRepository.fetchUsers()
            if (result.isFailure) {
                Toast.makeText(this@MainActivity, "Kullanıcı listesi alınamadı.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val myIdentity = sessionPreferences.getCurrentIdentity()
            val usersArray = result.getOrNull() ?: return@launch

            // Zaten odada olanları da hariç tutalım (kendimiz ve o an görüşmedeki katılımcılar)
            val alreadyInThisCall = currentRoom.remoteParticipants.values
                .mapNotNull { it.identity?.value }
                .toSet()

            val availableNames = mutableListOf<String>()
            for (i in 0 until usersArray.length()) {
                val user = usersArray.getJSONObject(i)
                val uIdentity = user.getString("identity")
                val uCurrentRoom = user.optString("currentRoom", "")
                val isBusy = uCurrentRoom.isNotEmpty() && uCurrentRoom != "null"

                if (uIdentity == myIdentity) continue
                if (uIdentity in alreadyInThisCall) continue
                if (isBusy) continue // müsait değilse listeye hiç girmesin

                availableNames.add(uIdentity)
            }

            if (availableNames.isEmpty()) {
                Toast.makeText(this@MainActivity, "Davet edilebilecek müsait kimse yok.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Kullanıcı Ekle")
                .setItems(availableNames.toTypedArray()) { _, which ->
                    inviteParticipantToCurrentCall(availableNames[which])
                }
                .setNegativeButton("İptal", null)
                .show()
        }
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

            val result = UserRepository.fetchToken(myIdentity, targetIdentity, currentRoomName, encryptedKeysJson)
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

    private fun manualRefreshTracks() {
        val room = CallManager.room ?: return
        showStatus("Katılımcılar taranıyor...")
        room.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { pub ->
                val track = pub.track as? VideoTrack
                if (track != null) {
                    runOnUiThread {
                        videoAdapter.addTrack(participant.identity?.value ?: "Bilinmeyen", track)
                    }
                }
            }
        }
        remoteVideosRecyclerView.requestLayout()
    }

    private fun checkAndRequestPermissions() {
        if (!PermissionUtils.hasAllPermissions(this)) {
            requestPermissionLauncher.launch(PermissionUtils.getNeededPermissions())
        }
    }


    private fun setDefaultAvatar(imageView: ImageView, identity: String) {
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
                        delay(duration)
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
        } catch (e: Exception) {
            Toast.makeText(this, "Fotoğraf işlenemedi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePhotoBitmap(bitmap: android.graphics.Bitmap) {
        val resized = ImageUtils.resizeBitmap(bitmap, 200)
        val base64 = ImageUtils.bitmapToBase64(resized)

        val identity = sessionPreferences.getCurrentIdentity()
        if (identity != null) {
            lifecycleScope.launch {
                val res = UserRepository.updateUserPhoto(identity, base64)
                if (res.isSuccess) {
                    currentProfilePhotoImg.setImageBitmap(resized)
                    currentProfilePhotoImg.setPadding(0, 0, 0, 0)
                    currentProfilePhotoImg.imageTintList = null
                    Toast.makeText(this@MainActivity, "Profil fotoğrafı güncellendi", Toast.LENGTH_SHORT).show()

                    if (homePanel.visibility == View.VISIBLE) {
                        homePanel.visibility = View.GONE
                        navigateToContacts()
                    }
                } else {
                    // 👈 YENİ: artık hatayı görebileceğiz
                    val error = res.exceptionOrNull()?.message ?: "bilinmeyen hata"
                    Logger.e("Fotoğraf güncellenemedi: $error")
                    Toast.makeText(this@MainActivity, "Fotoğraf yüklenemedi: $error", Toast.LENGTH_LONG).show()
                }
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

        showStatus("İşlem yapılıyor...", 10000)

        // YENİ: Cihazın RSA anahtar çifti yoksa burada üretiliyor (Keystore'da,
        // sadece ilk seferde gerçekleşir). Genel anahtarı sunucuya gönderiyoruz ki
        // başkaları bize E2EE oda anahtarı şifreleyip yollayabilsin.
        val publicKey = KeyManager.getOrCreatePublicKeyBase64()

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result else "NO_TOKEN"

            lifecycleScope.launch {
                val result = UserRepository.auth(mode, identity, password, fcmToken, publicKey)
                if (result.isSuccess) {
                    sessionPreferences.setLoggedIn(true, identity)
                    sessionPreferences.saveRememberMe(identity, password, rememberMeCheckBox.isChecked)

                    currentUserTextView.text = identity
                    homePanel.visibility = View.GONE
                    val successMsg = if (mode == "register") "Kayıt Başarılı!" else "Giriş Başarılı!"
                    showStatus(successMsg)
                    loadOwnProfilePhoto() // önceden yüklenmiş profil fotoğrafı varsa göster

                    if (mode == "register") {
                        // Yeni kayıt olan kullanıcıya fotoğraf seçtir
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Profil Fotoğrafı")
                            .setMessage("Harika! Şimdi bir profil fotoğrafı seçmek ister misin?")
                            .setPositiveButton("Seç") { _, _ -> showImageSourceDialog(photoPickerLauncherInternal, cameraLauncherInternal) }
                            .setNegativeButton("Sonra", null)
                            .show()
                    }

                    navigateToContacts()
                } else {
                    val exception = result.exceptionOrNull()
                    val errorMsg = if (exception?.message?.contains("401") == true) "Hatalı şifre!"
                    else if (exception?.message?.contains("409") == true) "Bu isim zaten alınmış!"
                    else "İşlem başarısız: ${exception?.message ?: "Bilinmeyen hata"}"
                    showStatus(errorMsg)
                }
            }
        }
    }

    private var callTimeoutJob: kotlinx.coroutines.Job? = null

    private fun startCall(target: String) {
        val identity = sessionPreferences.getCurrentIdentity() ?: "Misafir"
        if (identity == "Misafir") {
            Toast.makeText(this, "Önce giriş yapmalısın", Toast.LENGTH_SHORT).show()
            return
        }

        // Kendi aramamızı yaparken bildirim gelirse (sunucu hatası vb) temizle
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            kotlinx.coroutines.delay(20000)
            val room = CallManager.room
            // Grup görüşmesinde sadece oda boşsa (kimse gelmediyse) aramayı sonlandır
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
                // YENİ: Bu görüşme için rastgele, bir kereye mahsus bir oda anahtarı üretiyoruz.
                val roomKey = EncryptionManager.generateRoomKey()
                // Her hedefin GENEL anahtarıyla bu oda anahtarını ayrı ayrı şifreliyoruz.
                val encryptedKeysJson = buildEncryptedKeysForTargets(target, roomKey)

                val result = UserRepository.fetchToken(identity, target, null, encryptedKeysJson)
                if (result.isSuccess) {
                    val json = result.getOrNull()!!

                    // YENİ: Sunucu, aradığımız kişilerden hangilerinin zaten başka bir
                    // görüşmede olduğunu "busyTargets" dizisiyle bildiriyor. Varsa bilgilendirelim.
                    val busyTargetsArray = json.optJSONArray("busyTargets")
                    if (busyTargetsArray != null && busyTargetsArray.length() > 0) {
                        val busyNames = (0 until busyTargetsArray.length()).map { busyTargetsArray.getString(it) }
                        Toast.makeText(
                            this@MainActivity,
                            "${busyNames.joinToString(", ")} şu an aktif bir görüşmede",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    connectToRoom(json.getString("url"), json.getString("token"), true, roomKey)
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
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            val targets = targetCsv.split(",").map { it.trim() }.toSet()
            val obj = org.json.JSONObject()

            val usersResult = UserRepository.fetchUsers()
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
            Logger.d("E2EE_DEBUG: sonuç JSON = ${obj.toString()}")
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
            delay(5000) // 5 saniye sonra gizle
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

        val newRoom = CallManager.connect(this, url, token, useVideo, localVideoRenderer, null, roomOptions)

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
                                if (videoAdapter.addTrack(participant.identity?.value ?: "Bilinmeyen", track)) {
                                    remoteVideosRecyclerView.post {
                                        remoteVideosRecyclerView.requestLayout()
                                        videoAdapter.notifyDataSetChanged()
                                    }
                                }
                                showStatus("Yeni katılımcı bağlandı: ${participant.identity?.value}")
                            }
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
                        runOnUiThread {
                            Logger.e("Participant Disconnected: $identity")
                            if (identity.isNotEmpty()) {
                                videoAdapter.removeTrack(identity)
                            }

                            // Sadece hiç kimse kalmadıysa odayı kapat
                            if (newRoom.remoteParticipants.isEmpty()) {
                                leaveRoom(true)
                            } else {
                                showStatus("$identity görüşmeden ayrıldı.")
                            }
                        }
                    }
                    is RoomEvent.DataReceived -> {
                        val message = String(event.data)
                        val participantName = event.participant?.identity?.value ?: "Biri"

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
                            withContext(Dispatchers.Main) {
                                // Video track zaten ParticipantDisconnected ile temizlenecek,
                                // burada sadece odayı kapatıp kapatmayacağımıza karar veriyoruz.
                                if (newRoom.remoteParticipants.isEmpty()) {
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
                delay(if (attempt == 0) 500 else 2000)
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
                                    if (videoAdapter.addTrack(participant.identity?.value ?: "Bilinmeyen", track)) {
                                        Logger.e("Existing Track added for: ${participant.identity?.value}")
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
                            delay(200)
                            retryCount++
                        }
                    }

                    if (localTrack != null) {
                        val identity = sessionPreferences.getCurrentIdentity() ?: "Ben"
                        videoAdapter.addTrack("$identity (Sen)", localTrack)
                        remoteVideosRecyclerView.post {
                            remoteVideosRecyclerView.requestLayout()
                            videoAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    private fun leaveRoom(forced: Boolean = false) {
        callTimeoutJob?.cancel()
        AudioManagerCompat.setSpeakerphoneOn(this, false)

        lifecycleScope.launch {
            if (!forced) {
                try {
                    val identity = sessionPreferences.getCurrentIdentity() ?: "Biri"
                    CallManager.publishData("LEFT_CALL:$identity")
                } catch (e: Exception) {}
            }

            withContext(Dispatchers.Main) {
                CallManager.disconnect(this@MainActivity)
                videoAdapter.clear()
                if (!forced) showStatus("Görüşmeden ayrıldın")
                else showStatus("Görüşme sonlandırıldı")

                uiContainer.visibility = View.VISIBLE
                findViewById<View>(R.id.bottom_navigation).visibility = View.VISIBLE
                callControls.visibility = View.GONE
                remoteVideosRecyclerView.visibility = View.GONE
                switchCameraButton.isEnabled = false
                controlsHideJob?.cancel()
                callControls.alpha = 1f
            }
        }
    }

    private fun toggleMute() {
        isMicMuted = !isMicMuted
        lifecycleScope.launch { CallManager.room?.localParticipant?.setMicrophoneEnabled(!isMicMuted) }

        muteButton.apply {
            if (isMicMuted) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.danger_red)
                setIconResource(R.drawable.ic_mic_off)
            } else {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue_alpha)
                setIconResource(R.drawable.ic_mic_on)
            }
        }
    }

    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        AudioManagerCompat.setSpeakerphoneOn(this, isSpeakerOn)

        speakerButton.apply {
            if (isSpeakerOn) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent_blue_alpha)
                setIconResource(R.drawable.ic_speaker_on)
            } else {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.bg_input)
                setIconResource(R.drawable.ic_speaker_off)
            }
        }
        Toast.makeText(this, if (isSpeakerOn) "Hoparlör Açık" else "Hoparlör Kapalı", Toast.LENGTH_SHORT).show()
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





    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var heartbeatFailCount = 0

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatFailCount = 0
        heartbeatJob = lifecycleScope.launch {
            while (true) {
                val identity = sessionPreferences.getCurrentIdentity()
                if (identity != null) {
                    val result = UserRepository.sendHeartbeat(identity)
                    if (result.isFailure) {
                        heartbeatFailCount++
                        Logger.d("Heartbeat hatası ($heartbeatFailCount/2): ${result.exceptionOrNull()?.message}")
                        if (heartbeatFailCount >= 2) {
                            withContext(Dispatchers.Main) {
                                performLogout("Sunucu bağlantısı koptu.")
                            }
                            return@launch
                        }
                    } else {
                        heartbeatFailCount = 0
                    }
                }
                delay(2000)
            }
        }
    }

    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var refreshFailCount = 0

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        refreshFailCount = 0
        autoRefreshJob = lifecycleScope.launch {
            while (true) {
                if (contactsPanel.visibility == View.VISIBLE && !CallManager.isBusy()) {
                    val result = UserRepository.fetchUsers()
                    if (result.isFailure) {
                        refreshFailCount++
                        Logger.d("Rehber yenileme hatası ($refreshFailCount/2): ${result.exceptionOrNull()?.message}")
                        if (refreshFailCount >= 2) {
                            withContext(Dispatchers.Main) {
                                performLogout("Sunucuya ulaşılamıyor.")
                            }
                            return@launch
                        }
                    } else {
                        refreshFailCount = 0
                        refreshContacts()
                    }
                }
                delay(3000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Beklemeden ilk sinyalleri gönder
        sessionPreferences.getCurrentIdentity()?.let {
            lifecycleScope.launch {
                UserRepository.sendHeartbeat(it)
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
            sessionPreferences.getCurrentIdentity()?.let {
                lifecycleScope.launch { UserRepository.sendOffline(it) }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        CallManager.disconnect(this)
    }
}