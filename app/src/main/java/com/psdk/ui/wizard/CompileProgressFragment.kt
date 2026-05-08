package com.psdk.ui.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.psdk.CompileStepStatus
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.ui.CompileWizardViewModel
import java.io.File

class CompileProgressFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by activityViewModels()
    private lateinit var adapter: CompileStepAdapter
    private var engine: CompilationEngine? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_compile_progress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.stepsRecyclerView)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)

        adapter = CompileStepAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val executionLocation = viewModel.executionLocation.value ?: return
        val archive = viewModel.archive.value ?: return

        engine = CompilationEngine(
            context = requireContext(),
            executionLocation = executionLocation,
            archive = archive,
            callback = object : CompilationEngine.CompilationCallback {
                override fun onStepStarted(stepIndex: Int, stepName: String) {
                    activity?.runOnUiThread {
                        adapter.updateStepStatus(stepIndex, CompileStepStatus.IN_PROGRESS)
                        adapter.expandStep(stepIndex)
                    }
                }

                override fun onStepCompleted(stepIndex: Int, success: Boolean) {
                    activity?.runOnUiThread {
                        adapter.updateStepStatus(
                            stepIndex,
                            if (success) CompileStepStatus.SUCCESS else CompileStepStatus.ERROR
                        )
                    }
                }

                override fun onLogMessage(stepIndex: Int, message: String) {
                    activity?.runOnUiThread {
                        adapter.appendLog(stepIndex, message)
                    }
                }

                override fun onCompilationFinished(success: Boolean, logFile: File) {
                    activity?.runOnUiThread {
                        progressBar.hide()
                        viewModel.currentStep.value = 2
                    }
                }
            }
        )

        adapter.setSteps(listOf("Check engine", "Backing up previous Release", "Compilation", "Copying saves"))
        engine?.start()
    }
}

// Inner adapter for compilation steps
class CompileStepAdapter : RecyclerView.Adapter<CompileStepAdapter.StepViewHolder>() {

    data class StepState(
        val title: String,
        var status: CompileStepStatus = CompileStepStatus.READY,
        val logs: StringBuilder = StringBuilder(),
        var expanded: Boolean = false
    )

    private val steps = mutableListOf<StepState>()

    fun setSteps(titles: List<String>) {
        steps.clear()
        steps.addAll(titles.map { StepState(it) })
        notifyDataSetChanged()
    }

    fun updateStepStatus(index: Int, status: CompileStepStatus) {
        if (index < steps.size) {
            steps[index].status = status
            notifyItemChanged(index)
        }
    }

    fun appendLog(index: Int, message: String) {
        if (index < steps.size) {
            steps[index].logs.appendLine(message)
            notifyItemChanged(index)
        }
    }

    fun expandStep(index: Int) {
        steps.forEachIndexed { i, step ->
            step.expanded = (i == index)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_compile_step, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        holder.bind(steps[position])
    }

    override fun getItemCount() = steps.size

    inner class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusIcon: ImageView = itemView.findViewById(R.id.stepStatusIcon)
        private val title: TextView = itemView.findViewById(R.id.stepTitle)
        private val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
        private val logContainer: NestedScrollView = itemView.findViewById(R.id.logContainer)
        private val logText: TextView = itemView.findViewById(R.id.logText)
        private val header: View = itemView.findViewById(R.id.stepHeader)

        fun bind(step: StepState) {
            title.text = step.title

            // Status icon
            val context = itemView.context
            when (step.status) {
                CompileStepStatus.READY -> {
                    statusIcon.setImageResource(R.drawable.bg_step_pending)
                    title.setTextColor(context.getColor(R.color.colorOnSurfaceVariant))
                }
                CompileStepStatus.IN_PROGRESS -> {
                    statusIcon.setImageResource(R.drawable.bg_step_active)
                    title.setTextColor(context.getColor(R.color.colorPrimary))
                }
                CompileStepStatus.SUCCESS -> {
                    statusIcon.setImageDrawable(null)
                    statusIcon.setBackgroundResource(R.drawable.bg_step_completed)
                    statusIcon.setImageResource(R.drawable.ic_check)
                    statusIcon.setColorFilter(context.getColor(R.color.white))
                    title.setTextColor(context.getColor(R.color.colorSuccess))
                }
                CompileStepStatus.ERROR -> {
                    statusIcon.setImageResource(R.drawable.ic_error)
                    statusIcon.setColorFilter(context.getColor(R.color.colorError))
                    title.setTextColor(context.getColor(R.color.colorError))
                }
            }

            // Logs
            val hasLogs = step.logs.isNotEmpty()
            expandIcon.visibility = if (hasLogs) View.VISIBLE else View.GONE
            logContainer.visibility = if (step.expanded && hasLogs) View.VISIBLE else View.GONE
            logText.text = step.logs.toString()

            header.setOnClickListener {
                if (hasLogs) {
                    step.expanded = !step.expanded
                    notifyItemChanged(adapterPosition)
                }
            }
        }
    }
}
