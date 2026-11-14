# 탄 대로 출발 (board-and-go)

**교통약자석 승객이 앉고 네 문이 닫히고 잠긴 것을 차가 확인해야 초록불을 켜는, 운전자가 있는 셔틀·DRT·교통약자 이동 차량(Pleos Connect 탑재 시)용 앱입니다.** 시간이 아니라 승객 상태로 출발을 정합니다. 막지 않고 알립니다.

- 패키지: `com.moyeon.boardandgo`
- 화면에 보이는 이름: 탄 대로 출발
- 기획 원문: 팀 아이디어 회의의 '탄 대로 출발 v3.1 명세'를 그대로 구현했습니다.

## 무엇을 하나

| 기능 | 동작 |
|---|---|
| 출발 허락(초록) | 네 문 닫힘 + 잠김 + 교통약자석(조수석)이 점유돼 있으면 2초 이상 착석 → 무드등 초록 + 객실 저음 차임 한 번. 그 정차에서 처음 앉은 승객이면 이어서 S1 |
| 착석 확인 중 표시 | 2초를 기다리는 동안 승객 화면에 "교통약자석 착석 확인 중", 무드등은 호박색이 천천히 밝아짐 |
| 초록 전 출발 동작 | D · 브레이크 해제(P 아닐 때) · 깜빡이 → **운전석 독서등만** 깜빡임(객실 무음). 문이 열린 채면 빨간 무드등도 |
| 정차 사이클 | 문이 처음 열린 때부터 실제 출발(브레이크 해제 + 기어 P·N 아님 + 네 문 닫힘)까지. 알림과 S2 는 이 안에서만. 신호 대기·차로 변경(문이 안 열린 정차)은 무시 |
| 정차 중 일어섬 | 문 닫힘 + 브레이크 밟음 + 교통약자석 착석 해제 → S2 를 **한 정차에 한 번**. 10초 안 재발은 빛만(호박 깜빡임). 문이 열리면 "이제 일어나세요" 빛(호박 켜짐). 일어선 동안은 잠겨 있어도 초록을 주지 않음 |
| 느린 탑승 | 뒷문(RR) 열림 → 시간 기준 끔, S3 → 운전자가 운전석에 다시 앉으면 S4 → 최소 3초(기본 5초, 운전자 조정) 대기 → P단에서 [고정 완료] → 문 닫힘·잠김이면 초록. 운전자 알림은 빛으로만 |
| 느린 하차 | 운전자가 [느린 하차]를 고른 정차에서만. 휠체어 쪽 문(RR)만 잠금 유지, 다른 문은 평소대로, "이제 일어나세요" 빛은 휠체어 쪽(뒤)에 내지 않음 → P단 [하차 시작] → RR 잠금 해제 → [해제 완료] → S5 → 정상 판정으로 복귀 |
| 운행 요약 | P단에서 "초록 전 출발 N건 / 느린 탑승 N건 / 느린 하차 N건", 목표는 운전자가 정함. Fleet 전송은 "이번 주 운전자 3명 이상일 때 주 단위 합계만" 규칙을 판정해 보여 주기만 함 |
| 개인 기록 | 개인별 정차 기록은 차 안(앱 메모리·화면)에만 둡니다. **앱에 외부 전송 코드가 없습니다.** 인터넷 권한도 선언하지 않았습니다 |
| 승객용 안내 화면 | [승객 화면] 버튼. 큰 글씨로 지금 상황(착석 확인 중, 객실 음성 문구 자막 등)과 "이 차는 앞자리(교통약자석) 하나만 착석을 확인합니다" 안내 |
| 주행 중 최소 조작 | 기어 ≠ P 이면 [느린 하차] 예약 버튼만 보이고 나머지는 숨김 |

### 객실 음성 문구 (명세 그대로)

| # | 상황 | 문구 |
|---|---|---|
| S1 | 교통약자석 첫 착석 후 초록 | 차가 서고 문이 열리면 일어나세요. |
| S2 | 정차 사이클 중 교통약자석 일어섬 (한 정차 1회) | 문이 열리면 그때 일어나세요. |
| S3 | 뒷문 열림, 느린 탑승 시작 | 휠체어 고정 중이에요. 잠시만 기다려 주세요. |
| S4 | 운전자 착석 후 대기 | 고정 확인할게요. 불편하시면 말씀해 주세요. |
| S5 | 하차 고정 해제 완료 | 고정을 풀었어요. 천천히 내리세요. |

**음성 출력 방식과 대체:** 안드로이드 기본 `TextToSpeech`(한국어, 낮은 피치)로 냅니다. **테스트 에뮬레이터(Pleos_Connect_v2.1.0_API_34)에서는 TTS 초기화가 실패(status -1)해서**, 문구는 승객 화면 자막과 운전자 화면에 글로 띄우고 소리는 짧은 두 음 효과음으로 대신합니다. 초록 차임은 `AudioTrack` 으로 만든 저음(330Hz→262Hz) 한 번입니다. Pleos TextToSpeech SDK(3.3.1, nexus 에 있음)는 앱 등록(registerApp) 정보가 필요해 이번 버전에는 넣지 않았습니다.

## 실행 방법

```bash
# 설치 (에뮬레이터·차량이 adb 로 연결된 상태)
adb install -r apk/모연_탄대로출발_release.apk
adb shell am start -n com.moyeon.boardandgo/.ui.MainActivity
```

- **시연 시나리오 재생** 버튼(운전자 화면 오른쪽 아래): 명세 7절 '30초 시연' 순서(브레이크 → 조수석 문 → 착석 → 잠금 → 1초 만에 D → 2초 뒤 초록·S1 → 신호 대기 깜빡이 무반응 → 일어섬 S2 → 재발 시 빛만 → 느린 탑승 S3·S4·[고정 완료] → 느린 하차 [하차 시작]·[해제 완료]·S5 → P단 요약)를 **가상 신호로 같은 판정 엔진에 흘려** VHAL 패널 조작 없이 재현합니다. 재생 중에는 화면 위에 "시나리오 재생 중 · 가상 신호"가 뜨고 실제 차량 신호는 쓰지 않습니다. 무드등·독서등·문 잠금 쓰기는 재생 중에도 실제 SDK 로 호출합니다. 약 55초 걸립니다.
- 앱이 백그라운드로 가면 안드로이드가 프로세스를 얼려 시나리오 시간이 멈출 수 있습니다. 화면을 앞에 둔 채 재생하세요.
- 실제 신호 모드: 시나리오를 재생하지 않으면 Pleos Vehicle SDK 리스너(문·잠금·좌석·브레이크·기어·깜빡이)로 판정합니다. 에뮬레이터의 Vehicle 패널로 조작할 수 있습니다.
- 화면 오른쪽 아래 **SDK 상태** 탭에서 각 API 호출의 성공·실패·이벤트 수를 볼 수 있습니다. 같은 내용이 logcat 태그 `BoardAndGo` 로 남습니다.

## 빌드

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # JDK 17
sh ./gradlew test            # 판정 엔진 JUnit 테스트
sh ./gradlew assembleRelease # app/build/outputs/apk/release/
```

- `local.properties` 에 `sdk.dir=<Android SDK 경로>` 가 필요합니다(저장소에는 없음).
- release 서명: 저장소 **바깥** `../keystore/keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)를 읽습니다. 파일이 없으면 서명 없이 빌드됩니다. 키와 비밀번호는 저장소에 넣지 않습니다.
- 빌드 설정은 Pleos 공식 샘플(pleos-sample-vehicle-app)과 같습니다: Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, Jetpack Compose, `https://nexus-playground.pleos.ai/repository/maven-releases/` 의 `ai.pleos.playground:Vehicle:2.3.2`.

## APK 위치

`apk/모연_탄대로출발_release.apk` (release, minify 끔, 서명됨)

## 테스트 환경·필요 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos_Connect_v2.1.0_API_34 (Android 14, 빌드 release_connect_v2.1.0 user) |
| Vehicle SDK | 2.3.2 (`ai.pleos.playground:Vehicle`) |
| 화면 규격 | `adb shell wm size` → Physical size: 2560x1440, `wm density` → 160. 앱은 가로 화면 전용이며 Pleos 홈 화면의 오른쪽 앱 영역에서 실행됩니다 |
| minSdk / targetSdk | 29 / 35 |

### 쓰는 권한

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_DOORS` | 문 위치·잠금 읽기 |
| `pleos.car.permission.CONTROL_CAR_DOORS` | 문 잠금 쓰기(setDoorLock) |
| `pleos.car.permission.CAR_SEATS` | 운전석·교통약자석 착석 읽기 |
| `pleos.car.permission.CAR_DRIVE`, `CAR_POWERTRAIN`, `CAR_SAFETY`, `CAR_INFO` | 브레이크·깜빡이 읽기 |
| `android.car.permission.CAR_POWERTRAIN` | 기어 읽기·구독 (AAOS 표준 `GEAR_SELECTION`) |
| `pleos.car.permission.CAR_LIGHTS` | 조명 상태 읽기 |
| `pleos.car.permission.CONTROL_CAR_LIGHTS` | 무드등·운전석 독서등 쓰기 |

## 사용한 Pleos SDK API

| 기능 | API (Vehicle SDK 2.3.2) |
|---|---|
| SDK 연결 | `Vehicle(context).initialize()`, `release()` |
| 네 문 열림 | `Door.registerDoorPosition(DoorArea(ROW_1_LEFT/ROW_1_RIGHT/ROW_2_LEFT/ROW_2_RIGHT), DoorPositionListener)`, `getCurrentDoorPosition` |
| 문 잠김 | `Door.checkDoorLockCapabilities`, `registerDoorLockState`, `getDoorLockState` |
| 문 잠금 쓰기 | `Door.setDoorLock(DoorArea, Boolean)` — 문별 영역이 capability 에 없으면 네 문 묶음 영역으로 대신(시연 전용, 화면에 표시) |
| 교통약자석·운전석 착석 | `Seat.registerSeatOccupancy(SeatArea(ROW_1_RIGHT / ROW_1_LEFT))`, `getCurrentSeatOccupancy` |
| 브레이크 | `Brake.registerBrakePedalStatus`, `getCurrentBrakePedalStatus` |
| 기어 | AAOS 표준 `CarPropertyManager.getProperty(VehiclePropertyIds.GEAR_SELECTION, 0)`, `registerCallback(callback, GEAR_SELECTION, SENSOR_RATE_ONCHANGE)` (`android.car.VehicleGear`) |
| 깜빡이 | `TurnSignal.registerTurnSignalState`, `getCurrentTurnSignalState` |
| 무드등 | `Light.checkAmbientLightBrightnessColorCapabilities`, `setAmbientLightSwitch`, `setAmbientLightBrightnessColor`(0xRRGGBB), `setAmbientLightBrightness`, `getAmbientLightBrightnessColor` |
| 운전석 독서등 | `Light.checkReadingLightSwitchCapabilities`, `setReadingLightSwitch(SeatArea(ROW_1_LEFT), ON/OFF)`, `getReadingLightSwitch` |

Pleos Gear 도메인은 SDK 2.3.2에서 deprecated·공개 문서 제외 → SDK 안내대로 AAOS 표준 GEAR_SELECTION(CarPropertyManager)으로 읽음. `Car.createCar(context)` → `getCarManager(Car.PROPERTY_SERVICE)` 로 얻고, 기어 전용 스레드에서 부릅니다.

SDK 의 register·get 첫 호출은 내부 리플렉션 때문에 수 초 걸릴 수 있어(메인 스레드에서 부르면 ANR) 전용 스레드에서 부릅니다.

## 에뮬레이터에서 확인한 것 (명세 '첫날 확인 목록')

| 항목 | 결과 |
|---|---|
| 기어(GEAR_SELECTION) | AAOS 표준 `CarPropertyManager` 로 읽음. `getProperty` = 4(GEAR_PARK), `registerCallback` 등록 성공(true)·등록 직후 현재값 이벤트(GEAR_PARK) 수신. 에뮬레이터가 user 빌드라 기어 값을 바꿔 넣어 변화 이벤트를 확인하지는 못함 |
| 운전석 독서등 단독 제어 | capability 에 ROW_1_LEFT 가 단독 영역으로 있음. `setReadingLightSwitch(ROW_1_LEFT)` 는 운전석 영역에만 성공 응답. 다만 직후 `getReadingLightSwitch` 읽기 값은 OFF 로 남음(반영 여부 미확인). 낮 시인성은 에뮬레이터로 확인 불가 |
| setDoorLock 문별 동작·패널 반영 | 잠금 영역은 HOOD 와 네 문 묶음(ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT) 두 개뿐 → **문별 잠금 불가**, 문별 영역 조회는 IllegalArgumentException. 묶음 영역 쓰기는 성공 응답이지만 이후 읽기 값은 바뀌지 않음(반영 미확인) |
| 무드등 구역 | 무드등 영역이 다섯 좌석 묶음 하나뿐이라 휠체어 쪽(뒤)만 끌 수 없음 → 화면에 표시. 색은 0xRRGGBB 정수(현재값 0xffffff)로 쓰기 성공 응답 |
| 좌석 착석 토글 | ROW_1_LEFT·ROW_1_RIGHT 읽기·리스너 모두 동작(초기값 운전석 false, 조수석 true) |
| 브레이크 임계값 | SDK 는 Boolean 만 줌. 임계값은 차량 쪽 판단 |
| 음성 재생 | 안드로이드 TTS 초기화 실패(status -1) → 화면 문구 + 효과음으로 대체 |
| 객실 디스플레이 | 별도 SDK 없음 → 앱의 승객 화면(명세 2안)으로 대체 |

## 구조

```
app/src/main/java/com/moyeon/boardandgo/
  engine/   판정 엔진 (안드로이드 의존 없는 순수 Kotlin)
    Model.kt          신호·효과·음성 문구·무드등 패턴
    BoardingEngine.kt 초록 조건, 정차 사이클, 출발 동작 경고, S2 1회 규칙, 느린 탑승·하차
    TripSummary.kt    운행 요약, 운전자 목표, Fleet 전송 규칙(3명 이상)
    DemoScenario.kt   30초 시연 순서(가상 신호)
  vehicle/VehicleAdapter.kt  Vehicle SDK 리스너 등록·쓰기 호출
  audio/CabinAudio.kt        TTS·저음 차임·대체 효과음
  ui/                        Compose 운전자 화면·승객 화면, AppController
app/src/test/.../BoardingEngineTest.kt  JUnit 26개
```

## 남은 한계

- 착석 센서는 앞자리(교통약자석) 하나뿐입니다. 뒷좌석 승객 착석은 보지 않습니다.
- 슬라이딩 문이 RR(ROW_2_RIGHT)이라는 매핑은 추정입니다.
- 속도 API 가 없어 "실제 출발"은 브레이크 해제 + 기어 + 문 닫힘으로 추정합니다.
- 에뮬레이터에서는 문별 잠금과 무드등 구역 제어가 안 되고, 쓰기 반영도 확인하지 못했습니다.
