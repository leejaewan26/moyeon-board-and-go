package com.moyeon.boardandgo.engine

/** 네 문. 슬라이딩 문(휠체어 쪽)은 RR(ROW_2_RIGHT)로 가정한다(실차 매핑 미확인). */
enum class DoorPos(val label: String) { FL("운전석 문"), FR("조수석 문"), RL("뒷문 왼쪽"), RR("뒷문 오른쪽(휠체어)") }

/** 운전석과 교통약자석(조수석). 착석 센서는 앞자리 두 개만 쓴다. */
enum class SeatPos(val label: String) { DRIVER("운전석"), PASSENGER("교통약자석") }

enum class GearPos(val label: String) { PARK("P"), REVERSE("R"), NEUTRAL("N"), DRIVE("D"), UNKNOWN("?") }

enum class TurnState { OFF, LEFT, RIGHT }

/** 엔진에 들어오는 신호. 차량 SDK 리스너, 운전자 탭, 시연 시나리오가 모두 같은 형태로 넣는다. */
sealed interface Input {
    data class DoorOpen(val door: DoorPos, val open: Boolean) : Input
    /** door 가 null 이면 네 문 전체. */
    data class DoorLocked(val door: DoorPos?, val locked: Boolean) : Input
    data class SeatOccupied(val seat: SeatPos, val occupied: Boolean) : Input
    data class Brake(val pressed: Boolean) : Input
    data class Gear(val gear: GearPos) : Input
    data class Turn(val state: TurnState) : Input
    data object TapFixDone : Input
    data object SelectSlowAlight : Input
    data object TapAlightStart : Input
    data object TapReleaseDone : Input
}

/** 객실 음성 대본. 문구는 명세 v3.1 4절 그대로. */
enum class VoiceLine(val text: String) {
    S1("차가 서고 문이 열리면 일어나세요."),
    S2("문이 열리면 그때 일어나세요."),
    S3("휠체어 고정 중이에요. 잠시만 기다려 주세요."),
    S4("고정 확인할게요. 불편하시면 말씀해 주세요."),
    S5("고정을 풀었어요. 천천히 내리세요."),
}

/** 무드등 패턴. 청색은 쓰지 않는다(호박·초록·빨강). */
enum class LightPattern(val label: String) {
    OFF("꺼짐"),
    CHECKING("호박 · 천천히 밝아짐 (확인 중)"),
    GREEN("초록 (출발 허락)"),
    RED("빨강 (문 열린 채 출발 동작)"),
    SIT_STILL("호박 깜빡임 (앉아 계세요)"),
    STAND_NOW("호박 켜짐 (이제 일어나세요)"),
}

enum class LightZone { ALL, FRONT_ONLY }

/** 엔진이 내보내는 효과. 어댑터가 실제 SDK 쓰기·소리로 바꾼다. */
sealed interface Effect {
    data class Ambient(val pattern: LightPattern, val zone: LightZone = LightZone.ALL) : Effect
    /** 운전석 독서등만 깜빡인다. 객실에는 소리를 내지 않는다. */
    data object DriverReadingLightBlink : Effect
    /** 객실 저음 차임 한 번. */
    data object Chime : Effect
    data class Voice(val line: VoiceLine) : Effect
    /** door 가 null 이면 전체. */
    data class Lock(val door: DoorPos?, val locked: Boolean) : Effect
    data class Log(val text: String) : Effect
}

enum class Mode { NORMAL, SLOW_BOARDING, SLOW_ALIGHTING }

enum class SlowBoardStep(val label: String) {
    FIXING("휠체어 고정 중 — 운전석에 앉으면 다음 단계"),
    WAITING("S4 후 대기 중"),
    READY("P단에서 [고정 완료]를 누르세요"),
    CONFIRMED("고정 완료 — 문 닫힘·잠금이면 초록"),
}

enum class SlowAlightStep(val label: String) {
    PENDING("다음 정차에서 느린 하차"),
    ARMED("휠체어 쪽 문 잠금 유지 — P단에서 [하차 시작]"),
    STARTED("휠체어 쪽 문 잠금 해제 — 고정을 푼 뒤 [해제 완료]"),
}
