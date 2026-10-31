package com.psdk.ui

import android.content.Context
import android.util.Log
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.util.concurrent.Executors

/**
 * Sends the [LoaderMessage]s of [RootActivity] (LoaderHost.sendLoaderMessage).
 *
 * They go through an interpreter of their own (the loader's runs the
 * splash, a message posted there would wait for it to end), one after
 * the other on a background thread: callers never wait, and the splash
 * gets them in the order they were sent. Best-effort: a failure is
 * logged, the splash keeps its state.
 */
internal object LoaderMessages {
    private const val TAG = "LoaderMessages"

    private val sender = Executors.newSingleThreadExecutor { task ->
        Thread(task, "loader-messages").apply { isDaemon = true }
    }

    /** Created on [sender] at the first message, kept for the process. */
    private var interpreter: PsdkInterpreter? = null

    fun send(context: Context, message: LoaderMessage) {
        val appContext = context.applicationContext
        sender.execute { post(appContext, message.topic, message.payload) }
    }

    /**
     * A new splash state: what the splash was shown is not shown again.
     * Asynchronous like the rest: a splash reading before it lands (not
     * before show! has loaded its assets) briefly shows the old state.
     */
    fun clear(context: Context) {
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
                // the splash's interpreter does not log either.
                if (logMessage.isError() && logMessage.interpreterId != 0) Log.e(TAG, logMessage.message)
            }
        }).also { interpreter = it }
}
