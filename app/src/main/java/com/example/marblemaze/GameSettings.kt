package com.example.marblemaze

import android.content.Context

/** One-shot snapshot of user settings — never read SharedPreferences in hot paths. */
data class GameSettings(
    val sensitivity: Float, val sound: Boolean, val haptics: Boolean, val themeId: Int,
    val fog: Boolean, val zen: Boolean, val controlMode: Int,
    val ghost: Boolean, val highContrast: Boolean, val invertY: Boolean
) {
    companion object {
        fun from(ctx: Context) = GameSettings(
            sensitivity = Prefs.sensitivity(ctx), sound = Prefs.sound(ctx),
            haptics = Prefs.haptics(ctx), themeId = Prefs.theme(ctx),
            fog = Prefs.fog(ctx), zen = Prefs.zen(ctx), controlMode = Prefs.controlMode(ctx),
            ghost = Prefs.ghostEnabled(ctx), highContrast = Prefs.highContrast(ctx), invertY = Prefs.invertY(ctx))
    }
}
