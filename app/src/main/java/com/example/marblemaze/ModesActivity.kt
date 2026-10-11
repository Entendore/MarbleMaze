package com.example.marblemaze

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ModesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_modes)
        findViewById<Button>(R.id.new_mode_button).setOnClickListener {
            startActivity(Intent(this, ModeEditorActivity::class.java)) }
        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }
        list = findViewById(R.id.modes_list)
    }
    override fun onResume() { super.onResume(); build() }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun summary(m: RunMode): String {
        val parts = mutableListOf(Maze.GEN_NAMES.getOrElse(m.genMode) { "Classic" },
            "${m.difficulty} grid", "${m.marbles} marbles")
        if (m.fixedSeed != 0L) parts.add(getString(R.string.seed_label))
        return parts.joinToString("  \u00B7  ")
    }

    private fun build() {
        list.removeAllViews()
        val modes = Prefs.modes(this).toMutableList()
        val wk = ModeOfWeek.weekKey(); val motwId = ModeOfWeek.idFor(wk)
        if (modes.none { it.id == motwId }) Prefs.saveMode(this, ModeOfWeek.build(wk))
        val fresh = Prefs.modes(this)
        val pinned = fresh.firstOrNull { it.id == motwId }
        val rest = fresh.filterNot { it.id == motwId }
        if (pinned != null) addRow(pinned, true)
        if (rest.isEmpty() && pinned == null) list.addView(TextView(this).apply {
            text = getString(R.string.modes_empty); textSize = 14f
            setTextColor(Color.parseColor("#9AA3B2")); setPadding(0, dp(40f), 0, 0) })
        for (m in rest) addRow(m, false)
    }

    private fun addRow(m: RunMode, pinned: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(4f), dp(10f))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1D2233"))
                cornerRadius = dp(10f).toFloat()
                if (pinned) setStroke(dp(2), Color.parseColor("#D9A05B")) }
            setOnClickListener { startActivity(Intent(this@ModesActivity, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_MODE_ID, m.id)) }
            setOnLongClickListener { showBoard(m); true } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (pinned) addView(TextView(this@ModesActivity).apply {
                text = getString(R.string.motw_badge); textSize = 10f; setTextColor(Color.parseColor("#D9A05B")) })
            addView(TextView(this@ModesActivity).apply { text = m.name; textSize = 17f
                setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            addView(TextView(this@ModesActivity).apply { text = summary(m); textSize = 12f
                setTextColor(Color.parseColor("#9AA3B2")) })
            val b = Prefs.modeBestMs(this@ModesActivity, m.id)
            if (b != 0L) addView(TextView(this@ModesActivity).apply {
                text = getString(R.string.mode_best_fmt, GameView.formatTime(b)); textSize = 12f
                setTextColor(Color.parseColor("#FACC15")) }) }
        row.addView(col)
        fun btn(label: String, action: () -> Unit) = row.addView(Button(this).apply {
            text = label; minWidth = dp(44f); setOnClickListener { action() } })
        btn("\u270E") { startActivity(Intent(this, ModeEditorActivity::class.java)
            .putExtra(ModeEditorActivity.EXTRA_EDIT_ID, m.id)) }
        btn("\u29C9") { startActivity(Intent(this, ModeEditorActivity::class.java)
            .putExtra(ModeEditorActivity.EXTRA_EDIT_ID, m.id)
            .putExtra(ModeEditorActivity.EXTRA_DUPLICATE, true)) }
        btn("\u2B06") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.mode_share_fmt, m.name) + "\n" + ModeCode.encode(m)) },
            getString(R.string.share))) }
        btn("\u2715") { AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_mode_title, m.name))
            .setMessage(R.string.delete_mode_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                Prefs.deleteMode(this, m.id)
                Toast.makeText(this, getString(R.string.mode_deleted, m.name), Toast.LENGTH_SHORT).show()
                build() }.setNegativeButton(R.string.back, null).show() } }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6f), 0, dp(6f)); list.addView(row, lp)
    }

    private fun showBoard(m: RunMode) {
        val times = Prefs.modeTopTimes(this, m.id)
        AlertDialog.Builder(this).setTitle(getString(R.string.mode_board_title, m.name))
            .setMessage(if (times.isEmpty()) getString(R.string.board_empty)
                else times.mapIndexed { i, t -> "${i + 1}.  ${GameView.formatTime(t)}" }.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
