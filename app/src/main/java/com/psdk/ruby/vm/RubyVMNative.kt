package com.psdk.ruby.vm

class RubyVMNative {
    companion object {
        external fun exec(scriptContent: String, fifoLogs: String, fifoCommands: String, fifoReturn: String, rubyBaseDirectory: String?, executionLocation: String?, nativeLibsDirLocation: String?, additionalParam: String?): Int
        external fun updateVmLocation(executionLocation: String, archiveLocation: String): Int
    }
}