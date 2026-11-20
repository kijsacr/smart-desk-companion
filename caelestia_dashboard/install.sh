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

echo "Installing full Smart Desk Companion Caelestia dashboard into: $TARGET_DIR"

if [ -f "$TARGET_DIR/Content.qml" ] && [ ! -f "$TARGET_DIR/Content.qml.bak" ]; then
    echo "Backing up original Content.qml -> Content.qml.bak"
    $USE_SUDO cp "$TARGET_DIR/Content.qml" "$TARGET_DIR/Content.qml.bak"
fi

# Copy all top-level dashboard QML files
for qml_file in \
    Content.qml \
    Dash.qml \
    Media.qml \
    Performance.qml \
    PhoneNotificationsTab.qml \
    PhoneTasksTab.qml \
    Tabs.qml \
    WeatherTab.qml \
    Wrapper.qml; do
    if [ -f "$SCRIPT_DIR/$qml_file" ]; then
        $USE_SUDO cp "$SCRIPT_DIR/$qml_file" "$TARGET_DIR/$qml_file"
    fi
done

# Copy dashboard sub-modules (dash, media, performance) if present
for subdir in dash media performance; do
    if [ -d "$SCRIPT_DIR/$subdir" ]; then
        $USE_SUDO mkdir -p "$TARGET_DIR/$subdir"
        $USE_SUDO cp -r "$SCRIPT_DIR/$subdir/"* "$TARGET_DIR/$subdir/"
    fi
done

# Restart any previous background desktop-notification daemon so it picks up the latest logic
pkill -f "smartdesk_notif_daemon.lock" 2>/dev/null || true

echo "Done! Full Caelestia dashboard installed."
echo "Reload Quickshell / Caelestia to apply:"
echo "  killall quickshell; caelestia shell -d"
