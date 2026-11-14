package com.cpsfloat

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.Calendar
import java.util.Random

/**
 * 环境感知事件总线。无障碍服务（SenseService）在这里发布“键盘弹出、前台应用变化、有通知、在打字、有操作”，
 * 桌宠服务订阅。事件都在主线程回调。桌宠只依赖这里和系统广播，和 CPS 悬浮窗没有任何关联。
 */
object Sense {
    interface Listener {
        fun onKeyboard(shown: Boolean)
        fun onForeground(pkg: String)
        fun onNotification()
        fun onTyping()
        fun onActivity()
    }

    @Volatile
    var connected = false

    @Volatile
    var listener: Listener? = null
}

/**
 * 桌宠大脑：根据各种触发条件，决定此刻该播放 assets/clawd 里的哪一个动画。
 *
 * 优先级（从高到低）：
 *   被拎起 > 一次性动画（点击反应、通知、报错、头晕、开心……）> 睡眠 > 持续场景（打字、键盘、过热、低电量、
 *   听音乐、前台应用类型）> 偶尔的待机小动作 > 横着走（随机走动时）> 眼睛跟随（默认待机）。
 */
class PetBrain(private val ctx: Context) {

    class Show(val id: String, val flip: Boolean, val seq: Int)

    var cfg = PetCfg()
    var mini = 0                 // 0 = 普通模式，1 = 贴左边缘，2 = 贴右边缘
    var dragging = false
    var moving = false
    var movingLeft = false
    var forcedSleep = false      // 菜单里手动“睡觉”

    private val rnd = Random()

    // 一次性动画
    private var shot: String? = null
    private var shotUntil = 0L
    private var shotPrio = 0
    private var shotSeq = 0

    // 睡眠
    var asleep = false
        private set
    private var sleepSince = 0L
    private var sleepForced = false
    private var screenOff = false
    private var lastActivity = 0L

    // 场景
    private var kbd = false
    private var musicOn = false
    private var lowBat = false
    private var hot = false
    private var fgPkg = ""
    private var fgLoop: String? = null
    private val typingTimes = ArrayList<Long>()
    private var lastTypeAt = 0L
    private var typeSessionStart = 0L
    private var typeSessionCount = 0

    // 待机小动作
    private var variantId: String? = null
    private var variantUntil = 0L
    private var nextVariantAt = 0L
    private var nextNightAt = 0L

    private var hadNetLoss = false
    private var lastCoffeeDay = -1

    private fun on(key: String) = key !in cfg.off

    fun reset(now: Long) {
        shot = null
        shotUntil = 0L
        asleep = false
        lastActivity = now
        variantId = null
        nextVariantAt = now + 20000L + rnd.nextInt(20000)
        kbd = false
        typingTimes.clear()
        lastTypeAt = 0L
        typeSessionStart = 0L
        typeSessionCount = 0
    }

    // ---------------- 事件输入 ----------------

    private fun fire(id: String, durMs: Long, prio: Int, now: Long) {
        if (shot != null && now < shotUntil && prio < shotPrio) return
        shot = id
        shotUntil = now + durMs
        shotPrio = prio
        shotSeq++
        variantId = null
    }

    fun activity(now: Long) {
        lastActivity = now
    }

    /** 点击桌宠（连点次数 n，left = 点在螃蟹左半边） */
    fun tap(n: Int, left: Boolean, now: Long) {
        lastActivity = now
        if (asleep) {
            if (forcedSleep) {
                forcedSleep = false          // 手动睡觉：点一下叫醒
                return
            }
            if (!cfg.dnd && !(screenOff && on("screen"))) return   // 自动睡着：点一下醒来
        }
        if (mini != 0) {
            if (n >= 2) {
                fire("mini-happy", 3000L, 60, now)
            } else if (shot == "mini-peek" && now < shotUntil) {
                shotUntil = now
            } else {
                fire("mini-peek", 3500L, 60, now)
            }
            return
        }
        when {
            n >= 4 -> fire("react-annoyed", 6000L, 62, now)
            n == 3 -> fire("react-double-jump", 3500L, 62, now)
            n == 2 -> fire("react-double", 3500L, 61, now)
            else -> fire(if (left) "react-left" else "react-right", 2500L, 60, now)
        }
    }

    /** 刚进入迷你模式 */
    fun enteredMini(now: Long) {
        fire(if (asleep) "mini-enter-sleep" else "mini-enter", 3200L, 65, now)
    }

    /** 刚启用桌宠：打个招呼 */
    fun introduce(now: Long) {
        if (mini == 0) fire("about-hero", 4400L, 95, now)
    }

    fun notification(now: Long) {
        if (!on("notif")) return
        if (mini != 0) fire("mini-alert", 4000L, 70, now) else fire("notification", 6000L, 70, now)
    }

    fun keyboard(shown: Boolean, now: Long) {
        kbd = shown && on("kbd")
        if (shown) lastActivity = now
    }

    /** 输入框里真的有文字变化（无障碍服务已过滤掉非输入框、程序自动改文字的情况）。 */
    fun typing(now: Long) {
        lastActivity = now
        if (!on("typing")) return
        // 超过 5 秒没打字就算新的一轮打字
        if (now - lastTypeAt > 5000L) {
            typeSessionStart = now
            typeSessionCount = 0
        }
        lastTypeAt = now
        typeSessionCount++
        typingTimes.add(now)
        while (typingTimes.isNotEmpty() && now - typingTimes[0] > 4000L) typingTimes.removeAt(0)
    }

    /** 0 = 没在打字，1 = 正在打字，2 = 长时间连续打字（老板模式）。停手后 2.5 秒（老板模式 5 秒）就结束。 */
    private fun typingState(now: Long): Int {
        if (!on("typing") || lastTypeAt == 0L) return 0
        val since = now - lastTypeAt
        if (since <= 5000L && now - typeSessionStart >= 20000L && typeSessionCount >= 40) return 2
        if (since <= 2500L) {
            var recent = 0
            for (t in typingTimes) if (now - t <= 3000L) recent++
            if (recent >= 3) return 1
        }
        return 0
    }

    fun foreground(pkg: String, now: Long) {
        lastActivity = now
        if (pkg == fgPkg) return
        fgPkg = pkg
        if (!on("app")) {
            fgLoop = null
            return
        }
        fgLoop = loopFor(pkg)
    }

    fun screen(on: Boolean, now: Long) {
        screenOff = !on
        if (on) lastActivity = now
    }

    fun unlocked(now: Long) {
        lastActivity = now
        screenOff = false
        if (!on("screen")) return
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val day = cal.get(Calendar.DAY_OF_YEAR)
        if (h in 6..10 && lastCoffeeDay != day && mini == 0) {
            lastCoffeeDay = day
            fire("coffee-head-flip", 8000L, 55, now)
        }
    }

    fun music(playing: Boolean) {
        musicOn = playing && on("music")
    }

    fun battery(levelPct: Int, charging: Boolean, tempTenthC: Int) {
        val p = on("power")
        lowBat = p && levelPct in 0..15 && !charging
        hot = p && tempTenthC >= 430
    }

    fun charger(connected: Boolean, now: Long) {
        if (!connected || !on("power")) return
        if (mini != 0) {
            fire("mini-happy", 4000L, 50, now)
        } else if (rnd.nextBoolean()) {
            fire("heart-eyes", 8000L, 50, now)
        } else {
            fire("aegyo-shy", 6400L, 50, now)
        }
    }

    fun install(now: Long) {
        if (!on("install") || mini != 0) return
        fire("working-building", 6000L, 45, now)
    }

    fun net(up: Boolean, now: Long) {
        if (!on("net")) return
        if (!up) {
            hadNetLoss = true
            if (mini != 0) fire("mini-alert", 4000L, 80, now) else fire("error", 6000L, 80, now)
        } else if (hadNetLoss) {
            hadNetLoss = false
            if (mini != 0) fire("mini-happy", 3000L, 52, now) else fire("happy", 4000L, 52, now)
        }
    }

    fun shake(now: Long) {
        if (!on("shake")) return
        lastActivity = now
        if (mini == 0) fire("dizzy", 4500L, 85, now)
    }

    /** 点到别处 / 拖动后把迷你探头收回去 */
    fun endPeek(now: Long) {
        if (shot == "mini-peek") shotUntil = now
    }

    // ---------------- 决策 ----------------

    private fun loopFor(pkg: String): String? {
        if (pkg.isEmpty()) return null
        if (pkg in BROWSERS) return "idle-reading"
        val cat = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).category
        } catch (_: Throwable) {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        return when (cat) {
            ApplicationInfo.CATEGORY_GAME -> "working-juggling"
            ApplicationInfo.CATEGORY_AUDIO -> "headphones-groove"
            ApplicationInfo.CATEGORY_VIDEO -> "working-wizard"
            ApplicationInfo.CATEGORY_IMAGE -> "designing"
            ApplicationInfo.CATEGORY_SOCIAL -> "working-carrying"
            ApplicationInfo.CATEGORY_NEWS -> "idle-reading"
            ApplicationInfo.CATEGORY_MAPS -> "working-debugger"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "working-thinking"
            else -> null
        }
    }

    private fun ctxClip(now: Long): String? {
        val ts = typingState(now)
        if (mini != 0) {
            // 迷你模式：键盘弹出或正在打字 → 迷你打字
            return if (kbd || ts > 0) "mini-typing" else null
        }
        // 普通模式：只有真的在打字才播放打字动画，键盘只是弹出来不算
        if (ts == 2) return "working-typing-boss"
        if (ts == 1) return "working-typing"
        if (hot) return "working-ultrathink"
        if (lowBat) return "idle-low-battery"
        if (musicOn) return "headphones-groove"
        return fgLoop
    }

    /** 现在是否“很空闲”：可以随机走动、做待机小动作。 */
    fun calm(now: Long): Boolean {
        if (dragging || asleep || mini != 0) return false
        if (shot != null && now < shotUntil) return false
        return ctxClip(now) == null
    }

    private fun sleepClip(now: Long): String {
        if (mini != 0) return "mini-sleep"
        if (sleepForced) return "collapse-sleep"
        val t = now - sleepSince
        return when {
            t < 3800L -> "idle-yawn"
            t < 7800L -> "idle-doze"
            t < 8800L -> "idle-collapse"
            else -> "sleeping"
        }
    }

    fun decide(now: Long): Show {
        // 1. 睡眠 / 醒来判断
        val forced = cfg.dnd || forcedSleep || (screenOff && on("screen"))
        val auto = Sense.connected && on("touch") && now - lastActivity > cfg.sleepAfter * 1000L
        val want = forced || auto
        if (want && !asleep) {
            asleep = true
            sleepSince = now
            variantId = null
        } else if (!want && asleep) {
            asleep = false
            lastActivity = now
            if (mini == 0) fire("wake", 1500L, 90, now)
        }
        sleepForced = forced

        // 2. 被拎起
        if (dragging) return Show("react-drag", false, 0)

        // 3. 一次性动画
        val s = shot
        if (s != null) {
            if (now < shotUntil) return Show(s, mini == 1 && s.startsWith("mini-"), shotSeq)
            shot = null
            // 咖啡顶头演完之后，接着演“喝咖啡”
            if (s == "coffee-head-flip" && mini == 0) {
                fire("coffee-hand", 4400L, 55, now)
                return Show("coffee-hand", false, shotSeq)
            }
        }

        // 4. 睡眠
        if (asleep) return Show(sleepClip(now), mini == 1, 0)

        // 5. 持续场景
        val c = ctxClip(now)
        if (c != null) {
            variantId = null
            return Show(c, mini == 1 && c.startsWith("mini-"), 0)
        }
        if (mini != 0) return Show("mini-idle", mini == 1, 0)

        // 6. 待机小动作
        val v = variantId
        if (v != null) {
            if (moving || now >= variantUntil) {
                variantId = null
                nextVariantAt = now + 25000L + rnd.nextInt(25000)
                // 随机播放的咖啡顶头正常演完后，同样接着喝咖啡
                if (v == "coffee-head-flip" && !moving && !dragging) {
                    fire("coffee-hand", 4400L, 55, now)
                    return Show("coffee-hand", false, shotSeq)
                }
            } else {
                return Show(v, false, 0)
            }
        }
        if (cfg.idleVariants && !moving && now >= nextVariantAt) {
            val pick = pickVariant(now)
            variantId = pick.first
            variantUntil = now + pick.second
            return Show(pick.first, false, 0)
        }

        // 7. 横着走 / 眼睛跟随
        if (moving) return Show("mini-crabwalk", movingLeft, 0)
        return Show("idle-follow", false, 0)
    }

    private fun pickVariant(now: Long): Pair<String, Long> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h in 0..4 && on("night") && now >= nextNightAt) {
            nextNightAt = now + 30L * 60L * 1000L
            return Pair("outlaw-bender", 15000L)
        }
        return when (rnd.nextInt(7)) {
            0 -> Pair("idle-living", 16000L)
            1 -> Pair("idle-look", 10000L)
            2 -> Pair("idle-reading", 14000L)
            3 -> Pair("idle-bubble", 9000L)
            4 -> Pair("coffee-head-flip", 8000L)
            5 -> Pair("working-sweeping", 10000L)
            else -> Pair("working-wizard", 7000L)
        }
    }

    companion object {
        private val BROWSERS = setOf(
            "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta", "com.microsoft.emmx",
            "com.brave.browser", "com.opera.browser", "com.android.browser", "com.sec.android.app.sbrowser",
            "com.UCMobile", "com.heytap.browser", "com.mi.globalbrowser", "com.miui.browser",
            "com.vivo.browser", "com.huawei.browser", "com.duckduckgo.mobile.android", "com.quark.browser"
        )
    }
}
