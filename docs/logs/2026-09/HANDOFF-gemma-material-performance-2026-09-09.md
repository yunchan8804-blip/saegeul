# Gemma 문장 재료 성능·검증 핸드오프

작성일: 2026-09-09

> 2026-09-28 메모: 아래 "입력할 때 추론하지 않는다"는 전제는 지금 제품과 다르다. 지금은 자동 추천이 입력 중에 기기 안 Gemma로 문장을 만든다(`ec872659`, [자동 추천 기록](gemma-automatic-context-2026-09-12.md)).

> 후속 구현·검증의 현재 상태는 [성능 계측과 띄어쓰기 제안 후속 기록](gemma-performance-followup-2026-09-09.md)을 따른다. 지정 3문맥 표시·touch와 띄어쓰기 6검사는 통과했으며, 전체 20문맥 coverage와 생성 조건 비교·전체 품질 검증은 아직 미완료다. 아래 내용은 인수 시점의 기준과 실패 이력을 보존한다.

## 현재 기준

- 기준 커밋은 원격에 올라간 `ca0240b1`이며, 핸드오프 시작 시 작업 트리는 clean이었다.
- 작업 경로는 `D:\workspace\Saegul`이다. 이번 핸드오프 문서는 새로 추가했으며 아직 커밋하지 않았다.
- 작업 브랜치는 `YunChan/gemma-ondevice-materials`다. 다음 세션은 `AGENTS.md`, 관련 SSOT와 현재 branch·dirty 상태를 먼저 다시 확인한다.
- 실기기 대상은 무선 Fold6 `SM-F956N`이다. 마지막으로 사용한 주소는 `100.109.125.97:39155`지만 포트는 바뀔 수 있으므로, 다음 세션에서 `adb devices -l`로 실제 연결을 재확인한 뒤에만 사용한다. 과거 `:42039`, `:33691`, `:35223` 주소는 재사용하지 않는다.
- 이 문서는 현재 ADB 연결, 기기 열 상태, 자동 축적 스위치의 최종 복원 상태를 새로 검증하지 않았다. 특히 마지막 자동 축적 설정이 꺼짐으로 복원됐는지는 다음 세션에서 실제 상태로 확인해야 한다.

제품 방향은 입력할 때 추론하지 않고, 사용자가 동의한 DLC 모델로 공개 고정 문맥의 재료를 미리 쌓아 로컬 인덱스에서 조회하는 것이다. 모델 다운로드·가져오기에는 명시적 동의가 계속 필요하며, 개인 입력이나 언어 금고를 생성 프롬프트로 보내지 않는다.

현재 구현은 debug 전용이며 기본 꺼짐이다. 충전·배터리 15% 초과·키보드 비활성·저메모리 아님·thermal MODERATE 미만을 요구한다. WorkManager 반복은 15분, 한 실행 최대 4문맥/새 요청 시작 예산 120초, 공개 20문맥 각각 목표 4개/최대 6시도다. 저장 상한은 2,000개·1 MiB이며 중복과 구조를 검사한다. 이 계약 변경과 정식 배포 승격은 별도 설계 판단이다.

## 고정 baseline과 현재 결과

| 항목 | 기준 |
|---|---|
| 모델 | `gemma-4-E2B-it` |
| 모델 크기 | 2,588,147,712바이트 |
| 모델 SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| 런타임 | LiteRT-LM Android 0.13.1, CPU baseline, 기본 sampler, 최대 2048 tokens |
| 최종 앱 APK SHA-256 | `483861C6A476336B563FA320BF876D1683DA005786B8B7BA6935261ECE7A1BD9` |
| 최종 AndroidTest APK SHA-256 | `5D6AFA9575688CD145D3B5978D08978CCC5214F14A18DC6C1DCFB901CB0F6982` |
| 단위 테스트 | 1,135개 수집, 1,128개 통과, 기존 skip 7개, 실패·오류 0개 |

APK 파일명에 남은 `g31cd10a3` 같은 커밋 표기는 커밋 전 빌드 이름일 수 있으며, 최종 소스 baseline `ca0240b1`과 모순으로 해석하지 않는다. 설치·검증 대상 식별은 위 APK SHA-256을 우선한다.

자동 축적 D 검증과 키보드 중지 E 검증은 통과했다. D는 홈 전환 뒤 실제 자동 축적으로 저장 수가 8개에서 10개로 늘고 새 공개 문맥 하나가 증가했으며, 중지·재로드·disabled 상태 예약 차단까지 확인했다. E는 실제 추론 관측 뒤 키보드를 열어 네이티브 생성이 끝나고 저장 수 10개가 보존되는 것을 확인했다. 이는 전체 20문맥 coverage나 한국어 자연스러움의 통과 증거는 아니다.

생성 재료의 3문맥 검사에서는 `회의 자료를 ` 422ms와 `약속을 ` 417ms에 후보가 실제 표시됐고, 이후 정확한 터치 삽입도 통과했다. `오늘 저녁 `은 생성 재료가 없어 실패했다. 현재 테스트의 1초 제한은 로컬 후보 표시 시간에 적용되며, 터치 삽입 정확성은 별도 assertion이다. 삽입까지 포함한 전체 시간이 1초라고 측정한 것은 아니며, 입력 시 모델 추론 1초를 뜻하지도 않는다.

공개 20문맥 중 마지막 관측 coverage는 10/20이고, 공개 문맥별 재료 개수 합계는 22개다. 이 22개는 전체 은행 수가 아니라 공개 앞부분에 정확히 연결되는 재료만 센 합계다.

FULL과 SUFFIX 비교에서 현재 조건의 SUFFIX 제품 적용은 보류됐다. 같은 원문 재생 기준으로 조회 후보가 있는 문맥은 FULL 18/20, SUFFIX 13/20이었다. 한국어 적합·저장·앞부분 조건을 함께 만족한 후보는 FULL 32/40, SUFFIX 14/40이고, 이 적합 후보가 있으며 실제 조회도 성공한 문맥은 FULL 18/20, SUFFIX 9/20이었다. 20문맥 소표본과 단일 비블라인드 평가이므로 일반 성능 우위로 확장하지 않는다. 당시 FULL 중앙값은 초기화 528.5ms, 생성 2,958ms, 전체 호출 7,241ms였으며 이번 세션에서 새로 측정한 값은 아니다.

## 실패 이력과 해석 경계

자동 축적 D의 첫 실행 r1은 instrumentation 클래스명 오타로 실행되지 않았다. r2는 `KEYCODE_HOME` 주입이 기기에서 false를 반환해 실제 background 전환을 입증하지 못했다. r3는 `ACTION_MAIN`과 `CATEGORY_HOME` 인텐트 뒤 Gemma Activity lifecycle이 `STARTED` 아래로 내려간 것을 확인하고 통과했다. r1/r2는 통과 증거가 아니다.

전체 20문맥 coverage 검증도 별도로 세 번 실패했다.

- coverage r1은 47.211초 뒤 20문맥 coverage 미충족으로 끝났다. 이후 열 서비스에서 SKIN `MODERATE`가 관측됐다.
- coverage r2는 예약 작업이 idle로 전환되기를 기다리다 180초 제한을 넘겼다. WorkManager의 예약 시각과 실제 실행 시각 차이를 열 원인으로 단정하지 않았다.
- coverage r3는 periodic `RUNNING`과 실제 모델 실행을 관측했지만 약 29초부터 891.785초까지 thermal 상태 `2`(MODERATE)와 `기기 온도가 높아 생성을 미룹니다.` 상태가 지속돼 900초 deadline에 실패했다.

배터리 온도나 기기 발열 하나만으로 모든 성능 문제를 단정하지 않는다. 앱의 thermal·충전·배터리·메모리·키보드 guard를 유지한다. 사용자가 이전에 보고한 약 20초 앱 시작, 언어 금고 진입 지연과 키보드 프리징 전체를 이번 검증으로 해결했다고 주장하지 않는다. 재현되면 생성 비용과 별도로 화면 준비 시간·메인 스레드 정체를 측정한다.

## 파일 지도와 증거

| 영역 | 주요 파일 |
|---|---|
| 모델 다운로드·가져오기·무결성 | `app/src/debug/java/org/fcitx/fcitx5/android/debug/gemma/GemmaModelFiles.kt` |
| 생성·취소·native close | `app/src/debug/java/org/fcitx/fcitx5/android/debug/gemma/GemmaMaterialGenerator.kt` |
| 자동 축적 상태·계획·예약·worker | `GemmaAccumulationStore.kt`, `GemmaAccumulationPlanner.kt`, `GemmaAccumulationScheduler.kt`, `GemmaAccumulationWorker.kt` |
| 실험 화면 | `GemmaExperimentActivity.kt`, `app/src/debug/AndroidManifest.xml` |
| 암호화 은행·형식·중복 | `app/src/main/java/org/fcitx/fcitx5/android/input/ai/ondevice/GeneratedSentenceBank.kt` |
| 공개 20문맥 정책·로컬 조회 | `GeneratedMaterialPolicy.kt`, `ImmediateContextualPredictions`, `FcitxInputMethodService`의 `ondevice_generated` 경로 |
| 자동 축적 실기기 검증 | `app/src/androidTest/java/org/fcitx/fcitx5/android/GemmaAccumulationDeviceTest.kt`, `GemmaAccumulationCoverageDeviceTest.kt` |
| 1초 표시·터치 검증 | `app/src/androidTest/java/org/fcitx/fcitx5/android/ImmediateSentenceLatencyDeviceTest.kt` |

보존할 로컬 증거는 다음과 같다.

- `.artifacts/gemma-accumulation-20260909/`: `integrated-r1`~`integrated-r6` 빌드 로그, `device-accumulation-r1`~`r3`, `device-keyboard-r1`, `device-latency-r1`, `device-coverage-r1`~`r3`의 `.log`와 `.exit`, 새글 화면 캡처. ADB exit 0이어도 instrumentation 내부 `FAILURES!!!`이면 실패다.
- `.artifacts/gemma-experiment-20260909/`: CPU r1~r3, UI 후보·저장 관측, SDK 0.13.1 AAR 검사, 초기 통합 빌드 로그.
- `.artifacts/gemma-comparison-20260909/`: FULL/SUFFIX 40회 생성·40회 재생의 `rows.json`, summary와 quality summary. 원문 80건의 보수 한국어 판정 표는 `docs/gemma-continuation-comparison-2026-09-09.md`에 있다.
- 기준 문서는 `docs/gemma-ondevice-experiment-2026-09-09.md`, `docs/gemma-material-accumulation-2026-09-09.md`, `docs/gemma-continuation-comparison-2026-09-09.md`다.

원시 session 로그, 개인 앱 화면 캡처, 개인 입력·금고 내용은 읽거나 새 문서에 복사하지 않는다. 모델 파일, APK, 로그·캡처는 Git에 커밋하지 않는다.

## 다음 세션 실행 순서

1. `AGENTS.md`와 위 기준 문서를 읽고, `git status --short`, 현재 branch, 최신 commit을 확인한다. 이어 `adb devices -l`로 현재 Fold6 연결을 확인한다. 연결 주소나 thermal 상태는 이 문서의 과거 관측으로 가정하지 않는다.
2. 동일 Gemma 4 E2B·LiteRT-LM 0.13.1·CPU baseline에서 wall 시간, 준비 시간(해시 검증과 초기화), 추론, close, 은행 저장 시간을 각각 분리 계측한다. `GemmaMaterialGenerator`는 `requireVerifiedModel`을 initialization 시작 시각보다 먼저 호출하고, 호출마다 검증·Engine 생성·initialize·finally close를 수행한다. 따라서 기존 `initializationMs`만으로 해시 검증 비용을 판단하지 않는다. 무결성 검증을 삭제하는 방안은 해결책으로 확정하지 않는다. 실행 중 thermal 상태, RAM, thermal 제한 도달 시간과 accepted unique material 수를 함께 기록해 유효 재료 1개당 wall/prep/inference/close/save 비용을 계산한다. 은행의 문장 본문은 증거에 출력하지 않는다.
3. 한 회 생성량과 실행 간격을 한 변수씩만 바꿔 비교한다. 모든 guard와 취소·close 계약을 유지한다. warm reuse는 RAM 점유, 취소 비용, close 지연을 함께 비교하기 전에는 채택하지 않는다.
4. 더 작은 모델 후보가 필요하면 먼저 공식 모델 카드, 라이선스, 한국어 지원, LiteRT-LM Android 런타임·artifact 호환성을 조사한다. 특정 새 모델은 이 핸드오프에서 선정하지 않았으며, root가 조사 근거를 보고 선택한다.
5. 실제 공개 20문맥 coverage, 3문맥의 `ondevice_generated` 1초 touch 게이트, 키보드 시작 시 중지, 재료·사용자 데이터 보존을 재검증한다. 자연스러움은 형식·성능 게이트와 분리해 원문을 검토한다.

## 재개 명령과 검증 기준

다음 명령은 주소를 현재 `adb devices -l`에서 재확인한 뒤에만 사용한다. 과거 주소는 넣지 않는다.

```powershell
$adb = (Get-Command adb -ErrorAction Stop).Source
$serial = "100.109.125.97:39155"
& $adb connect $serial
& $adb devices -l
& $adb -s $serial get-state
& $adb -s $serial shell getprop ro.product.model
& $adb -s $serial shell dumpsys thermalservice | Select-String -Pattern '^Thermal Status:'
```

`get-state`가 `device`, 모델이 `SM-F956N`인지 확인한다. 마지막 로컬 adb 경로는 `C:\Users\encep\bin\adb.cmd`였다. 연결 거부·offline이면 같은 구포트를 반복하지 말고 현재 무선 디버깅 화면의 IP·포트를 확인한다. 기기 내부 모델은 `no_backup/gemma/model.litertlm`에 보존했으므로 앱 데이터를 지우거나 모델을 다시 내려받는 것으로 시작하지 않는다.

빌드는 JDK 17에서 실제 변경 범위에 맞게 최소 명령을 먼저 실행하고, 통합 시 한 번만 전체 확인한다.

APK 출력은 `app/build/outputs/apk/debug/`와 `app/build/outputs/apk/androidTest/debug/`다. 새로 빌드했다면 해당 빌드의 arm64-v8a 앱과 AndroidTest APK를 해시로 식별해 각각 `adb -s <현재주소> install -r <APK경로>`로 설치하고 설치 성공을 확인한 뒤 테스트한다. 과거 파일명의 APK를 새 빌드로 착각하지 않는다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PbuildABI=arm64-v8a
```

자동 축적과 전체 coverage의 실제 검증 명령은 다음과 같다.

```powershell
& $adb -s $serial shell am instrument -w -r `
  -e class 'org.fcitx.fcitx5.android.GemmaAccumulationDeviceTest#accumulateInBackgroundAndPausePreservingMaterials' `
  net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner

& $adb -s $serial shell am instrument -w -r `
  -e class 'org.fcitx.fcitx5.android.GemmaAccumulationCoverageDeviceTest#accumulateCoverageAcrossPublicPrefixes' `
  net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner

& $adb -s $serial shell am instrument -w -r `
  -e class 'org.fcitx.fcitx5.android.GemmaAccumulationKeyboardDeviceTest#keyboardStopsActiveAccumulation' `
  net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner
```

입력 중 추론 없이 저장 재료를 보여 주고 실제 터치 삽입까지 검증하는 명령은 다음과 같다.

```powershell
& $adb -s $serial shell am instrument -w -r `
  -e candidateSource ondevice_generated `
  -e tapMode touch `
  -e class 'org.fcitx.fcitx5.android.ImmediateSentenceLatencyDeviceTest#sentencePackCandidatesAppearWithinOneSecondAndAppendExactlyOffline' `
  net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner
```

완료 조건은 다음을 모두 만족하는 실제 증거다.

- baseline과 변경 조건별로 wall, 해시 검증, 초기화, 추론, close, 은행 저장 시간, thermal/RAM, thermal 제한 도달 시간, accepted unique material 기준 유효 재료 1개당 비용을 분리한 기록이 있다.
- 비교는 한 변수씩 바꾸고 guard·취소·데이터 보존 경계를 유지한다.
- 공개 20문맥 각각에 재료가 최소 하나 있고 재로드 뒤에도 보존된다.
- `회의 자료를 `, `오늘 저녁 `, `약속을 ` 세 문맥이 `ondevice_generated` source로 1초 안에 실제 표시되고, touch로 정확히 한 번 삽입된다. 표시 시간과 삽입 정확성을 각각 기록한다.
- 키보드 활성과 자동 축적 중지가 native 생성과 이후 저장을 막고, 기존 사용자 재료와 설정은 보존된다.
- 한국어 자연스러움은 별도 검토에서 조사·어미·띄어쓰기·문맥 호응까지 판단한다.

## 협업과 금지 사항

root는 요구 해석, 성능 측정 설계, 모델 선정, 개인정보·동시성 경계, 워커 결과 diff 리뷰와 최종 수용 판단을 직접 맡는다. explorer/researcher는 공개 소스·공식 문서·현재 코드 근거만 수집하며 원시 session 로그와 개인 앱 캡처를 읽지 않는다. 구현 worker는 root가 확정한 파일 경계와 계약 안에서만 코드를 바꾸고, verifier는 승인된 빌드·테스트·기기 명령의 원문만 수집한다. 워커는 설계·모델 선정·검증 결론을 바꾸지 않는다.

초기 코드 비용 탐색과 작은 모델 공식 조사는 독립 워커로 병렬 진행한다. root가 계측 설계를 확정한 뒤 runtime 구현은 `debug/gemma/**` 담당 한 명에게 맡긴다. 은행 정책 변경은 `input/ai/ondevice/**` 담당으로 분리하며 인터페이스 변경은 root가 먼저 확정한다. 같은 기기의 ADB 실행과 Gradle 빌드는 검증 워커 한 명이 순차 수행해 측정을 섞지 않는다. 모든 패킷에 비목표·금지 영역·검증 명령을 넣고, root가 diff와 원문 결과를 읽어 수용/재작업을 판정한다.

다음 행동은 금지한다.

- 개인 입력·금고를 프롬프트에 넣거나 비공개 설정·로그를 광범위하게 수집하는 행위. 기존 모델의 설치·생성·ADB 검증은 이번 작업에서 승인된 범위이며 같은 승인을 반복해서 묻지 않는다. 신규 모델의 다운로드·라이선스·데이터 경계는 선택 시 별도로 확정하고 제품의 다운로드 동의 흐름을 유지한다.
- 앱 데이터 clear, 은행 삭제, 모든 preferences clear, 가짜 모델·가짜 충전/thermal 상태·mock 성공값으로 검증을 통과시키는 행위.
- 모델·APK·개인정보 가능 로그·캡처를 커밋하거나, 서브모듈을 이 저장소에서 직접 수정하는 행위.
- 관측한 thermal 제한을 모든 성능 문제의 원인으로 일반화하거나, 기존 앱 시작·금고 진입 지연까지 해결했다고 주장하는 행위.
- 한 번의 추론 시간이 짧다는 이유만으로 지속 가능한 성능이라고 판정하는 행위.
- root 검토 전 조건 완화, fallback, silent catch, 테스트 skip으로 실패를 통과로 바꾸는 행위.
