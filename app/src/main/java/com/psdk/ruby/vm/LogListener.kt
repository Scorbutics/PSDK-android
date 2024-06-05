package com.psdk.ruby.vm

import java.lang.Exception

interface LogListener {
    fun accept(lineMessage: String)
    fun onLogError(e: Exception)
}
