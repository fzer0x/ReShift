#!/system/bin/sh

SKIPUNZIP=0

ui_print "- Configured for ReShift"
ui_print "- Checking environment..."

if [ "$KSU_NEXT" = "true" ] || [ -n "$KSU_NEXT_VER" ] || [ -f "/sys/module/kernelsu/parameters/ksu_next" ]; then
    ui_print "- Environment: KernelSU Next detected (v${KSU_NEXT_VER:-${KSU_VER:-1.0.0}})"
elif [ "$KSU" = "true" ] || [ -d "/data/adb/ksu" ]; then
    ui_print "- Environment: KernelSU detected (v${KSU_VER:-0.9.0})"
elif [ "$APATCH" = "true" ] || [ -n "$APATCH_VER" ]; then
    ui_print "- Environment: APatch detected (v${APATCH_VER:-1.0.0})"
elif [ -n "$MAGISK_VER" ]; then
    ui_print "- Environment: Magisk detected (v$MAGISK_VER)"
else
    ui_print "- Environment: Magisk / KernelSU / KSU Next Compatible Root"
fi

FRIDA_TARGET="/data/adb/reshift/frida-server"
FRIDA_INJECT_TARGET="/data/adb/reshift/frida-inject"
FRIDA_CLI_TARGET="/data/adb/reshift/frida"
FRIDA_MODULE_BINARY="$MODPATH/frida-server"
FRIDA_MODULE_INJECT="$MODPATH/frida-inject"
FRIDA_MODULE_CLI="$MODPATH/frida"

ui_print "- Setting up module permissions..."

set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755

if [ -d "$MODPATH/zygisk" ]; then
    ui_print "- Setting up Zygisk native components..."
    set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
fi

# Ensure /data/adb/reshift storage and default zygisk config exist with proper SELinux contexts
mkdir -p /data/adb/reshift/scripts
mkdir -p /data/local/tmp
chmod 755 /data/adb/reshift
chmod 755 /data/adb/reshift/scripts
chcon -R u:object_r:system_file:s0 /data/adb/reshift 2>/dev/null || true

if [ ! -f /data/adb/reshift/zygisk_config.json ]; then
    echo '{"enabled":false,"apps":[],"globalScriptId":null}' > /data/adb/reshift/zygisk_config.json
    chmod 644 /data/adb/reshift/zygisk_config.json
    chcon u:object_r:system_file:s0 /data/adb/reshift/zygisk_config.json 2>/dev/null || true
fi

install_binary() {
    local src=$1
    local dest=$2
    local name=$3
    if [ -f "$src" ]; then
        ui_print "- Installing $name from module..."
        cp -f "$src" "$dest"
        chmod 755 "$dest"
        chown root:shell "$dest" 2>/dev/null || chown 0:2000 "$dest" 2>/dev/null
        chcon u:object_r:system_file:s0 "$dest" 2>/dev/null || true
        ui_print "- $name installed successfully to $dest"
    elif [ -f "$dest" ]; then
        ui_print "- Existing $name found at $dest, updating permissions"
        chmod 755 "$dest"
        chown root:shell "$dest" 2>/dev/null || chown 0:2000 "$dest" 2>/dev/null
        chcon u:object_r:system_file:s0 "$dest" 2>/dev/null || true
    else
        ui_print "- Notice: $name managed at runtime via ReShift Downloader Engine (/data/adb/reshift/)"
    fi
}

install_binary "$FRIDA_MODULE_BINARY" "$FRIDA_TARGET" "frida-server"
install_binary "$FRIDA_MODULE_INJECT" "$FRIDA_INJECT_TARGET" "frida-inject"
install_binary "$FRIDA_MODULE_CLI" "$FRIDA_CLI_TARGET" "frida"

if [ ! -f "$FRIDA_CLI_TARGET" ] && [ -f "$FRIDA_INJECT_TARGET" ]; then
    ui_print "- Creating CLI alias at $FRIDA_CLI_TARGET..."
    cp -f "$FRIDA_INJECT_TARGET" "$FRIDA_CLI_TARGET"
    chmod 755 "$FRIDA_CLI_TARGET"
    chcon u:object_r:system_file:s0 "$FRIDA_CLI_TARGET" 2>/dev/null || true
fi

if [ -f "$MODPATH/frida-gadget.so" ]; then
    ui_print "- Installing Frida Gadget to persistent storage..."
    cp -f "$MODPATH/frida-gadget.so" "/data/adb/reshift/frida-gadget.so"
    set_perm "/data/adb/reshift/frida-gadget.so" 0 0 0644
    chcon u:object_r:system_file:s0 "/data/adb/reshift/frida-gadget.so" 2>/dev/null || true
fi

ui_print "- ReShift Module installation completed successfully"


