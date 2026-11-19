package com.psdk.remote

import android.content.Context
import com.psdk.zip.KeyAttestationGate
import android.net.ConnectivityManager
import com.psdk.ClassicModeState
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * A small APK's setup step, in place of copying a bundled archive: makes
 * archive.staging the manifest's game, downloading it when it isn't
 * already. The rest of the setup (keys, splash, compile) is a full APK's.
 * Once installed, [installed] records the game and deletes the archive.
 */
object RemoteSetup {
    /** Room for the archive and its extraction (Release/), on top of it */
    private const val EXTRACTION_FACTOR = 1.2

    /** The setup screen, from the setup thread */
    interface Ui {
        /** The status line under the splash */
        fun status(text: String)

        /** Blocking: whether to download `bytes` on a metered connection */
        fun confirmMeteredDownload(bytes: Long): Boolean
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(ClassicModeState.PREFS, Context.MODE_PRIVATE)

    /**
     * Blocking, off the UI thread. @throws RemoteGame.Error what the player
     * must hear: no connection, no room, an app too old for the game.
     */
    fun prepare(context: Context, descriptor: RemoteGame.Descriptor, staging: File, ui: Ui) {
        ui.status("Looking for the game...")
        val manifest = RemoteGame.fetchManifest(descriptor)
        RemoteGame.incompatibility(context, manifest)?.let { throw RemoteGame.Error(it, retryable = false) }
        // The archive opens only on a device passing the integrity check
        // (release builds): one that fails learns it now, not after the
        // download (hundreds of MB). Its verdict is cached for the archive's
        // opening.
        (KeyAttestationGate.check(context) as? KeyAttestationGate.Result.Fail)?.let {
            throw RemoteGame.Error(KeyAttestationGate.playerMessage(it.reason), retryable = !KeyAttestationGate.isPermanent(it.reason))
        }

        val prefs = prefs(context)
        if (staging.isFile && prefs.getString(ClassicModeState.GAME_STAGED_SHA_KEY, null) == manifest.sha256) return

        val partial = File(staging.parentFile, "${staging.name}.download")
        val missing = manifest.size - (if (partial.isFile) partial.length() else 0L)
        val needed = missing + (manifest.size * EXTRACTION_FACTOR).toLong()
        val free = GameDownloader.freeSpace(staging.parentFile!!)
        if (free < needed) {
            throw RemoteGame.Error("Not enough storage to install the game: ${mb(needed)} needed, ${mb(free)} free. " +
                "Free some space, then try again.")
        }
        // Unknown (no network state): not asked, as on Wi-Fi
        val metered = runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered }
            .getOrNull() == true
        if (missing > 0 && metered && !ui.confirmMeteredDownload(missing)) {
            throw RemoteGame.Error("The game will download once you are on Wi-Fi: try again then.")
        }

        // The archive the staging file held is no longer this game's
        prefs.edit().remove(ClassicModeState.GAME_STAGED_SHA_KEY).apply()
        val finished = CountDownLatch(1)
        var outcome: GameDownloader.State = GameDownloader.State.Idle
        var lastPercent = -1
        // The game downloaded: "Lethalmon v2.24", as its splash shows it
        val what = listOfNotNull(manifest.title?.takeIf { it.isNotBlank() } ?: "the game",
            manifest.version.takeIf { it.isNotBlank() }?.let { "v${GameSplash.displayVersion(it)}" })
            .joinToString(" ")
        // Armed once this download is asked for: listen() first gives the
        // current state, which may be a former download's end
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopListening = GameDownloader.listen { state ->
            if (!armed.get()) return@listen
            when (state) {
                is GameDownloader.State.Running -> {
                    val percent = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        ui.status("Downloading $what... $percent%\n${mb(state.done)} / ${mb(state.total)}")
                    }
                }
                is GameDownloader.State.Done, is GameDownloader.State.Failed -> {
                    outcome = state
                    finished.countDown()
                }
                GameDownloader.State.Idle -> Unit
            }
        }
        try {
            armed.set(true)
            GameDownloader.start(context, manifest, staging)
            finished.await()
        } finally {
            stopListening()
        }
        when (val result = outcome) {
            is GameDownloader.State.Failed -> throw RemoteGame.Error(result.message, result.retryable)
            else -> prefs.edit()
                .putString(ClassicModeState.GAME_STAGED_SHA_KEY, manifest.sha256)
                .putString(ClassicModeState.GAME_STAGED_VERSION_KEY, manifest.version)
                .apply()
        }
    }

    /**
     * The game is installed (Release/ compiled from archive.staging): records
     * it, and deletes the archive (hundreds of MB). A later setup needing it
     * (an app update) downloads it again.
     */
    fun installed(context: Context, staging: File) {
        val prefs = prefs(context)
        val sha = prefs.getString(ClassicModeState.GAME_STAGED_SHA_KEY, null) ?: return
        prefs.edit()
            .putString(ClassicModeState.GAME_INSTALLED_SHA_KEY, sha)
            .putString(ClassicModeState.GAME_INSTALLED_VERSION_KEY, prefs.getString(ClassicModeState.GAME_STAGED_VERSION_KEY, null))
            .remove(ClassicModeState.GAME_STAGED_SHA_KEY)
            .remove(ClassicModeState.GAME_STAGED_VERSION_KEY)
            .apply()
        if (!staging.delete()) android.util.Log.w("RemoteSetup", "archive not deleted: ${staging.path}")
    }

    fun mb(bytes: Long): String = "${(bytes + 512 * 1024) / (1024 * 1024)} MB"

    /** Bridge for a setup thread to ask the UI thread, blocking */
    fun <T> onUiThread(activity: android.app.Activity, block: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var value: T? = null
        activity.runOnUiThread { block { result -> value = result; latch.countDown() } }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
