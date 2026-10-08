package quest.montana.app

/**
 * THE UNITS OF A SPAN, AS iOS COUNTS THEM (K.5; iOS MTBizText.span and MTBizText.hours, Foundation's Duration units style). Pure
 * arithmetic, no platform: the platform's own words for each unit come after (BizSupplyText, MeasureFormat). Each result is a
 * list of (count, the unit's length in seconds), the biggest unit first.
 *
 * span -- days, hours, minutes, seconds, at most two units (maximumUnitCount 2): the milliseconds rounded to whole seconds, half
 * to even; the first unit is the biggest that is not zero, the second the next that is not zero; the whole is rounded to the
 * second unit, half to even, and told in those two units alone -- so 23 h 59 min 59 s reads 24 h 0 min and 3 days 59 min 45 s
 * reads 3 days 60 min, as iOS does. Matched against Foundation on 607 spans (Swift, macOS, en_US): no difference.
 *
 * hours -- hours and minutes however many hours (a week's or a month's work is never told in days), the minute cut down, never
 * rounded up: worked time is never shown longer than it was. A unit at zero is not told; nothing at all reads 0 min.
 */
object BizDuration {
    const val DAY = 86_400L
    const val HOUR = 3_600L
    const val MINUTE = 60L
    const val SECOND = 1L
    private val SPAN_UNITS = longArrayOf(DAY, HOUR, MINUTE, SECOND)

    /** n / d rounded to the nearest whole, a half to the even one (Foundation's toNearestOrEven); n and d not negative. */
    private fun halfEven(n: Long, d: Long): Long {
        val q = n / d
        val r = n % d
        return when {
            2 * r == d -> q + (q % 2)
            d < 2 * r -> q + 1
            else -> q
        }
    }

    fun span(ms: Long): List<Pair<Long, Long>> {
        val s = halfEven(maxOf(0L, ms), 1000L)
        // the units this span has a count of, by its decomposition (the next bigger unit is the last bigger one in the list)
        val shown = SPAN_UNITS.filter { u -> (s % (SPAN_UNITS.lastOrNull { it > u } ?: Long.MAX_VALUE)) / u != 0L }
        if (shown.isEmpty()) return listOf(0L to SECOND)
        val first = shown[0]
        if (shown.size == 1) return listOf(s / first to first)
        val second = shown[1]
        val whole = halfEven(s, second) * second
        return listOf(whole / first to first, (whole % first) / second to second)
    }

    fun hours(seconds: Long): List<Pair<Long, Long>> {
        val s = maxOf(0L, seconds)
        return listOf(s / HOUR to HOUR, s % HOUR / MINUTE to MINUTE).filter { it.first != 0L }.ifEmpty { listOf(0L to MINUTE) }
    }
}
