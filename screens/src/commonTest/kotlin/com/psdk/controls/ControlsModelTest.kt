package com.psdk.controls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlsModelTest {
    private class MemoryStore(var file: String? = null) : ControlsStore {
        val images = mutableMapOf<String, ByteArray>()
        var failWrites = false

        override fun read() = file

        override fun write(json: String) {
            if (failWrites) throw IllegalStateException("disk full")
            file = json
        }

        override fun writeImage(path: String, png: ByteArray) {
            images[path] = png
        }

        override fun deleteImage(path: String) {
            images.remove(path)
        }

        var asideFile: String? = null

        var asideFails = false

        override fun putAside() {
            if (asideFails) throw IllegalStateException("read-only")
            asideFile = file
            file = null
        }

        override fun reset() {
            file = null
            images.clear()
        }
    }

    private val labels = LabelRenderer { it.encodeToByteArray() }

    @Test
    fun eachChangeIsWrittenAtOnce() {
        val store = MemoryStore()
        val model = ControlsModel(store, labels)
        model.setOpacity(0.3)
        assertEquals(0.3, PlayerLayout.parse(store.file).opacity)
        model.saveButton(null, " RUN ", "LShift")
        assertEquals("RUN", store.images["touch_layout/player_1.png"]!!.decodeToString())
        assertEquals(listOf(PlayerButton("player_1", "RUN", "LShift")), PlayerLayout.parse(store.file).buttons)
        model.deleteButton("player_1")
        assertTrue(store.images.isEmpty())
        assertEquals(emptyList(), model.layout.buttons)
    }

    @Test
    fun aGamepadKeyIsWrittenAtOnce() {
        val store = MemoryStore()
        val model = ControlsModel(store, labels)
        model.setRemap("b", "Escape")
        assertEquals(mapOf("b" to "Escape"), PlayerLayout.parse(store.file).remaps)
        model.setRemap("b", null)
        assertEquals(emptyMap(), PlayerLayout.parse(store.file).remaps)
    }

    @Test
    fun resetRemovesTheFile() {
        val store = MemoryStore("""{"version": 1, "scale": 2.0}""")
        val model = ControlsModel(store, labels)
        model.reset()
        assertNull(store.file)
        assertEquals(1.0, model.layout.scale)
    }

    @Test
    fun anUnreadableFileIsSaidAndStartedOver() {
        val store = MemoryStore("garbage")
        val model = ControlsModel(store, labels)
        assertNotNull(model.problem)
        assertEquals("garbage", store.asideFile)
        model.setScale(1.5)
        assertNull(model.problem)
        assertEquals(1.5, PlayerLayout.parse(store.file).scale)
    }

    @Test
    fun aFileThatCannotBeSetAsideIsNeverOverwritten() {
        val store = MemoryStore("garbage").apply { asideFails = true }
        val model = ControlsModel(store, labels)
        val problem = model.problem
        model.setScale(1.5)
        model.saveButton(null, "A", "A")
        assertEquals("garbage", store.file)
        assertTrue(store.images.isEmpty())
        assertEquals(problem, model.problem)
        // The player's own reset removes it: saves work again
        model.reset()
        model.setScale(1.5)
        assertEquals(1.5, PlayerLayout.parse(store.file).scale)
    }

    @Test
    fun aFailedSaveKeepsTheShownLayoutAsTheFile() {
        val store = MemoryStore()
        val model = ControlsModel(store, labels)
        store.failWrites = true
        model.setScale(2.0)
        assertEquals(1.0, model.layout.scale)
        assertTrue(model.problem!!.contains("disk full"))
    }

    @Test
    fun aButtonsImageStaysUntilTheFileNoLongerNamesIt() {
        val store = MemoryStore()
        val model = ControlsModel(store, labels)
        model.saveButton(null, "A", "A")
        store.failWrites = true
        model.deleteButton("player_1")
        assertTrue("touch_layout/player_1.png" in store.images)
        assertEquals(1, model.layout.buttons.size)
    }
}
