package com.psdk.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.psdk.R
import com.psdk.db.entities.Project
import java.io.File
import java.util.UUID

class ProjectListAdapter(
    private val onProjectClick: (Project) -> Unit,
) : ListAdapter<Project, ProjectListAdapter.ViewHolder>(ProjectDiffCallback()) {

    /**
     * Tracks projects that have already played their entry animation so
     * scroll-back / DiffUtil rebinds don't re-animate. Cleared only when
     * the adapter is recreated; that's by design — entry is a one-time
     * "deal me in" beat.
     */
    private val animatedIn: MutableSet<UUID> = mutableSetOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_project_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position, animatedIn)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.startIdleBob()
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.stopIdleBob()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.projectCardRoot)
        private val projectIcon: ImageView = itemView.findViewById(R.id.projectIcon)
        private val projectName: TextView = itemView.findViewById(R.id.projectName)
        private val projectStatus: TextView = itemView.findViewById(R.id.projectStatus)
        private val syncWarningIcon: ImageView = itemView.findViewById(R.id.syncWarningIcon)

        private var idleBob: ObjectAnimator? = null

        fun bind(project: Project, position: Int, animatedIn: MutableSet<UUID>) {
            projectName.text = project.name

            val isReadable = File(project.directory).canRead()
            val hasRelease = File(project.directory, "Release").exists()

            // Status chip: text + colour ramp per state. The chip drawable
            // is a colour-neutral pill (bg_status_chip.xml) — the tint
            // here paints it the right family.
            val ctx = itemView.context
            val (label, fg) = when {
                !isReadable  -> "Not found" to R.color.colorError
                hasRelease   -> "Ready to play" to R.color.colorSuccess
                else         -> "Needs compilation" to R.color.colorInProgress
            }
            projectStatus.text = label
            val fgColor = ContextCompat.getColor(ctx, fg)
            projectStatus.setTextColor(fgColor)
            // ~18% alpha tint on the pill so the GL background still reads
            // through faintly — keeps the row glassy instead of stamped.
            val bgColor = ColorUtils.setAlphaComponent(fgColor, 46)
            projectStatus.backgroundTintList = ColorStateList.valueOf(bgColor)

            // Per-project logo with bundled fallback. The wizard writes
            // loader_assets/game.png on extract; freshly-created projects
            // get the bundled gear until they compile once.
            val logoFile = File(project.directory, "loader_assets/game.png")
            val logo = if (isReadable && logoFile.exists()) {
                BitmapFactory.decodeFile(logoFile.absolutePath)
            } else null
            if (logo != null) {
                projectIcon.setImageBitmap(logo)
            } else {
                projectIcon.setImageResource(R.drawable.logo)
            }

            syncWarningIcon.visibility = if (isReadable) View.GONE else View.VISIBLE

            cardRoot.setOnClickListener {
                if (isReadable) onProjectClick(project)
            }
            cardRoot.isEnabled = isReadable
            cardRoot.alpha = if (isReadable) 1.0f else 0.6f

            // Press response: dip + brighten stroke on down, springback on
            // up. Returning false from the touch listener keeps the click
            // listener wired — touch events are ambient, click is the
            // commit.
            attachPressFeedback()

            // First-time entry: fade + lift in, staggered per row so the
            // list "deals" rather than pops. DiffUtil rebinds the same
            // project to (potentially) a different holder, so we key on
            // project.id, not position.
            if (animatedIn.add(project.id)) {
                playEntryAnimation(position)
            } else {
                cardRoot.alpha = if (isReadable) 1.0f else 0.6f
                cardRoot.translationY = 0f
            }
        }

        private fun attachPressFeedback() {
            cardRoot.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.98f).scaleY(0.98f)
                            .setDuration(90L)
                            .start()
                        cardRoot.setStrokeColor(
                            ColorUtils.setAlphaComponent(
                                ContextCompat.getColor(v.context, R.color.colorSplashAccent),
                                153, // 60% alpha
                            )
                        )
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f).scaleY(1f)
                            .setInterpolator(OvershootInterpolator(2.0f))
                            .setDuration(280L)
                            .start()
                        cardRoot.setStrokeColor(
                            ContextCompat.getColor(v.context, R.color.colorRowGlassStroke)
                        )
                    }
                }
                false
            }
        }

        private fun playEntryAnimation(position: Int) {
            cardRoot.alpha = 0f
            cardRoot.translationY = 8f * cardRoot.resources.displayMetrics.density
            cardRoot.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(position * ENTRY_STAGGER_MS)
                .setDuration(ENTRY_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        /**
         * Idle vertical bob, ±1.5 dp on a 4-second sine. Phase is offset
         * per adapter position so neighbouring rows aren't lock-step.
         * Cancelled on detach so a recycled holder doesn't drift.
         */
        fun startIdleBob() {
            @Suppress("DEPRECATION")
            val pos = adapterPosition.coerceAtLeast(0)
            val density = cardRoot.resources.displayMetrics.density
            val amp = 1.5f * density
            // Stagger the phase by a chunk of the cycle so row 0/1/2 don't
            // visibly bob in unison.
            val startDelay = (pos * (BOB_PERIOD_MS / 4)).toLong()
            idleBob?.cancel()
            idleBob = ObjectAnimator.ofPropertyValuesHolder(
                cardRoot,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -amp, 0f, amp, 0f),
            ).apply {
                duration = BOB_PERIOD_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                this.startDelay = startDelay
                start()
            }
        }

        fun stopIdleBob() {
            idleBob?.cancel()
            idleBob = null
            cardRoot.translationY = 0f
        }
    }

    class ProjectDiffCallback : DiffUtil.ItemCallback<Project>() {
        override fun areItemsTheSame(oldItem: Project, newItem: Project): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Project, newItem: Project): Boolean = oldItem == newItem
    }

    companion object {
        private const val ENTRY_STAGGER_MS = 60L
        private const val ENTRY_DURATION_MS = 320L
        private const val BOB_PERIOD_MS = 4000L
    }
}
