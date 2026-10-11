package com.example.marblemaze

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class ModeEditorActivity : AppCompatActivity() {
    companion object { const val EXTRA_EDIT_ID = "edit_id"; const val EXTRA_DUPLICATE = "duplicate" }

    private var genMode = Maze.GEN_CLASSIC; private var difficulty = 14
    private var marbles = 3; private var editId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_mode_editor)
        val nameEdit = findViewById<EditText>(R.id.mode_name)
        val seedEdit = findViewById<EditText>(R.id.mode_seed)
        val duplicate = intent.getBooleanExtra(EXTRA_DUPLICATE, false)
        val existing = intent.getStringExtra(EXTRA_EDIT_ID)
            ?.let { id -> Prefs.modes(this).firstOrNull { it.id == id } }
        if (existing != null) {
            editId = if (duplicate) null else existing.id
            genMode = existing.genMode; difficulty = existing.difficulty; marbles = existing.marbles
            nameEdit.setText(if (duplicate) existing.name + " copy" else existing.name)
            if (existing.fixedSeed != 0L) seedEdit.setText(existing.fixedSeed.toString())
            when (existing.genMode) { Maze.GEN_CAVES -> findViewById<RadioGroup>(R.id.gen_group).check(R.id.gen_caves)
                Maze.GEN_SPIRAL -> findViewById<RadioGroup>(R.id.gen_group).check(R.id.gen_spiral)
                Maze.GEN_CHAMBERS -> findViewById<RadioGroup>(R.id.gen_group).check(R.id.gen_chambers)
                Maze.GEN_OPEN -> findViewById<RadioGroup>(R.id.gen_group).check(R.id.gen_open)
                Maze.GEN_RIVERS -> findViewById<RadioGroup>(R.id.gen_group).check(R.id.gen_rivers) }
            bind(R.id.opt_traps, existing.traps); bind(R.id.opt_pads, existing.pads)
            bind(R.id.opt_belts, existing.belts); bind(R.id.opt_winds, existing.winds)
            bind(R.id.opt_movers, existing.movers); bind(R.id.opt_flappers, existing.flappers)
            bind(R.id.opt_spinners, existing.spinners); bind(R.id.opt_portals, existing.portals)
            bind(R.id.opt_gates, existing.gates); bind(R.id.opt_crumble, existing.crumble)
            bind(R.id.opt_terrain, existing.terrain); bind(R.id.opt_coins, existing.coins)
            bind(R.id.opt_power, existing.powerUps); bind(R.id.opt_bumpers, existing.bumpers) }
        findViewById<RadioGroup>(R.id.gen_group).setOnCheckedChangeListener { _, id ->
            genMode = when (id) { R.id.gen_caves -> Maze.GEN_CAVES; R.id.gen_spiral -> Maze.GEN_SPIRAL
                R.id.gen_chambers -> Maze.GEN_CHAMBERS; R.id.gen_open -> Maze.GEN_OPEN
                R.id.gen_rivers -> Maze.GEN_RIVERS; else -> Maze.GEN_CLASSIC } }
        val diffSeek = findViewById<SeekBar>(R.id.diff_seek)
        val diffLabel = findViewById<TextView>(R.id.diff_label_value)
        diffSeek.max = 22; diffSeek.progress = (difficulty - 8).coerceIn(0, 22)
        diffLabel.text = getString(R.string.grid_size_fmt, difficulty)
        diffSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                difficulty = p + 8; diffLabel.text = getString(R.string.grid_size_fmt, difficulty) }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit })
        val marSeek = findViewById<SeekBar>(R.id.mar_seek)
        val marLabel = findViewById<TextView>(R.id.mar_label_value)
        marSeek.max = 9; marSeek.progress = marbles - 1
        marLabel.text = getString(R.string.marble_count_fmt, marbles)
        marSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                marbles = p + 1; marLabel.text = getString(R.string.marble_count_fmt, marbles) }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit })
        findViewById<Button>(R.id.save_button).setOnClickListener {
            val name = nameEdit.text.toString().trim().ifEmpty { getString(R.string.default_mode_name) }
            Prefs.saveMode(this, RunMode(
                id = editId ?: UUID.randomUUID().toString(), name = name, genMode = genMode,
                difficulty = difficulty, marbles = marbles,
                fixedSeed = seedEdit.text.toString().trim().toLongOrNull() ?: 0L,
                traps = findViewById<CheckBox>(R.id.opt_traps).isChecked,
                pads = findViewById<CheckBox>(R.id.opt_pads).isChecked,
                belts = findViewById<CheckBox>(R.id.opt_belts).isChecked,
                winds = findViewById<CheckBox>(R.id.opt_winds).isChecked,
                movers = findViewById<CheckBox>(R.id.opt_movers).isChecked,
                flappers = findViewById<CheckBox>(R.id.opt_flappers).isChecked,
                spinners = findViewById<CheckBox>(R.id.opt_spinners).isChecked,
                portals = findViewById<CheckBox>(R.id.opt_portals).isChecked,
                gates = findViewById<CheckBox>(R.id.opt_gates).isChecked,
                crumble = findViewById<CheckBox>(R.id.opt_crumble).isChecked,
                terrain = findViewById<CheckBox>(R.id.opt_terrain).isChecked,
                coins = findViewById<CheckBox>(R.id.opt_coins).isChecked,
                powerUps = findViewById<CheckBox>(R.id.opt_power).isChecked,
                bumpers = findViewById<CheckBox>(R.id.opt_bumpers).isChecked))
            Toast.makeText(this, getString(R.string.mode_saved, name), Toast.LENGTH_SHORT).show()
            finish() }
        findViewById<Button>(R.id.cancel_button).setOnClickListener { finish() }
    }
    private fun bind(id: Int, v: Boolean) { findViewById<CheckBox>(id).isChecked = v }
}
