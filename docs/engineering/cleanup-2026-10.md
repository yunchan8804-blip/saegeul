# 2026-10 정리·리팩토링 계획

- 기준일: 2026-10-09
- 상태: 진행 중
- 방법: `refactor-governance` 스킬의 순서(삭제 → 형태 정리 → 계약·중복 정리 → 캐시 → 공통 흐름 → 최적화 → 화면)를 따른다. 묶음 하나를 끝내고 검증을 통과해야 다음 묶음으로 간다.
- 이 문서가 이번 정리의 작업 보드다. 끝난 묶음은 상태만 바꾸고, 모두 끝나면 `docs/archive/engineering/`으로 옮긴다.

## 목표와 비목표

- 목표: 쓰지 않는 코드·리소스·문서를 지우고, 흩어진 한글 판정 코드를 한 곳으로 모으고, 키 입력마다 도는 경로의 낭비를 줄인다. 낡은 문서(제거된 외부 글쓰기 AI, 옛 저장소 경로, GitHub 전제)를 실제와 맞춘다.
- 비목표: 기능 추가, 사용자에게 보이는 동작 변경. 동작이 바뀌는 정리는 따로 표시하고 따로 결정한다.

## 불변량

- 단위 테스트 전체 통과(2026-10-09 기준 2321개, 실패 0, 기존 스킵 3). `RedTeam*` 테스트는 고치거나 지우지 않는다.
- 한글 조합·후보 대체 길이·문장 시작 판정 결과가 바뀌지 않는다(골든 테스트로 고정한 뒤 옮긴다).
- 개인정보 선언(`docs/independent-fork/privacy-data-safety.md`, `play-data-safety-declaration.json`)과 어긋나는 변경 금지.
- 서브모듈, `Versions.kt`, `ProductIdentity.kt`, 릴리스 스크립트는 건드리지 않는다.

## 패치 묶음

| ID | 묶음 | 내용 | 위험 | 선행 | 상태 |
|---|---|---|---|---|---|
| G1 | 코드 삭제 | 호출 0인 Gemma 옛 "prefix plan" 경로, 탭·미디어 시제품의 죽은 메서드, 고아 레이아웃·문자열, 호출 0인 자체 코드, 외부 AI 시절 주석·이름 | 낮음 | 없음 | 완료(948e43d5) |
| G2 | 문서·폴더 정리 | 제거된 기능 문서 삭제·보관, 2026-09 로그 보관, 낡은 서술·옛 경로·깨진 링크 수정, 참조 0 스크립트 정리, GitHub 전용 파일 정리, 결정 기록 시작 | 낮음 | 없음 | 완료(1fcf6c27) |
| G3 | 한글 판정 단일화 | 음절 범위·자모 테이블·분해·받침 판정을 한 객체로, 형태소 엔진 단일 인스턴스, 호출마다 만드는 정규식을 상수로 | 낮음~중간 | G1 | 진행 중 |
| G4 | 예측 조회 단일화 | 후보 갱신 한 번에 예측 스냅샷 하나만 계산, 후보 바 커밋 스냅샷 맵 3개를 하나로 | 중간 | G3 | 대기 |
| G5 | 오타 엔진 중복 제거 | 키 입력→한글 오토마타 두 벌 통일, 인접 키 맵 출처 통일, 교정 한 번 순회 | 높음 | G3, 골든 테스트 | 진행 중(대청소 1차: 한글 대응표 통합) |
| G6 | 텍스트 경계 상수 | 문장 끝 부호 집합 13가지를 이름 붙인 몇 개로 | 높음(동작 차이) | 정책 결정(통일로 확정) | 대기(대청소 2차) |
| G7 | 서비스·컨트롤러 분할 | `FcitxInputMethodService`(3154줄)·`ContextualPredictionController`·`HorizontalCandidateComponent`의 책임 분리, Host 람다 묶음 정리 | 높음 | G4 | 진행 중(대청소 1차: 서비스 분해) |
| G8 | 화면 규칙 적용 | `app/design.md` "검증 규칙" 절을 기준으로 설정·대시보드 점검 | 중간 | 없음 | 일부 완료(ea5e4818). 키보드 위 창 48dp·키보드 높이 상한·설정 화면 나누기는 결정 대기 |

### 보류 이유

- G5: 두 오토마타의 겹받침 분리 규칙이 다를 수 있다. 차이를 골든 테스트로 먼저 고정해야 한다.
- G6: 같은 "문장 끝"이 곳마다 다르다(예: `…` 뒤에서 `ContextualPredictionInput.resolve`는 끊고 `PredictionInput.currentSentence`는 끊지 않는다). 사용자가 하나로 통일하기로 정했고, 어느 쪽 규칙으로 맞출지는 대청소 2차에서 정한다.
- G7: 프롬프트 격리 불변식이 걸려 있다. 대청소 1차에서 불변식을 지키며 나누는 중이다.

### 지우지 않고 남기는 것

- `BufferedInputController`의 종료 결과 상태 머신: `hangul-buffered-input-backlog.md` 235행의 미완료 항목(서비스 연결 대기)이다.
- upstream fcitx5-android에서 온 호출 0 코드(`KeyDefPreset`의 일부 클래스, `utils/*`의 일부 함수): upstream과의 차이를 키우지 않기 위해 남긴다.
- `GraphEnrichmentStatusStore.LEGACY_PROVIDER_FAILURES`, `PersonalizedSentenceRecord`의 옛 기본 출처 값: 기존 저장 데이터를 읽는 데 쓴다.

## 2026-10-10 정리 라운드

위 표의 묶음과는 따로 한 번 더 훑은 라운드다. 삭제 전에는 항목마다 코드, 리소스, manifest, 테스트, androidTest, 문서에서 참조가 없는지 다시 찾았다.

### 이번에 지운 것

- 문자열 `gemma_home_eyebrow`, `gemma_home_title`, `gemma_home_summary`, `gemma_home_open`: 쓰는 곳이 없어 모든 언어 폴더에서 지웠다. 같은 파일의 `gemma_home_vault_title`은 쓰고 있어 남겼다.
- 문자열 `style_report_footer_privacy`, `vault_style_report_row`: 쓰는 곳이 없어 지웠다. 용어 확인 테스트(`VaultHomeStringsTerminologyTest`)의 검사 목록에서도 이 두 이름만 뺐다. 다른 기대값은 그대로다.
- ProGuard 규칙 `PinyinCustomPhrase` 유지 항목: 이 클래스가 저장소 어디에도 없어 규칙만 남아 있었다.

### 이번에 옮긴 것

모두 `git mv`로 옮겼고, 맨 위에 "보관 문서" 안내 한 줄을 붙였다.

- `docs/ai/ai-personalization-backlog.md` → `docs/archive/ai/`
- `docs/korean-input/korean-contextual-prediction-quality-plan.md` → `docs/archive/korean-input/`
- `docs/independent-fork/archive/` 안의 `refactor-pack.md`, `baseline.json` → `docs/archive/independent-fork/` (보관소를 `docs/archive/` 하나로 합쳤다. `baseline.json`은 JSON이라 안내 줄을 넣지 않았다.)
- 링크는 `refactor-pack.md`가 가리키던 `../README.md`를 `../../independent-fork/README.md`로 고쳤다. 나머지 두 문서의 상대 링크가 "그대로 열린다"고 적었는데 틀렸다. 옮긴 문서를 가리키던 `docs/archive/logs/2026-09/korean-contextual-prediction-resume-2026-09-08.md`의 링크 11개가 끊어져 있었고, 같은 파일에서 무시되는 `.artifacts/`를 가리키는 링크 28개도 폴더 깊이가 틀려 있었다. 대청소 1차에서 고쳤다.

### 코드 정리 (다른 작업자 담당, 진행 중)

아래는 코드 쪽에서 하는 일이고, 끝난 뒤 확정해서 여기에 반영한다.

- 쓰지 않는 상수와 필드 삭제: `HANGUL_SYLLABLE_START`/`END` 별칭, `MAX_ATTEMPTS_PER_PREFIX`, `STATUS_TARGET_REACHED`, `totalPrefixes`, `restoreCursorOffset` (진행 중)
- 편집기 복귀 대기열 4벌을 `EditorResumeQueue` 하나로 합치기 (진행 중)
- 리소스 확인 함수 하나로 합치기 (진행 중)
- OCR의 한글 범위를 `HangulSyllable`로 바꾸기 (진행 중)

### 일부러 남긴 것

- upstream(fcitx5-android)에서 온 쓰지 않는 코드: upstream과 차이를 키우지 않으려고 남긴다.
- `VaultHabitTracker`: 앱에서는 안 쓰지만 `RedTeam*` 테스트가 쓴다. 회귀를 막는 테스트라 지우지 않는다.
- 테스트에서만 쓰는 멤버 목록: 다음 라운드에서 하나씩 판단한다.
- 아주 큰 파일 나누기 중 서비스 분해(G7) 밖의 것: 동작이 달라질 수 있어 보류한다.

### 대청소 1차 (진행 중)

- 서비스 분해(G7): `FcitxInputMethodService` 등의 책임 나누기
- 한글 대응표 통합(G5)
- 통합 탭 시제품 삭제: `tab/`·`media/` 아래 시제품 코드와 그 테스트를 지운다. 예시 데이터만 있고 사용자에게 보이던 창이다.
- 이 작업(Kotlin 밖의 정리):
  - 루트의 `flake.nix`·`flake.lock`·`shell.nix`(upstream Nix 환경)와 옛 배포 메타데이터 파일 `app/net.chanpaca.saegeul.yml` 삭제(사용자 결정)
  - 한글 플러그인 APK의 Play 등록 자료 `plugin/hangul/src/main/play/listings/` 삭제
  - 앱 라이선스 고지에 libhangul·fcitx5-hangul·국립국어원 어휘(KOGL 제1유형) 항목 추가, 링크되지 않는 libime 항목 삭제
  - 기기 테스트 정리: 2026-09-09 일회성 비교 실험 테스트 2개와 쓰지 않는 평가 파일 삭제
  - `scripts/` 정리: 참조 0이 된 Play 리스팅 스크립트, 이어쓰기 표면화 모듈과 그 테스트, 일회성 기기 측정 스크립트 삭제
  - `site/` 정리: 쓰지 않는 CSS 삭제, 클래스 이름 `ondevice-arch-card`로 변경, 캐시 값 갱신
  - 문서: 끊긴 상대 링크 정리, `hangul-buffered-input-handoff.md` 보관, 읽는 곳 없는 `ios-port-backlog.json` 삭제

### 이어서 할 일

- 2차: 문장 끝 판정 통일(G6, 사용자가 통일을 결정함), 테스트에서만 쓰는 멤버 정리
- 3차: 컴파일 경고 정리, 패키지 정리

## 결정이 필요한 것

- `admin/` 운영 데모(Vite+React, 실제 SDK와 연결 안 됨)를 남길지.
- `docs/wiki/`를 GitHub Wiki로 내보내는 `scripts/publish-wiki.py`를 Forgejo 기준으로 바꿀지.
- origin 원격과 `release-contract.yml`의 `-ExpectedRepositoryUrl`(지금 GitHub 주소)을 Forgejo 주소로 바꾸는 일. 주소를 받으면 한다.
