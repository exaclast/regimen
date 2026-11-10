# Regimen Decisions

Why things are the way they are. Tasks live in `BACKLOG.md`; how the app works today is in `AGENTS.md`.
When a decision is made, add it here with the date. When one is reversed, say so rather than deleting it.

---

## Waiting on you

| ID | Question | Recommendation |
|----|----------|----------------|
| D-2 | **Calendar days or rolling 24 hours for daily limits?** (B8) Calendar days chosen without asking; rolling would flag a once-daily dose taken a little earlier than yesterday | Keep calendar days |
| D-3 | **Is Android Auto Backup acceptable for the plaintext database?** It's protected by device backup encryption, not your passphrase (T-052) | Exclude the database, since the app has its own encrypted backups |
| D-4 | **Is losing one-tap Take acceptable when a minimum interval blocks it?** (B7) Augmentin taken at 9:59 has no Take on its 17:00 reminder until 19:59. Also B2's single target window and B9's "Log Evening dose early", chosen without asking | Keep them; revisit if they annoy you in daily use |
| D-5 | **Should a History tab replace Providers and Indications in the bottom bar?** Proposed 2026-11-03, before those became optional tabs | Keep four tabs; reach history from the medication page and the calendar |
| D-7 | **Where does list/detail start, and which tabs get it?** At 852 dp (foldable inner) two panes are tight and one column already looks good | Start around 1,000 dp; all three tabs for consistency |
| D-8 | **Today in wide windows: a grid or a capped column?** | Try the grid (T-070) after the width cap (T-066) |
| D-9 | **How does a watch get its data?** A companion that talks to the phone, or a standalone app with its own copy kept in step by sync. See `designs/wear-os.md` | Companion first: it needs no sync, and standalone can follow once sync (M6) exists |

---

## Made

### Data and compatibility

- **No backup-format compatibility yet.** There are no users. The backup format is v1. Backups from before the Indications
  rename lose their indications.
- **The database does migrate,** so dev installs keep their data (user decision, 2026-11-09). `RegimenDatabase` is at v3:
  v2 renamed `prescribers`, v3 renamed `conditions` and moved medication link keys. Rows from before indication kinds
  become Chronic.
- **After the first stable release,** both the database and the backup format need real migrations. Anything that reshapes
  data should land before then (milestone M3).

### Sync

- **Any device can log.** The device marked Primary only decides conflicts: it keeps its own version and others defer.
- **Two non-primary devices:** first to sync wins. No per-record "edited by" tracking.
- **The same dose logged on two devices:** keep one, tombstone the other, tell the user.
- **Reminders ring only on the primary by default,** with a per-device switch (built with sync).
- **Shared settings:** patient name, provider and indication tracking. **Per device:** reminders, alarms, theme, FDA consent,
  medication grouping.
- **Encryption:** a random data key wrapped by a passphrase key in every file's header. The recovery code is the data key.
- **Timing:** pull on open (rate-limited), at each window start, and periodically. Never block a reminder on the network.
  Push about 30 s after the last change and when the app goes to the background.
- **Data split:** one small file for meds, schedules, windows and settings; logs sharded by dose month. Sharding is
  transport-only, added when size warrants.

### Dosing

- **Daily limits count the calendar day a dose was taken,** with exceptions in `AGENTS.md`. (Open for review: D-2.)
- **Windows always sort by start time** (2026-11-03). Manual reordering was removed.
- **A dose logged at another time is paced at that time,** counting only earlier doses toward the interval, and all of that
  day toward daily limits (2026-11-09).
- **Editing a logged dose doesn't re-check pacing.** It corrects what already happened.
- **Supply is derived, not decremented,** so two devices logging can't conflict (T-095).

### Medications and scanning

- **Photos are read with OCR and only text reaches Gemini Nano,** because AICore's safety check refuses many label photos
  but accepts the same label as text (2026-11-09).
- **No hard-coded OCR parsing,** at the user's request.
- **A complete NDC that resolves to an FDA record overrides the strength read from a label.** The name must still match
  exactly.
- **FDA autofill never applies a non-exact name without asking.**

### Organization

- **"Indication," not "condition,"** because the reason for a medication isn't always a condition. Kinds are Acute, Chronic
  and Preventive, listed Acute first (2026-11-09).
- **"Provider" everywhere,** but the field on a medication stays labelled Prescriber.
- **No folders.** Medications are grouped by chips (schedule, indication, form), sorted automatically from the schedule.
  Finished medications collapse under every grouping.
- **Short-term comes before Ongoing** in the schedule grouping, matching Acute first.
- **Regular providers are a manual switch,** because nothing recorded tells a regular provider from an occasional one.

### Project

- **Plans are in git, except `archive/`** (D-1, 2026-11-10). `AGENTS.md` points to the backlog, decisions and designs.
- **Debug builds use the application id `com.exaclast.regimen.debug`** and are labelled "Regimen Debug" (D-6, 2026-11-10),
  so they install beside a release. Each has its own data.

### UI and tooling

- **Material 3 is pinned to `1.5.0-beta02`** for Expressive APIs, which requires `compileSdk` 37. `targetSdk` is now 37.
- **`minSdk` is 35.**
- **Commit directly to `main`** until there's a stable build. Commit and push only when asked.
