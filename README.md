# ZelixGate Mobile

**ZelixGate**, Android cihazlarda çalışan; parçalama (split) tabanlı DPI aşma, DNS-over-HTTPS (DoH), reklam engelleme ve isteğe bağlı SOCKS5 üst vekil özelliklerini tek bir VPN tünelinde birleştiren bir ağ aracıdır. Masaüstü ZelixGate deneyiminin Android sürümüdür.

> **Sürüm:** 1.0.2 (versionCode 1020)  
> **Paket adı:** `com.zelixsec.app`  
> **Minimum Android:** 7.0 (API 24)  
> **Hedef Android:** 14 (API 34)

## İndir

En son APK için:

**[github.com/ZelixYzlm/ZelixGate-Mobil/releases/latest](https://github.com/ZelixYzlm/ZelixGate-Mobil/releases/latest)**

APK'yı indirdikten sonra dosyayı açın. Android, bilinmeyen kaynaklardan kurulum için izin isteyecektir. Kurulumdan sonra ilk bağlantıda Android'in **VPN izni** onayını vermeniz gerekir.

## Sürüm 1.0.2 yenilikleri

- **Android 14 tünel hatası düzeltildi.** `Starting FGS without a type` hatasına neden olan ön plan servisi, Android 14 için `specialUse` türü ve `FOREGROUND_SERVICE_SPECIAL_USE` izniyle başlatılıyor.
- **ZelixSec → ZelixGate.** Uygulama adı, arayüz, bildirimler ve tüm görünen metinler ZelixGate olarak güncellendi.
- **Pil optimizasyonu uyarısı.** Ana sayfada, bağlantının arka planda kopmaması için pil optimizasyonundan muafiyet öneren bir uyarı kutusu ve doğrudan ilgili ekranı açan **Ayarlar** butonu eklendi.
- **Güncelleme hatırlatması.** Yeni bir GitHub sürümü yayınlandığında ana sayfanın en üstünde güncelleme banner'ı görünür; indirme ve kurulum tek dokunuşla başlatılabilir.
- **Sürüm numarası PC sürümüyle eşitlendi:** 1.0.2.
- Güncelleme kaynağı yeni depoya taşındı: `ZelixYzlm/ZelixGate-Mobil`.
- Eski ayarlar (`zelixsec-settings.json`) ve eski güncelleme adresi otomatik olarak yeni biçime taşınır.

## Özellikler

- **DPI aşma / parçalama:** `split2` modu ve ayarlanabilir parçalama konumu ile SNI tabanlı engellemeleri aşma.
- **Hazır profiller:** Discord, Roblox, Sosyal + Yayın ve Tüm Trafik.
- **DNS-over-HTTPS:** Cloudflare ve diğer sağlayıcılarla şifreli DNS; sistem DNS'ine geri dönüş seçeneği.
- **Reklam ve izleyici engelleme:** Yerleşik hosts listesi, özel engelleme listesi ve test aracı.
- **SOCKS5 üst vekil:** İsteğe bağlı zincirleme (upstream) desteği.
- **Tanılama:** Hedef alan adı için DNS, doğrudan bağlantı ve tünelli bağlantı testleri.
- **Arayüz:** WebView tabanlı modern arayüz; Türkçe ve İngilizce başta olmak üzere çoklu dil, tema ve saydamlık ayarları.
- **Arka planda çalışma:** VPN ön plan servisi, bildirimden durdurma ve yeniden bağlanma desteği.

## Kurulum notları

### Eski ZelixSec sürümünden geçiş

Yeni APK, eski uygulamayla **aynı paket adını** (`com.zelixsec.app`) ve **aynı imza anahtarını** kullanır. Bu nedenle eski sürümün üzerine kurulabilir; ayarlarınız korunur.

Eski sürüm 1.0.9 olarak adlandırıldığı için yeni 1.0.2 sürümü otomatik güncelleme kontrolünde "daha yeni" görünmez. Bu geçişte APK'yı **elle** kurmanız gerekir. Sonraki sürümlerde (1.0.3 ve üzeri) ana sayfa güncelleme banner'ı normal şekilde çalışır.

### Pil optimizasyonu

Android, arka plandaki uygulamaları uyutarak VPN bağlantısını koparabilir. Ana sayfadaki uyarı kutusunda **Ayarlar** butonuna dokunup ZelixGate'i pil optimizasyonundan muaf tutun. Bazı üreticilerde (Xiaomi, Huawei, Samsung vb.) ek olarak "Otomatik başlatma" veya "Arka planda çalışmaya izin ver" ayarını açmanız gerekebilir.

### Güncellemeler

Uygulama açılıştan 5 saniye sonra GitHub Releases üzerinden sessiz bir güncelleme kontrolü yapar. Yeni sürüm varsa:

1. Ana sayfanın üstünde güncelleme banner'ı görünür.
2. **Güncelle** butonu APK'yı indirir.
3. İndirme bitince **Kur** butonu Android paket kurucusunu açar.
4. Android her zaman son kurulum onayını kullanıcıya bırakır.

Güncelleme adresi ayarlardan değiştirilebilir; varsayılan kaynak `https://api.github.com/repos/ZelixYzlm/ZelixGate-Mobil/releases/latest` adresidir.

## Gizlilik

- Ayarlar ve tanılama kayıtları cihazın yerel depolamasında tutulur.
- Uygulama, reklam veya analiz SDK'sı içermez.
- VPN tüneli cihaz üzerinde çalışır; trafik ZelixGate sunucularına gönderilmez.
- Reklam engelleme listesi ve DoH sorguları dışında harici bir servise veri iletilmez.

## Derleme

### Gereksinimler

- JDK 21
- Android SDK Platform 34 ve Build-Tools 34.0.0
- Android NDK `27.2.12479018`
- Gradle 8.10.2 (depodaki `gradlew` sarmalayıcısı kullanılabilir)

### Adımlar

```bash
git clone https://github.com/ZelixYzlm/ZelixGate-Mobil.git
cd ZelixGate-Mobil

# Windows
gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

Çıktı: `app/build/outputs/apk/release/app-release.apk`

### İmzalama

İmza bilgileri depoya **eklenmez**. Yerel derlemede `local.properties` dosyasına şu anahtarları ekleyin:

```properties
sdk.dir=C\:\\path\\to\\AndroidSdk
zelix.storeFile=zelixsec-release.jks
zelix.storePassword=...
zelix.keyAlias=zelixsec
zelix.keyPassword=...
```

CI ortamında aynı değerler `ZELIX_STORE_FILE`, `ZELIX_STORE_PASSWORD`, `ZELIX_KEY_ALIAS` ve `ZELIX_KEY_PASSWORD` ortam değişkenleriyle verilebilir. Anahtar tanımlı değilse release APK imzasız üretilir.

## Teknik yapı

- **Dil:** Kotlin + Java (JNI)
- **Arayüz:** WebView + HTML/CSS/JavaScript
- **Tünel:** `hev-socks5-tunnel`, `hev-task-system`, lwIP
- **Parçalama motoru:** `DesyncProxy` (masaüstü motorunun Android portu)
- **Güncelleme:** GitHub Releases API + Android `FileProvider` / paket kurucu

## Yasal uyarı

Bu yazılım yalnızca kişisel kullanım ve eğitim amaçlıdır. Kullanımınızın bulunduğunuz ülkenin yasalarına ve kullandığınız hizmetlerin koşullarına uygun olması sizin sorumluluğunuzdadır.

## Bağlantılar

- **Depo:** [github.com/ZelixYzlm/ZelixGate-Mobil](https://github.com/ZelixYzlm/ZelixGate-Mobil)
- **Sürümler:** [github.com/ZelixYzlm/ZelixGate-Mobil/releases](https://github.com/ZelixYzlm/ZelixGate-Mobil/releases)
- **Sorun bildirimi:** [github.com/ZelixYzlm/ZelixGate-Mobil/issues](https://github.com/ZelixYzlm/ZelixGate-Mobil/issues)
