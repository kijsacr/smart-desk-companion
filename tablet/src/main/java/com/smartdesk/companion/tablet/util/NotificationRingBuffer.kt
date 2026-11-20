package com.smartdesk.companion.tablet.util

import com.smartdesk.companion.tablet.model.NotificationPayload

/**
 * Fixed-capacity ring buffer designed for the Lenovo Yoga Tab 11 (Helio G90T)
 * to bound the active notification state collection strictly to [maxCapacity] (5 items),
 * preventing unbounded list allocations and unnecessary Compose recompositions.
 */
class NotificationRingBuffer(
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY
) {
    private val buffer = ArrayDeque<NotificationPayload>(maxCapacity)

    @Synchronized
    fun replaceAll(items: List<NotificationPayload>): List<NotificationPayload> {
        buffer.clear()
        val bounded = items
            .sortedByDescending { it.epochMillis }
            .take(maxCapacity)
        buffer.addAll(bounded)
        return buffer.toList()
    }

    @Synchronized
    fun push(item: NotificationPayload): List<NotificationPayload> {
        // Deduplicate identical consecutive notification updates from the same sender/title
        buffer.removeAll { existing ->
            existing.id == item.id ||
                (existing.packageName == item.packageName &&
                    existing.title == item.title &&
                    existing.message == item.message)
        }
        buffer.addFirst(item)
        while (buffer.size > maxCapacity) {
            buffer.removeLast()
        }
        return buffer.toList()
    }

    @Synchronized
    fun clear(): List<NotificationPayload> {
        buffer.clear()
        return emptyList()
    }

    @Synchronized
    fun snapshot(): List<NotificationPayload> = buffer.toList()

    companion object {
        const val DEFAULT_MAX_CAPACITY = 5
    }
}
