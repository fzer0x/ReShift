#!/system/bin/sh

log_info() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] INFO: $1" >> $LOG_FILE
}

log_error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1" >> $LOG_FILE
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 5
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

echo "[$(date '+%Y-%m-%d %H:%M:%S')] --- Boot service started (Stealth: $STEALTH_MODE) ---" > $LOG_FILE

if [ "$STEALTH_MODE" -eq 1 ]; then
    log_info "Stealth Mode ACTIVE: Loaded config for $FRIDA_SERVER_NAME on port $FRIDA_PORT"
else
    [ -z "$FRIDA_SERVER_NAME" ] && FRIDA_SERVER_NAME="frida-server"
    [ -z "$FRIDA_PORT" ] && FRIDA_PORT=27042
    [ -z "$FRIDA_PATH" ] && FRIDA_PATH="/data/local/tmp/frida-server"
    log_info "Stealth Mode INACTIVE: Using standard parameters ($FRIDA_SERVER_NAME:$FRIDA_PORT)"
fi

check_binary() {
    local path=$1
    local name=$2
    if [ -f "$path" ]; then
        chmod 755 "$path"
        chown root:shell "$path"
        log_info "$name found and permissioned at $path"
        return 0
    fi
    return 1
}

if ! check_binary "$FRIDA_PATH" "Server"; then
    log_error "Frida server not found at $FRIDA_PATH. Searching fallback..."
    for ALT_PATH in "/data/local/tmp/frida-server" "/data/adb/reshift/frida-server" "/data/adb/reshift/$FRIDA_SERVER_NAME"; do
        if check_binary "$ALT_PATH" "Server fallback"; then
            FRIDA_PATH="$ALT_PATH"
            break
        fi
    done
    [ ! -f "$FRIDA_PATH" ] && log_error "FATAL: No Frida server binary found" && exit 1
fi

CLI_PATH="/data/local/tmp/frida"
if ! check_binary "$CLI_PATH" "Frida CLI"; then
    log_info "CLI binary not found at $CLI_PATH. Checking inject fallback..."
    CLI_PATH="/data/local/tmp/frida-inject"
    if ! check_binary "$CLI_PATH" "CLI fallback"; then
        log_error "No CLI-capable binary found. RPC functionality will be unavailable."
    fi
fi

if netstat -tuln | grep -q ":$FRIDA_PORT "; then
    log_info "Port $FRIDA_PORT is already in use. Checking if it's an old frida instance..."
    EXISTING_PID=$(pgrep -f "$FRIDA_SERVER_NAME")
    if [ ! -z "$EXISTING_PID" ]; then
        log_info "Existing instance found (PID: $EXISTING_PID), restarting..."
        kill -9 $EXISTING_PID
        sleep 1
    fi
fi

log_info "Starting $FRIDA_SERVER_NAME on port $FRIDA_PORT..."

setsid nohup "$FRIDA_PATH" -l 0.0.0.0:"$FRIDA_PORT" > /dev/null 2>&1 &

sleep 3
FINAL_PID=$(pgrep -f "$FRIDA_SERVER_NAME")
if [ ! -z "$FINAL_PID" ]; then
    log_info "Frida server successfully started with PID $FINAL_PID"

    if [ -d "/data/adb/modules/zygisk-frida" ]; then
        log_info "ZygiskFrida module detected, ensuring compatibility..."
    fi
else
    log_error "Frida server failed to start. Check binary compatibility."
fi
