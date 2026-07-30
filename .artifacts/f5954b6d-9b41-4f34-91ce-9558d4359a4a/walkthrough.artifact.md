# Arama Geçmişini Temizleme Özelliği Eklendi

Kullanıcıların arama geçmişini tek tıkla silebilmesini sağlayan özellik başarıyla entegre edildi.

## Yapılan Değişiklikler

### 1. Veri ve Mantık Katmanı
- **[HistoryViewModel.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/viewmodel/HistoryViewModel.kt)** dosyasına `clearHistory()` fonksiyonu eklendi. Bu fonksiyon, yerel veritabanındaki tüm arama kayıtlarını siler.

### 2. Kullanıcı Arayüzü (UI)
- **[activity_main.xml](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/res/layout/activity_main.xml)** dosyasına, arama geçmişi başlığının yanına kırmızı renkli bir "TEMİZLE" butonu eklendi.
- **[MainActivity.kt](file:///home/dogukan/Desktop/kopya6/kopya6/app/src/main/java/com/dogu/livekit/ui/MainActivity.kt)** içinde bu buton bağlandı ve yanlışlıkla silmeleri önlemek için bir onay penceresi (AlertDialog) eklendi.

## Nasıl Kullanılır?
1. Uygulamanın alt menüsünden "Geçmiş" sekmesine gidin.
2. Sağ üstte yer alan **TEMİZLE** butonuna basın.
3. Çıkan onay penceresinde "Evet" seçeneğini seçerek tüm geçmişi temizleyebilirsiniz.

## Doğrulama Sonuçları
- Proje başarıyla derlendi (`assembleDebug`).
- Veritabanı işlemleri ve UI etkileşimleri kontrol edildi.
