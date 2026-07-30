# Arama Geçmişini Temizleme Özelliği Planı

Bu plan, kullanıcıların arama geçmişini tek tıkla temizleyebilmesi için gerekli UI ve mantıksal değişiklikleri içerir.

## Proposed Changes

### 1. Veri Katmanı (DAO)

#### [MODIFY] [CallLogDao.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/data/dao/CallLogDao.kt)
- `deleteAll()` metodu zaten mevcut.

---

### 2. ViewModel Katmanı

#### [MODIFY] [HistoryViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/HistoryViewModel.kt)
- `clearHistory()` fonksiyonu eklenecek. Bu fonksiyon `viewModelScope` içinde `db.callLogDao().deleteAll()` çağrısını yapacak.

---

### 3. Arayüz (UI) Katmanı

#### [MODIFY] [activity_main.xml](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/res/layout/activity_main.xml)
- `history_panel` içine "TEMİZLE" butonu eklenecek.

#### [MODIFY] [MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)
- `clearHistoryBtn` butonu tanımlanacak ve tıklandığında onay penceresi gösterip `historyViewModel.clearHistory()` çağrılacak.

## Verification Plan

### Manual Verification
- Arama geçmişi sekmesine gidilecek.
- "TEMİZLE" butonuna basılacak.
- Onay penceresinde "Evet" denilecek.
- Listenin anında boşaldığı doğrulanacak.
