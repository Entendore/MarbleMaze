package com.example.marblemaze

import android.content.Context

object Achievements {
    private data class Def(val id: String, val title: String, val desc: String)
    val ALL = listOf(
        Def("first_win", "First Roll", "Win your first maze"),
        Def("ten_wins", "Marathoner", "Win 10 mazes"),
        Def("all_coins", "Treasure Hunter", "Collect every coin in a run"),
        Def("coins_25", "Coin Collector", "Collect 25 coins in total"),
        Def("no_trap", "Untouchable", "Win without falling in a trap"),
        Def("three_star", "Perfectionist", "Earn 3 stars on any level"),
        Def("beat_ghost", "Ghostbuster", "Beat your own ghost"),
        Def("combo_5", "On Fire", "Reach a x5 combo"),
        Def("speed_demon", "Speed Demon", "Finish 30% under par"),
        Def("daily_done", "Daily Devotee", "Complete a Daily Challenge"),
        Def("marble_10", "Crowd Control", "Win a 10-marble maze"),
        Def("expert_win", "Expert Hand", "Win on Expert"),
        Def("rewind", "Time Bender", "Use the rewind button"),
        Def("fog_win", "Lights Out", "Win with Fog of War on"),
        Def("touch_win", "Thumbs Up", "Win using touch controls"),
        Def("zen_win", "Inner Peace", "Clear a Zen garden"),
        Def("roller", "Long Hauler", "Roll 300 m in total"),
        Def("boss", "Boss Slayer", "Clear a boss arena"),
        Def("code", "Code Breaker", "Clear an imported challenge code"),
        Def("streak_7", "Week Warrior", "Reach a 7-day Daily streak"),
        Def("pinball", "Pinball Wizard", "25+ bumper kicks in one winning run"),
        Def("curator", "Mode Curator", "Win 5 different custom modes")
    )
    private val byId = ALL.associateBy { it.id }
    fun title(id: String) = byId[id]?.title ?: id
    fun unlock(ctx: Context, id: String): Boolean = byId.containsKey(id) && Prefs.unlockAchievement(ctx, id)
}
