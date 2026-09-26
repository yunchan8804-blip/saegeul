# Android 스마트 입력·AI 인벤토리 (2026-09-14)

목적: iOS 1:1 이식 격차표의 기준 인벤토리. 모든 행은 코드 또는 문서에서 직접 확인한 사실만 담는다. 추측은 `추정`으로 표시한다.

조사 범위: `app/src/main/java/org/fcitx/fcitx5/android/input/**`(특히 `input/ai/**`), `app/src/debug/**`, `app/src/release/**`, `plugin/hangul/**`, `app/src/main/assets/**`, `docs/korean-smart-input-ssot.md`, `docs/gemma-*.md`, `docs/wiki/*.md`, `docs/independent-fork/privacy-data-safety.md`.

경로는 따로 명시하지 않는 한 `app/src/main/java/org/fcitx/fcitx5/android/` 기준 상대 경로다.

## 0. 먼저 확정한 두 가지 전제

### 0.1 온디바이스 Gemma 추론은 현재 debug 빌드 전용

- `input/FcitxInputMethodService.kt:2659-2660`

```kotlin
val automaticSuggestionsSupported: Boolean
    get() = BuildConfig.DEBUG
```

- 추론 런타임 구현은 debug 소스셋에만 있다.
  - `app/src/debug/java/org/fcitx/fcitx5/android/input/ai/ondevice/OnDeviceSuggestionEngine.kt`
  - `app/src/debug/java/org/fcitx/fcitx5/android/input/ai/ondevice/OnDeviceAutomaticSuggestionRuntime.kt`
  - `app/src/debug/java/org/fcitx/fcitx5/android/input/ai/ondevice/OnDeviceContextCompletionRuntime.kt`
- release 소스셋에는 스텁만 있다.
  - `app/src/release/java/org/fcitx/fcitx5/android/input/ai/ondevice/OnDeviceAutomaticSuggestionRuntime.kt`
- 설정의 「언어 금고」 카테고리와 자동 추천 opt-in 스위치도 `BuildConfig.DEBUG` 안에만 추가된다.
  - `ui/main/settings/behavior/PrivacyAiSettingsFragment.kt:111-132`
- debug 전용 파일 전체 목록(16개): `debug/AiDebugGenerationOverride.kt`, `debug/AiEditorTestActivity.kt`, `debug/gemma/GemmaAccumulationPlanner.kt`, `GemmaAccumulationScheduler.kt`, `GemmaAccumulationStore.kt`, `GemmaAccumulationWorker.kt`, `GemmaExperimentActivity.kt`, `GemmaGenerationEligibility.kt`, `GemmaGenerationMetrics.kt`, `GemmaMaterialGenerator.kt`, `GemmaModelFiles.kt`, `GemmaOpenMaterialPlan.kt`, `input/ai/ondevice/OnDeviceAutomaticSuggestionRuntime.kt`, `input/ai/ondevice/OnDeviceContextCompletionRuntime.kt`, `input/ai/ondevice/OnDeviceSuggestionEngine.kt`, `ui/main/ai/GemmaPreparationFactory.kt`

### 0.2 iOS 1차 범위는 이미 SSOT에 잠겨 있음

`docs/korean-smart-input-ssot.md:87-95` — 「iOS 듀얼 플랫폼 (2026-09-13 잠금)」

> 1. iOS 1차 성공 정의는 **자판 + 로컬 스마트 입력**이다. Full Access 없이 동작한다. 키보드 확장 안 Gemma 추론은 넣지 않는다.
> 2. 1차 공개 자판은 위키 17종에서 **모아키만 제외**한다. 모아키는 삼성 특허 법률 검토 뒤에만 올린다.
> 3. iOS 1차 수익화는 없다. StoreKit·호스트 광고는 뒤 단계다.

---

## 1. 로컬(오프라인) 스마트 입력

| 기능 | Android 근거 파일:줄 | 설정 이름 | Full Access 필요 여부 추정 근거 | 비고 |
|---|---|---|---|---|
| 한/영 오타 즉시 복구 (`KO-01`) | `input/typo/KoreanTypoRecovery.kt:37`, `input/typo/TypoRecoveryWindow.kt`, 호출 `input/FcitxInputMethodService.kt:4238-4239`, 툴바 버튼 `input/bar/ui/idle/ButtonsBarUi.kt:176` | 툴바 버튼 상시 노출 | 불필요. 소스 주석이 "never reads a clipboard or contacts a provider" (`KoreanTypoRecovery.kt:36`) | 두벌식 역매핑 순수 함수. `dkssud→안녕`, `ㅗ디ㅣㅐ→hello` 양방향 |
| 초성 통합 검색 (`KO-02`) | `input/search/KoreanUnifiedSearch.kt:7-46`, `input/search/KoreanInitials.kt:7-39`, `input/search/KoreanSearchRepository.kt:14-46`, `input/search/KoreanSearchWindow.kt`, 툴바 버튼 `ButtonsBarUi.kt:172` | 툴바 버튼 상시 노출 | **필요.** 검색 소스에 클립보드 기록이 포함됨 (`input/search/KoreanSearchModels.kt:9`) | 소스 4종과 정렬 rank: QuickPhrase(0)·Clipboard(1)·Emotion(2)·Emoji(3). 기본 limit 60 (`KoreanUnifiedSearch.kt:8`). 화면 안 19개 초성 패드 |
| 조사 받침 자동 판별 (`KO-07`) | `input/context/KoreanParticleSuggester.kt:67-78`, `input/context/KoreanParticleWindow.kt`, 진입 `input/search/KoreanSearchWindow.kt:63`, 호출 `FcitxInputMethodService.kt:2433` | 통합 검색 화면 안 버튼 (`input/search/KoreanSearchUi.kt:126,141`) | 불필요. 마지막 한글 음절의 종성만 계산 | 6종 `KoreanParticleKind`: Topic·Subject·Object·Conjunction·Direction·Copula. ㄹ 받침 예외 처리 (`KoreanParticleSuggester.kt:78`). 커밋 계약 `KoreanParticleCommitContract.canCommit` (:39-47), 1회 소비 게이트 `KoreanParticleCommitGate` (:50-59) |
| 한글 어절 자동완성 (`KO-09`) | `plugin/hangul/src/main/cpp/fcitx5-hangul/src/completiondictionary.cpp`, `engine.cpp`, 정책 `candidatepolicy.h:28-33`, 데이터 `plugin/hangul/src/main/assets/usr/share/fcitx5/hangul/completion.txt` | 한글 addon 설정 `한글 어절 자동완성`, 기본 켬 | 불필요. 번들 자산만 조회 | 5,250 표제어 · 91,467 bytes. 국립국어원 `한국어 학습용 어휘 목록` 5,965개에서 완성형 한글만 추출 + 프로젝트 작성 모바일 대화 표현을 앞에 배치. **KOGL(공공누리) 제1유형**. 고지 `completion-NOTICE.md`, 원본 SHA-256 `3B49681F05D6A7490C13DA2A2847E433EFFDF65DA409FD295792D6EE33685064`, 생성 스크립트 `scripts/generate-korean-completion-dictionary.ps1` |
| 다음 어절 추천 (`KO-07`) | `input/ai/BundledKoreanNgram.kt`(KONGRAM1 리더), 후보 생성 `input/ai/AiContextualPredictor.kt`의 `corpus_ngram` 블록, 로드 `FcitxApplication.warmUpLanguageAssets`, 데이터 `app/src/main/assets/korean/ko-ngram.bin` | 자동완성 토글에 종속 | 불필요. 번들 바이너리만 조회 | FineWeb-2 어절 bigram·trigram에 구어 층(ChatbotData 학습 분할, MIT)과 손작성 `nextword.txt` 쌍을 관측 수 가중 λ(0.7·n/(n+10))로 보간했다. 정적 연어(`KoreanCollocationModel`)는 코퍼스 아래로 내렸다. 생성 절차는 `docs/korean-smart-input-ssot.md`에 있다. 네이티브 `NextWordDictionary`는 코드에 남아 있지만, 메인 앱 번들에서 `nextword.txt`를 빼서 동작하지 않는다(`app/build.gradle.kts`의 `bundleHangulEngineAssets`) |
| 개인 단어장 (`KO-06`) | `data/personaldictionary/PersonalDictionaryStore.kt:16-17,55`, `data/personaldictionary/PersonalDictionary.kt:98`, UI `ui/main/settings/behavior/PersonalDictionaryFragment.kt:38-85` | `개인 단어장` opt-in 스위치 | **필요 추정.** 본체 앱 설정 화면이 쓰고 IME가 읽는 공유 저장소 (`noBackupFilesDir/korean-personal-dictionary/words.txt`) | 백업 제외. 카테고리별 단어 분류(`PersonalWordCategory`) |
| 한자 음훈 변환 (`KO-05`) | 정책 `plugin/hangul/src/main/cpp/fcitx5-hangul/src/candidatepolicy.h:12-25`, 진입 `input/status/StatusAreaWindow.kt:88`, 아이콘 매핑 `input/status/StatusAreaEntry.kt:35-36`, 데이터 `plugin/hangul/src/main/assets/usr/share/libhangul/hanja/hanja.txt` | 더보기 → 상태 항목 (명시 1회 변환) | 불필요. 번들 자산만 조회 | 6,756,043 bytes. `가:可:옳을 가` 형식으로 음훈 포함. 자동완성이 켜져 있으면 지속 한자 모드를 끄고 명시 1회 변환으로만 동작 (`usePersistentHanjaCandidates`, `shouldClearLegacyHanjaMode`). 보조 데이터 `mssymbol.txt` |
| 오프라인 국어사전 (`KO-05A`) | `input/search/KoreanDictionary.kt:11-30`, `KoreanDictionaryRepository.kt`, `KoreanDictionaryAdapter.kt`, 데이터 `app/src/main/assets/korean/dictionary.bin` | 통합 검색 안 명시적 `국어사전` 모드 | 불필요. 번들 바이너리만 조회 | 3,414,000 bytes 정렬 바이너리 offset 인덱스(lazy decode). **한국어 위키낱말사전, CC BY-SA 4.0**. 고지 `app/src/main/assets/korean/dictionary-ATTRIBUTION.txt` (Wiktextract/kaikki.org, 덤프 2026-07-03, 추출 2026-07-24). 읽기 전용이며 입력 원문을 바꾸지 않음 |
| 한국식 감정표현 추천 (`KO-08`) | `input/emotion/KoreanEmotionLexicon.kt:13-50`, `input/emotion/ExplicitEmotionSearch.kt` | 통합 검색 경유 | 불필요. 주석이 "never receives editor or clipboard text" (`KoreanEmotionLexicon.kt:12`) | 감정 10종(Celebrate·Apology·Thanks·Awkward·Laugh·Love·Angry·Sad·Cheer·Agree) × 이모지·kaomoji·`ㅋㅋ`/`ㅎㅎ` 강약 chip. quickQueries 14개 |
| 이모지 한국어 키워드 검색 | `input/search/KoreanEmojiKeywords.kt` | 통합 검색 경유 | 불필요 | |
| 동적 빠른 문구 (`KO-03`) | `input/dynamicphrase/DynamicPhraseWindow.kt`, `DynamicPhraseUi.kt`, 저장소 `data/quickphrase/dynamic/DynamicPhraseProfileStore.kt:26-28`, 툴바 버튼 `ButtonsBarUi.kt:168` | 빠른 문구 툴바 | **필요 추정.** `{클립보드}` 토큰이 Fcitx 클립보드 최신 항목을 읽음 | 토큰 7종 `{날짜}{시간}{이름}{전화번호}{이메일}{주소}{클립보드}` + 영문 alias. 프로필은 Android Keystore AES-GCM + `noBackupFilesDir/dynamic-phrase/profile.bin` |
| 자동 스니펫 확장 (`KO-03A`) | `FcitxInputMethodService.kt:268,1165`, 설정 정의 `data/prefs/AppPrefs.kt:59-63` | `auto_snippet_expansion`, 기본 켬 | 조건부. 날짜·시간 별칭만 쓰면 불필요, 개인 프로필·클립보드 토큰이면 **필요 추정** | 기본 별칭 `:이름` `:전화` `:전화번호` `:이메일` `:메일` `:주소` `:주소1` `:날짜` `:시간` + 영문. Space 경계는 치환+공백 유지, Enter 경계는 확장만 하고 Enter를 소비 |
| 민감 빠른 문구 금고 (`SEC-01`) | `input/dynamicphrase/SensitivePhraseAuthenticator.kt`, `SensitivePhraseAuthActivity.kt`, `SensitivePhraseSession.kt`, `SensitivePhraseWindow.kt`, 설정 `ui/main/settings/SensitivePhraseVaultSettings.kt` | 설정 화면 | **필요 추정.** 인증 결합 Keystore + Activity 소유 BiometricPrompt | 60초 실제 세션 만료 + package allowlist. SSOT 상태 `IN_PROGRESS`(생체 실기기 게이트 잔여) |
| 스마트 클립보드 (`UX-01`) | `input/clipboard/SmartClipboardTransformer.kt`, `SmartClipboardModels.kt`, `SmartClipboardUi.kt` | 클립보드 창 안 smart 모드 | **필요.** 클립보드 원문 접근 | 서식 제거·선택 항목 합치기·전화/계좌 형식화·PII 마스킹. 명시 선택 후 1회 삽입 |
| 클립보드 기록·제안 | 설정 `data/prefs/AppPrefs.kt:441-464`, UI `input/clipboard/ClipboardWindow.kt`, `ClipboardStateMachine.kt` | `clipboard_enable` 기본 **꺼짐**, `clipboard_suggestion` 기본 켬, `clipboard_mask_sensitive` 기본 켬, `clipboard_limit`, `clipboard_suggestion_timeout`, `clipboard_return_after_paste` | **필요.** 시스템 클립보드 감시 | Room DB. Android 클라우드 백업·기기 이전·ZIP 내보내기에서 제외 |
| 앱별 키보드 profile (`KO-04`) | `input/profile/AppKeyboardProfile.kt:71,99`, 적용 `FcitxInputMethodService.kt:1945-1969`, UI `ui/main/settings/behavior/AppProfileSettingsFragment.kt` | `앱별 프로필` | 불필요. `EditorInfo.packageName`만 사용 | package별 layout·theme·transport·toolbar·AI 정책·network 정책. `AppFeaturePolicy.Block` 지원 |
| 한글 버퍼 호환 입력 (`KO-BASE-02`) | `input/BufferedHangulMode.kt`, `BufferedHangulWindow.kt`, `BufferedInputController.kt`, `BufferedInputTransport.kt`, 설정 `AppPrefs.kt:65-75`, 툴바 버튼 `ButtonsBarUi.kt:164` | `buffered_hangul_input`, `buffered_input_transport` | 불필요 | 삼성 클립보드 부작용·문제 surface 회피용 Direct commit 경로 |
| 실시간 오타 교정 엔진 | `input/ai/KoreanTypoCorrectionEngine.kt` (659줄, 겹받침 맵 :516-523,583,608), `input/ai/typo/KeyboardAwareTypoCorrector.kt`, `input/ai/typo/DubeolsikKeyMap.kt:52,66`, `input/ai/typo/BaseKoreanVocabulary.kt`, `input/ai/typo/CorrectionPatternStore.kt`, `input/ai/typo/CorrectionSessionTracker.kt`, 후보 생성 `input/ai/AiContextualPredictor.kt:171-323` | 후보 바에 `✏️` 배지로 표시 | 불필요. 번들 TSV만 조회 | 어휘 `app/src/main/assets/ko_base_vocab.tsv` 150,000행 + 헤더 1행. 출처 FineWeb-2(ODC-By 1.0)에 ChatbotData(MIT) 구어 가중을 더했다. 오타 교정은 상위 `TYPO_VOCAB_LIMIT`(30,000)만 쓴다. 소스 6종: `typo_personal`·`typo_keyboard`·`typo_keyboard_stem`·`typo_sentence_correction`·`typo_word_correction`·`typo_correction` |
| 기기 AI 띄어쓰기 제안 | `input/ai/ImmediateContextualPredictions.kt:38-56`, `input/ai/ondevice/GeneratedSpacingIndex.kt:12-49`, 연결 `FcitxInputMethodService.kt:3773` | 없음 (debug 한정) | 불필요. 메모리 인덱스 조회만 | **`BuildConfig.DEBUG`에서만 연결.** 배지 `기기 AI 띄어쓰기`. 문장 길이 8~160자, 종결부호 필수, 공백 위치만 다르고 정답 후보가 유일할 때만 제안 |
| 후보 바 (가로·2행·확장·부동) | `input/candidates/horizontal/HorizontalCandidateComponent.kt`, `HorizontalCandidateViewAdapter.kt`, `input/candidates/CandidateItemUi.kt:101-128`, `input/candidates/CandidateBadge.kt:17-45`, `input/candidates/expanded/`, `input/candidates/floating/`, 설정 `AppPrefs.kt:346-378,387-392,394-430` | `horizontal_candidate_style`, `two_row_candidate_bar`, `expanded_candidate_style`, `expanded_candidate_grid_span_count`, `show_candidates_window`, `candidates_orientation`, `candidates_font_size` 등 | 불필요 | 선택 방식은 후보 탭 1회. AI 배지 후보는 `"<텍스트> <출처>"` contentDescription 부여 (`CandidateItemUi.kt:125`). 배지 아이콘 매핑 13갈래 (`CandidateBadge.kt:21-33`) |

**소계: 20개**

---

## 2. 온디바이스 AI

이 영역의 추론 실행체는 0.1절대로 debug 소스셋에만 있다. 정책·세션·조정기 클래스는 `main`에 있으나 backend 없이는 동작하지 않는다.

| 기능 | Android 근거 파일:줄 | 설정 이름 | Full Access 필요 여부 추정 근거 | 비고 |
|---|---|---|---|---|
| 자동 문맥 추천 — 단어(WORD) | `input/ai/ondevice/OnDeviceSuggestionPolicy.kt:12-38,74`, 후보화 `input/candidates/horizontal/HorizontalCandidateComponent.kt:247-268`, 서비스 연결 `FcitxInputMethodService.kt:2720-2760,3195-3213` | `automatic_ondevice_suggestions_opt_in` (`PrivacyAiSettingsFragment.kt:114-120`, `setDefaultValue(true)`) | 불필요. 외부 공급자 fallback 없음 | 배지 문자열 `Gemma 이어쓰기` (`res/values/gemma_automatic_strings.xml:8`). 프롬프트에 앱 패키지·inputType·imeAction 메타데이터만 포함 (:31) |
| 자동 문맥 추천 — 문장(SENTENCE) | `input/ai/ondevice/OnDeviceSuggestionCoordinator.kt:23-75`, `OnDeviceSuggestionSession.kt` | 동일 | 불필요 | 배지 `Gemma 생성` (`gemma_automatic_strings.xml:7`). 상태 6종 `OFF/DEBOUNCING/GENERATING/READY/NO_CANDIDATE/ERROR` |
| 웜업·생성 상태행 | `HorizontalCandidateComponent.kt:632-712,1054-1055`, `input/ai/ondevice/OnDeviceAutomaticSuggestionWarmupState.kt`, 문자열 `res/values/gemma_automatic_strings.xml:9-20` | 동일 | 불필요 | 300ms 디바운스. 상태 문구: `AI 엔진 불러오는 중`, `AI 문장 생성 중…`, `추천 문장 없음`, `AI 생성 실패`, `배터리 20% 미만 · AI 추천 대기`, `기기 발열 · AI 추천 대기`, `메모리 부족 · AI 추천 대기`, `절전 모드 · AI 추천 대기` |
| 명시 문맥 완성 | `input/ai/ondevice/OnDeviceContextCompletionPolicy.kt:21-50`, `OnDeviceContextCompletionGate.kt:41-75`, `OnDeviceContextCompletionWindow.kt`, `OnDeviceAutomaticEditorSnapshot.kt`, 게이트 `FcitxInputMethodService.kt:1807-1808` | AI 패널 안 별도 명시 동작 | 불필요 | 문맥 최대 2048자, 접미부 최대 120자, raw 응답 최대 4096자 (`OnDeviceContextCompletionPolicy.kt:55-57`). 원문 보존 검사 통과가 의미 통과는 아님 |
| 공개 문장 재료 생성 | `input/ai/ondevice/GeneratedMaterialPolicy.kt:17-24,26-35,37-55,57-75`, `debug/gemma/GemmaMaterialGenerator.kt`, `debug/gemma/GemmaOpenMaterialPlan.kt` | debug 실험 화면 (`GemmaExperimentActivity`) | 불필요 | 고정 20개 접두구, 접두구당 목표 4개(`TARGET_PER_PREFIX`)·최대 6회 시도(`MAX_ATTEMPTS_PER_PREFIX`). 개방형 프롬프트는 상황×의도 조합 순환, 의도 6종(질문·요청·제안·상태 전달·확인·응답) |
| 문장 재료 자동 축적 | `debug/gemma/GemmaAccumulationScheduler.kt`, `GemmaAccumulationWorker.kt`, `GemmaAccumulationPlanner.kt`, `GemmaAccumulationStore.kt` | debug 실험 화면 | 불필요 | 키보드 활성 중에는 `PUBLIC_MATERIAL` 목적을 거부·취소 (`input/ai/ondevice/OnDeviceGenerationControl.kt:36-40`) |
| 생성 재료 기반 추천 | `input/ai/ondevice/GeneratedSentenceBank.kt:29,168,199,370`, 소비 `input/ai/ImmediateContextualPredictions.kt:68-79`, 연결 `FcitxInputMethodService.kt:3772` | 없음 (debug 한정) | 불필요. 메모리 인덱스 조회 | 배지 `기기 AI 재료`. `PREFIX`/`CONTEXT_SUFFIX` 증거만 채택 |
| 자원 게이트 (배터리·발열·메모리) | `input/ai/ondevice/OnDeviceGenerationControl.kt:32-56,70-86`, `debug/gemma/GemmaGenerationEligibility.kt:43-98` | 없음 | 불필요 | 자동 생성 배터리 30% 이상, 수동 강화 20% 이상, 발열 `THERMAL_STATUS_SEVERE` 이상 차단, 절전 모드 차단. 목적 3종 lease: `PUBLIC_MATERIAL`(키보드 비활성 시만)·`EXPLICIT_CONTEXT`(항상)·`AUTO_CONTEXT`(키보드 활성 시만) |
| 즉시 문맥 추천 (로컬 휴리스틱) | `input/ai/ImmediateContextualPredictions.kt:19-113`, `input/ai/KoreanDiscourseContinuation.kt`, 호출 `FcitxInputMethodService.kt:3763-3774` | 상시 | 불필요 | 배지 `이어쓰기`. 메모 캐시 키는 stroke·context·package·epoch·팩 revision |
| 의미 기반 문장 예측 | `input/ai/KoreanSemanticSentencePredictor.kt:855-883` (928줄), `input/ai/KoreanSentenceContinuation.kt`, `KoreanSentenceEndingExtractor.kt`, `AiToneAdaptivePredictor.kt`, `SentenceRelevanceReranker.kt` | 상시 | 불필요 | 의도별 배지 13종 이상: `✨ 맞춤AI`·`핵심구문`·`일정추천`·`업무/보고`·`답변추천`·`의견제안`·`감사문구`·`안심문구`·`일상/안부`·`마무리인사`·`동의/확인`·`응원/축하`·`현황/보고`·`정중요청`·`✨ AI완성` |
| 개인 n-gram 예측 | `input/ai/PersonalNgramModel.kt` (663줄), `PersonalNgramTokenizer.kt`, 참조 `FcitxInputMethodService.kt:1765,3215,3448` | 언어 지문 기능에 종속 | **필요 추정.** 암호화 파일 저장소 사용 | 배지 `⭐` (`AiContextualPredictor.kt:499-500`) |
| 개인 문장 RAG (BM25) | `input/ai/rag/PersonalSentenceVault.kt:19-35`, 후보화 `AiContextualPredictor.kt:402-403`, 게이트 `input/ai/PersonalSentenceCompletionGate.kt` | 언어 지문 기능에 종속 | **필요 추정.** 암호화 파일 저장소 사용 | 최대 3,000문장, 반감기 45일. 주석: "No LLM, no embeddings, no network". 배지 `✨ 내기록` |
| 문장 팩 | `input/ai/sentencepack/SentencePackRepository.kt`, `SentencePackCatalog.kt`, `SentencePackIndex.kt`, `SentencePackCsv.kt`, UI `ui/main/settings/behavior/SentencePackDialog.kt`, 요약 `PrivacyAiSettingsFragment.kt:150-164,375-405`, 자산 `app/src/main/assets/sentence-packs/ko-basic-v1.txt` | `문장 팩` 설정 항목 | 기본팩은 불필요, 추가 팩 다운로드는 **필요** | 기본팩 10,093 bytes 오프라인 번들. 프로젝트 자체 작성 예문(`sentence-packs/NOTICE.md`: "외부 말뭉치를 복제하지 않고 프로젝트에서 작성한 기본 예문"). 배지 `기본문장`. 오프라인 모드 켜면 다운로드 즉시 취소 (`PrivacyAiSettingsFragment.kt:143-146`) |
| AI 어시스턴트 창 | `input/ai/AiAssistantWindow.kt` (708줄), `AiAssistantUi.kt` (1123줄), 툴바 버튼 `ButtonsBarUi.kt:180`, 연결 `input/bar/KawaiiBarComponent.kt:482` | 툴바 AI 버튼 | 조건부. 로컬 문맥 완성만 쓰면 불필요, 외부 AI는 **필요** | `docs/gemma-context-ime-2026-09-12.md:46`: "AI 도구 버튼은 debug 로컬 기능의 개인정보·앱 정책 조건을 만족해도 열 수 있다. 외부 AI 실행의 네트워크 차단은 그대로 유지한다." |

**소계: 14개**

### 2.1 추천 소스·배지 전수 (후보 바에 나타나는 출처 라벨)

| source 코드 | badge | 정의 위치 |
|---|---|---|
| `typo_personal` / `typo_keyboard` / `typo_keyboard_stem` / `typo_sentence_correction` / `typo_word_correction` / `typo_correction` | `✏️` | `input/ai/AiContextualPredictor.kt:171-323` |
| `personalized_style` | `✨ 내스타일` | `AiContextualPredictor.kt:377-378` |
| `rag_personal` | `✨ 내기록` | `AiContextualPredictor.kt:402-403` |
| `choseong_abbrev` | `⚡` | `AiContextualPredictor.kt:444-445` |
| `collocation_next_word` | `✨ AI완성` / `✨ AI단어` | `AiContextualPredictor.kt:474-475` |
| `personal_ngram` | `⭐` | `AiContextualPredictor.kt:499-500` |
| `base_vocab` | (없음) | `AiContextualPredictor.kt:525-526` |
| `base_lexicon` | `✨ AI완성` / `✨ AI단어` | `AiContextualPredictor.kt:547-548` |
| `discourse_continuation` | `이어쓰기` | `input/ai/ImmediateContextualPredictions.kt:33-34` |
| `ondevice_generated_spacing` | `기기 AI 띄어쓰기` | `ImmediateContextualPredictions.kt:49-50` |
| `sentence_pack` | `기본문장` | `ImmediateContextualPredictions.kt:62-63` |
| `ondevice_generated` | `기기 AI 재료` | `ImmediateContextualPredictions.kt:75-76` |
| `llm_cached` | `✨ AI완성` / `✨ AI단어` | `ImmediateContextualPredictions.kt:100-101` |
| `email_domain` | `📧` | `FcitxInputMethodService.kt:3630-3631,3642-3643` |
| `url_tld` | (없음) | `FcitxInputMethodService.kt:3677-3678` |
| 자동 온디바이스 후보 | `Gemma 생성` / `Gemma 이어쓰기` | `HorizontalCandidateComponent.kt:255-259` |

---

## 3. 컴패니언·네트워크 기능

| 기능 | Android 근거 파일:줄 | 설정 이름 | Full Access 필요 여부 추정 근거 | 비고 |
|---|---|---|---|---|
| AI 글쓰기 액션 | `input/ai/AiAction.kt:12-99`, 실행 `input/ai/OpenAiResponsesClient.kt`, 적용 `input/ai/AiEditorTransaction.kt`, `AiResultApplyPolicy.kt`, diff `AiTextDiff.kt` | `AI 공급자 설정` (`PrivacyAiSettingsFragment.kt:166-185`) | **필요.** HTTPS 전송 | 액션 16종: `Proofread`·`Polite`·`Casual`·`Business`·`Decline`·`Apology`·`CustomerService`·`Compose`·`Reply`·`Custom`·`ContinueTyping`·`TranslateEnglish`·`TranslateKorean`·`TranslateJapanese`·`TranslateChinese`·`GraphEnrich`. 결과 적용 4갈래: 교체·뒤에 넣기·복사·취소 |
| 컴패니언 자동 발견 (mDNS) | `input/ai/AiProviderDiscovery.kt` (234줄), `AiProviderDiscoveryManifest.kt:1-30`, `AiProviderManifestFailure.kt`, `AiProviderSetupActivity.kt` (457줄) | `내 컴퓨터 자동으로 찾기 (추천)` | **필요.** DNS-SD/mDNS 로컬 네트워크 검색 + HTTPS 매니페스트 검증 | service type `_saegeul-ai._tcp.`. TXT의 `manifest` 값만 신뢰하고 mDNS 이름·IP는 신뢰하지 않음. 매니페스트는 `https://<trusted-host>/.well-known/saegeul-ai-provider` 고정 (SSOT 8.2.3). 예시 `docs/examples/saegeul-ai-provider-manifest.debug.json` |
| Tailscale Serve HTTPS 경계 | `docs/korean-smart-input-ssot.md:1333-1350,1438-1443` | 없음 (앱 밖 구성) | **필요** | loopback gateway `127.0.0.1:9211`, tailnet 공개면 Tailscale Serve HTTPS `:9210`, 발견 `_saegeul-ai._tcp.local.`. SSOT: "public host뿐 아니라 loopback, RFC1918, CGNAT/Tailscale IP, MagicDNS, `.ts.net`에도 평문 HTTP 예외를 두지 않는다" |
| PC 컴패니언 설치·실행 | `companion/windows/{install.bat,install.ps1,start.bat,uninstall.bat,uninstall.ps1}`, `companion/macos/{install.command,install.sh,start.command,uninstall.sh}`, `companion/linux/{install.sh,start.sh,uninstall.sh}`, `scripts/ai-provider-companion.py` | 앱 밖 | 해당 없음 (PC 측) | Windows 작업 스케줄러 / macOS LaunchAgent / systemd user 서비스. PC에 로그인된 Codex·Claude Code·AGY CLI를 stdio로 재사용. grant는 `%LOCALAPPDATA%/Saegeul/ai-companion-oauth.bin`에 DPAPI current-user 범위로 저장 |
| endpoint OAuth (`AI-07`) | `input/ai/AiOAuthLoginActivity.kt` (207줄), `AiOAuthRefreshPolicy.kt`, `AiOAuthSessionIdentity.kt`, `AiOAuthStartFailure.kt`, `AiProviderCredentialStore.kt` (234줄) | `AI 공급자 설정` | **필요.** 외부 브라우저 + Keystore 저장 | public client Authorization Code + PKCE S256, 외부 브라우저(Custom Tab). API key와 상호 배타 (SSOT 8.2.1) |
| BYOK 글쓰기 공급자 | `input/ai/AiProviderProfile.kt:1-30` (`AiEndpointPolicy`), `OpenAiResponsesClient.kt` (453줄), `AiAuthorization.kt` | `AI 공급자 설정` | **필요** | 키 저장 위치 `noBackupFilesDir/ai/provider.bin`. HTTPS만 허용 |
| 음성 받아쓰기 3모드 | `input/voice/VoiceProviderProfile.kt`, `VoiceTranscriptionWindow.kt`, `OpenAiRealtimeTranscriptionClient.kt`, `OpenAiTranscriptionClient.kt`, `PcmStreamRecorder.kt`, `PcmMemoryRecorder.kt`, `VoiceDisclosureConsentStore.kt`, `VoicePermissionActivity.kt`, 툴바 버튼 `ButtonsBarUi.kt:184` | `음성 공급자 설정`, 기본 모드 `DeviceDictation` | 기본 모드 불필요(시스템 음성 IME 전환), 온라인 2모드 **필요** | 모드 3종 `DeviceDictation`·`OpenAiRealtime`(24kHz PCM WSS)·`OpenAiApi`(16kHz WAV HTTPS, 최대 5분). 녹음은 메모리에서만 처리, `RECORD_AUDIO`는 `exported=false` 투명 Activity가 요청 |
| 화자 분리 회의 전사 | `input/voice/OpenAiDiarizationClient.kt`, `MeetingTranscriptionWindow.kt`, `MeetingTranscriptionRuntime.kt`, `MeetingAudioSource.kt`, `MeetingDiarizationContract.kt` | 위와 동일 profile 재사용 | **필요** | 최대 24MB·60분 파일. 별도 고지 동의 후 시스템 파일 선택기 |
| 온디바이스 OCR + 모델 다운로드 | `input/ocr/TesseractKoreanOcrEngine.kt`, `OcrModelManager.kt:20-32`, `OcrWindow.kt`, `OcrDocumentActivity.kt`, `OcrImageSource.kt`, 툴바 버튼 `ButtonsBarUi.kt:188` | `한국어 모델 받기` | 인식 자체는 불필요, 모델 다운로드는 **필요** | 다운로드 호스트를 `raw.githubusercontent.com`으로 고정하고 query/fragment/userInfo 금지, SHA-256 검증 (`OcrModelDescriptor.validate`) |
| GIF 4공급자 | `input/gif/GifSearchWindow.kt`, 설정 `PrivacyAiSettingsFragment.kt:215-261`, 툴바 버튼 `ButtonsBarUi.kt:192` | `GIF 공급자 설정`, `KLIPY 설정`, `GIPHY 설정` | Animated Noto Emoji 외 **필요** | 4종: Animated Noto Emoji(검색어 미전송) / Wikimedia Commons(키 불필요) / KLIPY / GIPHY. 결과·캐시 namespace를 섞지 않음. 오프라인 모드에서 네트워크 공급자 자동 차단 |
| 개인 지식그래프 강화 | `input/ai/rag/PersonalGraphEnricher.kt:17-31`, `GraphEnrichAutoPolicy.kt:12-35`, `GraphEnrichmentRunner.kt`, `GraphEnrichmentStatusStore.kt`, `PersonalGraphStore.kt`, 설정 `AppPrefs.kt:53-57` | `graph_enrich_auto`, 기본 **꺼짐** | **필요.** 확정 문장 묶음을 HTTPS AI 공급자로 전송 | 자동 실행 조건: 신규 30문장 이상(`MIN_NEW_SENTENCES`) + 직전 강화 후 24시간 경과(`MIN_INTERVAL_MS`). 결과는 Keystore 암호화 저장, 요청은 `store=false` |
| 완전 오프라인 모드 (`SEC-03`) | 설정 `AppPrefs.kt:47-52`, 게이트 `FcitxInputMethodService.kt:1796-1804,1907-1919`, `input/InputFeatureBlock.kt:14-23`, 앱 초기화 `FcitxApplication.kt:85`, 프로필 `input/profile/AppKeyboardProfile.kt:99` | `offline_mode` | 해당 없음 | 차단 사유 3종 `PrivateEditor`·`OfflineMode`·`AppPolicy`. `PrivateEditor`는 설정으로 해제 불가한 개인정보 보증이라 fix-it 액션을 주지 않음 (`InputFeatureBlock.kt:10-12`) |

**소계: 12개**

---

## 4. Typing DNA·광고·대시보드

| 기능 | Android 근거 파일:줄 | 설정 이름 | Full Access 필요 여부 추정 근거 | 비고 |
|---|---|---|---|---|
| 확정 문장 수집기 | `input/ai/UserTypingContextCollector.kt:7-26`, `input/ai/TypingDnaCommitSink.kt`, 연결 `FcitxInputMethodService.kt:3422` | 없음 (일반 입력창에서 상시) | 불필요 | 패키지별 최근 5문장·최대 300자 슬라이딩 윈도, 최소 트리거 6자. 한국어 종결어미 인식 목록 보유 |
| PII 스크러버 | `input/ai/KoreanPiiScrubber.kt:15-40` | 없음 | 불필요 | 전화·주민등록번호·신용카드·계좌·이메일·OTP 패턴. 스테이징 버퍼나 프로파일러에 들어가기 전에 치환 |
| 스테이징 금고 | `input/ai/TypingDnaVault.kt:15-40`, 암호 `input/ai/vault/KeystoreVaultCipher.kt`, `AesGcmVaultCipher.kt`, `EnvelopeVaultCipher.kt`, `VaultFileStorage.kt` | 없음 | **필요 추정.** 암호화 파일 저장소 | 카테고리 3종 `messenger`/`work`/`general`, 카테고리당 임계 15문장·최대 50문장. 처리기가 정상 반환한 뒤에만 삭제 |
| 언어 지문 프로파일러 | `input/ai/TypingDnaProfiler.kt:10-40`, `TypingDnaProfile.kt`, `TypingDnaCompiler.kt` | 없음 | 로컬 통계 폴백은 불필요, LLM 경로는 **필요** | 말투(Honorific/Informal)·습관 종결어미·빈출 bigram·상용 문장 추출. LLM 없이 동작하는 온디바이스 통계 폴백 보유 |
| 프로파일 저장소 | `input/ai/TypingDnaRepository.kt:14-39`, `input/ai/vault/VaultFile.kt` | 없음 | **필요 추정** | AES-256-GCM, 가능하면 StrongBox. 클라우드 백업·기기 이전 제외 (`docs/independent-fork/privacy-data-safety.md:32`) |
| 즉시 분석·동기화 | `input/ai/TypingDnaInstantSync.kt`, `TypingDnaSyncStatus.kt`, 서비스 `FcitxInputMethodService.kt:176-187,1691-1712`, UI `PrivacyAiSettingsFragment.kt:281-290` | `지금 언어 지문 분석 및 동기화` | 조건부. 그래프 강화가 같이 실행되면 **필요** | |
| 언어 금고 대시보드 | `ui/main/ai/TypingDnaDashboardActivity.kt`, `DashboardSnapshot.kt:63,151-164`, `TypingDnaCardPreference.kt`, `TypingDnaCardSnapshot.kt`, `TypingDnaChartView.kt`, `VaultTimelineView.kt`, `CelebrationOverlayView.kt` | 본체 앱 화면 | 해당 없음 (본체 앱) | 레벨·데이터 축적 현황·톤 밸런스 그래프 |
| 홈 화면 위젯 | `ui/main/ai/VaultWidgetProvider.kt:23-35`, `res/xml/vault_widget_info.xml` | 시스템 위젯 추가 | 해당 없음 | 레벨·진행·누적 표시, 탭하면 대시보드 진입 |
| 습관·스트릭 | `input/ai/VaultHabitTracker.kt:9-15` | 없음 | 해당 없음 | 연속일·스트릭 프리즈·오늘/어제 분석 문장 수. 설계 근거 `docs/ai-vault-engagement-design.md` 3절 |
| 레벨 곡선·포인트 | `input/ai/TypingDnaLevelCurve.kt`, `data/points/LevelRewardStore.kt` | 없음 | 해당 없음 | |
| 전면 광고 게이트 | `ads/TypingDnaAdGate.kt:12-60`, `ads/AdServingPolicy.kt`, `ads/AvenueFrequencyPolicy.kt`, `ads/AvenueFrequencyStore.kt`, `ads/LocalAvenueCatalog.kt`, `ads/TypingDnaInterstitialController.kt:53,128` | 없음 (오프라인 모드에 종속) | **필요** | placement 3종 `TYPING_DNA_SYNC_COMPLETE`·`TYPING_DNA_DASHBOARD_BANNER`·`THEME_POINT_EARN`. `if (offlineMode) return false` (`TypingDnaAdGate.kt:59`). 언어 지문 즉시 분석 완료 뒤에만 요청 |
| 대시보드 배너 광고 | `ads/DashboardBannerController.kt:50,122` | 위와 동일 | **필요** | 대시보드 한정 |
| 리워드 광고 (테마 포인트) | `ads/ThemePointRewardedController.kt:124,132` | 위와 동일 | **필요** | SDK `play-services-ads` 24.7.0 (`gradle/libs.versions.toml:15,56`), 광고 단위 ID는 Gradle property 주입 (`app/build.gradle.kts:147-171`) |
| AI 사용량 집계 | `input/ai/AiUsageStore.kt` (241줄), UI `PrivacyAiSettingsFragment.kt:316-326` | `AI 사용량 초기화` | 불필요 | 주석: 입력·출력·자격증명을 포함하지 않는 집계 전용. 성공/실패와 글자 수만 기록 |

**소계: 14개**

---

## 5. SSOT 원칙 원문 인용

### 5.1 변경 불가 제품 원칙 — `docs/korean-smart-input-ssot.md:159-179`

> 1. 입력·첨부·링크 삽입은 사용자 동작 하나당 성공 경로에서 정확히 한 번만 실행한다.
> 2. 전송 성공을 확인할 수 없는 경로 뒤에 다른 전송 방식을 자동 실행하지 않는다.
> 3. password, sensitive, `IME_FLAG_NO_PERSONALIZED_LEARNING` editor에서는 네트워크 검색, AI, 개인화, clipboard 기록, rich content 다운로드를 실행하지 않는다.
> 4. editor 또는 selection identity가 바뀐 뒤 이전 결과를 자동 제출하지 않는다.
> 5. AI는 사용자가 AI 툴바를 명시적으로 연 뒤에만 editor 원문을 capture한다. 선택이 있으면 선택을 우선하고, 없으면 complete `ExtractedText`의 전체 editor를 우선 사용한다. 전체 추출이 불가·null·partial·stale이면 최대 4,000자의 커서 주변 문단으로 fail-closed fallback한다. private/sensitive/`IME_FLAG_NO_PERSONALIZED_LEARNING`/offline editor는 원문 capture·network 모두 0회이며, 원문은 preview·요청 외 저장하거나 로그에 남기지 않는다.
> 6. 실제 입력 원문, API key, clipboard 원문, 음성, 다운로드 URL query를 일반 로그에 남기지 않는다.
> 7. 공급자별 결과, attribution, API key, 캐시 정책을 한 그리드나 저장소에 무단 혼합하지 않는다.
> 8. 지원하지 않는 editor나 engine을 지원한다고 표시하지 않는다.
> 9. 사용자 데이터 백업에 API key, ephemeral token, 민감 빠른 문구 평문, 임시 GIF를 포함하지 않는다.
> 10. 링크·텍스트·rich content는 가능한 경우 clipboard를 거치지 않고 `InputConnection`으로 전달한다.
> 11. AI 지시문, GIF·통합 검색 등 IME가 소유한 모든 text 입력은 현재 `KeyboardWindow`의 layout, Fcitx 조합, 후보, 숫자·기호 전환, theme, 높이를 재사용한다. 기능별 두벌식 복제판을 만들지 않는다.

### 5.2 `KO-09` 한글 어절 자동완성 계약 — `docs/korean-smart-input-ssot.md:133-151`

> 2. 후보는 기기 안의 정적 사전만 사용한다. MVP에서는 입력 원문·선택 이력·앱 package를 저장하거나 네트워크로 보내지 않으며 개인 학습은 `KO-06`으로 분리한다.
> 5. 후보는 자동 적용하지 않는다. 카드 tap, 숫자 키 또는 사용자가 Tab으로 후보를 명시적으로 고른 뒤 Enter를 누른 경우에만 적용한다. 후보가 미선택인 Enter·space·문장부호는 현재 어절을 그대로 확정한다.
> 6. 선택 시 아직 조합 중인 부분을 초기화하고, 이미 editor 또는 buffered transport로 빠진 접두어 뒤의 접미부만 `commitString`으로 정확히 한 번 보낸다. 실패 뒤 다른 삽입 방식을 자동 시도하지 않는다.
> 7. backspace, cursor 이동, focus 변경, input method 변경, reset 뒤에는 추적 접두어와 후보를 함께 정리해 이전 editor의 후보를 재사용하지 않는다.
> 8. password, sensitive, `NoSpellCheck` editor와 한자 모드에서는 후보를 만들지 않는다. 사전이 없거나 손상됐을 때는 입력 자체를 막지 않고 자동완성만 fail-closed한다.

### 5.3 `KO-02` 초성 통합 검색 계약 — `docs/korean-smart-input-ssot.md:295-308`

> 5. 결과를 탭하면 조합을 먼저 안전하게 확정하고 `commitText`를 정확히 한 번 호출한 뒤 일반 키보드로 돌아간다. editor identity가 바뀌었으면 삽입하지 않고 오류를 표시한다.
> 6. sensitive clipboard 항목은 검색 repository 단계에서 제외한다. password, sensitive, no-personalized-learning editor에서는 통합 검색 전체를 열어도 데이터 조회와 삽입을 차단한다.
> 7. 검색은 전부 기기 안에서 수행하며 query나 결과 원문을 로그·분석·네트워크로 보내지 않는다.
> 8. 빈 query는 전체 clipboard를 노출하지 않고 검색 안내만 표시한다. 결과 수에는 상한을 둔다.

### 5.4 `KO-03A` 자동 스니펫 계약 — `docs/korean-smart-input-ssot.md:344-353`

> 3. trigger는 문장 시작 또는 공백 뒤에서 시작해 cursor 바로 앞에서 정확히 끝나야 한다. URL·시간·emoji shortcode의 일부처럼 앞 문자가 붙은 `:` 문자열은 확장하지 않는다.
> 4. space 경계는 trigger를 확장 결과로 교체하고 공백 하나를 유지한다. Enter 경계는 채팅 전송 사고를 막기 위해 확장만 하고 해당 Enter를 소비하며, 사용자가 다시 Enter를 눌러야 전송 또는 줄바꿈된다.
> 5. 일반 composing과 한글 buffered compatibility 경로 모두 trigger를 정확히 한 번 삭제하고 결과를 정확히 한 번 삽입한다. buffered 경로는 trigger 앞의 보류 중인 일반 텍스트를 보존한다.
> 6. 자동 스니펫은 기본 켬 toggle을 제공한다. password, sensitive, no-personalized-learning editor에서는 상용구와 주변 텍스트를 읽지 않고 완전히 비활성화한다.
> 7. 개인 profile은 기존 Keystore·`noBackupFilesDir` 정책을 그대로 사용한다. 값이 비었거나 복호화에 실패하거나 editor/selection이 바뀐 경우 literal trigger와 경계키를 보존하고 자동 대체하지 않는다.

### 5.5 온디바이스 재료 생성 계약 — `docs/korean-smart-input-ssot.md:26`

> 생성 재료 기반 교정의 첫 범위는 **명시적으로 선택하는 띄어쓰기 제안**이다. 입력한 완결 문장과 저장 문장의 글자·문장부호가 모두 같고 ASCII 공백 위치만 다르며 저장소의 정답 후보가 하나일 때만 제시한다. 글자·조사·어미를 추측해서 바꾸지 않는다. 입력 중에는 메모리 인덱스만 조회하며 사용자 입력을 모델이나 별도 저장소로 보내지 않는다. 제안에는 `기기 AI 띄어쓰기` 출처를 표시한다. 적용은 캡처한 editor 세션·선택 위치·원문이 현재와 모두 일치할 때 한 번만 허용하며 민감 입력에서는 조회·적용을 차단한다. 정식 앱 승격은 포함하지 않는다.

### 5.6 자동 문맥 추천 개인정보·상태 경계 — `docs/gemma-automatic-context-2026-09-12.md:16-21`

> - 기능 활성화는 별도 opt-in이며 설정에서 처리 범위를 설명한다. 비밀번호·민감 필드·학습 금지·앱 차단 정책을 그대로 적용한다. 완전 오프라인에서 동작하며 외부 공급자 fallback이 없다.
> - 앱 정보는 현재 EditorInfo의 패키지와 필드 종류 등 입력 대상 메타데이터로 한정한다. 화면 수집, 클립보드, 위치, 다른 앱 기록, 개인 금고를 자동으로 읽지 않는다. 앱 종류를 확실히 알 수 없으면 일반 문맥으로 처리한다.
> - 원문·응답·세션 캐시는 메모리 전용이다. 공개 은행, 학습 저장소, 디스크 캐시, 로그에 넣지 않는다. 기기 증거에는 사전에 정한 공개 입력만 기록한다.
> - 캐시 키는 앱·field ID·IME 세션·편집 revision·정확한 원문·커서다. 세션 전환, 숨김, 개인정보 제한, 선택·커서 이동, 삭제·치환은 요청과 캐시를 폐기한다. 원문이 되돌아와도 이전 요청을 되살리지 않는다.
> - 자동 관측이 한글 조합을 강제로 확정해서는 안 된다. 조합 중 상태를 포함한 snapshot·적용 계약은 별도 통합 검증을 거친다. 후보 적용은 사용자 터치와 현재 snapshot 재검증이 필요하다.

### 5.7 명시 문맥 완성 확정 경계 — `docs/gemma-context-ime-2026-09-12.md:44-53` (발췌)

> - 기존 AI 패널에서 별도 명시 동작으로 문맥 완성을 연다. 원문을 먼저 보여주고 사용자가 실행을 눌러야 모델에 전달한다. 자동 입력·공개 재료 조회에 모델 생성을 끼워 넣지 않는다.
> - 최초 지원 범위는 커서가 문맥 끝에 있고 선택 영역이 없는 미완성 입력이다. 선택 영역이나 중간 커서는 안내 후 거부하며 임의로 이동하지 않는다. 문맥은 최대 2048자다.
> - 검토한 문맥만 메모리에서 처리한다. 공개 은행·개인 금고·클립보드를 프롬프트에 합치거나 원문·응답을 로그에 저장하지 않는다. 외부 공급자 fallback은 없다.
> - `PUBLIC_MATERIAL`은 키보드 활성 중 거부·취소한다. 사용자가 누른 `EXPLICIT_CONTEXT`만 기존 목적별 단일 native lease를 사용한다. 배터리 20% 이상, 절전 아님, 메모리 여유, 확인 가능한 열 상태가 MODERATE 미만인 조건을 시작과 실행 중 검사한다.
> - 입력·커서·선택·세션 변경, 화면 종료, 사용자 취소는 ticket을 무효화하고 해당 native run을 취소한다. 변경 후 원래 값으로 돌아와도 이전 요청을 살리지 않는다. 늦게 도착한 결과는 폐기한다.

### 5.8 AI text 읽기와 교체 — `docs/korean-smart-input-ssot.md:1464-1486`

> - IME가 임의의 chat bubble이나 화면 전체를 읽을 수 있다고 가정하지 않는다.
> - 사용자가 AI 툴바를 연 뒤에만 `InputConnection`을 읽는다. 선택이 있으면 선택문을 사용한다.
> - 선택이 없으면 `getExtractedText()`가 `startOffset=0`, `partialStartOffset=-1`, 4,000자 이하인 complete editor를 제공할 때만 전체 입력칸을 source로 사용한다. null, partial, oversized 또는 stale extract는 전체 입력칸으로 표시하지 않는다.
> - 전체 추출 fallback은 커서 전·후 값을 각각 보존한 최대 4,000자 cursor context다.
> - 상대 메시지 기반 답장은 사용자가 선택, 복사 또는 share한 텍스트만 사용한다.
> - AI 결과는 원문, diff, 후보를 보여주고 `교체`, `뒤에 넣기`, `복사`, `취소`를 구분한다.
> - 전체/주변 source 교체는 `deleteSurroundingText(before, after)`와 `commitText()`를 같은 batch에서 정확히 한 번 수행하고, undo는 원문과 capture 당시 cursor split을 복원한다. 실패 시 clipboard 또는 다른 삽입 방식으로 fallback하지 않는다.

### 5.9 민감 입력창 차단 범위 — `docs/wiki/07-Privacy-Security-and-Keystore.md:36-42`

> - 비밀번호 필드 (`TYPE_TEXT_VARIATION_PASSWORD`)
> - 민감 정보 필드 (`TYPE_TEXT_FLAG_NO_SUGGESTIONS`)
> - 개인화 학습 금지 플래그 (`IME_FLAG_NO_PERSONALIZED_LEARNING`)
>
> 위 플래그가 지정된 입력창에서는 **오타 검사, 자동완성 후보, 클립보드 기록, AI 툴바, 네트워크 연결이 하드웨어 레벨에서 일괄 차단**됩니다.

코드 대응: `FcitxInputMethodService.kt:1787-1794` (`allowsTextInspectionFeatures`), `:1796-1799` (`allowsNetworkInputFeatures`), `:1801-1804` (`allowsAiInputFeatures`), `:1806-1808` (`allowsOnDeviceContextCompletionFeatures`), native 측 `plugin/hangul/src/main/cpp/fcitx5-hangul/src/candidatepolicy.h:28-43` (`allowKoreanCompletion`, `allowKoreanNextWord`).

### 5.10 개인정보 경계 — `docs/independent-fork/privacy-data-safety.md` 2절 발췌

| 기능 | 처리 위치·수신자 | 시작 조건 | 사용자 제어 | Play 분류 |
|---|---|---|---|---|
| 일반 한글 입력 | 기기 내 Fcitx/libhangul | 키보드 사용 | 비밀번호·민감·개인화 금지 입력창에서 검사 기능 차단 | 수집 안 함 |
| 클립보드 기록 | 앱 전용 Room DB | 새 설치 기본 `OFF` | 기능 끄기, 개별·전체 삭제 | 기기 밖 수집 안 함 |
| 개인 단어장 | 백업 제외 앱 저장소 | 명시적 추가 | 추가·삭제 | 수집 안 함 |
| 언어 지문·개인화 예측 | 기기 내 앱 저장소. Android Keystore 하드웨어 키(AES-256-GCM, 가능하면 StrongBox)로 암호화하고 클라우드 백업·기기 이전에서 제외 | 일반 입력창에서 한글 입력 | 민감 입력창 차단, 언어 지문 초기화 버튼으로 전체 삭제 | 수집 안 함 |
| 한글 OCR | 기기 내 Tesseract | 명시적 이미지 선택 | 결과 검토·선택·취소 | 사진·사용자 콘텐츠 수집 안 함 |
| OCR 모델 | GitHub raw content | 사용자가 `한국어 모델 받기` 선택 | 전체 오프라인 모드, 앱별 네트워크 정책 | 대략적 위치(IP 추론) |
| 선택 문장팩 | GitHub raw content | 설정에서 사용자가 다운로드를 선택할 때만. 자동 다운로드·원격 업데이트 조회 없음 | 오프라인 모드, 다운로드 취소·재시도·삭제 | 대략적 위치 |
| 글쓰기 AI | OpenAI 또는 사용자가 설정한 HTTPS AI 공급자 | 정확한 원문·공급자 고지 뒤 작업 버튼 | 오프라인 모드, 앱별 차단, 키 삭제 | 기타 사용자 생성 콘텐츠 + 대략적 위치 |
| 개인 지식 그래프 강화 | 사용자가 설정한 HTTPS AI 공급자(본인 PC 컴패니언 포함) | 대시보드 수동 실행, 또는 「자동 강화」(기본 꺼짐)를 켠 경우 신규 30문장·24시간 조건 | 오프라인 모드, 자동 강화 끄기, 언어 지문 초기화 | 기타 사용자 생성 콘텐츠 + 대략적 위치 |
| 정밀/실시간/회의 음성 전사 | 설정한 OpenAI 호환 HTTPS·WSS 음성 공급자 | 별도 고지 동의 → 마이크 권한 → 녹음 | 취소, 권한 철회, 키 삭제 | 음성 또는 소리 녹음 + 대략적 위치 |
| GIF 공급자 | Google Fonts / Wikimedia / KLIPY / GIPHY | GIF 도구 진입 또는 검색 | 오프라인 모드, 공급자 변경, 캐시 삭제 | 앱 내 검색 기록 + 대략적 위치 (Noto는 검색어 미전송) |
| AdMob 전면 광고 | Google AdMob | 언어 지문 「지금 즉시 분석 및 동기화」 완료 뒤 | 전체 오프라인 모드, 광고 ID 재설정 | 대략적 위치 + 기기/기타 ID + 앱 상호작용 |

Play Data Safety 데이터 유형 6종(모두 선택·공유 `예`): 대략적 위치, 앱 내 검색 기록, 기타 사용자 생성 콘텐츠, 음성 또는 소리 녹음, 앱 상호작용, 기기 또는 기타 ID.

---

## 6. 번들 데이터 자산과 라이선스 고지

| 자산 | 크기 | 내용 | 라이선스 | 고지 파일 |
|---|---|---|---|---|
| `plugin/hangul/src/main/assets/usr/share/fcitx5/hangul/completion.txt` | 91,467 bytes / 5,250 표제어 | 어절 자동완성 사전 | **KOGL 공공누리 제1유형** (국립국어원) | `completion-NOTICE.md` (같은 디렉터리) |
| `plugin/hangul/src/main/assets/usr/share/fcitx5/hangul/nextword.txt` | 27,615 bytes / 486행 | 다음 어절 매핑 | 프로젝트 자체 작성 | `completion-NOTICE.md`. 메인 앱 번들에서는 빠지고, 쌍은 `ko-ngram.bin`의 구어 층으로 옮겼다 |
| `plugin/hangul/src/main/assets/usr/share/fcitx5/hangul/symbol.txt` | 11,236 bytes / 1,011행 | 기호 | libhangul upstream | — |
| `plugin/hangul/src/main/assets/usr/share/libhangul/hanja/hanja.txt` | 6,756,043 bytes | 한자 음훈 | libhangul upstream | AboutLibraries |
| `plugin/hangul/src/main/assets/usr/share/libhangul/hanja/mssymbol.txt` | 11,269 bytes | 기호 변환 | libhangul upstream | — |
| `app/src/main/assets/korean/dictionary.bin` | 3,414,000 bytes | 오프라인 국어사전 | **CC BY-SA 4.0** (한국어 위키낱말사전) | `app/src/main/assets/korean/dictionary-ATTRIBUTION.txt` |
| `app/src/main/assets/ko_base_vocab.tsv` | 2,387,931 bytes / 150,000행 | 어절 완성·오타 교정 기본 어휘 | ODC-By 1.0 (FineWeb-2) + MIT (ChatbotData) | 파일 1행 주석, `legal/NOTICE.txt` |
| `app/src/main/assets/korean/ko-ngram.bin` | 8,808,880 bytes | 어절 bigram·trigram (KONGRAM1) | ODC-By 1.0 (FineWeb-2) + MIT (ChatbotData) | `legal/NOTICE.txt` |
| `app/src/main/assets/sentence-packs/ko-basic-v1.txt` | 10,093 bytes | 기본 문장팩 | 프로젝트 라이선스 | `app/src/main/assets/sentence-packs/NOTICE.md` |
| `app/src/main/assets/legal/DATA-PRIVACY.txt` | 8,055 bytes | 앱 내 개인정보 안내 | — | — |
| `app/src/main/assets/legal/NOTICE.txt`, `FORK-NOTICE.txt` | 1,115 / 981 bytes | 포크 고지 | — | — |

---

## 7. 툴바 진입점 (키보드에서 트리거되는 기능)

`input/bar/ui/idle/ButtonsBarUi.kt` 기준. 창 연결은 `input/bar/KawaiiBarComponent.kt`.

| 버튼 | ButtonsBarUi 줄 | 연결 창 (KawaiiBarComponent 줄) |
|---|---|---|
| 실행 취소 / 다시 실행 | :148, :152 | — |
| 커서 이동 | :156 | `TextEditingWindow` (:419) |
| 클립보드 | :160 | `ClipboardWindow` (:423) / `UnifiedTabExtensionWindow(CLIPBOARD)` (:427) |
| 한글 버퍼 | :164 | `BufferedHangulWindow` (:432) |
| 빠른 문구 | :168 | `SensitivePhraseWindow` (:461) |
| 한국어 통합 검색 | :172 | `KoreanSearchWindow` (:474) |
| 오타 복구 | :176 | `TypoRecoveryWindow` (:478) |
| AI 어시스턴트 | :180 | `AiAssistantWindow` (:482) |
| 정밀 받아쓰기 | :184 | `VoiceTranscriptionWindow` (:486) |
| OCR | :188 | `OcrWindow` (:490) |
| GIF | :192 | `GifSearchWindow` (:494) / `UnifiedTabExtensionWindow(MEDIA)` (:498) |
| 더보기 | :196 | `StatusAreaWindow` (:503) / `UnifiedTabExtensionWindow(SETTINGS)` (:507) |
| 행 확장 | :103-137 | 기본 1행, 명시적으로 누를 때만 2행 (`UX-03`) |

탭 시스템: `tab/TabManager.kt`, `tab/TabModel.kt`, `tab/UnifiedTabExtensionWindow.kt`, `tab/UserTabSyncManager.kt`.

---

## 8. 총계와 이식 관점 요약

**기능 총수: 60개**

| 영역 | 개수 |
|---|---|
| 1. 로컬(오프라인) 스마트 입력 | 20 |
| 2. 온디바이스 AI | 14 |
| 3. 컴패니언·네트워크 | 12 |
| 4. Typing DNA·광고·대시보드 | 14 |

Full Access 관점 분류(추정):

- 순수 로컬 계산 + 번들 자산만 사용 → **약 24개**
- 클립보드 또는 본체 앱과의 공유 암호화 저장소 때문에 필요 → **약 12개**
- 네트워크 전송이 본질 → **약 24개**

iOS 1차 범위(자판 + 로컬 스마트 입력, Full Access 없이)에 그대로 들어갈 후보는 영역 1의 20개 중 클립보드·공유 저장소에 걸리지 않는 **12개**다.

1. 한/영 오타 즉시 복구 (`KO-01`)
2. 조사 받침 자동 판별 (`KO-07`)
3. 한글 어절 자동완성 (`KO-09`)
4. 다음 어절 추천
5. 한자 음훈 변환 (`KO-05`)
6. 오프라인 국어사전 (`KO-05A`)
7. 한국식 감정표현 추천 (`KO-08`)
8. 이모지 한국어 키워드 검색
9. 앱별 키보드 profile (`KO-04`)
10. 한글 버퍼 호환 입력 (`KO-BASE-02`)
11. 실시간 오타 교정 엔진
12. 후보 바 (가로·2행·확장·부동)

이식 시 주의할 구조적 사실 두 가지.

- **엔진 계층 위치.** 어절 자동완성·다음 어절·한자 변환은 Kotlin이 아니라 `plugin/hangul/src/main/cpp/fcitx5-hangul`(포크된 C++ 서브모듈)에 있다. `completiondictionary.cpp`, `engine.cpp`, `candidatepolicy.h`가 핵심이며 iOS에서는 이 계층을 별도로 얹어야 한다. 저장소 규칙상 서브모듈은 이 저장소에서 직접 수정하지 않고 포크 저장소에서 고친다.
- **라이선스 고지 이관.** 번들 데이터 4종이 서로 다른 라이선스를 달고 있다. `completion.txt`는 KOGL 제1유형, `dictionary.bin`은 CC BY-SA 4.0, `ko_base_vocab.tsv`·`korean/ko-ngram.bin`은 ODC-By 1.0(FineWeb-2)과 MIT(ChatbotData), `ko-basic-v1.txt`는 프로젝트 라이선스다. iOS 앱 번들에도 각 고지를 함께 옮겨야 한다.
