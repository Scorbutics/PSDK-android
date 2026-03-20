package com.psdk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ExpandableListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.File


class CompileProcessActivity : ComponentActivity() {
    private var m_executionLocation: String? = null
    private var m_archiveLocation: String? = null
    private var m_currentLogs: CompileStepLogs? = null
    private val m_allStepLogs = mutableListOf<CompileStepLogs>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compiler_process)
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

        val checkEngineLogs = CompileStepLogs(CompileStepData("Check engine", CompileStepStatus.IN_PROGRESS), StringBuilder())
        val compilationLogs = CompileStepLogs(CompileStepData("Compilation", CompileStepStatus.READY), StringBuilder())
        val copySavesLogs = CompileStepLogs(CompileStepData("Copying saves", CompileStepStatus.READY), StringBuilder())

        m_allStepLogs.addAll(listOf(checkEngineLogs, compilationLogs, copySavesLogs))
        m_currentLogs = checkEngineLogs

        val compilationStepsDetails = mapOf(
            Pair(checkEngineLogs.step, listOf(checkEngineLogs.logs)),
            Pair(compilationLogs.step, listOf(compilationLogs.logs)),
            Pair(copySavesLogs.step, listOf(copySavesLogs.logs))
        )
        val compilationStepsTitles = ArrayList<CompileStepData>(compilationStepsDetails.keys)
        val expandableListAdapter = CompileProcessStepListAdapter(this, compilationStepsTitles, compilationStepsDetails)
        compilationStepsView.setAdapter(expandableListAdapter)
        compilationStepsView.expandGroup(0, true)

        val interpreter = PsdkInterpreter.create(
            context = this,
            listener = object : LogListener {
                override fun onLogMessage(logMessage: LogMessage) {
                    m_currentLogs?.logs?.appendLine(logMessage.message)
                    runOnUiThread {
                        expandableListAdapter.notifyDataSetChanged()
                    }
                }
            },
            onError = { e ->
                runOnUiThread {
                    Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_LONG).show()
                }
            }
        )

        val location = ScriptLocation(m_executionLocation, m_archiveLocation)

        val onCompleteCompileProcess: CompletionTask = { returnCode: Int ->
            m_currentLogs?.step?.status = if (returnCode == 0) CompileStepStatus.SUCCESS else CompileStepStatus.ERROR
            m_currentLogs = null
            interpreter.close()
            setResult(returnCode)
            runOnUiThread {
                progressBarCompilation.visibility = View.INVISIBLE
                saveLogsToFile()
                if (returnCode == 0) {
                    compilationEndState.visibility = View.VISIBLE
                    compilationEndState.text = "Compilation success !"
                    compilationEndState.setTextColor(Color.GREEN)
                    showRestartDialog()
                } else {
                    compilationEndState.visibility = View.VISIBLE
                    backToMainScreen.visibility = View.VISIBLE
                    compilationEndState.text = "Compilation failure"
                    compilationEndState.setTextColor(Color.RED)
                }
            }
        }

        val onCompleteRubyCompilation: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                m_currentLogs?.step?.status = CompileStepStatus.ERROR
                saveLogsToFile()
                runOnUiThread {
                    progressBarCompilation.visibility = View.INVISIBLE
                    compilationEndState.visibility = View.VISIBLE
                    backToMainScreen.visibility = View.VISIBLE
                    compilationEndState.text = "Ruby compilation failure"
                    compilationEndState.setTextColor(Color.RED)
                }
                Thread.sleep(2000)
                m_currentLogs = null
                interpreter.close()
                setResult(returnCode)
            } else {
                m_currentLogs?.step?.status = CompileStepStatus.SUCCESS
                m_currentLogs = copySavesLogs
                m_currentLogs?.step?.status = CompileStepStatus.IN_PROGRESS
                runOnUiThread {
                    compilationStepsView.collapseGroup(1)
                    compilationStepsView.expandGroup(2, true)
                }
                interpreter.enqueue(RubyScript(assets, COPY_SAVES_SCRIPT), location, onCompleteCompileProcess)
            }
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                m_currentLogs?.step?.status = CompileStepStatus.ERROR
                saveLogsToFile()
                runOnUiThread {
                    progressBarCompilation.visibility = View.INVISIBLE
                    compilationEndState.visibility = View.VISIBLE
                    backToMainScreen.visibility = View.VISIBLE
                    compilationEndState.text = "Check engine failure"
                    compilationEndState.setTextColor(Color.RED)
                }
                Thread.sleep(2000)
                m_currentLogs = null
                interpreter.close()
                setResult(returnCode)
            } else {
                m_currentLogs?.step?.status = CompileStepStatus.SUCCESS
                m_currentLogs = compilationLogs
                m_currentLogs?.step?.status = CompileStepStatus.IN_PROGRESS
                runOnUiThread {
                    compilationStepsView.collapseGroup(0)
                    compilationStepsView.expandGroup(1, true)
                }
                interpreter.enqueue(RubyScript(assets, RUBY_COMPILE_SCRIPT), location, onCompleteRubyCompilation)
            }
        }

        try {
            interpreter.enqueue(RubyScript(assets, CHECK_ENGINE_SCRIPT), location, onCompleteCheck)
        } catch (ex: Exception) {
            Toast.makeText(applicationContext, ex.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLogsToFile() {
        val logFile = File(m_executionLocation, COMPILATION_LOG_FILE)
        logFile.bufferedWriter().use { writer ->
            for (stepLogs in m_allStepLogs) {
                writer.appendLine("=== ${stepLogs.step.title} [${stepLogs.step.status}] ===")
                writer.appendLine(stepLogs.logs.toString())
                writer.newLine()
            }
        }
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(this)
            .setTitle("Restart required")
            .setMessage("Compilation completed successfully. The app needs to restart to reset the internal engine. The app will now restart.")
            .setCancelable(false)
            .setPositiveButton("Restart now") { _, _ ->
                restartApp()
            }
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val COPY_SAVES_SCRIPT = "copy_saves.rb"
        private const val RUBY_COMPILE_SCRIPT = "compile.rb"
        private const val CHECK_ENGINE_SCRIPT = "check_engine.rb"
        const val COMPILATION_LOG_FILE = "last_compilation.log"
    }
}
