# SPEC: Aplikasi Android "LAN Print" (nama sementara)

Cetak dari HP lewat Wi-Fi ke printer USB (via laptop) dan mesin fotokopi (via LAN).
Dokumen ini berisi: tujuan, arsitektur, fitur, permission, hasil analisis APK PrinterShare, dan roadmap.

---

## 1. Tujuan & Kondisi Jaringan

```
[HP Android] ──Wi-Fi──┬──► [Laptop + Print Server] ──USB──► [Printer USB]
                      │
                      └──► [Mesin Fotokopi] (kabel LAN, IP statis, IPP / RAW 9100)
```

- HP dan laptop ada di Wi-Fi/subnet yang sama dengan mesin fotokopi (atau routernya menjembatani Wi-Fi ke LAN).
- Printer USB tidak bisa diakses HP langsung lewat jaringan, jadi laptop harus jadi **print server**.
- Mesin fotokopi punya IP sendiri, jadi HP bisa langsung mengirim ke sana.

## 2. Keputusan Arsitektur Utama

**Dua jalur cetak:**

| Jalur | Tujuan | Protokol | Siapa yang merender |
|---|---|---|---|
| A. Direct | Mesin fotokopi (LAN) | IPP (631), RAW (9100), LPR (515) | HP |
| B. Via laptop | Printer USB | HTTP/HTTPS ke Companion Server | Laptop (driver Windows/OS) |

**Kenapa jalur B lewat laptop, bukan driver di HP?**
Driver printer USB sudah terpasang di laptop. Kalau HP cukup mengirim PDF ke laptop, kita tidak perlu menanam driver per merek di aplikasi Android. Laptop juga bisa mengonversi DOCX/XLSX/PPTX ke PDF dengan LibreOffice, yang hampir mustahil dilakukan akurat langsung di HP.

### 2.1 Companion Server (di laptop)

- Program kecil (pilih salah satu: Python/FastAPI, Node.js, atau Kotlin/JVM) yang jalan di background.
- Mengumumkan diri lewat mDNS/Bonjour: `_lanprint._tcp` (opsional juga `_ipp._tcp`).
- Endpoint:
  - `GET  /api/v1/printers` : daftar printer terpasang di laptop + kemampuan (warna, duplex, ukuran kertas)
  - `POST /api/v1/jobs` : unggah file (PDF/gambar/Office) + opsi cetak, balikan `jobId`
  - `GET  /api/v1/jobs/{id}` : status (queued / converting / printing / done / error)
  - `DELETE /api/v1/jobs/{id}` : batalkan
- Konversi Office: `soffice --headless --convert-to pdf` (LibreOffice).
- Cetak PDF ke printer: Windows → SumatraPDF `-print-to "NamaPrinter"`; Linux/macOS → `lp -d NamaPrinter`.
- Keamanan: PIN pairing sekali di awal, token per perangkat, hanya listen di jaringan lokal, opsi TLS self-signed.
- Alternatif tanpa menulis server: di laptop Linux/macOS pasang **CUPS** dan share printer, HP cukup bicara IPP. Di Windows biasanya tetap butuh Companion.

### 2.2 Jalur Direct (mesin fotokopi)

Urutan prioritas format yang dikirim:
1. **IPP Everywhere**: kirim `application/pdf` atau `image/urf` / `image/pwg-raster` (kebanyakan fotokopi modern: Canon, Ricoh, Kyocera, Epson, Brother, Xerox, Sharp).
2. **RAW 9100 + PDF langsung**: banyak mesin Ricoh/Kyocera/Sharp menerima PDF mentah.
3. **RAW 9100 + PCL6 / PostScript**: untuk mesin lama.
4. LPR (515): cadangan.

Temukan printer lewat: mDNS (`_ipp._tcp`, `_ipps._tcp`, `_printer._tcp`, `_pdl-datastream._tcp`), input IP manual, dan scan subnet port 631/9100 (opsional).
Ambil kemampuan printer lewat IPP `Get-Printer-Attributes` (ukuran kertas, duplex, warna, format dokumen yang didukung).

## 3. Fitur yang Dicetak

| Konten | Cara mendapatkan | Cara dirender menjadi PDF/bitmap |
|---|---|---|
| Gambar | Photo Picker / `ACTION_OPEN_DOCUMENT` / Share | Decode `Bitmap`, skala, lalu masuk ke `PdfDocument` |
| PDF | Storage Access Framework (SAF) / Share | `PdfRenderer` (preview + raster) atau kirim PDF mentah |
| DOCX, XLSX, PPTX, ODT, TXT, RTF | SAF / Share | Kirim ke Companion → LibreOffice → PDF. Jika tidak ada laptop: peringatkan dulu |
| Email | `ACTION_SEND` dari Gmail/aplikasi email (teks/HTML + lampiran); opsi lanjut: Gmail API (OAuth) atau IMAP | HTML → WebView → `createPrintDocumentAdapter` → PDF |
| Kontak | `ContactsContract` (`READ_CONTACTS`) | Susun layout sendiri ke `PdfDocument` |
| Pesan (SMS) | `Telephony.Sms` (`READ_SMS`) **lihat catatan Play Store** | Susun layout ke `PdfDocument` |
| Kalender | `CalendarContract` (`READ_CALENDAR`) | Susun layout (hari/minggu/bulan/agenda) ke `PdfDocument` |
| Halaman web | WebView + input URL / Share dari browser | `WebView.createPrintDocumentAdapter()` |
| Dari aplikasi lain | Daftarkan `PrintService` (menu Cetak bawaan Android) | Sistem Android memberi PDF/PWG-raster lewat `PrintJob` |

Fitur dasar: preview, pilih printer, jumlah salinan, rentang halaman, orientasi, ukuran kertas (A4/F4/Letter), warna/hitam-putih, duplex, kualitas, status/antrian cetak, notifikasi selesai, riwayat, halaman uji cetak.

**Catatan Play Store:** `READ_SMS` dan `READ_CALL_LOG` termasuk permission "restricted"; Google hanya mengizinkannya untuk aplikasi SMS/telepon default. Jika aplikasi hanya dibagikan lewat APK sendiri (sideload), boleh dipakai. Jika mau masuk Play Store, ganti dengan alur Share (pengguna membagikan pesan dari aplikasi SMS ke LAN Print).

### 3.1 Pengaturan Cetak (wajib ada)

Semua pengaturan muncul di satu layar "Opsi Cetak" dengan **preview langsung**, bisa disimpan sebagai **preset** ("Dokumen hemat", "Foto", "Fotokopi A3"), dan nilai terakhir diingat per printer.

**A. Kertas**

| Pengaturan | Pilihan |
|---|---|
| Ukuran kertas | A3 (297×420), A4 (210×297), A5 (148×210), A6 (105×148), B4 (257×364), B5 (182×257), **F4/Folio (215×330)**, Letter (216×279), Legal (216×356), Tabloid/Ledger (279×432), Executive, Statement, 4×6" / 5×7" (foto), amplop (DL, C5, No.10), **Ukuran kustom** (lebar × tinggi, mm/inci) |
| Orientasi | Otomatis, Potret, Lanskap |
| Jenis kertas | Biasa, Tebal, Daur ulang, Foto, Glossy/Doff, Label, Amplop, Kop surat |
| Sumber/baki | Otomatis, Baki 1, Baki 2, Baki 3, Bypass/manual (daftar diambil dari printer) |
| Daftar ukuran yang tampil | Hanya yang didukung printer terpilih (dibaca dari printer); ukuran lain tetap bisa dipilih dengan peringatan |

Catatan teknis: F4 Indonesia (215×330 mm) tidak sama dengan "folio" PWG (210×330 mm), jadi dikirim ke IPP sebagai ukuran kustom (`custom_f4_215x330mm`) bila printer tidak punya F4 bawaan. Bila ukuran tidak didukung, tawarkan: pakai ukuran terdekat + skala, atau batal.

**B. Tata letak & skala**

| Pengaturan | Pilihan |
|---|---|
| Skala | Muat ke halaman (fit), Isi halaman (fill/crop), Ukuran asli 100%, Persen manual (25–400%) |
| Margin | Standar printer, Sempit, Tanpa tepi (borderless, bila didukung), Kustom (mm) |
| Halaman per lembar | 1, 2, 4, 6, 9, 16 (n-up) + urutan & garis tepi |
| Posisi | Tengah / kiri-atas; geser X/Y; putar 90°; cermin (mirror) |
| Poster | Pecah 1 gambar ke beberapa lembar (mis. A4 → A3 tempel) |
| Rentang halaman | Semua, halaman saat ini, "1-3,5,8-10", genap/ganjil saja |
| Salinan | 1–999, **collate** (urut) atau tidak, balik urutan halaman |
| Duplex | Satu sisi, Dua sisi balik panjang (buku), Dua sisi balik pendek (kalender) |
| Finishing (mesin fotokopi) | Staple, punch/lubang, lipat, offset output, baki keluaran (bila didukung) |

**C. Kualitas & warna**

| Pengaturan | Pilihan |
|---|---|
| Mode warna | Warna, Hitam-putih/grayscale, Otomatis |
| Kualitas | Draft/Hemat tinta, Normal, Tinggi/Foto |
| Resolusi | 150 / 300 / 600 / 1200 dpi (sesuai kemampuan printer) |
| Hemat toner/tinta | Aktif/nonaktif |
| Optimasi isi | Teks, Grafik, Teks+Grafik, Foto |
| Rendering intent | Perseptual, Relatif, Saturasi (untuk foto) |

**D. Penyesuaian gambar** (slider, ada tombol Reset, preview waktu nyata)

| Pengaturan | Rentang | Keterangan |
|---|---|---|
| Kecerahan (terang/gelap) | −100 … +100 | Geser gambar lebih terang/gelap |
| Kontras | −100 … +100 | Bedakan area terang dan gelap |
| Ketajaman (sharpness) | 0 … 100 | Unsharp mask / konvolusi |
| Saturasi | −100 … +100 | Kepekatan warna |
| Gamma / Midtone | 0,5 … 2,5 | Perbaiki foto terlalu gelap/pucat |
| Suhu warna (hangat/dingin) | −100 … +100 | Opsional |
| Kegelapan cetak (density/darkness) | 1 … 5 | Dikirim ke printer bila mendukung, kalau tidak dihitung di HP |
| Ambang hitam-putih (threshold) | 0 … 255 | Untuk dokumen fotokopi/hasil foto kertas |
| Mode dokumen | Normal, **Bersihkan latar** (kertas kuning/abu → putih), Hitamkan teks | Cocok untuk cetak hasil foto dokumen |
| Mode foto | Auto-enhance, Hapus mata merah (opsional) | |
| Potong & luruskan | Crop manual, auto-deteksi tepi dokumen, deskew, putar | |
| Filter cepat | Asli, Grayscale, Hitam-putih tajam, Sepia, Negatif (invert) | |

Penerapan: untuk gambar/foto, penyesuaian dilakukan di HP pada `Bitmap` (`ColorMatrix` untuk kecerahan/kontras/saturasi/gamma; konvolusi atau shader AGSL/OpenGL untuk ketajaman; threshold manual). Untuk PDF/dokumen, penyesuaian diterapkan saat dirender ke raster **hanya jika pengguna mengaktifkannya**; bila tidak, PDF dikirim apa adanya agar teks tetap vektor dan tajam.

**E. Model data (Kotlin)**

```kotlin
data class PrintOptions(
    // Kertas
    val paperSize: PaperSize = PaperSize.A4,          // enum + Custom(widthMm, heightMm)
    val orientation: Orientation = Orientation.AUTO,
    val mediaType: MediaType = MediaType.PLAIN,
    val tray: Tray = Tray.AUTO,
    // Tata letak
    val scale: Scale = Scale.FIT,                      // FIT, FILL, ACTUAL, Percent(n)
    val margins: Margins = Margins.DEFAULT,
    val pagesPerSheet: Int = 1,
    val pageRange: String? = null,
    val copies: Int = 1,
    val collate: Boolean = true,
    val duplex: Duplex = Duplex.OFF,
    val finishing: Set<Finishing> = emptySet(),
    // Kualitas & warna
    val color: ColorMode = ColorMode.COLOR,
    val quality: Quality = Quality.NORMAL,
    val dpi: Int = 300,
    val tonerSave: Boolean = false,
    val contentOptimize: Optimize = Optimize.AUTO,
    // Penyesuaian gambar
    val brightness: Int = 0,      // -100..100
    val contrast: Int = 0,        // -100..100
    val sharpness: Int = 0,       // 0..100
    val saturation: Int = 0,      // -100..100
    val gamma: Float = 1.0f,      // 0.5..2.5
    val darkness: Int = 3,        // 1..5
    val bwThreshold: Int? = null, // null = nonaktif
    val cleanBackground: Boolean = false,
    val filter: ImageFilter = ImageFilter.NONE,
)
```

**F. Pemetaan ke printer (IPP)**

| Pengaturan | Atribut IPP |
|---|---|
| Ukuran kertas | `media` / `media-col` (`media-size`: x-dimension, y-dimension dalam 1/100 mm) |
| Jenis kertas, baki | `media-type`, `media-source` |
| Orientasi | `orientation-requested` |
| Skala | `print-scaling` (`auto`, `fit`, `fill`, `none`) |
| Salinan, collate | `copies`, `multiple-document-handling` |
| Duplex | `sides` (`one-sided`, `two-sided-long-edge`, `two-sided-short-edge`) |
| Halaman per lembar | `number-up` |
| Rentang halaman | `page-ranges` |
| Warna | `print-color-mode` (`color`, `monochrome`, `auto`) |
| Kualitas | `print-quality` (3 draft, 4 normal, 5 high) |
| Resolusi | `printer-resolution` |
| Optimasi isi | `print-content-optimize` |
| Finishing | `finishings` (staple, punch, dll.) |
| Baki keluaran | `output-bin` |

Kecerahan, kontras, ketajaman, dan saturasi **tidak ada** di IPP standar, jadi diterapkan di HP (atau di Companion) sebelum dikirim. Opsi yang tidak didukung printer ditampilkan abu-abu dengan keterangan alasannya.

**G. Companion Server (printer USB)**
`POST /api/v1/jobs` menerima objek `options` yang sama (JSON). Companion menerjemahkannya ke setelan driver Windows/CUPS (mis. `lp -o media=A4 -o sides=two-sided-long-edge -o print-quality=5`, atau DEVMODE di Windows). `GET /api/v1/printers` mengembalikan kemampuan tiap printer (ukuran kertas, baki, duplex, warna) agar UI hanya menampilkan pilihan yang valid.

## 4. Stack yang Disarankan

- Bahasa: Kotlin, Jetpack Compose (UI), Coroutines/Flow.
- `minSdk 26` (Android 8), `targetSdk 35` ke atas.
- Jaringan: `NsdManager` (mDNS), OkHttp (ke Companion), socket biasa (RAW 9100/LPR), klien IPP sendiri atau library IPP (mis. jipp / ipp-client-kotlin).
- Database lokal: Room (printer tersimpan, riwayat).
- Background: `WorkManager` + foreground service tipe `dataSync` untuk pengiriman job besar.
- Integrasi sistem: `android.printservice.PrintService` agar muncul di menu Cetak semua aplikasi.
- Test: printer sungguhan + emulator printer IPP (mis. `ippeveprinter` dari CUPS) untuk uji tanpa menghabiskan kertas.

## 5. Permission (AndroidManifest.xml)

### 5.1 Manifest siap pakai

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- ===== Jaringan (wajib) ===== -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" /> <!-- mDNS/multicast -->
    <!-- Android 13+: izin cari perangkat di jaringan lokal (mDNS/scan) -->
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation" />
    <!-- Android 12 ke bawah: scan Wi-Fi/mDNS butuh lokasi -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="32" />

    <!-- ===== Notifikasi & background ===== -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- ===== Akses file ===== -->
    <!-- Disarankan: TIDAK perlu izin storage, pakai SAF / Photo Picker.
         Izin di bawah hanya untuk Android 12 ke bawah jika perlu baca langsung. -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <!-- Android 13+: hanya jika mau galeri sendiri (kalau pakai Photo Picker, hapus) -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

    <!-- ===== Data pribadi (fitur kontak, kalender, pesan) ===== -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.READ_SMS" />          <!-- restricted di Play Store -->
    <!-- Opsional: <uses-permission android:name="android.permission.READ_CALL_LOG" /> -->

    <!-- ===== Email via akun (hanya jika pakai Gmail API / IMAP) ===== -->
    <!-- Pakai OAuth (Google Sign-In) / form login IMAP; tidak perlu GET_ACCOUNTS -->

    <uses-feature android:name="android.hardware.wifi" android:required="true" />
    <uses-feature android:name="android.hardware.telephony" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:usesCleartextTraffic="true"
        android:networkSecurityConfig="@xml/network_security_config">
        <!-- cleartext hanya untuk IP lokal, lihat 5.3 -->

        <!-- Layanan cetak sistem: muncul di menu Cetak semua aplikasi -->
        <service
            android:name=".print.LanPrintService"
            android:exported="true"
            android:permission="android.permission.BIND_PRINT_SERVICE">
            <intent-filter>
                <action android:name="android.printservice.PrintService" />
            </intent-filter>
            <meta-data
                android:name="android.printservice"
                android:resource="@xml/printservice" />
        </service>

        <!-- Terima Share dari aplikasi lain -->
        <activity android:name=".ui.ShareReceiverActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
                <data android:mimeType="application/pdf" />
                <data android:mimeType="text/*" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.*" />
                <data android:mimeType="application/msword" />
                <data android:mimeType="application/vnd.ms-excel" />
                <data android:mimeType="application/vnd.ms-powerpoint" />
            </intent-filter>
        </activity>

        <!-- Buka file dari file manager -->
        <activity android:name=".ui.ViewFileActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/pdf" />
                <data android:mimeType="image/*" />
            </intent-filter>
        </activity>

        <service
            android:name=".print.SendJobService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
    </application>
</manifest>
```

### 5.2 Penjelasan permission

| Permission | Untuk apa | Kapan diminta (runtime) |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Kirim job ke laptop/fotokopi, cek koneksi Wi-Fi | Otomatis (normal) |
| `CHANGE_WIFI_MULTICAST_STATE` | Menerima paket mDNS untuk menemukan printer | Otomatis (normal) |
| `NEARBY_WIFI_DEVICES` | Menemukan perangkat jaringan lokal di Android 13+ | Saat pertama kali scan printer |
| `ACCESS_FINE_LOCATION` (≤ Android 12) | Syarat scan/discovery di versi lama | Saat pertama kali scan |
| `POST_NOTIFICATIONS` | Notifikasi progres/selesai/gagal cetak | Saat pertama cetak (Android 13+) |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` | Upload file besar tetap jalan saat aplikasi ditutup | Otomatis |
| `READ_EXTERNAL_STORAGE` (≤ 12) / `READ_MEDIA_IMAGES` (13+) | Baca gambar langsung | **Hindari**: pakai Photo Picker & SAF yang tanpa izin |
| `READ_CONTACTS` | Cetak kontak | Saat membuka fitur Kontak |
| `READ_CALENDAR` | Cetak agenda | Saat membuka fitur Kalender |
| `READ_SMS` | Cetak pesan | Saat membuka fitur Pesan (restricted) |
| `BIND_PRINT_SERVICE` | Syarat sistem untuk mendaftarkan `PrintService` | Otomatis; dikunci hanya ke sistem |

Prinsip: minta permission **saat fitur dipakai** (bukan saat aplikasi dibuka), dan fitur lain tetap jalan jika permission ditolak.

### 5.3 Network security config (izinkan HTTP ke IP lokal saja)

`res/xml/network_security_config.xml`:
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <!-- Printer & laptop di jaringan lokal sering tanpa TLS; IPP/RAW tidak lewat sini,
         ini hanya untuk HTTP ke Companion jika TLS belum dipakai. -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.0.0</domain>
        <!-- Untuk rentang IP, lebih aman: Companion pakai HTTPS self-signed + pin sertifikat -->
    </domain-config>
</network-security-config>
```
Catatan: `network-security-config` tidak mendukung rentang CIDR. Solusi yang lebih bersih: Companion memakai HTTPS dengan sertifikat self-signed yang di-pin saat pairing, lalu `cleartextTrafficPermitted` tetap `false`.

## 6. Lampiran (hanya referensi, tidak dipakai di pengembangan): Analisis APK PrinterShare v12.25.2

Package: `com.dynamixsoftware.printershare`. Hasil bedah `classes.dex`, `AndroidManifest.xml`, dan `assets/`:

### 6.1 Server unduhan driver / komponen

| URL | Fungsi (disimpulkan dari string di kode) |
|---|---|
| `https://download.printershare.net/android/` | **Server utama unduhan paket driver & plugin** (juga ada versi `http://`) |
| `http://download.dynamixsoftware.com/printershare/android/` | Server lama (legacy / cadangan) |
| `https://download.printershare.net/build/version.xml` | Manifest versi: daftar komponen + nomor versi terbaru |
| `https://api.printershare.net/v1/...` | API akun/lisensi: `activate`, `pricing`, `upgrade`, `help` (bukan untuk driver) |

Komponen yang dikelola (nama dan versi tertanam di APK):

| Komponen | Versi di APK | Isi |
|---|---|---|
| `drv_hplip` | 3.25.6 | Driver HP (HPLIP) |
| `drv_gutenprint` | 5.2.8-pre1s | Driver Gutenprint (banyak merek inkjet) |
| `drv_escpr` | 1.7.17 | Driver Epson ESC/P-R |
| `drv_splix` | 2.0.0s | Driver Samsung/Dell/Xerox (SpliX) |
| `lib_pdfrender` | 6.0.3 / 7.0.3 / 8.0.3 | Library render PDF (per versi Android), memuat `libpdfium.so` |

- File `assets/data/drv_*.dat` hanyalah **katalog model → driver** (teks: nama printer + indeks), bukan driver itu sendiri. Binary driver nyata (library `.so`) diunduh aplikasi dari server di atas saat pengguna memilih printer, lalu disimpan lokal ("Cannot install driver pack" muncul jika gagal).
- Pola nama file persis di server (mis. `drv_hplip_3.25.6_arm64.zip`) **tidak tertulis utuh** di string APK dan server tidak bisa saya akses dari sini (jaringan sandbox dibatasi). Untuk mengonfirmasi, jalankan aplikasi dengan proxy (mitmproxy/HTTP Toolkit) atau buka `version.xml` di browser Anda.

### 6.2 Protokol yang dipakai PrinterShare (berguna sebagai referensi fitur)

IPP (`_ipp._tcp`), WSD / WS-Print (Microsoft `wdp/print`, WS-Discovery), SMB/CIFS (jcifs), Canon command set, Bluetooth, USB host; plus aktivitas: cetak gambar, dokumen, kontak, pesan, kalender, call log, web, Gmail.

### 6.3 Izin yang diminta PrinterShare (sebagai pembanding)

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_FINE_LOCATION`, `BLUETOOTH*`, `READ_EXTERNAL_STORAGE`, `READ_CONTACTS`, `READ_CALENDAR`, `READ_SMS`, `READ_CALL_LOG`, `READ_PHONE_STATE`, `GET_ACCOUNTS`/`MANAGE_ACCOUNTS`/`USE_CREDENTIALS`, `INSTALL_PACKAGES`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_DATA_SYNC)`, `AD_ID`. Banyak yang berlebihan; daftar di bagian 5 sengaja lebih ramping.

### 6.4 Peringatan penting

- APK yang dilampirkan adalah versi **modifikasi pihak ketiga** (tanda "modyolo"; di dalamnya ada jejak `ApkSignatureKillerEx`, yaitu pemalsu tanda tangan). Jangan dipasang di HP yang berisi data penting dan jangan dipakai sebagai basis kode.
- PrinterShare adalah perangkat lunak berhak cipta. **Jangan menyalin kode atau mengunduh paket driver dari server mereka untuk dibundel ke aplikasi Anda** (melanggar lisensi dan bisa diblokir kapan saja).
- Driver di dalamnya (HPLIP, Gutenprint, ESC/P-R, SpliX) berasal dari proyek open source. Jika suatu saat butuh driver di HP, ambil langsung dari sumber aslinya dan patuhi lisensinya (GPL/LGPL), tetapi dengan arsitektur di bagian 2, **kemungkinan besar Anda tidak memerlukan driver sama sekali di HP.**

## 7. Rencana Pengembangan

| Fase | Isi | Hasil |
|---|---|---|
| 0 | Siapkan Companion minimal di laptop (upload PDF → cetak) + cek model/IP mesin fotokopi & dukungan IPP/9100 | Bukti konsep cetak dari `curl` |
| 1 | Aplikasi: temukan printer (mDNS + IP manual), pilih file PDF/gambar, kirim ke Companion & IPP direct | MVP bisa mencetak |
| 2 | Semua Pengaturan Cetak (bagian 3.1: kertas A3/A4/F4/dll., skala, duplex, warna, kecerahan, kontras, ketajaman) + preview + preset + status job + riwayat | Aplikasi layak pakai harian |
| 3 | Dokumen Office via LibreOffice di Companion; halaman web; Share-target | Cakupan format lengkap |
| 4 | Kontak, kalender, pesan, email | Fitur "konten ponsel" |
| 5 | `PrintService` sistem, TLS + pairing, installer Companion, uji di berbagai merek | Rilis |

## 8. Pertanyaan Terbuka (jawaban Anda menentukan detail)

1. OS laptop: Windows, Linux, atau macOS?
2. Merek/model printer USB dan mesin fotokopi (untuk cek dukungan IPP/PDF langsung)?
3. Aplikasi untuk pemakaian pribadi/kantor saja, atau akan dirilis ke Play Store? (menentukan nasib `READ_SMS`)
4. Perlu fitur scan/fotokopi dari HP juga, atau cetak saja?
