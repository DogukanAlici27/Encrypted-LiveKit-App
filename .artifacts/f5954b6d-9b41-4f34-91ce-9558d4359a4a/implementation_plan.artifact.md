# Engellenenler Listesi ve Durum Güncelleme Hızı İyileştirme Planı

Bu plan, engellenen kişilerin listede kalmamasını sağlayan hatayı düzeltmeyi ve çevrimiçi/çevrimdışı durum güncellemelerini hızlandırmayı amaçlar.

## Sorun Analizi
1.  **Engelleme Hatası**: `UserRepository.syncUsers` metodu sunucudan kullanıcı listesini çektiğinde, yerel veritabanındaki `isBlocked` (engelli) bilgisini hesaba katmadan her şeyi sıfırlıyor (`false` yapıyor). Bu yüzden kişi engellense bile bir sonraki senkronizasyonda (maksimum 5 saniye içinde) engeli kalkmış gibi görünüyor.
2.  **Yavaş Güncelleme**: Mevcut `autoRefresh` döngüsü 5 saniyede bir çalışıyor. Sunucunun heartbeat TTL süresiyle birleştiğinde bu durum UI'da gecikmelere yol açıyor.

## Proposed Changes

### 1. Veri Tutarlılığı İyileştirmeleri

#### [MODIFY] [UserRepository.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/data/repository/UserRepository.kt)
- `syncUsers` metodu güncellenecek: Sunucudan gelen veriler işlenirken, yerel veritabanında bu kullanıcı zaten varsa `isBlocked` durumu korunacak.

#### [MODIFY] [UserDao.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/data/dao/UserDao.kt)
- `getBlockedUsers` metodu `Flow<List<UserEntity>>` dönecek şekilde değiştirilecek. Böylece engelleme yapıldığında ayarlar ekranındaki liste anında yenilenecek.

---

### 2. Performans ve Hız İyileştirmeleri

#### [MODIFY] [MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)
- `startAutoRefresh` döngüsü **5 saniyeden 3 saniyeye** düşürülecek.
- `loadBlockedUsers` metodu artık statik bir liste yüklemek yerine `Flow` üzerinden sürekli dinleme yapacak.

## Verification Plan

### Manual Verification
- Bir kullanıcıyı engelleyin ve 10 saniye bekleyin. Ayarlar -> Engellenen Kişiler listesinde hala durup durmadığını ve ana rehberden gizlenip gizlenmediğini kontrol edin.
- Başka bir cihazdan uygulamayı kapatın/açın. Durumun 3 saniye içinde güncellendiğini doğrulayın.
