package com.psdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.psdk.R
import java.io.File
import java.nio.charset.StandardCharsets

class LogBottomSheetFragment : BottomSheetDialogFragment() {

    private var outputLogPath: String? = null
    private var errorLogPath: String? = null
    private var directContent: String? = null
    private var title: String = "Logs"

    private var outputContent: String = ""
    private var errorContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            outputLogPath = it.getString(ARG_OUTPUT_LOG_PATH)
            errorLogPath = it.getString(ARG_ERROR_LOG_PATH)
            directContent = it.getString(ARG_DIRECT_CONTENT)
            title = it.getString(ARG_TITLE, "Logs")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_log_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)
        val logContent = view.findViewById<TextView>(R.id.logContent)

        toolbar.title = title
        toolbar.setNavigationOnClickListener { dismiss() }

        // Load content
        if (directContent != null) {
            outputContent = directContent!!
            errorContent = ""
            tabLayout.visibility = View.GONE
        } else {
            outputContent = readLogFile(outputLogPath)
            errorContent = readLogFile(errorLogPath)
        }

        logContent.text = outputContent.ifEmpty { "No log available" }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val content = when (tab?.position) {
                    0 -> outputContent
                    1 -> errorContent
                    else -> ""
                }
                logContent.text = content.ifEmpty { "No log available" }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    override fun onStart() {
        super.onStart()
        val behavior = BottomSheetBehavior.from(requireView().parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.peekHeight = resources.getDimensionPixelSize(R.dimen.bottom_sheet_peek_height)
    }

    private fun readLogFile(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        return try {
            val file = File(path)
            if (file.exists()) file.readText(StandardCharsets.UTF_8) else ""
        } catch (e: Exception) {
            "Error reading log: ${e.localizedMessage}"
        }
    }

    companion object {
        private const val ARG_OUTPUT_LOG_PATH = "output_log_path"
        private const val ARG_ERROR_LOG_PATH = "error_log_path"
        private const val ARG_DIRECT_CONTENT = "direct_content"
        private const val ARG_TITLE = "title"

        fun newInstance(
            outputLogPath: String,
            errorLogPath: String?,
            title: String = "Logs"
        ): LogBottomSheetFragment {
            return LogBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_OUTPUT_LOG_PATH, outputLogPath)
                    putString(ARG_ERROR_LOG_PATH, errorLogPath)
                    putString(ARG_TITLE, title)
                }
            }
        }

        fun newInstanceWithContent(
            content: String,
            title: String = "Logs"
        ): LogBottomSheetFragment {
            return LogBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DIRECT_CONTENT, content)
                    putString(ARG_TITLE, title)
                }
            }
        }
    }
}
