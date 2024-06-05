package com.psdk

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ExpandableListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.RubyInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.RubyScript.ScriptCurrentLocation
import java.nio.file.*


class CompileActivity : ComponentActivity() {
    private var m_rubyInterpreter: RubyInterpreter? = null
    private var m_applicationPath: String? = null
    private var m_internalWriteablePath: String? = null
    private var m_executionLocation: String? = null
    private var m_archiveLocation: String? = null
    private var currentLogs: CompileStepLogs? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compiler)
        m_applicationPath = applicationInfo.dataDir
        m_internalWriteablePath = filesDir.path
        m_executionLocation = intent.getStringExtra("EXECUTION_LOCATION")
        m_archiveLocation = intent.getStringExtra("ARCHIVE_LOCATION")

        val compilationStepsView = findViewById<ExpandableListView>(R.id.compilationStepsView)
        val progressBarCompilation = findViewById<ProgressBar>(R.id.progressBarCompilation)
        progressBarCompilation.visibility = View.VISIBLE
        val compilationEndState = findViewById<TextView>(R.id.compilationEndState)
        val backToMainScreen = findViewById<TextView>(R.id.backToMainScreen)
        compilationEndState.visibility = View.GONE
        backToMainScreen.visibility = View.GONE

        backToMainScreen.setOnClickListener {
            finish()
        }

        val checkEngineLogs = CompileStepLogs(CompileStepData("Check engine", CompileStepStatus.IN_PROGRESS), StringBuilder());
        val compilationLogs = CompileStepLogs(CompileStepData("Compilation", CompileStepStatus.READY), StringBuilder());

        val compilationStepsDetails = mapOf(Pair(checkEngineLogs.step, listOf(checkEngineLogs.logs)), Pair(compilationLogs.step, listOf(compilationLogs.logs)))
        val compilationStepsTitles = ArrayList<CompileStepData>(compilationStepsDetails.keys)
        val expandableListAdapter = CompileStepListAdapter(this, compilationStepsTitles, compilationStepsDetails)
        compilationStepsView.setAdapter(expandableListAdapter)
        compilationStepsView.expandGroup(0, true)
        val rubyInterpreter = object : RubyInterpreter(assets, applicationInfo.dataDir, buildPsdkProcessData()) {
            override fun accept(lineMessage: String) {
                currentLogs?.logs?.appendLine(lineMessage)
                runOnUiThread {
                    expandableListAdapter.notifyDataSetChanged()
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
            currentLogs?.step?.status = if (returnCode == 0) CompileStepStatus.SUCCESS else CompileStepStatus.ERROR
            currentLogs = null
            runOnUiThread {
                progressBarCompilation.visibility = View.INVISIBLE
                compilationEndState.visibility = View.VISIBLE
                backToMainScreen.visibility = View.VISIBLE
                compilationEndState.text = resultText
                compilationEndState.setTextColor(if (returnCode == 0) Color.GREEN else Color.RED)
            }
            Thread.sleep(2000)
            setResult(returnCode)
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                runOnUiThread {
                    progressBarCompilation.visibility = View.INVISIBLE
                    compilationEndState.text = "Check engine failure"
                }
                Thread.sleep(2000)
                setResult(returnCode)
            } else {
                currentLogs?.step?.status = CompileStepStatus.SUCCESS
                currentLogs = compilationLogs
                currentLogs?.step?.status = CompileStepStatus.IN_PROGRESS
                runOnUiThread {
                    compilationStepsView.collapseGroup(0)
                    compilationStepsView.expandGroup(1, true)
                }
                rubyInterpreter.enqueue(RubyScript(assets, SCRIPT), onCompleteCompilation)
            }
        }
        try {
            currentLogs = checkEngineLogs
            rubyInterpreter.enqueue(RubyScript(assets, CHECK_ENGINE_SCRIPT), onCompleteCheck);
        } catch (ex: Exception) {
            Toast.makeText(applicationContext, ex.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildPsdkProcessData(): ScriptCurrentLocation {
        return ScriptCurrentLocation(m_internalWriteablePath, m_executionLocation, applicationInfo.nativeLibraryDir, m_archiveLocation)
    }

    companion object {
        private const val SCRIPT = "compile.rb"
        private const val CHECK_ENGINE_SCRIPT = "check_engine.rb"
    }
}