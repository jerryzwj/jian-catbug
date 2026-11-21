package com.jerryzwj.catbug

import android.content.Context

/**
 * 少量本地设置，存 SharedPreferences。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("catbug_prefs", Context.MODE_PRIVATE)

    /** 0=慢 1=中 2=快 */
    var speed: Int
        get() = sp.getInt("speed", 1)
        set(value) = sp.edit().putInt("speed", value).apply()

    /** 同屏最多虫子数量 1..3 */
    var maxBugs: Int
        get() = sp.getInt("max_bugs", 2).coerceIn(1, 3)
        set(value) = sp.edit().putInt("max_bugs", value.coerceIn(1, 3)).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(value) = sp.edit().putBoolean("sound", value).apply()

    var showScore: Boolean
        get() = sp.getBoolean("show_score", true)
        set(value) = sp.edit().putBoolean("show_score", value).apply()

    /** 暖白底，夜里不刺眼 */
    var warmBg: Boolean
        get() = sp.getBoolean("warm_bg", false)
        set(value) = sp.edit().putBoolean("warm_bg", value).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(value) = sp.edit().putBoolean("haptics", value).apply()

    fun speedMul(): Float = when (speed) {
        0 -> 0.62f
        2 -> 1.55f
        else -> 1f
    }
}
