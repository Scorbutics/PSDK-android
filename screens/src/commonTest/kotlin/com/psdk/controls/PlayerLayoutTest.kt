package com.psdk.controls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLayoutTest {
    private fun json(layout: PlayerLayout): JsonObject = Json.parseToJsonElement(layout.toJson()).jsonObject

    @Test
    fun noFileIsTheDefaults() {
        val layout = PlayerLayout.parse(null)
        assertEquals(1.0, layout.scale)
        assertEquals(PlayerLayout.DEFAULT_OPACITY, layout.opacity)
        assertEquals(ActionLayout.NINTENDO, layout.actionLayout)
        assertEquals(emptyList(), layout.buttons)
        assertEquals("""{"version":1}""", Json.encodeToString(JsonObject.serializer(), json(layout)))
    }

    @Test
    fun theEditorsPlacesAreKept() {
        val file = """{"version": 1, "groups": {"dpad": {"anchor": "bottom_left", "x": 0.2, "y": 0.1, "size": 1.5}},
            "buttons": {"select": {"visible": false}}}"""
        val changed = PlayerLayout.parse(file).withScale(1.5).withActionLayout(ActionLayout.XBOX)
        val root = json(changed)
        assertEquals(Json.parseToJsonElement(file).jsonObject["groups"], root["groups"])
        assertEquals(Json.parseToJsonElement(file).jsonObject["buttons"], root["buttons"])
        assertEquals("1.5", root["scale"].toString())
        assertEquals("\"xbox\"", root["action_layout"].toString())
    }

    @Test
    fun settingsSnapIntoTheRuntimesRangesAndDefaultsLeaveTheFile() {
        val layout = PlayerLayout.EMPTY.withScale(1.3).withOpacity(0.93)
        assertEquals(1.25, layout.scale)
        assertEquals(0.8, layout.opacity)
        assertEquals(0.5, PlayerLayout.EMPTY.withScale(0.1).scale)
        val back = layout.withScale(1.0).withOpacity(0.5).withActionLayout(ActionLayout.NINTENDO)
        assertEquals(setOf("version"), json(back).keys)
    }

    @Test
    fun aNewButtonHasNoPlaceAndKeepsTheOneTheEditorGaveIt() {
        val added = PlayerLayout.EMPTY.withButton(PlayerButton("player_1", "RUN", "LShift"))
        val entry = json(added)["buttons"]!!.jsonObject["player_1"]!!.jsonObject
        assertEquals(setOf("key", "image", "label"), entry.keys)
        assertEquals("\"touch_layout/player_1.png\"", entry["image"].toString())
        // The in-game editor moved it
        val moved = PlayerLayout.parse(
            """{"version": 1, "buttons": {"player_1": {"anchor": "bottom_left", "x": 0.1, "y": 0.2,
                "key": "LShift", "image": "touch_layout/player_1.png", "label": "RUN"}}}""",
        ).withButton(PlayerButton("player_1", "JUMP", "Space"))
        val movedEntry = json(moved)["buttons"]!!.jsonObject["player_1"]!!.jsonObject
        assertEquals("\"bottom_left\"", movedEntry["anchor"].toString())
        assertEquals(listOf(PlayerButton("player_1", "JUMP", "Space")), moved.buttons)
    }

    @Test
    fun onlyThePlayersOwnButtonsAreListed() {
        val layout = PlayerLayout.parse(
            """{"version": 1, "buttons": {"start": {"anchor": "top_left", "x": 0, "y": 0},
                "run": {"anchor": "top_left", "x": 0, "y": 0, "key": "LShift", "image": "run.png"},
                "player_2": {"anchor": "top_left", "x": 0, "y": 0, "key": "A", "image": "x.png", "label": "A"}}}""",
        )
        assertEquals(listOf(PlayerButton("player_2", "A", "A")), layout.buttons)
        assertEquals("player_1", layout.newButtonName())
        assertEquals("player_3", layout.withButton(PlayerButton("player_1", "B", "B")).newButtonName())
    }

    @Test
    fun gamepadButtonsTakeAKeyAndGiveItBack() {
        val placed = PlayerLayout.parse("""{"version": 1, "buttons": {"start": {"anchor": "top_left", "x": 0.1, "y": 0.1}}}""")
        val remapped = placed.withRemap("a", "Space").withRemap("start", "K")
        assertEquals(mapOf("a" to "Space", "start" to "K"), remapped.remaps)
        assertEquals(emptyList(), remapped.buttons)
        val back = remapped.withRemap("a", null).withRemap("start", null)
        assertEquals(emptyMap(), back.remaps)
        // The place the editor gave stays; an entry left empty goes
        assertEquals(json(placed)["buttons"], json(back)["buttons"])
        assertNull(json(PlayerLayout.EMPTY.withRemap("a", "Space").withRemap("a", null))["buttons"])
        // A cross's button holds only its key; a row button keeps its other fields
        val cross = json(PlayerLayout.EMPTY.withRemap("up", "W"))["buttons"]!!.jsonObject["up"]!!.jsonObject
        assertEquals(setOf("key"), cross.keys)
        val start = PlayerLayout.parse("""{"version": 1, "buttons": {"start": {"visible": false, "size": 1.5}}}""")
        assertEquals(setOf("visible", "size", "key"), json(start.withRemap("start", "K"))["buttons"]!!.jsonObject["start"]!!.jsonObject.keys)
    }

    @Test
    fun onlyGamepadButtonsCountAsRemapped() {
        val layout = PlayerLayout.parse(
            """{"version": 1, "buttons": {"a": {"key": null}, "player_1": {"key": "A", "image": "x.png", "label": "A"}, "b": {"key": "K"}}}""",
        )
        assertEquals(mapOf("b" to "K"), layout.remaps)
        assertFailsWith<IllegalArgumentException> { layout.withRemap("player_1", null) }
    }

    @Test
    fun theLastButtonRemovedRemovesTheSection() {
        val layout = PlayerLayout.EMPTY.withButton(PlayerButton("player_1", "A", "A")).withoutButton("player_1")
        assertNull(json(layout)["buttons"])
    }

    @Test
    fun anotherFileIsRefused() {
        assertFailsWith<IllegalArgumentException> { PlayerLayout.parse("[1]") }
        assertFailsWith<IllegalArgumentException> { PlayerLayout.parse("""{"version": 2}""") }
        assertFailsWith<IllegalArgumentException> { PlayerLayout.parse("not json") }
    }

    @Test
    fun everyOfferedKeyIsAScancodeName() {
        assertTrue(Keys.ALL.all { (name, _) -> name.matches(Regex("[A-Z]\\w*")) })
        assertEquals(Keys.ALL.size, Keys.ALL.map { it.first }.toSet().size)
    }
}
