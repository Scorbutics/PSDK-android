package com.psdk.saves

import kotlin.test.Test
import kotlin.test.assertEquals

class SavesModelTest {
    @Test
    fun confirmTextSaysWhatIsReplaced() {
        val contents = SavesContents("Lethalmon", "desktop", "2026-11-04T12:30:00Z", listOf("Saves/Pokemon_Party", "GlobalPokedex.dat"))
        assertEquals(
            "Replace this game's saves with the saves of Lethalmon from a computer exported 2026-11-04 (2 files)? " +
                "The saves here now are kept in a backup.",
            SavesModel(gameRunning = false).confirmText(contents),
        )
        assertEquals(
            "Replace this game's saves with the saves (1 file)? The saves here now are kept in a backup. " +
                "The game closes afterwards: start it again to play.",
            SavesModel(gameRunning = true).confirmText(SavesContents(null, null, null, listOf("Saves/Pokemon_Party"))),
        )
    }
}
