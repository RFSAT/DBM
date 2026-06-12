#!/usr/bin/env bash
# DMS — Driver-facing Pi: WiFi Access Point + camera RTSP node.
# Raspberry Pi Zero 2 W, Raspberry Pi OS Lite (Bookworm).
# Usage: sudo bash setup_ap.sh
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run as root"; exit 1; }
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="$SCRIPT_DIR/../common"

AP_IP="192.168.50.1"
SSID="DMS-CAR"
PASSPHRASE="ChangeMe-DMS-2026"     # <-- change before deployment
CHANNEL="6"                        # fix to least-congested of 1/6/11
ROLE="driver"

echo "== Packages =="
apt-get update
apt-get install -y hostapd dnsmasq python3 rpicam-apps-lite iw

echo "== Static IP on wlan0 (NetworkManager) =="
# Bookworm uses NetworkManager; create a shared (AP-side) profile is NOT used,
# we configure the interface manually and let hostapd own the radio.
nmcli radio wifi on || true
nmcli dev set wlan0 managed no || true

cat > /etc/systemd/network/10-wlan0.network <<EOF
[Match]
Name=wlan0
[Network]
Address=${AP_IP}/24
EOF
systemctl enable systemd-networkd

echo "== hostapd =="
sed -e "s|@SSID@|${SSID}|" -e "s|@PASS@|${PASSPHRASE}|" -e "s|@CH@|${CHANNEL}|" \
    "$SCRIPT_DIR/hostapd.conf.tpl" > /etc/hostapd/hostapd.conf
sed -i 's|^#\?DAEMON_CONF=.*|DAEMON_CONF="/etc/hostapd/hostapd.conf"|' /etc/default/hostapd
systemctl unmask hostapd
systemctl enable hostapd

echo "== dnsmasq (DHCP with MAC reservations) =="
install -m 644 "$SCRIPT_DIR/dnsmasq.conf" /etc/dnsmasq.conf
echo ">>> Edit /etc/dnsmasq.conf to insert the real MAC addresses of the"
echo ">>> front/rear Pis and the phone for fixed-IP reservations."
systemctl enable dnsmasq

echo "== Common node components (mediamtx + camera + status) =="
bash "$COMMON/install_node.sh" "$ROLE"

echo "== Hardware watchdog =="
grep -q '^dtparam=watchdog=on' /boot/firmware/config.txt || \
  echo 'dtparam=watchdog=on' >> /boot/firmware/config.txt
apt-get install -y watchdog
sed -i 's|^#watchdog-device|watchdog-device|' /etc/watchdog.conf
systemctl enable watchdog

echo
echo "Done. Reboot. AP '${SSID}' on ${AP_IP}, stream rtsp://${AP_IP}:8554/${ROLE}"
echo "Optional hardening for ignition power cuts: enable Overlay FS via raspi-config"
echo "(Performance Options -> Overlay File System) once configuration is final."
