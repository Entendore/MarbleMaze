package com.example.marblemaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeOfWeekTest {
    @Test fun `same week identical`() { assertEquals(ModeOfWeek.build(202415), ModeOfWeek.build(202415)) }
    @Test fun `different weeks differ`() { assertTrue(ModeOfWeek.build(202415) != ModeOfWeek.build(202416)) }
}
