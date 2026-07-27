# 한국어 스마트 입력 제품 SSOT

## 1. 문서 권한

이 문서는 `D:\workspace\fcitx5-android`에서 진행하는 한국어 특화 입력, 생성형 AI,
음성 전사, GIF 검색·삽입, 개인정보 보호 기능의 단일 제품 기준 문서다.

- 기능 범위, 우선순위, 상태, 비기능 요구사항, 검증 게이트는 이 문서를 기준으로 판단한다.
- `hangul-buffered-input*.md`는 한글 버퍼 호환 입력의 상세 설계와 증거를 보존하는 하위 문서다.
- 다른 대화나 작업에서 새로운 계약이 합의되면 구현 전에 이 문서에 먼저 반영한다.
- 같은 정책을 여러 문서에 복사하지 않는다. 하위 문서는 이 문서를 링크하고 세부 증거만 유지한다.
- 실제 구현 상태와 문서 상태가 다르면 실제 동작을 재검증한 뒤 둘을 함께 수정한다.

최종 갱신일: 2026-07-28
기준 브랜치: `feat/hangul-buffered-input`
현재 활성 구현 마일스톤: `AI/STT 분리 마감·전체 기능 회귀·실기기 외부 게이트 폐쇄`

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
| `KO-BASE-08` | 숫자·기호 전환 상태 복구 | `DONE` | 레거시 상태 migration 테스트와 A35·Fold6 실제 Fcitx `?123` 숫자판 전환 통과 |
| `KO-01` | 한/영 오타 즉시 복구 | `DONE` | 5 JVM 테스트와 A35 Discord 교체·실행 취소 |
| `KO-02` | 초성 통합 검색 | `DONE` | 6 JVM 테스트와 A35 `ㄱㅅ` 검색·1회 삽입 |
| `KO-03` | 동적 빠른 문구 | `DONE` | 7 JVM 테스트, 암호화 profile·날짜·clipboard 미리보기와 정확히 1회 삽입 검증 |
| `KO-03A` | 자동 스니펫 확장 | `DONE` | API 34 emulator에서 `:주소1`·`:이메일`의 Space·Enter·buffered 분할·private 차단 통과 |
| `KO-09` | 한글 어절 자동완성 | `DONE` | API 34 emulator에서 로컬 후보·명시 선택·미선택 Space·buffered 1회 확정·private 차단 통과 |
| `KO-04` | 앱별 키보드 profile | `DONE` | API 34 emulator에서 exact package·전역 fallback·키보드 표면 저장·정책 차단·설정 dialog 검증 통과 |
| `KO-05` | 한자 후보 음훈 | `DONE` | API 34 emulator에서 명시 액션·음훈 렌더·1회 교체·한글 자동완성 복귀 통과 |

한국어 기준선과 GIF·KO-01·KO-02는 각각 검증 가능한 checkpoint commit으로 고정돼 있다.
일반 Android 기능은 API 34 emulator에서 실제 UI·입력·exactly-once를 검증하고, 마이크 음질과 Fold
posture처럼 emulator가 재현할 수 없는 항목만 실기기 gate로 유지한다. 이 기준으로 `KO-09`는
일반·buffered·private editor 검증을 완료했다.

#### KO-BASE-08 숫자·기호 전환 회귀 계약과 증거 (2026-07-26)

1. `?123`의 기본 목적지는 숫자판 `Number`다. 사용자가 명시적으로 기호 picker를 선택한 경우에만
   `Symbol` 목적지를 보존한다.
2. 과거 버전이 저장한 `Text`, `Hangul`, `MobileHangul:*` 또는 알 수 없는 목적지는 유효한 숫자·기호
   목적지가 아니다. 첫 `?123` 동작에서 `Number`로 교정하고 저장소도 즉시 갱신한다.
3. 숫자판에서 `ABC`로 돌아갈 때 현재 한글 표면을 복원하되, 그 text surface를 다음 `?123` 목적지로
   다시 저장하지 않는다.

| 항목 | 상태 | 증거 |
| --- | --- | --- |
| migration 단위 테스트 | `PASS` | `KeyboardLayoutMemoryTest` 4개가 Number 기억, text surface 비기억, 모든 mobile Hangul 레거시 값 교정, 유효한 Number·Symbol 보존을 검증 |
| 전체 자동 테스트·빌드 | `PASS` | app 23 suites·85 tests·failure/error/skipped 0, arm64 app과 Hangul plugin assemble 성공 |
| A35 레거시 재현 | `PASS` | 저장값을 `MobileHangul:Cheonjiin`으로 강제한 뒤 `?123` 한 번에 숫자판이 열리고 저장값이 `Number`로 자가 복구됨 |
| A35 반복 왕복 | `PASS` | 한글 `?123 → Number → ABC → 한글 → ?123 → Number` 반복 통과 |
| 최종 A35 설치 | `PASS` | `SM-A356N / RFCX60GBL3D`, 최신 arm64 app/plugin 설치, debug Fcitx IME 재선택 후 `?123 → Number` 확인 |
| Z Fold6 설치 | `PASS` | `SM-F956N`, 최신 arm64 app/plugin 설치, debug Fcitx IME 재선택 후 cover 화면에서 `?123 → Number` 확인 |

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
11. AI 지시문, GIF·통합 검색 등 IME가 소유한 모든 text 입력은 현재 `KeyboardWindow`의 layout,
    Fcitx 조합, 후보, 숫자·기호 전환, theme, 높이를 재사용한다. 기능별 두벌식 복제판을 만들지 않는다.

## 6. 통합 제품 백로그

### 6.1 현재 활성 마일스톤

| ID | 기능 | 상태 | 가치 | 난이도 |
| --- | --- | --- | --- | --- |
| `GIF-01` | GIF 검색·링크·첨부 파이프라인 | `DONE` | 키보드 이탈 없이 GIF 검색·전달 | L |
| `GIF-02` | 실사용 리액션 GIF 공급자 | `IN_PROGRESS` | KLIPY 한국어 검색·밈 catalog 구현 완료, production key·승인 gate 남음 | M |
| `GIF-03` | 선택형 GIPHY 공급자 | `IN_PROGRESS` | 비혼합·branding·analytics·안전등급 구현, production/media-copy 승인 gate | M |
| `VOICE-01` | GPT 실시간 받아쓰기 안정화 | `IN_PROGRESS` | timeout·서버 오류 즉시 복구 구현, 실제 STT key 한국어 품질 gate 남음 | L |
| `VOICE-02` | 고정밀 녹음 전사 | `IN_PROGRESS` | push-to-stop·preview 구현, 실제 STT key·Z Fold6 품질 gate 남음 | L |
| `VOICE-03` | 화자 분리 회의·메모 | `IN_PROGRESS` | 파일 선택·화자 구간 preview·선택 삽입 구현, 실제 STT key·회의 음원 품질 gate 남음 | L |
| `VOICE-04` | Codex 구독 OAuth 음성 bridge | `BLOCK` | 공개 CLI·HTTP audio 계약이 없어 비공식 OAuth token 역이용 금지 | L |
| `UX-03` | 흔들리지 않는 기능 툴바 | `DONE` | 기본 1행·명시적 2행, 입력 중 높이 변화 차단 | M |
| `SEC-01` | 민감 빠른 문구 금고 | `IN_PROGRESS` | 인증 결합 저장·세션 구현, 생체 실기기 gate 남음 | L |

`GIF-01`의 전송 파이프라인 계약과 `GIF-02`의 공급자 계약은 7절에 있다. 사용자 실사용 결과
Animated Noto Emoji는 움직이는 이모지 fallback으로는 유효하지만 리액션 GIF catalog로는 현저히
부족했다. KLIPY 공급자를 별도 source와 cache namespace로 구현하고 A35·Z Fold6에서 실사용 catalog를
검증했다. 다만 저장소에 test key를 넣지 않으며 production key와 partner 승인이 끝날 때까지
`GIF-02`의 배포 상태는 `IN_PROGRESS`로 유지한다.

현재 코드 우선순위는 글쓰기 AI와 음성 STT의 설정·자격증명·실행 gate를 분리된 제품 흐름으로 마감하고
전체 기능 회귀를 닫는 것이다. `UX-03` 높이 안정성은 코드와 emulator·Z Fold6 cover 검증을 마쳤다.
코드·emulator로 닫을 수 있는 회귀 gate와 owner·하드웨어·공급자 외부 gate를 별도로 기록한다. 외부
gate는 A35 최신 APK 전체 matrix, Z Fold6 unfolded 전환, 실제 STT key의 한국어 품질, 생체 인증,
GIF production key·partner 승인이다. 외부 gate 때문에 무관한 코드 항목을 계속 `IN_PROGRESS`로 두거나,
반대로 코드가 통과했다는 이유로 외부 gate가 필요한 음성·보안 항목을 `DONE`으로 올리지 않는다.

### 6.2 1차 한국어 로컬 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `KO-01` | 한/영 오타 즉시 복구 | `DONE` | `dkssud→안녕`, `ㅗ디ㅣㅐ→hello`, preview 후 교체 | S |
| `KO-02` | 초성 통합 검색 | `DONE` | 빠른 문구·clipboard·emoji를 `ㄱㅅ` 등으로 검색 | M |
| `KO-03` | 동적 빠른 문구 | `DONE` | 날짜·시간·이름·전화·이메일·주소·clipboard 변수, preview | M |
| `KO-03A` | 자동 스니펫 확장 | `DONE` | `:` trigger 뒤 space·Enter로 암호화 profile 또는 사용자 상용구 확장 | M |
| `KO-04` | 앱별 키보드 profile | `DONE` | package별 layout, theme, transport, toolbar, AI 정책 | M |
| `KO-05` | 한자 음훈 후보 | `DONE` | bundled libhangul 한자·독음·뜻을 명시적 1회 변환 후보로 표시 | M |
| `KO-05A` | 국어사전 정의 후보 | `DONE` | 한글 통합 검색에서 오프라인 정의·품사·출처를 읽기 전용으로 표시하고 입력 원문은 변경하지 않음 | M |
| `KO-05B` | 국어사전 cold-load 최적화 | `DONE` | 정렬 binary offset index로 정의를 lazy decode하고 JVM p95·phone/wide emulator 첫 조회 2초 이하를 검증 | M |
| `KO-06` | 개인 단어장 | `DONE` | opt-in·백업 제외 원자 저장, 개인 후보 우선순위·중복 제거·민감 editor 차단과 emulator 실제 후보 선택 통과 | L |
| `KO-07` | 한국어 조사·문맥 후보 | `DONE` | 받침·ㄹ 예외 조사와 공백 경계 로컬 다음 어절 후보, emulator UI·선택·취소·정확히 1회 삽입 통과 | L |
| `KO-08` | 한국식 감정표현 추천 | `DONE` | 로컬 emoji·kaomoji·ㅋㅋ/ㅎㅎ 강·약 chip, emulator 후보·정확히 1회 삽입·민감 editor 차단 통과 | M |
| `KO-09` | 한글 어절 자동완성 | `DONE` | 두 음절부터 로컬 접두어 후보, 일반·buffered 명시 선택·미선택 Space·private 차단 emulator 통과 | M |

#### KO-09 상세 계약

1. 두벌식·세벌식·모바일 한글 표면에서 현재 조합 중인 어절이 완성형 한글 두 음절 이상이면
   영어 단어 힌트와 같은 가로 후보 영역에 현재 입력과 접두어 완성 후보를 표시한다.
2. 후보는 기기 안의 정적 사전만 사용한다. MVP에서는 입력 원문·선택 이력·앱 package를 저장하거나
   네트워크로 보내지 않으며 개인 학습은 `KO-06`으로 분리한다.
3. 기본 어휘는 국립국어원의 `한국어 학습용 어휘 목록` 5,965개에서 완성형 한글 표제어를 추출하고,
   일상 대화에서 필요한 활용형·인사말은 프로젝트 보강 목록으로 앞에 둔다. 원본 checksum, 출처,
   공공누리 제1유형 표시와 생성 스크립트를 함께 보존한다.
4. 정렬은 현재 입력 자체, 일상 표현 보강 순위, 국립국어원 빈도 순위 순이다. 같은 문자열은 한 번만
   표시하고 한 페이지 크기를 넘는 결과는 기존 확장 후보 UI에서 탐색한다.
5. 후보는 자동 적용하지 않는다. 카드 tap, 숫자 키 또는 사용자가 Tab으로 후보를 명시적으로 고른 뒤
   Enter를 누른 경우에만 적용한다. 후보가 미선택인 Enter·space·문장부호는 현재 어절을 그대로 확정한다.
6. 선택 시 아직 조합 중인 부분을 초기화하고, 이미 editor 또는 buffered transport로 빠진 접두어 뒤의
   접미부만 `commitString`으로 정확히 한 번 보낸다. 실패 뒤 다른 삽입 방식을 자동 시도하지 않는다.
7. backspace, cursor 이동, focus 변경, input method 변경, reset 뒤에는 추적 접두어와 후보를 함께
   정리해 이전 editor의 후보를 재사용하지 않는다.
8. password, sensitive, `NoSpellCheck` editor와 한자 모드에서는 후보를 만들지 않는다. 사전이 없거나
   손상됐을 때는 입력 자체를 막지 않고 자동완성만 fail-closed한다.
9. 한글 addon 설정에 `한글 어절 자동완성` toggle을 제공하며 기본값은 켬이다. 설정 변경은 현재
   조합을 손상시키지 않고 다음 후보 갱신부터 반영한다.

완료 게이트는 사전 parser·순위·중복·limit 테스트, `안녕→안녕하세요` 접두어 테스트, 미선택 Enter와
space 비치환 테스트, backspace/reset·민감 editor·buffered 접미부 1회 확정 검증, 전체 app/plugin build,
API 34 emulator의 일반 editor 및 buffered 호환 editor 실제 입력 검증이다.

#### KO-09 구현·검증 증거 (2026-07-26)

| 항목 | 상태 | 증거 |
|---|---|---|
| 사전 출처·재현성 | `PASS` | 국립국어원 원본 SHA-256을 고정하고 생성 결과 5,250개·91,467 bytes·SHA-256 `1778F7ACCBE3190A3ECDDFC9991B2511F466425B48185004072C22558BBBA2C1` 재현 |
| native parser·후보 테스트 | `PASS` | Android arm64-v8a A35와 API 34 x86_64 emulator의 `/data/local/tmp`에서 `testcompletiondictionary`를 실행해 순위·limit·중복·CRLF·접미부·다음 어절 정책 검증 |
| 전체 자동 테스트·빌드 | `PASS` | `:app:testDebugUnitTest :app:assembleDebug :plugin:hangul:assembleDebug -PbuildABI=x86_64`, 현재 기준 64 suites·281 tests·실패 0 |
| plugin 패키징 | `PASS` | plugin APK에 `completion.txt`, `completion-NOTICE.md`, 한국어 번역, `libhangul.so` 포함 및 AboutLibraries에 KOGL 제1유형 표시 확인 |
| A35 후보 UX | `PASS` | Discord에서 `안녕` 입력 시 `안녕 / 안녕하세요 / 안녕하십니까` 순서로 표시 |
| A35 명시적 선택 | `PASS` | `안녕하세요` 후보 tap 뒤 editor hierarchy의 compose text가 `안녕하세요` 정확히 한 번임을 확인; 메시지는 전송하지 않고 draft 삭제 |
| A35 미선택 space | `PASS` | 후보를 누르지 않고 space 입력 뒤 compose text가 `안녕 `으로 유지되고 자동완성 후보로 치환되지 않음을 확인 |
| emulator 일반 editor | `PASS/EMULATOR` | Pixel 7 API 34 x86_64의 Settings search에서 `안녕 / 안녕하세요 / 안녕하십니까` 후보를 확인하고 `안녕하세요` 선택 뒤 editor XML이 정확히 1회, 미선택 Space 뒤 `안녕 ` 그대로임을 확인 |
| emulator buffered editor | `PASS/EMULATOR` | 한글 버퍼 호환 모드·Direct commit에서 editor를 scratch space로 쓰지 않고 IME preedit `안녕→안녕하세요`를 만든 뒤 Space boundary에서 `안녕하세요 `를 정확히 1회 전달 |
| 한글 후보 언어 경계 | `PASS` | 자동완성 사전은 현대 한글 음절만 적재·노출하고, 자동완성 중 지속 Hanja 후보가 후보창을 선점하지 않도록 분리; 한자는 명시적 1회 변환 action으로 유지 |
| 한자 상태 표현 회귀 | `FIXED` | 자동완성이 켜진 기기의 과거 `HanjaMode=True`를 시작·설정 저장 시 `False`로 정규화하고, 상태 영역은 현재 모드를 `한글`로 표시한다. 한자 후보는 명시적으로 요청한 1회 변환에서만 연다. |
| 민감 editor 차단 | `PASS/EMULATOR` | `password=true`인 OpenAI STT API 키 editor가 자동으로 비완성 입력 surface를 사용하고 한글 완성 후보를 노출하지 않음. test text는 취소 전에 삭제했고 key 상태는 `연결 안 됨`으로 유지 |
| 최종 A35 설치 | `PASS` | `SM-A356N / RFCX60GBL3D`, 최신 app/plugin `0.1.2-92-g0c3b30cf` 설치 및 debug Fcitx IME 재선택 |
| 최종 Z Fold6 설치 | `SUPERSEDED` | 자동완성은 posture·sensor·제조사 의존성이 없어 API 34 emulator를 canonical Android gate로 인정. Fold posture 검증과 분리 |

#### KO-06 구현·검증 증거 (2026-07-27)

| 항목 | 상태 | 증거 |
|---|---|---|
| 저장·parser 정책 | `PASS` | `noBackupFilesDir/korean-personal-dictionary/words.txt` v1 형식, `AtomicFile`, 최대 500개·64 KiB·완성형 한글 2~32자, 중복 제거와 malformed UTF-8·header·category·행 수 fail-closed JVM/native 테스트 |
| opt-in·privacy | `PASS` | 기본 off이며 직접 추가한 단어만 저장한다. Password·Sensitive·NoSpellCheck editor는 개인/기본 자동완성을 모두 조회 전 차단하고 입력 원문·선택 기록·package를 저장하지 않는다 |
| emulator 실제 후보 | `PASS/EMULATOR` | API 34 x86_64에서 `챈파카`를 이름으로 추가하고 개인 후보를 켠 뒤 `챈파` 조합 시 `챈파 / 챈파카` 순서 확인. 개인 후보 tap 뒤 대상 editor가 `챈파카` 정확히 1회이며 crash 없음 |
| cleanup | `PASS/EMULATOR` | 테스트 단어를 앱 UI로 삭제하고 개인 후보를 off로 복구. 최종 파일은 v1 header와 `enabled\t0`만 남아 사용자 테스트 데이터 없음 |

#### KO-08 구현·검증 증거 (2026-07-27)

| 항목 | 상태 | 증거 |
|---|---|---|
| 로컬 catalog·privacy | `PASS` | 축하·죄송·감사·당황·웃음·사랑·화남·슬픔·응원·인정별 emoji·한국형 표현·kaomoji를 editor·clipboard·network 입력 없이 정렬하고 중복 제거. private policy는 후보 생성 전에 차단 |
| 강·약 웃음 chip | `PASS/EMULATOR` | Pixel 7 API 34 x86_64에서 `ㅋㅋ` top `😂`, `ㅋㅋㅋㅋ` top `🤣`, `ㅎㅎㅎㅎ` top `😄`를 확인. `ㅋㅋ/ㅋㅋㅋㅋ/ㅎㅎ/ㅎㅎㅎㅎ`를 같은 가로 quick row에서 직접 선택 가능하도록 노출 |
| 일반 감정 후보 | `PASS/EMULATOR` | `축하` chip에서 `🎉`, `🥳`, `👏`, 한국형 표현·kaomoji 후보를 세로 목록으로 탐색하고 설명 label이 함께 표시되는 것을 확인 |
| 정확히 1회 삽입 | `PASS/EMULATOR` | `축하` 후보 `🥳`와 강한 웃음 후보 `😄`를 각각 명시적으로 tap한 뒤 대상 editor XML이 선택한 표현 하나만 정확히 1회 포함하고 일반 keyboard로 복귀함을 확인 |
| private editor 차단 | `PASS/EMULATOR` | `password=true`인 OpenAI STT API 키 editor에서는 감정 검색을 포함한 clipboard·AI toolbar surface가 노출되지 않음. 별도 policy·stale editor·exactly-once 회귀 테스트도 통과 |

#### KO-02 상세 계약

1. 키보드 툴바에서 통합 검색을 열고 화면 안의 19개 초성 패드로 query를 즉시 조합한다.
   일반 문자열은 같은 화면의 입력 대화상자에서 붙여넣기 또는 물리 키보드로 입력한다.
2. `ㄱㅅ`은 `감사합니다`, `감사`, `고생`처럼 음절 초성이 연속 일치하는 결과를 찾는다.
3. 결과는 빠른 문구, 민감하지 않은 clipboard, 한국어 keyword가 붙은 emoji를 같은 목록에서
   source label로 구분해 표시한다. 원본 저장소를 복제하거나 서로 덮어쓰지 않는다.
4. 초성 prefix, 일반 prefix, 초성 contains, 일반 contains 순으로 점수를 부여하고 같은 점수에서는
   빠른 문구, clipboard, emoji 순과 원본 순서를 유지한다.
5. 결과를 탭하면 조합을 먼저 안전하게 확정하고 `commitText`를 정확히 한 번 호출한 뒤 일반 키보드로
   돌아간다. editor identity가 바뀌었으면 삽입하지 않고 오류를 표시한다.
6. sensitive clipboard 항목은 검색 repository 단계에서 제외한다. password, sensitive,
   no-personalized-learning editor에서는 통합 검색 전체를 열어도 데이터 조회와 삽입을 차단한다.
7. 검색은 전부 기기 안에서 수행하며 query나 결과 원문을 로그·분석·네트워크로 보내지 않는다.
8. 빈 query는 전체 clipboard를 노출하지 않고 검색 안내만 표시한다. 결과 수에는 상한을 둔다.

완료 게이트는 초성 matcher·정렬·dedupe·민감 항목 제외 테스트, 전체 JVM test와 arm64 build,
A35에서 `ㄱㅅ` 검색 후 빠른 문구 또는 emoji 1회 삽입, 일반 문자열 clipboard 검색 검증이다.

#### KO-03 상세 계약

1. 기존 `.mb` 빠른 문구 파일 형식과 Fcitx quickphrase addon은 변경하지 않는다. 문구 값에
   지원 토큰이 포함된 경우에만 Android 입력 계층에서 동적 미리보기를 연다.
2. MVP 토큰은 `{날짜}`, `{시간}`, `{이름}`, `{전화번호}`, `{이메일}`, `{주소}`, `{클립보드}`이며 영문
   호환 alias `{date}`, `{time}`, `{name}`, `{phone}`, `{email}`, `{address}`, `{clipboard}`도 허용한다.
3. `{날짜}`는 `yyyy년 M월 d일`, `{시간}`은 기기 시간대의 `HH:mm`으로 확장한다. 미리보기가
   열린 시점의 값을 고정해 확인한 문자열과 실제 삽입 문자열이 달라지지 않게 한다.
4. 지원하지 않는 `{토큰}`은 원문 그대로 보존한다. 지원 토큰의 값이 비어 있거나 정책상
   차단되면 삽입 버튼을 비활성화하고 누락 이유를 변수별로 표시한다.
5. `{이름}`, `{전화번호}`, `{이메일}`, `{주소}` profile은 Android Keystore AES-GCM으로 암호화하고
   `noBackupFilesDir`에 저장한다. SharedPreferences, 사용자 ZIP export, clipboard, log에는
   평문을 남기지 않는다.
6. `{클립보드}`는 Fcitx clipboard의 최신 non-sensitive text만 사용한다. sensitive 항목,
   password/sensitive/no-personalized-learning editor에서는 값을 읽지 않는다.
7. 날짜·시간만 쓰는 문구는 private editor에서도 사용할 수 있지만, 개인 profile 또는
   clipboard 토큰이 하나라도 있으면 해당 값은 fail-closed한다.
8. 미리보기에서 사용자가 `넣기`를 누른 경우에만 editor identity와 selection을 다시 확인하고
   확장 결과를 `commitText()`로 정확히 한 번 삽입한다. editor가 바뀌면 삽입하지 않는다.
9. 기존 조합과 buffered Hangul prefix를 먼저 정리한 뒤 미리보기를 연다. 취소하거나 실패해도
   원본 토큰 문구를 자동 삽입하지 않는다.
10. 빠른 문구 편집 화면에 지원 토큰 안내를, 빠른 문구 목록의 추가 메뉴에 암호화 profile
    설정 진입점을 제공한다.

#### KO-03A 자동 스니펫 상세 계약

1. 기본 별칭은 `:이름`, `:전화`, `:전화번호`, `:이메일`, `:메일`, `:주소`, `:주소1`, `:날짜`,
   `:시간`과 영문 `:name`, `:phone`, `:email`, `:address`, `:address1`, `:date`, `:time`이며 각각
   KO-03 동적 변수로 해석한다. 값은 별칭이나 상용구 파일에 복제하지 않는다.
2. 활성 상용구의 키워드가 `:`로 시작하고 공백이 없으면 사용자 스니펫으로 등록한다. 구문은 일반
   문자열과 KO-03 동적 토큰을 모두 허용한다. 같은 키워드가 서로 다른 구문에 중복되면 자동 선택하지
   않고 literal 입력을 보존한다.
3. trigger는 문장 시작 또는 공백 뒤에서 시작해 cursor 바로 앞에서 정확히 끝나야 한다. URL·시간·emoji
   shortcode의 일부처럼 앞 문자가 붙은 `:` 문자열은 확장하지 않는다.
4. space 경계는 trigger를 확장 결과로 교체하고 공백 하나를 유지한다. Enter 경계는 채팅 전송 사고를
   막기 위해 확장만 하고 해당 Enter를 소비하며, 사용자가 다시 Enter를 눌러야 전송 또는 줄바꿈된다.
5. 일반 composing과 한글 buffered compatibility 경로 모두 trigger를 정확히 한 번 삭제하고 결과를
   정확히 한 번 삽입한다. buffered 경로는 trigger 앞의 보류 중인 일반 텍스트를 보존한다.
6. 자동 스니펫은 기본 켬 toggle을 제공한다. password, sensitive, no-personalized-learning editor에서는
   상용구와 주변 텍스트를 읽지 않고 완전히 비활성화한다.
7. 개인 profile은 기존 Keystore·`noBackupFilesDir` 정책을 그대로 사용한다. 값이 비었거나 복호화에
   실패하거나 editor/selection이 바뀐 경우 literal trigger와 경계키를 보존하고 자동 대체하지 않는다.
8. 카탈로그는 입력 시작 시 background에서 갱신하며 키 입력마다 전체 상용구 파일이나 Keystore를 읽지
   않는다. 실제 trigger가 일치한 경우에만 필요한 profile을 복호화한다.

완료 게이트는 기본 별칭·사용자 override·중복·경계 오탐·space/Enter 정책·일반/buffered 분할 trigger
계획 테스트, email profile 하위 호환 테스트, 전체 JVM/build, API 34 emulator에서 `:주소1`·`:이메일`
확장 및 private editor 비활성 검증이다. 이 기능은 자세·화면 크기·실물 센서에 의존하지 않으므로 Fold
실기기 확인을 완료 조건으로 중복 요구하지 않는다.

#### KO-03A 구현·검증 증거 (2026-07-27)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 스니펫 catalog | `PASS` | 기본 `:주소1`·`:이메일`, 사용자 override, URL/어절 내부 오탐 방지, 분할 입력, 후행 문자열 거부를 6개 JVM 테스트로 검증 |
| A35 space 확장 | `PASS` | `:주소1 `과 `:이메일 `이 암호화 profile 값으로 정확히 한 번 교체됨 |
| A35 Enter 확장 | `PASS` | trigger 뒤 Enter가 먼저 스니펫을 확장하고 경계 동작을 한 번만 적용함 |
| private editor | `PASS` | private editor에서 자동 스니펫을 비활성화하고 literal 입력을 보존함 |
| emulator 일반 입력 | `PASS` | API 34 `Pixel_7_API_34`에서 영문 `:address1 `→`TEST ADDRESS `, 한글 `:주소1 `→`TEST ADDRESS `, `:이메일 `→`test@example.com `을 각각 정확히 한 번 치환 |
| emulator Enter | `PASS` | `:이메일` 뒤 첫 Enter가 `test@example.com`으로만 확장되고 Enter 자체는 소비됨 |
| emulator buffered 분할 | `PASS` | `DirectCommit`에서 editor에는 `:`만 있고 IME buffer에 `이메일`이 남은 상태로 Space를 눌러 `test@example.com `을 정확히 한 번 확정 |
| emulator private editor | `PASS` | `password=true` editor에서 `:email `이 7개 bullet의 literal로 유지되고 profile 값은 hierarchy에 나타나지 않음 |
| 보안 정리 | `PASS` | 임시 profile은 `no_backup/dynamic-phrase/profile.bin`에만 암호문으로 생성한 뒤 삭제했고, 테스트 종료 시 buffer `끔`·`SystemPaste`로 복구 |
| 전체 회귀 | `PASS` | app 64 suites·281 tests·failure/error/skipped 0, `x86_64` app/plugin build 성공 |
| Z Fold6 | `SUPERSEDED` | 자세·화면 크기에 의존하지 않는 입력 계약이라 emulator gate로 완료; Fold 실기기 중복 확인은 요구하지 않음 |

#### KO-03 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| template JVM test | `PASS` | 7개, failure/error 0. 한·영 token, 시각 고정, 미지원 token 보존, 누락 값, private editor, sensitive clipboard 포함 |
| 전체 JVM/build | `PASS` | app 71개, failure/error/skipped 0; arm64 app과 Hangul plugin build 성공 |
| 암호화 profile | `PASS` | A35에서 Android Keystore AES-GCM 저장 후 `no_backup/dynamic-phrase/profile.bin`만 생성됨을 확인; 검증용 profile은 종료 후 삭제 |
| A35 날짜·시간 | `PASS` | `오늘은 {날짜} {시간}입니다.`가 미리보기에서 고정된 한국어 날짜·시간으로 치환되고 원본 trigger 없이 정확히 한 번 삽입됨 |
| A35 개인 profile | `PASS` | `{이름} / {전화번호} / {주소}`가 암호화 profile 값으로 미리보기되고 editor hierarchy에서 삽입 문자열 1회 확인 |
| A35 clipboard | `PASS` | 최신 non-sensitive `KO03_CLIPBOARD`가 `받은 내용: KO03_CLIPBOARD`로 치환됨; 원문 quickphrase는 자동 삽입되지 않음 |
| 민감 입력 | `PASS` | `privateEditorAllowsDateAndTimeButBlocksPersonalAndClipboard`, `sensitiveClipboardIsNeverExpanded`가 삽입 비활성 정책 검증 |
| 최종 A35 설치 | `PASS` | `SM-A356N / RFCX60GBL3D`, `0.1.2-84-g9522b817` app/plugin 재설치 및 debug Fcitx IME 재선택; app `09:12:45`, plugin `09:12:47` |
| 최종 Z Fold6 설치 | `SUPERSEDED` | 동적 문구는 자세·화면 크기에 의존하지 않아 기존 A35와 API 34 emulator 검증으로 완료 |

#### KO-02 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 검색 엔진 JVM test | `PASS` | 6개, failure/error 0. 초성 추출·연속 일치, 일반 문자열, exact/prefix/contains 정렬, source 우선순위, dedupe, limit 포함 |
| 원본 데이터 통합 | `PASS` | enabled 빠른 문구를 직접 읽고, Room clipboard는 `deleted=0 AND sensitive=0`, emoji는 분리된 한국어 keyword로 모델링 |
| 민감 입력 | `PASS` | sensitive 모델을 엔진에서 다시 제외하고 password/sensitive/no-personalized editor에서 toolbar와 repository 조회·삽입 차단 |
| A35 초성 UX | `PASS` | 19개 화면 내 초성 pad에서 `ㄱ`→`ㅅ`을 탭하자 `ㄱㅅ` query와 source label이 표시됨 |
| A35 정확한 삽입 | `PASS` | Discord에서 🙏 결과를 탭한 뒤 compose text가 Unicode U+1F64F 하나임을 hierarchy로 확인; 메시지는 전송하지 않고 draft 삭제 |
| 전체 JVM/build | `PASS` | app 64개, failure/error/skipped 0; arm64 app과 Hangul plugin build 성공 |
| 최종 A35 설치 | `PASS` | app/plugin 재설치 및 Fcitx IME 재선택, app `2026-07-26 08:21:07`, plugin `08:21:09` |

#### KO-04 앱별 profile 계약·증거 (2026-07-27)

1. profile key는 Android package name의 정확 일치다. profile이 없으면 기존 전역 layout·theme·toolbar·
   buffered transport를 그대로 사용한다.
2. 앱별 `network=Allow` 또는 `AI=Allow`는 private/password/`NoPersonalizedLearning` editor와 전역
   offline mode를 절대로 해제하지 못한다. 앱별 `Block`은 toolbar와 직접 window 진입 모두 차단한다.
3. package name 자체도 앱 사용 정보이므로 profile JSON은 `noBackupFilesDir/app-profile/profiles.json`에
   `AtomicFile`로 저장하며 사용자 ZIP export에 넣지 않는다.
4. 현재 앱에 profile이 있으면 키보드 단의 한글 표면 전환은 전역 설정이 아니라 그 profile에 저장한다.

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| resolver·migration test | `PASS` | 8개 테스트가 전역 fallback, package exact match, private/offline hard deny, v0→v1, unknown enum inherit를 검증 |
| 설정 dialog viewport | `PASS` | 6개 selector가 있는 form을 화면 높이의 48%·최대 420dp viewport로 제한해 API 34 emulator에서 취소·저장 버튼이 항상 화면 안에 노출됨. 경계 테스트 2개 추가 |
| app/plugin build | `PASS` | x86_64 app·Hangul plugin assemble과 app 65 suites·283 tests, failure/error/skipped 0 |
| A35 설정 UI | `PASS` | `com.android.chrome` profile을 추가하고 `기본 펼침`을 저장한 뒤 목록 summary에서 재확인 |
| A35 runtime | `PASS` | Chrome editor를 다시 열자 접혀 있던 toolbar가 profile에 따라 펼쳐지고 GIF 진입 버튼이 즉시 표시됨 |
| emulator exact match | `PASS` | `com.android.chrome` profile 재시작 뒤 2행 toolbar·`천지인 플러스`·network/AI 차단이 즉시 적용됨 |
| keyboard surface 저장 | `PASS` | Chrome에서 Space 길게 눌러 `단모음`으로 바꾸자 해당 package profile JSON만 `Danmoum`으로 원자 갱신되고 표면도 즉시 전환됨 |
| exact fallback | `PASS` | `com.android.settings` profile은 실제 editor package `com.google.android.settings.intelligence`에 적용되지 않고 전역 Physical layout·접힌 toolbar로 복귀 |
| 저장·정리 | `PASS` | profile은 `no_backup/app-profile/profiles.json`에만 존재했고 검증 후 임시 profile 파일을 제거해 기본 상태로 복구 |
| Z Fold6 profile matrix | `SUPERSEDED` | package 정책 자체는 posture·sensor에 의존하지 않아 emulator를 Android 완료 gate로 인정. Fold 전용 cover/unfold layout은 `UX-02`에서만 별도 검증 |

#### KO-05 한자 음훈과 국어사전 경계 (2026-07-27)

- 배포 중인 libhangul `hanja.txt`에는 `가:可:옳을 가`처럼 글자·독음·뜻이 이미 포함돼 있었지만
  Hangul addon이 candidate text만 전달하고 comment를 버리고 있었다.
- `HangulCandidate`가 comment를 Fcitx candidate metadata로 전달하도록 수정한다. Android의 기존
  `CandidateItemUi`는 comment가 있을 때 `글자 · 음훈`으로 표시하므로 별도 추측 사전을 만들지 않는다.
- 국어사전 정의·예문은 국립국어원 한국어기초사전 Open API/전체 내려받기와 라이선스를 별도 검토한다.
  API key가 필요한 원격 조회를 한자 후보와 암묵적으로 섞지 않는다.

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| native data | `PASS` | prebuilt libhangul 표제 `가→可→옳을 가` 확인 |
| addon build | `PASS` | comment 전달 코드 포함 arm64 Hangul plugin assemble 성공 |
| native regression | `PASS` | `testhangul.cpp`가 F9 뒤 첫 후보 `可`와 comment `옳을 가`를 assertion; Android TestFrontend target 부재로 cross compile 실행은 별도 gate |
| status action UX | `PASS` | 더보기의 상태 항목은 지속 한자 모드가 아닌 `한글`로 표시하고, 실행 직후 keyboard로 자동 복귀해 결과 후보를 바로 노출 |
| Android surrounding race | `FIXED` | `commitString()` 직후 stale surrounding text를 다시 읽지 않고 flush 전에 전체 활성 어절을 보존해 한자 lookup에 사용 |
| emulator candidate UI | `PASS` | `가`의 명시 액션 뒤 `可 옳을 가`, `家 집 가`, `加 더할 가`가 표시되고 첫 후보 선택 시 editor가 `可` 한 글자로 정확히 1회 교체 |
| 한글 모드 복귀 | `PASS` | 한자 선택 뒤 `안녕`을 새로 입력하면 `안녕하세요`·`안녕하십니까` 등 한글 자동완성만 노출되고 한자 후보가 잔류하지 않음 |
| x86_64 build/test | `PASS` | app·Hangul plugin assemble과 app 65 suites·283 tests, failure/error/skipped 0 |
| Z Fold6 candidate UI | `SUPERSEDED` | 후보 생성·선택은 posture·sensor에 의존하지 않아 API 34 emulator를 Android 완료 gate로 인정 |
| 국어사전 정의 | `DONE` | `KO-05A`로 분리해 한글 통합 검색 안의 명시적 `국어사전` mode로 구현. 한자·자동완성 후보와 상태를 공유하지 않음 |

#### KO-05A 오프라인 국어사전 구현·검증 증거 (2026-07-27)

- 국립국어원 한국어기초사전 Open API는 32자리 인증 키가 필요하고 전체 내려받기는 사용 목적과
  이메일 제출이 필요하므로, 사용자 동의 없이 계정을 만들거나 이메일을 제출하지 않았다. 국립국어원
  text의 공공 라이선스와 별도 권리인 multimedia도 같은 asset으로 취급하지 않는다.
- 무키·오프라인 MVP는 한국어 위키낱말사전의 2026-07-03 dump를 2026-07-24에 추출한 Kaikki
  Wiktextract snapshot을 사용한다. 원본 gzip SHA-256은
  `DF65C8B26BD20DED6D7FC7616106670443C08B551E8D61949A1040E6D68A22E1`, 생성 결과는 현대 한글
  31,808개 표제어·33,530개 품사 entry·3,414,000 bytes이며 SHA-256은
  `F3EFFF8B1DEB278A890912B180106F9298EBFED9F617BAB2618DC2094CE7AB48`다.
- 생성 스크립트는 `lang_code=ko`와 현대 한글 표제어만 허용하고 한 entry당 정의를 최대 4개로
  제한한다. 결과는 deterministic sorted binary index이며 원본 URL·원본 checksum·dump/extract date와
  wiktextract commit을 attribution asset에 고정한다.
- 데이터는 CC BY-SA 4.0으로 표기하고 각 결과에서 정확한 한국어 위키낱말사전 문서 URL을 열어
  출처·기여자 이력에 도달할 수 있게 한다. 정의는 읽기 전용이며 `commitText`를 호출하지 않는다.
- password·sensitive·`NoSpellCheck` editor에서는 query capture와 조회를 차단한다. 선택 영역 또는
  cursor 인접 현대 한글 어절만 조회하고, 손상·누락 asset은 일반 입력에 영향 없이 fail-closed한다.

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 생성 재현성·라이선스 | `PASS` | 원본/결과 SHA-256 고정, 동일 결과 2회 재생성, AboutLibraries `CC-BY-SA-4.0`, 누락·unknown license 0 |
| parser·query 정책 | `PASS` | 현대 한글 exact/prefix, 선택·cursor 경계, malformed row·비한글·민감 editor fail-closed 단위 테스트 |
| 전체 자동 테스트·빌드 | `PASS` | `:app:testDebugUnitTest :app:assembleDebug -PbuildABI=x86_64`, 67 suites·306 tests·failure/error/skipped 0 |
| APK asset 계약 | `FIXED/PASS` | Android asset packaging이 `.gz`를 자동 확장해 만든 초기 경로 불일치를 발견한 뒤 최종 binary asset으로 교체; APK·`descriptor.json` 경로와 checksum 일치 |
| phone emulator UX | `PASS/EMULATOR` | Pixel 7 API 34 Settings search에서 `나무`를 입력하고 `국어사전` mode를 열어 `나무 · 명사`와 정의를 확인; editor 원문은 `나무` 그대로 유지 |
| wide emulator UX | `PASS/EMULATOR` | API 34 2560×1600 landscape·모아키에서 `나무` 입력, 영문 attribution·정의·source link가 잘림 없이 표시되고 원문이 변하지 않음을 확인 |
| runtime 안정성 | `PASS/EMULATOR` | 두 emulator에 동일 APK 설치, default IME 유지, startup crash·`FileNotFound`·`AndroidRuntime` fatal 0 |
| cold-load 성능 | `FIXED/PASS` | 3만여 정의 객체 eager parse를 offset table+조회 record lazy decode로 교체. bundled asset 20회 JVM cold-load p95 9ms·warm lookup p95 0ms, phone emulator 1,559ms·wide emulator 520ms |
| Z Fold6 실기기 | `NOT RUN` | 기기는 연결됐지만 사용자가 이후 작업을 emulator로 한정했으므로 설치·입력·데이터 변경을 수행하지 않음 |

#### KO-01 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 변환기 JVM test | `PASS` | 5개, failure/error 0. 영문 QWERTY→한글, 한글→영문, 겹자모·겹받침, 문장부호, cursor-local chunk 포함 |
| 안전한 교체 | `PASS` | 미리보기 뒤 editor identity와 직전 원문을 다시 확인하고 정확히 한 번 교체; 별도 실행 취소 제공 |
| 민감 입력 | `PASS` | password, sensitive, no-personalized-learning editor에서 툴바 action 비활성 |
| A35 Discord | `PASS` | `dkssudgktpdy` 전체를 `안녕하세요`로 교체한 뒤 원문으로 실행 취소됨; 메시지는 전송하지 않고 검증 draft 삭제 |
| 최종 app JVM test | `PASS` | GIF·KO-01을 포함한 58개, failure/error/skipped 0 |
| 최종 arm64 설치 | `PASS` | A35 app/plugin 재설치 및 Fcitx IME 재선택, app `2026-07-26 08:03:10`, plugin `08:03:13` |

### 6.3 AI 텍스트 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `AI-00` | AI provider·보안 기반 | `DONE` | provider profile, key vault, privacy gate, usage 표시 | L |
| `AI-01` | 한국어 맞춤법·띄어쓰기·조사 교정 | `DONE` | Unicode-safe diff·선택 적용·정확히 1회 교체·undo와 emulator 실제 Codex 교정 통과 | M |
| `AI-02` | 존댓말·말투 변환 | `DONE` | 존댓말·카톡·업무·거절·사과·고객응대 action과 실제 Codex/Claude 결과 matrix 통과 | M |
| `AI-03` | 빠른 문장 생성 | `DONE` | 프리셋·활성 키보드 직접 지시·빈 입력창 생성, 서로 다른 후보 정확히 3개, 실제 Codex/Claude 생성·교체·undo 통과 | M |
| `AI-04` | 답장 초안 | `DONE` | 선택·문단·명시적 clipboard·Sharesheet intake, TTL·privacy·정확히 1회 입력과 실제 답장 생성 통과 | M |
| `AI-05` | 키보드 번역 | `DONE` | 한↔영·일·중 action·preview·정확히 1회 교체와 emulator 실제 OAuth companion 번역 통과 | M |
| `AI-06` | AI provider profile | `DONE` | OpenAI·OpenAI-compatible endpoint, model tier, 암호화 BYOK 분리 | M |
| `AI-07` | 원격 호환 endpoint OAuth | `DONE` | public client Authorization Code + PKCE S256, 외부 브라우저, 암호화 token refresh·revoke·명시적 재로그인; PC CLI companion과 두 기기 live 통과 | L |
| `AI-08` | 일반 사용자 AI 연결 안내 | `DONE` | 미연결·OAuth 만료 상태에 설명과 `설정하기` CTA를 제공하고 개인정보·AI 화면으로 직행; private/offline/policy 차단과 분리 | S |
| `AI-09` | 내 컴퓨터 자동 발견·연결 | `DONE` | mDNS 발견, Tailscale HTTPS manifest 검증, 연결 확인, AppAuth login과 PC 재시작 후 DPAPI grant 복구; A35·Z Fold6 통과 | L |
| `AI-10` | 직접 지시 터치 안전·인증 복구 | `DONE` | prompt 상단까지 IME touchable inset으로 보고해 뒤 editor touch 관통을 차단하고 API key 401은 사용자용 `설정하기` 상태로 복구 | S |

### 6.4 음성·멀티모달 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `VOICE-01` | GPT 실시간 받아쓰기 | `IN_PROGRESS` | 24 kHz PCM streaming·item별 partial/final 조정·한국어 hint·최종 preview·exactly-once commit과 연결/마무리 timeout·준비 후 서버 오류의 즉시 녹음 중단 구현, 실제 key 한국어 품질 gate | L |
| `VOICE-02` | 고정밀 녹음 전사 | `IN_PROGRESS` | 시간 제안 없는 push-to-stop, 5분 memory safety boundary, preview와 최초 권한 복귀 구현; 실제 STT key 품질·Z Fold6 gate 남음 | L |
| `VOICE-03` | 화자 분리 회의·메모 | `IN_PROGRESS` | 명시 선택 파일·화자/timestamp preview·선택 삽입 구현, 실시간·정밀 모드가 같은 독립 STT profile을 재사용한다. picker가 IME를 detach해도 같은 editor에 새 회의 window를 복원하고, STT 401은 `설정하기`로 복구; 실제 OpenAI key·음원 품질 gate | L |
| `VOICE-04` | Codex 구독 OAuth 음성 bridge | `BLOCK` | Codex/ChatGPT desktop Voice를 Android 전사 결과로 반환할 공개 CLI·HTTP 계약이 없음. 비공식 OAuth token/API 역이용 금지 | L |
| `VOICE-05` | 휴대폰 받아쓰기 기본 모드 | `DONE` | 글쓰기 AI 연결 여부와 무관한 기본 음성 모드다. system voice IME가 있으면 즉시 전환하고, 없으면 Android 음성 입력 설정 안내를 제공한다 | S |
| `VOICE-06` | 독립 STT 공급자·보안 저장소 | `DONE` | 글쓰기 AI/OAuth와 분리된 OpenAI STT key·모델 선택, 공식 endpoint allowlist, Keystore/no-backup 저장과 즉시 삭제. 온라인 모드는 전용 key 저장 성공과 함께만 확정하고 설정 취소 시 기존 휴대폰 받아쓰기를 보존 | M |
| `MM-01` | OCR·사진 속 한글 입력 | `DONE` | 명시 선택 이미지의 로컬 한글 OCR·줄별 preview·1회 삽입과 picker 복귀 복원, `tessdata_best`·EXIF/픽셀 회전·저화질 emulator 정확도 matrix, 공개 native source build·라이선스 고지 통과 | L |

### 6.5 편의·기기·보안 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `UX-01` | Smart clipboard action | `DONE` | 명시 선택·서식 제거·합치기·전화/계좌 형식화·PII 마스킹, emulator 미리보기·1회 삽입·민감 editor 차단 통과 | M |
| `UX-02` | Fold·tablet 분할 키보드 | `DONE` | compact/expanded·세로/가로 profile, 초기 복원, 중앙 non-touch gap, 두벌식 자음·모음 손 경계와 모바일 표면·숫자판 왕복을 API 34 phone/tablet emulator에서 검증 | L |
| `UX-03` | 안정형 기능 툴바 | `DONE` | 열린 툴바는 기본 1행 가로 스크롤이며 고정 펼침 버튼을 명시적으로 누를 때만 2행 6열이 된다. 자동 제안은 1행을 유지하고, 명시적으로 펼친 세션만 96dp envelope를 이어받아 입력 중 editor가 요동하지 않는다 | M |
| `SEC-01` | 민감 빠른 문구 금고 | `IN_PROGRESS` | 인증 결합 Keystore·60초 package 세션·allowlist 구현, 생체 실기기 gate | L |
| `SEC-02` | Privacy dashboard | `DONE` | 기능별 전송 범위, provider, 집계 사용량, 즉시 삭제 | M |
| `SEC-03` | 완전 offline mode | `DONE` | AI·OpenAI 전사·GIF toolbar를 fail-closed로 비활성화하고 API 34 emulator의 Fcitx UID BPF 통계로 3개 동작 전후 network 0 byte·0 packet 검증 | S |

#### UX-01 구현·검증 증거 (2026-07-27)

| 항목 | 상태 | 증거 |
|---|---|---|
| 명시 선택·동작 menu | `PASS/EMULATOR` | Pixel 7 API 34 x86_64에서 smart mode 진입 뒤 카드 1개·2개를 직접 선택하고, 선택 수에 따라 단일 항목 동작과 `선택 항목 합치기` 활성 상태가 달라지는 것을 확인 |
| 개인정보 마스킹 | `PASS/EMULATOR` | `user@example.com 01012345678 123456789012`를 선택해 이메일·한국 전화번호·계좌 후보 3개가 각각 `u•••@e••••••.com`, `010-••••-5678`, `123-•••••-9012`로 가려진 local preview 확인 |
| 합치기·정확히 1회 삽입 | `PASS/EMULATOR` | `second item`과 개인정보 test 항목을 선택 순서대로 줄바꿈 합친 preview를 확인하고 명시적 `미리보기 넣기` 뒤 대상 editor XML에서 `user@example.com`과 `second item`이 각각 정확히 1회임을 확인 |
| private editor 차단 | `PASS/EMULATOR` | `password=true`인 OpenAI STT API 키 editor에서 clipboard·AI toolbar surface가 노출되지 않고 저장된 clipboard 원문에 접근할 수 없음을 확인 |
| 자동·회귀 검증 | `PASS` | transformer의 plain text·합치기·한국 전화·계좌 grouping·PII mask와 선택 상태·private/editor identity·exactly-once 회귀 테스트 통과 |

#### KO-07 구현·검증 증거 (2026-07-27)

| 항목 | 상태 | 증거 |
|---|---|---|
| 조사 규칙 UI | `PASS/EMULATOR` | Pixel 7 API 34 x86_64의 실제 한글 입력에서 `사과`는 `는·가·를·와·로·예요`, `집`은 `은·이·을·과·으로·이에요`, `길`은 ㄹ 예외 `로`를 표시 |
| 조사 정확히 1회 삽입 | `PASS/EMULATOR` | 명시 선택 뒤 editor XML이 각각 `사과는`, `집은`, `길로`이며 원 단어와 선택 조사가 각 1회임을 확인 |
| 다음 어절 선택 | `PASS/EMULATOR` | `오늘 ` 공백 경계에서 `하루도·날씨가` 로컬 후보를 표시하고 `하루도` 선택 뒤 editor XML이 `오늘 하루도`, 두 어절이 각 1회임을 확인 |
| 다음 어절 취소 | `PASS/EMULATOR` | `오늘 ` 뒤 후보가 표시된 상태에서 boundary space를 지우면 editor가 `오늘`로 복귀하고 다음 어절 후보가 사라짐을 확인 |
| fail-closed 회귀 | `PASS` | 조사 받침·ㄹ 예외, next-word 64KiB·500행 parser와 mode policy, 조사 package·field·inputType·cursor·context·membership identity 및 exactly-once gate 테스트 통과 |

### 6.6 2026-07-26 병렬 구현 checkpoint 계약

| ID | 코드 계약 | 현재 증거 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `KO-06` | `noBackupFilesDir` versioned·atomic 사전, 기본 off, 최대 500개, 개인 후보를 정적 후보보다 우선하고 중복 제거 | Kotlin store·policy test, native completion/cache test, API 34 emulator 등록·삭제·우선 후보·정확히 1회 선택 | 없음. 기기 posture·hardware 의존성이 없어 emulator를 canonical Android gate로 인정 |
| `KO-07` | 사용자가 `조사` chip을 눌렀을 때만 은/는·이/가·을/를·과/와·으로/로·이에요/예요를 받침과 ㄹ 예외로 제안 | 순수 규칙·editor identity·exactly-once 테스트와 API 34 emulator의 `사과·집·길` 후보·1회 삽입 | 없음. posture·sensor·제조사 의존성이 없어 emulator를 canonical Android gate로 인정 |
| `KO-08` | editor·clipboard를 읽지 않는 명시적 감정 chip, 로컬 emoji/kaomoji, ㅋㅋ·ㅎㅎ 반복 강도 순위 | 강도·privacy·dedupe·exactly-once 테스트, API 34 emulator의 일반·강한 웃음 chip·후보·1회 삽입·password editor 차단 | 없음. posture·sensor·제조사 의존성이 없어 emulator를 canonical Android gate로 인정 |
| `AI-04` | `ACTION_SEND text/plain` 또는 사용자가 누른 clipboard 행만 4,000자 이하로 process memory에 5분 보관 | action/MIME/TTL/private gate·editor identity·exactly-once 테스트 | Sharesheet→키보드 preview→답장 생성·삽입을 두 기기에서 확인 |
| `UX-01` | 최대 10개 명시 선택, plain text·합치기·한국 전화·명시적 계좌 grouping·PII mask preview | transformer·선택 상태·private gate·exactly-once 테스트, API 34 emulator의 일반 editor mask·합치기·1회 삽입과 password editor 차단 | 없음. posture·sensor·제조사 의존성이 없어 emulator를 canonical Android gate로 인정 |
| `SEC-01` | vault 전용 auth-bound AES-GCM, 매 read/write `CryptoObject` identity 확인, package allowlist, 60초 memory session | codec·allowlist·TTL·앱 전환·손상·commit gate 테스트 | Android 11+ 생체/기기 인증 prompt, 앱 전환 재잠금, 허용/비허용 package 확인 |
| `VOICE-02` | elapsed-only 16 kHz mono in-memory WAV, 5분 safety boundary, 명시 `transcription` capability, 한국어 hint, preview 뒤 1회 삽입 | PCM/WAV·multipart·capability·privacy·stale editor·commit 테스트 | 표준 transcription endpoint로 두 기기 permission/focus 복귀, 정확도와 취소 확인 |

이 checkpoint의 통합 자동 검증은 app JVM 45 suites·190 tests, failure/error/skipped 0과
arm64 app·Hangul plugin assemble까지 통과했다. 상태를 `DONE`으로 올리는 것은 위 실기기 게이트가
통과한 뒤로 제한한다.

app lint에서 이번 checkpoint가 처음 만든 `USE_BIOMETRIC` 권한 2건과 Android 11 API guard 3건을
발견해 수정했고 재실행에서 모두 사라졌다. 전체 lint는 기존 `fragment_setup.xml` AppCompat tint,
`removed_n_items` format, 다국어 번역 누락을 포함한 선행 부채 286 errors·67 warnings 때문에 계속
실패한다. 이번 기능의 녹색 build/test와 저장소 전체 lint 부채를 같은 상태로 표시하지 않는다.

첫 checkpoint `4a515bfa`는 A35 `SM-A356N`과 Z Fold6 `SM-F956N`에 app/plugin
`0.1.2-97-g4a515bfa`를 모두 덮어 설치하고 debug Fcitx IME를 재선택했다. A35에서 Sharesheet 원문이
AI 창에 표시되고 실제 답장 초안이 생성됐으며, `AI 정밀 받아쓰기` 화면에서 Android 마이크 permission
dialog까지 정상 진입했다. 권한은 자동 승인하지 않았다. A35에서 한국어 GIF 밈 chip·KLIPY grid와
`축하` 감정표현 후보를, Z Fold6 cover에서 GIF chip·2열 rich grid를 확인했다. 두 기기의 `?123`은
각 기기에 저장된 Number 또는 Symbol 목적지로 정상 전환됐다.

### 6.7 Fold·GIPHY·회의 전사 checkpoint 계약

| ID | 구현 계약 | 자동 검증 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `UX-02` | compact와 expanded profile을 독립 저장하고 각각 세로/가로 중앙 간격을 둔다. 두 축이 모두 600dp 이상일 때만 expanded로 판정하며 size가 불명확하면 일반 layout을 유지한다. Text surface는 물리 손 경계 `T/G/V`까지 왼쪽에 유지하고 숫자판은 split하지 않는다. | profile 경계·방향·독립 toggle·gap cap·fill key·두벌식 `ㅎ/ㅍ` 경계를 포함한 10 tests와 API 34 `QA_Tablet`의 첫 editor 복원·세로/가로·Moakey·중앙 무입력·`?123` 왕복 | 없음. hinge/posture 센서 자체는 실기기 전용 관찰 항목으로 분리 |
| `GIF-03` | GIPHY는 사용자가 명시적으로 고르는 별도 provider다. production review 확인 key가 없으면 network 0회이며 KLIPY로 자동 fallback하지 않는다. rating `g`, 한국어·한국 locale, Powered by GIPHY, canonical/media 분리와 load/click/sent analytics를 적용한다. | provider parser·pagination·안전등급·credential·resolver·analytics tests | 실제 production key review; GIF 첨부는 별도 media-copy 서면 승인 필요 |
| `VOICE-03` | `ACTION_OPEN_DOCUMENT`로 고른 content URI만 최대 60분·24MiB 범위에서 stream하고 화자·timestamp segment를 preview한다. 사용자가 체크한 segment만 16,000자 이하로 정확히 한 번 입력한다. | MIME/확장자·크기·시간·multipart·response parser·selection·commit tests, phone·tablet emulator picker detach/resume와 401 설정 CTA | 실제 OpenAI key와 회의 음원의 화자 분리 정확도 |

두 번째 checkpoint의 단독 통합 검증은 app JVM 51 suites·219 tests, failure/error/skipped 0과
arm64 app·Hangul plugin assemble을 통과했다. GIPHY key·회의 음원·화자 전사 원문은 prefs, cache,
backup, 일반 log에 저장하지 않는다.

`f5d212f8` 기준 app/plugin `0.1.2-98-gf5d212f8`를 A35 `SM-A356N`과 Z Fold6
`SM-F956N`에 덮어 설치하고 두 기기 모두 debug Fcitx IME를 다시 선택했다. A35에서 `?123` 숫자판
전환을 다시 확인했고, GIPHY key가 없는 상태가 `네트워크 요청 0회`로 표시되는 것을 확인했다.
회의 파일 화면은 현재 TwentyOz 호환 profile에서 capability 확인 전 차단되며, 사용자 파일은 고르거나
전송하지 않았다. Fold6 cover에서는 compact split을 끈 상태를 유지하고 expanded split만 켰다.
당시 남겼던 회전·모바일 표면·중앙 공백 입력 gate는 아래 API 34 tablet emulator 검증으로 닫았다.
실제 hinge/posture 센서 이벤트만 실기기 전용 관찰 항목으로 분리한다.

Z Fold6 내부 화면 `1856×2160`에서 기존 index 반분이 두벌식 둘째·셋째 행의 `ㅎ(G)`·`ㅍ(V)`을
모음 쪽으로 넘기는 결함을 재현했다. Text surface에 물리 QWERTY 손 경계 `5/5/5/3`을 적용하고
회귀 테스트를 추가한 뒤, 같은 펼친 화면에서 `ㅎ`과 `ㅍ`이 왼쪽 자음 그룹으로 복귀하고 영어
`G/V`도 왼쪽에 유지되는 것을 무선 ADB 캡처로 확인했다.

#### UX-02 emulator 완료 증거 (2026-07-27)

실기기를 들고 있지 않은 작업 시간의 Android 기준 환경을 phone `Pixel_7_API_34`와 tablet
`QA_Tablet`(API 34, 2560×1600, 320dpi) emulator로 고정했다. tablet의 expanded split을 켠 채
APK를 덮어 설치해 IME 프로세스를 새로 시작했을 때, 첫 editor부터 저장된 가로 128dp 간격이
적용됐다. 기존에는 첫 `onSizeChanged` 안에서 ConstraintLayout 자식 여백을 바꿔 같은 layout
pass에 변경이 덮였고, 설정을 껐다 켜야만 분할이 보였다. 분할 지오메트리를 다음 main-loop
turn에 다시 적용하도록 고쳐 초기 복원을 통과했다.

가로 Text surface는 `T/G/V` 왼쪽 경계를 유지하면서 256px gap 탭이 입력 0회, 실제 `Q` 탭이
`q` 1회였다. 세로 회전 후에는 저장된 96dp가 정확히 192px gap으로 바뀌었고 같은 중앙 무입력
검증을 통과했다. `?123`은 split 없이 모든 키를 유지했고 `ABC` 왕복 뒤 Text split이 복원됐다.
Hangul을 추가하고 `Moakey (two hand)` 표면으로 바꾼 뒤에도 세로·가로 gap 탭은 입력 0회,
실제 `ㅃ` 키는 각각 1회 입력됐다. 따라서 기능 완료 gate는 emulator로 닫으며, 실제 Fold의
hinge/posture 센서 이벤트는 코드 완료를 막지 않는 실기기 전용 관찰 항목으로 유지한다. 최종
통합 회귀는 app JVM 65 suites·288 tests, failure/error/skipped 0과 x86_64 app·Hangul plugin
assemble을 통과했다.

### 6.8 한국어 다음 단어·GIF 밈 품질·로컬 OCR checkpoint 계약

| ID | 구현 계약 | 현재 자동 증거 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `KO-07` | 실제 공백 경계 뒤에만 project-curated 로컬 다음 어절을 미선택 후보로 표시한다. 기존 완성·개인 단어·한자 후보와 mode를 섞지 않고, 선택 시 현재 후보 membership을 다시 확인한 뒤 1회 확정한다. | 64KiB·500행 fail-closed parser, 중복·limit·민감 editor policy, A35·Z Fold6 arm64 native test와 API 34 emulator의 `오늘 ` 노출·선택·취소·1회 삽입 | 없음. emulator를 canonical Android gate로 인정 |
| `GIF-02` | KLIPY exact query의 성공한 첫 page가 비었을 때만 한국어 반응 intent를 최대 2개 시도한다. 원 결과와 합치지 않고 recovery page를 단일 page로 종료한다. Noto는 로컬 tag 가중치와 emoji family 다양성을 적용한다. GIPHY에는 query 보정·재정렬·필터를 적용하지 않는다. | GIF 17 suites·64 tests, provider isolation·stable dedupe·safe fallback·Noto family diversity | KLIPY production access·branding review, 실제 희소 query의 두 기기 grid 품질 matrix |
| `MM-01` | JPEG·PNG·WebP content URI만 system document picker로 1회 받는다. 최대 15MiB·100MP source를 4MP 이하로 축소하고 Tesseract 한국어 모델로 기기 안에서 인식한다. 결과는 기본 미선택 줄별 preview 뒤 선택분만 1회 입력한다. picker가 IME를 분리해도 원 editor 신원과 one-shot URI를 process memory에 보관하고 같은 editor에서만 OCR window를 복원한다. | OCR contract·image bound·고정 commit/크기/SHA-256 model install·picker resume·orientation policy tests 17개, Pixel 7 API 34 emulator의 picker 복귀·한국어 UI 이미지 인식·줄 선택·정확히 1회 삽입, tablet emulator의 정방향·픽셀 90도 회전·저화질 이미지 목표 3줄 인식과 저화질 선택분 1회 삽입, Tesseract4Android 4.9.0 공개 tag source build와 native 라이선스 export | 없음. emulator를 canonical Android gate로 인정하고 생체·Fold posture처럼 하드웨어 고유 항목만 별도 관찰 |

이 checkpoint의 통합 자동 검증은 app JVM 56 suites·236 tests, failure/error/skipped 0과 arm64
app·Hangul plugin assemble을 통과했다. lint에서 새로 발견한 Android 6 `contentLengthLong` 1건은
API guard로 수정했고, 재실행 결과 이번 OCR·GIF·toolbar 변경 파일의 lint 항목은 0건이다. 전체 lint는
선행 부채 286 errors·67 warnings 때문에 계속 실패하며 첫 항목은 기존 `fragment_setup.xml`의
`android:tint`다.

OCR engine과 `tessdata_best` 한국어 모델은 Apache-2.0 계열의 공개 소스다. 모델은 사용자가 명시한
다운로드 동작에서만 고정 HTTPS URL로 받고, 고정 commit `e12c65a915945e4c28e237a9b52bc4a8f39a0cec`,
크기 12,528,128 bytes, SHA-256 `f888d4038348a0c3d25151e7f452bda0d74ca275b18cab146798bcbb94084fff`를 모두 검증해
`noBackupFilesDir/ocr/tesseract`에 둔다. 선택한 원본 이미지·content URI·인식 결과는 prefs, cache,
backup, 일반 log에 저장하지 않으며 Bitmap은 작업 종료 시 지우고 recycle한다. 모델 설치 뒤 OCR은
완전 offline mode에서도 동작하지만 password·sensitive·`NoSpellCheck` editor에서는 picker 전 차단한다.

2026-07-27 emulator 재검증에서 기존 callback 방식은 system picker가 IME window를 분리할 때
결과를 버리고 일반 키보드로 복귀하는 결함이 확인됐다. 이를 editor-bound one-shot resume queue로
교체해 동일 editor에서만 OCR window를 정확히 한 번 복원하도록 수정했다. Pixel 7 API 34에서는
한국어 UI screenshot에서 `이미지에서 한글` 줄을 인식하고 명시 선택한 그 줄만 설정 검색란에 1회
삽입했다. QA tablet API 34 가로 화면에서는 미설치 모델 안내와 두 동작 버튼이 겹침·잘림 없이
표시됐다. 통합 JVM 결과는 65 suites·288 tests, failure/error/skipped 0이다.

같은 날 정확도 재검증에서 기존 `tessdata_fast`는 선명한 생성 이미지조차 한국어 세 줄을 심하게
파편화해 기본 모델로 부적합하다고 판정했다. `tessdata_best`로 교체하고 8개 EXIF orientation을 decode
단계에서 보정했으며, 신뢰도·한글 비율·짧은 파편 줄 기준이 약할 때만 `PSM_SINGLE_BLOCK`·
`PSM_SPARSE_TEXT`와 90/-90/180도 픽셀 회전을 순차 시도하는 bounded fallback을 추가했다. QA tablet
API 34에서 정방향, EXIF 없는 픽셀 90도 회전, 480×304 blur/JPEG quality 18 이미지가 모두 목표 한국어
세 줄을 인식했다. 저화질 결과 세 줄을 선택해 기존 draft 뒤에 각각 정확히 한 번 삽입하고 삭제 뒤 원문
`17`로 복원했다. orientation policy 4개를 포함한 통합 JVM 결과는 66 suites·299 tests,
failure/error/skipped 0이며 x86_64 debug build도 통과했다.

2026-07-27 native 배포 감사에서 앱이 해석한 JitPack AAR의 SHA-256은
`bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`였고, APK에는
x86_64 `libtesseract.so`·`libleptonica.so`·`libjpeg.so`·`libpngx.so`가 모두 포함됐다. upstream
`Tesseract4Android 4.9.0` tag의 공개 commit
`15c534717b1cb58261b58d4e4c1200c7f81f668c`를 별도 clean checkout하고 Temurin JDK 17,
NDK `27.2.12479018`, CMake 3.22.1로 `:tesseract4android:assembleStandardRelease`를 실행해
arm64-v8a·armeabi-v7a·x86·x86_64 네 ABI AAR source build가 성공했다. 로컬 source-build AAR은
native build-id 등의 차이로 JitPack AAR과 byte-identical하지 않았으므로 재현성을 과장하지 않는다.
다만 F-Droid 공식 inclusion policy는 자유 소프트웨어이며 공개 source가 있는 JitPack prebuilt를 trusted
Maven dependency로 허용한다. 따라서 완료 기준은 공개 tag source build·license·현재 artifact 식별·APK
패키징으로 고정한다.

JitPack POM에 license 선언이 없어 기존 오픈 소스 라이선스 화면에서 Tesseract4Android가 빈 라이선스로
노출되던 결함도 함께 수정했다. wrapper·Tesseract·Leptonica·IJG libjpeg·libpng의 버전, source URL과
Apache-2.0·BSD-2-Clause·IJG·Libpng를 `app/licenses`에 명시했고,
`:app:exportLibrariesDebug` 결과 `ARTIFACTS WITHOUT LICENSE`와 `UNKNOWN LICENSES`가 모두 0이었다.
새 APK를 phone·tablet API 34 emulator에 설치한 뒤 라이선스 화면에서 wrapper와 네 native 구성요소의
라이선스를 확인했다. phone emulator에 보존돼 있던 구형 fast model(1,677,415 bytes)은 유효 모델로
오인하지 않았고, 명시적인 `한국어 모델 받기` 뒤 best model 12,528,128 bytes로 원자 교체되어 최종
SHA-256 `f888d4038348a0c3d25151e7f452bda0d74ca275b18cab146798bcbb94084fff`와 OCR ready 상태를 통과했다.

### 6.9 반응형 툴바·AI 설정 CTA checkpoint 계약

| ID | 구현 계약 | 자동 증거 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `UX-03` | 툴바를 열면 고정 48dp 펼침/접힘 control과 기존 12개 도구의 1행 가로 스크롤을 먼저 표시한다. 사용자가 control을 누른 경우에만 도구를 실제 2행 6열 grid·96dp로 배치한다. Candidate·Clipboard·NumberRow·InlineSuggestion·Title은 해당 editor의 명시적 펼침 envelope를 이어받되 자동 후보 내용은 1행 `NOWRAP`을 유지한다. 새 editor는 48dp로 시작하고 Android same-editor restart만 명시적 펼침을 보존한다. 폭 측정이나 타이핑 자체는 높이 변경 원인이 아니다. | `ToolbarLayoutPolicyTest`, `ToolbarHeightSessionTest`, 전체 68 suites·318 tests 실패 0, API 34 x86_64 emulator에서 IME frame `y=1405→1279`, 실제 1행→2행 6열→1행 후보 전환 중 `y=1279` 유지·접기 후 `y=1405`, OOM·GridLayout count 회귀 실동작 수정. Z Fold6 cover의 `0.1.2-141-g89b5f79e`에서도 touchable top `y=1566→1458`, 실제 2행 6열, `hellp` 1행 후보 중 `y=1458` 유지, 접기 뒤 `y=1566`을 확인했다. | 제품 완료 gate 없음. Z Fold6 unfolded 전환과 A35 최신 APK 확인은 제조사·posture 회귀 관찰로 별도 추적 |
| `AI-08` | AI 글쓰기·음성 받아쓰기·회의 전사의 미연결 또는 OAuth 만료 상태만 `설정하기`를 제공한다. 글쓰기 AI는 `SettingsRoute.PrivacyAi`로 이동한 뒤 `내 컴퓨터 자동으로 찾기 / OpenAI API 키 사용 / 고급 연결 설정` chooser를 정확히 한 번 바로 열고, STT 미연결 상태는 같은 route의 `OpenAI 음성 전사` 입력 dialog를 정확히 한 번 바로 연다. private editor, offline mode, app policy 차단은 credential 저장소를 열거나 CTA를 노출하지 않는다. 기본 휴대폰 받아쓰기에서는 OpenAI STT를 미연결 경고가 아닌 선택 사항으로 표시하고, OpenAI 모드를 실제 선택한 경우에만 별도 키를 요구한다. | 공통 gate 우선순위 테스트와 AI·voice JVM test, API 34 x86_64 emulator의 글쓰기 chooser·STT dialog 직행·선택 모드 summary·취소 후 휴대폰 모드 유지 PASS | A35 최신 APK 재확인 |
| `AI-10` | AI 직접 지시 strip처럼 `keyboardView` 위에 놓인 상호작용 surface는 그 최상단부터 IME의 content·visible·touchable inset으로 보고한다. 화면에 보이는 `실행`·`취소`가 뒤 editor의 전송·검색·navigation control로 관통하면 안 된다. API key 401은 provider 원문 오류나 재시도만 노출하지 않고 사용자용 설명과 `설정하기`를 제공한다. | `ImeTouchableTopPolicyTest`, API key 401 typed-state test, Pixel 7 API 34에서 WindowManager touchable region이 prompt 상단과 일치하고 `실행` 뒤 target activity 유지·`개인정보·AI` CTA 이동 PASS | 실제 공급자 결과 품질 matrix만 별도 유지 |
| `AI-11` | AI 글쓰기 첫 화면은 맞춤법·문장 3개·답장 3개·직접 지시와 화면 안에 고정된 `더보기`만 1행으로 표시한다. `더보기`를 명시적으로 누를 때만 말투와 번역 2행을 추가하고, 가짜 지시문 행을 두지 않는다. `직접 지시`는 원문이 비어 있어도 기존 Fcitx 키보드 입력으로 열리며, 미연결 상태는 비활성 기능 미리보기와 일반 사용자용 `설정하기`를 함께 표시한다. | `AiActionMenuPolicy`의 전체 14 action·중복 0·빈 원문 Custom 단독 활성 테스트, 전체 68 suites·323 tests 실패 0, API 34 x86_64에서 미연결 1행 preview·CTA, 고정 `더보기`·3행 펼침, 빈 Chrome editor의 Custom 단독 활성과 실제 Fcitx prompt keyboard 진입 PASS | 실제 공급자 결과 품질 matrix만 별도 유지 |
| `UX-04` | IME가 소유한 AI·STT 설정 Activity를 열 때 원 editor identity를 대상으로 software-keyboard resume를 1회 예약한다. 자체 설정 text field나 다른 editor가 이를 소비하면 안 되며, 복귀 전 transient AI·음성 surface와 확장 높이는 KeyboardWindow로 정리한다. 모든 입력 종료의 전역 virtual 강제는 물리 키보드 사용자를 깨뜨리므로 금지한다. | `VirtualKeyboardResumeGateTest`의 exact editor·one-shot·self-settings non-consume 3개, 전체 69 suites·326 tests 실패 0. API 34 x86_64에서 AI 설정→API key ADB physical text→Settings root→원 editor 복귀 후 실제 QWERTY와 inset 1342, 빈 editor Custom→Fcitx prompt→`qw`·Run 활성 PASS | A35에서 실제 Bluetooth/DeX 물리 키보드와 설정 왕복 시 floating candidates 정책을 보존하는지 확인 |
| `VOICE-07` | 빠른 받아쓰기 모드와 회의·메모 파일 전사 진입을 독립시킨다. `휴대폰 받아쓰기`가 선택돼 있어도 별도 STT profile 파일이 있고 privacy·network gate가 허용되면 회의 버튼을 표시한다. 표시 단계에서는 파일 존재만 확인하고, 사용자가 회의 창을 명시적으로 연 뒤에만 profile을 복호화한다. | mode를 입력받지 않는 `MeetingVoiceProfileResolver`, 차단 시 credential loader 0회, private/network/setup/ready와 회의 버튼 visibility 계약 테스트, 전체 68 suites·323 tests 실패 0 | 실제 OpenAI key·다화자 음원의 한국어 화자 분리 품질 |

실기기 캡처 전 `dumpsys input_method`의 `mCurId`가 debug Fcitx service인지 확인한다. 삼성
HoneyBoard가 활성인 화면을 이 앱의 숫자판이나 툴바 증거로 사용하지 않는다.

2026-07-26 checkpoint에서 A35 `SM-A356N`과 Z Fold6 `SM-F956N`에 동일 arm64 app/plugin을
설치하고 매 캡처 전 debug Fcitx `mCurId`를 확인했다. 두 cover 폭에서 툴바 12개 action이 6×2로
렌더됐고, 두 기기의 `?123`은 Number로 전환됐다. 두 기기 모두 AI 글쓰기에서 일반 사용자용 미연결
안내와 `설정하기` 버튼이 표시됐으며 버튼은 `개인정보·AI` route와 미연결 summary로 직접 이동했다.
Z Fold6를 펼친 뒤 내부 화면 `1856×2160`, override density 360에서 같은 toolbar가 12×1 한 줄로
자동 복귀하고 keyboard 본체와 `?123`이 함께 정상 렌더되는 것을 무선 ADB 캡처로 확인했다.

2026-07-27 높이 회귀 감사에서 compact toolbar 2행과 자동 후보 1행이 교대할 때 대상 앱의 editor
viewport가 매 입력마다 48dp씩 변하는 결함을 다시 재현했다. 첫 sticky-height 구현만으로는 Chrome이
같은 editor를 `restarting=true`로 재시작할 때 수동 toolbar 상태가 초기화되어 `y=1279 -> y=1405`,
즉 density 420에서 정확히 `126px = 48dp` 요동이 남았다. `restarting` 신호를 input broadcast에
전달하고 이전 boolean 자체가 아니라 사용자가 실제로 보던 펼침 상태를 보존하도록 수정했다. 새 editor는
자기 app profile 기본값을 사용한다. Pixel 7 API 34 emulator에서 수정 APK를 다시 설치한 뒤 Chrome의
수동 2행 toolbar를 열고 한글 입력·재포커스를 반복해 bar 시작 `y=1280`, keyboard 본체 시작
`y=1532`가 전후 동일함을 픽셀 측정했다. Candidate adapter는 96dp가 실제 확보된 때만 `WRAP`,
48dp session에서는 `NOWRAP`을 사용해 잘린 숨은 두 번째 행을 만들지 않는다. 전체 app JVM test,
arm64 assemble과 `git diff --check`가 통과했고 동일 arm64 APK를 Z Fold6에 설치했다.

위 자동 폭 전환은 같은 날 사용자 피드백으로 대체됐다. 현재 계약은 도구를 열어도 48dp 1행이 기본이고,
고정 펼침 control을 직접 눌러야만 96dp 2행 6열이 된다. 후보 내용은 항상 1행이며, 사용자가 2행을
명시적으로 연 세션에서만 빈 두 번째 envelope를 유지해 타이핑으로 editor가 움직이지 않게 한다.
API 34 x86_64 emulator의 실제 입력 화면에서 1행 `y=1405`, 2행 `y=1279`, 후보 전환 뒤에도
`y=1279`, 명시적 접기 뒤 `y=1405`를 확인했다. 최초 구현에서 발견된 lazy UI callback OOM과
GridLayout 열 수 축소 crash도 실동작 재현 뒤 제거했다.

2026-07-27 출근 이후 반복 검증 기준은 `Pixel_7_API_34` x86_64 emulator로 전환했다. 첫 toolbar
확장 때 descendant layout pass 안에서 ancestor 높이를 바꾸면 두 번째 행이 잘리는 문제를 다음 frame으로
높이 갱신을 미뤄 수정했고, 1080×1920에서 12개 action의 6×2 렌더를 다시 확인했다. 미연결 음성
받아쓰기의 `설정하기`는 일반 설정 목록에 멈추지 않고 STT 전용 key·model dialog를 바로 열며, dialog
소비 뒤 resume·회전으로 자동 재표시되지 않는다. 같은 emulator의 Google Messages draft에서 암호화
test profile을 잠시 저장한 뒤 `:주소1`과
Space를 입력해 `서울시 테스트로 1 `로 정확히 한 번 교체되는 것을 확인했고, draft와 test profile은
검증 직후 삭제했다. 같은 emulator에서 글쓰기 AI와 별도인 OpenAI STT test profile을 설정하고
`녹음 시작` -> Android 권한 dialog -> `녹음 중` 상태 전이를 확인했다. 공통 window title과 toolbar
접근성 명칭은 `음성 받아쓰기`로 통일하고, 내부 상태만 `휴대폰 받아쓰기` 또는 `정밀 받아쓰기`로
표시한다. test STT key는 앱의 제거 동작으로 삭제하고 기본 휴대폰 받아쓰기로 복구했다. 전체 JVM
62 suites·268 tests와 x86_64 debug build가 통과했다. 실제 microphone 품질과 Fold 자세 전환은
emulator가 대체할 수 없으므로 실기기 gate로 유지한다.

같은 emulator에서 글쓰기 AI Direct OpenAI test profile을 UI로 잠시 저장하고, 대상 editor 원문
preview -> 같은 Fcitx `KeyboardWindow`의 내부 prompt 입력 -> `실행` 경로를 다시 검증했다. 기존 구현은
prompt strip을 `keyboardView` 위에 그리면서도 `onComputeInsets()`에는 keyboard 상단만 보고해, strip의
버튼을 누르면 뒤 editor control로 touch가 관통할 수 있었다. 최상단 interactive IME surface를 inset
기준으로 바꾸고 WindowManager의 touchable region이 실제 prompt 상단과 일치함을 확인했다. dummy
API key의 401은 영문 provider 오류 대신 `글쓰기 AI가 이 API 키를 거부했어요`와 `설정하기`로
표시되며, CTA는 `개인정보·AI` route로 이동했다. test profile의 `provider.bin`은 검증 직후 앱의
연결 끊기로 삭제했다. 이 checkpoint는 app JVM 63 suites·271 tests, failure/error/skipped 0과 x86_64
debug build를 통과했다.

2026-07-27 Pixel 7 API 34 emulator에서 글쓰기 AI가 미연결인 상태를 유지한 채 음성 전용 dummy
STT profile만 UI로 저장해 두 자격 증명이 실제 설정 화면에서도 분리됨을 확인했다. `녹음 시작`은 최초
Android 권한 dialog 뒤 같은 Google Messages editor의 `녹음 중` 상태로 복귀했고, 중지 뒤 dummy key
401을 `STT API 키를 다시 연결`과 `설정하기`로 표시했다. 회의 파일 선택은 과거 window callback이
picker detach 때 취소되는 결함이 있어, editor-bound process-memory one-shot queue와 새
`MeetingTranscriptionWindow` 복원으로 교체했다. 1초 WAV를 Downloads에서 고른 뒤 phone과 tablet의 같은
message editor에 회의 window가 다시 붙고 실제 전사 요청 단계까지 도달했다. 스트리밍 upload 중 공급자가
인증 header만 보고 조기에 401을 반환하면 body write `IOException`이 먼저 노출되는 결함도 response code를
회수하도록 수정했다. tablet landscape에서 `OpenAI가 STT API 키를 거부`와 `설정하기`를 확인했으며,
picker one-shot·취소·editor mismatch·stale 요청·조기 HTTP 실패 회귀를 포함한 app JVM 65 suites·295 tests,
failure/error/skipped 0과 x86_64 debug build가 통과했다.

2026-07-28 수렴 감사에서는 13시간 이상 지속된 목표를 코드 완료와 외부 게이트로 다시 분리했다. 글쓰기
AI와 음성 STT는 타입, Keystore alias, `noBackupFilesDir` 저장 파일, 설정 진입점이 이미 분리돼 있었지만,
한 설정 화면에서 `휴대폰 받아쓰기 사용 중` 바로 아래에 `OpenAI 음성 전사 연결되지 않음`을 같은 비중으로
표시해 일반 사용자에게 OpenAI 키가 필수처럼 보이는 결함이 남아 있었다. 기본 모드에서는 이를
`선택 사항`으로 표시하고, OpenAI 실시간·정밀 전사를 실제 선택한 경우에만 STT 전용 key dialog를 연다.
선택 도중 취소하면 휴대폰 모드가 유지된다. 또한 회의 전사가 offline/app-network-blocked 상태에서도
STT 저장소를 먼저 읽던 누락을 공통 `VoiceProviderPolicy`로 막아 private·offline·기기 받아쓰기에서는
자격증명을 복호화하지 않는다. 전체 app JVM 68 suites·319 tests, failure/error/skipped 0, x86_64·arm64
debug assemble, Pixel 7 API 34 emulator의 설정 문구·모드 선택·dialog 취소·Google Messages 키보드 복귀를
통과했다. arm64 APK는 Z Fold6에 설치했지만 기기가 잠겨 이번 checkpoint의 화면 상호작용은 수행하지
못했다. A35는 ADB에 없고 실제 OpenAI STT key도 로컬 환경에 없으므로 두 항목은 실기기·자격증명 gate로
유지한다.

같은 날 후속 수렴에서 장시간 목표가 끝나지 않은 이유를 코드 결함과 외부 gate로 다시 나눴다. 기본
휴대폰 받아쓰기를 고른 상태가 별도 회의 전사의 STT profile까지 숨기던 결합을 제거했다. 음성 toolbar는
암호화 profile 파일의 존재와 privacy·network policy만으로 회의 버튼을 결정하며, 실제 Keystore 복호화는
명시적 회의 창 진입 뒤에만 수행한다. AI 글쓰기 첫 화면은 값도 보존하지 않던 40dp 가짜 지시문 행과
항상 보이던 3개 action 행을 제거했다. 기본 1행의 `더보기`는 가로 스크롤 밖에 고정해 좁은 화면에서도
보이게 하고, 직접 지시는 빈 editor에서도 기존 Fcitx prompt keyboard로 들어간다. 전체 app JVM
68 suites·323 tests, failure/error/skipped 0을 통과했다. emulator-5556은 설정 Activity 복귀 뒤
`mInputShown=true`인데 실제 IME surface가 0-height인 플랫폼 수명주기 상태가 남았지만, emulator-5554의
깨끗한 재설치에서 미연결 1행, 고정 `더보기`, 3행 펼침, 빈 Chrome editor의 Custom 단독 활성과 기존
Fcitx prompt keyboard 진입을 다시 확인했다. 두 emulator의 임시 AI test profile은
`no_backup/ai/provider.bin`에서 삭제했다.

같은 checkpoint의 재현을 더 좁힌 결과, 0-height는 플랫폼 창 자체가 아니라 내부 `InputView`가
물리 키보드 판정으로 `GONE`인 상태였다. 프레임워크에는 IME가 `shown=true`로 남지만 ADB 입력이나
실제 하드웨어 키가 `InputDevice` 후보창 모드를 활성화한 뒤 IME 소유 설정 Activity를 오가면, 원래
editor가 새 touch/tool-type callback 없이 복원되어 소프트웨어 surface가 계속 숨을 수 있었다. 모든
입력 종료에서 가상 키보드를 강제하면 블루투스 키보드 사용자를 깨뜨리므로 금지했다. 대신 설정 진입
시 원래 editor identity를 저장하고, 자체 설정의 API key field에서는 소비하지 않은 채 정확히 같은
editor로 돌아온 첫 `onStartInputView`에서만 software surface를 한 번 복구한다. 설정을 열기 전 AI·음성
임시 window와 확장 높이도 KeyboardWindow로 정리한다. API 34 emulator에서 OpenAI key dialog에
ADB physical text를 입력해 의도적으로 키보드를 접은 뒤 Settings root를 거쳐 원 editor로 돌아왔고,
QWERTY surface·`mInputShown=true`·`mIsInputViewShown=true`·`contentTopInsets=1342`를 함께 확인했다.
빈 editor의 `직접 지시`도 같은 Fcitx keyboard에서 `qw`를 입력해 prompt buffer와 활성 `Run`까지
확인했다. 시험 provider는 즉시 삭제했다. 전체 app JVM 69 suites·326 tests, failure/error/skipped 0과
x86_64·arm64 debug assemble가 통과했다.

2026-07-28 AI/STT hardening checkpoint에서는 코드 완료와 live 성공 gate를 다시 분리했다.
글쓰기 Responses transport는 성공·오류 body를 256 KiB로 제한하고 `refusal`, 출력 한도,
content filter, 불완전 응답을 서로 다른 정제 오류로 처리하며 한국어·영어 사용자 안내를 제공한다.
정밀 전사는 활성 `HttpURLConnection`을 세션 client가 소유해 취소 시 즉시 `disconnect()`하고, 취소가
요청 시작보다 먼저 도착한 경우에도 새 요청을 열지 않는다. Realtime은 ready 직후 recorder 등록 사이에
terminal socket 오류가 도착하는 race를 두 번째 failure checkpoint로 차단했다. AI와 STT credential
store는 운영에서 기존 `noBackupFilesDir`·Android Keystore alias·on-disk format을 유지하면서 파일 root와
cipher를 주입할 수 있게 분리했고, 원자 교체·`.bak` 복구·평문 미기록·손상 격리·독립 삭제를 테스트한다.

통합 app JVM은 70 suites·339 tests, failure/error/skipped 0이며 x86_64·arm64 debug assemble이 모두
통과했다. API 34 emulator에는 새 x86_64 APK를 설치해 AI profile 저장·복호화 summary·삭제와 STT 전용
profile 저장·정밀 모드 활성·삭제·휴대폰 받아쓰기 복귀를 UI로 확인했고, 두 `provider.bin` 시험 파일은
모두 제거했다. 같은 emulator에서 PC companion의 Tailscale MagicDNS 이름은 해석되지 않아 수동 HTTPS
연결도 `안전하게 확인하지 못했어`로 fail-closed 되었으며, dummy OpenAI 글쓰기 key는 401을 정제 안내와
`설정하기`로 복구하고 삭제했다. arm64 APK는 Z Fold6에 덮어 설치하고 debug Fcitx를 기본 IME로 확인했지만
기기가 잠겨 화면 상호작용은 수행하지 않았다. A35와 실제 OpenAI STT key는 연결 환경에 없으므로 실제
한국어 전사·preview·정확히 1회 입력과 마이크 품질은 외부 live gate로 유지한다.

## 7. GIF-01 상세 계약

### 7.1 핵심 UX

1. 키보드 toolbar의 GIF 버튼으로 `GifSearchWindow`를 연다.
2. 승인된 KLIPY key가 있으면 첫 화면에 KLIPY 인기 결과와 한국어 query 결과를 grid로 표시한다.
   key가 없는 공개 build에서는 Animated Noto Emoji를 fallback으로 사용한다.
3. 검색창은 IME가 자기 자신을 다시 호출하는 `AlertDialog/EditText`가 아니라 GIF surface 안의
   독립 한글·영문 인라인 자판을 사용한다. query는 대상 editor의 `InputConnection`에 쓰지 않는다.
4. thumbnail을 탭하면 해당 카드 자체 위에 반투명 selection overlay를 표시한다.
5. overlay에는 `링크 넣기`와 `GIF 첨부`를 동시에 표시한다.
6. 선택된 카드를 다시 탭하거나 바깥을 탭하면 overlay를 닫는다.
7. 다른 카드를 탭하면 overlay가 그 카드로 이동한다.
8. `링크 넣기`는 provider의 canonical page URL을 `commitText()`로 정확히 한 번 삽입한다.
9. `GIF 첨부`는 실제 animated `image/gif` 파일을 Android Commit Content API로 전달한다.
10. 성공 뒤 일반 keyboard로 돌아간다. 실패 뒤에는 현재 결과와 선택을 보존하고 retry를 제공한다.

버튼 표기는 `URL`/`이미지`보다 `링크 넣기`/`GIF 첨부`를 우선한다.

### 7.2 상태 모델

| 상태 | 표시 | 허용 동작 |
| --- | --- | --- |
| `Initial` | 검색창과 기본 결과 | 검색 편집 진입, 결과 선택, 닫기 |
| `EditingQuery` | 독립 두벌식·QWERTY 검색 자판 | 한/영, shift, 삭제, 지우기, 검색 |
| `Loading` | 진행 표시 | 취소 또는 query 교체 |
| `Results` | 결과 grid | 선택, 새 검색 |
| `Selected` | 카드 overlay와 attribution | 링크, 첨부, 선택 해제 |
| `Downloading` | 선택 카드 progress | 중복 action 차단 |
| `RetryableError` | 카드 또는 화면 오류 | 재시도, 선택 해제, 새 검색 |
| `Committed` | 짧은 성공 상태 | 일반 keyboard 복귀 |

stale search response가 최신 query 결과를 덮지 않도록 request generation을 비교한다.

### 7.3 링크 삽입 계약

- media CDN URL이 아니라 사람이 attribution을 확인할 수 있는 provider canonical page URL을 삽입한다.
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
| `width`, `height`, `byteSize` | download 및 layout 검증. metadata에 크기가 없으면 `0`이고 실제 응답을 제한한다. |
| `author` | 저작자 또는 제공자 표기 |
| `licenseName`, `licenseUrl` | 라이선스 표기와 상세 링크 |
| `attribution` | 카드와 상세 overlay에 표시할 문구 |
| `safe` | provider filter를 통과한 결과인지 여부 |

canonical page URL과 downloadable media URL을 혼동하지 않는다.

### 7.7 Animated Noto Emoji 공개 fallback 공급자

- Google의 [Animated Noto Emoji](https://googlefonts.github.io/noto-emoji-animation/) 공식 catalog를
  사용한다. 2026-07-26 라이브 확인 기준 881개 항목이며 media는 실제 `512.gif`다.
- catalog metadata만 한 번 내려받고 한국어 query와 영어 tag의 매칭은 전부 기기 안에서 수행한다.
  검색어 자체는 provider로 전송하지 않는다.
- 한국어 감정·반응어를 공식 영어 tag로 확장하고, 자주 쓰는 축하·웃음·박수·사랑·감사 결과를
  기본 grid에 우선 배치한다.
- grid thumbnail은 animated WebP를 `ImageDecoder`로 재생하고 Android 8 이하에서는 정적 첫 프레임으로
  안전하게 fallback한다. 첨부 원본은 별도 `image/gif` URL을 사용한다.
- author는 Google, license는 CC BY 4.0으로 카드에 항상 표시하며 canonical page와 media URL을 분리한다.
- catalog 장애 때도 동일한 공개 자산의 검증된 curated subset을 사용한다.
- 이 공급자는 움직이는 이모지용 공개 fallback이다. 영화·방송·밈·인물 반응을 포함하는 실사용
  GIF 검색의 기본 공급자로 간주하지 않는다.

### 7.8 Wikimedia Commons 보조 공급자와 공급자 평가

Commons provider와 license parser는 보존하지만 기본 반응 GIF 화면에서는 제외한다. Commons 검색은
공개 라이선스 출처로는 적합하지만, 한국어 반응 검색 결과가 비거나 정적 자료 중심이라 일상적인
키보드 반응 GIF의 기본 소스로는 품질이 부족했다. 향후 `공개 미디어` 별도 tab으로 제공한다.

| 후보 | 결정 | 근거 |
| --- | --- | --- |
| Animated Noto Emoji | 공개 fallback | key 불필요, CC BY 4.0이지만 리액션·밈 recall이 현저히 낮음 |
| Wikimedia Commons | 별도 tab backlog | license metadata는 우수하지만 반응 GIF recall·품질이 낮음 |
| Openverse | 기본 제외 | 라이브 GIF 검색이 사실상 Wikimedia 결과에 집중되고 한국어 recall 개선이 없음 |
| GifCities | 제외 | GeoCities archive 자산으로 현대 반응 품질과 개별 저작권 상태가 불명확 |
| KLIPY | `GIF-02` 우선 후보 | 1천만+ catalog, 한국어 검색·지역화, 키보드 사례가 있으나 API key·attribution·partner 승인이 필요 |
| GIPHY | optional provider `IN_PROGRESS` | 별도 provider·보안 key·branding·analytics 구현 완료, production/media-copy 승인 gate |

2026-07-26 공급자 재검토는 각 사업자의 1차 문서를 기준으로 했다.

- [KLIPY Developers](https://klipy.com/developers)는 test key를 시간당 100회로 제한하고,
  Partner Panel의 content filter·branding 적용 뒤 production access를 요청하도록 안내한다.
  [KLIPY API Overview](https://klipy.com/api-overview)는 지역화, contextual category, `Search KLIPY`
  placeholder와 Powered by KLIPY attribution을 권장한다.
- [GIPHY API Best Practices](https://developers.giphy.com/docs/api/)는 Powered by GIPHY, client-side
  request와 action analytics를 요구하고, 결과 재정렬·필터링·다른 공급자와 같은 grid 혼합 및 승인 없는
  media URL/asset cache를 금지한다. [Search endpoint](https://developers.giphy.com/docs/api/endpoint/)는
  사용자가 입력한 정확한 query를 보정·확장 없이 보내도록 명시한다.
- [Animated Noto Emoji](https://googlefonts.github.io/noto-emoji-animation/)는 공개 animation catalog로
  계속 사용하되, Noto Emoji의 자산별 라이선스 경계는
  [공식 Noto Emoji 저장소](https://github.com/googlefonts/noto-emoji)의 표시를 함께 확인한다.

따라서 API key·production 승인 없이 새 상용 공급자를 추가하지 않는다. GIPHY query는 확장하거나
재정렬하지 않고, 한국어 검색 품질 보강은 KLIPY와 기기 내 Noto 검색에만 적용한다.

Commons tab을 구현할 때는 기존 MediaWiki MIME·license allowlist·restriction 필터를 그대로 유지하고,
다른 provider 결과와 같은 grid에 섞지 않는다.

### 7.9 KLIPY 실사용 catalog 공급자

`KlipyGifProvider`는 `GIF-02`의 실사용 공급자로 구현됐다. 2026-07-26 라이브 probe와 A35 검색에서
`축하`, `웃겨`, `고마워` 한국어 query가 실제 GIF rendition과 밈·인물·캐릭터 반응 결과를 반환했다.

- API key는 Android Keystore AES-GCM으로 암호화하고 `noBackupFilesDir/gif/provider.bin`에만 저장한다.
  Gradle·BuildConfig·source·SharedPreferences·backup·log·clipboard에는 주입하거나 복제하지 않는다.
- key 저장은 사용자가 개인정보·AI 설정에서 명시적으로 수행한다. key가 없거나 복호화가 실패하면
  공개 Animated Noto Emoji fallback만 사용하고 KLIPY request를 만들지 않는다.
- `/gifs/trending`과 `/gifs/search` 결과를 별도 KLIPY provider model로 parse하고 공개 공급자 결과와
  같은 grid response 또는 disk directory에 혼합하지 않는다.
- canonical `klipy.com/gifs/{slug}`와 downloadable GIF rendition을 분리한다.
- 모든 카드에 `Powered by KLIPY`와 KLIPY API Terms attribution을 표시한다.
- query는 UTF-8로 encode하고 `locale=ko`를 지정하며, API 응답·media·canonical URL 모두 HTTPS만 허용한다.
- test key는 개발 검증에만 사용한다. 배포 전 production key, attribution 검수, partner 조건 승인을
  `GIF-02-PROD` owner gate로 처리한다.

GIPHY는 충분한 catalog를 제공하지만 KLIPY 결과와 혼합하지 않는 명시적 선택형 optional provider로만
유지한다. production review가 확인되지 않은 key에서는 요청을 만들지 않는다.

KLIPY 결과는 단일 첫 page로 끝내지 않는다. provider page·`has_next`를 모델에 보존하고 grid 끝에서
다음 page를 요청하며 media identity로 중복 제거한다. 새 query generation이 시작되면 이전 page 응답은
버린다. 인기·밈·퇴근·월요일·웃겨·어색·동의·놀람·축하·화이팅·사랑·감사·화남·슬픔 chip을 제공하되,
chip과 직접 입력 query는 같은 safe-search gate를 거친다. 명시적 성인·폭력 query와 metadata는
클라이언트에서도 제외하고, partner filter를 최종 source of truth로 유지한다. Noto는 UI에서
`작은 공개 fallback`이라고 표시해 리액션 catalog처럼 오인시키지 않는다.

한국어 밈 검색 품질 계약은 다음과 같다.

1. KLIPY에는 사용자가 입력한 query를 먼저 그대로 보낸다. 성공 응답의 첫 page가 비었을 때만
   `ㅋㅋ`·퇴근·월요일·머쓱·인정·놀람·축하·응원·감사·사과·사랑·분노·슬픔 등 검증된 한국어
   반응 intent의 fallback query를 최대 2개 순차 시도한다.
2. 원 query 결과와 fallback query 결과를 합치거나 재정렬하지 않는다. fallback이 성공하면 해당
   단일 page만 표시해 다음 page에서 query identity가 바뀌는 문제를 막는다.
3. KLIPY 결과는 공급자 순서를 유지하면서 동일 slug ID 또는 동일 media URL만 제거한다. grid의 다음
   page도 동일 provider만 허용한다.
4. Noto 검색은 query와 catalog tag가 모두 기기 안에 있으므로 한국어 채팅 표현을 공식 영어 tag로
   확장해 점수화할 수 있다. 직접 tag match, prefix, 포함, popularity 순으로 결정하고 피부색 변형은
   한 emoji family에서 하나만 남겨 첫 화면의 반응 종류를 늘린다.
5. GIPHY에는 이 planner를 절대 적용하지 않는다. exact query, provider order, duplicate까지 응답 그대로
   유지하되 다른 provider ID가 같은 grid로 들어오는 것만 fail-closed로 차단한다.
6. 모든 fallback query도 기존 safe-search를 다시 통과하며, 원 요청의 network error를 다른 query로
   숨기지 않고 사용자에게 retry 가능한 오류로 표시한다.

GIPHY optional provider는 위 계약을 코드로 구현했다. 별도 Keystore/no-backup credential,
명시적 provider 선택, `Powered by GIPHY`, `rating=g`, `lang=ko`, `country_code=KR`, pagination과
공식 load/click/sent analytics URL을 사용한다. key와 사용자가 확인한 production review가 모두 없으면
`GiphyUnavailable`로 network를 0회 유지하고 KLIPY로 자동 fallback하지 않는다. 별도 media-copy 서면
승인이 없으면 link만 허용하고 원본 download·disk cache·GIF 첨부는 비활성화한다. KLIPY/Commons/Noto와
같은 grid response나 cache namespace에는 혼합하지 않는다.

[Google Tenor FAQ](https://support.google.com/tenor/answer/10455265?hl=en)가 명시한 대로 Tenor API는
2026-06-30 종료됐으므로 새로운 기본 공급자로 사용하지 않는다. 공급자 상태는 구현 시점에 공식 문서로
다시 검증한다.

### 7.10 cache와 cleanup

- thumbnail memory cache와 original GIF disk cache를 분리한다.
- original GIF는 app `cacheDir/gif-share/{providerId}` 아래에만 저장한다.
- partial download는 별도 확장자로 쓰고 검증 뒤 atomic rename한다.
- MIME, GIF87a/GIF89a signature, byte limit를 모두 통과해야 공유한다.
- MVP original 최대 크기는 20 MiB다.
- 전송 직후 삭제하지 않고 24시간 TTL을 적용한다.
- window 시작, 새 download 시작, 앱 시작 중 안전한 지점에서 expired file을 정리한다.
- cache file과 query는 사용자 ZIP export와 Android backup 대상이 아니다.

### 7.11 접근성·오류·표시

- thumbnail, author, license, 두 action에 `contentDescription`을 제공한다.
- loading, disabled attach 이유, retryable error를 색상만으로 표현하지 않는다.
- network timeout, empty result, metadata exclusion, thumbnail failure, original download failure,
  content rejection을 서로 구분한다.
- attribution은 thumbnail loading 실패와 관계없이 텍스트로 접근 가능해야 한다.

### 7.12 완료 게이트

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

### 7.13 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| GIF unit/state test | `PASS` | KLIPY parser 7개와 기존 GIF 19개, failure/error 0. 한국어 query·canonical/media 분리·HTTPS·key 비노출·MIME/private gate 포함 |
| app 전체 JVM test | `PASS` | 31 suites, 112개, failure/error/skipped 0 |
| arm64 app/plugin build | `PASS` | `:app:assembleDebug`, `:plugin:hangul:assembleDebug` |
| A35 설치 | `PASS` | `SM-A356N / RFCX60GBL3D`, AI·KLIPY 통합 app과 Hangul plugin 최종 APK 재설치, debug Fcitx IME 재선택 |
| 기본 source live probe | `PASS` | catalog 881개, party WebP·GIF 모두 HTTP 200, GIF MIME과 957,983-byte 응답 확인 |
| 검색 입력·preview | `PASS` | A35에서 검색창 탭 즉시 독립 자판 표시, `cnrgk→축하`, 결과 grid 표시. 0.7초 간격 화면의 GIF 영역 256,605 pixel 변화 확인 |
| x86_64 emulator 검색 UI | `PASS` | Pixel 7 API 34에서 toolbar GIF 진입, 검색창 탭 즉시 인라인 한글 자판 표시, `웃음` 검색 결과와 선택 카드의 `링크 넣기`·`GIF 첨부` overlay 확인 |
| 일반 text editor | `PASS` | Chrome은 `contentMimeTypes`에 GIF 미지원; 첨부 disabled 설명 표시, Noto canonical URL을 focused editor에 정확히 1회 입력 |
| GIF 지원 editor | `PASS` | Discord `contentMimeTypes=[image/*]`; 1,176,505-byte animated GIF를 `commitContent`로 전달해 전송 전 compose preview가 실제 표시됨 |
| x86_64 emulator commit path | `PASS` | Google Messages가 `image/gif` content URI와 canonical link를 `SOURCE_INPUT_METHOD`로 수신하고 1,176,505-byte 원본을 조회함. 해당 SMS conversation의 후속 attachment 제약 거부 뒤 link 자동 삽입은 0회였고 draft는 비움 |
| attach 실패 fallback | `PASS` | attach 경로에는 URL commit이 없고 실패는 상태 표시만 수행 |
| private editor network | `PASS` | `GifSearchGateTest.privateEditorMakesZeroProviderRequests`가 provider 호출 0을 검증 |
| Z Fold6 설치 | `PASS` | `SM-F956N`, wireless ADB로 동일 app과 Hangul plugin 재설치, Fcitx 입력기 서비스 등록 및 기본 입력기 전환 확인 |
| KLIPY 한국어 검색 | `PASS` | A35에서 `축하` 검색 결과가 캐릭터·밈 GIF grid로 표시되고 카드 overlay가 선택 카드 위로 이동 |
| KLIPY link exactly-once | `PASS` | Chrome URL editor의 실제 text에서 canonical `https://klipy.com/gifs/...` occurrence가 정확히 1개 |
| KLIPY 미지원 editor | `PASS` | Chrome overlay에서 `GIF 첨부` disabled와 `이 앱은 GIF 첨부를 지원하지 않음`을 동시에 표시 |
| KLIPY Fold UI | `PASS` | Z Fold6 cover 화면에서 toolbar 진입, 2열 인기 GIF grid와 attribution 렌더 확인 |
| 개인 KLIPY key vault | `PASS` | Keystore AES-GCM·백업 제외 파일·atomic replace/recovery와 Missing/Configured/Unreadable 상태를 8개 단위 테스트로 검증 |
| key 기반 provider 전환 | `PASS` | A35·Z Fold6 설정 화면에서 개인 key 저장 후 `Powered by KLIPY` 상태 확인, A35 keyboard에서 풍부한 trending grid 재확인 |
| KLIPY production 승인 | `GATE` | source에 key를 넣지 않음. 공개 배포 전 전용 production key·partner 약관 승인 필요 |
| pagination·밈 chip·safe gate | `PASS` | page/has-next, stale generation, media dedupe, next-page retry, 한국어 quick chip, query·metadata 차단을 단위 테스트로 검증 |
| GIPHY optional provider | `PASS/GATE` | key·승인 부재 network 0, provider 비혼합, g rating, ko/KR, pagination, attribution, canonical/media, analytics tests 통과. 실제 production review와 media-copy 승인은 외부 gate |

`GIF-01`의 코드, 자동 테스트, A35 기능 검증, A35·Z Fold6 설치 게이트가 모두 통과했다. 2026-07-26
사용자 피드백으로 기존 `AlertDialog/EditText` 검색과 Commons 기본 결과의 실사용 실패를 재현한 뒤
`b7e7c394`에서 인라인 검색 자판과 Animated Noto Emoji 기본 provider로 교체했다. Discord 검증 중에는
compose preview까지만 확인했고 메시지는 전송하지 않았으며, 검증 뒤 draft attachment를 제거했다.

## 8. AI·음성 아키텍처 계약

### 8.1 provider 경계

- 글쓰기 `AiProviderProfile`은 Responses API만 소유한다. PC Codex/Claude OAuth companion 또는
  OpenAI-compatible 글쓰기 endpoint가 음성 전사를 암묵적으로 제공한다고 추정하지 않는다.
- 음성은 별도 `VoiceProviderProfile`과 모드(`DeviceDictation`, `OpenAiRealtime`, `OpenAiApi`)를 사용한다. 기본값은
  네트워크와 API key가 필요 없는 `DeviceDictation`이다.
- `OpenAiRealtime`은 고급 사용자가 명시적으로 선택한 개인 BYOK 경로다. 공식
  `wss://api.openai.com/v1/realtime?model=gpt-realtime-whisper`만 허용하며 24 kHz mono PCM을 stream한다.
- `OpenAiApi` 음성 모드는 공식 `https://api.openai.com/v1/audio/transcriptions`만 허용하며,
  `gpt-4o-transcribe`와 `gpt-4o-mini-transcribe` 중 하나를 명시적으로 선택한다.
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

2026-07-27 `VOICE-02` 구현은 글쓰기 AI transport와 분리된 STT 전용 profile을 사용하며
`gpt-4o-transcribe` `/audio/transcriptions` 구간 전사로 제한한다. UI 명칭은 `AI 정밀 받아쓰기`이며
실시간·부분 전사라고 표시하지 않는다. 16 kHz mono PCM은 UI countdown 없이 최대 5분의 안전 상한으로
메모리에 보관해 WAV로 만들고,
전사 요청 종료·취소·window detach 시 byte array를 지운다. 파일·cache·SharedPreferences·backup·log에
음성이나 전사문을 남기지 않는다. `RECORD_AUDIO`는 IME service가 직접 요청하지 않고 `exported=false`
투명 Activity에서만 요청한다. 현재 IME는 Android background activity start 예외에 해당하지만 실기기
permission dialog와 focus 복귀는 별도 gate다.

2026-07-27 `VOICE-01`은 OpenAI Realtime transcription session에서 24 kHz mono PCM,
`gpt-realtime-whisper`, `language: ko`, `input_audio_buffer.append/commit`, transcript delta/completed의
`item_id`별 조정을 구현했다. partial은 window preview에만 쓰고 completed transcript만 기존 final preview와
editor identity·exactly-once commit gate로 넘긴다. Android 앱의 현재 개인 BYOK 경로는 기존 STT 전용
Keystore credential을 OkHttp WebSocket에 명시적으로 사용한다. 일반 배포 기본 구조는 모바일 표준 key 대신
backend가 발급한 짧은 수명의 token과 WebRTC transport를 사용해야 하며 이 production hardening은 별도 gate다.
연결·최종 전사 timeout은 `CancellationException`으로 사라지지 않고 사용자에게 보이는 전사 오류로 변환한다.
세션 준비 뒤 공급자 오류나 socket close가 오면 최초 오류만 보존하고 `AudioRecord`를 즉시 한 번 중단하여
사용자가 중지 버튼을 다시 눌러야 오류가 드러나는 정지 상태를 만들지 않는다. offline·차단 editor는 선택 모드를
먼저 판정해 STT credential을 복호화하지 않는다.
공식 기준은 [Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription),
[Realtime WebSocket](https://developers.openai.com/api/docs/guides/realtime-websocket),
[Speech to text](https://developers.openai.com/api/docs/guides/speech-to-text)다.

### 8.2 API key와 token

- 일반 배포 기본 경로는 backend가 standard provider key를 보관한다.
- 일반 배포 Realtime client에는 backend가 발급한 짧은 수명의 token을 사용한다. 개인 고급 BYOK는
  사용자의 명시적 선택일 때만 STT 전용 Keystore key를 직접 사용하고 추출 위험을 숨기지 않는다.
- 개인용 advanced BYOK는 workload별로 분리한다. 글쓰기 key는 `noBackupFilesDir/ai/provider.bin`,
  STT key는 별도 Keystore alias와 `noBackupFilesDir/voice/provider.bin`에 저장한다.
- BYOK standard key는 추출 위험이 0이라고 표시하지 않는다.
- key와 token을 `AppPrefs`, 일반 SharedPreferences, user-data ZIP, log, crash report에 넣지 않는다.
- 기능별 사용량, 실패 유형, provider만 표시하며 prompt와 결과 원문은 기본 저장하지 않는다.

#### 8.2.1 API key와 endpoint OAuth는 상호 배타적이다

- `API key 직접 입력`은 기존 BYOK 경로이며 요청마다 그 key 하나만 `Authorization: Bearer`로 보낸다.
- `OAuth 2.0 로그인 (PKCE)`은 사용자가 운영하거나 신뢰하는 OpenAI-compatible endpoint가 제공하는
  OAuth/OIDC Authorization Code flow다. OpenAI standard API가 일반 모바일 사용자 OAuth를 제공한다고
  가정하지 않는다.
- Android 앱은 public client다. client secret 입력란·저장 필드·token request 인증을 만들지 않는다.
  인가는 AppAuth 외부 browser/Custom Tab에서 수행하고 WebView를 쓰지 않는다.
- authorization request는 무작위 `state`와 PKCE verifier를 만들고 `S256` 외 method면 시작 전에
  fail-closed한다. callback은 요청 state와 정확히 일치해야 token exchange를 수행한다.
- redirect URI는 `${applicationId}.oauth:/callback` 단일 규칙이며 release는
  `org.fcitx.fcitx5.android.oauth:/callback`, debug는
  `org.fcitx.fcitx5.android.debug.oauth:/callback`이다. endpoint 등록값과 정확히 일치해야 하며 open
  redirect나 동적 redirect 입력은 지원하지 않는다.
- browser 왕복과 token 교환 callback 시마다 현재 profile의 client ID, authorization endpoint,
  token endpoint, redirect URI를 원래 request와 정확히 비교한다. 설정이 중간에 바뀌면 이전 응답을
  새 profile token으로 저장하지 않고 fail-closed한다.
- access/refresh token과 AppAuth state는 `noBackupFilesDir/ai/oauth-session.bin`에 Android Keystore
  AES-GCM으로 암호화한다. profile fingerprint가 달라지면 token을 재사용하지 않는다.
- AI text·정확 전사·회의 전사는 같은 Bearer resolver를 쓴다. 만료 전 refresh를 한 번 수행하며,
  refresh 실패나 resource server의 `401`은 token을 폐기하고 명시적 재로그인 오류로 끝낸다.
  원 요청 자동 재시도, API key fallback, OAuth/API-key header 혼합은 금지한다.
- 로그아웃은 revocation endpoint가 설정돼 있으면 refresh token 우선으로 폐기를 요청한다. 원격 응답과
  관계없이 local token은 반드시 삭제하고, 원격 폐기 미확인은 사용자에게 구분해 알린다.
- OAuth→API key 전환이나 OAuth profile 교체도 이전 profile의 revocation을 먼저 시도하고 local OAuth
  state는 반드시 삭제한 다음 새 profile을 저장한다. 오래된 refresh token을 새 provider에 남기지 않는다.
- password, sensitive, private/no-personalized-learning editor와 offline/app별 차단 상태에서는 기존
  privacy gate가 token refresh를 포함한 모든 AI network call보다 먼저 실행된다.

#### 8.2.2 HTTPS·Tailscale 경계

- API base, authorization, token, revocation endpoint는 모두 `https://`만 허용한다. public host뿐 아니라
  loopback, RFC1918, CGNAT/Tailscale IP, MagicDNS, `.ts.net`에도 평문 HTTP 예외를 두지 않는다.
- Tailscale과 MagicDNS는 Android에서 원격 컴퓨터까지 가는 network path다. Tailscale Serve를 쓰면
  tailnet 내부 HTTPS와 ACL, backend용 identity header를 구성할 수 있지만, 이 앱의 endpoint OAuth
  Bearer 발급과 같은 인증 계층으로 간주하거나 자동 혼합하지 않는다.
- Tailscale OAuth Apps는 Tailscale API/internal tool 권한용 별도 기능이며 2026-06-30 현재 alpha다.
  OpenAI-compatible endpoint 사용자 로그인으로 사용하지 않고 Tailscale OAuth client secret을 Android에
  넣지 않는다.
- 공식 기준:
  [AppAuth Android](https://github.com/openid/AppAuth-Android),
  [RFC 8252 Native Apps](https://www.rfc-editor.org/rfc/rfc8252),
  [RFC 9700 OAuth Security BCP](https://www.rfc-editor.org/rfc/rfc9700),
  [OpenAI production key security](https://developers.openai.com/api/docs/guides/production-best-practices),
  [Tailscale MagicDNS](https://tailscale.com/docs/features/magicdns),
  [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve),
  [Tailscale OAuth Apps](https://tailscale.com/docs/features/oauth-apps).

#### 8.2.3 `AI-09` 컴퓨터 자동 발견·연결 마법사

일반 사용자의 기본 진입은 OAuth endpoint 직접 입력이 아니다. `AI 공급자 > 내 컴퓨터 자동으로 찾기
(추천)`에서 다음 순서를 한 화면으로 수행한다.

1. 같은 local network에서 DNS-SD/mDNS service type `_fcitx-ai._tcp.`를 검색한다.
2. service TXT의 `manifest` 값만 읽는다. 이 값은 반드시
   `https://<trusted-host>/.well-known/fcitx-ai-provider`여야 한다.
3. mDNS 이름·IP·TXT 자체는 신뢰하지 않는다. Android system trust store로 HTTPS certificate를 검증하고,
   redirect를 따르지 않으며, 최대 128 KiB JSON만 읽는다.
4. manifest version, provider ID, Responses capability, model mapping, OAuth endpoint, public client ID,
   scope와 현재 build의 고정 redirect URI를 검증한다. `api_key`, `client_secret`, access/refresh token이
   어느 depth에든 있으면 전체 manifest를 거부한다.
5. 사용자에게 컴퓨터 이름, AI service 이름, certificate host를 한 번 보여 주고 명시적으로 `연결하기`를
   누르게 한다. 이후 검증된 profile을 암호화 저장하고 기존 AppAuth PKCE browser login을 자동 시작한다.
6. OAuth login 실패 시 API key fallback이나 이전 credential 자동 재사용을 하지 않는다. 사용자가 같은
   컴퓨터를 다시 선택해 재시도한다.

manifest v1 contract는 다음과 같다. `redirect_uri`는 설치 variant에 따라 release
`org.fcitx.fcitx5.android.oauth:/callback` 또는 debug
`org.fcitx.fcitx5.android.debug.oauth:/callback`을 endpoint가 사전에 public-client redirect로 등록한
값과 정확히 맞춰 제공해야 한다.

```json
{
  "protocol_version": 1,
  "provider_id": "home-ai",
  "display_name": "Home AI",
  "base_url": "https://computer.example/v1",
  "oauth": {
    "authorization_endpoint": "https://computer.example/oauth/authorize",
    "token_endpoint": "https://computer.example/oauth/token",
    "revocation_endpoint": "https://computer.example/oauth/revoke",
    "client_id": "fcitx-android-public",
    "scopes": ["openid", "offline_access", "ai.invoke"],
    "redirect_uri": "org.fcitx.fcitx5.android.oauth:/callback"
  },
  "models": {
    "fast": "fast-model",
    "balanced": "balanced-model",
    "quality": "quality-model"
  },
  "capabilities": ["responses"]
}
```

`transcription`은 provider가 실제 `/audio/transcriptions` 호환 endpoint를 구현한 경우에만 배열에
추가한다. 현재 Codex·Claude CLI companion은 `responses`만 선언한다.

컴퓨터 쪽 기본 경로는 `scripts/ai-provider-companion.py`가 제공하는 로컬 CLI gateway다. 사용자가 PC에서
이미 로그인한 Codex(ChatGPT 구독 OAuth)와 Claude Code(Claude 구독 OAuth)를 그대로 사용하며, Android에
OpenAI·Anthropic API key나 CLI의 `auth.json`, OAuth access token을 복사하지 않는다. 휴대폰에는 companion
전용 Authorization Code + PKCE 권한만 발급한다. 이 companion grant는
`%LOCALAPPDATA%/Fcitx5Android/ai-companion-oauth.bin`에 Windows DPAPI current-user 범위로 암호화해
보존하므로 PC 재시작 뒤에도 다시 로그인하지 않고 refresh할 수 있다.

요청 실행 경계는 다음으로 고정한다.

- Codex: `codex exec --ephemeral --sandbox read-only --skip-git-repo-check --ignore-user-config
  --ignore-rules -c approval_policy=never -c web_search=disabled --color never -C <empty-sandbox> -`
- Claude Code: `claude -p --safe-mode --tools '' --permission-mode dontAsk --no-session-persistence
  --output-format json`
- 자식 process에서는 API key·token override 환경 변수를 제거해 CLI에 저장된 구독 OAuth 로그인을 강제한다.
- 한 번에 한 요청만 실행하고 prompt·결과·Bearer token을 log에 남기지 않으며, strict suggestion JSON 외
  출력은 거부한다. Fast·Quality는 Codex, Balanced는 Claude로 route한다.

gateway는 loopback `127.0.0.1:9211`, tailnet 공개면은 Tailscale Serve HTTPS `:9210`, 발견은
`_fcitx-ai._tcp.local.`을 사용한다. WPF tray `tools/FcitxAiCompanionTray`가 상태·backend·시작·중지·재시작·
local health 열기를 제공하고 helper process를 감시해 장애 시 복구한다.
`scripts/install-ai-provider-companion-tray.ps1`은 single-file tray를 `%LOCALAPPDATA%/Fcitx5Android/tray`에
배포하고 현재 사용자 logon 예약 작업 `Fcitx5 Android AI Companion`을 설치한다. 기존 독립 OAuth
provider를 광고할 때만 `--manifest-url`의 advertise-only 호환 모드를 사용한다.

Android emulator처럼 Tailscale private address나 MagicDNS에 직접 들어갈 수 없는 검증 환경은 별도로
관리하는 HTTPS reverse proxy origin을 `--public-origin https://host[:port]`로 지정할 수 있다. 이 값은
HTTPS origin만 허용하고 credential, path, query, fragment와 잘못된 port를 거부한다. companion은 이
origin으로 manifest·OAuth·Responses URL을 만들고 Tailscale Serve 설정은 건드리지 않는다. reverse
proxy가 loopback gateway로 연결되는지와 외부 노출 수명·access policy는 운영자가 별도로 관리한다.
`--manifest-url` advertise-only mode와 `--public-origin`은 동시에 사용하지 않는다. 임시 public tunnel은
emulator 검증용일 뿐 tray의 Tailscale 기본 경로를 대체하는 production 기본값이 아니다.

새 HTTPS tunnel은 URL 발급 직후 route 전파가 끝나기 전까지 일시적으로 404·502를 반환할 수 있다.
`--public-origin` startup 검증은 network 실패와 HTTP 404/408/425/429/500/502/503/504만 0.5초부터
최대 3초 backoff로 7회까지 제한 재시도한다. protocol version, capability, OAuth field, model mapping처럼
manifest 자체가 잘못된 경우에는 재시도하지 않고 즉시 fail-closed한다. Quick Tunnel 검증 harness는 사용자의
기존 named-tunnel ingress 설정을 상속하지 않는 빈 cloudflared config를 사용해야 한다.

현재 targetSdk 36에서는 기존 local-network NSD 경로를 쓴다. targetSdk 37 전환 시 Android local
network protection을 별도 milestone로 올리고, broad `ACCESS_LOCAL_NETWORK` 요청보다 system service
picker의 scoped grant를 먼저 적용한다. 사용자가 직접 입력하는 fallback도 OAuth 필드가 아니라 동일한
HTTPS computer origin 하나만 받는다.

### 8.3 text 읽기와 교체

- IME가 임의의 chat bubble이나 화면 전체를 읽을 수 있다고 가정하지 않는다.
- `InputConnection.getSelectedText()`와 surrounding text는 null 또는 stale일 수 있다.
- 상대 메시지 기반 답장은 사용자가 선택, 복사 또는 share한 텍스트만 사용한다.
- AI 결과는 원문, diff, 후보를 보여주고 `교체`, `뒤에 넣기`, `복사`, `취소`를 구분한다.
- 교체 뒤 최소 한 번의 undo 경로를 제공한다.

`AI-04` 답장 intake는 IME가 다른 앱 화면을 임의로 읽는 방식으로 만들지 않는다. 사용자가 Android
Sharesheet로 `text/plain`을 명시적으로 공유하거나 clipboard 목록의 특정 행을 직접 누른 경우만 받는다.
공유 원문은 process memory에 최대 4,000자·5분만 유지하고 prefs, file, backup, log에 남기지 않는다.
private/offline/app별 AI 차단과 editor identity가 하나라도 맞지 않으면 preview·network·commit을 모두
차단한다. 결과 입력 뒤에는 같은 action을 재사용해 중복 commit할 수 없다.

### 8.4 음성 privacy와 정확한 제품 명칭

toolbar entry와 window title은 세 mode를 포괄하는 `음성 받아쓰기`다. 기본 mode의 내부 title은
`휴대폰 받아쓰기`, OpenAI `VOICE-01`은 `실시간 받아쓰기`, `VOICE-02`는 `정밀 받아쓰기`다.
UI는 30초를 권장하거나 countdown하지 않고 사용자가 멈출 때까지 elapsed time만 보여 준다. Realtime은
24 kHz mono PCM chunk를 보내 partial을 미리 보여 주고, 구간 전사는 16 kHz mono PCM과 5분의 내부
memory safety boundary를 사용해 WAV multipart로 보낸다. 종료 뒤 모든 audio byte array를 지운다.
음성·전사문을 file, cache, prefs, backup, log에 저장하지 않는다. `RECORD_AUDIO` 권한은
`exported=false` 투명 permission Activity에서만 요청한다. private/no-personalized/offline/app AI 차단,
editor identity 변경, 취소에서는 전송 또는 입력을 fail-closed한다. provider resolver는 editor의 text
inspection 허용 여부를 필수 입력으로 받고, private editor에서는 STT credential loader 자체를 호출하지
않는다.

음성 모드는 글쓰기 provider capability와 분리한다. 기본 `휴대폰 받아쓰기`는 활성 system voice IME로
즉시 전환하고, `OpenAI 음성 전사`를 사용자가 고른 경우에만 별도 STT key와 모델을 요구한다. 음성 toolbar
활성 상태도 글쓰기 AI 연결 여부가 아니라 현재 음성 모드와 editor privacy/network policy만 따른다.

최초 `RECORD_AUDIO` 권한 dialog는 IME를 숨기고 다시 시작할 수 있다. 따라서 기존 window callback에서
바로 `AudioRecord`를 시작하지 않는다. 요청 시 package/field/input type을 process memory에 묶고,
권한 결과 뒤 같은 editor가 다시 활성화된 경우에만 음성 window를 한 번 복구해 녹음을 시작한다. 다른
editor, stale request, process death에서는 결과를 폐기한다. 2026-07-27 A35에서 권한을 철회한 상태로
`녹음 시작` 1회 탭 -> 권한 승인 -> 같은 Chrome editor 복귀 -> `녹음 중` 표시와 audio HAL capture를
확인했다.

현재 Codex CLI 0.145.0의 `codex exec`는 text와
`--image`만 입력으로 받고 audio option이 없다. ChatGPT desktop의 Codex Voice는 공식 UI 기능이지만
Android companion이 audio를 보내 transcript를 돌려받을 공개 자동화 계약은 없다. 따라서 구독 OAuth
token을 추출해 비공식 endpoint를 호출하거나 표준 API 사용량으로 위장하지 않는다. 공개 bridge가 생기기
전까지 PC CLI companion은 `responses`만 제공하고 `VOICE-04`는 `BLOCK`이다.

공식 기준: [ChatGPT Work and Codex Voice](https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex),
[Voice Dictation FAQ](https://help.openai.com/en/articles/12168547-voice-dictation-faq),
[GPT-4o Transcribe](https://developers.openai.com/api/docs/models/gpt-4o-transcribe).

`VOICE-01` realtime delta는 `gpt-realtime-whisper` WebSocket과 item별 delta/completed 조정까지
구현됐다. 표준 key를 APK에 내장하지 않으며, 사용자가 직접 저장한 STT 전용 BYOK만 advanced mode에서
사용한다. 일반 배포의 backend ephemeral token·WebRTC 전환은 production hardening gate로 유지한다.

`VOICE-03`은 Realtime과 분리된 명시적 파일 작업이다. Android system document picker에서 사용자가
고른 `content://` audio만 허용하고, FLAC·MP3/MP4/M4A·MPEG/MPGA·OGG·WAV·WebM 중 metadata와
확장자를 교차 검증한다. 선언 size가 없더라도 stream 도중 24MiB를 넘으면 즉시 중단하며 최대 duration은
60분이다. 요청은 `gpt-4o-transcribe-diarize`, `response_format=diarized_json`,
`chunking_strategy=auto`, `language=ko`로 제한한다. 표준 OpenAI profile이 아니면 capability를 추정하지
않고 fail-closed한다. 결과는 화자·시작/종료 timestamp를 preview하고 사용자가 체크한 segment만
exactly-once로 입력한다. 자동 요약은 이 경로에 포함하지 않는다.

### 8.5 AI text MVP 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 공급자 profile | `PASS` | OpenAI·OpenAI-compatible HTTPS endpoint, Fast/Balanced/Quality model tier를 pure model로 검증; 평문 HTTP는 loopback·private·Tailscale도 차단 |
| API key vault | `PASS` | Android Keystore AES-GCM과 `noBackupFilesDir/ai/provider.bin`; SharedPreferences·user ZIP·log에 key를 저장하지 않음 |
| OAuth public client | `PASS` | AppAuth external browser, Authorization Code, state, PKCE S256, 고정 redirect, client secret 없음, 암호화 AuthState·refresh·revoke 구현; AppCompat dialog theme와 Android 11+ browser query 회귀 수정 |
| OAuth request contract | `PASS` | API key/OAuth 혼합·HTTP endpoint 거부, `.ts.net` HTTPS profile, callback profile 불일치 차단, applicationId redirect, Bearer 1회 사용, 401 무재시도·명시적 재로그인 unit test |
| Android 통합 build/test | `PASS` | 2026-07-28 최종 `:app:testDebugUnitTest` 68 suites·319 tests failure/error/skipped 0, x86_64·arm64 debug assemble, debug merged manifest redirect scheme 일치 |
| OAuth live provider | `PASS` | `alpaca-home` CLI companion을 A35·Z Fold6가 각각 발견하고 외부 browser 승인·PKCE token 교환·암호화 session 저장 통과. Pixel 7·QA tablet API 34 emulator도 임시 HTTPS `--public-origin`의 manifest 확인·browser 승인·token 교환·암호화 session 저장 뒤 우리 키보드로 직접 지시문을 입력해 후보 3개·정확히 1회 삽입·undo를 통과했다. 시험 session은 두 emulator에서 revoke·삭제했고 PC 재시작용 companion grant는 Windows DPAPI로 보존한다. |
| public-origin startup | `PASS` | 새 Quick Tunnel의 route 전파 중 404·502 뒤 정상 manifest를 제한 재시도로 수용하고, 잘못된 manifest 계약은 sleep 없이 즉시 거부하는 Python test를 고정했다. companion Python 11 tests와 실제 Cloudflare tunnel OAuth 왕복을 통과했다. |
| 컴퓨터 자동 연결 마법사 | `PASS` | A35·Z Fold6 cover에서 `_fcitx-ai._tcp.local.`의 `alpaca-home`을 발견하고 Tailscale HTTPS `:9210` manifest 검증·확인창·OAuth 연결 통과; 502와 불일치 manifest는 credential 없이 fail-closed |
| PC 유료 CLI 실행 | `PASS` | Codex CLI `exec`는 ChatGPT 로그인, Claude Code `-p`는 Max 로그인으로 실행; API/token 환경 변수 제거, tool·web·write·session persistence 차단, A35에서 Codex 맞춤법과 Claude 존댓말 실제 생성 성공, Fold에서 Codex 생성 성공 |
| PC WPF tray·자동 실행 | `PASS` | single-file WPF tray의 current-user logon 예약 작업 설치 후 helper를 종료하자 tray-owned 새 PID가 `127.0.0.1:9211` health와 Tailscale HTTPS manifest를 자동 복구; 2026-07-27 Release build warning/error 0, 예약 작업 `Running`, health `ok`, Codex·Claude backend 모두 healthy |
| Responses client | `PASS` | `/responses`, `store=false`, JSON suggestion parse, redirect 금지, prompt/result 비로그와 sanitized error 구현 |
| text/action test | `PASS` | AI 5 suites·12 tests, failure 0. action prompt, provider validation, selection/문단 source, 응답 parse, usage 원문 비저장을 검증 |
| Privacy dashboard | `PASS` | A35에서 현재 provider, 전송 원칙, 기능별 집계 usage, usage 삭제와 GIF cache 삭제 UI 렌더 확인 |
| 명시적 network action | `PASS` | AI window를 열 때는 원문 preview만 표시하고 `문장 3개`를 누른 뒤에만 Responses request 실행 |
| A35 생성 결과 | `PASS` | `meeting 30 minutes late polite` 선택 범위로 한국어 지각 안내 초안 생성, 결과 card와 공급자 표시 확인 |
| 교체 exactly-once·undo | `PASS` | Chrome URL editor에서 결과를 한 번 교체한 뒤 `실행 취소`로 원문이 정확히 복원됨 |
| Z Fold6 UI | `PASS` | cover 화면에서 AI toolbar, 원문 preview와 전체 action group이 잘림 없이 표시됨 |
| AI 결과 우선 UI | `PASS/EMULATOR` | 결과 상태에서 보이지 않는 `weight=1` status container와 가짜 지시문 행을 제거하고 공급자 표기를 숨김; 원문은 한 줄로 축소, 클립보드 선택은 `AI 글쓰기` 제목 우측 버튼으로 유지, 기본 action은 1행·말투/번역은 명시적 펼침으로 분리해 결과 card가 전체 가용 높이를 사용 |
| AI 직접 지시문 | `PASS` | 기능별 두벌식 복제판 제거. 현재 `KeyboardWindow`·Fcitx 조합·후보·한/영·숫자·기호·천지인/세벌식·theme을 그대로 쓰되 output은 내부 최대 300자 buffer로 격리; A35에서 영문 입력·후보·숫자판·천지인 picker·picker restart prompt 보존과 target editor 무변경 통과, pure buffer Unicode·preedit·limit 회귀 테스트 통과 |
| AI 직접 지시 터치 경계 | `PASS/EMULATOR` | prompt strip 상단을 IME content·visible·touchable inset으로 사용해 뒤 editor의 전송/검색 버튼 touch 관통을 차단. API 34 WindowManager region·target activity 유지와 실제 `실행` -> 401 설정 안내 전이를 검증 |
| API key 거부 UX | `PASS/EMULATOR` | API key 401을 typed failure로 분리해 provider 영문 원문과 의미 없는 재시도를 숨기고 한국어 설명·`설정하기`를 표시; CTA의 `개인정보·AI` 직행과 test credential 삭제 확인 |
| 완전 offline zero-request | `PASS/EMULATOR` | API 34 x86_64에서 offline mode와 `OpenAI API 정밀 전사`를 선택하고 확장 toolbar의 AI·전사·GIF를 각각 실제 tap. 모든 동작 뒤 host가 Settings `SearchActivity`로 유지됐고 Fcitx UID 10191 BPF 통계가 `rx=4,595,372 / tx=49,121 / rxPackets=3,157 / txPackets=1,078`로 동일해 delta가 모두 0. 종료 후 offline OFF·`휴대폰 받아쓰기 (추천)`으로 복구하고 `no_backup/voice`·AI credential 부재 확인 |
| 음성 모드 gate | `PASS` | 30초 권장/countdown 제거, elapsed-only·5분 memory safety boundary 적용. 글쓰기 Codex/Claude companion과 STT를 분리하고 기본 `휴대폰 받아쓰기`를 제공한다. A35·Z Fold6에서 Google voice IME 전환과 입력 focus 유지를 확인했고 fallback 정책 unit test 및 companion Python 7 tests 통과 |
| 독립 STT 설정 | `PASS` | 글쓰기 AI/OAuth와 분리된 휴대폰 받아쓰기·OpenAI STT 모드, 정확도/효율 모델, STT 전용 Keystore/no-backup key 저장·삭제와 공식 endpoint allowlist 구현. API 34 emulator에서 미연결 CTA가 STT key·model dialog를 한 번에 직접 여는 경로와 private editor에서 credential loader 0회인 회귀 테스트 확인 |
| 최초 마이크 권한 복귀 | `PASS/A35+EMULATOR` | 권한 Activity가 IME를 재시작해도 같은 editor identity에서 결과를 한 번만 소비한다. A35 권한 철회 상태의 첫 탭·승인 직후 `녹음 중` 및 audio HAL capture 확인; API 34 emulator에서도 권한 dialog와 `녹음 중` 상태 전이 재확인; 다른 editor·stale request 회귀 테스트 통과 |
| Realtime protocol·실패 UX | `PASS/CODE+EMULATOR` | 공식 24 kHz PCM session JSON, append/commit, item별 delta/completed와 401 typed failure를 상태 테스트로 고정. API 34 x86_64에서 `연결 중` 즉시 표시, 거부된 key의 한국어 설명·`설정하기` STT dialog 직행, crash·key log 부재와 test credential 삭제·휴대폰 받아쓰기 복구 확인 |
| OpenAI 실제 전사 품질 | `GATE` | dummy key로 녹음·요청·401 오류 경계만 검증했다. 실제 STT key를 저장하지 않은 상태이며 한국어 정확도·preview·1회 입력은 사용자 key로 별도 검증 필요 |
| 두 기기 최종 설치 | `PASS` | 2026-07-27 A35 `01:02:42`, Z Fold6 `01:02:50`에 음성 fallback 커밋 `6526f823`의 동일 `0.1.2-109-g6526f823` arm64 debug APK 재설치 후 debug Fcitx IME 재선택 |
| AI-01 diff·부분 적용 | `PASS/EMULATOR+ZFOLD` | bounded LCS·대형 입력 fallback·Unicode code-point 범위·stale source/미검토 target 거부와 선택 checkbox UI. emulator의 부분 적용에 더해 Z Fold6 cover에서 기존 OAuth CLI companion으로 `안녕하세욕`을 전송해 `안녕하세요`, `욕 → 요` 결과를 받고 `교체` 정확히 1회·`실행 취소` 시 뒤 공백까지 원문 복원을 실측; 문자는 전송하지 않고 draft 삭제 |
| AI-04 명시적 intake | `PASS` | Sharesheet text/plain·clipboard 행·4,000자·5분 TTL·private/offline/app gate·stale editor·exactly-once 테스트와 A35 실제 답장 생성 통과 |
| AI 3개 후보 계약 | `PASS/EMULATOR` | Compose·Reply·Custom은 Responses `text.format` strict JSON Schema의 `minItems=maxItems=3`을 전송한다. Android parser와 PC CLI companion도 공백·중복 제거 후 정확히 3개가 아니면 성공 card 대신 한국어 재시도 상태로 fail-closed. Pixel 7·QA tablet API 34 emulator에서 OAuth companion의 실제 custom prompt가 서로 다른 한국어 후보 3개를 반환했고 첫 후보 교체·원문 undo 통과. Android 66 suites·299 tests, companion Python 11 tests와 x86_64 app assembly 통과 |
| AI-05 번역 matrix | `PASS/EMULATOR` | OAuth companion으로 `안녕하세요 → Hello`, `Hello → 안녕하세요`, `Hello → こんにちは`, `Hello → 你好`를 각 action에서 실제 생성하고 preview card를 확인 |
| AI-02 말투 matrix | `PASS/A35+EMULATOR` | A35의 Claude 존댓말, API 34 emulator OAuth companion의 카톡체·업무용·정중한 거절·사과·고객응대 action이 모두 실제 한국어 결과 card를 반환 |
| 구간 녹음 버튼·권한·오류 | `PASS/EMULATOR` | 권한 미허용 상태의 `녹음 시작` 1회 탭으로 Android permission dialog 표시, 승인 뒤 같은 editor에서 `녹음 중 0초`·mic indicator·2초 경과를 확인하고, 중지 뒤 dummy key 401을 한국어 설명과 `설정하기`로 복구했다. 보고된 무반응 상태는 emulator가 input device를 물리 keyboard로 오인해 Fcitx input view 전체를 접은 수명주기 상태와 상관됐고 IME 재선택으로 복구됐다. 정상 input view에서 recorder 버튼 자체는 즉시 동작하므로 microphone 품질 gate와 분리한다. |
| STT 모드 설정 원자성 | `PASS/EMULATOR` | 전용 key가 없는 Pixel 7·QA tablet에서 OpenAI 정밀 전사를 선택한 뒤 key dialog를 취소해도 `휴대폰 받아쓰기`가 유지됐다. Pixel 7에서 test key 저장 성공 뒤에만 정밀 모드가 활성화됐고 앱 UI의 `STT API 키 제거`로 key 삭제·휴대폰 모드 복귀·제거 행 소멸을 확인했다. test key는 남기지 않았다. 설정 아이콘은 별도 mutable drawable과 theme tint를 사용해 phone·tablet light theme에서 흰색 소실 없이 표시한다. |
| 회의 파일 picker·인증 복구 | `PASS/EMULATOR` | Pixel 7과 QA tablet API 34에서 1초 WAV 선택 뒤 같은 editor의 새 회의 window로 one-shot 복귀. tablet landscape에서 streaming body 조기 종료의 HTTP 401을 회수해 일반 파일 오류 대신 STT 재연결 설명과 `설정하기`를 표시 |
| 회의 전사 모드 독립 | `PASS/CODE` | 빠른 받아쓰기가 `휴대폰 받아쓰기`여도 저장된 STT profile이 있으면 회의 버튼을 표시한다. 표시 시 파일 존재만 확인하고 명시적 회의 창 진입 뒤에만 복호화하며 private/offline/app-network 차단에서는 loader 0회 |

사용량 저장소는 action·성공/실패·입출력 문자 수·마지막 provider/model만 집계하며 prompt, 결과,
API key, endpoint URL 필드를 갖지 않는다. 개인 debug build의 공급자 credential도 Gradle environment
property로만 주입하고 저장소·APK 산출물 이름·오류 출력에 노출하지 않는다.

## 9. 권장 구현 순서

### 단계 0 — 현재 기준선 보존

1. 기존 dirty tree를 삭제하거나 reset하지 않는다.
2. 현재 한글 배열·테마·다국어 변경의 test/build/device 증거를 유지한다.
3. 큰 기능 묶음 전 review 가능한 checkpoint를 만든다.

### 단계 1 — GIF pipeline과 실사용 catalog

1. Animated Noto 기본 provider, Commons 보조 provider와 license policy pure model·test.
2. repository, search generation, thumbnail loader.
3. toolbar, grid, selection overlay.
4. canonical link exactly-once path.
5. FileProvider, original cache, `RichContentCommitter`.
6. sensitive/private gate, error, retry, cleanup.
7. JVM test, build, A35/Fold6 지원·미지원 editor 검증.
8. KLIPY provider, 한국어 검색, provider별 cache, 두 기기 catalog 검증. (`DONE`)
9. 전용 production key와 partner 승인. (`GATE`)

### 단계 2 — 로컬 한국어 quick wins

1. `KO-01` 한/영 오타 복구. (`DONE`)
2. `KO-02` 초성 통합 검색. (`DONE`)
3. `KO-03` 동적 빠른 문구. (`DONE`: 암호화 profile·preview·정확히 1회 삽입 검증)
4. `KO-03A` 자동 스니펫 확장. (`DONE`: emulator 일반·Enter·buffered 분할·private 차단 통과)
5. `KO-09` 한글 어절 자동완성. (`DONE`: emulator 일반·buffered 후보 선택·미선택 Space·private 차단 통과)
6. `KO-04` 앱별 profile과 network policy. (`DONE`: emulator exact match·fallback·표면 저장·정책 차단·dialog viewport 통과)
7. `KO-05` 한자 음훈 candidate UI. (`DONE`: emulator 명시 액션·음훈·1회 교체·한글 복귀 통과)

### 단계 3 — AI 기반과 text 기능

1. `AI-00`, `AI-06`, `SEC-02` 공급자·Keystore·privacy/usage 기반. (`DONE`)
2. `AI-07` endpoint OAuth public-client flow와 token lifecycle. (`DONE`: PC CLI companion·두 기기 login·generation 통과)
3. `AI-08` 일반 사용자용 AI 연결·재로그인 CTA. (`DONE`: 코드·테스트·두 기기 미연결 안내와 설정 직행 통과)
4. `AI-09` 컴퓨터 자동 발견·검증·연결 마법사. (`DONE`: 두 기기 mDNS·Tailscale HTTPS·PKCE·DPAPI 재시작 복구 통과)
5. `AI-10` 직접 지시 터치 안전·API key 인증 복구. (`DONE`: API 34 emulator touchable region·설정 CTA 통과)
6. `SEC-03` offline network gate. (`DONE`: API 34 emulator에서 AI·OpenAI 전사·GIF 개별 tap 전후 UID BPF network delta 0)
7. `AI-01` 맞춤법·띄어쓰기. (`DONE`: emulator 실제 diff 선택·1회 적용 통과)
8. `AI-02` 말투 변환. (`DONE`: 존댓말·카톡체·업무용·정중한 거절·사과·고객응대 실제 결과 통과)
9. `AI-03` 문장 생성. (`DONE`: strict structured output·정확히 3개 후보·실제 OAuth companion 생성·교체·undo 통과)
10. `AI-05` 번역. (`DONE`: emulator 한↔영·일·중 실제 OAuth companion 결과 통과)
11. `AI-04` 답장 초안. (`DONE`: clipboard/share intake·실제 답장 생성·정확히 1회 입력 통과)

### 단계 4 — 음성

1. push-to-talk audio capture와 permission UX. (`DONE`: A35와 API 34 emulator 최초 권한 자동 복귀·AudioRecord·중지·401 복구 PASS)
2. `VOICE-02` 고정밀 구간 전사. (`IN_PROGRESS`: 독립 STT profile·elapsed-only 5분 safety capture·preview 완료, 실제 key live 품질 gate)
3. `VOICE-01` realtime partial transcript. (`IN_PROGRESS`: WebSocket·partial/final 상태·emulator 401 UX PASS, 실제 key 한국어 품질과 production ephemeral token/WebRTC gate)
4. `VOICE-03` diarization과 회의 UI. (`IN_PROGRESS`: 독립 STT profile 재사용, API 34 x86_64 phone·tablet emulator에서 system picker 진입·WAV 선택·동일 editor 복원·요청 실행·조기 401 설정 CTA·landscape 무잘림 PASS; 실제 OpenAI key·회의 음원 품질 gate)
5. `VOICE-04` Codex 구독 OAuth voice bridge. (`BLOCK`: desktop UI 외 공개 CLI·HTTP audio 계약 없음)

### 단계 5 — 개인화·대화면·장기 기능

1. `KO-05` 한자 음훈, `KO-05A` 국어사전 정의·`KO-05B` 색인 성능, `KO-06` 개인 단어장. (`DONE`: emulator 기능·cold-load gate 통과)
2. `UX-01` smart clipboard action. (`DONE`: emulator 명시 선택·mask·합치기·1회 삽입·private editor 차단 통과)
3. `SEC-01` 민감 문구 금고. (`IN_PROGRESS`: 코드 완료, 생체 인증 실기기 gate)
4. `UX-02` Fold·tablet 분할 layout. (`DONE`: 펼친 Fold6 한글·영문 손 경계와 API 34 tablet emulator 초기 복원·회전·모바일·중앙 무입력·숫자판 왕복 통과)
5. `KO-07` 조사 MVP와 `KO-08` 감정 후보. (`DONE`: emulator 조사 받침·ㄹ 예외, 감정 강·약 chip, 후보·1회 삽입 통과)
6. `KO-07` 다음 어절과 `MM-01` 로컬 OCR. (`DONE`: emulator 공백 후보·선택·취소·1회 삽입, OCR picker 복원·정방향·90도 회전·저화질 인식·줄 선택·1회 삽입, 공개 native source build·라이선스 export 통과)
7. `UX-03` 기본 1행·명시적 2행 안정형 툴바. (`DONE/CODE+EMULATOR+FOLD_COVER`: 1행 scroll·2행 6열·1행 후보 높이 유지 통과, Z Fold6 unfolded·A35 최신 APK 확인 gate)

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
.\gradlew.bat :app:testDebugUnitTest -PbuildABI=x86_64
.\gradlew.bat :app:assembleDebug -PbuildABI=x86_64
.\gradlew.bat :plugin:hangul:assembleDebug -PbuildABI=x86_64
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
| 2026-07-26 | 실사용 실패를 근거로 GIF 검색을 인라인 한글·영문 자판으로 교체하고 기본 provider를 Animated Noto Emoji로 변경. Commons는 별도 공개 미디어 tab backlog로 이동 |
| 2026-07-26 | Animated Noto의 밈·리액션 부족을 근거로 KLIPY를 실사용 catalog로 구현. key 없는 공개 build는 Noto fallback을 유지하고 production key·partner 승인은 별도 gate로 둠 |
| 2026-07-26 | KLIPY key의 Gradle·BuildConfig 주입을 제거하고 Android Keystore·noBackup 전용 저장소와 명시적 설정 UX를 유일한 key 경로로 확정 |
| 2026-07-26 | 앱별 profile은 package별 layout·theme·toolbar·transport·network·AI 정책만 저장하며 private editor와 전역 offline hard deny가 항상 우선 |
| 2026-07-26 | 한자 음훈은 새 네트워크 사전이 아니라 이미 배포되는 libhangul `hanja.txt` comment를 먼저 정확히 노출하고 국어사전 정의는 별도 source/license gate로 분리 |
| 2026-07-27 | 국어사전 정의는 계정·API key 없이 재현 가능한 한국어 위키낱말사전 snapshot을 현대 한글만 추출해 오프라인 제공하고, 품사·CC BY-SA 4.0 attribution·원문/기여자 링크를 함께 노출. 국립국어원 Open API/전체 내려받기는 key·사용 목적·이메일 동의가 필요한 별도 provider 후보로 유지 |
| 2026-07-27 | 국어사전 3만여 정의의 eager object parse는 production에 허용하지 않는다. 정렬 binary offset index와 조회 record lazy decode로 교체하고 bundled JVM cold-load p95 9ms, phone 1,559ms, wide 520ms를 `KO-05B` 완료 gate로 확정 |
| 2026-07-26 | GIF attach 실패 뒤 link 자동 삽입 금지 |
| 2026-07-26 | GIF 품질은 KLIPY pagination·한국어 밈 chip·safe gate로 보강하고 GIPHY는 branding·tracking·production review를 갖춘 별도 provider로만 허용 |
| 2026-07-26 | 답장 intake는 Sharesheet/명시적 clipboard만 허용하고 화면 임의 읽기와 영구 원문 저장을 금지 |
| 2026-07-26 | 민감 문구는 매 작업 인증 결합 cipher, package allowlist, 60초 메모리 세션을 모두 만족할 때만 노출 |
| 2026-07-26 | 구간 전사는 `AI 정밀 받아쓰기`로 명명하고 Realtime delta는 ephemeral-token backend가 준비될 때까지 별도 gate로 유지 |
| 2026-07-26 | Fold split은 compact/expanded와 세로/가로 profile을 독립 저장하고 불명확한 posture에서는 일반 layout을 유지 |
| 2026-07-26 | GIPHY는 production review key와 별도 provider 선택이 있을 때만 활성화하며 media-copy 승인 전에는 link-only로 제한 |
| 2026-07-26 | 회의 화자 분리는 system picker의 명시 선택 audio만 stream하고 segment review 없이 자동 입력하거나 요약하지 않음 |
| 2026-07-26 | 한국어 다음 단어는 실제 공백 경계 뒤의 project-curated 로컬 후보만 기본 미선택으로 표시하고 사용자 입력을 학습·저장하지 않음 |
| 2026-07-27 | 툴바는 기본 1행 가로 스크롤, 고정 48dp control의 명시적 펼침만 2행 6열로 전환하며 자동 후보는 1행을 유지하고 editor 높이는 해당 세션 envelope로 고정 |
| 2026-07-26 | 일반 사용자용 AI 미연결·재로그인 상태에만 `설정하기`를 제공하며 private/offline/policy 차단은 CTA 없이 fail-closed |
| 2026-07-26 | GIPHY exact query 계약은 보존하고 한국 밈 query fallback은 성공한 empty KLIPY 첫 page와 로컬 Noto에만 적용 |
| 2026-07-26 | OCR은 proprietary ML SDK 대신 Apache-2.0 Tesseract 계열과 pinned 한국어 best model을 사용하며 원본·결과를 저장하지 않음 |
| 2026-07-26 | 표준 API key의 일반 mobile direct 저장은 기본 경로로 사용하지 않음 |
| 2026-07-26 | AI text action은 선택/현재 문단 preview 후에만 network를 호출하고 결과 교체·추가·undo를 명시적 동작으로 제한 |
| 2026-07-27 | 글쓰기 AI와 음성 STT profile/key를 분리하고 휴대폰 받아쓰기를 기본값으로 유지. 최초 마이크 권한 뒤에는 동일 editor에서만 음성 window와 녹음을 정확히 한 번 재개 |
| 2026-07-26 | 동적 빠른 문구는 기존 `.mb` 형식을 유지하고, 명시적 미리보기 뒤 정확히 한 번 삽입 |
| 2026-07-27 | AI·GIF·검색 등 IME 내부 text 입력은 활성 `KeyboardWindow`와 Fcitx 엔진을 재사용하며 기능별 두벌식 복제판을 금지 |
| 2026-07-27 | provider manifest capability를 profile SSOT로 보존하고 실제 `transcription` 미선언 provider에서는 음성 capture를 시작하지 않음 |
| 2026-07-27 | Codex desktop Voice는 공개 companion audio 계약이 생기기 전까지 `BLOCK`; 구독 OAuth token의 비공식 endpoint 사용 금지 |
| 2026-07-27 | 음성 미지원 공급자 화면은 비활성 녹음 버튼을 금지하고, 활성 system voice IME가 있으면 `휴대폰 받아쓰기 사용`, 없으면 `설정하기`를 제공 |
| 2026-07-27 | IME가 keyboard 위에 interactive prompt를 표시하면 그 prompt 상단부터 touchable inset으로 보고하고, 뒤 editor control로 touch를 통과시키지 않음 |
| 2026-07-27 | 글쓰기 API key 401은 provider 오류 문자열이나 무조건 재시도로 표시하지 않고, 사용자용 연결 확인 문구와 `설정하기`로 복구시킴 |
| 2026-07-27 | OCR native 배포는 공개 tag source build·trusted JitPack FLOSS dependency·artifact 식별·전이 라이선스 고지를 완료 기준으로 삼고 byte-identical하지 않은 AAR을 재현 빌드라고 주장하지 않음 |
| 2026-07-27 | 출근 이후 일반 Android 기능·회귀 검증의 기준 기기는 API 34 x86_64 emulator로 전환. A35 마이크 품질과 Z Fold cover/unfold posture처럼 emulator가 재현할 수 없는 하드웨어 게이트만 최종 실기기 확인으로 남김 |
| 2026-07-27 | 문장 생성·답장·직접 지시는 Responses strict JSON Schema와 수신측 exact-count 검증을 함께 사용해 서로 다른 후보 3개가 아니면 부분 결과를 표시하지 않음 |
| 2026-07-27 | 실시간 받아쓰기는 개인 고급 BYOK에서 공식 Realtime WebSocket을 허용하고 partial은 preview 전용, completed만 명시적 최종 입력 gate로 전달. 일반 배포는 backend 단기 token과 WebRTC를 production gate로 유지 |
| 2026-07-27 | 개인 단어장은 posture·센서·제조사 의존성이 없어 API 34 emulator의 등록·삭제·후보 우선순위·1회 선택을 Android 완료 gate로 인정하고 실기기 중복 gate를 제거 |
| 2026-07-27 | smart clipboard는 posture·센서·제조사 의존성이 없어 API 34 emulator의 명시 선택·mask·합치기·정확히 1회 삽입·password editor 차단을 Android 완료 gate로 인정 |
| 2026-07-27 | 한국식 감정표현은 휴대폰에서 ㅋㅋ·ㅎㅎ 강도를 직접 고를 수 있도록 강·약 quick chip을 모두 제공하고, API 34 emulator의 후보·1회 삽입·password editor 차단을 Android 완료 gate로 인정 |
| 2026-07-27 | 한국어 조사·다음 어절은 posture·sensor·제조사 의존성이 없어 API 34 emulator의 받침·ㄹ 예외, 공백 후보, 선택·취소·정확히 1회 삽입을 Android 완료 gate로 인정 |
| 2026-07-27 | 한글 어절 자동완성은 API 34 emulator의 일반·buffered editor 후보·명시 선택·미선택 Space·private 차단을 Android 완료 gate로 인정하고 Fold posture gate와 분리 |
| 2026-07-27 | 동적 문구와 자동 스니펫은 API 34 emulator의 암호화 profile·Space·Enter·buffered 분할 trigger·private literal 보존을 Android 완료 gate로 인정하고 Fold posture gate와 분리 |
| 2026-07-27 | 앱별 profile은 API 34 emulator의 exact package·전역 fallback·키보드 표면별 저장·network/AI 차단을 Android 완료 gate로 인정. Fold 자세별 표면은 `UX-02`에만 남김 |
| 2026-07-27 | 한자는 일반 한글 자동완성에 섞지 않고 더보기의 `한글` 상태 액션으로만 1회 연다. flush 전 활성 어절로 조회하고 선택 뒤 즉시 한글 자동완성으로 복귀 |
| 2026-07-27 | 비하드웨어 Android 회귀의 완료 기준은 Pixel 7 API 34 emulator와 QA tablet로 통일하고 A35 microphone 품질·Fold posture·생체 인증만 실기기 gate로 유지. `녹음 시작` 경로는 권한 dialog·즉시 녹음 상태·2초 경과·중지·401 설정 복구까지 통과했다. 보고된 무반응은 emulator가 물리 keyboard로 오인해 Fcitx input view 전체를 접은 수명주기 상태와 상관되므로 IME 재선택 복구와 recorder 동작을 분리해 판정한다. |
| 2026-07-27 | 회의 음성 `ACTION_OPEN_DOCUMENT` 결과는 detach된 window callback으로 전달하지 않고 원래 editor identity와 함께 process memory에서 1회 보관한 뒤 새 meeting window가 소비한다. STT 401은 파일 재선택이 아니라 음성 설정 CTA로 복구한다. |
| 2026-07-27 | Tailscale private network를 사용할 수 없는 emulator 검증에는 strict HTTPS origin-only `--public-origin`을 허용하되 임시 tunnel을 production 기본값으로 승격하지 않음 |
| 2026-07-27 | 새 `--public-origin` route의 일시적 404·502는 bounded backoff로만 재검증하고 manifest 계약 오류는 즉시 fail-closed. Pixel 7·QA tablet에서 OAuth·직접 지시·후보 3개·삽입·undo 뒤 시험 grant와 tunnel을 모두 정리 |
| 2026-07-27 | 전용 STT credential이 없는 온라인 받아쓰기 선택은 mode를 선저장하지 않는다. key 저장 성공과 mode 변경을 같은 사용자 완료 경로로 묶고, key dialog 취소 시 기존 휴대폰 받아쓰기를 보존한다. |
| 2026-07-28 | AI 글쓰기는 기본 action 1행과 고정 `더보기`를 사용하고 말투·번역은 명시적 펼침에서만 표시한다. 가짜 prompt row를 금지하며 빈 editor의 `직접 지시`도 기존 Fcitx prompt keyboard로 연다. |
| 2026-07-28 | 빠른 받아쓰기 mode는 회의·메모 파일 전사의 STT profile 선택을 숨기지 않는다. 회의 버튼 렌더는 암호화 파일 존재만 보고 실제 credential 복호화는 privacy·network 허용 상태의 명시적 회의 진입 뒤에만 수행한다. |
| 2026-07-28 | IME 소유 설정 Activity 왕복은 원 editor identity에 묶인 one-shot software-keyboard resume로 복구한다. 전역 virtual 강제는 금지하고 자체 설정 editor는 예약을 소비하지 않는다. |
