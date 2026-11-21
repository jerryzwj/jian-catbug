package com.jerryzwj.catbug

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 压死虫子时的"咔嚓"音效。
 * 不打包音频资源，首次启动即时合成 3 个变体 WAV，交给 SoundPool 播放（延迟低）。
 */
class Sfx(private val context: Context) {

    private var pool: SoundPool? = null
    private val ids = ArrayList<Int>()
    private var loaded = 0

    fun init() {
        val dir = File(context.cacheDir, "sfx")
        if (!dir.exists()) dir.mkdirs()

        val files = listOf(
            File(dir, "crunch1.wav"),
            File(dir, "crunch2.wav"),
            File(dir, "crunch3.wav")
        )
        if (files.any { !it.exists() || it.length() < 1000L }) {
            files.forEachIndexed { index, f ->
                runCatching { writeWav(f, crunchSamples(index), 44100) }
            }
        }

        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded++ }
        pool = p
        files.forEach { f ->
            if (f.exists()) runCatching { ids.add(p.load(f.absolutePath, 1)) }
        }
    }

    fun squish() {
        val p = pool ?: return
        if (ids.isEmpty() || loaded <= 0) return
        runCatching {
            p.play(ids[Random.nextInt(ids.size)], 1f, 1f, 0, 0, 1f)
        }
    }

    fun release() {
        runCatching { pool?.release() }
        pool = null
        ids.clear()
        loaded = 0
    }

    private fun crunchSamples(variant: Int): ShortArray {
        val rate = 44100
        val n = (rate * 0.14).toInt()
        val out = ShortArray(n)
        val rnd = Random(variant * 7717 + 13)

        // 低频"噗"一下 + 高频碎壳噪点
        var env = 1.0
        val decay = 26.0 + variant * 4.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            env = exp(-t * decay)
            val noise = rnd.nextDouble() * 2.0 - 1.0
            val thump = sin(2.0 * PI * (120.0 - variant * 15.0) * t) * 0.6
            var s = noise * 0.85 + thump
            s *= env
            // 开头 6ms 快速淡入，避免爆音
            if (i < 264) s *= i / 264.0
            val v = (s * 22000).toInt().coerceIn(-32767, 32767)
            out[i] = v.toShort()
        }
        return out
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val byteRate = sampleRate * 2
        val dataSize = samples.size * 2
        val header = ByteArray(44)
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        putInt(4, 36 + dataSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        putInt(16, 16)
        putShort(20, 1)          // PCM
        putShort(22, 1)          // mono
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, 2)          // block align
        putShort(34, 16)         // bits
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        putInt(40, dataSize)

        file.outputStream().use { os ->
            os.write(header)
            for (s in samples) {
                os.write(s.toInt() and 0xFF)
                os.write((s.toInt() shr 8) and 0xFF)
            }
        }
    }
}
