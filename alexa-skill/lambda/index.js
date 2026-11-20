"use strict";

/**
 * Smart Desk Companion — V8 Alexa-Hosted Skill (Phase 1 + Phase 2 Complete)
 *
 * Single-file Node.js skill that communicates with the existing Firebase
 * Realtime Database over its REST API.
 * Uses Node's built-in `https` module so it works on both Node 16
 * (Alexa-hosted default) and Node 18/20+ with zero extra dependencies.
 *
 * Supported Intents:
 * - Phase 1:
 *   1. `ShowTimetableIntent` ("show my timetable")
 * - Phase 2:
 *   2. `AddTaskIntent` + `TaskDateOnlyIntent` + `AMAZON.YesIntent` / `AMAZON.NoIntent` ("add a task")
 *   3. `WhatsDueIntent` ("what's due", "what's due tomorrow")
 *   4. `SetThemeIntent` ("set clock theme to Cyber Neon")
 *   5. `PhoneBatteryIntent` ("what's my phone battery")
 *   6. `PcStatsIntent` ("pc stats", "how is my laptop")
 *   7. `MusicModeIntent` ("turn music mode on / off / auto")
 *   8. `ClearNotificationsIntent` ("clear notifications", "clear the tablet")
 *
 * Security & privacy guarantee (V8 Section 9):
 * - This skill NEVER reads or logs `notifications` (only deletes it on ClearNotificationsIntent)
 *   and NEVER reads, logs, or speaks `config/batteryAutomation`.
 * - Error logs record only the intent name and error code.
 */

const crypto = require("crypto");
const https = require("https");
const { URL } = require("url");

// ============================================================================
// 1. TOP-LEVEL CONFIGURATION BLOCK (V8 Section 0.6, Section 3.1 & Section 12)
// ============================================================================
const CONFIG = {
  // Replace or set FIREBASE_DB_URL env var if your database URL ever changes.
  // No trailing slash.
  DB_URL: (
    process.env.FIREBASE_DB_URL ||
    "https://smart-desk-companion-cf7fd-default-rtdb.asia-southeast1.firebasedatabase.app"
  ).replace(/\/+$/, ""),

  // How long the timetable overlay stays visible on the tablet (seconds)
  DURATION_SEC: 20,

  // Hour of day (0-23 in IST) at/after which "show my timetable" previews the next day
  CUTOFF_HOUR: 16,

  // Staleness thresholds & task spoken limit (V8 Section 3.1)
  BATTERY_STALE_MIN: 10,
  PC_STALE_SEC: 45,
  TASK_SPEAK_LIMIT: 4,

  // Per-request timeout for Firebase REST calls (milliseconds)
  FIREBASE_TIMEOUT_MS: 4000,

  // Fixed offset for India Standard Time (UTC+05:30 = +330 minutes)
  IST_OFFSET_MS: (5 * 60 + 30) * 60 * 1000,

  // Task title max length (V8 Section 5.1)
  MAX_TASK_TITLE_LENGTH: 80,
};

// ============================================================================
// 2. CATALOGS (Exact Parity with SubjectCatalog.kt & ClockThemeCatalog.kt)
// ============================================================================

const SUBJECT_CATALOG = [
  {
    id: "computer_networks",
    name: "Computer Networks",
    synonyms: ["cn", "c n", "networks", "computer network"],
  },
  {
    id: "probability_statistics",
    name: "Probability and Statistics",
    synonyms: [
      "probability",
      "statistics",
      "prob and stats",
      "stats",
      "p and s",
      "maths",
      "probability & statistics",
    ],
  },
  {
    id: "operating_systems",
    name: "Operating Systems",
    synonyms: ["os", "o s", "operating system"],
  },
  {
    id: "oop",
    name: "Object Oriented Programming",
    synonyms: ["oops", "object oriented", "o o p", "java"],
  },
  {
    id: "cloud_engineering",
    name: "Cloud Engineering",
    synonyms: ["cloud", "cloud computing"],
  },
  {
    id: "general_management",
    name: "General Management Minor",
    synonyms: [
      "gm",
      "g m",
      "general management",
      "management",
      "management minor",
    ],
  },
  {
    id: "capstone_project",
    name: "Capstone Project",
    synonyms: ["capstone", "project", "major project"],
  },
  {
    id: "placement_training",
    name: "Placement Training",
    synonyms: ["placement", "placements", "training"],
  },
  {
    id: "mentoring_hed",
    name: "Mentoring and HED",
    synonyms: [
      "mentoring",
      "hed",
      "h e d",
      "mentoring and h e d",
      "mentoring & hed",
    ],
  },
  {
    id: "others",
    name: "Others",
    synonyms: ["other", "general", "misc", "miscellaneous"],
  },
];

const CLOCK_THEME_CATALOG = [
  { id: "ocean_blue", name: "Ocean Blue", synonyms: ["ocean", "blue"] },
  {
    id: "sunset_float",
    name: "Sunset Float",
    synonyms: ["sunset", "orange", "default theme"],
  },
  {
    id: "aurora",
    name: "Aurora",
    synonyms: ["aurora green", "teal", "northern lights"],
  },
  {
    id: "rose_quartz",
    name: "Rose Quartz",
    synonyms: ["rose", "pink", "quartz"],
  },
  {
    id: "emerald",
    name: "Emerald",
    synonyms: ["emerald green", "green"],
  },
  {
    id: "amber_glow",
    name: "Amber Glow",
    synonyms: ["amber", "gold", "yellow"],
  },
  {
    id: "violet_dusk",
    name: "Violet Dusk",
    synonyms: ["violet", "purple", "dusk"],
  },
  {
    id: "cyber_neon",
    name: "Cyber Neon",
    synonyms: ["cyber", "neon", "cyan"],
  },
  {
    id: "mono_ice",
    name: "Mono Ice",
    synonyms: ["mono", "ice", "white", "monochrome"],
  },
  {
    id: "night_dim",
    name: "Night Dim",
    synonyms: ["night", "dim", "red", "dark"],
  },
];

const MUSIC_MODE_CATALOG = [
  {
    id: "on",
    name: "on",
    synonyms: ["start", "enable", "turn on", "music", "vinyl"],
  },
  {
    id: "off",
    name: "off",
    synonyms: [
      "stop",
      "disable",
      "turn off",
      "normal",
      "back to dashboard",
      "dashboard",
    ],
  },
  {
    id: "auto",
    name: "auto",
    synonyms: [
      "automatic",
      "follow phone",
      "do not disturb",
      "dnd",
      "default",
    ],
  },
];

function findSubjectById(subjectId) {
  return (
    SUBJECT_CATALOG.find((s) => s.id === subjectId) ||
    SUBJECT_CATALOG[SUBJECT_CATALOG.length - 1]
  );
}

function matchCatalogEntry(catalog, rawInput) {
  if (!rawInput || typeof rawInput !== "string") return null;
  const cleaned = rawInput
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!cleaned) return null;

  for (const entry of catalog) {
    if (entry.id.toLowerCase() === cleaned) return entry;
    if (entry.name.toLowerCase() === cleaned) return entry;
    for (const syn of entry.synonyms || []) {
      if (syn.toLowerCase() === cleaned) return entry;
    }
  }
  // Substring fallback if unambiguous
  for (const entry of catalog) {
    if (cleaned.includes(entry.name.toLowerCase())) return entry;
    for (const syn of entry.synonyms || []) {
      if (syn.length >= 3 && cleaned.includes(syn.toLowerCase())) return entry;
    }
  }
  return null;
}

/**
 * Extracts a resolved slot entry from an Alexa Intent slot object (checks
 * Entity Resolution first, then falls back to raw spoken value matching).
 */
function resolveSlotFromCatalog(slotObj, catalog) {
  if (!slotObj) return null;

  const resolutions =
    slotObj.resolutions && slotObj.resolutions.resolutionsPerAuthority;
  if (Array.isArray(resolutions)) {
    for (const auth of resolutions) {
      if (
        auth &&
        auth.status &&
        auth.status.code === "ER_SUCCESS_MATCH" &&
        Array.isArray(auth.values) &&
        auth.values.length > 0
      ) {
        const matchedId =
          auth.values[0].value && auth.values[0].value.id;
        const matchedName =
          auth.values[0].value && auth.values[0].value.name;
        const byId = catalog.find((item) => item.id === matchedId);
        if (byId) return byId;
        const byName = matchCatalogEntry(catalog, matchedName);
        if (byName) return byName;
      }
    }
  }

  if (slotObj.value) {
    return matchCatalogEntry(catalog, slotObj.value);
  }
  return null;
}

// ============================================================================
// 3. IST TIME & TIMETABLE HIGHLIGHT LOGIC (Exact Parity with TimetableData.kt)
// ============================================================================

const DAY_NAMES_BY_UTC_INDEX = [
  "SUNDAY",
  "MONDAY",
  "TUESDAY",
  "WEDNESDAY",
  "THURSDAY",
  "FRIDAY",
  "SATURDAY",
];

const SHORT_CODE_BY_DAY_NAME = {
  MONDAY: "MON",
  TUESDAY: "TUE",
  WEDNESDAY: "WED",
  THURSDAY: "THU",
  FRIDAY: "FRI",
  SATURDAY: "SAT",
};

const SPOKEN_DAY_BY_DAY_NAME = {
  SUNDAY: "Sunday",
  MONDAY: "Monday",
  TUESDAY: "Tuesday",
  WEDNESDAY: "Wednesday",
  THURSDAY: "Thursday",
  FRIDAY: "Friday",
  SATURDAY: "Saturday",
};

const MONTH_NAMES_FULL = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

/**
 * Converts a Date object, ISO string, or epoch millisecond timestamp into
 * India Standard Time (UTC+05:30) calendar & clock components, regardless of
 * the Lambda container's system timezone.
 */
function toIstComponents(inputDateOrMs = Date.now()) {
  const utcMs =
    inputDateOrMs instanceof Date
      ? inputDateOrMs.getTime()
      : typeof inputDateOrMs === "string"
      ? Date.parse(inputDateOrMs)
      : Number(inputDateOrMs);

  if (Number.isNaN(utcMs)) {
    throw new Error("Invalid date/timestamp passed to toIstComponents");
  }

  const istShifted = new Date(utcMs + CONFIG.IST_OFFSET_MS);
  const year = istShifted.getUTCFullYear();
  const month = istShifted.getUTCMonth() + 1; // 1..12
  const dayOfMonth = istShifted.getUTCDate(); // 1..31
  const utcDayIndex = istShifted.getUTCDay(); // 0 (Sun) .. 6 (Sat)
  const dayOfWeek = DAY_NAMES_BY_UTC_INDEX[utcDayIndex];
  const hour = istShifted.getUTCHours(); // 0..23
  const minute = istShifted.getUTCMinutes();
  const second = istShifted.getUTCSeconds();

  const isoDate = `${String(year).padStart(4, "0")}-${String(month).padStart(
    2,
    "0"
  )}-${String(dayOfMonth).padStart(2, "0")}`;

  return {
    utcMs,
    year,
    month,
    dayOfMonth,
    dayOfWeek,
    hour,
    minute,
    second,
    isoDate,
  };
}

/**
 * Returns the IST calendar components for the next calendar day (date + daysToAdd).
 */
function addIstDays(istComp, daysToAdd) {
  const baseUtcMidnightMs = Date.UTC(
    istComp.year,
    istComp.month - 1,
    istComp.dayOfMonth + daysToAdd,
    0,
    0,
    0
  );
  const shifted = new Date(baseUtcMidnightMs);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    dayOfMonth: shifted.getUTCDate(),
    dayOfWeek: DAY_NAMES_BY_UTC_INDEX[shifted.getUTCDay()],
  };
}

function isThirdSaturday(istDateComp) {
  return (
    istDateComp.dayOfWeek === "SATURDAY" &&
    istDateComp.dayOfMonth >= 15 &&
    istDateComp.dayOfMonth <= 21
  );
}

function isNoClassDay(istDateComp) {
  return istDateComp.dayOfWeek === "SUNDAY" || isThirdSaturday(istDateComp);
}

/**
 * Exact JavaScript port of `TimetableData.resolveHighlight` from
 * `shared/src/main/java/com/smartdesk/companion/shared/TimetableData.kt`.
 */
function resolveHighlight(
  inputDateOrMs = Date.now(),
  cutoffHour = CONFIG.CUTOFF_HOUR
) {
  const todayIst = toIstComponents(inputDateOrMs);

  // Rule 1: Today is Sunday or 3rd Saturday -> FULL_WEEK
  if (isNoClassDay(todayIst)) {
    return {
      mode: "FULL_WEEK",
      highlightDay: null,
      day: null,
      spokenText: "Showing the full week's timetable on your tablet.",
      ist: todayIst,
    };
  }

  // Rule 2: Before cutoffHour (16:00 IST) -> TODAY
  if (todayIst.hour < cutoffHour) {
    const shortCode = SHORT_CODE_BY_DAY_NAME[todayIst.dayOfWeek] || null;
    return {
      mode: "TODAY",
      highlightDay: todayIst.dayOfWeek,
      day: shortCode,
      spokenText: "Showing today's timetable on your tablet.",
      ist: todayIst,
    };
  }

  // Rule 3: At or after cutoffHour (16:00 IST) -> inspect tomorrow
  const tomorrowIst = addIstDays(todayIst, 1);
  if (isNoClassDay(tomorrowIst)) {
    return {
      mode: "FULL_WEEK",
      highlightDay: null,
      day: null,
      spokenText: "Showing the full week's timetable on your tablet.",
      ist: todayIst,
    };
  }

  const tomorrowShortCode =
    SHORT_CODE_BY_DAY_NAME[tomorrowIst.dayOfWeek] || null;
  const spokenDay =
    SPOKEN_DAY_BY_DAY_NAME[tomorrowIst.dayOfWeek] || "tomorrow";
  return {
    mode: "TOMORROW",
    highlightDay: tomorrowIst.dayOfWeek,
    day: tomorrowShortCode,
    spokenText: `Showing ${spokenDay}'s timetable on your tablet.`,
    ist: todayIst,
  };
}

function buildTimetableCommandPayload(decision, options = {}) {
  const requestId =
    options.requestId ||
    (typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`);
  const clientTimestampMs =
    typeof options.clientTimestampMs === "number"
      ? options.clientTimestampMs
      : Date.now();
  const durationSec =
    typeof options.durationSec === "number"
      ? options.durationSec
      : CONFIG.DURATION_SEC;

  return {
    requestId,
    requestedAt: { ".sv": "timestamp" },
    clientTimestampMs,
    mode: decision.mode,
    highlightDay: decision.highlightDay,
    day: decision.day,
    durationSec,
  };
}

// ============================================================================
// 4. PHASE 2 TASK LOGIC (Exact Parity with TaskScheduleLogic.kt)
// ============================================================================

/**
 * Sanitizes a voice-captured task title (V8 Section 5.1 & Section 9):
 * - Removes control characters
 * - Trims whitespace
 * - Limits to maximum 80 characters
 */
function sanitizeTaskTitle(rawTitle) {
  if (typeof rawTitle !== "string") return "";
  return rawTitle
    .replace(/[\x00-\x1F\x7F]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, CONFIG.MAX_TASK_TITLE_LENGTH);
}

/**
 * Parses a full ISO calendar date (`yyyy-MM-dd`) into `{ year, month, day, utcMidnightMs }`.
 * Returns `null` for partial `AMAZON.DATE` values such as `2026-W46`, `2026-11`,
 * `2026-W46-WE`, `XXXX-11-10`, or invalid calendar dates like `2026-02-30`.
 */
function parseFullCalendarDate(isoDateStr) {
  if (typeof isoDateStr !== "string") return null;
  const trimmed = isoDateStr.trim();
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(trimmed);
  if (!match) return null;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;

  const utcMidnightMs = Date.UTC(year, month - 1, day, 0, 0, 0);
  const check = new Date(utcMidnightMs);
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() + 1 !== month ||
    check.getUTCDate() !== day
  ) {
    return null;
  }

  return {
    iso: trimmed,
    year,
    month,
    day,
    dayOfWeek: DAY_NAMES_BY_UTC_INDEX[check.getUTCDay()],
    utcMidnightMs,
  };
}

/**
 * Validates an `AMAZON.DATE` slot value for `AddTaskIntent` (V8 Section 5.1):
 * - Must be a full calendar date (`yyyy-MM-dd`), rejects partial values (`2026-W46`, `2026-11`, etc.).
 * - Must not be before `todayIstIso` (`today` is allowed, as on the phone).
 */
function validateTaskDeadline(rawAmazonDate, todayIstIso) {
  const parsed = parseFullCalendarDate(rawAmazonDate);
  if (!parsed) {
    return {
      valid: false,
      reason: "PARTIAL_OR_INVALID",
    };
  }
  const todayParsed = parseFullCalendarDate(todayIstIso);
  if (todayParsed && parsed.utcMidnightMs < todayParsed.utcMidnightMs) {
    return {
      valid: false,
      reason: "PAST_DATE",
    };
  }
  return {
    valid: true,
    iso: parsed.iso,
    parsed,
  };
}

/**
 * Formats an ISO date (`yyyy-MM-dd`) as `<Weekday, d Month>` for spoken output,
 * e.g. `"Saturday, 14 November"`.
 */
function formatSpokenWeekdayAndDate(isoDateStr) {
  const parsed = parseFullCalendarDate(isoDateStr);
  if (!parsed) return isoDateStr;
  const weekday = SPOKEN_DAY_BY_DAY_NAME[parsed.dayOfWeek] || "";
  const monthName = MONTH_NAMES_FULL[parsed.month - 1] || "";
  return `${weekday}, ${parsed.day} ${monthName}`;
}

/**
 * Exact JavaScript port of `TaskScheduleLogic.sortPendingTasks` from
 * `shared/src/main/java/com/smartdesk/companion/shared/TaskModelsAndLogic.kt`.
 *
 * - Keeps only tasks with `completed === false` and non-blank `title`.
 * - Sorts ascending by `deadline` date (overdue first, then nearest deadline;
 *   invalid/missing deadlines sort to `LocalDate.MAX`).
 * - Ties broken by `createdAt` ascending (oldest first).
 */
function sortPendingTasks(tasksMapOrArray) {
  const list = Array.isArray(tasksMapOrArray)
    ? tasksMapOrArray
    : tasksMapOrArray && typeof tasksMapOrArray === "object"
    ? Object.entries(tasksMapOrArray).map(([key, val]) => ({
        ...(val || {}),
        id: (val && val.id) || key,
      }))
    : [];

  return list
    .filter((item) => {
      if (!item || typeof item !== "object") return false;
      const title = typeof item.title === "string" ? item.title.trim() : "";
      if (!title) return false;
      return item.completed !== true;
    })
    .map((item) => ({
      id: String(item.id || ""),
      title: String(item.title).trim(),
      subjectId:
        typeof item.subjectId === "string" && item.subjectId.trim()
          ? item.subjectId.trim()
          : "others",
      deadline: typeof item.deadline === "string" ? item.deadline.trim() : "",
      completed: false,
      createdAt: Number(item.createdAt || 0),
      completedAt: item.completedAt ?? null,
    }))
    .sort((a, b) => {
      const parsedA = parseFullCalendarDate(a.deadline);
      const parsedB = parseFullCalendarDate(b.deadline);
      const msA = parsedA ? parsedA.utcMidnightMs : Number.MAX_SAFE_INTEGER;
      const msB = parsedB ? parsedB.utcMidnightMs : Number.MAX_SAFE_INTEGER;
      if (msA !== msB) return msA - msB;
      return a.createdAt - b.createdAt;
    });
}

/**
 * Exact JavaScript port of `TaskScheduleLogic.evaluateDeadline` from
 * `shared/src/main/java/com/smartdesk/companion/shared/TaskModelsAndLogic.kt`.
 */
function evaluateTaskDeadline(deadlineIso, todayIstIso) {
  const target = parseFullCalendarDate(deadlineIso);
  const today = parseFullCalendarDate(todayIstIso);
  if (!target || !today) {
    return {
      label: deadlineIso,
      spokenDue: `due ${deadlineIso}`,
      isUrgentRed: false,
      daysUntilDeadline: Number.MAX_SAFE_INTEGER,
    };
  }

  const MS_PER_DAY = 24 * 60 * 60 * 1000;
  const daysUntil = Math.round(
    (target.utcMidnightMs - today.utcMidnightMs) / MS_PER_DAY
  );

  let label;
  let spokenDue;
  if (daysUntil < 0) {
    label = "Overdue";
    spokenDue = "overdue";
  } else if (daysUntil === 0) {
    label = "Today";
    spokenDue = "due today";
  } else if (daysUntil === 1) {
    label = "Tomorrow";
    spokenDue = "due tomorrow";
  } else {
    const formatted = formatSpokenWeekdayAndDate(deadlineIso);
    label = formatted;
    spokenDue = `due ${formatted}`;
  }

  const isUrgentRed = daysUntil <= 1;

  return {
    label,
    spokenDue,
    isUrgentRed,
    daysUntilDeadline: daysUntil,
  };
}

/**
 * Builds the spoken summary for `WhatsDueIntent` (V8 Section 5.2).
 * - With no `filterDateIso`: speaks up to `TASK_SPEAK_LIMIT` (4) tasks with their
 *   subject and deadline wording (overdue, due today, due tomorrow, weekday),
 *   then `"and N more"`.
 * - With `filterDateIso`: speaks only tasks due that exact day.
 * - If none: `"You have no pending tasks."`
 */
function formatWhatsDueSpeech(
  tasksMapOrArray,
  todayIstIso,
  filterDateIso = null,
  limit = CONFIG.TASK_SPEAK_LIMIT
) {
  let pending = sortPendingTasks(tasksMapOrArray);

  if (filterDateIso) {
    pending = pending.filter((t) => t.deadline === filterDateIso);
  }

  if (pending.length === 0) {
    return "You have no pending tasks.";
  }

  const visible = pending.slice(0, limit);
  const remainingCount = pending.length - visible.length;

  const parts = visible.map((task) => {
    const subject = findSubjectById(task.subjectId);
    const deadlineInfo = evaluateTaskDeadline(task.deadline, todayIstIso);
    return `${task.title} for ${subject.name}, ${deadlineInfo.spokenDue}`;
  });

  let sentence =
    pending.length === 1
      ? `You have 1 pending task: ${parts[0]}.`
      : `You have ${pending.length} pending tasks: ${parts.join("; ")}.`;

  if (remainingCount > 0) {
    sentence = `${sentence.slice(0, -1)}, and ${remainingCount} more.`;
  }

  return sentence;
}

/**
 * Builds the exact task object written to `/tasks/<id>` (V8 Section 5.1).
 */
function buildTaskRecord(id, title, subjectId, deadlineIso, createdAtMs) {
  return {
    id: String(id),
    title: sanitizeTaskTitle(title),
    subjectId: findSubjectById(subjectId).id,
    deadline: String(deadlineIso),
    completed: false,
    createdAt: Number(createdAtMs),
    completedAt: null,
  };
}

// ============================================================================
// 5. FIREBASE REST HELPER (Node 16 + Node 18/20 compatible, 4s timeout, 1 retry)
// ============================================================================

function httpsJsonRequest(urlString, method, body, timeoutMs) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(urlString);
    const payloadString = body !== undefined ? JSON.stringify(body) : null;

    const reqOptions = {
      protocol: parsedUrl.protocol,
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || 443,
      path: parsedUrl.pathname + parsedUrl.search,
      method,
      headers: {
        Accept: "application/json",
      },
    };

    if (payloadString !== null) {
      reqOptions.headers["Content-Type"] = "application/json";
      reqOptions.headers["Content-Length"] = Buffer.byteLength(payloadString);
    }

    let settled = false;
    const req = https.request(reqOptions, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);

        const raw = Buffer.concat(chunks).toString("utf8");
        if (res.statusCode < 200 || res.statusCode >= 300) {
          const err = new Error(`HTTP_${res.statusCode}`);
          err.code = `HTTP_${res.statusCode}`;
          return reject(err);
        }
        if (!raw || !raw.trim()) {
          return resolve(null);
        }
        try {
          return resolve(JSON.parse(raw));
        } catch (_) {
          const err = new Error("INVALID_JSON");
          err.code = "INVALID_JSON";
          return reject(err);
        }
      });
    });

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      req.destroy();
      const timeoutErr = new Error("TIMEOUT");
      timeoutErr.code = "TIMEOUT";
      reject(timeoutErr);
    }, timeoutMs);

    req.on("error", (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (!err.code) err.code = "NETWORK_ERROR";
      reject(err);
    });

    if (payloadString !== null) {
      req.write(payloadString);
    }
    req.end();
  });
}

/**
 * Performs a Firebase REST request (`GET`, `PUT`, `PATCH`, `POST`, `DELETE`)
 * with a 4-second timeout and at most 1 retry on failure.
 */
async function firebaseRequest(
  method,
  relativePath,
  body = undefined,
  options = {}
) {
  const baseUrl = (options.dbUrl || CONFIG.DB_URL).replace(/\/+$/, "");
  const cleanPath = String(relativePath).replace(/^\/+/, "");
  const url = `${baseUrl}/${cleanPath}.json`;
  const timeoutMs = options.timeoutMs || CONFIG.FIREBASE_TIMEOUT_MS;

  let lastError = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      return await httpsJsonRequest(url, method, body, timeoutMs);
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError;
}

/**
 * Checks `/config/alexa/enabled.json`.
 * Missing or `null` counts as `true` (enabled by default).
 * Returns `false` only when explicitly set to `false` or `{ enabled: false }`.
 */
async function isAlexaEnabled(options = {}) {
  const value = await firebaseRequest(
    "GET",
    "config/alexa/enabled",
    undefined,
    options
  );
  if (value === false || (value && value.enabled === false)) {
    return false;
  }
  return true;
}

/**
 * Best-effort write to `/status/alexa.json`. Never throws.
 */
async function writeAlexaStatus(lastIntent, ok, error = null, options = {}) {
  try {
    await firebaseRequest(
      "PUT",
      "status/alexa",
      {
        lastIntent,
        lastAt: { ".sv": "timestamp" },
        ok: Boolean(ok),
        error: ok ? null : error || "unknown error",
      },
      options
    );
  } catch (_) {
    // Best-effort status reporting; ignore secondary errors.
  }
}

// ============================================================================
// 6. INTENT EXECUTION FUNCTIONS (Testable Directly Without Alexa SDK)
// ============================================================================

async function executeShowTimetable(options = {}) {
  const nowMs = typeof options.nowMs === "number" ? options.nowMs : Date.now();
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const decision = resolveHighlight(nowMs, CONFIG.CUTOFF_HOUR);
    const payload = buildTimetableCommandPayload(decision, {
      clientTimestampMs: nowMs,
      requestId: options.requestId,
      durationSec: CONFIG.DURATION_SEC,
    });

    await firebaseRequest("PUT", "commands/timetable", payload, options);
    await writeAlexaStatus("ShowTimetableIntent", true, null, options);

    return {
      speech: decision.spokenText,
      ok: true,
      decision,
      payload,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=ShowTimetableIntent code=${code}`);
    await writeAlexaStatus(
      "ShowTimetableIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

/**
 * Writes a confirmed task to Firebase (V8 Section 5.1):
 * 1. `POST /tasks.json` to get a Firebase push key (`{ name: "-N..." }`).
 * 2. `PUT /tasks/<pushKey>.json` with `id` set to `<pushKey>` and the exact
 *    phone field types (`id`, `title`, `subjectId`, `deadline`, `completed: false`,
 *    `createdAt`, `completedAt: null`).
 */
async function commitConfirmedTask(pendingTask, options = {}) {
  const nowMs = typeof options.nowMs === "number" ? options.nowMs : Date.now();
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const initialRecord = buildTaskRecord(
      "pending",
      pendingTask.title,
      pendingTask.subjectId,
      pendingTask.deadline,
      nowMs
    );

    const postRes = await firebaseRequest(
      "POST",
      "tasks",
      initialRecord,
      options
    );
    const pushKey =
      (postRes && typeof postRes.name === "string" && postRes.name.trim()) ||
      `task_${nowMs}`;

    // Validate pushKey contains only safe Firebase key chars before using in path (V8 Section 9)
    if (!/^[A-Za-z0-9_-]+$/.test(pushKey)) {
      const keyErr = new Error("INVALID_PUSH_KEY");
      keyErr.code = "INVALID_PUSH_KEY";
      throw keyErr;
    }

    const finalRecord = buildTaskRecord(
      pushKey,
      pendingTask.title,
      pendingTask.subjectId,
      pendingTask.deadline,
      nowMs
    );

    await firebaseRequest("PUT", `tasks/${pushKey}`, finalRecord, options);
    await writeAlexaStatus("AddTaskIntent", true, null, options);

    const subject = findSubjectById(finalRecord.subjectId);
    return {
      speech: `Added ${finalRecord.title} for ${subject.name}.`,
      ok: true,
      task: finalRecord,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=AddTaskIntent code=${code}`);
    await writeAlexaStatus(
      "AddTaskIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

async function executeWhatsDue(daySlotValue = null, options = {}) {
  const nowMs = typeof options.nowMs === "number" ? options.nowMs : Date.now();
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const todayIst = toIstComponents(nowMs);
    let filterDateIso = null;
    if (daySlotValue && String(daySlotValue).trim()) {
      const parsedDay = parseFullCalendarDate(String(daySlotValue).trim());
      if (!parsedDay) {
        return {
          speech:
            "Please ask for a specific day, like what's due today or what's due tomorrow.",
          ok: true,
        };
      }
      filterDateIso = parsedDay.iso;
    }

    const rawTasks = await firebaseRequest("GET", "tasks", undefined, options);
    const speech = formatWhatsDueSpeech(
      rawTasks,
      todayIst.isoDate,
      filterDateIso,
      CONFIG.TASK_SPEAK_LIMIT
    );

    await writeAlexaStatus("WhatsDueIntent", true, null, options);
    return {
      speech,
      ok: true,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=WhatsDueIntent code=${code}`);
    await writeAlexaStatus(
      "WhatsDueIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

async function executeSetTheme(themeSlotObjOrText, options = {}) {
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const resolvedTheme =
      typeof themeSlotObjOrText === "string"
        ? matchCatalogEntry(CLOCK_THEME_CATALOG, themeSlotObjOrText)
        : resolveSlotFromCatalog(themeSlotObjOrText, CLOCK_THEME_CATALOG);

    if (!resolvedTheme) {
      return {
        speech:
          "I didn't recognise that clock theme. Try Ocean Blue, Sunset Float, Aurora, Cyber Neon, or Night Dim.",
        ok: false,
      };
    }

    await firebaseRequest(
      "PUT",
      "config/clockTheme",
      resolvedTheme.id,
      options
    );
    await writeAlexaStatus("SetThemeIntent", true, null, options);

    return {
      speech: `Clock theme set to ${resolvedTheme.name}.`,
      ok: true,
      theme: resolvedTheme,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=SetThemeIntent code=${code}`);
    await writeAlexaStatus(
      "SetThemeIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

async function executePhoneBattery(options = {}) {
  const nowMs = typeof options.nowMs === "number" ? options.nowMs : Date.now();
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const data = await firebaseRequest(
      "GET",
      "phone/battery",
      undefined,
      options
    );
    const updatedAt = data && Number(data.updatedAt || 0);
    const level = data && typeof data.level === "number" ? Math.round(data.level) : null;
    const charging = Boolean(data && data.charging);
    const staleMs = CONFIG.BATTERY_STALE_MIN * 60 * 1000;

    let speech;
    if (
      !data ||
      level === null ||
      !updatedAt ||
      Math.abs(nowMs - updatedAt) > staleMs
    ) {
      speech = "I don't have a recent reading from your phone.";
    } else {
      speech = `Your phone is at ${level} percent and ${
        charging ? "charging" : "not charging"
      }.`;
    }

    await writeAlexaStatus("PhoneBatteryIntent", true, null, options);
    return {
      speech,
      ok: true,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=PhoneBatteryIntent code=${code}`);
    await writeAlexaStatus(
      "PhoneBatteryIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

async function executePcStats(options = {}) {
  const nowMs = typeof options.nowMs === "number" ? options.nowMs : Date.now();
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const data = await firebaseRequest("GET", "pc/stats", undefined, options);
    const updatedAt = data && Number(data.updatedAt || 0);
    const cpu = data && typeof data.cpu === "number" ? Math.round(data.cpu) : null;
    const ram = data && typeof data.ram === "number" ? Math.round(data.ram) : null;
    const temp =
      data && typeof data.temp === "number" ? Math.round(data.temp) : null;
    const staleMs = CONFIG.PC_STALE_SEC * 1000;

    let speech;
    if (
      !data ||
      cpu === null ||
      ram === null ||
      !updatedAt ||
      Math.abs(nowMs - updatedAt) > staleMs
    ) {
      speech = "Your laptop looks offline.";
    } else if (temp !== null) {
      speech = `Your laptop CPU is at ${cpu} percent, RAM is at ${ram} percent, and temperature is ${temp} degrees.`;
    } else {
      speech = `Your laptop CPU is at ${cpu} percent and RAM is at ${ram} percent.`;
    }

    await writeAlexaStatus("PcStatsIntent", true, null, options);
    return {
      speech,
      ok: true,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=PcStatsIntent code=${code}`);
    await writeAlexaStatus(
      "PcStatsIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

async function executeMusicMode(modeSlotObjOrText, options = {}) {
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    const resolvedMode =
      typeof modeSlotObjOrText === "string"
        ? matchCatalogEntry(MUSIC_MODE_CATALOG, modeSlotObjOrText)
        : resolveSlotFromCatalog(modeSlotObjOrText, MUSIC_MODE_CATALOG);

    if (!resolvedMode) {
      return {
        speech: "Please say turn music mode on, off, or auto.",
        ok: false,
      };
    }

    const payload = {
      mode: resolvedMode.id,
      updatedAt: { ".sv": "timestamp" },
    };

    await firebaseRequest("PUT", "config/musicModeOverride", payload, options);
    await writeAlexaStatus("MusicModeIntent", true, null, options);

    const speech =
      resolvedMode.id === "on"
        ? "Music and Clock mode turned on."
        : resolvedMode.id === "off"
        ? "Music and Clock mode turned off."
        : "Music and Clock mode set to automatic.";

    return {
      speech,
      ok: true,
      mode: resolvedMode.id,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=MusicModeIntent code=${code}`);
    await writeAlexaStatus(
      "MusicModeIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

async function executeClearNotifications(options = {}) {
  try {
    const enabled = await isAlexaEnabled(options);
    if (!enabled) {
      return {
        speech: "Alexa control is switched off in your phone settings.",
        ok: false,
        disabled: true,
      };
    }

    // V8 Section 5.7 & Section 9: Deletes ONLY the `notifications` node; never reads it
    // and never touches tasks, commands, or config.
    await firebaseRequest("DELETE", "notifications", undefined, options);
    await writeAlexaStatus("ClearNotificationsIntent", true, null, options);

    return {
      speech: "Cleared notifications on your tablet.",
      ok: true,
    };
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(
      `[SmartDeskSkill] intent=ClearNotificationsIntent code=${code}`
    );
    await writeAlexaStatus(
      "ClearNotificationsIntent",
      false,
      "could not reach Firebase",
      options
    );
    return {
      speech: "Sorry, I couldn't reach your tablet.",
      ok: false,
      error: code,
    };
  }
}

// ============================================================================
// 7. MULTI-TURN ADD TASK CONVERSATION STEPPER (V8 Section 5.1)
// ============================================================================

async function handleAddTaskTurn(handlerInput, incomingSlots = {}) {
  try {
    const enabled = await isAlexaEnabled();
    if (!enabled) {
      return handlerInput.responseBuilder
        .speak("Alexa control is switched off in your phone settings.")
        .withShouldEndSession(true)
        .getResponse();
    }
  } catch (err) {
    const code = (err && err.code) || "FIREBASE_ERROR";
    console.error(`[SmartDeskSkill] intent=AddTaskIntent code=${code}`);
    await writeAlexaStatus(
      "AddTaskIntent",
      false,
      "could not reach Firebase"
    );
    return handlerInput.responseBuilder
      .speak("Sorry, I couldn't reach your tablet.")
      .withShouldEndSession(true)
      .getResponse();
  }

  const sessionAttributes =
    handlerInput.attributesManager.getSessionAttributes() || {};
  const pending = sessionAttributes.pendingTask || {
    title: null,
    subjectId: null,
    subjectName: null,
    deadline: null,
    awaitingConfirmation: false,
  };

  // 1. Check incoming taskTitle slot
  if (incomingSlots.taskTitle && incomingSlots.taskTitle.value) {
    const cleaned = sanitizeTaskTitle(incomingSlots.taskTitle.value);
    if (cleaned) {
      pending.title = cleaned;
      pending.awaitingConfirmation = false;
    }
  }

  // 2. Check incoming subject slot
  if (incomingSlots.subject && incomingSlots.subject.value) {
    const matchedSubject = resolveSlotFromCatalog(
      incomingSlots.subject,
      SUBJECT_CATALOG
    );
    if (matchedSubject) {
      pending.subjectId = matchedSubject.id;
      pending.subjectName = matchedSubject.name;
      pending.awaitingConfirmation = false;
    } else if (pending.title && !pending.subjectId) {
      sessionAttributes.pendingTask = pending;
      handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
      return handlerInput.responseBuilder
        .speak(
          "I didn't catch that subject. Please say a subject like Computer Networks, Operating Systems, OOP, Cloud Engineering, or Others."
        )
        .reprompt("Which subject is this task for?")
        .withShouldEndSession(false)
        .getResponse();
    }
  }

  // 3. Check incoming deadline slot
  if (incomingSlots.deadline && incomingSlots.deadline.value) {
    const todayIst = toIstComponents(Date.now());
    const validation = validateTaskDeadline(
      incomingSlots.deadline.value,
      todayIst.isoDate
    );
    if (!validation.valid) {
      sessionAttributes.pendingTask = pending;
      handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
      const msg =
        validation.reason === "PAST_DATE"
          ? "That date is in the past. Please give today or a future due date."
          : "Please give a specific calendar day, like today, tomorrow, or Friday. When is it due?";
      return handlerInput.responseBuilder
        .speak(msg)
        .reprompt("When is the task due?")
        .withShouldEndSession(false)
        .getResponse();
    }
    pending.deadline = validation.iso;
    pending.awaitingConfirmation = false;
  }

  // Now check which piece is still needed in order: title -> subject -> deadline -> confirm
  if (!pending.title) {
    sessionAttributes.pendingTask = pending;
    handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
    return handlerInput.responseBuilder
      .speak("What is the task? Start with, the task is...")
      .reprompt("Say, the task is, followed by your task title.")
      .withShouldEndSession(false)
      .getResponse();
  }

  if (!pending.subjectId) {
    sessionAttributes.pendingTask = pending;
    handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
    return handlerInput.responseBuilder
      .speak(`Got it, ${pending.title}. Which subject is this for?`)
      .reprompt(
        "Which subject is this for? For example, Computer Networks, Operating Systems, or Others."
      )
      .withShouldEndSession(false)
      .getResponse();
  }

  if (!pending.deadline) {
    sessionAttributes.pendingTask = pending;
    handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
    return handlerInput.responseBuilder
      .speak(`For ${pending.subjectName}. When is it due?`)
      .reprompt("When is it due? You can say today, tomorrow, or a date.")
      .withShouldEndSession(false)
      .getResponse();
  }

  // All three fields are ready -> ask for confirmation before writing (V8 Section 5.1)
  pending.awaitingConfirmation = true;
  sessionAttributes.pendingTask = pending;
  handlerInput.attributesManager.setSessionAttributes(sessionAttributes);

  const spokenDate = formatSpokenWeekdayAndDate(pending.deadline);
  const confirmPrompt = `Add ${pending.title} for ${pending.subjectName}, due ${spokenDate}. Shall I?`;

  return handlerInput.responseBuilder
    .speak(confirmPrompt)
    .reprompt("Say yes to add the task, or no to cancel.")
    .withShouldEndSession(false)
    .getResponse();
}

// ============================================================================
// 8. ALEXA SKILL HANDLERS (ask-sdk-core)
// ============================================================================

const LaunchRequestHandler = {
  canHandle(handlerInput) {
    return handlerInput.requestEnvelope.request.type === "LaunchRequest";
  },
  async handle(handlerInput) {
    try {
      const enabled = await isAlexaEnabled();
      if (!enabled) {
        return handlerInput.responseBuilder
          .speak("Alexa control is switched off in your phone settings.")
          .withShouldEndSession(true)
          .getResponse();
      }
    } catch (err) {
      const code = (err && err.code) || "FIREBASE_ERROR";
      console.error(`[SmartDeskSkill] intent=LaunchRequest code=${code}`);
      return handlerInput.responseBuilder
        .speak("Sorry, I couldn't reach your tablet.")
        .withShouldEndSession(true)
        .getResponse();
    }

    const speakOutput =
      "Smart Desk is ready. You can say show my timetable, add a task, what's due, set clock theme, phone battery, or P.C. stats.";
    return handlerInput.responseBuilder
      .speak(speakOutput)
      .reprompt("Try saying show my timetable or what's due.")
      .getResponse();
  },
};

const ShowTimetableIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "ShowTimetableIntent"
    );
  },
  async handle(handlerInput) {
    const result = await executeShowTimetable();
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const AddTaskIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "AddTaskIntent"
    );
  },
  async handle(handlerInput) {
    const slots = handlerInput.requestEnvelope.request.intent.slots || {};
    return handleAddTaskTurn(handlerInput, slots);
  },
};

const TaskDateOnlyIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "TaskDateOnlyIntent"
    );
  },
  async handle(handlerInput) {
    const sessionAttributes =
      handlerInput.attributesManager.getSessionAttributes() || {};
    const slots = handlerInput.requestEnvelope.request.intent.slots || {};
    if (sessionAttributes.pendingTask) {
      return handleAddTaskTurn(handlerInput, slots);
    }
    // If not in the middle of adding a task, treat a bare date as "what's due on <date>"
    const dayVal = slots.deadline && slots.deadline.value;
    const result = await executeWhatsDue(dayVal);
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const YesIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "AMAZON.YesIntent"
    );
  },
  async handle(handlerInput) {
    const sessionAttributes =
      handlerInput.attributesManager.getSessionAttributes() || {};
    const pending = sessionAttributes.pendingTask;
    if (
      pending &&
      pending.awaitingConfirmation &&
      pending.title &&
      pending.subjectId &&
      pending.deadline
    ) {
      delete sessionAttributes.pendingTask;
      handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
      const result = await commitConfirmedTask(pending);
      return handlerInput.responseBuilder
        .speak(result.speech)
        .withShouldEndSession(true)
        .getResponse();
    }

    return handlerInput.responseBuilder
      .speak("There is no pending action to confirm. Try saying show my timetable or add a task.")
      .withShouldEndSession(true)
      .getResponse();
  },
};

const NoIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "AMAZON.NoIntent"
    );
  },
  handle(handlerInput) {
    const sessionAttributes =
      handlerInput.attributesManager.getSessionAttributes() || {};
    if (sessionAttributes.pendingTask) {
      delete sessionAttributes.pendingTask;
      handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
      return handlerInput.responseBuilder
        .speak("Okay, cancelled.")
        .withShouldEndSession(true)
        .getResponse();
    }
    return handlerInput.responseBuilder
      .speak("Okay.")
      .withShouldEndSession(true)
      .getResponse();
  },
};

const WhatsDueIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "WhatsDueIntent"
    );
  },
  async handle(handlerInput) {
    const slots = handlerInput.requestEnvelope.request.intent.slots || {};
    const dayVal = slots.day && slots.day.value ? slots.day.value : null;
    const result = await executeWhatsDue(dayVal);
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const SetThemeIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "SetThemeIntent"
    );
  },
  async handle(handlerInput) {
    const slots = handlerInput.requestEnvelope.request.intent.slots || {};
    const result = await executeSetTheme(slots.themeName);
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const PhoneBatteryIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "PhoneBatteryIntent"
    );
  },
  async handle(handlerInput) {
    const result = await executePhoneBattery();
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const PcStatsIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "PcStatsIntent"
    );
  },
  async handle(handlerInput) {
    const result = await executePcStats();
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const MusicModeIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "MusicModeIntent"
    );
  },
  async handle(handlerInput) {
    const slots = handlerInput.requestEnvelope.request.intent.slots || {};
    const result = await executeMusicMode(slots.mode);
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const ClearNotificationsIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name ===
        "ClearNotificationsIntent"
    );
  },
  async handle(handlerInput) {
    const result = await executeClearNotifications();
    return handlerInput.responseBuilder
      .speak(result.speech)
      .withShouldEndSession(true)
      .getResponse();
  },
};

const HelpIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name === "AMAZON.HelpIntent"
    );
  },
  handle(handlerInput) {
    const speakOutput =
      "You can say show my timetable, add a task, what's due, set clock theme to Cyber Neon, what's my phone battery, P.C. stats, turn music mode on, or clear notifications.";
    return handlerInput.responseBuilder
      .speak(speakOutput)
      .reprompt("What would you like to do?")
      .getResponse();
  },
};

const CancelAndStopIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      (handlerInput.requestEnvelope.request.intent.name ===
        "AMAZON.CancelIntent" ||
        handlerInput.requestEnvelope.request.intent.name ===
          "AMAZON.StopIntent")
    );
  },
  handle(handlerInput) {
    const sessionAttributes =
      handlerInput.attributesManager.getSessionAttributes() || {};
    delete sessionAttributes.pendingTask;
    handlerInput.attributesManager.setSessionAttributes(sessionAttributes);
    return handlerInput.responseBuilder
      .speak("Okay.")
      .withShouldEndSession(true)
      .getResponse();
  },
};

const FallbackIntentHandler = {
  canHandle(handlerInput) {
    return (
      handlerInput.requestEnvelope.request.type === "IntentRequest" &&
      handlerInput.requestEnvelope.request.intent.name ===
        "AMAZON.FallbackIntent"
    );
  },
  handle(handlerInput) {
    const sessionAttributes =
      handlerInput.attributesManager.getSessionAttributes() || {};
    if (sessionAttributes.pendingTask && !sessionAttributes.pendingTask.title) {
      return handlerInput.responseBuilder
        .speak(
          "To tell me the task name, start with the phrase: the task is..."
        )
        .reprompt("Say, the task is, followed by your task title.")
        .withShouldEndSession(false)
        .getResponse();
    }
    const speakOutput =
      "Sorry, I didn't catch that. Try saying show my timetable, add a task, or what's due.";
    return handlerInput.responseBuilder
      .speak(speakOutput)
      .reprompt("Try saying show my timetable.")
      .getResponse();
  },
};

const SessionEndedRequestHandler = {
  canHandle(handlerInput) {
    return handlerInput.requestEnvelope.request.type === "SessionEndedRequest";
  },
  handle(handlerInput) {
    return handlerInput.responseBuilder.getResponse();
  },
};

const ErrorHandler = {
  canHandle() {
    return true;
  },
  handle(handlerInput, error) {
    const req = handlerInput.requestEnvelope.request || {};
    const intentName =
      (req.intent && req.intent.name) || req.type || "UNKNOWN";
    const code = (error && error.code) || "UNHANDLED_ERROR";
    // V8 Section 9: Log only intent name and error code
    console.error(`[SmartDeskSkill] intent=${intentName} code=${code}`);
    return handlerInput.responseBuilder
      .speak("Sorry, I couldn't reach your tablet.")
      .withShouldEndSession(true)
      .getResponse();
  },
};

// Build the Lambda handler if `ask-sdk-core` is installed (Alexa-hosted Lambda environment)
let lambdaHandler = null;
try {
  const Alexa = require("ask-sdk-core");
  lambdaHandler = Alexa.SkillBuilders.custom()
    .addRequestHandlers(
      LaunchRequestHandler,
      ShowTimetableIntentHandler,
      AddTaskIntentHandler,
      TaskDateOnlyIntentHandler,
      YesIntentHandler,
      NoIntentHandler,
      WhatsDueIntentHandler,
      SetThemeIntentHandler,
      PhoneBatteryIntentHandler,
      PcStatsIntentHandler,
      MusicModeIntentHandler,
      ClearNotificationsIntentHandler,
      HelpIntentHandler,
      CancelAndStopIntentHandler,
      FallbackIntentHandler,
      SessionEndedRequestHandler
    )
    .addErrorHandlers(ErrorHandler)
    .lambda();
} catch (_) {
  // Allows local unit testing (`node alexa-skill/tests/test-timetable.js`) without npm install
}

exports.handler = lambdaHandler;
exports.CONFIG = CONFIG;
exports.SUBJECT_CATALOG = SUBJECT_CATALOG;
exports.CLOCK_THEME_CATALOG = CLOCK_THEME_CATALOG;
exports.MUSIC_MODE_CATALOG = MUSIC_MODE_CATALOG;
exports.matchCatalogEntry = matchCatalogEntry;
exports.resolveSlotFromCatalog = resolveSlotFromCatalog;
exports.toIstComponents = toIstComponents;
exports.isThirdSaturday = isThirdSaturday;
exports.isNoClassDay = isNoClassDay;
exports.resolveHighlight = resolveHighlight;
exports.buildTimetableCommandPayload = buildTimetableCommandPayload;
exports.sanitizeTaskTitle = sanitizeTaskTitle;
exports.parseFullCalendarDate = parseFullCalendarDate;
exports.validateTaskDeadline = validateTaskDeadline;
exports.formatSpokenWeekdayAndDate = formatSpokenWeekdayAndDate;
exports.sortPendingTasks = sortPendingTasks;
exports.evaluateTaskDeadline = evaluateTaskDeadline;
exports.formatWhatsDueSpeech = formatWhatsDueSpeech;
exports.buildTaskRecord = buildTaskRecord;
exports.firebaseRequest = firebaseRequest;
exports.isAlexaEnabled = isAlexaEnabled;
exports.writeAlexaStatus = writeAlexaStatus;
exports.executeShowTimetable = executeShowTimetable;
exports.commitConfirmedTask = commitConfirmedTask;
exports.executeWhatsDue = executeWhatsDue;
exports.executeSetTheme = executeSetTheme;
exports.executePhoneBattery = executePhoneBattery;
exports.executePcStats = executePcStats;
exports.executeMusicMode = executeMusicMode;
exports.executeClearNotifications = executeClearNotifications;
