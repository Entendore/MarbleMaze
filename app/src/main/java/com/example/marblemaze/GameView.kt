package com.example.marblemaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context, private val cfg: Config) : View(context) {

    data class Config(
        val marbles: Int, val difficulty: Int, val level: Int,
        val seed: Long = System.nanoTime(), val haptics: Boolean = true,
        val daily: Boolean = false, val themeId: Int = 0, val fog: Boolean = false,
        val zen: Boolean = false, val controlMode: Int = 0, val custom: Boolean = false,
        val spectate: Boolean = false, val spectateData: FloatArray? = null,
        val opts: RunOptions? = null, val highContrast: Boolean = false
    )

    interface Listener { fun onGameWon(result: GameResult); fun onMarbleBanked(banked: Int, total: Int) {} }
    var listener: Listener? = null

    private enum class State { COUNTDOWN, PLAYING, WON }
    private enum class Surf { NORMAL, ICE, MUD, RUBBLE }
    private enum class PType { MAGNET, SHIELD, SLOW }

    private val theme = GameThemes.resolveTheme(cfg.themeId, context)
    private val isBoss = !cfg.daily && cfg.level % 10 == 0

    // ---- two independent RNG streams (v12 determinism contract) ----
    private var board = Random(cfg.seed)
    private val fx = Random(System.nanoTime())

    private var maze = Maze(1, 1)
    private var cols = 0; private var rows = 0
    private var cellSize = 0f; private var wallT = 0f
    private var boardLeft = 0f; private var boardTop = 0f
    private var boardW = 0f; private var boardH = 0f
    private var staticLayer: Bitmap? = null; private var staticPad = 0f
    private var fogBitmap: Bitmap? = null; private var fogCanvas: Canvas? = null
    private val fogClear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private var viewScale = 1f; private var panX = 0f; private var panY = 0f
    private var lastPanX = 0f; private var lastPanY = 0f; private var multiTouch = false
    private var joystickActive = false; private var joyAx = 0f; private var joyAy = 0f
    private var touchTx = 0f; private var touchTy = 0f; private var touchUsed = false
    private val scaleDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = viewScale; viewScale = (viewScale * d.scaleFactor).coerceIn(1f, Tuning.MAX_ZOOM)
            val fxp = d.focusX - (boardLeft + boardW / 2f); val fyp = d.focusY - (boardTop + boardH / 2f)
            panX += fxp * (old - viewScale); panY += fyp * (old - viewScale); clampPan(); return true
        }
    })
    private val tapDet = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { viewScale = 1f; panX = 0f; panY = 0f; return true }
    })

    // ---- entities ----
    private class Wall(val rect: RectF, val bouncy: Boolean, val crumble: Boolean, val index: Int) {
        var hits = 0; var broken = false }
    private val allWalls = mutableListOf<Wall>()
    private var cellWalls: Array<Array<MutableList<Wall>>> = arrayOf()
    private class Gate(val rect: RectF, val edge: Int) { var unlocked = false }
    private val gates = mutableListOf<Gate>(); private var cellGates: Array<Array<MutableList<Gate>>> = arrayOf()
    private class Flapper(val rect: RectF, val dx: Int, val dy: Int)
    private val flappers = mutableListOf<Flapper>(); private var cellFlappers: Array<Array<MutableList<Flapper>>> = arrayOf()
    private val belts = mutableListOf<Triple<Int, Int, Pair<Int, Int>>>()
    private var beltDirs = IntArray(0); private var windDirs = IntArray(0)
    private var windCells = mutableListOf<Triple<Int, Int, Pair<Int, Int>>>()
    private var drainBase = IntArray(0); private var drainSign = 1
    private var drainOscillate = false; private var drainActive = false
    private class Rubble(val pebbles: FloatArray)
    private val rubble = mutableListOf<Rubble>()
    private class Rock(val x: Float, val y: Float, val rad: Float)
    private val rocks = mutableListOf<Rock>()
    private class Bumper(val x: Float, val y: Float, val rad: Float) { var flash = 0f }
    private val bumpers = mutableListOf<Bumper>(); private var bumperHits = 0
    private class Mover(val cx: Float, val cy: Float, val horiz: Boolean, val amp: Float,
                        val period: Float, val phase: Float, val w: Float, val h: Float) {
        fun off(t: Float) = (amp * sin(2.0 * PI * t / period + phase)).toFloat()
        fun vel(t: Float) = (amp * 2.0 * PI / period * cos(2.0 * PI * t / period + phase)).toFloat() }
    private val movers = mutableListOf<Mover>()
    private class KeyItem(val x: Float, val y: Float, val gateIndex: Int, var taken: Boolean = false)
    private val keys = mutableListOf<KeyItem>()
    private val marbles = mutableListOf<Marble>()
    private val surface = mutableListOf<Surf>()
    private class ExitHole(val x: Float, val y: Float, val r: Float, val letter: String) { var flash = 0f }
    private val exits = mutableListOf<ExitHole>()
    private class Hole(val x: Float, val y: Float, val r: Float)
    private val traps = mutableListOf<Hole>()
    private class Pad(val cx: Int, val cy: Int, val dx: Int, val dy: Int)
    private val pads = mutableListOf<Pad>()
    private class PortalPair(val ax: Float, val ay: Float, val bx: Float, val by: Float, val r: Float)
    private var portal: PortalPair? = null
    private class Coin(var x: Float, var y: Float, var taken: Boolean = false)
    private val coins = mutableListOf<Coin>()
    private class PowerUp(val x: Float, val y: Float, val type: PType, var taken: Boolean = false)
    private val powerUps = mutableListOf<PowerUp>()
    private class Spinner(val cx: Float, val cy: Float, val halfLen: Float, val halfThick: Float,
                          val omega: Float, var angle: Float, val arms: Int = 1)
    private val spinners = mutableListOf<Spinner>()
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                           var life: Float, val maxLife: Float, var size: Float, var color: Int, var grav: Float)
    private val particles = ArrayList<Particle>(256); private val pPool = ArrayDeque<Particle>()
    private class FloatText(val text: String, var x: Float, var y: Float, var age: Float, val color: Int)
    private val floats = mutableListOf<FloatText>()
    private val occupied = mutableSetOf<Pair<Int, Int>>()
    private val usedEdges = mutableSetOf<Int>()
    private val DIRS4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private var score = 0; private var combo = 1; private var comboTimer = 0f
    private var maxCombo = 1; private var trapsHit = 0; private var distCells = 0f
    private var slowUntil = -1f
    private var bossName: String? = null

    private class Snap(val t: Long, val score: Int, val combo: Int, val comboTimer: Float,
                       val trapsHit: Int, val banked: BooleanArray, val data: FloatArray)
    private val rewindBuf = ArrayDeque<Snap>()
    private var rewinding = false; private var usedRewind = false; private var rewindStart = 0L

    private val ghostKey = if (cfg.daily || cfg.zen || cfg.custom || cfg.spectate) null
        else "${cfg.difficulty}_${cfg.level}_${cfg.marbles}"
    private var ghost: FloatArray? = null; private var ghostStride = 0; private var ghostOn = false
    private var ghostIdx = 0; private var ghostBanked = BooleanArray(0)
    private var recBuf = FloatArray(8192); private var recLen = 0; private var lastRecMs = 0L
    private var recordedStride = 3
    private var specSamples: FloatArray? = null; private var specStride = 3
    private var specIdx = 0; private var specClock = 0f; private var specRate = 1f; private var specPlaying = true
    private var pendingRestore: String? = null
    var autosaveWriter: ((String) -> Unit)? = null
    private var lastAutosaveMs = 0L

    private var txT = 0f; private var tyT = 0f; private var txX = 0f; private var tyY = 0f
    private var uprightZ = 9.81f; private var tiltPaused = false
    private var state = State.COUNTDOWN; private var countdown = 2.2f; private var lastTick = 3
    private var goFlash = 0f; private var lastFrame = 0L
    private var elapsedMs = 0L; private var bankedCount = 0; private var coinsCollected = 0
    private var now = 0L; private var animTime = 0f; private var shake = 0f
    private var shakeX = 0f; private var shakeY = 0f; private var running = false

    private val debugEnabled = BuildConfig.DEBUG
    private var frameEma = 16.6f

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (!running) return
            if (lastFrame != 0L) { var dt = (nanos - lastFrame) / 1e9f; if (dt > 0.1f) dt = 0.1f
                frameEma += (dt * 1000f - frameEma) * 0.05f; step(dt) }
            lastFrame = nanos; invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- paints ----
    private val boardP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.board }
    private val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = theme.border }
    private val bossFrameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4f); color = Color.parseColor("#99EF4444") }
    private val wallP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.wall }
    private val bouncyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bouncy }
    private val crumbleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9A87E") }
    private val crackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = dp(1.5f); color = Color.parseColor("#6B4E2E") }
    private val rubbleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A7862") }
    private val rubbleD = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6E5F4E") }
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#59000000") }
    private val startP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22FFFFFF") }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A0A0F") }
    private val letterP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val strokeLetterP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.BLACK }
    private val trapP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B91C1C") }
    private val padP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.parseColor("#38BDF8"); strokeCap = Paint.Cap.ROUND }
    private val windP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.parseColor("#FF94A3B8"); strokeCap = Paint.Cap.ROUND }
    private val portalP = Paint(Paint.ANTI_ALIAS_FLAG); private val portalArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val coinP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FACC15") }
    private val iceP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DA5D8FF") }
    private val mudP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5E4A2E14") }
    private val spinP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F87171") }
    private val spinEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FECACA") }
    private val hubP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7F1D1D") }
    private val rockP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#78716C") }
    private val bumpP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F59E0B") }
    private val bumpCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#92400E") }
    private val bumpRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.parseColor("#FDE68A") }
    private val moverP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B7280") }
    private val flapperP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#86EFAC") }
    private val flapperA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }
    private val gateP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9CA3AF") }
    private val lockP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FACC15") }
    private val keyRingP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.parseColor("#FCD34D") }
    private val beltBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#331E3A4A") }
    private val beltStr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A667E8F9"); strokeWidth = dp(2f) }
    private val powBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1A1F2E") }
    private val powRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val shieldRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.parseColor("#60A5FA") }
    private val marbleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.BLACK }
    private val marbleP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ghostP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.WHITE; pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f) }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = dp(9f) }
    private val deltaP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD; textSize = dp(13f) }
    private val joyBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.parseColor("#55FFFFFF") }
    private val joyKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88FFFFFF") }
    private val trailP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val partP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floatP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(14f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val hudP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(16f); typeface = Typeface.MONOSPACE; isFakeBoldText = true }
    private val hudR = Paint(hudP).apply { textAlign = Paint.Align.RIGHT }
    private val hudC = Paint(hudP).apply { textAlign = Paint.Align.CENTER }
    private val comboP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FACC15"); textSize = dp(13f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(64f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val overlayP = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val slowTint = Paint().apply { color = Color.parseColor("#26C084FC") }
    private val tmpRect = RectF()
    private val grads = HashMap<Int, RadialGradient>()

    // ---- lifecycle ----
    fun resume() { if (running) return; running = true; lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frameCb) }
    fun pause() { if (!running) return; running = false; Choreographer.getInstance().removeFrameCallback(frameCb) }
    fun setTilt(x: Float, y: Float) {
        if (cfg.spectate) return
        txT = if (abs(x) < 0.12f) 0f else x; tyT = if (abs(y) < 0.12f) 0f else y
    }
    fun setUpright(z: Float) { uprightZ = z }
    fun beginRewind() {
        if (cfg.spectate || state != State.PLAYING || rewinding || rewindBuf.isEmpty()) return
        rewinding = true; usedRewind = true; rewindStart = elapsedMs; Sfx.tick()
    }
    fun endRewind() {
        if (!rewinding) return; rewinding = false
        if (!cfg.zen) { val s = ((rewindStart - elapsedMs) / 1000L).coerceAtLeast(0L)
            val c = (s * Tuning.REWIND_COST_PER_SEC).toInt()
            if (c > 0) { score = (score - c).coerceAtLeast(0)
                floats.add(FloatText("-$c", boardLeft + boardW / 2f, boardTop + dp(70f), 0f, Color.parseColor("#F87171"))) } }
        lastRecMs = elapsedMs
    }
    fun spectateDuration(): Float { val s = specSamples ?: return 0f; val n = s.size / specStride
        return if (n >= 2) s[(n - 1) * specStride] else 0f }
    fun spectatePos(): Float = specClock
    fun spectateIsPlaying(): Boolean = specPlaying && state == State.PLAYING
    fun spectateTogglePlay() { specPlaying = !specPlaying; Sfx.tick() }
    fun spectateSetRate(r: Float) { specRate = r.coerceIn(0.1f, 4f); Sfx.tick() }
    fun spectateSeekTo(t: Float) {
        val s = specSamples ?: return; val n = s.size / specStride; if (n < 2) return
        val end = s[(n - 1) * specStride]; specClock = t.coerceIn(0f, end)
        if (state == State.WON && specClock < end) { state = State.PLAYING; particles.clear() }
        var lo = 0; var hi = n - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (s[mid * specStride] <= specClock) lo = mid else hi = mid - 1 }
        specIdx = lo
    }
    fun queueRestore(json: String) { pendingRestore = json }

    val isPlaying: Boolean get() = state == State.PLAYING
    fun debugElapsed(): Long = elapsedMs
    fun debugWinNow() {
        if (cfg.spectate || marbles.isEmpty()) return
        val t = SystemClock.elapsedRealtime()
        marbles.forEach { it.banked = true; it.bankTime = t }
        bankedCount = marbles.size; finishRun()
    }
    fun boardFingerprint(): String = buildString {
        append(cols).append('x').append(rows).append('|')
        for (wl in allWalls) append(wl.rect.left.toInt()).append(',').append(wl.rect.top.toInt()).append(';')
        append('|'); for (h in exits) append(h.x.toInt()).append(',').append(h.y.toInt()).append(';')
        append('|'); for (m in marbles) append(m.x.toInt()).append(',').append(m.y.toInt()).append(';')
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e); tapDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { multiTouch = false; lastPanX = e.x; lastPanY = e.y
                if (state == State.COUNTDOWN && viewScale == 1f && cfg.controlMode == 0 && !cfg.spectate) countdown = 0f
                if (cfg.controlMode != 0 && !cfg.spectate) { joystickActive = true; joyAx = e.x; joyAy = e.y; touchTx = 0f; touchTy = 0f } }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; joystickActive = false; touchTx = 0f; touchTy = 0f }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !multiTouch && joystickActive) {
                    val maxR = dp(Tuning.JOYSTICK_RADIUS_DP)
                    var dx = (e.x - joyAx) / maxR; var dy = (e.y - joyAy) / maxR
                    val len = sqrt(dx * dx + dy * dy); if (len > 1f) { dx /= len; dy /= len }
                    touchTx = dx; touchTy = dy; if (len > 0.15f) touchUsed = true
                } else { panX += e.x - lastPanX; panY += e.y - lastPanY; clampPan() }
                lastPanX = e.x; lastPanY = e.y }
            MotionEvent.ACTION_POINTER_UP -> { joystickActive = false; touchTx = 0f; touchTy = 0f }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { joystickActive = false; touchTx = 0f; touchTy = 0f; multiTouch = false }
        }
        return true
    }
    private fun clampPan() {
        panX = panX.coerceIn(-boardW * viewScale * 0.5f, boardW * viewScale * 0.5f)
        panY = panY.coerceIn(-boardH * viewScale * 0.5f, boardH * viewScale * 0.5f)
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); if (w > 0 && h > 0) newGame() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow()
        staticLayer?.let { if (!it.isRecycled) it.recycle() }; staticLayer = null
        fogBitmap?.let { if (!it.isRecycled) it.recycle() }; fogBitmap = null; fogCanvas = null }

    fun newGame() {
        if (width == 0 || height == 0) return
        board = Random(cfg.seed)   // determinism: re-seeded generation stream, every time
        state = State.COUNTDOWN; countdown = if (cfg.spectate) 0.8f else 2.2f; lastTick = 3
        goFlash = 0f; elapsedMs = 0L; bankedCount = 0; coinsCollected = 0
        score = 0; combo = 1; comboTimer = 0f; maxCombo = 1; trapsHit = 0; distCells = 0f
        slowUntil = -1f; bumperHits = 0
        particles.forEach { if (pPool.size < 256) pPool.addLast(it) }; particles.clear()
        floats.clear(); marbles.clear(); allWalls.clear(); traps.clear(); pads.clear()
        coins.clear(); powerUps.clear(); exits.clear(); gates.clear(); keys.clear()
        flappers.clear(); belts.clear(); windCells.clear(); rubble.clear(); rocks.clear()
        bumpers.clear(); movers.clear(); spinners.clear(); usedEdges.clear(); grads.clear()
        portal = null; shake = 0f; tiltPaused = false; uprightZ = 9.81f
        txX = 0f; tyY = 0f; txT = 0f; tyT = 0f; viewScale = 1f; panX = 0f; panY = 0f
        rewinding = false; usedRewind = false; touchUsed = false; rewindBuf.clear()
        specSamples = null; specIdx = 0; specClock = 0f; specRate = 1f; specPlaying = true
        ghost = null; ghostStride = 0; ghostOn = false; ghostIdx = 0; recLen = 0; lastRecMs = 0L
        lastAutosaveMs = 0L; bossName = null; drainActive = false; drainOscillate = false; drainSign = 1

        if (cfg.spectate) {
            val s = cfg.spectateData
            if (s == null || s.size < 6) { state = State.WON; postInvalidateOnAnimation(); return }
            specSamples = s; specStride = 1 + 2 * cfg.marbles
        } else {
            recordedStride = 1 + 2 * cfg.marbles
            val k = ghostKey
            if (k != null) {
                try {
                    val st = Prefs.ghost(context, k)
                    if (st != null) {
                        val b = Base64.decode(st, Base64.NO_WRAP)
                        val fb = FloatArray(b.size / 4); ByteBuffer.wrap(b).asFloatBuffer().get(fb)
                        val expected = 1 + 2 * cfg.marbles
                        ghostStride = if (fb.size >= 2 * expected && fb.size % expected == 0) expected
                            else if (fb.size >= 6 && fb.size % 3 == 0) 3 else 0
                        if (ghostStride > 0) ghost = fb
                    }
                } catch (_: Exception) {}
                ghostOn = ghost != null && Prefs.ghostEnabled(context)
                if (ghostStride >= 3) ghostBanked = BooleanArray((ghostStride - 1) / 2)
            }
        }

        val margin = dp(8f); val hudH = dp(64f); val barH = dp(84f)
        val availW = width - margin * 2f; val availH = height - margin * 2f - hudH - barH
        val aspect = availW / availH
        val difficulty = (cfg.difficulty + (cfg.level - 1) * 2).coerceAtMost(34)
        if (aspect >= 1f) { cols = difficulty; rows = (difficulty / aspect).roundToInt().coerceIn(4, difficulty) }
        else { rows = difficulty; cols = (difficulty * aspect).roundToInt().coerceIn(4, difficulty) }
        cellSize = min(availW / cols, availH / rows)
        wallT = (cellSize * 0.14f).coerceIn(dp(3f), dp(7f))
        boardW = cols * cellSize; boardH = rows * cellSize
        boardLeft = margin + (availW - boardW) / 2f; boardTop = hudH + margin + (availH - boardH) / 2f

        val o = cfg.opts
        maze = Maze(cols, rows)
        if (isBoss) {
            for (y in 0 until rows) for (x in 0 until cols) { val c = maze[x, y]
                c.top = y == 0; c.left = x == 0; c.bottom = y == rows - 1; c.right = x == cols - 1 }
        } else maze.generate(o?.genMode ?: Maze.GEN_CLASSIC, board, braid = if (cfg.marbles > 1) 0.5f else 0.35f)
        if (debugEnabled && !isBoss && !maze.isFullyConnected())
            android.util.Log.w("MazeView", "generator produced DISCONNECTED maze — bug!")

        occupied.clear(); occupied.add(0 to 0)
        surface.clear(); repeat(cols * rows) { surface.add(Surf.NORMAL) }
        cellGates = Array(cols) { Array(rows) { mutableListOf<Gate>() } }
        cellFlappers = Array(cols) { Array(rows) { mutableListOf<Flapper>() } }
        beltDirs = IntArray(cols * rows) { 9 }; windDirs = IntArray(cols * rows) { 9 }
        drainBase = IntArray(cols * rows) { 9 }

        val area = cols * rows; val dist = bfsDist()
        placeExits(dist)
        if (isBoss) {
            val tier = (cfg.level / 10).coerceAtMost(4); var hSign = 0
            when (tier) {
                2 -> { bossName = context.getString(R.string.boss_twins)
                    val half = cellSize * 2.4f; val om = 0.65f + board.nextFloat() * 0.2f
                    addSpinner(cols / 4, rows / 2, half, om, 2); addSpinner(3 * cols / 4, rows / 2, half, -om, 2) }
                3 -> { bossName = context.getString(R.string.boss_hurricane)
                    hSign = if (board.nextBoolean()) 1 else -1
                    addSpinner(cols / 2, rows / 2, cellSize * 2.6f, -hSign * 0.75f, 2) }
                4 -> when (board.nextInt(3)) {
                    0 -> { bossName = context.getString(R.string.boss_drain)
                        addSpinner(cols / 2, rows / 2, cellSize * 2.2f, 0.85f + board.nextFloat() * 0.2f, 3)
                        placeDrain(outward = false, oscillate = false) }
                    1 -> { bossName = context.getString(R.string.boss_volcano)
                        addSpinner(cols / 2, rows / 2, cellSize * 2.2f, 0.85f + board.nextFloat() * 0.2f, 3)
                        placeDrain(outward = true, oscillate = false) }
                    else -> { bossName = context.getString(R.string.boss_tide)
                        addSpinner(cols / 2, rows / 2, cellSize * 2.2f, 0.85f + board.nextFloat() * 0.2f, 3)
                        placeDrain(outward = false, oscillate = true) } }
                else -> { bossName = context.getString(R.string.boss_blade)
                    addSpinner(cols / 2, rows / 2, cellSize * 2.6f,
                        (0.55f + board.nextFloat() * 0.4f) * if (board.nextBoolean()) 1f else -1f, 2) }
            }
            repeat((area / 22).coerceIn(4, 9)) {
                val c = freeCell { x, y -> x + y >= 3 } ?: return@repeat
                occupied.add(c); rocks.add(Rock(ccx(c.first), ccy(c.second), cellSize * 0.38f)) }
            if (drainActive) rebuildDrain() else if (hSign != 0) placeHurricane(hSign)
        } else {
            if (o == null || o.terrain) { terrain((area / 14).coerceIn(3, 9), Surf.ICE); terrain((area / 16).coerceIn(2, 8), Surf.MUD) }
            val gateCount = when { o != null -> if (o.gates) 2 else 0
                cfg.level == 1 -> 0; cfg.level <= 3 -> 1; else -> 2 }
            if (gateCount > 0) { placeGates(gateCount); placeKeys(dist) }
            if (o == null || o.traps) repeat((area / 40 + cfg.level).coerceIn(1, 12)) {
                val c = freeCell { x, y -> x + y >= 3 } ?: return@repeat
                occupied.add(c); traps.add(Hole(ccx(c.first), ccy(c.second), cellSize * 0.30f)) }
            if (o == null || o.pads) repeat((area / 16).coerceIn(2, 10)) {
                val c = freeCell() ?: return@repeat; val d = openDirs(c.first, c.second)
                if (d.isEmpty()) return@repeat
                occupied.add(c); val dir = d[board.nextInt(d.size)]; pads.add(Pad(c.first, c.second, dir.first, dir.second)) }
            if (o == null || o.belts) repeat((area / 18).coerceIn(2, 8)) {
                val c = freeCell { x, y -> val d = openDirs(x, y)
                    d.size == 2 && d[0].first == -d[1].first && d[0].second == -d[1].second } ?: return@repeat
                val d = openDirs(c.first, c.second)[0]
                occupied.add(c); belts.add(Triple(c.first, c.second, d))
                beltDirs[c.second * cols + c.first] = packDir(d.first, d.second) }
            if (o == null || o.winds) placeWinds((area / 26).coerceIn(1, 5))
            if (o == null || o.movers) placeMovers(o?.let { 2 } ?: if (cfg.level < 3) 0 else min(1 + (cfg.level - 3) / 3, 3))
            if (o == null || o.flappers) placeFlappers(o?.let { 2 } ?: if (cfg.level == 1) 0 else if (cfg.level <= 3) 1 else 2)
            if (o == null || o.spinners) placeSpinners(min(1 + area / 45 + cfg.level / 3, 6))
            if (o == null || o.portals) placePortal()
            if (o == null || o.bumpers) placeBumpers(if (o != null) 3 else if (cfg.level < 6) 0 else (area / 60).coerceIn(2, 6))
        }
        if (o == null || o.coins) {
            val de = mutableListOf<Pair<Int, Int>>(); val ot = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until rows) for (x in 0 until cols) { if (isOcc(x, y)) continue
                val c = maze[x, y]; if (listOf(c.top, c.right, c.bottom, c.left).count { it } >= 3) de.add(x to y) else ot.add(x to y) }
            de.shuffle(board); ot.shuffle(board)
            for ((x, y) in (de + ot).take((area / 10).coerceIn(4, 14))) { coins.add(Coin(ccx(x), ccy(y))); occupied.add(x to y) }
        }
        if (o == null || o.powerUps) {
            fun pu(type: PType, n: Int) { repeat(n) { val c = freeCell { x, y -> x + y >= 3 } ?: return@repeat
                occupied.add(c); powerUps.add(PowerUp(ccx(c.first), ccy(c.second), type)) } }
            pu(PType.MAGNET, (area / 70).coerceIn(1, 3)); pu(PType.SHIELD, (area / 90).coerceIn(1, 2)); pu(PType.SLOW, (area / 90).coerceIn(1, 2))
        }
        buildWalls(bouncyChance = 0.10f, allowCrumble = o?.crumble ?: true)
        buildStatic()
        val order = bfsOrder(); val radius = cellSize * 0.27f
        var placed = 0; var i = 0; val cap = order.size * 2 + 10
        while (placed < cfg.marbles && i < cap) {
            val (cx, cy) = order[i % order.size]; i++
            if (isOcc(cx, cy)) continue
            val m = Marble(ccx(cx) + (board.nextFloat() - 0.5f) * cellSize * 0.16f,
                ccy(cy) + (board.nextFloat() - 0.5f) * cellSize * 0.16f, radius,
                GameThemes.MARBLE_COLORS[placed % GameThemes.MARBLE_COLORS.size], placed)
            m.spinPhase = board.nextFloat() * 2f * PI.toFloat()
            marbles.add(m); placed++
        }
        recordedStride = 1 + 2 * marbles.size
        pendingRestore?.let { j -> pendingRestore = null; applyRestore(j) }
        postInvalidateOnAnimation()
    }

    // ---- generation helpers ----
    private fun ccx(x: Int) = boardLeft + (x + 0.5f) * cellSize
    private fun ccy(y: Int) = boardTop + (y + 0.5f) * cellSize
    private fun isOcc(x: Int, y: Int) = (x to y) in occupied
    private fun freeCell(pred: (Int, Int) -> Boolean = { _, _ -> true }): Pair<Int, Int>? {
        repeat(300) { val x = board.nextInt(cols); val y = board.nextInt(rows)
            if (!isOcc(x, y) && pred(x, y)) return x to y }
        return null
    }
    private fun openDirs(x: Int, y: Int): List<Pair<Int, Int>> {
        val o = mutableListOf<Pair<Int, Int>>(); val c = maze[x, y]
        if (!c.top && y > 0) o.add(0 to -1); if (!c.bottom && y < rows - 1) o.add(0 to 1)
        if (!c.left && x > 0) o.add(-1 to 0); if (!c.right && x < cols - 1) o.add(1 to 0)
        return o
    }
    private fun bfsDist(): IntArray {
        val d = IntArray(cols * rows) { -1 }; val q = ArrayDeque<Int>()
        d[0] = 0; q.addLast(0)
        while (q.isNotEmpty()) { val i = q.removeFirst(); val x = i % cols; val y = i / cols; val c = maze[x, y]
            if (!c.top && y > 0 && d[i - cols] < 0) { d[i - cols] = d[i] + 1; q.addLast(i - cols) }
            if (!c.bottom && y < rows - 1 && d[i + cols] < 0) { d[i + cols] = d[i] + 1; q.addLast(i + cols) }
            if (!c.left && x > 0 && d[i - 1] < 0) { d[i - 1] = d[i] + 1; q.addLast(i - 1) }
            if (!c.right && x < cols - 1 && d[i + 1] < 0) { d[i + 1] = d[i] + 1; q.addLast(i + 1) } }
        return d
    }
    private fun bfsOrder(): List<Pair<Int, Int>> {
        val seen = Array(cols) { BooleanArray(rows) }; val q = ArrayDeque<Pair<Int, Int>>()
        q.addLast(0 to 0); seen[0][0] = true; val out = mutableListOf<Pair<Int, Int>>()
        while (q.isNotEmpty()) { val (x, y) = q.removeFirst(); out.add(x to y); val c = maze[x, y]
            if (!c.top && y > 0 && !seen[x][y - 1]) { seen[x][y - 1] = true; q.addLast(x to y - 1) }
            if (!c.bottom && y < rows - 1 && !seen[x][y + 1]) { seen[x][y + 1] = true; q.addLast(x to y + 1) }
            if (!c.left && x > 0 && !seen[x - 1][y]) { seen[x - 1][y] = true; q.addLast(x - 1 to y) }
            if (!c.right && x < cols - 1 && !seen[x + 1][y]) { seen[x + 1][y] = true; q.addLast(x + 1 to y) } }
        return out
    }
    private fun placeExits(dist: IntArray) {
        val cand = (0 until cols * rows).filter { dist[it] >= 2 && !isOcc(it % cols, it / cols) }
            .sortedByDescending { dist[it] }
        val chosen = mutableListOf<Pair<Int, Int>>()
        for (gap in intArrayOf(3, 2, 1)) { for (i in cand) {
            if (chosen.size == cfg.marbles) break
            val x = i % cols; val y = i / cols
            if (chosen.all { (abs(it.first - x) + abs(it.second - y)) >= gap }) chosen.add(x to y) }
            if (chosen.size == cfg.marbles) break }
        while (chosen.size < cfg.marbles) { val c = freeCell() ?: break; chosen.add(c) }
        chosen.forEachIndexed { i, (x, y) -> occupied.add(x to y)
            exits.add(ExitHole(ccx(x), ccy(y), cellSize * 0.31f, GameThemes.LETTERS[i % GameThemes.LETTERS.size])) }
    }
    private fun terrain(count: Int, surf: Surf) {
        var p = 0; var a = 0
        while (p < count && a++ < 400) {
            val sx = board.nextInt(cols); val sy = board.nextInt(rows)
            if (isOcc(sx, sy)) continue
            val blob = mutableListOf(sx to sy); occupied.add(sx to sy); surface[sy * cols + sx] = surf; p++
            val target = 2 + board.nextInt(4); var g = 0
            while (blob.size < target && g++ < 20) {
                val (bx, by) = blob[board.nextInt(blob.size)]; val (dx, dy) = DIRS4[board.nextInt(4)]
                val nx = bx + dx; val ny = by + dy
                if (nx < 0 || ny < 0 || nx >= cols || ny >= rows || isOcc(nx, ny)) continue
                occupied.add(nx to ny); surface[ny * cols + nx] = surf; blob.add(nx to ny); p++ }
        }
    }
    private fun edgeId(x: Int, y: Int, right: Boolean) = (y * cols + x) * 2 + if (right) 0 else 1
    private fun reachBlocking(blocked: Set<Int>): BooleanArray {
        val seen = BooleanArray(cols * rows); val q = ArrayDeque<Int>()
        seen[0] = true; q.addLast(0)
        while (q.isNotEmpty()) { val i = q.removeFirst(); val x = i % cols; val y = i / cols; val c = maze[x, y]
            if (!c.top && y > 0 && edgeId(x, y - 1, false) !in blocked && !seen[i - cols]) { seen[i - cols] = true; q.addLast(i - cols) }
            if (!c.bottom && y < rows - 1 && edgeId(x, y, false) !in blocked && !seen[i + cols]) { seen[i + cols] = true; q.addLast(i + cols) }
            if (!c.left && x > 0 && edgeId(x - 1, y, true) !in blocked && !seen[i - 1]) { seen[i - 1] = true; q.addLast(i - 1) }
            if (!c.right && x < cols - 1 && edgeId(x, y, true) !in blocked && !seen[i + 1]) { seen[i + 1] = true; q.addLast(i + 1) } }
        return seen
    }
    private fun exitCells(): Set<Int> = exits.map { h ->
        (((h.y - boardTop) / cellSize).toInt()) * cols + (((h.x - boardLeft) / cellSize).toInt()) }.toSet()
    private fun placeGates(count: Int) {
        if (count <= 0) return
        val ec = exitCells()
        val cands = mutableListOf<Triple<Int, Int, Boolean>>()
        for (y in 0 until rows) for (x in 0 until cols) {
            if (x < cols - 1 && !maze[x, y].right) cands.add(Triple(x, y, true))
            if (y < rows - 1 && !maze[x, y].bottom) cands.add(Triple(x, y, false)) }
        cands.shuffle(board)
        fun rect(x: Int, y: Int, right: Boolean) = if (right) {
            val bx = boardLeft + (x + 1) * cellSize
            RectF(bx - wallT / 2f, boardTop + y * cellSize, bx + wallT / 2f, boardTop + (y + 1) * cellSize)
        } else { val by = boardTop + (y + 1) * cellSize
            RectF(boardLeft + x * cellSize, by - wallT / 2f, boardLeft + (x + 1) * cellSize, by + wallT / 2f) }
        fun usable(x: Int, y: Int, right: Boolean): Boolean {
            val e = edgeId(x, y, right); if (e in usedEdges) return false
            val c1 = y * cols + x; val c2 = if (right) c1 + 1 else c1 + cols
            if (x + y < 2) return false
            return c1 !in ec && c2 !in ec }
        for ((x, y, r) in cands) { if (gates.size == count) break
            if (!usable(x, y, r)) continue
            val e = edgeId(x, y, r)
            if (ec.any { !reachBlocking(setOf(e))[it] }) { gates.add(Gate(rect(x, y, r), e)); usedEdges.add(e) } }
        for ((x, y, r) in cands) { if (gates.size == count) break
            if (!usable(x, y, r)) continue
            val e = edgeId(x, y, r)
            if (ec.all { reachBlocking(setOf(e))[it] }) { gates.add(Gate(rect(x, y, r), e)); usedEdges.add(e) } }
        for (g in gates) register(cellGates, g.rect, g)
        val blocked = gates.map { it.edge }.toSet()
        if (blocked.isEmpty()) return
        val seen = reachBlocking(blocked)
        val kc = (0 until cols * rows).filter { seen[it] && bfsDist()[it] > 0 && !isOcc(it % cols, it / cols) }
            .sortedByDescending { bfsDist()[it] }
        gates.indices.forEach { i ->
            val idx = kc.getOrNull(i) ?: freeCell() ?: return@forEach
            occupied.add(idx % cols to idx / cols)
            keys.add(KeyItem(ccx(idx % cols), ccy(idx / cols), i)) }
    }
    private fun placeFlappers(count: Int) {
        if (count <= 0) return
        val ec = exitCells()
        val cands = mutableListOf<Triple<Int, Int, Boolean>>()
        for (y in 0 until rows) for (x in 0 until cols) {
            if (x < cols - 1 && !maze[x, y].right) cands.add(Triple(x, y, true))
            if (y < rows - 1 && !maze[x, y].bottom) cands.add(Triple(x, y, false)) }
        cands.shuffle(board)
        for ((x, y, r) in cands) { if (flappers.size == count) break
            val e = edgeId(x, y, r); if (e in usedEdges || x + y < 2) continue
            val c1 = y * cols + x; val c2 = if (r) c1 + 1 else c1 + cols
            if (c1 in ec || c2 in ec || isOcc(x, y)) continue
            val (ox, oy) = if (r) (x + 1) to y else x to (y + 1); if (isOcc(ox, oy)) continue
            if (ec.any { !reachBlocking(setOf(e))[it] }) continue
            usedEdges.add(e)
            val rect = if (r) { val bx = boardLeft + (x + 1) * cellSize
                RectF(bx - wallT / 2f, boardTop + y * cellSize + wallT, bx + wallT / 2f, boardTop + (y + 1) * cellSize - wallT)
            } else { val by = boardTop + (y + 1) * cellSize
                RectF(boardLeft + x * cellSize + wallT, by - wallT / 2f, boardLeft + (x + 1) * cellSize - wallT, by + wallT / 2f) }
            val dir = if (r) (if (board.nextBoolean()) 1 to 0 else -1 to 0) else (if (board.nextBoolean()) 0 to 1 else 0 to -1)
            flappers.add(Flapper(rect, dir.first, dir.second)) }
        for (f in flappers) register(cellFlappers, f.rect, f)
    }
    private fun placeWinds(count: Int) {
        var p = 0; var a = 0
        while (p < count && a++ < 300) {
            val w = 2 + board.nextInt(2); val h = 2 + board.nextInt(2)
            val x0 = board.nextInt((cols - w).coerceAtLeast(1)); val y0 = board.nextInt((rows - h).coerceAtLeast(1))
            var ok = true
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) if (isOcc(x, y) || x + y < 2) { ok = false; break }
            if (!ok) continue
            val (dx, dy) = DIRS4[board.nextInt(4)]
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
                occupied.add(x to y); windDirs[y * cols + x] = packDir(dx, dy)
                windCells.add(Triple(x, y, dx to dy)) }
            p++
        }
    }
    private fun placeMovers(count: Int) {
        if (count <= 0) return
        var n = 0
        for (y in 0 until rows) { var x = 0
            while (x < cols && n < count) {
                if (!maze[x, y].top && !maze[x, y].bottom && x + 2 < cols &&
                    !maze[x + 1, y].top && !maze[x + 1, y].bottom && !maze[x + 2, y].top && !maze[x + 2, y].bottom) {
                    val cells = listOf(x to y, x + 1 to y, x + 2 to y)
                    if (cells.none { isOcc(it.first, it.second) }) {
                        cells.forEach { occupied.add(it) }
                        movers.add(Mover(ccx(x + 1), ccy(y), true, cellSize,
                            2.4f + board.nextFloat() * 1.6f, board.nextFloat() * 2f * PI.toFloat(),
                            cellSize * 0.7f, cellSize * 0.5f)); n++ }
                    x += 3 } else x++ } }
    }
    private fun placeSpinners(count: Int) {
        repeat(count) { val c = freeCell { x, y -> openDirs(x, y).size >= 3 && x + y >= 3 } ?: return@repeat
            occupied.add(c)
            spinners.add(Spinner(ccx(c.first), ccy(c.second), cellSize * 1.5f, wallT * 0.6f,
                (0.9f + board.nextFloat() * 0.9f) * if (board.nextBoolean()) 1f else -1f,
                board.nextFloat() * 2f * PI.toFloat())) }
    }
    private fun placePortal() {
        if (cols * rows < 50) return
        val a = freeCell() ?: return
        val b = freeCell { x, y -> abs(x - a.first) + abs(y - a.second) >= (cols + rows) / 2 } ?: return
        occupied.add(a); occupied.add(b)
        portal = PortalPair(ccx(a.first), ccy(a.second), ccx(b.first), ccy(b.second), cellSize * 0.30f)
    }
    private fun placeBumpers(count: Int) {
        repeat(count) { val c = freeCell { x, y -> x + y >= 3 } ?: return@repeat
            occupied.add(c); bumpers.add(Bumper(ccx(c.first), ccy(c.second), cellSize * 0.34f)) }
    }
    private fun addSpinner(cx: Int, cy: Int, halfLen: Float, omega: Float, arms: Int) {
        val s = Spinner(ccx(cx), ccy(cy), halfLen, wallT * 1.1f, omega,
            board.nextFloat() * 2f * PI.toFloat(), arms)
        for (y in 0 until rows) for (x in 0 until cols)
            if (sqrt((ccx(x) - s.cx).pow2() + (ccy(y) - s.cy).pow2()) < halfLen + cellSize * 0.5f) occupied.add(x to y)
        spinners.add(s)
    }
    private fun placeDrain(outward: Boolean, oscillate: Boolean) {
        drainSign = if (outward) -1 else 1; drainOscillate = oscillate; drainActive = true
        val ccx0 = (cols - 1) / 2.0; val ccy0 = (rows - 1) / 2.0
        for (y in 0 until rows) for (x in 0 until cols) {
            if (isOcc(x, y) || x + y < 3) continue
            val rx = x - ccx0; val ry = y - ccy0
            drainBase[y * cols + x] = packDir(
                if (abs(rx) > abs(ry)) (if (rx > 0) -1 else 1) else 0,
                if (abs(rx) > abs(ry)) 0 else (if (ry > 0) -1 else 1)) }
        rebuildDrain()
    }
    private fun placeHurricane(sign: Int) {
        val ccx0 = (cols - 1) / 2.0; val ccy0 = (rows - 1) / 2.0
        for (y in 0 until rows) for (x in 0 until cols) {
            if (isOcc(x, y) || x + y < 3) continue
            val rx = x - ccx0; val ry = y - ccy0
            val d = if (abs(rx) > abs(ry)) 0 to sign * (if (rx > 0) 1 else -1)
                    else -sign * (if (ry > 0) 1 else -1) to 0
            windDirs[y * cols + x] = packDir(d.first, d.second)
            if ((x + y) % 2 == 0) windCells.add(Triple(x, y, d)) }
    }
    private fun packDir(dx: Int, dy: Int) = (dx + 1) * 3 + (dy + 1)
    private fun Int.dDx() = this / 3 - 1
    private fun Int.dDy() = this % 3 - 1
    private fun rebuildDrain() {
        windDirs.fill(9); windCells.clear()
        for (idx in drainBase.indices) { val d = drainBase[idx]; if (d == 9) continue
            val wx = d.dDx() * drainSign; val wy = d.dDy() * drainSign
            windDirs[idx] = packDir(wx, wy)
            if ((idx % cols + idx / cols) % 2 == 0) windCells.add(Triple(idx % cols, idx / cols, wx to wy)) }
    }
    private fun updateDrain() {
        if (!drainOscillate || !drainActive) return
        val want = if ((elapsedMs / Tuning.DRAIN_FLIP_PERIOD_MS.toLong()).toInt() % 2 == 0) 1 else -1
        if (want != drainSign) { drainSign = want; rebuildDrain() }
    }
    private fun <T> register(grid: Array<Array<MutableList<T>>>, rect: RectF, entry: T) {
        val c0 = ((rect.left - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val c1 = ((rect.right - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val r0 = ((rect.top - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        val r1 = ((rect.bottom - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        for (cx in c0..c1) for (cy in r0..r1) grid[cx][cy].add(entry)
    }
    private fun buildWalls(bouncyChance: Float, allowCrumble: Boolean) {
        cellWalls = Array(cols) { Array(rows) { mutableListOf<Wall>() } }
        fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
            val perim = x0 <= boardLeft + 0.5f || x1 >= boardLeft + boardW - 0.5f ||
                y0 <= boardTop + 0.5f || y1 >= boardTop + boardH - 0.5f
            val crumble = !perim && !isBoss && allowCrumble && (cfg.opts != null || cfg.level >= 4) &&
                board.nextFloat() < Tuning.CRUMBLE_CHANCE
            val bouncy = !crumble && board.nextFloat() < bouncyChance
            val wl = Wall(RectF(x0, y0, x1, y1), bouncy, crumble, allWalls.size)
            allWalls.add(wl); register(cellWalls, wl.rect, wl)
        }
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val c = maze[cx, cy]; val x = boardLeft + cx * cellSize; val y = boardTop + cy * cellSize
            if (c.top) add(x, y, x + cellSize, y + wallT)
            if (c.left) add(x, y, x + wallT, y + cellSize)
            if (cy == rows - 1 && c.bottom) add(x, y + cellSize - wallT, x + cellSize, y + cellSize)
            if (cx == cols - 1 && c.right) add(x + cellSize - wallT, y, x + cellSize, y + cellSize) }
    }
    private fun buildStatic() {
        staticPad = dp(10f)
        val w = ceil(boardW + staticPad * 2).toInt().coerceAtLeast(1)
        val h = ceil(boardH + staticPad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.translate(staticPad - boardLeft, staticPad - boardTop)
        c.drawRoundRect(boardLeft - dp(6f), boardTop - dp(6f), boardLeft + boardW + dp(6f),
            boardTop + boardH + dp(6f), dp(12f), dp(12f), boardP)
        c.drawRoundRect(boardLeft - dp(6f), boardTop - dp(6f), boardLeft + boardW + dp(6f),
            boardTop + boardH + dp(6f), dp(12f), dp(12f), borderP)
        if (isBoss) c.drawRoundRect(boardLeft - dp(2f), boardTop - dp(2f), boardLeft + boardW + dp(2f),
            boardTop + boardH + dp(2f), dp(8f), dp(8f), bossFrameP)
        for (i in surface.indices) { val s = surface[i]
            if (s == Surf.NORMAL || s == Surf.RUBBLE) continue
            val x = boardLeft + (i % cols) * cellSize; val y = boardTop + (i / cols) * cellSize
            tmpRect.set(x + dp(1f), y + dp(1f), x + cellSize - dp(1f), y + cellSize - dp(1f))
            when (s) { Surf.ICE -> { c.drawRoundRect(tmpRect, dp(4f), dp(4f), iceP)
                c.save(); c.clipRect(tmpRect)
                c.drawLine(x, y + cellSize * 0.7f, x + cellSize * 0.7f, y, windP)
                c.restore() }
                Surf.MUD -> c.drawRoundRect(tmpRect, dp(4f), dp(4f), mudP)
                else -> Unit } }
        c.drawCircle(ccx(0), ccy(0), cellSize * 0.28f, startP)
        for (rk in rocks) { c.drawCircle(rk.x + dp(2f), rk.y + dp(3f), rk.rad, shadowP)
            c.drawCircle(rk.x, rk.y, rk.rad, rockP) }
        val rr = wallT / 2f
        c.save(); c.translate(dp(2f), dp(3f))
        for (wl in allWalls) if (!wl.crumble) c.drawRoundRect(wl.rect, rr, rr, shadowP)
        c.restore()
        for (wl in allWalls) if (!wl.crumble) c.drawRoundRect(wl.rect, rr, rr, if (wl.bouncy) bouncyP else wallP)
        staticLayer?.let { if (!it.isRecycled) it.recycle() }
        staticLayer = bmp
        val fb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fogBitmap?.let { if (!it.isRecycled) it.recycle() }
        fogBitmap = fb; fogCanvas = Canvas(fb)
    }

    // ---- loop ----
    private fun step(dt: Float) {
        now = SystemClock.elapsedRealtime()
        val slow = state == State.PLAYING && animTime < slowUntil
        val gdt = dt * (if (slow) Tuning.SLOW_FACTOR else 1f)
        animTime += if (state == State.PLAYING) gdt else dt
        shake *= exp(-7f * dt)
        shakeX = (fx.nextFloat() * 2f - 1f) * shake; shakeY = (fx.nextFloat() * 2f - 1f) * shake
        for (s in spinners) s.angle += s.omega * gdt
        updateDrain(); updateParticles(dt); updateFloats(dt)
        for (h in exits) h.flash = (h.flash - gdt * 2.5f).coerceAtLeast(0f)
        for (b in bumpers) b.flash = (b.flash - gdt * 3f).coerceAtLeast(0f)
        when (state) {
            State.COUNTDOWN -> { countdown -= dt; val n = ceil(countdown).toInt()
                if (n != lastTick && n in 1..3) { lastTick = n; Sfx.tick() }
                if (countdown <= 0f) { state = State.PLAYING; goFlash = 0.6f; Sfx.go() } }
            State.PLAYING -> { goFlash = (goFlash - dt).coerceAtLeast(0f)
                if (cfg.spectate) { spectateStep(dt); return }
                if (rewinding) { rewindStep(dt); return }
                comboTimer -= gdt; if (comboTimer <= 0f) combo = 1
                if (cfg.controlMode != 1) {
                    if (!tiltPaused && uprightZ < Tuning.STEEP_PAUSE_Z) tiltPaused = true
                    else if (tiltPaused && uprightZ > Tuning.FLAT_RESUME_Z) tiltPaused = false }
                if (!tiltPaused) {
                    if (!cfg.zen) elapsedMs += (gdt * 1000).toLong()
                    val k = min(1f, gdt * 10f); txX += (txT - txX) * k; tyY += (tyT - tyY) * k
                    var rem = gdt
                    while (rem > 1e-5f) { val s = min(1f / 120f, rem); physics(s); rem -= s }
                    for (m in marbles) { m.portalCooldown = (m.portalCooldown - gdt).coerceAtLeast(0f)
                        m.trapGrace = (m.trapGrace - gdt).coerceAtLeast(0f) }
                    if (elapsedMs - lastRecMs >= 100) { lastRecMs = elapsedMs; recordSnap() }
                    if (elapsedMs - lastAutosaveMs >= Tuning.AUTOSAVE_INTERVAL_MS) {
                        lastAutosaveMs = elapsedMs; exportRunState()?.let { autosaveWriter?.invoke(it) } } } }
            State.WON -> Unit
        }
    }
    private fun spectateStep(dt: Float) {
        val s = specSamples ?: return; val st = specStride; val n = s.size / st
        if (n < 2 || marbles.isEmpty()) { state = State.WON; return }
        if (specPlaying) { val end = s[(n - 1) * st]; specClock += dt * specRate
            if (specClock >= end) { specClock = end; state = State.WON; confetti(); Sfx.win() } }
        while (specIdx < n - 1 && s[(specIdx + 1) * st] <= specClock) specIdx++
        val i = specIdx * st; if (s[i] > specClock) return
        val j = if (specIdx < n - 1) i + st else -1
        for (k in marbles.indices) { var gx = s[i + 1 + k * 2]; var gy = s[i + 2 + k * 2]
            if (j >= 0) { val span = s[j] - s[i]
                if (span > 1e-4f) { val f = ((specClock - s[i]) / span).coerceIn(0f, 1f)
                    gx += (s[j + 1 + k * 2] - gx) * f; gy += (s[j + 2 + k * 2] - gy) * f } }
            marbles[k].x = gx; marbles[k].y = gy }
        elapsedMs = (specClock * 1000).toLong()
    }
    private fun recPush(v: Float) {
        if (recLen == recBuf.size) { if (recLen >= Tuning.MAX_REC_FLOATS) return; recBuf = recBuf.copyOf(recBuf.size * 2) }
        recBuf[recLen++] = v
    }
    private fun recordSnap() {
        if (marbles.isNotEmpty() && !cfg.zen) {
            val d = FloatArray(marbles.size * 4)
            for (i in marbles.indices) { val m = marbles[i]
                d[i * 4] = m.x; d[i * 4 + 1] = m.y; d[i * 4 + 2] = m.vx; d[i * 4 + 3] = m.vy }
            rewindBuf.addLast(Snap(elapsedMs, score, combo, comboTimer, trapsHit,
                BooleanArray(marbles.size) { marbles[it].banked }, d))
            if (rewindBuf.size > Tuning.REWIND_BUF_SIZE) rewindBuf.removeFirst() }
        if (marbles.isNotEmpty() && recLen + recordedStride <= Tuning.MAX_REC_FLOATS) {
            recPush(elapsedMs / 1000f)
            for (m in marbles) { recPush(m.x); recPush(m.y) } }
    }
    private fun rewindStep(dt: Float) {
        val target = elapsedMs - (dt * 1_000_000f * Tuning.REWIND_SPEED).toLong()
        var snap: Snap? = null
        while (rewindBuf.isNotEmpty() && rewindBuf.last().t > target) snap = rewindBuf.removeLast()
        val s = snap ?: rewindBuf.lastOrNull() ?: return
        elapsedMs = s.t; score = s.score; combo = s.combo; comboTimer = s.comboTimer; trapsHit = s.trapsHit
        for (i in marbles.indices) { val m = marbles[i]
            m.x = s.data[i * 4]; m.y = s.data[i * 4 + 1]; m.vx = s.data[i * 4 + 2]; m.vy = s.data[i * 4 + 3]
            m.banked = s.banked[i]; if (m.banked) m.bankTime = now }
        bankedCount = marbles.count { it.banked }; lastRecMs = elapsedMs
    }
    private fun physics(dt: Float) {
        val vmax = Tuning.MAX_SPEED * cellSize
        val srcX: Float; val srcY: Float
        when (cfg.controlMode) { 1 -> { srcX = touchTx; srcY = touchTy }
            2 -> { srcX = (txX + touchTx).coerceIn(-1.2f, 1.2f); srcY = (tyY + touchTy).coerceIn(-1.2f, 1.2f) }
            else -> { srcX = txX; srcY = tyY } }
        for (m in marbles) { if (m.banked) continue
            val ci = ((m.x - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
            val cj = ((m.y - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
            var dampMul = 1f; var accMul = 1f
            when (surface[cj * cols + ci]) {
                Surf.ICE -> dampMul = Tuning.ICE_DAMP
                Surf.MUD -> { dampMul = Tuning.MUD_DAMP; accMul = Tuning.MUD_ACCEL }
                Surf.RUBBLE -> { dampMul = Tuning.RUBBLE_DAMP; accMul = Tuning.RUBBLE_ACCEL }
                Surf.NORMAL -> Unit }
            var ax = -srcX * Tuning.TILT_FORCE * cellSize * accMul
            var ay = srcY * Tuning.TILT_FORCE * cellSize * accMul
            for (p in pads) if (abs(m.x - ccx(p.cx)) < cellSize * 0.45f && abs(m.y - ccy(p.cy)) < cellSize * 0.45f) {
                ax += p.dx * Tuning.PAD_FORCE * cellSize; ay += p.dy * Tuning.PAD_FORCE * cellSize
                if (animTime - m.lastPadFx > 0.8f) { m.lastPadFx = animTime; Sfx.boost() } }
            val wd = windDirs[cj * cols + ci]
            if (wd != 9) { ax += wd.dDx() * Tuning.WIND_FORCE * cellSize; ay += wd.dDy() * Tuning.WIND_FORCE * cellSize }
            val home = exits[m.exitIndex]
            suction(home.x, home.y, home.r, m, 1.2f, ax, ay).let { ax = it.first; ay = it.second }
            if (m.trapGrace <= 0f) for (t in traps) suction(t.x, t.y, t.r, m, 2.6f, ax, ay).let { ax = it.first; ay = it.second }
            val damp = exp(-Tuning.DAMPING * dampMul * dt)
            m.vx = (m.vx + ax * dt) * damp; m.vy = (m.vy + ay * dt) * damp
            var sp = sqrt(m.vx * m.vx + m.vy * m.vy)
            if (sp > vmax) { m.vx *= vmax / sp; m.vy *= vmax / sp; sp = vmax }
            if (sp < cellSize * 0.02f && sqrt(ax * ax + ay * ay) < cellSize * 0.3f) { m.vx = 0f; m.vy = 0f }
            val bd = beltDirs[cj * cols + ci]
            if (bd != 9) { val tx = bd.dDx() * Tuning.BELT_SPEED * cellSize; val ty = bd.dDy() * Tuning.BELT_SPEED * cellSize
                val f = 1f - exp(-Tuning.BELT_GRIP * dt); m.vx += (tx - m.vx) * f; m.vy += (ty - m.vy) * f }
            m.x += m.vx * dt; m.y += m.vy * dt
            if (m.radius > 0f) m.spin += sp / m.radius * dt
            distCells += sp * dt / cellSize }
        // magnet
        for (m in marbles) { if (m.banked || m.magnetUntil <= animTime) continue
            for (cn in coins) { if (cn.taken) continue
                val dx = m.x - cn.x; val dy = m.y - cn.y; val d = sqrt(dx * dx + dy * dy)
                if (d < cellSize * Tuning.MAGNET_RANGE && d > 1f) { val f = (7f * dt).coerceAtMost(1f)
                    cn.x += dx * f; cn.y += dy * f } } }
        // marble-marble
        for (i in 0 until marbles.size) { val a = marbles[i]; if (a.banked) continue
            for (j in i + 1 until marbles.size) { val b = marbles[j]; if (b.banked) continue
                Physics.resolveCircles(a.x, a.y, a.vx, a.vy, b.x, b.y, b.vx, b.vy,
                    a.radius + b.radius, Tuning.RESTITUTION)?.let { r ->
                    a.x = r.ax; a.y = r.ay; a.vx = r.avx; a.vy = r.avy
                    b.x = r.bx; b.y = r.by; b.vx = r.bvx; b.vy = r.bvy
                    if (r.impact > cellSize * 2.5f) Sfx.bounce((r.impact / (cellSize * 4f)).coerceIn(0.15f, 1f)) } } }
        for (m in marbles) { if (m.banked) continue
            collideWalls(m); collideGates(m); collideFlappers(m); collideMovers(m)
            collideRubble(m)
            for (rk in rocks) collideCircle(m, rk.x, rk.y, rk.rad, Tuning.ROCK_REST)
            for (b in bumpers) collideBumper(m, b)
            for (s in spinners) collideSpinner(m, s)
            checkTraps(m); checkPortal(m); checkCoins(m); checkKeys(m); checkPowerUps(m); checkExit(m) }
    }
    private fun suction(hx: Float, hy: Float, hr: Float, m: Marble, st: Float, ax: Float, ay: Float): Pair<Float, Float> {
        val dx = hx - m.x; val dy = hy - m.y; val d = sqrt(dx * dx + dy * dy); val range = hr * 2.2f
        if (d > 1f && d < range) { val pull = st * cellSize * (1f - d / range)
            return (ax + dx / d * pull) to (ay + dy / d * pull) }
        return ax to ay
    }
    private fun collideWalls(m: Marble) {
        val cx = ((m.x - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val cy = ((m.y - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        for (ox in -1..1) for (oy in -1..1) { val x = cx + ox; val y = cy + oy
            if (x < 0 || y < 0 || x >= cols || y >= rows) continue
            for (wl in cellWalls[x][y]) { if (wl.broken) continue
                val hit = Physics.circleVsRect(m.x, m.y, m.vx, m.vy, m.radius, wl.rect,
                    if (wl.bouncy) Tuning.BOUNCY_REST else Tuning.RESTITUTION) ?: continue
                m.x += hit.pushX; m.y += hit.pushY
                if (hit.impact > 0f) {
                    val (rvx, rvy) = Physics.reflect(m.vx, m.vy, hit.nx, hit.ny, -hit.impact,
                        if (wl.bouncy) Tuning.BOUNCY_REST else Tuning.RESTITUTION)
                    m.vx = rvx; m.vy = rvy
                    if (!wl.bouncy) { m.vx *= Tuning.CONTACT_FRICTION; m.vy *= Tuning.CONTACT_FRICTION }
                    if (hit.impact > cellSize * 1.1f && now - m.lastImpactFx > 90) {
                        m.lastImpactFx = now; Sfx.bounce((hit.impact / (cellSize * 5f)).coerceIn(0.15f, 1f))
                        burst(m.x, m.y, 4, Color.parseColor("#9CA3AF"), 110f)
                        shake = min(shake + min(hit.impact / cellSize, 6f) * 0.4f, 6f); buzzImpact(0.4f) } }
                if (wl.crumble && !wl.broken && hit.impact > cellSize * 1.4f) {
                    wl.hits++; burst(wl.rect.centerX(), wl.rect.centerY(), 5, crumbleP.color, 130f)
                    if (wl.hits >= Tuning.CRUMBLE_HITS) { wl.broken = true; addRubble(wl.rect, wl.index)
                        burst(wl.rect.centerX(), wl.rect.centerY(), 16, crumbleP.color, 210f)
                        Sfx.crack(); shake = min(shake + 3f, 6f); buzzImpact(0.8f) } else Sfx.bounce(0.3f) } } }
    }
    private fun addRubble(rect: RectF, wallIndex: Int) {
        // Deterministic layout: pure function of (seed, wallIndex) — restore/spectate agree.
        val rng = Random(cfg.seed xor (0x9E37L * (wallIndex + 1)))
        val n = 12; val peb = FloatArray(n * 3)
        for (i in 0 until n) { peb[i * 3] = rect.left + rng.nextFloat() * rect.width()
            peb[i * 3 + 1] = rect.top + rng.nextFloat() * rect.height()
            peb[i * 3 + 2] = wallT * (0.25f + rng.nextFloat() * 0.4f) }
        rubble.add(Rubble(peb))
        val c0 = ((rect.left - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val c1 = ((rect.right - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val r0 = ((rect.top - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        val r1 = ((rect.bottom - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        for (cx in c0..c1) for (cy in r0..r1) { val i = cy * cols + cx
            if (surface[i] == Surf.NORMAL) surface[i] = Surf.RUBBLE }
    }
    private fun collideRubble(m: Marble) {
        for (rb in rubble) { val p = rb.pebbles; var i = 0
            while (i < p.size) { collideCircleQuiet(m, p[i], p[i + 1], p[i + 2]); i += 3 } }
    }
    private fun collideCircleQuiet(m: Marble, x: Float, y: Float, rad: Float) {
        val dx = m.x - x; val dy = m.y - y; val minD = m.radius + rad
        val d2 = dx * dx + dy * dy
        if (d2 < minD * minD && d2 > 1e-12f) { val d = sqrt(d2); val nx = dx / d; val ny = dy / d
            m.x += nx * (minD - d); m.y += ny * (minD - d)
            val vn = m.vx * nx + m.vy * ny
            if (vn < 0f) { val j = -(1f + Tuning.PEBBLE_REST) * vn
                m.vx += j * nx; m.vy += j * ny
                if (-vn > cellSize * 0.8f && now - m.lastImpactFx > 100) {
                    m.lastImpactFx = now; Sfx.bounce(0.25f); burst(m.x, m.y, 2, rubbleP.color, 70f) } } }
    }
    private fun collideCircle(m: Marble, x: Float, y: Float, rad: Float, e: Float) {
        val dx = m.x - x; val dy = m.y - y; val minD = m.radius + rad
        val d2 = dx * dx + dy * dy
        if (d2 >= minD * minD || d2 == 0f) return
        val d = sqrt(d2); val nx = dx / d; val ny = dy / d
        m.x += nx * (minD - d); m.y += ny * (minD - d)
        val vn = m.vx * nx + m.vy * ny
        if (vn < 0f) { val j = -(1f + e) * vn; m.vx += j * nx; m.vy += j * ny
            if (-vn > cellSize && now - m.lastImpactFx > 120) { m.lastImpactFx = now
                Sfx.bounce((-vn / (cellSize * 4f)).coerceIn(0.2f, 1f)) } }
    }
    private fun collideBumper(m: Marble, b: Bumper) {
        val dx = m.x - b.x; val dy = m.y - b.y; val minD = m.radius + b.rad
        val d2 = dx * dx + dy * dy
        if (d2 >= minD * minD || d2 == 0f) return
        val d = sqrt(d2); val nx = dx / d; val ny = dy / d
        m.x += nx * (minD - d); m.y += ny * (minD - d)
        val vn = m.vx * nx + m.vy * ny
        if (vn < 0f) { val j = -(1f + Tuning.BUMPER_REST) * vn; m.vx += j * nx; m.vy += j * ny
            val sp = sqrt(m.vx * m.vx + m.vy * m.vy); val kick = Tuning.BUMPER_KICK * cellSize
            if (sp < kick && sp > 0f) { m.vx *= kick / sp; m.vy *= kick / sp }
            bumperHits++; b.flash = 1f; Sfx.bounce(1f); buzzImpact(0.9f)
            burst(b.x + nx * b.rad, b.y + ny * b.rad, 6, Color.parseColor("#FDE68A"), 150f) }
    }
    private fun collideGates(m: Marble) {
        val cx = ((m.x - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val cy = ((m.y - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        for (ox in -1..1) for (oy in -1..1) { val x = cx + ox; val y = cy + oy
            if (x < 0 || y < 0 || x >= cols || y >= rows) continue
            for (g in cellGates[x][y]) { if (g.unlocked) continue
                val hit = Physics.circleVsRect(m.x, m.y, m.vx, m.vy, m.radius, g.rect, Tuning.RESTITUTION) ?: continue
                m.x += hit.pushX; m.y += hit.pushY
                if (hit.impact > 0f) { val (rvx, rvy) = Physics.reflect(m.vx, m.vy, hit.nx, hit.ny, -hit.impact, Tuning.RESTITUTION)
                    m.vx = rvx; m.vy = rvy } } }
    }
    private fun collideFlappers(m: Marble) {
        val cx = ((m.x - boardLeft) / cellSize).toInt().coerceIn(0, cols - 1)
        val cy = ((m.y - boardTop) / cellSize).toInt().coerceIn(0, rows - 1)
        for (ox in -1..1) for (oy in -1..1) { val x = cx + ox; val y = cy + oy
            if (x < 0 || y < 0 || x >= cols || y >= rows) continue
            for (f in cellFlappers[x][y]) {
                if (m.vx * f.dx + m.vy * f.dy >= 0f) continue
                val hit = Physics.circleVsRect(m.x, m.y, m.vx, m.vy, m.radius, f.rect, Tuning.RESTITUTION * 0.6f) ?: continue
                m.x += hit.pushX; m.y += hit.pushY
                if (hit.impact > 0f) { val (rvx, rvy) = Physics.reflect(m.vx, m.vy, hit.nx, hit.ny, -hit.impact, Tuning.RESTITUTION * 0.6f)
                    m.vx = rvx; m.vy = rvy } } }
    }
    private fun collideMovers(m: Marble) {
        for (mv in movers) { val off = mv.off(animTime); var svx = 0f; var svy = 0f
            val rect = if (mv.horiz) { tmpRect.set(mv.cx - mv.w / 2f + off, mv.cy - mv.h / 2f, mv.cx + mv.w / 2f + off, mv.cy + mv.h / 2f); svx = mv.vel(animTime); tmpRect }
            else { tmpRect.set(mv.cx - mv.w / 2f, mv.cy - mv.h / 2f + off, mv.cx + mv.w / 2f, mv.cy + mv.h / 2f + off); svy = mv.vel(animTime); tmpRect }
            val hit = Physics.circleVsRect(m.x, m.y, m.vx - svx, m.vy - svy, m.radius, rect, Tuning.MOVER_REST) ?: continue
            m.x += hit.pushX; m.y += hit.pushY
            if (hit.impact > 0f) { val (rvx, rvy) = Physics.reflect(m.vx - svx, m.vy - svy, hit.nx, hit.ny, -hit.impact, Tuning.MOVER_REST)
                m.vx = rvx + svx; m.vy = rvy + svy } }
    }
    private fun collideSpinner(m: Marble, s: Spinner) {
        for (k in 0 until s.arms) { val aa = s.angle + k * (2f * PI.toFloat() / s.arms)
            val cA = cos(aa); val sA = sin(aa); val dx0 = m.x - s.cx; val dy0 = m.y - s.cy
            val lx = dx0 * cA + dy0 * sA; val ly = -dx0 * sA + dy0 * cA
            val r = m.radius + s.halfThick
            val px = lx.coerceIn(-s.halfLen, s.halfLen); val py = ly.coerceIn(-s.halfThick, s.halfThick)
            val ddx = lx - px; val ddy = ly - py; val d2 = ddx * ddx + ddy * ddy
            if (d2 > r * r) continue
            var nlx: Float; var nly: Float; var pen: Float
            if (d2 > 1e-12f) { val d = sqrt(d2); nlx = ddx / d; nly = ddy / d; pen = r - d }
            else { val pL = lx + s.halfLen + r; val pR = s.halfLen - lx + r
                val pT = ly + s.halfThick + r; val pB = s.halfThick - ly + r
                if (pL <= pR && pL <= pT && pL <= pB) { nlx = -1f; nly = 0f; pen = pL }
                else if (pR <= pT && pR <= pB) { nlx = 1f; nly = 0f; pen = pR }
                else if (pT <= pB) { nlx = 0f; nly = -1f; pen = pT }
                else { nlx = 0f; nly = 1f; pen = pB } }
            val wnx = nlx * cA - nly * sA; val wny = nlx * sA + nly * cA
            m.x += wnx * pen; m.y += wny * pen
            val cwx = s.cx + px * cA - py * sA; val cwy = s.cy + px * sA + py * cA
            val bvx = -s.omega * (cwy - s.cy); val bvy = s.omega * (cwx - s.cx)
            val vn = (m.vx - bvx) * wnx + (m.vy - bvy) * wny
            if (vn < 0f) { val j = -(1f + Tuning.SPINNER_REST) * vn
                m.vx += j * wnx; m.vy += j * wny
                if (now - m.lastImpactFx > 120) { m.lastImpactFx = now; Sfx.bounce(0.5f); buzzImpact(0.6f) } } }
    }
    private fun bumpCombo() { combo = (combo + 1).coerceAtMost(Tuning.COMBO_MAX); comboTimer = Tuning.COMBO_WINDOW
        if (combo > maxCombo) maxCombo = combo }
    private fun checkTraps(m: Marble) {
        if (m.trapGrace > 0f) return
        for (t in traps) if (sqrt((m.x - t.x).pow2() + (m.y - t.y).pow2()) < t.r * 0.6f) {
            if (m.hasShield) { m.hasShield = false; m.trapGrace = 0.8f
                burst(m.x, m.y, 16, Color.parseColor("#60A5FA"), 190f)
                floats.add(FloatText("SHIELD!", m.x, m.y - m.radius - dp(8f), 0f, Color.parseColor("#60A5FA")))
                Sfx.unlock(); return }
            trapsHit++; burst(m.x, m.y, 14, Color.parseColor("#EF4444"), 190f)
            m.x = ccx(0) + (fx.nextFloat() - 0.5f) * cellSize * 0.3f
            m.y = ccy(0) + (fx.nextFloat() - 0.5f) * cellSize * 0.3f
            m.vx = 0f; m.vy = 0f; m.trapGrace = 1f; m.magnetUntil = 0f
            if (!cfg.zen) { elapsedMs += Tuning.TRAP_PENALTY_MS
                floats.add(FloatText("+3s", m.x, m.y - m.radius - dp(8f), 0f, Color.parseColor("#F87171"))) }
            shake = 6f; Sfx.trapped(); return }
    }
    private fun checkPortal(m: Marble) {
        val p = portal ?: return
        if (m.portalCooldown > 0f) return
        if (sqrt((m.x - p.ax).pow2() + (m.y - p.ay).pow2()) < p.r * 0.7f) { burst(m.x, m.y, 8, Color.parseColor("#A855F7"), 150f); m.x = p.bx; m.y = p.by; m.portalCooldown = 0.6f; burst(m.x, m.y, 8, Color.parseColor("#FB923C"), 150f); Sfx.portal() }
        else if (sqrt((m.x - p.bx).pow2() + (m.y - p.by).pow2()) < p.r * 0.7f) { burst(m.x, m.y, 8, Color.parseColor("#A855F7"), 150f); m.x = p.ax; m.y = p.ay; m.portalCooldown = 0.6f; burst(m.x, m.y, 8, Color.parseColor("#FB923C"), 150f); Sfx.portal() }
    }
    private fun checkCoins(m: Marble) {
        for (cn in coins) { if (cn.taken) continue
            if (sqrt((m.x - cn.x).pow2() + (m.y - cn.y).pow2()) < cellSize * 0.42f) {
                cn.taken = true; coinsCollected++
                burst(cn.x, cn.y, 10, Color.parseColor("#FACC15"), 150f)
                if (!cfg.zen) { bumpCombo(); score += Tuning.COIN_POINTS * combo
                    floats.add(FloatText("+${Tuning.COIN_POINTS * combo}", cn.x, cn.y - dp(6f), 0f, Color.parseColor("#FACC15"))) }
                Sfx.coin() } }
    }
    private fun checkPowerUps(m: Marble) {
        for (pu in powerUps) { if (pu.taken) continue
            if (sqrt((m.x - pu.x).pow2() + (m.y - pu.y).pow2()) < cellSize * 0.42f) {
                pu.taken = true
                when (pu.type) { PType.MAGNET -> { m.magnetUntil = animTime + Tuning.MAGNET_DURATION
                        floats.add(FloatText("MAGNET!", pu.x, pu.y - dp(12f), 0f, Color.parseColor("#F87171"))); Sfx.boost() }
                    PType.SHIELD -> { m.hasShield = true
                        floats.add(FloatText("SHIELD!", pu.x, pu.y - dp(12f), 0f, Color.parseColor("#60A5FA"))); Sfx.unlock() }
                    PType.SLOW -> { slowUntil = animTime + Tuning.SLOW_DURATION
                        floats.add(FloatText("SLOW!", pu.x, pu.y - dp(12f), 0f, Color.parseColor("#C084FC"))); Sfx.portal() } }
                burst(pu.x, pu.y, 12, Color.WHITE, 160f) } }
    }
    private fun checkKeys(m: Marble) {
        for (k in keys) { if (k.taken) continue
            if (sqrt((m.x - k.x).pow2() + (m.y - k.y).pow2()) < cellSize * 0.45f) {
                k.taken = true; burst(k.x, k.y, 12, Color.parseColor("#FCD34D"), 160f)
                floats.add(FloatText("KEY!", k.x, k.y - dp(12f), 0f, Color.parseColor("#FCD34D"))); Sfx.key()
                val g = gates[k.gateIndex]
                if (!g.unlocked) { g.unlocked = true
                    burst(g.rect.centerX(), g.rect.centerY(), 18, Color.parseColor("#E5E7EB"), 200f)
                    floats.add(FloatText("OPEN!", g.rect.centerX(), g.rect.centerY() - dp(14f), 0f, Color.WHITE))
                    Sfx.unlock(); shake = 4f } } }
    }
    private fun checkExit(m: Marble) {
        val h = exits[m.exitIndex]
        if (sqrt((m.x - h.x).pow2() + (m.y - h.y).pow2()) < h.r * 0.72f) {
            m.banked = true; m.bankTime = now; bankedCount++; h.flash = 1f
            burst(h.x, h.y, 12, m.color, 170f)
            if (!cfg.zen) { bumpCombo(); score += Tuning.BANK_POINTS * combo
                floats.add(FloatText("+${Tuning.BANK_POINTS * combo}", h.x, h.y - h.r, 0f, m.color)) }
            Sfx.bank(); listener?.onMarbleBanked(bankedCount, marbles.size)
            if (bankedCount == marbles.size && marbles.isNotEmpty()) finishRun() }
    }
    private fun finishRun() {
        state = State.WON; confetti(); Sfx.win()
        var stars = 1
        val parMs = if (cfg.zen) 0L else cols.toLong() * rows * 800L + cfg.marbles * 7000L
        if (!cfg.zen) { if (elapsedMs <= parMs) stars++
            if (coins.isNotEmpty() && coinsCollected >= coins.size) stars++
            score += ((parMs * 2 - elapsedMs) / 100).coerceAtLeast(0).toInt() }
        var ghostTotal = 0f
        if (ghost != null && ghostStride >= 3) { val n = ghost!!.size / ghostStride
            if (n >= 2) ghostTotal = ghost!![(n - 1) * ghostStride] }
        val beatGhost = ghostTotal > 0f && elapsedMs < (ghostTotal * 1000).toLong()
        val newBest = ghostTotal == 0f || elapsedMs < (ghostTotal * 1000).toLong()
        val bytes = if (recLen / recordedStride >= 2) {
            val bb = ByteBuffer.allocate(recLen * 4); bb.asFloatBuffer().put(recBuf, 0, recLen); bb.array() } else null
        val ghostSave = if (!cfg.daily && !cfg.zen && !cfg.custom && newBest && ghostKey != null) bytes else null
        val replayOut = if (!cfg.daily && !cfg.zen) bytes else null
        listener?.onGameWon(GameResult(elapsedMs, parMs, coinsCollected, coins.size,
            cfg.difficulty, cfg.level, stars, cfg.daily, score, trapsHit, maxCombo,
            cfg.marbles, beatGhost, ghostSave, replayOut, usedRewind, cfg.fog, touchUsed,
            cfg.zen, cfg.seed, distCells, isBoss, cfg.custom, bumperHits))
    }

    // ---- save/restore ----
    fun exportRunState(): String? {
        if (cfg.spectate || state != State.PLAYING) return null
        return try {
            val o = org.json.JSONObject()
            o.put("v", 1).put("seed", cfg.seed).put("mar", cfg.marbles)
                .put("diff", cfg.difficulty).put("lvl", cfg.level)
            o.put("t", elapsedMs).put("score", score).put("combo", combo)
                .put("maxCombo", maxCombo).put("trapsHit", trapsHit)
            o.put("usedRewind", usedRewind).put("touchUsed", touchUsed)
                .put("dist", distCells.toDouble()).put("bump", bumperHits)
            fun idxs(n: Int, f: (Int) -> Boolean) = org.json.JSONArray().also { a -> for (i in 0 until n) if (f(i)) a.put(i) }
            o.put("coins", idxs(coins.size) { coins[it].taken })
            o.put("keys", idxs(keys.size) { keys[it].taken })
            o.put("gates", idxs(gates.size) { gates[it].unlocked })
            o.put("broken", idxs(allWalls.size) { allWalls[it].broken })
            val ms = org.json.JSONArray()
            for (m in marbles) ms.put(org.json.JSONArray()
                .put(m.x.toDouble()).put(m.y.toDouble()).put(m.vx.toDouble()).put(m.vy.toDouble())
                .put(m.banked).put(m.exitIndex))
            o.put("m", ms); o.toString()
        } catch (_: Exception) { null }
    }
    private fun applyRestore(json: String): Boolean = try {
        val o = org.json.JSONObject(json)
        if (o.getInt("v") != 1 || o.getLong("seed") != cfg.seed || o.getInt("mar") != cfg.marbles ||
            o.getInt("diff") != cfg.difficulty || o.getInt("lvl") != cfg.level) false
        else {
            elapsedMs = o.getLong("t"); score = o.getInt("score"); combo = o.getInt("combo")
            maxCombo = o.getInt("maxCombo"); trapsHit = o.getInt("trapsHit")
            usedRewind = o.getBoolean("usedRewind"); touchUsed = o.getBoolean("touchUsed")
            distCells = o.getDouble("dist").toFloat(); bumperHits = o.optInt("bump", 0)
            fun each(a: org.json.JSONArray, f: (Int) -> Unit) { for (i in 0 until a.length()) f(a.getInt(i)) }
            each(o.getJSONArray("coins")) { coins[it].taken = true }
            each(o.getJSONArray("keys")) { keys[it].taken = true }
            each(o.getJSONArray("gates")) { gates[it].unlocked = true }
            each(o.getJSONArray("broken")) { allWalls[it].broken = true; addRubble(allWalls[it].rect, allWalls[it].index) }
            val ms = o.getJSONArray("m"); bankedCount = 0
            for (k in 0 until min(ms.length(), marbles.size)) { val a = ms.getJSONArray(k); val m = marbles[k]
                m.x = a.getDouble(0).toFloat(); m.y = a.getDouble(1).toFloat()
                m.vx = a.getDouble(2).toFloat(); m.vy = a.getDouble(3).toFloat()
                m.banked = a.getBoolean(4); m.exitIndex = a.getInt(5)
                if (m.banked) { m.bankTime = now; bankedCount++ } }
            lastRecMs = elapsedMs; lastAutosaveMs = elapsedMs
            state = State.PLAYING; countdown = 0f; true }
    } catch (_: Exception) { false }

    // ---- fx ----
    private fun obtain(): Particle = pPool.removeLastOrNull() ?: Particle(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0, 0f)
    private fun burst(x: Float, y: Float, n: Int, color: Int, speed: Float) {
        repeat(n) { val a = fx.nextFloat() * 2f * PI.toFloat(); val s = speed * (0.3f + fx.nextFloat() * 0.7f)
            val p = obtain(); p.x = x; p.y = y; p.vx = cos(a) * s; p.vy = sin(a) * s
            p.life = 0.45f + fx.nextFloat() * 0.35f; p.maxLife = 0.8f
            p.size = dp(2f) + fx.nextFloat() * dp(2f); p.color = color; p.grav = 0f; particles.add(p) }
        while (particles.size > 600) { val d = particles.removeAt(0); if (pPool.size < 256) pPool.addLast(d) }
    }
    private fun confetti() { repeat(130) { val p = obtain()
        p.x = exits.firstOrNull()?.x ?: boardLeft + boardW / 2f
        p.y = exits.firstOrNull()?.y ?: boardTop + boardH / 2f
        p.vx = (fx.nextFloat() - 0.5f) * 460f; p.vy = -120f - fx.nextFloat() * 420f
        p.life = 1.1f + fx.nextFloat(); p.maxLife = 2.2f
        p.size = dp(2.5f) + fx.nextFloat() * dp(2.5f)
        p.color = GameThemes.MARBLE_COLORS[fx.nextInt(GameThemes.MARBLE_COLORS.size)]; p.grav = 750f
        particles.add(p) } }
    private fun updateParticles(dt: Float) {
        for (i in particles.indices.reversed()) { val p = particles[i]; p.life -= dt
            if (p.life <= 0f) { particles.removeAt(i); if (pPool.size < 256) pPool.addLast(p); continue }
            p.vy += p.grav * dt; p.x += p.vx * dt; p.y += p.vy * dt } }
    private fun updateFloats(dt: Float) { for (i in floats.indices.reversed()) { floats[i].age += dt
        if (floats[i].age > 1f) floats.removeAt(i) } }
    private fun buzzImpact(strength: Float) {
        if (!cfg.haptics) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val vv = v?.takeIf { it.hasVibrator() } ?: return
            vv.vibrate(VibrationEffect.createOneShot(30L, (60 + 150 * strength.coerceIn(0f, 1f)).toInt().coerceIn(1, 255)))
        } else performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    // ---- render ----
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cols == 0) return
        now = SystemClock.elapsedRealtime()
        canvas.drawColor(theme.bg)
        canvas.save()
        val bcx = boardLeft + boardW / 2f; val bcy = boardTop + boardH / 2f
        canvas.translate(bcx + panX, bcy + panY); canvas.scale(viewScale, viewScale); canvas.translate(-bcx, -bcy)
        if (shake > 0.05f) canvas.translate(shakeX, shakeY)
        staticLayer?.let { canvas.drawBitmap(it, boardLeft - staticPad, boardTop - staticPad, null) }
        drawRubble(canvas); drawWind(canvas); drawCrumble(canvas)
        for ((i, h) in exits.withIndex()) {
            val col = GameThemes.MARBLE_COLORS[i % GameThemes.MARBLE_COLORS.size]
            val pulse = 1f + 0.08f * sin(animTime * 5f + i) + h.flash * 0.35f
            glowP.color = col; glowP.alpha = (60 + h.flash * 150).toInt().coerceAtMost(220)
            canvas.drawCircle(h.x, h.y, h.r * pulse + dp(3f), glowP)
            holeP.color = col; canvas.drawCircle(h.x, h.y, h.r, holeP)
            canvas.drawCircle(h.x, h.y, h.r * 0.55f, holeIn)
            val ls = if (cfg.highContrast) h.r * 0.95f else h.r * 0.75f
            if (cfg.highContrast) { strokeLetterP.textSize = ls; canvas.drawText(h.letter, h.x, h.y + h.r * 0.30f, strokeLetterP) }
            letterP.textSize = ls; canvas.drawText(h.letter, h.x, h.y + h.r * 0.30f, letterP) }
        for (t in traps) { val tp = 1f + 0.06f * sin(animTime * 7f + t.x)
            glowP.color = Color.parseColor("#B91C1C"); glowP.alpha = 50
            canvas.drawCircle(t.x, t.y, t.r * tp + dp(2f), glowP)
            canvas.drawCircle(t.x, t.y, t.r * tp, trapP); canvas.drawCircle(t.x, t.y, t.r * 0.55f * tp, holeIn) }
        for (p in pads) { val ang = when { p.dx == 1 -> 0f; p.dx == -1 -> 180f; p.dy == 1 -> 90f; else -> 270f }
            canvas.save(); canvas.translate(ccx(p.cx), ccy(p.cy)); canvas.rotate(ang)
            for (k in 0..2) { val off = (((animTime / 0.7f) + k / 3f) % 1f) * cellSize * 0.9f - cellSize * 0.45f
                padP.alpha = (255 * (1f - abs(off) / (cellSize * 0.45f))).toInt().coerceIn(0, 255)
                canvas.drawLine(off - dp(4f), 0f, off + dp(4f), 0f, padP) }
            padP.alpha = 255; canvas.restore() }
        for (b in belts) { val x = boardLeft + b.first * cellSize; val y = boardTop + b.second * cellSize
            tmpRect.set(x + dp(2f), y + dp(2f), x + cellSize - dp(2f), y + cellSize - dp(2f))
            canvas.drawRoundRect(tmpRect, dp(4f), dp(4f), beltBg)
            canvas.save(); canvas.clipRect(tmpRect)
            val gap = cellSize / 2f
            val dir = if (b.third.first != 0) b.third.first.toFloat() else b.third.second.toFloat()
            var off = (animTime * Tuning.BELT_SPEED * cellSize * dir) % gap; if (off < 0f) off += gap
            for (k in -1..2) { val s = off + k * gap
                if (b.third.second == 0) { val sx = x + s
                    if (sx > x + dp(2f) && sx < x + cellSize - dp(2f)) canvas.drawLine(sx, y + dp(4f), sx, y + cellSize - dp(4f), beltStr) }
                else { val sy = y + s
                    if (sy > y + dp(2f) && sy < y + cellSize - dp(2f)) canvas.drawLine(x + dp(4f), sy, x + cellSize - dp(4f), sy, beltStr) } }
            canvas.restore() }
        for (b in bumpers) { val pulse = 1f + 0.05f * sin(animTime * 6f + b.x) + b.flash * 0.35f
            glowP.color = Color.parseColor("#FBBF24"); glowP.alpha = (70 + b.flash * 150).toInt().coerceAtMost(230)
            canvas.drawCircle(b.x, b.y, b.rad * pulse + dp(3f), glowP)
            canvas.drawCircle(b.x, b.y, b.rad * pulse, bumpP); canvas.drawCircle(b.x, b.y, b.rad * 0.55f, bumpCore)
            if (b.flash > 0f) { bumpRing.alpha = (255 * b.flash).toInt()
                canvas.drawCircle(b.x, b.y, b.rad * (1f + (1f - b.flash) * 0.9f), bumpRing) } }
        portal?.let { p -> drawPortalEnd(canvas, p.ax, p.ay, p.r, Color.parseColor("#A855F7"))
            drawPortalEnd(canvas, p.bx, p.by, p.r, Color.parseColor("#FB923C")) }
        for ((i, cn) in coins.withIndex()) { if (cn.taken) continue
            val bob = sin(animTime * 3f + i) * dp(2f)
            canvas.drawCircle(cn.x, cn.y + bob, cellSize * 0.13f, coinP) }
        for ((i, k) in keys.withIndex()) { if (k.taken) continue
            val bob = sin(animTime * 3f + i * 1.7f) * dp(2f)
            canvas.drawCircle(k.x, k.y + bob, cellSize * 0.15f, keyRingP) }
        for ((i, pu) in powerUps.withIndex()) { if (pu.taken) continue
            val bob = sin(animTime * 3f + i * 2.3f) * dp(2f); val r = cellSize * 0.19f
            val col = when (pu.type) { PType.MAGNET -> Color.parseColor("#F87171")
                PType.SHIELD -> Color.parseColor("#60A5FA"); PType.SLOW -> Color.parseColor("#C084FC") }
            powRing.color = col; glowP.color = col
            glowP.alpha = (40 + 30 * sin(animTime * 4f + i)).toInt().coerceAtLeast(0)
            canvas.drawCircle(pu.x, pu.y + bob, r + dp(3f), glowP)
            canvas.drawCircle(pu.x, pu.y + bob, r, powBg); canvas.drawCircle(pu.x, pu.y + bob, r, powRing)
            letterP.textSize = r * 1.1f
            canvas.drawText(when (pu.type) { PType.MAGNET -> "M"; PType.SHIELD -> "S"; PType.SLOW -> "T" }, pu.x, pu.y + bob + r * 0.38f, letterP) }
        for (s in spinners) { for (k in 0 until s.arms) { val a = s.angle + k * (2f * PI.toFloat() / s.arms)
                canvas.save(); canvas.translate(s.cx, s.cy); canvas.rotate(a * 180f / PI.toFloat())
                tmpRect.set(-s.halfLen, -s.halfThick, s.halfLen, s.halfThick)
                canvas.drawRoundRect(tmpRect, s.halfThick, s.halfThick, spinP)
                tmpRect.set(-s.halfLen * 0.85f, -s.halfThick * 0.45f, s.halfLen * 0.85f, -s.halfThick * 0.1f)
                canvas.drawRoundRect(tmpRect, s.halfThick * 0.3f, s.halfThick * 0.3f, spinEdge)
                canvas.restore() }
            canvas.drawCircle(s.cx, s.cy, s.halfThick * 1.5f, hubP) }
        for (mv in movers) { val off = mv.off(animTime)
            val cx = mv.cx + if (mv.horiz) off else 0f; val cy = mv.cy + if (mv.horiz) 0f else off
            canvas.drawRoundRect(cx - mv.w / 2f + dp(2f), cy - mv.h / 2f + dp(3f), cx + mv.w / 2f + dp(2f), cy + mv.h / 2f + dp(3f), dp(6f), dp(6f), shadowP)
            canvas.drawRoundRect(cx - mv.w / 2f, cy - mv.h / 2f, cx + mv.w / 2f, cy + mv.h / 2f, dp(6f), dp(6f), moverP) }
        val rr = wallT / 2f
        for (f in flappers) { canvas.drawRoundRect(f.rect, rr, rr, flapperP)
            val cx = f.rect.centerX(); val cy = f.rect.centerY(); val a = dp(5f)
            if (f.dx != 0) { val ex = cx + f.dx * a; canvas.drawLine(cx - f.dx * a, cy, ex, cy, flapperA) }
            else { val ey = cy + f.dy * a; canvas.drawLine(cx, cy - f.dy * a, cx, ey, flapperA) } }
        for (g in gates) { if (g.unlocked) continue
            canvas.drawRoundRect(g.rect, rr, rr, gateP)
            val r = cellSize * 0.14f; canvas.drawCircle(g.rect.centerX(), g.rect.centerY(), r, lockP) }
        drawGhost(canvas)
        for ((idx, m) in marbles.withIndex()) { var scale = 1f; var alpha = 1f
            if (m.banked) { val age = (now - m.bankTime) / 450f
                if (age >= 1f) continue; scale = 1f - age; alpha = scale }
            val r = m.radius * scale
            canvas.save(); canvas.translate(m.x - r, m.y - r)
            if (m.banked) { marbleP.shader = null; marbleP.color = m.color }
            else { marbleP.color = Color.WHITE
                marbleP.shader = grads.getOrPut(m.color) { RadialGradient(r * 0.68f, r * 0.62f, r * 1.25f,
                    intArrayOf(ColorUtils.blendARGB(m.color, Color.WHITE, 0.55f), m.color,
                        ColorUtils.blendARGB(m.color, Color.BLACK, 0.5f)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) } }
            marbleP.alpha = (255 * alpha).toInt()
            canvas.drawCircle(r, r, r, marbleP); marbleP.shader = null
            specP.alpha = (150 * alpha).toInt(); canvas.drawCircle(r * 0.68f, r * 0.58f, r * 0.16f, specP)
            if (!m.banked && m.hasShield) { shieldRing.alpha = (200 * alpha).toInt(); canvas.drawCircle(r, r, r + dp(3f), shieldRing) }
            canvas.restore()
            if (cfg.highContrast && !m.banked) { marbleRim.alpha = (200 * alpha).toInt()
                canvas.drawCircle(m.x, m.y, r - dp(0.5f), marbleRim) }
            if (cfg.spectate) { nameP.color = m.color; nameP.alpha = 220
                canvas.drawText(GameThemes.COLOR_NAMES[idx % GameThemes.COLOR_NAMES.size], m.x, m.y - r - dp(3f), nameP) } }
        for (p in particles) { partP.color = p.color
            partP.alpha = (255 * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.size, partP) }
        for (f in floats) { floatP.color = f.color; floatP.alpha = (255 * (1f - f.age)).toInt()
            canvas.drawText(f.text, f.x, f.y - f.age * dp(30f), floatP) }
        floatP.alpha = 255; floatP.color = Color.WHITE
        if (cfg.fog) { val fb = fogBitmap; val fc = fogCanvas
            if (fb != null && fc != null) { fc.drawColor(Color.argb(244, 3, 5, 11), PorterDuff.Mode.SRC)
                for (m in marbles) if (!m.banked) cutLight(fc, m.x, m.y, cellSize * 2.6f, 255)
                for (h in exits) cutLight(fc, h.x, h.y, cellSize * 1.15f, 120)
                canvas.drawBitmap(fb, boardLeft - staticPad, boardTop - staticPad, null) } }
        canvas.restore()
        drawHud(canvas)
        if (joystickActive && cfg.controlMode != 0) { val maxR = dp(Tuning.JOYSTICK_RADIUS_DP)
            canvas.drawCircle(joyAx, joyAy, maxR, joyBase)
            canvas.drawCircle(joyAx + touchTx * maxR, joyAy + touchTy * maxR, dp(18f), joyKnob) }
        val cx = boardLeft + boardW / 2f; val cy = boardTop + boardH / 2f
        when (state) {
            State.COUNTDOWN -> canvas.drawText("${ceil(countdown).toInt().coerceIn(1, 3)}", cx, cy + dp(24f), bigP)
            State.WON -> { canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, overlayP)
                bigP.textSize = dp(40f)
                canvas.drawText(when { cfg.spectate -> context.getString(R.string.replay_end)
                    cfg.zen -> "ZEN \u2665"; else -> context.getString(R.string.solved_short) }, cx, cy + dp(14f), bigP)
                bigP.textSize = dp(64f) }
            State.PLAYING -> {
                if (animTime < slowUntil) { canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, slowTint)
                    canvas.drawText("SLO-MO", cx, boardTop + dp(18f), comboP) }
                if (rewinding) { overlayP.alpha = 90
                    canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, overlayP)
                    overlayP.alpha = 150; bigP.textSize = dp(30f)
                    canvas.drawText("\u25C0\u25C0", cx, cy + dp(10f), bigP); bigP.textSize = dp(64f) }
                else if (tiltPaused) { canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, overlayP)
                    bigP.textSize = dp(30f); canvas.drawText(context.getString(R.string.hold_flat), cx, cy + dp(10f), bigP)
                    bigP.textSize = dp(64f) }
                else if (goFlash > 0f) { bigP.textSize = dp(44f)
                    bigP.alpha = (255 * (goFlash / 0.6f)).toInt()
                    canvas.drawText("GO!", cx, cy + dp(16f), bigP); bigP.alpha = 255; bigP.textSize = dp(64f) }
                if (bossName != null && elapsedMs < 4000) { hintP.alpha = (220 * (1f - elapsedMs / 4000f)).toInt()
                    canvas.drawText("☠ $bossName", cx, boardTop + dp(46f), hintP); hintP.alpha = 255 } }
        }
        if (debugEnabled) { partP.color = Color.parseColor("#7CFF00")
            canvas.drawText("%4.1fms m=%d w=%d p=%d".format(
                java.util.Locale.US, frameEma, marbles.size, allWalls.size, particles.size),
                boardLeft, dp(80f), hudP) }
    }
    private val hintP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f); textAlign = Paint.Align.CENTER }
    private fun cutLight(fc: Canvas, x: Float, y: Float, r: Float, mid: Int) {
        fogClear.shader = RadialGradient(x, y, r,
            intArrayOf(Color.argb(mid, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
        fc.drawCircle(x, y, r, fogClear)
    }
    private fun drawRubble(c: Canvas) { for (rb in rubble) { val p = rb.pebbles; var i = 0
        while (i < p.size) { c.drawCircle(p[i], p[i + 1], p[i + 2], rubbleP); i += 3 } } }
    private fun drawCrumble(c: Canvas) { val rr = wallT / 2f
        for (wl in allWalls) { if (!wl.crumble) continue
            if (wl.broken) continue
            c.drawRoundRect(wl.rect, rr, rr, crumbleP)
            if (wl.hits >= 1) c.drawLine(wl.rect.centerX(), wl.rect.centerY(),
                wl.rect.centerX() + wallT, wl.rect.centerY() + dp(2f), crackP) } }
    private fun drawWind(c: Canvas) { for (w in windCells) {
            val px = boardLeft + w.first * cellSize; val py = boardTop + w.second * cellSize
            val ang = when { w.third.first == 1 -> 0f; w.third.first == -1 -> 180f; w.third.second == 1 -> 90f; else -> 270f }
            c.save(); c.clipRect(px, py, px + cellSize, py + cellSize)
            c.translate(px + cellSize / 2f, py + cellSize / 2f); c.rotate(ang)
            c.drawLine(-dp(4f), 0f, dp(4f), 0f, windP); c.restore() } }
    private fun drawPortalEnd(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        portalP.color = color; portalP.alpha = 70; c.drawCircle(x, y, r, portalP)
        portalArc.color = color; c.save(); c.translate(x, y); c.rotate(animTime * 100f)
        c.drawCircle(0f, 0f, r + dp(3f), portalArc); c.restore() }
    private fun drawGhost(c: Canvas) {
        if (!ghostOn || state != State.PLAYING || tiltPaused || rewinding) return
        val g = ghost ?: return; val st = ghostStride; if (st < 3) return
        val n = g.size / st; if (n < 2) return
        val t = elapsedMs / 1000f
        while (ghostIdx < n - 1 && g[(ghostIdx + 1) * st] <= t) ghostIdx++
        val i = ghostIdx * st; if (g[i] > t) return
        val j = if (ghostIdx < n - 1) i + st else -1
        val count = min((st - 1) / 2, GameThemes.MARBLE_COLORS.size)
        val r = marbles.firstOrNull()?.radius ?: cellSize * 0.27f
        for (k in 0 until count) { var gx = g[i + 1 + k * 2]; var gy = g[i + 2 + k * 2]
            if (j >= 0) { val span = g[j] - g[i]
                if (span > 1e-4f) { val f = ((t - g[i]) / span).coerceIn(0f, 1f)
                    gx += (g[j + 1 + k * 2] - gx) * f; gy += (g[j + 2 + k * 2] - gy) * f } }
            if (gx < boardLeft - cellSize || gx > boardLeft + boardW + cellSize ||
                gy < boardTop - cellSize || gy > boardTop + boardH + cellSize) continue
            ghostP.color = GameThemes.MARBLE_COLORS[k % GameThemes.MARBLE_COLORS.size]; ghostP.alpha = 55
            c.drawCircle(gx, gy, r, ghostP)
            if (k == 0) { ghostRing.alpha = 120; c.drawCircle(gx, gy, r + dp(2f), ghostRing) }
            nameP.color = GameThemes.MARBLE_COLORS[k % GameThemes.MARBLE_COLORS.size]; nameP.alpha = 170
            c.drawText(GameThemes.COLOR_NAMES[k % GameThemes.COLOR_NAMES.size], gx, gy - r - dp(3f), nameP)
            if (k < ghostBanked.size && !ghostBanked[k]) exits.getOrNull(k.coerceAtMost(exits.size - 1))?.let { ex ->
                if (sqrt((gx - ex.x).pow2() + (gy - ex.y).pow2()) < ex.r) { ghostBanked[k] = true
                    floats.add(FloatText(GameThemes.COLOR_NAMES[k % GameThemes.COLOR_NAMES.size] + " banked!",
                        ex.x, ex.y - ex.r - dp(10f), 0f, GameThemes.MARBLE_COLORS[k % GameThemes.MARBLE_COLORS.size]))
                    Sfx.coin() } } }
    }
    private fun ghostDelta(): Float? {
        val g = ghost ?: return null; val st = ghostStride
        if (st < 3 || marbles.isEmpty()) return null
        val n = g.size / st; if (n < 2) return null
        val t = elapsedMs / 1000f
        var idx = ghostIdx
        while (idx < n - 1 && g[(idx + 1) * st] <= t) idx++
        val i = idx * st; if (g[i] > t) return null
        var gx = g[i + 1]; var gy = g[i + 2]
        if (idx < n - 1) { val j = i + st; val span = g[j] - g[i]
            if (span > 1e-4f) { val f = ((t - g[i]) / span).coerceIn(0f, 1f)
                gx += (g[j + 1] - gx) * f; gy += (g[j + 2] - gy) * f } }
        val me = marbles[0]; if (me.banked) return null
        val dMe = sqrt((exits[me.exitIndex].x - me.x).pow2() + (exits[me.exitIndex].y - me.y).pow2())
        val dGh = sqrt((exits[0].x - gx).pow2() + (exits[0].y - gy).pow2())
        return (dGh - dMe) / cellSize
    }
    private fun drawHud(c: Canvas) {
        val right = boardLeft + boardW; val cx = boardLeft + boardW / 2f
        if (cfg.spectate) { c.drawText("\u25B6", boardLeft, dp(28f), hudP)
            c.drawText(formatTime(elapsedMs), cx, dp(28f), hudC) }
        else if (cfg.zen) c.drawText("ZEN", boardLeft, dp(28f), hudP)
        else c.drawText(formatTime(elapsedMs), boardLeft, dp(28f), hudP)
        val label = when { cfg.spectate -> context.getString(R.string.spectate_tag)
            cfg.daily -> context.getString(R.string.daily_short)
            isBoss -> "LV ${cfg.level} \u2620"; else -> "LV ${cfg.level}" }
        c.drawText(label, right, dp(28f), hudR)
        if (!cfg.zen && !cfg.spectate) c.drawText("$score", cx, dp(28f), hudC)
        if (!cfg.zen && !cfg.spectate && combo > 1 && comboTimer > 0f) {
            comboP.alpha = (140 + 100 * sin(animTime * 10f)).toInt().coerceIn(0, 255)
            c.drawText("COMBO x$combo", cx, dp(50f), comboP); comboP.alpha = 255 }
        if (ghostOn && !cfg.zen && !cfg.spectate) ghostDelta()?.let { d ->
            val ahead = d >= 0f
            deltaP.color = if (ahead) Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
            c.drawText((if (ahead) "\u25B2" else "\u25BC") + " %.1fm".format(java.util.Locale.US, abs(d)), right, dp(78f), deltaP) }
        var dx = boardLeft + dp(6f)
        for (m in marbles) { dotP.color = m.color; dotP.alpha = if (m.banked) 60 else 255
            c.drawCircle(dx, dp(50f), dp(4.5f), dotP); dx += dp(12f) }
        hudP.textSize = dp(12f); c.drawText("$bankedCount/${marbles.size}", dx + dp(4f), dp(55f), hudP)
        hudP.textSize = dp(16f)
        c.drawText("\u25C6 $coinsCollected / ${coins.size}", right, dp(54f), hudR)
    }
    private fun Float.pow2() = this * this
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    companion object {
        fun formatTime(ms: Long): String =
            String.format(Locale.US, "%d:%04.1f", ms / 60000, (ms % 60000) / 1000.0)
    }
}
