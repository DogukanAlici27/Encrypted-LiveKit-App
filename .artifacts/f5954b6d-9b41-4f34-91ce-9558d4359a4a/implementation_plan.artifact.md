# "Hayalet" Görüşme Durumunu Düzeltme Planı

Bu plan, görüşmede olmayan kullanıcıların "Görüşmede" (Meşgul) olarak görünmesi sorununu (ghost status) çözmeyi hedefler.

## Sorun Analizi
- **UI Mantığı**: Şu anki kodda bir kullanıcının "Görüşmede" görünmesi için sadece `currentRoom` bilgisinin dolu olması yeterli. Kullanıcı çevrimdışı olsa bile eğer sunucuda eski bir oda bilgisi kalmışsa "Görüşmede" görünüyor.
- **Arka Plan Kısıtlaması**: Uygulama arka plana alındığında `MainActivity` heartbeat (sinyal) göndermeyi durduruyor. Eğer kullanıcı o sırada bir görüşmedeyse, sunucu kullanıcının hala görüşmede olduğunu "online" süresi bitene kadar sanmaya devam ediyor.
- **Anlık Temizlik**: Görüşmeden ayrılındığında sunucuya hemen boş bir oda bilgisi gönderilmiyor, bir sonraki heartbeat döngüsü (2 saniye) bekleniyor.

## Proposed Changes

### 1. UI Düzenlemesi (Quick Fix)

#### [MODIFY] [MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)
- `addUserButton` metodunda `isInCall` kontrolü güncellenecek: Sadece kullanıcı `isOnline` İSE ve `currentRoom` doluysa "Görüşmede" görünecek.

### 2. Kesintisiz Sinyal (Heartbeat) Mekanizması

#### [MODIFY] [CallService.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/call/CallService.kt)
- `CallService`'e `@AndroidEntryPoint` eklenecek.
- Servis çalıştığı sürece (görüşme devam ederken) arka planda periyodik olarak heartbeat gönderecek. Bu sayede uygulama arka planda olsa bile kullanıcının görüşme durumu güncel kalacak.

### 3. Anlık Durum Güncelleme

#### [MODIFY] [MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)
- `leaveRoom` metodu içinde, `CallManager.disconnect()` yapıldıktan hemen sonra sunucuya boş oda bilgisi içeren bir heartbeat gönderilecek.

## Verification Plan

### Manual Verification
1. Bir görüşmeye girin ve çıkın. Rehberde durumunuzun anında "Çevrimiçi"ye döndüğünü (kırmızıdan yeşile) doğrulayın.
2. Görüşme sırasında uygulamayı arka plana atın. Diğer kullanıcıların sizi hala "Görüşmede" (Kırmızı) görmeye devam ettiğini doğrulayın (CallService sayesinde).
3. Uygulamayı tamamen kapatın (Kill). Sunucu zaman aşımına uğradığında durumun "Çevrimdışı"ya (Gri) döndüğünü doğrulayın.
