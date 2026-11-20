# Smart Desk Companion (V7)

Android Studio multi-module project (`:tablet` for **Lenovo Yoga Tab 11 YT-X705X** and `:phone` for **Moto G67**) plus the `pc_agent` Python resource monitor.

---

## One-Time V7 Setup Steps

### 1. Tablet: Enable Device Admin for Proximity Screen-Off (Recommended)
To allow the tablet to immediately turn its screen off (`DevicePolicyManager.lockNow()`) when your phone hotspot disconnects or goes away, either enable **Smart Desk Companion** under **Tablet Settings → Security → Device admin apps**, or run this one-time ADB command with the tablet connected:

```powershell
adb shell dpm set-active-admin com.smartdesk.companion.tablet/.receiver.TabletDeviceAdminReceiver
```

*(If Device Admin is not enabled, the tablet automatically falls back to Method B: clearing `FLAG_KEEP_SCREEN_ON` and dimming brightness to `0%` so Android's screen timeout turns the display off.)*

### 2. Phone: Permissions & Battery Optimisation Exemption
Open **Smart Desk Phone Companion → Settings** on your Moto G67 and verify:
1. **Notification Relay Service**: Active (`Notification Access` granted).
2. **OTP Full-Screen Alerts**: Grant **`RECEIVE_SMS`** permission when prompted so SMS OTPs are read directly (bypassing Android 15+ `"Sensitive notification content hidden"` redaction).
3. **Proximity Screen-Off**: Tap **EXEMPT PHONE APP FROM BATTERY OPTIMISATION** so the 60-second presence heartbeat and phone battery sync run reliably in the background.
