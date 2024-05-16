package com.psdk

import android.app.Activity
import android.content.Intent
import com.psdk.PsdkProcess.InputData
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.widget.ScrollView
import java.io.IOException
import java.lang.Exception
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class CompileActivity : Activity() {
    private var m_psdkProcessLauncher: PsdkProcessLauncher? = null
    private var m_applicationPath: String? = null
    private var m_internalWriteablePath: String? = null
    private var m_executionLocation: String? = null
    private var m_archiveLocation: String? = null
    private var m_outputArchiveLocation: String? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compiler)
        m_applicationPath = applicationInfo.dataDir
        m_internalWriteablePath = filesDir.path
        m_executionLocation = intent.getStringExtra("EXECUTION_LOCATION")
        m_archiveLocation = intent.getStringExtra("ARCHIVE_LOCATION")
        m_outputArchiveLocation = intent.getStringExtra("OUTPUT_ARCHIVE_LOCATION")
        val compilationLog = findViewById<TextView>(R.id.compilationLog)
        val compilationScrollView = findViewById<ScrollView>(R.id.compilationScrollView)
        compilationLog.isSelected = true
        val compilationEndState = findViewById<TextView>(R.id.compilationEndState)
        val self: Activity = this
        val processLauncher  = object : PsdkProcessLauncher(m_applicationPath) {
            override fun accept(lineMessage: String?) {
                runOnUiThread {
                    compilationLog.append(lineMessage)
                    compilationLog.append("\n")
                    compilationScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }

            override fun onLogError(e: Exception) {
                Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
        val onCompleteCompilation = { returnCode: Int -> {
                if (returnCode == 0) {
                    compilationEndState.text = "Compilation success !"
                    try {
                        ZipUtility.zip("$m_executionLocation/Release", m_outputArchiveLocation)
                        //removeRecursivelyDirectory(m_executionLocation + "/Release");
                        val mainIntent = Intent(self, MainActivity::class.java)
                        startActivity(mainIntent)
                    } catch (e: Exception) {
                        compilationEndState.text = "Unable to build the final archive: " + e.localizedMessage
                    }
                } else {
                    compilationEndState.text = "Compilation failure"
                }
            }
        }

        val onCompleteCheckEngine = { returnCode: Int ->
            if (returnCode != 0) {
                compilationEndState.text = "Check engine failure"
            }
        }

        m_psdkProcessLauncher = processLauncher
        try {
            //processLauncher.runAsync(PsdkProcess(this, CHECK_ENGINE_SCRIPT), buildPsdkProcessData(), onCompleteCheckEngine);
            processLauncher.join()
            processLauncher.runAsync(PsdkProcess(this, SCRIPT), buildPsdkProcessData(), onCompleteCompilation)
        } catch (ex: Exception) {
            Toast.makeText(applicationContext, ex.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    @Throws(IOException::class)
    private fun removeRecursivelyDirectory(directoryPath: String) {
        val directory = Paths.get(directoryPath)
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            @Throws(IOException::class)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun postVisitDirectory(dir: Path, exc: IOException): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun onBackPressed() {
        m_psdkProcessLauncher!!.killCurrentProcess()
    }

    private fun buildPsdkProcessData(): InputData {
        return InputData(m_internalWriteablePath, m_executionLocation, m_archiveLocation)
    }

    companion object {
        private const val SCRIPT = "compile.rb"
        private const val CHECK_ENGINE_SCRIPT = "check_engine.rb"
    }
}