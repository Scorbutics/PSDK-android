package com.psdk.controls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** Where A and B sit on the action cross (the buttons send the same keys). */
enum class ActionLayout(val value: String) { NINTENDO("nintendo"), XBOX("xbox") }

/**
 * A button the player added: [label] drawn on its image, [key] the
 * Sf::Keyboard::Scancode name it sends (see [Keys]).
 */
data class PlayerButton(val name: String, val label: String, val key: String)

/**
 * The player's layout file, `touch_layout.json` in the game's folder: the
 * runtime's LiteRGSS::TouchOverlay::LayoutFile, version 1, loaded over the
 * game's own (runtime 1.6.0 and later: buttons without a place, gamepad
 * keys). The Controls screen changes the gamepad's size, opacity, A / B order
 * and the keys its buttons send, and the player's buttons; the rest of the
 * file (the places the in-game editor wrote) is kept as is. Immutable: each
 * change is a new one.
 */
class PlayerLayout private constructor(private val root: Map<String, JsonElement>) {

    val scale: Double get() = number(SCALE) ?: 1.0
    val opacity: Double get() = number(OPACITY) ?: DEFAULT_OPACITY
    val actionLayout: ActionLayout
        get() = ActionLayout.entries.find { it.value == string(root[ACTION_LAYOUT]) } ?: ActionLayout.NINTENDO

    /** The player's buttons: the ones this screen added, in the file's order. */
    val buttons: List<PlayerButton>
        get() = section(BUTTONS).mapNotNull { (name, entry) ->
            val fields = entry as? JsonObject ?: return@mapNotNull null
            val key = string(fields[KEY])
            if (!name.startsWith(BUTTON_PREFIX) || key == null) null
            else PlayerButton(name, string(fields[LABEL]) ?: "", key)
        }

    /** Snapped to [SCALE_STEP] within [SCALES]; the default leaves the file. */
    fun withScale(value: Double) = with(SCALE, snapped(value, SCALES, SCALE_STEP, 1.0))

    /** Snapped to [OPACITY_STEP] within [OPACITIES]; the default leaves the file. */
    fun withOpacity(value: Double) = with(OPACITY, snapped(value, OPACITIES, OPACITY_STEP, DEFAULT_OPACITY))

    fun withActionLayout(value: ActionLayout) =
        with(ACTION_LAYOUT, if (value == ActionLayout.NINTENDO) null else JsonPrimitive(value.value))

    /**
     * Add [button], or change its label and key: a button already in the file
     * keeps its place. A new one has none: the runtime puts it where the
     * screen is free, until the player moves it (in-game editor).
     */
    fun withButton(button: PlayerButton): PlayerLayout {
        val buttons = section(BUTTONS)
        val entry = (buttons[button.name] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        entry[KEY] = JsonPrimitive(button.key)
        entry[IMAGE] = JsonPrimitive(imagePath(button.name))
        entry[LABEL] = JsonPrimitive(button.label)
        return with(BUTTONS, JsonObject(buttons + (button.name to JsonObject(entry))))
    }

    /** The keys the player gives the gamepad's own buttons ([GAMEPAD] names). */
    val remaps: Map<String, String>
        get() = GAMEPAD.mapNotNull { (name, _) ->
            string((section(BUTTONS)[name] as? JsonObject)?.get(KEY))?.let { name to it }
        }.toMap()

    /**
     * The key a gamepad button sends: null puts back the game's (its layout
     * file's, else its own binding). The button's place stays.
     */
    fun withRemap(name: String, key: String?): PlayerLayout {
        require(GAMEPAD.any { it.first == name }) { "$name is not a gamepad button" }
        val buttons = section(BUTTONS)
        val entry = (buttons[name] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (key == null) entry.remove(KEY) else entry[KEY] = JsonPrimitive(key)
        val changed = if (entry.isEmpty()) buttons - name else buttons + (name to JsonObject(entry))
        return with(BUTTONS, if (changed.isEmpty()) null else JsonObject(changed))
    }

    fun withoutButton(name: String): PlayerLayout {
        val buttons = section(BUTTONS) - name
        return with(BUTTONS, if (buttons.isEmpty()) null else JsonObject(buttons))
    }

    /** A name no button of the file has. */
    fun newButtonName(): String {
        val names = section(BUTTONS).keys
        return generateSequence(1) { it + 1 }.map { "$BUTTON_PREFIX$it" }.first { it !in names }
    }

    fun toJson(): String = JSON.encodeToString(JsonObject.serializer(), JsonObject(root))

    private fun with(key: String, value: JsonElement?) =
        PlayerLayout(if (value == null) root - key else root + (key to value))

    private fun section(key: String): Map<String, JsonElement> = root[key] as? JsonObject ?: emptyMap()

    private fun number(key: String) = (root[key] as? JsonPrimitive)?.doubleOrNull

    private fun string(element: JsonElement?) = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "touch_layout.json"

        /** The runtime's LayoutFile ranges. */
        val SCALES = 0.5..2.0
        val OPACITIES = 0.1..0.8
        const val DEFAULT_OPACITY = 0.5
        const val SCALE_STEP = 0.25
        const val OPACITY_STEP = 0.1

        /** The gamepad's own buttons a key can be given to, and how the screen names them. */
        val GAMEPAD = listOf(
            "a" to "A", "b" to "B", "x" to "X", "y" to "Y", "l" to "L", "r" to "R",
            "start" to "Start", "select" to "Select",
            "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right",
        )

        /** The player's buttons; their images go in the game's folder, under [IMAGE_DIR]. */
        const val BUTTON_PREFIX = "player_"
        const val IMAGE_DIR = "touch_layout"

        private const val SCALE = "scale"
        private const val OPACITY = "opacity"
        private const val ACTION_LAYOUT = "action_layout"
        private const val BUTTONS = "buttons"
        private const val KEY = "key"
        private const val IMAGE = "image"
        // Not the runtime's: the screen keeps the text the image was drawn from
        private const val LABEL = "label"

        private val JSON = Json { prettyPrint = true }

        val EMPTY = PlayerLayout(mapOf("version" to JsonPrimitive(VERSION)))

        /** Relative to the game's folder, as the layout file names it. */
        fun imagePath(name: String) = "$IMAGE_DIR/$name.png"

        /**
         * @param json the file's content, null when there is none
         * @throws IllegalArgumentException not a layout file of [VERSION]
         */
        fun parse(json: String?): PlayerLayout {
            if (json == null) return EMPTY
            val root = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("not a JSON object", e)
            }
            val version = root["version"]?.let { (it as? JsonPrimitive)?.intOrNull }
            require(version == VERSION) { "version ${root["version"]}, $VERSION expected" }
            return PlayerLayout(root)
        }

        private fun snapped(value: Double, range: ClosedRange<Double>, step: Double, default: Double): JsonPrimitive? {
            val steps = ((value.coerceIn(range) - range.start) / step).roundToInt()
            val snapped = ((range.start + steps * step) * 100).roundToInt() / 100.0
            return if (snapped == default) null else JsonPrimitive(snapped)
        }
    }
}
