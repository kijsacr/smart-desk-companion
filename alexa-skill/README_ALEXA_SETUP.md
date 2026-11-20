# Smart Desk Companion — Alexa Setup Guide (V8 Complete: Phase 1 + Phase 2)

This guide walks you click-by-click through setting up and updating your personal **Smart Desk** Alexa skill. You do **not** need an AWS account, credit card, or server—Amazon hosts it for free inside your personal Alexa account.

---

## 1. Create the Skill (`developer.amazon.com/alexa/console/ask`)

*(Skip to Step 2 if you already created the skill during Phase 1.)*

1. Open [https://developer.amazon.com/alexa/console/ask](https://developer.amazon.com/alexa/console/ask) and sign in with **the exact same Amazon account** that your Echo Dot and Moto G67 Alexa app use.
2. Click **Create Skill** and enter:
   - **Name**: `Smart Desk`
   - **Primary locale**: **English (IN)** *(Recommended for Echo Dots in India; you can also add `English (US)` under **Build → Language Settings** so both work).*
   - **Choose a model**: **Custom**
   - **Choose a method to host your skill's backend resources**: **Alexa-hosted (Node.js)**
   - **Template**: **Start from Scratch**
3. Click **Create skill** and wait ~60 seconds for provisioning.

---

## 2. Build Tab — JSON Editor (`interactionModel.en-IN.json`)

1. In the top navigation bar, click **Build**.
2. In the left sidebar under **Interaction Model**, click **JSON Editor**.
3. Select all existing text (`Ctrl+A`), delete it, and paste the full contents of [`alexa-skill/interactionModel.en-IN.json`](./interactionModel.en-IN.json).
4. Click **Save Model**, then click **Build Model** and wait ~30–60 seconds for the **Build Successful** notification.
   - *(Tip: If you added both `English (IN)` and `English (US)` in Language Settings, switch the top-left language dropdown to the other locale and paste the same JSON there too, then click **Save Model → Build Model**.)*

---

## 3. Code Tab — Paste `lambda/index.js`, Set `DB_URL`, Save & Deploy

1. Click the **Code** tab in the top navigation bar.
2. In the left file tree under `lambda/`, click **`index.js`**:
   - Select all existing text (`Ctrl+A`), delete it, and paste the full contents of [`alexa-skill/lambda/index.js`](./lambda/index.js).
   - Verify `CONFIG.DB_URL` at the top of `index.js` is set to your Firebase Realtime Database URL (`https://smart-desk-companion-cf7fd-default-rtdb.asia-southeast1.firebasedatabase.app`, no trailing slash).
3. *(Only if `package.json` changed)* Check `lambda/package.json` matches [`alexa-skill/lambda/package.json`](./lambda/package.json).
4. Click **Save** (top-right), then click **Deploy** (top-right) and wait ~30–60 seconds until deployment finishes.

---

## 4. Test Tab — Simulator Test Phrases (Phase 1 & Phase 2)

1. Click the **Test** tab at the top and ensure *"Skill testing is enabled in:"* is set to **Development**.
2. Type these exact test phrases in the **Alexa Simulator**:
   - **Timetable**:
     - `ask smart desk to show my timetable`
   - **Add a task (multi-turn guided dialog)**:
     - `ask smart desk to add a task`
     - When asked for the task: `the task is Chapter 5 problem set`
     - When asked for the subject: `Computer Networks` *(or `CN`)*
     - When asked for the due date: `tomorrow` *(or `Friday`)*
     - When asked *"Add Chapter 5 problem set for Computer Networks, due ... Shall I?"*: `yes`
   - **What's due**:
     - `ask smart desk what's due`
     - `ask smart desk what's due tomorrow`
   - **Set Clock Theme**:
     - `ask smart desk to set clock theme to Cyber Neon`
     - `ask smart desk to set theme to Sunset Float`
   - **Phone Battery**:
     - `ask smart desk what's my phone battery`
   - **PC Stats**:
     - `ask smart desk for pc stats`
   - **Music & Clock Mode Override**:
     - `ask smart desk to turn music mode on`
     - `ask smart desk to turn music mode off`
     - `ask smart desk to set music mode to auto`
   - **Clear Tablet Notifications**:
     - `ask smart desk to clear notifications`

---

## 5. Echo Dot Check

1. Open the **Amazon Alexa** app on your Moto G67 → **Devices** → select your **Echo Dot** → **Device Settings (gear icon)** → **Language** and verify it matches your skill's built locale (**English (India)**).
2. In the Alexa app, check **More → Skills & Games → Your Skills → Dev** and confirm **Smart Desk** is enabled.
3. Speak any of the phrases in Step 4 directly to your Echo Dot.

---

## 6. Alexa Routines for Short Phrases (One Recipe per Implemented Intent)

To trigger commands on your Echo Dot with a short phrase (without saying *"ask smart desk..."*), open the **Alexa app → More → Routines → `+`**, set **When → Voice** to the short phrase, and set **Alexa Will → Custom** to the action below:

| Intent | When you say (**Voice**) | Custom Action (**Alexa Will → Custom**) |
| :--- | :--- | :--- |
| **1. ShowTimetableIntent** | `"Alexa, timetable"` | `ask smart desk to show my timetable` |
| **2. AddTaskIntent** | `"Alexa, new desk task"` | `ask smart desk to add a task` |
| **3. WhatsDueIntent** | `"Alexa, what's due"` | `ask smart desk what's due` |
| **4. SetThemeIntent** | `"Alexa, neon clock"` | `ask smart desk to set clock theme to Cyber Neon` |
| **5. PhoneBatteryIntent** | `"Alexa, my phone battery"` | `ask smart desk what's my phone battery` |
| **6. PcStatsIntent** | `"Alexa, laptop stats"` | `ask smart desk for pc stats` |
| **7. MusicModeIntent** | `"Alexa, desk music mode"` | `ask smart desk to turn music mode on` |
| **8. ClearNotificationsIntent** | `"Alexa, clear my tablet"` | `ask smart desk to clear notifications` |

---

## 7. Troubleshooting

- **Skill says *"You just triggered <IntentName>"***:
  - The default Hello World template is still active. Go to the **Code** tab, paste [`lambda/index.js`](./lambda/index.js), click **Save**, and click **Deploy**.
- **Echo Dot says *"Hmm, I don't know that"***:
  - **Locale mismatch**: Your Echo Dot is set to `English (India)` (`en-IN`) while the skill model was only built in `English (US)` (or vice-versa). Add `English (IN)` in **Build → Language Settings**, paste `interactionModel.en-IN.json`, and click **Save Model → Build Model**.
- **Skill says *"Sorry, I couldn't reach your tablet"***:
  - Check **Code → CloudWatch Logs** (top-right icon in the Code tab) for `[SmartDeskSkill] intent=... code=...`.
  - Verify `CONFIG.DB_URL` in `lambda/index.js` is `https://smart-desk-companion-cf7fd-default-rtdb.asia-southeast1.firebasedatabase.app` (no trailing slash) and that your Firebase Realtime Database rules allow read/write.
- **Skill says *"Alexa control is switched off in your phone settings"***:
  - Open the Smart Desk Phone app → **Settings** → scroll to **12. Alexa Voice Control (V8)** and turn on **"Allow Alexa voice control"**.

---

## 8. What to Report Back to Antigravity If Something Fails

If any command does not behave as expected, tell Antigravity these **three things**:
1. **The exact phrase you typed or spoke** (and whether it was in the Browser Simulator or on the Echo Dot).
2. **What Alexa replied** (or the `JSON Output` from the Test tab).
3. **What the Phone Settings Alexa Card status line shows** (under **Settings → 12. Alexa Voice Control (V8)**, or any `[SmartDeskSkill]` line from CloudWatch Logs).

---

## 9. Security & Privacy (V8 Section 9)

- **Notifications & OTPs are never read or spoken**: The skill never reads or logs the `notifications` node; it only sends a blind `DELETE /notifications.json` when you explicitly ask to clear notifications.
- **Webhook secrets are never read or logged**: The skill never accesses `config/batteryAutomation`.
- **Database URL & open rules**: The Firebase URL lives in `CONFIG.DB_URL`. Because your personal Firebase Realtime Database is currently in open test mode, anyone who knows the URL could read or write to it. Keep the URL private (do not push it to a public repository). Tightening Firebase rules in the future would require adding an authentication token/credential to the skill, the phone app, the tablet app, and the PC agent.
