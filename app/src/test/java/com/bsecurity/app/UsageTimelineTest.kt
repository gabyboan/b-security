package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class UsageTimelineTest {
    @Test fun emptyHistoryHasNoUsage() = assertTrue(UsageTimeline(100, 200).result().isEmpty())
    @Test fun completedSessionCountsOnlyItsDuration() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 110); usage.pause("game", 150)
        assertEquals(mapOf("game" to 40L), usage.result())
    }
    @Test fun openSessionCountsUntilNowAndResultCanBeReadTwice() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 150)
        assertEquals(mapOf("game" to 50L), usage.result())
        assertEquals(mapOf("game" to 50L), usage.result())
    }
    @Test fun switchingAppsClosesThePreviousSession() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 110); usage.resume("video", 140); usage.pause("video", 180)
        assertEquals(mapOf("game" to 30L, "video" to 40L), usage.result())
    }
    @Test fun repeatedResumeDoesNotResetTheStartTime() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 110); usage.resume("game", 130); usage.pause("game", 150)
        assertEquals(40L, usage.result()["game"])
    }
    @Test fun delayedPauseForAnotherAppDoesNotCloseTheCurrentApp() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 110); usage.resume("video", 140); usage.pause("game", 150)
        assertEquals(mapOf("game" to 30L, "video" to 60L), usage.result())
    }
    @Test fun screenOffAndShutdownStopCounting() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 110); usage.idle(130); usage.idle(160)
        assertEquals(20L, usage.result()["game"])
    }
    @Test fun sessionCrossingMidnightCountsOnlyToday() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 60); usage.pause("game", 130)
        assertEquals(30L, usage.result()["game"])
    }
    @Test fun multipleSessionsOfOneAppAreAdded() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 110); usage.pause("game", 130); usage.resume("game", 180)
        assertEquals(40L, usage.result()["game"])
    }
    @Test fun eventsBeforeMidnightDoNotAddTimeToToday() {
        val usage = UsageTimeline(100, 200)
        usage.resume("game", 50); usage.pause("game", 90)
        assertEquals(0L, usage.result()["game"])
    }
}
