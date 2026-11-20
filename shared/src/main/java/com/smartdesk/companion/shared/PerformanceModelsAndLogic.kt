package com.smartdesk.companion.shared

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.google.firebase.database.DataSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * V9 Section 9: Section category for jigsaw puzzle piece coloring.
 */
enum class SectionCategory(val colorHex: Long, val accentHex: Long) {
    INTRO(0xFF00838F, 0xFF4DD0E1),
    VERSE(0xFF1565C0, 0xFF64B5F6),
    PRE_CHORUS(0xFFEF6C00, 0xFFFFB74D),
    CHORUS(0xFFC62828, 0xFFFF8A80),
    BRIDGE(0xFF6A1B9A, 0xFFCE93D8),
    INSTRUMENTAL(0xFF00695C, 0xFF80CBC4),
    ADLIB(0xFFAD1457, 0xFFF48FB1),
    OUTRO(0xFF283593, 0xFF9FA8DA),
    UNLABELED(0xFF37474F, 0xFF90A4AE),
    FULL_SONG(0xFF455A64, 0xFFB0BEC5);

    val containerColor: Color get() = Color(colorHex)
    val accentColor: Color get() = Color(accentHex)

    companion object {
        fun fromLabel(label: String): SectionCategory {
            val lower = label.trim().lowercase(Locale.US)
            return when {
                lower == "full song" -> FULL_SONG
                lower.startsWith("unlabeled") -> UNLABELED
                lower.contains("intro") -> INTRO
                lower.contains("pre") && lower.contains("chorus") -> PRE_CHORUS
                lower.contains("instrumental") || lower.contains("interlude") || lower.contains("solo") -> INSTRUMENTAL
                lower.contains("chorus") || lower.contains("refrain") -> CHORUS
                lower.contains("bridge") -> BRIDGE
                lower.contains("adlib") || lower.contains("ad lib") || lower.contains("vamp") || lower.contains("tag") -> ADLIB
                lower.contains("outro") || lower.contains("ending") || lower.contains("coda") -> OUTRO
                lower.contains("verse") || lower.contains("vesre") -> VERSE
                else -> VERSE
            }
        }
    }
}

/**
 * V9 Section 6 Step 3: Detected text line inside a song strip.
 */
@Immutable
data class SongTextLine(
    val lineIndexInSection: Int,
    val text: String,
    val isChordLine: Boolean,
    val yTopNorm: Float,
    val yBottomNorm: Float,
    val yCenterNorm: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("idx", lineIndexInSection)
        put("text", text)
        put("chord", isChordLine)
        put("yTop", yTopNorm.toDouble())
        put("yBot", yBottomNorm.toDouble())
        put("yCtr", yCenterNorm.toDouble())
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "lineIndexInSection" to lineIndexInSection,
        "text" to text,
        "isChordLine" to isChordLine,
        "yTopNorm" to yTopNorm.toDouble(),
        "yBottomNorm" to yBottomNorm.toDouble(),
        "yCenterNorm" to yCenterNorm.toDouble()
    )

    companion object {
        fun fromJson(obj: JSONObject): SongTextLine = SongTextLine(
            lineIndexInSection = obj.optInt("idx", 0),
            text = obj.optString("text", ""),
            isChordLine = obj.optBoolean("chord", false),
            yTopNorm = obj.optDouble("yTop", 0.0).toFloat(),
            yBottomNorm = obj.optDouble("yBot", 0.0).toFloat(),
            yCenterNorm = obj.optDouble("yCtr", 0.0).toFloat()
        )

        fun fromSnapshot(s: DataSnapshot): SongTextLine = SongTextLine(
            lineIndexInSection = s.child("lineIndexInSection").getValue(Int::class.java)
                ?: s.child("lineIndexInSection").getValue(Long::class.java)?.toInt() ?: 0,
            text = s.child("text").getValue(String::class.java).orEmpty(),
            isChordLine = s.child("isChordLine").getValue(Boolean::class.java) ?: false,
            yTopNorm = (s.child("yTopNorm").getValue(Double::class.java) ?: 0.0).toFloat(),
            yBottomNorm = (s.child("yBottomNorm").getValue(Double::class.java) ?: 0.0).toFloat(),
            yCenterNorm = (s.child("yCenterNorm").getValue(Double::class.java) ?: 0.0).toFloat()
        )
    }
}

/**
 * V9 Section 6 Steps 3 & 4: A distinct section region on the song strip.
 * Duplicate sections with identical content share one [SongSectionRegion] (the first occurrence).
 */
@Immutable
data class SongSectionRegion(
    val regionId: String,
    val rawLabel: String,
    val displayLabel: String,
    val normalizedFamilyName: String,
    val category: SectionCategory,
    val yStartNorm: Float,
    val yEndNorm: Float,
    val defaultYStartNorm: Float,
    val defaultYEndNorm: Float,
    val normalizedContentSignature: String,
    val lines: List<SongTextLine>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("regionId", regionId)
        put("rawLabel", rawLabel)
        put("displayLabel", displayLabel)
        put("family", normalizedFamilyName)
        put("category", category.name)
        put("yStart", yStartNorm.toDouble())
        put("yEnd", yEndNorm.toDouble())
        put("defYStart", defaultYStartNorm.toDouble())
        put("defYEnd", defaultYEndNorm.toDouble())
        put("sig", normalizedContentSignature)
        val arr = JSONArray()
        lines.forEach { arr.put(it.toJson()) }
        put("lines", arr)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "regionId" to regionId,
        "rawLabel" to rawLabel,
        "displayLabel" to displayLabel,
        "normalizedFamilyName" to normalizedFamilyName,
        "category" to category.name,
        "yStartNorm" to yStartNorm.toDouble(),
        "yEndNorm" to yEndNorm.toDouble(),
        "defaultYStartNorm" to defaultYStartNorm.toDouble(),
        "defaultYEndNorm" to defaultYEndNorm.toDouble(),
        "normalizedContentSignature" to normalizedContentSignature,
        "lines" to lines.map { it.toMap() }
    )

    companion object {
        fun fromJson(obj: JSONObject): SongSectionRegion {
            val linesArr = obj.optJSONArray("lines")
            val parsedLines = mutableListOf<SongTextLine>()
            if (linesArr != null) {
                for (i in 0 until linesArr.length()) {
                    linesArr.optJSONObject(i)?.let { parsedLines.add(SongTextLine.fromJson(it)) }
                }
            }
            val rawLabel = obj.optString("rawLabel", "Section")
            val displayLabel = obj.optString("displayLabel", rawLabel)
            val catName = obj.optString("category", "")
            val cat = runCatching { SectionCategory.valueOf(catName) }
                .getOrElse { SectionCategory.fromLabel(displayLabel) }
            val yStart = obj.optDouble("yStart", 0.0).toFloat().coerceIn(0f, 1f)
            val yEnd = obj.optDouble("yEnd", 1.0).toFloat().coerceIn(yStart, 1f)
            return SongSectionRegion(
                regionId = obj.optString("regionId", UUID.randomUUID().toString()),
                rawLabel = rawLabel,
                displayLabel = displayLabel,
                normalizedFamilyName = obj.optString("family", displayLabel.lowercase(Locale.US)),
                category = cat,
                yStartNorm = yStart,
                yEndNorm = yEnd,
                defaultYStartNorm = obj.optDouble("defYStart", yStart.toDouble()).toFloat(),
                defaultYEndNorm = obj.optDouble("defYEnd", yEnd.toDouble()).toFloat(),
                normalizedContentSignature = obj.optString("sig", ""),
                lines = parsedLines
            )
        }

        fun fromSnapshot(s: DataSnapshot): SongSectionRegion {
            val rawLabel = s.child("rawLabel").getValue(String::class.java) ?: "Section"
            val displayLabel = s.child("displayLabel").getValue(String::class.java) ?: rawLabel
            val catStr = s.child("category").getValue(String::class.java).orEmpty()
            val cat = runCatching { SectionCategory.valueOf(catStr) }
                .getOrElse { SectionCategory.fromLabel(displayLabel) }
            val yStart = (s.child("yStartNorm").getValue(Double::class.java) ?: 0.0).toFloat().coerceIn(0f, 1f)
            val yEnd = (s.child("yEndNorm").getValue(Double::class.java) ?: 1.0).toFloat().coerceIn(yStart, 1f)
            val defStart = (s.child("defaultYStartNorm").getValue(Double::class.java) ?: yStart.toDouble()).toFloat()
            val defEnd = (s.child("defaultYEndNorm").getValue(Double::class.java) ?: yEnd.toDouble()).toFloat()
            val parsedLines = s.child("lines").children.map { SongTextLine.fromSnapshot(it) }
            return SongSectionRegion(
                regionId = s.child("regionId").getValue(String::class.java) ?: s.key.orEmpty(),
                rawLabel = rawLabel,
                displayLabel = displayLabel,
                normalizedFamilyName = s.child("normalizedFamilyName").getValue(String::class.java)
                    ?: displayLabel.lowercase(Locale.US),
                category = cat,
                yStartNorm = yStart,
                yEndNorm = yEnd,
                defaultYStartNorm = defStart,
                defaultYEndNorm = defEnd,
                normalizedContentSignature = s.child("normalizedContentSignature").getValue(String::class.java).orEmpty(),
                lines = parsedLines
            )
        }
    }
}

/**
 * V9 Section 6 Step 4 & Section 9: One puzzle piece in the song's play order structure.
 * Multiple [SongStructureItem] entries can point to the same [regionId] (e.g. repeated Chorus).
 */
@Immutable
data class SongStructureItem(
    val instanceId: String,
    val regionId: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("instanceId", instanceId)
        put("regionId", regionId)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "instanceId" to instanceId,
        "regionId" to regionId
    )

    companion object {
        fun fromJson(obj: JSONObject): SongStructureItem = SongStructureItem(
            instanceId = obj.optString("instanceId", UUID.randomUUID().toString()),
            regionId = obj.optString("regionId", "")
        )

        fun fromSnapshot(s: DataSnapshot): SongStructureItem = SongStructureItem(
            instanceId = s.child("instanceId").getValue(String::class.java) ?: UUID.randomUUID().toString(),
            regionId = s.child("regionId").getValue(String::class.java).orEmpty()
        )
    }
}

/**
 * V9 Section 9 & 10: A performance note pinned after a specific line in a section.
 */
@Immutable
data class SongPinnedNote(
    val noteId: String,
    val regionId: String,
    val lineIndexInSection: Int,
    val anchorYNorm: Float = 0f,
    val text: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("noteId", noteId)
        put("regionId", regionId)
        put("lineIdx", lineIndexInSection)
        put("yNorm", anchorYNorm.toDouble())
        put("text", text)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "noteId" to noteId,
        "regionId" to regionId,
        "lineIndexInSection" to lineIndexInSection,
        "anchorYNorm" to anchorYNorm.toDouble(),
        "text" to text
    )

    companion object {
        fun fromJson(obj: JSONObject): SongPinnedNote = SongPinnedNote(
            noteId = obj.optString("noteId", UUID.randomUUID().toString()),
            regionId = obj.optString("regionId", ""),
            lineIndexInSection = obj.optInt("lineIdx", -1),
            anchorYNorm = obj.optDouble("yNorm", 0.0).toFloat().coerceIn(0f, 1f),
            text = obj.optString("text", "")
        )

        fun fromSnapshot(s: DataSnapshot): SongPinnedNote = SongPinnedNote(
            noteId = s.child("noteId").getValue(String::class.java) ?: s.key.orEmpty(),
            regionId = s.child("regionId").getValue(String::class.java).orEmpty(),
            lineIndexInSection = s.child("lineIndexInSection").getValue(Int::class.java)
                ?: s.child("lineIndexInSection").getValue(Long::class.java)?.toInt() ?: -1,
            anchorYNorm = (s.child("anchorYNorm").getValue(Double::class.java) ?: 0.0).toFloat().coerceIn(0f, 1f),
            text = s.child("text").getValue(String::class.java).orEmpty()
        )
    }
}

/**
 * V9 Section 5 & 6: Processed song metadata, regions, structure, and notes.
 */
@Immutable
data class PerformanceSong(
    val id: String,
    val title: String,
    val sourceType: String, // "PDF" or "TEXT"
    val rawTextContent: String = "",
    val contentFingerprint: String = "",
    val stripWidthPx: Int = AppConfigConstants.PERF_STRIP_TARGET_WIDTH_PX,
    val stripTotalHeightPx: Int = 2400,
    val tileHeightsPx: List<Int> = emptyList(),
    val tileCount: Int = 0,
    val pdfChunkCount: Int = 0,
    val regions: List<SongSectionRegion> = emptyList(),
    val defaultStructure: List<SongStructureItem> = emptyList(),
    val customStructure: List<SongStructureItem> = emptyList(),
    val notes: List<SongPinnedNote> = emptyList()
) {
    val songId: String
        get() = id

    val stripHeightPx: Int
        get() = stripTotalHeightPx

    val activeStructure: List<SongStructureItem>
        get() = customStructure.ifEmpty { defaultStructure }

    fun findRegion(regionId: String): SongSectionRegion? =
        regions.firstOrNull { it.regionId == regionId }

    fun regionForStructureIndex(sectionIndex: Int): SongSectionRegion? {
        val struct = activeStructure
        if (struct.isEmpty()) return regions.firstOrNull()
        val item = struct.getOrNull(sectionIndex.coerceIn(0, struct.lastIndex)) ?: return regions.firstOrNull()
        return findRegion(item.regionId) ?: regions.firstOrNull()
    }

    fun updateRegion(updatedRegion: SongSectionRegion): PerformanceSong {
        val newRegions = regions.map {
            if (it.regionId == updatedRegion.regionId) updatedRegion else it
        }
        return copy(regions = newRegions)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("sourceType", sourceType)
        put("rawTextContent", rawTextContent)
        put("contentFingerprint", contentFingerprint)
        put("stripWidthPx", stripWidthPx)
        put("stripTotalHeightPx", stripTotalHeightPx)
        put("tileHeightsPx", JSONArray(tileHeightsPx))
        put("tileCount", tileCount)
        put("pdfChunkCount", pdfChunkCount)
        put("regions", JSONArray().apply { regions.forEach { put(it.toJson()) } })
        put("defaultStructure", JSONArray().apply { defaultStructure.forEach { put(it.toJson()) } })
        put("customStructure", JSONArray().apply { customStructure.forEach { put(it.toJson()) } })
        put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "sourceType" to sourceType,
        "rawTextContent" to rawTextContent,
        "contentFingerprint" to contentFingerprint,
        "stripWidthPx" to stripWidthPx,
        "stripTotalHeightPx" to stripTotalHeightPx,
        "tileHeightsPx" to tileHeightsPx,
        "tileCount" to tileCount,
        "pdfChunkCount" to pdfChunkCount,
        "regions" to regions.map { it.toMap() },
        "defaultStructure" to defaultStructure.map { it.toMap() },
        "customStructure" to customStructure.map { it.toMap() },
        "notes" to notes.map { it.toMap() }
    )

    companion object {
        const val SOURCE_PDF = "PDF"
        const val SOURCE_TEXT = "TEXT"

        fun fromJson(obj: JSONObject): PerformanceSong {
            val thArr = obj.optJSONArray("tileHeightsPx")
            val tileHeights = mutableListOf<Int>()
            if (thArr != null) {
                for (i in 0 until thArr.length()) tileHeights.add(thArr.optInt(i, 0))
            }
            val regArr = obj.optJSONArray("regions")
            val regs = mutableListOf<SongSectionRegion>()
            if (regArr != null) {
                for (i in 0 until regArr.length()) {
                    regArr.optJSONObject(i)?.let { regs.add(SongSectionRegion.fromJson(it)) }
                }
            }
            val defArr = obj.optJSONArray("defaultStructure")
            val defStruct = mutableListOf<SongStructureItem>()
            if (defArr != null) {
                for (i in 0 until defArr.length()) {
                    defArr.optJSONObject(i)?.let { defStruct.add(SongStructureItem.fromJson(it)) }
                }
            }
            val custArr = obj.optJSONArray("customStructure")
            val custStruct = mutableListOf<SongStructureItem>()
            if (custArr != null) {
                for (i in 0 until custArr.length()) {
                    custArr.optJSONObject(i)?.let { custStruct.add(SongStructureItem.fromJson(it)) }
                }
            }
            val notesArr = obj.optJSONArray("notes")
            val parsedNotes = mutableListOf<SongPinnedNote>()
            if (notesArr != null) {
                for (i in 0 until notesArr.length()) {
                    notesArr.optJSONObject(i)?.let { parsedNotes.add(SongPinnedNote.fromJson(it)) }
                }
            }
            return PerformanceSong(
                id = obj.optString("id", UUID.randomUUID().toString()),
                title = obj.optString("title", "Untitled Song"),
                sourceType = obj.optString("sourceType", SOURCE_PDF),
                rawTextContent = obj.optString("rawTextContent", ""),
                contentFingerprint = obj.optString("contentFingerprint", ""),
                stripWidthPx = obj.optInt("stripWidthPx", AppConfigConstants.PERF_STRIP_TARGET_WIDTH_PX),
                stripTotalHeightPx = obj.optInt("stripTotalHeightPx", 2400).coerceAtLeast(1),
                tileHeightsPx = tileHeights,
                tileCount = obj.optInt("tileCount", tileHeights.size),
                pdfChunkCount = obj.optInt("pdfChunkCount", 0),
                regions = regs,
                defaultStructure = defStruct,
                customStructure = custStruct.ifEmpty { defStruct },
                notes = parsedNotes
            )
        }

        fun fromSnapshot(s: DataSnapshot): PerformanceSong {
            val id = s.child("id").getValue(String::class.java) ?: s.key.orEmpty()
            val title = s.child("title").getValue(String::class.java) ?: "Untitled Song"
            val sourceType = s.child("sourceType").getValue(String::class.java) ?: SOURCE_PDF
            val rawText = s.child("rawTextContent").getValue(String::class.java).orEmpty()
            val fingerprint = s.child("contentFingerprint").getValue(String::class.java).orEmpty()
            val widthPx = s.child("stripWidthPx").getValue(Int::class.java)
                ?: s.child("stripWidthPx").getValue(Long::class.java)?.toInt()
                ?: AppConfigConstants.PERF_STRIP_TARGET_WIDTH_PX
            val totalHeightPx = (
                s.child("stripTotalHeightPx").getValue(Int::class.java)
                    ?: s.child("stripTotalHeightPx").getValue(Long::class.java)?.toInt()
                    ?: 2400
                ).coerceAtLeast(1)
            val tileHeights = s.child("tileHeightsPx").children.mapNotNull {
                it.getValue(Int::class.java) ?: it.getValue(Long::class.java)?.toInt()
            }
            val tileCount = s.child("tileCount").getValue(Int::class.java)
                ?: s.child("tileCount").getValue(Long::class.java)?.toInt()
                ?: tileHeights.size
            val pdfChunkCount = s.child("pdfChunkCount").getValue(Int::class.java)
                ?: s.child("pdfChunkCount").getValue(Long::class.java)?.toInt()
                ?: 0
            val regions = s.child("regions").children.map { SongSectionRegion.fromSnapshot(it) }
            val defStruct = s.child("defaultStructure").children.map { SongStructureItem.fromSnapshot(it) }
            val custStruct = s.child("customStructure").children.map { SongStructureItem.fromSnapshot(it) }
            val notes = s.child("notes").children.map { SongPinnedNote.fromSnapshot(it) }

            return PerformanceSong(
                id = id,
                title = title,
                sourceType = sourceType,
                rawTextContent = rawText,
                contentFingerprint = fingerprint,
                stripWidthPx = widthPx,
                stripTotalHeightPx = totalHeightPx,
                tileHeightsPx = tileHeights,
                tileCount = tileCount,
                pdfChunkCount = pdfChunkCount,
                regions = regions,
                defaultStructure = defStruct,
                customStructure = custStruct.ifEmpty { defStruct },
                notes = notes
            )
        }
    }
}

/**
 * V9 Section 5: A performance session containing one or more ordered songs.
 */
@Immutable
data class PerformanceSession(
    val id: String,
    val name: String,
    val dateIso: String,
    val songOrder: List<String>,
    val songs: Map<String, PerformanceSong>,
    val taskId: String = "perf_session_$id",
    val updatedAt: Long = System.currentTimeMillis()
) {
    val sessionId: String
        get() = id

    val songsById: Map<String, PerformanceSong>
        get() = songs

    val songCount: Int
        get() = songs.size

    val orderedSongs: List<PerformanceSong>
        get() {
            val byOrder = songOrder.mapNotNull { songs[it] }
            val remaining = songs.values.filter { it.id !in songOrder }
            return byOrder + remaining
        }

    fun findSong(targetId: String): PerformanceSong? =
        songs[targetId] ?: songs.values.firstOrNull { it.id == targetId || it.songId == targetId }

    fun updateSong(updatedSong: PerformanceSong): PerformanceSession {
        val newSongs = LinkedHashMap(songs)
        newSongs[updatedSong.id] = updatedSong
        return copy(songs = newSongs, updatedAt = System.currentTimeMillis())
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("dateIso", dateIso)
        put("songOrder", JSONArray(songOrder))
        put("taskId", taskId)
        put("updatedAt", updatedAt)
        val songsObj = JSONObject()
        songs.forEach { (k, v) -> songsObj.put(k, v.toJson()) }
        put("songs", songsObj)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "dateIso" to dateIso,
        "songOrder" to songOrder,
        "taskId" to taskId,
        "updatedAt" to updatedAt,
        "songs" to songs.mapValues { it.value.toMap() }
    )

    companion object {
        fun fromJson(obj: JSONObject): PerformanceSession {
            val id = obj.optString("id", UUID.randomUUID().toString())
            val orderArr = obj.optJSONArray("songOrder")
            val order = mutableListOf<String>()
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) {
                    val sId = orderArr.optString(i, "")
                    if (sId.isNotEmpty()) order.add(sId)
                }
            }
            val songsMap = LinkedHashMap<String, PerformanceSong>()
            val songsObj = obj.optJSONObject("songs")
            if (songsObj != null) {
                val keys = songsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    songsObj.optJSONObject(k)?.let { songsMap[k] = PerformanceSong.fromJson(it) }
                }
            }
            return PerformanceSession(
                id = id,
                name = obj.optString("name", "Untitled Session"),
                dateIso = obj.optString("dateIso", ""),
                songOrder = order.ifEmpty { songsMap.keys.toList() },
                songs = songsMap,
                taskId = obj.optString("taskId", "perf_session_$id"),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }

        fun fromSnapshot(s: DataSnapshot): PerformanceSession? {
            if (!s.exists()) return null
            val id = s.child("id").getValue(String::class.java) ?: s.key ?: return null
            val name = s.child("name").getValue(String::class.java)?.trim().orEmpty()
            if (name.isEmpty()) return null
            val dateIso = s.child("dateIso").getValue(String::class.java).orEmpty()
            val order = s.child("songOrder").children.mapNotNull { it.getValue(String::class.java) }
            val songsMap = LinkedHashMap<String, PerformanceSong>()
            for (songSnap in s.child("songs").children) {
                val song = PerformanceSong.fromSnapshot(songSnap)
                if (song.id.isNotBlank()) {
                    songsMap[song.id] = song
                }
            }
            val taskId = s.child("taskId").getValue(String::class.java) ?: "perf_session_$id"
            val updatedAt = s.child("updatedAt").getValue(Long::class.java) ?: 0L
            return PerformanceSession(
                id = id,
                name = name,
                dateIso = dateIso,
                songOrder = order.ifEmpty { songsMap.keys.toList() },
                songs = songsMap,
                taskId = taskId,
                updatedAt = updatedAt
            )
        }
    }
}

/**
 * V9 Section 2 & 11: Persistent state snapshot saved locally and at `/performance/state`.
 */
@Immutable
data class PerformanceStateSnapshot(
    val enabled: Boolean = false,
    val sessionId: String = "",
    val songId: String = "",
    val sectionIndex: Int = 0,
    val scrollY: Float = 0f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panLocked: Boolean = false,
    val darkTheme: Boolean = true,
    val epoch: Long = 0L,
    val seq: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "sessionId" to sessionId,
        "songId" to songId,
        "sectionIndex" to sectionIndex,
        "scrollY" to scrollY.toDouble(),
        "zoom" to zoom.toDouble(),
        "panX" to panX.toDouble(),
        "panLocked" to panLocked,
        "darkTheme" to darkTheme,
        "epoch" to epoch,
        "seq" to seq,
        "updatedAt" to updatedAt
    )

    fun toJsonString(): String = JSONObject().apply {
        put("enabled", enabled)
        put("sessionId", sessionId)
        put("songId", songId)
        put("sectionIndex", sectionIndex)
        put("scrollY", scrollY.toDouble())
        put("zoom", zoom.toDouble())
        put("panX", panX.toDouble())
        put("panLocked", panLocked)
        put("darkTheme", darkTheme)
        put("epoch", epoch)
        put("seq", seq)
        put("updatedAt", updatedAt)
    }.toString()

    companion object {
        fun fromJsonString(raw: String?): PerformanceStateSnapshot {
            if (raw.isNullOrBlank()) return PerformanceStateSnapshot()
            return runCatching {
                val obj = JSONObject(raw)
                PerformanceStateSnapshot(
                    enabled = obj.optBoolean("enabled", false),
                    sessionId = obj.optString("sessionId", ""),
                    songId = obj.optString("songId", ""),
                    sectionIndex = obj.optInt("sectionIndex", 0).coerceAtLeast(0),
                    scrollY = obj.optDouble("scrollY", 0.0).toFloat().coerceIn(0f, 1f),
                    zoom = obj.optDouble("zoom", 1.0).toFloat().coerceIn(0.75f, 2.5f),
                    panX = obj.optDouble("panX", 0.0).toFloat().coerceIn(-0.6f, 0.6f),
                    panLocked = obj.optBoolean("panLocked", false),
                    darkTheme = obj.optBoolean("darkTheme", true),
                    epoch = obj.optLong("epoch", 0L),
                    seq = obj.optLong("seq", 0L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            }.getOrDefault(PerformanceStateSnapshot())
        }

        fun fromSnapshot(s: DataSnapshot): PerformanceStateSnapshot {
            if (!s.exists()) return PerformanceStateSnapshot()
            return PerformanceStateSnapshot(
                enabled = s.child("enabled").getValue(Boolean::class.java) ?: false,
                sessionId = s.child("sessionId").getValue(String::class.java).orEmpty(),
                songId = s.child("songId").getValue(String::class.java).orEmpty(),
                sectionIndex = (
                    s.child("sectionIndex").getValue(Int::class.java)
                        ?: s.child("sectionIndex").getValue(Long::class.java)?.toInt() ?: 0
                    ).coerceAtLeast(0),
                scrollY = (s.child("scrollY").getValue(Double::class.java) ?: 0.0).toFloat().coerceIn(0f, 1f),
                zoom = (s.child("zoom").getValue(Double::class.java) ?: 1.0).toFloat().coerceIn(0.75f, 2.5f),
                panX = (s.child("panX").getValue(Double::class.java) ?: 0.0).toFloat().coerceIn(-0.6f, 0.6f),
                panLocked = s.child("panLocked").getValue(Boolean::class.java) ?: false,
                darkTheme = s.child("darkTheme").getValue(Boolean::class.java) ?: true,
                epoch = s.child("epoch").getValue(Long::class.java) ?: 0L,
                seq = s.child("seq").getValue(Long::class.java) ?: 0L,
                updatedAt = s.child("updatedAt").getValue(Long::class.java) ?: 0L
            )
        }
    }
}

/**
 * V9 Section 11: Compact live control message sent over BLE GATT and `/performance/live`.
 */
@Immutable
data class PerformanceControlMessage(
    val epoch: Long,
    val seq: Long,
    val type: String, // "scroll", "zoom", "pan", "section", "song", "theme", "mode", "ping"
    val modeEnabled: Boolean,
    val sessionId: String,
    val songId: String,
    val sectionIndex: Int,
    val scrollY: Float,
    val zoom: Float,
    val panX: Float,
    val panLocked: Boolean,
    val darkTheme: Boolean,
    val requiresAck: Boolean = false,
    val sentAtMs: Long = System.currentTimeMillis()
) {
    fun isNewerThan(lastEpoch: Long, lastSeq: Long): Boolean {
        if (epoch > lastEpoch) return true
        if (epoch == lastEpoch && seq > lastSeq) return true
        return false
    }

    /**
     * Compact JSON representation (<160 bytes) for single-MTU BLE GATT writes.
     */
    fun toCompactJsonBytes(): ByteArray {
        val obj = JSONObject().apply {
            put("e", epoch)
            put("q", seq)
            put("t", type)
            put("m", if (modeEnabled) 1 else 0)
            put("s", sessionId)
            put("g", songId)
            put("i", sectionIndex)
            put("y", ((scrollY * 10000f).toInt()) / 10000.0)
            put("z", ((zoom * 1000f).toInt()) / 1000.0)
            put("x", ((panX * 10000f).toInt()) / 10000.0)
            put("l", if (panLocked) 1 else 0)
            put("d", if (darkTheme) 1 else 0)
            put("a", if (requiresAck) 1 else 0)
            put("ts", sentAtMs)
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    fun toFirebaseMap(): Map<String, Any?> = mapOf(
        "e" to epoch,
        "q" to seq,
        "t" to type,
        "m" to if (modeEnabled) 1 else 0,
        "s" to sessionId,
        "g" to songId,
        "i" to sectionIndex,
        "y" to ((scrollY * 10000f).toInt()) / 10000.0,
        "z" to ((zoom * 1000f).toInt()) / 1000.0,
        "x" to ((panX * 10000f).toInt()) / 10000.0,
        "l" to if (panLocked) 1 else 0,
        "d" to if (darkTheme) 1 else 0,
        "a" to if (requiresAck) 1 else 0,
        "ts" to sentAtMs
    )

    companion object {
        const val TYPE_SCROLL = "scroll"
        const val TYPE_ZOOM = "zoom"
        const val TYPE_PAN = "pan"
        const val TYPE_SECTION = "section"
        const val TYPE_SONG = "song"
        const val TYPE_THEME = "theme"
        const val TYPE_MODE = "mode"
        const val TYPE_PING = "ping"

        fun fromCompactJsonBytes(bytes: ByteArray): PerformanceControlMessage? = runCatching {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            PerformanceControlMessage(
                epoch = obj.optLong("e", 0L),
                seq = obj.optLong("q", 0L),
                type = obj.optString("t", TYPE_SCROLL),
                modeEnabled = obj.optInt("m", 1) == 1,
                sessionId = obj.optString("s", ""),
                songId = obj.optString("g", ""),
                sectionIndex = obj.optInt("i", 0).coerceAtLeast(0),
                scrollY = obj.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f),
                zoom = obj.optDouble("z", 1.0).toFloat().coerceIn(0.75f, 2.5f),
                panX = obj.optDouble("x", 0.0).toFloat().coerceIn(-0.6f, 0.6f),
                panLocked = obj.optInt("l", 0) == 1,
                darkTheme = obj.optInt("d", 1) == 1,
                requiresAck = obj.optInt("a", 0) == 1,
                sentAtMs = obj.optLong("ts", 0L)
            )
        }.getOrNull()

        fun fromSnapshot(s: DataSnapshot): PerformanceControlMessage? {
            if (!s.exists()) return null
            val epoch = s.child("e").getValue(Long::class.java) ?: return null
            val seq = s.child("q").getValue(Long::class.java) ?: return null
            return PerformanceControlMessage(
                epoch = epoch,
                seq = seq,
                type = s.child("t").getValue(String::class.java) ?: TYPE_SCROLL,
                modeEnabled = (s.child("m").getValue(Int::class.java) ?: s.child("m").getValue(Long::class.java)?.toInt() ?: 1) == 1,
                sessionId = s.child("s").getValue(String::class.java).orEmpty(),
                songId = s.child("g").getValue(String::class.java).orEmpty(),
                sectionIndex = (s.child("i").getValue(Int::class.java) ?: s.child("i").getValue(Long::class.java)?.toInt() ?: 0).coerceAtLeast(0),
                scrollY = (s.child("y").getValue(Double::class.java) ?: 0.0).toFloat().coerceIn(0f, 1f),
                zoom = (s.child("z").getValue(Double::class.java) ?: 1.0).toFloat().coerceIn(0.75f, 2.5f),
                panX = (s.child("x").getValue(Double::class.java) ?: 0.0).toFloat().coerceIn(-0.6f, 0.6f),
                panLocked = (s.child("l").getValue(Int::class.java) ?: s.child("l").getValue(Long::class.java)?.toInt() ?: 0) == 1,
                darkTheme = (s.child("d").getValue(Int::class.java) ?: s.child("d").getValue(Long::class.java)?.toInt() ?: 1) == 1,
                requiresAck = (s.child("a").getValue(Int::class.java) ?: s.child("a").getValue(Long::class.java)?.toInt() ?: 0) == 1,
                sentAtMs = s.child("ts").getValue(Long::class.java) ?: 0L
            )
        }
    }
}

/**
 * V9 Section 11: HMAC-SHA256 helper for authenticating the BLE GATT link using the
 * shared secret stored in Firebase (`performance/bleSecret`).
 */
object PerformanceCryptoAuth {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computeChallengeResponse(secret: String, challengeNonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val raw = mac.doFinal(challengeNonce.toByteArray(Charsets.UTF_8))
        return raw.take(12).joinToString("") { "%02x".format(it) }
    }
}

/**
 * V9 Section 6 Steps 3, 4 & 5:
 * Pure-Kotlin Chord Sheet Section Finder, Chord Detector, and Duplicate Section Merger.
 */
object ChordSheetSectionAnalyzer {

    /**
     * Raw positioned line input before section grouping.
     */
    data class PositionedRawLine(
        val text: String,
        val yTopNorm: Float,
        val yBottomNorm: Float,
        val isBoldFont: Boolean = false
    )

    private val BRACKET_LABEL_REGEX = Regex("""^\s*\[([^\]]+)\]\s*$""")

    // Matches tokens like A, Asus4, F#m7, E/G#, Bm7, D2, C#m, Eb, |D, E|F#m7, |, etc.
    private val CHORD_TOKEN_REGEX = Regex(
        """^\|?[A-G][#b]?(?:m|maj|min|sus|add|dim|aug|M|\d)*(?:/[A-G][#b]?)?\|?$"""
    )

    /**
     * V9 Section 6 Step 3 bullet 4:
     * Detects chord lines either by bold font OR by every token matching a chord pattern
     * (roots A–G with # or b, minor/major/sus/add/dim/aug, numbers like 2, 7, slash bass
     * notes like E/G#, and bar lines `|`).
     */
    fun isChordLine(text: String, isBoldFont: Boolean = false): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (BRACKET_LABEL_REGEX.matches(trimmed)) return false
        if (trimmed.all { it == '-' || it == '_' || it == '=' }) return false

        // Split by whitespace and bar lines while preserving bar-delimited tokens like E|F#m7
        val rawTokens = trimmed
            .replace("|", " | ")
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (rawTokens.isEmpty()) return false

        val allMatchChordOrBar = rawTokens.all { token ->
            token == "|" ||
                token == "/" ||
                token == "x2" ||
                token == "x3" ||
                token == "x4" ||
                CHORD_TOKEN_REGEX.matches(token)
        }
        val hasAtLeastOneChord = rawTokens.any { token ->
            token != "|" && token != "/" && CHORD_TOKEN_REGEX.matches(token)
        }
        if (allMatchChordOrBar && hasAtLeastOneChord) return true

        // If font is explicitly bold and not a title/header line, check if majority of tokens are chords
        if (isBoldFont && hasAtLeastOneChord) {
            val chordCount = rawTokens.count { it == "|" || CHORD_TOKEN_REGEX.matches(it) }
            if (chordCount * 2 >= rawTokens.size) return true
        }
        return false
    }

    /**
     * Returns the bracketed section name if [lineText] is entirely a bracketed phrase
     * (e.g. `[Verse 1]`, `[Pre chorus]`, `[Vesre 4]`), or `null` otherwise.
     */
    fun extractBracketLabelOrNull(lineText: String): String? {
        val m = BRACKET_LABEL_REGEX.matchEntire(lineText.trim()) ?: return null
        val inside = m.groupValues[1].trim()
        return inside.takeIf { it.isNotEmpty() }
    }

    /**
     * Normalizes a section name for duplicate merging (V9 Section 6 Step 4):
     * ignores trailing numbers and case (`"Chorus 1"` -> `"chorus"`, `"Pre chorus"` -> `"pre chorus"`).
     */
    fun normalizeSectionFamilyName(rawLabel: String): String {
        return rawLabel.trim()
            .replace(Regex("""\s+\d+\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.US)
    }

    /**
     * Strips trailing number from a label (`"Chorus 1"` -> `"Chorus"`, `"Pre chorus"` -> `"Pre chorus"`).
     */
    fun stripTrailingNumberFromLabel(rawLabel: String): String {
        return rawLabel.trim().replace(Regex("""\s+\d+\s*$"""), "").trim()
    }

    /**
     * Builds a whitespace-normalized content signature of a section's lines (chords + lyrics)
     * so identical repeated sections merge cleanly.
     */
    fun computeNormalizedContentSignature(lines: List<PositionedRawLine>): String {
        return lines
            .map { it.text.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    data class AnalyzedSongStructure(
        val regions: List<SongSectionRegion>,
        val defaultStructure: List<SongStructureItem>
    )

    /**
     * Executes V9 Section 6 Steps 3, 4, and 5 on a list of positioned lines from a stitched strip.
     */
    fun analyzePositionedLines(
        rawLines: List<PositionedRawLine>
    ): AnalyzedSongStructure {
        val nonBlankLines = rawLines
            .map { it.copy(text = it.text.trimEnd()) }
            .filter { it.text.isNotBlank() }
            .sortedBy { it.yTopNorm }

        if (nonBlankLines.isEmpty()) {
            return createSingleFullSongFallback(emptyList())
        }

        // Compute median line height / vertical step to detect blank line gaps
        val heights = nonBlankLines.map { (it.yBottomNorm - it.yTopNorm).coerceAtLeast(0.004f) }.sorted()
        val medianHeight = heights[heights.size / 2]
        // A gap larger than 0.85x a normal line height indicates an explicit blank line between two rows
        val blankGapThreshold = (medianHeight * 0.85f).coerceAtLeast(0.003f)

        data class RawCandidateSection(
            val rawLabel: String,
            val isUnlabeledAutoSection: Boolean,
            val labelYTopNorm: Float,
            val contentLines: MutableList<PositionedRawLine> = mutableListOf()
        )

        val candidates = mutableListOf<RawCandidateSection>()
        var unlabeledCounter = 0
        var seenFirstBracketLabel = false
        var prevLineBottomNorm = 0f

        for (line in nonBlankLines) {
            val bracketLabel = extractBracketLabelOrNull(line.text)
            if (bracketLabel != null) {
                seenFirstBracketLabel = true
                candidates.add(
                    RawCandidateSection(
                        rawLabel = bracketLabel,
                        isUnlabeledAutoSection = false,
                        labelYTopNorm = line.yTopNorm
                    )
                )
                prevLineBottomNorm = line.yBottomNorm
                continue
            }

            if (!seenFirstBracketLabel) {
                // Step 3 bullet 2: Text before the first label (title lines, "Artist:/Song:") is a header, not a section.
                prevLineBottomNorm = line.yBottomNorm
                continue
            }

            val currentSection = candidates.lastOrNull() ?: continue
            val gapFromPrev = line.yTopNorm - prevLineBottomNorm

            // Step 3 bullet 3:
            // Unlabeled content that follows a blank line after a labeled section (for example a bare
            // `A A A A` chord line, or a closing "Hosanna, Hosanna" tag) becomes its own section
            // named "Unlabeled 1", "Unlabeled 2"...
            // Wait: inside a normal section, chord lines sit directly above lyric lines with no blank line.
            // A blank line after a section has already accumulated at least one lyric/chord pair (or >= 2 lines)
            // and has a vertical gap >= blankGapThreshold.
            val hasCompletedSectionBody = currentSection.contentLines.size >= 2 &&
                currentSection.contentLines.any { !isChordLine(it.text, it.isBoldFont) }

            if (!currentSection.isUnlabeledAutoSection &&
                hasCompletedSectionBody &&
                gapFromPrev >= blankGapThreshold
            ) {
                // Check if this trailing unlabeled block is at the end of a section (e.g. `A A A A` or closing tag)
                unlabeledCounter++
                val unlabeledSec = RawCandidateSection(
                    rawLabel = "Unlabeled $unlabeledCounter",
                    isUnlabeledAutoSection = true,
                    labelYTopNorm = line.yTopNorm
                )
                unlabeledSec.contentLines.add(line)
                candidates.add(unlabeledSec)
            } else {
                currentSection.contentLines.add(line)
            }
            prevLineBottomNorm = line.yBottomNorm
        }

        // Step 1 fallback: If no bracketed labels were found at all, create a single section called "Full song"
        if (candidates.isEmpty()) {
            return createSingleFullSongFallback(nonBlankLines)
        }

        // Compute each candidate's region [yStartNorm, yEndNorm) on the strip:
        // "A section's region is its top-to-bottom range on the strip, from its label to just before the next label (or end)."
        data class BoundedCandidate(
            val index: Int,
            val rawLabel: String,
            val familyName: String,
            val contentSignature: String,
            val yStartNorm: Float,
            val yEndNorm: Float,
            val lines: List<SongTextLine>
        )

        val boundedList = candidates.mapIndexed { idx, cand ->
            val yStart = (cand.labelYTopNorm - medianHeight * 0.25f).coerceIn(0f, 1f)
            val nextCandidate = candidates.getOrNull(idx + 1)
            val lastContentBottom = cand.contentLines.maxOfOrNull { it.yBottomNorm } ?: cand.labelYTopNorm
            val yEnd = if (nextCandidate != null) {
                (nextCandidate.labelYTopNorm - medianHeight * 0.30f).coerceAtLeast(lastContentBottom + medianHeight * 0.2f)
            } else {
                (lastContentBottom + medianHeight * 0.6f).coerceAtMost(1.0f)
            }.coerceIn(yStart + 0.01f, 1.0f)

            val songLines = cand.contentLines.mapIndexed { lineIdx, raw ->
                val yCtr = ((raw.yTopNorm + raw.yBottomNorm) * 0.5f).coerceIn(0f, 1f)
                SongTextLine(
                    lineIndexInSection = lineIdx,
                    text = raw.text.trim(),
                    isChordLine = isChordLine(raw.text, raw.isBoldFont),
                    yTopNorm = raw.yTopNorm.coerceIn(0f, 1f),
                    yBottomNorm = raw.yBottomNorm.coerceIn(0f, 1f),
                    yCenterNorm = yCtr
                )
            }

            val family = if (cand.isUnlabeledAutoSection) {
                cand.rawLabel.lowercase(Locale.US)
            } else {
                normalizeSectionFamilyName(cand.rawLabel)
            }

            BoundedCandidate(
                index = idx,
                rawLabel = cand.rawLabel,
                familyName = family,
                contentSignature = computeNormalizedContentSignature(cand.contentLines),
                yStartNorm = yStart,
                yEndNorm = yEnd,
                lines = songLines
            )
        }

        // Step 4: Merge duplicates.
        // "Merge sections that have the same normalized name (ignore numbers and case) and identical
        // content (lyrics and chords, whitespace-normalized)."
        data class MergeKey(val familyName: String, val contentSignature: String)

        val distinctByMergeKey = LinkedHashMap<MergeKey, BoundedCandidate>()
        for (b in boundedList) {
            val key = MergeKey(b.familyName, b.contentSignature)
            if (!distinctByMergeKey.containsKey(key)) {
                distinctByMergeKey[key] = b
            }
        }

        // Count how many distinct sections exist per familyName after merging:
        // - "If, after merging, a name has exactly one distinct section, show it without a number ('Chorus', 'Pre chorus')."
        // - "If a name has several distinct sections (for example two different bridges), keep their original labels ('Bridge 1', 'Bridge 2'), and merge only the exact repeats within each."
        val distinctCountByFamily = distinctByMergeKey.keys.groupingBy { it.familyName }.eachCount()

        val regionByMergeKey = LinkedHashMap<MergeKey, SongSectionRegion>()
        var regionSeq = 0
        for ((key, firstOccurrence) in distinctByMergeKey) {
            regionSeq++
            val familyCount = distinctCountByFamily[key.familyName] ?: 1
            val displayLabel = if (familyCount == 1 && !key.familyName.startsWith("unlabeled")) {
                stripTrailingNumberFromLabel(firstOccurrence.rawLabel)
            } else {
                firstOccurrence.rawLabel
            }

            val regionId = "sec_${regionSeq}_${
                displayLabel.lowercase(Locale.US).replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
            }"
            regionByMergeKey[key] = SongSectionRegion(
                regionId = regionId,
                rawLabel = firstOccurrence.rawLabel,
                displayLabel = displayLabel,
                normalizedFamilyName = key.familyName,
                category = SectionCategory.fromLabel(displayLabel),
                yStartNorm = firstOccurrence.yStartNorm,
                yEndNorm = firstOccurrence.yEndNorm,
                defaultYStartNorm = firstOccurrence.yStartNorm,
                defaultYEndNorm = firstOccurrence.yEndNorm,
                normalizedContentSignature = key.contentSignature,
                lines = firstOccurrence.lines
            )
        }

        // Step 5: Default structure (play order) lists each section at every place it occurs
        // (including back-to-back repeats!).
        val defaultStructure = boundedList.mapIndexed { orderIdx, b ->
            val key = MergeKey(b.familyName, b.contentSignature)
            val region = regionByMergeKey.getValue(key)
            SongStructureItem(
                instanceId = "struct_${orderIdx + 1}_${region.regionId}",
                regionId = region.regionId
            )
        }

        return AnalyzedSongStructure(
            regions = regionByMergeKey.values.toList(),
            defaultStructure = defaultStructure
        )
    }

    private fun createSingleFullSongFallback(nonBlankLines: List<PositionedRawLine>): AnalyzedSongStructure {
        val songLines = nonBlankLines.mapIndexed { idx, raw ->
            SongTextLine(
                lineIndexInSection = idx,
                text = raw.text.trim(),
                isChordLine = isChordLine(raw.text, raw.isBoldFont),
                yTopNorm = raw.yTopNorm.coerceIn(0f, 1f),
                yBottomNorm = raw.yBottomNorm.coerceIn(0f, 1f),
                yCenterNorm = ((raw.yTopNorm + raw.yBottomNorm) * 0.5f).coerceIn(0f, 1f)
            )
        }
        val region = SongSectionRegion(
            regionId = "sec_1_full_song",
            rawLabel = "Full song",
            displayLabel = "Full song",
            normalizedFamilyName = "full song",
            category = SectionCategory.FULL_SONG,
            yStartNorm = 0f,
            yEndNorm = 1f,
            defaultYStartNorm = 0f,
            defaultYEndNorm = 1f,
            normalizedContentSignature = "full_song",
            lines = songLines
        )
        val struct = listOf(
            SongStructureItem(
                instanceId = "struct_1_sec_1_full_song",
                regionId = region.regionId
            )
        )
        return AnalyzedSongStructure(
            regions = listOf(region),
            defaultStructure = struct
        )
    }
}
