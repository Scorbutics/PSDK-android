package com.psdk.remote

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Downloads a small APK's game archive (RemoteGame) into its target file.
 *
 * - Resumes: the bytes already in `<target>.download` stay, the rest is
 *   asked with an HTTP Range (a killed app, a lost connection).
 * - Checks: the size and SHA-256 of the manifest, computed while
 *   downloading (the bytes kept are hashed again first). A mismatch deletes
 *   the download: the next attempt starts over.
 * - Lands whole: renamed onto the target once checked, never truncated.
 *
 * One download at a time, process-wide, on its own thread; a foreground
 * service (GameDownloadService) keeps the process alive meanwhile. Callers
 * follow it with [listen].
 */
object GameDownloader {
    private const val TIMEOUT_MS = 30_000
    private const val BUFFER = 256 * 1024

    sealed class State {
        object Idle : State()
        data class Running(val done: Long, val total: Long) : State()
        data class Done(val file: File) : State()
        data class Failed(val message: String, val retryable: Boolean) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    private val listeners = mutableListOf<(State) -> Unit>()
    private var worker: Thread? = null

    /** Called with each state (from the download thread); the current one first */
    fun listen(listener: (State) -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        listener(state)
        return { synchronized(listeners) { listeners -= listener } }
    }

    private fun publish(next: State) {
        state = next
        synchronized(listeners) { listeners.toList() }.forEach { it(next) }
    }

    /** Starts downloading `manifest`'s archive into `target`, unless already under way */
    @Synchronized
    fun start(context: Context, manifest: RemoteGame.Manifest, target: File) {
        if (worker?.isAlive == true) return
        GameDownloadService.start(context.applicationContext, manifest.title)
        worker = Thread({
            val result = try {
                download(manifest, target) { done, total -> publish(State.Running(done, total)) }
                State.Done(target)
            } catch (e: RemoteGame.Error) {
                State.Failed(e.message ?: "Download failed", e.retryable)
            } catch (e: java.io.IOException) {
                State.Failed("The download stopped (${e.message ?: e.javaClass.simpleName})", retryable = true)
            }
            GameDownloadService.stop(context.applicationContext)
            publish(result)
        }, "psdk-game-download").apply { start() }
    }

    /** Blocking. @throws RemoteGame.Error, java.io.IOException */
    fun download(manifest: RemoteGame.Manifest, target: File, progress: (Long, Long) -> Unit) {
        val partial = File(target.parentFile, "${target.name}.download")
        val digest = MessageDigest.getInstance("SHA-256")
        // What a former attempt left: hashed again (the hash isn't kept), or
        // dropped when it can't be the start of this archive
        var done = if (partial.isFile && partial.length() <= manifest.size) partial.length() else 0L
        if (done == 0L) partial.delete()
        if (done > 0) {
            partial.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                var read = 0L
                while (read < done) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), done - read).toInt())
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    read += n
                }
            }
        }
        progress(done, manifest.size)

        if (done < manifest.size) {
            val headers = if (done > 0) mapOf("Range" to "bytes=$done-") else emptyMap()
            Http.open(manifest.archiveUrl, TIMEOUT_MS, headers).use { response ->
                when (response.code) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    HttpURLConnection.HTTP_OK -> {
                        // No resume on this server: from the start
                        done = 0
                        digest.reset()
                    }
                    else -> throw RemoteGame.Error("The game's server answered ${response.code}")
                }
                RandomAccessFile(partial, "rw").use { out ->
                    out.setLength(done)
                    out.seek(done)
                    val buffer = ByteArray(BUFFER)
                    response.stream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (done + n > manifest.size) {
                                partial.delete()
                                throw RemoteGame.Error("The downloaded game is bigger than announced: it will be downloaded again")
                            }
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            progress(done, manifest.size)
                        }
                    }
                }
            }
        }

        if (done != manifest.size) {
            throw java.io.IOException("interrupted at $done of ${manifest.size} bytes")
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha256 != manifest.sha256) {
            partial.delete()
            throw RemoteGame.Error("The downloaded game is damaged: it will be downloaded again")
        }
        target.delete()
        if (!partial.renameTo(target)) throw java.io.IOException("cannot move ${partial.name} to ${target.name}")
    }

    /** Bytes free where `dir` is */
    fun freeSpace(dir: File): Long = if (Build.VERSION.SDK_INT >= 26) dir.usableSpace else dir.freeSpace
}
