package fr.acinq.phoenix.utils

import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.utils.Connection
import fr.acinq.phoenix.app.AppConfigurationManager
import fr.acinq.phoenix.app.PeerManager
import fr.acinq.tor.Tor
import fr.acinq.tor.TorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

data class Connections(
    val internet: Connection = Connection.CLOSED,
    val tor: Connection? = null,
    val peer: Connection = Connection.CLOSED,
    val electrum: Connection = Connection.CLOSED
) {
    val global : Connection
        get() = internet + tor + peer + electrum
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsMonitor(
    peerManager: PeerManager,
    electrumClient: ElectrumClient,
    networkMonitor: NetworkMonitor,
    configurationManager: AppConfigurationManager,
    tor: Tor,
): CoroutineScope {

    private val job = Job()
    override val coroutineContext = MainScope().coroutineContext + job

    private val _connections = MutableStateFlow(Connections())
    public val connections: StateFlow<Connections> get() = _connections

    private val torState: Flow<TorState?> = combine(configurationManager.isTorEnabled, tor.state) { torEnabled, torState ->
        when {
            torEnabled -> torState
            else -> null
        }
    }

    init {
        launch {
            combine(
                peerManager.getPeer().connectionState,
                electrumClient.connectionState,
                networkMonitor.networkState,
                torState,
            ) { peerState, electrumState, internetState, torState ->
                Connections(
                    peer = peerState,
                    electrum = electrumState,
                    internet = when (internetState) {
                        NetworkState.Available -> Connection.ESTABLISHED
                        NetworkState.NotAvailable -> Connection.CLOSED
                    },
                    tor = when(torState) {
                        TorState.STARTING -> Connection.ESTABLISHING
                        TorState.RUNNING -> Connection.ESTABLISHED
                        TorState.STOPPED -> Connection.CLOSED
                        null -> null
                    }
                )
            }.collect {
                _connections.value = it
            }
        }
    }
}