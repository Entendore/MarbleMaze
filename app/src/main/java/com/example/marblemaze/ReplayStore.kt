package com.example.marblemaze

/** In-memory handoff of replay data to SpectateActivity (avoids Intent limits). */
object ReplayStore {
    var seed: Long = 0L; var difficulty: Int = 14; var level: Int = 1; var marbles: Int = 3
    var data: FloatArray? = null
    fun clear() { data = null }
}
