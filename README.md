# GPS Tracker Bot - Delivery Tracking System

Sistem tracking lokasi real-time via Telegram Bot untuk pengiriman paket.

## 🚀 Setup

### 1. Buat Bot Telegram
- Buka [@BotFather](https://t.me/BotFather) di Telegram
- Kirim `/newbot` dan ikuti instruksi
- Simpan **Bot Token**

### 2. Install & Jalankan

```bash
cd gps-tracker-bot

# Install dependencies
pip3 install flask python-telegram-bot requests

# Set environment variables
export BOT_TOKEN="your_bot_token_here"
export BASE_URL="https://your-domain.com"  # atau http://localhost:5000

# Jalankan
python3 bot.py
```

### 3. Deploy (Opsional)
Untuk akses publik, gunakan **ngrok** atau VPS:

```bash
# Dengan ngrok
ngrok http 5000
# Copy URL https ke BASE_URL
```

## 📌 Fitur

| Fitur | Deskripsi |
|-------|-----------|
| 📦 Buat Link | Generate link tracking unik |
| 🔔 Notifikasi | Alert otomatis saat link dibuka |
| 🗺 Peta | Lihat lokasi di Google Maps |
| 📊 Riwayat | Semua event tracking tersimpan |
| 🗑 Hapus | Hapus link yang tidak digunakan |
| ⏸ Toggle | Aktifkan/nonaktifkan link |

## 🔄 Alur Kerja

```
1. User → Bot: "Buat Link Tracking"
2. Bot → User: Link unik (https://domain.com/track/abc123)
3. User → Target: Kirim link via WhatsApp/SMS/dll
4. Target → Web: Buka link → "Bagikan Lokasi Saya"
5. Web → Server: Kirim koordinat GPS
6. Server → Bot: Notifikasi lokasi + Google Maps link
7. User → Bot: Lihat riwayat & peta
```

## 📁 Struktur Project

```
gps-tracker-bot/
├── bot.py              # Main application (Flask + Telegram Bot)
├── templates/
│   ├── index.html      # Landing page
│   ├── track.html      # Halaman tracking (target membuka ini)
│   ├── map.html        # Peta lokasi
│   └── error.html      # Halaman error
├── tracker.db          # SQLite database (auto-created)
└── README.md
```

## 🔒 Privacy & Security

- Target harus secara eksplisit mengklik "Bagikan Lokasi Saya"
- Browser akan meminta izin GPS (tidak bisa di-bypass)
- Data hanya dibagikan ke pemilik link
- Link bisa dinonaktifkan/dihapus kapan saja
