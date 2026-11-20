pragma ComponentBehavior: Bound

import QtQuick
import QtQuick.Layouts
import Quickshell
import Quickshell.Io
import Quickshell.Widgets
import M3Shapes
import Caelestia.Config
import Caelestia.I18n
import qs.components
import qs.components.controls
import qs.components.containers
import qs.services

Item {
    id: root

    property string databaseUrl: "https://smart-desk-companion-cf7fd-default-rtdb.asia-southeast1.firebasedatabase.app"
    property string notificationsNode: "notifications"

    readonly property string cacheFilePath: "/tmp/smartdesk_phone_notifs_cache.json"
    readonly property string seenFilePath: "/tmp/smartdesk_phone_notifs_seen.json"
    readonly property string iconDirPath: "/tmp/smartdesk_app_icons"

    property var notificationsList: []
    property string lastPayloadFingerprint: ""
    property bool initialBatchRendered: false
    property bool isLoading: false
    property bool isConnected: true
    property string statusText: Tr.tr("Synced with Firebase")

    implicitWidth: 840
    implicitHeight: 420

    // Persistent in-memory cache across tab switches & dashboard open/close (0ms startup!)
    PersistentProperties {
        id: persistentCache

        property string cachedJson: ""

        reloadableId: "smartdesk_phone_notifications_v3"
    }

    // Disk cache watched live (updated both by QML and the background desktop-notification daemon)
    FileView {
        id: diskCacheView

        printErrors: false
        path: root.cacheFilePath
        watchChanges: true
        onFileChanged: reload()
        onLoaded: {
            const raw = text();
            if (!raw || !raw.trim())
                return;
            try {
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed)) {
                    root.applyIncomingItems(parsed, false);
                }
            } catch (e) {}
        }
    }

    // Crisp vector SVG logos for phone apps (rendered natively by Qt SVG in the dashboard & exported for notify-send)
    readonly property var appLogoSvgs: ({
        "whatsapp": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#25D366"/><path fill="#FFFFFF" d="M32.1 12C21.1 12 12.2 20.9 12.2 31.8c0 3.5.9 6.9 2.7 9.9L12 52l10.6-2.8c2.9 1.6 6.1 2.4 9.5 2.4 11 0 19.9-8.9 19.9-19.8C52 20.9 43.1 12 32.1 12zm11.6 28.1c-.5 1.4-2.8 2.6-4 2.8-1.1.2-2.5.3-4-.2-.9-.3-2.1-.7-3.6-1.4-6.4-2.8-10.6-9.2-10.9-9.6-.3-.4-2.6-3.5-2.6-6.6 0-3.1 1.6-4.7 2.2-5.3.6-.6 1.3-.8 1.7-.8.4 0 .8 0 1.2.1.4 0 .9-.1 1.4 1.1.5 1.3 1.8 4.4 2 4.7.2.3.3.7.1 1.1-.2.4-.3.7-.6 1-.3.4-.7.8-1 1.1-.3.3-.7.7-.3 1.3.4.6 1.7 2.8 3.6 4.5 2.5 2.2 4.6 2.9 5.2 3.2.6.3 1 .2 1.4-.2.4-.4 1.6-1.9 2-2.5.4-.6.9-.5 1.5-.3.6.2 3.8 1.8 4.4 2.1.6.3 1.1.5 1.2.8.2.3.2 1.7-.3 3.1z"/></svg>`,
        "instagram": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><defs><linearGradient id="ig" x1="0%" y1="100%" x2="100%" y2="0%"><stop offset="0%" stop-color="#FEDA75"/><stop offset="25%" stop-color="#FA7E1E"/><stop offset="50%" stop-color="#D62976"/><stop offset="75%" stop-color="#962FBF"/><stop offset="100%" stop-color="#4F5BD5"/></linearGradient></defs><rect width="64" height="64" rx="16" fill="url(#ig)"/><rect x="14" y="14" width="36" height="36" rx="10" fill="none" stroke="#FFFFFF" stroke-width="3.8"/><circle cx="32" cy="32" r="8.8" fill="none" stroke="#FFFFFF" stroke-width="3.8"/><circle cx="42.5" cy="21.5" r="2.4" fill="#FFFFFF"/></svg>`,
        "gmail": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#FFFFFF"/><path fill="#4285F4" d="M14 23.5v22c0 1.9 1.6 3.5 3.5 3.5H21V30.8l-7-5.3z"/><path fill="#34A853" d="M43 49h3.5c1.9 0 3.5-1.6 3.5-3.5v-22l-7 5.3V49z"/><path fill="#EA4335" d="M43 20.2L32 28.6 21 20.2V16l11 8.4L43 16v4.2z"/><path fill="#C5221F" d="M14 20.3v3.2l7 5.3V20.2l-3.1-2.4c-2.1-1.6-3.9-.2-3.9 2.5z"/><path fill="#FBBC04" d="M50 20.3c0-2.7-1.8-4.1-3.9-2.5L43 20.2v8.6l7-5.3v-3.2z"/></svg>`,
        "calendar": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#FFFFFF"/><path fill="#4285F4" d="M15 15h24v10H15z"/><path fill="#EA4335" d="M39 15h10v10H39z"/><path fill="#FBBC04" d="M39 25h10v14H39z"/><path fill="#34A853" d="M15 39h24v10H15z"/><path fill="#1967D2" d="M15 25h10v14H15z"/><path fill="#34A853" d="M39 39h10l-10 10z"/><text x="32" y="37" text-anchor="middle" font-family="sans-serif" font-weight="bold" font-size="14" fill="#1A73E8">31</text></svg>`,
        "messages": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#1A73E8"/><path fill="#8AB4F8" d="M22 17h24c2.8 0 5 2.2 5 5v14c0 2.8-2.2 5-5 5H27l-5 5V22c0-2.8 2.2-5 5-5z"/><path fill="#FFFFFF" d="M18 22h24c2.8 0 5 2.2 5 5v13c0 2.8-2.2 5-5 5H23l-7 6V27c0-2.8 2.2-5 5-5z"/><rect x="23" y="29" width="14" height="2.6" rx="1.3" fill="#1A73E8"/><rect x="23" y="34.5" width="10" height="2.6" rx="1.3" fill="#1A73E8"/></svg>`,
        "phone": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#00C853"/><path fill="#FFFFFF" d="M44.7 38.6c-2.4 0-4.7-.4-6.9-1.1-.7-.2-1.5-.1-2 .5l-4.4 4.4c-5.6-2.9-10.3-7.5-13.2-13.2l4.4-4.4c.6-.6.7-1.4.5-2.1-.7-2.2-1.1-4.5-1.1-6.9 0-1.1-.9-2-2-2H14c-1.1 0-2 .9-2 2 0 18.8 15.2 34 34 34 1.1 0 2-.9 2-2v-6.1c0-1.2-.9-2.1-2-2.1z"/></svg>`,
        "telegram": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#2AABEE"/><path fill="#FFFFFF" d="M46.9 18.6L14.2 31.2c-2.2.9-2.2 2.1-.4 2.7l8.4 2.6 19.4-12.2c.9-.6 1.8-.3 1.1.4L27 38.9l-.6 8.6c.9 0 1.3-.4 1.8-.9l4.1-4 8.5 6.3c1.6.9 2.7.4 3.1-1.5l5.5-26c.6-2.3-.9-3.3-2.5-2.8z"/></svg>`,
        "discord": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#5865F2"/><path fill="#FFFFFF" d="M44.8 21.3c-2.4-1.1-5-1.9-7.7-2.4-.3.6-.7 1.4-1 2-2.9-.4-5.8-.4-8.6 0-.3-.6-.6-1.4-1-2-2.7.5-5.3 1.3-7.7 2.4-4.9 7.3-6.2 14.4-5.5 21.4 3.2 2.4 6.4 3.8 9.5 4.8.8-1 1.5-2.1 2.1-3.3-1.1-.4-2.2-1-3.2-1.6.3-.2.5-.4.8-.6 6.1 2.8 12.8 2.8 18.8 0 .3.2.5.4.8.6-1 .6-2.1 1.2-3.2 1.6.6 1.2 1.3 2.3 2.1 3.3 3.1-1 6.3-2.4 9.5-4.8.8-8.1-1.3-15.1-5.7-21.4zM25.7 38.4c-1.8 0-3.3-1.7-3.3-3.7s1.5-3.7 3.3-3.7c1.9 0 3.4 1.7 3.3 3.7 0 2-1.5 3.7-3.3 3.7zm12.6 0c-1.8 0-3.3-1.7-3.3-3.7s1.5-3.7 3.3-3.7c1.9 0 3.4 1.7 3.3 3.7 0 2-1.4 3.7-3.3 3.7z"/></svg>`,
        "youtube": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#FF0000"/><path fill="#FFFFFF" d="M26 22v20l17-10z"/></svg>`,
        "swiggy": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#FC8019"/><path fill="#FFFFFF" d="M32 13c-8.3 0-15 6.5-15 14.5 0 10.2 13.1 22.4 14.1 23.3.5.4 1.3.4 1.8 0 1-.9 14.1-13.1 14.1-23.3C47 19.5 40.3 13 32 13zm0 19.5c-3 0-5.5-2.5-5.5-5.5S29 21.5 32 21.5s5.5 2.5 5.5 5.5-2.5 5.5-5.5 5.5z"/></svg>`,
        "zomato": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#E23744"/><text x="32" y="42" text-anchor="middle" font-family="sans-serif" font-weight="900" font-style="italic" font-size="30" fill="#FFFFFF">Z</text></svg>`,
        "otp": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#F59E0B"/><path fill="#FFFFFF" d="M37 22c-5.5 0-10 4.5-10 10 0 1.4.3 2.7.8 3.9L16 47.7V53h6v-4h4v-4h4l2.1-2.1c1.5.7 3.1 1.1 4.9 1.1 5.5 0 10-4.5 10-10s-4.5-12-10-12zm3 11c-1.7 0-3-1.3-3-3s1.3-3 3-3 3 1.3 3 3-1.3 3-3 3z"/></svg>`,
        "default": `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="#5C6BC0"/><path fill="#FFFFFF" d="M32 50c2.2 0 4-1.8 4-4h-8c0 2.2 1.8 4 4 4zm12-12V28c0-6.1-3.3-11.3-9-12.6V14c0-1.7-1.3-3-3-3s-3 1.3-3 3v1.4c-5.7 1.3-9 6.5-9 12.6v10l-4 4v2h32v-2l-4-4z"/></svg>`
    })

    function cleanBaseUrl(): string {
        return root.databaseUrl.replace(/\/+$/, "");
    }

    function resolveAppKey(pkg: string, isOtp: bool, source: string): string {
        const p = (pkg || "").toLowerCase();
        const s = (source || "").toLowerCase();
        if (p.indexOf("whatsapp") !== -1 || s.indexOf("whatsapp") !== -1)
            return "whatsapp";
        if (p.indexOf("instagram") !== -1 || s.indexOf("instagram") !== -1)
            return "instagram";
        if (p.indexOf("calendar") !== -1)
            return "calendar";
        if (p.indexOf("gm") !== -1 || p.indexOf("gmail") !== -1)
            return "gmail";
        if (p.indexOf("telegram") !== -1)
            return "telegram";
        if (p.indexOf("discord") !== -1)
            return "discord";
        if (p.indexOf("youtube") !== -1)
            return "youtube";
        if (p.indexOf("swiggy") !== -1 || s.indexOf("swiggy") !== -1)
            return "swiggy";
        if (p.indexOf("zomato") !== -1 || s.indexOf("zomato") !== -1)
            return "zomato";
        if (p.indexOf("messaging") !== -1 || p.indexOf("mms") !== -1 || p.indexOf("sms") !== -1)
            return "messages";
        if (p.indexOf("dialer") !== -1 || p.indexOf("incallui") !== -1 || p.indexOf("telecom") !== -1 || p.indexOf("phone") !== -1)
            return "phone";
        if (isOtp)
            return "otp";
        return "default";
    }

    function getLogoDataUri(appKey: string): string {
        const svg = root.appLogoSvgs[appKey] || root.appLogoSvgs["default"];
        return "data:image/svg+xml;utf8," + encodeURIComponent(svg);
    }

    function appDisplayName(pkg: string, isOtp: bool, source: string): string {
        if (isOtp)
            return source ? `OTP • ${source}` : "OTP Alert";
        const p = (pkg || "").toLowerCase();
        if (p.indexOf("whatsapp") !== -1)
            return "WhatsApp";
        if (p.indexOf("instagram") !== -1)
            return "Instagram";
        if (p.indexOf("calendar") !== -1)
            return "Calendar";
        if (p.indexOf("gm") !== -1 || p.indexOf("gmail") !== -1)
            return "Gmail";
        if (p.indexOf("telegram") !== -1)
            return "Telegram";
        if (p.indexOf("discord") !== -1)
            return "Discord";
        if (p.indexOf("youtube") !== -1)
            return "YouTube";
        if (p.indexOf("swiggy") !== -1)
            return "Swiggy";
        if (p.indexOf("zomato") !== -1)
            return "Zomato";
        if (p.indexOf("messaging") !== -1 || p.indexOf("mms") !== -1 || p.indexOf("sms") !== -1)
            return "Messages";
        if (p.indexOf("dialer") !== -1 || p.indexOf("incallui") !== -1 || p.indexOf("telecom") !== -1 || p.indexOf("phone") !== -1)
            return "Phone Call";
        const parts = (pkg || "App").split(".");
        const last = parts[parts.length - 1] || "Notification";
        return last.charAt(0).toUpperCase() + last.slice(1);
    }

    function formatOtpCode(rawCode: string): string {
        const digits = (rawCode || "").replace(/[^0-9]/g, "");
        if (digits.length === 6)
            return digits.slice(0, 3) + " " + digits.slice(3, 6);
        if (digits.length === 8)
            return digits.slice(0, 4) + " " + digits.slice(4, 8);
        return digits || rawCode;
    }

    function computeFingerprint(items: var): string {
        if (!Array.isArray(items) || items.length === 0)
            return "empty";
        const parts = [];
        for (let i = 0; i < items.length; i++) {
            const it = items[i];
            parts.push(`${it.id}:${it.epochMillis}:${it.title}:${it.message}`);
        }
        return parts.join("|");
    }

    function parseNotificationsPayload(data: var): var {
        if (!data || typeof data !== "object")
            return [];

        const items = [];
        let fallbackIdx = 0;

        function toItem(key: string, obj: var): var {
            if (!obj || typeof obj !== "object")
                return null;
            const pkg = String(obj.packageName || "");
            if (pkg.toLowerCase().indexOf("spotify") !== -1)
                return null;

            const isOtp = obj.isOtp === true;
            const source = String(obj.source || "");
            const otp = String(obj.otp || "");
            const sentAt = Number(obj.sentAt || 0);
            const epochMillis = Number(obj.epochMillis || sentAt || (++fallbackIdx));
            const rawTitle = String(obj.title || "");
            const rawMessage = String(obj.message || "");
            const timestamp = String(obj.timestamp || "");

            const title = isOtp ? (source || rawTitle || "OTP") : rawTitle;
            const message = isOtp ? formatOtpCode(otp || rawMessage) : rawMessage;

            if (!title.trim() && !message.trim())
                return null;

            const appKey = resolveAppKey(pkg, isOtp, source || title);

            return {
                id: key,
                packageName: pkg || "android.system",
                title: title || "Notification",
                message: message,
                timestamp: timestamp,
                epochMillis: epochMillis,
                isOtp: isOtp,
                source: source || title,
                otp: otp || (isOtp ? rawMessage : ""),
                appKey: appKey,
                appName: appDisplayName(pkg, isOtp, source || title),
                logoDataUri: getLogoDataUri(appKey)
            };
        }

        if (data.hasOwnProperty("packageName") && data.hasOwnProperty("title")) {
            const single = toItem("root", data);
            if (single)
                items.push(single);
        } else {
            const keys = Object.keys(data);
            for (let i = 0; i < keys.length; i++) {
                const k = keys[i];
                const parsed = toItem(k, data[k]);
                if (parsed)
                    items.push(parsed);
            }
        }

        items.sort((a, b) => b.epochMillis - a.epochMillis);
        return items;
    }

    function applyIncomingItems(items: var, saveToDisk: bool): void {
        if (!Array.isArray(items))
            return;

        // Build lookup of existing items by ID so unchanged items preserve JS object reference
        // (keeps existing cards stable on screen while only newly arrived cards animate in!)
        const existingById = {};
        for (let i = 0; i < root.notificationsList.length; i++) {
            const ex = root.notificationsList[i];
            if (ex && ex.id) {
                existingById[ex.id] = ex;
            }
        }

        const hydrated = [];
        for (let i = 0; i < items.length; i++) {
            const it = items[i];
            if (!it || typeof it !== "object")
                continue;
            const idStr = String(it.id || "");
            const epochNum = Number(it.epochMillis || 0);
            const titleStr = String(it.title || "Notification");
            const msgStr = String(it.message || "");
            const prev = existingById[idStr];

            if (prev && prev.epochMillis === epochNum && prev.title === titleStr && prev.message === msgStr && prev.logoDataUri) {
                hydrated.push(prev);
            } else {
                const appKey = it.appKey || resolveAppKey(it.packageName || "", it.isOtp === true, it.source || titleStr);
                hydrated.push({
                    id: idStr,
                    packageName: String(it.packageName || ""),
                    title: titleStr,
                    message: msgStr,
                    timestamp: String(it.timestamp || ""),
                    epochMillis: epochNum,
                    isOtp: it.isOtp === true,
                    source: String(it.source || ""),
                    otp: String(it.otp || ""),
                    appKey: appKey,
                    appName: it.appName || appDisplayName(it.packageName || "", it.isOtp === true, it.source || titleStr),
                    logoDataUri: getLogoDataUri(appKey),
                    isLiveArrival: root.initialBatchRendered
                });
            }
        }

        const fp = computeFingerprint(hydrated);
        if (fp !== root.lastPayloadFingerprint) {
            root.lastPayloadFingerprint = fp;
            root.notificationsList = hydrated;
            root.initialBatchRendered = true;
            const serialized = JSON.stringify(hydrated);
            persistentCache.cachedJson = serialized;
            if (saveToDisk) {
                diskCacheView.setText(serialized);
            }
        }
        root.statusText = Tr.tr("Live • %1 active").arg(root.notificationsList.length);
    }

    function fetchNotifications(): void {
        const xhr = new XMLHttpRequest();
        const url = `${cleanBaseUrl()}/${root.notificationsNode}.json`;
        xhr.open("GET", url, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== XMLHttpRequest.DONE)
                return;
            root.isLoading = false;
            if (xhr.status >= 200 && xhr.status < 300) {
                root.isConnected = true;
                try {
                    const parsed = xhr.responseText && xhr.responseText.trim() ? JSON.parse(xhr.responseText) : null;
                    const items = root.parseNotificationsPayload(parsed);
                    root.applyIncomingItems(items, true);
                } catch (e) {
                    root.isConnected = false;
                    root.statusText = Tr.tr("Parse error");
                }
            } else {
                root.isConnected = false;
                root.statusText = Tr.tr("Offline (HTTP %1)").arg(xhr.status);
            }
        };
        root.isLoading = true;
        xhr.send();
    }

    function dismissNotification(notifId: string): void {
        if (!notifId)
            return;
        const remaining = root.notificationsList.filter(item => item.id !== notifId);
        root.applyIncomingItems(remaining, true);

        const xhr = new XMLHttpRequest();
        const targetPath = notifId === "root" ? `${cleanBaseUrl()}/${root.notificationsNode}.json` : `${cleanBaseUrl()}/${root.notificationsNode}/${encodeURIComponent(notifId)}.json`;
        xhr.open("DELETE", targetPath, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState === XMLHttpRequest.DONE)
                root.fetchNotifications();
        };
        xhr.send();
    }

    function dismissAllNotifications(): void {
        root.applyIncomingItems([], true);
        root.statusText = Tr.tr("Cleared all notifications");

        const xhr = new XMLHttpRequest();
        const url = `${cleanBaseUrl()}/${root.notificationsNode}.json`;
        xhr.open("DELETE", url, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState === XMLHttpRequest.DONE)
                root.fetchNotifications();
        };
        xhr.send();
    }

    /**
     * Ensures SVG app logo files exist in /tmp/smartdesk_app_icons and starts a lightweight
     * single-instance background Python watcher so phone notifications pop up as desktop
     * notifications AND keep the cache pre-warmed even while the top dashboard is closed.
     */
    function ensureBackgroundDesktopNotifier(): void {
        const pyScript = `
import os, sys, time, json, urllib.request, subprocess, fcntl

LOCK_PATH = "/tmp/smartdesk_notif_daemon.lock"
CACHE_PATH = "${root.cacheFilePath}"
SEEN_PATH = "${root.seenFilePath}"
ICON_DIR = "${root.iconDirPath}"
DB_URL = "${root.cleanBaseUrl()}/${root.notificationsNode}.json"

lock_fd = open(LOCK_PATH, "w")
try:
    fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
except IOError:
    sys.exit(0)

os.makedirs(ICON_DIR, exist_ok=True)
svgs = ${JSON.stringify(root.appLogoSvgs)}
for k, v in svgs.items():
    p = os.path.join(ICON_DIR, f"{k}.svg")
    if not os.path.exists(p):
        with open(p, "w") as f:
            f.write(v)

try:
    with open(SEEN_PATH, "r") as f:
        seen = set(json.load(f))
    first_run = False
except Exception:
    seen = set()
    first_run = True

def resolve_key(pkg, is_otp, source):
    p = (pkg or "").lower()
    s = (source or "").lower()
    if "whatsapp" in p or "whatsapp" in s: return "whatsapp"
    if "instagram" in p or "instagram" in s: return "instagram"
    if "calendar" in p: return "calendar"
    if "gm" in p or "gmail" in p: return "gmail"
    if "telegram" in p: return "telegram"
    if "discord" in p: return "discord"
    if "youtube" in p: return "youtube"
    if "swiggy" in p or "swiggy" in s: return "swiggy"
    if "zomato" in p or "zomato" in s: return "zomato"
    if "messaging" in p or "mms" in p or "sms" in p: return "messages"
    if "dialer" in p or "incallui" in p or "telecom" in p or "phone" in p: return "phone"
    if is_otp: return "otp"
    return "default"

def app_name(pkg, is_otp, source):
    if is_otp: return f"OTP • {source}" if source else "OTP Alert"
    p = (pkg or "").lower()
    if "whatsapp" in p: return "WhatsApp"
    if "instagram" in p: return "Instagram"
    if "calendar" in p: return "Calendar"
    if "gm" in p or "gmail" in p: return "Gmail"
    if "telegram" in p: return "Telegram"
    if "discord" in p: return "Discord"
    if "youtube" in p: return "YouTube"
    if "swiggy" in p: return "Swiggy"
    if "zomato" in p: return "Zomato"
    if "messaging" in p or "mms" in p or "sms" in p: return "Messages"
    if "dialer" in p or "incallui" in p or "telecom" in p or "phone" in p: return "Phone Call"
    last = (pkg or "App").split(".")[-1] or "Notification"
    return last[:1].upper() + last[1:]

def fmt_otp(code):
    d = "".join(ch for ch in (code or "") if ch.isdigit())
    if len(d) == 6: return d[:3] + " " + d[3:]
    if len(d) == 8: return d[:4] + " " + d[4:]
    return d or code

last_cache_str = ""
while True:
    try:
        req = urllib.request.Request(DB_URL, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=4) as resp:
            raw = resp.read().decode("utf-8").strip()
            data = json.loads(raw) if raw else None
        items = []
        if isinstance(data, dict):
            entries = [("root", data)] if ("packageName" in data and "title" in data) else list(data.items())
            idx = 0
            for k, obj in entries:
                if not isinstance(obj, dict): continue
                pkg = str(obj.get("packageName") or "")
                if "spotify" in pkg.lower(): continue
                is_otp = obj.get("isOtp") is True
                source = str(obj.get("source") or "")
                otp = str(obj.get("otp") or "")
                sent_at = int(obj.get("sentAt") or 0)
                idx += 1
                epoch = int(obj.get("epochMillis") or sent_at or idx)
                raw_t = str(obj.get("title") or "")
                raw_m = str(obj.get("message") or "")
                ts = str(obj.get("timestamp") or "")
                title = (source or raw_t or "OTP") if is_otp else raw_t
                msg = fmt_otp(otp or raw_m) if is_otp else raw_m
                if not title.strip() and not msg.strip(): continue
                akey = resolve_key(pkg, is_otp, source or title)
                aname = app_name(pkg, is_otp, source or title)
                items.append({
                    "id": str(k),
                    "packageName": pkg or "android.system",
                    "title": title or "Notification",
                    "message": msg,
                    "timestamp": ts,
                    "epochMillis": epoch,
                    "isOtp": is_otp,
                    "source": source or title,
                    "otp": otp or (raw_m if is_otp else ""),
                    "appKey": akey,
                    "appName": aname
                })
        items.sort(key=lambda x: x["epochMillis"], reverse=True)
        cache_str = json.dumps(items)
        if cache_str != last_cache_str:
            last_cache_str = cache_str
            tmp_c = CACHE_PATH + ".tmp"
            with open(tmp_c, "w") as f:
                f.write(cache_str)
            os.replace(tmp_c, CACHE_PATH)

        seen_dirty = False
        now_ms = int(time.time() * 1000)
        for it in items:
            sig = f"{it['id']}_{it['epochMillis']}"
            if sig not in seen:
                seen.add(sig)
                seen_dirty = True
                if not first_run or (it["epochMillis"] > 0 and abs(now_ms - it["epochMillis"]) < 25000):
                    icon_file = os.path.join(ICON_DIR, f"{it['appKey']}.svg")
                    summary = f"OTP • {it['title']}" if it["isOtp"] else f"{it['title']} ({it['appName']})"
                    body = f"Verification code: {it['message']}" if it["isOtp"] else it["message"]
                    urg = "critical" if it["isOtp"] else "normal"
                    subprocess.Popen([
                        "notify-send",
                        "-a", it["appName"],
                        "-u", urg,
                        "-i", icon_file,
                        "-h", f"STRING:image-path:{icon_file}",
                        summary,
                        body
                    ])
        first_run = False
        if seen_dirty:
            with open(SEEN_PATH, "w") as f:
                json.dump(list(seen)[-200:], f)
    except Exception:
        pass
    time.sleep(2.5)
`;
        Quickshell.execDetached(["python3", "-c", pyScript]);
    }

    Component.onCompleted: {
        // 1. Instantly populate notificationsList from in-memory PersistentProperties (0ms delay!)
        if (persistentCache.cachedJson) {
            try {
                const cached = JSON.parse(persistentCache.cachedJson);
                if (Array.isArray(cached) && cached.length > 0) {
                    root.applyIncomingItems(cached, false);
                }
            } catch (e) {}
        }

        // 2. Ensure background desktop notification watcher & SVG icons are active
        root.ensureBackgroundDesktopNotifier();

        // 3. Fetch latest state from Firebase
        root.fetchNotifications();
    }

    onVisibleChanged: {
        if (visible)
            root.fetchNotifications();
    }

    Timer {
        id: pollTimer

        interval: 2500
        repeat: true
        running: root.visible
        onTriggered: root.fetchNotifications()
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Tokens.padding.medium
        spacing: Tokens.spacing.medium

        // Top Header Bar
        RowLayout {
            Layout.fillWidth: true
            spacing: Tokens.spacing.medium

            StyledRect {
                implicitWidth: 42
                implicitHeight: 42
                radius: Tokens.rounding.medium
                color: Colours.palette.m3primaryContainer

                MaterialIcon {
                    anchors.centerIn: parent
                    text: "notifications_active"
                    fontStyle: Tokens.font.icon.large
                    color: Colours.palette.m3onPrimaryContainer
                    fill: 1
                }
            }

            ColumnLayout {
                spacing: 2

                RowLayout {
                    spacing: Tokens.spacing.small

                    StyledText {
                        text: Tr.tr("Phone Notifications")
                        font: Tokens.font.title.builders.medium.weight(Font.DemiBold).build()
                        color: Colours.palette.m3onSurface
                    }

                    StyledRect {
                        visible: root.notificationsList.length > 0
                        implicitWidth: countText.implicitWidth + Tokens.padding.small * 2
                        implicitHeight: countText.implicitHeight + 4
                        radius: Tokens.rounding.full
                        color: Colours.palette.m3primary

                        StyledText {
                            id: countText

                            anchors.centerIn: parent
                            text: String(root.notificationsList.length)
                            font: Tokens.font.body.builders.small.weight(Font.Bold).build()
                            color: Colours.palette.m3onPrimary
                        }
                    }
                }

                RowLayout {
                    spacing: Tokens.spacing.extraSmall

                    StyledRect {
                        implicitWidth: 8
                        implicitHeight: 8
                        radius: Tokens.rounding.full
                        color: root.isConnected ? Colours.palette.m3primary : Colours.palette.m3error
                    }

                    StyledText {
                        text: root.statusText
                        font: Tokens.font.body.small
                        color: Colours.palette.m3onSurfaceVariant
                    }
                }
            }

            Item {
                Layout.fillWidth: true
            }

            IconButton {
                icon: "refresh"
                type: IconButton.Tonal
                isRound: true
                onClicked: root.fetchNotifications()
            }

            IconTextButton {
                visible: root.notificationsList.length > 0
                icon: "clear_all"
                text: Tr.tr("Dismiss All")
                type: IconTextButton.Tonal
                onClicked: root.dismissAllNotifications()
            }
        }

        // Empty State vs Notification Feed
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            // Empty State
            Loader {
                anchors.centerIn: parent
                active: root.notificationsList.length === 0
                asynchronous: true

                sourceComponent: ColumnLayout {
                    spacing: Tokens.spacing.small

                    MaterialShape {
                        Layout.alignment: Qt.AlignHCenter
                        color: Colours.palette.m3surfaceContainerHigh
                        implicitSize: emptyIcon.implicitHeight + Tokens.padding.extraLarge * 1.6
                        shape: MaterialShape.ClamShell

                        MaterialIcon {
                            id: emptyIcon

                            anchors.centerIn: parent
                            text: "notifications_off"
                            fontStyle: Tokens.font.icon.builders.extraLarge.scale(1.6).build()
                            color: Colours.palette.m3onSurfaceVariant
                        }
                    }

                    StyledText {
                        Layout.alignment: Qt.AlignHCenter
                        text: Tr.tr("All caught up")
                        font: Tokens.font.title.large
                        color: Colours.palette.m3onSurface
                    }

                    StyledText {
                        Layout.alignment: Qt.AlignHCenter
                        text: Tr.tr("Incoming notifications from your phone will appear here")
                        font: Tokens.font.body.medium
                        color: Colours.palette.m3onSurfaceVariant
                    }
                }
            }

            // Scrollable Notification Cards
            ClippingRectangle {
                anchors.fill: parent
                visible: root.notificationsList.length > 0
                radius: Tokens.rounding.large
                color: "transparent"

                StyledFlickable {
                    id: notifFlickable

                    anchors.fill: parent
                    anchors.rightMargin: scrollBar.implicitWidth + Tokens.spacing.extraSmall
                    flickableDirection: Flickable.VerticalFlick
                    contentWidth: width
                    contentHeight: notifColumn.implicitHeight

                    ColumnLayout {
                        id: notifColumn

                        width: notifFlickable.width
                        spacing: Tokens.spacing.small

                        Repeater {
                            model: ScriptModel {
                                values: root.notificationsList
                                objectProp: "id"
                            }

                            delegate: StyledRect {
                                id: notifCard

                                required property int index
                                required property var modelData

                                readonly property bool isOtpCard: modelData.isOtp === true
                                property bool isDismissing: false
                                property real enterOpacity: 0
                                property real enterOffsetY: modelData.isLiveArrival ? -18 : 12
                                property real enterOffsetX: 0
                                property real enterScale: modelData.isLiveArrival ? 0.94 : 0.98

                                Layout.fillWidth: true
                                implicitHeight: cardRow.implicitHeight + Tokens.padding.medium * 2
                                radius: Tokens.rounding.large
                                color: isOtpCard ? Colours.palette.m3tertiaryContainer : Colours.tPalette.m3surfaceContainer

                                opacity: enterOpacity
                                scale: enterScale
                                transform: Translate {
                                    x: notifCard.enterOffsetX
                                    y: notifCard.enterOffsetY
                                }

                                Component.onCompleted: {
                                    if (modelData.isLiveArrival) {
                                        staggerTimer.interval = 10;
                                    } else {
                                        staggerTimer.interval = Math.min(index * 35, 160);
                                    }
                                    staggerTimer.start();
                                }

                                Timer {
                                    id: staggerTimer

                                    repeat: false
                                    onTriggered: {
                                        notifCard.enterOpacity = 1;
                                        notifCard.enterOffsetY = 0;
                                        notifCard.enterScale = 1.0;
                                    }
                                }

                                Timer {
                                    id: dismissFinishTimer

                                    interval: 180
                                    repeat: false
                                    onTriggered: root.dismissNotification(notifCard.modelData.id)
                                }

                                Behavior on enterOpacity {
                                    Anim {
                                        type: Anim.DefaultEffects
                                    }
                                }

                                Behavior on enterOffsetY {
                                    Anim {
                                        easing: Tokens.anim.emphasizedDecel
                                    }
                                }

                                Behavior on enterOffsetX {
                                    Anim {
                                        easing: Tokens.anim.emphasized
                                    }
                                }

                                Behavior on enterScale {
                                    Anim {
                                        easing: Tokens.anim.emphasizedDecel
                                    }
                                }

                                RowLayout {
                                    id: cardRow

                                    anchors.fill: parent
                                    anchors.margins: Tokens.padding.medium
                                    spacing: Tokens.spacing.medium

                                    // App Logo Badge with vector brand icon + optional OTP key corner badge
                                    Item {
                                        Layout.alignment: Qt.AlignTop
                                        implicitWidth: 46
                                        implicitHeight: 46

                                        Image {
                                            id: appLogoImg

                                            anchors.fill: parent
                                            source: notifCard.modelData.logoDataUri
                                            sourceSize: Qt.size(92, 92)
                                            fillMode: Image.PreserveAspectFit
                                            smooth: true
                                            mipmap: true
                                        }

                                        // Small corner key badge when an OTP came from SMS/Gmail/WhatsApp
                                        StyledRect {
                                            visible: notifCard.isOtpCard
                                            anchors.right: parent.right
                                            anchors.bottom: parent.bottom
                                            anchors.rightMargin: -2
                                            anchors.bottomMargin: -2
                                            implicitWidth: 20
                                            implicitHeight: 20
                                            radius: Tokens.rounding.full
                                            color: Colours.palette.m3tertiary
                                            border.width: 2
                                            border.color: notifCard.color

                                            MaterialIcon {
                                                anchors.centerIn: parent
                                                text: "key"
                                                fontStyle: Tokens.font.icon.small
                                                color: Colours.palette.m3onTertiary
                                                fill: 1
                                            }
                                        }
                                    }

                                    // Title, App Pill, Timestamp & Message Body
                                    ColumnLayout {
                                        Layout.fillWidth: true
                                        spacing: 4

                                        RowLayout {
                                            Layout.fillWidth: true
                                            spacing: Tokens.spacing.small

                                            StyledText {
                                                Layout.fillWidth: true
                                                text: notifCard.modelData.title
                                                font: Tokens.font.body.builders.medium.weight(Font.DemiBold).build()
                                                color: notifCard.isOtpCard ? Colours.palette.m3onTertiaryContainer : Colours.palette.m3onSurface
                                                elide: Text.ElideRight
                                                maximumLineCount: 1
                                            }

                                            StyledRect {
                                                implicitWidth: appTagText.implicitWidth + Tokens.padding.small * 1.6
                                                implicitHeight: appTagText.implicitHeight + 4
                                                radius: Tokens.rounding.small
                                                color: notifCard.isOtpCard ? Colours.palette.m3tertiary : Colours.palette.m3surfaceContainerHighest

                                                StyledText {
                                                    id: appTagText

                                                    anchors.centerIn: parent
                                                    text: notifCard.modelData.appName
                                                    font: Tokens.font.body.builders.small.weight(Font.Medium).build()
                                                    color: notifCard.isOtpCard ? Colours.palette.m3onTertiary : Colours.palette.m3onSurfaceVariant
                                                }
                                            }

                                            StyledText {
                                                visible: Boolean(notifCard.modelData.timestamp)
                                                text: notifCard.modelData.timestamp
                                                font: Tokens.font.body.small
                                                color: notifCard.isOtpCard ? Colours.palette.m3onTertiaryContainer : Colours.palette.m3onSurfaceVariant
                                                opacity: 0.85
                                            }
                                        }

                                        StyledText {
                                            Layout.fillWidth: true
                                            text: notifCard.modelData.message
                                            font: notifCard.isOtpCard ? Tokens.font.title.builders.large.weight(Font.Bold).build() : Tokens.font.body.medium
                                            color: notifCard.isOtpCard ? Colours.palette.m3onTertiaryContainer : Colours.palette.m3onSurfaceVariant
                                            wrapMode: Text.Wrap
                                            maximumLineCount: 3
                                            elide: Text.ElideRight
                                        }
                                    }

                                    // Dismiss Single Notification Button (smoothly slides/fades card out)
                                    IconButton {
                                        Layout.alignment: Qt.AlignVCenter
                                        icon: "close"
                                        type: IconButton.Text
                                        isRound: true
                                        disabled: notifCard.isDismissing
                                        onClicked: {
                                            notifCard.isDismissing = true;
                                            notifCard.enterOpacity = 0;
                                            notifCard.enterOffsetX = 60;
                                            notifCard.enterScale = 0.95;
                                            dismissFinishTimer.start();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                StyledScrollBar {
                    id: scrollBar

                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.right: parent.right
                    flickable: notifFlickable
                }
            }
        }
    }
}
