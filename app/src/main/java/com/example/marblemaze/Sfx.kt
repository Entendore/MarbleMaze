package com.example.marblemaze

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Procedurally synthesised SFX via short-lived static AudioTracks — zero assets. */
object Sfx {
    var enabled = true
    private const val RATE = 22050; private const val VOL = 0.55
    private val cache = HashMap<String, ShortArray>()
    private val voices = AtomicInteger(0)

    fun bounce(intensity: Float = 0.5f) {
        val lvl = (intensity.coerceIn(0f, 1f) * 4).toInt()
        play(get("b$lvl") {
            val f0 = 260.0 + lvl * 90; val dur = 0.05
            pcm((RATE * dur).toInt()) { i -> val t = i / RATE.toDouble()
                sin(2 * PI * (f0 + (f0 * 0.7 - f0) * (t / dur)) * t) * exp(-30 * t) }
        })
    }
    fun coin() = play(get("coin") {
        val a = RATE * 60 / 1000; var ph = 0.0
        pcm(a + RATE * 180 / 1000) { i ->
            if (i < a) { ph += 2 * PI * 988.0 / RATE; sin(ph) * exp(-8 * i / RATE.toDouble()) }
            else { val t2 = (i - a) / RATE.toDouble(); ph += 2 * PI * 1319.0 / RATE; sin(ph) * exp(-7 * t2) * 0.95 } }
    })
    fun bank() = play(get("bank") { pcm(RATE / 4) { i -> val t = i / RATE.toDouble()
        (sin(2 * PI * 659.26 * t) + sin(2 * PI * 987.77 * t)) * 0.5 * exp(-9 * t) } })
    fun trapped() = play(get("trap") { val dur = 0.30; pcm((RATE * dur).toInt()) { i -> val t = i / RATE.toDouble()
        sin(2 * PI * (300.0 + (80.0 - 300.0) * (t / dur)) * t) * exp(-6 * t) } })
    fun win() = play(get("win") {
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50); val seg = RATE * 120 / 1000
        var ph = 0.0; var lf = notes[0]
        pcm(seg * notes.size) { i ->
            val f = notes[(i / seg).coerceAtMost(notes.size - 1)]
            if (f != lf) { ph = 0.0; lf = f }
            val t = (i % seg) / RATE.toDouble(); ph += 2 * PI * f / RATE; sin(ph) * exp(-4 * t) }
    })
    fun key() = play(get("key") { pcm(RATE * 120 / 1000) { i -> val t = i / RATE.toDouble(); sin(2 * PI * 1318.5 * t) * exp(-14 * t) } })
    fun unlock() = play(get("unlock") { pcm(RATE * 320 / 1000) { i -> val t = i / RATE.toDouble()
        (sin(2 * PI * 392.0 * t) + sin(2 * PI * 493.88 * t) + sin(2 * PI * 587.33 * t)) / 3 * exp(-6 * t) } })
    fun boost() = play(get("boost") { val dur = 0.14; pcm((RATE * dur).toInt()) { i -> val t = i / RATE.toDouble()
        sin(2 * PI * (220.0 + (900.0 - 220.0) * (t / dur)) * t) * exp(-5 * t) } })
    fun portal() = play(get("portal") { val dur = 0.16; pcm((RATE * dur).toInt()) { i -> val t = i / RATE.toDouble()
        sin(2 * PI * (1250.0 + (350.0 - 1250.0) * (t / dur)) * t) * exp(-6 * t) } })
    fun tick() = play(get("tick") { pcm(RATE * 60 / 1000) { i -> val t = i / RATE.toDouble(); sin(2 * PI * 880.0 * t) * exp(-25 * t) } })
    fun go() = play(get("go") { pcm(RATE * 220 / 1000) { i -> val t = i / RATE.toDouble(); sin(2 * PI * 1318.5 * t) * exp(-8 * t) } })
    fun crack() = play(get("crack") {
        var lfsr = 987654321L; val n = (RATE * 0.12).toInt()
        pcm(n) { i -> val t = i / RATE.toDouble(); lfsr = lfsr * 1103515245L + 12345L
            val noise = (((lfsr shr 16) and 0x7fffL) / 32767.0) - 0.5
            ((noise * 1.2) * 0.6 + sin(2 * PI * (500.0 - 2600.0 * t) * t) * 0.4) * exp(-24 * t) }
    })
    fun release() { try { } catch (_: Exception) {} }

    private fun pcm(n: Int, gen: (Int) -> Double): ShortArray = ShortArray(n) { i ->
        val s = gen(i).coerceIn(-1.0, 1.0); (s * Short.MAX_VALUE * VOL).toInt().toShort() }
    private fun get(key: String, build: () -> ShortArray): ShortArray = cache.getOrPut(key, build)
    private fun play(buf: ShortArray) {
        if (!enabled || voices.get() >= 8) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2).build()
            track.write(buf, 0, buf.size)
            track.setNotificationMarkerPosition(buf.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { voices.decrementAndGet(); try { t?.release() } catch (_: Exception) {} }
                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            })
            voices.incrementAndGet(); track.play()
        } catch (_: Exception) {}
    }
}
