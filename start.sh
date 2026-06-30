#!/bin/bash
#############################################
# GPS Tracker Bot — Auto Start Script
# cd /root/gps-tracker-bot && ./start.sh
############################################=

cd /root/gps-tracker-bot

# Config
export BOT_TOKEN="8845527390:AAH1RZGR9zuYM7Se_O5171QwgnhQ6gs85dY"
export BASE_URL="${BASE_URL:-http://localhost:5000}"

# Kill old processes
pkill -f "python3 bot.py" 2>/dev/null
pkill -f "ngrok http" 2>/dev/null
sleep 1

# Start Ngrok (if available)
if command -v ngrok &>/dev/null; then
    ngrok http 5000 --log=stdout > /tmp/ngrok.log 2>&1 &
    sleep 3
    NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['tunnels'][0]['public_url'])" 2>/dev/null)
    if [ -n "$NGROK_URL" ]; then
        export BASE_URL="$NGROK_URL"
        echo "🔗 Ngrok URL: $BASE_URL"
    fi
elif [ -f /tmp/ngrok ]; then
    /tmp/ngrok http 5000 --log=stdout > /tmp/ngrok.log 2>&1 &
    sleep 3
    NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['tunnels'][0]['public_url'])" 2>/dev/null)
    if [ -n "$NGROK_URL" ]; then
        export BASE_URL="$NGROK_URL"
        echo "🔗 Ngrok URL: $BASE_URL"
    fi
fi

# Reset DB if --reset flag
if [ "$1" == "--reset" ]; then
    rm -f tracker.db
    echo "💾 Database reset"
fi

# Start Bot
echo "🤖 Starting GPS Tracker Bot..."
echo "🌐 BASE_URL: $BASE_URL"
python3 bot.py
