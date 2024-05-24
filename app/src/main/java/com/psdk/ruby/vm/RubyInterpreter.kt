package com.psdk.ruby.vm

import android.content.res.AssetManager
import java.lang.Exception

typealias CompletionTask = (Int) -> Unit;

abstract class RubyInterpreter(private val assets: AssetManager, private val applicationPath: String, private val location: RubyScript.ScriptCurrentLocation):
    LogListener {

    fun runAsync(script: RubyScript, onComplete: CompletionTask) {
        if (vm == null) {
            vm = buildMainScript(applicationPath, assets, location)
        }
        vm!!.runAsync(script, onComplete)
    }

    private fun buildMainScript(applicationPath: String, assets: AssetManager, location: RubyScript.ScriptCurrentLocation): RubyVM {
        val script = RubyScript(assets, FIFO_INTERPRETER_SCRIPT)
        val self = this
        val launcher = object : RubyVM(applicationPath, script) {
            override fun accept(lineMessage: String?) {
                self.accept(lineMessage)
            }

            override fun onLogError(e: Exception) {
                self.onLogError(e)
            }
        }
        launcher.startVM(location)
        return launcher
    }
    companion object {
        private const val FIFO_INTERPRETER_SCRIPT = "fifo_interpreter.rb"
        private var vm: RubyVM? = null
    }

}