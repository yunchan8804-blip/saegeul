# 언어 금고와 Gemma 보강 통합 계약

상태: 2026-09-10 구현·단위 검사·에뮬레이터 UI 검증을 마쳤다. 이전 버전은 Fold6에 설치해 홈·금고 화면을 확인했고, A35에는 최신 단일 보강 APK를 데이터 보존 설치해 실제 Gemma 수동 백그라운드 생성·통합 보강 버튼·숫자·기호 자판 16키 실제 터치를 확인했다. Fold6 최신 설치와 자동 보강 원래 설정 복원은 남아 있다.

## 사용자 흐름

언어 금고를 단일 진입점으로 사용한다. 상단 `언어 금고 보강`은 개인 입력 반영과 Gemma 수동 예약을 각각 시작하는 단일 행동이다. Gemma가 만든 일반 문장 재료 수, 마지막 작업 시각, 자동 보강 토글과 현재 예약·실행·대기 이유는 같은 상단 구역에서 확인한다. 기존 작업 시각에는 실패도 포함되므로 저장 완료 시각으로 표시하지 않는다. 모델이 없더라도 개인 입력 반영은 실행하며 Gemma는 모델 관리로만 안내한다. 다운로드를 자동 시작하지 않는다. 개인 입력 통계와 삭제는 하단 개인 구역에 유지하고, 중복된 개인 입력 반영 버튼은 표시하지 않는다.

기존 금고 강화는 외부 AI 개인 그래프 분석이고 Gemma 준비는 공개 문장 생성이었다. 이 둘을 같은 실행으로 표시하지 않는다. Gemma 사용 빌드의 개인 입력 반영 버튼은 외부 AI 호출을 연달아 실행하지 않는다. 개인 금고를 Gemma로 분석하는 추가 범위는 사용자 답변을 기다리며, 현재 공개 생성 프롬프트에 개인 원문을 추가하지 않는다.

### 단일 보강 제어점

금고 상단 `언어 금고 보강` 제어점 하나에서 기존 개인 입력 반영과 Gemma 수동 예약을 각각 시작한다. 두 작업은 독립적으로 시작·표시·완료하며, 한쪽의 실패·대기·모델 부재가 다른 쪽의 실행을 막지 않는다. Gemma가 이미 실행 중이거나 `manualRequested`가 남아 있으면 Gemma 수동 예약을 중복으로 만들지 않는다. 모델이 없어도 개인 입력 반영은 사용할 수 있고, 모델 관리는 별도 행동으로 남긴다. 기존 하단의 별도 개인 입력 반영 버튼은 숨긴다. 개인 금고 원문은 Gemma 공개 문장 생성에 전달하지 않는다.

## 예약과 취소

- `enabled`는 주기 자동 보강 옵션이다. 기존 기본값 false와 저장 키를 유지한다.
- `manualRequested`는 사용자가 요청한 한 번 보강의 지속 상태다. 자동 옵션을 켜지 않고 고유 일회 작업을 예약한다. 이미 남은 요청은 중복 예약하지 않는다. 새 요청은 `APPEND_OR_REPLACE`로 예약해 이전 Worker가 완료 상태를 저장하고 반환하는 사이에도 유실되지 않게 한다. 자동 일회 예약은 기존 `KEEP`를 유지한다.
- 공개 생성 허용은 `enabled || manualRequested`로 판단한다. 모델·배터리·발열·절전·메모리·키보드 가드는 그대로 적용한다.
- 자동 옵션을 끄면 진행·예약 작업도 중지한다. `manualRequested`를 해제하고 `generationEpoch`를 증가시켜 이전 요청의 저장을 거부한다.
- 정상 일회 배치 종료·소진·오류에서는 수동 요청을 해제한다. 시스템 취소·일시적인 자원 차단은 재개 가능하게 남긴다. 사용자가 끈 요청을 재개하지 않는다.
- 화면 수명은 상태 구독에만 영향을 준다. Activity 종료가 예약 작업을 취소하지 않는다. 모델 관리의 실험용 직접 추론과 구분한다.

### 수동 실행 분량 확장

추가 사용자 요청에 따라 자동 작업과 명시적 수동 요청의 실행 정책을 구분한다. 자동은 배터리 30% 이상, 최대 4개 문맥, 새 생성 시작 예산 120초를 유지한다. 수동은 배터리 20% 이상, 최대 16개 문맥, 새 생성 시작 예산 480초다. 시간 예산은 진행 중인 추론을 강제 종료하는 제한이 아니므로 정확히 8분 안에 끝난다고 표시하지 않는다. 생성 횟수는 저장 문장 수가 아니다.

Worker는 시작 시 요청 모드를 고정한다. 명시적 수동 요청은 화면을 떠나도 수동 정책을 유지하며, 이미 실행 중인 자동 작업을 중간에 대량 실행으로 바꾸지 않는다. 절전·심한 발열·메모리 부족·키보드 활성·배터리 미확인 차단과 6회 연속 무생산 중지는 유지한다. MODERATE 발열에서는 두 모드 모두 최대 1개 문맥으로 줄인다. 품질 검증이나 저장 필터를 완화하여 수집량을 늘리지 않는다.

WorkManager는 화면을 떠난 뒤에도 유지할 작업을 지원하지만 실행 시각을 보장하지 않는다. 시스템 예약 대기를 실행 중으로 표현하지 않는다. [Android 작업 예약](https://developer.android.com/develop/background-work/background-tasks/persistent), [작업 관리](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).

## 구현 경계와 검증

Gemma runtime은 기존 debug 경계를 유지한다. main UI는 source-set별 controller를 사용하며 release에는 지원하지 않는 기능을 표시하지 않는다. 공개 생성 은행과 개인 금고의 데이터·삭제·통계 경계를 유지한다. 모델 학습이나 입력 후보 유용성 증가를 저장 수 증가로 대신 주장하지 않는다.

검증은 상태 전이 단위 검사, debug 앱·계측 APK 빌드, 에뮬레이터의 모델 없는 화면·토글·뒤로가기·200% 글꼴·어두운 화면으로 나눈다. 실제 Gemma 생성·화면을 떠난 뒤 저장·명시 중지 후 재적재 보존은 A35에서 별도로 확인했다. 이번 A35 검사는 키보드 시작 시 생성 중지 검증을 포함하지 않으며, Fold6에서도 연결 복구 뒤 독립 재확인이 필요하다.

### 통합 검증 기록

- Fold6 USB `R3CX70NE9VH` 연결을 확인했다. 기존 debug 홈 캡처 `fold6-home-before-valid.png`에서 카드 하단 테두리에 목록 구분선이 붙는 현상을 확인했다. 카드 Preference의 위·아래 divider를 비활성화하고 일반 설정 행의 구분선은 유지한다. 캡처는 `adb shell screencap` 후 `adb pull`로 보존한다.

- 첫 컴파일은 UI의 `Intent`, `BuildConfig` import 누락으로 실패했다. 누락을 수정한 뒤 재실행했다. 실패 로그도 보존한다.
- Gemma 상태 전이·실행 조건·계획 단위 검사: 28개 통과, 실패·오류·스킵 0. 새 XML 시각은 2026-09-10 18:18:55이며 Gradle 종료 코드 0이다.
- 수동 정책 확장 후 같은 검사 범위는 32개 통과, 실패·오류·스킵 0이다. `unit-policy-latest-20260910.log` 종료 코드 0을 확인했다.
- x86_64 debug 앱과 계측 APK 빌드: 종료 코드 0. release Kotlin 컴파일도 종료 코드 0이며 릴리스 APK를 생성한 것은 아니다.
- 첫 기본 UI 계측은 `CompoundButton.performClick()` 반환값을 토글 성공으로 오인한 테스트 단정에서 실패했다. [AOSP 구현](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/CompoundButton.java)은 먼저 토글하고 상위 클릭 처리 여부를 반환한다. 실제 터치 후 저장 상태·WorkManager·이동 결과를 확인하도록 검사 방법을 수정한다. 실패 후 런처가 찍힌 `ui-basic.png`는 금고 화면 증거에서 제외한다.
- 실제 터치 방식의 기본 UI 검사는 `ui-basic-latest.log`에서 `OK (1 test)`를 확인했다. 이후 `gemma-vault-basic.png`도 닫힌 금고 대신 Chrome을 캡처한 것이므로 제외한다. 금고 Activity는 외부 실행을 허용하지 않아, 최종 검사는 Activity가 열린 계측 도중 직접 PNG를 저장하도록 변경했다. 제품의 Activity 공개 범위는 바꾸지 않는다.
- 200% 글꼴에서 높이 160px인 보강 버튼의 보이는 높이가 97px로 줄어 실패했다. 진단 좌표는 버튼 y=2180, 화면 끝 2340, 보이는 끝 2277로 하단 navigation inset 63px와 일치한다. [AndroidX NestedScrollView](https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/widget/NestedScrollView.java)의 사각형 스크롤 계산은 전체 높이를 사용한다. 하단 inset을 내부 padding 대신 바깥 margin으로 반영해 실제 viewport가 navigation bar 위에서 끝나게 수정했다. 48dp 검증 기준은 유지한다.
- 같은 확대 캡처에서 마지막 분석 시각이 toolbar 제목을 밀어내는 것도 확인했다. 시각 표시를 toolbar 다음 독립 행으로 이동했다. 실패 로그·PNG는 재현 증거로 보존한다.
- 증거 디렉터리: `.artifacts/gemma-vault-unification-20260910/`.

### 최종 UI 및 전달 결과

- 기본: `ui-color-basic.log`, 야간: `ui-dark-focus-final.log`, 200%: `ui-font200-scrollfix.log`, 800dp: `ui-wide-focus-final.log`에서 각각 `OK (1 test)`를 확인했다. 실제 터치로 자동 옵션 저장·취소와 모델 관리 왕복을 검증했다. 모델 없는 환경이며 추론 성공 검사는 아니다.
- 최종 야간 검사는 창 focus/RESUMED와 터치 좌표 안정을 기다린 뒤 통과했다. 이전 `ui-color-dark.log`의 전환 중 입력 주입 실패는 남겨 두며 성공으로 집계하지 않는다.
- 에뮬레이터는 `Saegeul_Quality_20260908`, API 34, x86_64다. 검사 후 night=no, font_scale=1.0, 화면/밀도 override 없음으로 복원했다. 800dp는 조작·줄바꿈 검증이며, 기존 디자인의 최대 읽기 폭 640dp 적용은 별도 미완료 항목이다.
- Fold6 실제 화면: `fold6-home-after.png`, `fold6-vault-final.png`. 카드에 붙던 구분선 제거와 단일 금고 진입, 자동/수동/모델 관리 컨트롤을 직접 확인했다. 기존 개인 금고와 모델을 삭제하지 않았다.
- arm64 앱 SHA-256: `CD629D2EC9AFBA76D4AE881928BEBF66A0C1B51DB6FB6DBCCCC2E50F70F46E71`. `Saegeul-gemma-vault-20260910-arm64.apk`를 Fold6에 `install -r`로 설치했다.
- arm64 계측 APK SHA-256: `76D5F5C566FAFD0F80A1B0545D9E1DD8E045E10552DBAC1A4B7626756C16DBC2`. 빌드 완료이며 Fold6 설치·실행 직전에 연결이 끊겼다.
- 18:56:54 KST Taildrop으로 `z-fold6`에 `Saegeul-gemma-vault-20260910.apk` 전송 완료(`sent`, 종료 코드 0).

### A35 실기기 생성 검증

- 대상은 A35 `RFCX60GBL3D`, API 36이다. arm64 debug 앱 SHA-256 `CD629D2EC9AFBA76D4AE881928BEBF66A0C1B51DB6FB6DBCCCC2E50F70F46E71`를 `install -r`로 설치해 기존 앱 데이터를 보존했다.
- 공개 Gemma 모델은 공식 주소에서 가져온 뒤 크기 `2,588,147,712` bytes와 SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`를 확인했다. 모델을 대체하거나 개인 금고 원문을 프롬프트에 넣지 않았다.
- `manual-background.log`의 `GemmaManualBackgroundDeviceTest`는 `OK (1 test)`, 115.109초다. 자동 보강이 꺼진 상태에서 수동 요청 뒤 화면을 떠나 실행했고, 공개 문장 재료 수는 0→8, 시퀀스는 0→5가 됐다. 기록한 발열 값은 0, 배터리는 54%다. 종료 후 `manualRequested=false`, `enabled=false`, native runtime=false를 확인했다.
- `manual-background-rerun.log`의 `device not found`는 성공 결과가 아니다.
- `numeric.log`의 `NumericSymbolInputLatencyDeviceTest`는 1개 실패했다. `NumberReady=false`, 1075ms이며 원인은 아직 확인하지 못했다. 진단 검사를 추가했지만 USB 연결이 끊겨 재실행하지 못했다. 이 실패를 모델 준비·실제 Gemma 생성 성공과 섞어 완료 처리하지 않는다.
- A35 재연결 뒤 최신 제품 APK SHA-256 `5C5148993D9142D7F348FCB1C1247835CA8691C2B291AB05B62A847C05D6271D`를 `install -r`로 설치했고, 이번 제품 변경은 없었다. 최신 계측 APK SHA-256은 `EDDC675F46B23B348EB39DF7DBD0883CF3524C8F063CEFEAFB34E9BECDE7CC72`다.
- 재연결 첫 진단 `.artifacts/gemma-vault-a35-reconnect-20260910/numeric-diagnostic.log`은 1088ms로 실패했다. 저장 PNG에서 `q` 키 오터치를 확인해 입력 지연의 근거로 사용하지 않는다. 실제 키 경계를 세 번, 각 100ms 안에 안정 확인하고 숨겨진 라벨 후보를 건너뛰도록 계측을 보정했다.
- 보정 뒤 `numeric-stable.log`는 `OK (1 test)`, 8.533초다. `12+34-56*78/90=.`의 16키가 모두 정확했고 각 키 반영 시간은 29~72ms, 자판 전환은 147ms였다. 자동 보강과 native runtime은 모두 꺼진 조건이다. 이 결과는 A35의 해당 debug IME·편집기 세션과 숫자·기호 자판 첫 입력에 한정하며, 다른 앱·연속 고속 타이핑·Fold6 및 모든 입력 상황의 완료를 뜻하지 않는다.

### 단일 보강 제어점 UI 검증

- 최신 통합 UI 계측은 `.artifacts/gemma-vault-single-action-20260910/ui-single-clean-basic.log`에서 `OK (1 test)`, 8.77초이며, 200% 글꼴 검사는 `ui-single-clean-font200.log`에서 `OK (1 test)`, 7.31초다. 계측 중 직접 저장한 PNG에서 잘림과 Toast 표시가 없음을 확인했다.
- 이 UI 검증은 `vaultUiOfflineMode=true`를 명시한 모델 없는 경로로 한정한다. `finally`에서 기존 값 `false`를 복원한다. 따라서 모델 다운로드·네트워크·실제 Gemma 추론의 성공을 이 결과로 주장하지 않는다.
- 이전 온라인 `ui-single-basic` 실패는 개인 입력 반영 뒤 `AdActivity`가 떠서 focus를 덮은 것이며 logcat으로 원인을 남겼다. 광고 제품 정책은 바꾸지 않았다. 중간 오프라인 통과에서 Toast가 결과를 덮는 문제를 발견해, 통합 경로의 Toast만 제거한 뒤 최종 두 검사를 통과했다.
- 최신 통합 버튼은 A35에 데이터 보존 설치한 모델 있는 기기에서 직접 터치해 검증했다. Fold6의 최신 설치·터치 검증은 아직 없다.
- 최종 arm64 앱 `Saegeul-vault-single-action-final-arm64.apk` SHA-256은 `5C5148993D9142D7F348FCB1C1247835CA8691C2B291AB05B62A847C05D6271D`이고, 계측 APK SHA-256은 `A21BA692BBBB006F8ECDDE40F10166956A37965ACDFF5EB534205CCC563D50DB`다. arm64 빌드는 25초·종료 코드 0, release Kotlin 컴파일은 16초·종료 코드 0이다. 릴리스 APK를 생성한 것은 아니다.
- 19:57:44 KST에 Taildrop으로 Fold6 `100.109.125.97`에 `Saegeul-vault-single-action-20260910.apk`를 전송했고 `sent`, 종료 코드 0을 확인했다. 전송은 설치가 아니므로 실물폰 설치·검증 미완료 상태는 바꾸지 않는다.
- A35의 `unified-button-background.log`는 `OK (1 test)`, 101.502초다. 모델이 있는 최신 제품에서 실제 `언어 금고 보강` 버튼을 터치해 `unifiedVaultAction=true`, `manualRequested=true`, `personalResultCompleted=true`를 확인하고 HOME으로 나갔다. 이후 시퀀스는 21→26, 공개 문장 재료 수는 39→49로 10개 증가했다. 발열 값은 0, 배터리는 52%다.
- 종료 뒤 `manualRequested=false`, `enabled=false`, native runtime=false를 확인했다. 중지 뒤 재적재 보존 단정도 통과했고, `vaultUiOfflineMode`는 원래 `false`로 복원했다. 이 실물 검증은 오프라인 통합 경로에 한정하며 광고가 포함된 E2E나 모든 외부 개인 입력 반영 경로의 검증으로 확대하지 않는다.

### 재연결 후 필수 작업

Fold6 자동 보강은 검사 직전 UI로 켜짐→꺼짐 전환했다. 마지막 확인은 `enabled=false`, `manual_requested=false`, `generation_epoch=1`, `open_sequence=20`, `stored=188`이다. 직후 USB `R3CX70NE9VH`가 사라졌고 기존 무선 주소 `100.109.125.97:41475`도 연결 거부였다. 현재 A35를 Fold6로 대신 판정하지 않는다.

1. Fold6 연결 복구 후 **자동 보강 옵션을 원래 켜짐으로 복원**한다. 검사 재개 시에만 다시 잠시 끄고 종료 후 복원한다.
2. A35 재연결에서 `NumericSymbolInputLatencyDeviceTest#numericAndSymbolKeysAppendWithinOneSecondByActualTouch`의 진단·재실행은 완료했다. 진단의 `q` 오터치 실패는 보존하고, 안정화 계측에서 16개 합성 키의 정확한 입력과 키별 1초 이내 반영을 확인했다. 다른 앱·연속 고속 타이핑·기호창 `!?#`과 Fold6 재현은 이 완료 범위에 포함하지 않는다.
3. Fold6을 A35와 별개로 재연결한 뒤 `GemmaManualBackgroundDeviceTest#manualRequestContinuesAfterLeavingVault`를 다시 실행한다. 자동 꺼짐에서 명시 수동 요청 후 HOME으로 나가 최소 5회 생성과 저장 수 증가, 중지 뒤 재적재 보존을 확인한다. 실제 모델·배터리·발열 조건을 완화하지 않는다. A35 성공을 Fold6 성공으로 대신 기록하지 않는다.
4. 개인 원문을 Gemma로 분석하는 범위는 미확정이며 구현하지 않았다. 이전 독립 입력 후보 유용성 과제도 이 UI·분량 변경으로 완료 처리하지 않는다.
