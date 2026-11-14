#!/bin/bash
# 部署守卫服务端到 /data/home/guardian
set -e
APP_DIR=/data/home/guardian
mkdir -p "$APP_DIR"

echo "=== 1. 确认 Node 版本 ==="
node --version

echo "=== 2. 同步代码（由本地 scp 完成，此处仅校验） ==="
ls -la "$APP_DIR"

echo "=== 3. 安装依赖 ==="
cd "$APP_DIR"
if [ -f package-lock.json ]; then
  npm ci --omit=dev --no-audit --no-fund
else
  npm install --omit=dev --no-audit --no-fund
fi

echo "=== 4. 用 systemd 托管，保证重启后自动拉起 ==="
sudo -n tee /etc/systemd/system/guardian.service > /dev/null <<'UNIT'
[Unit]
Description=Guardian device-monitor server
After=network.target

[Service]
Type=simple
User=user
WorkingDirectory=/data/home/guardian
Environment=PORT=8080
Environment=HOST=0.0.0.0
Environment=DATA_DIR=/data/home/guardian/data
Environment=PHOTO_DIR=/data/home/guardian/data/photos
ExecStart=/usr/local/bin/node /data/home/guardian/src/index.js
Restart=always
RestartSec=3
StandardOutput=append:/data/home/guardian/server.log
StandardError=append:/data/home/guardian/server.err.log

[Install]
WantedBy=multi-user.target
UNIT

sudo -n systemctl daemon-reload
sudo -n systemctl enable guardian >/dev/null 2>&1 || true
sudo -n systemctl restart guardian
sleep 2

echo "=== 5. 状态 ==="
sudo -n systemctl is-active guardian
curl -s -m 5 http://127.0.0.1:8080/api/health; echo
