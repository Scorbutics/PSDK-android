package com.psdk.ui

import android.content.Context
import android.util.Log
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.litergss.HostMessageSender
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage

/**
 * The messages to the scripts of this process's Ruby VM: the splash's
 * [LoaderMessage]s (LoaderHost.sendLoaderMessage) in the shell process, the
 * game's in the `:game` process. Through a PsdkInterpreter of their own (see
 * [HostMessageSender]).
 */
internal object HostMessages {
    private const val TAG = "HostMessages"

    /** The application's, from the first message: the interpreter is made after it. */
    private lateinit var appContext: Context

    private val sender = HostMessageSender {
        val interpreter = PsdkInterpreter.create(appContext, object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                // A post's own errors only: this interpreter can also get
                // the process's native output (interpreter id 0), which
                // the scripts' interpreter does not log either.
                if (logMessage.isError() && logMessage.interpreterId != 0) Log.e(TAG, logMessage.message)
            }
        })
        HostMessageSender.Poster(interpreter::post)
    }

    fun send(context: Context, topic: String, payload: String = "") {
        appContext = context.applicationContext
        sender.send(topic, payload)
    }

    fun send(context: Context, message: LoaderMessage) = send(context, message.topic, message.payload)

    /**
     * A new splash state: what the splash was shown is not shown again.
     * Asynchronous like the rest: a splash reading before it lands (not
     * before show! has loaded its assets) briefly shows the old state.
     */
    fun clearLoader() = sender.sendIfStarted("loader.clear")
}
