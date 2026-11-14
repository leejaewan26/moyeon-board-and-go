package com.moyeon.boardandgo.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyeon.boardandgo.engine.DoorPos
import com.moyeon.boardandgo.engine.EngineState
import com.moyeon.boardandgo.engine.GearPos
import com.moyeon.boardandgo.engine.Input
import com.moyeon.boardandgo.engine.LightPattern
import com.moyeon.boardandgo.engine.LightZone
import com.moyeon.boardandgo.engine.Mode
import com.moyeon.boardandgo.engine.TurnState

private val Amber = Color(0xFFFFA000)
private val Green = Color(0xFF00C853)
private val Red = Color(0xFFFF1744)
private val Panel = Color(0xFF1B1D22)
private val Muted = Color(0xFF9AA0A6)

fun LightPattern.color(): Color = when (this) {
    LightPattern.OFF -> Color(0xFF2A2D33)
    LightPattern.GREEN -> Green
    LightPattern.RED -> Red
    LightPattern.CHECKING, LightPattern.SIT_STILL, LightPattern.STAND_NOW -> Amber
}

// =====================================================================
// 운전자 화면
// =====================================================================

@Composable
fun DriverScreen(s: UiState, c: AppController) {
    val e = s.engine
    val driving = e.gear != GearPos.PARK && e.gear != GearPos.UNKNOWN
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101114)).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Header(s)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                StatusCard(s, Modifier.weight(1f))
                VehicleRow(e)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (driving && !s.scenarioRunning) {
                    DrivingPanel(s, c)
                } else {
                    ActionPanel(s, c)
                    if (e.showSummary) SummaryPanel(s, c)
                    LogPanel(s, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton(
                        if (s.scenarioRunning) "시연 멈춤" else "시연 시나리오 재생",
                        modifier = Modifier.weight(1f),
                        color = if (s.scenarioRunning) Red else Color(0xFF3A3F47),
                    ) { c.toggleScenario() }
                    BigButton("승객 화면", modifier = Modifier.weight(1f), color = Color(0xFF3A3F47)) { c.setPassengerScreen(true) }
                }
            }
        }
    }
}

@Composable
private fun Header(s: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("탄 대로 출발", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("승객이 앉고 문이 잠긴 것을 확인해야 초록", fontSize = 26.sp, color = Muted)
        Spacer(Modifier.weight(1f))
        if (s.scenarioRunning) {
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Amber).padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text("시나리오 재생 중 · 가상 신호", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
    if (s.scenarioRunning && s.scenarioCaption != null) {
        Text(s.scenarioCaption, fontSize = 30.sp, color = Amber, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusCard(s: UiState, modifier: Modifier) {
    val e = s.engine
    val (headline, sub) = headline(e)
    val blinking = SystemClock.elapsedRealtime() - s.readingBlinkAt < 2500
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.size(150.dp).clip(CircleShape).background(e.light.color()))
            Column {
                Text(headline, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = if (e.green) Green else Color.White, lineHeight = 72.sp)
                if (sub != null) Text(sub, fontSize = 30.sp, color = Muted, lineHeight = 38.sp)
            }
        }
        Text(
            "무드등: ${e.light.label}" + if (e.lightZone == LightZone.FRONT_ONLY) " · 휠체어 쪽(뒤)은 끔" else "",
            fontSize = 28.sp, color = Color.White
        )
        if (blinking) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF4A3B00)).padding(18.dp)) {
                Text("운전석 독서등 깜빡임 — 초록 전 출발 동작 (객실 무음)", fontSize = 30.sp, color = Amber, fontWeight = FontWeight.Bold)
            }
        }
        e.lastVoice?.takeIf { e.lastVoiceAgeMs < 8000 }?.let {
            Text("객실 음성 ${it.name}: \"${it.text}\"", fontSize = 28.sp, color = Color(0xFFE0E0E0))
        }
        if (s.lockFallback) {
            Text("시연 전용: 패널에 문별 잠금이 없어 전체 잠금으로 대신함 (실차에선 휠체어 쪽 문만 잠금)", fontSize = 24.sp, color = Amber)
        }
        if (s.ambientZoneFallback && e.lightZone == LightZone.FRONT_ONLY) {
            Text("이 차량(에뮬레이터)은 무드등 영역이 하나로 묶여 있어 휠체어 쪽만 끌 수 없음 — 실차에선 뒤 구역 끔", fontSize = 24.sp, color = Amber)
        }
        Spacer(Modifier.weight(1f))
        Text("교통약자석 = 조수석. 착석 센서는 이 자리 하나만 봅니다. 순정 잠금 버튼은 언제나 쓸 수 있습니다(막지 않고 알림).", fontSize = 22.sp, color = Muted)
    }
}

private fun headline(e: EngineState): Pair<String, String?> {
    val anyOpen = e.doorOpen.values.any { it }
    val allLocked = e.doorLocked.values.all { it }
    return when {
        e.green -> "출발 가능" to "네 문 닫힘·잠김" + if (e.passengerSeated) " · 교통약자석 착석 확인" else ""
        !e.inCycle && e.gear == GearPos.PARK -> "P단 · 승객 대기" to "문이 열리면 정차 사이클을 시작합니다."
        !e.inCycle -> "주행 중" to "신호 대기·차로 변경에는 반응하지 않습니다. 문이 열리면 정차 사이클 시작."
        e.mode == Mode.SLOW_BOARDING -> "느린 탑승" to (e.slowBoardStep?.label ?: "") +
            if (e.s4RemainingMs > 0) " · ${(e.s4RemainingMs + 999) / 1000}초" else ""
        e.mode == Mode.SLOW_ALIGHTING -> "느린 하차" to e.slowAlightStep?.label
        e.light == LightPattern.CHECKING -> "착석 확인 중" to "교통약자석 2초 착석을 기다립니다"
        e.light == LightPattern.SIT_STILL -> "승객 일어섬" to "다시 앉거나 문이 열릴 때까지 기다립니다"
        anyOpen -> "출발 대기" to "문 열림: " + e.doorOpen.filterValues { it }.keys.joinToString { it.label }
        !allLocked -> "출발 대기" to "문을 잠그면 초록"
        else -> "출발 대기" to null
    }
}

@Composable
private fun VehicleRow(e: EngineState) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DoorPos.entries.forEach { d ->
            val open = e.doorOpen.getValue(d)
            val locked = e.doorLocked.getValue(d)
            Chip(
                d.name,
                (if (open) "열림" else "닫힘") + "\n" + (if (locked) "잠김" else "풀림"),
                if (open) Red else if (locked) Green else Amber,
                Modifier.weight(1f)
            )
        }
        Chip("교통약자석", if (e.passengerSeated) "착석" else "빔", if (e.passengerSeated) Green else Muted, Modifier.weight(1.3f))
        Chip("운전석", if (e.driverSeated) "착석" else "빔", if (e.driverSeated) Green else Muted, Modifier.weight(1f))
        Chip("브레이크", if (e.brakePressed) "밟음" else "뗌", Color.White, Modifier.weight(1.1f))
        Chip("기어", e.gear.label, if (e.gear == GearPos.PARK) Green else Color.White, Modifier.weight(0.8f))
        Chip("깜빡이", when (e.turn) { TurnState.OFF -> "끔"; TurnState.LEFT -> "왼쪽"; TurnState.RIGHT -> "오른쪽" }, Color.White, Modifier.weight(1f))
    }
}

@Composable
private fun Chip(title: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier.border(2.dp, color.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 16.sp, color = Muted, maxLines = 1)
        Text(value, fontSize = 26.sp, color = color, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 30.sp)
    }
}

@Composable
private fun DrivingPanel(s: UiState, c: AppController) {
    val e = s.engine
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("주행 중에는 조작을 줄였습니다", fontSize = 34.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Text("[느린 하차]만 미리 고를 수 있습니다. 나머지는 P단에서.", fontSize = 26.sp, color = Muted)
        when {
            e.canSelectSlowAlight -> BigButton("느린 하차 (다음 정차)", height = 140.dp) { c.driverTap(Input.SelectSlowAlight) }
            e.mode == Mode.SLOW_ALIGHTING -> Text("느린 하차 예약됨: ${e.slowAlightStep?.label}", fontSize = 30.sp, color = Amber)
        }
    }
}

@Composable
private fun ActionPanel(s: UiState, c: AppController) {
    val e = s.engine
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (e.mode) {
            Mode.SLOW_BOARDING -> {
                Text("느린 탑승 · ${e.slowBoardStep?.label}", fontSize = 28.sp, color = Amber)
                val label = if (e.s4RemainingMs > 0) "고정 완료 (${(e.s4RemainingMs + 999) / 1000}초 뒤)" else "고정 완료"
                BigButton(label, enabled = e.canTapFixDone, color = Green) { c.driverTap(Input.TapFixDone) }
            }
            Mode.SLOW_ALIGHTING -> {
                Text("느린 하차 · ${e.slowAlightStep?.label}", fontSize = 28.sp, color = Amber)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton("하차 시작", Modifier.weight(1f), enabled = e.canTapAlightStart) { c.driverTap(Input.TapAlightStart) }
                    BigButton("해제 완료", Modifier.weight(1f), enabled = e.canTapReleaseDone, color = Green) { c.driverTap(Input.TapReleaseDone) }
                }
            }
            Mode.NORMAL -> {
                BigButton("느린 하차", enabled = e.canSelectSlowAlight) { c.driverTap(Input.SelectSlowAlight) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("S4 뒤 대기 ${e.s4WaitSeconds}초 (최소 3초)", fontSize = 26.sp, color = Color.White, modifier = Modifier.weight(1f))
            SmallButton("−") { c.changeS4Wait(-1) }
            SmallButton("+") { c.changeS4Wait(1) }
        }
    }
}

@Composable
private fun SummaryPanel(s: UiState, c: AppController) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("운행 요약 (P단)", fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Text(s.summaryLine, fontSize = 30.sp, color = if (s.goalMet) Green else Amber, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("내 목표: 초록 전 출발 ${s.goal.maxEarlyDepartures}건 이하 " + if (s.goalMet) "· 달성" else "· 넘음", fontSize = 24.sp, color = Color.White, modifier = Modifier.weight(1f))
            SmallButton("−") { c.changeGoal(-1) }
            SmallButton("+") { c.changeGoal(1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.fleetMessage, fontSize = 24.sp, color = Muted, modifier = Modifier.weight(1f))
            SmallButton("−") { c.changeDrivers(-1) }
            SmallButton("+") { c.changeDrivers(1) }
        }
        Text("개인별 정차 기록은 이 차 안에만 남고 밖으로 보내지 않습니다. 회사는 이 화면 열람을 요구할 수 없습니다.", fontSize = 22.sp, color = Muted)
    }
}

@Composable
private fun LogPanel(s: UiState, modifier: Modifier) {
    var showSdk by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Panel).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallButton("기록", selected = !showSdk) { showSdk = false }
            SmallButton("SDK 상태", selected = showSdk) { showSdk = true }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showSdk) {
                item { Text("TTS: ${s.ttsStatus}", fontSize = 20.sp, color = Color.White) }
                item { Text("차임: ${s.chimeStatus}", fontSize = 20.sp, color = Color.White) }
                items(s.apiStatus) { (name, st) ->
                    val col = when (st.ok) { true -> Green; false -> Red; null -> Muted }
                    Text("${if (st.ok == false) "✕" else "○"} $name — ${st.detail}" + if (st.events > 0) " (이벤트 ${st.events})" else "", fontSize = 19.sp, color = col)
                }
            } else {
                items(s.log) { Text(it, fontSize = 21.sp, color = Color(0xFFD0D0D0)) }
            }
        }
    }
}

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    color: Color = Amber,
    height: Dp = 112.dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = if (color == Amber || color == Green) Color.Black else Color.White,
            disabledContainerColor = Color(0xFF2A2D33),
            disabledContentColor = Color(0xFF6B7078),
        )
    ) { Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SmallButton(text: String, selected: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(72.dp).width(if (text.length <= 1) 88.dp else 180.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFF3A3F47) else Color.Transparent,
            contentColor = Color.White
        )
    ) { Text(text, fontSize = 26.sp) }
}

// =====================================================================
// 승객용 안내 화면 (명세 '기다리는 이유를 보여 준다' 2안)
// =====================================================================

@Composable
fun PassengerScreen(s: UiState, onExit: () -> Unit) {
    val e = s.engine
    val voice = e.lastVoice?.takeIf { e.lastVoiceAgeMs < 8000 }
    val main: String = when {
        e.passengerNotice != null -> e.passengerNotice
        voice != null -> voice.text
        else -> when (e.light) {
            LightPattern.GREEN -> "출발합니다. 앉아 계세요."
            LightPattern.STAND_NOW -> "이제 일어나세요."
            LightPattern.SIT_STILL -> "문이 열리면 그때 일어나세요."
            LightPattern.CHECKING -> if (e.mode == Mode.SLOW_BOARDING) "휠체어 고정 중이에요." else "교통약자석 착석 확인 중"
            LightPattern.RED -> "잠시만 기다려 주세요."
            LightPattern.OFF -> "편히 앉아 가세요."
        }
    }
    val sub: String? = if (e.passengerNotice != null && voice != null) voice.text else null
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().height(56.dp).background(e.light.color()))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(64.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (s.scenarioRunning) Text("시나리오 재생 중", fontSize = 30.sp, color = Amber)
            Text(main, fontSize = 110.sp, lineHeight = 130.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (sub != null) {
                Spacer(Modifier.height(32.dp))
                Text(sub, fontSize = 56.sp, color = Color(0xFFE0E0E0), textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "이 차는 앞자리(교통약자석) 하나만 착석을 확인합니다. 차가 서고 문이 열리면 일어나세요.",
                fontSize = 30.sp, color = Muted, modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onExit, modifier = Modifier.height(88.dp)) { Text("운전자 화면", fontSize = 28.sp, color = Color.White) }
        }
        Box(Modifier.fillMaxWidth().height(56.dp).background(e.light.color()))
    }
}
