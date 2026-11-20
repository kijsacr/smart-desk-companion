"use strict";

/**
 * Smart Desk Companion — V8 Phase 1 & Phase 2 Unit Tests (V8 Section 4.3, 5 & 10)
 *
 * Uses only Node's built-in `assert` module (zero external test frameworks).
 *
 * Tests:
 * 1. Phase 1: Parity test for `resolveHighlight` against every row of the V4
 *    Section 14 table + Mon–Thu `< 16:00` / `>= 16:00` + `15:59` vs `16:00` boundary.
 * 2. Phase 1: Time zone test (`21:00 UTC` on Friday -> Saturday morning IST).
 * 3. Phase 1: Payload test (`commands/timetable` field-for-field match).
 * 4. Phase 2: Task sorting parity test (`sortPendingTasks`: overdue first,
 *    nearest deadline, ties by `createdAt` ascending, completed excluded).
 * 5. Phase 2: Task urgency & `formatWhatsDueSpeech` test (4-item cap + "and N more" + date filter).
 * 6. Phase 2: `validateTaskDeadline`, `sanitizeTaskTitle`, and `buildTaskRecord` tests.
 * 7. Phase 2: Subject, Theme, and MusicMode synonym matching tests.
 */

const assert = require("assert");
const {
  SUBJECT_CATALOG,
  CLOCK_THEME_CATALOG,
  MUSIC_MODE_CATALOG,
  matchCatalogEntry,
  resolveHighlight,
  buildTimetableCommandPayload,
  toIstComponents,
  sanitizeTaskTitle,
  validateTaskDeadline,
  sortPendingTasks,
  evaluateTaskDeadline,
  formatWhatsDueSpeech,
  buildTaskRecord,
} = require("../lambda/index.js");

function istDate(isoLocalWithoutOffset) {
  return new Date(`${isoLocalWithoutOffset}+05:30`);
}

let passed = 0;
function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`  [PASS] ${name}`);
  } catch (err) {
    console.error(`  [FAIL] ${name}`);
    console.error(err);
    process.exitCode = 1;
  }
}

console.log("=== V8 Phase 1 & Phase 2 Unit Tests ===");

// ----------------------------------------------------------------------------
// 1. Phase 1: Parity tests against V4 Section 14 table & V8 Section 4.1
// ----------------------------------------------------------------------------

test("Fri 6 Nov 2026 10:27 IST -> TODAY / FRIDAY / FRI", () => {
  const res = resolveHighlight(istDate("2026-11-06T10:27:00"));
  assert.strictEqual(res.mode, "TODAY");
  assert.strictEqual(res.highlightDay, "FRIDAY");
  assert.strictEqual(res.day, "FRI");
  assert.strictEqual(res.spokenText, "Showing today's timetable on your tablet.");
});

test("Fri 6 Nov 2026 17:00 IST -> TOMORROW / SATURDAY / SAT (Sat 7 Nov is 1st Sat)", () => {
  const res = resolveHighlight(istDate("2026-11-06T17:00:00"));
  assert.strictEqual(res.mode, "TOMORROW");
  assert.strictEqual(res.highlightDay, "SATURDAY");
  assert.strictEqual(res.day, "SAT");
  assert.strictEqual(res.spokenText, "Showing Saturday's timetable on your tablet.");
});

test("Sat 14 Nov 2026 10:00 IST (2nd Saturday) -> TODAY / SATURDAY / SAT", () => {
  const res = resolveHighlight(istDate("2026-11-14T10:00:00"));
  assert.strictEqual(res.mode, "TODAY");
  assert.strictEqual(res.highlightDay, "SATURDAY");
  assert.strictEqual(res.day, "SAT");
});

test("Sat 14 Nov 2026 17:00 IST (Tomorrow is Sunday) -> FULL_WEEK / null", () => {
  const res = resolveHighlight(istDate("2026-11-14T17:00:00"));
  assert.strictEqual(res.mode, "FULL_WEEK");
  assert.strictEqual(res.highlightDay, null);
  assert.strictEqual(res.day, null);
});

test("Fri 20 Nov 2026 17:00 IST (Tomorrow Sat 21 Nov is 3rd Saturday) -> FULL_WEEK / null", () => {
  const res = resolveHighlight(istDate("2026-11-20T17:00:00"));
  assert.strictEqual(res.mode, "FULL_WEEK");
  assert.strictEqual(res.highlightDay, null);
  assert.strictEqual(res.day, null);
});

test("Sat 21 Nov 2026 any time (3rd Saturday) -> FULL_WEEK / null", () => {
  const morning = resolveHighlight(istDate("2026-11-21T09:15:00"));
  assert.strictEqual(morning.mode, "FULL_WEEK");
  assert.strictEqual(morning.highlightDay, null);

  const evening = resolveHighlight(istDate("2026-11-21T18:30:00"));
  assert.strictEqual(evening.mode, "FULL_WEEK");
  assert.strictEqual(evening.highlightDay, null);
});

test("Sun 8 Nov 2026 any time (Sunday) -> FULL_WEEK / null", () => {
  const morning = resolveHighlight(istDate("2026-11-08T11:00:00"));
  assert.strictEqual(morning.mode, "FULL_WEEK");
  assert.strictEqual(morning.highlightDay, null);

  const evening = resolveHighlight(istDate("2026-11-08T19:00:00"));
  assert.strictEqual(evening.mode, "FULL_WEEK");
  assert.strictEqual(evening.highlightDay, null);
});

test("Mon–Thu before 16:00 IST -> TODAY; from 16:00 -> TOMORROW; 15:59 vs 16:00 boundary", () => {
  const before = resolveHighlight(istDate("2026-11-02T15:59:59"));
  assert.strictEqual(before.mode, "TODAY");
  assert.strictEqual(before.day, "MON");

  const atCutoff = resolveHighlight(istDate("2026-11-02T16:00:00"));
  assert.strictEqual(atCutoff.mode, "TOMORROW");
  assert.strictEqual(atCutoff.day, "TUE");
});

// ----------------------------------------------------------------------------
// 2. Phase 1: Time zone test
// ----------------------------------------------------------------------------

test("Request at 21:00 UTC on Friday 6 Nov 2026 is Saturday 7 Nov 02:30 IST -> TODAY / SAT", () => {
  const fridayNightUtc = new Date("2026-11-06T21:00:00Z");
  const ist = toIstComponents(fridayNightUtc);
  assert.strictEqual(ist.dayOfWeek, "SATURDAY");
  assert.strictEqual(ist.dayOfMonth, 7);

  const res = resolveHighlight(fridayNightUtc);
  assert.strictEqual(res.mode, "TODAY");
  assert.strictEqual(res.highlightDay, "SATURDAY");
  assert.strictEqual(res.day, "SAT");
});

// ----------------------------------------------------------------------------
// 3. Phase 1: Payload shape test
// ----------------------------------------------------------------------------

test("Payload matches TimetableCommandSender.kt field-for-field", () => {
  const decision = resolveHighlight(istDate("2026-11-06T10:27:00"));
  const payload = buildTimetableCommandPayload(decision, {
    requestId: "550e8400-e29b-41d4-a716-446655440000",
    clientTimestampMs: 1762405020000,
    durationSec: 20,
  });

  assert.deepStrictEqual(payload, {
    requestId: "550e8400-e29b-41d4-a716-446655440000",
    requestedAt: { ".sv": "timestamp" },
    clientTimestampMs: 1762405020000,
    mode: "TODAY",
    highlightDay: "FRIDAY",
    day: "FRI",
    durationSec: 20,
  });
});

// ----------------------------------------------------------------------------
// 4. Phase 2: Task sorting parity test (`sortPendingTasks`)
// ----------------------------------------------------------------------------

test("sortPendingTasks matches TaskScheduleLogic.kt (overdue first, nearest deadline, ties by createdAt)", () => {
  const rawFirebaseTasks = {
    t1: {
      id: "t1",
      title: "Future task",
      subjectId: "cloud_engineering",
      deadline: "2026-11-15",
      completed: false,
      createdAt: 2000,
    },
    t2: {
      id: "t2",
      title: "Overdue task",
      subjectId: "operating_systems",
      deadline: "2026-11-09",
      completed: false,
      createdAt: 5000,
    },
    t3: {
      id: "t3",
      title: "Completed task (should be ignored)",
      subjectId: "oop",
      deadline: "2026-11-08",
      completed: true,
      createdAt: 1000,
    },
    t4: {
      id: "t4",
      title: "Due today (newer)",
      subjectId: "computer_networks",
      deadline: "2026-11-10",
      completed: false,
      createdAt: 4000,
    },
    t5: {
      id: "t5",
      title: "Due today (older)",
      subjectId: "probability_statistics",
      deadline: "2026-11-10",
      completed: false,
      createdAt: 1500,
    },
  };

  const sorted = sortPendingTasks(rawFirebaseTasks);
  assert.deepStrictEqual(
    sorted.map((t) => t.id),
    ["t2", "t5", "t4", "t1"]
  );
});

// ----------------------------------------------------------------------------
// 5. Phase 2: Urgency & `formatWhatsDueSpeech` test
// ----------------------------------------------------------------------------

test("evaluateTaskDeadline & formatWhatsDueSpeech cap at 4 items and filter by date", () => {
  const todayIso = "2026-11-10";
  assert.strictEqual(evaluateTaskDeadline("2026-11-09", todayIso).label, "Overdue");
  assert.strictEqual(evaluateTaskDeadline("2026-11-09", todayIso).isUrgentRed, true);
  assert.strictEqual(evaluateTaskDeadline("2026-11-10", todayIso).label, "Today");
  assert.strictEqual(evaluateTaskDeadline("2026-11-10", todayIso).isUrgentRed, true);
  assert.strictEqual(evaluateTaskDeadline("2026-11-11", todayIso).label, "Tomorrow");
  assert.strictEqual(evaluateTaskDeadline("2026-11-11", todayIso).isUrgentRed, true);
  assert.strictEqual(evaluateTaskDeadline("2026-11-14", todayIso).isUrgentRed, false);

  const fiveTasks = [
    { id: "1", title: "Lab 1", subjectId: "operating_systems", deadline: "2026-11-09", completed: false, createdAt: 1 },
    { id: "2", title: "Lab 2", subjectId: "computer_networks", deadline: "2026-11-10", completed: false, createdAt: 2 },
    { id: "3", title: "Quiz 3", subjectId: "probability_statistics", deadline: "2026-11-11", completed: false, createdAt: 3 },
    { id: "4", title: "Essay", subjectId: "general_management", deadline: "2026-11-14", completed: false, createdAt: 4 },
    { id: "5", title: "Final Demo", subjectId: "capstone_project", deadline: "2026-11-20", completed: false, createdAt: 5 },
  ];

  const allSpeech = formatWhatsDueSpeech(fiveTasks, todayIso, null, 4);
  assert(allSpeech.includes("You have 5 pending tasks:"));
  assert(allSpeech.includes("Lab 1 for Operating Systems, overdue"));
  assert(allSpeech.includes("Lab 2 for Computer Networks, due today"));
  assert(allSpeech.includes("Quiz 3 for Probability and Statistics, due tomorrow"));
  assert(allSpeech.includes("and 1 more."));

  const tomorrowOnlySpeech = formatWhatsDueSpeech(fiveTasks, todayIso, "2026-11-11", 4);
  assert.strictEqual(
    tomorrowOnlySpeech,
    "You have 1 pending task: Quiz 3 for Probability and Statistics, due tomorrow."
  );

  const emptySpeech = formatWhatsDueSpeech([], todayIso, null, 4);
  assert.strictEqual(emptySpeech, "You have no pending tasks.");
});

// ----------------------------------------------------------------------------
// 6. Phase 2: `validateTaskDeadline`, `sanitizeTaskTitle` & `buildTaskRecord`
// ----------------------------------------------------------------------------

test("validateTaskDeadline rejects partial dates and past dates, allows today & future", () => {
  const todayIso = "2026-11-10";
  assert.strictEqual(validateTaskDeadline("2026-W46", todayIso).valid, false);
  assert.strictEqual(validateTaskDeadline("2026-11", todayIso).valid, false);
  assert.strictEqual(validateTaskDeadline("2026-W46-WE", todayIso).valid, false);
  assert.strictEqual(validateTaskDeadline("2026-11-09", todayIso).valid, false);
  assert.strictEqual(validateTaskDeadline("2026-11-09", todayIso).reason, "PAST_DATE");
  assert.strictEqual(validateTaskDeadline("2026-11-10", todayIso).valid, true);
  assert.strictEqual(validateTaskDeadline("2026-11-14", todayIso).valid, true);
});

test("sanitizeTaskTitle and buildTaskRecord match PhoneTaskAndThemeManager.kt schema", () => {
  const dirty = "   Chapter 5\n\tproblem set   ";
  assert.strictEqual(sanitizeTaskTitle(dirty), "Chapter 5 problem set");
  assert.strictEqual(sanitizeTaskTitle("a".repeat(120)).length, 80);

  const record = buildTaskRecord(
    "-Nxyz987",
    "Chapter 5 problem set",
    "computer_networks",
    "2026-11-14",
    1762770000000
  );
  assert.deepStrictEqual(record, {
    id: "-Nxyz987",
    title: "Chapter 5 problem set",
    subjectId: "computer_networks",
    deadline: "2026-11-14",
    completed: false,
    createdAt: 1762770000000,
    completedAt: null,
  });
});

// ----------------------------------------------------------------------------
// 7. Phase 2: Catalog synonym matching
// ----------------------------------------------------------------------------

test("Catalog synonyms resolve subjects, themes, and music modes accurately", () => {
  assert.strictEqual(matchCatalogEntry(SUBJECT_CATALOG, "CN").id, "computer_networks");
  assert.strictEqual(matchCatalogEntry(SUBJECT_CATALOG, "OS").id, "operating_systems");
  assert.strictEqual(matchCatalogEntry(SUBJECT_CATALOG, "stats").id, "probability_statistics");
  assert.strictEqual(matchCatalogEntry(SUBJECT_CATALOG, "GM").id, "general_management");
  assert.strictEqual(matchCatalogEntry(SUBJECT_CATALOG, "capstone").id, "capstone_project");

  assert.strictEqual(matchCatalogEntry(CLOCK_THEME_CATALOG, "Cyber Neon").id, "cyber_neon");
  assert.strictEqual(matchCatalogEntry(CLOCK_THEME_CATALOG, "Ocean Blue").id, "ocean_blue");
  assert.strictEqual(matchCatalogEntry(CLOCK_THEME_CATALOG, "Night Dim").id, "night_dim");

  assert.strictEqual(matchCatalogEntry(MUSIC_MODE_CATALOG, "start").id, "on");
  assert.strictEqual(matchCatalogEntry(MUSIC_MODE_CATALOG, "back to dashboard").id, "off");
  assert.strictEqual(matchCatalogEntry(MUSIC_MODE_CATALOG, "auto").id, "auto");
});

console.log(`\nAll ${passed} tests passed!`);
