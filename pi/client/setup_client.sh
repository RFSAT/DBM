#!/usr/bin/env bash
# DMS — Front/Rear Pi: WiFi client of the driver-Pi AP + camera RTSP node.
# Usage: sudo bash setup_client.sh <front|rear> <static-ip e.g. 192.168.50.11>
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run as root"; exit 1; }
ROLE="${1:?role: front|rear}"
IP="${2:?static IP, e.g. 192.168.50.11}"
SSID="DMS-CAR"
PASSPHRASE="ChangeMe-DMS-2026"     # must match AP
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="$SCRIPT_DIR/../common"

apt-get update
apt-get install -y python3 rpicam-apps-lite

echo "== WiFi client profile (NetworkManager) =="
nmcli connection delete dms-car 2>/dev/null || true
nmcli connection add type wifi ifname wlan0 con-name dms-car ssid "$SSID" \
  wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$PASSPHRASE" \
  ipv4.method manual ipv4.addresses "${IP}/24" ipv4.gateway 192.168.50.1 \
  connection.autoconnect yes connection.autoconnect-retries 0
# autoconnect-retries 0 = retry forever (AP Pi may boot slower than this node)

echo "== Common node components =="
bash "$COMMON/install_node.sh" "$ROLE"

echo "== Hardware watchdog =="
grep -q '^dtparam=watchdog=on' /boot/firmware/config.txt || \
  echo 'dtparam=watchdog=on' >> /boot/firmware/config.txt
apt-get install -y watchdog
sed -i 's|^#watchdog-device|watchdog-device|' /etc/watchdog.conf
systemctl enable watchdog

echo "Done. Reboot. Stream: rtsp://${IP}:8554/${ROLE}"
