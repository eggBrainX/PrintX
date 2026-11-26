# PrintX / LAN Print

Aplikasi Android cetak jaringan lokal (Kotlin + Jetpack Compose + Material 3) dengan dukungan IPP/IPPS, RAW 9100, LPR, Companion Server, Print Service sistem Android, scanner eSCL, cetak thermal ESC/POS, serta tata letak grid KTP & pasfoto.

## Build APK

Aplikasi ini menggunakan GitHub Actions untuk membangun file APK debug secara otomatis dengan kunci penandatanganan (*signing key*) tetap agar pembaruan APK dapat langsung menimpa versi sebelumnya.

1. **Aktifkan izin workflow di GitHub (wajib agar workflow dapat menyimpan `debug.keystore` sekali di awal):**
   - Buka **Settings → Actions → General → Workflow permissions** → pilih **Read and write permissions**, lalu simpan (*Save*).
2. **Cara mengambil APK:**
   - Buka tab **Actions** pada repositori GitHub → pilih **run terbaru** (atau jalankan manual lewat tombol *Run workflow*) → gulir ke bagian **Artifacts** → unduh file ZIP `PrintX-debug-<nomor-run>` → ekstrak file ZIP → install/update APK di perangkat Android Anda.
3. **Catatan pembaruan:**
   - Jika sebelumnya APK dipasang dari sumber lain (menggunakan kunci tanda tangan yang berbeda), silakan **uninstall** aplikasi satu kali terlebih dahulu. Setelah terpasang dari build GitHub Actions ini, pembaruan APK berikutnya dapat langsung menimpa versi lama tanpa perlu uninstall.
