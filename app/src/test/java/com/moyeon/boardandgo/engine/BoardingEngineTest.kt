package com.moyeon.boardandgo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoardingEngineTest {

    /** 엔진 + 가상 시계. Lock 효과는 잠금 상태 피드백으로 되돌려 넣는다(실차에선 잠금 리스너가 한다). */
    private class Harness(s4: Int = 5) {
        val engine = BoardingEngine(s4)
        var now = 0L
        val all = mutableListOf<Effect>()

        fun send(input: Input): List<Effect> {
            val out = engine.handle(input, now)
            all += out
            out.filterIsInstance<Effect.Lock>().forEach { send(Input.DoorLocked(it.door, it.locked)) }
            return out
        }

        fun advance(ms: Long): List<Effect> {
            val out = mutableListOf<Effect>()
            val end = now + ms
            while (now < end) {
                now = minOf(now + 100, end)
                out += engine.tick(now)
            }
            all += out
            return out
        }

        fun state() = engine.snapshot(now)

        /** 정차 전 기본 상태: 주행 중(D, 브레이크 뗌), 네 문 닫힘·잠김, 운전자 착석. */
        fun driving(passengerSeated: Boolean = false) {
            send(Input.SeatOccupied(SeatPos.DRIVER, true))
            send(Input.SeatOccupied(SeatPos.PASSENGER, passengerSeated))
            send(Input.DoorLocked(null, true))
            send(Input.Gear(GearPos.DRIVE))
            send(Input.Brake(false))
            advance(3000) // 한동안 주행
            all.clear()
        }

        /** 정차해서 FR 을 열었다 닫고 다시 잠근다. */
        fun stopAndCycleFr() {
            send(Input.Brake(true))
            send(Input.DoorLocked(null, false))
            send(Input.DoorOpen(DoorPos.FR, true))
            send(Input.DoorOpen(DoorPos.FR, false))
        }
    }

    private lateinit var h: Harness

    @Before fun setUp() { h = Harness() }

    private fun List<Effect>.voices() = filterIsInstance<Effect.Voice>().map { it.line }
    private fun List<Effect>.has(e: Effect) = contains(e)
    private fun List<Effect>.greens() = count { it == Effect.Ambient(LightPattern.GREEN) }

    // ---------------- 초록 조건 ----------------

    @Test fun `문이 닫혀도 잠기지 않으면 초록 없음`() {
        h.driving()
        h.stopAndCycleFr()
        h.advance(3000)
        assertFalse(h.state().green)
        assertEquals(0, h.all.greens())
    }

    @Test fun `빈 교통약자석이면 닫힘과 잠금 즉시 초록과 차임 한 번`() {
        h.driving()
        h.stopAndCycleFr()
        val out = h.send(Input.DoorLocked(null, true))
        assertTrue(out.has(Effect.Ambient(LightPattern.GREEN)))
        assertEquals(1, out.count { it == Effect.Chime })
        assertTrue(out.voices().isEmpty())
        h.advance(3000)
        assertEquals(1, h.all.greens())
    }

    @Test fun `교통약자석 착석은 2초 채워야 초록, 그동안 착석 확인 중 표시`() {
        h.driving()
        h.send(Input.Brake(true))
        h.send(Input.DoorLocked(null, false))
        h.send(Input.DoorOpen(DoorPos.FR, true))
        h.send(Input.SeatOccupied(SeatPos.PASSENGER, true))
        h.send(Input.DoorOpen(DoorPos.FR, false))
        val lockOut = h.send(Input.DoorLocked(null, true))
        assertTrue(lockOut.has(Effect.Ambient(LightPattern.CHECKING)))
        assertEquals("교통약자석 착석 확인 중", h.state().passengerNotice)
        assertFalse(h.state().green)
        h.advance(1800)
        assertFalse(h.state().green)
        val out = h.advance(300)
        assertTrue(h.state().green)
        assertTrue(out.has(Effect.Chime))
        assertEquals(listOf(VoiceLine.S1), out.voices())
    }

    @Test fun `S1 은 처음 앉은 정차에서만, 이미 앉아 있던 승객에겐 없음`() {
        h.driving(passengerSeated = true)
        h.stopAndCycleFr()
        h.send(Input.DoorLocked(null, true))
        assertTrue(h.state().green)
        assertTrue(h.all.voices().isEmpty())
    }

    // ---------------- 출발 동작 경고 ----------------

    @Test fun `초록 전 D 면 운전석 독서등만 깜빡이고 객실 소리 없음`() {
        h.driving()
        h.send(Input.Gear(GearPos.PARK))
        h.stopAndCycleFr()
        val out = h.send(Input.Gear(GearPos.DRIVE))
        assertTrue(out.has(Effect.DriverReadingLightBlink))
        assertTrue(out.voices().isEmpty())
        assertFalse(out.has(Effect.Chime))
        assertFalse(out.any { it is Effect.Ambient })
    }

    @Test fun `문 열린 채 출발 동작이면 빨간 무드등도`() {
        h.driving()
        h.send(Input.Brake(true))
        h.send(Input.DoorOpen(DoorPos.FR, true))
        val out = h.send(Input.Turn(TurnState.RIGHT))
        assertTrue(out.has(Effect.DriverReadingLightBlink))
        assertTrue(out.has(Effect.Ambient(LightPattern.RED)))
        // 브레이크 해제도 출발 동작
        h.send(Input.Turn(TurnState.OFF))
        val out2 = h.send(Input.Brake(false))
        assertTrue(out2.has(Effect.DriverReadingLightBlink))
        assertTrue(h.state().inCycle) // 문이 열려 있으니 실제 출발로 보지 않음
    }

    @Test fun `신호 대기 중 깜빡이·브레이크 해제에는 아무 반응 없음`() {
        h.driving()
        h.send(Input.Brake(true))
        val out = h.send(Input.Turn(TurnState.LEFT)) + h.send(Input.Turn(TurnState.OFF)) + h.send(Input.Brake(false))
        assertTrue(out.none { it is Effect.DriverReadingLightBlink || it is Effect.Voice || it is Effect.Ambient || it == Effect.Chime })
        assertFalse(h.state().inCycle)
    }

    @Test fun `P단에서 브레이크를 떼는 것은 출발 동작이 아님`() {
        h.driving()
        h.send(Input.Brake(true))
        h.send(Input.Gear(GearPos.PARK))
        h.send(Input.DoorOpen(DoorPos.FR, true))
        val out = h.send(Input.Brake(false))
        assertFalse(out.has(Effect.DriverReadingLightBlink))
    }

    @Test fun `초록 뒤 출발 동작은 경고 없음, 출발하면 사이클 끝`() {
        h.driving()
        h.stopAndCycleFr()
        h.send(Input.DoorLocked(null, true))
        val out = h.send(Input.Turn(TurnState.LEFT)) + h.send(Input.Brake(false))
        assertFalse(out.has(Effect.DriverReadingLightBlink))
        assertFalse(h.state().inCycle)
        assertEquals(0, h.state().earlyDepartures)
    }

    @Test fun `초록 없이 실제 출발하면 초록 전 출발 1건`() {
        h.driving()
        h.stopAndCycleFr() // 잠그지 않음
        h.send(Input.Brake(false))
        assertFalse(h.state().inCycle)
        assertEquals(1, h.state().earlyDepartures)
    }

    // ---------------- 정차 중 일어섬 ----------------

    @Test fun `정차 중 일어서면 S2 한 번, 10초 안에 또 일어서면 빛만`() {
        h.driving(passengerSeated = true)
        h.stopAndCycleFr()
        val first = h.send(Input.SeatOccupied(SeatPos.PASSENGER, false))
        assertEquals(listOf(VoiceLine.S2), first.voices())
        assertTrue(first.has(Effect.Ambient(LightPattern.SIT_STILL)))
        h.advance(1000)
        h.send(Input.SeatOccupied(SeatPos.PASSENGER, true))
        h.advance(500)
        val second = h.send(Input.SeatOccupied(SeatPos.PASSENGER, false))
        assertTrue(second.voices().isEmpty())
        assertTrue(second.has(Effect.Ambient(LightPattern.SIT_STILL)))
        // 같은 정차에서는 10초가 지나도 다시 내지 않는다
        h.advance(11_000)
        h.send(Input.SeatOccupied(SeatPos.PASSENGER, true))
        h.advance(500)
        assertTrue(h.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices().isEmpty())
    }

    @Test fun `일어선 동안은 잠겨 있어도 초록을 주지 않고, 문이 열리면 이제 일어나세요 빛`() {
        h.driving(passengerSeated = true)
        h.stopAndCycleFr()
        h.send(Input.DoorLocked(null, true))
        assertTrue(h.state().green)
        h.send(Input.SeatOccupied(SeatPos.PASSENGER, false))
        h.advance(3000)
        assertFalse(h.state().green)
        h.send(Input.DoorLocked(null, false))
        val out = h.send(Input.DoorOpen(DoorPos.FR, true))
        assertTrue(out.has(Effect.Ambient(LightPattern.STAND_NOW)))
    }

    @Test fun `S2 는 문이 열려 있거나 브레이크를 안 밟았거나 정차 사이클 밖이면 없음`() {
        // 사이클 밖(신호 대기)
        h.driving(passengerSeated = true)
        h.send(Input.Brake(true))
        assertTrue(h.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices().isEmpty())
        // 문 열림
        val h2 = Harness()
        h2.driving(passengerSeated = true)
        h2.send(Input.Brake(true))
        h2.send(Input.DoorOpen(DoorPos.FR, true))
        assertTrue(h2.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices().isEmpty())
        // 브레이크 안 밟음(P단 주차)
        val h3 = Harness()
        h3.driving(passengerSeated = true)
        h3.send(Input.Brake(true))
        h3.send(Input.Gear(GearPos.PARK))
        h3.send(Input.DoorOpen(DoorPos.FR, true))
        h3.send(Input.DoorOpen(DoorPos.FR, false))
        h3.send(Input.Brake(false))
        assertTrue(h3.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices().isEmpty())
    }

    @Test fun `다음 정차라도 10초 안이면 S2 대신 빛만, 10초 뒤면 다시 S2`() {
        h.driving(passengerSeated = true)
        h.stopAndCycleFr()
        assertEquals(listOf(VoiceLine.S2), h.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices())
        h.send(Input.SeatOccupied(SeatPos.PASSENGER, true))
        h.advance(2100)
        h.send(Input.DoorLocked(null, true))
        h.send(Input.Brake(false)) // 출발
        assertFalse(h.state().inCycle)
        h.advance(2000)
        h.stopAndCycleFr()
        assertTrue(h.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices().isEmpty())
        h.send(Input.SeatOccupied(SeatPos.PASSENGER, true))
        h.advance(2100)
        h.send(Input.DoorLocked(null, true))
        h.send(Input.Brake(false))
        h.advance(10_000)
        h.stopAndCycleFr()
        assertEquals(listOf(VoiceLine.S2), h.send(Input.SeatOccupied(SeatPos.PASSENGER, false)).voices())
    }

    @Test fun `일반 승하차에는 음성 없음`() {
        h.driving()
        h.stopAndCycleFr()
        h.send(Input.DoorLocked(null, true))
        h.send(Input.Brake(false))
        assertTrue(h.all.voices().isEmpty())
    }

    // ---------------- 느린 탑승 ----------------

    private fun slowBoardingUntilS4(): List<Effect> {
        h.driving()
        h.send(Input.Brake(true))
        h.send(Input.Gear(GearPos.PARK))
        h.send(Input.DoorLocked(null, false))
        val s3 = h.send(Input.DoorOpen(DoorPos.RR, true))
        assertEquals(listOf(VoiceLine.S3), s3.voices())
        assertEquals(Mode.SLOW_BOARDING, h.state().mode)
        h.send(Input.SeatOccupied(SeatPos.DRIVER, false))
        h.send(Input.DoorOpen(DoorPos.RR, false))
        h.send(Input.DoorLocked(null, true))
        assertFalse("고정 완료 전에는 닫히고 잠겨도 초록 없음", h.state().green)
        return h.send(Input.SeatOccupied(SeatPos.DRIVER, true))
    }

    @Test fun `느린 탑승 전체 순서`() {
        val s4 = slowBoardingUntilS4()
        assertEquals(listOf(VoiceLine.S4), s4.voices())
        assertEquals(SlowBoardStep.WAITING, h.state().slowBoardStep)
        h.send(Input.TapFixDone) // 대기 중 탭은 무시
        assertFalse(h.state().green)
        h.advance(5000)
        assertTrue(h.state().canTapFixDone)
        assertEquals("고정 완료 전에는 차임 없음", 0, h.all.count { it == Effect.Chime })
        val out = h.send(Input.TapFixDone)
        assertTrue(h.state().green)
        assertTrue(out.has(Effect.Chime))
        assertEquals(1, h.state().slowBoardings)
    }

    @Test fun `고정 완료는 P단에서만`() {
        slowBoardingUntilS4()
        h.advance(5000)
        h.send(Input.Gear(GearPos.DRIVE)) // 초록 전 D → 독서등
        assertTrue(h.all.has(Effect.DriverReadingLightBlink))
        assertFalse(h.state().canTapFixDone)
        h.send(Input.TapFixDone)
        assertFalse(h.state().green)
        h.send(Input.Gear(GearPos.PARK))
        h.send(Input.TapFixDone)
        assertTrue(h.state().green)
    }

    @Test fun `S4 대기는 최소 3초`() {
        val e = BoardingEngine(s4WaitSeconds = 0)
        assertEquals(3, e.s4WaitSeconds)
        e.s4WaitSeconds = 1
        assertEquals(3, e.s4WaitSeconds)
        e.s4WaitSeconds = 8
        assertEquals(8, e.s4WaitSeconds)

        h = Harness(s4 = 1)
        slowBoardingUntilS4()
        h.advance(2900)
        assertFalse(h.state().canTapFixDone)
        h.advance(200)
        assertTrue(h.state().canTapFixDone)
    }

    @Test fun `운전석이 처음부터 비어 있으면 착석만으로 S4`() {
        h.driving()
        h.send(Input.SeatOccupied(SeatPos.DRIVER, false))
        h.send(Input.Brake(true))
        h.send(Input.DoorOpen(DoorPos.RR, true))
        assertEquals(listOf(VoiceLine.S4), h.send(Input.SeatOccupied(SeatPos.DRIVER, true)).voices())
    }

    // ---------------- 느린 하차 ----------------

    @Test fun `느린 하차 - 고른 정차에서만 RR 잠금 유지, 두 번 탭`() {
        h.driving(passengerSeated = false)
        val sel = h.send(Input.SelectSlowAlight)
        assertTrue(sel.has(Effect.Lock(DoorPos.RR, true)))
        assertEquals(SlowAlightStep.PENDING, h.state().slowAlightStep)

        h.send(Input.Brake(true))
        h.send(Input.Gear(GearPos.PARK))
        val open = h.send(Input.DoorOpen(DoorPos.FR, true))
        assertTrue("휠체어 쪽엔 이제 일어나세요 빛을 내지 않음", open.has(Effect.Ambient(LightPattern.STAND_NOW, LightZone.FRONT_ONLY)))
        assertEquals(SlowAlightStep.ARMED, h.state().slowAlightStep)
        assertTrue(h.state().doorLocked.getValue(DoorPos.RR))

        h.send(Input.TapReleaseDone) // 순서 틀림 → 무시
        assertEquals(SlowAlightStep.ARMED, h.state().slowAlightStep)

        val start = h.send(Input.TapAlightStart)
        assertTrue(start.has(Effect.Lock(DoorPos.RR, false)))
        h.send(Input.DoorOpen(DoorPos.RR, true))
        assertEquals("하차 중 RR 열림은 느린 탑승이 아님", Mode.SLOW_ALIGHTING, h.state().mode)

        val done = h.send(Input.TapReleaseDone)
        assertEquals(listOf(VoiceLine.S5), done.voices())
        assertEquals(Mode.NORMAL, h.state().mode)
        assertEquals(1, h.state().slowAlightings)

        h.send(Input.DoorOpen(DoorPos.RR, false))
        h.send(Input.DoorOpen(DoorPos.FR, false))
        h.send(Input.DoorLocked(null, true))
        assertTrue(h.state().green)
    }

    @Test fun `느린 하차 중에는 해제 완료 전까지 초록 없음`() {
        h.driving()
        h.send(Input.Brake(true))
        h.send(Input.Gear(GearPos.PARK))
        h.send(Input.DoorOpen(DoorPos.FR, true))
        h.send(Input.SelectSlowAlight) // 정차 중 선택 → 바로 ARMED
        assertEquals(SlowAlightStep.ARMED, h.state().slowAlightStep)
        h.send(Input.DoorOpen(DoorPos.FR, false))
        h.send(Input.DoorLocked(null, true))
        h.advance(3000)
        assertFalse(h.state().green)
    }

    @Test fun `느린 하차를 고르지 않은 정차에서는 잠그지 않음`() {
        h.driving()
        h.stopAndCycleFr()
        assertTrue(h.all.none { it is Effect.Lock })
    }

    // ---------------- 요약·Fleet ----------------

    @Test fun `Fleet 은 이번 주 운전자 3명 이상일 때만`() {
        assertFalse(FleetPolicy.decide(1).allowed)
        assertEquals("Fleet 전송: 이번 주 운전자 1명이라 보내지 않음", FleetPolicy.decide(1).message)
        assertFalse(FleetPolicy.decide(2).allowed)
        assertTrue(FleetPolicy.decide(3).allowed)
    }

    @Test fun `요약 문구와 목표`() {
        val s = TripSummary().apply { slowBoardings = 2; slowAlightings = 2 }
        assertEquals("초록 전 출발 0건 / 느린 탑승 2건 / 느린 하차 2건", s.line())
        assertTrue(DriverGoal(0).met(s))
        s.earlyDepartures = 1
        assertFalse(DriverGoal(0).met(s))
    }

    // ---------------- 30초 시연 ----------------

    @Test fun `시연 시나리오를 엔진에 흘리면 명세 순서대로 나온다`() {
        val hh = Harness()
        var t = 0L
        for (step in DemoScenario.steps) {
            hh.advance(step.atMs - t)
            t = step.atMs
            step.input?.let { hh.send(it) }
        }
        hh.advance(500)
        assertEquals(listOf(VoiceLine.S1, VoiceLine.S2, VoiceLine.S3, VoiceLine.S4, VoiceLine.S5), hh.all.voices())
        assertTrue(hh.all.has(Effect.DriverReadingLightBlink))
        assertTrue(hh.all.has(Effect.Ambient(LightPattern.CHECKING)))
        assertTrue(hh.all.has(Effect.Ambient(LightPattern.SIT_STILL)))
        assertTrue(hh.all.has(Effect.Ambient(LightPattern.STAND_NOW, LightZone.FRONT_ONLY)))
        val st = hh.state()
        assertEquals(0, st.earlyDepartures)
        assertEquals(1, st.slowBoardings)
        assertEquals(1, st.slowAlightings)
        assertTrue(st.showSummary)
        assertTrue(st.green)
        assertTrue("시연은 40초 안", DemoScenario.durationMs < 60_000)
    }

    @Test fun `시연 4단계 신호 대기 깜빡이에는 독서등이 한 번도 안 켜진다`() {
        val hh = Harness()
        var t = 0L
        var blinksBefore = -1
        for (step in DemoScenario.steps) {
            hh.advance(step.atMs - t); t = step.atMs
            if (step.caption.startsWith("4. 깜빡이")) blinksBefore = hh.all.count { it == Effect.DriverReadingLightBlink }
            step.input?.let { hh.send(it) }
            if (step.caption.startsWith("4. 깜빡이")) {
                assertEquals(blinksBefore, hh.all.count { it == Effect.DriverReadingLightBlink })
            }
        }
        assertEquals(1, hh.all.count { it == Effect.DriverReadingLightBlink })
    }
}
