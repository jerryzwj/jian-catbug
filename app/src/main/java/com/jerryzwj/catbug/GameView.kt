package com.jerryzwj.catbug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class Species { BEETLE, ROACH, SPIDER, MOTH, CENTIPEDE }
enum class Gait { STRAIGHT, ZIGZAG, STAGGER }

class Bug(
    var x: Float,
    var y: Float,
    var dir: Int,
    var speed: Float,
    var size: Float,
    var species: Species,
    var gait: Gait,
    var value: Int
) {
    var phase: Float = Random.nextFloat() * 6.28f
    var squished: Boolean = false
    var squishT: Float = 0f
    var dead: Boolean = false
    var fat: Boolean = false
}

private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var maxLife: Float, var r: Float)
private class Ring(var x: Float, var y: Float, var t: Float, var maxT: Float, var r0: Float, var r1: Float)
private class FloatText(var x: Float, var y: Float, var t: Float, var text: String)

/**
 * 纯 Canvas 绘制的白底抓虫画面：
 * 虫子从左右两端爬出，任意手指（猫爪）按到附近就算抓住。
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var prefs: Prefs? = null
    var sfx: Sfx? = null
    var onPauseRequest: (() -> Unit)? = null

    var running: Boolean = true
        set(value) {
            field = value
            lastFrame = 0L
            if (!value) threeFingerStart = 0L
            invalidate()
        }

    var score: Int = 0
        private set

    private val dp: Float = resources.displayMetrics.density
    private val bugs = ArrayList<Bug>()
    private val particles = ArrayList<Particle>()
    private val rings = ArrayList<Ring>()
    private val texts = ArrayList<FloatText>()

    private var lastFrame = 0L
    private var spawnTimer = 0.0f
    private var playTime = 0.0f
    private var spawning = 0
    private var threeFingerStart = 0L

    private val bugPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val splatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val bgPaint = Paint()
    private val tmpRect = RectF()
    private val path = Path()

    private val bugColor = 0xFF1A1A1A.toInt()

    init {
        setBackgroundColor(Color.WHITE)
    }

    fun resetGame() {
        bugs.clear()
        particles.clear()
        rings.clear()
        texts.clear()
        score = 0
        playTime = 0f
        spawnTimer = 0f
        spawning = 0
        spawn()
        spawnTimer = 0.55f
        lastFrame = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bugs.isEmpty() && w > 0 && h > 0) resetGame()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        if (running) update(dt)

        // 背景
        bgPaint.color = if (prefs?.warmBg == true) 0xFFF7F1E4.toInt() else Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 涟漪
        for (r in rings) {
            val k = (r.t / r.maxT).coerceIn(0f, 1f)
            val radius = r.r0 + (r.r1 - r.r0) * k
            splatPaint.style = Paint.Style.STROKE
            splatPaint.color = 0xFF1A1A1A.toInt()
            splatPaint.alpha = ((1f - k) * 90f).toInt()
            splatPaint.strokeWidth = dp * 3f * (1f - k * 0.6f)
            canvas.drawCircle(r.x, r.y, radius, splatPaint)
        }

        // 粒子
        for (p in particles) {
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            splatPaint.style = Paint.Style.FILL
            splatPaint.color = bugColor
            splatPaint.alpha = (k * 200f).toInt()
            canvas.drawCircle(p.x, p.y, p.r * (0.4f + k * 0.6f), splatPaint)
        }

        // 虫子
        for (b in bugs) drawBug(canvas, b)

        // 分数飘字
        textPaint.color = Color.BLACK
        textPaint.textSize = dp * 20f
        for (t in texts) {
            val k = (t.t / 0.85f).coerceIn(0f, 1f)
            textPaint.alpha = ((1f - k) * 150f).toInt()
            canvas.drawText(t.text, t.x, t.y - dp * 54f * k, textPaint)
        }

        // 角落里的分数（很淡，不抢虫子视线）
        if (prefs?.showScore == true) {
            textPaint.textSize = dp * 34f
            textPaint.alpha = 60
            canvas.drawText(score.toString(), dp * 24f, dp * 56f, textPaint)
        }

        if (running) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- 更新

    private fun update(dt: Float) {
        playTime += dt

        // 三指长按呼出设置
        if (threeFingerStart > 0L && SystemClock.uptimeMillis() - threeFingerStart > 1500L) {
            threeFingerStart = 0L
            onPauseRequest?.invoke()
            return
        }

        val maxBugs = prefs?.maxBugs ?: 2
        spawnTimer -= dt
        val alive = bugs.count { !it.squished && !it.dead }
        if (spawnTimer <= 0f && alive < maxBugs) {
            spawn()
            val gap = (0.75f + Random.nextFloat() * 0.9f) / difficulty()
            spawnTimer = gap.coerceAtLeast(0.35f)
        }

        for (b in bugs) updateBug(b, dt)
        bugs.removeAll { it.dead }

        val pIt = particles.iterator()
        while (pIt.hasNext()) {
            val p = pIt.next()
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.90f
            p.vy *= 0.90f
            if (p.life <= 0f) pIt.remove()
        }
        val rIt = rings.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            r.t += dt
            if (r.t >= r.maxT) rIt.remove()
        }
        val tIt = texts.iterator()
        while (tIt.hasNext()) {
            val t = tIt.next()
            t.t += dt
            if (t.t >= 0.85f) tIt.remove()
        }
    }

    private fun difficulty(): Float = 1f + (playTime / 200f).coerceAtMost(0.45f)

    private fun updateBug(b: Bug, dt: Float) {
        if (b.squished) {
            b.squishT += dt
            if (b.squishT > 0.58f) b.dead = true
            return
        }
        b.phase += dt

        val amp = when (b.species) {
            Species.MOTH -> 1.1f
            Species.SPIDER -> 0.55f
            Species.CENTIPEDE -> 0.25f
            else -> 0.35f
        }

        var mul = 1f
        when (b.gait) {
            Gait.STRAIGHT -> b.y += sin(b.phase * 1.7f) * b.size * amp * dt * 2.2f
            Gait.ZIGZAG -> b.y += sin(b.phase * 2.7f) * b.size * amp * dt * 6.5f
            Gait.STAGGER -> {
                mul = if (sin(b.phase * 1.55f) > 0f) 1f else 0.15f
                b.y += sin(b.phase * 3.2f) * b.size * 0.2f * dt * 3f
            }
        }

        val v = b.speed * mul * (1f + 0.16f * sin(b.phase * 3.3f))
        b.x += b.dir * v * dt

        val top = dp * 58f
        val bottom = height - dp * 58f
        if (b.y < top) { b.y = top; b.phase += 0.4f }
        if (b.y > bottom) { b.y = bottom; b.phase += 0.4f }

        // 爬到边缘不掉头消失，转身继续在屏幕里爬
        val edge = b.size * (if (b.species == Species.CENTIPEDE) 0.80f else 0.55f) + dp * 4f
        if (b.dir > 0 && b.x > width - edge) {
            b.x = width - edge
            turnAround(b)
        } else if (b.dir < 0 && b.x < edge) {
            b.x = edge
            turnAround(b)
        }
    }

    private fun turnAround(b: Bug) {
        b.dir = -b.dir
        b.phase += Random.nextFloat() * 1.2f
        // 掉头时换个节奏，避免在同一条水平线来回蹭
        if (Random.nextFloat() < 0.45f) {
            b.gait = when {
                b.species == Species.MOTH -> Gait.ZIGZAG
                b.species == Species.CENTIPEDE -> Gait.STRAIGHT
                else -> listOf(Gait.STRAIGHT, Gait.ZIGZAG, Gait.STAGGER).random()
            }
        }
        val span = (height - dp * 130f).coerceAtLeast(1f)
        b.y = (b.y + (Random.nextFloat() - 0.5f) * span * 0.35f)
            .coerceIn(dp * 58f, height - dp * 58f)
    }

    // ---------------------------------------------------------------- 生成

    private fun spawn() {
        if (width <= 0 || height <= 0) return
        spawning++

        val species = pickSpecies()
        val sizeDp: Float
        val speedDp: Float
        when (species) {
            Species.BEETLE -> { sizeDp = 44f; speedDp = 66f }
            Species.ROACH -> { sizeDp = 50f; speedDp = 100f }
            Species.SPIDER -> { sizeDp = 48f; speedDp = 82f }
            Species.MOTH -> { sizeDp = 46f; speedDp = 126f }
            Species.CENTIPEDE -> { sizeDp = 72f; speedDp = 48f }
        }

        var size = dp * sizeDp * (0.88f + Random.nextFloat() * 0.28f)
        var speed = dp * speedDp * (prefs?.speedMul() ?: 1f) * difficulty() *
            (0.85f + Random.nextFloat() * 0.35f)

        val isFat = spawning % 14 == 0
        if (isFat) {
            size *= 1.4f
            speed *= 0.72f
        }

        val dir = if (Random.nextBoolean()) 1 else -1
        val x = if (dir > 0) -size else width + size
        val margin = dp * 70f + size * 0.5f
        val y = margin + Random.nextFloat() * (height - margin * 2f).coerceAtLeast(1f)

        val gait = when {
            species == Species.MOTH -> Gait.ZIGZAG
            species == Species.CENTIPEDE -> Gait.STRAIGHT
            species == Species.SPIDER -> if (Random.nextFloat() < 0.5f) Gait.ZIGZAG else Gait.STRAIGHT
            Random.nextFloat() < 0.42f -> Gait.ZIGZAG
            Random.nextFloat() < 0.30f -> Gait.STAGGER
            else -> Gait.STRAIGHT
        }

        val bug = Bug(x, y, dir, speed, size, species, gait, if (isFat) 3 else 1)
        bug.fat = isFat
        bugs.add(bug)
    }

    private fun pickSpecies(): Species {
        val r = Random.nextInt(100)
        return when {
            r < 26 -> Species.BEETLE
            r < 52 -> Species.ROACH
            r < 74 -> Species.SPIDER
            r < 90 -> Species.MOTH
            else -> Species.CENTIPEDE
        }
    }

    // ---------------------------------------------------------------- 绘制

    private fun drawBug(canvas: Canvas, b: Bug) {
        canvas.save()
        canvas.translate(b.x, b.y)

        if (!b.squished) drawBugShadow(canvas, b.size)

        if (b.dir < 0) canvas.rotate(180f)

        if (b.squished) {
            val k = (b.squishT / 0.58f).coerceIn(0f, 1f)
            val alpha = if (k < 0.55f) 255 else (255 * (1f - (k - 0.55f) / 0.45f)).toInt().coerceIn(0, 255)

            // 压扁后散开的体液
            splatPaint.style = Paint.Style.FILL
            splatPaint.color = splatColor(b.species)
            splatPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
            canvas.drawOval(
                -b.size * (0.55f + k * 0.25f), -b.size * 0.10f,
                b.size * (0.55f + k * 0.25f), b.size * 0.12f, splatPaint
            )
            canvas.save()
            canvas.scale(1f + k * 0.5f, (1f - k * 0.75f).coerceAtLeast(0.1f))
            drawSpecies(canvas, b.size, b.species, b.phase, alpha, b.fat)
            canvas.restore()
        } else {
            val wobble = when (b.gait) {
                Gait.ZIGZAG -> sin(b.phase * 7f) * 6f
                Gait.STAGGER -> sin(b.phase * 3f) * 3f
                else -> sin(b.phase * 4f) * 1.5f
            }
            canvas.rotate(wobble)
            drawSpecies(canvas, b.size, b.species, b.phase, 255, b.fat)
        }
        canvas.restore()
    }

    private fun drawBugShadow(canvas: Canvas, s: Float) {
        shadowPaint.color = Color.BLACK
        shadowPaint.alpha = 22
        canvas.drawOval(-s * 0.44f, s * 0.20f, s * 0.44f, s * 0.34f, shadowPaint)
    }

    private fun splatColor(species: Species): Int = when (species) {
        Species.BEETLE -> 0xFF3E5B2C.toInt()
        Species.ROACH -> 0xFF8A5426.toInt()
        Species.SPIDER -> 0xFF5A4A34.toInt()
        Species.MOTH -> 0xFFB08A5A.toInt()
        Species.CENTIPEDE -> 0xFFB0552E.toInt()
    }

    private fun fill(canvas: Canvas, color: Int, alpha: Int): Paint {
        val p = bugPaint
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = alpha
        p.shader = null
        return p
    }

    private fun stroke(color: Int, alpha: Int, width: Float): Paint {
        val p = bugPaint
        p.style = Paint.Style.STROKE
        p.color = color
        p.alpha = alpha
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.ROUND
        p.shader = null
        return p
    }

    private fun drawSpecies(canvas: Canvas, s: Float, species: Species, phase: Float, alpha: Int, fat: Boolean) {
        when (species) {
            Species.BEETLE -> drawBeetle(canvas, s, phase, alpha, fat)
            Species.ROACH -> drawRoach(canvas, s, phase, alpha, fat)
            Species.SPIDER -> drawSpider(canvas, s, phase, alpha, fat)
            Species.MOTH -> drawMoth(canvas, s, phase, alpha, fat)
            Species.CENTIPEDE -> drawCentipede(canvas, s, phase, alpha, fat)
        }
    }

    /** 金龟子：墨绿鞘翅 + 金属高光 */
    private fun drawBeetle(c: Canvas, s: Float, phase: Float, a: Int, fat: Boolean) {
        val legW = (s * 0.055f).coerceAtLeast(dp)
        val legs = stroke(0xFF2A2016.toInt(), a, legW)
        for (i in -1..1) {
            val lx = i * s * 0.18f
            for (side in intArrayOf(-1, 1)) {
                val wig = sin(phase * 8.5f + i * 1.1f + (if (side > 0) 0f else 1.7f)) * s * 0.10f
                val kx = lx - s * 0.10f
                val ky = side * s * 0.46f + wig
                val fx = kx - s * 0.12f
                val fy = side * s * 0.60f + wig * 0.4f
                c.drawLine(lx, side * s * 0.26f, kx, ky, legs)
                c.drawLine(kx, ky, fx, fy, legs)
            }
        }

        // 鞘翅
        c.drawOval(rect(-s * 0.40f, -s * 0.34f, s * 0.38f, s * 0.34f), fill(c, 0xFF34502A.toInt(), a))
        c.drawOval(rect(-s * 0.34f, -s * 0.28f, s * 0.28f, s * 0.30f), fill(c, 0xFF446737.toInt(), a))
        // 金属高光
        c.drawOval(rect(-s * 0.24f, -s * 0.24f, s * 0.06f, s * 0.04f), fill(c, 0xFF7FA85C.toInt(), (a * 0.55f).toInt()))
        // 翅中缝
        c.drawLine(s * 0.10f, 0f, -s * 0.40f, 0f, stroke(0xFF1E2A18.toInt(), a, (s * 0.022f).coerceAtLeast(dp * 0.7f)))
        // 前胸背板
        c.drawOval(rect(s * 0.10f, -s * 0.30f, s * 0.40f, s * 0.30f), fill(c, 0xFF273C1F.toInt(), a))
        c.drawOval(rect(s * 0.14f, -s * 0.22f, s * 0.32f, s * 0.02f), fill(c, 0xFF4C6E3A.toInt(), (a * 0.6f).toInt()))
        // 头
        c.drawCircle(s * 0.44f, 0f, s * 0.19f, fill(c, 0xFF1B1B1B.toInt(), a))
        c.drawCircle(s * 0.40f, -s * 0.07f, s * 0.06f, fill(c, 0xFF4A4A4A.toInt(), (a * 0.7f).toInt()))
        // 触角（末端带小球）
        val ant = stroke(0xFF231A10.toInt(), a, (s * 0.035f).coerceAtLeast(dp * 0.9f))
        for (side in intArrayOf(-1, 1)) {
            c.drawLine(s * 0.52f, side * s * 0.12f, s * 0.72f, side * s * 0.28f, ant)
            c.drawCircle(s * 0.76f, side * s * 0.31f, s * 0.045f, fill(c, 0xFF231A10.toInt(), a))
        }
        if (fat) c.drawOval(rect(-s * 0.38f, -s * 0.30f, s * 0.30f, s * 0.28f), fill(c, 0xFFFFCE6B.toInt(), (a * 0.18f).toInt()))
    }

    /** 蟑螂：油亮棕红，长触角 */
    private fun drawRoach(c: Canvas, s: Float, phase: Float, a: Int, fat: Boolean) {
        val legW = (s * 0.045f).coerceAtLeast(dp)
        val legs = stroke(0xFF3A2412.toInt(), a, legW)
        for (i in -1..1) {
            val lx = i * s * 0.22f
            for (side in intArrayOf(-1, 1)) {
                val wig = sin(phase * 12f + i * 1.4f + (if (side > 0) 0f else 1.9f)) * s * 0.09f
                val kx = lx - s * 0.14f
                val ky = side * s * 0.40f + wig
                val fx = kx - s * 0.16f
                val fy = side * s * 0.56f + wig * 0.4f
                c.drawLine(lx, side * s * 0.18f, kx, ky, legs)
                c.drawLine(kx, ky, fx, fy, legs)
            }
        }

        // 触角
        val ant = stroke(0xFF3A2412.toInt(), a, (s * 0.035f).coerceAtLeast(dp * 0.8f))
        for (side in intArrayOf(-1, 1)) {
            val sway = sin(phase * 4.5f + (if (side > 0) 0f else 1.2f)) * s * 0.10f
            c.drawLine(s * 0.52f, side * s * 0.08f, s * 0.74f, side * (s * 0.26f) + sway, ant)
            c.drawLine(s * 0.74f, side * (s * 0.26f) + sway, s * 0.94f, side * (s * 0.14f) + sway * 1.4f, ant)
        }

        // 腹部（鞘翅）
        c.drawOval(rect(-s * 0.52f, -s * 0.22f, s * 0.42f, s * 0.22f), fill(c, 0xFF7A4518.toInt(), a))
        c.drawOval(rect(-s * 0.50f, -s * 0.20f, s * 0.40f, s * 0.02f), fill(c, 0xFF9A5C25.toInt(), a))
        c.drawOval(rect(-s * 0.30f, -s * 0.14f, s * 0.16f, s * 0.04f), fill(c, 0xFFC08348.toInt(), (a * 0.6f).toInt()))
        // 翅中缝
        c.drawLine(s * 0.18f, 0f, -s * 0.50f, 0f, stroke(0xFF552F0E.toInt(), a, (s * 0.02f).coerceAtLeast(dp * 0.6f)))
        // 前胸背板
        c.drawOval(rect(s * 0.20f, -s * 0.26f, s * 0.46f, s * 0.26f), fill(c, 0xFF4A2A10.toInt(), a))
        c.drawOval(rect(s * 0.24f, -s * 0.20f, s * 0.40f, s * 0.00f), fill(c, 0xFF7A4A1E.toInt(), (a * 0.5f).toInt()))
        // 头
        c.drawCircle(s * 0.50f, 0f, s * 0.14f, fill(c, 0xFF231204.toInt(), a))
        // 尾须
        val cerci = stroke(0xFF3A2412.toInt(), a, (s * 0.03f).coerceAtLeast(dp * 0.7f))
        c.drawLine(-s * 0.52f, -s * 0.06f, -s * 0.64f, -s * 0.16f, cerci)
        c.drawLine(-s * 0.52f, s * 0.06f, -s * 0.64f, s * 0.16f, cerci)
        if (fat) c.drawOval(rect(-s * 0.46f, -s * 0.18f, s * 0.36f, s * 0.18f), fill(c, 0xFFFFCE6B.toInt(), (a * 0.18f).toInt()))
    }

    /** 蜘蛛：八条分节腿 + 腹部花纹 */
    private fun drawSpider(c: Canvas, s: Float, phase: Float, a: Int, fat: Boolean) {
        val legW = (s * 0.045f).coerceAtLeast(dp)
        val legs = stroke(0xFF4A3521.toInt(), a, legW)
        val joint = fill(c, 0xFF3A2917.toInt(), a)
        for (side in intArrayOf(-1, 1)) {
            for (i in 0..3) {
                val baseAng = Math.toRadians((28.0 + i * 34.0) * side).toFloat()
                val wig = sin(phase * 8.5f + i * 0.9f + (if (side > 0) 0f else 1.4f)) * 0.14f
                val a1 = baseAng + wig
                val kx = s * 0.14f + cos(a1) * s * 0.30f
                val ky = sin(a1) * s * 0.30f
                val fx = kx + cos(a1) * s * 0.34f
                val fy = ky + sin(a1) * s * 0.34f
                c.drawLine(s * 0.14f, 0f, kx, ky, legs)
                c.drawLine(kx, ky, fx, fy, legs)
                c.drawCircle(kx, ky, legW * 0.9f, joint)
            }
        }

        // 腹部
        c.drawOval(rect(-s * 0.46f, -s * 0.32f, s * 0.04f, s * 0.32f), fill(c, 0xFF2E2418.toInt(), a))
        c.drawOval(rect(-s * 0.42f, -s * 0.27f, s * 0.00f, s * 0.27f), fill(c, 0xFF5A4527.toInt(), a))
        c.drawOval(rect(-s * 0.34f, -s * 0.14f, s * 0.02f, s * 0.02f), fill(c, 0xFF7A6034.toInt(), (a * 0.55f).toInt()))
        // 腹部斑纹（浅色人字纹）
        val mark = stroke(0xFFC9B07A.toInt(), (a * 0.9f).toInt(), (s * 0.05f).coerceAtLeast(dp * 1.1f))
        for (i in 0..2) {
            val mx = -s * 0.34f + i * s * 0.11f
            c.drawLine(mx - s * 0.06f, -s * 0.18f, mx + s * 0.02f, 0f, mark)
            c.drawLine(mx - s * 0.06f, s * 0.18f, mx + s * 0.02f, 0f, mark)
        }

        // 头胸部
        c.drawCircle(s * 0.18f, 0f, s * 0.20f, fill(c, 0xFF3B2C1C.toInt(), a))
        c.drawCircle(s * 0.14f, -s * 0.06f, s * 0.08f, fill(c, 0xFF5A462E.toInt(), (a * 0.7f).toInt()))
        // 眼睛
        val eye = fill(c, 0xFFE6DDC8.toInt(), a)
        c.drawCircle(s * 0.30f, -s * 0.06f, s * 0.035f, eye)
        c.drawCircle(s * 0.30f, s * 0.06f, s * 0.035f, eye)
        if (fat) c.drawOval(rect(-s * 0.40f, -s * 0.26f, s * 0.00f, s * 0.24f), fill(c, 0xFFFFCE6B.toInt(), (a * 0.18f).toInt()))
    }

    /** 飞蛾：棕黄四翅 + 眼斑 + 绒毛身体 + 持续扇动 */
    private fun drawMoth(c: Canvas, s: Float, phase: Float, a: Int, fat: Boolean) {
        val spread = 0.62f + 0.38f * abs(sin(phase * 13f))
        val flap = sin(phase * 13f) * 7f
        val edgeW = (s * 0.022f).coerceAtLeast(dp * 0.6f)

        for (side in intArrayOf(-1, 1)) {
            c.save()
            c.scale(1f, side.toFloat())
            c.rotate(-flap)

            // 后翅（略浅、圆润）
            path.reset()
            path.moveTo(s * 0.12f, s * 0.02f)
            path.quadTo(-s * 0.02f, s * 0.44f * spread, -s * 0.34f, s * 0.42f * spread)
            path.quadTo(-s * 0.52f, s * 0.34f * spread, -s * 0.46f, s * 0.06f)
            path.quadTo(-s * 0.34f, -s * 0.04f, -s * 0.08f, s * 0.00f)
            path.close()
            c.drawPath(path, fill(c, 0xFF9C6C33.toInt(), a))
            c.drawOval(rect(-s * 0.36f, s * 0.04f, -s * 0.14f, s * 0.26f * spread), fill(c, 0xFFB98B49.toInt(), (a * 0.6f).toInt()))
            c.drawPath(path, stroke(0xFF57391A.toInt(), (a * 0.85f).toInt(), edgeW))

            // 前翅（主翅 + 翅尖暗部 + 横向深带）
            path.reset()
            path.moveTo(s * 0.16f, s * 0.05f)
            path.quadTo(s * 0.04f, s * 0.50f * spread, -s * 0.22f, s * 0.56f * spread)
            path.quadTo(-s * 0.46f, s * 0.56f * spread, -s * 0.46f, s * 0.26f * spread)
            path.quadTo(-s * 0.38f, s * 0.04f, -s * 0.06f, s * 0.01f)
            path.close()
            c.drawPath(path, fill(c, 0xFFB9823B.toInt(), a))
            c.drawOval(rect(-s * 0.46f, s * 0.18f * spread, -s * 0.24f, s * 0.50f * spread), fill(c, 0xFF8E5A22.toInt(), (a * 0.5f).toInt()))
            val band = stroke(0xFF6B4517.toInt(), (a * 0.55f).toInt(), (s * 0.06f).coerceAtLeast(dp))
            c.drawLine(-s * 0.42f, s * 0.30f * spread, -s * 0.10f, s * 0.16f, band)
            // 眼斑
            c.drawCircle(-s * 0.30f, s * 0.30f * spread, (s * 0.075f).coerceAtLeast(dp * 2f), fill(c, 0xFFEBD8B2.toInt(), a))
            c.drawCircle(-s * 0.30f, s * 0.30f * spread, (s * 0.038f).coerceAtLeast(dp * 1f), fill(c, 0xFF4A3016.toInt(), a))
            c.drawPath(path, stroke(0xFF57391A.toInt(), (a * 0.85f).toInt(), edgeW))
            // 翅脉
            val vein = stroke(0xFF7A5220.toInt(), (a * 0.45f).toInt(), dp * 0.9f)
            c.drawLine(s * 0.10f, s * 0.04f, -s * 0.26f, s * 0.44f * spread, vein)
            c.drawLine(s * 0.03f, s * 0.04f, -s * 0.30f, s * 0.20f, vein)

            c.restore()
        }

        // 腹部（绒毛 + 浅色横带）
        c.drawOval(rect(-s * 0.34f, -s * 0.22f, s * 0.06f, s * 0.22f), fill(c, 0xFF7B5C38.toInt(), a))
        c.drawOval(rect(-s * 0.30f, -s * 0.16f, s * 0.02f, s * 0.04f), fill(c, 0xFF9C7A4E.toInt(), (a * 0.8f).toInt()))
        for (i in 0..2) {
            val bx = -s * 0.26f + i * s * 0.10f
            c.drawLine(bx, -s * 0.19f, bx, s * 0.19f, stroke(0xFFC9AE82.toInt(), (a * 0.6f).toInt(), (s * 0.02f).coerceAtLeast(dp * 0.6f)))
        }
        // 胸
        c.drawOval(rect(-s * 0.04f, -s * 0.20f, s * 0.14f, s * 0.20f), fill(c, 0xFF6A4A2C.toInt(), a))
        // 头 + 眼
        c.drawCircle(s * 0.20f, 0f, s * 0.13f, fill(c, 0xFF4A3018.toInt(), a))
        c.drawCircle(s * 0.24f, -s * 0.06f, s * 0.045f, fill(c, 0xFF1E1206.toInt(), a))
        c.drawCircle(s * 0.24f, s * 0.06f, s * 0.045f, fill(c, 0xFF1E1206.toInt(), a))
        // 羽状触角
        for (side in intArrayOf(-1, 1)) {
            val ant = stroke(0xFF5A4A2E.toInt(), a, (s * 0.028f).coerceAtLeast(dp * 0.7f))
            c.drawLine(s * 0.26f, side * s * 0.08f, s * 0.46f, side * s * 0.22f, ant)
            c.drawLine(s * 0.46f, side * s * 0.22f, s * 0.52f, side * s * 0.36f, ant)
            val hair = stroke(0xFF7A6440.toInt(), (a * 0.6f).toInt(), dp * 0.7f)
            c.drawLine(s * 0.34f, side * s * 0.13f, s * 0.38f, side * s * 0.26f, hair)
            c.drawLine(s * 0.42f, side * s * 0.17f, s * 0.44f, side * s * 0.32f, hair)
        }
        if (fat) c.drawOval(rect(-s * 0.34f, -s * 0.30f, s * 0.24f, s * 0.30f), fill(c, 0xFFFFCE6B.toInt(), (a * 0.14f).toInt()))
    }

    /** 蜈蚣：橙红分节 + 黄色足 + 波浪步态 */
    private fun drawCentipede(c: Canvas, s: Float, phase: Float, a: Int, fat: Boolean) {
        val segs = 11
        val legW = (s * 0.042f).coerceAtLeast(dp * 0.9f)
        val legs = stroke(0xFFA96B18.toInt(), a, legW)
        val legsDark = stroke(0xFF6E4410.toInt(), a, dp * 0.8f)

        for (i in 0..segs) {
            val fx = -s * 0.56f + (i.toFloat() / segs) * s * 1.04f
            val off = sin(phase * 6f + i * 0.85f) * s * 0.08f
            val r = s * 0.135f * (1.06f - abs(i - segs / 2f) / (segs * 1.6f))

            // 两段式短足，向后倾
            val wig = sin(phase * 10f + i * 0.9f) * s * 0.03f
            for (side in intArrayOf(-1, 1)) {
                c.drawLine(fx, off + side * r * 0.6f, fx - s * 0.05f, off + side * (r + s * 0.15f) + wig * side, legs)
                c.drawLine(fx - s * 0.05f, off + side * (r + s * 0.15f) + wig * side, fx - s * 0.13f, off + side * (r + s * 0.26f), legsDark)
            }

            // 体节：外圈深、内圈亮、顶部高光
            c.drawCircle(fx, off, r, fill(c, 0xFF7E3419.toInt(), a))
            c.drawCircle(fx, off - r * 0.14f, r * 0.78f, fill(c, 0xFFC0653C.toInt(), a))
            c.drawCircle(fx, off - r * 0.36f, r * 0.36f, fill(c, 0xFFE0905F.toInt(), (a * 0.55f).toInt()))
            // 节间暗缝
            c.drawLine(fx + r * 0.9f, off - r * 0.8f, fx + r * 0.9f, off + r * 0.8f, stroke(0xFF5E2410.toInt(), (a * 0.5f).toInt(), dp * 0.7f))
        }

        // 头
        val hy = sin(phase * 6f + segs * 0.85f) * s * 0.08f
        c.drawCircle(s * 0.50f, hy, s * 0.17f, fill(c, 0xFF6B2814.toInt(), a))
        c.drawCircle(s * 0.54f, hy - s * 0.05f, s * 0.05f, fill(c, 0xFFA4492A.toInt(), (a * 0.8f).toInt()))
        // 颚须
        val ant = stroke(0xFF5A2410.toInt(), a, (s * 0.03f).coerceAtLeast(dp * 0.7f))
        val sway = sin(phase * 5f) * s * 0.06f
        c.drawLine(s * 0.62f, hy - s * 0.08f, s * 0.80f, hy - s * 0.20f + sway, ant)
        c.drawLine(s * 0.62f, hy + s * 0.08f, s * 0.80f, hy + s * 0.20f + sway, ant)
        if (fat) c.drawCircle(0f, 0f, s * 0.5f, fill(c, 0xFFFFCE6B.toInt(), (a * 0.10f).toInt()))
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float): RectF {
        tmpRect.set(l, t, r, b)
        return tmpRect
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (i in 0 until e.pointerCount) {
                    trySquish(e.getX(i), e.getY(i), e.getPointerId(i))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    trySquish(e.getX(i), e.getY(i), e.getPointerId(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                threeFingerStart = 0L
                return true
            }
        }

        if (e.pointerCount >= 3) {
            if (threeFingerStart == 0L) threeFingerStart = SystemClock.uptimeMillis()
        } else {
            threeFingerStart = 0L
        }
        return true
    }

    private fun trySquish(x: Float, y: Float, pointerId: Int) {
        var best: Bug? = null
        var bestDist = Float.MAX_VALUE
        for (b in bugs) {
            if (b.squished || b.dead) continue
            val radius = b.size * 0.85f + dp * 26f
            val dx = x - b.x
            val dy = y - b.y
            val d = dx * dx + dy * dy
            if (d <= radius * radius && d < bestDist) {
                best = b
                bestDist = d
            }
        }
        if (best != null) {
            hit(best, x, y)
        } else {
            // 空按也给一点反馈，让猫知道"这里按到了"
            if (rings.size < 18) {
                rings.add(Ring(x, y, 0f, 0.45f, dp * 10f, dp * 34f))
            }
        }
    }

    private fun hit(b: Bug, x: Float, y: Float) {
        b.squished = true
        b.squishT = 0f
        score += b.value
        if (prefs?.sound == true) sfx?.squish()
        if (prefs?.haptics == true) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        rings.add(Ring(b.x, b.y, 0f, 0.5f, b.size * 0.4f, b.size * 1.6f))

        val count = if (b.fat) 16 else 10
        for (i in 0 until count) {
            val ang = Random.nextFloat() * 6.2832f
            val sp = dp * (8f + Random.nextFloat() * 26f)
            particles.add(
                Particle(
                    b.x, b.y,
                    cos(ang) * sp, sin(ang) * sp,
                    0.16f + Random.nextFloat() * 0.22f,
                    0.40f,
                    dp * (1.6f + Random.nextFloat() * 2.6f)
                )
            )
        }

        texts.add(FloatText(b.x, b.y, 0f, if (b.value > 1) "+${b.value}" else "+1"))
        invalidate()
    }
}
