# AA Studio (Android, Kotlin)

Editor overlay bergaya studio streaming: susun halaman web (Web Source) di kanvas 16:9, lalu
tampilkan sebagai jendela melayang di atas aplikasi lain. Identitas dan kode orisinal; hanya
terinspirasi dari alur kerja editor overlay (kanvas, daftar layer, panel properti).

## Fitur
- **Editor**: kanvas 1920x1080, ketuk untuk memilih, seret untuk memindahkan, handle sudut untuk
  mengubah ukuran, cubit untuk zoom, geser untuk pan, input X/Y/Lebar/Tinggi, daftar layer
  (ke depan/belakang, tampil/sembunyi, kunci, hapus), Undo/Redo, simpan otomatis.
- **Web Source**: URL divalidasi, WebView dengan indikator loading, pesan error + tombol "Coba lagi",
  muat ulang, Preview di dalam aplikasi. WebView tanpa akses file dan tanpa JavaScript bridge.
- **Overlay melayang**: `TYPE_APPLICATION_OVERLAY` lewat `WindowManager`, dijalankan sebagai foreground
  service (notifikasi dengan tombol Hentikan). Header: geser untuk memindah, − / + ukuran, Suara, Sentuh
  ON/OFF, muat ulang, ✕ tutup.
- **Izin overlay**: `Settings.canDrawOverlays()` diperiksa setiap kali halaman dibuka lagi;
  tombol membuka `ACTION_MANAGE_OVERLAY_PERMISSION`. Tanpa izin, Editor dan Preview tetap berfungsi.
- **Audio**: suara uji bawaan (nada dibuat dari kode), impor MP3/OGG, Play/Jeda/Stop, volume 0–100%,
  mute, penanganan file rusak, audio focus. Volume/mute master juga diterapkan ke elemen
  `<audio>`/`<video>` di Web Source.
- **Proyek**: buat, buka, ganti nama, hapus (dengan konfirmasi); tersimpan lokal sebagai JSON.

## Build
Android Studio (Koala atau lebih baru): **Open** folder ini, tunggu sinkronisasi Gradle, lalu
**Build > Build APK(s)**. Atau lewat terminal (butuh Android SDK + Gradle 8.9 + JDK 17):

    gradle assembleDebug

APK ada di `app/build/outputs/apk/debug/`. Repositori ini juga punya GitHub Actions
(`.github/workflows/build.yml`): setiap push membangun APK dan menyimpannya sebagai artifact
**AA-Studio-debug** di tab Actions.

## Memakai overlay
1. Buat proyek, tambah Web Source (URL HTTPS), atur posisi dan ukuran.
2. Tekan **Jalankan overlay**. Bila izin belum aktif, aplikasi membuka halaman izin; nyalakan
   "Tampil di atas aplikasi lain" untuk AA Studio, lalu kembali.
3. Overlay muncul di atas aplikasi lain. Hentikan lewat tombol ✕ atau notifikasi.

## Batasan yang diketahui
- Sebagian situs menolak dimuat di WebView atau butuh login; akan tampil pesan error.
- Autoplay audio tergantung situs dan kebijakan WebView. Kontrol mute/volume hanya menjangkau
  elemen `<audio>`/`<video>`, bukan audio dari Web Audio API.
- Audio Web Source tidak berjalan sendiri saat aplikasi ditutup total; selama overlay aktif,
  layanan foreground menjaga proses tetap hidup.
- Android 14+ dapat menampilkan notifikasi overlay lebih menonjol; ini perilaku sistem.
- HTTP polos (tanpa S) diblokir secara default demi keamanan.
