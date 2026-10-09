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
| G1 | 코드 삭제 | 호출 0인 Gemma 옛 "prefix plan" 경로, 탭·미디어 시제품의 죽은 메서드, 고아 레이아웃·문자열, 호출 0인 자체 코드, 외부 AI 시절 주석·이름 | 낮음 | 없음 | 대기 |
| G2 | 문서·폴더 정리 | 제거된 기능 문서 삭제·보관, 2026-09 로그 보관, 낡은 서술·옛 경로·깨진 링크 수정, 참조 0 스크립트 정리, GitHub 전용 파일 정리, 결정 기록 시작 | 낮음 | 없음 | 대기 |
| G3 | 한글 판정 단일화 | 음절 범위·자모 테이블·분해·받침 판정을 한 객체로, 형태소 엔진 단일 인스턴스, 호출마다 만드는 정규식을 상수로 | 낮음~중간 | G1 | 대기 |
| G4 | 예측 조회 단일화 | 후보 갱신 한 번에 예측 스냅샷 하나만 계산, 후보 바 커밋 스냅샷 맵 3개를 하나로 | 중간 | G3 | 대기 |
| G5 | 오타 엔진 중복 제거 | 키 입력→한글 오토마타 두 벌 통일, 인접 키 맵 출처 통일, 교정 한 번 순회 | 높음 | G3, 골든 테스트 | 보류 |
| G6 | 텍스트 경계 상수 | 문장 끝 부호 집합 13가지를 이름 붙인 몇 개로 | 높음(동작 차이) | 정책 결정 | 보류 |
| G7 | 서비스·컨트롤러 분할 | `FcitxInputMethodService`(3154줄)·`ContextualPredictionController`·`HorizontalCandidateComponent`의 책임 분리, Host 람다 묶음 정리 | 높음 | G4 | 보류 |
| G8 | 화면 규칙 적용 | `app/design.md` "검증 규칙" 절을 기준으로 설정·대시보드 점검 | 중간 | 없음 | 대기 |

### 보류 이유

- G5: 두 오토마타의 겹받침 분리 규칙이 다를 수 있다. 차이를 골든 테스트로 먼저 고정해야 한다.
- G6: 같은 "문장 끝"이 곳마다 다르다(예: `…` 뒤에서 `ContextualPredictionInput.resolve`는 끊고 `PredictionInput.currentSentence`는 끊지 않는다). 어느 쪽이 맞는지 먼저 정한다.
- G7: 프롬프트 격리 불변식이 걸려 있다. G4로 조회 경로를 먼저 줄인 뒤 한다.

### 지우지 않고 남기는 것

- `BufferedInputController`의 종료 결과 상태 머신: `hangul-buffered-input-backlog.md` 235행의 미완료 항목(서비스 연결 대기)이다.
- upstream fcitx5-android에서 온 호출 0 코드(`KeyDefPreset`의 일부 클래스, `utils/*`의 일부 함수): upstream과의 차이를 키우지 않기 위해 남긴다.
- `GraphEnrichmentStatusStore.LEGACY_PROVIDER_FAILURES`, `PersonalizedSentenceRecord`의 옛 기본 출처 값: 기존 저장 데이터를 읽는 데 쓴다.

## 결정이 필요한 것

- 툴바 길게 누르기로 열리는 통합 탭 창(`tab/UnifiedTabExtensionWindow.kt`)의 즐겨찾기·미디어·검색 탭은 예시 데이터(`https://example.com/*.gif`)와 빈 클릭 처리만 있다. 릴리스에서 사용자에게 보인다. 지울지, 기능을 채울지.
- `admin/` 운영 데모(Vite+React, 실제 SDK와 연결 안 됨)를 남길지.
- `flake.nix`·`shell.nix`(upstream Nix 환경)를 남길지.
- `docs/wiki/`를 GitHub Wiki로 내보내는 `scripts/publish-wiki.py`를 Forgejo 기준으로 바꿀지.
- origin 원격과 `release-contract.yml`의 `-ExpectedRepositoryUrl`(지금 GitHub 주소)을 Forgejo 주소로 바꾸는 일. 주소를 받으면 한다.
