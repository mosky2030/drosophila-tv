package app.orchard.tv.settings

import kotlin.math.min
import kotlin.math.pow

/**
 * 桌宠的体型与族群 —— 出生那天最小，养满一年最大；半年多一只，一年再多一只，最多三只。
 *
 * 曲线取 d^0.35（d = 天数 / 365）。指数不是随手挑的，它要同时满足三件事：
 *   • 头一天就看得出长了一点：18px → 38px（一夜之间身子大了一圈，养着才有反馈）；
 *   • 一个月不能把增长用光：30 天 85px，之后还有大半年可长；
 *   • 一年到 180px 收口。线性曲线前几周几乎看不出来，平方根曲线又会在头十天就把大半吃完。
 *
 * 上限 = 最小值的 10 倍（the owner, 2026-11-19：「最大的时候是最小的时候的10倍。现在感觉是
 * 大概4-5倍」）。之前上限是精灵图的原生 64px（1:1 落像素，最清），但那样最大只有最小的 3.6 倍，
 * 在电视上还是「一小只」。180px 是 64px 精灵放大 2.8 倍：像素格子会变粗，而这正是像素风该有的
 * 样子——PETOverlay 在大体型时用最近邻取样（FilterQuality.None），只放大不糊。
 *
 * 两个使用点必须共用这一份计算，否则「关闭桌宠」提示里报的像素数会和屏幕上的果蝇不一致：
 * PetOverlay 画它、WallpaperSheet 报它。体型调试预览已按机主要求去掉（2026-11-19）。
 */
object PetGrowth {
    /**
     * 刚孵化：18px —— 和之前定稿的「小的样子」一样大（身子约 14px）。
     * 试过 14px 与 11px：在电视上就只剩一团色块，认不出是苍蝇（2026-11-19 模拟器实测）。
     */
    const val MIN_PX = 18f

    /** 养满一年：最小值的 10 倍（the owner, 2026-11-19）。 */
    const val MAX_PX = MIN_PX * 10f

    const val YEAR_DAYS = 365f

    /**
     * 第二只、第三只出现的时间点（天）。这两只不在任何地方存档：它们的出生时间是从主果蝇的
     * 年龄推出来的（[bornOffsetSec]），所以「保留历史」这件事对它们自动成立——主果蝇的出生
     * 时间还在，族群数量就还是对的，不存在「重装之后变成一只」。
     */
    const val MATE_1_DAYS = 180f
    const val MATE_2_DAYS = 365f

    /** 最多三只（the owner, 2026-11-19：「最多三只」）。 */
    const val MAX_FLIES = 3

    /** 年龄 → 0..1 的成长进度。 */
    fun fraction(ageSec: Float): Float {
        val days = ageSec.coerceAtLeast(0f) / 86_400f
        return min(1f, days / YEAR_DAYS).toDouble().pow(0.35).toFloat()
    }

    /** 年龄 → 体型，单位是 64px 精灵图的画布边长。 */
    fun px(ageSec: Float): Float = MIN_PX + fraction(ageSec) * (MAX_PX - MIN_PX)

    /** 已经长出来的像素数（关闭桌宠的提示要报这个数）。 */
    fun grown(ageSec: Float): Int = Math.round(px(ageSec) - MIN_PX)

    /**
     * 现在应该有几位成员：主果蝇一只，满 [MATE_1_DAYS] 天多一只小的，满 [MATE_2_DAYS] 天再多
     * 一只，封顶 [MAX_FLIES]（the owner, 2026-11-19：「养到半年以后会出现两只果蝇，一大一小，
     * 养到1年，会出现3只，两大一小。最多三只」）。
     */
    fun count(ageSec: Float): Int {
        val days = ageSec.coerceAtLeast(0f) / 86_400f
        var n = 1
        if (days >= MATE_1_DAYS) n++
        if (days >= MATE_2_DAYS) n++
        return min(n, MAX_FLIES)
    }

    /** 第 [index] 只「出生」在主果蝇出生之后的多少秒（0 = 主果蝇本人）。 */
    fun bornOffsetSec(index: Int): Float = when (index) {
        1 -> MATE_1_DAYS * 86_400f
        2 -> MATE_2_DAYS * 86_400f
        else -> 0f
    }

    /**
     * 第 [index] 只自己的年龄：主果蝇的年龄减去它出现的时间点。所以在半年的那一天刚冒出来的
     * 第二只是最小的（18px），之后它自己也会长大；主果蝇一岁时第二只已经 185 天大（约 145px）、
     * 第三只刚出生（18px）——正好是「两大一小」。
     */
    fun ageSecOf(index: Int, ageSec: Float): Float =
        if (index <= 0) ageSec else (ageSec - bornOffsetSec(index)).coerceAtLeast(0f)

    /** 第 [index] 只的体型，只由它自己的年龄决定。 */
    fun pxOf(index: Int, ageSec: Float): Float = px(ageSecOf(index, ageSec))
}
