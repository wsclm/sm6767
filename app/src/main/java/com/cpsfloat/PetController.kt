package com.cpsfloat

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 桌宠只由两个悬浮窗组成：
 *  - 显示窗口：整幅 SVG 动画（正方形，动画里有大片透明区域），完全不拦截触摸；
 *  - 点击窗口：只盖在螃蟹身体上的小窗口，负责点击 / 连点 / 长按 / 拖动。
 * 这样透明区域不会挡住下面的应用。
 */

// 以下比例都是“占显示窗口边长”的比例（来自 SVG 的 viewBox -15 -25 45 45 里螃蟹的位置）
private const val CRAB_L = 0.333f      // 螃蟹身体左边界
private const val CRAB_R = 0.667f      // 螃蟹身体右边界
private const val HIT_L = 0.30f
private const val HIT_R = 0.70f
private const val HIT_T = 0.60f
private const val HIT_B = 0.93f
private const val MINI_EDGE = 0.633f   // 迷你模式：屏幕边缘对准螃蟹身体的这个位置（右手臂露出一半）

/** 盖在螃蟹身上的透明点击层。 */
private class HitView(c: Context) : View(c) {
    var onTap: ((Int, Boolean) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onLong: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longFired = false
    private var cancelled = false
    private var tapCount = 0
    private var lastTapAt = 0L
    private val longRun = Runnable {
        if (!dragging && !cancelled) {
            longFired = true
            onLong?.invoke()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longRun)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.rawX
                downY = e.rawY
                lastX = e.rawX
                lastY = e.rawY
                dragging = false
                longFired = false
                cancelled = false
                removeCallbacks(longRun)
                postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val far = abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop
                if (!dragging && !longFired && !cancelled && far) {
                    removeCallbacks(longRun)
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) onDrag?.invoke(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                } else if (!longFired && !cancelled) {
                    val now = SystemClock.uptimeMillis()
                    tapCount = if (now - lastTapAt < 450L) tapCount + 1 else 1
                    lastTapAt = now
                    val left = e.x < width / 2f
                    onTap?.invoke(tapCount, left)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                }
                cancelled = true
            }
        }
        return true
    }
}

/** 长按桌宠弹出的小菜单（相当于原作的右键菜单）。 */
private class MenuBox(c: Context, val onOutside: () -> Unit) : LinearLayout(c) {
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        return super.onTouchEvent(e)
    }
}

private class PetMenu(
    private val ctx: Context,
    private val wm: WindowManager,
    private val items: List<Pair<String, () -> Unit>>,
    private val onClosed: () -> Unit
) {
    private var root: LinearLayout? = null
    private val h = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { dismiss() }

    fun show(ax: Int, ay: Int, aw: Int, ah: Int, sw: Int, sh: Int) {
        val box = MenuBox(ctx) { dismiss() }
        box.orientation = LinearLayout.VERTICAL
        box.background = GradientDrawable().apply {
            setColor(Pal.CARD)
            cornerRadius = ctx.dp(14).toFloat()
            setStroke(ctx.dp(1), Pal.LINE)
        }
        box.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        for (item in items) {
            val tv = TextView(ctx)
            tv.text = item.first
            tv.textSize = 14.5f
            tv.setTextColor(Pal.TEXT)
            tv.setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(16), ctx.dp(11))
            tv.setOnClickListener {
                dismiss()
                item.second()
            }
            box.addView(tv)
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val mw = box.measuredWidth
        val mh = box.measuredHeight
        var x = ax + aw / 2 - mw / 2
        var y = ay + ah + ctx.dp(6)
        if (y + mh > sh) y = ay - mh - ctx.dp(6)
        x = x.coerceIn(0, (sw - mw).coerceAtLeast(0))
        y = y.coerceIn(0, (sh - mh).coerceAtLeast(0))
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        l.gravity = Gravity.TOP or Gravity.START
        l.x = x
        l.y = y
        try {
            wm.addView(box, l)
            root = box
            h.postDelayed(autoClose, 6000L)
        } catch (_: Throwable) {
            onClosed()
        }
    }

    fun dismiss() {
        h.removeCallbacks(autoClose)
        val r = root
        root = null
        if (r != null) {
            try { wm.removeView(r) } catch (_: Throwable) { }
            onClosed()
        }
    }
}

class PetController(private val ctx: Context, private val wm: WindowManager) {

    val brain = PetBrain(ctx)

    private val h = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private val d = ctx.resources.displayMetrics.density

    private var cfg = PetCfg()
    private var disp: PetView? = null
    private var dLp: WindowManager.LayoutParams? = null
    private var hit: HitView? = null
    private var hLp: WindowManager.LayoutParams? = null

    // 显示窗口左上角
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var moving = false
    private var phaseEnd = 0L
    private var dragging = false
    private var lastTick = 0L
    private var scrW = 0
    private var scrH = 0
    private var mini = 0
    private var lastSeq = 0
    private var tiltX = 0f
    private var tiltY = 0f
    private var menu: PetMenu? = null

    // 走向 / 离开屏幕边缘
    private var slideTarget = 0f
    private var sliding = false
    private var slideEnter = false
    private var slideSide = 0

    private val tick = object : Runnable {
        override fun run() {
            if (disp == null) return
            step()
            h.postDelayed(this, 33)
        }
    }

    private fun now() = SystemClock.uptimeMillis()
    private fun w(): Float = ctx.dp(cfg.sizeDp).toFloat()

    private fun minX() = -HIT_L * w()
    private fun maxX() = scrW - HIT_R * w()
    private fun minY() = -HIT_T * w()
    private fun maxY() = scrH - HIT_B * w()

    private fun miniX(side: Int): Float =
        if (side == 1) -(1f - MINI_EDGE) * w() else scrW - MINI_EDGE * w()

    @Suppress("DEPRECATION")
    private fun updateBounds() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        scrW = dm.widthPixels
        scrH = dm.heightPixels
    }

    // ---------------- 配置 ----------------

    fun apply(c: PetCfg) {
        val old = cfg
        cfg = c
        brain.cfg = c
        if (!c.enabled) {
            remove()
            return
        }
        if (disp == null) {
            create()
        } else {
            if (old.sizeDp != c.sizeDp) resize(old)
            if (old.alphaPct != c.alphaPct) {
                dLp?.alpha = c.alphaPct / 100f
                push()
            }
            if (c.miniSide != mini && !sliding && !dragging) {
                if (c.miniSide != 0) startEnterMini(c.miniSide) else startExitMini()
            }
        }
    }

    private fun create() {
        if (!webViewAvailable()) {
            Toast.makeText(ctx, "系统 WebView 不可用，无法显示桌宠", Toast.LENGTH_LONG).show()
            Cfg.putPetBool(ctx, "enabled", false)
            return
        }
        updateBounds()
        val v = try {
            PetView(ctx)
        } catch (t: Throwable) {
            Toast.makeText(ctx, "桌宠初始化失败：" + t.message, Toast.LENGTH_LONG).show()
            Cfg.putPetBool(ctx, "enabled", false)
            return
        }
        val dl = WindowManager.LayoutParams(
            w().toInt(), w().toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        dl.gravity = Gravity.TOP or Gravity.START
        dl.alpha = cfg.alphaPct / 100f
        val hv = HitView(ctx)
        val hl = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        hl.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            dl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            hl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            dl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            hl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        mini = cfg.miniSide
        val saved = Cfg.petPos(ctx)
        val fx = (saved?.get(0) ?: 780) / 1000f
        val fy = (saved?.get(1) ?: 700) / 1000f
        x = minX() + fx * (maxX() - minX())
        y = minY() + fy * (maxY() - minY())
        if (mini != 0) x = miniX(mini)
        y = y.coerceIn(minY(), maxY())

        hv.onTap = { n, left -> brain.tap(n, left, now()) }
        hv.onDragStart = { onDragStart() }
        hv.onDrag = { dx, dy -> onDrag(dx, dy) }
        hv.onDragEnd = { onDragEnd() }
        hv.onLong = { showMenu() }

        disp = v
        dLp = dl
        hit = hv
        hLp = hl
        placeInit()
        try {
            wm.addView(v, dl)
            wm.addView(hv, hl)
        } catch (t: Throwable) {
            try { wm.removeView(v) } catch (_: Throwable) { }
            try { wm.removeView(hv) } catch (_: Throwable) { }
            v.destroy()
            disp = null
            dLp = null
            hit = null
            hLp = null
            return
        }
        brain.mini = mini
        brain.dragging = false
        brain.moving = false
        brain.reset(now())
        brain.introduce(now())
        lastSeq = 0
        lastTick = now()
        beginPause()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    private fun resize(old: PetCfg) {
        val ow = ctx.dp(old.sizeDp).toFloat()
        val cx = x + ow / 2f
        val cy = y + ow / 2f
        x = cx - w() / 2f
        y = cy - w() / 2f
        if (mini != 0) x = miniX(mini)
        clampXY()
        dLp?.width = w().toInt()
        dLp?.height = w().toInt()
        push()
    }

    private fun clampXY() {
        if (mini == 0 && !sliding) x = x.coerceIn(minX(), maxX())
        y = y.coerceIn(minY(), maxY())
    }

    /** 屏幕方向变化：按比例换算位置，确保桌宠仍在屏幕内。 */
    fun onScreenChanged() {
        if (disp == null) return
        val fx = if (maxX() > minX()) (x - minX()) / (maxX() - minX()) else 0f
        val fy = if (maxY() > minY()) (y - minY()) / (maxY() - minY()) else 0f
        updateBounds()
        sliding = false
        x = if (mini != 0) miniX(mini) else minX() + fx * (maxX() - minX())
        y = minY() + fy * (maxY() - minY())
        clampXY()
        push()
    }

    fun remove() {
        menu?.dismiss()
        h.removeCallbacks(tick)
        if (disp != null) savePos()
        disp?.let {
            try { wm.removeView(it) } catch (_: Throwable) { }
            it.destroy()
        }
        hit?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        disp = null
        dLp = null
        hit = null
        hLp = null
        dragging = false
        moving = false
        sliding = false
    }

    private fun savePos() {
        val rx = maxX() - minX()
        val ry = maxY() - minY()
        val fx = if (rx > 0f) ((x - minX()) / rx * 1000f).toInt() else 780
        val fy = if (ry > 0f) ((y - minY()) / ry * 1000f).toInt() else 700
        Cfg.putPetPos(ctx, fx.coerceIn(0, 1000), fy.coerceIn(0, 1000))
    }

    private fun placeInit() {
        val dl = dLp ?: return
        val hl = hLp ?: return
        dl.x = x.toInt()
        dl.y = y.toInt()
        hl.x = (x + HIT_L * w()).toInt()
        hl.y = (y + HIT_T * w()).toInt()
        hl.width = ((HIT_R - HIT_L) * w()).toInt().coerceAtLeast(1)
        hl.height = ((HIT_B - HIT_T) * w()).toInt().coerceAtLeast(1)
    }

    private fun push() {
        placeInit()
        val v = disp ?: return
        val hv = hit ?: return
        try { wm.updateViewLayout(v, dLp) } catch (_: Throwable) { }
        try { wm.updateViewLayout(hv, hLp) } catch (_: Throwable) { }
    }

    // ---------------- 外部输入 ----------------

    /** 手机倾斜 → 待机时眼睛的朝向（-1..1） */
    fun setTilt(lx: Float, ly: Float) {
        tiltX = lx
        tiltY = ly
    }

    // ---------------- 拖动 / 迷你模式 ----------------

    private fun onDragStart() {
        dragging = true
        sliding = false
        moving = false
        menu?.dismiss()
        brain.dragging = true
        brain.moving = false
        brain.activity(now())
        brain.endPeek(now())
    }

    private fun onDrag(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(-0.45f * w(), scrW - 0.55f * w())
        y = (y + dy).coerceIn(minY(), maxY())
        push()
    }

    private fun onDragEnd() {
        dragging = false
        brain.dragging = false
        val crabL = x + CRAB_L * w()
        val crabR = x + CRAB_R * w()
        val thr = 0.12f * w()
        val distL = crabL
        val distR = scrW - crabR
        val near = min(distL, distR) < thr
        if ((cfg.miniEdge || mini != 0) && near) {
            val side = if (distL <= distR) 1 else 2
            if (mini == side) {
                x = miniX(side)
                clampXY()
                push()
            } else {
                if (mini != 0) {
                    mini = 0
                    brain.mini = 0
                }
                startEnterMini(side)
            }
        } else {
            if (mini != 0) {
                mini = 0
                brain.mini = 0
                Cfg.putPetInt(ctx, "mini", 0)
            }
            clampXY()
            push()
            beginPause()
        }
        savePos()
    }

    /** 螃蟹横着走到屏幕边缘，然后钻进去（迷你模式）。 */
    private fun startEnterMini(side: Int) {
        mini = 0
        brain.mini = 0
        sliding = true
        slideEnter = true
        slideSide = side
        slideTarget = miniX(side)
        moving = true
        vx = 0f
        brain.moving = true
        brain.movingLeft = slideTarget < x
    }

    /** 从屏幕边缘走出来，回到普通模式。 */
    private fun startExitMini() {
        val side = mini
        mini = 0
        brain.mini = 0
        Cfg.putPetInt(ctx, "mini", 0)
        sliding = true
        slideEnter = false
        slideSide = side
        val inward = 0.45f * w()
        slideTarget = (if (side == 1) miniX(1) + inward else miniX(2) - inward).coerceIn(minX(), maxX())
        moving = true
        vx = 0f
        brain.moving = true
        brain.movingLeft = slideTarget < x
    }

    private fun finishSlide() {
        sliding = false
        moving = false
        brain.moving = false
        if (slideEnter) {
            mini = slideSide
            brain.mini = slideSide
            x = miniX(slideSide)
            clampXY()
            push()
            Cfg.putPetInt(ctx, "mini", slideSide)
            brain.enteredMini(now())
        } else {
            clampXY()
            push()
            beginPause()
        }
        savePos()
    }

    // ---------------- 菜单 ----------------

    private fun showMenu() {
        val hl = hLp ?: return
        menu?.dismiss()
        brain.activity(now())
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(label: String, act: () -> Unit) {
            items.add(Pair(label, act))
        }
        val isMini = mini != 0
        add(if (isMini) "退出迷你模式" else "迷你模式") {
            val side = if (x + w() / 2f < scrW / 2f) 1 else 2
            Cfg.putPetInt(ctx, "mini", if (isMini) 0 else side)
        }
        val dndOn = cfg.dnd
        add(if (dndOn) "关闭勿扰模式" else "勿扰模式（睡觉）") {
            Cfg.putPetBool(ctx, "dnd", !dndOn)
        }
        val asleep = brain.asleep
        add(if (asleep) "叫醒" else "睡觉") {
            if (asleep) {
                brain.forcedSleep = false
                brain.activity(now())
            } else {
                brain.forcedSleep = true
            }
        }
        add("关闭桌宠") {
            Cfg.putPetBool(ctx, "enabled", false)
        }
        val m = PetMenu(ctx, wm, items) { menu = null }
        menu = m
        m.show(hl.x, hl.y, hl.width, hl.height, scrW, scrH)
    }

    // ---------------- 行为 ----------------

    private fun randMs(a: Int, b: Int): Long {
        val lo = min(a, b).coerceAtLeast(1)
        val hi = max(a, b).coerceAtLeast(lo)
        return ((lo + rnd.nextFloat() * (hi - lo)) * 1000f).toLong()
    }

    private fun beginPause() {
        moving = false
        vx = 0f
        brain.moving = false
        phaseEnd = now() + randMs(cfg.pauseMin, cfg.pauseMax)
    }

    private fun beginMove() {
        moving = true
        val dir = if (rnd.nextBoolean()) 1f else -1f
        vx = dir * cfg.speedDp * d * (0.7f + 0.6f * rnd.nextFloat())
        phaseEnd = now() + randMs(cfg.moveMin, cfg.moveMax)
    }

    private fun step() {
        val t = now()
        val dt = ((t - lastTick) / 1000f).coerceIn(0f, 0.1f)
        lastTick = t
        val v = disp ?: return

        brain.mini = mini
        brain.dragging = dragging

        if (sliding) {
            // 走向 / 离开边缘
            val speed = 260f * d
            val dir = if (slideTarget >= x) 1f else -1f
            brain.moving = true
            brain.movingLeft = dir < 0f
            x += dir * speed * dt
            if ((dir > 0f && x >= slideTarget) || (dir < 0f && x <= slideTarget)) {
                x = slideTarget
                finishSlide()
            } else {
                push()
            }
        } else if (!dragging) {
            if (mini != 0) {
                // 贴在边缘，只允许上下被拖动
                x = miniX(mini)
                moving = false
                brain.moving = false
            } else if (cfg.wander && brain.calm(t)) {
                if (t >= phaseEnd) {
                    if (moving) beginPause() else beginMove()
                }
                if (moving) {
                    x += vx * dt
                    if (x < minX()) {
                        x = minX()
                        vx = abs(vx)
                    } else if (x > maxX()) {
                        x = maxX()
                        vx = -abs(vx)
                    }
                    push()
                }
                brain.moving = moving
                brain.movingLeft = vx < 0f
            } else {
                if (moving) beginPause()
                brain.moving = false
            }
        }

        val s = brain.decide(t)
        v.show(s.id, s.flip, s.seq != 0 && s.seq != lastSeq)
        lastSeq = s.seq
        if (s.id == "idle-follow") {
            if (cfg.follow) v.look(tiltX, tiltY) else v.look(0f, 0f)
        }
    }
}
