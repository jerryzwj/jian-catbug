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
    private val fatColor = 0xFF14351F.toInt()

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
        val edge = b.size * 0.55f + dp * 4f
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
        val p = bugPaint
        p.color = if (b.fat) fatColor else bugColor
        p.alpha = 255

        canvas.save()
        canvas.translate(b.x, b.y)
        if (b.dir < 0) canvas.rotate(180f)

        if (b.squished) {
            val k = (b.squishT / 0.58f).coerceIn(0f, 1f)
            val alpha = when {
                k < 0.55f -> 255
                else -> (255 * (1f - (k - 0.55f) / 0.45f)).toInt().coerceIn(0, 255)
            }
            // 压扁的"体液"痕迹
            splatPaint.style = Paint.Style.FILL
            splatPaint.color = p.color
            splatPaint.alpha = ((alpha * 0.55f).toInt()).coerceIn(0, 255)
            canvas.drawOval(
                -b.size * (0.55f + k * 0.25f), -b.size * 0.10f,
                b.size * (0.55f + k * 0.25f), b.size * 0.12f, splatPaint
            )
            canvas.save()
            canvas.scale(1f + k * 0.5f, (1f - k * 0.75f).coerceAtLeast(0.1f))
            drawSpecies(canvas, b.size, b.species, b.phase, p.color, alpha)
            canvas.restore()
        } else {
            val wobble = when (b.gait) {
                Gait.ZIGZAG -> sin(b.phase * 7f) * 7f
                Gait.STAGGER -> sin(b.phase * 3f) * 3f
                else -> sin(b.phase * 4f) * 1.5f
            }
            canvas.rotate(wobble)
            drawSpecies(canvas, b.size, b.species, b.phase, p.color, 255)
        }
        canvas.restore()
    }

    private fun drawSpecies(canvas: Canvas, s: Float, species: Species, phase: Float, color: Int, alpha: Int) {
        val p = bugPaint
        p.color = color
        p.alpha = alpha
        p.strokeWidth = (s * 0.075f).coerceAtLeast(dp * 1.4f)
        p.strokeCap = Paint.Cap.ROUND

        when (species) {
            Species.BEETLE -> {
                p.style = Paint.Style.FILL
                tmpRect.set(-s * 0.40f, -s * 0.34f, s * 0.40f, s * 0.34f)
                canvas.drawOval(tmpRect, p)
                canvas.drawCircle(s * 0.44f, 0f, s * 0.22f, p)

                p.style = Paint.Style.STROKE
                for (i in -1..1) {
                    val lx = i * s * 0.22f
                    val wig = sin(phase * 9f + i * 1.2f) * s * 0.07f
                    canvas.drawLine(lx, -s * 0.30f, lx - s * 0.10f, -s * 0.52f - wig, p)
                    canvas.drawLine(lx, s * 0.30f, lx - s * 0.10f, s * 0.52f + wig, p)
                }
                canvas.drawLine(s * 0.52f, -s * 0.10f, s * 0.74f, -s * 0.32f, p)
                canvas.drawLine(s * 0.52f, s * 0.10f, s * 0.74f, s * 0.32f, p)
            }

            Species.ROACH -> {
                p.style = Paint.Style.FILL
                tmpRect.set(-s * 0.52f, -s * 0.22f, s * 0.44f, s * 0.22f)
                canvas.drawOval(tmpRect, p)
                canvas.drawCircle(s * 0.50f, 0f, s * 0.15f, p)

                p.style = Paint.Style.STROKE
                for (i in -1..1) {
                    val lx = i * s * 0.22f
                    val wig = sin(phase * 13f + i * 1.5f) * s * 0.08f
                    canvas.drawLine(lx, -s * 0.20f, lx - s * 0.14f, -s * 0.48f - wig, p)
                    canvas.drawLine(lx, s * 0.20f, lx - s * 0.14f, s * 0.48f + wig, p)
                }
                // 长触角
                canvas.drawLine(s * 0.56f, -s * 0.08f, s * 0.78f, -s * 0.30f, p)
                canvas.drawLine(s * 0.78f, -s * 0.30f, s * 0.92f, -s * 0.16f, p)
                canvas.drawLine(s * 0.56f, s * 0.08f, s * 0.78f, s * 0.30f, p)
                canvas.drawLine(s * 0.78f, s * 0.30f, s * 0.92f, s * 0.16f, p)
            }

            Species.SPIDER -> {
                p.style = Paint.Style.STROKE
                val legLen = s * 0.34f
                for (side in intArrayOf(-1, 1)) {
                    for (i in 0..3) {
                        val baseAng = Math.toRadians((30.0 + i * 33.0) * side).toFloat()
                        val wig = sin(phase * 9f + i * 0.8f) * 0.16f
                        val a1 = baseAng + wig
                        val kx = s * 0.12f + cos(a1) * legLen * 0.55f
                        val ky = sin(a1) * legLen * 0.55f
                        val fx = kx + cos(a1) * legLen * 0.55f
                        val fy = ky + sin(a1) * legLen * 0.55f
                        canvas.drawLine(s * 0.12f, 0f, kx, ky, p)
                        canvas.drawLine(kx, ky, fx, fy, p)
                    }
                }
                p.style = Paint.Style.FILL
                canvas.drawCircle(-s * 0.14f, 0f, s * 0.30f, p)
                canvas.drawCircle(s * 0.16f, 0f, s * 0.18f, p)
                p.style = Paint.Style.STROKE
                canvas.drawCircle(-s * 0.14f, 0f, s * 0.08f, p)
            }

            Species.MOTH -> {
                val flap = 0.45f + 0.55f * abs(sin(phase * 13f))
                p.style = Paint.Style.FILL
                // 上翅
                path.reset()
                path.moveTo(s * 0.06f, -s * 0.06f)
                path.lineTo(-s * 0.38f, -s * 0.58f * flap)
                path.lineTo(-s * 0.50f, -s * 0.04f)
                path.close()
                canvas.drawPath(path, p)
                // 下翅
                path.reset()
                path.moveTo(s * 0.06f, s * 0.06f)
                path.lineTo(-s * 0.34f, s * 0.52f * flap)
                path.lineTo(-s * 0.48f, s * 0.02f)
                path.close()
                canvas.drawPath(path, p)
                // 身体
                tmpRect.set(-s * 0.06f, -s * 0.30f, s * 0.16f, s * 0.30f)
                canvas.drawOval(tmpRect, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = (s * 0.05f).coerceAtLeast(dp)
                canvas.drawLine(s * 0.12f, -s * 0.22f, s * 0.34f, -s * 0.44f, p)
                canvas.drawLine(s * 0.12f, s * 0.22f, s * 0.34f, s * 0.44f, p)
            }

            Species.CENTIPEDE -> {
                p.style = Paint.Style.STROKE
                val seg = 7
                for (i in 0..seg) {
                    val fx = -s * 0.50f + (i.toFloat() / seg) * s * 0.95f
                    val off = sin(phase * 6f + i * 0.9f) * s * 0.07f
                    p.style = Paint.Style.FILL
                    val r = s * 0.15f * (1f - abs(i - seg / 2f) / (seg * 1.6f))
                    canvas.drawCircle(fx, off, r, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = (s * 0.055f).coerceAtLeast(dp)
                    if (i < seg) {
                        canvas.drawLine(fx, off - r * 0.8f, fx - s * 0.06f, off - s * 0.26f, p)
                        canvas.drawLine(fx, off + r * 0.8f, fx - s * 0.06f, off + s * 0.26f, p)
                    }
                }
            }
        }
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
