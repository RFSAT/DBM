package com.rfsat.dms.parking

/**
 * Parser for the OSM conditional-restriction grammar used by the street-parking
 * tags, e.g.
 *
 *     no_stopping @ (Mo-Fr 07:00-09:00)
 *     permit @ (Mo-Fr 08:00-18:30)
 *     no_stopping @ (09:00-15:00); no_parking @ (Mo-Fr 07:00-09:00,15:00-18:00)
 *
 * Semantics implemented (the subset that actually appears on parking tags):
 *  - Rules are separated by ';' and evaluated IN ORDER — a later matching rule
 *    overrides an earlier one. This is the documented precedence.
 *  - A condition may carry weekday ranges (Mo-Fr, Sa-Su, Mo,We,Fr), time spans
 *    (07:00-09:00), or both. Multiple time spans may be comma-separated.
 *  - A rule with no condition applies unconditionally.
 *  - Anything we cannot parse is IGNORED rather than guessed at, so an exotic
 *    condition degrades to "no opinion" instead of a wrong warning.
 *
 * Deliberately NOT implemented: stay-duration conditions ("stay < 2 hours"),
 * date ranges, public holidays, week numbers. Those return [UNPARSED] so the
 * caller can present the raw text to the driver instead of a decision.
 */
object ParkingCondition {

    /** Returned when a condition exists but uses syntax we don't evaluate. */
    const val UNPARSED = "\u0000unparsed"

    data class Rule(val value: String, val condition: String?)

    private val DAYS = mapOf(
        "MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6, "SU" to 7)

    /** Split a conditional tag value into ordered rules. */
    fun parse(raw: String): List<Rule> =
        raw.split(';').mapNotNull { part ->
            val s = part.trim()
            if (s.isEmpty()) return@mapNotNull null
            val at = s.indexOf('@')
            if (at < 0) Rule(s, null)
            else {
                val v = s.substring(0, at).trim()
                val c = s.substring(at + 1).trim().removePrefix("(").removeSuffix(")").trim()
                if (v.isEmpty()) null else Rule(v, c.ifEmpty { null })
            }
        }

    /**
     * Resolve the value in force at the given moment.
     *
     * @param base        the unconditional tag value, or null
     * @param conditional the ':conditional' tag value, or null
     * @param dow         ISO day of week, 1=Monday .. 7=Sunday
     * @param minutes     minutes since midnight, 0..1439
     * @return the value in force, [UNPARSED] if a condition could not be
     *         evaluated, or null if nothing applies.
     */
    fun resolve(base: String?, conditional: String?, dow: Int, minutes: Int): String? {
        var current = base
        val rules = conditional?.let { parse(it) } ?: return current
        for (r in rules) {
            when (matches(r.condition, dow, minutes)) {
                MatchResult.YES -> current = r.value
                MatchResult.NO -> Unit
                MatchResult.UNKNOWN -> return UNPARSED
            }
        }
        return current
    }

    private enum class MatchResult { YES, NO, UNKNOWN }

    private fun matches(cond: String?, dow: Int, minutes: Int): MatchResult {
        if (cond == null) return MatchResult.YES
        val c = cond.trim().uppercase()
        // Reject the constructs we deliberately don't evaluate.
        if (c.contains("STAY") || c.contains("PH") || c.contains("WEIGHT") ||
            c.contains("AND") || Regex("""\d{4}""").containsMatchIn(c)) {
            return MatchResult.UNKNOWN
        }

        // Separate the weekday part (leading) from the time spans.
        val timeSpans = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})""")
            .findAll(c).map {
                val (h1, m1, h2, m2) = it.destructured
                (h1.toInt() * 60 + m1.toInt()) to (h2.toInt() * 60 + m2.toInt())
            }.toList()
        val dayPart = c.substringBefore(timeSpans.firstOrNull()?.let {
            Regex("""\d{1,2}:\d{2}""").find(c)?.value } ?: "").trim()

        val dayOk = if (dayPart.isBlank()) true else dayMatches(dayPart, dow)
            ?: return MatchResult.UNKNOWN
        if (!dayOk) return MatchResult.NO
        if (timeSpans.isEmpty()) return MatchResult.YES
        val inSpan = timeSpans.any { (a, b) ->
            if (b >= a) minutes >= a && minutes < b
            else minutes >= a || minutes < b        // span crossing midnight
        }
        return if (inSpan) MatchResult.YES else MatchResult.NO
    }

    /** null = could not parse the day expression. */
    private fun dayMatches(expr: String, dow: Int): Boolean? {
        var sawAny = false
        for (token in expr.split(',')) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val range = t.split('-')
            when (range.size) {
                1 -> {
                    val d = DAYS[range[0]] ?: return null
                    sawAny = true
                    if (d == dow) return true
                }
                2 -> {
                    val a = DAYS[range[0].trim()] ?: return null
                    val b = DAYS[range[1].trim()] ?: return null
                    sawAny = true
                    val hit = if (a <= b) dow in a..b else (dow >= a || dow <= b)
                    if (hit) return true
                }
                else -> return null
            }
        }
        return if (sawAny) false else null
    }
}
