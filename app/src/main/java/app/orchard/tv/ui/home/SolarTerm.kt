package app.orchard.tv.ui.home

import java.time.LocalDate
import kotlin.math.sin

/**
 * The twenty-four solar terms (节气), for the large clock beside the Chinese date: the term the day
 * falls in, as "立冬". Computed from the sun's apparent longitude rather than tabulated: on
 * [LocalDate] a table would run to thousands of dates, and the low-precision series below places
 * every crossing within a quarter of an hour (Meeus, Astronomical Algorithms, ch. 25), which is far
 * finer than the day this is read at.
 */
object SolarTerm {
    /**
     * From the spring equinox, where the sun's longitude is 0°, round the year: each term is the
     * moment that longitude reaches the next multiple of 15°.
     */
    private val NAMES = arrayOf(
        "春分", "清明", "谷雨", "立夏", "小满", "芒种",
        "夏至", "小暑", "大暑", "立秋", "处暑", "白露",
        "秋分", "寒露", "霜降", "立冬", "小雪", "大雪",
        "冬至", "小寒", "大寒", "立春", "雨水", "惊蛰",
    )

    /**
     * The term [date] falls in, as "立冬". Read on Beijing time, the calendar the Chinese date
     * beside it follows; a day a crossing falls on is named for the term that starts with it.
     */
    fun of(date: LocalDate): String? {
        val from = longitude(date, 0.0)
        val to = longitude(date, 24.0)
        val noon = longitude(date, 12.0)
        // A crossing inside the day starts its term on this day; otherwise the term of the noon.
        val slot = if (slot(to) != slot(from)) slot(to) else slot(noon)
        return NAMES[slot]
    }

    /** Which of the twenty-four fifteenths of the circle a longitude falls in. */
    private fun slot(degrees: Double): Int = ((degrees / 15.0).toInt() % 24 + 24) % 24

    /**
     * The sun's apparent longitude in degrees at [hour] o'clock Beijing time on [date]: its mean
     * longitude with the equation of the centre, corrected for aberration and nutation.
     */
    private fun longitude(date: LocalDate, hour: Double): Double {
        // 2440587.5 is 1970-01-01 00:00 UT; the day counts from there, and Beijing is eight on.
        val jd = 2440587.5 + date.toEpochDay() + (hour - 8.0) / 24.0
        val t = (jd - 2451545.0) / 36525.0
        val mean = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val centre = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mean) +
            (0.019993 - 0.000101 * t) * sin(2 * mean) + 0.000289 * sin(3 * mean)
        val node = Math.toRadians(125.04 - 1934.136 * t)
        val apparent = 280.46646 + 36000.76983 * t + 0.0003032 * t * t + centre -
            0.00569 - 0.00478 * sin(node)
        return ((apparent % 360.0) + 360.0) % 360.0
    }
}
