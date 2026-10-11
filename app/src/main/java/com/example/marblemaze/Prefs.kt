package com.example.marblemaze

import android.content.Context

object Prefs {
    private const val FILE = "marble_maze"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sensitivity(ctx: Context): Float = p(ctx).getFloat("sensitivity", 1.0f)
    fun setSensitivity(ctx: Context, v: Float) = p(ctx).edit().putFloat("sensitivity", v).apply()
    fun sound(ctx: Context): Boolean = p(ctx).getBoolean("sound", true)
    fun setSound(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("sound", v).apply()
    fun haptics(ctx: Context): Boolean = p(ctx).getBoolean("haptics", true)
    fun setHaptics(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("haptics", v).apply()
    fun ghostEnabled(ctx: Context): Boolean = p(ctx).getBoolean("ghost_on", true)
    fun setGhostEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ghost_on", v).apply()
    fun theme(ctx: Context): Int = p(ctx).getInt("theme", 0)
    fun setTheme(ctx: Context, id: Int) = p(ctx).edit().putInt("theme", id).apply()
    fun controlMode(ctx: Context): Int = p(ctx).getInt("control", 0)
    fun setControlMode(ctx: Context, v: Int) = p(ctx).edit().putInt("control", v).apply()
    fun fog(ctx: Context): Boolean = p(ctx).getBoolean("fog", false)
    fun setFog(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fog", v).apply()
    fun zen(ctx: Context): Boolean = p(ctx).getBoolean("zen", false)
    fun setZen(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("zen", v).apply()
    fun invertY(ctx: Context): Boolean = p(ctx).getBoolean("invertY", false)
    fun setInvertY(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("invertY", v).apply()
    fun highContrast(ctx: Context): Boolean = p(ctx).getBoolean("hc", false)
    fun setHighContrast(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("hc", v).apply()

    fun bestMs(ctx: Context, difficulty: Int): Long = p(ctx).getLong("best_$difficulty", 0L)
    fun setBestMs(ctx: Context, difficulty: Int, ms: Long) = p(ctx).edit().putLong("best_$difficulty", ms).apply()
    fun stars(ctx: Context, difficulty: Int, level: Int): Int = p(ctx).getInt("stars_${difficulty}_$level", 0)
    fun setStars(ctx: Context, difficulty: Int, level: Int, s: Int) {
        if (s > stars(ctx, difficulty, level)) p(ctx).edit().putInt("stars_${difficulty}_$level", s).apply()
    }
    fun totalStars(ctx: Context, difficulty: Int): Int {
        var sum = 0; for (l in 1..40) sum += stars(ctx, difficulty, l); return sum
    }
    fun unlocked(ctx: Context, difficulty: Int): Int = p(ctx).getInt("unlocked_$difficulty", 1)
    fun unlock(ctx: Context, difficulty: Int, level: Int) {
        if (level > unlocked(ctx, difficulty)) p(ctx).edit().putInt("unlocked_$difficulty", level).apply()
    }
    fun dailyBestMs(ctx: Context, key: String): Long = p(ctx).getLong("daily_$key", 0L)
    fun setDailyBestMs(ctx: Context, key: String, ms: Long) = p(ctx).edit().putLong("daily_$key", ms).apply()
    fun highScore(ctx: Context, difficulty: Int): Int = p(ctx).getInt("hs_$difficulty", 0)
    fun setHighScore(ctx: Context, difficulty: Int, score: Int): Boolean {
        val old = highScore(ctx, difficulty)
        if (score <= old) return false
        p(ctx).edit().putInt("hs_$difficulty", score).apply(); return true
    }
    fun codeBestMs(ctx: Context, code: String): Long = p(ctx).getLong("code_${code.hashCode()}", 0L)
    fun setCodeBestMs(ctx: Context, code: String, ms: Long): Boolean {
        val old = codeBestMs(ctx, code)
        if (old != 0L && ms >= old) return false
        p(ctx).edit().putLong("code_${code.hashCode()}", ms).apply(); return true
    }
    fun ghost(ctx: Context, key: String): String? = p(ctx).getString("ghost_$key", null)
    fun setGhost(ctx: Context, key: String, b64: String) = p(ctx).edit().putString("ghost_$key", b64).apply()

    fun achievements(ctx: Context): Set<String> = p(ctx).getStringSet("achievements", emptySet()) ?: emptySet()
    fun unlockAchievement(ctx: Context, id: String): Boolean {
        val set = achievements(ctx).toMutableSet()
        if (id in set) return false
        set.add(id); p(ctx).edit().putStringSet("achievements", set).apply(); return true
    }

    fun games(ctx: Context) = p(ctx).getInt("stat_games", 0)
    fun totalCoins(ctx: Context) = p(ctx).getInt("stat_coins", 0)
    fun totalStars(ctx: Context) = p(ctx).getInt("stat_stars", 0)
    fun recordWin(ctx: Context, s: Int, c: Int) {
        p(ctx).edit().putInt("stat_games", games(ctx) + 1)
            .putInt("stat_coins", totalCoins(ctx) + c).putInt("stat_stars", totalStars(ctx) + s).apply()
    }
    fun totalDist(ctx: Context): Float = p(ctx).getFloat("dist", 0f)
    fun playtimeMs(ctx: Context): Long = p(ctx).getLong("playtime", 0L)
    fun addPlaytime(ctx: Context, ms: Long, distCells: Float) {
        p(ctx).edit().putLong("playtime", playtimeMs(ctx) + ms)
            .putFloat("dist", totalDist(ctx) + distCells).apply()
    }

    fun streak(ctx: Context): Int = p(ctx).getInt("streak", 0)
    fun lastDailyDone(ctx: Context): String = p(ctx).getString("last_daily", "") ?: ""
    fun recordDailyDone(ctx: Context, todayKey: String) {
        if (lastDailyDone(ctx) == todayKey) return
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val yesterday = String.format(java.util.Locale.US, "%04d%02d%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
        val next = if (lastDailyDone(ctx) == yesterday) streak(ctx) + 1 else 1
        p(ctx).edit().putInt("streak", next).putString("last_daily", todayKey).apply()
    }

    fun topTimes(ctx: Context, difficulty: Int, level: Int): List<Long> =
        (p(ctx).getString("top_${difficulty}_$level", null) ?: "")
            .split(",").mapNotNull { it.toLongOrNull() }.sorted()
    fun addTopTime(ctx: Context, difficulty: Int, level: Int, ms: Long): Int {
        val list = (topTimes(ctx, difficulty, level) + ms).sorted().take(5)
        p(ctx).edit().putString("top_${difficulty}_$level", list.joinToString(",")).apply()
        return list.indexOf(ms) + 1
    }

    fun modes(ctx: Context): List<RunMode> {
        val set = p(ctx).getStringSet("custom_modes", emptySet()) ?: emptySet()
        return set.mapNotNull { s -> try { RunMode.fromJson(org.json.JSONObject(s)) } catch (_: Exception) { null } }
            .sortedBy { it.name.lowercase() }
    }
    fun saveMode(ctx: Context, mode: RunMode) {
        val set = (p(ctx).getStringSet("custom_modes", emptySet()) ?: emptySet())
            .filterNot { it.contains("\"id\":\"${mode.id}\"") }.toMutableSet()
        set.add(mode.toJson().toString())
        p(ctx).edit().putStringSet("custom_modes", set).apply()
    }
    fun deleteMode(ctx: Context, id: String) {
        val set = (p(ctx).getStringSet("custom_modes", emptySet()) ?: emptySet())
            .filterNot { it.contains("\"id\":\"$id\"") }.toSet()
        p(ctx).edit().putStringSet("custom_modes", set).apply()
    }
    fun modeBestMs(ctx: Context, id: String): Long = p(ctx).getLong("modebest_$id", 0L)
    fun setModeBestMs(ctx: Context, id: String, ms: Long): Boolean {
        val old = modeBestMs(ctx, id)
        if (old != 0L && ms >= old) return false
        p(ctx).edit().putLong("modebest_$id", ms).apply(); return true
    }
    fun modeWins(ctx: Context): Set<String> = p(ctx).getStringSet("mode_wins", emptySet()) ?: emptySet()
    fun recordModeWin(ctx: Context, id: String): Int {
        val set = modeWins(ctx).toMutableSet(); set.add(id)
        p(ctx).edit().putStringSet("mode_wins", set).apply(); return set.size
    }
    fun modeTopTimes(ctx: Context, modeId: String): List<Long> =
        (p(ctx).getString("topmode_$modeId", null) ?: "").split(",").mapNotNull { it.toLongOrNull() }.sorted()
    fun addTopModeTime(ctx: Context, modeId: String, ms: Long): Int {
        val list = (modeTopTimes(ctx, modeId) + ms).sorted().take(5)
        p(ctx).edit().putString("topmode_$modeId", list.joinToString(",")).apply()
        return list.indexOf(ms) + 1
    }

    fun setMyWinReplay(ctx: Context, key: String, seed: Long, samples: ByteArray) {
        p(ctx).edit().putLong("mywin_seed_$key", seed)
            .putString("mywin_$key", android.util.Base64.encodeToString(samples, android.util.Base64.NO_WRAP)).apply()
    }
    fun myWinReplay(ctx: Context, key: String): Pair<Long, ByteArray>? {
        val b64 = p(ctx).getString("mywin_$key", null) ?: return null
        val seed = p(ctx).getLong("mywin_seed_$key", 0L)
        return try { seed to android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
    }
}
