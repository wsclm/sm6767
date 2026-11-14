package com.cpsfloat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.abs
import org.json.JSONObject

/**
 * Clawd 动画清单。每个动画对应 assets/clawd/clawd-<id>.svg（Clawd on Desk 的原版素材，原样播放）。
 * durMs = 0 表示循环动画（由触发条件决定何时结束）；> 0 表示一次性动画，播放这么久后自动回到别的状态。
 */
class Clip(val id: String, val label: String, val durMs: Long, val group: String)

object Clips {
    const val G_IDLE = "待机"
    const val G_SLEEP = "睡眠"
    const val G_WORK = "工作 / 场景"
    const val G_MOOD = "情绪"
    const val G_REACT = "点击反应"
    const val G_MINI = "迷你模式"

    val ALL: List<Clip> = listOf(
        Clip("idle-follow", "眼睛跟随", 0, G_IDLE),
        Clip("idle-living", "悠闲", 16000, G_IDLE),
        Clip("idle-look", "东张西望", 10000, G_IDLE),
        Clip("idle-reading", "看书", 14000, G_IDLE),
        Clip("idle-bubble", "吹泡泡", 4500, G_IDLE),
        Clip("idle-low-battery", "低电量", 0, G_IDLE),
        Clip("static-base", "静止", 0, G_IDLE),

        Clip("idle-yawn", "打哈欠", 3800, G_SLEEP),
        Clip("idle-doze", "打盹", 0, G_SLEEP),
        Clip("idle-collapse", "趴下", 1000, G_SLEEP),
        Clip("sleeping", "熟睡", 0, G_SLEEP),
        Clip("collapse-sleep", "趴下睡", 0, G_SLEEP),
        Clip("wake", "醒来", 1500, G_SLEEP),

        Clip("working-thinking", "思考", 0, G_WORK),
        Clip("working-typing", "打字", 0, G_WORK),
        Clip("working-typing-boss", "老板打字", 0, G_WORK),
        Clip("working-building", "建造", 0, G_WORK),
        Clip("working-juggling", "杂耍", 0, G_WORK),
        Clip("working-sweeping", "扫地", 10000, G_WORK),
        Clip("working-carrying", "搬运", 0, G_WORK),
        Clip("working-debugger", "侦探调试", 0, G_WORK),
        Clip("working-ultrathink", "超载冒烟", 0, G_WORK),
        Clip("working-wizard", "巫师", 7000, G_WORK),
        Clip("headphones-groove", "耳机律动", 0, G_WORK),
        Clip("designing", "设计", 0, G_WORK),
        Clip("coffee-hand", "喝咖啡", 4400, G_WORK),
        Clip("coffee-head-flip", "咖啡顶头", 8000, G_WORK),
        Clip("outlaw-bender", "通宵", 15000, G_WORK),

        Clip("happy", "开心", 0, G_MOOD),
        Clip("error", "报错", 0, G_MOOD),
        Clip("dizzy", "头晕", 0, G_MOOD),
        Clip("notification", "灯泡提醒", 0, G_MOOD),
        Clip("heart-eyes", "爱心眼", 0, G_MOOD),
        Clip("aegyo-shy", "害羞撒娇", 0, G_MOOD),
        Clip("about-hero", "帅气登场", 4400, G_MOOD),

        Clip("react-left", "看左边", 2500, G_REACT),
        Clip("react-right", "看右边", 2500, G_REACT),
        Clip("react-double", "双击反应", 3500, G_REACT),
        Clip("react-double-jump", "惊跳", 3500, G_REACT),
        Clip("react-annoyed", "烦躁", 6000, G_REACT),
        Clip("react-drag", "被拎起", 0, G_REACT),

        Clip("mini-idle", "迷你待机", 0, G_MINI),
        Clip("mini-peek", "探头", 0, G_MINI),
        Clip("mini-enter", "钻进边缘", 3200, G_MINI),
        Clip("mini-enter-sleep", "睡着钻进", 3200, G_MINI),
        Clip("mini-alert", "迷你提醒", 0, G_MINI),
        Clip("mini-happy", "迷你开心", 0, G_MINI),
        Clip("mini-typing", "迷你打字", 0, G_MINI),
        Clip("mini-crabwalk", "横着走", 0, G_MINI),
        Clip("mini-sleep", "迷你睡觉", 0, G_MINI)
    )

    private val byId: Map<String, Clip> = ALL.associateBy { it.id }

    fun get(id: String): Clip? = byId[id]

    fun groups(): List<String> = ALL.map { it.group }.distinct()

    private const val RANDOM = "空闲时随机播放（约每 25–50 秒一次，需开启“偶尔做待机小动作”）"

    /** 每个动画在什么条件下触发（App 里预览时显示）。 */
    private val TRIGGERS: Map<String, String> = mapOf(
        "idle-follow" to "默认待机：没有别的事发生时播放；眼睛和身体朝手机倾斜的方向看（需开启“眼睛跟随”）",
        "idle-living" to RANDOM,
        "idle-look" to RANDOM,
        "idle-reading" to "打开浏览器 / 新闻类应用时持续播放（需环境感知）；空闲时也会随机播放",
        "idle-bubble" to RANDOM,
        "idle-low-battery" to "电量 ≤ 15% 且没在充电时持续播放（触发条件：电量与温度）",
        "static-base" to "不会自动触发，仅供预览",
        "idle-yawn" to "睡觉的第 1 步（约 3.8 秒）：无操作超过设定时间后开始（需环境感知）",
        "idle-doze" to "睡觉的第 2 步（约 4 秒）：打哈欠之后",
        "idle-collapse" to "睡觉的第 3 步（约 1 秒）：打盹之后趴下",
        "sleeping" to "睡觉的第 4 步：熟睡，直到有操作（或点螃蟹）为止",
        "collapse-sleep" to "锁屏、开启勿扰模式，或在菜单里点“睡觉”时，直接趴下睡",
        "wake" to "从睡觉状态醒来时播放一次：有操作、解锁、点螃蟹或叫醒",
        "working-thinking" to "打开办公 / 效率类应用时持续播放（需环境感知）",
        "working-typing" to "在输入框里真的打字时播放，停手约 2.5 秒后结束（需环境感知）。键盘只是弹出来不会触发",
        "working-typing-boss" to "连续打字 ≥ 20 秒且输入 ≥ 40 次时，打字升级为老板模式；停手 5 秒后结束（需环境感知）",
        "working-building" to "有应用安装或更新时播放约 6 秒",
        "working-juggling" to "打开游戏类应用时持续播放（需环境感知）",
        "working-sweeping" to RANDOM,
        "working-carrying" to "打开社交 / 聊天类应用时持续播放（需环境感知）",
        "working-debugger" to "打开地图类应用时持续播放（需环境感知）",
        "working-ultrathink" to "机身温度 ≥ 43℃ 时持续播放（触发条件：电量与温度）",
        "working-wizard" to "打开视频类应用时持续播放（需环境感知）；空闲时也会随机播放",
        "headphones-groove" to "手机正在播放音乐 / 声音，或打开音乐类应用时",
        "designing" to "打开图片 / 相册 / 设计类应用时持续播放（需环境感知）",
        "coffee-hand" to "“咖啡顶头”播放完之后自动接着播放一次（约 4.4 秒）",
        "coffee-head-flip" to "清晨 6–11 点当天第一次解锁时；空闲时也会随机播放。演完后接着播放“喝咖啡”",
        "outlaw-bender" to "凌晨 0–5 点还在用手机时，最多每 30 分钟一次",
        "happy" to "断网后网络恢复时",
        "error" to "网络断开时",
        "dizzy" to "用力摇晃手机时",
        "notification" to "收到新通知时播放约 6 秒（需环境感知）",
        "heart-eyes" to "插上充电器时（和“害羞撒娇”随机二选一）",
        "aegyo-shy" to "插上充电器时（和“爱心眼”随机二选一）",
        "about-hero" to "刚启用桌宠时播放一次",
        "react-left" to "点一下螃蟹的左半边",
        "react-right" to "点一下螃蟹的右半边",
        "react-double" to "快速连点 2 下",
        "react-double-jump" to "快速连点 3 下",
        "react-annoyed" to "快速连点 4 下及以上",
        "react-drag" to "拖动螃蟹时",
        "mini-idle" to "迷你模式下的默认待机",
        "mini-peek" to "迷你模式下点一下螃蟹探头（约 3.5 秒）",
        "mini-enter" to "螃蟹走到屏幕边缘、进入迷你模式时播放一次",
        "mini-enter-sleep" to "睡着的时候进入迷你模式",
        "mini-alert" to "迷你模式下收到通知或断网",
        "mini-happy" to "迷你模式下连点 2 下、网络恢复或插上充电器",
        "mini-typing" to "迷你模式下键盘弹出，或正在打字时（需环境感知）。只在迷你模式触发",
        "mini-crabwalk" to "随机走动时左右横着走；进出迷你模式时走向 / 离开屏幕边缘",
        "mini-sleep" to "迷你模式下睡觉：无操作超时、锁屏或勿扰模式"
    )

    fun trigger(id: String): String = TRIGGERS[id] ?: "未设置"
}

/** 系统 WebView 是否可用（桌宠动画依赖它来播放 SVG）。 */
fun webViewAvailable(): Boolean = try {
    WebView.getCurrentWebViewPackage() != null
} catch (_: Throwable) {
    false
}

/**
 * 动画播放器：一个透明 WebView，把 SVG 直接内联进页面，SVG 里自带的 CSS / SMIL 动画由浏览器原样播放。
 * 每次切换都会整体替换 SVG，所以一次性动画每次都从头开始。
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class PetView(c: Context) : FrameLayout(c) {

    private val web = WebView(c.applicationContext)
    private val cache = HashMap<String, String>()
    private var ready = false
    private val pending = ArrayList<String>()

    var currentId = ""
        private set
    private var currentFlip = false
    private var lastLookAt = 0L
    private var lastLX = 0f
    private var lastLY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isClickable = false
        web.isLongClickable = false
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.setSupportZoom(false)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                for (js in pending) web.evaluateJavascript(js, null)
                pending.clear()
            }
        }
        addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web.loadDataWithBaseURL("https://clawd.local/", HTML, "text/html", "utf-8", null)
    }

    private fun exec(js: String) {
        if (ready) web.evaluateJavascript(js, null) else pending.add(js)
    }

    /**
     * mini-crabwalk 原版带一段“身体左右摇摆 + 影子平移”，第一帧会让螃蟹突然向左偏约 2 格、向下偏 1 格并倾斜 2°，
     * 而窗口本身又在移动，所以从待机切到走路时螃蟹会“跳”一下、和影子错开。
     * 这里去掉摇摆和影子平移，只保留腿的动作，螃蟹的位置、大小就和待机时完全一致，走路时不再错位。
     */
    private fun steadyCrabwalk(svg: String): String {
        return svg
            .replace("transform: translate(-2px, 1px) rotate(2deg);", "transform: translate(0px, 0px) rotate(0deg);")
            .replace("transform: translate(3px, 1px) rotate(2deg);", "transform: translate(0px, 0px) rotate(0deg);")
            .replace("transform: translateX(-2px) scale(0.9); opacity: 0.4;", "transform: translateX(0px) scale(1); opacity: 0.5;")
            .replace("transform: translateX(3px) scale(0.9); opacity: 0.4;", "transform: translateX(0px) scale(1); opacity: 0.5;")
    }

    private fun svgText(id: String): String? {
        cache[id]?.let { return it }
        return try {
            val t = context.assets.open("clawd/clawd-$id.svg").bufferedReader(Charsets.UTF_8).use { it.readText() }
            // 去掉可能存在的 XML 声明，才能内联进 HTML
            var s = t.substring(t.indexOf("<svg").coerceAtLeast(0))
            // 个别素材（登场 / 通宵 / 静止）的视口和其他动画不同，统一成标准视口，螃蟹的大小和位置才不会跳变
            s = VIEWBOX.replaceFirst(s, "viewBox=\"-15 -25 45 45\"")
            if (id == "mini-crabwalk") s = steadyCrabwalk(s)
            cache[id] = s
            s
        } catch (_: Throwable) {
            null
        }
    }

    /** 播放某个动画；相同动画不会重复加载，restart = true 时强制从头播放。 */
    fun show(id: String, flip: Boolean = false, restart: Boolean = false) {
        if (!restart && id == currentId && flip == currentFlip) return
        val t = svgText(id) ?: return
        currentId = id
        currentFlip = flip
        lastLX = 0f
        lastLY = 0f
        exec("setSvg(" + JSONObject.quote(t) + "," + flip + ");")
    }

    /** 眼睛 / 身体朝某个方向偏移（x、y 取 -1..1），只对“眼睛跟随”动画有效。 */
    fun look(x: Float, y: Float) {
        if (currentId != "idle-follow") return
        if (abs(x - lastLX) < 0.03f && abs(y - lastLY) < 0.03f) return
        val now = System.currentTimeMillis()
        if (now - lastLookAt < 60L) return
        lastLookAt = now
        lastLX = x
        lastLY = y
        exec("look(" + x.coerceIn(-1f, 1f) + "," + y.coerceIn(-1f, 1f) + ");")
    }

    fun destroy() {
        try {
            removeView(web)
            web.stopLoading()
            web.destroy()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val VIEWBOX = Regex("viewBox=\"[^\"]*\"")

        private const val HTML = "<!DOCTYPE html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden;width:100%;height:100%}" +
            "#s{position:absolute;left:0;top:0;width:100%;height:100%}" +
            "#s svg{width:100%;height:100%;display:block}" +
            "#s.flip{transform:scaleX(-1)}</style></head><body><div id=\"s\"></div><script>" +
            "function setSvg(t,f){var s=document.getElementById('s');s.className=f?'flip':'';s.innerHTML=t;}" +
            "function look(x,y){" +
            "var e=document.getElementById('eyes-js'),b=document.getElementById('body-js'),h=document.getElementById('shadow-js');" +
            "if(e)e.style.transform='translate('+(x*1.4)+'px,'+(y*0.9)+'px)';" +
            "if(b)b.style.transform='translate('+(x*0.5)+'px,'+(y*0.3)+'px)';" +
            "if(h)h.style.transform='translate('+(x*0.8)+'px,0px)';}" +
            "</script></body></html>"
    }
}
