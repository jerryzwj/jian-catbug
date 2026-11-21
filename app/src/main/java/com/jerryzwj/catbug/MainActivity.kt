package com.jerryzwj.catbug

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var game: GameView
    private lateinit var prefs: Prefs
    private lateinit var sfx: Sfx
    private var panel: SettingsPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        sfx = Sfx(this).also { runCatching { it.init() } }

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        game = GameView(this).apply {
            this.prefs = this@MainActivity.prefs
            this.sfx = this@MainActivity.sfx
            this.onPauseRequest = { showPanel() }
        }
        root.addView(game, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        game.running = panel == null
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        game.running = false
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("猫在玩的时候更容易误触返回键")
    override fun onBackPressed() {
        if (panel == null) showPanel() else closePanel()
    }

    private fun enterImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        // 两套 API 叠加设置，确保不同 ROM 都能真正全屏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            controller.hide(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showPanel() {
        if (panel != null) return
        game.running = false

        val p = SettingsPanel(
            this,
            prefs,
            onClose = { closePanel() },
            onQuit = { finish() },
            onReset = { game.resetGame() }
        )
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(p, lp)
        p.alpha = 0f
        p.animate().alpha(1f).setDuration(180).start()
        panel = p
        hideSystemBars()
    }

    private fun closePanel() {
        val p = panel ?: return
        panel = null
        p.animate().alpha(0f).setDuration(150).withEndAction {
            root.removeView(p)
        }.start()
        game.running = true
        hideSystemBars()
    }
}
