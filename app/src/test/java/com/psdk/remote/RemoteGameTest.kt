package com.psdk.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The exporter writes JSON nulls for what doesn't apply (a plain .psa has no
// cert, a game from sources no Ruby). org.json's optString reads them as
// "null": a .psa small APK was refused as "published for another version".
class RemoteGameTest {
    private val url = "https://example.com/releases/latest/download/android-game.json"

    @Test
    fun jsonNullsAreAbsent() {
        val manifest = RemoteGame.parseManifest(
            """{"format":1,"title":"Lethalmon","version":"536",
               "archive":{"url":"game.psa","size":42,"sha256":"ab"},
               "ruby":null,"backend":"raylib","compiled":false,"cert_sha256":null,"min_app_version":"1.14.0"}""",
            url,
        )
        assertNull(manifest.certSha256)
        assertNull(manifest.ruby)
        assertEquals("raylib", manifest.backend)
        assertEquals("game.psa", manifest.archiveName)
        assertEquals("https://example.com/releases/latest/download/game.psa", manifest.archiveUrl)
    }
}
