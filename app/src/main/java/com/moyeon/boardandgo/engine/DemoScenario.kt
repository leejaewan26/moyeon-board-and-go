package com.moyeon.boardandgo.engine

/**
 * 명세 7절 '30초 시연' 순서를 가상 신호로 만든 것. VHAL 패널 조작 없이 같은 엔진에 흘려 넣는다.
 * atMs 는 시나리오 시작부터의 시각(밀리초).
 */
object DemoScenario {
    data class Step(val atMs: Long, val input: Input?, val caption: String)

    val steps: List<Step> = buildList {
        var t = 0L
        fun at(dt: Long, input: Input?, caption: String) { t += dt; add(Step(t, input, caption)) }

        // 출발 전 상태: P, 브레이크 밟음, 네 문 잠김·닫힘, 운전자 착석, 교통약자석 비어 있음
        at(0, Input.Gear(GearPos.PARK), "준비: P단")
        at(0, Input.SeatOccupied(SeatPos.DRIVER, true), "준비: 운전자 착석")
        at(0, Input.SeatOccupied(SeatPos.PASSENGER, false), "준비: 교통약자석 비어 있음")
        at(0, Input.DoorLocked(null, true), "준비: 네 문 잠김")

        // 1. 브레이크 → 잠금 해제 → FR Open
        at(800, Input.Brake(true), "1. 브레이크")
        at(600, Input.DoorLocked(null, false), "1. 잠금 해제")
        at(600, Input.DoorOpen(DoorPos.FR, true), "1. 조수석 문 열림 → 정차 사이클 시작")
        // 2. 조수석 ON → Close → 잠금 → 착석 확인 중 → 1초 만에 D → 독서등 깜빡임
        at(1500, Input.SeatOccupied(SeatPos.PASSENGER, true), "2. 교통약자석 착석")
        at(300, Input.DoorOpen(DoorPos.FR, false), "2. 조수석 문 닫힘")
        at(300, Input.DoorLocked(null, true), "2. 잠금 → '착석 확인 중'")
        at(400, Input.Gear(GearPos.DRIVE), "2. 1초 만에 D → 운전석 독서등만 깜빡임")
        // 3. 2초 후 초록 + 차임 → S1 → 출발
        at(1200, null, "3. 2초 착석 확인 → 초록 + 차임 → S1")
        at(2500, Input.Brake(false), "3. 출발")
        // 4. 신호 대기에서 깜빡이 → 아무 반응 없음
        at(2000, Input.Brake(true), "4. 신호 대기 (문 안 열림)")
        at(600, Input.Turn(TurnState.LEFT), "4. 깜빡이 → 반응 없음")
        at(1500, Input.Turn(TurnState.OFF), "4. 깜빡이 끔")
        at(400, Input.Brake(false), "4. 신호 대기 끝, 출발")
        // 5. 정차 → FR Open/Close → 조수석 OFF → S2 → 다시 ON/OFF → 빛만
        at(2000, Input.Brake(true), "5. 정차")
        at(500, Input.DoorOpen(DoorPos.FR, true), "5. 조수석 문 열림")
        at(1000, Input.DoorOpen(DoorPos.FR, false), "5. 조수석 문 닫힘")
        at(1000, Input.SeatOccupied(SeatPos.PASSENGER, false), "5. 교통약자석 일어섬 → S2")
        at(2500, Input.SeatOccupied(SeatPos.PASSENGER, true), "5. 다시 앉음")
        at(1000, Input.SeatOccupied(SeatPos.PASSENGER, false), "5. 10초 안에 다시 일어섬 → 빛만")
        // 6. RR Open → S3 → Close → 운전석 착석 → S4 → 3초 이상 대기 → [고정 완료] → 초록
        at(2000, Input.Gear(GearPos.PARK), "6. P단")
        at(300, Input.DoorOpen(DoorPos.RR, true), "6. 뒷문(RR) 열림 → 느린 탑승, S3")
        at(500, Input.SeatOccupied(SeatPos.DRIVER, false), "6. 운전자가 내려 휠체어 고정")
        at(3500, Input.DoorOpen(DoorPos.RR, false), "6. 뒷문 닫힘")
        at(800, Input.SeatOccupied(SeatPos.DRIVER, true), "6. 운전석 착석 → S4, 대기 시작")
        at(300, Input.DoorLocked(null, true), "6. 잠금")
        at(5400, Input.TapFixDone, "6. [고정 완료] → 초록")
        at(2000, Input.Gear(GearPos.DRIVE), "6. D")
        at(500, Input.Brake(false), "6. 출발")
        // 7. 다음 정차에서 [느린 하차] → FR 열림, RR 잠김 유지 → [하차 시작] → [해제 완료] → S5
        at(1500, Input.SelectSlowAlight, "7. [느린 하차] 선택 → RR 잠금 유지")
        at(1000, Input.Brake(true), "7. 정차")
        at(300, Input.Gear(GearPos.PARK), "7. P단")
        at(600, Input.DoorOpen(DoorPos.FR, true), "7. 조수석 문은 평소대로 열림, RR 은 잠금 유지 (시연: 패널에 문별 잠금이 없으면 전체 잠금으로 대신)")
        at(2000, Input.TapAlightStart, "7. [하차 시작] → RR 잠금 해제")
        at(800, Input.DoorOpen(DoorPos.RR, true), "7. 운전자가 뒷문 열고 고정 해제")
        at(3000, Input.TapReleaseDone, "7. [해제 완료] → S5")
        at(3000, Input.DoorOpen(DoorPos.RR, false), "7. 승객 하차, 뒷문 닫힘")
        at(300, Input.DoorOpen(DoorPos.FR, false), "7. 조수석 문 닫힘")
        at(300, Input.DoorLocked(null, true), "7. 잠금 → 초록")
        // 8. P → 요약
        at(1500, null, "8. P단 운행 요약 — Fleet 전송: 이번 주 운전자 1명이라 보내지 않음")
    }

    val durationMs: Long get() = steps.last().atMs
}
