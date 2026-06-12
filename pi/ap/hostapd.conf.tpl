interface=wlan0
driver=nl80211
ssid=@SSID@
hw_mode=g
channel=@CH@
ieee80211n=1
wmm_enabled=1
ht_capab=[SHORT-GI-20]
country_code=IE
auth_algs=1
wpa=2
wpa_passphrase=@PASS@
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
# Keep beacons tight; small in-car network
beacon_int=100
max_num_sta=8
ignore_broadcast_ssid=0
