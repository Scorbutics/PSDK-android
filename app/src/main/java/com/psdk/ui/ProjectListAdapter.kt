package com.psdk.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.psdk.R
import com.psdk.db.entities.Project
import java.io.File

class ProjectListAdapter(
    private val onProjectClick: (Project) -> Unit,
    private val onDeleteClick: (Project) -> Unit
) : ListAdapter<Project, ProjectListAdapter.ViewHolder>(ProjectDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_project_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val projectName: TextView = itemView.findViewById(R.id.projectName)
        private val projectStatus: TextView = itemView.findViewById(R.id.projectStatus)
        private val syncWarningIcon: ImageView = itemView.findViewById(R.id.syncWarningIcon)
        private val deleteButton: ImageView = itemView.findViewById(R.id.deleteButton)

        fun bind(project: Project) {
            projectName.text = project.name

            val isReadable = File(project.directory).canRead()
            val hasRelease = File(project.directory, "Release").exists()

            projectStatus.text = when {
                !isReadable -> "Not found on filesystem"
                hasRelease -> "Ready to play"
                else -> "Needs compilation"
            }

            projectStatus.setTextColor(
                itemView.context.getColor(
                    when {
                        !isReadable -> R.color.colorError
                        hasRelease -> R.color.colorSuccess
                        else -> R.color.colorOnSurfaceVariant
                    }
                )
            )

            syncWarningIcon.visibility = if (isReadable) View.GONE else View.VISIBLE

            itemView.setOnClickListener {
                if (isReadable) onProjectClick(project)
            }
            itemView.isEnabled = isReadable
            itemView.alpha = if (isReadable) 1.0f else 0.6f

            deleteButton.setOnClickListener { onDeleteClick(project) }
        }
    }

    class ProjectDiffCallback : DiffUtil.ItemCallback<Project>() {
        override fun areItemsTheSame(oldItem: Project, newItem: Project): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Project, newItem: Project): Boolean = oldItem == newItem
    }
}
