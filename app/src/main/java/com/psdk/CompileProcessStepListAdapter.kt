package com.psdk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.TextView


class CompileProcessStepListAdapter(
    private val context: Context, private val expandableListTitle: List<CompileStepData>,
    private val expandableListDetail: Map<CompileStepData, List<StringBuilder>>
) : BaseExpandableListAdapter() {
    override fun getChild(listPosition: Int, expandedListPosition: Int): StringBuilder {
        return expandableListDetail[expandableListTitle[listPosition]]!!.get(expandedListPosition)
    }

    override fun getChildId(listPosition: Int, expandedListPosition: Int): Long {
        return expandedListPosition.toLong()
    }

    override fun getChildView(
        listPosition: Int, expandedListPosition: Int,
        isLastChild: Boolean, convertView: View?, parent: ViewGroup
    ): View {
        var convertViewV = convertView
        val expandedListText = getChild(listPosition, expandedListPosition)
        if (convertViewV == null) {
            val layoutInflater = context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertViewV = layoutInflater.inflate(R.layout.compilation_item_step, null)
        }
        val expandedListTextView = convertViewV!!.findViewById<TextView>(R.id.compilation_item_step)
        expandedListTextView.text = expandedListText

        convertViewV.isSelected = true

        return convertViewV
    }

    override fun getChildrenCount(listPosition: Int): Int {
        return expandableListDetail[expandableListTitle[listPosition]]!!.size
    }

    override fun getGroup(listPosition: Int): CompileStepData {
        return expandableListTitle[listPosition]
    }

    override fun getGroupCount(): Int {
        return expandableListTitle.size
    }

    override fun getGroupId(listPosition: Int): Long {
        return listPosition.toLong()
    }

    override fun getGroupView(
        listPosition: Int, isExpanded: Boolean,
        convertView: View?, parent: ViewGroup
    ): View {
        var convertViewV = convertView
        val groupData = getGroup(listPosition)
        if (convertViewV == null) {
            val layoutInflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertViewV = layoutInflater.inflate(R.layout.compilation_group_step, null)
        }
        val listTitleTextView = convertViewV!!
            .findViewById<View>(R.id.compilation_group_step_title) as TextView
        listTitleTextView.setTypeface(null, Typeface.BOLD)
        listTitleTextView.text = groupData.title
        val color = when(groupData.status) {
            CompileStepStatus.SUCCESS -> Color.GREEN
            CompileStepStatus.READY -> Color.GRAY
            CompileStepStatus.IN_PROGRESS -> Color.BLUE
            CompileStepStatus.ERROR -> Color.RED
        }
        listTitleTextView.setTextColor(color)

        return convertViewV
    }

    override fun hasStableIds(): Boolean {
        return false
    }

    override fun isChildSelectable(listPosition: Int, expandedListPosition: Int): Boolean {
        return true
    }
}
