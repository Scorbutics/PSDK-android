package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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
    private var currentTabIndex: Int = 0

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
        toolbar.inflateMenu(R.menu.log_bottom_sheet_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> {
                    shareCurrentTab()
                    true
                }
                else -> false
            }
        }

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
                currentTabIndex = tab?.position ?: 0
                val content = when (currentTabIndex) {
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

    private fun shareCurrentTab() {
        val tabLabel = if (currentTabIndex == 1) "Errors" else "Output"
        val filePath = if (currentTabIndex == 1) errorLogPath else outputLogPath
        val content = if (currentTabIndex == 1) errorContent else outputContent

        if (content.isBlank()) {
            Toast.makeText(requireContext(), "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val subject = "$title — $tabLabel"
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Prefer attaching the file (better target support: email, drive, gist).
        // Fall back to plain text for direct-content mode or missing files.
        val file = filePath?.let { File(it) }
        if (file != null && file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().packageName + ".provider",
                    file
                )
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_STREAM, uri)
            } catch (e: Exception) {
                // FileProvider couldn't expose the file (path not covered by file_paths.xml,
                // permission issue, etc.) — degrade to inline text rather than crashing.
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, content)
            }
        } else {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, content)
        }

        startActivity(Intent.createChooser(intent, "Share $tabLabel"))
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
