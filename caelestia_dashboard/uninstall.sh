#!/usr/bin/env bash
set -euo pipefail

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
    echo "Error: Could not find Caelestia dashboard directory."
    exit 1
fi

echo "Restoring original Caelestia dashboard in: $TARGET_DIR"

if [ -f "$TARGET_DIR/Content.qml.bak" ]; then
    $USE_SUDO mv "$TARGET_DIR/Content.qml.bak" "$TARGET_DIR/Content.qml"
fi

$USE_SUDO rm -f "$TARGET_DIR/PhoneNotificationsTab.qml" "$TARGET_DIR/PhoneTasksTab.qml"

echo "Restored! Restart Quickshell / Caelestia to apply."
