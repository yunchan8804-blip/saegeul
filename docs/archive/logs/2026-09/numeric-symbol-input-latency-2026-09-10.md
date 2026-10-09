# 숫자·특수문자 입력 지연 후속 기록 (2026-09-10)

## 상태

사용자가 일반 숫자·특수문자 입력에서 심각한 지연을 제보했다. A35 재연결에서 숫자·기호 자판의 16키 실제 터치 검증은 통과했다. 이 결과는 해당 debug IME·편집기 세션의 첫 입력에 한정하며, 다른 앱·연속 고속 타이핑·Fold6와 모든 입력 상황의 완료 판정은 아니다.

## 확인된 기준 증거

- `outputs/numeric-symbol-latency-20260910/baseline-unit-corrected.log`은 두 suite의 29개 테스트를 14초에 통과시켰고, `baseline-unit-corrected.exit`는 `0`이다.
- JUnit XML 기준 `AiContextualPredictorTypoTest` 9개와 `KoreanTypoCorrectionEngineTest` 20개 모두 failures/errors/skips가 0이다.
- 최초 baseline 실패는 기존 네 자리 숫자 학습 거부 정책을 무시하고 `2026`에서 `2027`로 바뀌기를 기대한 테스트 오류였다. fixture를 `123`에서 `124`로 고쳤으며 정책 변경은 없다.
- 기준 앱은 `outputs/numeric-symbol-latency-20260910/pre-fix-app.apk`로 보존했다. SHA-256은 `A843D4B7073628171FA3990FCE84E53632284AF9823689A2202B1128C884963A`이며 당시 설치본과 같다.
- 구현 diff는 수용됐다. 첫 통합 시도는 `outputs/numeric-symbol-latency-20260910/integrated-build.log`에서 20초 후 `BUILD FAILED`, exit `1`로 끝났다. 새 instrumentation이 coroutine 밖에서 suspend `load`를 호출한 컴파일 오류가 2곳 있었다.
- 이 실패에서는 unit test task가 실행 전 중단됐다. 따라서 `integrated-test-results`의 29개 XML과 당시 test APK는 이전 빌드 잔존물이며 첫 통합 결과가 아니다. 이후 `runBlocking` 수정 뒤 재실행했다.
- 재시도 `integrated-build-retry.log`는 `BUILD SUCCESSFUL in 20s`, exit `0`이다. 56개 중 55개 통과, failures/errors 0, 기존 Ignore 1개다. 집중 변경 3 suite는 9+21+17=47개를 모두 통과했다.
- Ignore는 `AiContextualPredictorPersonalNgramTest.userPhraseSourceScoresHigherThanSyntheticLlmAtSameScore`이며 HEAD에도 같은 `@Ignore`가 있다. 이번 수정으로 skip을 추가하지 않았다.
- app 및 androidTest APK 빌드도 성공했다. app SHA-256은 `BAF1939B3CC7ED58ECA8179775DB560F0AD6F6653A688638A1804734FA83C78D`, test APK SHA-256은 `BE88A5B4ADE1F2892BC775C1DBC5028BA8CFF28F14BE081BDECA1D4659C41B8B`다.

## 구현 범위와 불변 조건

전용 numeric/phone 필드는 cursor IPC보다 먼저 차단한다. legacy no-letter typo 스캔과 keyboard-aware fuzzy의 no-letter 경로는 제외하되, 명시적인 개인 교정은 유지한다. `PersonalNgram.complete`는 접두부 인덱스가 비어 있으면 전체 어휘·점수 구축을 생략하고, base vocabulary completion이 없으면 문맥 점수를 계산하지 않는다.

개인정보·저장 경계와 Gemma 자동 준비/native 생성 자원 가드는 완화하지 않는다. 빌드와 단위 테스트 통합 검증은 통과했다. A35 재연결에서 최신 APK를 데이터 보존 설치하고 숫자·기호 자판의 실기기 측정을 마쳤으며, 자동 보강과 native runtime은 모두 꺼진 상태였다.

## A35 재연결 실기기 측정

대상 A35는 `RFCX60GBL3D`다. 최신 제품 APK SHA-256 `5C5148993D9142D7F348FCB1C1247835CA8691C2B291AB05B62A847C05D6271D`를 `install -r`로 설치해 기존 데이터를 보존했다. 이번 설치에서 제품 변경은 없었다. 최신 계측 APK SHA-256은 `EDDC675F46B23B348EB39DF7DBD0883CF3524C8F063CEFEAFB34E9BECDE7CC72`다.

첫 재연결 진단 `.artifacts/gemma-vault-a35-reconnect-20260910/numeric-diagnostic.log`은 1088ms 실패다. PNG에서 `q` 키 오터치를 확인했으므로 이 결과를 입력 지연의 근거로 사용하지 않는다. 실제 키 경계를 세 번, 각 100ms 안에 안정 확인하고 숨겨진 라벨 후보를 건너뛰게 보정했다.

보정된 `NumericSymbolInputLatencyDeviceTest`는 같은 editor/session에서 `numeric-stable.log`로 측정했다. `OK (1 test)`, 8.533초이며 `12+34-56*78/90=.`의 16키가 모두 정확했다. 키별 반영은 29~72ms, 자판 전환은 147ms였다.

키별 1초는 측정 상한이며 제품 지연 목표가 아니다. 이 결과는 A35의 해당 debug IME·편집기 세션과 숫자·기호 자판 첫 입력에 한정한다.

## 측정 방법

실제 키 touch `DOWN`부터 editor의 정확한 텍스트 반영까지 `12+34-56*78/90=.`의 16키를 각각 기록한다. A35 재연결에서는 사용자 데이터를 보존하기 위해 보존한 기준 앱으로 되돌리지 않았고, 최신 앱을 `install -r`로 설치해 이 측정을 수행했다. 따라서 이번 실기기 수치는 기준 앱과의 현장 전후 비교가 아니라 최신 앱의 제한된 실제 입력 측정이다.

초기 IME 준비는 별도 10초, main 조회는 별도 5초 timeout이다. 자동 준비와 native 생성은 시작·종료 모두 꺼져 있어야 하며, 실제 editor와 session 일치도 확인한다.

Gradle manifest 기준 target 앱은 `net.chanpaca.saegeul.debug`, test 패키지는 `net.chanpaca.saegeul.debug.test`, runner는 `androidx.test.runner.AndroidJUnitRunner`다. `<ADB_SERIAL>`은 실행 전에 확인한 실제 endpoint로 교체한다.

```powershell
$adb = 'C:\Users\encep\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s '<ADB_SERIAL>' shell am instrument -w -r -e class 'org.fcitx.fcitx5.android.NumericSymbolInputLatencyDeviceTest#numericAndSymbolKeysAppendWithinOneSecondByActualTouch' net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner
```

실행 전후 APK, `OK (1 test)` 또는 `FAILURES!!!`, exit code, instrumentation JSON은 `outputs/numeric-symbol-latency-20260910`에 보존한다.

## 남은 검증 조건

- A35의 숫자·기호 자판 첫 입력 16키 검사와 기기 데이터 보존 설치는 완료했다.
- 장문·연속 고속 타이핑, 다른 앱의 editor, 기호창 `!?#` 추가 입력은 별도 검증 대상이다.
- Fold6에서는 최신 앱 설치와 같은 숫자·기호 입력 재현이 아직 없다.

초기 `q` 오터치 실패는 보존한다. 안정화 계측 통과만으로 모든 현장의 멈춤 원인이 해결됐다고 주장하지 않는다.

## Fold6 재현과 금고 암호 수정(2026-09-11)

TDD 순서로 진행했다. Fold6 `SM-F956N` / `R3CX70NE9VH`(유선, 접힌 상태, Android 16)에 최신 앱·계측 APK를 데이터 보존 설치하고 `NumericSymbolInputLatencyDeviceTest`를 실행했다.

**레드 1(전제 조건)**: 기기의 Gemma 자동 준비 `enabled=true`와 기본 입력기가 HoneyBoard라서 각각 실패했다. 측정을 위해 자동 준비를 임시 해제하고 debug IME로 전환했고, 원본 `gemma_accumulation_state.xml`은 `outputs/numeric-symbol-latency-fold6-20260910/gemma_accumulation_state.before.xml`에 보존했다.

**레드 2(계약 불일치)**: `?123` 키는 `KeyboardWindow.switchLayout`이 마지막 기호 표면(`last_symbol_layout`)을 다시 여는 제품 계약인데, Fold6 저장값이 `Symbol`이라 기호 피커가 열렸고 넘패드 키를 기다리던 테스트가 실패했다(A35는 기본값 `Number`라 통과했던 것). 테스트가 측정 전에 표면을 Number로 고정하고 종료 시 복원하도록 수정했다(원본값 보존 확인: `Symbol`).

**레드 3(진짜 결함 — 사용자 보고 재현)**: 수정 후에도 8~9번째 키에서 메인 스레드가 5초 이상 멈췄다(`onMain` 5000ms 타임아웃, 두 번 재현). Samsung Good Catch 스레드 덤프와 keystore2 watchdog 원문이 원인을 고정한다.

- 메인은 `HorizontalCandidateComponent.renderCandidates → getContextualCandidateSnapshot → getRawContextualPredictions → activePreeditForContextualInput → fcitx.runImmediately` 경로에서 멈췄다.
- 워커는 `contextualPredictor` lazy 초기화에서 `SynchronizedLazyImpl` 최대 12.4초 대기, `CorrectionPatternStore.save()` 모니터 6.1초 점유(`Long monitor contention ... for 6.023s`/`6.100s` 원문)였다.
- 근본 원인: `KeystoreVaultCipher`가 StrongBox(SPU) 키를 우선 생성하고, 추출 불가 키라 금고 파일을 **저장·조회할 때마다** 하드웨어 연산을 수행한다. 이 기기에서 SPU 연산 1회가 `createOperation` 2.3초 + 32KB `update`마다 1.2~2.5초로 실측됐다(keystore2 watchdog, uid 10894 STRONGBOX).

**수정(그린)**: 봉투 암호화로 전환했다.

- `EnvelopeVaultCipher`(신규): 파일 암호화는 프로세스당 한 번 생성한 무작위 소프트웨어 DEK로 수행하고, DEK는 하드웨어 키로 1회 랩핑해 블록에 함께 저장한다. 하드웨어 연산은 프로세스당 랩핑 1회 + 미래 갱신 언랩 1회로 한정된다. 디스크에는 DEK가 감싸진 형태로만 존재하므로 기기 바운드 저장 경계는 유지된다.
- `KeystoreVaultCipher`: 새 키는 StrongBox를 요구하지 않는 TEE 하드웨어 키로 별칭 `saegeul.vault.v2`에 생성한다. 기존 사용자 데이터는 v1 StrongBox 키 폴백으로 읽는다(봉투 SGW1 블록과 봉투 이전 직접 암호화 블록 모두). 파일은 저장 시점에 v2 형식으로 재암호화된다.
- 단위 테스트 `EnvelopeVaultCipherTest` 6건(왕복, 랩핑 1회, 구직접 형식 호환, 프로세스 재시작 언랩 1회, AAD·무결성 거부, 형식 헤더 판별)을 먼저 추가해 레드를 만들고 구현했다. `--tests` 클래스 필터는 이 환경에서 "No tests found"로 동작하지 않아 전체 `:app:testDebugUnitTest`로 검증했다: 1,241개 중 실패·오류 0, 기존 스킵 7, `BUILD SUCCESSFUL`(34초).

**실기기 그린 계측**(수정 APK 설치 후 2회 연속 `OK (1 test)`):

| 실행 | 자판 전환 | 키별 반영 | 판정 |
| --- | --- | --- | --- |
| run3 | 149ms | 16키 모두 24~55ms | 통과 |
| run4 | 118ms | 16키 모두 22~79ms | 통과 |

에디터 길이가 8에 닿을 때마다 5초씩 멈추던 재현이 사라졌다. 이는 Fold6의 해당 debug IME·editor 세션 첫 입력에 한정한다.

- 수정 앱 SHA-256: `d046eb691fd1fd019413ff1ce9eb55cdab69e3df187169eec27441e7c378fcaf`.
- 수정 계측 APK SHA-256: `ba1573f3781c9a22a9c1d3f47248a401d5f196fc280c22c1d4327a40a21824b0`.
- 원문은 `outputs/numeric-symbol-latency-fold6-20260910/final-sha256-v2.txt`에 있다.

증거 원문은 `outputs/numeric-symbol-latency-fold6-20260910/`에 보존했다: 레드 계측 로그(`run5-8`, `final-instrumentation*.log`), 전체 logcat(`run8-logcat-full.log`, `final-run2-logcat.log`), 실패 스크린샷, 단위 테스트 로그, 수정 전후 설정 XML, 최종 SHA-256.

측정 후 기기 상태를 원복했다: 자동 준비 `enabled=true` 복원, 기본 입력기 HoneyBoard 복원, `last_symbol_layout=Symbol`은 테스트가 스스로 복원했다. 새글 debug IME는 설정에서 다시 선택할 수 있다.

**남은 조건(갱신)**: 위 두 실기기 통과로 이 문서의 A35·Fold6 16키 재현은 완료다. 여전히 별도 검증 대상인 것: 장문·연속 고속 타이핑, 다른 앱 editor, 기호창 `!?#` 추가 입력, 펼친 폴드 자세. 또한 이번 수정은 금고 암호 경계를 다루므로 SSOT의 개인정보·데이터 경계 절과 `docs/independent-fork/privacy-data-safety.md` 선언과 어긋나지 않는지 검토가 필요하다(선언 자체는 변경하지 않았다). 커밋·push는 하지 않았다.

## A35 수정 앱 회귀 검증(2026-09-11)

같은 수정 APK(앱 SHA-256 `d046eb691fd1fd019413ff1ce9eb55cdab69e3df187169eec27441e7c378fcaf`)를 A35 `SM-A356N` / `RFCX60GBL3D`에 데이터 보존 설치하고 동일 계측을 두 번 실행했다. 시작 전 기기 상태는 그대로였다: 자동 준비 `enabled=false`(사용자 설정 그대로, 변경·복원 불필요), 기본 입력기 이미 debug Saegeul, `last_symbol_layout=Number`.

| 실행 | 자판 전환 | 키별 반영 | 판정 |
| --- | --- | --- | --- |
| run1 | 125ms | 16키 모두 35~77ms | `OK (1 test)` |
| run2 | — | 두 번째 실행도 `OK (1 test)` | 통과 |

`lastSymbolLayout`은 실행 전후 모두 `Number`로 보존됐다. 이전 세션의 개인 금고 파일은 헤더 직접 확인으로 상태를 기록했다(`outputs/numeric-symbol-latency-fold6-20260910/a35-vault-headers.txt`):

| 파일 | 형식 | 해석 |
| --- | --- | --- |
| `personal_ngram.json` | SGV1+aesgcm+SGW2 | 저장 시점에 v2 봉투로 재암호화됨 |
| `personal_corrections.json` | SGV1+aesgcm+SGW2 | 저장 시점에 v2 봉투로 재암호화됨 |
| `prediction_metrics.json` | SGV1+aesgcm+SGW2 | 저장 시점에 v2 봉투로 재암호화됨 |
| `personalized_sentences.json` | SGV1+aesgcm+구형식 IV | 봉투 이전 직접 형식 보존, v1 폴백으로 읽힘 |
| `typing_dna.json` | SGV1+aesgcm+구형식 IV | 같음 |
| `gemma_materials.json`(noBackupFilesDir) | SGV1+aesgcm+구형식 IV | 같음 |

즉 구형식 파일은 그대로 보존되고 v1 폴백 복호화로 읽히며, 저장이 일어난 파일만 v2 형식으로 자연 재암호화된다. 파일 내용 전체의 원문 일치 비교는 암호문이라 이 실행으로 증명하지 않으며, 사용자 데이터 내용 보존의 최종 확인은 별도 수동 검증 항목으로 남는다.
