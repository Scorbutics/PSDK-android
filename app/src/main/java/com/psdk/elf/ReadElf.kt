/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.psdk.elf

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.HashMap
import kotlin.experimental.and

/**
 * A poor man's implementation of the readelf command. This program is designed
 * to parse ELF (Executable and Linkable Format) files.
 */
class ReadElf private constructor(file: File) : AutoCloseable {
    class Symbol internal constructor(val name: String, st_info: Int) {
        val bind: Int
        val type: Int

        init {
            bind = st_info shr 4 and 0x0F
            type = st_info and 0x0F
        }

        override fun toString(): String {
            return "Symbol[" + name + "," + toBind() + "," + toType() + "]"
        }

        private fun toBind(): String {
            when (bind) {
                STB_LOCAL -> return "LOCAL"
                STB_GLOBAL -> return "GLOBAL"
                STB_WEAK -> return "WEAK"
            }
            return "STB_??? ($bind)"
        }

        private fun toType(): String {
            when (type) {
                STT_NOTYPE -> return "NOTYPE"
                STT_OBJECT -> return "OBJECT"
                STT_FUNC -> return "FUNC"
                STT_SECTION -> return "SECTION"
                STT_FILE -> return "FILE"
                STT_COMMON -> return "COMMON"
                STT_TLS -> return "TLS"
            }
            return "STT_??? ($type)"
        }

        companion object {
            const val STB_LOCAL = 0
            const val STB_GLOBAL = 1
            const val STB_WEAK = 2
            const val STB_LOPROC = 13
            const val STB_HIPROC = 15
            const val STT_NOTYPE = 0
            const val STT_OBJECT = 1
            const val STT_FUNC = 2
            const val STT_SECTION = 3
            const val STT_FILE = 4
            const val STT_COMMON = 5
            const val STT_TLS = 6
        }
    }

    private val mPath: String
    private val mFile: RandomAccessFile
    private val mBuffer = ByteArray(512)
    private var mEndian = 0
    var isDynamic = false
        private set
    var isPIE = false
        private set
    var type = 0
        private set
    private var mAddrSize = 0
    var arch = 0
        private set

    /** Symbol Table offset  */
    private var mSymTabOffset: Long = 0

    /** Symbol Table size  */
    private var mSymTabSize: Long = 0

    /** Dynamic Symbol Table offset  */
    private var mDynSymOffset: Long = 0

    /** Dynamic Symbol Table size  */
    private var mDynSymSize: Long = 0

    /** Section Header String Table offset  */
    private var mShStrTabOffset: Long = 0

    /** Section Header String Table size  */
    private var mShStrTabSize: Long = 0

    /** String Table offset  */
    private var mStrTabOffset: Long = 0

    /** String Table size  */
    private var mStrTabSize: Long = 0

    /** Dynamic String Table offset  */
    private var mDynStrOffset: Long = 0

    /** Dynamic String Table size  */
    private var mDynStrSize: Long = 0

    /** Symbol Table symbol names  */
    private var mSymbols: Map<String, Symbol>? = null

    /** Dynamic Symbol Table symbol names  */
    private var mDynamicSymbols: Map<String, Symbol>? = null

    init {
        mPath = file.path
        mFile = RandomAccessFile(file, "r")
        require(mFile.length() >= EI_NIDENT) { "Too small to be an ELF file: $file" }
        readHeader()
    }

    override fun close() {
        try {
            mFile.close()
        } catch (ignored: IOException) {
        }
    }

    @Throws(Throwable::class)
    protected fun finalize() {
        close()
    }

    @Throws(IOException::class)
    private fun readHeader() {
        mFile.seek(0)
        mFile.readFully(mBuffer, 0, EI_NIDENT)
        require(!(mBuffer[0] != ELFMAG[0] || mBuffer[1] != ELFMAG[1] || mBuffer[2] != ELFMAG[2] || mBuffer[3] != ELFMAG[3])) { "Invalid ELF file: $mPath" }
        val elfClass = mBuffer[EI_CLASS].toInt()
        mAddrSize = if (elfClass == ELFCLASS32) {
            4
        } else if (elfClass == ELFCLASS64) {
            8
        } else {
            throw IOException("Invalid ELF EI_CLASS: $elfClass: $mPath")
        }
        mEndian = mBuffer[EI_DATA].toInt()
        if (mEndian == ELFDATA2LSB) {
        } else if (mEndian == ELFDATA2MSB) {
            throw IOException("Unsupported ELFDATA2MSB file: $mPath")
        } else {
            throw IOException("Invalid ELF EI_DATA: $mEndian: $mPath")
        }
        type = readHalf()
        val e_machine = readHalf()
        if (e_machine != EM_386 && e_machine != EM_X86_64 && e_machine != EM_AARCH64 && e_machine != EM_ARM && e_machine != EM_MIPS && e_machine != EM_QDSP6) {
            throw IOException("Invalid ELF e_machine: $e_machine: $mPath")
        }
        // AbiTest relies on us rejecting any unsupported combinations.
        if (e_machine == EM_386 && elfClass != ELFCLASS32 ||
                e_machine == EM_X86_64 && elfClass != ELFCLASS64 ||
                e_machine == EM_AARCH64 && elfClass != ELFCLASS64 ||
                e_machine == EM_ARM && elfClass != ELFCLASS32 ||
                e_machine == EM_QDSP6 && elfClass != ELFCLASS32
        ) {
            throw IOException("Invalid e_machine/EI_CLASS ELF combination: " +
                    e_machine + "/" + elfClass + ": " + mPath)
        }
        arch = e_machine
        val e_version = readWord()
        if (e_version != EV_CURRENT.toLong()) {
            throw IOException("Invalid e_version: $e_version: $mPath")
        }
        val e_entry = readAddr()
        val ph_off = readOff()
        val sh_off = readOff()
        val e_flags = readWord()
        val e_ehsize = readHalf()
        val e_phentsize = readHalf()
        val e_phnum = readHalf()
        val e_shentsize = readHalf()
        val e_shnum = readHalf()
        val e_shstrndx = readHalf()
        readSectionHeaders(sh_off, e_shnum, e_shentsize, e_shstrndx)
        readProgramHeaders(ph_off, e_phnum, e_phentsize)
    }

    @Throws(IOException::class)
    private fun readSectionHeaders(sh_off: Long, e_shnum: Int, e_shentsize: Int, e_shstrndx: Int) {
        // Read the Section Header String Table offset first.
        run {
            mFile.seek(sh_off + e_shstrndx * e_shentsize)
            val sh_name = readWord()
            val sh_type = readWord()
            val sh_flags = readX(mAddrSize)
            val sh_addr = readAddr()
            val sh_offset = readOff()
            val sh_size = readX(mAddrSize)
            // ...
            if (sh_type == SHT_STRTAB.toLong()) {
                mShStrTabOffset = sh_offset
                mShStrTabSize = sh_size
            }
        }
        for (i in 0 until e_shnum) {
            // Don't bother to re-read the Section Header StrTab.
            if (i == e_shstrndx) {
                continue
            }
            mFile.seek(sh_off + i * e_shentsize)
            val sh_name = readWord()
            val sh_type = readWord()
            val sh_flags = readX(mAddrSize)
            val sh_addr = readAddr()
            val sh_offset = readOff()
            val sh_size = readX(mAddrSize)
            if (sh_type == SHT_SYMTAB.toLong() || sh_type == SHT_DYNSYM.toLong()) {
                val symTabName = readShStrTabEntry(sh_name)
                if (".symtab" == symTabName) {
                    mSymTabOffset = sh_offset
                    mSymTabSize = sh_size
                } else if (".dynsym" == symTabName) {
                    mDynSymOffset = sh_offset
                    mDynSymSize = sh_size
                }
            } else if (sh_type == SHT_STRTAB.toLong()) {
                val strTabName = readShStrTabEntry(sh_name)
                if (".strtab" == strTabName) {
                    mStrTabOffset = sh_offset
                    mStrTabSize = sh_size
                } else if (".dynstr" == strTabName) {
                    mDynStrOffset = sh_offset
                    mDynStrSize = sh_size
                }
            } else if (sh_type == SHT_DYNAMIC.toLong()) {
                isDynamic = true
            }
        }
    }

    @Throws(IOException::class)
    private fun readProgramHeaders(ph_off: Long, e_phnum: Int, e_phentsize: Int) {
        for (i in 0 until e_phnum) {
            mFile.seek(ph_off + i * e_phentsize)
            val p_type = readWord()
            if (p_type == PT_LOAD) {
                if (mAddrSize == 8) {
                    // Only in Elf64_phdr; in Elf32_phdr p_flags is at the end.
                    val p_flags = readWord()
                }
                val p_offset = readOff()
                val p_vaddr = readAddr()
                // ...
                if (p_vaddr == 0L) {
                    isPIE = true
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun readSymbolTable(symStrOffset: Long, symStrSize: Long,
                                tableOffset: Long, tableSize: Long): HashMap<String, Symbol> {
        val result = HashMap<String, Symbol>()
        mFile.seek(tableOffset)
        while (mFile.filePointer < tableOffset + tableSize) {
            val st_name = readWord()
            var st_info: Int
            if (mAddrSize == 8) {
                st_info = readByte()
                val st_other = readByte()
                val st_shndx = readHalf()
                val st_value = readAddr()
                val st_size = readX(mAddrSize)
            } else {
                val st_value = readAddr()
                val st_size = readWord()
                st_info = readByte()
                val st_other = readByte()
                val st_shndx = readHalf()
            }
            if (st_name == 0L) {
                continue
            }
            val symName = readStrTabEntry(symStrOffset, symStrSize, st_name)
            if (symName != null) {
                val s = Symbol(symName, st_info)
                result[symName] = s
            }
        }
        return result
    }

    @Throws(IOException::class)
    private fun readShStrTabEntry(strOffset: Long): String? {
        return if (mShStrTabOffset == 0L || strOffset < 0 || strOffset >= mShStrTabSize) {
            null
        } else readString(mShStrTabOffset + strOffset)
    }

    @Throws(IOException::class)
    private fun readStrTabEntry(tableOffset: Long, tableSize: Long, strOffset: Long): String? {
        return if (tableOffset == 0L || strOffset < 0 || strOffset >= tableSize) {
            null
        } else readString(tableOffset + strOffset)
    }

    @Throws(IOException::class)
    private fun readHalf(): Int {
        return readX(2).toInt()
    }

    @Throws(IOException::class)
    private fun readWord(): Long {
        return readX(4)
    }

    @Throws(IOException::class)
    private fun readOff(): Long {
        return readX(mAddrSize)
    }

    @Throws(IOException::class)
    private fun readAddr(): Long {
        return readX(mAddrSize)
    }

    @Throws(IOException::class)
    private fun readX(byteCount: Int): Long {
        mFile.readFully(mBuffer, 0, byteCount)
        var answer = 0
        if (mEndian == ELFDATA2LSB) {
            for (i in byteCount - 1 downTo 0) {
                answer = answer shl 8 or 0xff.toByte().and(mBuffer[i]).toInt()
            }
        } else {
            val N = byteCount - 1
            for (i in 0..N) {
                answer = answer shl 8 or 0xff.toByte().and(mBuffer[i]).toInt()
            }
        }
        return answer.toLong()
    }

    @Throws(IOException::class)
    private fun readString(offset: Long): String? {
        val originalOffset = mFile.filePointer
        mFile.seek(offset)
        mFile.readFully(mBuffer, 0, Math.min(mBuffer.size.toLong(), mFile.length() - offset).toInt())
        mFile.seek(originalOffset)
        for (i in mBuffer.indices) {
            if (mBuffer[i].toInt() == 0) {
                return String(mBuffer, 0, i)
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun readByte(): Int {
        return mFile.read() and 0xff
    }

    fun getSymbol(name: String): Symbol? {
        if (mSymbols == null) {
            mSymbols = try {
                readSymbolTable(mStrTabOffset, mStrTabSize, mSymTabOffset, mSymTabSize)
            } catch (e: IOException) {
                return null
            }
        }
        return mSymbols!![name]
    }

    fun getDynamicSymbol(name: String): Symbol? {
        if (mDynamicSymbols == null) {
            mDynamicSymbols = try {
                readSymbolTable(
                        mDynStrOffset, mDynStrSize, mDynSymOffset, mDynSymSize)
            } catch (e: IOException) {
                return null
            }
        }
        return mDynamicSymbols!![name]
    }

    companion object {
        /** The magic values for the ELF identification.  */
        private val ELFMAG = byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        private const val EI_NIDENT = 16
        private const val EI_CLASS = 4
        private const val EI_DATA = 5
        private const val EM_386 = 3
        private const val EM_MIPS = 8
        private const val EM_ARM = 40
        private const val EM_X86_64 = 62

        // http://en.wikipedia.org/wiki/Qualcomm_Hexagon
        private const val EM_QDSP6 = 164
        private const val EM_AARCH64 = 183
        private const val ELFCLASS32 = 1
        private const val ELFCLASS64 = 2
        private const val ELFDATA2LSB = 1
        private const val ELFDATA2MSB = 2
        private const val EV_CURRENT = 1
        private const val PT_LOAD: Long = 1
        private const val SHT_SYMTAB = 2
        private const val SHT_STRTAB = 3
        private const val SHT_DYNAMIC = 6
        private const val SHT_DYNSYM = 11
        @Throws(IOException::class)
        fun read(file: File): ReadElf {
            return ReadElf(file)
        }
    }
}