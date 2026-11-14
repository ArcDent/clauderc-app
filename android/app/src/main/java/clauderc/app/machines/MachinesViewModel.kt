package clauderc.app.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.net.RelayLink
import clauderc.app.protocol.Machine
import clauderc.app.protocol.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MachineRow(val machine: Machine, val sessions: Int?, val pending: Int)

data class MachinesUiState(
    /** Every paired relay with its connection state; empty before the first pairing. */
    val relays: List<RelayLink> = emptyList(),
    /** Null while the first machines.list is outstanding. */
    val rows: List<MachineRow>? = null,
)

class MachinesViewModel(fleet: Fleet, relays: Flow<List<RelayLink>>) : ViewModel() {
    val state: StateFlow<MachinesUiState> = combine(fleet.machines, fleet.sessions, relays) { machines, sessions, links ->
        MachinesUiState(
            relays = links,
            rows = machines
                ?.sortedWith(compareByDescending<Machine> { it.online }.thenBy { it.name })
                ?.map { m ->
                    val list = sessions[m.id]?.takeIf { m.online }
                    MachineRow(m, list?.sessions?.size, list?.sessions?.count { it.state == SessionState.Pending } ?: 0)
                },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MachinesUiState())
}
