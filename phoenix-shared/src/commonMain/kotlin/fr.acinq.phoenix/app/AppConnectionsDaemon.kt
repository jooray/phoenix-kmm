package fr.acinq.phoenix.app

import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.io.TcpSocket
import fr.acinq.eclair.utils.Connection
import fr.acinq.phoenix.data.ElectrumServer
import fr.acinq.phoenix.data.address
import fr.acinq.phoenix.data.asServerAddress
import fr.acinq.phoenix.utils.*
import fr.acinq.tor.Tor
import fr.acinq.tor.TorState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.time.ExperimentalTime


@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AppConnectionsDaemon(
    private val configurationManager: AppConfigurationManager,
    private val walletManager: WalletManager,
    private val peerManager: PeerManager,
    private val currencyManager: CurrencyManager,
    private val monitor: NetworkMonitor,
    private val tcpSocketBuilder: TcpSocket.Builder,
    private val tor: Tor,
    private val electrumClient: ElectrumClient,
    loggerFactory: LoggerFactory,
) : CoroutineScope by MainScope() {

    private val logger = newLogger(loggerFactory)

    private var peerConnectionJob :Job? = null
    private var electrumConnectionJob: Job? = null
    private var httpControlFlowEnabled: Boolean = false
    private var torConnectionJob :Job? = null

    private var networkStatus = MutableStateFlow(NetworkState.NotAvailable)

    private data class TrafficControl(
        val networkIsAvailable: Boolean = false,

        // Under normal circumstances, the connections are automatically managed based on whether
        // or not an Internet connection is available. However, the app may need to influence
        // this in one direction or another.
        // For example, the app may want us to disconnect.
        // This variable allows different parts of the app to "vote" towards various overrides.
        //
        // if > 0, triggers disconnect & prevents future connection attempts.
        // if <= 0, allows connection based on available Internet connection (as usual).
        // Any part of the app that "votes" is expected to properly balance their calls.
        // For example, on iOS, when the app goes into background mode,
        // it votes by incrementing this value. Therefore it must balance that call by
        // decrementing the value when the app goes into the foreground again.
        val disconnectCount: Int = 0
    ) {
        fun incrementDisconnectCount(): TrafficControl {
            val safeInc = disconnectCount.let { if (it == Int.MAX_VALUE) it else it + 1 }
            return copy(disconnectCount = safeInc)
        }
        fun decrementDisconnectCount(): TrafficControl {
            val safeDec = disconnectCount.let { if (it == Int.MIN_VALUE) it else it - 1 }
            return copy(disconnectCount = safeDec)
        }
    }

    private val peerControlFlow = MutableStateFlow(TrafficControl())
    private val peerControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    private val electrumControlFlow = MutableStateFlow(TrafficControl())
    private val electrumControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    private val httpApiControlFlow = MutableStateFlow(TrafficControl())
    private val httpApiControlChanges = Channel<TrafficControl.() -> TrafficControl>()

//    private val torControlFlow = MutableStateFlow(TrafficControl())
//    private val torControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    init {
        fun enableControlFlow(
            label: String,
            controlFlow: MutableStateFlow<TrafficControl>,
            controlChanges: ReceiveChannel<TrafficControl.() -> TrafficControl>
        ) = launch {
            controlChanges.consumeEach { change ->
                val newState = controlFlow.value.change()
                logger.info { "$label = $newState" }
                controlFlow.value = newState
            }
        }

        enableControlFlow("peerControlFlow", peerControlFlow, peerControlChanges)
        enableControlFlow("electrumControlFlow", electrumControlFlow, electrumControlChanges)
        enableControlFlow("httpApiControlFlow", httpApiControlFlow, httpApiControlChanges)
//        enableControlFlow("torControlFlow", torControlFlow, torControlChanges)

        // Electrum
        launch {
            electrumControlFlow.collect {
                when {
                    it.networkIsAvailable && it.disconnectCount <= 0 -> {
                        if (electrumConnectionJob == null) {
                            electrumClient.socketBuilder =
                                if (configurationManager.isTorEnabled.value)
                                    tcpSocketBuilder.torProxy(loggerFactory)
                                else
                                    tcpSocketBuilder
                            logger.info { "Electrum socket builder=${electrumClient.socketBuilder}" }
                            electrumConnectionJob = connectionLoop("Electrum", electrumClient.connectionState) {
                                val electrumServer = configurationManager.getElectrumServer()
                                electrumClient.connect(electrumServer.asServerAddress())
                            }
                        }
                    }
                    else -> {
                        electrumConnectionJob?.let {
                            it.cancel()
                            logger.info { "electrumClient.disconnect" }
                            electrumClient.disconnect()
                            logger.info { "electrumClient.disconnected" }
                        }
                        electrumConnectionJob = null
                    }
                }
            }
        }
        launch {
            configurationManager.run {
                if (!getElectrumServer().customized) {
                    setRandomElectrumServer()
                }

                var previousElectrumServer: ElectrumServer? = null
                subscribeToElectrumServer().collect {
                    if (previousElectrumServer?.address() != it.address()) {
                        logger.info { "Electrum server has changed. We need to refresh the connection." }
                        electrumControlChanges.send { incrementDisconnectCount() }
                        electrumControlChanges.send { decrementDisconnectCount() }
                    }

                    previousElectrumServer = it
                }
            }
        }
        // Peer
        launch {
            peerControlFlow.collect {
                val peer = peerManager.getPeer()
                when {
                    it.networkIsAvailable && it.disconnectCount <= 0 -> {
                        if (peerConnectionJob == null) {
                            peer.socketBuilder =
                                if (configurationManager.isTorEnabled.value)
                                    tcpSocketBuilder.torProxy(loggerFactory)
                                else
                                    tcpSocketBuilder
                            logger.info { "Peer socket builder=${peer.socketBuilder}" }
                            peerConnectionJob = connectionLoop("Peer", peer.connectionState) {
                                peer.connect()
                            }
                        }
                    }
                    else -> {
                        peerConnectionJob?.let {
                            it.cancel()
                            logger.info { "peer.disconnect" }
                            peer.disconnect()
                            logger.info { "peer.disconnected" }
                        }
                        peerConnectionJob = null
                    }
                }
            }
        }
        // HTTP APIs
        launch {
            httpApiControlFlow.collect {
                when {
                    it.networkIsAvailable && it.disconnectCount <= 0 -> {
                        if (!httpControlFlowEnabled) {
                            httpControlFlowEnabled = true
                            configurationManager.startWalletParamsLoop()
                            currencyManager.start()
                        }
                    }
                    else -> {
                        if (httpControlFlowEnabled) {
                            httpControlFlowEnabled = false
                            configurationManager.stopWalletParamsLoop()
                            currencyManager.stop()
                        }
                    }
                }
            }
        }
        // Tor
//        launch {
//            combine(configurationManager.isTorEnabled, torControlFlow) {
//              torEnabled: Boolean, controlFlow: TrafficControl -> torEnabled to controlFlow
//            }.collect { (torEnabled, controlFlow) ->
//                logger.info { "torControlFlow: $torEnabled $controlFlow" }
//                when {
//                    torEnabled && controlFlow.networkIsAvailable && controlFlow.disconnectCount <= 0 -> {
//                        if (torConnectionJob == null) {
//                            torConnectionJob = connectionLoop("Tor", tor.state.connectionState()) {
//                                try {
//                                    logger.info { "tor start" }
//                                    tor.start(this)
//                                    logger.info { "tor started" }
//                                } catch (t: Throwable) {
//                                    logger.error(t) { "Tor cannot be started. ${t.message}" }
//                                }
//                            }
//                        }
//                    }
//                    else -> {
//                        torConnectionJob?.let {
//                            it.cancel()
//                            tor.stop()
//                        }
//                        torConnectionJob = null
//                    }
//                }
//            }
//        }
        // Internet connection monitor
        launch {
            monitor.start()
            monitor.networkState.filter { it != networkStatus.value }.collect {
                logger.info { "New internet status: $it" }
                networkStatus.value = it
            }
        }
        // Internet dependent flows - related to the app configuration
        launch {
            networkStatus.collect {
                when (it) {
                    NetworkState.Available -> {
//                        torControlChanges.send { copy(networkIsAvailable = true) }
                        httpApiControlChanges.send { copy(networkIsAvailable = true) }
                    }
                    NetworkState.NotAvailable -> {
//                        torControlChanges.send { copy(networkIsAvailable = false) }
                        httpApiControlChanges.send { copy(networkIsAvailable = false) }
                    }
                }
            }
        }
        // Internet dependent flows - related to the Wallet
        launch {
            // Suspends until the wallet is initialized
            walletManager.wallet.filterNotNull().first()
            combine(
                networkStatus,
                configurationManager.isTorEnabled,
                tor.state
            ) { networkState: NetworkState, torEnabled: Boolean, torState: TorState ->
                networkState to (if (torEnabled) torState else null)
            }.collect { (networkStatus, torState) ->
                when (networkStatus) {
                    NetworkState.NotAvailable -> {
                        peerControlChanges.send { copy(networkIsAvailable = false) }
                        electrumControlChanges.send { copy(networkIsAvailable = false) }
                    }
                    NetworkState.Available -> when(torState) {
                        TorState.STOPPED, TorState.STARTING -> {
                            peerControlChanges.send { copy(networkIsAvailable = false) }
                            electrumControlChanges.send { copy(networkIsAvailable = false) }
                        }
                        null, TorState.RUNNING -> {
                            peerControlChanges.send { copy(networkIsAvailable = true) }
                            electrumControlChanges.send { copy(networkIsAvailable = true) }
                        }
                    }
                }
            }
        }
    }

    fun incrementDisconnectCount(): Unit {
        launch {
            peerControlChanges.send { incrementDisconnectCount() }
            electrumControlChanges.send { incrementDisconnectCount() }
            httpApiControlChanges.send { incrementDisconnectCount() }
//            torControlChanges.send { incrementDisconnectCount() }
        }
    }

    fun decrementDisconnectCount(): Unit {
        launch {
            peerControlChanges.send { decrementDisconnectCount() }
            electrumControlChanges.send { decrementDisconnectCount() }
            httpApiControlChanges.send { decrementDisconnectCount() }
//            torControlChanges.send { decrementDisconnectCount() }
        }
    }

    private fun connectionLoop(name: String, statusStateFlow: StateFlow<Connection>, connect: suspend () -> Unit) = launch {
        var retryDelay = RETRY_DELAY
        statusStateFlow.collect {
            if (it == Connection.CLOSED) {
                logger.debug { "Wait for $retryDelay before retrying connection on $name" }
                delay(retryDelay) ; retryDelay = increaseDelay(retryDelay)
                connect()
            } else if (it == Connection.ESTABLISHED) {
                retryDelay = RETRY_DELAY
            }
        }
    }
}
