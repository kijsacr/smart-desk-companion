package com.smartdesk.companion.shared

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class ClockTheme(
    val id: String,
    val name: String,
    val mood: String,
    val digitColors: List<Color>,
    val overlapHighlightColors: List<Color>,
    val colonDotColor: Color,
    val colonAlpha: Float = 0.94f,
    val overlapAlpha: Float = 0.90f
)

/**
 * Module G: Clock Themes
 * Themes 1 ("ocean_blue") and 2 ("sunset_float") retain the exact existing colour values
 * from V3 so they render identically to today. Themes 3–10 add the new V4 palettes.
 */
object ClockThemeCatalog {

    val OCEAN_BLUE = ClockTheme(
        id = "ocean_blue",
        name = "Ocean Blue",
        mood = "Cool blues",
        digitColors = listOf(
            Color(0xFF0060CE),
            Color(0xFF4BA8FF),
            Color(0xFF0060CE),
            Color(0xFF4BA8FF)
        ),
        overlapHighlightColors = listOf(
            Color(0xFF6FE3FF),
            Color(0xFF6FE3FF)
        ),
        colonDotColor = Color(0xFFEFF4FA)
    )

    val SUNSET_FLOAT = ClockTheme(
        id = "sunset_float",
        name = "Sunset Float",
        mood = "Blue and orange",
        digitColors = listOf(
            Color(0xFF63B8FF),
            Color(0xFFFF9E44),
            Color(0xFF005BC5),
            Color(0xFFFF6200)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFFFB8C6),
            Color(0xFFFF8E72)
        ),
        colonDotColor = Color(0xFFEFF4FA)
    )

    val AURORA = ClockTheme(
        id = "aurora",
        name = "Aurora",
        mood = "Green/teal/violet northern lights",
        digitColors = listOf(
            Color(0xFF34D399),
            Color(0xFF22D3EE),
            Color(0xFFA78BFA),
            Color(0xFF60A5FA)
        ),
        overlapHighlightColors = listOf(
            Color(0xFF67E8F9),
            Color(0xFFC4B5FD)
        ),
        colonDotColor = Color(0xFFE0F2FE)
    )

    val ROSE_QUARTZ = ClockTheme(
        id = "rose_quartz",
        name = "Rose Quartz",
        mood = "Soft pink and peach",
        digitColors = listOf(
            Color(0xFFFB7185),
            Color(0xFFF9A8D4),
            Color(0xFFFDBA74),
            Color(0xFFC084FC)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFFECDD3),
            Color(0xFFF5D0FE)
        ),
        colonDotColor = Color(0xFFFFE4E6)
    )

    val EMERALD = ClockTheme(
        id = "emerald",
        name = "Emerald",
        mood = "Fresh greens",
        digitColors = listOf(
            Color(0xFF10B981),
            Color(0xFF6EE7B7),
            Color(0xFF14B8A6),
            Color(0xFFA3E635)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFA7F3D0),
            Color(0xFFD9F99D)
        ),
        colonDotColor = Color(0xFFD1FAE5)
    )

    val AMBER_GLOW = ClockTheme(
        id = "amber_glow",
        name = "Amber Glow",
        mood = "Warm golds and oranges",
        digitColors = listOf(
            Color(0xFFF59E0B),
            Color(0xFFFB923C),
            Color(0xFFFCD34D),
            Color(0xFFF97316)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFFDE68A),
            Color(0xFFFED7AA)
        ),
        colonDotColor = Color(0xFFFEF3C7)
    )

    val VIOLET_DUSK = ClockTheme(
        id = "violet_dusk",
        name = "Violet Dusk",
        mood = "Purple twilight",
        digitColors = listOf(
            Color(0xFF8B5CF6),
            Color(0xFFC084FC),
            Color(0xFF6366F1),
            Color(0xFFF472B6)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFDDD6FE),
            Color(0xFFFBCFE8)
        ),
        colonDotColor = Color(0xFFEDE9FE)
    )

    val CYBER_NEON = ClockTheme(
        id = "cyber_neon",
        name = "Cyber Neon",
        mood = "Bright synthwave mix",
        digitColors = listOf(
            Color(0xFF22D3EE),
            Color(0xFFF0ABFC),
            Color(0xFF4ADE80),
            Color(0xFFFACC15)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFF5D0FE),
            Color(0xFFFEF08A)
        ),
        colonDotColor = Color(0xFFCFFAFE)
    )

    val MONO_ICE = ClockTheme(
        id = "mono_ice",
        name = "Mono Ice",
        mood = "Minimal greyscale",
        digitColors = listOf(
            Color(0xFFE5E7EB),
            Color(0xFF9CA3AF),
            Color(0xFFF3F4F6),
            Color(0xFFD1D5DB)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFFFFFFF),
            Color(0xFFE5E7EB)
        ),
        colonDotColor = Color(0xFF9CA3AF)
    )

    val NIGHT_DIM = ClockTheme(
        id = "night_dim",
        name = "Night Dim",
        mood = "Very dim amber for bedside use, low blue light",
        digitColors = listOf(
            Color(0xFFB45309),
            Color(0xFF92400E),
            Color(0xFF78350F),
            Color(0xFFA16207)
        ),
        overlapHighlightColors = listOf(
            Color(0xFFD97706),
            Color(0xFFB45309)
        ),
        colonDotColor = Color(0xFF78350F)
    )

    val allThemes: List<ClockTheme> = listOf(
        OCEAN_BLUE,
        SUNSET_FLOAT,
        AURORA,
        ROSE_QUARTZ,
        EMERALD,
        AMBER_GLOW,
        VIOLET_DUSK,
        CYBER_NEON,
        MONO_ICE,
        NIGHT_DIM
    )

    private val lookupMap: Map<String, ClockTheme> =
        allThemes.associateBy { it.id }

    fun findById(themeId: String?): ClockTheme {
        if (themeId.isNullOrBlank()) return SUNSET_FLOAT
        return when (themeId) {
            "BLUE_ORANGE_FLOAT" -> SUNSET_FLOAT
            "DUAL_BLUE_FLOAT" -> OCEAN_BLUE
            else -> lookupMap[themeId] ?: SUNSET_FLOAT
        }
    }
}
