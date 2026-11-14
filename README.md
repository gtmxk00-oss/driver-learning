# 운전자학습 (Driver Learning)

운전자가 놓쳤던 곳과 약한 회전 유형을 기억해, 처음 가는 길에서도 방향지시등이 꺼진 채 어려운 구간에 다가갈 때만 음성 한 문장으로 알려 주는 차량용 앱입니다. 일반 운전자부터 택시·화물·버스 기사까지 운전 유형별로 동작합니다.

## 현재 상태

이 저장소는 1차 서류 심사용 시제품입니다.

| 구분 | 상태 |
| --- | --- |
| 판단 규칙 (약한 유형 학습, 필요할 때만 말하기, 운전 유형 프로필, 출발 전 브리핑, 음성 메모, 프로필별 기억 삭제) | 구현, 자동 테스트 통과 |
| 속도·길 익숙도·습관에 따른 판단 (판단 거리는 60km/h 기준 속도에 비례해 절반~두 배, 처음 가는 길은 30% 더 일찍, 세 번 이상 다닌 익숙한 길에서는 약한 유형을 말하지 않고 기억해 둔 곳만, 평소 방향지시등을 잘 켜지 않는 운전자에게는 신호가 꺼진 것만으로 말하지 않음, 80km/h 이상이거나 다음 회전이 500m 안에 또 있으면 짧은 문장) | 구현, 자동 테스트 통과. PC 가상 콘솔의 속도·익숙도·다음 회전 간격으로 볼 수 있음 |
| 회전 세부 (회전교차로 “3시 방향 출구”, 내비가 주는 분기 이름)를 문장에 넣기 | 구현 |
| 차선 추천 (다음 회전 방향에 맞는 차선과 옮길 시점을 내비 화면 아래 네 칸 차선 그림으로 표시: 왼쪽 회전·출구는 1차선, 오른쪽은 맨 오른쪽 차선, 회전교차로는 오른쪽 차선에서 천천히) | 구현. 시연·차량 연동 화면 모두 |
| 목적지별 기억과 출발 전 브리핑 (NaviHelper 목적지 이름에 묶어 저장, 정차 중 그 목적지가 잡히면 최대 3곳 읽어 줌) | 구현 |
| 오늘 주행 요약과 알림 줄이기 제안 (P로 바꾸면 놓침 질문 → 줄이기 제안 → 요약 순서로 한 문장씩) | 구현, 자동 테스트 통과 |
| 음성 메모·음성 대답 (Pleos SpeechToText SDK, 차량 마이크; PC는 브라우저 음성 인식) | 구현. 에뮬레이터에서 SDK 연결·준비 확인 |
| 차 신호로 판단 보정 (와이퍼로 비 25%·전조등으로 밤 15% 판단 거리 확대, 핸들 45° 이상이면 재탐색을 놓침으로 보지 않음, 제한속도 80 이상 도로는 짧은 문장, 주행 거리를 요약 앞에) | 구현, 자동 테스트 통과 (`GuidanceSession`, `coach-guidance.js`) |
| 도착·정차 감지 (내비 도착 + 브레이크 정차에서 P 전에 질문, 정차 중 안전벨트 해제로 주행 종료·요약) | 구현, 자동 테스트 통과 |
| 내비 없이 기억 지점 알림 (확인된 놓침·메모의 좌표를 저장하고 FusedLocation 근접 알림 반경 300m, 최대 20곳으로 등록; 경로 없이 접근하면 한 문장) | 구현. PC 콘솔의 “기억 지점 접근” 버튼으로 같은 경로를 시연 |
| 구간별 수치 기록 (회전에 다가가며 지난 약 100m 구간마다 통과·놓침·방향지시등 없이 지남 횟수 세 개를 기기에 저장; 결과가 2회 이상이고 절반 이상을 놓친 구간에서는 판단 거리를 25% 늘리고 약한 유형이 아니어도 한 문장; 같은 기기의 다른 운전 유형이 남긴 횟수는 숫자만 더해 넘겨받음) | 구현, 자동 테스트 통과 (`DriveCoach`·`GuidanceSession`, `coach.js`·`coach-guidance.js`). PC 콘솔의 구간 A·B·C(가상 위치)로 시연합니다. 에뮬레이터에서는 차 위치가 움직이지 않아 실제 주행으로는 확인하지 못했습니다 |
| 여러 차량의 구간 집계 (서버) | 계획. 지금은 한 기기 안의 프로필끼리만 더합니다 |
| 화면 모양 (차량 연동 화면, 첫 화면, PC 시연 화면) | Pleos Connect 기본 화면에 맞췄습니다: 밝은 바탕, 검은 글자와 주 버튼, 줄 목록. Android 화면은 야간에 어두운 바탕으로 바뀝니다. Pleos Connect UI Design Guide의 글로 된 기준(명암비 4.5:1, 글자 높이, 줄 간격)을 따랐고, 그림으로만 제공되는 치수 표는 반영하지 못했습니다 |
| 음성 출력 (Pleos TextToSpeech SDK, ON_DEVICE; 서비스가 없으면 Android 음성 합성으로 대체하고 화면에 표시) | 구현. 에뮬레이터 검증은 아래 상태 참고 |
| 동반앱 시연 화면 (PC, Android) | 구현, 가상 신호로 동작 |
| 동반앱 실제 위치 모드 (PC, Android) | 구현. 이 기기의 GPS로 위치·속도·정확도를 실시간 표시하고, 저장한 기억 지점에 다가가면 음성으로 알립니다. 방향지시등·내비게이션 정보는 아직 받지 못합니다 |
| 차량 연동 화면 (PC, Android) | 구현. 같은 규칙(`coach-guidance.js` = `GuidanceSession`)으로 다음 회전·방향지시등·기어·재탐색 이벤트를 판단합니다. Android에서는 Pleos SDK 이벤트가 들어오고, PC에서는 Pleos Virtual Console 모양의 가상 차량 콘솔로 같은 이벤트를 넣어 놓침 → 재탐색 → 정차 질문 → 다음 주행 알림까지 볼 수 있습니다 |
| 실제 지도, 장소 검색, 경로, 주변 주유소·주차장, 위성지도 (PC, Android 이전 단계 시제품) | 이전 단계 시제품이라 제출 범위가 아닙니다. 운전자학습은 자체 지도를 내지 않고 Pleos 내비 위의 기억 계층만 맡습니다 |
| Pleos Connect SDK 5종 연동 (Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, SpeechToText 3.3.1, TextToSpeech 3.3.1) + Android Car API | 구현. Vehicle: 방향지시등·기어·조수석 착석·브레이크·핸들 각도·앞유리 와이퍼·전조등·운전석 안전벨트·주행 거리(신호마다 따로 등록해 없는 것은 건너뜀). NaviHelper: 다음 회전(두 번째까지)·재탐색·주의 지점·목적지·도착·제한속도·현재 위치·남은 거리와 시간. FusedLocation: 위치와 기억 지점 근접 알림. SpeechToText: 음성 메모·대답. TextToSpeech: 모든 음성 출력. Car API: 속도·주행 규제 |
| Pleos Connect Emulator v2.1.0 (Android 14, API 34) | release APK 설치·실행 확인. 차량 연동 화면에서 다섯 SDK가 모두 연결되고(Vehicle·NaviHelper·FusedLocation·SpeechToText·TextToSpeech 모두 “연결됨/준비됨”), Virtual Console로 넣은 기어·방향지시등·속도·브레이크·핸들 각도·앞유리 와이퍼·전조등(하향등)이 Vehicle SDK 이벤트로 그대로 들어오는 것을 확인했습니다(2026-11-10 최종 APK로 다시 확인했고, 시연 영상의 에뮬레이터 녹화 구간에 담았습니다). 내장 내비게이션에 목적지를 잡으면 다음 회전 “좌회전 · 80m”가 NaviHelper로 들어오고, “여기 헷갈려”를 누르면 그 회전을 기억한 뒤 곧바로 한 문장을 화면에 띄우며 차량 음성 서비스에 speak 요청이 들어가 재생이 시작되는 것까지 로그로 확인했습니다. 에뮬레이터가 지원하지 않는 것: 주행 거리(trip distance, ERROR_NOT_SUPPORTED_PROPERTY → 화면에서 자동으로 빠짐), 안전벨트 변경(콘솔에 항목 없음, 기본값 “해제”로 수신), 내비 이벤트 주입(TBT·도착·제한속도는 내장 내비가 경로를 안내할 때만 들어옴). |
| 실차 구동 | 미검증 |
| 주행 중 동작 | 차량 연동 화면만 DO(distractionOptimized)를 선언하고 자기 태스크로 열려서, 기어를 D로 바꿔도 Pleos 홈 화면이 차단하지 않는 것을 에뮬레이터에서 확인했습니다. 주행 중에는 CarUxRestrictions에 따라 설명·버튼·유형 선택을 숨기고 상태 한 줄과 질문만 남깁니다. 시연·실제 위치 화면은 주행 상태가 되면 시스템이 차단하고 정차하면 돌아옵니다 |

시연 화면의 차량 신호와 내비게이션 정보는 모두 시뮬레이션입니다. 실제 위치 모드는 위치만 실제이고 방향지시등과 내비게이션 정보는 없습니다. 두 화면 모두 이 사실을 항상 표시합니다.

## 저장소 구성

| 경로 | 내용 |
| --- | --- |
| `android/` | Android Gradle 프로젝트 |
| `android/core/` | 판단 규칙 `DriveCoach`, 실제 이벤트를 판단으로 바꾸는 `GuidanceSession`, 실제 위치 규칙 `LiveCoach`, 시연 장면 `CoachDemo` (순수 Java) |
| `android/coach/` | 동반앱 시연 화면과 실제 위치 모드 |
| `android/pleos/` | 제출용 앱. Pleos SDK 브릿지(`PleosBridge`), 차량 연동 화면(`GuidanceActivity`), 속도(`CarSpeedSource`)·위치(`PleosLocationSource`)·음성(`PleosVoice`)·주행 중 제한(`UxRestrictionsWatch`)이 여기 있습니다. 위치 권한은 실제 위치 모드를 켤 때만 요청하고, 차량 권한은 Pleos의 `CAR_LIGHTS`·`CAR_DRIVE`·`CAR_SEATS`·`NAVI_ROUTE`·`NAVI_ROUTE_SEARCH`·`FUSED_LOCATION`·`STT_SERVICE`와 Android Car API의 `CAR_SPEED`를 선언합니다. 네트워크 권한은 쓰지 않습니다 |
| `android/shared/`, `mobile/`, `automotive/` | 이전 단계의 지도 내비게이션 시제품 |
| `pc/` | PC 웹 시연. `src/coach.js`가 Android와 같은 규칙입니다 |
| `apk/` | 실행용 release APK |

## 시연 영상

[Release v0.12.0-pleos](../../releases/tag/v0.12.0-pleos)에 두 편이 있습니다.

| 영상 | 길이 | 내용 |
| --- | --- | --- |
| [본편](../../releases/download/v0.12.0-pleos/driver-learning-film.mp4) | 4분 22초 | 기능 소개 본편입니다(2026-11-14에 새로 만든 것으로 교체). 설명용 그래픽으로 그린 야간 도로 장면과 기능별 모션 그래픽으로 문제, 놓친 자리 기억과 정차 중 질문, 필요할 때만 말하기, 속도·길 익숙도·차 신호에 따른 판단, 출발 전 브리핑, 메모, 내비 없이 기억 지점 알림, 주행 요약, 운전 유형, 구간별 수치 기록, 다섯 SDK 연결을 차례로 보여 줍니다. 공식 Pleos Connect 에뮬레이터의 실제 화면 녹화(내장 내비에서 목적지를 잡는 장면, 다섯 SDK 연결과 다음 회전 수신, 차량 신호 실시간 반영, 메모 뒤 즉시 발화)와, 확인한 것과 남은 것을 나눈 정리가 들어 있습니다 |
| [상세 시연](../../releases/download/v0.12.0-pleos/driver-learning-detailed-demo.mp4) | 10분 28초 | 다섯 장으로 나눈 기능별 설명입니다. 5장에서 와이퍼·전조등·핸들·도착과 브레이크·안전벨트·주행 거리·근접 알림·구간별 수치 기록을 가상 차량 콘솔로 하나씩 실연하고, 에뮬레이터 캡처 네 장을 보여 줍니다 |

음성은 두 편 모두 AI 합성음입니다(본편 Google Gemini TTS, 상세 시연 Supertonic 3). 본편은 2026-11-14에 새로 만든 것으로 바꿨고 배경음은 직접 합성했습니다. 상세 시연은 2026-11-10에 만들었고 그림·배경음·효과음은 자체 제작입니다.

## 실행 방법

### APK

Android Automotive 에뮬레이터 또는 기기에 설치합니다.

```
adb install apk/P2E3_운전자학습_release.apk
```

앱 목록에서 운전자학습을 열고 재생을 누르면 장면이 이어집니다. 아래 장면 버튼으로 원하는 장면을 바로 볼 수 있습니다.

실제 위치 버튼을 누르면 이 기기의 GPS로 동작하는 화면이 열립니다. 위치 켜기를 누를 때 위치 권한을 묻습니다. 헷갈린 곳에서 여기 헷갈려를 누르면 기억 지점으로 저장하고, 다음에 그 지점에 다가가면 음성으로 알립니다. 에뮬레이터에서는 `adb emu geo fix <경도> <위도>`로 위치를 넣어 볼 수 있습니다.

차량 연동 버튼은 Pleos Connect SDK에 붙는 화면입니다. 연결을 누르면 Vehicle·NaviHelper·FusedLocation·SpeechToText·TextToSpeech SDK와 Car API를 각각 초기화하고, 내비가 알려 주는 다음 회전, 방향지시등, 속도, 길 익숙도로 판단해 재탐색 뒤 정차하면 놓쳤는지 묻고, 이어서 알림을 줄일지와 오늘 주행 요약을 말합니다. 여기 헷갈려 버튼은 지금 회전을 기억하고, 말로 하기 버튼은 차량 마이크로 한 마디를 들어 메모하거나 정차 중 질문에 답합니다(에뮬레이터는 마이크가 없을 수 있어 `Android/data/kr.routememory.pleos/files/stt-test.pcm`에 16kHz 16bit mono PCM을 두면 그 녹음을 대신 씁니다). 목적지에 기억해 둔 곳이 있으면 정차 중 브리핑을 읽습니다. Pleos Connect Emulator 또는 Pleos 차량에서만 동작하며, 그 외 환경에서는 어느 SDK가 연결되지 않았는지 화면에 표시합니다.

### PC 웹 시연

Node.js 22 이상이 필요합니다. 별도 패키지 설치는 없습니다.

```
cd pc
npm test
npm run build
node serve.cjs
```

브라우저에서 `http://127.0.0.1:4173/`을 열고 왼쪽 메뉴의 동반앱 시연을 누릅니다. 위의 실제 위치 버튼을 누르면 브라우저 위치로 같은 기능을 쓸 수 있습니다. 차량 연동 화면의 가상 차량 콘솔에서 구간(가상 위치)을 고르면 구간별 수치 기록이 쌓이는 것을 볼 수 있습니다. 차량 연동 버튼은 Android의 차량 연동 화면을 같은 규칙으로 보여 주며, 아래 가상 차량 콘솔(방향지시등·기어·다음 회전·재탐색)로 이벤트를 넣습니다. 화면은 차량 홈 화면 모양(상태 바, 계기판의 기어·방향지시등 표시, 하단 독)을 직접 그린 모형으로 감싸 차 안에서 보이는 모습에 맞췄고, 제조사의 이미지나 아이콘은 쓰지 않았습니다. 놓침 시나리오 버튼은 왼쪽 출구를 방향지시등 없이 지나쳐 재탐색이 일어나고 정차하면 묻는 흐름을, 같은 길 다시 버튼은 그 다음 주행에서 한 문장을 말하는 것을, 하루 주행 요약 버튼은 세 회전을 지나 P로 바꿨을 때 질문·줄이기 제안·요약이 차례로 나오는 것을 보여 줍니다. 콘솔의 속도 슬라이더, 길 익숙도, 목적지(경로 시작), 세부(예: 3시 방향 출구), 다음 회전까지 거리, 메모 칸과 말로 하기(브라우저 음성 인식)로 나머지 규칙을 직접 넣어 볼 수 있습니다.

### 소스에서 Android 빌드

JDK 17과 Android SDK 35가 필요합니다. Pleos Connect SDK는 `settings.gradle`에 적힌 공개 Maven 저장소(nexus-playground.pleos.ai)에서 내려받습니다.

```
cd android
./gradlew :core:verifyCoach :core:verifyLive :core:verifyGuidance :coach:testDebugUnitTest :pleos:testDebugUnitTest :pleos:assembleDebug
```

서명된 release APK를 만들려면 `keystore.properties.example`을 참고해 `keystore.properties`를 만든 뒤 `:pleos:assembleRelease`를 실행합니다.

## 테스트 환경 및 필요한 설정

- Android 10(API 29) 이상. 앱은 Automotive 기기를 대상으로 선언되어 있습니다.
- 실행 확인 환경: Pleos Connect Emulator v2.1.0 (Android 14, API 34, x86_64, 2560×1440, 콜드 부트). 가상 시연은 정차 상태에서 하고, 차량 연동 화면은 주행 상태에서도 남습니다.
- 빌드 도구: Gradle 8.11.1, Android Gradle Plugin 8.9.1, Java 17. `android.car`는 선택 라이브러리로 선언해 Car API가 없는 기기에서도 설치됩니다
- 자동 테스트: JUnit, Robolectric, Node.js 내장 테스트
- 음성 알림은 차량 연동 화면에서는 Pleos TextToSpeech SDK로, 시연·실제 위치 화면과 그 SDK가 없는 환경에서는 기기의 Android 음성 합성으로 나갑니다. 한국어 음성이 없으면 화면으로만 표시됩니다.
- 실제 위치 모드는 위치 권한이 필요합니다. 위치는 기기 밖으로 보내지 않고, 기억 지점은 기기(브라우저)에만 저장됩니다.
- API 키나 계정 설정은 필요하지 않습니다. 차량 연동 화면을 실제로 쓰려면 Pleos Connect Emulator(Pleos Playground 문서의 SDK 사이트로 설치, 콜드 부트)가 필요하고, 방향지시등·기어·속도는 에뮬레이터의 Virtual Console(`adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`)에서, 위치는 `adb emu geo fix <경도> <위도>`로 넣습니다. 속도 권한(`android.car.permission.CAR_SPEED`)은 `adb install -g`로 설치하면 에뮬레이터에서 바로 허용됩니다.

## APK 파일 위치

`apk/P2E3_운전자학습_release.apk`

## 라이선스

사용한 오픈소스와 지도·데이터의 출처는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 있습니다.
