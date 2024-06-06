package com.psdk.ruby.vm

import android.content.res.AssetManager
import java.lang.Exception

typealias CompletionTask = (Int) -> Unit;

abstract class RubyInterpreter(private val assets: AssetManager, private val applicationPath: String, private val location: RubyScript.ScriptCurrentLocation):
    LogListener {

    fun enqueue(script: RubyScript, onComplete: CompletionTask) {
        if (vm == null) {
            vm = buildMainScript(applicationPath, assets, location, this)
        } else {
            vm!!.listener = this
        }
        vm!!.enqueue(script, onComplete)
    }

    private fun buildMainScript(applicationPath: String, assets: AssetManager, location: RubyScript.ScriptCurrentLocation, listener: LogListener): ListenerRubyVM {
        val script = RubyScript(assets, FIFO_INTERPRETER_SCRIPT)
        val launcher = ListenerRubyVM(applicationPath, script, listener)
        launcher.startVM(location)
        return launcher
    }
    companion object {
        private const val FIFO_INTERPRETER_SCRIPT = "fifo_interpreter.rb"
        private var vm: ListenerRubyVM? = null
    }

    class ListenerRubyVM(applicationPath: String, main: RubyScript, var listener: LogListener): RubyVM(applicationPath, main) {
        override fun accept(lineMessage: String) {
            listener.accept(lineMessage)
        }

        override fun onLogError(e: Exception) {
            listener.onLogError(e)
        }

    }
}