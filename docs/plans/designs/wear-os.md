# Design: Wear OS

Tasks are T-106–110 in `../BACKLOG.md` (milestone M7). The open question is D-9 in `../DECISIONS.md`. Nothing here is
started, and it comes after the foundations work (M3).

**What a watch is for:** glance at what's due, and Take, Skip or Snooze from the wrist. Not editing medications,
schedules or backups.

---

## Cheapest first step: what the phone's notifications already do (T-106)

Android shows phone notifications on a paired watch, with their action buttons. Reminders are already one notification
per window with Take / Skip (or Take all), so some of this may work today with no watch code. Check on a real watch:

- Do Take and Skip work from the watch, and does the phone's data and the other reminders update afterward?
- Reminders are ongoing and a swipe means snooze (`setDeleteIntent`). On a watch, dismissing may behave differently, so
  swipe-to-snooze may not work there.
- The end-of-window alarm relies on a full-screen intent and sound, which a watch won't do.
- Discreet mode hides details on the lock screen. Check what a watch shows.

This tells us how much a watch app adds before any code is written.

## Options for a watch app (D-9)

| | A. Companion | B. Standalone |
|-|--------------|---------------|
| Data | Lives on the phone. The watch asks for today's doses and sends back logs (Wear Data Layer) | The watch keeps its own copy, kept in step by sync |
| Needs | A phone nearby | Sync (T-102) with its device list, merge and tie rules |
| Works without the phone | No | Yes |
| Cost | Moderate: a message protocol and a snapshot format | High: everything sync needs, plus a database on the watch |
| Reminders | Phone's notifications bridge to the watch; the app adds a richer screen | The watch would need its own alarms |

**Recommendation:** A first. It reuses the phone's data and reminders, avoids a second source of truth, and can be replaced
later without throwing away the watch screens. B only makes sense after sync exists and the model has been hardened
(T-061–065), because a watch is one more writer that can log a dose.

## What the codebase gives us

- **Pure logic is mostly separable.** `model`, `domain` and `sync` have no Android imports, except `DeviceIdentity`.
  `DoseEngine` and `DoseTimeline` both call `notifications.DoseAlarmTiming` for window times. It has no Android imports
  either, so it can move with them. That's the main refactor: a shared module (T-107).
- **There's only one Gradle module today (`:app`).** A watch app is a second application module, so the shared code needs
  a home both can depend on. Doing this also makes the logic easier to test.
- **Every change that matters goes through `DoseNotificationManager.refresh` and `RegimenRepository`.** A watch must log
  through the phone's repository in option A, so Pacing, the daily-limit rules and Undo stay in one place. The watch
  shouldn't re-implement them.
- **Logging rules to keep:** a dose over its pacing limit is logged in the app, which explains the limit first (see
  `AGENTS.md`). On the watch, an over-limit dose should say "Open on phone" rather than log silently.
- **Privacy:** a watch screen shows medication names. Respect the discreet-notifications setting, and think about what
  shows when the watch is locked.

## Screens, kept small

- **Today:** a list of due doses in time order, each with Take, Skip and Snooze. A "nothing due" state.
- **Tile or widget:** the next dose and its window. Wear OS widgets and the Material 3 for Wear OS components cover this.
- **Complication:** next dose time.

Implementation references available in this environment: the `wear-compose-m3` and `wear-widgets` skills.

## Testing

Use the Wear OS emulator paired to a phone for development. A real watch is needed to judge notification bridging
(T-106), haptics and battery impact.
