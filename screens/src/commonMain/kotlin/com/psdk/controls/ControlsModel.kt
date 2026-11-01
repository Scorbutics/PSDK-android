package com.psdk.controls

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scorbutics.litergss.overlay.ActionLayout
import com.scorbutics.litergss.overlay.PlayerButton
import com.scorbutics.litergss.overlay.PlayerLayout

/** The game's folder, where the player's layout file and its images are. */
interface ControlsStore {
    /** The player's layout file, null when there is none. */
    fun read(): String?

    /** Whole or not at all: the app may be killed while it writes. */
    fun write(json: String)

    /** @param path relative to the game's folder ([PlayerLayout.imagePath]) */
    fun writeImage(path: String, png: ByteArray)

    fun deleteImage(path: String)

    /** Keep a file that can't be read beside (as the game does), out of the next write's way. */
    fun putAside()

    /** No player's layout file and none of its images: the game's layout again. */
    fun reset()
}

/** Draws a button's label as its PNG image (the platform's fonts). */
fun interface LabelRenderer {
    fun render(label: String): ByteArray
}

/**
 * What the Controls screen shows and changes: each change is written at
 * once, so leaving the screen any way keeps it.
 */
class ControlsModel(private val store: ControlsStore, private val labels: LabelRenderer) {

    var layout by mutableStateOf(PlayerLayout.EMPTY)
        private set

    /** Why the last change was not saved, or the file could not be read. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** An unreadable file that could not be set aside: never overwritten. */
    private var locked = false

    init {
        layout = try {
            PlayerLayout.parse(store.read())
        } catch (e: Exception) {
            // The runtime refuses it too: the next change starts over, the file kept aside
            problem = try {
                store.putAside()
                "Your controls file could not be read (${e.message}): the game's controls apply."
            } catch (aside: Exception) {
                locked = true
                "Your controls file could not be read (${e.message}) nor set aside (${aside.message}): " +
                    "nothing is saved until it is removed."
            }
            PlayerLayout.EMPTY
        }
    }

    fun setScale(value: Double) {
        save { layout.withScale(value) }
    }

    fun setOpacity(value: Double) {
        save { layout.withOpacity(value) }
    }

    fun setActionLayout(value: ActionLayout) {
        save { layout.withActionLayout(value) }
    }

    /** @param key null: the game's key again */
    fun setRemap(name: String, key: String?) {
        save { layout.withRemap(name, key) }
    }

    /** @param name the button to change, null for a new one */
    fun saveButton(name: String?, label: String, key: String) {
        save {
            val button = PlayerButton(name ?: layout.newButtonName(), label.trim(), key)
            store.writeImage(PlayerLayout.imagePath(button.name), labels.render(button.label))
            layout.withButton(button)
        }
    }

    /** Its image goes once the file no longer names it: the runtime refuses a file whose image is missing. */
    fun deleteButton(name: String) {
        if (save { layout.withoutButton(name) }) {
            try {
                store.deleteImage(PlayerLayout.imagePath(name))
            } catch (_: Exception) {
                // An image nothing names: harmless
            }
        }
    }

    fun reset() {
        try {
            store.reset()
            layout = PlayerLayout.EMPTY
            problem = null
            locked = false
        } catch (e: Exception) {
            problem = "Not reset: ${e.message ?: e::class.simpleName}"
        }
    }

    /** @return whether the change was written */
    private fun save(change: () -> PlayerLayout): Boolean = if (locked) false else try {
        val changed = change()
        store.write(changed.toJson())
        layout = changed
        problem = null
        true
    } catch (e: Exception) {
        problem = "Not saved: ${e.message ?: e::class.simpleName}"
        false
    }

    companion object {
        /** Longest label: it must fit the button's image. */
        const val LABEL_MAX = 5
    }
}
