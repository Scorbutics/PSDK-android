package com.psdk.ui

import android.content.Context
import android.util.Log
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.util.concurrent.Executors

/**
 * Posts messages to the scripts of this process's Ruby VM (VMHost.deliver,
 * read with VMHost.each_message): the splash's [LoaderMessage]s
 * (LoaderHost.sendLoaderMessage) in the shell process, the game's in the
 * `:game` process.
 *
 * They go through an interpreter of their own (the one running the splash or
 * the game is busy until it ends: a message posted there would wait), one
 * after the other on a background thread: callers never wait, and the
 * scripts get them in the order they were sent. Best-effort: a failure is
 * logged, the scripts keep their state.
 */
internal object HostMessages {
    private const val TAG = "HostMessages"

    private val sender = Executors.newSingleThreadExecutor { task ->
        Thread(task, "host-messages").apply { isDaemon = true }
    }

    /** Created on [sender] at the first message, kept for the process. */
    private var interpreter: PsdkInterpreter? = null

    fun send(context: Context, topic: String, payload: String = "") {
        val appContext = context.applicationContext
        sender.execute { post(appContext, topic, payload) }
    }

    fun send(context: Context, message: LoaderMessage) = send(context, message.topic, message.payload)

    /**
     * A new splash state: what the splash was shown is not shown again.
     * Asynchronous like the rest: a splash reading before it lands (not
     * before show! has loaded its assets) briefly shows the old state.
     */
    fun clearLoader(context: Context) {
        val appContext = context.applicationContext
        sender.execute {
            // No interpreter yet: nothing was ever sent, nothing to clear
            if (interpreter != null) post(appContext, "loader.clear")
        }
    }

    private fun post(context: Context, topic: String, payload: String = "") {
        try {
            val rc = interpreter(context).post(topic, payload)
            if (rc != 0) Log.w(TAG, "$topic not delivered (exit code $rc)")
        } catch (e: Exception) {
            Log.w(TAG, "$topic not delivered", e)
        }
    }

    private fun interpreter(context: Context): PsdkInterpreter =
        interpreter ?: PsdkInterpreter.create(context, object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                // A post's own errors only: this interpreter can also get
                // the process's native output (interpreter id 0), which
                // the scripts' interpreter does not log either.
                if (logMessage.isError() && logMessage.interpreterId != 0) Log.e(TAG, logMessage.message)
            }
        }).also { interpreter = it }
}
