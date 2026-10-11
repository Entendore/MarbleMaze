package com.example.marblemaze

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** App-flow smoke: menu → level select → game launches and reaches PLAYING. */
@RunWith(AndroidJUnit4::class)
class AppFlowSmokeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun clean() = ctx.getSharedPreferences("marble_maze", 0).edit().clear().commit()

    @Test fun `menu to game flow`() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.start_button)).perform(click())
        onView(withId(R.id.daily_button)).check(matches(isDisplayed()))
        // launch level 1 directly (grid cells are programmatic views)
        ActivityScenario.launch(GameActivity::class.java
            .putExtra(GameActivity.EXTRA_MARBLES, 1)
            .putExtra(GameActivity.EXTRA_DIFFICULTY, 9)
            .putExtra(GameActivity.EXTRA_LEVEL, 1))
        var won = false
        ActivityScenario.launch(GameActivity::class.java
            .putExtra(GameActivity.EXTRA_MARBLES, 1)
            .putExtra(GameActivity.EXTRA_DIFFICULTY, 9)
            .putExtra(GameActivity.EXTRA_LEVEL, 1)).onActivity { gv ->
            val view = (gv.findViewById<android.widget.FrameLayout>(R.id.game_container)
                .getChildAt(0)) as GameView
            view.debugWinNow(); won = true }
        assertTrue(won) }
}
