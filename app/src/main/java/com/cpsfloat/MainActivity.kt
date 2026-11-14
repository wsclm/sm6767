package com.cpsfloat

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {

    private var cur = 0   // 0、1 = 窗口 1/2，2 = 桌宠
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var permBox: LinearLayout
    private var regionValue: TextView? = null
    private var demoView: PetView? = null
    private var shownEnabled = false
    private var shownPetEnabled = false
    private var shownMini = 0
    private var shownDnd = false

    private val REQ_FONT = 21
    private val REQ_SHIZUKU = 22
    private var pendingFontSlot = 0
    private var pendingDone: (() -> Unit)? = null

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread {
            buildPerm()
            syncCps()
        }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { buildPerm() } }

    // 区域悬浮窗点“完成”后刷新区域文字；通知栏“停止”后同步开关状态
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            if (key != null) {
                if (cur < SLOTS) {
                    if (key.startsWith("s${cur}_r")) regionValue?.text = regionText(Cfg.load(this, cur))
                    if (key == "s${cur}_enabled" && Cfg.load(this, cur).enabled != shownEnabled) buildSlot()
                } else if (key == "pet_enabled" || key == "pet_mini" || key == "pet_dnd") {
                    val pc = Cfg.loadPet(this)
                    if (pc.enabled != shownPetEnabled || pc.miniSide != shownMini || pc.dnd != shownDnd) buildPet()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        buildRoot()

        Cfg.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        try {
            Shizuku.addRequestPermissionResultListener(shizukuResult)
            Shizuku.addBinderReceivedListenerSticky(binderListener)
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        buildPerm()
        if (cur >= SLOTS && ::content.isInitialized) buildPet()   // 从无障碍设置回来后刷新状态
    }

    override fun onDestroy() {
        demoView?.destroy()
        demoView = null
        Cfg.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuResult)
            Shizuku.removeBinderReceivedListener(binderListener)
        } catch (_: Throwable) { }
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** CPS 悬浮窗和桌宠是两个互相独立的服务。 */
    private fun syncCps() {
        if (Cfg.anyEnabled(this) && Settings.canDrawOverlays(this)) FloatService.sync(this)
    }

    private fun syncPet() {
        if (Cfg.loadPet(this).enabled && Settings.canDrawOverlays(this)) PetService.sync(this)
    }

    private fun anySync() {
        syncCps()
        syncPet()
    }

    /** 二次确认对话框（用于一键重置） */
    private fun confirm(title: String, msg: String, okText: String, onOk: () -> Unit) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
        }
        box.addView(Ui.title(this, title))
        box.addView(Ui.sub(this, msg).apply { setPadding(0, dp(10), 0, 0) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Ui.button(this, "取消", false) { dlg.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        btns.addView(Ui.button(this, okText) { dlg.dismiss(); onOk() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        box.addView(btns, lp(-1, -2, 18, this))
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Pal.CARD); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Pal.LINE)
            })
            setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    // ---------------- 整体页面 ----------------
    private fun buildRoot() {
        val keep = if (::scroll.isInitialized) scroll.scrollY else 0
        scroll = ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(40))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "CPS 悬浮窗"; setTextColor(Pal.TEXT); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, 0)
        })
        root.addView(Ui.sub(this, "所有设置修改后即时生效").apply { setPadding(dp(4), dp(2), 0, dp(14)) })

        permBox = Ui.card(this)
        root.addView(permBox, lp(-1, -2))

        root.addView(Ui.segmented(this, listOf("窗口 1", "窗口 2", "桌宠"), cur) { cur = it; buildTab() },
            lp(-1, -2, 14, this))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, lp(-1, -2, 14, this))

        setContentView(scroll)
        buildPerm()
        buildTab()
        if (keep > 0) scroll.post { scroll.scrollTo(0, keep) }
    }

    private fun buildTab() {
        if (cur < SLOTS) buildSlot() else buildPet()
    }

    // ---------------- 权限卡片 ----------------
    private fun statusRow(name: String, status: String, ok: Boolean, action: String?, onClick: (() -> Unit)?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.label(this, name))
        col.addView(TextView(this).apply {
            text = status; textSize = 12.5f; setTextColor(if (ok) Pal.OK else Pal.SUB)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onClick != null) row.addView(Ui.button(this, action, false) { onClick() })
        return row
    }

    private fun buildPerm() {
        if (!::permBox.isInitialized) return
        permBox.removeAllViews()
        val canDraw = Settings.canDrawOverlays(this)
        permBox.addView(Ui.title(this, "权限"))
        permBox.addView(Ui.divider(this))
        permBox.addView(statusRow("悬浮窗权限", if (canDraw) "已授权" else "未授权", canDraw,
            if (canDraw) null else "去授权") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (!canDraw && Sh.ready()) {
            permBox.addView(Ui.button(this, "用 Shizuku 授予悬浮窗权限", true) {
                Thread {
                    try {
                        Sh.run("appops set $packageName SYSTEM_ALERT_WINDOW allow")
                        runOnUiThread { buildPerm(); toast("已通过 Shizuku 授权") }
                    } catch (e: Throwable) {
                        runOnUiThread { toast("失败: ${e.message}") }
                    }
                }.start()
            }, lp(-1, -2, 8, this))
        }
        permBox.addView(Ui.divider(this))
        val alive = Sh.alive()
        val ready = Sh.ready()
        permBox.addView(statusRow(
            "Shizuku（全屏/区域统计需要）",
            when {
                ready -> "已授权"
                alive -> "未授权"
                else -> "未运行，请先启动 Shizuku 应用"
            },
            ready, if (alive && !ready) "申请授权" else null
        ) {
            try { Shizuku.requestPermission(REQ_SHIZUKU) } catch (e: Throwable) { toast("Shizuku 不可用: ${e.message}") }
        })
    }

    private fun regionText(c: SlotCfg): String =
        if (c.hasRegion) "${c.rw} × ${c.rh}  @ (${c.rx}, ${c.ry})" else "未设置"

    // ---------------- 窗口设置 ----------------
    private fun buildSlot() {
        demoView?.destroy()
        demoView = null
        content.removeAllViews()
        regionValue = null
        val i = cur
        val c = Cfg.load(this, i)
        shownEnabled = c.enabled

        // 启用 + 模式
        val a = Ui.card(this)
        a.addView(Ui.switchRow(this, "启用窗口 ${i + 1}", "开启后立即显示 CPS 悬浮窗", c.enabled) { on ->
            if (on && !Settings.canDrawOverlays(this)) {
                toast("请先授予悬浮窗权限")
                buildSlot()
                return@switchRow
            }
            shownEnabled = on
            Cfg.putBool(this, i, "enabled", on)
            if (on) syncCps()
        })
        a.addView(Ui.divider(this))
        a.addView(Ui.label(this, "统计方式"))
        a.addView(Ui.segmented(this, listOf("全屏", "指定区域", "区域覆盖"), c.mode) { m ->
            Cfg.putInt(this, i, "mode", m)
            buildSlot()
            if (m != Modes.FULL && !Cfg.load(this, i).hasRegion) pickRegion(i)
            syncCps()
        }, lp(-1, -2, 8, this))
        a.addView(Ui.sub(this, when (c.mode) {
            Modes.FULL -> "统计整个屏幕的点击（需要 Shizuku）"
            Modes.REGION -> "只统计所选区域内的点击，点击仍会传给下层应用（需要 Shizuku）"
            else -> "在区域上盖一层透明窗口统计，该区域点击会被拦截（无需 Shizuku）"
        }).apply { setPadding(0, dp(8), 0, 0) })
        if (c.mode != Modes.FULL) {
            a.addView(Ui.divider(this))
            a.addView(Ui.navRow(this, "点击区域", regionText(c), { regionValue = it }) { pickRegion(i) })
            a.addView(Ui.sub(this, "点击后会弹出悬浮窗：拖动移动、拖角点缩放，点“完成”即自动保存（横屏、竖屏各存一份）"))
        }
        content.addView(a, lp(-1, -2))

        // 外观 + 预览
        val pv = Ui.card(this)
        pv.addView(Ui.title(this, "外观"))
        val preview = CpsView(this)
        val previewBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(this@MainActivity, 0xFF6B6A66.toInt(), 14)
            setPadding(dp(8), dp(18), dp(8), dp(18))
            addView(preview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        pv.addView(previewBox, lp(-1, -2, 10, this))
        fun refreshPreview() {
            val x = Cfg.load(this, i)
            preview.configure(x)
            preview.text = if (x.hideText) "" else x.template.replace("{cps}", "12")
            // 太大时整体等比缩小以放进预览框（只影响预览，不影响悬浮窗）
            preview.fit = 1f
            val cs = preview.measureContent()
            val availW = resources.displayMetrics.widthPixels - dp(84)
            val availH = dp(170)
            preview.fit = minOf(1f, availW.toFloat() / cs[0], availH.toFloat() / cs[1])
            preview.requestLayout()
            preview.invalidate()
        }
        refreshPreview()

        pv.addView(Ui.divider(this))
        pv.addView(Ui.label(this, "显示文本"))
        val et = EditText(this).apply {
            setText(c.template)
            isSingleLine = true
            setTextColor(Pal.TEXT); textSize = 15f
            background = rounded(this@MainActivity, Pal.FIELD, 10)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Cfg.putStr(this@MainActivity, i, "template", s.toString())
                    refreshPreview()
                }
            })
        }
        pv.addView(et, lp(-1, -2, 6, this))
        pv.addView(Ui.sub(this, "{cps} 会替换为数值").apply { setPadding(0, dp(4), 0, 0) })

        pv.addView(Ui.divider(this))
        pv.addView(Ui.navRow(this, "字体", Fonts.label(c.font)) { fontDialog(i) { buildSlot() } })
        pv.addView(Ui.switchRow(this, "粗体", null, c.bold) {
            Cfg.putBool(this, i, "bold", it)
            refreshPreview()
        })

        pv.addView(Ui.divider(this))
        pv.addView(Ui.sliderRow(this, "字号", "sp", 8, 120, 300, c.sizeSp) {
            Cfg.putInt(this, i, "size", it)
            refreshPreview()
        })
        pv.addView(Ui.sliderRow(this, "背景宽度", "dp", 0, 400, 1000, c.bgW) {
            Cfg.putInt(this, i, "bgW", it)
            refreshPreview()
        }, lp(-1, -2, 12, this))
        pv.addView(Ui.sliderRow(this, "背景高度", "dp", 0, 200, 600, c.bgH) {
            Cfg.putInt(this, i, "bgH", it)
            refreshPreview()
        }, lp(-1, -2, 12, this))
        pv.addView(Ui.sub(this, "背景宽/高为 0 时自适应文字大小；背景比文字小时文字仍完整显示在背景上方").apply { setPadding(0, dp(2), 0, 0) })
        pv.addView(Ui.sliderRow(this, "圆角", "dp", 0, 40, 100, c.radiusDp) {
            Cfg.putInt(this, i, "radius", it)
            refreshPreview()
        }, lp(-1, -2, 12, this))
        pv.addView(Ui.sliderRow(this, "整体缩放", "%", 25, 300, 500, c.scalePct) {
            Cfg.putInt(this, i, "scale", it)
            refreshPreview()
        }, lp(-1, -2, 12, this))
        pv.addView(Ui.sub(this, "整体缩放会把背景、文字、圆角一起按比例缩放；背景宽/高、整体缩放都以显示窗口中心为基点").apply {
            setPadding(0, dp(2), 0, 0)
        })

        pv.addView(Ui.divider(this))
        pv.addView(Ui.colorRow(this, "文字颜色", c.textColor, Cfg.DEF_TEXT_COLOR) {
            Cfg.putInt(this, i, "textColor", it)
            refreshPreview()
        })
        pv.addView(Ui.colorRow(this, "背景颜色", c.bgColor, Cfg.DEF_BG_COLOR) {
            Cfg.putInt(this, i, "bgColor", it)
            refreshPreview()
        })
        content.addView(pv, lp(-1, -2, 12, this))

        // 行为
        val b = Ui.card(this)
        b.addView(Ui.title(this, "行为"))
        b.addView(Ui.divider(this))
        b.addView(Ui.switchRow(this, "隐藏文字", "只显示背景", c.hideText) {
            Cfg.putBool(this, i, "hideText", it)
            refreshPreview()
        })
        b.addView(Ui.switchRow(this, "未点击时只显示背景", null, c.idleBgOnly) { Cfg.putBool(this, i, "idleBgOnly", it) },
            lp(-1, -2, 12, this))
        b.addView(Ui.switchRow(this, "锁定位置", "锁定后无法拖动，且触摸可穿透显示窗口", c.locked) { Cfg.putBool(this, i, "locked", it) },
            lp(-1, -2, 12, this))
        b.addView(Ui.divider(this))
        b.addView(Ui.sub(this, "拖动悬浮窗即可移动位置，位置会自动保存"))
        b.addView(Ui.button(this, "重置位置", false) {
            val dp0 = Cfg.defPos(i)
            Cfg.putPos(this, i, dp0[0], dp0[1])
            toast("已重置当前方向的位置")
        }, lp(-1, -2, 10, this))
        content.addView(b, lp(-1, -2, 12, this))

        content.addView(Ui.button(this, "一键重置本窗口的所有设置", false) {
            confirm("重置窗口 ${i + 1}", "文字、颜色、背景大小、整体缩放、位置、统计方式和点击区域都会恢复为默认值（是否启用保持不变）。", "重置") {
                Cfg.resetSlot(this, i)
                buildSlot()
                toast("已重置窗口 ${i + 1}")
            }
        }, lp(-1, -2, 14, this))
        content.addView(Ui.button(this, "停止全部悬浮窗", false) { stopAll() }, lp(-1, -2, 10, this))
    }

    private fun stopAll() {
        for (k in 0 until SLOTS) Cfg.putBool(this, k, "enabled", false)
        Cfg.putPetBool(this, "enabled", false)
        buildTab()
    }

    private fun pickRegion(slot: Int) {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            return
        }
        // 不退出软件：区域悬浮窗直接显示在本界面之上
        FloatService.pick(this, slot)
    }

    // ---------------- 桌宠设置 ----------------
    private fun senseEnabled(): Boolean {
        val v = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return v.contains(packageName) && v.contains("SenseService")
    }

    /** 借助 Shizuku 直接把“桌宠环境感知”无障碍服务打开。 */
    private fun enableSenseViaShizuku() {
        Thread {
            try {
                val comp = "$packageName/${SenseService::class.java.name}"
                val old = Sh.run("settings get secure enabled_accessibility_services").trim()
                val list = when {
                    old.isEmpty() || old == "null" -> comp
                    old.contains("SenseService") -> old
                    else -> "$old:$comp"
                }
                Sh.run("settings put secure enabled_accessibility_services $list")
                Sh.run("settings put secure accessibility_enabled 1")
                runOnUiThread {
                    toast("已开启环境感知")
                    if (cur >= SLOTS) buildPet()
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("失败: ${e.message}") }
            }
        }.start()
    }

    private fun buildPet() {
        demoView?.destroy()
        demoView = null
        content.removeAllViews()
        regionValue = null
        val p = Cfg.loadPet(this)
        shownPetEnabled = p.enabled
        shownMini = p.miniSide
        shownDnd = p.dnd

        // ---- 开关 + 动画预览 ----
        val a = Ui.card(this)
        a.addView(Ui.switchRow(this, "Clawd 桌宠", "使用 Clawd on Desk 的原版像素螃蟹动画，根据手机状态自动变化（与 CPS 悬浮窗无关）", p.enabled) { on ->
            if (on && !Settings.canDrawOverlays(this)) {
                toast("请先授予悬浮窗权限")
                buildPet()
                return@switchRow
            }
            shownPetEnabled = on
            Cfg.putPetBool(this, "enabled", on)
            if (on) syncPet()
        })
        a.addView(Ui.divider(this))
        if (webViewAvailable()) {
            val demo = PetView(this)
            demoView = demo
            demo.alpha = p.alphaPct / 100f
            demo.show("idle-follow")
            val box = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                background = rounded(this@MainActivity, Pal.FIELD, 14)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            box.addView(demo, LinearLayout.LayoutParams(dp(220), dp(220)))
            a.addView(box, lp(-1, -2))
            val nameView = Ui.sub(this, "点下面任意动画即可预览，并显示它的触发条件（共 ${Clips.ALL.size} 个）").apply { setPadding(0, dp(10), 0, dp(4)) }
            a.addView(nameView)
            for (g in Clips.groups()) {
                a.addView(Ui.label(this, g), lp(-1, -2, 10, this))
                val list = Clips.ALL.filter { it.group == g }
                var idx = 0
                while (idx < list.size) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for (k in 0 until 3) {
                        val item = list.getOrNull(idx + k)
                        val chip = TextView(this).apply {
                            gravity = Gravity.CENTER
                            textSize = 12.5f
                            setSingleLine(true)
                            if (item != null) {
                                text = item.label
                                setTextColor(Pal.TEXT)
                                background = rounded(this@MainActivity, Pal.FIELD, 10)
                                setOnClickListener {
                                    demo.show(item.id, false, true)
                                    nameView.text = "正在预览：${item.label}（clawd-${item.id}.svg）\n触发条件：${Clips.trigger(item.id)}"
                                }
                            }
                            setPadding(dp(4), dp(9), dp(4), dp(9))
                        }
                        row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (k > 0) leftMargin = dp(6)
                        })
                    }
                    a.addView(row, lp(-1, -2, 6, this))
                    idx += 3
                }
            }
        } else {
            a.addView(Ui.sub(this, "系统 WebView 不可用，无法预览或显示桌宠动画。请在系统设置里启用 / 更新“Android System WebView”。").apply {
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        content.addView(a, lp(-1, -2))

        // ---- 环境感知（无障碍） ----
        val sense = Ui.card(this)
        sense.addView(Ui.title(this, "环境感知"))
        sense.addView(Ui.divider(this))
        val on = senseEnabled()
        sense.addView(statusRow(
            "桌宠环境感知（无障碍服务）",
            if (on) "已开启：键盘 / 前台应用 / 通知 / 打字 / 操作 都能感知" else "未开启：键盘、打开应用、通知、打字、无操作睡觉这几项不会触发",
            on, null, null
        ))
        sense.addView(Ui.sub(this, "只用来判断键盘是否弹出、当前打开的是哪个应用、是否有新通知和是否在打字，不读取、不保存、不上传任何屏幕或输入内容。" +
            "Android 13+ 侧载安装的应用需要先在“应用信息 → 右上角 ⋮ → 允许受限制的设置”后才能开启。").apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        if (!on && Sh.ready()) {
            sense.addView(Ui.button(this, "用 Shizuku 一键开启", true) { enableSenseViaShizuku() }, lp(-1, -2, 10, this))
        }
        sense.addView(Ui.button(this, "打开系统无障碍设置", false) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开无障碍设置")
            }
        }, lp(-1, -2, 10, this))
        content.addView(sense, lp(-1, -2, 12, this))

        // ---- 触发条件 ----
        val trig = Ui.card(this)
        trig.addView(Ui.title(this, "触发条件"))
        trig.addView(Ui.sub(this, "每一项可以单独开关。桌宠只响应手机自身的状态，不会响应 CPS 的数值。").apply { setPadding(0, dp(2), 0, dp(2)) })
        trig.addView(Ui.divider(this))
        for ((n, t) in PetTrig.ALL.withIndex()) {
            trig.addView(Ui.switchRow(this, t.label, t.desc, t.key !in p.off) { v ->
                Cfg.setTrigger(this, t.key, v)
            }, lp(-1, -2, if (n == 0) 0 else 12, this))
        }
        content.addView(trig, lp(-1, -2, 12, this))

        // ---- 行为 ----
        val b = Ui.card(this)
        b.addView(Ui.title(this, "行为"))
        b.addView(Ui.divider(this))
        b.addView(Ui.switchRow(this, "随机走动", "没事做的时候横着走一走（用“横着走”动画）；关闭后停在原地", p.wander) {
            Cfg.putPetBool(this, "wander", it)
        })
        b.addView(Ui.switchRow(this, "眼睛跟随", "待机时眼睛和身体朝手机倾斜的方向看", p.follow) {
            Cfg.putPetBool(this, "follow", it)
        }, lp(-1, -2, 12, this))
        b.addView(Ui.switchRow(this, "偶尔做待机小动作", "悠闲、东张西望、看书、吹泡泡、喝咖啡、扫地、巫师……", p.idleVariants) {
            Cfg.putPetBool(this, "variants", it)
        }, lp(-1, -2, 12, this))
        b.addView(Ui.divider(this))
        b.addView(Ui.switchRow(this, "迷你模式", "缩到屏幕边缘只露出一半，点一下探头；长按螃蟹也能切换", p.miniSide != 0) {
            Cfg.putPetInt(this, "mini", if (it) 2 else 0)
        })
        b.addView(Ui.switchRow(this, "拖到屏幕边缘自动进入迷你模式", null, p.miniEdge) {
            Cfg.putPetBool(this, "miniedge", it)
        }, lp(-1, -2, 12, this))
        b.addView(Ui.switchRow(this, "勿扰模式", "一直睡觉（仍可拖动、点它）", p.dnd) {
            Cfg.putPetBool(this, "dnd", it)
        }, lp(-1, -2, 12, this))
        b.addView(Ui.divider(this))
        b.addView(Ui.sliderRow(this, "无操作多久后睡觉", "秒", 10, 600, 7200, p.sleepAfter) { Cfg.putPetInt(this, "sleep", it) })
        b.addView(Ui.sub(this, "睡觉过程：打哈欠 → 打盹 → 趴下 → 熟睡；需要开启环境感知，有任何操作都会醒来").apply {
            setPadding(0, dp(6), 0, 0)
        })
        b.addView(Ui.sub(this, "点螃蟹：1 下看向点击的一侧 · 2 下双击反应 · 3 下惊跳 · 4 下烦躁；长按弹出菜单；拖动可移动").apply {
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(b, lp(-1, -2, 12, this))

        // ---- 外观与移动 ----
        val m = Ui.card(this)
        m.addView(Ui.title(this, "外观与移动"))
        m.addView(Ui.divider(this))
        m.addView(Ui.sliderRow(this, "大小（整个动画画面）", "dp", 80, 400, 800, p.sizeDp) { Cfg.putPetInt(this, "size", it) })
        m.addView(Ui.sliderRow(this, "整体透明度（不透明度）", "%", 10, 100, 100, p.alphaPct) {
            Cfg.putPetInt(this, "alpha", it)
            demoView?.alpha = it / 100f
        }, lp(-1, -2, 12, this))
        m.addView(Ui.sliderRow(this, "走动速度", "dp/秒", 10, 200, 600, p.speedDp) { Cfg.putPetInt(this, "speed", it) },
            lp(-1, -2, 12, this))
        m.addView(Ui.divider(this))
        m.addView(Ui.label(this, "每次走动多久（在最短～最长之间随机）"))
        m.addView(Ui.sliderRow(this, "走动最短", "秒", 1, 30, 600, p.moveMin) { Cfg.putPetInt(this, "mmin", it) },
            lp(-1, -2, 8, this))
        m.addView(Ui.sliderRow(this, "走动最长", "秒", 1, 30, 600, p.moveMax) { Cfg.putPetInt(this, "mmax", it) },
            lp(-1, -2, 12, this))
        m.addView(Ui.divider(this))
        m.addView(Ui.label(this, "每次停多久（在最短～最长之间随机）"))
        m.addView(Ui.sliderRow(this, "停止最短", "秒", 1, 60, 600, p.pauseMin) { Cfg.putPetInt(this, "pmin", it) },
            lp(-1, -2, 8, this))
        m.addView(Ui.sliderRow(this, "停止最长", "秒", 1, 60, 600, p.pauseMax) { Cfg.putPetInt(this, "pmax", it) },
            lp(-1, -2, 12, this))
        m.addView(Ui.sub(this, "只会左右横着走，碰到屏幕边缘会掉头；最短大于最长时自动对调").apply {
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(m, lp(-1, -2, 12, this))

        content.addView(Ui.button(this, "一键重置桌宠的所有设置", false) {
            confirm("重置桌宠", "大小、走动、行为开关、触发条件、睡觉时间和位置都会恢复为默认值（是否启用保持不变）。", "重置") {
                Cfg.resetPet(this)
                buildPet()
                toast("已重置桌宠设置")
            }
        }, lp(-1, -2, 14, this))
        content.addView(Ui.button(this, "停止全部悬浮窗", false) { stopAll() }, lp(-1, -2, 10, this))
    }

    // ---------------- 字体 ----------------
    private fun fontDialog(slot: Int, done: () -> Unit) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val cfg = Cfg.load(this, slot)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        box.addView(Ui.title(this, "选择字体"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((name, key) in Fonts.presets) {
            val sel = cfg.font == key
            list.addView(TextView(this).apply {
                text = "$name   12 CPS"
                textSize = 18f
                typeface = Fonts.typeface(key, cfg.bold)
                setTextColor(if (sel) Pal.ACCENT else Pal.TEXT)
                background = if (sel) rounded(this@MainActivity, Pal.ACCENT_SOFT, 12) else null
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    Cfg.putStr(this@MainActivity, slot, "font", key)
                    dlg.dismiss()
                    done()
                }
            })
        }
        val sc = ScrollView(this).apply { addView(list) }
        box.addView(sc, lp(-1, dp(340), 10, this))
        box.addView(Ui.button(this, "重置为默认字体", false) {
            Cfg.putStr(this@MainActivity, slot, "font", Cfg.DEF_FONT)
            dlg.dismiss()
            done()
        }, lp(-1, -2, 10, this))
        box.addView(Ui.button(this, "导入字体文件 (.ttf / .otf)", false) {
            val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            pendingFontSlot = slot
            pendingDone = done
            dlg.dismiss()
            startActivityForResult(pickIntent, REQ_FONT)
        }, lp(-1, -2, 10, this))
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Pal.CARD); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Pal.LINE)
            })
            setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FONT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val dir = File(filesDir, "fonts").apply { mkdirs() }
            val f = File(dir, "font_${pendingFontSlot}_${System.currentTimeMillis()}.ttf")
            contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
            android.graphics.Typeface.createFromFile(f) // 校验字体是否有效
            dir.listFiles()?.filter { it.name.startsWith("font_${pendingFontSlot}_") && it != f }?.forEach { it.delete() }
            Cfg.putStr(this, pendingFontSlot, "font", "file:${f.absolutePath}")
            pendingDone?.invoke()
        } catch (e: Throwable) {
            toast("字体文件无效")
        }
    }
}
