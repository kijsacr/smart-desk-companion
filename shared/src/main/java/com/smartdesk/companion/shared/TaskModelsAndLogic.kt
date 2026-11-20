package com.smartdesk.companion.shared

import androidx.compose.runtime.Immutable
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.IgnoreExtraProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Module I: Task Data Model
 * Matches the Firebase `/tasks/<id>` schema:
 * {
 *   "id": "-Nabc123",
 *   "title": "Chapter 5 problem set",
 *   "subjectId": "computer_networks",
 *   "deadline": "2026-11-14",
 *   "completed": false,
 *   "createdAt": 1762400000000,
 *   "completedAt": null
 * }
 */
@Immutable
@IgnoreExtraProperties
data class TaskItem(
    val id: String = "",
    val title: String = "",
    val subjectId: String = SubjectCatalog.OTHERS_ENTRY.id,
    val deadline: String = "",
    val completed: Boolean = false,
    val createdAt: Long = 0L,
    val completedAt: Long? = null
)

@Immutable
data class TaskDeadlinePresentation(
    val label: String,
    val isUrgentRed: Boolean,
    val daysUntilDeadline: Long
)

/**
 * Shared pure-Kotlin sorting, formatting, and urgency evaluation logic used by both
 * the :phone Task Creation / Pending list and the :tablet read-only Task Viewer.
 */
object TaskScheduleLogic {

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val shortDisplayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

    private val fullPickerDisplayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

    fun parseIsoDateOrNull(isoDate: String?): LocalDate? {
        if (isoDate.isNullOrBlank()) return null
        return try {
            LocalDate.parse(isoDate.trim(), isoFormatter)
        } catch (_: Exception) {
            null
        }
    }

    fun formatIsoDate(date: LocalDate): String = date.format(isoFormatter)

    fun formatFullDateForField(date: LocalDate): String = date.format(fullPickerDisplayFormatter)

    /**
     * Module F: Sorting
     * Only tasks with `completed = false` are included.
     * Sorted ascending by deadline date (nearest at the top; overdue tasks sort to the very top).
     * Ties are broken by `createdAt` ascending (oldest first).
     */
    fun sortPendingTasks(allTasks: List<TaskItem>): List<TaskItem> {
        return allTasks
            .filter { !it.completed && it.title.isNotBlank() }
            .sortedWith(
                compareBy<TaskItem>(
                    { parseIsoDateOrNull(it.deadline) ?: LocalDate.MAX },
                    { it.createdAt }
                )
            )
    }

    /**
     * Sorts completed tasks for the collapsible Completed section on the phone
     * (most recently completed first).
     */
    fun sortCompletedTasks(allTasks: List<TaskItem>): List<TaskItem> {
        return allTasks
            .filter { it.completed && it.title.isNotBlank() }
            .sortedByDescending { it.completedAt ?: it.createdAt }
    }

    /**
     * Module F & Section 9.2:
     * Evaluates the deadline label (`Overdue`, `Today`, `Tomorrow`, or `Fri, 14 Nov`)
     * and the red urgency state (`isUrgentRed`) relative to [today].
     */
    fun evaluateDeadline(
        deadlineIso: String,
        today: LocalDate = LocalDate.now()
    ): TaskDeadlinePresentation {
        val targetDate = parseIsoDateOrNull(deadlineIso)
            ?: return TaskDeadlinePresentation(
                label = deadlineIso,
                isUrgentRed = false,
                daysUntilDeadline = Long.MAX_VALUE
            )

        val daysUntil = ChronoUnit.DAYS.between(today, targetDate)

        val label = when {
            daysUntil < 0L -> "Overdue"
            daysUntil == 0L -> "Today"
            daysUntil == 1L -> "Tomorrow"
            else -> targetDate.format(shortDisplayFormatter)
        }

        val isUrgentRed = if (AppConfigConstants.URGENCY_KEEP_RED_FOR_TODAY_AND_OVERDUE) {
            daysUntil <= AppConfigConstants.URGENCY_THRESHOLD_DAYS
        } else {
            daysUntil == AppConfigConstants.URGENCY_THRESHOLD_DAYS
        }

        return TaskDeadlinePresentation(
            label = label,
            isUrgentRed = isUrgentRed,
            daysUntilDeadline = daysUntil
        )
    }

    fun parseTasksSnapshot(snapshot: DataSnapshot): List<TaskItem> {
        if (!snapshot.exists()) return emptyList()
        val result = mutableListOf<TaskItem>()
        for (child in snapshot.children) {
            val item = parseSingleTaskSnapshot(child)
            if (item != null) {
                result.add(item)
            }
        }
        return result
    }

    private fun parseSingleTaskSnapshot(snapshot: DataSnapshot): TaskItem? {
        val title = snapshot.child("title").getValue(String::class.java)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val id = snapshot.child("id").getValue(String::class.java)
            ?.takeIf { it.isNotBlank() }
            ?: snapshot.key
            ?: return null
        val subjectId = snapshot.child("subjectId").getValue(String::class.java)
            ?.takeIf { it.isNotBlank() }
            ?: SubjectCatalog.OTHERS_ENTRY.id
        val deadline = snapshot.child("deadline").getValue(String::class.java).orEmpty()
        val completed = snapshot.child("completed").getValue(Boolean::class.java) ?: false
        val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
        val completedAt = snapshot.child("completedAt").getValue(Long::class.java)

        return TaskItem(
            id = id,
            title = title,
            subjectId = subjectId,
            deadline = deadline,
            completed = completed,
            createdAt = createdAt,
            completedAt = completedAt
        )
    }
}
