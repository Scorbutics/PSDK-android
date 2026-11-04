package com.psdk.saves

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A game's saves, moved between Android and desktop as a saves file (a ZIP)
 * both read and write. Its desktop counterpart, and the format, is the
 * exporter's lib/save_transfer.rb: keep the two in step.
 *
 * [root] is the game's save root, where PSDK writes: Release/ (debug mode:
 * the game's folder). The saves file holds, relative to it, Saves/ (but
 * Saves/input.json or input.yml, a device's own keys, and Saves/Telemetry/,
 * an install's own id and events), Global*.dat[.bak] and
 * deleted_saves/, plus psdk_saves.json and README.txt. Nothing else is read
 * from it.
 */
object SaveTransfer {
    const val FORMAT = 1
    const val MANIFEST = "psdk_saves.json"
    const val README = "README.txt"
    private const val STAGING_DIR = ".saves_import"

    /** Written into the save root by an import, for a running game to see (see [takeImported]) */
    private const val IMPORTED_MARKER = ".saves_imported"

    /** The saves of a single game stay far below; more is not a saves file */
    private const val MAX_ENTRY_SIZE = 256L * 1024 * 1024

    class Error(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** A device's own keys (input.yml: older PSDK versions) */
    private val KEYS_FILES = setOf("Saves/input.json", "Saves/input.yml")

    private val GLOBAL_FILE = Regex("""Global[^/]*\.dat(\.bak)?""")

    /** A path of the saves file the game reads: see the class */
    fun isTransferable(path: String): Boolean {
        if (!isSafe(path)) return false
        return when {
            path.startsWith("Saves/") -> path !in KEYS_FILES && !path.startsWith("Saves/Telemetry/")
            path.startsWith("deleted_saves/") -> true
            else -> GLOBAL_FILE.matches(path)
        }
    }

    /** Relative, inside its root, nothing a filesystem reads otherwise */
    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) return false
        if (path.contains('\\') || path.contains('\u0000') || path.contains(':')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** The saves in [root], as paths relative to it */
    fun files(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter(::isTransferable)
            .sorted()
            .toList()
    }

    /** Writes the saves of [root] into [out]. Returns the paths written. */
    fun export(root: File, out: OutputStream, game: String?, now: Date = Date()): List<String> {
        val saves = files(root)
        if (saves.isEmpty()) throw Error("No saves yet: save in game first.")
        ZipOutputStream(out).use { zip ->
            zip.putText(MANIFEST, manifest(game, now, saves), now)
            zip.putText(README, README_TEXT, now)
            for (path in saves) {
                zip.putNextEntry(ZipEntry(path).apply { time = now.time })
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return saves
    }

    /** What [input] holds: its manifest's fields (null without one) and its saves */
    fun inspect(input: InputStream): SavesContents {
        var manifest: Map<String, String> = emptyMap()
        val saves = mutableListOf<String>()
        readZip(input) { name, zip ->
            when {
                name == MANIFEST -> manifest = parseManifest(zip.readLimited())
                isTransferable(name) -> saves += name
            }
        }
        if (saves.isEmpty()) throw Error("This file holds no saves of a PSDK game (a Saves folder)")
        return SavesContents(manifest["game"], manifest["platform"], manifest["exported_at"], saves)
    }

    /**
     * Replaces the saves of [root] with those of [input]; the saves in place
     * move to [backupRoot]/<date>/ first. Read whole before anything moves;
     * a move that fails puts everything back. Leaves the marker
     * [takeImported] takes, before the first move: a game running on the
     * old saves closes, whatever happens to this screen meanwhile. One
     * import at a time per process.
     */
    fun import(input: InputStream, root: File, backupRoot: File, now: Date = Date()): Imported {
        if (!importing.compareAndSet(false, true)) throw Error("An import is already running")
        val staging = File(root, STAGING_DIR)
        try {
            staging.deleteRecursively()
            val saves = linkedSetOf<String>()
            readZip(input) { name, zip ->
                // A name given twice: the first one
                if (!isTransferable(name) || name in saves) return@readZip
                val target = File(staging, name)
                target.parentFile?.mkdirs()
                target.writeBytes(zip.readLimited())
                saves += name
            }
            if (saves.isEmpty()) throw Error("This file holds no saves of a PSDK game (a Saves folder)")

            val current = files(root)
            val backup = if (current.isEmpty()) null else freeDir(backupRoot, stamp(now))
            val marker = File(root, IMPORTED_MARKER)
            marker.writeText(now.time.toString())
            val backedUp = mutableListOf<String>()
            val placed = mutableListOf<String>()
            try {
                for (path in current) move(File(root, path), File(backup!!, path)).also { backedUp += path }
                for (path in saves) move(File(staging, path), File(root, path)).also { placed += path }
            } catch (e: IOException) {
                // Back as before: the imported saves out, the saves in place back
                val restored = placed.all { File(root, it).delete() } &&
                    backedUp.all { File(backup!!, it).renameTo(File(root, it)) }
                if (restored) marker.delete()
                throw Error(if (restored) "Saves not imported (${e.message}): nothing changed"
                    else "Saves not imported (${e.message}): your previous saves are in ${backup?.name}", e)
            }
            return Imported(saves.toList(), backup)
        } finally {
            staging.deleteRecursively()
            importing.set(false)
        }
    }

    private val importing = java.util.concurrent.atomic.AtomicBoolean(false)

    private val LEGACY_NAMES = runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)

    /** [root]/[name], or [name]-2, -3... when taken: never into another backup */
    private fun freeDir(root: File, name: String): File =
        generateSequence(1) { it + 1 }.map { File(root, if (it == 1) name else "$name-$it") }.first { !it.exists() }

    /** Whether saves were imported into [root] since the last call: a game running on the old ones must stop */
    fun takeImported(root: File): Boolean = File(root, IMPORTED_MARKER).delete()

    /** The saves file's name: <title>-saves-<date>.zip */
    fun defaultName(game: String?, now: Date = Date()): String {
        val name = (game?.takeIf { it.isNotBlank() } ?: "game").replace(Regex("""[^\p{L}\p{N}\-_.]+"""), "_")
        return "$name-saves-${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(now)}.zip"
    }

    class Imported(val saves: List<String>, val backup: File?)

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw Error("$from not moved to $to")
    }

    private fun readZip(input: InputStream, onEntry: (String, ZipInputStream) -> Unit) {
        try {
            // Names without the UTF-8 flag: IBM437, as the ZIP spec has it (and the Ruby side)
            ZipInputStream(input, LEGACY_NAMES).use { zip ->
                var entry = zip.nextEntry ?: throw Error("This file is not a saves file (an empty or not a ZIP file)")
                while (true) {
                    if (!entry.isDirectory) onEntry(entry.name, zip)
                    entry = zip.nextEntry ?: break
                }
            }
        } catch (e: ZipException) {
            // Damaged, or a name that would write outside (Android 14+ rejects ../ names)
            throw Error("This file is not a saves file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // A name neither UTF-8 nor flagged otherwise (Windows Explorer: its code page)
            throw Error("This file is not a saves file: a name can't be read (${e.message})", e)
        }
    }

    private fun ZipInputStream.readLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_SIZE) throw Error("This file is not a saves file: an entry is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.putText(name: String, text: String, now: Date) {
        putNextEntry(ZipEntry(name).apply { time = now.time })
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun stamp(now: Date) = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now)

    private fun iso(now: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

    internal fun manifest(game: String?, now: Date, saves: List<String>): String = buildString {
        append("{\n")
        append("  \"format\": ").append(FORMAT).append(",\n")
        append("  \"game\": ").append(game?.let(::jsonString) ?: "null").append(",\n")
        append("  \"platform\": \"android\",\n")
        append("  \"exported_at\": \"").append(iso(now)).append("\",\n")
        append("  \"files\": [").append(saves.joinToString(", ", transform = ::jsonString)).append("]\n")
        append("}\n")
    }

    /**
     * The manifest's string fields, and its format (checked): a flat JSON
     * object read without a JSON library (org.json is the device's only).
     */
    internal fun parseManifest(data: ByteArray): Map<String, String> {
        val text = data.toString(Charsets.UTF_8)
        val format = Regex(""""format"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (format != null && format > FORMAT) throw Error("Saves file format $format: this version reads $FORMAT. Update the game.")
        val fields = mutableMapOf<String, String>()
        for (match in Regex(""""(game|platform|exported_at)"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(text)) {
            fields[match.groupValues[1]] = unescape(match.groupValues[2])
        }
        return fields
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c != '\\' || i >= value.length) {
                append(c)
                continue
            }
            when (val e = value[i++]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'u' -> if (i + 4 <= value.length) {
                    value.substring(i, i + 4).toIntOrNull(16)?.let { append(it.toChar()) }
                    i += 4
                }
                else -> append(e)
            }
        }
    }

    private val README_TEXT = """
        Saves of a PSDK game, exported to move them to another device.

        On Android: in the game, open the gamepad's tools button > Settings,
        then Saves > Import saves, and pick this file.

        On a computer: from the game's folder, run
            ruby save_transfer.rb import <this file>
        or unzip this file into the game's folder (where Game.rb is), letting it
        replace the files already there. When the game's folder is read-only,
        the game keeps its saves in %APPDATA%\.<game title>\ (Windows) or
        ~/.<game title>/ instead: unzip it there.

        psdk_saves.json and README.txt are not needed by the game.

    """.trimIndent()
}
