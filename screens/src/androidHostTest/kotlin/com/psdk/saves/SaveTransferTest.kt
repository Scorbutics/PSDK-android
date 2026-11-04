package com.psdk.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveTransferTest {
    private val dir = kotlin.io.path.createTempDirectory().toFile()
    private val game = File(dir, "game")
    private val now = Date(1793795400000L) // 2026-11-04T12:30:00Z

    private val saves = listOf(
        "GlobalPokedex.dat", "GlobalPokedex.dat.bak", "Saves/Pokemon_Party", "Saves/Pokemon_Party-2",
        "Saves/Pokemon_Party.bak", "deleted_saves/Pokemon_Party-3",
    )

    init {
        write(
            game,
            "Saves/Pokemon_Party" to byteArrayOf(0x50, 0x4b, 0, -1),
            "Saves/Pokemon_Party.bak" to "old".toByteArray(),
            "Saves/Pokemon_Party-2" to "second".toByteArray(),
            "Saves/input.json" to "keys".toByteArray(),
            "Saves/Telemetry/id" to "abc".toByteArray(),
            "GlobalPokedex.dat" to "dex".toByteArray(),
            "GlobalPokedex.dat.bak" to "dex-old".toByteArray(),
            "deleted_saves/Pokemon_Party-3" to "gone".toByteArray(),
            "battle.dat" to "data".toByteArray(),
            ".gameopts" to "--scale=2".toByteArray(),
            "Game.rb" to "puts 1".toByteArray(),
        )
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun write(root: File, vararg files: Pair<String, ByteArray>) {
        for ((path, data) in files) File(root, path).apply { parentFile.mkdirs() }.writeBytes(data)
    }

    private fun tree(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private fun exported(): ByteArray = ByteArrayOutputStream().also { SaveTransfer.export(game, it, "Test Game", now) }.toByteArray()

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun transferablePaths() {
        saves.forEach { assertTrue(SaveTransfer.isTransferable(it), it) }
        listOf(
            "Saves/input.json", "Saves/input.yml", "Saves/Telemetry/id", "battle.dat", ".gameopts", "Game.rb", "Data/configs/x.json", "Saves", "Saves/",
            "../Saves/x", "Saves/../Game.rb", "/Saves/x", "Saves//x", "Saves/./x", "Saves\\x", "C:/Saves/x",
            "SavesBackup/1/Saves/x", "GlobalPokedex.dat/x", "sub/GlobalPokedex.dat", "__MACOSX/Saves/._x",
            "psdk_saves.json", "README.txt",
        ).forEach { assertFalse(SaveTransfer.isTransferable(it), it) }
    }

    @Test
    fun exportWritesTheSavesAndAManifest() {
        val names = mutableListOf<String>()
        var manifest = ""
        ZipInputStream(ByteArrayInputStream(exported())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                if (entry.name == SaveTransfer.MANIFEST) manifest = zip.readBytes().decodeToString()
            }
        }
        assertEquals(listOf("psdk_saves.json", "README.txt") + saves, names)
        assertTrue(manifest.contains("\"platform\": \"android\""), manifest)
        assertTrue(manifest.contains("\"exported_at\": \"2026-11-04T12:30:00Z\""), manifest)
        assertEquals(mapOf("game" to "Test Game", "platform" to "android", "exported_at" to "2026-11-04T12:30:00Z"),
            SaveTransfer.parseManifest(manifest.toByteArray()))
    }

    @Test
    fun exportWithoutSavesFails() {
        assertFailsWith<SaveTransfer.Error> { SaveTransfer.export(File(dir, "none"), ByteArrayOutputStream(), "x") }
    }

    @Test
    fun importReplacesTheSavesAndKeepsTheOthersAside() {
        val other = File(dir, "other")
        write(other, "Saves/Pokemon_Party-1" to "theirs".toByteArray(), "Saves/input.json" to "their keys".toByteArray(),
            "GlobalPokedex.dat" to "their dex".toByteArray(), "Game.rb" to "their game".toByteArray())
        val backupRoot = File(dir, "SavesBackup")

        val imported = SaveTransfer.import(ByteArrayInputStream(exported()), other, backupRoot, now)

        assertEquals(saves, imported.saves.sorted())
        val tree = tree(other)
        saves.forEach { assertContentEquals(File(game, it).readBytes(), File(other, it).readBytes(), it) }
        assertFalse("Saves/Pokemon_Party-1" in tree)
        assertEquals("their keys", tree["Saves/input.json"])
        assertEquals("their game", tree["Game.rb"])
        val backup = imported.backup!!
        assertEquals(backupRoot, backup.parentFile)
        assertEquals(mapOf("GlobalPokedex.dat" to "their dex", "Saves/Pokemon_Party-1" to "theirs"), tree(backup))
        assertFalse(File(other, ".saves_import").exists())
        assertTrue(SaveTransfer.takeImported(other))
        assertFalse(SaveTransfer.takeImported(other))
    }

    @Test
    fun importIntoAnEmptyFolderHasNoBackup() {
        val target = File(dir, "new")
        assertNull(SaveTransfer.import(ByteArrayInputStream(exported()), target, File(dir, "SavesBackup"), now).backup)
        assertEquals(saves, SaveTransfer.files(target))
    }

    @Test
    fun aZipMadeByHandOnlyGivesItsSaves() {
        val data = zip("Saves/Pokemon_Party" to "save", "Game.rb" to "evil", "__MACOSX/Saves/._Pokemon_Party" to "mac")
        val contents = SaveTransfer.inspect(ByteArrayInputStream(data))
        assertEquals(SavesContents(null, null, null, listOf("Saves/Pokemon_Party")), contents)
        val target = File(dir, "target")
        write(target, "Game.rb" to "mine".toByteArray())
        SaveTransfer.import(ByteArrayInputStream(data), target, File(dir, "b"), now)
        assertEquals(mapOf("Game.rb" to "mine", "Saves/Pokemon_Party" to "save", ".saves_imported" to now.time.toString()), tree(target))
    }

    @Test
    fun aFileWithoutSavesChangesNothing() {
        val before = tree(game)
        assertFailsWith<SaveTransfer.Error> {
            SaveTransfer.import(ByteArrayInputStream(zip("Game.rb" to "x")), game, File(dir, "b"), now)
        }
        assertFailsWith<SaveTransfer.Error> {
            SaveTransfer.import(ByteArrayInputStream("not a zip".toByteArray()), game, File(dir, "b"), now)
        }
        assertEquals(before, tree(game))
    }

    @Test
    fun aNameGivenTwiceIsTheFirstOne() {
        val target = File(dir, "target")
        // ZipOutputStream refuses a name twice: a name of the same length, patched
        val data = zip("Saves/Pokemon_Party" to "first", "Saves/Pokemon_PartX" to "second")
        val text = String(data, Charsets.ISO_8859_1).replace("Saves/Pokemon_PartX", "Saves/Pokemon_Party")
        val imported = SaveTransfer.import(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), target, File(dir, "b"), now)
        assertEquals(listOf("Saves/Pokemon_Party"), imported.saves)
        assertEquals("first", File(target, "Saves/Pokemon_Party").readText())
    }

    @Test
    fun twoImportsInTheSameSecondKeepBothBackups() {
        val backupRoot = File(dir, "SavesBackup")
        val first = SaveTransfer.import(ByteArrayInputStream(zip("Saves/Pokemon_Party" to "1")), game, backupRoot, now)
        val second = SaveTransfer.import(ByteArrayInputStream(zip("Saves/Pokemon_Party" to "2")), game, backupRoot, now)
        assertEquals("20261104-${first.backup!!.name.substringAfter('-')}", first.backup!!.name)
        assertEquals("${first.backup!!.name}-2", second.backup!!.name)
        assertEquals("second", File(first.backup, "Saves/Pokemon_Party-2").readText())
        assertEquals("1", File(second.backup, "Saves/Pokemon_Party").readText())
    }

    /** A move that fails (a folder where a save goes): everything back as before, no marker */
    @Test
    fun aFailedImportPutsEverythingBack() {
        val target = File(dir, "target")
        write(target, "Saves/Pokemon_Party/x" to "a folder's file".toByteArray(), "Saves/Pokemon_Party-1" to "mine".toByteArray())
        val before = tree(target)
        assertFailsWith<SaveTransfer.Error> {
            SaveTransfer.import(
                ByteArrayInputStream(zip("Saves/A" to "a", "Saves/Pokemon_Party" to "theirs")),
                target, File(dir, "SavesBackup"), now,
            )
        }
        assertEquals(before, tree(target))
        assertFalse(SaveTransfer.takeImported(target))
        // And the next one runs: the failed one let go of its lock
        SaveTransfer.import(ByteArrayInputStream(zip("Saves/A" to "a")), File(dir, "other"), File(dir, "b"), now)
    }

    /** Windows Explorer: names in its code page, no UTF-8 flag */
    @Test
    fun namesWithoutTheUtf8FlagAreRead() {
        val data = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out, java.nio.charset.Charset.forName("IBM437")).use { zip ->
                for (name in listOf("Saves/Pokemon_Party", "Notes é.txt")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        assertEquals(listOf("Saves/Pokemon_Party"), SaveTransfer.inspect(ByteArrayInputStream(data)).saves)
    }

    @Test
    fun aNewerFormatIsRefused() {
        val data = zip("psdk_saves.json" to "{\"format\": 2}", "Saves/Pokemon_Party" to "x")
        assertFailsWith<SaveTransfer.Error> { SaveTransfer.inspect(ByteArrayInputStream(data)) }
    }

    /** Written by the exporter's lib/save_transfer.rb (desktop), title 'Jeu é "test"' */
    @Test
    fun readsASavesFileOfTheDesktop() {
        val data = Base64.getDecoder().decode(RUBY_SAVES_FILE)
        val contents = SaveTransfer.inspect(ByteArrayInputStream(data))
        assertEquals("Jeu é \"test\"", contents.game)
        assertEquals("desktop", contents.platform)
        assertEquals("2026-11-04T12:30:00Z", contents.exportedAt)
        assertEquals(listOf("GlobalPokedex.dat", "Saves/Pokemon_Party", "deleted_saves/Pokemon_Party-3"), contents.saves)

        val target = File(dir, "target")
        SaveTransfer.import(ByteArrayInputStream(data), target, File(dir, "b"), now)
        assertContentEquals(byteArrayOf(0x50, 0x4b, 0x50, 0x52, 0x54, 0, -1), File(target, "Saves/Pokemon_Party").readBytes())
        assertEquals("gone", File(target, "deleted_saves/Pokemon_Party-3").readText())
    }

    @Test
    fun defaultName() {
        assertEquals("Jeu_é_test_-saves-20261104.zip", SaveTransfer.defaultName("Jeu é \"test\"", now))
        assertEquals("game-saves-20261104.zip", SaveTransfer.defaultName(null, now))
    }

    private companion object {
        const val RUBY_SAVES_FILE = "UEsDBBQAAAgIAMBjZF0k6BdwngAAANoAAAAPAAAAcHNka19zYXZlcy5qc29uZY1BCsIwEEX3PUWYdatJKy56AaGrgq60UiKZijQ1IRmlIh7Ic3gxkxZXLh/v/Zlnwhh0xg2SoGQijXiWAwaACm/s82YNEHpqACZptaTYx0Ch78nYWeBojSNU7XQJcp6vMyEyvtqJvCx4yfl+DruLRh+SQ4CAG21OUtemR4XjQoV1OoutvKNfRjGYa1tLR4+fUqgxvvL/SVZAaI7J6wtQSwMEFAAACAgAwGNkXYFXnS1aAQAARAIAAAoAAABSRUFETUUudHh0ZZJPawIxEMXv+RTvIlXYrncRYUEppZRKLXgRJJpZTd1NQpL1Tw/97J2Ni7Z0T5lk8nvzXnYhjxRgS0jMF9MX7GRNGejsrI+kEC1qeyTEPdVtIY3lpYeio95SLsSbQWGUt1qNoE3b1yGso3vppHoIfN9WAZsmRmswwYJi1GYXMsFtBos0yQTPdauN0JYZCyo4vT0wSgeUuupEJba2dk0kP0LpbX2TYp3SVop8Bt8YAf58s7kk3jp6aUJJPvcb6KvO+AaeCOvRmC/t7mLsiV3/Y6N/4hQIT7yZWGGQobr6gY7Ck6vkNsWWMAGy8iTVpd3xlGO5/5XOHcuqbdujNdUlxZLOcSBygbnhmkobdK+Yz6fFR9Fb5ePUE3VkCyv0l9ooewoDWC++h39Oh3wzRBYYdT517AYSwgV1WCd8/hn4fdrg32fF9HWWx3OEZLf89jBEiv+LzeU2fS5+AFBLAwQUAAAICADAY2RdAtzL9gUAAAADAAAAEQAAAEdsb2JhbFBva2VkZXguZGF0S0mtAABQSwMEFAAACAgAwGNkXWWthsQJAAAABwAAABMAAABTYXZlcy9Qb2tlbW9uX1BhcnR5C/AOCAph+A8AUEsDBBQAAAgIAMBjZF1FT5gCBgAAAAQAAAAdAAAAZGVsZXRlZF9zYXZlcy9Qb2tlbW9uX1BhcnR5LTNLz89LBQBQSwECFAAUAAAICADAY2RdJOgXcJ4AAADaAAAADwAAAAAAAAAAAAAAAAAAAAAAcHNka19zYXZlcy5qc29uUEsBAhQAFAAACAgAwGNkXYFXnS1aAQAARAIAAAoAAAAAAAAAAAAAAAAAywAAAFJFQURNRS50eHRQSwECFAAUAAAICADAY2RdAtzL9gUAAAADAAAAEQAAAAAAAAAAAAAAAABNAgAAR2xvYmFsUG9rZWRleC5kYXRQSwECFAAUAAAICADAY2RdZa2GxAkAAAAHAAAAEwAAAAAAAAAAAAAAAACBAgAAU2F2ZXMvUG9rZW1vbl9QYXJ0eVBLAQIUABQAAAgIAMBjZF1FT5gCBgAAAAQAAAAdAAAAAAAAAAAAAAAAALsCAABkZWxldGVkX3NhdmVzL1Bva2Vtb25fUGFydHktM1BLBQYAAAAABQAFAEABAAD8AgAAAAA="
    }
}
