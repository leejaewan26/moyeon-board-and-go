package com.moyeon.boardandgo.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.VehicleGear
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.moyeon.boardandgo.engine.DoorPos
import com.moyeon.boardandgo.engine.GearPos
import com.moyeon.boardandgo.engine.Input
import com.moyeon.boardandgo.engine.LightPattern
import com.moyeon.boardandgo.engine.LightZone
import com.moyeon.boardandgo.engine.SeatPos
import com.moyeon.boardandgo.engine.TurnState

/**
 * Pleos Vehicle SDK 2.3.2 어댑터. 리스너 신호를 엔진 Input 으로 바꾸고, 엔진 효과를 실제 쓰기 호출로 바꾼다.
 * 모든 호출은 try/catch + onFailed 로 감싸 실패해도 화면이 죽지 않게 하고, 결과를 [apiStatus]에 남긴다.
 * 기어: Pleos Gear 도메인은 SDK 2.3.2에서 deprecated·공개 문서 제외 → SDK 안내대로 AAOS 표준 GEAR_SELECTION(CarPropertyManager)으로 읽음([GearSelectionReader]).
 */
class VehicleAdapter(
    context: Context,
    private val onInput: (Input) -> Unit,
    private val onStatusChanged: () -> Unit,
) {
    companion object { const val TAG = "BoardAndGo" }

    data class ApiStatus(val ok: Boolean?, val detail: String, val events: Int = 0)

    private val main = Handler(Looper.getMainLooper())
    /** SDK 의 register·get 은 내부에서 kotlin-reflect 를 써 첫 호출이 수 초 걸린다(메인에서 부르면 ANR). 전용 스레드에서 부른다. */
    private val worker = HandlerThread("vehicle-sdk").apply { start() }
    private val bg = Handler(worker.looper)
    private val vehicle = Vehicle(context.applicationContext)
    @Volatile private var initialized = false
    private val gearReader = GearSelectionReader(
        context,
        onGear = { raw -> main.post { onInput(Input.Gear(raw.toGearPos())) } },
        onResult = { api, ok, detail -> status(api, ok, detail) },
    )

    /** API 이름 → 마지막 결과. 화면 'SDK 상태'와 logcat 에 보인다. */
    val apiStatus = linkedMapOf<String, ApiStatus>()

    /** 문별 잠금이 capability 에 있는지. 없으면 시연 전용 전체 잠금으로 대신한다. */
    @Volatile var perDoorLock: Boolean = false
        private set
    @Volatile var lockFallbackUsed: Boolean = false
        private set
    @Volatile private var lockAreas: List<DoorArea> = emptyList()
    @Volatile private var ambientAreas: List<SeatArea> = emptyList()
    /** 무드등을 앞·뒤 구역으로 나눠 켤 수 없을 때(영역이 하나로 묶여 있을 때) true. */
    @Volatile var ambientZoneFallback: Boolean = false
        private set

    private val doorValues = mapOf(
        DoorPos.FL to DoorValue.ROW_1_LEFT,
        DoorPos.FR to DoorValue.ROW_1_RIGHT,
        DoorPos.RL to DoorValue.ROW_2_LEFT,
        DoorPos.RR to DoorValue.ROW_2_RIGHT,
    )
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)

    private val listeners = mutableListOf<() -> Unit>() // unregister 동작

    // ------------------------------------------------------------------
    fun start() = bg.post {
        gearReader.start()
        try {
            vehicle.initialize()
            initialized = true
            status("Vehicle.initialize", true, "성공")
        } catch (e: Exception) {
            status("Vehicle.initialize", false, e.describe())
            return@post
        }
        // 서비스 바인딩이 비동기일 수 있어 잠시 뒤 등록한다.
        bg.postDelayed({ registerAll(); readInitial(); inspectCapabilities() }, 800)
    }

    fun stop() {
        gearReader.stop()
        main.removeCallbacksAndMessages(null)
        bg.removeCallbacksAndMessages(null)
        bg.post {
            listeners.forEach { runCatching(it) }
            listeners.clear()
            if (initialized) runCatching { vehicle.release() }
            initialized = false
            worker.quitSafely()
        }
    }

    // ------------------------------------------------------------------
    private fun registerAll() {
        val door = vehicle.getDoor()
        doorValues.forEach { (pos, value) ->
            val area = DoorArea(value)
            val posName = "Door.registerDoorPosition(${value.name})"
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(pos0: Int?) = event(posName) { onInput(Input.DoorOpen(pos, (pos0 ?: 0) > 0)) }
                override fun onFailed(e: Exception) = status(posName, false, e.describe())
            }
            register(posName, { door.registerDoorPosition(area, l) }, { door.unregisterDoorPosition(area, l) })
        }

        val seat = vehicle.getSeat()
        listOf(SeatPos.DRIVER to driverSeat, SeatPos.PASSENGER to passengerSeat).forEach { (pos, area) ->
            val name = "Seat.registerSeatOccupancy(${area.values.first().name})"
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) = event(name) {
                    if (occupied != null) onInput(Input.SeatOccupied(pos, occupied))
                }
                override fun onFailed(e: Exception) = status(name, false, e.describe())
            }
            register(name, { seat.registerSeatOccupancy(area, l) }, { seat.unregisterSeatOccupancy(area, l) })
        }

        val brake = vehicle.getBrake()
        val bName = "Brake.registerBrakePedalStatus"
        val bl = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) = event(bName) { if (pressed != null) onInput(Input.Brake(pressed)) }
            override fun onFailed(e: Exception) = status(bName, false, e.describe())
        }
        register(bName, { brake.registerBrakePedalStatus(bl) }, { brake.unregisterBrakePedalStatus(bl) })

        val turn = vehicle.getTurnSignal()
        val tName = "TurnSignal.registerTurnSignalState"
        val tl = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(v: TurnSignalValue?) = event(tName) { onInput(Input.Turn(v.toTurn())) }
            override fun onFailed(e: Exception) = status(tName, false, e.describe())
        }
        register(tName, { turn.registerTurnSignalState(tl) }, { turn.unregisterTurnSignalState(tl) })
    }

    /** 시나리오가 끝나면 실제 값을 다시 읽는다. 잠금·독서등은 앱이 쓴 값이 차량에 반영됐는지 확인하는 용도도 겸한다. */
    fun resync() = bg.post {
        readInitial()
        readBackWrites()
    }

    private fun readBackWrites() {
        lockAreas.firstOrNull { it.values.size > 1 || it.values.firstOrNull() == DoorValue.ROW_2_RIGHT }?.let { area ->
            val n = "Door.getDoorLockState(${area.values.joinToString("+") { it.name }})"
            call(n) { vehicle.getDoor().getDoorLockState(area, { v -> ok(n, "읽기 값 $v") { if (v != null) onInput(Input.DoorLocked(null, v)) } }, { fail(n, it) }) }
        }
        val rn = "Light.getReadingLightSwitch(ROW_1_LEFT)"
        call(rn) { vehicle.getLight().getReadingLightSwitch(driverSeat, { v -> ok(rn, "읽기 값 $v") {} }, { fail(rn, it) }) }
        val pn = "Light.getReadingLightSwitch(ROW_1_RIGHT)"
        call(pn) { vehicle.getLight().getReadingLightSwitch(passengerSeat, { v -> ok(pn, "읽기 값 $v") {} }, { fail(pn, it) }) }
        ambientAreas.firstOrNull()?.let { a ->
            val cn = "Light.getAmbientLightBrightnessColor(${a.values.joinToString("+") { it.name }})"
            call(cn) { vehicle.getLight().getAmbientLightBrightnessColor(a, { v -> ok(cn, "읽기 값 0x${Integer.toHexString(v ?: 0)}") {} }, { fail(cn, it) }) }
        }
    }

    private fun readInitial() {
        val door = vehicle.getDoor()
        doorValues.forEach { (pos, value) ->
            val n = "Door.getCurrentDoorPosition(${value.name})"
            call(n) { door.getCurrentDoorPosition(DoorArea(value), { p -> ok(n, "값 $p") { onInput(Input.DoorOpen(pos, (p ?: 0) > 0)) } }, { fail(n, it) }) }
        }
        listOf(SeatPos.DRIVER to driverSeat, SeatPos.PASSENGER to passengerSeat).forEach { (pos, area) ->
            val n = "Seat.getCurrentSeatOccupancy(${area.values.first().name})"
            call(n) { vehicle.getSeat().getCurrentSeatOccupancy(area, { o -> ok(n, "값 $o") { if (o != null) onInput(Input.SeatOccupied(pos, o)) } }, { fail(n, it) }) }
        }
        val bn = "Brake.getCurrentBrakePedalStatus"
        call(bn) { vehicle.getBrake().getCurrentBrakePedalStatus({ b -> ok(bn, "값 $b") { if (b != null) onInput(Input.Brake(b)) } }, { fail(bn, it) }) }
        gearReader.refresh()
        val tn = "TurnSignal.getCurrentTurnSignalState"
        call(tn) { vehicle.getTurnSignal().getCurrentTurnSignalState({ t -> ok(tn, "값 ${t?.name}") { onInput(Input.Turn(t.toTurn())) } }, { fail(tn, it) }) }
    }

    private fun inspectCapabilities() {
        val n = "Door.checkDoorLockCapabilities"
        call(n) {
            vehicle.getDoor().checkDoorLockCapabilities({ cfg, areas ->
                lockAreas = areas
                perDoorLock = areas.any { a -> a.values.size == 1 && a.values[0] == DoorValue.ROW_2_RIGHT }
                ok(n, "${cfg.accessType} 영역=${areas.joinToString { a -> a.values.joinToString("+") { it.name } }} 문별잠금=$perDoorLock") {
                    bg.post { registerLockStates() }
                }
            }, { fail(n, it) })
        }
        val an = "Light.checkAmbientLightBrightnessColorCapabilities"
        call(an) {
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ cfg, areas ->
                ambientAreas = areas
                ok(an, "${cfg.accessType} 영역=${areas.joinToString { a -> a.values.joinToString("+") { it.name } }}") {
                    if (areas.isNotEmpty()) bg.post { areas.firstOrNull()?.let { a ->
                        val cn = "Light.getAmbientLightBrightnessColor(${a.values.joinToString("+") { it.name }})"
                        call(cn) { vehicle.getLight().getAmbientLightBrightnessColor(a, { v -> ok(cn, "현재값 0x${Integer.toHexString(v ?: 0)}") {} }, { fail(cn, it) }) }
                    } }
                }
            }, { fail(an, it) })
        }
        val rn = "Light.checkReadingLightSwitchCapabilities"
        call(rn) {
            vehicle.getLight().checkReadingLightSwitchCapabilities({ cfg, map ->
                ok(rn, "${cfg.accessType} " + map.entries.joinToString { (a, l) -> a.values.joinToString("+") { it.name } + "=" + l.joinToString("/") }) {}
            }, { fail(rn, it) })
        }
    }

    /** capability 가 준 잠금 영역(문별 또는 네 문 묶음)에 잠금 리스너를 걸고 현재값을 읽는다. */
    private fun registerLockStates() {
        val door = vehicle.getDoor()
        lockAreas.filter { a -> a.values.none { it == DoorValue.HOOD || it == DoorValue.REAR } }.forEach { area ->
            val pos: DoorPos? = if (area.values.size == 1) doorValues.entries.firstOrNull { it.value == area.values[0] }?.key else null
            val aName = area.values.joinToString("+") { it.name }
            val name = "Door.registerDoorLockState($aName)"
            val l = object : DoorLockStateListener {
                override fun onDoorLockStateUpdated(locked: Boolean?) = event(name) { if (locked != null) onInput(Input.DoorLocked(pos, locked)) }
                override fun onFailed(e: Exception) = status(name, false, e.describe())
            }
            register(name, { door.registerDoorLockState(area, l) }, { door.unregisterDoorLockState(area, l) })
            val rn = "Door.getDoorLockState($aName)"
            call(rn) { door.getDoorLockState(area, { v -> ok(rn, "값 $v") { if (v != null) onInput(Input.DoorLocked(pos, v)) } }, { fail(rn, it) }) }
        }
    }

    // ---------------- 쓰기 ----------------

    /** 문 잠금. 문별 잠금이 capability 에 없으면 시연 전용으로 전체 잠금을 쓴다(실차에서는 쓰지 않는다). */
    fun setLock(door: DoorPos?, locked: Boolean, onDone: (DoorPos?, Boolean) -> Unit) = bg.post {
        if (!initialized) { status("Door.setDoorLock", false, "Vehicle 초기화 안 됨"); return@post }
        val d = vehicle.getDoor()
        val targets: List<Pair<DoorPos?, DoorArea>> = when {
            door != null && perDoorLock -> listOf(door to DoorArea(doorValues.getValue(door)))
            door != null -> { lockFallbackUsed = true; allDoorTargets() }
            else -> allDoorTargets()
        }
        targets.forEach { (pos, area) ->
            val n = "Door.setDoorLock(${area.values.joinToString("+") { it.name }}, $locked)"
            call(n) {
                d.setDoorLock(area, locked, { _, v -> ok(n, "완료 $v") { onDone(pos, v ?: locked) } }, { fail(n, it) })
            }
        }
    }

    private fun allDoorTargets(): List<Pair<DoorPos?, DoorArea>> {
        // capability 가 여러 문을 묶은 영역 하나를 주면 그것을, 아니면 네 문 각각을 잠근다.
        val combined = lockAreas.firstOrNull { it.values.size > 1 }
        return if (combined != null) listOf(null to combined)
        else doorValues.map { (pos, v) -> pos to DoorArea(v) }
    }

    fun setAmbient(pattern: LightPattern, zone: LightZone) = bg.post {
        if (!initialized) return@post
        val light = vehicle.getLight()
        val areas = ambientAreas.ifEmpty { listOf(driverSeat, passengerSeat) }
        val rear = setOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT, SeatValue.ROW_2_CENTER, SeatValue.ROW_3_LEFT, SeatValue.ROW_3_RIGHT, SeatValue.ROW_3_CENTER)
        areas.forEach { area ->
            val isRear = area.values.all { it in rear }
            if (zone == LightZone.FRONT_ONLY && !isRear && area.values.any { it in rear }) ambientZoneFallback = true
            val off = pattern == LightPattern.OFF || (zone == LightZone.FRONT_ONLY && isRear)
            val aName = area.values.joinToString("+") { it.name }
            val sw = "Light.setAmbientLightSwitch($aName)"
            call(sw) { light.setAmbientLightSwitch(area, if (off) LightSwitch.OFF else LightSwitch.ON, { _, v -> ok(sw, "완료 $v") {} }, { fail(sw, it) }) }
            if (!off) {
                val cn = "Light.setAmbientLightBrightnessColor($aName)"
                val color = pattern.rgb()
                call(cn) { light.setAmbientLightBrightnessColor(area, color, { _, v -> ok(cn, "완료 0x${Integer.toHexString(v ?: -1)}") {} }, { fail(cn, it) }) }
                when (pattern) {
                    LightPattern.CHECKING -> ramp(light, area, aName)
                    LightPattern.SIT_STILL -> blinkAmbient(light, area, aName)
                    else -> brightness(light, area, aName, 100)
                }
            }
        }
    }

    private fun brightness(light: ai.pleos.playground.vehicle.api.Light, area: SeatArea, aName: String, value: Int) {
        val n = "Light.setAmbientLightBrightness($aName)"
        call(n) { light.setAmbientLightBrightness(area, value, { _, v -> ok(n, "완료 $v") {} }, { fail(n, it) }) }
    }

    /** '확인 중' 패턴: 호박색이 2초 동안 천천히 밝아진다. */
    private fun ramp(light: ai.pleos.playground.vehicle.api.Light, area: SeatArea, aName: String) {
        listOf(20, 40, 60, 80, 100).forEachIndexed { i, v -> bg.postDelayed({ brightness(light, area, aName, v) }, i * 400L) }
    }

    private fun blinkAmbient(light: ai.pleos.playground.vehicle.api.Light, area: SeatArea, aName: String) {
        listOf(100, 10, 100, 10, 100).forEachIndexed { i, v -> bg.postDelayed({ brightness(light, area, aName, v) }, i * 300L) }
    }

    /** 운전석 독서등만 세 번 깜빡인다. 객실에는 소리를 내지 않는다. */
    fun blinkDriverReadingLight() = bg.post {
        if (!initialized) return@post
        val light = vehicle.getLight()
        val seq = listOf(LightSwitch.ON, LightSwitch.OFF, LightSwitch.ON, LightSwitch.OFF, LightSwitch.ON, LightSwitch.OFF)
        seq.forEachIndexed { i, s ->
            bg.postDelayed({
                val n = "Light.setReadingLightSwitch(ROW_1_LEFT)"
                call(n) { light.setReadingLightSwitch(driverSeat, s, { v -> ok(n, "완료 $v") {} }, { fail(n, it) }) }
            }, i * 350L)
        }
        bg.postDelayed({
            val n1 = "Light.getReadingLightSwitch(ROW_1_LEFT) 깜빡임 중"
            val n2 = "Light.getReadingLightSwitch(ROW_1_RIGHT) 깜빡임 중"
            call(n1) { light.getReadingLightSwitch(driverSeat, { v -> ok(n1, "읽기 값 $v") {} }, { fail(n1, it) }) }
            call(n2) { light.getReadingLightSwitch(passengerSeat, { v -> ok(n2, "읽기 값 $v") {} }, { fail(n2, it) }) }
        }, 150L)
    }

    // ---------------- 공통 ----------------

    private fun register(name: String, reg: () -> Unit, unreg: () -> Unit) {
        try {
            reg()
            listeners += unreg
            main.post { if (apiStatus[name]?.ok != false) status(name, true, "등록됨 (이벤트 대기)") }
        } catch (e: Exception) {
            status(name, false, e.describe())
        }
    }

    private inline fun call(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { status(name, false, e.describe()) }
    }

    private fun ok(name: String, detail: String, then: () -> Unit) { main.post {
        status(name, true, detail)
        then()
    } }

    private fun fail(name: String, e: Exception) { main.post { status(name, false, e.describe()) } }

    private fun event(name: String, then: () -> Unit) { main.post {
        val prev = apiStatus[name]
        apiStatus[name] = ApiStatus(true, "이벤트 수신", (prev?.events ?: 0) + 1)
        Log.i(TAG, "EVENT $name #${apiStatus[name]?.events}")
        onStatusChanged()
        then()
    } }

    private fun status(name: String, ok: Boolean?, detail: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { status(name, ok, detail) }; return }
        val prev = apiStatus[name]
        apiStatus[name] = ApiStatus(ok, detail, prev?.events ?: 0)
        if (ok == false) Log.w(TAG, "FAIL $name: $detail") else Log.i(TAG, "OK $name: $detail")
        onStatusChanged()
    }

    private fun Exception.describe() = "${javaClass.simpleName}: ${message ?: "-"}"
}

/** android.car.VehicleGear(Int) → 엔진 기어. */
private fun Int.toGearPos(): GearPos = when (this) {
    VehicleGear.GEAR_PARK -> GearPos.PARK
    VehicleGear.GEAR_REVERSE -> GearPos.REVERSE
    VehicleGear.GEAR_NEUTRAL -> GearPos.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> GearPos.UNKNOWN
    else -> GearPos.DRIVE // GEAR_DRIVE, GEAR_1..9
}

private fun TurnSignalValue?.toTurn(): TurnState = when (this) {
    TurnSignalValue.LEFT -> TurnState.LEFT
    TurnSignalValue.RIGHT -> TurnState.RIGHT
    else -> TurnState.OFF
}

/** 무드등 색(0xRRGGBB). 청색은 쓰지 않는다. */
fun LightPattern.rgb(): Int = when (this) {
    LightPattern.GREEN -> 0x00C853
    LightPattern.RED -> 0xFF1744
    LightPattern.CHECKING, LightPattern.SIT_STILL, LightPattern.STAND_NOW -> 0xFFA000
    LightPattern.OFF -> 0x000000
}
