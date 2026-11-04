package com.psdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The "The game stopped" dialog's text, and the marker a classic game's crash leaves
class GameExitReportTest {
    @get:Rule
    val dir = TemporaryFolder()

    // As PSDK writes it (Yuki::EXC.build_error_log), the probe error Lethalmon raised
    private val errorLog = "================================Erreur de script================================\r\n" +
        "Message :\r\nFake error\r\n\r\nType : RuntimeError\r\nScript : 00000 Engine.rb (user)\r\n" +
        "===================================Fin du log==================================="

    @Test
    fun theDialogSaysTheErrorOfTheLog() {
        assertEquals("RuntimeError: Fake error", GameExitReport.summary(errorLog, crashed = false, exitCode = 3))
        assertEquals("NoMethodError: undefined method",
            GameExitReport.summarizeErrorLog("Error type : NoMethodError\nError message : undefined method\n"))
    }

    @Test
    fun withoutALogItSaysWhy() {
        assertEquals("The game closed unexpectedly and did not write an error report.",
            GameExitReport.summary(null, crashed = true, exitCode = null))
        assertEquals("The game stopped with an error (exit code 3).", GameExitReport.summary(null, crashed = false, exitCode = 3))
    }

    @Test
    fun aMarkerLeftBehindIsACrashReportedOnce() {
        val location = dir.root.path
        assertNull(GameExitReport.takeCrash(location, bootedAtMs = 0))
        val before = System.currentTimeMillis()
        GameExitReport.markRunning(location)
        val markedAt = GameExitReport.takeCrash(location, bootedAtMs = 0)!!
        assertTrue("marked at $markedAt, before $before", markedAt >= before)
        assertNull(GameExitReport.takeCrash(location, bootedAtMs = 0))
    }

    // No onStop when the device is turned off: no crash either
    @Test
    fun aMarkerFromBeforeTheLastBootIsNoCrash() {
        val location = dir.root.path
        GameExitReport.markRunning(location)
        assertNull(GameExitReport.takeCrash(location, bootedAtMs = System.currentTimeMillis() + 60_000))
        assertNull(GameExitReport.takeCrash(location, bootedAtMs = 0))
    }

    @Test
    fun anUnreadableMarkerCountsFromItsDate() {
        val location = dir.root.path
        val marker = java.io.File(location, ".game_running")
        marker.writeText("not a time")
        marker.setLastModified(1_000_000L)
        assertEquals(1_000_000L, GameExitReport.takeCrash(location, bootedAtMs = 0))
    }

    @Test
    fun anErrorLogOlderThanTheSessionIsAnothersOne() {
        val log = dir.newFile("Error.log")
        log.setLastModified(10_000_000L)
        assertEquals(log, GameExitReport.freshErrorLog(log, sinceMs = 10_001_000L))
        assertNull(GameExitReport.freshErrorLog(log, sinceMs = 10_000_000L + 60_000))
        assertNull(GameExitReport.freshErrorLog(null, sinceMs = 0))
        assertNull(GameExitReport.freshErrorLog(java.io.File(dir.root, "none.log"), sinceMs = 0))
    }

    @Test
    fun aSessionLeftNormallyLeavesNoCrash() {
        val location = dir.root.path
        GameExitReport.markRunning(location)
        GameExitReport.clearRunning(location)
        assertNull(GameExitReport.takeCrash(location, bootedAtMs = 0))
    }
}
