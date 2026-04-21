package com.asksakis.freegate.utils

/**
 * Beautify Frigate's snake_case / kebab-case identifiers for UI display. The raw
 * identifier still goes over the wire and into SharedPreferences — this is purely
 * a presentation helper.
 */
object FrigateNameFormatter {

    private val separators = "[_\\-]+".toRegex()

    fun pretty(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .split(separators)
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> c.uppercaseChar() }
            }
    }
}
