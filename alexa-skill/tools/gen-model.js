"use strict";

/**
 * Smart Desk Companion — Interaction Model Generator (V8 Phase 1 & Phase 2)
 *
 * Generates `interactionModel.en-IN.json` directly from the discovered
 * `SubjectCatalog.kt` (10 subjects) and `ClockThemeCatalog.kt` (10 themes),
 * plus `MusicModeType` (`on`, `off`, `auto`).
 *
 * Usage:
 *   node alexa-skill/tools/gen-model.js          # prints full Phase 2 model
 *   node alexa-skill/tools/gen-model.js --write  # writes interactionModel.en-IN.json
 */

const fs = require("fs");
const path = require("path");

// Discovered from `shared/src/main/java/com/smartdesk/companion/shared/SubjectCatalog.kt`
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
    synonyms: ["mentoring", "hed", "h e d", "mentoring and h e d"],
  },
  {
    id: "others",
    name: "Others",
    synonyms: ["other", "general", "misc", "miscellaneous"],
  },
];

// Discovered from `shared/src/main/java/com/smartdesk/companion/shared/ClockThemeCatalog.kt`
const CLOCK_THEME_CATALOG = [
  {
    id: "ocean_blue",
    name: "Ocean Blue",
    synonyms: ["ocean", "blue"],
  },
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

// V8 Section 5.6: Music & Clock Mode Override slot values
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

function buildPhase2InteractionModel() {
  return {
    interactionModel: {
      languageModel: {
        invocationName: "smart desk",
        intents: [
          { name: "AMAZON.CancelIntent", samples: [] },
          { name: "AMAZON.HelpIntent", samples: [] },
          { name: "AMAZON.StopIntent", samples: [] },
          { name: "AMAZON.FallbackIntent", samples: [] },
          { name: "AMAZON.NavigateHomeIntent", samples: [] },
          { name: "AMAZON.YesIntent", samples: [] },
          { name: "AMAZON.NoIntent", samples: [] },
          {
            name: "ShowTimetableIntent",
            slots: [],
            samples: [
              "show my timetable",
              "show the timetable",
              "show timetable",
              "put the timetable on the tablet",
              "timetable",
              "open my timetable",
              "display my timetable",
              "show my class schedule",
              "show my schedule",
            ],
          },
          {
            name: "AddTaskIntent",
            slots: [
              {
                name: "taskTitle",
                type: "AMAZON.SearchQuery",
              },
              {
                name: "subject",
                type: "SubjectType",
              },
              {
                name: "deadline",
                type: "AMAZON.DATE",
              },
            ],
            samples: [
              "add a task",
              "add task",
              "create a task",
              "new task",
              "add homework",
              "add assignment",
              "add task {taskTitle}",
              "create task {taskTitle}",
              "new task {taskTitle}",
              "the task is {taskTitle}",
              "task is {taskTitle}",
              "title is {taskTitle}",
              "it is {taskTitle}",
              "called {taskTitle}",
              "task {taskTitle}",
              "for {subject}",
              "subject {subject}",
              "subject is {subject}",
              "in {subject}",
              "{subject}",
              "due {deadline}",
              "due on {deadline}",
              "on {deadline}",
              "by {deadline}",
              "deadline {deadline}",
              "deadline is {deadline}",
              "for {subject} due {deadline}",
              "for {subject} on {deadline}",
            ],
          },
          {
            name: "TaskDateOnlyIntent",
            slots: [
              {
                name: "deadline",
                type: "AMAZON.DATE",
              },
            ],
            samples: ["{deadline}"],
          },
          {
            name: "WhatsDueIntent",
            slots: [
              {
                name: "day",
                type: "AMAZON.DATE",
              },
            ],
            samples: [
              "what's due",
              "what is due",
              "whats due",
              "what homework do I have",
              "what tasks do I have",
              "what tasks are due",
              "my pending tasks",
              "read my tasks",
              "what's due {day}",
              "what is due {day}",
              "whats due {day}",
              "what's due on {day}",
              "what is due on {day}",
              "what tasks are due {day}",
              "what homework is due {day}",
            ],
          },
          {
            name: "SetThemeIntent",
            slots: [
              {
                name: "themeName",
                type: "ThemeType",
              },
            ],
            samples: [
              "set clock theme to {themeName}",
              "set theme to {themeName}",
              "change theme to {themeName}",
              "change clock theme to {themeName}",
              "switch theme to {themeName}",
              "use {themeName} theme",
              "theme {themeName}",
            ],
          },
          {
            name: "PhoneBatteryIntent",
            slots: [],
            samples: [
              "phone battery",
              "what is my phone battery",
              "what's my phone battery",
              "how much battery does my phone have",
              "check phone battery",
              "battery level",
            ],
          },
          {
            name: "PcStatsIntent",
            slots: [],
            samples: [
              "pc stats",
              "laptop stats",
              "how is my laptop",
              "check pc stats",
              "cpu and ram",
              "what are my pc stats",
              "computer stats",
            ],
          },
          {
            name: "MusicModeIntent",
            slots: [
              {
                name: "mode",
                type: "MusicModeType",
              },
            ],
            samples: [
              "turn music mode {mode}",
              "set music mode to {mode}",
              "music mode {mode}",
              "{mode} music mode",
              "switch music mode {mode}",
            ],
          },
          {
            name: "ClearNotificationsIntent",
            slots: [],
            samples: [
              "clear notifications",
              "clear the tablet",
              "dismiss everything",
              "clear all notifications",
              "clear tablet notifications",
              "dismiss all notifications",
            ],
          },
        ],
        types: [
          {
            name: "SubjectType",
            values: SUBJECT_CATALOG.map((sub) => ({
              id: sub.id,
              name: {
                value: sub.name,
                synonyms: sub.synonyms,
              },
            })),
          },
          {
            name: "ThemeType",
            values: CLOCK_THEME_CATALOG.map((theme) => ({
              id: theme.id,
              name: {
                value: theme.name,
                synonyms: theme.synonyms,
              },
            })),
          },
          {
            name: "MusicModeType",
            values: MUSIC_MODE_CATALOG.map((m) => ({
              id: m.id,
              name: {
                value: m.name,
                synonyms: m.synonyms,
              },
            })),
          },
        ],
      },
    },
  };
}

if (require.main === module) {
  const model = buildPhase2InteractionModel();
  const json = JSON.stringify(model, null, 2) + "\n";
  if (process.argv.includes("--write")) {
    const outPath = path.join(__dirname, "..", "interactionModel.en-IN.json");
    fs.writeFileSync(outPath, json, "utf8");
    console.log(`Wrote ${outPath}`);
  } else {
    process.stdout.write(json);
  }
}

module.exports = {
  SUBJECT_CATALOG,
  CLOCK_THEME_CATALOG,
  MUSIC_MODE_CATALOG,
  buildPhase2InteractionModel,
};
