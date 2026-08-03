# LiveKit Projesi — Dosya Dosya Rehber (v2.0 MVVM)

Bu doküman, `LiveKit` projesindeki güncellenmiş MVVM mimarisini, dosya yapısını, önemli fonksiyonları ve bileşenler arası iletişimi anlatır.

---

## 1. Genel Mimari

Proje modern Android geliştirme standartlarına uygun **MVVM (Model-View-ViewModel)** mimarisi üzerine inşa edilmiştir ve iki ana kısımdan oluşur:

1. **Android Uygulaması** (`app/` klasörü, Kotlin) — Hilt (DI), Coroutines, Flow, Room ve WorkManager kullanan istemci.
2. **Token Server** (`livekit-token-server/` klasörü, Node.js/Express) — Kimlik doğrulama ve LiveKit JWT token üretimi yapan backend.

### Genel Akış

Arayan taraf `CallViewModel` üzerinden bir arama başlatır, Token Server Firebase üzerinden hedefe bildirim gönderir. Aranan taraf `IncomingCallActivity` ile karşılanır. Bağlantı kurulduğunda her iki taraf da `CallViewModel` üzerinden LiveKit Cloud'a şifreli (E2EE) olarak bağlanır.

---

## 2. Android Uygulaması Paket Yapısı (`app/src/main/java/com/dogu/livekit/`)

Proje, sorumlulukların net ayrımı için mantıksal paketlere bölünmüştür:

### 2.1 `core/` — Temel Araçlar ve Altyapı
- **`encryption/`**: `EncryptionManager` ve `KeyManager` ile E2EE (uçtan uca şifreleme) anahtar yönetimi.
- **`hardware/`**: `AudioManagerCompat` ile ses çıkış yönetimi.
- **`logging/`**: Uygulama genelinde kullanılan `Logger`.
- **`util/`**: `ImageUtils` (Base64/Bitmap), `PermissionUtils` (İzin yönetimi) ve `ViewExtensions` (UI animasyonları/yardımcıları).

### 2.2 `data/` — Veri Katmanı
- **`local/`**: Room veritabanı (`AppDatabase`), DAO'lar (`UserDao`, `CallLogDao`) ve kullanıcı tercihleri (`SessionPreferences`).
- **`remote/`**: `NetworkClient` ile backend API bağlantı yönetimi.
- **`repository/`**: `UserRepository`. Tüm ağ ve veritabanı işlemlerini koordine eden, ViewModel'lara temiz veri sağlayan ana katman.

### 2.3 `domain/` — İş Kuralları
- **`call/`**: `CallManager`. LiveKit SDK ile doğrudan konuşan, aktif `Room` referansını tutan singleton bağlantı yöneticisi.

### 2.4 `ui/` — Kullanıcı Arayüzü (MVVM)
Her ekran kendi ViewModel'ına ve gerekirse özel adapter'larına sahiptir:

- **`auth/`**: `AuthViewModel`. Kayıt, giriş, şifre değiştirme ve hesap silme mantığını yönetir.
- **`contacts/`**: `ContactsViewModel`. Rehber listesi, kullanıcı engelleme ve sunucu senkronizasyonunu yönetir.
- **`history/`**: `HistoryViewModel`. Arama geçmişini Room'dan çeker ve temizler.
- **`call/`**: 
    - `CallViewModel`: Arama başlatma, cevaplama, oda olaylarını (katılımcı girdi/çıktı, track eklendi vb.) dinleme ve UI'ya bildirme.
    - `VideoAdapter`: Video katılımcılarını grid yapısında gösterir.
    - `IncomingCallActivity`: Gelen arama ekranı.
- **`main/`**: 
    - `MainActivity`: Uygulamanın ana hostu. View Binding kullanır. Paneller arası geçişi ve ViewModel gözlemlemesini (observation) yapar.
    - `ContactsAdapter`, `CallLogAdapter`, `BlockedUsersAdapter`: Performanslı liste yönetimi için `ListAdapter` kullanan adapter'lar.

### 2.5 `service/` & `worker/` — Arka Plan İşlemleri
- **`CallService`**: Aktif aramayı foreground service olarak tutar.
- **`MyFirebaseMessagingService`**: Gelen arama bildirimlerini yakalar.
- **`HeartbeatWorker` & `UserSyncWorker`**: `WorkManager` ile periyodik olarak (15 dk) sunucuya aktiflik sinyali gönderir ve rehberi günceller.

---

## 3. Önemli Fonksiyonlar ve Akışlar

### 3.1 Arama Başlatma
1. `MainActivity` -> `startCall(target)`
2. `CallViewModel` -> `startCall(...)`: Token server'dan token ister, Room anahtarı üretir.
3. `MainActivity` -> `CallEvent.Connect` olayını yakalar ve `callViewModel.connectToRoom(...)` çağırır.
4. `CallManager.connect(...)`: Foreground servisi başlatır ve LiveKit'e bağlanır.

### 3.2 UI Güncelleme (Reactive Flow)
ViewModel'lar veriyi `StateFlow` veya `SharedFlow` olarak yayınlar. `MainActivity` bu akışları `lifecycleScope` içinde dinleyerek UI'yı (RecyclerView, Button durumları vb.) günceller.

---

## 4. Dosyalar Arası İlişki Haritası

```
MainActivity (View)
   |-- observes AuthViewModel (AuthState, AuthEvent)
   |-- observes ContactsViewModel (contacts, blockedUsers)
   |-- observes CallViewModel (isMicMuted, isCameraOn, events)
   |-- observes HistoryViewModel (history)
   |
   |--> CallViewModel
           |--> UserRepository (Data)
           |--> CallManager (Domain/LiveKit)
```

---

*Bu doküman, Doğu'nun LiveKit E2EE projesinin modern MVVM sürümü için hazırlanmıştır — 3 Ağustos 2026.*
