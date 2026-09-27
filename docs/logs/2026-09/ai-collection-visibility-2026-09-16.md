# AI 추천 복구·수집 견고화·백그라운드 가시성 (2026-09-16)

상태: 설계 확정, 구현 진행 중. 이 문서가 이번 작업의 정본이다. 워커는 이 문서의 계약을 바꾸지 않는다.

## 0. 실기기 진단 (Fold6 SM-F956N, Android 16, 디버그 패키지가 기본 입력기)

로그 원문: `scratchpad/fold6-logs/` (logcat 전체 33만 줄, prefs·jobs·notification dump). 요약:

| 증상 | 실측 | 코드 원인 |
|---|---|---|
| AI 자동 추천이 거의 항상 안 됨 | 키보드를 열 때마다 웜업이 `BackendException: LEASE_RELEASE_FAILED`로 즉시 중단(14분 창에서 4/4회). lease begin/end 로그 0건 → 로그 창 이전에 이미 고착 | `OnDeviceSuggestionEngine.releaseLease`가 `OnDeviceGenerationControl.end()==false`(선점으로 이미 교체된 lease)를 치명 실패로 래치. `terminalFailure`는 리셋 경로가 없고, 코디네이터도 `terminalFailure=true`로 래치되며 서비스는 코디네이터를 재생성하지 않음 |
| 실패가 보이지 않음 | `Timber.i`로만 기록. 인디케이터 `Blocked(code)`는 코드 문자열만 전달 | 실패 코드→사람 문구 매핑과 재시도 동선 없음 |
| 수집이 안 되는 것처럼 보임 | 실제로는 오늘 98문장 학습, 재료 1,132개 저장. 그래프 강화는 9/13부터 `REAUTH_REQUIRED`로 정지 | 수집 경로 4개 클래스에 로그 0줄. 디버그 빌드에서 강화 상태 UI가 `GONE`. 알림 채널 importance LOW |
| 업무 페르소나가 안 쌓임 | — | `WORK_PACKAGES`의 Notion·Jira·잔디 패키지명 오기, 카카오워크 누락, 메신저 규칙이 업무 규칙보다 먼저 평가, 카테고리별 임계 15개가 화면에 안 보임 |
| 페르소나 3개뿐 | — | `TypingDnaVault`의 문자열 상수 3개, 대시보드 막대 3개 고정 |
| 백스페이스 한 번에 수집 중 문장 전부 폐기 | — | `onEditorContinuityLost` → `discardPending(pkg)` 전체 삭제, `onStartInput(restarting=true)`도 전체 삭제 |
| 상세 설정이 숨겨짐 | — | "언어 금고" 카테고리는 `BuildConfig.DEBUG`만, 대시보드 진입은 `!DEBUG`만. 두 빌드가 서로 다른 절반만 봄 |

## 1. 범위와 비범위

범위: 디버그·릴리스 공통 main 소스셋과 debug 소스셋. 아래 A~D.
비범위: 릴리스 빌드의 온디바이스 Gemma 지원, 모델 다운로드 UX, 버전 범프·릴리스, 광고 정책 변경, 개인정보 선언 변경(수집 범위·저장 항목은 그대로다. 로그에 원문을 남기지 않는다).

## 2. 확정 설계

### A. 자동 추천 엔진 복구 (`input/ai/ondevice/**`, `FcitxInputMethodService` 자동 추천 구역)

- A1. **선점된 lease 반납은 정상이다.** `OnDeviceSuggestionEngine.releaseLease`는 `end()`가 false를 돌려주면 `Timber.i("lease already released (preempted)")`만 남기고 정상 반환한다. 래치·예외 없음. `LEASE_RELEASE_FAILED` 코드는 삭제한다.
- A2. **치명 래치는 프로세스 수명 동안 영구일 수 없다.** 엔진의 `terminalFailure`(NATIVE_STOP_TIMEOUT, NATIVE_CLOSE_FAILED, RESOURCE_MONITOR_*)는 그 엔진 인스턴스에 한정한다. 런타임 `OnDeviceAutomaticSuggestionRuntime`은 `val terminalFailureCode: String?`를 노출한다(릴리스 스텁은 null). 코디네이터 `OnDeviceSuggestionCoordinator`는 `val isTerminal: Boolean`을 노출한다.
- A3. **서비스가 복구를 소유한다.** `startAutomaticSuggestionWarmupIfAllowed` 진입 시 `runtime.terminalFailureCode != null || coordinator.isTerminal`이면 기존 GPU→CPU 폴백 분기와 같은 방식으로 코디네이터·런타임을 폐기하고 새로 만든 뒤 웜업한다. 복구 예산: 10분 창에 3회. 초과하면 실패 코드 `ENGINE_UNRECOVERABLE`로 표시하고 옵트인 토글 재설정 또는 `onDestroy` 전까지 재시도하지 않는다. 복구 시 `Timber.w`와 상태 저장소(A5)에 기록한다.
- A4. 웜업 실패는 `Timber.w(code)` 한 줄 + 스택은 `Timber.d`. 성공은 기존 `Timber.i`.
- A5. **`AiRuntimeStatusStore`** (main, prefs `ai_runtime_status`): `lastWarmupResult`(OK/코드), `lastWarmupAtMs`, `lastWarmupDurationMs`, `backend`(gpu/cpu), `lastGenerationAtMs`, `lastGenerationLatencyMs`, `recoveryCount`, `lastRecoveryAtMs`, `lastFailureCode`, `lastFailureAtMs`. 서비스가 쓰고 대시보드·설정이 읽는다. 원문·프롬프트·응답은 절대 넣지 않는다.
- A7. `GemmaMaterialGenerator`의 lease 반납은 conversation/engine close 성공에 조건부라 close가 던지면 lease가 영구 고아가 되어 이후 AUTO_CONTEXT가 전부 BUSY로 막힌다. 반납을 `finally`로 옮겨 항상 호출한다.
- A8. 옵트인을 켜는 경로에서도 A3 복구 검사를 거친다(코디네이터가 terminal이면 `setEnabled(true)`가 조용히 막히던 결함).
- A6. **실패 문구 매핑** `OnDeviceFailureText`(main, strings ko/en): `BUSY`→"다른 AI 작업이 끝나길 기다리는 중", `MODEL_MISSING`/`MODEL_INVALID`→"모델 준비 필요", `ENGINE_UNRECOVERABLE`→"AI 엔진 오류 · 입력기 재시작 필요", `NATIVE_*`→"AI 엔진 재시작 중", `THERMAL`/`LOW_MEMORY`/`LOW_BATTERY`→각 사유, 그 외→"AI 준비 실패 (코드)". `IdleUi`의 `Blocked` 표시는 이 문구를 쓰고, 탭하면 재시도(웜업 재시작)한다. 기존 코드 문자열 노출은 제거한다.

### B. 수집 견고화와 페르소나 확장 (`input/ai/**` ondevice 제외, `FcitxInputMethodService` 수집 훅 구역)

- B1. **페르소나 레지스트리** `input/ai/persona/PersonaRegistry.kt`. `data class PersonaCategory(id, labelRes, packages: Set<String>, tokens: List<String>)`. 내장 id(저장 키로 쓰므로 불변): `messenger`, `work`, `email`, `social`, `notes`, `browser`, `commerce`, `general`. 기존 `TypingDnaVault.CATEGORY_*` 상수는 레지스트리 id의 별칭으로 남긴다. 프로필 JSON `personas` 맵은 문자열 키라 마이그레이션이 없다.
- B2. **분류 순서** `PersonaRegistry.classify(packageName, override: String?)`: (1) 앱별 프로필 override → (2) 모든 페르소나의 정확 패키지 집합(researcher가 확인한 표) → (3) 토큰 휴리스틱을 아래 우선순위로: work(`work`, `works`, `team`, `office`, `enterprise`, `corp`, `biz`, `jira`, `slack`) → email(`mail`) → notes(`note`, `memo`, `docs`, `keep`) → messenger(`talk`, `chat`, `message`, `messenger`, `sms`, `mms`) → social(`instagram`, `twitter`, `facebook`, `tiktok`, `band`, `blog`, `community`) → browser(`browser`, `chrome`, `search`) → commerce(`shop`, `pay`, `bank`, `market`, `delivery`) → `general`. 업무 토큰이 메신저 토큰보다 먼저다.
- B3. **임계값** `thresholdPerCategory` 15 → 10. `TypingDnaVault.pendingByCategory(): Map<String, Int>` 추가. 수동 동기화는 지금처럼 미달분도 컴파일한다.
- B4. **백스페이스 의미론** `UserTypingContextCollector`: 제거 문자열을 알면 접미부만 잘라내고(기존), 모르면 마지막 공백 구분 토큰 하나만 버린다. 버퍼 전체 폐기 `discardPending`은 편집기 전환(패키지 또는 fieldId 변경, `restarting=false`)에서만 호출한다. `onStartInput(restarting=true)`는 같은 편집기면 보존한다. 삭제된 글자가 버퍼에 남는 경우는 없어야 한다(테스트로 고정).
- B5. **수집 진단** `input/ai/CollectionDiagnostics` (main): `Timber.tag("SaegeulCollect")`로 사건을 남기고 일별 카운터를 prefs `collection_stats`에 저장한다. 사건: `emitted(category, length)`, `dropped(reason ∈ privacy|short|backspace|editorSwitch|duplicate|blank)`, `batchReady(category, size)`, `compiled(category, ok|error)`. 로그와 카운터에 원문·단어를 넣지 않는다. 최근 사건 200개 메모리 링버퍼를 대시보드가 읽는다.
- B6. **키보드 피드백 콜백** 서비스에 `onCollectionEvent(category, pendingCount, threshold, compiled: Boolean)`를 전달해 상태 행에 1.2초 문구를 띄운다: 앱 세션 첫 문장 "업무로 학습 중 · 3/10", 컴파일 시 "업무 페르소나 갱신됨". pref `collection_feedback_in_keyboard` 기본 true.

### C. 백그라운드 진행 알림 (`input/ai/rag/GraphEnrichmentRunner`, `debug/gemma/GemmaAccumulationWorker`, 새 `utils/BackgroundProgressNotifier`)

- C1. 채널 2개(main): `saegeul-ai-progress`(IMPORTANCE_LOW, ongoing 진행), `saegeul-ai-alerts`(IMPORTANCE_DEFAULT, 조치 필요). 기존 `saegeul-graph-enrich` 채널은 앱 시작 시 `deleteNotificationChannel`로 제거한다.
- C2. `BackgroundProgressNotifier`(main): `progress(id, title, text, current, total)`, `done(id, title, text, contentIntent?)`, `alert(id, title, text, contentIntent?)`, `cancel(id)`. `POST_NOTIFICATIONS` 미허가면 아무 것도 띄우지 않고 자체 prefs `notifier_status.last_blocked_ms`에 기록한다(대시보드가 읽는다). pref `background_progress_notifications`(기본 true)가 progress·done을 게이트하고 alert는 항상 띄운다.
- C3. `GemmaAccumulationWorker`: 시작 "문장 재료 준비 중 · 0/N 문맥", 문맥마다 갱신, 차단·취소 시 progress 취소(사유는 대시보드), 정상 종료에 `added>0`이면 done "문장 재료 N개 추가 · 총 M개", 모델 없음·오류는 alert에 사유. foreground service는 쓰지 않는다(권한 추가 없음).
- C4. `GraphEnrichmentRunner`: 청크 기준 결정 진행률(`PersonalGraphEnricher.onChunkOutcome`에 `index, total` 추가), 실패 alert에 사유 문구(`REAUTH_REQUIRED`는 "다시 로그인 필요"와 글쓰기 AI 설정으로 가는 PendingIntent), 취소 경로에서 ongoing 알림 취소. 디버그에서도 동기화 뒤 강화를 실행한다(`if (!BuildConfig.DEBUG)` 제거, D2-a에서 수행). 미참조가 된 `enrich_notify_channel`·`enrich_notify_failed` 문자열은 삭제한다.

### D. 설정·대시보드 정리 (`ui/**`, `res/**`)

- D1. `PrivacyAiSettingsFragment` "언어 금고" 카테고리를 **두 빌드 모두** 최상단에 둔다. 항목: 언어 금고 대시보드(단일 진입, 릴리스 전용 중복 항목 삭제) / 자동 문맥 추천 스위치(릴리스는 비활성 + "이 빌드에서는 지원되지 않습니다") / GPU 가속 스위치(`automatic_ondevice_suggestions_use_gpu`, 릴리스 비활성) / 백그라운드 진행 알림 스위치 / 키보드 수집 피드백 스위치 / 알림 권한 열기(권한 거부 시에만 표시, 시스템 앱 알림 설정으로) / 지금 분석·동기화 / 전체 초기화. 완전 오프라인 모드 미러는 `AppPrefs.advanced.offlineMode`와 같은 ManagedPreference에 바인딩해 두 화면이 항상 같은 값을 보게 한다.
- D2. 대시보드 최상단에 "지금 상태" 카드: 수집(오늘 학습 N · 페르소나별 대기 n/10 · 오늘 건너뜀 사유별 수), AI 추천 엔진(상태·백엔드·마지막 웜업·실패 문구·재시도), 재료 준비(debug 카드 유지), 그래프 강화(단계·실패 사유·조치 버튼). 강화 상태 UI의 `BuildConfig.DEBUG` GONE 분기를 제거한다.
- D3. 앱 카테고리 막대 3개 고정 → 레지스트리 기반 동적 행(비율·개수·대기 n/10). `DashboardSnapshot`·`TypingDnaRepository` 통계는 카테고리 맵으로 일반화한다.
- D4. `AppProfileSettingsFragment`에 앱별 페르소나 override 선택(레지스트리 라벨 목록 + "자동").
- D5. 손대는 화면의 하드코딩 한글은 strings.xml(ko/en)로 옮긴다.

## 3. 검증 게이트

1. 단위: `input.ai.ondevice.*`(기존 128 + 신규: 선점 뒤 반납 정상, 치명 후 복구 예산), `input.ai.*`(레지스트리 분류 표, 백스페이스 토큰 폐기, 임계 10, 진단 카운터), `rag.GraphEnrichmentRunnerTest`(알림 분기), `debug.gemma.*`.
2. 빌드: `:app:assembleDebug :app:assembleDebugAndroidTest`.
3. 실기기 Fold6: 설치 → 키보드 열기 → 로그에 `warm-up finished` 확인 → 문장 입력 후 후보 표시 → 업무 앱(Gmail/Slack)에서 3문장 입력 → `SaegeulCollect emitted category=work` → 축적 잡 강제 실행 → 진행 알림 스크린샷 → 설정·대시보드 스크린샷.

## 4. 워커 경계

| 패킷 | 파일 경계 |
|---|---|
| P1 엔진 복구 | `input/ai/ondevice/**`(main·debug·release), `FcitxInputMethodService`의 자동 추천 함수들, `input/bar/ui/IdleUi.kt`·`KawaiiBarComponent.kt`의 Blocked 표시, `AiRuntimeStatusStore` 신규 |
| P2 수집·페르소나 | `input/ai/persona/**` 신규, `TypingDnaVault`, `UserTypingContextCollector`, `TypingDnaCommitSink`, `TypingDnaRepository`(통계 일반화), `CollectionDiagnostics` 신규, `FcitxInputMethodService`의 수집 훅(`observeCommittedEditorText`, `onEditorContinuityLost` 호출부, `onStartInput` 세션 시작, `onSentenceCommitted`) |
| P4 알림 | `utils/BackgroundProgressNotifier` 신규, `GraphEnrichmentRunner`, `GraphEnrichmentStatusStore`(필요 시), `debug/gemma/GemmaAccumulationWorker`, `FcitxApplication`(채널 정리), `AppPrefs`(알림 pref) |
| P3 UI | `ui/**`, `res/**`, `AppKeyboardProfile`(persona 필드), `DashboardSnapshot` |

P1·P2·P4는 병렬, P3는 P1·P2 수용 뒤 실행한다. `FcitxInputMethodService`는 P1·P2가 서로 다른 함수만 만진다.

## 5. 구현 기록 (2026-09-16, 오케스트레이터가 diff를 직접 읽고 수용한 것만)

- **P1 엔진 복구(수용)**: `OnDeviceSuggestionEngine.releaseLease`·`GemmaMaterialGenerator`의 lease 반납을 선점·close 결과와 무관하게 정상 처리. `OnDeviceRecoveryBudget`(10분 3회) + 서비스 `recoverAutomaticSuggestionStateIfTerminal`(구 백엔드 `invalidate(closeBackend=true)` 후 재생성, 초과 시 `ENGINE_UNRECOVERABLE`). 코디네이터 재생성 시 옵트인 상태 즉시 동기화(재생성 뒤 코디네이터가 비활성으로 남던 결함 추가 수정). `AiRuntimeStatusStore`(prefs `ai_runtime_status`). `OnDeviceFailureText`로 후보 바 상태 행·툴바 버튼·다이얼로그 문구 통일, 다이얼로그에 재시도. `LEASE_RELEASE_FAILED` 코드 삭제.
- **P2 수집·페르소나(수용)**: `PersonaRegistry` 8종(정확 패키지 집합은 Play 스토어 확인분, 업무 토큰이 메신저 토큰보다 우선). `TypingDnaVault.categorizePackage/recordSentence(personaOverride)`, 임계 10, `pendingByCategory()`. `UserTypingContextCollector.onBackspaceContinuityLost`(알려진 접미부만 절단, 모르면 마지막 어절만) + `onEditorSessionStarted(pkg, fieldId, restarting)`(같은 편집기 재시작은 보존). `CollectionDiagnostics`(`SaegeulCollect` 로그, 일별 카운터, 링버퍼 200). 서비스 `collectionFeedback` StateFlow. `TypingDnaStats.categoryCounts`.
- **P4 알림(수용)**: `BackgroundProgressNotifier`(채널 `saegeul-ai-progress`/`saegeul-ai-alerts`, 구 채널 삭제, 권한·설정 게이트 `shouldPost`). 그래프 강화는 청크 index/total 결정 진행률과 실패 사유 alert(재로그인은 글쓰기 AI 설정 PendingIntent), 취소 시 정리. 축적 워커는 시작/문맥별 진행, 추가분 있을 때만 완료 알림, 모델 없음·오류는 alert. `AppPrefs.internal.backgroundProgressNotifications`·`collectionFeedbackInKeyboard`.
- **P3 설정·대시보드(수용)**: "언어 금고" 카테고리를 두 빌드 공통 최상단으로(대시보드 단일 진입, 자동 추천·GPU 스위치는 릴리스 비활성, 알림·피드백 스위치, 알림 권한 열기). 강화 UI의 DEBUG GONE 분기 제거, 디버그에서도 동기화 뒤 강화 실행. 대시보드 "지금 상태" 카드(AI 엔진·그래프 강화·알림·수집 4행, 최근 활동 접이식). 카테고리 분포를 레지스트리 기반 동적 8행·8색으로. `AppKeyboardProfile.persona` + 앱별 프로필 화면 선택기, 서비스 `onSentenceCommitted`에서 한 번 계산해 vault·n-gram·RAG에 동일 전달. 키보드 상태 행 `COLLECTION_FEEDBACK`(1.2초).
- 범위에서 제외한 것: `PrivacyAiSettingsFragment` API 키 다이얼로그와 대시보드 초기화 다이얼로그의 하드코딩 한글(별도 작업), `TypingDnaStats`의 구 비율 필드 3개(호환 유지), `GemmaAccumulationStore.nextOpenPlan()` null 반환의 정상 종료/비활성 구분.
- **실기기 검증에서 잡은 회귀(수정 완료)**: P3의 D6가 `HorizontalCandidateComponent` 생성자(`init`)에서 `service.collectionFeedback`을 구독해, 키보드를 여는 순간 `ComponentNotExistException`으로 IME가 크래시했다(의존성 매니저에 붙기 전에 `service`를 참조). 구독을 `onScopeSetupFinished`와 뷰 attach 훅으로 옮기고, 뷰 detach 시 Job·지연 Runnable을 정리하도록 고쳤다. 단위 테스트로는 잡을 수 없는 종류라 실기기 시나리오가 필수다.
- **실기기 검증에서 잡은 결함 2(수정 완료)**: 재료 축적 워커가 WorkManager 스레드에서 `tryBegin(PUBLIC_MATERIAL)`로 웜 상태 AUTO 엔진을 선점하면, 서비스의 선점 핸들러가 메인 스레드 전용 함수(`updateAutomaticSuggestionWarmupState`의 `check(Looper)`)를 그대로 호출해 `IllegalStateException("Check failed.")`가 `tryBegin` 안에서 터졌고, 이미 배정된 PUBLIC_MATERIAL lease가 고아가 되어 이후 자동 추천은 전부 BUSY로 막혔다(워커는 FAILURE + "문장 재료 준비 실패" 알림). 선점·재개 콜백을 `runOnMainThread`로 넘기고, `OnDeviceGenerationControl.tryBegin/end`가 콜백 예외를 삼켜 lease 반환을 보장하도록 고쳤다(`OnDeviceGenerationControlTest`에 케이스 추가). A35 실기기에서 선점 → 워커 실행(진행 알림 1/4) → 워커 종료 → 키보드 재오픈 웜업 성공(15.7초)까지 확인했다.

## 6. 실기기 검증 결과 (2026-09-16 저녁, 오케스트레이터 직접 수행)

| 항목 | A35 (USB) | 폴드6 (무선) |
|---|---|---|
| 키보드 열기 크래시 | 0건 | 0건 |
| 자동 추천 웜업 | `warm-up finished warm=true` 16.2s / 20.0s / 15.7s (GPU) | 13.1s / 8.7s (Thermal Status 2에서도 성공) |
| 단어·문장 추천 표시 | 후보 바에 "그리고·그런데·이제", 상태 행 "AI 문장 생성 중…", `generated elapsedMs=6023` | — |
| 수집 로그 | `SaegeulCollect emitted category=general len=12`, `len=6`, 카운터 `emitted 4 · compiled 1` | — |
| 축적 워커의 lease 선점 | `preempt PUBLIC_MATERIAL ← AUTO_CONTEXT` → 메인 스레드 정리 → 엔진 "already released (preempted)" → 워커 실행·진행 알림 "1/4 문맥 · 저장 62개" → 종료 후 키보드 재오픈 웜업 성공 | 워커 실행 → 재료 1개 추가 → 완료 알림 "문장 재료 1개 추가 · 총 1150개 저장" → 재오픈 웜업 성공 |
| 대시보드 "지금 상태" 카드 | AI 추천 엔진 "준비됨 · GPU · 12분 전", 수집 "오늘 학습 4문장 · 건너뜀 68", "메신저 1/10 · 일반 3/10" | — |
| 설정 "언어 금고" 카테고리 | 대시보드·자동 추천·GPU·진행 알림·수집 피드백·리포트·동기화·초기화 8항목 표시 | — |

주의: `adb shell input text`로 넣은 문자는 IME를 거치지 않아 수집 경로에서 커서 불일치(`dropped reason=backspace`)로만 잡힌다. 수집 검증은 키보드 키를 실제로 탭해야 한다.
