# Smart Desk Companion — Caelestia Top Dashboard Integration

Adds two dedicated tabs (**Notifications** and **Tasks**) to your laptop's **Caelestia / Quickshell** top-of-screen dashboard, synced live with your Firebase Realtime Database (`https://smart-desk-companion-cf7fd-default-rtdb.asia-southeast1.firebasedatabase.app`).

---

## Files Included

- **`Content.qml`** — Updated Caelestia dashboard container registering the **Notifications** (`notifications`) and **Tasks** (`task_alt`) tabs alongside *Dashboard*, *Media*, *Performance*, and *Weather*.
- **`PhoneNotificationsTab.qml`** — Live phone notification feed:
  - Polls `/notifications.json` every 3 seconds via pure QML (`XMLHttpRequest` + `Timer`).
  - Displays app icon & badge (*WhatsApp, Instagram, Gmail, Calendar, Messages, Phone Call*), sender/title, message body, and timestamp.
  - Highlights **OTP notifications** (`isOtp = true`) in a distinct M3 tertiary container with formatted code (`482 913`).
  - Supports **Dismiss Single Notification** (`×` button on each card) and **Dismiss All** (`DELETE /notifications.json`).
- **`PhoneTasksTab.qml`** — Live task & deadline manager:
  - Polls `/tasks.json` every 4 seconds via pure QML (`XMLHttpRequest` + `Timer`).
  - Displays a scrollable list of **Pending Tasks** sorted by nearest deadline first (`Overdue`, `Today`, `Tomorrow`, or `EEE, d MMM`), with red M3 error-container urgency highlights for tasks due tomorrow, today, or overdue.
  - Matches all subjects and icons from `SubjectCatalog.kt` (*Computer Networks, Probability & Statistics, Operating Systems, OOP, Cloud Engineering, General Management Minor, Capstone Project, Placement Training, Mentoring & HED, Live Performance, Others*).
  - Interactive **Checkbox** on each task row to mark tasks completed (or uncheck them from the collapsible **Completed (N)** section at the bottom), syncing immediately back to `/tasks/<id>.json`.
- **`install.sh` / `uninstall.sh`** — One-step installer and uninstaller with automatic backup of your original `Content.qml`.
- **`Dash.qml`, `Media.qml`, `Performance.qml`, `Tabs.qml`, `WeatherTab.qml`, `Wrapper.qml`** — Reference copies of the existing Caelestia dashboard module files.

---

## Installation

Run the installer script from `/home/kijsacr/Projects/TAB/caelestia_dashboard`:

```bash
chmod +x /home/kijsacr/Projects/TAB/caelestia_dashboard/install.sh
/home/kijsacr/Projects/TAB/caelestia_dashboard/install.sh
```

Then reload Caelestia / Quickshell:

```bash
killall quickshell && caelestia shell -d
```

To revert to the original Caelestia dashboard at any time:

```bash
chmod +x /home/kijsacr/Projects/TAB/caelestia_dashboard/uninstall.sh
/home/kijsacr/Projects/TAB/caelestia_dashboard/uninstall.sh
```
