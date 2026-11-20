#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYSTEM_DASH_DIR="/etc/xdg/quickshell/caelestia/modules/dashboard"
USER_DASH_DIR="${HOME}/.config/quickshell/caelestia/modules/dashboard"

if [ -d "$USER_DASH_DIR" ]; then
    TARGET_DIR="$USER_DASH_DIR"
    USE_SUDO=""
elif [ -d "$SYSTEM_DASH_DIR" ]; then
    TARGET_DIR="$SYSTEM_DASH_DIR"
    if [ "$(id -u)" -ne 0 ]; then
        USE_SUDO="sudo"
    else
        USE_SUDO=""
    fi
else
    echo "Error: Could not find Caelestia dashboard directory at:"
    echo "  - $USER_DASH_DIR"
    echo "  - $SYSTEM_DASH_DIR"
    exit 1
fi

echo "Updating ONLY PhoneNotificationsTab.qml in: $TARGET_DIR"
$USE_SUDO cp "$SCRIPT_DIR/PhoneNotificationsTab.qml" "$TARGET_DIR/PhoneNotificationsTab.qml"

# Restart any previous background desktop-notification daemon so it picks up the latest logic
pkill -f "smartdesk_notif_daemon.lock" 2>/dev/null || true

echo "Done! Only PhoneNotificationsTab.qml was updated."
echo "Reload Quickshell / Caelestia to apply:"
echo "  killall quickshell; caelestia shell -d"
