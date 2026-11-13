#!/system/bin/sh

MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "." ] && MODDIR="/data/adb/modules/snakeloader_frida"

mkdir -p /data/adb/reshift/scripts
chmod 755 /data/adb/reshift
chmod 755 /data/adb/reshift/scripts
restorecon -R /data/adb/reshift 2>/dev/null || true

# Always guarantee valid zygisk_config.json exists
if [ ! -f /data/adb/reshift/zygisk_config.json ]; then
    echo '{"enabled":false,"apps":[],"globalScriptId":null}' > /data/adb/reshift/zygisk_config.json
    chmod 644 /data/adb/reshift/zygisk_config.json
    restorecon /data/adb/reshift/zygisk_config.json 2>/dev/null || true
fi

# Sync temp binaries with module and ensure valid permissions & SELinux contexts
for BIN in frida-server frida-inject frida; do
    if [ -f "$MODDIR/$BIN" ]; then
        if [ ! -f "/data/local/tmp/$BIN" ] || [ "$MODDIR/$BIN" -nt "/data/local/tmp/$BIN" ]; then
            cp -f "$MODDIR/$BIN" "/data/local/tmp/$BIN"
        fi
    fi
    if [ -f "/data/local/tmp/$BIN" ]; then
        chmod 755 "/data/local/tmp/$BIN"
        chown root:shell "/data/local/tmp/$BIN" 2>/dev/null || chown 0:2000 "/data/local/tmp/$BIN" 2>/dev/null
        restorecon "/data/local/tmp/$BIN" 2>/dev/null || true
    fi
done
