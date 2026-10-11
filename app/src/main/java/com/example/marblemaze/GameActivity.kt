package com.example.marblemaze

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

class GameActivity : AppCompatActivity(), SensorEventListener, GameView.Listener {

    companion object {
        const val EXTRA_MARBLES = "extra_marbles"; const val EXTRA_DIFFICULTY = "extra_difficulty"
        const val EXTRA_LEVEL = "extra_level"; const val EXTRA_DAILY_KEY = "extra_daily_key"
        const val EXTRA_SEED = "extra_seed"; const val EXTRA_CUSTOM = "extra_custom"
        const val EXTRA_MODE_ID = "extra_mode_id"
        private const val CALIB = 30; private const val KEY_RUN = "run_state"
    }

    private lateinit var gameView: GameView
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val settings by lazy { GameSettings.from(this) }
    private var calib = 0; private var sumX = 0f; private var sumY = 0f; private var baseX = 0f; private var baseY = 0f
    private var modeId: String? = null; private var modeName = ""
    private var winShowing = false
    private val handler = Handler(Looper.getMainLooper())
    private val autosaveExec by lazy { Executors.newSingleThreadExecutor() }
    private val autosaveFile get() = File(filesDir, "autosave.json")

    private val backCb = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { if (!winShowing) showPause() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_game)
        onBackPressedDispatcher.addCallback(this, backCb)
        Sfx.enabled = settings.sound

        modeId = intent.getStringExtra(EXTRA_MODE_ID)
        val runMode = modeId?.let { id -> Prefs.modes(this).firstOrNull { it.id == id } }
        if (modeId != null && runMode == null) { finish(); return }
        modeName = runMode?.name ?: ""
        val marbleCount = runMode?.marbles ?: intent.getIntExtra(EXTRA_MARBLES, 3)
        val difficulty = runMode?.difficulty ?: intent.getIntExtra(EXTRA_DIFFICULTY, 14)
        val level = if (runMode != null) 1 else intent.getIntExtra(EXTRA_LEVEL, 1)
        val dailyKey = intent.getStringExtra(EXTRA_DAILY_KEY)
        val custom = intent.getBooleanExtra(EXTRA_CUSTOM, false) || runMode != null
        val seed = runMode?.fixedSeed?.takeIf { it != 0L }
            ?: intent.getLongExtra(EXTRA_SEED, System.nanoTime())

        gameView = GameView(this, GameView.Config(marbles = marbleCount, difficulty = difficulty,
            level = level, seed = seed, haptics = settings.haptics, daily = dailyKey != null,
            themeId = settings.themeId, fog = settings.fog,
            zen = settings.zen && dailyKey == null && !custom,
            controlMode = settings.controlMode, custom = custom,
            opts = runMode?.toOptions(), highContrast = settings.highContrast))
        gameView.listener = this
        findViewById<FrameLayout>(R.id.game_container).addView(gameView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))

        savedInstanceState?.getString(KEY_RUN)?.let {
            gameView.queueRestore(it)
            Toast.makeText(this, R.string.run_restored, Toast.LENGTH_SHORT).show() }
        if (savedInstanceState == null) {
            try { autosaveFile.takeIf { it.isFile }?.readText()?.let {
                autosaveFile.delete(); gameView.queueRestore(it)
                Toast.makeText(this, R.string.run_restored, Toast.LENGTH_SHORT).show() } } catch (_: Exception) {} }
        gameView.autosaveWriter = { json -> autosaveExec.execute { try {
            val tmp = File(filesDir, "autosave.tmp"); tmp.writeText(json)
            if (!tmp.renameTo(autosaveFile)) tmp.delete() } catch (_: Exception) {} } }

        findViewById<Button>(R.id.pause_button).setOnClickListener { showPause() }
        findViewById<Button>(R.id.restart_button).setOnClickListener { recal(); gameView.newGame(); gameView.resume() }
        findViewById<Button>(R.id.menu_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.rewind_button).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; gameView.beginRewind(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; gameView.endRewind(); v.performClick(); true }
                else -> false } }
    }

    override fun onResume() { super.onResume(); gameView.resume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        recal() }
    override fun onPause() { super.onPause(); gameView.pause(); sensorManager.unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null)
        autosaveExec.shutdownNow()
        if (isFinishing) autosaveFile.delete() }
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has)
        if (has) { WindowCompat.setDecorFitsSystemWindows(window, false)
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }

    private fun recal() { calib = 0; sumX = 0f; sumY = 0f }
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GRAVITY && e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = e.values[0]; val y = e.values[1]; gameView.setUpright(e.values[2])
        if (calib < CALIB) { sumX += x; sumY += y; calib++
            if (calib == CALIB) { baseX = sumX / CALIB; baseY = sumY / CALIB }
            return }
        val sy = if (settings.invertY) -y else y
        gameView.setTilt((x - baseX) * settings.sensitivity, sy * settings.sensitivity)
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    private fun showPause() {
        gameView.pause()
        AlertDialog.Builder(this).setTitle(R.string.paused)
            .setItems(arrayOf(getString(R.string.recalibrate), getString(R.string.restart_maze),
                getString(R.string.quit_to_menu))) { _, w ->
                when (w) { 0 -> { recal(); gameView.resume() }
                    1 -> { recal(); gameView.newGame(); gameView.resume() }
                    else -> finish() } }
            .setPositiveButton(R.string.resume) { _, _ -> gameView.resume() }
            .setOnCancelListener { gameView.resume() }.show()
    }

    override fun onMarbleBanked(banked: Int, total: Int) = Unit

    override fun onGameWon(result: GameResult) {
        autosaveFile.delete()
        Prefs.recordWin(this, result.stars, result.coins)
        Prefs.addPlaytime(this, result.timeMs, result.distCells)
        if (!result.daily && !result.zen && !result.custom && result.ghost != null)
            Prefs.setGhost(this, "${result.difficulty}_${result.level}_${result.marbleCount}",
                Base64.encodeToString(result.ghost, Base64.NO_WRAP))
        if (!result.daily && !result.zen && !result.custom && result.replay != null)
            Prefs.setMyWinReplay(this, "${result.difficulty}_${result.level}_${result.marbleCount}",
                result.seed, result.replay)
        val newHigh = if (!result.zen) Prefs.setHighScore(this, result.difficulty, result.score) else false
        if (result.daily) dailyKey()?.let { Prefs.recordDailyDone(this, it) }
        val rank = if (!result.zen && !result.custom && !result.daily)
            Prefs.addTopTime(this, result.difficulty, result.level, result.timeMs) else 0
        if (result.custom && modeId != null) {
            Prefs.recordModeWin(this, modeId!!)
            Prefs.addTopModeTime(this, modeId!!, result.timeMs) }
        toastAch(eval(result))
        val code = "MM7-${result.seed.toString(36).uppercase(Locale.US)}-${result.difficulty}-${result.level}-${result.marbleCount}"
        val stars = "\u2605".repeat(result.stars) + "\u2606".repeat(3 - result.stars)
        val title = when { result.zen -> getString(R.string.zen_solved)
            result.custom && modeName.isNotEmpty() -> getString(R.string.mode_solved)
            result.daily -> getString(R.string.daily_solved)
            result.custom -> getString(R.string.custom_solved)
            else -> getString(R.string.maze_solved) }
        val msg = buildList {
            if (result.custom && modeName.isNotEmpty()) add(getString(R.string.mode_cleared, modeName))
            else if (!result.zen) add(getString(R.string.solved_line, result.level) + "  " + stars)
            else add(stars)
            if (!result.zen) { add(getString(R.string.time_fmt,
                GameView.formatTime(result.timeMs), GameView.formatTime(result.parMs)))
                add(getString(R.string.coins_fmt, result.coins, result.coinsTotal))
                if (result.beatGhost) add(getString(R.string.beat_team_fmt,
                    GameThemes.teamCaptain(result.difficulty, result.level, result.marbleCount),
                    GameThemes.teamMembers(result.marbleCount).joinToString(", ")))
                add(if (newHigh) getString(R.string.score_new, result.score)
                    else getString(R.string.score_fmt, result.score, Prefs.highScore(this@GameActivity, result.difficulty)))
                if (rank in 1..5) add(getString(R.string.board_rank_fmt, rank))
                add(getString(R.string.code_fmt, code)) }
            else add(getString(R.string.zen_message, result.coins, result.coinsTotal)) }.joinToString("\n")
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            winShowing = true
            val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setNegativeButton(R.string.back_to_menu) { _, _ -> finish() }.setCancelable(false)
            if (result.zen) { b.setPositiveButton(R.string.next_level) { _, _ -> finish() } }
            else { b.setPositiveButton(if (modeId != null) R.string.replay else R.string.next_level) { _, _ ->
                    if (modeId != null) { startActivity(intent(this, 1, modeId)); finish() }
                    else { startActivity(intent(this, result.level + 1, null)); finish() } }
                val my = Prefs.myWinReplay(this,
                    "${result.difficulty}_${result.level}_${result.marbleCount}")
                if (my != null) b.setNeutralButton(R.string.watch_my_win) { _, _ ->
                    ReplayStore.seed = my.first; ReplayStore.difficulty = result.difficulty
                    ReplayStore.level = result.level; ReplayStore.marbles = result.marbleCount
                    ReplayStore.data = my.second
                    winShowing = false
                    startActivity(Intent(this, SpectateActivity::class.java)) }
                else b.setNeutralButton(R.string.share) { _, _ -> showShare(result, code, msg) } }
            b.show() }, 700)
    }

    private fun dailyKey(): String? = intent.getStringExtra(EXTRA_DAILY_KEY)
    private fun intent(level: Int, modeId: String?): Intent {
        val i = Intent(this, GameActivity::class.java)
            .putExtra(EXTRA_MARBLES, 3).putExtra(EXTRA_DIFFICULTY, 14).putExtra(EXTRA_LEVEL, level)
        if (modeId != null) i.putExtra(EXTRA_MODE_ID, modeId)
        return i
    }

    private fun eval(r: GameResult): List<String> {
        val got = mutableListOf<String>()
        fun g(id: String) { if (Achievements.unlock(this, id)) got.add(Achievements.title(id)) }
        g("first_win")
        if (Prefs.games(this) >= 10) g("ten_wins")
        if (Prefs.totalCoins(this) >= 25) g("coins_25")
        if (r.coinsTotal > 0 && r.coins >= r.coinsTotal) g("all_coins")
        if (r.trapsHit == 0) g("no_trap"); if (r.stars >= 3) g("three_star")
        if (r.beatGhost) g("beat_ghost"); if (r.maxCombo >= 5) g("combo_5")
        if (!r.zen && r.parMs > 0 && r.timeMs * 10 <= r.parMs * 7) g("speed_demon")
        if (r.daily) g("daily_done"); if (r.marbleCount >= 10) g("marble_10")
        if (r.difficulty >= 28) g("expert_win"); if (r.usedRewind) g("rewind")
        if (r.fog) g("fog_win"); if (r.touchUsed) g("touch_win"); if (r.zen) g("zen_win")
        if (Prefs.totalDist(this) >= 300f) g("roller"); if (r.boss) g("boss"); if (r.custom) g("code")
        if (Prefs.streak(this) >= 7) g("streak_7")
        if (r.bumperHits >= 25) g("pinball")
        if (r.custom && Prefs.modeWins(this).size >= 5) g("curator")
        return got
    }
    private fun toastAch(l: List<String>) { if (l.isNotEmpty())
        Toast.makeText(this, "\uD83C\uDFC6 ${l.joinToString(", ")}", Toast.LENGTH_LONG).show() }

    private fun showShare(r: GameResult, code: String, summary: String) {
        AlertDialog.Builder(this).setTitle(R.string.share)
            .setItems(arrayOf(getString(R.string.share_text), getString(R.string.share_qr),
                getString(R.string.export_replay))) { _, w ->
                when (w) { 0 -> { val text = getString(R.string.share_template, r.level,
                        "\u2605".repeat(r.stars) + "\u2606".repeat(3 - r.stars),
                        GameView.formatTime(r.timeMs), r.score, r.coins, r.coinsTotal) +
                        "\n" + getString(R.string.code_share_fmt, code)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, getString(R.string.share))) }
                    1 -> shareQr(code)
                    2 -> r.replay?.let { bytes ->
                        val payload = ReplayCodec.encode(ReplayCodec.Replay(
                            r.seed, r.difficulty, r.level, r.marbleCount,
                            ByteBuffer.wrap(bytes).asFloatBuffer().let { fb ->
                                FloatArray(bytes.size / 4) { fb.get(it) } }))
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, payload) },
                            getString(R.string.export_replay))) } } }
            .setNegativeButton(R.string.back, null).show()
    }

    private fun shareQr(code: String) = try {
        val m = QrCode.encode(code); val quiet = 2; val dim = m.size + quiet * 2
        val scale = (700 / dim).coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(dim * scale, dim * scale, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE); val p = Paint().apply { color = Color.BLACK }
        for (y in m.indices) for (x in m[0].indices) if (m[y][x]) c.drawRect(
            ((x + quiet) * scale).toFloat(), ((y + quiet) * scale).toFloat(),
            ((x + quiet + 1) * scale).toFloat(), ((y + quiet + 1) * scale).toFloat(), p)
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "challenge_qr.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, getString(R.string.share)))
    } catch (_: Exception) { Toast.makeText(this, R.string.qr_failed, Toast.LENGTH_SHORT).show() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        gameView.exportRunState()?.let { outState.putString(KEY_RUN, it) }
    }
}
