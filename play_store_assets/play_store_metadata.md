# Google Play Store Listing & ASO Metadata

Draft listing copy. Review current Google Play policies before publication; compliance and approval are not guaranteed. The six screenshots in this folder are real captures of the release build; the app icon and feature graphic are unchanged and need an owner decision (section 6).

---

## 1. App Title (Max 30 characters)

**Recommended Title:**
```text
myfrnd: Daily Companion Habits
```
*(Exact character count: 30 / 30)*

**Alternative Options:**
- `myfrnd: Habit & Eye Break Buddy` *(30 chars)*
- `myfrnd: Screen Break & Water` *(27 chars)*
- `myfrnd: Focus & Habit Companion` *(30 chars)*

---

## 2. Short Description (Max 80 characters)

```text
Your friendly companion for screen eye breaks, hydration & spoken task reminders.
```
*(Exact character count: 79 / 80)*

---

## 3. Full Long Description (Max 4,000 characters)

```text
Meet myfrnd — your interactive daily companion designed to help you stay healthy, energized, and organized while using your phone.

Staring at screens for long hours causes digital eye strain, dehydration, and fatigue. myfrnd introduces a friendly on-screen character who drops in gently throughout your day to remind you to rest your eyes, drink water, and conquer your to-do list.

✨ MEET YOUR INTERACTIVE COMPANION
• Expressive Reactions: Your companion blinks naturally, breathes smoothly, bounces joyfully when tapped, and can speak reminders aloud when you turn voice on (off by default).
• Interactive Dashboard Stage: Cycle through cheerful poses, speech quotes, and motivational cheers whenever you need a quick boost.
• Always by Your Side: With your permission, a small overlay appears briefly over your other apps and leaves on its own. Tap to dismiss anytime!

👀 SMART SCREEN EYE BREAKS (20-20-20 RULE)
• Screen-Aware Timers: Eye-break and water reminders run while your screen is available (on and unlocked).
• Eye-Break Reminders: A gentle nudge to look at something about 20 feet away for 20 seconds, every 15, 20, 30, or 45 minutes (your choice).
• Gentle Visuals: Your companion appears with a soft glow and a relaxed, sleepy-eyed look to remind you to rest your eyes.

💧 SMART HYDRATION & WATER REMINDERS
• Never Forget to Drink Water: Customizable hydration intervals (15m, 30m, 45m, or 60m).
• Refreshing Visuals: Accompanied by crystalline water bubble particle animations and cheerful hydration coaching.
• Small Habit, Big Difference: A friendly nudge to take a sip of water when you can.

📋 SPOKEN WORK & TASK TO-DOS
• Optional Spoken Reminders: Add a work task, study goal, or habit. When enabled, speech uses an installed offline voice; reminders also appear as text. Reminders wait until your companion is on and your phone is unlocked, and Android may delay them.
• Quick Presets & Custom Dates: Set reminders for "In 15 min", "In 1 hour", "Next 8 PM", or "Tomorrow 9 AM", or pick an exact calendar date and minute.
• Clear Reminder Management: Edit upcoming reminders, keep overdue tasks visible, and confirm before deleting. Multiple due tasks appear one at a time. Displaying a reminder does not mark your real-world task complete.

🔒 100% OFFLINE & PRIVACY-BY-DESIGN
• No Account Required: No logins, passwords, or cloud databases.
• Zero Data Collection: Your habits, notes, and timers remain strictly on your private device storage.
• No Annoying Ads: Zero banner ads, zero video interruptions, zero third-party tracking cookies.

🌟 WHO IS MYFRND FOR?
• Remote workers and desk professionals spending hours on screens.
• Students studying for exams who want regular reminders to rest their eyes.
• Anyone looking to build healthier daily hydration habits effortlessly.

Download myfrnd today and enjoy a healthier, more balanced screen-time routine with your new favorite daily companion!
```

---

## 4. Google Play Category & Store Settings

| Setting | Value |
| :--- | :--- |
| **App or Game** | App |
| **Free or Paid** | Free |
| **Primary Category** | **Health & Fitness** *(or **Productivity**)* |
| **Tags** | `Digital wellbeing`, `Habit tracker`, `Eye care`, `Drink water reminder`, `To-do list` |
| **Target Audience** | Ages 13 and above |
| **Contact Email** | `dutypein@gmail.com` |

---

## 5. Google Play "Data Safety" Form Answers

When filling out the **Data safety** section in Google Play Console:

1. **Does your app collect or share any of the required user data types?**
   &rarr; Select: **No**
2. **Is all user data collected by your app encrypted in transit?**
   &rarr; Select: **N/A** (No data is collected or transmitted to external servers).
3. **Do you provide a way for users to request that their data be deleted?**
   &rarr; Select: **Yes**
   &rarr; Provide URL: `https://gopib9119.github.io/myfrnd/data-deletion.html`
4. **Is your app target audience including children under 13?**
   &rarr; Select: **No** (Ages 13+)

**Privacy policy URL** (App content > Privacy policy): `https://gopib9119.github.io/myfrnd/privacy.html`

---

## 6. Play Store Visual Assets Checklist

All files are in this folder (`play_store_assets/`).

| Asset File | Dimensions | Format | Status |
| :--- | :--- | :--- | :--- |
| `app_icon_512.png` | 512 x 512 px | PNG, no alpha | **Owner decision, not changed.** The artwork (a 3D character on a white square with rounded corners drawn into the image) differs from the launcher icon (green droplet with a check) and from the in-app companion. Play applies its own corner mask, so a full-bleed square is the safer upload. |
| `feature_graphic_1024x500.png` | 1024 x 500 px | PNG | **Owner decision, not changed.** It shows a mascot that does not appear in the app. |
| `screenshot_1_companion.png` | 1080 x 2160 px | 24-bit PNG, no alpha | Real capture: dashboard with the 3D companion hero |
| `screenshot_2_eyebreaks.png` | 1080 x 2160 px | 24-bit PNG, no alpha | Real capture: "Your daily rhythm" with the live Water and Eyes countdowns |
| `screenshot_3_hydration.png` | 1080 x 2160 px | 24-bit PNG, no alpha | Real capture: the 3D companion dropping onto the home screen on its thread with a reminder bubble ("Preview on my screen"). The launcher's own Google app icons are visible behind it; replace it with a neutral-background capture if you prefer |
| `screenshot_4_tasks.png` | 1080 x 2160 px | 24-bit PNG, no alpha | Real capture: "Your reminders" with three upcoming reminders |
| `screenshot_5_editor.png` | 1080 x 2160 px | 24-bit PNG, no alpha | Real capture: the reminder editor with the quick-time presets |
| `screenshot_6_privacy.png` | 1080 x 2160 px | 24-bit PNG, no alpha | Real capture: "Make it yours", the "No account. No ads." footer and the 3D model credit (CC BY 4.0) |

The six screenshots were captured from the R8-shrunk release build on an Android 16 emulator at 1080 x 2160 (exactly 2:1, the maximum aspect ratio Play accepts), with no text, frame or artwork added. Re-capture them from the release you actually ship. The earlier set were composited mockups (1080 x 2400, with an alpha channel) that showed screens and features the app does not have, and they were replaced.
