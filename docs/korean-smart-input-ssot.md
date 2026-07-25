# 한국어 스마트 입력 제품 SSOT

## 1. 문서 권한

이 문서는 `D:\workspace\fcitx5-android`에서 진행하는 한국어 특화 입력, 생성형 AI,
음성 전사, GIF 검색·삽입, 개인정보 보호 기능의 단일 제품 기준 문서다.

- 기능 범위, 우선순위, 상태, 비기능 요구사항, 검증 게이트는 이 문서를 기준으로 판단한다.
- `hangul-buffered-input*.md`는 한글 버퍼 호환 입력의 상세 설계와 증거를 보존하는 하위 문서다.
- 다른 대화나 작업에서 새로운 계약이 합의되면 구현 전에 이 문서에 먼저 반영한다.
- 같은 정책을 여러 문서에 복사하지 않는다. 하위 문서는 이 문서를 링크하고 세부 증거만 유지한다.
- 실제 구현 상태와 문서 상태가 다르면 실제 동작을 재검증한 뒤 둘을 함께 수정한다.

최종 갱신일: 2026-07-26
기준 브랜치: `feat/hangul-buffered-input`
현재 활성 마일스톤: `GIF-01 Commons GIF 검색·링크·첨부 MVP`

## 2. 상태 표기

| 상태 | 의미 |
| --- | --- |
| `DONE` | 코드, 자동 테스트, 요구된 실제 기기 검증까지 끝남 |
| `IN_PROGRESS` | 현재 구현 중이며 완료 게이트가 남음 |
| `NEXT` | 활성 마일스톤 직후 구현할 항목 |
| `BACKLOG` | 계약은 있으나 구현 순서가 오지 않음 |
| `GATE` | 다음 단계 진입 전에 반드시 만족해야 하는 조건 |
| `BLOCK` | 외부 권한, 공급자 계약 또는 재현 환경이 없어 진행 불가 |

내부 구현 플래그나 파일 존재만으로 `DONE`으로 표시하지 않는다. 사용자가 실제로 볼 수 있는
동작 또는 재현 가능한 테스트 증거가 있어야 한다.

## 3. 제품 방향

제품 포지셔닝은 **한국어 파워유저용 오픈형 스마트 키보드**다.

핵심 차별점은 다음 네 가지다.

1. 두벌식·세벌식·천지인·단모음·모아키·베가·나랏글을 한 앱에서 정확하게 제공한다.
2. 한/영 오타 복구, 초성 검색, 한자, 조사·띄어쓰기처럼 한국어 고유 문제를 우선한다.
3. AI와 네트워크 기능을 사용자가 명시적으로 켜고 공급자와 데이터 범위를 선택한다.
4. 비밀번호·민감 입력·private editor에서는 네트워크와 기록 기능을 fail-closed한다.

검색 포털이나 스티커 상점 전체를 키보드에 복제하는 것은 목표가 아니다. 입력 흐름을 실제로
단축하고, 결과를 사용자가 확인한 뒤 정확히 한 번 삽입하는 기능을 우선한다.

## 4. 현재 기준선

### 4.1 upstream 및 기존 앱 기능

| 영역 | 현재 기능 | 상태 |
| --- | --- | --- |
| 입력 엔진 | Fcitx5 addon과 외부 APK plugin 구조 | `DONE` |
| 언어 | 영어, 중국어, 일본어, 한국어, 베트남어, 태국어, Sinhala, RIME | `DONE` |
| 후보 | 가로 후보, 확장 후보, 물리 키보드 floating 후보 | `DONE` |
| 편집 | Undo, Redo, 커서 이동, 선택, 잘라내기, 복사, 붙여넣기 | `DONE` |
| 클립보드 | plain text 기록, 고정, 편집, 공유, 삭제, 민감 항목 마스킹 | `DONE` |
| 빠른 문구 | 내장·사용자 문구 편집과 import | `DONE` |
| 기호 | Emoji, emoticon, symbol picker와 최근 사용 | `DONE` |
| 테마 | 색상, 배경 이미지, dynamic color, 사용자 theme import/export | `DONE` |
| 음성 | 설치된 외부 voice IME로 전환 | `DONE` |
| 데이터 | 설정·DB·외부 사용자 데이터를 ZIP으로 import/export | `DONE` |

### 4.2 이 브랜치에서 추가된 한국어 기능

| ID | 기능 | 상태 | 핵심 증거 |
| --- | --- | --- | --- |
| `KO-BASE-01` | 현대 두벌식 한글 key legend | `DONE` | JVM 테스트와 Fold6 실제 기기 |
| `KO-BASE-02` | 한글 버퍼 호환 입력 및 Direct commit | `DONE` | 문제 surface와 Samsung clipboard 부작용 검증 |
| `KO-BASE-03` | 세벌식 390·Final·Noshift·옛글·Ahnmatae surface | `DONE` | generated table 테스트와 Fold6 입력 |
| `KO-BASE-04` | 천지인·천지인+·단모음·모아키·베가·나랏글 surface | `DONE` | 42 JVM 테스트, A35와 Fold6 실제 기기 |
| `KO-BASE-05` | 스페이스바 길게 눌러 한글 표면 전환 | `DONE` | 두 기기 왕복·설정 유지 |
| `KO-BASE-06` | 한지 Light·단청 Dark 등 한국 테마 | `DONE` | serialization 테스트와 실기기 렌더 |
| `KO-BASE-07` | 앱 및 plugin 한국어 번역 확대 | `DONE` | build와 두 기기 설치 |

현재 변경은 dirty tree에 있으므로 새로운 기능을 대규모로 겹치기 전에 검증 가능한 checkpoint를
남겨야 한다. 기존 사용자의 변경을 삭제하거나 되돌리지 않는다.

## 5. 변경 불가 제품 원칙

1. 입력·첨부·링크 삽입은 사용자 동작 하나당 성공 경로에서 정확히 한 번만 실행한다.
2. 전송 성공을 확인할 수 없는 경로 뒤에 다른 전송 방식을 자동 실행하지 않는다.
3. password, sensitive, `IME_FLAG_NO_PERSONALIZED_LEARNING` editor에서는 네트워크 검색,
   AI, 개인화, clipboard 기록, rich content 다운로드를 실행하지 않는다.
4. editor 또는 selection identity가 바뀐 뒤 이전 결과를 자동 제출하지 않는다.
5. AI와 검색 기능은 현재 입력 전체를 암묵적으로 읽지 않는다. 사용자가 선택·복사·입력한 범위만
   명시적 action으로 처리한다.
6. 실제 입력 원문, API key, clipboard 원문, 음성, 다운로드 URL query를 일반 로그에 남기지 않는다.
7. 공급자별 결과, attribution, API key, 캐시 정책을 한 그리드나 저장소에 무단 혼합하지 않는다.
8. 지원하지 않는 editor나 engine을 지원한다고 표시하지 않는다.
9. 사용자 데이터 백업에 API key, ephemeral token, 민감 빠른 문구 평문, 임시 GIF를 포함하지 않는다.
10. 링크·텍스트·rich content는 가능한 경우 clipboard를 거치지 않고 `InputConnection`으로 전달한다.

## 6. 통합 제품 백로그

### 6.1 현재 활성 마일스톤

| ID | 기능 | 상태 | 가치 | 난이도 |
| --- | --- | --- | --- | --- |
| `GIF-01` | Commons GIF 검색·링크·첨부 MVP | `IN_PROGRESS` | 키보드 이탈 없이 반응 GIF 사용 | L |

`GIF-01`의 상세 계약은 7절에 있다.

### 6.2 1차 한국어 로컬 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `KO-01` | 한/영 오타 즉시 복구 | `NEXT` | `dkssud→안녕`, `ㅗ디ㅣㅐ→hello`, preview 후 교체 | S |
| `KO-02` | 초성 통합 검색 | `NEXT` | 빠른 문구·clipboard·emoji를 `ㄱㅅ` 등으로 검색 | M |
| `KO-03` | 동적 빠른 문구 | `NEXT` | 날짜·시간·이름·전화·주소·clipboard 변수, preview | M |
| `KO-04` | 앱별 키보드 profile | `NEXT` | package별 layout, theme, transport, toolbar, AI 정책 | M |
| `KO-05` | 한자·국어사전 후보 | `BACKLOG` | 한글 단어의 한자, 음훈, 동음이의어를 로컬 후보로 표시 | M |
| `KO-06` | 개인 단어장 | `BACKLOG` | 이름·회사명·전문용어를 opt-in으로 로컬 우선 후보에 반영 | L |
| `KO-07` | 한국어 조사·문맥 후보 | `BACKLOG` | 조사와 다음 어절 추천, 자동 확정은 기본 off | L |
| `KO-08` | 한국식 감정표현 추천 | `BACKLOG` | emoji·kaomoji·ㅋㅋ/ㅎㅎ 후보, provider와 분리 | M |

### 6.3 AI 텍스트 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `AI-00` | AI provider·보안 기반 | `BACKLOG` | provider profile, key vault, privacy gate, usage 표시 | L |
| `AI-01` | 한국어 맞춤법·띄어쓰기·조사 교정 | `BACKLOG` | diff, 부분 적용, 전체 교체, undo | M |
| `AI-02` | 존댓말·말투 변환 | `BACKLOG` | 반말/존댓말, 업무, 카톡, 거절, 사과, 고객응대 | M |
| `AI-03` | 빠른 문장 생성 | `BACKLOG` | 의도와 톤으로 초안 3개, 선택 결과만 삽입 | M |
| `AI-04` | 답장 초안 | `BACKLOG` | 사용자가 복사·선택한 상대 문장과 답장 의도만 전송 | M |
| `AI-05` | 키보드 번역 | `BACKLOG` | 한↔영·일·중, 원문/번역문 비교와 명시적 교체 | M |
| `AI-06` | AI provider profile | `BACKLOG` | OpenAI, 사용자 proxy, 승인된 호환 endpoint를 분리 | M |

### 6.4 음성·멀티모달 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `VOICE-01` | GPT 실시간 받아쓰기 | `BACKLOG` | push-to-talk, partial transcript, 언어 hint, 명시적 commit | L |
| `VOICE-02` | 고정밀 녹음 전사 | `BACKLOG` | 녹음 완료 뒤 정확도 우선 전사와 교정 | L |
| `VOICE-03` | 화자 분리 회의·메모 | `BACKLOG` | 파일 기반 diarization, 화자 label, 요약은 별도 action | L |
| `MM-01` | OCR·사진 속 한글 입력 | `BACKLOG` | 사용자가 고른 이미지에 한해 OCR, preview 후 삽입 | L |

### 6.5 편의·기기·보안 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `UX-01` | Smart clipboard action | `BACKLOG` | 서식 제거, 합치기, 전화·계좌 형식화, 개인정보 마스킹 | M |
| `UX-02` | Fold·tablet 분할 키보드 | `BACKLOG` | cover/unfolded posture별 profile과 양손 thumb layout | L |
| `SEC-01` | 민감 빠른 문구 금고 | `BACKLOG` | Keystore 암호화, 생체 인증, package allowlist | L |
| `SEC-02` | Privacy dashboard | `BACKLOG` | 기능별 전송 범위, provider, 최근 사용량, 즉시 삭제 | M |
| `SEC-03` | 완전 offline mode | `BACKLOG` | 네트워크 기능과 원격 provider를 한 번에 차단 | S |

## 7. GIF-01 상세 계약

### 7.1 핵심 UX

1. 키보드 toolbar의 GIF 버튼으로 `GifSearchWindow`를 연다.
2. 첫 화면은 Commons의 검증된 기본 결과를, 검색 뒤에는 한국어 query 결과를 grid로 표시한다.
3. thumbnail을 탭하면 해당 카드 자체 위에 반투명 selection overlay를 표시한다.
4. overlay에는 `링크 넣기`와 `GIF 첨부`를 동시에 표시한다.
5. 선택된 카드를 다시 탭하거나 바깥을 탭하면 overlay를 닫는다.
6. 다른 카드를 탭하면 overlay가 그 카드로 이동한다.
7. `링크 넣기`는 provider의 canonical page URL을 `commitText()`로 정확히 한 번 삽입한다.
8. `GIF 첨부`는 실제 animated `image/gif` 파일을 Android Commit Content API로 전달한다.
9. 성공 뒤 일반 keyboard로 돌아간다. 실패 뒤에는 현재 결과와 선택을 보존하고 retry를 제공한다.

버튼 표기는 `URL`/`이미지`보다 `링크 넣기`/`GIF 첨부`를 우선한다.

### 7.2 상태 모델

| 상태 | 표시 | 허용 동작 |
| --- | --- | --- |
| `Initial` | 검색창과 기본 결과 | 검색, 결과 선택, 닫기 |
| `Loading` | 진행 표시 | 취소 또는 query 교체 |
| `Results` | 결과 grid | 선택, 새 검색 |
| `Selected` | 카드 overlay와 attribution | 링크, 첨부, 선택 해제 |
| `Downloading` | 선택 카드 progress | 중복 action 차단 |
| `RetryableError` | 카드 또는 화면 오류 | 재시도, 선택 해제, 새 검색 |
| `Committed` | 짧은 성공 상태 | 일반 keyboard 복귀 |

stale search response가 최신 query 결과를 덮지 않도록 request generation을 비교한다.

### 7.3 링크 삽입 계약

- media CDN URL이 아니라 사람이 attribution을 확인할 수 있는 canonical Commons page URL을 삽입한다.
- `currentInputConnection.commitText(url, 1)` 호출은 사용자 action당 최대 한 번이다.
- 실패 또는 invalid connection 뒤 clipboard paste나 다른 transport를 자동 실행하지 않는다.
- buffered Hangul prefix와 composing text를 먼저 안전하게 정리한다.

### 7.4 GIF 첨부 계약

1. `EditorInfoCompat.getContentMimeTypes()` 결과에 `image/gif` 또는 `image/*`가 있는지 확인한다.
2. 미지원이면 `GIF 첨부`를 disabled 처리하고 `이 앱은 GIF 첨부를 지원하지 않음`을 표시한다.
3. 원본 media URL을 앱 전용 cache로 내려받고 GIF signature와 size 제한을 검증한다.
4. `FileProvider`의 read-only `content://` URI를 만든다.
5. `InputContentInfoCompat(uri, ClipDescription(title, image/gif), canonicalUrl)`을 만든다.
6. `InputConnectionCompat.commitContent()`와
   `INPUT_CONTENT_GRANT_READ_URI_PERMISSION`으로 한 번만 전달한다.
7. false 또는 exception이면 실패로 표시하고 link를 자동 삽입하지 않는다.
8. 수신 앱이 URI를 비동기로 읽을 수 있으므로 전송 직후 파일을 삭제하지 않는다.

### 7.5 조합과 editor 수명

- rich content action 전에 현재 Hangul engine preedit와 buffered prefix를 명시적으로 확정한다.
- `finishComposingText()` 결과와 editor identity를 확인한 뒤 content를 보낸다.
- action 중 focus, package, input connection 또는 selection anchor가 바뀌면 전송을 중단한다.
- password, sensitive, private editor에서는 window 진입과 network request 자체를 차단한다.
- attach 실패 뒤 `commitText()` fallback은 금지한다.

### 7.6 provider 모델

모든 provider 결과는 다음 필드를 제공한다.

| 필드 | 의미 |
| --- | --- |
| `providerId` | 공급자 고유 ID |
| `id` | 공급자 내부 결과 ID |
| `title` | 사용자 표시 제목 |
| `canonicalUrl` | attribution이 가능한 공유 페이지 |
| `mediaUrl` | 실제 원본 GIF 다운로드 URL |
| `thumbnailUrl` | grid thumbnail URL |
| `mimeType` | 반드시 `image/gif` |
| `width`, `height`, `byteSize` | download 및 layout 검증 |
| `author` | 저작자 또는 제공자 표기 |
| `licenseName`, `licenseUrl` | 라이선스 표기와 상세 링크 |
| `attribution` | 카드와 상세 overlay에 표시할 문구 |
| `safe` | provider filter를 통과한 결과인지 여부 |

canonical page URL과 downloadable media URL을 혼동하지 않는다.

### 7.7 Wikimedia Commons 기본 공급자

- MediaWiki API의 file namespace 검색과 `imageinfo/extmetadata`를 사용한다.
- query는 UTF-8 한국어를 그대로 지원하며 safe search는 항상 켠 상태로 취급한다.
- MIME이 정확히 `image/gif`인 파일만 허용한다.
- CC0, Public Domain, CC BY, CC BY-SA, GFDL 등 명시적으로 허용한 open/free license만 노출한다.
- license, author 또는 canonical source를 확인할 수 없는 결과는 제외한다.
- `do not use`, copyright violation, fair use, non-free, all rights reserved, permission missing,
  deletion candidate 신호가 있는 결과는 제외한다.
- card 또는 overlay에 provider, author, license를 표시하며 canonical page로 이동할 수 있게 한다.
- Commons와 다른 provider 결과를 같은 grid에 섞지 않는다.

### 7.8 선택적 GIPHY 공급자

GIPHY는 `GIF-01` 완료 조건이 아니며 별도 backlog로 둔다.

- 별도 provider/tab과 별도 API key 설정을 사용한다.
- `Powered by GIPHY`, attribution, API 정책을 UI에 표시한다.
- Commons 결과와 혼합하거나 Commons인 것처럼 cache하지 않는다.
- GIPHY의 proxy/cache/rendition 제한을 구현 전에 최신 공식 계약으로 다시 확인한다.

Tenor API는 2026-06-30 종료 계약 때문에 새로운 기본 공급자로 사용하지 않는다. 공급자 상태는
구현 시점에 공식 문서로 다시 검증한다.

### 7.9 cache와 cleanup

- thumbnail memory cache와 original GIF disk cache를 분리한다.
- original GIF는 app `cacheDir/gif-share` 아래에만 저장한다.
- partial download는 별도 확장자로 쓰고 검증 뒤 atomic rename한다.
- MIME, GIF87a/GIF89a signature, byte limit를 모두 통과해야 공유한다.
- MVP original 최대 크기는 20 MiB다.
- 전송 직후 삭제하지 않고 24시간 TTL을 적용한다.
- window 시작, 새 download 시작, 앱 시작 중 안전한 지점에서 expired file을 정리한다.
- cache file과 query는 사용자 ZIP export와 Android backup 대상이 아니다.

### 7.10 접근성·오류·표시

- thumbnail, author, license, 두 action에 `contentDescription`을 제공한다.
- loading, disabled attach 이유, retryable error를 색상만으로 표현하지 않는다.
- network timeout, empty result, metadata exclusion, thumbnail failure, original download failure,
  content rejection을 서로 구분한다.
- attribution은 thumbnail loading 실패와 관계없이 텍스트로 접근 가능해야 한다.

### 7.11 완료 게이트

`GIF-01`은 다음 항목이 모두 확인돼야 `DONE`이다.

1. toolbar 진입, 한국어 검색, 기본 결과 grid.
2. 카드 overlay의 링크·첨부 두 버튼과 이동·해제 동작.
3. canonical link 정확히 한 번 삽입.
4. 실제 animated GIF를 Commit Content로 첨부.
5. 미지원 editor에서 attach disabled와 설명 표시.
6. 검색·download 오류, retry와 24시간 cache cleanup.
7. safe filter, author, license, provider attribution.
8. provider parser, license policy, selection state, MIME support, exactly-once policy 테스트.
9. 전체 app JVM 테스트와 arm64-v8a debug build PASS.
10. A35와 Z Fold6에 최신 app/plugin 설치.
11. GIF 지원 앱에서 attachment가 실제 수신되고 움직임이 유지됨.
12. 일반 text editor에서 attachment가 disabled되고 link만 정확히 한 번 입력됨.
13. password/private editor에서 검색 request가 0회임.
14. attach 실패에서 link 자동 삽입이 0회임.

## 8. AI·음성 아키텍처 계약

### 8.1 provider 경계

- `AiProvider`는 text generation, transcription, realtime capability를 명시한다.
- model ID를 UI 문자열이나 action 코드에 직접 흩뿌리지 않고 registry와 profile로 관리한다.
- 최신·추천 모델은 구현 시 OpenAI 공식 resolver와 문서로 다시 검증한다.
- 2026-07-26 기준 후보는 다음과 같지만 영구 상수로 간주하지 않는다.

| 역할 | 기준 후보 |
| --- | --- |
| 실시간 받아쓰기 | `gpt-realtime-whisper` |
| 정확도 우선 파일 전사 | `gpt-4o-transcribe` |
| 저비용 파일 전사 | `gpt-4o-mini-transcribe` |
| 화자 분리 | `gpt-4o-transcribe-diarize` |
| 고빈도 짧은 교정·routing | `gpt-5.6-luna` |
| 문장 생성·말투 변환 | `gpt-5.6-terra` |
| 선택적 최고 품질 | `gpt-5.6-sol` |

Responses API typed streaming event를 기본 text integration 후보로 사용한다. 모든 workload를 Sol로
보내지 않고 latency, cost, quality 역할을 분리한다.

### 8.2 API key와 token

- 일반 배포 기본 경로는 backend가 standard provider key를 보관한다.
- Realtime client에는 backend가 발급한 짧은 수명의 ephemeral token을 사용한다.
- 개인용 advanced BYOK를 제공할 경우 Android Keystore로 암호화하고 ciphertext는
  `noBackupFilesDir` 아래에 저장한다.
- BYOK standard key는 추출 위험이 0이라고 표시하지 않는다.
- key와 token을 `AppPrefs`, 일반 SharedPreferences, user-data ZIP, log, crash report에 넣지 않는다.
- 기능별 사용량, 실패 유형, provider만 표시하며 prompt와 결과 원문은 기본 저장하지 않는다.

### 8.3 text 읽기와 교체

- IME가 임의의 chat bubble이나 화면 전체를 읽을 수 있다고 가정하지 않는다.
- `InputConnection.getSelectedText()`와 surrounding text는 null 또는 stale일 수 있다.
- 상대 메시지 기반 답장은 사용자가 선택, 복사 또는 share한 텍스트만 사용한다.
- AI 결과는 원문, diff, 후보를 보여주고 `교체`, `뒤에 넣기`, `복사`, `취소`를 구분한다.
- 교체 뒤 최소 한 번의 undo 경로를 제공한다.

## 9. 권장 구현 순서

### 단계 0 — 현재 기준선 보존

1. 기존 dirty tree를 삭제하거나 reset하지 않는다.
2. 현재 한글 배열·테마·다국어 변경의 test/build/device 증거를 유지한다.
3. 큰 기능 묶음 전 review 가능한 checkpoint를 만든다.

### 단계 1 — GIF-01 완료

1. Commons provider와 license policy pure model·test.
2. repository, search generation, thumbnail loader.
3. toolbar, grid, selection overlay.
4. canonical link exactly-once path.
5. FileProvider, original cache, `RichContentCommitter`.
6. sensitive/private gate, error, retry, cleanup.
7. JVM test, build, A35/Fold6 지원·미지원 editor 검증.

### 단계 2 — 로컬 한국어 quick wins

1. `KO-01` 한/영 오타 복구.
2. `KO-02` 초성 통합 검색.
3. `KO-03` 동적 빠른 문구.
4. `KO-04` 앱별 profile과 network policy.

### 단계 3 — AI 기반과 text 기능

1. `AI-00`, `SEC-02`, `SEC-03`을 먼저 구현한다.
2. `AI-01` 맞춤법·띄어쓰기.
3. `AI-02` 말투 변환.
4. `AI-03` 문장 생성.
5. `AI-05` 번역.
6. `AI-04` 답장 초안.

### 단계 4 — 음성

1. push-to-talk audio capture와 permission UX.
2. `VOICE-01` realtime partial transcript.
3. `VOICE-02` 고정밀 file transcription.
4. `VOICE-03` diarization과 회의 UI.

### 단계 5 — 개인화·대화면·장기 기능

1. `KO-05` 한자·사전과 `KO-06` 개인 단어장.
2. `UX-01` smart clipboard action.
3. `SEC-01` 민감 문구 금고.
4. `UX-02` Fold·tablet 분할 layout.
5. `KO-07`, `KO-08`, `MM-01`을 실사용 수요 순으로 평가한다.

## 10. 공통 검증 게이트

각 기능 완료 시 다음 증거를 남긴다.

- 요구사항별 자동 테스트 이름과 assertion 대상.
- 실행한 Gradle task와 PASS/FAIL 수치.
- Android API, 기기, app, editor 유형.
- package/input connection 변경과 exactly-once 결과.
- network provider, timeout, retry, cache 결과.
- 민감 editor에서 network·clipboard·파일 접근 호출 수 0.
- 실패 뒤 자동 fallback 또는 중복 입력 수 0.
- 사용자가 실제로 본 화면 또는 삽입 결과.
- 남은 `BLOCK`, owner가 필요한 외부 계약, 명시적 non-goal.

최소 공통 명령은 다음과 같다.

```powershell
.\gradlew.bat :app:testDebugUnitTest -PbuildABI=arm64-v8a
.\gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a
.\gradlew.bat :plugin:hangul:assembleDebug -PbuildABI=arm64-v8a
git diff --check
```

plugin lint와 assembly는 현재 task graph 제약 때문에 별도 invocation으로 실행한다.

## 11. 결정 로그

| 날짜 | 결정 |
| --- | --- |
| 2026-07-25 | Direct commit을 broken-IME 기본 후보로 유지하고 clipboard paste 자동 fallback을 금지 |
| 2026-07-26 | 한글 모바일 surface 9종과 물리 layout switcher를 기준선으로 확정 |
| 2026-07-26 | 제품 방향을 한국어 파워유저용 오픈형 스마트 키보드로 확정 |
| 2026-07-26 | AI·로컬·음성·GIF 기능을 이 문서의 통합 backlog로 관리 |
| 2026-07-26 | GIF MVP 기본 provider는 Wikimedia Commons, GIPHY는 별도 optional provider |
| 2026-07-26 | GIF attach 실패 뒤 link 자동 삽입 금지 |
| 2026-07-26 | 표준 API key의 일반 mobile direct 저장은 기본 경로로 사용하지 않음 |
