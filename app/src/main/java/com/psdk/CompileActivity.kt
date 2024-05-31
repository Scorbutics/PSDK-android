package com.psdk

import android.app.Activity
import android.content.Intent
import com.psdk.ruby.vm.RubyScript.ScriptCurrentLocation
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.widget.ScrollView
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.RubyInterpreter
import com.psdk.ruby.vm.RubyScript
import java.io.IOException
import java.lang.Exception
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class CompileActivity : Activity() {
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
        val compilationScrollView = findViewById<ScrollView>(R.id.compilationScrollView)
        compilationLog.isSelected = true
        val compilationEndState = findViewById<TextView>(R.id.compilationEndState)
        val self: Activity = this
        val rubyInterpreter = object : RubyInterpreter(assets, applicationInfo.dataDir, buildPsdkProcessData()) {
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
        m_rubyInterpreter = rubyInterpreter

        val onCompleteCompilation: CompletionTask = { returnCode: Int ->
            var resultText: String;
            if (returnCode == 0) {
                resultText = "Compilation success !"
                val mainIntent = Intent(self, MainActivity::class.java)
                startActivity(mainIntent)
            } else {
                resultText = "Compilation failure"
            }
            runOnUiThread {
                compilationEndState.text = resultText
            }
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            if (returnCode != 0) {
                runOnUiThread {
                    compilationEndState.text = "Check engine failure"
                    val mainIntent = Intent(self, MainActivity::class.java)
                    startActivity(mainIntent)
                }
            } else {
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