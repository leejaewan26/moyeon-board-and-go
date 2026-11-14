package com.moyeon.boardandgo.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moyeon.boardandgo.audio.CabinAudio
import com.moyeon.boardandgo.engine.BoardingEngine
import com.moyeon.boardandgo.engine.DemoScenario
import com.moyeon.boardandgo.engine.DriverGoal
import com.moyeon.boardandgo.engine.Effect
import com.moyeon.boardandgo.engine.EngineState
import com.moyeon.boardandgo.engine.FleetPolicy
import com.moyeon.boardandgo.engine.Input
import com.moyeon.boardandgo.vehicle.VehicleAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val engine: EngineState,
    val scenarioRunning: Boolean,
    val scenarioCaption: String?,
    val log: List<String>,
    val apiStatus: List<Pair<String, VehicleAdapter.ApiStatus>>,
    val ttsStatus: String,
    val chimeStatus: String,
    val lockFallback: Boolean,
    val perDoorLock: Boolean,
    val ambientZoneFallback: Boolean,
    val readingBlinkAt: Long,
    val driversThisWeek: Int,
    val goal: DriverGoal,
    val goalMet: Boolean,
    val fleetMessage: String,
    val summaryLine: String,
    val passengerScreen: Boolean,
)

class AppController(app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    private var engine = BoardingEngine()
    private val log = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private var scenarioJob: Job? = null
    private var scenarioCaption: String? = null
    private var readingBlinkAt = 0L
    private var driversThisWeek = 1
    private var goal = DriverGoal(0)
    private var passengerScreen = false

    private val audio = CabinAudio(app) { refreshOnMain() }
    private val adapter = VehicleAdapter(app, ::onVehicleInput) { refreshOnMain() }

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<UiState> = _state

    init {
        adapter.start()
        viewModelScope.launch {
            while (isActive) {
                apply(engine.tick(now()))
                refresh()
                delay(200)
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ---------------- 입력 ----------------

    private fun onVehicleInput(input: Input) {
        if (scenarioJob?.isActive == true) return // 시나리오 재생 중에는 실제 신호를 쓰지 않는다
        feed(input)
    }

    fun driverTap(input: Input) = feed(input)

    private fun feed(input: Input) {
        apply(engine.handle(input, now()))
        refresh()
    }

    private fun apply(effects: List<Effect>) {
        val scenario = scenarioJob?.isActive == true
        effects.forEach { e ->
            when (e) {
                is Effect.Log -> addLog(e.text)
                is Effect.Ambient -> adapter.setAmbient(e.pattern, e.zone)
                Effect.DriverReadingLightBlink -> { readingBlinkAt = now(); adapter.blinkDriverReadingLight() }
                Effect.Chime -> audio.chime()
                is Effect.Voice -> { addLog("객실 음성 ${e.line.name}: \"${e.line.text}\""); audio.speak(e.line.name, e.line.text) }
                is Effect.Lock -> {
                    adapter.setLock(e.door, e.locked) { door, locked ->
                        if (scenarioJob?.isActive != true) feed(Input.DoorLocked(door, locked))
                    }
                    // 시나리오 재생 중에는 실제 리스너를 쓰지 않으므로 잠금 결과를 바로 되돌려 넣는다.
                    if (scenario) feed(Input.DoorLocked(e.door, e.locked))
                }
            }
        }
    }

    // ---------------- 시연 ----------------

    fun toggleScenario() {
        if (scenarioJob?.isActive == true) {
            scenarioJob?.cancel()
            return
        }
        engine = BoardingEngine(engine.s4WaitSeconds)
        log.clear()
        addLog("시연 시나리오 재생 시작 (VHAL 신호 대신 가상 신호)")
        scenarioJob = viewModelScope.launch {
            val start = now()
            try {
                for (step in DemoScenario.steps) {
                    val wait = start + step.atMs - now()
                    if (wait > 0) delay(wait)
                    scenarioCaption = step.caption
                    addLog("▶ ${step.caption}")
                    step.input?.let { feed(it) } ?: refresh()
                }
                delay(4000)
            } finally {
                scenarioCaption = null
                addLog("시연 시나리오 끝 — 실제 차량 신호로 돌아감")
                main.post { scenarioJob = null; engine = BoardingEngine(engine.s4WaitSeconds); adapter.resync(); refresh() }
            }
        }
        refresh()
    }

    // ---------------- 설정 ----------------

    fun changeS4Wait(delta: Int) { engine.s4WaitSeconds += delta; refresh() }
    fun changeDrivers(delta: Int) { driversThisWeek = (driversThisWeek + delta).coerceIn(1, 20); refresh() }
    fun changeGoal(delta: Int) { goal = DriverGoal((goal.maxEarlyDepartures + delta).coerceIn(0, 20)); refresh() }
    fun setPassengerScreen(on: Boolean) { passengerScreen = on; refresh() }

    // ---------------- 상태 ----------------

    private fun addLog(text: String) {
        log.addFirst("${fmt.format(Date())}  $text")
        while (log.size > 40) log.removeLast()
    }

    private fun refreshOnMain() = main.post { refresh() }

    private fun refresh() { _state.value = buildState() }

    private fun buildState(): UiState {
        val snap = engine.snapshot(now())
        return UiState(
            engine = snap,
            scenarioRunning = scenarioJob?.isActive == true,
            scenarioCaption = scenarioCaption,
            log = log.toList(),
            apiStatus = adapter.apiStatus.toList(),
            ttsStatus = audio.ttsStatus,
            chimeStatus = audio.chimeStatus,
            lockFallback = adapter.lockFallbackUsed,
            perDoorLock = adapter.perDoorLock,
            ambientZoneFallback = adapter.ambientZoneFallback,
            readingBlinkAt = readingBlinkAt,
            driversThisWeek = driversThisWeek,
            goal = goal,
            goalMet = goal.met(engine.summary),
            fleetMessage = FleetPolicy.decide(driversThisWeek).message,
            summaryLine = engine.summary.line(),
            passengerScreen = passengerScreen,
        )
    }

    override fun onCleared() {
        scenarioJob?.cancel()
        adapter.stop()
        audio.release()
    }
}
