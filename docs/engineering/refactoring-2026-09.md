# 코드 정리·리팩터링 설계 (2026-09)

detekt 1.23.8과 PMD CPD 7.13.0으로 `app/src/main/java`를 측정한 결과(위반 1,143건, 중복 블록 44개)를 바탕으로 정한 작업 단위와 원칙이다. 각 단위는 별도 커밋으로 들어가고, 워커 작업 패킷은 이 문서를 정본으로 삼는다.

## 공통 원칙

- **동작은 바꾸지 않는다.** 리팩터링 단위에서 동작 변경이 필요하면 별도 버그 수정 단위로 뺀다.
- **저장 형식은 바꾸지 않는다.** 설정 키, 암호화 파일 이름·Keystore 별칭·직렬화 형식, 백업(`.saegeulbackup`) 형식은 그대로 읽혀야 한다.
- **정본 하나.** 같은 일을 하는 코드가 둘 이상이면 한 곳(정본)만 남기고 나머지는 정본을 부른다. 정본은 그 개념을 소유한 패키지에 둔다. 공용 유틸 폴더(`utils/`)에는 도메인 로직을 두지 않는다.
- **계약(interface)은 교체 지점에만.** 구현이 둘 이상이거나 테스트에서 바꿔 끼워야 하는 지점에만 interface를 만든다. 구현이 하나뿐인 곳에 interface를 만들지 않는다.
- **파라미터 묶기.** 같은 인자 묶음이 두 곳 이상에서 같이 다니면 값 객체(data class)로 묶는다. 한 함수에만 있는 긴 파라미터는 먼저 함수를 쪼갤 수 있는지 본다.
- **외부 호출면 유지.** 여러 파일이 부르는 공개 메서드는 한 줄 위임으로 남겨 호출부 수정을 최소화할 수 있다. 위임만 남은 메서드는 다음 단위에서 호출부를 옮긴 뒤 지운다.
- **RedTeam 테스트는 고치지 않는다.** 리팩터링으로 RedTeam 테스트가 깨지면 리팩터링이 틀린 것이다.
- **JNI·upstream 경계는 그대로.** `core/Fcitx.kt`의 JNI 시그니처, upstream에서 온 `KeyDef` 계열 공개 모양은 이번 범위에서 바꾸지 않는다.

## 작업 단위

| 단위 | 대상 | 결정 |
|---|---|---|
| R0 | 전체 | 안 쓰는 private 속성 15개·안 쓰는 파라미터 5개 제거. 예외를 삼키는 곳 36개(`SwallowedException`)는 이유 주석이 없으면 `Timber` 로그를 남기거나 좁은 예외 타입으로 바꾼다. 빈 함수 블록 29개는 의도면 주석, 아니면 제거 |
| R1 | `input/gif/GifProviderCredentialStore`, `GiphyProviderCredentialStore`, `input/voice/VoiceProviderCredentialStore`, `PrivacyAiSettingsFragment`의 키 입력 다이얼로그 3개 | 암호화 자격증명 저장을 정본 `EncryptedCredentialStore` 하나로 합치고 공급자별 차이(파일 이름·별칭·검증 규칙)는 설정 값으로 넘긴다. 키 입력 다이얼로그는 정본 하나(`CredentialInputDialog`)로 합친다. 기존 저장 파일·별칭을 그대로 읽는지 테스트로 확인한다 |
| R2 | `input/ai/ondevice/OnDeviceContextCompletionRuntime` ↔ `OnDeviceSuggestionEngine` 중복, 도구 창 머리 UI 4곳(`OnDeviceContextCompletionWindow`, `DynamicPhraseUi`, `SensitivePhraseUi`, `TypoRecoveryUi`), `OcrUi` ↔ `MeetingTranscriptionUi` | 중복 블록을 정본 함수·정본 뷰 조각으로 합친다 |
| R3 | `input/candidates/**` | `FlexboxExpandedCandidateWindow`·`GridExpandedCandidateWindow` 공통부를 기반 클래스로. `HorizontalCandidateComponent`에서 Drawable·상태 줄·후보 병합을 분리하고 `renderCandidates`(240줄)를 단계별 함수로 |
| R4 | `input/keyboard/effects/**` | 효과 12종을 `ChromaEffectRenderer` 계약(한 효과 = 한 구현)으로 나누고, 그리기 인자(canvas·크기·팔레트·시간)는 `EffectFrame` 값 객체로 묶는다. `ParticleTouchOverlayView`와 겹치는 코드는 정본으로 |
| R5 | `input/keyboard/BaseKeyboard.createKeyView`(211줄), `KeyDrawable` | 키 종류별 뷰 생성 함수로 쪼갠다. 키 배경 그리기 인자는 `KeyBackgroundStyle` 값 객체로 |
| R6 | `input/ai/AiContextualPredictor`(predict 428줄, 의존 13개), `KoreanSemanticSentencePredictor`(335줄 함수) | 후보 소스를 `CandidateSource` 계약으로 나누고 predict를 "소스 수집 → 병합 → 재순위 → 필터" 단계로 쪼갠다. 존댓말·반말 판정은 정본 하나로. 기존 예측 테스트 결과가 같아야 한다 |
| R7 | `ui/main/settings/behavior/PrivacyAiSettingsFragment`, `ui/main/settings/theme/CustomThemeActivity`, `ui/main/ai/TypingDnaDashboardActivity`, `data/prefs/ManagedPreferenceCategory`(int·twinInt 파라미터 13개) | 화면을 섹션 단위 클래스로 나누고, SeekBar 리스너 등 반복은 정본 도우미로. 설정 항목 생성 인자는 명세 값 객체로 |
| R8 | 입력칸 식별 인자 묶음 | 패키지명·필드 ID·입력 타입·선택 범위를 따로 넘기는 곳(`FcitxInputMethodService.matchesCurrentEditor`, `InternalPromptCapture.matches`, `EngineRestartEditorRehydrationGate`, `OnDeviceSuggestionPolicy`, `OnDeviceAutomaticEditorSnapshot`)을 정본 값 객체 `EditorIdentity` 하나로 |
| R9 | `input/FcitxInputMethodService`(5,491줄) | 자동 제안·컨텍스트 완성·예측/후보 커밋·개인 학습·내부 프롬프트·정책 게이트를 컨트롤러로 분리. 자세한 경계는 아래 |
| R10 | `data/backup/VaultBackup`(export/import 파라미터 7개), `utils/BackgroundProgressNotifier`, `AiEditorTransaction`(복원 범위 인자) | 인자 묶음을 값 객체로(`VaultBackupPaths`, `ProgressSpec`, `SelectionRestore`) |
| R11 | `input/`·`input/ai/`·`ui/main/ai/`·`ui/main/settings/`·`utils/` 패키지 | 흩어진 파일을 주제별 하위 패키지로 옮긴다(`input/buffered`, `input/prompt`, `input/policy`, `input/ai/typingdna`, `input/ai/typo`, `ui/main/ai/dashboard` 등). 마지막 단위로 한다 |

## R9 — 서비스 분할 경계

- 서비스 본체에 남는 것: Android 콜백, fcitx 이벤트 처리, 키 처리, 커서·선택·커밋 헬퍼, 버퍼드 한글. 이 넷이 공유 상태의 중심이다.
- 분리하는 컨트롤러와 소유 상태:
  - `AutomaticSuggestionController`: `automaticSuggestion*` 필드, TTL·숨김 유예 타이머(자기 Handler), 워밍업 Job
  - `OnDeviceContextCompletionController`: 스냅샷 무효화 리스너, 추출 텍스트 모니터
  - 두 컨트롤러가 함께 쓰던 추출 텍스트 토큰 카운터는 작은 공유 객체 `ExtractedTextTokens`로
  - `ContextualPredictionController`: 예측 캐시·Job·후보 커밋·지표 기록
  - `PersonalLearningController`: TypingDNA 관찰·교정 경계·n-gram/교정 저장 디바운스(자기 Handler)
  - `InternalPromptController`: 캡처 상태·게이트. 엔진 재시작 때 서비스가 `resetForEngineRestart()` 하나만 부른다
  - `InputFeaturePolicy`: `allowsTextInspectionFeatures` 등 읽기 전용 정책
- 컨트롤러는 서비스의 필요한 상태를 좁은 접근자로 읽는다. 컨트롤러끼리 직접 부르지 않고, 필요한 신호는 서비스가 전달한다.
- 세션 경계 처리(`onStartInput`·`onFinishInput`·`onDestroy`)는 컨트롤러의 같은 이름 메서드로 위임한다.

## 순서

K(자판 수정) → R0 → R8 → R9 → R1·R4·R7(서로 다른 영역, 순차 검증) → R2·R3·R5 → R6 → R10 → R11.
각 단위 뒤에 `./gradlew :app:testDebugUnitTest`와 `:app:assembleDebug`를 통과해야 다음으로 간다. R9·R6 뒤에는 실기기 입력 확인을 한다.
