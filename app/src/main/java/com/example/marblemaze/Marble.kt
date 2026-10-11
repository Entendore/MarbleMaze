package com.example.marblemaze

class Marble(var x: Float, var y: Float, val radius: Float, val color: Int, var exitIndex: Int) {
    var vx = 0f; var vy = 0f
    var banked = false; var bankTime = 0L
    var spin = 0f; var spinPhase = 0f
    var portalCooldown = 0f; var trapGrace = 0f
    var lastImpactFx = 0L; var lastPadFx = 0f
    var magnetUntil = 0f; var hasShield = false
}
