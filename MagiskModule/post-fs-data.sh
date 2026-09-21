#!/system/bin/sh
mkdir -p /data/adb/reshift
[ -f "/data/local/tmp/frida-server" ] && chmod 755 /data/local/tmp/frida-server
[ -f "/data/local/tmp/frida-inject" ] && chmod 755 /data/local/tmp/frida-inject
[ -f "/data/local/tmp/frida" ] && chmod 755 /data/local/tmp/frida
