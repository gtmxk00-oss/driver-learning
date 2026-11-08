# 길기억 (Route Memory)

운전자가 놓쳤던 곳과 약한 회전 유형을 기억해, 처음 가는 길에서도 방향지시등이 꺼진 채 어려운 구간에 다가갈 때만 음성 한 문장으로 알려 주는 차량용 앱입니다. 일반 운전자부터 택시·화물·버스 기사까지 운전 유형별로 동작합니다.

## 현재 상태

이 저장소는 1차 서류 심사용 시제품입니다.

| 구분 | 상태 |
| --- | --- |
| 판단 규칙 (약한 유형 학습, 필요할 때만 말하기, 운전 유형 프로필, 출발 전 브리핑, 편한 길 제안, 프로필별 기억 삭제) | 구현, 자동 테스트 통과 |
| 동반앱 시연 화면 (PC, Android) | 구현, 가상 신호로 동작 |
| 동반앱 실제 위치 모드 (PC, Android) | 구현. 이 기기의 GPS로 위치·속도·정확도를 실시간 표시하고, 저장한 기억 지점에 다가가면 음성으로 알립니다. 방향지시등·내비게이션 정보는 아직 받지 못합니다 |
| 실제 지도, 장소 검색, 경로, 주변 주유소·주차장, 위성지도 (PC, Android 시제품) | 구현, 공개 지도 서비스 사용. 실유가·주차 잔여 면수·고화질 항공영상·실시간 교통은 연결 전 |
| Pleos Connect SDK 연동 (Vehicle 2.3.2, NaviHelper 3.0.5) | 구현. 방향지시등·기어는 Vehicle SDK, 다음 회전·재탐색·주의 지점·내비 상태는 NaviHelper SDK에서 받아 판단 규칙(`GuidanceSession`)에 연결했습니다. 단위 테스트 통과 |
| Pleos Connect Emulator v2.1.0 (Android 14, API 34) | release APK 설치·실행 확인. 차량 연동 화면에서 Vehicle SDK·NaviHelper SDK 모두 연결되고, Virtual Console로 넣은 방향지시등(좌·우·끄기)과 기어(P·D)가 그대로 수신됩니다. 내비 경로를 실제로 시작해 다음 회전(TBT)을 받는 것은 아직 확인하지 못했습니다 |
| 실차 구동 | 미검증 |
| 주행 중 동작 | 차량 연동 화면만 DO(distractionOptimized)를 선언하고 자기 태스크로 열려서, 기어를 D로 바꿔도 Pleos 홈 화면이 차단하지 않는 것을 에뮬레이터에서 확인했습니다. 시연·실제 위치 화면은 주행 상태가 되면 시스템이 차단하고 정차하면 돌아옵니다. CarUxRestrictions에 따른 화면 축소는 아직 없습니다 |

시연 화면의 차량 신호와 내비게이션 정보는 모두 시뮬레이션입니다. 실제 위치 모드는 위치만 실제이고 방향지시등과 내비게이션 정보는 없습니다. 두 화면 모두 이 사실을 항상 표시합니다.

## 저장소 구성

| 경로 | 내용 |
| --- | --- |
| `android/` | Android Gradle 프로젝트 |
| `android/core/` | 판단 규칙 `DriveCoach`, 실제 이벤트를 판단으로 바꾸는 `GuidanceSession`, 실제 위치 규칙 `LiveCoach`, 시연 장면 `CoachDemo` (순수 Java) |
| `android/coach/` | 동반앱 시연 화면과 실제 위치 모드 |
| `android/pleos/` | 제출용 앱. Pleos SDK 브릿지(`PleosBridge`)와 차량 연동 화면(`GuidanceActivity`)이 여기 있습니다. 위치 권한은 실제 위치 모드를 켤 때만 요청하고, 차량 권한은 Pleos의 `CAR_LIGHTS`·`CAR_DRIVE`·`NAVI_ROUTE`·`NAVI_ROUTE_SEARCH`를 선언합니다. 네트워크 권한은 쓰지 않습니다 |
| `android/shared/`, `mobile/`, `automotive/` | 이전 단계의 지도 내비게이션 시제품 |
| `pc/` | PC 웹 시연. `src/coach.js`가 Android와 같은 규칙입니다 |
| `apk/` | 실행용 release APK |

## 실행 방법

### APK

Android Automotive 에뮬레이터 또는 기기에 설치합니다.

```
adb install apk/P2E3_길기억_release.apk
```

앱 목록에서 길기억을 열고 재생을 누르면 장면이 이어집니다. 아래 장면 버튼으로 원하는 장면을 바로 볼 수 있습니다.

실제 위치 버튼을 누르면 이 기기의 GPS로 동작하는 화면이 열립니다. 위치 켜기를 누를 때 위치 권한을 묻습니다. 헷갈린 곳에서 여기 헷갈려를 누르면 기억 지점으로 저장하고, 다음에 그 지점에 다가가면 음성으로 알립니다. 에뮬레이터에서는 `adb emu geo fix <경도> <위도>`로 위치를 넣어 볼 수 있습니다.

차량 연동 버튼은 Pleos Connect SDK에 붙는 화면입니다. 연결을 누르면 Vehicle SDK와 NaviHelper SDK를 각각 초기화하고, 내비가 알려 주는 다음 회전과 방향지시등으로 판단해 재탐색 뒤 정차하면 놓쳤는지 묻습니다. Pleos Connect Emulator 또는 Pleos 차량에서만 동작하며, 그 외 환경에서는 어느 SDK가 연결되지 않았는지 화면에 표시합니다.

### PC 웹 시연

Node.js 22 이상이 필요합니다. 별도 패키지 설치는 없습니다.

```
cd pc
npm test
npm run build
node serve.cjs
```

브라우저에서 `http://127.0.0.1:4173/`을 열고 왼쪽 메뉴의 동반앱 시연을 누릅니다. 위의 실제 위치 버튼을 누르면 브라우저 위치로 같은 기능을 쓸 수 있습니다.

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
- 빌드 도구: Gradle 8.11.1, Android Gradle Plugin 8.9.1, Java 17
- 자동 테스트: JUnit, Robolectric, Node.js 내장 테스트
- 음성 알림은 기기의 Android 음성 합성을 사용합니다. 한국어 음성이 없으면 화면으로만 표시됩니다.
- 실제 위치 모드는 위치 권한이 필요합니다. 위치는 기기 밖으로 보내지 않고, 기억 지점은 기기(브라우저)에만 저장됩니다.
- API 키나 계정 설정은 필요하지 않습니다. 차량 연동 화면을 실제로 쓰려면 Pleos Connect Emulator(Pleos Playground 문서의 SDK 사이트로 설치, 콜드 부트)가 필요하고, 방향지시등·기어는 에뮬레이터의 Virtual Console(`adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`)에서 넣습니다.

## APK 파일 위치

`apk/P2E3_길기억_release.apk`

## 라이선스

사용한 오픈소스와 지도·데이터의 출처는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 있습니다.
