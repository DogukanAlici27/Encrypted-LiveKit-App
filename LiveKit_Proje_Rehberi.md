# LiveKit Projesi — Dosya Dosya Rehber

Bu doküman, `LiveKit` projesindeki her dosyanın ne işe yaradığını, içindeki önemli fonksiyonların ne yaptığını ve dosyaların birbiriyle nasıl konuştuğunu anlatır. Sıfırdan bilen biri okuduğunda projeyi anlayabilecek şekilde yazıldı.

---

## 1. Genel Mimari

Proje **iki ayrı program**dan oluşuyor:

1. **Android uygulaması** (`app/` klasörü, Kotlin) — kullanıcının telefonunda çalışan istemci.
2. **Token server** (`livekit-token-server/` klasörü, Node.js/Express) — kimlik doğrulama yapan ve LiveKit için giriş bileti (JWT token) üreten küçük bir backend.

Üçüncü bir bileşen daha var ama bu projenin parçası değil, dışarıdan kullanılan bir servis:

3. **LiveKit Cloud** — ses/video paketlerini katılımcılar arasında yönlendiren sunucu (SFU — Selective Forwarding Unit). Kendi kodun değil, LiveKit'in barındırdığı bir servis.

### Genel akış (adım adım)

```
[Kullanıcı A telefonu]                [Token Server]              [Kullanıcı B telefonu]
        |                                    |                              |
        | 1. login/register                 |                              |
        |----------------------------------->|                              |
        |                                    |                              |
        | 2. "B'yi ara" -> token iste        |                              |
        |----------------------------------->|                              |
        |                                    | 3. Firebase push gönder      |
        |                                    |----------------------------->|
        | 4. JWT + oda bilgisi döner         |                              |
        |<-----------------------------------|                              |
        |                                    |                              |  (B: IncomingCallActivity açılır,
        |                                    |                              |   "Kabul et"e basar)
        |                                    |          5. B de token ister |
        |                                    |<-----------------------------|
        |                                    | 6. JWT döner                 |
        |                                    |----------------------------->|
        |                                                                   |
        | 7. İkisi de LiveKit Cloud'a bağlanır, aynı odada buluşurlar       |
        |===================================================================|
        |            (ŞİFRELİ ses/görüntü akışı - E2EE ile)                |
        |===================================================================|
```

Token server sadece "kapıyı açan" taraf — bağlantı kurulduktan sonra devrede değil. Gerçek ses/video trafiği doğrudan telefon <-> LiveKit Cloud arasında akıyor, ve bu trafik `EncryptionManager` sayesinde uçtan uca şifreli.

---

## 2. Android Uygulaması (`app/src/main/java/com/dogu/livekit/`)

### 2.1 `ui/main/MainActivity.kt` (1085 satır) — Projenin kalbi

Bu, hem giriş ekranını, hem rehberi, hem de arama ekranını yöneten tek bir dev `Activity`. Küçük internship projelerinde sık görülen "God Activity" yaklaşımı — yani MVVM gibi katmanlara ayrılmamış, her şey tek dosyada.

**Önemli değişkenler:**
```kotlin
private val selectedParticipants = mutableSetOf<String>()   // grup araması için seçilen kişiler
private val videoAdapter = VideoAdapter()                    // ekrandaki video karolarını yöneten adapter
private lateinit var sessionPreferences: SessionPreferences   // giriş bilgisi hafızası
private var isMicMuted = false
private var isSpeakerOn = true
```

**Fonksiyon fonksiyon ne yapıyor:**

| Fonksiyon | Satır | Görevi |
|---|---|---|
| `onCreate` | 88 | Activity açılırken UI'yi bağlar, oturumu yükler, izinleri ister |
| `handleIntent` | 129 | Bildirimden gelen "aramayı başlat" komutunu yakalar |
| `bindUI` | 145 | Tüm `findViewById` çağrıları ve buton click listener'ları burada |
| `authOnServer` | 558 | Giriş/kayıt formunu token server'a POST eder |
| `refreshContacts` | 291 | `/users` endpoint'inden rehberi çeker, ekrana buton olarak basar |
| `startCall` | 607 | Bir kişiyi/grubu arar: token ister, sonra `connectToRoom`'u çağırır. 20 saniyelik "kimse açmazsa" timeout mantığı var |
| `connectToRoom` | 714 | **En kritik fonksiyon.** `RoomOptions` (E2EE dahil) hazırlar, `CallManager.connect()`'i çağırır, `RoomEvent`'leri dinler |
| `leaveRoom` | 909 | Görüşmeden çıkar, `CallManager.disconnect()`'i çağırır, UI'yi eski haline döndürür |
| `toggleMute` / `toggleSpeaker` / `switchCamera` | 938-969 | Görüşme sırasındaki kontrol butonları |
| `startGroupCall` | 442 | Seçili kişilerin hepsini virgülle birleştirip tek bir arama isteği yapar |

**`connectToRoom` fonksiyonunun içi (senin E2EE eklediğin yer):**
```kotlin
private suspend fun connectToRoom(url: String, token: String, useVideo: Boolean) {
    LiveKit.init(applicationContext)   // native E2EE kütüphanesini yükler

    val roomOptions = io.livekit.android.RoomOptions(
        adaptiveStream = false,
        dynacast = false,
        e2eeOptions = EncryptionManager.getE2EEOptions()   // şifreleme burada devreye giriyor
    )

    val newRoom = CallManager.connect(
        context = this, url = url, token = token, useVideo = useVideo,
        localRenderer = localVideoRenderer, remoteRenderer = null, roomOptions = roomOptions
    )

    // Room'daki olayları dinle: kim katıldı, kim ayrıldı, hangi track geldi, encryption durumu ne
    lifecycleScope.launch {
        newRoom.events.collect { event ->
            when (event) {
                is RoomEvent.TrackSubscribed -> { /* videoyu ekrana bas */ }
                is RoomEvent.ParticipantConnected -> { /* "X katıldı" bildirimi göster */ }
                is RoomEvent.TrackE2EEStateEvent -> { /* şifreleme durumunu logla */ }
                is RoomEvent.ParticipantDisconnected -> { /* kişi listeden çıkar */ }
                // ...
            }
        }
    }
}
```

### 2.2 `domain/call/CallManager.kt` (109 satır) — LiveKit bağlantı yöneticisi

`MainActivity`'nin LiveKit SDK'sı ile doğrudan uğraşmaması için araya konan bir katman (soyutlama). `object` (Kotlin'de singleton) olduğu için uygulama boyunca **tek bir `room` referansı** tutuluyor — bu sayede `CallManager.isBusy()` gibi kontroller her yerden yapılabiliyor.

```kotlin
object CallManager {
    var room: Room? = null   // aktif oda, uygulama genelinde tek referans

    suspend fun connect(context, url, token, useVideo, localRenderer, remoteRenderer, roomOptions): Room {
        // 1. Foreground service başlat (arama arka plana atılsa da devam etsin diye)
        ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))

        // 2. Önceki bağlantı varsa temiz kapat
        room?.disconnect()

        // 3. Yeni Room'u E2EE dahil roomOptions ile oluştur
        val newRoom = LiveKit.create(context, roomOptions ?: RoomOptions())
        room = newRoom

        // 4. Renderer'ları ana thread'de ilklendir (WebRTC kısıtlaması)
        withContext(Dispatchers.Main) {
            newRoom.initVideoRenderer(localRenderer)
        }

        // 5. Bağlan, mikrofon/kamerayı aç
        newRoom.connect(url, token)
        newRoom.localParticipant.setMicrophoneEnabled(true)
        if (useVideo) newRoom.localParticipant.setCameraEnabled(true)

        return newRoom
    }

    fun disconnect(context: Context?) { room?.disconnect(); room = null; /* servisi durdur */ }
    fun isConnected(): Boolean = room?.state == Room.State.CONNECTED
    fun isBusy(): Boolean = room != null && room?.state != Room.State.DISCONNECTED
}
```

`isBusy()` fonksiyonu özellikle önemli: `MyFirebaseMessagingService` gelen bir bildirim aldığında, eğer sen zaten başka bir görüşmedeysen bu fonksiyon `true` döner ve yeni bildirim gösterilmez.

### 2.3 `core/encryption/EncryptionManager.kt` — E2EE katmanı

```kotlin
object EncryptionManager {
    private const val SHARED_ENCRYPTION_KEY = "test-paylasilan-parola-123"

    fun getE2EEOptions(): E2EEOptions {
        return E2EEOptions().apply {
            keyProvider.setSharedKey(SHARED_ENCRYPTION_KEY)
        }
    }
}
```

Tek işi: tüm katılımcıların bildiği ortak bir parolayı `KeyProvider`'a vermek. LiveKit SDK bu parolayı native (C++) katmanda AES-GCM anahtarına çevirip her video/ses frame'ini şifreliyor — LiveKit Cloud sunucusu bu içeriği hiç göremiyor.

⚠️ **Önemli not:** Parola şu an hardcoded ve herkes aynı sabiti biliyor. Gerçek bir üründe bu parola, her görüşme için token server tarafından dinamik üretilip güvenli bir kanaldan (token ile birlikte) dağıtılmalı.

### 2.4 `service/CallService.kt` (68 satır) — Foreground Service

Android, arka plana atılan uygulamaların ses/kamera kullanımını kısıtlar/keser. Bu servis, `startForeground()` ile sistemi "bu bir aktif görüşme, öldürme" diye uyarır.

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Bildirim kanalı oluştur, "Görüşme devam ediyor..." bildirimini göster
    startForeground(2001, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE or FOREGROUND_SERVICE_TYPE_CAMERA)
    startHeartbeat()   // her 10 saniyede bir sunucuya "hâlâ buradayım" sinyali gönder
    return START_NOT_STICKY
}

private fun startHeartbeat() {
    heartbeatJob = serviceScope.launch {
        while (isActive) {
            UserRepository.sendHeartbeat(identity)
            delay(10000)
        }
    }
}
```

### 2.5 `service/MyFirebaseMessagingService.kt` — Gelen arama bildirimleri

Firebase Cloud Messaging'den bir push bildirimi geldiğinde tetiklenen servis.

```kotlin
override fun onMessageReceived(remoteMessage: RemoteMessage) {
    val caller = remoteMessage.data["caller"]
    val room = remoteMessage.data["room"]

    // 1. MEŞGULİYET KONTROLÜ: zaten görüşmedeysen bildirimi engelle
    if (CallManager.isBusy()) return

    // 2. KENDİ ARAMANI GÖRMEME: sunucu bazen yanlışlıkla sana da bildirim gönderebilir
    val isSelfCall = caller.equals(currentIdentity, ignoreCase = true)
    if (isSelfCall) return

    // 3. Her şey yolundaysa tam ekran gelen arama ekranını aç
    showCallNotification(caller, room)
}
```

`showCallNotification`, kilit ekranının üzerinde açılan bir tam ekran bildirim (`setFullScreenIntent`) oluşturup `IncomingCallActivity`'yi başlatıyor.

### 2.6 `ui/call/IncomingCallActivity.kt` (163 satır) — Gelen arama ekranı

```kotlin
private fun acceptCall(caller: String, room: String) {
    lifecycleScope.launch {
        val result = UserRepository.fetchToken(identity, caller, room)   // aynı odaya girmek için token iste
        // MainActivity'ye "start_call=true" ekstra bilgisiyle dön
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("url", json.getString("url"))
            putExtra("token", json.getString("token"))
            putExtra("start_call", true)
        })
    }
}

private fun declineCall(room: String) {
    lifecycleScope.launch {
        val result = UserRepository.fetchToken(identity, "REJECTER", room)
        // Kısa süreliğine odaya bağlanıp "REJECTED" verisi yayınla, sonra hemen ayrıl
        val rejectRoom = LiveKit.create(applicationContext)
        rejectRoom.connect(json.getString("url"), json.getString("token"))
        rejectRoom.localParticipant.publishData("REJECTED".toByteArray())
        rejectRoom.disconnect()
    }
}
```

İlginç detay: "Reddet" butonuna basınca uygulama **gerçekten odaya kısa süreliğine bağlanıp** bir veri paketi (`"REJECTED"`) yayınlıyor, sonra hemen ayrılıyor. Bu, arayan tarafın "karşı taraf reddetti" bilgisini anlık olarak alabilmesi için.

### 2.7 `ui/call/VideoAdapter.kt` (90 satır) — Video ızgarası

Görüşmedeki her katılımcının kamerasını bir `RecyclerView` grid'inde gösteren adapter.

```kotlin
class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    private val videoTracks = mutableListOf<Triple<String, String, VideoTrack>>()  // identity, trackId, track

    fun addTrack(identity: String, track: VideoTrack): Boolean {
        // Aynı track zaten listede mi diye kontrol et, yoksa ekle
        videoTracks.add(Triple(identity, track.sid, track))
        notifyItemInserted(videoTracks.size - 1)
    }

    override fun onBindViewHolder(holder, position) {
        // Her karo için SurfaceViewRenderer'ı ilklendir ve track'i ona bağla
        CallManager.room?.initVideoRenderer(holder.renderer)
        track.addRenderer(holder.renderer)
    }
}
```

`MainActivity`, kaç kişi görüşmedeyse ona göre `GridLayoutManager`'ın sütun sayısını ayarlıyor (3 kişiye kadar tam genişlik alt alta, 4+ kişide 2 sütunlu grid).

### 2.8 Altyapı / yardımcı dosyalar

| Dosya | Ne işe yarar |
|---|---|
| `data/remote/NetworkClient.kt` | Token server'ın URL'ini seçer. Cihazın emülatör mü gerçek telefon mu olduğunu `Build.FINGERPRINT` gibi bilgilere bakarak anlar; emülatörse `10.0.2.2:3005`, gerçek cihazsa bilgisayarının IP'si (`10.0.2.120:3005`) kullanılır. |
| `data/repository/UserRepository.kt` | Tüm HTTP isteklerini (`auth`, `fetchUsers`, `fetchToken`, `sendHeartbeat`, `sendOffline`) tek yerde toplayan repository katmanı. Her fonksiyon `Result<T>` döner (başarı/hata). |
| `data/local/prefs/SessionPreferences.kt` | `SharedPreferences` üzerinden "giriş yapılmış mı", "beni hatırla" bilgisini saklar. |
| `core/hardware/AudioManagerCompat.kt` | Hoparlör/kulaklık modu arasında geçiş yapar (`AudioManager.MODE_IN_COMMUNICATION`). |
| `core/logging/Logger.kt` | `Log.d/e/i`'yi sarmalayan, tek bir `TAG` kullanan basit logger. |
| `core/util/ImageUtils.kt` | Profil fotoğrafını `Bitmap` <-> `Base64 String` arasında çevirir (sunucuya JSON içinde göndermek için). |
| `core/util/KeyboardUtils.kt` | Klavyeyi programatik olarak gizler. |
| `core/util/PermissionUtils.kt` | Gereken izinlerin listesini (`RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`) döner ve hepsinin verilip verilmediğini kontrol eder. |
| `model/CallToken.kt` | Basit bir `data class`: `token` ve `url` alanlarını tutar. |

---

## 3. Token Server (`livekit-token-server/index.js`)

Node.js + Express ile yazılmış, hafızada (`users = {}` objesinde, veritabanı yok) çalışan basit bir backend.

```js
const API_KEY = process.env.LIVEKIT_API_KEY;      // .env dosyasından okunur
const API_SECRET = process.env.LIVEKIT_API_SECRET;
const users = {};   // { identity: { password, fcmToken, profilePhoto, lastSeen, currentRoom } }
```

**Endpoint'ler:**

| Endpoint | Metod | Görevi |
|---|---|---|
| `/register` | POST | Yeni kullanıcı kaydı (şifre düz metin olarak saklanıyor — test amaçlı, production'da hash'lenmeli) |
| `/login` | POST | Giriş yapar, FCM token'ı günceller |
| `/heartbeat` | POST | `CallService`'in attığı sinyali işler, kullanıcının hangi odada olduğunu günceller |
| `/offline` | POST | Kullanıcı çıkış yaparken odasını temizler |
| `/users` | GET | Rehber listesini döner (son 30 saniyede heartbeat attıysa "online" sayılıyor) |
| `/token` | GET | **En kritik endpoint.** LiveKit'in resmi `AccessToken` sınıfıyla JWT üretir, hem arayan hem aranan için Firebase push bildirimi tetikler |

**`/token` endpoint'inin çekirdeği:**
```js
app.get('/token', async (req, res) => {
  const { identity, target, room } = req.query;
  let roomName = room || `ROOM_${[identity, ...target.split(',')].sort().join('_')}`;

  // Firebase üzerinden hedefe (veya hedeflere, grup araması için) bildirim gönder
  target.split(',').forEach(t => {
    admin.messaging().send({
      data: { caller: identity, room: roomName, start_call: "true" },
      notification: { title: "Gelen Arama", body: `${identity} seni arıyor...` },
      token: users[t].fcmToken,
    });
  });

  // LiveKit JWT'sini üret
  const at = new AccessToken(API_KEY, API_SECRET, { identity, ttl: '60m' });
  at.addGrant({ room: roomName, roomJoin: true, canPublish: true, canSubscribe: true });
  res.json({ token: await at.toJwt(), url: process.env.LIVEKIT_URL, room: roomName });
});
```

Oda ismi, katılımcıların kimliklerini alfabetik sıraya sokup birleştirerek üretiliyor (`ROOM_alice_bob` gibi) — bu sayede aynı iki kişi kim ararsa arasın her zaman aynı oda ismine ulaşıyorlar.

⚠️ **Production'a geçmeden önce mentöre sorman gereken noktalar:**
- Şifreler düz metin saklanıyor (bcrypt/argon2 ile hashlenmeli).
- Kullanıcı verisi RAM'de (`users = {}`) — sunucu yeniden başlarsa her şey silinir. Gerçek bir veritabanı (Postgres/Redis) gerekir.
- `serviceAccountKey.json` ve `.env` dosyaları (Firebase/LiveKit gizli anahtarları) asla GitHub'a push edilmemeli — `.gitignore`'da olduklarından emin ol.

---

## 4. Dosyalar Arası İlişki Haritası

```
MainActivity.kt
   |-- authOnServer() ----------------> UserRepository.auth() -----> NetworkClient (token server)
   |-- refreshContacts() --------------> UserRepository.fetchUsers()
   |-- startCall() --------------------> UserRepository.fetchToken()
   |-- connectToRoom() ----------------> CallManager.connect()
   |                                        |-- CallService başlatır (foreground)
   |                                        |-- LiveKit.create(roomOptions)
   |                                        |     roomOptions.e2eeOptions <---- EncryptionManager.getE2EEOptions()
   |-- videoAdapter.addTrack() --------> VideoAdapter (RecyclerView)

MyFirebaseMessagingService.kt
   |-- onMessageReceived() ------------> CallManager.isBusy() kontrolü
   |-- showCallNotification() ---------> IncomingCallActivity açılır

IncomingCallActivity.kt
   |-- acceptCall() -------------------> UserRepository.fetchToken() -----> MainActivity'ye döner (start_call=true)
   |-- declineCall() -------------------> LiveKit.create() (geçici oda bağlantısı, "REJECTED" verisi yayınlar)

CallService.kt
   |-- startHeartbeat() ----------------> UserRepository.sendHeartbeat() (her 10 sn)
```

---

## 5. Öğrenme sırası önerisi (projeyi baştan anlatacaksan)

Birine bu projeyi anlatırken önerilen sıra:

1. **`model/CallToken.kt`** — en basit dosya, "token nedir" kavramını burada anlat.
2. **`data/remote/NetworkClient.kt` + `UserRepository.kt`** — HTTP katmanı, "istemci sunucuyla nasıl konuşur".
3. **`livekit-token-server/index.js`** — sunucu tarafı, JWT'nin nasıl üretildiği.
4. **`domain/call/CallManager.kt`** — LiveKit'e bağlanmanın çekirdeği.
5. **`core/encryption/EncryptionManager.kt`** — E2EE katmanı (bugün konuştuğumuz kısım).
6. **`ui/main/MainActivity.kt`** — hepsinin birleştiği yer, en son ve en detaylı anlatılacak dosya.
7. **`MyFirebaseMessagingService.kt` + `IncomingCallActivity.kt`** — gelen arama akışı, bonus konu.

---

*Bu doküman, Doğu'nun LiveKit E2EE demo projesi (`com.dogu.livekit`) için Claude tarafından hazırlanmıştır — 24 Temmuz 2026.*
