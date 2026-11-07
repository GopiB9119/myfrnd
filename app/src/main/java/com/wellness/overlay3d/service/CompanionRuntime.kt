package com.wellness.overlay3d.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompanionStatus(
    val running: Boolean = false,
    val screenActive: Boolean = false,
    val nextWater: Long? = null,
    val nextEye: Long? = null,
    val error: Int? = null
)

object CompanionRuntime {
    private val mutableStatus = MutableStateFlow(CompanionStatus())
    val status = mutableStatus.asStateFlow()

    fun publish(status: CompanionStatus) {
        mutableStatus.value = status
    }
}
