package com.example.marblemaze

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class SpectateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_game)
        val data = ReplayStore.data
        if (data == null || data.size < 6) { finish(); return }
        Sfx.enabled = Prefs.sound(this)
        val v = GameView(this, GameView.Config(
            marbles = ReplayStore.marbles, difficulty = ReplayStore.difficulty,
            level = ReplayStore.level, seed = ReplayStore.seed, haptics = false,
            themeId = Prefs.theme(this), spectate = true, spectateData = data))
        findViewById<FrameLayout>(R.id.game_container).addView(v,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        findViewById<Button>(R.id.pause_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.restart_button).setOnClickListener { v.newGame() }
        findViewById<Button>(R.id.menu_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.rewind_button).visibility = View.GONE
    }
    override fun onResume() { super.onResume() }
    override fun onDestroy() { super.onDestroy(); if (isFinishing) ReplayStore.clear() }
}
