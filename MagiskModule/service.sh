#!/system/bin/sh

MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "." ] && MODDIR="/data/adb/modules/snakeloader_frida"

log_info() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] INFO: $1" >> "$LOG_FILE"
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1" >> "$LOG_FILE"
}

# Wait for boot completion across Magisk, KernelSU, KSU Next & APatch
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 3
done

until [ -d "/data/adb" ]; do
  sleep 2
done

CONFIG_FILE_STEALTH="/data/adb/reshift/config.sh"
CONFIG_FILE_LEGACY="/data/local/tmp/config.sh"

if [ -f "$CONFIG_FILE_STEALTH" ]; then
    . "$CONFIG_FILE_STEALTH"
elif [ -f "$CONFIG_FILE_LEGACY" ]; then
    . "$CONFIG_FILE_LEGACY"
fi

[ -z "$STEALTH_MODE" ] && STEALTH_MODE=0

if [ "$STEALTH_MODE" -eq 1 ]; then
    LOG_FILE="/data/adb/reshift/frida_boot.log"
    mkdir -p /data/adb/reshift
    mkdir -p /data/adb/reshift/scripts
    chmod 755 /data/adb/reshift/scripts
else
    LOG_FILE="/data/local/tmp/frida_boot.log"
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] --- ReShift Boot Service (Stealth: $STEALTH_MODE) ---" > "$LOG_FILE"

if [ "$STEALTH_MODE" -eq 1 ]; then
    log_info "Stealth Mode ACTIVE: Config loaded for $FRIDA_SERVER_NAME on port $FRIDA_PORT"
else
    [ -z "$FRIDA_SERVER_NAME" ] && FRIDA_SERVER_NAME="frida-server"
    [ -z "$FRIDA_PORT" ] && FRIDA_PORT=27042
    [ -z "$FRIDA_PATH" ] && FRIDA_PATH="/data/adb/reshift/frida-server"
    log_info "Stealth Mode INACTIVE: Using standard parameters ($FRIDA_SERVER_NAME:$FRIDA_PORT)"
fi

# Sync runtime-managed binaries between /data/adb/reshift and /data/local/tmp
for BIN in frida-server frida-inject frida; do
    if [ -f "/data/adb/reshift/$BIN" ]; then
        chmod 755 "/data/adb/reshift/$BIN"
        restorecon "/data/adb/reshift/$BIN" 2>/dev/null || true
        if [ ! -f "/data/local/tmp/$BIN" ] || [ "/data/adb/reshift/$BIN" -nt "/data/local/tmp/$BIN" ]; then
            cp -f "/data/adb/reshift/$BIN" "/data/local/tmp/$BIN"
            chmod 755 "/data/local/tmp/$BIN"
            chown root:shell "/data/local/tmp/$BIN" 2>/dev/null || chown 0:2000 "/data/local/tmp/$BIN" 2>/dev/null
            restorecon "/data/local/tmp/$BIN" 2>/dev/null || true
            log_info "Synced $BIN from /data/adb/reshift/ to /data/local/tmp/$BIN"
        fi
    elif [ -f "$MODDIR/$BIN" ]; then
        if [ ! -f "/data/local/tmp/$BIN" ] || [ "$MODDIR/$BIN" -nt "/data/local/tmp/$BIN" ]; then
            cp -f "$MODDIR/$BIN" "/data/local/tmp/$BIN"
            chmod 755 "/data/local/tmp/$BIN"
            chown root:shell "/data/local/tmp/$BIN" 2>/dev/null || chown 0:2000 "/data/local/tmp/$BIN" 2>/dev/null
            restorecon "/data/local/tmp/$BIN" 2>/dev/null || true
            log_info "Synced $BIN from module to /data/local/tmp/$BIN"
        fi
    fi
done

# Ensure frida CLI alias is present
if [ ! -f "/data/adb/reshift/frida" ] && [ -f "/data/adb/reshift/frida-inject" ]; then
    cp -f "/data/adb/reshift/frida-inject" "/data/adb/reshift/frida"
    chmod 755 "/data/adb/reshift/frida"
    restorecon "/data/adb/reshift/frida" 2>/dev/null || true
    log_info "Created CLI alias /data/adb/reshift/frida from frida-inject"
fi

check_binary() {
    local path=$1
    local name=$2
    if [ -f "$path" ]; then
        chmod 755 "$path"
        chown root:shell "$path" 2>/dev/null || chown 0:2000 "$path" 2>/dev/null
        restorecon "$path" 2>/dev/null || true
        log_info "$name verified at $path"
        return 0
    fi
    return 1
}

if ! check_binary "$FRIDA_PATH" "Server"; then
    log_error "Frida server not found at $FRIDA_PATH. Searching fallback paths..."
    for ALT_PATH in "/data/adb/reshift/frida-server" "/data/adb/reshift/$FRIDA_SERVER_NAME" "$MODDIR/frida-server" "/data/local/tmp/frida-server"; do
        if check_binary "$ALT_PATH" "Server fallback"; then
            FRIDA_PATH="$ALT_PATH"
            break
        fi
    done
    [ ! -f "$FRIDA_PATH" ] && log_error "FATAL: No Frida server binary found. Download via ReShift App Downloader." && exit 1
fi

CLI_PATH="/data/adb/reshift/frida"
if ! check_binary "$CLI_PATH" "Frida CLI"; then
    log_info "CLI binary not found at $CLI_PATH. Checking inject fallback..."
    CLI_PATH="/data/adb/reshift/frida-inject"
    if ! check_binary "$CLI_PATH" "CLI fallback"; then
        CLI_PATH="/data/local/tmp/frida"
        if ! check_binary "$CLI_PATH" "Local tmp CLI"; then
            log_info "No CLI binary found. Local RPC functionality will use JNI / direct attach."
        fi
    fi
fi

# Clean up existing instance if port is already bound
if netstat -tuln 2>/dev/null | grep -q ":$FRIDA_PORT " || ss -tuln 2>/dev/null | grep -q ":$FRIDA_PORT "; then
    log_info "Port $FRIDA_PORT is active. Checking for existing frida instances..."
    EXISTING_PID=$(pgrep -f "$FRIDA_SERVER_NAME")
    if [ -n "$EXISTING_PID" ]; then
        log_info "Terminating previous instance (PID: $EXISTING_PID)..."
        kill -9 $EXISTING_PID
        sleep 1
    fi
fi

log_info "Launching $FRIDA_SERVER_NAME on port $FRIDA_PORT..."

setsid nohup "$FRIDA_PATH" -l 0.0.0.0:"$FRIDA_PORT" > /dev/null 2>&1 &

sleep 3
FINAL_PID=$(pgrep -f "$FRIDA_SERVER_NAME")
if [ -n "$FINAL_PID" ]; then
    log_info "Frida server running cleanly (PID $FINAL_PID)"
else
    log_error "Frida server start check warning. Will be auto-spawned on demand by ReShift app."
fi
