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
현재 활성 구현 마일스톤: `반응형 2행 툴바·일반 사용자 AI 설정 CTA·원격 AI OAuth 통합 checkpoint`

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
| `KO-03` | 동적 빠른 문구 | `IN_PROGRESS` | 7 JVM 테스트와 A35 날짜·profile·clipboard 미리보기·1회 삽입 |
| `KO-03A` | 자동 스니펫 확장 | `IN_PROGRESS` | `:주소1`·`:이메일` 별칭과 사용자 `:` 상용구의 경계키 확장 구현 중 |
| `KO-09` | 한글 어절 자동완성 | `IN_PROGRESS` | 로컬 빈도 사전, 명시적 후보 선택, buffered 입력 호환 구현 중 |
| `KO-04` | 앱별 키보드 profile | `IN_PROGRESS` | package별 layout·theme·toolbar·transport·network·AI 정책 구현, 두 기기 matrix 진행 중 |
| `KO-05` | 한자 후보 음훈 | `IN_PROGRESS` | bundled libhangul 음훈을 candidate comment로 전달, native test와 두 기기 UI gate 진행 중 |

한국어 기준선과 GIF·KO-01·KO-02는 각각 검증 가능한 checkpoint commit으로 고정돼 있다.
원칙적으로 새 기능은 현재 milestone의 공통 게이트와 두 기기 설치를 마친 뒤 다음 항목으로 넘어간다.
다만 `KO-03`은 Z Fold6 연결만 외부 gate로 남은 상태에서 사용자가 자동완성을 우선 지시했으므로,
코드 기준선을 보존한 채 `KO-09` 구현을 병행한다. 둘 다 두 기기 검증 전에는 `DONE`으로 올리지 않는다.

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

## 6. 통합 제품 백로그

### 6.1 현재 활성 마일스톤

| ID | 기능 | 상태 | 가치 | 난이도 |
| --- | --- | --- | --- | --- |
| `GIF-01` | GIF 검색·링크·첨부 파이프라인 | `DONE` | 키보드 이탈 없이 GIF 검색·전달 | L |
| `GIF-02` | 실사용 리액션 GIF 공급자 | `IN_PROGRESS` | KLIPY 한국어 검색·밈 catalog 구현 완료, production key·승인 gate 남음 | M |
| `GIF-03` | 선택형 GIPHY 공급자 | `IN_PROGRESS` | 비혼합·branding·analytics·안전등급 구현, production/media-copy 승인 gate | M |

`GIF-01`의 전송 파이프라인 계약과 `GIF-02`의 공급자 계약은 7절에 있다. 사용자 실사용 결과
Animated Noto Emoji는 움직이는 이모지 fallback으로는 유효하지만 리액션 GIF catalog로는 현저히
부족했다. KLIPY 공급자를 별도 source와 cache namespace로 구현하고 A35·Z Fold6에서 실사용 catalog를
검증했다. 다만 저장소에 test key를 넣지 않으며 production key와 partner 승인이 끝날 때까지
`GIF-02`의 배포 상태는 `IN_PROGRESS`로 유지한다.

### 6.2 1차 한국어 로컬 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `KO-01` | 한/영 오타 즉시 복구 | `DONE` | `dkssud→안녕`, `ㅗ디ㅣㅐ→hello`, preview 후 교체 | S |
| `KO-02` | 초성 통합 검색 | `DONE` | 빠른 문구·clipboard·emoji를 `ㄱㅅ` 등으로 검색 | M |
| `KO-03` | 동적 빠른 문구 | `IN_PROGRESS` | 날짜·시간·이름·전화·이메일·주소·clipboard 변수, preview | M |
| `KO-03A` | 자동 스니펫 확장 | `IN_PROGRESS` | `:` trigger 뒤 space·Enter로 암호화 profile 또는 사용자 상용구 확장 | M |
| `KO-04` | 앱별 키보드 profile | `IN_PROGRESS` | package별 layout, theme, transport, toolbar, AI 정책 | M |
| `KO-05` | 한자·국어사전 후보 | `IN_PROGRESS` | bundled 한자·음훈 후보 구현, 국어사전 정의 source는 별도 gate | M |
| `KO-06` | 개인 단어장 | `IN_PROGRESS` | opt-in 로컬 사전과 개인 후보 우선순위 구현, 두 기기 후보 검증 남음 | L |
| `KO-07` | 한국어 조사·문맥 후보 | `IN_PROGRESS` | 받침별 조사와 로컬 다음 어절 후보 구현, 두 기기 UX gate | L |
| `KO-08` | 한국식 감정표현 추천 | `IN_PROGRESS` | 로컬 emoji·kaomoji·ㅋㅋ/ㅎㅎ 강도 후보 구현, 두 기기 검증 남음 | M |
| `KO-09` | 한글 어절 자동완성 | `IN_PROGRESS` | 두 음절부터 로컬 접두어 후보, 선택한 후보만 정확히 한 번 확정 | M |

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
A35와 Z Fold6의 일반 editor 및 buffered 호환 editor 실기기 검증이다.

#### KO-09 구현·검증 증거 (2026-07-26)

| 항목 | 상태 | 증거 |
|---|---|---|
| 사전 출처·재현성 | `PASS` | 국립국어원 원본 SHA-256을 고정하고 생성 결과 5,250개·91,467 bytes·SHA-256 `1778F7ACCBE3190A3ECDDFC9991B2511F466425B48185004072C22558BBBA2C1` 재현 |
| native parser·후보 테스트 | `PASS` | Android arm64-v8a `testcompletiondictionary`를 A35 `/data/local/tmp`에서 실행해 순위·limit·중복·CRLF·접미부 계산 검증 |
| 전체 자동 테스트·빌드 | `PASS` | `:app:testDebugUnitTest :app:assembleDebug :plugin:hangul:assembleDebug -PbuildABI=arm64-v8a`, 현재 기준 23 suites·85 tests·실패 0 |
| plugin 패키징 | `PASS` | plugin APK에 `completion.txt`, `completion-NOTICE.md`, 한국어 번역, `libhangul.so` 포함 및 AboutLibraries에 KOGL 제1유형 표시 확인 |
| A35 후보 UX | `PASS` | Discord에서 `안녕` 입력 시 `안녕 / 안녕하세요 / 안녕하십니까` 순서로 표시 |
| A35 명시적 선택 | `PASS` | `안녕하세요` 후보 tap 뒤 editor hierarchy의 compose text가 `안녕하세요` 정확히 한 번임을 확인; 메시지는 전송하지 않고 draft 삭제 |
| A35 미선택 space | `PASS` | 후보를 누르지 않고 space 입력 뒤 compose text가 `안녕 `으로 유지되고 자동완성 후보로 치환되지 않음을 확인 |
| 한글 후보 언어 경계 | `PASS` | 자동완성 사전은 현대 한글 음절만 적재·노출하고, 자동완성 중 지속 Hanja 후보가 후보창을 선점하지 않도록 분리; 한자는 명시적 1회 변환 action으로 유지 |
| 민감 editor 차단 | `CODE` | `Password`, `Sensitive`, `NoSpellCheck` capability 중 하나라도 있으면 사전 조회 전에 fail-closed |
| 최종 A35 설치 | `PASS` | `SM-A356N / RFCX60GBL3D`, 최신 app/plugin `0.1.2-92-g0c3b30cf` 설치 및 debug Fcitx IME 재선택 |
| 최종 Z Fold6 설치 | `GATE` | 최신 APK 2개 Taildrop 전달 완료; Android 무선 디버깅 endpoint 인증이 없어 기기 설치·후보 UX 확인 대기 |

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
계획 테스트, email profile 하위 호환 테스트, 전체 JVM/build, A35와 Z Fold6에서 `:주소1`·`:이메일`
확장 및 private editor 비활성 검증이다.

#### KO-03A 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 스니펫 catalog | `PASS` | 기본 `:주소1`·`:이메일`, 사용자 override, URL/어절 내부 오탐 방지, 분할 입력, 후행 문자열 거부를 6개 JVM 테스트로 검증 |
| A35 space 확장 | `PASS` | `:주소1 `과 `:이메일 `이 암호화 profile 값으로 정확히 한 번 교체됨 |
| A35 Enter 확장 | `PASS` | trigger 뒤 Enter가 먼저 스니펫을 확장하고 경계 동작을 한 번만 적용함 |
| private editor | `PASS` | private editor에서 자동 스니펫을 비활성화하고 literal 입력을 보존함 |
| 전체 회귀 | `PASS` | 현재 기준 app 23 suites·85 tests·failure/error/skipped 0, arm64 app/plugin build 성공 |
| Z Fold6 | `GATE` | 최신 APK 전달 완료; `:주소1`·`:이메일`과 private editor 실기기 확인 필요 |

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
| 최종 Z Fold6 설치 | `BLOCK` | 무선 디버깅 endpoint가 mDNS에서 사라져 재연결 대기 |

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

#### KO-04 앱별 profile 계약·증거 (2026-07-26)

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
| app build | `PASS` | arm64 `:app:assembleDebug`와 전체 JVM test 통과 |
| A35 설정 UI | `PASS` | `com.android.chrome` profile을 추가하고 `기본 펼침`을 저장한 뒤 목록 summary에서 재확인 |
| A35 runtime | `PASS` | Chrome editor를 다시 열자 접혀 있던 toolbar가 profile에 따라 펼쳐지고 GIF 진입 버튼이 즉시 표시됨 |
| Z Fold6 profile matrix | `GATE` | cover/unfolded 상태에서 layout·theme·toolbar 정책을 각각 재시작해 검증해야 함 |

#### KO-05 한자 음훈과 국어사전 경계 (2026-07-26)

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
| 두 기기 candidate UI | `GATE` | A35·Z Fold6에서 실제 한자 action 뒤 음훈 렌더와 선택 교체를 확인해야 함 |

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
| `AI-01` | 한국어 맞춤법·띄어쓰기·조사 교정 | `IN_PROGRESS` | Unicode-safe diff·선택 적용 구현, 두 기기 실사용 gate 남음 | M |
| `AI-02` | 존댓말·말투 변환 | `IN_PROGRESS` | 존댓말·카톡·업무·거절·사과·고객응대 action 구현, action별 실기기 matrix 남음 | M |
| `AI-03` | 빠른 문장 생성 | `IN_PROGRESS` | 의도 기반 후보·명시적 교체/추가 구현과 A35 live 통과, 3개 후보 품질 gate 남음 | M |
| `AI-04` | 답장 초안 | `IN_PROGRESS` | 선택·문단·명시적 clipboard·Sharesheet intake 구현, 두 기기 검증 남음 | M |
| `AI-05` | 키보드 번역 | `IN_PROGRESS` | 한↔영·일·중 action과 preview 구현, 언어별 실기기 matrix 남음 | M |
| `AI-06` | AI provider profile | `DONE` | OpenAI·OpenAI-compatible endpoint, model tier, 암호화 BYOK 분리 | M |
| `AI-07` | 원격 호환 endpoint OAuth | `IN_PROGRESS` | public client Authorization Code + PKCE S256, 외부 브라우저, 암호화 token refresh·revoke·명시적 재로그인 구현; 실제 IdP·두 기기 gate | L |
| `AI-08` | 일반 사용자 AI 연결 안내 | `DONE` | 미연결·OAuth 만료 상태에 설명과 `설정하기` CTA를 제공하고 개인정보·AI 화면으로 직행; private/offline/policy 차단과 분리 | S |

### 6.4 음성·멀티모달 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `VOICE-01` | GPT 실시간 받아쓰기 | `IN_PROGRESS` | push-to-talk·권한·한국어 hint·최종 preview·exactly-once commit 구현, Realtime partial transcript GATE | L |
| `VOICE-02` | 고정밀 녹음 전사 | `IN_PROGRESS` | 30초 in-memory WAV 구간 전사 구현, 두 기기 정확도·취소 UX gate 남음 | L |
| `VOICE-03` | 화자 분리 회의·메모 | `IN_PROGRESS` | 명시 선택 파일·화자/timestamp preview·선택 삽입 구현, OpenAI profile·실기기 gate | L |
| `MM-01` | OCR·사진 속 한글 입력 | `IN_PROGRESS` | 명시 선택 이미지의 로컬 한글 OCR·줄별 preview·1회 삽입 구현, 실기기 정확도 gate | L |

### 6.5 편의·기기·보안 기능

| ID | 기능 | 상태 | MVP 계약 | 난이도 |
| --- | --- | --- | --- | --- |
| `UX-01` | Smart clipboard action | `IN_PROGRESS` | 명시 선택·서식 제거·합치기·전화/계좌 형식화·PII 마스킹 구현, 기기 검증 남음 | M |
| `UX-02` | Fold·tablet 분할 키보드 | `IN_PROGRESS` | compact/expanded·세로/가로 profile과 중앙 non-touch gap 구현, unfolded 검증 gate | L |
| `UX-03` | 폭 적응형 기능 툴바 | `IN_PROGRESS` | 열린 툴바는 compact 화면에서 6×2행, 넓은 화면에서 12×1행으로 자동 전환하고 모든 제어를 최소 48dp로 유지 | M |
| `SEC-01` | 민감 빠른 문구 금고 | `IN_PROGRESS` | 인증 결합 Keystore·60초 package 세션·allowlist 구현, 생체 실기기 gate | L |
| `SEC-02` | Privacy dashboard | `DONE` | 기능별 전송 범위, provider, 집계 사용량, 즉시 삭제 | M |
| `SEC-03` | 완전 offline mode | `IN_PROGRESS` | AI·GIF toolbar와 network gate 통합, 실제 기기 zero-request gate 남음 | S |

### 6.6 2026-07-26 병렬 구현 checkpoint 계약

| ID | 코드 계약 | 현재 증거 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `KO-06` | `noBackupFilesDir` versioned·atomic 사전, 기본 off, 최대 500개, 개인 후보를 정적 후보보다 우선하고 중복 제거 | Kotlin store·policy test와 arm64 native completion test, 파일 mtime/size cache | A35·Z Fold6에서 등록·삭제·우선 후보·손상 fail-closed 확인 |
| `KO-07` | 사용자가 `조사` chip을 눌렀을 때만 은/는·이/가·을/를·과/와·으로/로·이에요/예요를 받침과 ㄹ 예외로 제안 | 순수 규칙·editor identity·exactly-once 테스트 | 두 기기 UI와 실제 어절 검증; 다음 어절 문맥 모델은 별도 구현 |
| `KO-08` | editor·clipboard를 읽지 않는 명시적 감정 chip, 로컬 emoji/kaomoji, ㅋㅋ·ㅎㅎ 반복 강도 순위 | 강도·privacy·dedupe·exactly-once 테스트 | 두 기기 chip·후보·삽입 검증 |
| `AI-04` | `ACTION_SEND text/plain` 또는 사용자가 누른 clipboard 행만 4,000자 이하로 process memory에 5분 보관 | action/MIME/TTL/private gate·editor identity·exactly-once 테스트 | Sharesheet→키보드 preview→답장 생성·삽입을 두 기기에서 확인 |
| `UX-01` | 최대 10개 명시 선택, plain text·합치기·한국 전화·명시적 계좌 grouping·PII mask preview | transformer·선택 상태·private gate·exactly-once 테스트 | 일반 editor 두 기기와 private editor 차단 확인 |
| `SEC-01` | vault 전용 auth-bound AES-GCM, 매 read/write `CryptoObject` identity 확인, package allowlist, 60초 memory session | codec·allowlist·TTL·앱 전환·손상·commit gate 테스트 | Android 11+ 생체/기기 인증 prompt, 앱 전환 재잠금, 허용/비허용 package 확인 |
| `VOICE-02` | 30초 16 kHz mono in-memory WAV, `gpt-4o-transcribe`, 한국어 hint, preview 뒤 1회 삽입 | PCM/WAV·multipart·privacy·stale editor·commit 테스트 | 두 기기 permission/focus 복귀, 실제 provider 정확도와 취소 확인 |

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
| `UX-02` | compact와 expanded profile을 독립 저장하고 각각 세로/가로 중앙 간격을 둔다. 두 축이 모두 600dp 이상일 때만 expanded로 판정하며 size가 불명확하면 일반 layout을 유지한다. 숫자판은 split하지 않는다. | profile 경계·방향·독립 toggle·gap cap·fill key를 포함한 8 tests | Z Fold6 cover/펼침·회전·한글/영문/모바일 표면·중앙 무입력·`?123` 왕복 |
| `GIF-03` | GIPHY는 사용자가 명시적으로 고르는 별도 provider다. production review 확인 key가 없으면 network 0회이며 KLIPY로 자동 fallback하지 않는다. rating `g`, 한국어·한국 locale, Powered by GIPHY, canonical/media 분리와 load/click/sent analytics를 적용한다. | provider parser·pagination·안전등급·credential·resolver·analytics tests | 실제 production key review; GIF 첨부는 별도 media-copy 서면 승인 필요 |
| `VOICE-03` | `ACTION_OPEN_DOCUMENT`로 고른 content URI만 최대 60분·24MiB 범위에서 stream하고 화자·timestamp segment를 preview한다. 사용자가 체크한 segment만 16,000자 이하로 정확히 한 번 입력한다. | MIME/확장자·크기·시간·multipart·response parser·selection·commit tests | 표준 OpenAI profile과 실제 회의 음원 정확도·picker 복귀·취소·두 기기 UI |

두 번째 checkpoint의 단독 통합 검증은 app JVM 51 suites·219 tests, failure/error/skipped 0과
arm64 app·Hangul plugin assemble을 통과했다. GIPHY key·회의 음원·화자 전사 원문은 prefs, cache,
backup, 일반 log에 저장하지 않는다.

`f5d212f8` 기준 app/plugin `0.1.2-98-gf5d212f8`를 A35 `SM-A356N`과 Z Fold6
`SM-F956N`에 덮어 설치하고 두 기기 모두 debug Fcitx IME를 다시 선택했다. A35에서 `?123` 숫자판
전환을 다시 확인했고, GIPHY key가 없는 상태가 `네트워크 요청 0회`로 표시되는 것을 확인했다.
회의 파일 화면은 현재 TwentyOz 호환 profile에서 capability 확인 전 차단되며, 사용자 파일은 고르거나
전송하지 않았다. Fold6 cover에서는 compact split을 끈 상태를 유지하고 expanded split만 켰다.
실제 펼침·회전·중앙 공백 입력 검증은 물리 자세 전환 gate로 남긴다.

### 6.8 한국어 다음 단어·GIF 밈 품질·로컬 OCR checkpoint 계약

| ID | 구현 계약 | 현재 자동 증거 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `KO-07` | 실제 공백 경계 뒤에만 project-curated 로컬 다음 어절을 미선택 후보로 표시한다. 기존 완성·개인 단어·한자 후보와 mode를 섞지 않고, 선택 시 현재 후보 membership을 다시 확인한 뒤 1회 확정한다. | 64KiB·500행 fail-closed parser, 중복·limit·민감 editor policy, A35·Z Fold6 arm64 native test | 두 기기에서 직접 입력·자동완성 선택 뒤 공백·후보 선택·취소·민감 editor UI 검증 |
| `GIF-02` | KLIPY exact query의 성공한 첫 page가 비었을 때만 한국어 반응 intent를 최대 2개 시도한다. 원 결과와 합치지 않고 recovery page를 단일 page로 종료한다. Noto는 로컬 tag 가중치와 emoji family 다양성을 적용한다. GIPHY에는 query 보정·재정렬·필터를 적용하지 않는다. | GIF 17 suites·64 tests, provider isolation·stable dedupe·safe fallback·Noto family diversity | KLIPY production access·branding review, 실제 희소 query의 두 기기 grid 품질 matrix |
| `MM-01` | JPEG·PNG·WebP content URI만 system document picker로 1회 받는다. 최대 15MiB·100MP source를 4MP 이하로 축소하고 Tesseract 한국어 모델로 기기 안에서 인식한다. 결과는 기본 미선택 줄별 preview 뒤 선택분만 1회 입력한다. | OCR contract·image bound·고정 commit/크기/SHA-256 model install tests 9개와 Kotlin compile | A35·Z Fold6 실제 한국어 인쇄물·회전 이미지 정확도, picker 취소·focus 이동, F-Droid용 native AAR 재현성 또는 source build 전환 |

이 checkpoint의 통합 자동 검증은 app JVM 56 suites·236 tests, failure/error/skipped 0과 arm64
app·Hangul plugin assemble을 통과했다. lint에서 새로 발견한 Android 6 `contentLengthLong` 1건은
API guard로 수정했고, 재실행 결과 이번 OCR·GIF·toolbar 변경 파일의 lint 항목은 0건이다. 전체 lint는
선행 부채 286 errors·67 warnings 때문에 계속 실패하며 첫 항목은 기존 `fragment_setup.xml`의
`android:tint`다.

OCR engine과 `tessdata_fast` 한국어 모델은 Apache-2.0 계열의 공개 소스다. 모델은 사용자가 명시한
다운로드 동작에서만 고정 HTTPS URL로 받고, 응답 크기와 SHA-256을 모두 검증해
`noBackupFilesDir/ocr/tesseract`에 둔다. 선택한 원본 이미지·content URI·인식 결과는 prefs, cache,
backup, 일반 log에 저장하지 않으며 Bitmap은 작업 종료 시 지우고 recycle한다. 모델 설치 뒤 OCR은
완전 offline mode에서도 동작하지만 password·sensitive·`NoSpellCheck` editor에서는 picker 전 차단한다.

### 6.9 반응형 툴바·AI 설정 CTA checkpoint 계약

| ID | 구현 계약 | 자동 증거 | 남은 완료 게이트 |
| --- | --- | --- | --- |
| `UX-03` | 별도 중첩 메뉴를 추가하지 않는다. 기존 툴바 열기 상태에서 실제 가용 폭이 `12 × 48dp`보다 좁으면 6×2행·96dp, 충분하면 12×1행·48dp로 즉시 전환한다. Candidate·Clipboard·NumberRow·InlineSuggestion·Title은 항상 1행이며 action의 순서·위치·활성 정책을 유지한다. | `ToolbarLayoutPolicyTest`, Flexbox shrink 금지, 높이 상태 전환과 arm64 build, A35·Z Fold6 cover 6×2와 두 기기 `?123` PASS | Fold unfolded 12×1 자동 복귀 |
| `AI-08` | AI 글쓰기·정밀 받아쓰기·회의 전사의 미연결 또는 OAuth 만료 상태만 `설정하기`를 제공한다. 버튼은 `SettingsRoute.PrivacyAi`로 직접 이동한다. private editor, offline mode, app policy 차단은 credential 저장소를 열거나 CTA를 노출하지 않는다. | 공통 gate 우선순위 테스트와 AI·voice JVM test, A35·Z Fold6 미연결 안내와 `개인정보·AI` 직행 PASS | 없음 |

실기기 캡처 전 `dumpsys input_method`의 `mCurId`가 debug Fcitx service인지 확인한다. 삼성
HoneyBoard가 활성인 화면을 이 앱의 숫자판이나 툴바 증거로 사용하지 않는다.

2026-07-26 checkpoint에서 A35 `SM-A356N`과 Z Fold6 `SM-F956N`에 동일 arm64 app/plugin을
설치하고 매 캡처 전 debug Fcitx `mCurId`를 확인했다. 두 cover 폭에서 툴바 12개 action이 6×2로
렌더됐고, 두 기기의 `?123`은 Number로 전환됐다. 두 기기 모두 AI 글쓰기에서 일반 사용자용 미연결
안내와 `설정하기` 버튼이 표시됐으며 버튼은 `개인정보·AI` route와 미연결 summary로 직접 이동했다.

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
| 일반 text editor | `PASS` | Chrome은 `contentMimeTypes`에 GIF 미지원; 첨부 disabled 설명 표시, Noto canonical URL을 focused editor에 정확히 1회 입력 |
| GIF 지원 editor | `PASS` | Discord `contentMimeTypes=[image/*]`; 1,176,505-byte animated GIF를 `commitContent`로 전달해 전송 전 compose preview가 실제 표시됨 |
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

2026-07-26 `VOICE-01` 1차 구현은 현재 앱의 request-response transport 경계를 지키기 위해
`gpt-4o-transcribe` `/audio/transcriptions` 구간 전사로 제한한다. UI 명칭은 `AI 정밀 받아쓰기`이며
실시간·부분 전사라고 표시하지 않는다. 16 kHz mono PCM은 최대 30초만 메모리에 보관해 WAV로 만들고,
전사 요청 종료·취소·window detach 시 byte array를 지운다. 파일·cache·SharedPreferences·backup·log에
음성이나 전사문을 남기지 않는다. `RECORD_AUDIO`는 IME service가 직접 요청하지 않고 `exported=false`
투명 Activity에서만 요청한다. 현재 IME는 Android background activity start 예외에 해당하지만 실기기
permission dialog와 focus 복귀는 별도 gate다.

진짜 `VOICE-01` 완료 조건은 OpenAI Realtime transcription session에서 24 kHz mono PCM,
`gpt-realtime-whisper`, `language: ko`, `input_audio_buffer.append/commit`, transcript delta/completed를
`item_id`별로 조정하는 것이다. 표준 BYOK를 WebSocket에 장기 노출하지 않도록 backend ephemeral token
발급 경로도 함께 필요하다. 공식 기준은 [Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription)과
[Speech to text](https://developers.openai.com/api/docs/guides/speech-to-text)다.

### 8.2 API key와 token

- 일반 배포 기본 경로는 backend가 standard provider key를 보관한다.
- Realtime client에는 backend가 발급한 짧은 수명의 ephemeral token을 사용한다.
- 개인용 advanced BYOK를 제공할 경우 Android Keystore로 암호화하고 ciphertext는
  `noBackupFilesDir` 아래에 저장한다.
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

현재 구현은 `AI 정밀 받아쓰기`인 `VOICE-02`다. `VOICE-01` 실시간이라고 표시하지 않는다. 녹음은
최대 30초·16 kHz mono PCM이며 메모리에서 WAV multipart로 바꿔 전송한 뒤 모든 byte array를 지운다.
음성·전사문을 file, cache, prefs, backup, log에 저장하지 않는다. `RECORD_AUDIO` 권한은
`exported=false` 투명 permission Activity에서만 요청한다. private/no-personalized/offline/app AI 차단,
editor identity 변경, 취소에서는 전송 또는 입력을 fail-closed한다.

`VOICE-01` realtime delta는 `gpt-realtime-whisper` WebSocket, item별 delta/completed 조정, backend
ephemeral token 발급이 함께 준비될 때 별도로 구현한다. 장기 표준 API key를 실시간 client에 내장하는
방식은 허용하지 않는다.

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
| OAuth public client | `CODE_DONE` | AppAuth external browser, Authorization Code, state, PKCE S256, 고정 redirect, client secret 없음, 암호화 AuthState·refresh·revoke 구현 |
| OAuth request contract | `PASS` | API key/OAuth 혼합·HTTP endpoint 거부, `.ts.net` HTTPS profile, callback profile 불일치 차단, applicationId redirect, Bearer 1회 사용, 401 무재시도·명시적 재로그인 unit test |
| OAuth 통합 build/test | `PASS` | `:app:testDebugUnitTest` 56 suites·240 tests failure/error/skipped 0, `:app:assembleDebug -PbuildABI=arm64-v8a`, debug merged manifest redirect scheme 일치 |
| OAuth live provider | `GATE` | 실제 compatible endpoint의 client 등록·redirect·scope와 A35·Z Fold6 login/refresh/revoke를 검증해야 함 |
| Responses client | `PASS` | `/responses`, `store=false`, JSON suggestion parse, redirect 금지, prompt/result 비로그와 sanitized error 구현 |
| text/action test | `PASS` | AI 5 suites·12 tests, failure 0. action prompt, provider validation, selection/문단 source, 응답 parse, usage 원문 비저장을 검증 |
| Privacy dashboard | `PASS` | A35에서 현재 provider, 전송 원칙, 기능별 집계 usage, usage 삭제와 GIF cache 삭제 UI 렌더 확인 |
| 명시적 network action | `PASS` | AI window를 열 때는 원문 preview만 표시하고 `문장 3개`를 누른 뒤에만 Responses request 실행 |
| A35 생성 결과 | `PASS` | `meeting 30 minutes late polite` 선택 범위로 한국어 지각 안내 초안 생성, 결과 card와 공급자 표시 확인 |
| 교체 exactly-once·undo | `PASS` | Chrome URL editor에서 결과를 한 번 교체한 뒤 `실행 취소`로 원문이 정확히 복원됨 |
| Z Fold6 UI | `PASS` | cover 화면에서 AI toolbar, 원문 preview와 전체 action group이 잘림 없이 표시됨 |
| 두 기기 최종 설치 | `PASS` | A35·Z Fold6에 동일 arm64 app/plugin 재설치, debug Fcitx IME 재선택 |
| AI-01 diff·부분 적용 | `PASS` | bounded LCS·대형 입력 fallback·Unicode code-point 범위·stale source/미검토 target 거부와 선택 checkbox UI, 7개 신규 테스트 |
| AI-04 명시적 intake | `PASS` | Sharesheet text/plain·clipboard 행·4,000자·5분 TTL·private/offline/app gate·stale editor·exactly-once 테스트 |
| action별 품질 matrix | `GATE` | 말투 6종, 답장, 번역 4개, 3후보 보장을 provider/model별로 실제 기기 검증해야 함 |

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
3. `KO-03` 동적 빠른 문구. (`IN_PROGRESS`: Z Fold6 최종 설치 gate만 남음)
4. `KO-03A` 자동 스니펫 확장. (`IN_PROGRESS`: 사용자 우선순위로 구현)
5. `KO-09` 한글 어절 자동완성. (`IN_PROGRESS`: Z Fold6 최종 설치 gate만 남음)
6. `KO-04` 앱별 profile과 network policy. (`NEXT`)

### 단계 3 — AI 기반과 text 기능

1. `AI-00`, `AI-06`, `SEC-02` 공급자·Keystore·privacy/usage 기반. (`DONE`)
2. `AI-07` endpoint OAuth public-client flow와 token lifecycle. (`IN_PROGRESS`: 코드 완료, 실제 IdP·두 기기 gate)
3. `AI-08` 일반 사용자용 AI 연결·재로그인 CTA. (`DONE`: 코드·테스트·두 기기 미연결 안내와 설정 직행 통과)
4. `SEC-03` offline network gate. (`IN_PROGRESS`: 실제 기기 zero-request gate)
5. `AI-01` 맞춤법·띄어쓰기. (`IN_PROGRESS`: diff·부분 적용 코드 완료, 두 기기 UX 검증)
6. `AI-02` 말투 변환. (`IN_PROGRESS`: action별 품질 matrix)
7. `AI-03` 문장 생성. (`IN_PROGRESS`: A35 live 통과, 3후보 보장)
8. `AI-05` 번역. (`IN_PROGRESS`: 언어별 matrix)
9. `AI-04` 답장 초안. (`IN_PROGRESS`: clipboard/share intake 코드 완료, 두 기기 UX·품질 gate)

### 단계 4 — 음성

1. push-to-talk audio capture와 permission UX. (`IN_PROGRESS`: 코드 완료, 두 기기 permission/focus gate)
2. `VOICE-02` 고정밀 구간 전사. (`IN_PROGRESS`: in-memory WAV와 final preview 완료, live 품질 gate)
3. `VOICE-01` realtime partial transcript. (`GATE`: Realtime WebSocket와 ephemeral token backend)
4. `VOICE-03` diarization과 회의 UI. (`IN_PROGRESS`: 코드·테스트 완료, 표준 OpenAI·실기기 gate)

### 단계 5 — 개인화·대화면·장기 기능

1. `KO-05` 한자·사전과 `KO-06` 개인 단어장. (`IN_PROGRESS`: 개인 단어장 코드 완료, 두 기기 gate)
2. `UX-01` smart clipboard action. (`IN_PROGRESS`: 코드 완료, 두 기기 gate)
3. `SEC-01` 민감 문구 금고. (`IN_PROGRESS`: 코드 완료, 생체 인증 실기기 gate)
4. `UX-02` Fold·tablet 분할 layout. (`IN_PROGRESS`: 코드·테스트 완료, Fold6 펼침·회전 gate)
5. `KO-07` 조사 MVP와 `KO-08` 감정 후보. (`IN_PROGRESS`: 코드 완료, 두 기기 gate)
6. `KO-07` 다음 어절과 `MM-01` 로컬 OCR. (`IN_PROGRESS`: 코드·자동 테스트 완료, 두 기기 UX·정확도와 OCR native 배포 gate)
7. `UX-03` compact 2행·wide 1행 반응형 툴바. (`IN_PROGRESS`: A35·Fold cover 6×2와 숫자판 통과, unfolded 12×1 gate)

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
| 2026-07-26 | 실사용 실패를 근거로 GIF 검색을 인라인 한글·영문 자판으로 교체하고 기본 provider를 Animated Noto Emoji로 변경. Commons는 별도 공개 미디어 tab backlog로 이동 |
| 2026-07-26 | Animated Noto의 밈·리액션 부족을 근거로 KLIPY를 실사용 catalog로 구현. key 없는 공개 build는 Noto fallback을 유지하고 production key·partner 승인은 별도 gate로 둠 |
| 2026-07-26 | KLIPY key의 Gradle·BuildConfig 주입을 제거하고 Android Keystore·noBackup 전용 저장소와 명시적 설정 UX를 유일한 key 경로로 확정 |
| 2026-07-26 | 앱별 profile은 package별 layout·theme·toolbar·transport·network·AI 정책만 저장하며 private editor와 전역 offline hard deny가 항상 우선 |
| 2026-07-26 | 한자 음훈은 새 네트워크 사전이 아니라 이미 배포되는 libhangul `hanja.txt` comment를 먼저 정확히 노출하고 국어사전 정의는 별도 source/license gate로 분리 |
| 2026-07-26 | GIF attach 실패 뒤 link 자동 삽입 금지 |
| 2026-07-26 | GIF 품질은 KLIPY pagination·한국어 밈 chip·safe gate로 보강하고 GIPHY는 branding·tracking·production review를 갖춘 별도 provider로만 허용 |
| 2026-07-26 | 답장 intake는 Sharesheet/명시적 clipboard만 허용하고 화면 임의 읽기와 영구 원문 저장을 금지 |
| 2026-07-26 | 민감 문구는 매 작업 인증 결합 cipher, package allowlist, 60초 메모리 세션을 모두 만족할 때만 노출 |
| 2026-07-26 | 구간 전사는 `AI 정밀 받아쓰기`로 명명하고 Realtime delta는 ephemeral-token backend가 준비될 때까지 별도 gate로 유지 |
| 2026-07-26 | Fold split은 compact/expanded와 세로/가로 profile을 독립 저장하고 불명확한 posture에서는 일반 layout을 유지 |
| 2026-07-26 | GIPHY는 production review key와 별도 provider 선택이 있을 때만 활성화하며 media-copy 승인 전에는 link-only로 제한 |
| 2026-07-26 | 회의 화자 분리는 system picker의 명시 선택 audio만 stream하고 segment review 없이 자동 입력하거나 요약하지 않음 |
| 2026-07-26 | 한국어 다음 단어는 실제 공백 경계 뒤의 project-curated 로컬 후보만 기본 미선택으로 표시하고 사용자 입력을 학습·저장하지 않음 |
| 2026-07-26 | 툴바는 별도 중첩 메뉴 없이 실제 가용 폭으로 1행·2행을 자동 결정하고 모든 도구 위치와 48dp 터치 영역을 유지 |
| 2026-07-26 | 일반 사용자용 AI 미연결·재로그인 상태에만 `설정하기`를 제공하며 private/offline/policy 차단은 CTA 없이 fail-closed |
| 2026-07-26 | GIPHY exact query 계약은 보존하고 한국 밈 query fallback은 성공한 empty KLIPY 첫 page와 로컬 Noto에만 적용 |
| 2026-07-26 | OCR은 proprietary ML SDK 대신 Apache-2.0 Tesseract 계열과 pinned 한국어 fast model을 사용하며 원본·결과를 저장하지 않음 |
| 2026-07-26 | 표준 API key의 일반 mobile direct 저장은 기본 경로로 사용하지 않음 |
| 2026-07-26 | AI text action은 선택/현재 문단 preview 후에만 network를 호출하고 결과 교체·추가·undo를 명시적 동작으로 제한 |
| 2026-07-26 | 동적 빠른 문구는 기존 `.mb` 형식을 유지하고, 명시적 미리보기 뒤 정확히 한 번 삽입 |
