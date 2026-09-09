# Gemma 온디바이스 재료 생성 실험

## 확정 설계

기준 커밋은 `615181f4`, 실험 브랜치는 `YunChan/gemma-ondevice-materials`다. 이번 기능 추가는 디버그 실험이며 정식 출시 계약 변경이 아니다.

1. Gemma 4 E2B 모델은 APK에 포함하지 않는다. 사용자가 실험 화면에서 용량·출처 안내를 확인하고 다운로드하거나 이미 받은 파일을 명시적으로 가져온다. 모델을 사용할 때마다 입력창 내용을 전송하지 않는다.
2. 추론은 명시적 생성 동작에서만 실행한다. 첫 실험은 고정된 비개인적인 한국어 일상·업무 주제로 한정한다. 개인 금고 자동 읽기와 무제한 백그라운드 생성은 범위 밖이다.
3. 생성된 완성 문장을 검증해서 별도의 암호화 재료 저장소에 모델 출처와 함께 저장한다. 실제 사용자가 작성한 문장으로 가장하지 않는다. 기존 공통 문장팩과도 분리한다.
4. 키보드는 메모리 인덱스에서 재료를 조회한다. IO·모델 초기화·추론은 후보 조회에서 금지한다. 기존 prefix/suffix 및 어절 연결 규칙을 재사용하되 생성 재료는 약한 마지막 단어 일치만으로 노출하지 않는다.
5. 디버그 실험의 자동 실시간 prefetch는 비활성화하고 수동 AI 요청은 유지한다. 추론 종료 뒤에도 여러 입력 prefix에서 같은 저장 재료를 재사용하는지 검증한다.
6. 모델은 실행 종료 때 닫는다. 중복 생성은 막고 취소·화면 이탈 때 cancelProcess를 요청한다. 취소한 결과는 저장하지 않는다. 키보드가 나타나면 생성 중단을 요청해 입력을 우선한다.

## 공급물 고정

- SDK: `com.google.ai.edge.litertlm:litertlm-android:0.13.1`, debugImplementation. 현재 Kotlin 2.3.21·minSdk 23과 호환되는 메타데이터와 minSdk를 AAR에서 확인했다. 실제 E2B 실행 호환성은 실기기 검증 대상이다.
- 모델: `litert-community/gemma-4-E2B-it-litert-lm/gemma-4-E2B-it.litertlm`.
- SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`.
- 크기: 2,588,147,712바이트. Gallery allowlist의 다른 크기 대신 다운로드 본문 해시와 원본 HEAD 응답을 확인했다.
- 출처: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm . Apache 2.0. 실행용 가중치는 Git에 넣지 않는다.
- 모델 초기화·문맥 처리·생성 시간, 최대 메모리, 한국어 품질은 별도 측정한다. 타 기종 벤치마크를 이 기기의 결과로 대체하지 않는다.

## 변경 순서와 경계

| 단계 | 파일 경계 | 검증 |
|---|---|---|
| A | 새 ondevice 재료 저장소와 파서 | 저장 실패 보존, 재로드, PII/잘못된 출력 거부, 문맥/띄어쓰기 |
| B | debug 모델 관리·실행·실험 화면, app Gradle/debug manifest | 고정 SDK 컴파일, SHA 검증, 명시적 다운로드, 취소·닫기 |
| C | Application·IME·즉시 후보 수집 | 준비된 메모리만 조회, 세션/프라이버시 경계, 전후 후보 보존 |
| D | androidTest | 실제 모델 생성→영구 저장→모델 종료→재로드→추천 표시·선택·지연 |

A와 B는 병렬, C는 A 계약에 의존한다. 모델 생성이 실패하면 실패를 표시하고 이전 재료를 유지한다. 허구의 기본 응답이나 고정 성공값으로 성공을 꾸미지 않는다. 전체 리팩터나 임의 문장의 완벽한 생성은 이번 실험의 완료 조건이 아니다.

## 완료 증거

구현과 전체 단위 테스트, debug 앱·테스트 APK 빌드는 통과했다. 기기 설치, 실제 모델 생성 및 ADB E2E는 연결 기기가 0대여서 미완료다.

- JDK 17, `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PbuildABI=arm64-v8a`: `BUILD SUCCESSFUL in 45s`, exit 0.
- 새 XML 집계: 1,122건 수집, 1,115건 통과, 실패 0, 오류 0, 기존 스킵 7. 암호화 저장 실패 시 데이터 보존과 문맥 조회 테스트를 포함한다.
- 최초 통합 검사에서 AndroidTest의 `load().size` 컴파일 오류를 발견했다. `load()` 후 `sentenceCount`를 읽도록 수정했으며 검사 생략 없이 전체 명령을 재실행했다.
- 원문은 `.artifacts/gemma-experiment-20260909/gemma-integrated-build-r1.log`, `gemma-integrated-build-r2.log`에 보존한다. 모델과 로그는 Git에 넣지 않는다.
- 앱 APK는 `app/build/outputs/apk/debug/`, 테스트 APK는 `app/build/outputs/apk/androidTest/debug/`에 있다. 커밋 전 변경 상태에서 빌드했으므로 파일명에 표시된 기준 SHA `615181f4`만으로 실험 코드가 없다고 판단하지 않는다.

## 현재 구현 근거와 미검증 경계

- 구현 경계는 `GemmaModelFiles`, `GemmaMaterialGenerator`, `GeneratedSentenceBank`, `ImmediateContextualPredictions`, `FcitxInputMethodService`의 `ondevice_generated` source 연결이다. 모델 생성·검증·저장·재로드·로컬 prefix 조회 API가 소스에 존재하지만, 이 문서에서는 실행 성공을 주장하지 않는다.
- 단위 테스트와 APK 빌드는 통과했다. 모델 로딩·CPU generation, 실제 후보 표시·터치 및 latency는 실기기에서 별도로 통과해야 한다. SDK 0.13.1과 이 모델의 실제 실행 호환성도 아직 확인하지 못했다.
- 모델 파일은 확보돼 있으나 문서에는 특정 사용자 경로를 저장하지 않는다. 로컬 파일은 `MODEL_PATH` 환경변수 또는 사용자가 지정한 SAF `modelPath`로 표현한다. 기준 파일명은 `gemma-4-E2B-it.litertlm`이다.
- 확보된 모델 기준 SHA-256은 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, 크기는 `2,588,147,712`바이트다. 이 값은 기기 설치·실행 증거가 아니다.

- [x] SDK를 포함한 디버그 APK 빌드
- [ ] 실제 기기의 모델 로딩과 한국어 생성
- [ ] 생성 실패·취소 뒤 기존 재료 보존
- [ ] 생성 재료 저장·재로드와 입력 문맥별 조회
- [ ] 추론 없이 추천 표시 1초 이내 및 실제 선택 결과

## 실기기 재개 절차

1. 유선 기기 serial을 확인한다. 예: `adb devices -l`. 기기가 없으면 이후 단계는 실행하지 않고 미연결로 남긴다.
2. 데이터 보존 조건으로 debug APK와 test APK를 설치한다. 기존 앱 데이터를 삭제하거나 clear-data를 사용하지 않는다. 현재 실행 가능한 명령은 다음과 같으며, 변수 값은 실제 산출물 경로와 유선 serial로 채운다.

   ```powershell
   $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
   $serial = "<wired-serial>"
   $appApk = "<path-to-debug-apk>"
   $testApk = "<path-to-debug-test-apk>"
   & $adb -s $serial install -r -t $appApk
   & $adb -s $serial install -r -t $testApk
   & $adb -s $serial shell am start -n "net.chanpaca.saegeul.debug/org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity"
   ```

   `net.chanpaca.saegeul.debug`는 `ProductIdentity.applicationId`에 debug suffix가 적용된 실제 debug applicationId이고, Activity component는 `app/src/debug/AndroidManifest.xml` 선언과 같다.
3. PC에 확보한 모델은 고유 파일명으로 Download에 복사할 수 있다. 특정 사용자 경로는 문서에 쓰지 않고 `MODEL_PATH`로 표현한다. 복사 뒤 debug 앱의 `org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity`에서 SAF 가져오기를 선택한다. 소스상 모델은 `noBackupFilesDir/gemma/model.litertlm`에 저장되며 SHA-256·크기 검증을 거친다. 복사본과 내부 모델을 함께 보관할 수 있는 약 5.2 GB 이상의 여유 공간을 먼저 확인한다.

   ```powershell
   $modelPath = $env:MODEL_PATH
   $deviceModelPath = "/sdcard/Download/saegeul-gemma-$([guid]::NewGuid().ToString('N')).litertlm"
   & $adb -s $serial push $modelPath $deviceModelPath
   # 이후 Gemma 재료 실험 화면에서 SAF로 위 고유 파일명을 선택
   ```

   `adb run-as` stream 절차는 아직 검증하지 않았으므로 이 문서의 재개 명령에 포함하지 않는다.
4. instrumentation runner는 `androidx.test.runner.AndroidJUnitRunner`이며 test package는 `net.chanpaca.saegeul.debug.test`다. 대상 생성 테스트는 `org.fcitx.fcitx5.android.GemmaMaterialDeviceTest#generatePersistReloadAndQueryLocalMaterial`이다. 기본 `backend=cpu`로 실행한다.

   ```powershell
   & $adb -s $serial shell am instrument -w -r `
     -e backend cpu `
     -e class 'org.fcitx.fcitx5.android.GemmaMaterialDeviceTest#generatePersistReloadAndQueryLocalMaterial' `
     net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner
   ```
6. 실제 CPU generation test에서 모델 해시·크기, 초기화/생성 시간, 생성 결과 비공백, 생성기 종료, 암호화 bank 저장·재로드, 고정 prefix 3개 조회와 조회 1초 게이트를 기록한다. 이 테스트의 evidence scope는 코드상 UI 표시 증명이 아니다.
7. 그 다음 debug 앱이 실제로 제공하는 새글 IME에서 후보의 `candidateSource=ondevice_generated`를 확인하고, 실제 후보 chip 표시·터치·정확히 한 번 삽입 및 입력 반응 시간을 `ImmediateSentenceLatencyDeviceTest#sentencePackCandidatesAppearWithinOneSecondAndAppendExactlyOffline`로 검증한다. 이 테스트는 `candidateSource` 인자로 generated bank를 선택하고 `tapMode=touch`로 실제 touch 경로를 선택한다.

   ```powershell
   & $adb -s $serial shell am instrument -w -r `
     -e candidateSource ondevice_generated `
     -e tapMode touch `
     -e class 'org.fcitx.fcitx5.android.ImmediateSentenceLatencyDeviceTest#sentencePackCandidatesAppearWithinOneSecondAndAppendExactlyOffline' `
     net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner
   ```

### 재개 대상 component와 소스 근거

- debug Activity: `org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity` (`app/src/debug/AndroidManifest.xml`).
- model import/verification: `GemmaModelFiles.importFrom`, `GemmaModelFiles.requireVerifiedModel`, `GemmaModelFiles.modelFile`.
- generation: `GemmaMaterialGenerator.generate(modelFile, useGpu = false)`.
- AndroidTest: `GemmaMaterialDeviceTest#generatePersistReloadAndQueryLocalMaterial`.
- debug 생성 재료 source: `ImmediateContextualPredictions`의 `ondevice_generated`와 `FcitxInputMethodService` snapshot/metrics 경로.
