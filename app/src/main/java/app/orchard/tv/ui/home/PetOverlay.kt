package app.orchard.tv.ui.home

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.PetGrowth
import app.orchard.tv.settings.PetMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// 行为状态。the owner, 2026-11-19：「偶尔会飞行，不规则路线，S 形飞，飞不远再继续原地待着，
// 或者再趴一会」「飞到桌面边界撞上后会仰面不动，然后疯狂自转，在小范围移动，模拟真正的苍蝇
// 翻过来的状态。自转一会儿又正过来（硬切就好），继续随意爬行、飞行」
private const val S_IDLE = 0      // 原地待着：只有头在慢慢左右摆
private const val S_CRAWL = 1     // 爬：直线快走，八帧步态
private const val S_FLY = 2       // 飞：S 形，翅膀高频扇
private const val S_BACK = 3      // 仰面躺着一动不动（偶尔蹬一下腿）
private const val S_SPIN = 4      // 仰面疯狂自转，小范围乱蹭
private const val S_GROOM = 5     // 搓腿：两条前腿一上一下对搓（the owner, 2026-11-20）
private const val S_WING = 6      // 理翅：翅膀慢慢收放开合，像在梳理

// 帧索引（docs 里 tools/psd_bake.py 的布局，必须和它一致）：
//   00..02  静止摆头    03..10  八分之一步态    11..13  收翅    back  仰面
//   15..16  搓腿两相（tools/fly_groom.py 把前腿绕根部转出两个相位，其余像素不动）
private val IDLE_FRAMES = intArrayOf(1, 0, 1, 2)
private val BURST_FRAMES = intArrayOf(3, 11, 12, 13, 12, 11)
// 理翅：拿现成的收翅帧从「大张」慢慢折到「收拢」再放开。没有源 PSD 就不要再造新翅膀，
// 这三帧本来就是同一个翅膀绕枢轴转出来的，连起来看正好是「把翅膀收回去理一理」。
private val WING_FRAMES = intArrayOf(11, 12, 13, 12)
private const val WALK_FIRST = 3
private const val WALK_COUNT = 8
private const val BACK_FRAME = 14
private const val GROOM_0 = 15
private const val GROOM_1 = 16

/**
 * 果蝇的世界坐标。状态机永远在 1920×1080 的虚拟桌面里跑，主屏、屏保、个性化卡片、手机缩略图
 * 只是同一只果蝇在不同大小的窗口里的投影（the owner, 2026-11-19：「全部画面都有」）。
 * 这样多个画面同时画它也不会各自推着状态机跑，成长和位置永远只有一份。
 */
private const val VIRTUAL_W = 1920f
private const val VIRTUAL_H = 1080f

/**
 * 落影（the owner, 2026-11-20：「加阴影，但是上次加的椭圆阴影太难看了，所以去掉了，这次希望
 * 能好看一些」）。
 *
 * 上一版是个半透明椭圆，两个毛病：一是硬边，边界一刀切；二是和果蝇的形状毫无关系——一只六条腿
 * 两只翅膀的虫子，底下一个规规矩矩的椭圆，看着就是贴了张灰纸。这一版换成**用它自己的轮廓**做
 * 投影（tools/fly_shadow.py 把每帧的 alpha 模糊 σ=1.8 后染黑烘好），投出来就是这只果蝇的形状，
 * 腿缝里、翅膀边上露出的那一小截暗才像它真的压在桌面上。
 *
 * 影子**不跟着飞行方向转**（[SHADOW_LIFT] 是屏幕坐标里的下移），但会跟着果蝇自身朝向转——轮廓
 * 本来就是它自己的，转一起才是同一个东西。半径随体型缩放，所以幼体底下只是一小片淡影。
 */
private const val SHADOW_LIFT = 0.05f     // 影子相对体型下移的比例（屏幕方向，不是机头方向）

private class PetState {
    var x = -1f
    var y = -1f
    var w = VIRTUAL_W
    var h = VIRTUAL_H
    var px = 18f           // 本只当前的体型（由年龄/族群算出来，每个 tick 写一次）
    var heading = 0f
    var state = S_IDLE
    var decide = 1f        // 本状态还剩多久（idle 停顿 / 仰面躺多久 / 自转多久）
    var pace = 0f          // 本段的 px/s
    var dist = 0f          // 本段还剩多少 px，走完立刻停下
    var wob = 0f           // 飞行 S 形 / 自转乱蹭的相位
    var wobAmp = 2.2f      // S 形转向幅度（rad/s）
    var spin = 0f          // 仰面自转角度（度）
    var spinVel = 0f       // 角速度（度/秒），会加速
    var spinX = 0f         // 自转围绕的中心（落点）
    var spinY = 0f
    var twitch = 0f        // 仰面蹬腿的计时
    var gait = 0f          // 步态相位 0..1：按「走了多远」推进，不和帧率挂钩
}

private fun PetState.randomTarget(mx: Float, my: Float): Pair<Float, Float> {
    val tx = mx + Math.random().toFloat() * (w - 2 * mx)
    val ty = my + Math.random().toFloat() * (h - 2 * my)
    return tx to ty
}

/**
 * 飞行时能贴到多边上。原来是个固定比例（宽 3.5%、高 5%），体形长到 180px 之后那点留白不够半个
 * 身子，大果蝇就会有一半飞到屏幕外面去，所以再取一遍「半个身子」。
 */
private val PetState.edgeX: Float get() = maxOf(w * 0.035f, px * 0.55f)
private val PetState.edgeY: Float get() = maxOf(h * 0.05f, px * 0.55f)

private fun PetState.toIdle(lo: Float, hi: Float) {
    state = S_IDLE
    decide = lo + Math.random().toFloat() * (hi - lo)
}

/**
 * 沿当前朝向到飞行边界还有多远（px）。正常飞行的落点要收在墙前面——不然以现在的飞行距离，
 * 大部分飞行都是「撞墙」结束，翻面就从意外变成了常态（the owner, 2026-11-19）。
 */
private fun wallAhead(st: PetState): Float {
    val dx = cos(st.heading)
    val dy = sin(st.heading)
    val mx = st.edgeX
    val my = st.edgeY
    var d = Float.MAX_VALUE
    if (dx > 0.01f) d = min(d, (st.w - mx - st.x) / dx)
    if (dx < -0.01f) d = min(d, (mx - st.x) / dx)
    if (dy > 0.01f) d = min(d, (st.h - my - st.y) / dy)
    if (dy < -0.01f) d = min(d, (my - st.y) / dy)
    return if (d == Float.MAX_VALUE) 10_000f else d.coerceAtLeast(0f)
}

/**
 * 把一段飞行收进桌面：用和 S_FLY 完全相同的积分（heading += sin(wob)·amp·dt）预演一遍，
 * 走到边界就把距离截在那里。正常飞行的落点因此永远在屏幕里，撞墙翻面只剩 aimEdge 那一路。
 * 返回实际能飞的距离（不少于 120px，太短的飞行看起来像抖动）。
 */
private fun fitFlight(st: PetState, dist: Float): Float {
    val dt = 0.033f
    val step = st.pace * dt
    val mx = st.edgeX
    val my = st.edgeY
    var x = st.x
    var y = st.y
    var h = st.heading
    var w = st.wob
    var travelled = 0f
    while (travelled < dist) {
        w += dt * 13f
        h += sin(w) * st.wobAmp * dt
        x += cos(h) * step
        y += sin(h) * step
        if (x < mx || x > st.w - mx || y < my || y > st.h - my) {
            return (travelled - step).coerceIn(120f, dist)
        }
        travelled += step
    }
    return dist
}

/** 一次状态推进（dt 秒）。纯逻辑，不碰 Canvas。 */
private fun step(st: PetState, dt: Float) {
    if (st.w <= 0f || st.h <= 0f) return

    when (st.state) {
        S_IDLE -> {
            st.decide -= dt
            if (st.decide <= 0f) {
                // 待够了，挑下一件事做。搓腿和理翅各占约一成（the owner, 2026-11-20：
                // 「加搓腿/理翅动作帧」），剩下的八成还是原来的「三成起飞、七成爬」——
                // 两段随机数各管各的，所以原来那两个比例一点没变。
                val r = Math.random()
                if (r < 0.10) {
                    st.state = S_GROOM
                    st.decide = 1.1f + Math.random().toFloat() * 1.2f
                } else if (r < 0.19) {
                    st.state = S_WING
                    st.decide = 1.4f + Math.random().toFloat() * 1.4f
                } else if (Math.random() < 0.30) {
                    // 起飞。绝大多数是随便飞一段就落地趴着；「一头撞到墙上再翻面」是极小概率
                    // 的意外（the owner, 2026-11-19：撞墙仰面自转是极小概率事件，不用太经常，
                    // 大部分时间还是爬着、走动、飞行这些基本动作）。
                    val aimEdge = Math.random() < 0.03
                    val tx: Float
                    val ty: Float
                    var dist: Float
                    if (aimEdge) {
                        val pad = 160f
                        when ((Math.random() * 4).toInt()) {
                            0 -> { tx = -pad; ty = Math.random().toFloat() * st.h }
                            1 -> { tx = st.w + pad; ty = Math.random().toFloat() * st.h }
                            2 -> { tx = Math.random().toFloat() * st.w; ty = -pad }
                            else -> { tx = Math.random().toFloat() * st.w; ty = st.h + pad }
                        }
                        dist = 1600f                      // 安全上限：别真的一直飞
                    } else {
                        val (ax, ay) = st.randomTarget(st.w * 0.14f, st.h * 0.12f)
                        tx = ax; ty = ay
                        dist = hypot(tx - st.x, ty - st.y).coerceIn(420f, 900f)
                    }
                    st.heading = atan2(ty - st.y, tx - st.x)   // S 形的基础方向
                    // 飞行要「明显快很多」：爬 170–230，飞 1000–1200，差 5 倍左右（the owner,
                    // 2026-11-19）。速度一快，同样的距离一晃就到，所以距离也一起拉长到 420–900。
                    st.pace = 1000f + Math.random().toFloat() * 200f
                    st.wob = Math.random().toFloat() * 6.2832f
                    st.wobAmp = 5.0f + Math.random().toFloat() * 2.0f
                    if (!aimEdge) {
                        // 正常飞行：先看这一侧还有多少地方，贴着墙就掉头往屏幕中间飞（不然
                        // 一出门就撞上，翻面又变成常态），再把距离收到「整条弯曲航迹都在桌面
                        // 之内」。撞墙就只剩 aimEdge 那一路极小概率。
                        if (wallAhead(st) < 320f) st.heading = atan2(st.h * 0.5f - st.y, st.w * 0.5f - st.x)
                        dist = fitFlight(st, dist)
                    }
                    st.dist = dist
                    st.state = S_FLY
                } else {
                    val (tx, ty) = st.randomTarget(st.w * 0.18f, st.h * 0.15f)
                    st.heading = atan2(ty - st.y, tx - st.x)
                    st.pace = 170f + Math.random().toFloat() * 60f
                    // 爬多远才停：下限按 the owner 2026-11-19 的改动从 120 缩到 30 px
                    st.dist = hypot(tx - st.x, ty - st.y).coerceIn(30f, 420f)
                    st.state = S_CRAWL
                }
            }
        }

        S_CRAWL -> {
            val step = st.pace * dt
            val nx = st.x + cos(st.heading) * step
            val ny = st.y + sin(st.heading) * step
            val mx = st.w * 0.18f
            val my = st.h * 0.15f
            if (nx < mx || nx > st.w - mx || ny < my || ny > st.h - my) {
                // 撞界：本段作废、原地急停，不沿边滑
                st.x = nx.coerceIn(mx, st.w - mx)
                st.y = ny.coerceIn(my, st.h - my)
                st.toIdle(0.8f, 2.2f)
            } else {
                st.x = nx
                st.y = ny
                st.dist -= step
                // 爬完停一下就走开：以前是 1–30 秒，大半时间都在发呆；the owner 要的是
                // 「大部分时间还是爬着、走动、飞行」(2026-11-19)
                if (st.dist <= 0f) st.toIdle(0.6f, 8f)
            }
        }

        S_FLY -> {
            // S 形：让朝向按正弦摆，轨迹就是一条起伏的曲线（sin 的积分是 cos，所以是弯的）
            st.wob += dt * 13f
            st.heading += sin(st.wob) * st.wobAmp * dt
            val step = st.pace * dt
            val nx = st.x + cos(st.heading) * step
            val ny = st.y + sin(st.heading) * step
            // 飞行用真正的桌面边界（比爬行的活动区宽松），撞上了才翻面
            val mx = st.edgeX
            val my = st.edgeY
            if (nx < mx || nx > st.w - mx || ny < my || ny > st.h - my) {
                st.x = nx.coerceIn(mx, st.w - mx)
                st.y = ny.coerceIn(my, st.h - my)
                st.state = S_BACK
                st.decide = 0.8f + Math.random().toFloat() * 0.9f
                st.spin = 0f
                st.spinVel = 720f + Math.random().toFloat() * 420f
                st.twitch = 0f
            } else {
                st.x = nx
                st.y = ny
                st.dist -= step
                if (st.dist <= 0f) st.toIdle(0.8f, 6f)     // 落地，稍稍趴一会儿
            }
        }

        S_BACK -> {
            st.decide -= dt
            st.twitch += dt
            if (st.decide <= 0f) {
                st.state = S_SPIN
                st.decide = 2.6f + Math.random().toFloat() * 2.0f
                st.spinX = st.x
                st.spinY = st.y
                st.wob = 0f
            }
        }

        S_SPIN -> {
            st.decide -= dt
            st.spinVel = min(2340f, st.spinVel + 1560f * dt)
            st.spin += st.spinVel * dt
            if (st.spin > 3600f) st.spin -= 3600f
            // 小范围乱蹭：绕着落点画小圈
            st.wob += dt * 3.1f
            val r = 10f + 6f * sin(st.wob * 0.7f)
            val pad = maxOf(30f, st.px * 0.5f)
            st.x = (st.spinX + cos(st.wob) * r).coerceIn(pad, st.w - pad)
            st.y = (st.spinY + sin(st.wob * 1.3f) * r * 0.75f).coerceIn(pad, st.h - pad)
            if (st.decide <= 0f) {
                st.spin = 0f                                  // 硬切回正面（用户：硬切就好）
                st.toIdle(1.2f, 8f)
            }
        }

        // 搓腿和理翅都是原地不动的小动作，所以只倒计时、不碰坐标。腿脚和翅膀的相位不在
        // 状态里记——画的时候按全局帧号取（commands 里的 S_GROOM / S_WING 两支），
        // 三只果蝇共用一支心跳，帧号已经是一份现成的时钟，再存一份反而会不同步。
        S_GROOM -> {
            st.decide -= dt
            if (st.decide <= 0f) st.toIdle(0.9f, 5f)
        }

        S_WING -> {
            st.decide -= dt
            if (st.decide <= 0f) st.toIdle(0.8f, 5f)
        }
    }

    // 步态相位：按当前速度推进，不按帧数。以前是「每 tick 换一帧」= 固定 3.75 步/秒，爬慢
    // 下来之后那就成了原地狂奔；现在步子跟着地面速度走（the owner, 2026-11-19）。
    val speed = if (st.state == S_CRAWL || st.state == S_FLY) st.pace else 0f
    st.gait = (st.gait + dt * (0.8f + speed * 0.006f)) % 1f
}

/**
 * 三只互不重叠：按各自体型留出半径，挨得太近就对称推开（the owner, 2026-11-19：
 * 「三只不能重叠」）。放在每轮 step 之后，所以就算两只正朝对方爬，也是擦肩而过，
 * 不会叠成一只。自转是绕 spinX/spinY 画的圆，圆心要跟着一起搬，否则下一帧又被拉回去。
 */
private fun separateFlock(flock: List<PetState>) {
    for (a in flock.indices) for (b in a + 1 until flock.size) {
        val f = flock[a]
        val g = flock[b]
        var dx = g.x - f.x
        var dy = g.y - f.y
        var d = hypot(dx, dy)
        val minD = (f.px + g.px) * 0.55f + 10f
        if (d >= minD) continue
        if (d < 0.01f) {
            val th = Math.random().toFloat() * 2f * PI.toFloat()
            dx = cos(th); dy = sin(th); d = 1f
        }
        val push = (minD - d) * 0.5f + 0.5f
        val ux = dx / d
        val uy = dy / d
        f.x -= ux * push; f.y -= uy * push
        g.x += ux * push; g.y += uy * push
        if (f.state == S_SPIN) { f.spinX -= ux * push; f.spinY -= uy * push }
        if (g.state == S_SPIN) { g.spinX += ux * push; g.spinY += uy * push }
    }
    for (st in flock) {
        val pad = maxOf(30f, st.px * 0.58f)
        st.x = st.x.coerceIn(pad, st.w - pad)
        st.y = st.y.coerceIn(pad, st.h - pad)
    }
}

private data class PetSnapshot(
    val x: Float, val y: Float, val px: Float,
    val state: Int, val heading: Float, val spin: Float,
    val gait: Float, val twitch: Float,
)

private data class PetFrame(val frame: Long, val flies: List<PetSnapshot>)

/**
 * 一只果蝇，一份状态，所有画面共用。主屏、四种屏保、个性化卡片、手机页缩略图都只是把这里的
 * 快照按各自尺寸画出来；以前每个 PetOverlay 自己 remember 一份，屏保和卡片根本不可能同步。
 *
 * 精灵帧全部由 tools/psd_bake.py 从分层 PSD 烘出来（头和六条腿各自独立旋转、右半边逐像素镜像），
 * 运行时只有「仰面自转」这一个旋转是实时的。实时旋转+缩放的亚像素插值会让左右翅落在不同像素
 * 网格上、越折越歪（the owner, 2026-11-18），所以正面姿势一律用预烘帧。
 */
/** 一只果蝇这一帧要怎么画：哪帧精灵、画在哪、多大、转多少度。两条渲染路径共用的中间产物。 */
private data class FlyCmd(
    val sprite: ImageBitmap,
    val x: Float, val y: Float,
    val angle: Float, val scale: Float,
    val jitter: Float, val smooth: Boolean,
    /** 落影图（同一帧的模糊轮廓），和 sprite 同一套坐标。 */
    val shadow: ImageBitmap?,
)

object PetWorld {
    private val flock = ArrayList<PetState>().also { it.add(PetState()) }
    private var frame = 0L
    private var ticking = false
    private val scope = MainScope()
    private lateinit var frames: Array<ImageBitmap>

    /** 落影图，和 [frames] 同下标、同尺寸（tools/fly_shadow.py 烘的模糊轮廓）。 */
    private lateinit var shadows: Array<ImageBitmap>

    /**
     * Compose 靠读这个 State 触发重绘。原来 frame 是 PetOverlay 内部的 mutableStateOf，Canvas 的
     * draw lambda 读它就会重组；抽成单例后单靠普通 var 不会重组、果蝇会定住，所以这里保留一支
     * 可被 Compose 观察的 tick。手机页/卡片在非 Compose 线程读它也没有副作用（只是取个值）。
     */
    val tick = mutableStateOf(0L)

    @Synchronized
    fun init(res: Resources) {
        if (::frames.isInitialized) return
        val ids = intArrayOf(
            R.drawable.fly_frame_00, R.drawable.fly_frame_01, R.drawable.fly_frame_02,
            R.drawable.fly_frame_03, R.drawable.fly_frame_04, R.drawable.fly_frame_05,
            R.drawable.fly_frame_06, R.drawable.fly_frame_07, R.drawable.fly_frame_08,
            R.drawable.fly_frame_09, R.drawable.fly_frame_10, R.drawable.fly_frame_11,
            R.drawable.fly_frame_12, R.drawable.fly_frame_13, R.drawable.fly_frame_back,
            R.drawable.fly_frame_groom0, R.drawable.fly_frame_groom1,
        )
        val shIds = intArrayOf(
            R.drawable.fly_shadow_00, R.drawable.fly_shadow_01, R.drawable.fly_shadow_02,
            R.drawable.fly_shadow_03, R.drawable.fly_shadow_04, R.drawable.fly_shadow_05,
            R.drawable.fly_shadow_06, R.drawable.fly_shadow_07, R.drawable.fly_shadow_08,
            R.drawable.fly_shadow_09, R.drawable.fly_shadow_10, R.drawable.fly_shadow_11,
            R.drawable.fly_shadow_12, R.drawable.fly_shadow_13, R.drawable.fly_shadow_back,
            R.drawable.fly_shadow_groom0, R.drawable.fly_shadow_groom1,
        )
        frames = ids.map { BitmapFactory.decodeResource(res, it).asImageBitmap() }.toTypedArray()
        shadows = shIds.map { BitmapFactory.decodeResource(res, it).asImageBitmap() }.toTypedArray()
    }

    /** 启动唯一的一支 33ms 心跳；谁先画谁启动，之后主屏/屏保/卡片都只读这一份状态。 */
    fun start(res: Resources) {
        init(res)
        if (ticking) return
        ticking = true
        scope.launch {
            while (true) {
                tick()
                delay(33)
            }
        }
    }

    @Synchronized
    private fun tick() {
        val on = AerialSettings.petMode != PetMode.Off
        val born = AerialSettings.petBornAt
        val ageSec = if (born <= 0L) 0f else (System.currentTimeMillis() - born).coerceAtLeast(0L) / 1000f
        val want = PetGrowth.count(ageSec)
        while (flock.size < want) flock.add(PetState())
        while (flock.size > want) flock.removeAt(flock.size - 1)
        if (on) {
            for (i in flock.indices) {
                val st = flock[i]
                st.px = PetGrowth.pxOf(i, ageSec)
                // 新成员出生位置：原来是在 Canvas 里按 size 铺开的，抽成单例后没有 size 可
                // 读，就用虚拟桌面尺寸铺开。少了这步 x 一直是 -1，果蝇会落在屏幕外看不见。
                if (st.x < 0f) {
                    st.x = VIRTUAL_W * (0.15f + Math.random().toFloat() * 0.7f)
                    st.y = VIRTUAL_H * (0.2f + Math.random().toFloat() * 0.6f)
                }
                step(st, 0.033f)
            }
            if (flock.size > 1) separateFlock(flock)
        }
        frame++
        tick.value = frame
    }

    @Synchronized
    private fun snapshot(): PetFrame = PetFrame(
        frame,
        flock.map {
            PetSnapshot(it.x, it.y, it.px, it.state, it.heading, it.spin, it.gait, it.twitch)
        },
    )

    /**
     * 把当前快照投影到 w×h 的窗口：一份状态，主屏全屏、屏保、个性化卡片、手机缩略图各按自己的
     * 尺寸画。虚拟桌面 1920×1080 等比缩放并居中；小窗口给体型一点补偿，免得果蝇只剩几像素。
     */
    @Synchronized
    private fun commands(w: Float, h: Float): List<FlyCmd> {
        if (!::frames.isInitialized || AerialSettings.petMode == PetMode.Off || w <= 0f || h <= 0f) return emptyList()
        val snap = snapshot()
        val k = min(w / VIRTUAL_W, h / VIRTUAL_H)
        val ox = (w - VIRTUAL_W * k) / 2f
        val oy = (h - VIRTUAL_H * k) / 2f
        val boost = if (w < 800f) 2.2f else 1f
        val burst = (snap.frame % 242L).toInt()
        val out = ArrayList<FlyCmd>(snap.flies.size)
        for (st in snap.flies) {
            val onBack = st.state == S_BACK || st.state == S_SPIN
            val idx = when {
                onBack -> BACK_FRAME
                st.state == S_FLY -> if (snap.frame % 2L == 0L) 3 else 13
                // 搓腿：每 3 帧（约 1/10 秒）换一次相位，一秒搓五下——真苍蝇比这还快，
                // 再快在电视上就只剩抖动了。
                st.state == S_GROOM -> if ((snap.frame / 3L) % 2L == 0L) GROOM_0 else GROOM_1
                // 理翅：每 8 帧换一张，四张一轮约 1.1 秒，慢悠悠地收放。
                st.state == S_WING -> WING_FRAMES[((snap.frame / 8L) % 4L).toInt()]
                burst < 8 -> BURST_FRAMES[(burst * 6 / 8).coerceIn(0, 5)]
                st.state == S_CRAWL -> WALK_FIRST + ((st.gait * WALK_COUNT).toInt() % WALK_COUNT)
                else -> IDLE_FRAMES[(snap.frame / 13L % 4L).toInt()]
            }
            val sprite = min(st.px * k * boost, h * 0.22f)
            val jitter = if (st.state == S_BACK && (st.twitch % 0.55f) < 0.16f) 1f else 0f
            out.add(
                FlyCmd(
                    sprite = frames[idx],
                    x = ox + st.x * k, y = oy + st.y * k,
                    angle = if (onBack) st.spin else st.heading * 180f / PI.toFloat() + 90f,
                    scale = sprite / 64f,
                    jitter = jitter,
                    // 放大用最近邻（像素格子清楚），缩小用双线性（不把腿抽断）。
                    smooth = sprite <= 64f,
                    // 影子只跟体型走，不跟机头方向走（它在桌面上，不在果蝇身上）。
                    shadow = shadows.getOrNull(idx),
                ),
            )
        }
        return out
    }

    /** Compose 渲染路径：主屏和四种屏保各画一层，读的是同一份 commands。 */
    @Composable
    internal fun Overlay(modifier: Modifier = Modifier) {
        val res = LocalContext.current.resources
        LaunchedEffect(Unit) { start(res) }
        Canvas(modifier) {
            // 订阅单例心跳：这一行让 tick 更新时本 Canvas 重组重绘（果蝇才会动）。
            tick.value
            val cmds = commands(size.width, size.height)
            // 先把三只的影子全画完，再画果蝇。分开两轮而不是一只一只连着画：separateFlock 只
            // 保证身体不重叠，影子的柔边还是会碰到一起，一只一只画就会出现「前一只的影子压在
            // 后一只身上」。
            for (c in cmds) {
                val sh = c.shadow ?: continue
                withTransform({
                    translate(c.x, c.y + c.scale * 64f * SHADOW_LIFT)
                    rotate(c.angle, pivot = Offset.Zero)
                    scale(c.scale, c.scale, pivot = Offset.Zero)
                }) {
                    drawImage(
                        sh,
                        dstOffset = IntOffset(-32, -32),
                        dstSize = IntSize(64, 64),
                        // 影子永远是渐变的，不能走最近邻，否则会出来一圈方块边。
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            for (c in cmds) {
                withTransform({
                    translate(c.x, c.y)
                    // pivot 必须显式给原点：默认支点是画布中心（size/2），果蝇会被转到半个
                    // 屏幕之外——180px 的大果蝇整只飞出画面，只有幼体那几像素还碰巧留在里面。
                    rotate(c.angle, pivot = Offset.Zero)
                    scale(c.scale, c.scale, pivot = Offset.Zero)
                }) {
                    drawImage(
                        c.sprite,
                        dstOffset = IntOffset((-32f + c.jitter).toInt(), -32),
                        dstSize = IntSize(64, 64),
                        filterQuality = if (c.smooth) FilterQuality.Low else FilterQuality.None,
                    )
                }
            }
        }
    }

    /**
     * 非 Compose 渲染路径：手机页缩略图与个性化卡片。往一张 [Bitmap] 上合成当前这一帧。
     * 调用方必须传自己的副本——[Wallpapers.thumbnail] 返回的是缓存位图，直接画会污染卡片。
     */
    @Synchronized
    fun drawInto(bmp: Bitmap) {
        val cmds = commands(bmp.width.toFloat(), bmp.height.toFloat())
        if (cmds.isEmpty()) return
        val canvas = android.graphics.Canvas(bmp)
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
        // 两轮，理由同 Compose 那条路径：影子全部先落，果蝇再压上去。
        for (c in cmds) {
            val sh = c.shadow ?: continue
            paint.isFilterBitmap = true
            canvas.save()
            canvas.translate(c.x, c.y + c.scale * 64f * SHADOW_LIFT)
            canvas.rotate(c.angle)
            canvas.scale(c.scale, c.scale)
            canvas.drawBitmap(sh.asAndroidBitmap(), -32f, -32f, paint)
            canvas.restore()
        }
        for (c in cmds) {
            canvas.save()
            canvas.translate(c.x, c.y)
            canvas.rotate(c.angle)
            canvas.scale(c.scale, c.scale)
            paint.isFilterBitmap = c.smooth
            canvas.drawBitmap(c.sprite.asAndroidBitmap(), -32f + c.jitter, -32f, paint)
            canvas.restore()
        }
    }
}

/** 全屏果蝇层。Canvas 只画不拦截输入，所以永远不抢遥控器焦点。 */
@Composable
fun PetOverlay(modifier: Modifier = Modifier) = PetWorld.Overlay(modifier)
