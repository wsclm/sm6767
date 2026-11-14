package com.cpsfloat

import android.content.Context
import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.view.WindowManager

const val SLOTS = 2

object Modes {
    const val FULL = 0    // 全屏点击统计（Shizuku）
    const val REGION = 1  // 指定区域统计（Shizuku）
    const val COVER = 2   // 区域覆盖统计（无需 Shizuku，会拦截该区域点击）
}

data class SlotCfg(
    val enabled: Boolean,
    val mode: Int,
    val rx: Int, val ry: Int, val rw: Int, val rh: Int,   // 点击区域（屏幕像素）
    val x: Int, val y: Int,                                // CPS 显示中心位置（屏幕像素）
    val sizeSp: Int,
    val textColor: Int,
    val bgColor: Int,
    val radiusDp: Int,
    val bgW: Int,                                          // 背景宽度 dp，0 = 自适应
    val bgH: Int,                                          // 背景高度 dp，0 = 自适应
    val scalePct: Int,                                     // 整体显示缩放 %（背景 + 文字一起缩放，以中心为基点）
    val template: String,
    val font: String,
    val bold: Boolean,
    val hideText: Boolean,
    val idleBgOnly: Boolean,
    val locked: Boolean
) {
    val hasRegion get() = rw > 0 && rh > 0
}

data class PetCfg(
    val enabled: Boolean = false,
    val sizeDp: Int = 150,                 // 整个动画画面的边长（螃蟹本体约占 1/3）
    val alphaPct: Int = 100,               // 桌宠整体不透明度 %
    val speedDp: Int = 40,                 // 走动速度 dp/秒
    val moveMin: Int = 2, val moveMax: Int = 5,     // 每次走动的时长范围（秒）
    val pauseMin: Int = 4, val pauseMax: Int = 12,  // 每次停下的时长范围（秒）
    val wander: Boolean = true,            // 随机走动
    val follow: Boolean = true,            // 待机时眼睛跟随（手机倾斜 / 手指）
    val idleVariants: Boolean = true,      // 偶尔做待机小动作（悠闲、看书、吹泡泡……）
    val sleepAfter: Int = 60,              // 无操作多少秒后睡觉（需要环境感知）
    val miniEdge: Boolean = true,          // 拖到屏幕边缘自动进入迷你模式
    val miniSide: Int = 0,                 // 迷你模式：0 = 关，1 = 左边缘，2 = 右边缘
    val dnd: Boolean = false,              // 勿扰模式：一直睡觉
    val off: Set<String> = emptySet()      // 被关闭的触发条件（见 PetTrig）
)

/** 桌宠的触发条件（每项可单独开关）。桌宠与 CPS 悬浮窗完全无关，只响应手机自身的状态。 */
object PetTrig {
    class T(val key: String, val label: String, val desc: String)

    val ALL: List<T> = listOf(
        T("kbd", "迷你模式 + 键盘弹出 → 迷你打字", "螃蟹在迷你模式（贴在屏幕边缘）时，键盘弹出就播放“迷你打字”（需要环境感知）"),
        T("app", "打开应用 → 对应动作", "游戏杂耍、音乐听歌、视频巫师、浏览器 / 新闻看书、图片设计、地图侦探、社交搬运、办公思考（需要环境感知）"),
        T("notif", "收到通知 → 灯泡提醒", "有新通知时亮起灯泡（需要环境感知）"),
        T("typing", "真的在打字 → 打字 / 老板打字", "只在输入框里真的输入文字时才敲键盘（停手 2.5 秒后结束，键盘只是弹出来不算）；连续打字 20 秒以上变成老板模式（需要环境感知）"),
        T("touch", "无操作睡觉 / 有操作醒来", "一段时间没有任何操作就打哈欠睡着，有操作立刻醒来（需要环境感知）"),
        T("screen", "锁屏睡觉 / 解锁醒来", "锁屏时趴下睡觉，解锁后伸懒腰醒来；清晨第一次解锁喝咖啡"),
        T("music", "播放音乐 → 戴耳机律动", "手机正在播放音乐 / 视频声音时戴上耳机摇摆"),
        T("power", "电量与温度", "插上充电器冒爱心眼；电量低于 15% 没精神；机身过热时超载冒烟"),
        T("shake", "摇晃手机 → 头晕", "用力摇晃手机时螃蟹眼冒蚊香圈"),
        T("net", "网络断开 / 恢复", "断网时报错，恢复后开心"),
        T("install", "安装 / 更新应用 → 建造", "有应用安装或更新时戴安全帽施工"),
        T("night", "深夜 → 通宵", "凌晨还在用手机时，螃蟹陪你熬夜")
    )
}

/** 所有设置改动即时写入 SharedPreferences，服务监听变化后自动同步，无需“保存并应用”。 */
object Cfg {
    const val DEF_TEXT_COLOR = 0xFFFFFFFF.toInt()
    const val DEF_BG_COLOR = 0xAA000000.toInt()
    const val DEF_FONT = "sans-serif"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("cps_cfg", Context.MODE_PRIVATE)

    private fun k(i: Int, n: String) = "s${i}_$n"

    // ---- 横/竖屏分别保存位置与区域；某个方向没保存过时，按比例从另一个方向换算 ----
    @Suppress("DEPRECATION")
    private fun real(c: Context): IntArray {
        val wm = c.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    fun isLandscape(c: Context): Boolean { val r = real(c); return r[0] > r[1] }

    private fun sfx(land: Boolean) = if (land) "_l" else ""

    /** 从“另一个方向”换算到“当前方向”的 x、y 缩放系数 */
    private fun scale(c: Context, land: Boolean): FloatArray {
        val r = real(c)
        val a = minOf(r[0], r[1]).toFloat()
        val b = maxOf(r[0], r[1]).toFloat()
        return if (land) floatArrayOf(b / a, a / b) else floatArrayOf(a / b, b / a)
    }

    /** CPS 显示的默认中心位置（屏幕像素） */
    fun defPos(i: Int): IntArray = intArrayOf(300, 320 + i * 200)

    private fun pos(p: SharedPreferences, c: Context, i: Int, dx: Int, dy: Int, land: Boolean): IntArray {
        val s = sfx(land); val o = sfx(!land)
        if (p.contains(k(i, "cx$s"))) return intArrayOf(p.getInt(k(i, "cx$s"), dx), p.getInt(k(i, "cy$s"), dy))
        if (p.contains(k(i, "cx$o"))) {
            val f = scale(c, land)
            return intArrayOf((p.getInt(k(i, "cx$o"), dx) * f[0]).toInt(), (p.getInt(k(i, "cy$o"), dy) * f[1]).toInt())
        }
        return intArrayOf(dx, dy)
    }

    private fun region(p: SharedPreferences, c: Context, i: Int, land: Boolean): IntArray {
        val s = sfx(land); val o = sfx(!land)
        if (p.contains(k(i, "rw$s"))) return intArrayOf(
            p.getInt(k(i, "rx$s"), 0), p.getInt(k(i, "ry$s"), 0),
            p.getInt(k(i, "rw$s"), 0), p.getInt(k(i, "rh$s"), 0))
        if (p.contains(k(i, "rw$o"))) {
            val f = scale(c, land)
            return intArrayOf(
                (p.getInt(k(i, "rx$o"), 0) * f[0]).toInt(), (p.getInt(k(i, "ry$o"), 0) * f[1]).toInt(),
                (p.getInt(k(i, "rw$o"), 0) * f[0]).toInt(), (p.getInt(k(i, "rh$o"), 0) * f[1]).toInt())
        }
        return intArrayOf(0, 0, 0, 0)
    }

    fun load(c: Context, i: Int): SlotCfg {
        val p = prefs(c)
        fun gi(n: String, d: Int) = p.getInt(k(i, n), d)
        fun gb(n: String, d: Boolean) = p.getBoolean(k(i, n), d)
        val land = isLandscape(c)
        val dp0 = defPos(i)
        val ps = pos(p, c, i, dp0[0], dp0[1], land)
        val rg = region(p, c, i, land)
        return SlotCfg(
            enabled = gb("enabled", false),
            mode = gi("mode", Modes.FULL),
            rx = rg[0], ry = rg[1], rw = rg[2], rh = rg[3],
            x = ps[0], y = ps[1],
            sizeSp = gi("size", 22),
            textColor = gi("textColor", DEF_TEXT_COLOR),
            bgColor = gi("bgColor", DEF_BG_COLOR),
            radiusDp = gi("radius", 12),
            bgW = gi("bgW", 0),
            bgH = gi("bgH", 0),
            scalePct = gi("scale", 100),
            template = p.getString(k(i, "template"), "{cps} CPS") ?: "{cps} CPS",
            font = p.getString(k(i, "font"), DEF_FONT) ?: DEF_FONT,
            bold = gb("bold", true),
            hideText = gb("hideText", false),
            idleBgOnly = gb("idleBgOnly", false),
            locked = gb("locked", false)
        )
    }

    fun putInt(c: Context, i: Int, n: String, v: Int) = prefs(c).edit().putInt(k(i, n), v).apply()
    fun putBool(c: Context, i: Int, n: String, v: Boolean) = prefs(c).edit().putBoolean(k(i, n), v).apply()
    fun putStr(c: Context, i: Int, n: String, v: String) = prefs(c).edit().putString(k(i, n), v).apply()

    /** 保存到当前屏幕方向（横/竖屏各存一份） */
    fun putRegion(c: Context, i: Int, x: Int, y: Int, w: Int, h: Int) {
        val s = sfx(isLandscape(c))
        prefs(c).edit()
            .putInt(k(i, "rx$s"), x).putInt(k(i, "ry$s"), y)
            .putInt(k(i, "rw$s"), w).putInt(k(i, "rh$s"), h).apply()
    }

    fun putPos(c: Context, i: Int, x: Int, y: Int) {
        val s = sfx(isLandscape(c))
        prefs(c).edit().putInt(k(i, "cx$s"), x).putInt(k(i, "cy$s"), y).apply()
    }

    fun anyEnabled(c: Context) = (0 until SLOTS).any { load(c, it).enabled }

    // ---- 桌宠 ----
    fun loadPet(c: Context): PetCfg {
        val p = prefs(c)
        val d = PetCfg()
        return PetCfg(
            enabled = p.getBoolean("pet_enabled", d.enabled),
            sizeDp = p.getInt("pet_size", d.sizeDp),
            alphaPct = p.getInt("pet_alpha", d.alphaPct).coerceIn(5, 100),
            speedDp = p.getInt("pet_speed", d.speedDp),
            moveMin = p.getInt("pet_mmin", d.moveMin), moveMax = p.getInt("pet_mmax", d.moveMax),
            pauseMin = p.getInt("pet_pmin", d.pauseMin), pauseMax = p.getInt("pet_pmax", d.pauseMax),
            wander = p.getBoolean("pet_wander", d.wander),
            follow = p.getBoolean("pet_follow", d.follow),
            idleVariants = p.getBoolean("pet_variants", d.idleVariants),
            sleepAfter = p.getInt("pet_sleep", d.sleepAfter),
            miniEdge = p.getBoolean("pet_miniedge", d.miniEdge),
            miniSide = p.getInt("pet_mini", d.miniSide).coerceIn(0, 2),
            dnd = p.getBoolean("pet_dnd", d.dnd),
            off = HashSet(p.getStringSet("pet_off", emptySet()) ?: emptySet<String>())
        )
    }

    fun setTrigger(c: Context, key: String, on: Boolean) {
        val cur = HashSet(prefs(c).getStringSet("pet_off", emptySet()) ?: emptySet<String>())
        if (on) cur.remove(key) else cur.add(key)
        prefs(c).edit().putStringSet("pet_off", cur).apply()
    }

    // ---- 一键重置 ----
    /** 重置某个 CPS 窗口的全部设置（样式、行为、位置、区域），保留“是否启用”。 */
    fun resetSlot(c: Context, i: Int) {
        val p = prefs(c)
        val e = p.edit()
        val pre = "s${i}_"
        for (key in p.all.keys) {
            if (key.startsWith(pre) && key != k(i, "enabled")) e.remove(key)
        }
        e.apply()
    }

    /** 重置桌宠的全部设置（外观、行为、触发条件、位置），保留“是否启用”。 */
    fun resetPet(c: Context) {
        val p = prefs(c)
        val e = p.edit()
        for (key in p.all.keys) {
            if (key.startsWith("pet_") && key != "pet_enabled") e.remove(key)
        }
        e.apply()
    }

    fun putPetInt(c: Context, n: String, v: Int) = prefs(c).edit().putInt("pet_$n", v).apply()
    fun putPetBool(c: Context, n: String, v: Boolean) = prefs(c).edit().putBoolean("pet_$n", v).apply()

    /** 桌宠位置：占屏幕可移动范围的千分比（横竖屏通用） */
    fun petPos(c: Context): IntArray? {
        val p = prefs(c)
        if (!p.contains("pet_fx")) return null
        return intArrayOf(p.getInt("pet_fx", 0), p.getInt("pet_fy", 0))
    }

    fun putPetPos(c: Context, fx: Int, fy: Int) =
        prefs(c).edit().putInt("pet_fx", fx).putInt("pet_fy", fy).apply()

}
