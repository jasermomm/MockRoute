package com.jasermomm.mockroute.service

import com.jasermomm.mockroute.core.SimulationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SimulationBus {
    private val mutable = MutableStateFlow(SimulationSnapshot())
    val state: StateFlow<SimulationSnapshot> = mutable.asStateFlow()

    fun publish(snapshot: SimulationSnapshot) {
        mutable.value = snapshot
    }
}
