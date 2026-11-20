package com.smartdesk.companion.shared

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * V5 Addendum (Section B & Section D):
 * Static timetable data for Semester IV, BTCS B and the highlight decision rules
 * shared between the Phone widget/app and the Tablet full-screen overlay.
 */
enum class TimetableMode {
    TODAY,
    TOMORROW,
    FULL_WEEK;

    companion object {
        fun fromWireValue(raw: String?): TimetableMode {
            return when (raw?.trim()?.uppercase(Locale.US)) {
                "TODAY" -> TODAY
                "TOMORROW" -> TOMORROW
                else -> FULL_WEEK
            }
        }
    }
}

data class PeriodHeader(
    val code: String,
    val timeRange: String,
    val isLunchBreak: Boolean = false
)

/**
 * Represents a slot in a day's row (either a 1-period slot, a merged 2-period slot,
 * or an empty free period).
 */
data class TimetableSlot(
    val startPeriod: Int, // 1..6
    val periodSpan: Int = 1, // 1 or 2
    val title: String?, // null = empty slot (e.g. Saturday P5, P6)
    val subjectId: String? = null,
    val isLab: Boolean = false
)

data class DayScheduleRow(
    val dayOfWeek: DayOfWeek,
    val shortCode: String, // "MON".."SAT"
    val morningSlots: List<TimetableSlot>, // Covers P1..P4 (total span = 4)
    val afternoonSlots: List<TimetableSlot> // Covers P5..P6 (total span = 2)
)

data class HighlightDecision(
    val mode: TimetableMode,
    val highlightDay: DayOfWeek?
) {
    /**
     * Caption shown on the Phone Home-Screen Widget:
     * "Today", "Tomorrow", or "Full week".
     */
    fun toWidgetCaption(): String {
        return when (mode) {
            TimetableMode.TODAY -> "Today"
            TimetableMode.TOMORROW -> "Tomorrow"
            TimetableMode.FULL_WEEK -> "Full week"
        }
    }

    /**
     * Caption shown at the top-left of the Tablet Timetable Overlay:
     * e.g., "Today, Fri", "Tomorrow, Sat", or "This week".
     */
    fun toTabletOverlayTitle(): String {
        val dayName = highlightDay?.getDisplayName(TextStyle.SHORT, Locale.US)
        return when {
            mode == TimetableMode.TODAY && dayName != null -> "Today, $dayName"
            mode == TimetableMode.TOMORROW && dayName != null -> "Tomorrow, $dayName"
            else -> "This week"
        }
    }

    /**
     * Short 3-letter day code ("MON".."SAT") or null for FULL_WEEK.
     */
    fun toShortDayCode(): String? {
        return TimetableData.dayOfWeekToShortCode(highlightDay)
    }
}

object TimetableData {

    const val SEMESTER_TITLE: String = "SEMESTER IV • BTCS B"

    val periodHeaders: List<PeriodHeader> = listOf(
        PeriodHeader(code = "P1", timeRange = "09:00–10:00"),
        PeriodHeader(code = "P2", timeRange = "10:00–11:00"),
        PeriodHeader(code = "P3", timeRange = "11:00–12:00"),
        PeriodHeader(code = "P4", timeRange = "12:00–13:00"),
        PeriodHeader(code = "LUNCH", timeRange = "13:00–14:00", isLunchBreak = true),
        PeriodHeader(code = "P5", timeRange = "14:00–15:00"),
        PeriodHeader(code = "P6", timeRange = "15:00–16:00")
    )

    /**
     * Section B (Source of Truth):
     * Weekly timetable for Semester IV, BTCS B (Monday to Saturday).
     */
    val weeklySchedule: List<DayScheduleRow> = listOf(
        // MONDAY
        DayScheduleRow(
            dayOfWeek = DayOfWeek.MONDAY,
            shortCode = "MON",
            morningSlots = listOf(
                TimetableSlot(
                    startPeriod = 1,
                    periodSpan = 1,
                    title = "Computer Networks",
                    subjectId = "cn"
                ),
                TimetableSlot(
                    startPeriod = 2,
                    periodSpan = 1,
                    title = "Probability and Statistics",
                    subjectId = "prob_stats"
                ),
                TimetableSlot(
                    startPeriod = 3,
                    periodSpan = 2,
                    title = "OS Lab",
                    subjectId = "os",
                    isLab = true
                )
            ),
            afternoonSlots = listOf(
                TimetableSlot(
                    startPeriod = 5,
                    periodSpan = 2,
                    title = "Placement Training",
                    subjectId = "placement"
                )
            )
        ),

        // TUESDAY
        DayScheduleRow(
            dayOfWeek = DayOfWeek.TUESDAY,
            shortCode = "TUE",
            morningSlots = listOf(
                TimetableSlot(
                    startPeriod = 1,
                    periodSpan = 1,
                    title = "Operating Systems",
                    subjectId = "os"
                ),
                TimetableSlot(
                    startPeriod = 2,
                    periodSpan = 1,
                    title = "Object Oriented Programming",
                    subjectId = "oop"
                ),
                TimetableSlot(
                    startPeriod = 3,
                    periodSpan = 1,
                    title = "HED",
                    subjectId = "mentoring_hed"
                ),
                TimetableSlot(
                    startPeriod = 4,
                    periodSpan = 1,
                    title = "General Management Minor",
                    subjectId = "gen_mgmt_minor"
                )
            ),
            afternoonSlots = listOf(
                TimetableSlot(
                    startPeriod = 5,
                    periodSpan = 2,
                    title = "OOP Lab",
                    subjectId = "oop",
                    isLab = true
                )
            )
        ),

        // WEDNESDAY
        DayScheduleRow(
            dayOfWeek = DayOfWeek.WEDNESDAY,
            shortCode = "WED",
            morningSlots = listOf(
                TimetableSlot(
                    startPeriod = 1,
                    periodSpan = 1,
                    title = "Object Oriented Programming",
                    subjectId = "oop"
                ),
                TimetableSlot(
                    startPeriod = 2,
                    periodSpan = 1,
                    title = "Probability and Statistics",
                    subjectId = "prob_stats"
                ),
                TimetableSlot(
                    startPeriod = 3,
                    periodSpan = 2,
                    title = "General Management Minor",
                    subjectId = "gen_mgmt_minor"
                )
            ),
            afternoonSlots = listOf(
                TimetableSlot(
                    startPeriod = 5,
                    periodSpan = 2,
                    title = "General Management Minor",
                    subjectId = "gen_mgmt_minor"
                )
            )
        ),

        // THURSDAY
        DayScheduleRow(
            dayOfWeek = DayOfWeek.THURSDAY,
            shortCode = "THU",
            morningSlots = listOf(
                TimetableSlot(
                    startPeriod = 1,
                    periodSpan = 1,
                    title = "Probability and Statistics",
                    subjectId = "prob_stats"
                ),
                TimetableSlot(
                    startPeriod = 2,
                    periodSpan = 1,
                    title = "Operating Systems",
                    subjectId = "os"
                ),
                TimetableSlot(
                    startPeriod = 3,
                    periodSpan = 1,
                    title = "Object Oriented Programming",
                    subjectId = "oop"
                ),
                TimetableSlot(
                    startPeriod = 4,
                    periodSpan = 1,
                    title = "Computer Networks",
                    subjectId = "cn"
                )
            ),
            afternoonSlots = listOf(
                TimetableSlot(
                    startPeriod = 5,
                    periodSpan = 2,
                    title = "Capstone Project",
                    subjectId = "capstone"
                )
            )
        ),

        // FRIDAY
        DayScheduleRow(
            dayOfWeek = DayOfWeek.FRIDAY,
            shortCode = "FRI",
            morningSlots = listOf(
                TimetableSlot(
                    startPeriod = 1,
                    periodSpan = 2,
                    title = "Computer Networks Lab",
                    subjectId = "cn",
                    isLab = true
                ),
                TimetableSlot(
                    startPeriod = 3,
                    periodSpan = 1,
                    title = "Operating Systems",
                    subjectId = "os"
                ),
                TimetableSlot(
                    startPeriod = 4,
                    periodSpan = 1,
                    title = "Computer Networks",
                    subjectId = "cn"
                )
            ),
            afternoonSlots = listOf(
                TimetableSlot(
                    startPeriod = 5,
                    periodSpan = 2,
                    title = "Cloud Engineering",
                    subjectId = "cloud_eng"
                )
            )
        ),

        // SATURDAY
        DayScheduleRow(
            dayOfWeek = DayOfWeek.SATURDAY,
            shortCode = "SAT",
            morningSlots = listOf(
                TimetableSlot(
                    startPeriod = 1,
                    periodSpan = 1,
                    title = "Object Oriented Programming",
                    subjectId = "oop"
                ),
                TimetableSlot(
                    startPeriod = 2,
                    periodSpan = 1,
                    title = "Cloud Engineering",
                    subjectId = "cloud_eng"
                ),
                TimetableSlot(
                    startPeriod = 3,
                    periodSpan = 1,
                    title = "Mentoring",
                    subjectId = "mentoring_hed"
                ),
                TimetableSlot(
                    startPeriod = 4,
                    periodSpan = 1,
                    title = "Cloud Engineering",
                    subjectId = "cloud_eng"
                )
            ),
            afternoonSlots = listOf(
                TimetableSlot(
                    startPeriod = 5,
                    periodSpan = 1,
                    title = null
                ),
                TimetableSlot(
                    startPeriod = 6,
                    periodSpan = 1,
                    title = null
                )
            )
        )
    )

    /**
     * Section D:
     * Returns true if [date] is a Sunday or the 3rd Saturday of the month (dayOfMonth in 15..21).
     */
    fun isNoClassDay(date: LocalDate): Boolean {
        return date.dayOfWeek == DayOfWeek.SUNDAY ||
            (date.dayOfWeek == DayOfWeek.SATURDAY && date.dayOfMonth in 15..21)
    }

    /**
     * Section D:
     * Resolves which day (if any) should be highlighted when the Timetable is triggered at [now].
     * - If today is a Sunday or 3rd Saturday -> FULL_WEEK (no highlight).
     * - Before 16:00 -> target is today; at/after 16:00 -> target is tomorrow.
     * - If target is a Sunday or 3rd Saturday -> FULL_WEEK (no highlight).
     * - Otherwise -> highlight target (TODAY or TOMORROW).
     */
    fun resolveHighlight(now: LocalDateTime = LocalDateTime.now()): HighlightDecision {
        val today = now.toLocalDate()
        if (isNoClassDay(today)) {
            return HighlightDecision(mode = TimetableMode.FULL_WEEK, highlightDay = null)
        }
        val target = if (now.toLocalTime() < LocalTime.of(16, 0)) {
            today
        } else {
            today.plusDays(1)
        }
        if (isNoClassDay(target)) {
            return HighlightDecision(mode = TimetableMode.FULL_WEEK, highlightDay = null)
        }
        val mode = if (target == today) TimetableMode.TODAY else TimetableMode.TOMORROW
        return HighlightDecision(mode = mode, highlightDay = target.dayOfWeek)
    }

    fun dayOfWeekToShortCode(day: DayOfWeek?): String? {
        return when (day) {
            DayOfWeek.MONDAY -> "MON"
            DayOfWeek.TUESDAY -> "TUE"
            DayOfWeek.WEDNESDAY -> "WED"
            DayOfWeek.THURSDAY -> "THU"
            DayOfWeek.FRIDAY -> "FRI"
            DayOfWeek.SATURDAY -> "SAT"
            else -> null
        }
    }

    fun parseDayOfWeek(raw: String?): DayOfWeek? {
        return when (raw?.trim()?.uppercase(Locale.US)) {
            "MON", "MONDAY" -> DayOfWeek.MONDAY
            "TUE", "TUESDAY" -> DayOfWeek.TUESDAY
            "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY
            "THU", "THURSDAY" -> DayOfWeek.THURSDAY
            "FRI", "FRIDAY" -> DayOfWeek.FRIDAY
            "SAT", "SATURDAY" -> DayOfWeek.SATURDAY
            else -> null
        }
    }
}
