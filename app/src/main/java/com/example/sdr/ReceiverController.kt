package com.example.sdr

import java.util.concurrent.Executor

enum class ReceiverState { Idle, Starting, Running, Stopping, Error }

data class ReceiverSnapshot(
    val version: Long = 0,
    val sessionId: Long = 0,
    val state: ReceiverState = ReceiverState.Idle,
    val requestedHz: Long = 100_000_000,
    val appliedHz: Long? = null,
    val error: String? = null
)

/** Serialized hardware owner. Pending settings are replaced; STOP always runs next. */
class ReceiverController(
    private val executor: Executor,
    private val backend: Backend,
    private val listener: (ReceiverSnapshot) -> Unit
) {
    interface Backend {
        fun available(device: String): Boolean
        fun start(device: String, hz: Long, mode: DemodulationMode, gain: ReceiverGain): Int
        fun stop()
        fun tune(hz: Long): Int
        fun mode(mode: DemodulationMode)
        fun gain(gain: ReceiverGain): Int
        fun warmAudio(enabled: Boolean)
        fun close()
    }
    private data class Command(val session: Long, val id: Long, val action: Action)
    private sealed interface Action {
        data class Start(val device: String) : Action
        data object Stop : Action
        data class Tune(val hz: Long) : Action
        data class Mode(val value: DemodulationMode) : Action
        data class Gain(val value: ReceiverGain) : Action
        data class WarmAudio(val enabled: Boolean) : Action
    }
    private val lock = Any()
    private var snapshot = ReceiverSnapshot()
    private var sequence = 0L
    private var mode = DemodulationMode.WFM
    private var gain = ReceiverGain()
    private var stop: Command? = null
    private var start: Command? = null
    private var startReady = true
    private var tune: Command? = null
    private var modeCommand: Command? = null
    private var gainCommand: Command? = null
    private var warm: Command? = null
    private var tuneId = 0L
    private var modeId = 0L
    private var gainId = 0L
    private var scheduled = false
    private var closed = false

    fun snapshot(): ReceiverSnapshot = synchronized(lock) { snapshot }
    private fun publish(value: ReceiverSnapshot = snapshot) {
        snapshot = value.copy(version = snapshot.version + 1)
        listener(snapshot)
    }
    private fun command(action: Action) = Command(snapshot.sessionId, ++sequence, action)
    private fun active() = snapshot.state == ReceiverState.Starting || snapshot.state == ReceiverState.Running
    private fun schedule() {
        if (!scheduled) { scheduled = true; executor.execute(::runOne) }
    }
    fun start(device: String, hz: Long, newMode: DemodulationMode, newGain: ReceiverGain, deferred: Boolean = false) = synchronized(lock) {
        if (closed || active() || snapshot.state == ReceiverState.Stopping) return@synchronized
        mode = newMode; gain = newGain
        publish(snapshot.copy(sessionId = snapshot.sessionId + 1, state = ReceiverState.Starting,
            requestedHz = hz, appliedHz = null, error = null))
        startReady = !deferred
        start = command(Action.Start(device))
        if (startReady) schedule()
    }
    fun activateStart() = synchronized(lock) {
        if (!closed && start != null && snapshot.state == ReceiverState.Starting) { startReady = true; schedule() }
    }
    fun stop(error: String? = null) = synchronized(lock) {
        if (closed) return@synchronized
        requestStop(error)
    }
    private fun requestStop(error: String?) {
        start = null; tune = null; modeCommand = null; gainCommand = null
        publish(snapshot.copy(sessionId = snapshot.sessionId + 1, state = ReceiverState.Stopping,
            error = error ?: snapshot.error))
        stop = command(Action.Stop); schedule()
    }
    fun tune(hz: Long) = synchronized(lock) {
        if (closed) return@synchronized
        publish(snapshot.copy(requestedHz = hz))
        if (active()) { tune = command(Action.Tune(hz)); tuneId = tune!!.id; schedule() }
    }
    fun mode(value: DemodulationMode) = synchronized(lock) {
        if (closed) return@synchronized
        mode = value
        if (active()) { modeCommand = command(Action.Mode(value)); modeId = modeCommand!!.id; schedule() }
    }
    fun gain(value: ReceiverGain) = synchronized(lock) {
        if (closed) return@synchronized
        gain = value
        if (active()) { gainCommand = command(Action.Gain(value)); gainId = gainCommand!!.id; schedule() }
    }
    fun warmAudio(enabled: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        warm = command(Action.WarmAudio(enabled)); schedule()
    }
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        requestStop(null); closed = true; warm = null
    }
    private fun runOne() {
        val next = synchronized(lock) {
            val result = stop ?: start.takeIf { startReady } ?:
                (if (snapshot.state == ReceiverState.Running) listOfNotNull(tune, modeCommand, gainCommand).minByOrNull { it.id } else null) ?: warm
            when (result?.action) {
                Action.Stop -> stop = null
                is Action.Start -> start = null
                is Action.Tune -> tune = null
                is Action.Mode -> modeCommand = null
                is Action.Gain -> gainCommand = null
                is Action.WarmAudio -> warm = null
                null -> Unit
            }
            result
        }
        if (next != null) execute(next)
        synchronized(lock) {
            scheduled = false
            if (stop != null || (start != null && startReady) ||
                (snapshot.state == ReceiverState.Running && (tune != null || modeCommand != null || gainCommand != null)) || warm != null) schedule()
            else if (closed) backend.close()
        }
    }
    private fun execute(command: Command) {
        val action = command.action
        if (!synchronized(lock) { current(command) }) return
        try {
            when (action) {
                is Action.Start -> {
                    val settings = synchronized(lock) { Triple(snapshot.requestedHz, mode, gain) }
                    val result = if (backend.available(action.device))
                        backend.start(action.device, settings.first, settings.second, settings.third) else -101
                    val available = backend.available(action.device)
                    synchronized(lock) {
                        if (command.session != snapshot.sessionId) return
                        if (result == 0 && available) {
                            publish(snapshot.copy(state = ReceiverState.Running, appliedHz = settings.first))
                        } else {
                            requestStop(if (!available) "USB device disconnected" else "Start failed: $result")
                        }
                    }
                }
                Action.Stop -> {
                    backend.stop()
                    synchronized(lock) {
                        if (command.session == snapshot.sessionId)
                            publish(snapshot.copy(state = if (snapshot.error == null) ReceiverState.Idle else ReceiverState.Error,
                                appliedHz = null))
                    }
                }
                is Action.Tune -> {
                    val result = backend.tune(action.hz)
                    synchronized(lock) {
                        if (command.session == snapshot.sessionId && command.id == tuneId)
                            publish(snapshot.copy(appliedHz = if (result == 0) action.hz else snapshot.appliedHz,
                                error = if (result == 0) snapshot.error else "Tune failed: $result"))
                    }
                }
                is Action.Mode -> {
                    backend.mode(action.value)
                    synchronized(lock) {
                        if (command.session == snapshot.sessionId && command.id == modeId) publish()
                    }
                }
                is Action.Gain -> {
                    val result = backend.gain(action.value)
                    synchronized(lock) {
                        if (command.session == snapshot.sessionId && command.id == gainId && result != 0)
                            publish(snapshot.copy(error = "Gain failed: $result"))
                    }
                }
                is Action.WarmAudio -> backend.warmAudio(action.enabled)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (!current(command)) return
                if (action == Action.Stop) publish(snapshot.copy(state = ReceiverState.Error, appliedHz = null,
                    error = "Stop failed: ${error.message}"))
                else requestStop("Receiver failed: ${error.message}")
            }
        }
    }
    private fun current(command: Command): Boolean {
        if (command.action is Action.WarmAudio) return !closed
        if (command.session != snapshot.sessionId) return false
        return when (command.action) {
            is Action.Tune -> command.id == tuneId
            is Action.Mode -> command.id == modeId
            is Action.Gain -> command.id == gainId
            else -> true
        }
    }
}
