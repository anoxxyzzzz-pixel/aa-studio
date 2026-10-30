# AA Studio (Android, Kotlin)

Editor overlay bergaya studio streaming: susun halaman web (Web Source) di kanvas 16:9, lalu
tampilkan sebagai jendela melayang di atas aplikasi lain. Identitas dan kode orisinal; hanya
terinspirasi dari alur kerja editor overlay (kanvas, daftar layer, panel properti).

## Cara kerja (mirip alur editor overlay di layar)
Menekan ikon **AA Studio** tidak membuka layar aplikasi: editor langsung tampil **di atas aplikasi lain**
(layar beranda atau aplikasi apa pun yang sedang terbuka).

- **Editor (di atas layar)**: tiap Web Source adalah kotak. Seret badan untuk memindah, seret sudut
  untuk mengubah ukuran, ketuk ikon gir untuk properti (nama, URL, X/Y/lebar/tinggi, tampil, kunci,
  mute, volume, urutan layer, hapus). Bar bawah: **Hapus semua**, **+** (tambah Web Source),
  **Layer** (daftar, urutan, tampil/sembunyi, kunci, hapus). Tombol ✕ di atas atau tab **Stream**
  beralih ke mode Stream. Tombol Back menutup panel.
- **Stream**: tiap elemen yang tampil menjadi satu jendela WebView di atas aplikasi lain; sentuhan
  menembus ke aplikasi di bawahnya. Bubble **AA** bisa digeser; ketuk untuk menu: Editor, Suara/Mute,
  Sentuh ON/OFF (agar halaman web bisa disentuh), Stop. Notifikasi juga punya tombol Editor dan Hentikan.
- **Izin overlay**: bila belum aktif, ikon membuka halaman izin (`ACTION_MANAGE_OVERLAY_PERMISSION`).
  Status dibaca dari `Settings.canDrawOverlays()` dan diperiksa ulang tiap kembali. Begitu aktif,
  editor langsung muncul di atas layar.
- **Daftar proyek & editor dalam aplikasi**: ketuk notifikasi (atau tombol "Buka daftar proyek" di halaman izin).
  Di sana ada buat/ganti nama/hapus proyek (dengan konfirmasi), tombol ▶ untuk overlay, editor kanvas
  dalam aplikasi, Preview, dan pengaturan Audio.
- **Audio**: suara uji bawaan, impor MP3/OGG, Play/Jeda/Stop, volume 0–100%, mute, file rusak
  ditangani. Mute/volume juga diterapkan ke elemen `<audio>`/`<video>` di Web Source.
- Koordinat disimpan dalam kanvas 1920x1080 yang dipetakan ke seluruh layar, jadi posisi tetap
  proporsional saat layar diputar.

## Build
Android Studio (Koala atau lebih baru): **Open** folder ini, tunggu sinkronisasi Gradle, lalu
**Build > Build APK(s)**. Atau lewat terminal (butuh Android SDK + Gradle 8.9 + JDK 17):

    gradle assembleDebug

APK ada di `app/build/outputs/apk/debug/`. Repositori ini juga punya GitHub Actions
(`.github/workflows/build.yml`): setiap push membangun APK dan menyimpannya sebagai artifact
**AA-Studio-debug** di tab Actions.

## Memakai overlay
1. Tekan ikon AA Studio. Pertama kali, nyalakan "Tampil di atas aplikasi lain" lalu kembali.
2. Tekan **+** untuk menambah Web Source (URL HTTPS), atur posisi dan ukuran.
3. Tekan **Stream** untuk menampilkan halamannya. Hentikan lewat bubble (Stop) atau notifikasi.

## Batasan yang diketahui
- Sebagian situs menolak dimuat di WebView atau butuh login; akan tampil pesan error.
- Autoplay audio tergantung situs dan kebijakan WebView. Kontrol mute/volume hanya menjangkau
  elemen `<audio>`/`<video>`, bukan audio dari Web Audio API.
- Audio Web Source tidak berjalan sendiri saat aplikasi ditutup total; selama overlay aktif,
  layanan foreground menjaga proses tetap hidup.
- Android 14+ dapat menampilkan notifikasi overlay lebih menonjol; ini perilaku sistem.
- HTTP polos (tanpa S) diblokir secara default demi keamanan.
