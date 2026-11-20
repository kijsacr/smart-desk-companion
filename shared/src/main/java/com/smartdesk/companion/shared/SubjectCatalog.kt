package com.smartdesk.companion.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Router
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

@Immutable
data class SubjectEntry(
    val id: String,
    val name: String,
    val icon: ImageVector
)

/**
 * Module F — Subject Catalog
 * Single editable source file defining the user's subjects in order, followed by Others.
 * Both the :phone dropdown and the :tablet Task Viewer read from this catalog.
 */
object SubjectCatalog {

    val OTHERS_ENTRY = SubjectEntry(
        id = "others",
        name = "Others",
        icon = Icons.Default.Assignment
    )

    /**
     * V9 Section 5: Distinct subject entry for Performance Mode sessions on the dashboard tasks screen.
     * Rendered in a distinct Purple/Violet theme that no academic subject or urgent red card uses.
     */
    const val PERFORMANCE_SESSION_ID = "performance_session"

    val PERFORMANCE_SESSION_ENTRY = SubjectEntry(
        id = PERFORMANCE_SESSION_ID,
        name = "Live Performance",
        icon = Icons.Default.MusicNote
    )

    fun isPerformanceSession(subjectId: String?): Boolean =
        subjectId?.trim() == PERFORMANCE_SESSION_ID

    val allSubjects: List<SubjectEntry> = listOf(
        SubjectEntry(
            id = "computer_networks",
            name = "Computer Networks",
            icon = Icons.Default.Router
        ),
        SubjectEntry(
            id = "probability_statistics",
            name = "Probability and Statistics",
            icon = Icons.Default.BarChart
        ),
        SubjectEntry(
            id = "operating_systems",
            name = "Operating Systems",
            icon = Icons.Default.Memory
        ),
        SubjectEntry(
            id = "oop",
            name = "Object Oriented Programming",
            icon = Icons.Default.Code
        ),
        SubjectEntry(
            id = "cloud_engineering",
            name = "Cloud Engineering",
            icon = Icons.Default.Cloud
        ),
        SubjectEntry(
            id = "general_management",
            name = "General Management Minor",
            icon = Icons.Default.BusinessCenter
        ),
        SubjectEntry(
            id = "capstone_project",
            name = "Capstone Project",
            icon = Icons.Default.RocketLaunch
        ),
        SubjectEntry(
            id = "placement_training",
            name = "Placement Training",
            icon = Icons.Default.Badge
        ),
        SubjectEntry(
            id = "mentoring_hed",
            name = "Mentoring & HED",
            icon = Icons.Default.Groups
        ),
        OTHERS_ENTRY
    )

    private val lookupMap: Map<String, SubjectEntry> =
        (allSubjects + PERFORMANCE_SESSION_ENTRY).associateBy { it.id }

    /**
     * Resolves a subject by [subjectId], falling back to [OTHERS_ENTRY] for unknown or blank IDs.
     */
    fun findById(subjectId: String?): SubjectEntry {
        if (subjectId.isNullOrBlank()) return OTHERS_ENTRY
        return lookupMap[subjectId] ?: OTHERS_ENTRY
    }
}
