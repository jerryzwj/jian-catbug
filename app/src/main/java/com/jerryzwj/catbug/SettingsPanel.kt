package com.jerryzwj.catbug

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 暂停 / 设置面板。游戏中三指长按 1.5 秒呼出，避免猫爪误触。
 */
class SettingsPanel(
    context: Context,
    private val prefs: Prefs,
    private val onClose: () -> Unit,
    private val onQuit: () -> Unit,
    private val onReset: () -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context)

    private val green = Color.parseColor("#2F7D54")
    private val ink = Color.parseColor("#1B1B1B")
    private val sub = Color.parseColor("#7A7A7A")
    private val line = Color.parseColor("#E3E3DE")

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F4F4F1"))

        val scroll = ScrollView(context)
        scroll.isFillViewport = true

        val wrapper = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        val widthDp = resources.displayMetrics.widthPixels / density
        val cardWidth = (minOf(640f, widthDp - 40f) * density).toInt()

        content.orientation = VERTICAL
        content.setPadding(dp(36), dp(32), dp(36), dp(36))
        content.background = rounded(Color.WHITE, dp(22))
        content.elevation = dp(2).toFloat()

        wrapper.addView(content, ViewGroup.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        rebuild()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun rebuild() {
        content.removeAllViews()

        // 标题
        content.addView(text("简·猫抓虫", 26f, ink, bold = true))
        content.addView(text(
            "三根手指按住屏幕 1.5 秒，随时可以回到这里",
            13f, sub, top = 6
        ))

        content.addView(space(24))

        // 继续玩
        content.addView(
            button("继续玩", primary = true) { onClose() }
        )

        content.addView(space(18))

        section("虫子速度", listOf("慢", "中", "快"), prefs.speed) { prefs.speed = it; rebuild() }
        section("同时出现的虫子", listOf("1 只", "2 只", "3 只"), prefs.maxBugs - 1) {
            prefs.maxBugs = it + 1
            rebuild()
        }
        toggle("抓到时播放咔嚓音效", prefs.sound) { prefs.sound = it; rebuild() }
        toggle("角落显示分数", prefs.showScore) { prefs.showScore = it; rebuild() }
        toggle("暖白底色（夜里不刺眼）", prefs.warmBg) { prefs.warmBg = it; rebuild() }
        toggle("震动反馈", prefs.haptics) { prefs.haptics = it; rebuild() }

        content.addView(space(10))
        content.addView(lineView())

        content.addView(space(10))
        content.addView(button("重新开一局") { onReset(); onClose() })
        content.addView(space(10))
        content.addView(button("检查更新", primary = false) { checkUpdate() })

        content.addView(space(10))
        content.addView(button("退出游戏", primary = false) { onQuit() })

        content.addView(space(22))
        content.addView(text("版本 ${Updater.currentVersion()}", 12f, sub))
        content.addView(text("源码与更新都在 GitHub：${Updater.REPO}", 12f, sub, top = 4))
        content.addView(text("点这里打开 GitHub 页面", 12f, green, top = 8) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
        })
    }

    // ---------------------------------------------------------------- 小部件

    private fun text(
        s: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        top: Int = 0,
        onClick: (() -> Unit)? = null
    ): TextView = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(top), 0, 0)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        if (onClick != null) {
            setOnClickListener { onClick() }
            setPadding(0, dp(top + 6), 0, dp(6))
        }
    }

    private fun space(h: Int): View = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun lineView(): View = View(context).apply {
        setBackgroundColor(line)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else ink)
            isClickable = true
            background = rounded(if (primary) green else Color.WHITE, dp(16))
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

    private fun section(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        content.addView(text(title, 14f, sub, top = 18))

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = rounded(Color.parseColor("#F2F2EE"), dp(14))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        options.forEachIndexed { index, label ->
            val tv = TextView(context).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(if (index == selected) Color.WHITE else ink)
                background = rounded(if (index == selected) green else Color.TRANSPARENT, dp(10))
                setPadding(0, dp(11), 0, dp(11))
                isClickable = true
                setOnClickListener { onPick(index) }
            }
            row.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = 0
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        content.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val tv = TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(ink)
            isClickable = true
            setOnClickListener { onChange(!checked) }
        }
        row.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(context).apply {
            this.isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(sw)
        content.addView(lineView())
        content.addView(row)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (color == Color.WHITE) setStroke(dp(1), line)
        }

    // ---------------------------------------------------------------- 更新

    private fun checkUpdate() {
        Toast.makeText(context, "正在检查…", Toast.LENGTH_SHORT).show()
        Updater.checkLatest { info ->
            if (info == null) {
                Toast.makeText(context, "检查失败，网络或仓库还没有发布版本", Toast.LENGTH_LONG).show()
                return@checkLatest
            }
            if (Updater.isNewer(info.version, Updater.currentVersion())) {
                AlertDialog.Builder(context)
                    .setTitle("发现新版本 v${info.version}")
                    .setMessage(
                        "当前版本 v${Updater.currentVersion()}\n\n" +
                            (if (info.notes.isBlank()) "包含若干改进。" else info.notes.take(400))
                    )
                    .setPositiveButton("去下载") { _, _ ->
                        val url = info.apkUrl.ifEmpty { info.pageUrl }
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            } else {
                Toast.makeText(context, "已是最新版本 v${Updater.currentVersion()}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
