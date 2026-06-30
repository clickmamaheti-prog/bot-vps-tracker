#!/bin/bash
#############################################
# GPS Tracker Bot — Deploy to New VPS
# Jalankan di VPS baru: bash deploy.sh
#############################################

set -e

echo "🚀 GPS Tracker Bot — Deployment Script"
echo "========================================"

# 1. Install dependencies
echo ""
echo "📦 Installing dependencies..."
apt update -qq
apt install -y -qq python3 python3-pip python3-venv git curl > /dev/null 2>&1
echo "✅ Dependencies installed"

# 2. Clone repo
echo ""
echo "📥 Cloning repository..."
REPO_URL="https://github.com/winsdevcltr09/gps-link.git"
INSTALL_DIR="${1:-/opt/gps-tracker-bot}"

if [ -d "$INSTALL_DIR" ]; then
    echo "⚠️  Directory $INSTALL_DIR exists. Using existing code."
    cd "$INSTALL_DIR"
    git fetch origin
    git reset --hard origin/master
else
    mkdir -p "$INSTALL_DIR"
    git clone "$REPO_URL" "$INSTALL_DIR"
    cd "$INSTALL_DIR"
fi
echo "✅ Code ready at $INSTALL_DIR"

# 3. Setup Python virtual environment
echo ""
echo "🐍 Setting up Python environment..."
python3 -m venv venv
source venv/bin/activate
pip install --quiet -q flask python-telegram-bot requests > /dev/null 2>&1
echo "✅ Python packages installed"

# 4. Install ngrok
echo ""
echo "🔗 Installing ngrok..."
if ! command -v ngrok &>/dev/null; then
    curl -sL https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz -o /tmp/ngrok.tgz
    tar -xzf /tmp/ngrok.tgz -C /usr/local/bin/
    rm /tmp/ngrok.tgz
fi
echo "✅ Ngrok installed"

# 5. Create .env config
echo ""
echo "⚙️  Configuration..."
echo ""
read -p "Enter BOT_TOKEN: " BOT_TOKEN
read -p "Enter NGROK_AUTHTOKEN: " NGROK_AUTHTOKEN
read -p "Enter TELEGRAM_OWNER_ID: " OWNER_ID

cat > .env << EOF
BOT_TOKEN=$BOT_TOKEN
NGROK_AUTHTOKEN=$NGROK_AUTHTOKEN
TELEGRAM_OWNER_ID=$OWNER_ID
BASE_URL=
EOF

# Setup ngrok
ngrok config add-authtoken "$NGROK_AUTHTOKEN" > /dev/null 2>&1
echo "✅ Configuration saved"

# 6. Install systemd service
echo ""
echo "🔧 Installing systemd service..."
cat > /etc/systemd/system/gps-tracker.service << EOFSERVICE
[Unit]
Description=GPS Tracker Bot
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$INSTALL_DIR
Environment="BOT_TOKEN=$BOT_TOKEN"
Environment="BASE_URL="
ExecStart=$INSTALL_DIR/venv/bin/python3 $INSTALL_DIR/bot.py
Restart=always
RestartSec=10
Environment="PATH=$INSTALL_DIR/venv/bin:/usr/local/bin:/usr/bin:/bin"

[Install]
WantedBy=multi-user.target
EOFSERVICE

systemctl daemon-reload
systemctl enable gps-tracker > /dev/null 2>&1
echo "✅ Systemd service installed"

# 7. Create helper script
cat > $INSTALL_DIR/manage.sh << 'EOFSCRIPT'
#!/bin/bash
cd "$(dirname "$0")"
case "$1" in
    start) systemctl start gps-tracker ;;
    stop) systemctl stop gps-tracker ;;
    restart) systemctl restart gps-tracker ;;
    status) systemctl status gps-tracker ;;
    logs) journalctl -u gps-tracker -f ;;
    ngrok) ngrok http 5000 ;;
    reset-db) rm -f tracker.db && echo "DB reset" ;;
    *) echo "Usage: ./manage.sh {start|stop|restart|status|logs|ngrok|reset-db}" ;;
esac
EOFSCRIPT
chmod +x $INSTALL_DIR/manage.sh

echo ""
echo "========================================"
echo "✅ Deployment complete!"
echo ""
echo "Next steps:"
echo "1. Start bot:     cd $INSTALL_DIR && ./manage.sh start"
echo "2. Start ngrok:   cd $INSTALL_DIR && ./manage.sh ngrok"
echo "3. View logs:     cd $INSTALL_DIR && ./manage.sh logs"
echo "4. Get ngrok URL: curl -s http://localhost:4040/api/tunnels | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[\"tunnels\"][0][\"public_url\"])'"
echo ""
echo "⚠️  Don't forget to update BASE_URL in systemd env or .env after getting ngrok URL"
