package com.psdk.remote

import android.content.Context
import com.psdk.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * A small APK's game (the exporter's --remote, exporter/lib/remote_game.rb):
 * not inside the APK, hosted next to a manifest the APK points to. The app
 * downloads it at its first launch (GameDownloader), then installs it as a
 * bundled one; a different manifest later is an update.
 *
 *   assets/game.remote.json, in the APK: {"format": 1, "manifest_url": "..."}
 *   the manifest (android-game.json): the game's version, its archive (URL,
 *   size, SHA-256) and which app plays it (cert, Ruby, backend, app release).
 */
object RemoteGame {
    const val ASSET = "game.remote.json"
    private const val FORMAT = 1
    private const val TIMEOUT_MS = 15_000

    /** Where a small APK's game is: its manifest's URL */
    data class Descriptor(val manifestUrl: String)

    data class Manifest(
        val title: String?,
        val version: String,
        /** Absolute: resolved against the manifest's URL */
        val archiveUrl: String,
        val archiveName: String,
        val size: Long,
        val sha256: String,
        val ruby: String?,
        val backend: String?,
        val compiled: Boolean,
        val certSha256: String?,
        val minAppVersion: String?,
    )

    class Error(message: String, val retryable: Boolean = true) : Exception(message)

    /** The APK's descriptor; null for a full APK or the hub */
    fun descriptor(context: Context): Descriptor? = runCatching {
        val json = JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        if (json.optInt("format") != FORMAT) return null
        Descriptor(json.getString("manifest_url"))
    }.getOrNull()

    /** The manifest at the descriptor's URL. @throws Error unreachable or unreadable */
    fun fetchManifest(descriptor: Descriptor, timeoutMs: Int = TIMEOUT_MS): Manifest {
        val text = try {
            Http.open(descriptor.manifestUrl, timeoutMs = timeoutMs).use { response ->
                if (response.code != HttpURLConnection.HTTP_OK) {
                    throw Error("The game's server answered ${response.code}")
                }
                response.stream.bufferedReader().use { it.readText() }
            }
        } catch (e: java.io.IOException) {
            throw Error("The game's server can't be reached (${e.message ?: e.javaClass.simpleName})")
        }
        return parseManifest(text, descriptor.manifestUrl)
    }

    /** @throws Error not a manifest this app reads */
    fun parseManifest(text: String, manifestUrl: String): Manifest = try {
        val json = JSONObject(text)
        if (json.optInt("format") != FORMAT) {
            throw Error("This game needs a newer version of the app: update it", retryable = false)
        }
        val archive = json.getJSONObject("archive")
        val url = archive.getString("url")
        Manifest(
            title = json.optString("title").ifEmpty { null },
            version = json.getString("version"),
            archiveUrl = URI(manifestUrl).resolve(url).toString(),
            archiveName = url.substringAfterLast('/').substringBefore('?').ifEmpty { "game.epsa" },
            size = archive.getLong("size"),
            sha256 = archive.getString("sha256").lowercase(),
            ruby = json.optString("ruby").ifEmpty { null },
            backend = json.optString("backend").ifEmpty { null },
            compiled = json.optBoolean("compiled"),
            certSha256 = json.optString("cert_sha256").ifEmpty { null }?.lowercase(),
            minAppVersion = json.optString("min_app_version").ifEmpty { null },
        )
    } catch (e: org.json.JSONException) {
        throw Error("The game's manifest can't be read (${e.message})", retryable = false)
    }

    /**
     * Why this app can't play the manifest's game, null when it can: the
     * archive opens with keys derived from the signing cert, compiled
     * bytecode loads on its Ruby only, and the engine must be recent enough.
     */
    fun incompatibility(context: Context, manifest: Manifest): String? {
        val update = "Install the latest version of this app to play it."
        manifest.certSha256?.let { expected ->
            val cert = com.psdk.zip.EpsaArchive.signingCertDer(context)
            val actual = cert?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
            if (actual != expected) return "This version of the game was published for another version of this app. $update"
        }
        if (manifest.compiled && manifest.ruby != null && manifest.ruby != BuildConfig.RUBY_VARIANT) {
            return "This version of the game needs the Ruby ${manifest.ruby} app (this one is ${BuildConfig.RUBY_VARIANT}). $update"
        }
        if (manifest.backend != null && manifest.backend != BuildConfig.BACKEND) {
            return "This version of the game was published for the ${manifest.backend} app (this one is ${BuildConfig.BACKEND}). $update"
        }
        manifest.minAppVersion?.let { min ->
            if (compareVersions(BuildConfig.VERSION_NAME, min) < 0) {
                return "This version of the game needs version $min of the app (this one is ${BuildConfig.VERSION_NAME}). $update"
            }
        }
        return null
    }

    /** "1.13.10" vs "1.13.9", numerically; a non-numeric part counts as 0 */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}

/**
 * HTTP GET following redirects across hosts (a GitHub release's download
 * answers with one to its storage), request headers (Range) kept.
 */
internal object Http {
    private const val MAX_REDIRECTS = 5

    class Response(val connection: HttpURLConnection) : AutoCloseable {
        val code: Int get() = connection.responseCode
        val stream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    fun open(url: String, timeoutMs: Int, headers: Map<String, String> = emptyMap()): Response {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "PSDK-android/${BuildConfig.VERSION_NAME}")
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw java.io.IOException("redirect without a location")
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            return Response(connection)
        }
        throw java.io.IOException("too many redirects")
    }
}
