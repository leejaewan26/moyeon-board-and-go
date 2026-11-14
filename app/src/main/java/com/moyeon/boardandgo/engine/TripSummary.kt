package com.moyeon.boardandgo.engine

/**
 * 운행 요약. 개인별 정차 기록은 차 안(이 객체·화면)에만 두고 밖으로 보내지 않는다.
 * 앱에는 외부 전송 코드가 없다. FleetPolicy 는 "보낼 수 있는 조건인지"만 판정해 화면에 보여 준다.
 */
class TripSummary {
    var earlyDepartures = 0
    var slowBoardings = 0
    var slowAlightings = 0

    fun line(): String = "초록 전 출발 ${earlyDepartures}건 / 느린 탑승 ${slowBoardings}건 / 느린 하차 ${slowAlightings}건"
}

/** 운전자가 정하는 목표. 기본은 초록 전 출발 0건. */
data class DriverGoal(val maxEarlyDepartures: Int = 0) {
    fun met(summary: TripSummary) = summary.earlyDepartures <= maxEarlyDepartures
}

/**
 * Fleet 전송 규칙(명세 v3.1, P2): 주 단위 합계만, 그 주에 그 차를 운전한 사람이 3명 이상일 때만.
 * 조건이 안 되면 운전자 화면에만 남긴다.
 */
object FleetPolicy {
    const val MIN_DRIVERS_PER_WEEK = 3

    data class Decision(val allowed: Boolean, val message: String)

    fun decide(driversThisWeek: Int): Decision =
        if (driversThisWeek >= MIN_DRIVERS_PER_WEEK) {
            Decision(true, "Fleet 전송 조건 충족: 이번 주 운전자 ${driversThisWeek}명 — 주 단위 합계만 보낼 수 있음 (이 앱은 실제 전송 기능 없음)")
        } else {
            Decision(false, "Fleet 전송: 이번 주 운전자 ${driversThisWeek}명이라 보내지 않음")
        }
}
