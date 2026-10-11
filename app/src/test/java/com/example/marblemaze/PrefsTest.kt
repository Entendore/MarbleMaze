package com.example.marblemaze

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Before fun clear() = ctx.getSharedPreferences("marble_maze", 0).edit().clear().commit()

    @Test fun `modes save update delete`() {
        val m = RunMode(name = "A"); Prefs.saveMode(ctx, m)
        Prefs.saveMode(ctx, m.copy(difficulty = 20))
        assertEquals(1, Prefs.modes(ctx).size); assertEquals(20, Prefs.modes(ctx).first().difficulty)
        Prefs.deleteMode(ctx, m.id); assertTrue(Prefs.modes(ctx).isEmpty()) }
    @Test fun `corrupt json skipped`() {
        ctx.getSharedPreferences("marble_maze", 0).edit()
            .putStringSet("custom_modes", setOf("{not json")).commit()
        assertTrue(Prefs.modes(ctx).isEmpty()) }
    @Test fun `top times sorted capped five`() {
        Prefs.addTopTime(ctx, 14, 1, 500L); Prefs.addTopTime(ctx, 14, 1, 300L)
        Prefs.addTopTime(ctx, 14, 1, 400L); Prefs.addTopTime(ctx, 14, 1, 200L)
        Prefs.addTopTime(ctx, 14, 1, 100L); Prefs.addTopTime(ctx, 14, 1, 50L)
        assertEquals(listOf(50L, 100L, 200L, 300L, 400L), Prefs.topTimes(ctx, 14, 1)) }
    @Test fun `daily streak consecutive only`() {
        Prefs.recordDailyDone(ctx, "20240110"); assertEquals(1, Prefs.streak(ctx))
        Prefs.recordDailyDone(ctx, "20240110"); assertEquals(1, Prefs.streak(ctx))
        Prefs.recordDailyDone(ctx, "20240111"); assertEquals(2, Prefs.streak(ctx))
        Prefs.recordDailyDone(ctx, "20240113"); assertEquals(1, Prefs.streak(ctx)) }
    @Test fun `mode best only improves`() {
        assertTrue(Prefs.setModeBestMs(ctx, "m", 5000L))
        assertFalse(Prefs.setModeBestMs(ctx, "m", 6000L))
        assertEquals(5000L, Prefs.modeBestMs(ctx, "m")) }
    @Test fun `win replay round trip`() {
        Prefs.setMyWinReplay(ctx, "14_1_3", 777L, byteArrayOf(1, 2, 3))
        val (seed, bytes) = Prefs.myWinReplay(ctx, "14_1_3")!!
        assertEquals(777L, seed); assertEquals(3, bytes.size)
        assertNull(Prefs.myWinReplay(ctx, "14_1_9")) }
}
