package com.example.marblemaze

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

class LevelSelectActivity : AppCompatActivity() {
    companion object {
        const val MAX_LEVEL = 40
        private const val COLUMNS = 5
        private val MRF2 = "MRF2".toByteArray(Charsets.US_ASCII)
        private val MRF1 = "MRF1".toByteArray(Charsets.US_ASCII)
    }

    private lateinit var grid: LinearLayout
    private lateinit var progressText: TextView
    private var difficulty = 14; private var marbles = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_level_select)
        difficulty = intent.getIntExtra("difficulty", 14); marbles = intent.getIntExtra("marbles", 3)
        findViewById<TextView>(R.id.title_text).text =
            getString(R.string.choose_level_fmt, difficultyName(difficulty))
        progressText = findViewById(R.id.progress_text); grid = findViewById(R.id.level_grid)
        findViewById<Button>(R.id.daily_button).setOnClickListener { startDaily() }
        findViewById<Button>(R.id.import_button).setOnClickListener { showImport() }
        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }
    }
    override fun onResume() { super.onResume(); buildGrid() }

    private fun difficultyName(d: Int) = getString(when (d) {
        9 -> R.string.easy; 22 -> R.string.hard; 28 -> R.string.expert; else -> R.string.medium })
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun buildGrid() {
        grid.removeAllViews()
        val unlocked = Prefs.unlocked(this, difficulty)
        progressText.text = getString(R.string.progress_fmt, difficultyName(difficulty),
            unlocked, MAX_LEVEL, Prefs.totalStars(this, difficulty))
        var row: LinearLayout? = null
        for (lvl in 1..MAX_LEVEL) {
            if ((lvl - 1) % COLUMNS == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            val open = lvl <= unlocked; val stars = Prefs.stars(this, difficulty, lvl)
            val boss = lvl % 10 == 0
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(2f), dp(8f), dp(2f), dp(8f))
                background = GradientDrawable().apply { setColor(Color.parseColor(
                    when { !open -> "#161A26"; boss -> "#2A1420"; else -> "#1D2233" }))
                    cornerRadius = dp(8f).toFloat() }
                isEnabled = open; alpha = if (open) 1f else 0.45f
                addView(TextView(this@LevelSelectActivity).apply {
                    text = if (open) (if (boss) "\u2620 $lvl" else lvl.toString()) else "\uD83D\uDD12"
                    textSize = 20f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (boss) "#F87171" else "#FFFFFF")); gravity = Gravity.CENTER })
                addView(TextView(this@LevelSelectActivity).apply {
                    text = if (open) "\u2605".repeat(stars) + "\u2606".repeat(3 - stars) else ""
                    textSize = 11f; setTextColor(Color.parseColor("#FACC15")); gravity = Gravity.CENTER })
                contentDescription = "Level $lvl"
                if (open) {
                    setOnClickListener { startActivity(Intent(this@LevelSelectActivity, GameActivity::class.java)
                        .putExtra(GameActivity.EXTRA_MARBLES, marbles)
                        .putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty)
                        .putExtra(GameActivity.EXTRA_LEVEL, lvl)) }
                    setOnLongClickListener { showBoard(lvl); true } } }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3f), dp(3f), dp(3f), dp(3f)); cell.layoutParams = lp; row?.addView(cell) }
    }

    private fun showBoard(lvl: Int) {
        val times = Prefs.topTimes(this, difficulty, lvl)
        AlertDialog.Builder(this).setTitle(getString(R.string.board_title, lvl))
            .setMessage(if (times.isEmpty()) getString(R.string.board_empty)
                else times.mapIndexed { i, t -> "${i + 1}.  ${GameView.formatTime(t)}" }.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showImport() {
        val input = EditText(this).apply { hint = getString(R.string.import_hint); setSingleLine() }
        AlertDialog.Builder(this).setTitle(R.string.import_code).setView(input)
            .setPositiveButton(R.string.play_code) { _, _ ->
                val t = input.text.toString().trim()
                when { t.startsWith("MM8-") -> importMode(t)
                    t.startsWith(ReplayCodec.PREFIX) -> importReplay2(t.removePrefix(ReplayCodec.PREFIX).trim())
                    t.startsWith("MMREPLAY1:") -> importReplay1(t.removePrefix("MMREPLAY1:").trim())
                    t.startsWith("MM7-") -> importCode(t) } }
            .setNegativeButton(R.string.back, null).show()
    }

    private fun importMode(code: String) {
        val m = ModeCode.decode(code) ?: return toast(R.string.invalid_code)
        Prefs.saveMode(this, m)
        AlertDialog.Builder(this).setTitle(getString(R.string.mode_imported_title, m.name))
            .setMessage(Maze.GEN_NAMES.getOrElse(m.genMode) { "Classic" } +
                " \u00B7 ${m.difficulty} grid \u00B7 ${m.marbles} marbles")
            .setPositiveButton(R.string.play_code) { _, _ ->
                startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_MODE_ID, m.id)) }
            .setNegativeButton(R.string.back, null).show()
    }

    private fun importCode(s: String) {
        val parts = s.uppercase(Locale.US).split("-", " ").filter { it.isNotBlank() }
        val seed = if (parts.size == 5 && parts[0] == "MM7") parts[1].toLongOrNull(36) else null
        val diff = parts.getOrNull(2)?.toIntOrNull(); val lvl = parts.getOrNull(3)?.toIntOrNull()
        val mar = parts.getOrNull(4)?.toIntOrNull()
        if (seed == null || diff !in intArrayOf(9, 14, 22, 28) || lvl !in 1..MAX_LEVEL || mar !in 1..10)
            return toast(R.string.invalid_code)
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_MARBLES, mar!!).putExtra(GameActivity.EXTRA_DIFFICULTY, diff!!)
            .putExtra(GameActivity.EXTRA_LEVEL, lvl!!).putExtra(GameActivity.EXTRA_SEED, seed)
            .putExtra(GameActivity.EXTRA_CUSTOM, true))
    }

    private fun storeGhost(diff: Int, lvl: Int, mar: Int, floats: FloatArray) {
        val gb = ByteArray(floats.size * 4)
        ByteBuffer.wrap(gb).asFloatBuffer().put(floats)
        Prefs.setGhost(this, "${diff}_${lvl}_${mar}", Base64.encodeToString(gb, Base64.NO_WRAP))
    }

    private fun importReplay2(b64: String) {
        val r = ReplayCodec.decode(b64, intArrayOf(9, 14, 22, 28), MAX_LEVEL)
            ?: return toast(R.string.invalid_code)
        storeGhost(r.difficulty, r.level, r.marbles, r.floats)
        ReplayStore.seed = r.seed; ReplayStore.difficulty = r.difficulty
        ReplayStore.level = r.level; ReplayStore.marbles = r.marbles; ReplayStore.data = r.floats
        val cap = GameThemes.teamCaptain(r.difficulty, r.level, r.marbles)
        AlertDialog.Builder(this).setTitle(getString(R.string.replay_imported_title, cap))
            .setMessage(getString(R.string.replay_imported, r.level, difficultyName(r.difficulty), r.marbles))
            .setPositiveButton(R.string.watch_replay) { _, _ ->
                startActivity(Intent(this, SpectateActivity::class.java)) }
            .setNeutralButton(R.string.race_ghost) { _, _ ->
                startActivity(Intent(this, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_MARBLES, r.marbles)
                    .putExtra(GameActivity.EXTRA_DIFFICULTY, r.difficulty)
                    .putExtra(GameActivity.EXTRA_LEVEL, r.level)) }
            .setNegativeButton(R.string.back, null).show()
    }

    private fun importReplay1(b64: String) = try {
        val bb = ByteBuffer.wrap(Base64.decode(b64, Base64.NO_WRAP))
        val magic = ByteArray(4); bb.get(magic)
        if (!magic.contentEquals(MRF1)) throw IllegalArgumentException()
        val diff = bb.int; val lvl = bb.int; val mar = bb.int; val n = bb.int
        if (n <= 0 || n % (1 + 2 * mar) != 0 || bb.remaining() < n * 4) throw IllegalArgumentException()
        val floats = FloatArray(n); bb.asFloatBuffer().get(floats)
        storeGhost(diff, lvl, mar, floats)
        toast(R.string.replay_imported.let { getString(R.string.replay_imported, lvl, difficultyName(diff), mar) })
    } catch (_: Exception) { toast(R.string.invalid_code) }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun startDaily() {
        val cal = Calendar.getInstance()
        val key = String.format(Locale.US, "%04d%02d%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_MARBLES, 3).putExtra(GameActivity.EXTRA_DIFFICULTY, 14)
            .putExtra(GameActivity.EXTRA_LEVEL, 1).putExtra(GameActivity.EXTRA_DAILY_KEY, key)
            .putExtra(GameActivity.EXTRA_SEED, key.toLongOrNull()!! * 2654435761L))
    }
}
