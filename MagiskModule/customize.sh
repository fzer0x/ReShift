#!/system/bin/sh

SKIPUNZIP=0

ui_print "- Configured for ReShift"
ui_print "- Checking environment..."

if [ "$KSU_NEXT" = "true" ]; then
    ui_print "- Environment: KernelSU Next detected (v${KSU_VER:-1.0})"
elif [ "$KSU" = "true" ]; then
    ui_print "- Environment: KernelSU detected (v${KSU_VER:-0.9})"
elif [ "$APATCH" = "true" ]; then
    ui_print "- Environment: APatch detected (v${APATCH_VER:-1.0})"
elif [ -n "$MAGISK_VER" ]; then
    ui_print "- Environment: Magisk detected (v$MAGISK_VER)"
else
    ui_print "- Environment: Magisk / KernelSU Compatible Root"
fi

FRIDA_TARGET="/data/local/tmp/frida-server"
FRIDA_INJECT_TARGET="/data/local/tmp/frida-inject"
FRIDA_CLI_TARGET="/data/local/tmp/frida"
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

install_binary() {
    local src=$1
    local dest=$2
    local name=$3
    if [ -f "$src" ]; then
        ui_print "- Installing $name from module..."
        mkdir -p /data/local/tmp
        cp "$src" "$dest"
        chmod 755 "$dest"
        chown root:shell "$dest"
        ui_print "- $name installed successfully"
    elif [ -f "$dest" ]; then
        ui_print "- Existing $name found at $dest, updating permissions"
        chmod 755 "$dest"
        chown root:shell "$dest"
    else
        ui_print "- Notice: $name not found in module source, using runtime deployment"
    fi
}

install_binary "$FRIDA_MODULE_BINARY" "$FRIDA_TARGET" "frida-server"
install_binary "$FRIDA_MODULE_INJECT" "$FRIDA_INJECT_TARGET" "frida-inject"
install_binary "$FRIDA_MODULE_CLI" "$FRIDA_CLI_TARGET" "frida"

if [ -f "$MODPATH/frida-gadget.so" ]; then
    ui_print "- Installing Frida Gadget to persistent storage..."
    mkdir -p /data/adb/reshift
    cp "$MODPATH/frida-gadget.so" "/data/adb/reshift/frida-gadget.so"
    set_perm "/data/adb/reshift/frida-gadget.so" 0 0 0644
fi

ui_print "- ReShift Module installation completed successfully"
