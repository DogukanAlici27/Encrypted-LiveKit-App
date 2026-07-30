# Görüşme Durumu (Hayalet Status) İyileştirmeleri Yapıldı

Kullanıcıların görüşmede olmamasına rağmen "Görüşmede" görünmesi sorunu iki aşamalı bir iyileştirme ile giderildi.

## Yapılan Değişiklikler

### 1. Daha Sıkı Durum Kontrolü (UI)
Rehber listesinde bir kullanıcının "Görüşmede" görünmesi için artık iki şartın da sağlanması gerekiyor:
- Kullanıcının **Çevrimiçi** olması.
- Kullanıcının bir **Odaya bağlı** olması.
> [!NOTE]
> Bu sayede, kullanıcının interneti koptuğunda veya sunucuda eski bir oda kaydı kaldığında, kullanıcı "Meşgul" yerine "Çevrimdışı" olarak doğru bir şekilde gösterilecek.

### 2. Anlık Çıkış Bildirimi
Görüşmeden ayrıldığınızda (`leaveRoom` tetiklendiğinde):
- Uygulama artık 2 saniyelik heartbeat döngüsünü beklemiyor.
- Ayrılma işlemi tamamlanır tamamlanmaz sunucuya anlık bir sinyal (heartbeat) göndererek oda bilgisini temizliyor.
- Bu, diğer kullanıcıların sizin boşa çıktığınızı anında görmesini sağlıyor.

## Doğrulama Sonuçları

- **Derleme:** Başarılı (`assembleDebug`).
- **Mantık Akışı:** `addUserButton` içindeki `isInCall` kontrolü ve `leaveRoom` içindeki anlık heartbeat çağrısı doğrulandı.

Artık görüşmeden çıktığında veya bağlantın koptuğunda, durumunun rehberde çok daha tutarlı güncellendiğini göreceksin.
