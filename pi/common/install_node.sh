#!/usr/bin/env bash
# DMS — common per-node install: mediamtx (RTSP server with native Raspberry Pi
# camera support) + HTTP status endpoint. Called by setup_ap.sh / setup_client.sh.
# Usage: sudo bash install_node.sh <driver|front|rear>
set -euo pipefail

ROLE="${1:?role}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MTX_VER="v1.9.3"

ARCH="$(uname -m)"
case "$ARCH" in
  aarch64) MTX_ARCH="arm64" ;;
  armv7l|armv6l) MTX_ARCH="armv7" ;;
  *) echo "Unsupported arch $ARCH"; exit 1 ;;
esac

echo "== mediamtx ${MTX_VER} =="
mkdir -p /opt/mediamtx
curl -fL -o /tmp/mtx.tar.gz \
  "https://github.com/bluenviron/mediamtx/releases/download/${MTX_VER}/mediamtx_${MTX_VER}_linux_${MTX_ARCH}.tar.gz"
tar -xzf /tmp/mtx.tar.gz -C /opt/mediamtx
sed "s|@ROLE@|${ROLE}|g" "$SCRIPT_DIR/mediamtx.yml.tpl" > /opt/mediamtx/mediamtx.yml

cat > /etc/systemd/system/dms-stream.service <<'EOF'
[Unit]
Description=DMS camera RTSP stream (mediamtx + rpiCamera)
After=network.target
[Service]
ExecStart=/opt/mediamtx/mediamtx /opt/mediamtx/mediamtx.yml
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
EOF

echo "== Status endpoint =="
install -m 755 "$SCRIPT_DIR/status_server.py" /opt/mediamtx/status_server.py
cat > /etc/systemd/system/dms-status.service <<EOF
[Unit]
Description=DMS node status endpoint
After=network.target
[Service]
ExecStart=/usr/bin/python3 /opt/mediamtx/status_server.py ${ROLE}
Restart=always
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable dms-stream dms-status
echo "Node role '${ROLE}' installed."
