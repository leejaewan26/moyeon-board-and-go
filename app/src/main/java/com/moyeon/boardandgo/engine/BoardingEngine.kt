package com.moyeon.boardandgo.engine

/**
 * '탄 대로 출발' 판정 엔진 (명세 v3.1). 안드로이드 의존이 없다.
 *
 * - 정차 사이클: 문이 처음 열린 순간 시작하고, 실제 출발(브레이크 해제 + 기어 P·N 아님 + 네 문 닫힘)에서 끝난다.
 *   운전자 알림과 S2 는 이 구간 안에서만 낸다. 신호 대기(문이 안 열린 정차)는 무시한다.
 * - 초록 조건: 네 문 닫힘 + 잠김 + (교통약자석이 점유돼 있으면 2초 이상 착석) + 모드 조건.
 * - 막지 않고 알린다: 엔진은 출발을 막는 효과를 내지 않는다.
 */
class BoardingEngine(
    s4WaitSeconds: Int = DEFAULT_S4_WAIT_SECONDS,
) {
    companion object {
        const val SEAT_CONFIRM_MS = 2_000L
        const val RESTAND_WINDOW_MS = 10_000L
        const val DEFAULT_S4_WAIT_SECONDS = 5
        const val MIN_S4_WAIT_SECONDS = 3
    }

    // ---- 차량 상태 ----
    private val doorOpen = DoorPos.entries.associateWith { false }.toMutableMap()
    private val doorLocked = DoorPos.entries.associateWith { false }.toMutableMap()
    private var driverSeated = false
    private var passengerSeated = false
    private var passengerSince: Long? = null
    private var brakePressed = false
    private var gear = GearPos.UNKNOWN
    private var turn = TurnState.OFF

    // ---- 정차 사이클 ----
    private var inCycle = false
    private var cycleNo = 0
    private var greenGiven = false
    private var s1GivenThisCycle = false
    private var passengerEmptyAtCycleStart = true
    private var s2GivenThisCycle = false
    private var lastS2At: Long? = null
    private var checkingShown = false
    /** 정차 중 일어선 뒤: 다시 앉아 2초가 지나거나 문이 열릴 때까지 초록을 주지 않는다. */
    private var stoodPending = false

    // ---- 느린 탑승·하차 ----
    private var mode = Mode.NORMAL
    private var slowBoardStep: SlowBoardStep? = null
    private var driverLeftDuringSlow = false
    private var s4WaitUntil = 0L
    private var slowAlightStep: SlowAlightStep? = null

    var s4WaitSeconds: Int = s4WaitSeconds.coerceAtLeast(MIN_S4_WAIT_SECONDS)
        set(value) { field = value.coerceAtLeast(MIN_S4_WAIT_SECONDS) }

    // ---- 표시 ----
    private var light = LightPattern.OFF
    private var lightZone = LightZone.ALL
    private var lastVoice: VoiceLine? = null
    private var lastVoiceAt = 0L
    private var lastNotice: String? = null

    val summary = TripSummary()

    fun handle(input: Input, now: Long): List<Effect> {
        val out = mutableListOf<Effect>()
        when (input) {
            is Input.DoorOpen -> onDoor(input.door, input.open, now, out)
            is Input.DoorLocked -> {
                if (input.door == null) DoorPos.entries.forEach { doorLocked[it] = input.locked }
                else doorLocked[input.door] = input.locked
            }
            is Input.SeatOccupied -> onSeat(input.seat, input.occupied, now, out)
            is Input.Brake -> onBrake(input.pressed, out)
            is Input.Gear -> onGear(input.gear, out)
            is Input.Turn -> onTurn(input.state, out)
            Input.TapFixDone -> onFixDone(out)
            Input.SelectSlowAlight -> onSelectSlowAlight(out)
            Input.TapAlightStart -> onAlightStart(out)
            Input.TapReleaseDone -> onReleaseDone(now, out)
        }
        evaluate(now, out)
        record(now, out)
        return out
    }

    fun tick(now: Long): List<Effect> {
        val out = mutableListOf<Effect>()
        if (mode == Mode.SLOW_BOARDING && slowBoardStep == SlowBoardStep.WAITING && now >= s4WaitUntil) {
            slowBoardStep = SlowBoardStep.READY
            out += Effect.Log("S4 대기 ${s4WaitSeconds}초 끝 — [고정 완료] 가능")
        }
        evaluate(now, out)
        record(now, out)
        return out
    }

    // ------------------------------------------------------------------
    private fun onDoor(door: DoorPos, open: Boolean, now: Long, out: MutableList<Effect>) {
        val was = doorOpen.getValue(door)
        doorOpen[door] = open
        if (!open) {
            if (was && light == LightPattern.RED && !anyDoorOpen()) setLight(LightPattern.OFF, LightZone.ALL, out)
            return
        }
        if (was) return

        if (!inCycle) startCycle(out)
        greenGiven = false
        checkingShown = false
        stoodPending = false

        val alightStop = mode == Mode.SLOW_ALIGHTING && slowAlightStep != SlowAlightStep.PENDING
        if (door == DoorPos.RR && mode == Mode.NORMAL) {
            mode = Mode.SLOW_BOARDING
            slowBoardStep = SlowBoardStep.FIXING
            driverLeftDuringSlow = !driverSeated
            summary.slowBoardings++
            out += Effect.Log("뒷문(RR) 열림 → 느린 탑승. 시간 기준 끔")
            out += Effect.Voice(VoiceLine.S3)
            setLight(LightPattern.CHECKING, LightZone.ALL, out)
            return
        }
        when {
            mode == Mode.SLOW_BOARDING -> Unit // 고정이 끝날 때까지 빛 유지
            alightStop -> setLight(LightPattern.STAND_NOW, LightZone.FRONT_ONLY, out)
            else -> setLight(LightPattern.STAND_NOW, LightZone.ALL, out)
        }
    }

    private fun onSeat(seat: SeatPos, occupied: Boolean, now: Long, out: MutableList<Effect>) {
        when (seat) {
            SeatPos.DRIVER -> {
                driverSeated = occupied
                if (mode == Mode.SLOW_BOARDING && slowBoardStep == SlowBoardStep.FIXING) {
                    if (!occupied) driverLeftDuringSlow = true
                    else if (driverLeftDuringSlow) {
                        slowBoardStep = SlowBoardStep.WAITING
                        s4WaitUntil = now + s4WaitSeconds * 1000L
                        out += Effect.Log("운전석 착석 → S4, ${s4WaitSeconds}초 대기 (착석은 고정 증명이 아님)")
                        out += Effect.Voice(VoiceLine.S4)
                    }
                }
            }
            SeatPos.PASSENGER -> {
                if (occupied == passengerSeated) return
                passengerSeated = occupied
                if (occupied) {
                    passengerSince = now
                    checkingShown = false
                    stoodPending = false
                } else {
                    passengerSince = null
                    onPassengerStood(now, out)
                }
            }
        }
    }

    private fun onPassengerStood(now: Long, out: MutableList<Effect>) {
        if (!inCycle || anyDoorOpen() || !brakePressed) return
        greenGiven = false
        stoodPending = true
        val recent = lastS2At?.let { now - it < RESTAND_WINDOW_MS } ?: false
        if (!s2GivenThisCycle && !recent) {
            s2GivenThisCycle = true
            lastS2At = now
            out += Effect.Log("정차 중 교통약자석 일어섬 → S2 (이번 정차 1회)")
            out += Effect.Voice(VoiceLine.S2)
        } else {
            out += Effect.Log("다시 일어섬 → 빛만 바꿈")
        }
        setLight(LightPattern.SIT_STILL, LightZone.ALL, out, force = true)
    }

    private fun onBrake(pressed: Boolean, out: MutableList<Effect>) {
        val was = brakePressed
        brakePressed = pressed
        if (was && !pressed && gear != GearPos.PARK) departureAttempt("브레이크 해제", out)
    }

    private fun onGear(g: GearPos, out: MutableList<Effect>) {
        val was = gear
        gear = g
        if (g == GearPos.DRIVE && was != GearPos.DRIVE) departureAttempt("D", out)
        if (g == GearPos.PARK && was != GearPos.PARK) out += Effect.Log("P단 — 운행 요약 표시")
    }

    private fun onTurn(t: TurnState, out: MutableList<Effect>) {
        val was = turn
        turn = t
        if (t != TurnState.OFF && was == TurnState.OFF) departureAttempt("깜빡이", out)
    }

    /** 출발 동작(D·브레이크 해제·깜빡이). 정차 사이클 안, 초록 전에만 운전자에게 알린다. */
    private fun departureAttempt(what: String, out: MutableList<Effect>) {
        if (!inCycle || greenGiven) return
        out += Effect.Log("초록 전 출발 동작($what) → 운전석 독서등 깜빡임")
        out += Effect.DriverReadingLightBlink
        if (anyDoorOpen()) setLight(LightPattern.RED, LightZone.ALL, out, force = true)
    }

    private fun onFixDone(out: MutableList<Effect>) {
        if (mode != Mode.SLOW_BOARDING) return
        if (!parkedForDriverTap()) { out += Effect.Log("[고정 완료]는 P단에서만"); return }
        if (slowBoardStep != SlowBoardStep.READY) { out += Effect.Log("아직 [고정 완료]를 누를 수 없음 (${slowBoardStep?.label})"); return }
        slowBoardStep = SlowBoardStep.CONFIRMED
        out += Effect.Log("[고정 완료] 탭")
    }

    private fun onSelectSlowAlight(out: MutableList<Effect>) {
        if (mode == Mode.SLOW_BOARDING) { out += Effect.Log("느린 탑승 중에는 느린 하차를 고를 수 없음"); return }
        if (mode == Mode.SLOW_ALIGHTING) return
        mode = Mode.SLOW_ALIGHTING
        slowAlightStep = if (inCycle) SlowAlightStep.ARMED else SlowAlightStep.PENDING
        out += Effect.Log("[느린 하차] 선택 — 휠체어 쪽 문(RR)만 잠금 유지")
        out += Effect.Lock(DoorPos.RR, true)
    }

    private fun onAlightStart(out: MutableList<Effect>) {
        if (mode != Mode.SLOW_ALIGHTING || slowAlightStep != SlowAlightStep.ARMED) return
        if (!parkedForDriverTap()) { out += Effect.Log("[하차 시작]은 P단에서만"); return }
        slowAlightStep = SlowAlightStep.STARTED
        out += Effect.Log("[하차 시작] → 휠체어 쪽 문(RR) 잠금 해제")
        out += Effect.Lock(DoorPos.RR, false)
    }

    private fun onReleaseDone(now: Long, out: MutableList<Effect>) {
        if (mode != Mode.SLOW_ALIGHTING || slowAlightStep != SlowAlightStep.STARTED) return
        if (!parkedForDriverTap()) { out += Effect.Log("[해제 완료]는 P단에서만"); return }
        summary.slowAlightings++
        mode = Mode.NORMAL
        slowAlightStep = null
        out += Effect.Log("[해제 완료] → S5, 정상 출발 판정으로 복귀")
        out += Effect.Voice(VoiceLine.S5)
        setLight(LightPattern.STAND_NOW, LightZone.ALL, out, force = true)
    }

    // ------------------------------------------------------------------
    private fun evaluate(now: Long, out: MutableList<Effect>) {
        if (!inCycle) return

        // 실제 출발: 브레이크 해제 + 기어가 P·N 이 아님 + 네 문 닫힘
        if (!brakePressed && gear != GearPos.PARK && gear != GearPos.NEUTRAL && !anyDoorOpen()) {
            endCycle(out)
            return
        }
        if (greenGiven || stoodPending) return
        if (anyDoorOpen() || !allLocked()) return

        val modeOk = when (mode) {
            Mode.NORMAL -> true
            Mode.SLOW_BOARDING -> slowBoardStep == SlowBoardStep.CONFIRMED
            Mode.SLOW_ALIGHTING -> false
        }
        if (!modeOk) return

        val since = passengerSince
        if (passengerSeated && since != null && now - since < SEAT_CONFIRM_MS) {
            if (!checkingShown) {
                checkingShown = true
                lastNotice = "교통약자석 착석 확인 중"
                out += Effect.Log("교통약자석 착석 확인 중 (2초)")
                setLight(LightPattern.CHECKING, LightZone.ALL, out, force = true)
            }
            return
        }

        greenGiven = true
        lastNotice = null
        out += Effect.Log("초록 조건 충족 → 초록 + 저음 차임")
        setLight(LightPattern.GREEN, LightZone.ALL, out, force = true)
        out += Effect.Chime
        if (passengerSeated && passengerEmptyAtCycleStart && !s1GivenThisCycle) {
            s1GivenThisCycle = true
            out += Effect.Voice(VoiceLine.S1)
        }
    }

    private fun startCycle(out: MutableList<Effect>) {
        inCycle = true
        cycleNo++
        greenGiven = false
        s1GivenThisCycle = false
        s2GivenThisCycle = false
        checkingShown = false
        stoodPending = false
        passengerEmptyAtCycleStart = !passengerSeated
        if (mode == Mode.SLOW_ALIGHTING && slowAlightStep == SlowAlightStep.PENDING) {
            slowAlightStep = SlowAlightStep.ARMED
        }
        out += Effect.Log("정차 사이클 #$cycleNo 시작")
    }

    private fun endCycle(out: MutableList<Effect>) {
        if (!greenGiven) {
            summary.earlyDepartures++
            out += Effect.Log("초록 전 출발 1건 기록")
        }
        out += Effect.Log("출발 — 정차 사이클 #$cycleNo 끝")
        inCycle = false
        greenGiven = false
        checkingShown = false
        lastNotice = null
        if (mode == Mode.SLOW_BOARDING || (mode == Mode.SLOW_ALIGHTING && slowAlightStep != SlowAlightStep.PENDING)) {
            mode = Mode.NORMAL
            slowBoardStep = null
            slowAlightStep = null
        }
        setLight(LightPattern.OFF, LightZone.ALL, out)
    }

    private fun setLight(p: LightPattern, zone: LightZone, out: MutableList<Effect>, force: Boolean = false) {
        if (!force && p == light && zone == lightZone) return
        light = p
        lightZone = zone
        out += Effect.Ambient(p, zone)
    }

    private fun record(now: Long, out: List<Effect>) {
        out.filterIsInstance<Effect.Voice>().lastOrNull()?.let { lastVoice = it.line; lastVoiceAt = now }
    }

    private fun anyDoorOpen() = doorOpen.values.any { it }
    private fun allLocked() = doorLocked.values.all { it }
    private fun parkedForDriverTap() = gear == GearPos.PARK || gear == GearPos.UNKNOWN

    // ------------------------------------------------------------------
    fun snapshot(now: Long): EngineState = EngineState(
        doorOpen = doorOpen.toMap(),
        doorLocked = doorLocked.toMap(),
        driverSeated = driverSeated,
        passengerSeated = passengerSeated,
        brakePressed = brakePressed,
        gear = gear,
        turn = turn,
        inCycle = inCycle,
        cycleNo = cycleNo,
        green = greenGiven,
        light = light,
        lightZone = lightZone,
        mode = mode,
        slowBoardStep = slowBoardStep,
        slowAlightStep = slowAlightStep,
        s4RemainingMs = if (slowBoardStep == SlowBoardStep.WAITING) (s4WaitUntil - now).coerceAtLeast(0) else 0,
        s4WaitSeconds = s4WaitSeconds,
        canTapFixDone = mode == Mode.SLOW_BOARDING && slowBoardStep == SlowBoardStep.READY && parkedForDriverTap(),
        canSelectSlowAlight = mode == Mode.NORMAL,
        canTapAlightStart = mode == Mode.SLOW_ALIGHTING && slowAlightStep == SlowAlightStep.ARMED && parkedForDriverTap(),
        canTapReleaseDone = mode == Mode.SLOW_ALIGHTING && slowAlightStep == SlowAlightStep.STARTED && parkedForDriverTap(),
        showSummary = gear == GearPos.PARK,
        passengerNotice = lastNotice,
        lastVoice = lastVoice,
        lastVoiceAgeMs = if (lastVoice == null) Long.MAX_VALUE else now - lastVoiceAt,
        earlyDepartures = summary.earlyDepartures,
        slowBoardings = summary.slowBoardings,
        slowAlightings = summary.slowAlightings,
    )
}

data class EngineState(
    val doorOpen: Map<DoorPos, Boolean>,
    val doorLocked: Map<DoorPos, Boolean>,
    val driverSeated: Boolean,
    val passengerSeated: Boolean,
    val brakePressed: Boolean,
    val gear: GearPos,
    val turn: TurnState,
    val inCycle: Boolean,
    val cycleNo: Int,
    val green: Boolean,
    val light: LightPattern,
    val lightZone: LightZone,
    val mode: Mode,
    val slowBoardStep: SlowBoardStep?,
    val slowAlightStep: SlowAlightStep?,
    val s4RemainingMs: Long,
    val s4WaitSeconds: Int,
    val canTapFixDone: Boolean,
    val canSelectSlowAlight: Boolean,
    val canTapAlightStart: Boolean,
    val canTapReleaseDone: Boolean,
    val showSummary: Boolean,
    val passengerNotice: String?,
    val lastVoice: VoiceLine?,
    val lastVoiceAgeMs: Long,
    val earlyDepartures: Int,
    val slowBoardings: Int,
    val slowAlightings: Int,
)
