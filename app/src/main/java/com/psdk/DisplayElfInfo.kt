package com.psdk

import java.io.File
import java.lang.Exception
import java.util.HashMap

object DisplayElfInfo {
    private const val ARMV7ABI = "armeabi-v7a"
    private const val X86 = "x86"
    private const val MIPS = "mips"
    private const val X86_64 = "x86_64"
    private const val ARM64_V8 = "arm64-v8a"
    private const val ARMABI = "armeabi"
    private val typeMap = HashMap<Int, String>()
    fun findAbiType(libFile: File): String? {
        if (!libFile.exists()) {
            return ""
        }
        if (typeMap.isEmpty()) {
            initializeMap()
        }
        try {
            val elf: ReadElf = ReadElf.Companion.read(libFile)
            if (elf.type == 3) {
                val archCode = elf.arch
                elf.close()
                return typeMap[archCode]
            }
            elf.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun initializeMap() {
        typeMap[40] = ARMV7ABI
        typeMap[3] = X86
        typeMap[8] = MIPS
        typeMap[62] = X86_64
        typeMap[183] = ARM64_V8
        typeMap[164] = ARMABI
    }
}