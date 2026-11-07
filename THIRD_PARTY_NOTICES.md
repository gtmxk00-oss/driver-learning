# 오픈소스 및 데이터 출처

## 소프트웨어

| 이름 | 사용 위치 | 라이선스 |
| --- | --- | --- |
| Leaflet 1.9.4 | `pc/vendor/` | BSD 2-Clause (`pc/vendor/LICENSE-Leaflet.txt`) |
| Hallmark 디자인 지침 | 화면 디자인 참고 | MIT (`pc/licenses/Hallmark-LICENSE.txt`) |
| Pretendard Std 1.3.9 | `pc/vendor/`, PC 화면 글꼴 | SIL Open Font License 1.1 (`pc/vendor/LICENSE-Pretendard.txt`) |
| AndroidX Core, AndroidX Car App Library | `android/shared`, `mobile`, `automotive` | Apache License 2.0 |
| JUnit 4 | 테스트 | Eclipse Public License 1.0 |
| Robolectric | 테스트 | MIT |

제출용 앱 `android/pleos`는 위 Android 라이브러리를 포함하지 않고, 저장소의 `core`와 `coach` 모듈만 사용합니다.

## 지도와 데이터

아래는 `pc/`와 `android/`의 이전 단계 지도 시제품에서 호출하는 외부 서비스입니다. 저장소에 해당 데이터를 포함하지 않으며, 제출용 앱과 동반앱 시연 화면은 사용하지 않습니다.

| 이름 | 용도 | 조건 |
| --- | --- | --- |
| OpenStreetMap | 일반 지도 타일 | © OpenStreetMap contributors, ODbL. 타일 이용 정책 적용 |
| EOxCloudless 2025 | 위성 지도 | EOxCloudless by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025), CC BY-NC-SA 4.0, 비상업적 사용에 한함 |
| Photon | 주소·장소 검색 | OpenStreetMap 데이터 기반 공개 서비스 |
| OSRM | 경로 탐색 | OpenStreetMap 데이터 기반 공개 서비스 |

상용 배포 시 위 서비스는 상용 조건을 다시 확인하거나 다른 제공자로 교체합니다.
