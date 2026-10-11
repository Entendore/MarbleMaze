package com.example.marblemaze

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var statsText: TextView; private lateinit var bestText: TextView
    private lateinit var difficultyGroup: RadioGroup; private lateinit var themeGroup: RadioGroup
    private lateinit var controlGroup: RadioGroup
    private var marbleCount = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        statsText = findViewById(R.id.stats_text); bestText = findViewById(R.id.best_text)
        difficultyGroup = findViewById(R.id.difficulty_group)
        themeGroup = findViewById(R.id.theme_group); controlGroup = findViewById(R.id.control_group)

        when (Prefs.theme(this)) { 1 -> themeGroup.check(R.id.theme_neon); 2 -> themeGroup.check(R.id.theme_mono)
            3 -> themeGroup.check(R.id.theme_system); else -> themeGroup.check(R.id.theme_classic) }
        if (Build.VERSION.SDK_INT < 31) findViewById<RadioButton>(R.id.theme_system).apply { isEnabled = false; alpha = 0.4f }
        themeGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setTheme(this, when (id) { R.id.theme_neon -> 1; R.id.theme_mono -> 2; R.id.theme_system -> 3; else -> 0 }) }

        when (Prefs.controlMode(this)) { 1 -> controlGroup.check(R.id.radio_touch)
            2 -> controlGroup.check(R.id.radio_both); else -> controlGroup.check(R.id.radio_tilt) }
        controlGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setControlMode(this, when (id) { R.id.radio_touch -> 1; R.id.radio_both -> 2; else -> 0 }) }

        val seek = findViewById<SeekBar>(R.id.marble_seek)
        val label = findViewById<TextView>(R.id.marble_label)
        seek.max = 9; seek.progress = 2
        label.text = getString(R.string.marble_count_fmt, marbleCount)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                marbleCount = p + 1; label.text = getString(R.string.marble_count_fmt, marbleCount) }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit })

        fun currentDifficulty() = when (difficultyGroup.checkedRadioButtonId) {
            R.id.radio_easy -> 9; R.id.radio_hard -> 22; R.id.radio_expert -> 28; else -> 14 }
        difficultyGroup.setOnCheckedChangeListener { _, _ -> refreshBest(currentDifficulty()) }

        val sensSeek = findViewById<SeekBar>(R.id.sensitivity_seek)
        val sens = Prefs.sensitivity(this)
        sensSeek.progress = (((sens - 0.5f) / 1.5f) * 100).roundToInt().coerceIn(0, 100)
        sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                Prefs.setSensitivity(this@MainActivity, 0.5f + p / 100f * 1.5f) }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit })

        bind(R.id.sound_check, Prefs.sound(this)) { Prefs.setSound(this, it); Sfx.enabled = it }
        bind(R.id.haptics_check, Prefs.haptics(this)) { Prefs.setHaptics(this, it) }
        bind(R.id.ghost_check, Prefs.ghostEnabled(this)) { Prefs.setGhostEnabled(this, it) }
        bind(R.id.fog_check, Prefs.fog(this)) { Prefs.setFog(this, it) }
        bind(R.id.zen_check, Prefs.zen(this)) { Prefs.setZen(this, it) }
        bind(R.id.hc_check, Prefs.highContrast(this)) { Prefs.setHighContrast(this, it) }
        bind(R.id.inverty_check, Prefs.invertY(this)) { Prefs.setInvertY(this, it) }

        findViewById<Button>(R.id.achievements_button).setOnClickListener { showAchievements() }
        findViewById<Button>(R.id.start_button).setOnClickListener {
            startActivity(Intent(this, LevelSelectActivity::class.java)
                .putExtra("difficulty", currentDifficulty()).putExtra("marbles", marbleCount)) }
        findViewById<Button>(R.id.daily_button).setOnClickListener { startDaily() }
        findViewById<Button>(R.id.custom_modes_button).setOnClickListener {
            startActivity(Intent(this, ModesActivity::class.java)) }
    }

    private fun bind(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        val cb = findViewById<CheckBox>(id); cb.isChecked = value
        cb.setOnCheckedChangeListener { _, c -> set(c) }
    }

    private fun showAchievements() {
        val unlocked = Prefs.achievements(this)
        val sb = StringBuilder()
        for (a in Achievements.ALL)
            sb.append(if (a.id in unlocked) "\u2705 " else "\u2B1C ")
                .append(a.title).append(" — ").append(a.desc).append('\n')
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.achievements_fmt, unlocked.size, Achievements.ALL.size))
            .setMessage(sb.toString().trimEnd()).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun startDaily() {
        val cal = Calendar.getInstance()
        val key = String.format(Locale.US, "%04d%02d%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_MARBLES, 3).putExtra(GameActivity.EXTRA_DIFFICULTY, 14)
            .putExtra(GameActivity.EXTRA_LEVEL, 1)
            .putExtra(GameActivity.EXTRA_DAILY_KEY, key)
            .putExtra(GameActivity.EXTRA_SEED, key.toLongOrNull()!! * 2654435761L))
    }

    override fun onResume() {
        super.onResume()
        val diff = when (difficultyGroup.checkedRadioButtonId) {
            R.id.radio_easy -> 9; R.id.radio_hard -> 22; R.id.radio_expert -> 28; else -> 14 }
        val best = Prefs.bestMs(this, diff)
        bestText.text = if (best == 0L) getString(R.string.best_none)
            else getString(R.string.best_time_fmt, GameView.formatTime(best))
        statsText.text = getString(R.string.stats_fmt, Prefs.games(this), Prefs.totalStars(this),
            Prefs.totalCoins(this), Prefs.totalDist(this).toInt()) +
            if (Prefs.streak(this) > 0) "   \uD83D\uDD25 ${Prefs.streak(this)}" else ""
    }
}
