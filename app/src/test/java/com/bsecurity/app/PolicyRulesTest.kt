package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PolicyRulesTest {
    @Test fun unlimitedBudgetsNeverExpire() {
        assertFalse(BudgetMath.exhausted(Long.MAX_VALUE, 0))
        assertFalse(BudgetMath.exhausted(60_000, -1))
    }
    @Test fun budgetExpiresAtTheExactBoundary() {
        assertFalse(BudgetMath.exhausted(59_999, 1))
        assertTrue(BudgetMath.exhausted(60_000, 1))
        assertTrue(BudgetMath.exhausted(60_001, 1))
    }
    @Test fun fullDayBudgetDoesNotOverflow() {
        assertFalse(BudgetMath.exhausted(86_399_999, 1440))
        assertTrue(BudgetMath.exhausted(86_400_000, 1440))
    }
    @Test fun intervalsAreClippedToBothDayBoundaries() {
        assertEquals(5L, BudgetMath.overlap(10, 30, 20, 25))
        assertEquals(0L, BudgetMath.overlap(0, 10, 20, 30))
        assertEquals(0L, BudgetMath.overlap(40, 50, 20, 30))
        assertEquals(0L, BudgetMath.overlap(30, 20, 0, 50))
    }
    @Test fun localMidnightUsesTheDevicesTimeZone() {
        val now = Instant.parse("2026-11-17T02:30:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-16T03:00:00Z").toEpochMilli(),
            BudgetMath.dayStart(now, ZoneId.of("America/Argentina/Buenos_Aires")))
    }
    @Test fun daylightSavingDaysCanHave23Or25Hours() {
        val zone = ZoneId.of("America/New_York")
        fun start(date: String) = BudgetMath.dayStart(Instant.parse(date + "T16:00:00Z").toEpochMilli(), zone)
        assertEquals(23 * 3600_000L, start("2026-03-09") - start("2026-03-08"))
        assertEquals(25 * 3600_000L, start("2026-11-02") - start("2026-11-01"))
    }
    @Test fun stricterRuleKeepsEitherBlockAndTheShortestPositiveBudget() {
        assertEquals(AppRule(true, 15), AppRule(false, 30).merge(AppRule(true, 15)))
        assertEquals(AppRule(true, 10), AppRule(true, 10).merge(AppRule(false, 60)))
    }
    @Test fun unlimitedRulesDoNotRemoveAnExistingBudget() {
        assertEquals(AppRule(false, 20), AppRule(false, 20).merge(AppRule()))
        assertEquals(AppRule(false, 20), AppRule().merge(AppRule(false, 20)))
        assertEquals(AppRule(), AppRule().merge(AppRule()))
    }
    @Test fun ruleMergeIsCommutativeAndIdempotent() {
        val values = listOf(AppRule(), AppRule(true), AppRule(false, 10), AppRule(true, 1440))
        values.forEach { a ->
            assertEquals(a, a.merge(a))
            values.forEach { b -> assertEquals(a.merge(b), b.merge(a)) }
        }
    }
}
