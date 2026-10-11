package com.example.marblemaze

/** Single source of truth for gameplay tuning. */
object Tuning {
    const val TILT_FORCE = 2.3f; const val DAMPING = 2.6f; const val MAX_SPEED = 9f
    const val CONTACT_FRICTION = 0.996f
    const val RESTITUTION = 0.35f; const val BOUNCY_REST = 0.92f
    const val SPINNER_REST = 0.55f; const val MOVER_REST = 0.30f
    const val ROCK_REST = 0.5f; const val PEBBLE_REST = 0.5f; const val BUMPER_REST = 1.05f
    const val ICE_DAMP = 0.12f; const val MUD_DAMP = 4.2f; const val MUD_ACCEL = 0.55f
    const val RUBBLE_DAMP = 2.4f; const val RUBBLE_ACCEL = 0.85f
    const val PAD_FORCE = 3.4f; const val WIND_FORCE = 1.6f
    const val BELT_SPEED = 2.2f; const val BELT_GRIP = 5f; const val BUMPER_KICK = 6f
    const val STEEP_PAUSE_Z = 4.2f; const val FLAT_RESUME_Z = 5.6f
    const val MAGNET_DURATION = 10f; const val MAGNET_RANGE = 2.4f
    const val SLOW_DURATION = 4f; const val SLOW_FACTOR = 0.55f
    const val COMBO_WINDOW = 8f; const val COMBO_MAX = 9
    const val COIN_POINTS = 100; const val BANK_POINTS = 250
    const val REWIND_COST_PER_SEC = 100; const val REWIND_BUF_SIZE = 60; const val REWIND_SPEED = 2.0f
    const val DRAIN_FLIP_PERIOD_MS = 6000f
    const val CRUMBLE_CHANCE = 0.08f; const val CRUMBLE_HITS = 2; const val TRAP_PENALTY_MS = 3000L
    const val MAX_REC_FLOATS = 24_000; const val AUTOSAVE_INTERVAL_MS = 5000L
    const val MAX_ZOOM = 4f; const val JOYSTICK_RADIUS_DP = 80f
}
