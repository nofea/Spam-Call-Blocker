# Spam Call Blocker — Specification (v1)

| | |
|---|---|
| Status | Draft |
| Date | 2026-10-16 |
| Platform | Android 13+ (minSdk 33, targetSdk latest) |
| Language | Kotlin |
| Distribution | Personal sideload (not Google Play) |

## 1. Overview

A personal Android app that screens incoming calls and blocks spam calls originating from Indian numbers. The user lives in India, so these are domestic calls. Block decisions come from four sources:

1. **Built-in TRAI series rules.** Numbers in the 140-series, which TRAI assigns to promotional telemarketers.
2. **Remote spam lists.** Number lists the user subscribes to by URL, refreshed periodically.
3. **The user's own block list.** Numbers learned from the call log and from the user's "Block" actions.
4. **Prefix patterns.** Ranges suggested automatically from clusters of blocked numbers, plus wildcard rules the user writes.

### 1.1 Goals

- Block or silence spam calls before the phone rings.
- Keep false positives rare, using layered allow rules (§5).
- Keep all call data on the device. The app only downloads.
- Make each screening decision in under 100 ms (p95).

### 1.2 Non-goals (v1)

- Google Play release, or compliance with Play's call-log permission policy.
- A backend, crowd-sourced reporting, or uploading numbers anywhere.
- Replacing the system dialer.
- Blocking SMS spam.
- Android versions below 13.
- Scraping proprietary caller-ID databases such as Truecaller.

## 2. Platform mechanism

### 2.1 Call screening

- The app implements `android.telecom.CallScreeningService` and holds the `RoleManager.ROLE_CALL_SCREENING` role. Onboarding requests the role with `RoleManager.createRequestRoleIntent`.
- `onScreenCall(Call.Details)` must call `respondToCall(details, response)` well within the system's timeout of about 5 s. The target is under 100 ms.
- Only incoming calls are screened. Outgoing calls (`details.callDirection == DIRECTION_OUTGOING`) are allowed immediately.

### 2.2 Actions

Each matching rule or source has one configurable action:

| Action | `CallResponse.Builder` | Effect |
|---|---|---|
| **Reject** | `setDisallowCall(true)`, `setRejectCall(true)`, `setSkipNotification(true)` | The call is ended and doesn't ring. It still appears in the system call log. |
| **Silence** | `setSilenceCall(true)` | The call arrives but doesn't ring, and shows as missed. |
| **Warn** | allow (no flags) | The call rings normally, and the app posts a "Possible spam: <rule>" notification. |

`setSkipCallLog` is **not** used. The system call log stays the source of truth that the app learns from.

### 2.3 Platform limitations (accepted)

- **Contacts aren't screened.** Android doesn't send calls from saved contacts to a screening app that isn't the default dialer. Contacts are therefore effectively always allowed. The app checks contacts anyway as a safeguard.
- **The system block list can't be read.** `BlockedNumberContract` is only readable by the default dialer, SMS, or carrier apps. The app learns from the call log instead (§7). That includes `CallLog.Calls.BLOCKED_TYPE` entries for calls the system dialer has already blocked.
- **Exact timing isn't guaranteed.** Some OEM skins delay or kill background services. The app must not rely on long-running work inside `onScreenCall`.

## 3. Permissions

| Permission | Purpose |
|---|---|
| Call screening role | Screen incoming calls |
| `READ_CALL_LOG` | Import blocked calls, learn from history, check recent outgoing calls, detect repeat callers |
| `READ_CONTACTS` | Contacts allow rule; exclude contacts from derived prefixes |
| `POST_NOTIFICATIONS` | Spam warnings, block notifications, "Block this number?" prompts |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download remote lists only |

Privacy constraints:

- No analytics, crash-reporting, or advertising SDKs.
- No network requests except fetching the user's configured list URLs.
- Call data, contacts, and rules never leave the device.

Any permission the user denies degrades its feature gracefully. For example, without `READ_CALL_LOG` the recent-outgoing rule is turned off and the UI says so. The repeat-caller rule keeps working, because it uses the app's own history (§5).

## 4. Number normalization

- The app uses **libphonenumber** with the default region `IN`.
- Every number is stored and compared in **E.164** form (`+91XXXXXXXXXX`).
- Inputs it must handle: `9876543210`, `09876543210`, `919876543210`, `+91 98765 43210`, `+91-98765-43210`, and short or service numbers such as 140- and 160-series numbers.
- A number libphonenumber can't parse falls back to its digits plus a `+` prefix, and it's flagged as `unparsed` in history.
- **Hidden callers:** an empty or null handle, or `details.handlePresentation` of `PRESENTATION_RESTRICTED`, `PRESENTATION_UNKNOWN`, or `PRESENTATION_PAYPHONE`.
- **International callers:** any parsed number whose country code isn't 91.
- **TRAI series detection** works on the national significant number:
  - **140-series** (promotional telemarketers): the national number starts with `140`.
  - **160-series** (transactional and service calls from banks, government, regulated entities): the national number starts with `160`.
  - The prefix sets are kept in one `TraiSeries` object so they can be updated if TRAI changes the allocation.

## 5. Decision pipeline

Evaluation runs in order, and the **first match wins**. Contacts and the allowlist come first, then the user's own exact block list, then the safety-net allows, then every other block rule. A number the user explicitly blocked stays blocked even if they called it back or it calls twice in a row; to let it through, they mark it **Not spam** (§7) or save it as a contact.

| # | Stage | Rule | Default |
|---|---|---|---|
| 1 | Allow | Saved contact | on |
| 2 | Allow | Manual allowlist (exact or prefix) | on |
| 3 | Block | User exact block list (Block button and first-run import) | Reject |
| 4 | Allow | TRAI 160-series | on |
| 5 | Allow | User called this number within the last **N days** | on, N = 7 |
| 6 | Allow | **Repeat caller**: the same number already called within **M minutes** (this is the second or later call) | on, M = 5 |
| 7 | Block | Manual wildcard or prefix rules | per rule |
| 8 | Block | Accepted derived prefixes | Silence |
| 9 | Block | Remote list entries (enabled sources) | per source, default Silence |
| 10 | Block | TRAI 140-series | Reject |
| 11 | Block | Hidden caller toggle | on, Silence |
| 12 | Block | International (non +91) toggle | on, Silence |
| 13 | — | Otherwise | Allow |

Notes:

- **Repeat caller** uses the app's own `CallEvent` history, not the call log, so it stays fast. It also counts calls the app rejected or silenced earlier.
- **Hidden callers** skip stages 1–10, since there's no number to match, and go straight to stage 11.
- When several block rules match one number, the earliest stage wins. Within a stage, the more specific pattern (more literal digits) wins, so an exact number beats a prefix.
- The contact, recent-outgoing, and repeat-caller lookups start concurrently, so the slowest of them, not their sum, bounds the decision time.
- Every decision is written to `CallEvent` with the stage and rule ID that matched. Allows that came from a safety net are logged too, so the user can see why a call got through.
- `DecisionEngine` is pure Kotlin. It receives a `CallContext` (normalized number, presentation, timestamp) and a set of lookups (contacts, recent-outgoing, recent-events, `RuleIndex`). Those lookups are interfaces, so tests can fake them.

## 6. Pattern rules

### 6.1 Manual wildcard rules

Rules are written in E.164 form. The UI accepts national format and normalizes it: an exact number goes through §4, and a pattern without a leading `+` loses one leading `0` and gets `+91` prepended (`98765*` → `+9198765*`). Spaces and dashes are ignored. Suffix patterns are kept as typed.

| Syntax | Meaning | Example |
|---|---|---|
| `+9198765*` | prefix | any number starting +9198765 |
| `+91987654321?` | `?` = exactly one digit | 10 numbers |
| `*4321` | ends-with (suffix) | any number ending 4321 |
| `+91140*` | prefix | the TRAI 140-series (also built in) |

- A rule may contain only `+`, digits, `?`, and **one** `*`, either at the end (prefix) or at the start (suffix).
- Prefix rules and `?` rules compile into the prefix trie (§10). Suffix rules go in a small, separate linear list.
- The UI shows how many numbers each rule covers (e.g. "covers 10⁵ numbers"). If a rule covers more than 10⁷ numbers, the UI warns before saving.

### 6.2 Auto-derived prefix suggestions

**Input** is the set of exact numbers on the user block list. Only Indian mobile and landline numbers are considered.

**Algorithm:**
1. Take each number's national significant number (10 digits for mobile).
2. Group the numbers by every prefix length from **L** up to 9 digits.
3. Any prefix shared by at least **K** blocked numbers becomes a candidate. Among overlapping candidates, prefer the **longest** (the narrowest range).
4. Drop a candidate if:
   - it covers any saved contact, allowlisted number, or number the user has called;
   - it matches an existing block rule; or
   - it was previously dismissed.

**Defaults:** K = 3 numbers, L = 8 digits, giving ranges of at most 100 numbers. Both values can be changed in Settings, within K ≥ 2 and 6 ≤ L ≤ 9.

**Trigger:** after any change to the user block list (debounced), and once a day as a WorkManager job.

**Output:** a `DerivedSuggestion` with the prefix, its range size, and the numbers that produced it. The Rules screen shows these in a **Suggestions** inbox, where the user can Accept (choosing an action) or Dismiss.

A suggestion **never takes effect without the user's approval**. Dismissed prefixes are remembered and never suggested again.

## 7. Learning from the call log

- **First-run import.** After onboarding, the app reads every `CallLog.Calls.TYPE == BLOCKED_TYPE` entry. It shows them as a checklist with everything checked by default, and adds the confirmed numbers to the user block list (origin `IMPORT`). Calls the app itself rejected are also logged as blocked; those are left out, so re-running the import doesn't copy list or TRAI matches into the user block list.
- **Call log screen.** Lists recent incoming, missed, rejected, and blocked calls, each with a badge for the app's decision (if any). The badge is the decision that call got and never changes, so each row also shows the number's current exact rule ("Now blocked: Reject", "Now on allowlist"); the History screen does the same. Actions per number:
  - **Block**: opens an action chooser and adds the number to the exact block list.
  - **Not spam**: adds the number to the allowlist and removes any exact block for it.
- **Post-call prompt (optional, default on).** After a call ends from an unknown number that the app allowed, the app posts a notification: "Block <number>?" with **Block** and **Not spam** actions. Turning this off in Settings stops the prompts.
  - **Detection:** a `ContentObserver` on `CallLog.Calls.CONTENT_URI`. It runs only while the app's process is alive. As a fallback, a WorkManager job checks the log every 15 min and doesn't prompt for calls older than 1 h.
- **History reconciliation.** Every "Not spam" action on a number that was blocked by a remote list or a derived prefix is recorded as a `FalsePositive`, so the user can see which sources produce false positives. The Sources screen shows each source's false-positive count.

## 8. Remote spam lists

### 8.1 Subscriptions

The user adds subscriptions by entering a name, URL, format, and action. The app ships **no default URLs**. Example sources include community-maintained GitHub lists or a list the user hosts in a gist.

### 8.2 Formats

| Format | Rules |
|---|---|
| TXT | One entry per line. Blank lines and `#` comments are ignored. |
| CSV | Header row optional. The user picks the number column by header name or 1-based column number (default: the first column). With no name given, a first row whose number cell has no digits is treated as a header. |
| JSON | Either `["+9198…", …]` or `[{"number": "+9198…", …}, …]` |

- An entry ending in `*` is a **prefix** entry. Every other entry is an exact number, normalized with §4.
- Lines that fail to parse are counted and skipped. A list where **more than 50%** of lines fail to parse is rejected as a whole, and the previous copy is kept. An empty list is also rejected, so a broken URL can't silently wipe a source.
- Duplicate entries are collapsed.
- Hard limits: 10 MB per download and 500k entries per source. A list over either limit is rejected as a whole, not truncated.
- Prefix limits, so that a broken or compromised list can't block whole number ranges: a list is rejected as a whole if any prefix entry covers more numbers than the §6.1 warning threshold (10 million, e.g. `+9198*` or `+91*`), or if its prefix entries together cover more than 100 million numbers. Coverage is computed as in §6.1.

### 8.3 Refresh

- WorkManager runs a `PeriodicWorkRequest`, every **24 h** by default (options: 6 h, 12 h, 24 h, 7 d, or manual). By default it requires an unmetered network, and that can be switched off.
- Requests use conditional GET with `If-None-Match` / `If-Modified-Since`. A `304` response only updates `lastFetchedAt`. Validators that aren't printable ASCII are neither stored nor sent.
- A failure refreshing one source never stops the other sources from refreshing.
- New entries replace a source's old ones in a single Room transaction (delete, then insert), then `RuleIndex` is rebuilt.
- On failure, the app keeps the last good copy and stores the error in `lastError`, which appears on the Sources screen. Retries back off exponentially.
- Each source also has a **Refresh now** button.

## 9. Data model

### 9.1 Room entities

```kotlin
enum class Action { REJECT, SILENCE, WARN }
enum class RuleType { EXACT, PREFIX, WILDCARD, SUFFIX }
enum class RuleOrigin { USER, IMPORT, MANUAL_PATTERN, DERIVED }

@Entity data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,          // E.164 or wildcard pattern
    val type: RuleType,
    val action: Action,
    val origin: RuleOrigin,
    val enabled: Boolean = true,
    val note: String? = null,
    val createdAt: Long,
)

@Entity data class AllowRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val type: RuleType,           // EXACT or PREFIX
    val note: String? = null,
    val createdAt: Long,
)

@Entity data class ListSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val format: ListFormat,       // TXT, CSV, JSON
    val csvColumn: String? = null,
    val action: Action = Action.SILENCE,
    val enabled: Boolean = true,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchedAt: Long? = null,
    val entryCount: Int = 0,
    val lastError: String? = null,
)

@Entity(indices = [Index("value"), Index("sourceId")])
data class ListEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,           // FK → ListSource, cascade delete
    val value: String,            // E.164, or an E.164 prefix without `*`
    val isPrefix: Boolean,
)

@Entity(indices = [Index("number"), Index("timestamp")])
data class CallEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String?,          // null = hidden
    val timestamp: Long,
    val decision: Decision,       // ALLOW, WARN, SILENCE, REJECT
    val stage: Stage,             // pipeline stage that matched (§5)
    val ruleId: Long? = null,
    val sourceId: Long? = null,
    val decisionMicros: Long,     // timing, for the perf target
    val unparsed: Boolean = false, // §4 fallback was used
)

@Entity data class DerivedSuggestion(
    @PrimaryKey val prefix: String,   // E.164 without `*`, e.g. +9198765432
    val sampleNumbers: List<String>,  // TypeConverter
    val rangeSize: Long,
    val status: SuggestionStatus,     // PENDING, ACCEPTED, DISMISSED
    val createdAt: Long,
)

// A "Not spam" on a number a remote list or derived prefix had blocked (§7).
@Entity data class FalsePositive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val stage: Stage,
    val ruleId: Long? = null,
    val sourceId: Long? = null,
    val createdAt: Long,
)
```

- `CallEvent` rows older than **90 days** are deleted by the daily maintenance job.

### 9.2 Settings (DataStore Preferences)

| Key | Default |
|---|---|
| `blockHidden` / `hiddenAction` | true / SILENCE |
| `blockInternational` / `internationalAction` | true / SILENCE |
| `trai140Enabled` / `trai140Action` | true / REJECT |
| `trai160Allow` | true |
| `recentOutgoingDays` (0 = off) | 7 |
| `repeatCallerMinutes` (0 = off) | 5 |
| `deriveMinCount` (K) | 3 |
| `deriveMinPrefixDigits` (L) | 8 |
| `listRefreshInterval` | 24 h |
| `listRefreshUnmeteredOnly` | true |
| `postCallPrompt` | true |
| `notifyOnBlock` | true |

## 10. Performance design

- `RuleIndex` is an immutable, in-memory structure held behind an `AtomicReference`. It contains:
  - a digit **trie** of exact numbers and prefixes from all block rules and enabled list sources, where each terminal stores `(stage, ruleId|sourceId, action)`;
  - a separate trie for allow rules;
  - a short list of suffix rules.
- **Rebuilds:** started from Room `Flow` emissions and debounced by 500 ms. The new index is built on `Dispatchers.Default` and then swapped in atomically. `onScreenCall` never waits for a rebuild, because it always reads the current index.
- **Cold start:** if the service starts before the index has loaded, it loads the index synchronously, with a 2 s timeout. If the timeout hits, it allows the call and logs `Stage.INDEX_UNAVAILABLE`.
- **Contact check:** one `PhoneLookup.CONTENT_FILTER_URI` query.
- **Recent outgoing:** one call-log query filtered by number and date, with `LIMIT 1`.
- **Repeat caller:** one indexed Room query on `CallEvent`.
- **Target:** p95 under 100 ms end to end inside `onScreenCall`, with 200k list entries. `decisionMicros` is stored on every event, and debug builds show the p50 and p95 on the Home screen.

## 11. UI (Jetpack Compose + Material 3)

| Screen | Contents |
|---|---|
| **Onboarding** | Explanation → request the call screening role → `READ_CALL_LOG`, `READ_CONTACTS`, `POST_NOTIFICATIONS` → first-run blocked-call import (§7) |
| **Home** | Protection status (a red banner if the role isn't held, with a fix button), today's and this week's blocked counts, the last 10 events |
| **History** | Every `CallEvent` with its decision, matching stage or rule, and time. Can be filtered by decision. Each row has **Block** and **Not spam** actions. |
| **Call log** | System call log entries with the app's decision badges, plus **Block** and **Not spam** (§7) |
| **Rules** | Tabs: Blocked numbers · Patterns · Allowlist · Suggestions (shows a badge with the pending count) |
| **Sources** | Remote list subscriptions: add, edit, and enable; last fetch time, entry count, error, and false-positive count; **Refresh now** |
| **Settings** | Everything in §9.2, plus "Re-run blocked-call import" and "About / privacy" |

- **Notifications.** Two channels:
  - "Blocked calls" (low importance): one grouped notification per day.
  - "Spam prompts" (default importance): Warn actions and post-call prompts.
- Dark theme and dynamic color are supported.

## 12. Architecture

- **Single Gradle module** (`:app`), MVVM, Kotlin 2.x, Coroutines and Flow.
- **Libraries:** Hilt, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization (JSON lists), libphonenumber, and Compose with Navigation.

```
com.sheen.spamblocker
├── screening/   SpamScreeningService, DecisionEngine, CallContext, Stage
├── rules/       RuleIndex, WildcardCompiler, PrefixDeriver, TraiSeries
├── phone/       PhoneNormalizer (libphonenumber wrapper)
├── data/        Room DB, DAOs, entities, SettingsRepository (DataStore), repositories
├── lists/       ListFetcher (OkHttp), parsers (Txt/Csv/Json), ListRefreshWorker
├── calllog/     CallLogReader, BlockedImport, PostCallObserver
├── notify/      Notification channels and builders, action receivers
├── work/        MaintenanceWorker (history pruning, derivation)
├── di/          Hilt module (database, DataStore, OkHttp, app scope)
└── ui/          onboarding, home, history, calllog, rules, sources, settings, common
```

- `DecisionEngine`, `RuleIndex`, `WildcardCompiler`, `PrefixDeriver`, `PhoneNormalizer`, and the list parsers use no Android framework types, so they can be unit-tested on the JVM.

## 13. Testing & acceptance

### 13.1 Unit tests (JVM)

- **PhoneNormalizer:** every input format in §4, hidden or empty handles, and international numbers.
- **WildcardCompiler:** valid and invalid syntax, and coverage-size calculation.
- **RuleIndex:** exact, prefix, `?`, and suffix matches; correct priority when several rules match.
- **DecisionEngine:** a table-driven test with at least one case per pipeline stage, plus precedence cases (e.g. a number on both the allowlist and a block list is allowed; a repeat caller in the 140-series is allowed; a number on the user block list is blocked even when it was called recently, calls repeatedly, or is in the 160-series).
- **PrefixDeriver:** the K/L thresholds, longest-prefix preference, and exclusions (contacts, allowlist, outgoing calls, dismissed prefixes).
- **Parsers:** TXT, CSV, and JSON happy paths; malformed lines; the 50%-rejection rule; size limits.

### 13.2 Instrumented tests

- `SpamScreeningService` returns the right `CallResponse` for allow, warn, silence, and reject, using a fake `Call.Details` built through test hooks.
- Room DAO and migration tests.
- `ListRefreshWorker` against `MockWebServer`, covering 200, 304, a 500 error, and an oversized body.

### 13.3 Manual acceptance (real device)

1. A call from a 140-series number is rejected and logged with stage `TRAI_140`.
2. A call from a 160-series number rings.
3. A call from a saved contact rings.
4. A number in a subscribed list is silenced and appears in History.
5. A second call from the same number within 5 min rings.
6. A number the user called yesterday rings, even though it's on a remote list.
7. A hidden caller is silenced, and so is a +1 number.
8. After three numbers sharing an 8-digit prefix are blocked, a suggestion appears. Accepting it blocks a fourth number in that range.
9. On first run, numbers the system dialer has blocked are offered for import.
10. Airplane mode during a refresh leaves the old list in place and shows an error.
11. The p95 decision time shown on Home stays under 100 ms with a 200k-entry list loaded.

## 14. Future work (out of scope for v1)

- Backup and restore of rules and settings to a file.
- A stats dashboard and a home-screen widget.
- An optional shared reporting backend.
- SMS spam filtering.
- A Play Store or F-Droid release.
