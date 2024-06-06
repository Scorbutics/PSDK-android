package com.psdk

enum class CompileStepStatus {
    READY,
    IN_PROGRESS,
    SUCCESS,
    ERROR
}

data class CompileStepLogs(
    val step: CompileStepData,
    val logs: StringBuilder
)

data class CompileStepData(
    val title: String,
    var status: CompileStepStatus
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompileStepData

        return title == other.title
    }

    override fun hashCode(): Int {
        return title.hashCode()
    }
}
