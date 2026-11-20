# Smart Desk Companion (V6) — Laptop Resource Monitor Agent

Lightweight Python 3 background agent (`pc_monitor.py`) that pushes your laptop's **CPU %**, **RAM %**, and **Temperature** (`GPU` or `CPU`) to Firebase Realtime Database (`/pc/stats`) so your docked Lenovo Yoga Tab 11 displays a live stat pill (`CPU 14% | RAM 48% | 52°C`).

---

## 1. Configure `config.json`

Open `pc_agent/config.json` and paste your Firebase Realtime Database URL (the same URL configured in your Phone and Tablet apps):

```json
{
  "databaseUrl": "https://your-project-default-rtdb.firebaseio.com",
  "intervalSec": 5,
  "tempSource": "auto"
}
```

- **`databaseUrl`**: Your Firebase Realtime Database root URL (without a trailing slash).
- **`intervalSec`**: Sampling interval in seconds (default `5`). Writes only happen when CPU changes by $\ge 2\%$, RAM by $\ge 1\%$, Temp by $\ge 1^\circ\text{C}$, or every `15` seconds as a heartbeat.
- **`tempSource`**:
  - `"auto"` (default): Tries NVIDIA GPU (`nvidia-smi`) $\rightarrow$ LibreHardwareMonitor (`http://localhost:8085/data.json`) $\rightarrow$ Linux sensors $\rightarrow$ `null` (hides the temperature segment cleanly on the tablet).
  - `"gpu"`: GPU temperature only.
  - `"cpu"`: CPU temperature only.
  - `"none"`: Disables temperature polling (tablet shows `CPU xx% | RAM xx%`).

---

## 2. Install & Autostart on Windows

Open PowerShell inside the `pc_agent` folder and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\install_windows.ps1
```

This script:
1. Installs `psutil` (`python -m pip install --upgrade psutil`).
2. Registers a Windows Task Scheduler task named **`SmartDeskPCMonitor`** set to **At log on** using `pythonw.exe` (completely silent, no console window, below-normal CPU priority).
3. Starts the background task immediately.

---

## 3. Uninstall on Windows

To stop the background script and remove the scheduled task:

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall_windows.ps1
```

---

## 4. Linux Autostart (`systemd --user`)

On Linux, install `psutil` (`pip install psutil`) and create `~/.config/systemd/user/smartdesk-pc-monitor.service`:

```ini
[Unit]
Description=Smart Desk Companion PC Resource Monitor
After=network-online.target

[Service]
Type=simple
WorkingDirectory=/path/to/TAB/pc_agent
ExecStart=/usr/bin/python3 /path/to/TAB/pc_agent/pc_monitor.py
Nice=10
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
```

Then enable and start it:

```bash
systemctl --user daemon-reload
systemctl --user enable --now smartdesk-pc-monitor.service
```

---

## 5. Firebase Security Rules Recommendation (V6)

If you later configure Smart Plug webhook URLs in **Phone Settings $\rightarrow$ Battery Protection**, those URLs are stored at `/config/batteryAutomation` and may contain a webhook key. Ensure your Firebase Realtime Database rules restrict public access or lock `/config/batteryAutomation` to your authenticated devices.

---

## 6. V7 Proximity Screen-Off — One-Time Tablet Device Admin Setup

To allow the tablet to immediately lock/turn off its screen when your phone leaves (`DevicePolicyManager.lockNow()`), enable the tablet app's `TabletDeviceAdminReceiver` either in **Tablet Settings $\rightarrow$ Security $\rightarrow$ Device admin apps $\rightarrow$ Smart Desk Companion** or with a single ADB command:

```powershell
adb shell dpm set-active-admin com.smartdesk.companion.tablet/.receiver.TabletDeviceAdminReceiver
```

If Device Admin is not enabled, the tablet automatically falls back to **Method B** (clearing `FLAG_KEEP_SCREEN_ON` and dimming window brightness to `0%` so Android's display sleep timeout turns the screen off).
