package com.psdk.signing

import android.app.Application
import android.content.Context
import java.nio.file.Path

data class SigningOptions(
    val signerName: String,
    val password: String,
    val keyStoreFilePath: Path
)

fun buildDefaultSigningOptions(app: Application): SigningOptions {
    val defaultKeystorePath = app.getDir("signing", Context.MODE_PRIVATE).resolve("manager.keystore").toPath()
    return SigningOptions (
        signerName = "PSDK",
        keyStoreFilePath = defaultKeystorePath,
        password = "PSDK"
    )
}
