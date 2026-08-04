#!/system/bin/sh

# ReShift Frida Server Customization Script
# Compatible with Magisk, KernelSU, and APatch

SKIPUNZIP=0

# Zeige Info während der Installation
ui_print "- Configured for ReShift Snakeloader"
ui_print "- Checking environment..."

if [ "$KSU" = "true" ]; then
    ui_print "- Environment: KernelSU detected"
elif [ "$APATCH" = "true" ]; then
    ui_print "- Environment: APatch detected"
else
    ui_print "- Environment: Magisk detected"
fi

FRIDA_TARGET="/data/local/tmp/frida-server"
FRIDA_INJECT_TARGET="/data/local/tmp/frida-inject"
FRIDA_CLI_TARGET="/data/local/tmp/frida"
FRIDA_MODULE_BINARY="$MODPATH/frida-server"
FRIDA_MODULE_INJECT="$MODPATH/frida-inject"
FRIDA_MODULE_CLI="$MODPATH/frida"

ui_print "- Setting up permissions..."

# Stelle sicher, dass service.sh ausführbar ist
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755

# Zygisk Setup
if [ -d "$MODPATH/zygisk" ]; then
    ui_print "- Setting up Zygisk native components..."
    set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
fi

# Optional: Install binaries if included in module
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
        ui_print "- Existing $name found, updating permissions"
        chmod 755 "$dest"
        chown root:shell "$dest"
    else
        ui_print "- No $name found in module or system"
    fi
}

install_binary "$FRIDA_MODULE_BINARY" "$FRIDA_TARGET" "frida-server"
install_binary "$FRIDA_MODULE_INJECT" "$FRIDA_INJECT_TARGET" "frida-inject"
install_binary "$FRIDA_MODULE_CLI" "$FRIDA_CLI_TARGET" "frida"

# Install Frida Gadget if present
if [ -f "$MODPATH/frida-gadget.so" ]; then
    ui_print "- Installing Frida Gadget to persistent storage..."
    mkdir -p /data/adb/reshift
    cp "$MODPATH/frida-gadget.so" "/data/adb/reshift/frida-gadget.so"
    set_perm "/data/adb/reshift/frida-gadget.so" 0 0 0644
fi

ui_print "- Installation successful"
