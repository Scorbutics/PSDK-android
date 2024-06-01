package com.psdk

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.RubyInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.RubyScript.ScriptCurrentLocation
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class CompileActivity : ComponentActivity() {
    private var m_rubyInterpreter: RubyInterpreter? = null
    private var m_applicationPath: String? = null
    private var m_internalWriteablePath: String? = null
    private var m_executionLocation: String? = null
    private var m_archiveLocation: String? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compiler)
        m_applicationPath = applicationInfo.dataDir
        m_internalWriteablePath = filesDir.path
        m_executionLocation = intent.getStringExtra("EXECUTION_LOCATION")
        m_archiveLocation = intent.getStringExtra("ARCHIVE_LOCATION")
        val compilationLog = findViewById<TextView>(R.id.compilationLog)
        val progressBarCompilation = findViewById<ProgressBar>(R.id.progressBarCompilation)
        progressBarCompilation.visibility = View.VISIBLE
        val compilationEndState = findViewById<TextView>(R.id.compilationEndState)
        compilationEndState.visibility = View.GONE
        val compilationScrollView = findViewById<ScrollView>(R.id.compilationScrollView)
        compilationLog.isSelected = true
        val rubyInterpreter = object : RubyInterpreter(assets, applicationInfo.dataDir, buildPsdkProcessData()) {
            override fun accept(lineMessage: String?) {
                runOnUiThread {
                    compilationLog.append(lineMessage)
                    compilationLog.append("\n")
                    compilationScrollView.post{ compilationScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }

            override fun onLogError(e: Exception) {
                Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
        m_rubyInterpreter = rubyInterpreter

        val onCompleteCompilation: CompletionTask = { returnCode: Int ->
            val resultText: String;
            if (returnCode == 0) {
                resultText = "Compilation success !"
            } else {
                resultText = "Compilation failure"
            }
            runOnUiThread {
                progressBarCompilation.visibility = View.INVISIBLE
                compilationEndState.visibility = View.VISIBLE
                compilationEndState.text = resultText
                compilationEndState.setTextColor(if (returnCode == 0) Color.GREEN else Color.RED)
            }
            Thread.sleep(2000)
            setResult(returnCode)
            finish()
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            if (returnCode != 0) {
                runOnUiThread {
                    progressBarCompilation.visibility = View.INVISIBLE
                    compilationEndState.text = "Check engine failure"
                }
                Thread.sleep(2000)
                setResult(returnCode)
                finish()
            } else {
                runOnUiThread{
                    compilationLog.append("-------------------------\n")
                }
                rubyInterpreter.runAsync(RubyScript(assets, SCRIPT), onCompleteCompilation)
            }
        }
        try {
            rubyInterpreter.runAsync(RubyScript(assets, CHECK_ENGINE_SCRIPT), onCompleteCheck);
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

    private fun buildPsdkProcessData(): ScriptCurrentLocation {
        System.out.println(applicationInfo.nativeLibraryDir)
        return ScriptCurrentLocation(m_internalWriteablePath, m_executionLocation, applicationInfo.nativeLibraryDir, m_archiveLocation)
    }

    companion object {
        private const val SCRIPT = "compile.rb"
        private const val CHECK_ENGINE_SCRIPT = "check_engine.rb"
    }
}