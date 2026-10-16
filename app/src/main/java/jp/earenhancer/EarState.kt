package jp.earenhancer

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase { STOPPED, LOADING, LISTENING, ERROR }

data class EarStatus(
    val phase: Phase = Phase.STOPPED,
    val message: String = "待機中",
    val input: String = "未接続",
    val output: String = "未接続",
    val frames: Long = 0,
    val loadRatio: Float = 0f,
    val inputDbfs: Int = -90
)

object EarState {
    val status = MutableStateFlow(EarStatus())
}

object EarActions {
    const val START = "jp.earenhancer.START"
    const val STOP = "jp.earenhancer.STOP"
    const val UPDATE = "jp.earenhancer.UPDATE"
    const val INPUT = "input"
    const val ATTENUATION = "attenuation"
    const val VOLUME = "volume"
}
