# LiveKit: RAM'den Room DB'ye Modernizasyon Rehberi

Bu belge, LiveKit projesinde gerçekleştirilen devrimsel mimari değişikliği, kullanılan teknolojileri ve yeni senkronizasyon mantığını anlatır. Artık sunucunuz kapansa dahi verileriniz güvende ve sisteminiz "kendi kendini onarabilir" durumda.

---

## 1. Eski Sorun: "Balık Hafızalı" Sunucu
Eskiden tüm rehber ve kullanıcı bilgileri Java sunucusunun RAM hafızasında tutuluyordu.
- **Sonuç:** Sunucu her resetlendiğinde tüm kullanıcılar siliniyor, telefonlar "çevrimdışı" kalıyor ve kullanıcıların tekrar kayıt olması gerekiyordu.

## 2. Yeni Çözüm: "Patron Telefon" (Master-Slave) Mimarisi
Bugünkü çalışmalarımızla telefonun yerel hafızasını (Master) ana veri merkezi yaptık. Sunucu ise artık bu verileri yansıtan geçici bir köprü (Slave) olarak çalışıyor.

### Kullanılan Teknolojiler
*   **Room DB:** Telefonun içinde çalışan profesyonel SQLite veritabanı. Tüm rehber ve geçmiş burada saklanır.
*   **WorkManager:** Android'in en modern arka plan görev yöneticisi. Uygulama kapalıyken bile sunucuya sinyal gönderir.
*   **KSP (Kotlin Symbol Processing):** Room DB'nin mermi gibi hızlı çalışmasını sağlayan yeni nesil kod işleyici.
*   **Delta Sync (Diferansiyel Senkronizasyon):** Sadece değişen verileri gönderen akıllı bir algoritma.

---

## 3. Sistemin 4 Temel Direği

### A. Kalıcı Yerel Depolama (Room DB)
- **UserEntity:** Kullanıcı isimleri, şifreleri (yerel giriş için), fotoğrafları ve anahtarları artık telefonda mühürlü.
- **CallLogEntity:** Tüm gelen/giden/kaçırdığın aramalar saatine kadar burada saklanıyor. Alt menüye eklenen **"Geçmiş"** sekmesi veriyi buradan okuyor.

### B. Akıllı Senkronizasyon ve Delta Sync
- Telefon artık sunucudaki listeyi kendi listesiyle kıyaslıyor.
- **Diferansiyel Mantık:** Sadece sunucuda olmayan veya yeni açılmış (`needsSync = true`) hesaplar sunucuya itilir. Bu sayede sunucu logları "Zaten mevcut" hatalarından temizlendi.

### C. Kendi Kendini Onarma (Self-Healing)
- Sunucu resetlendiğinde telefon sunucunun hafızasının boşaldığını anlar (401 hatası).
- Hiçbir butona basmanıza gerek kalmadan, telefon arka planda sessizce kendi hesabını ve eksik hesapları sunucuya tekrar `/register` yaparak sunucuyu "eğitir".

### D. Kesintisiz Bağlantı Takibi (WorkManager)
- **Problem:** Eskiden uygulama arka plana atılınca bağlantı kopuyordu.
- **Çözüm:** `HeartbeatWorker` eklendi. Artık uygulama kapalı olsa dahi WorkManager periyodik olarak sunucuya "buradayım" sinyali gönderir ve sunucunun 40000ms'lik (40 saniye) esnek bekleme süresi sayesinde asla haksız yere "Offline" görünmezsiniz.

---

## 4. Kullanıcı Deneyimi İyileştirmeleri
- **Bağlantı Rozeti:** Sağ üstte anlık olarak **"ÇEVRİMİÇİ (Yeşil)"** veya **"ÇEVRİMDIŞI (Kırmızı)"** durumu görünür.
- **Çevrimdışı Giriş/Kayıt:** İnternet yokken bile yeni hesap açabilir veya kayıtlı hesaplarınla uygulamaya girip rehberinde gezinebilirsin.
- **HESABI SİL:** Ayarlar kısmından hem telefondan hem sunucudan kalıcı olarak hesap silme özelliği eklendi.
- **ŞİFRE DEĞİŞTİR:** Mevcut şifreni hem yerelde hem sunucuda güvenli bir şekilde güncelleme yeteneği eklendi.

---

> [!TIP]
> **Teknik Kanıt:** Android Studio'da **App Inspection** panelini açarak `livekit_database` içindeki `users` tablosuna bakabilirsiniz. Orada `password` ve `needsSync` sütunlarının nasıl akıllıca değiştiğini canlı olarak izleyebilirsiniz.

> [!IMPORTANT]
> Bu mimari sayesinde sunucunuz sadece bir "iletişim noktası" haline geldi. Tüm zekâ ve kalıcı veri artık kullanıcının cebindeki telefonda (Master) yaşıyor.

---
*LiveKit Modernizasyon Projesi - 29 Temmuz 2026*
