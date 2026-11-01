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
    fun aButtonGetsAPlaceItsImageAndKeepsItsPlaceWhenChanged() {
        val added = PlayerLayout.EMPTY.withButton(PlayerButton("player_1", "RUN", "LShift"))
        val entry = json(added)["buttons"]!!.jsonObject["player_1"]!!.jsonObject
        assertEquals(setOf("anchor", "x", "y", "key", "image", "label"), entry.keys)
        assertEquals("\"touch_layout/player_1.png\"", entry["image"].toString())
        // The in-game editor moved it
        val moved = PlayerLayout.parse(
            added.toJson().replace("\"top_left\"", "\"bottom_left\""),
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
    fun aNewButtonTakesAFreePlace() {
        fun place(layout: PlayerLayout, name: String) =
            json(layout)["buttons"]!!.jsonObject[name]!!.jsonObject.let {
                assertEquals("\"top_left\"", it["anchor"].toString())
                it["x"].toString() to it["y"].toString()
            }
        val two = PlayerLayout.EMPTY.withButton(PlayerButton("player_1", "A", "A")).withButton(PlayerButton("player_2", "B", "B"))
        assertEquals("0.35" to "0.3", place(two, "player_1"))
        assertEquals("0.47" to "0.3", place(two, "player_2"))
        val again = two.withoutButton("player_1").withButton(PlayerButton("player_1", "C", "C"))
        assertEquals("0.35" to "0.3", place(again, "player_1"))
        // The last place once all are taken
        val four = (3..4).fold(two) { layout, n -> layout.withButton(PlayerButton("player_$n", "$n", "A")) }
        assertEquals("0.59" to "0.3", place(four, "player_3"))
        assertEquals("0.59" to "0.3", place(four, "player_4"))
        // Any entry on a place takes it
        val held = PlayerLayout.parse("""{"version": 1, "buttons": {"run": {"anchor": "top_left", "x": 0.35, "y": 0.3, "visible": false}}}""")
        assertEquals("0.47" to "0.3", place(held.withButton(PlayerButton("player_1", "A", "A")), "player_1"))
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
