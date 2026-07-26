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
현재 활성 구현 마일스톤: `AI text MVP·GIF-02 실사용 공급자 checkpoint`

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
| `KO-BASE-08` | 숫자·기호 전환 상태 복구 | `IN_PROGRESS` | 레거시 상태 migration 테스트와 A35 반복 왕복 통과, Fold6 설치 검증 대기 |
| `KO-01` | 한/영 오타 즉시 복구 | `DONE` | 5 JVM 테스트와 A35 Discord 교체·실행 취소 |
| `KO-02` | 초성 통합 검색 | `DONE` | 6 JVM 테스트와 A35 `ㄱㅅ` 검색·1회 삽입 |
| `KO-03` | 동적 빠른 문구 | `IN_PROGRESS` | 7 JVM 테스트와 A35 날짜·profile·clipboard 미리보기·1회 삽입 |
| `KO-03A` | 자동 스니펫 확장 | `IN_PROGRESS` | `:주소1`·`:이메일` 별칭과 사용자 `:` 상용구의 경계키 확장 구현 중 |
| `KO-09` | 한글 어절 자동완성 | `IN_PROGRESS` | 로컬 빈도 사전, 명시적 후보 선택, buffered 입력 호환 구현 중 |

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
| 최종 A35 설치 | `PASS` | `SM-A356N / RFCX60GBL3D`, app/plugin `0.1.2-92-g0c3b30cf` 설치 및 debug Fcitx IME 재선택 |
| Z Fold6 전달·설치 | `GATE` | 동일 app/plugin APK를 Taildrop으로 전달 완료; 기기에서 설치 후 한글·천지인 왕복 확인 필요 |

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
| `KO-04` | 앱별 키보드 profile | `NEXT` | package별 layout, theme, transport, toolbar, AI 정책 | M |
| `KO-05` | 한자·국어사전 후보 | `BACKLOG` | 한글 단어의 한자, 음훈, 동음이의어를 로컬 후보로 표시 | M |
| `KO-06` | 개인 단어장 | `BACKLOG` | 이름·회사명·전문용어를 opt-in으로 로컬 우선 후보에 반영 | L |
| `KO-07` | 한국어 조사·문맥 후보 | `BACKLOG` | 조사와 다음 어절 추천, 자동 확정은 기본 off | L |
| `KO-08` | 한국식 감정표현 추천 | `BACKLOG` | emoji·kaomoji·ㅋㅋ/ㅎㅎ 후보, provider와 분리 | M |
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
| `AI-01` | 한국어 맞춤법·띄어쓰기·조사 교정 | `IN_PROGRESS` | preview·전체 교체·undo 구현, diff·부분 적용 gate 남음 | M |
| `AI-02` | 존댓말·말투 변환 | `IN_PROGRESS` | 존댓말·카톡·업무·거절·사과·고객응대 action 구현, action별 실기기 matrix 남음 | M |
| `AI-03` | 빠른 문장 생성 | `IN_PROGRESS` | 의도 기반 후보·명시적 교체/추가 구현과 A35 live 통과, 3개 후보 품질 gate 남음 | M |
| `AI-04` | 답장 초안 | `IN_PROGRESS` | 선택·현재 문단 범위와 답장 후보 구현, clipboard/share intake 남음 | M |
| `AI-05` | 키보드 번역 | `IN_PROGRESS` | 한↔영·일·중 action과 preview 구현, 언어별 실기기 matrix 남음 | M |
| `AI-06` | AI provider profile | `DONE` | OpenAI·OpenAI-compatible endpoint, model tier, 암호화 BYOK 분리 | M |

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
| `SEC-02` | Privacy dashboard | `DONE` | 기능별 전송 범위, provider, 집계 사용량, 즉시 삭제 | M |
| `SEC-03` | 완전 offline mode | `IN_PROGRESS` | AI·GIF toolbar와 network gate 통합, 실제 기기 zero-request gate 남음 | S |

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
| GIPHY | `GIF-02` 대안 | 충분한 catalog지만 API key·Powered by GIPHY·결과 비혼합 계약과 production 승인이 필요 |

Commons tab을 구현할 때는 기존 MediaWiki MIME·license allowlist·restriction 필터를 그대로 유지하고,
다른 provider 결과와 같은 grid에 섞지 않는다.

### 7.9 KLIPY 실사용 catalog 공급자

`KlipyGifProvider`는 `GIF-02`의 실사용 공급자로 구현됐다. 2026-07-26 라이브 probe와 A35 검색에서
`축하`, `웃겨`, `고마워` 한국어 query가 실제 GIF rendition과 밈·인물·캐릭터 반응 결과를 반환했다.

- API key는 constructor/BuildConfig 주입만 허용하며 source, log, error, metadata에 넣지 않는다.
- 공개 release 기본값은 빈 key다. key가 없으면 공개 Animated Noto Emoji fallback을 사용한다.
- `/gifs/trending`과 `/gifs/search` 결과를 별도 KLIPY provider model로 parse하고 공개 공급자 결과와
  같은 grid response 또는 disk directory에 혼합하지 않는다.
- canonical `klipy.com/gifs/{slug}`와 downloadable GIF rendition을 분리한다.
- 모든 카드에 `Powered by KLIPY`와 KLIPY API Terms attribution을 표시한다.
- query는 UTF-8로 encode하고 `locale=ko`를 지정하며, API 응답·media·canonical URL 모두 HTTPS만 허용한다.
- test key는 개발 검증에만 사용한다. 배포 전 production key, attribution 검수, partner 조건 승인을
  `GIF-02-PROD` owner gate로 처리한다.

GIPHY는 충분한 catalog를 제공하지만 KLIPY 결과와 혼합하지 않는 별도 optional provider 후보로만
유지한다. 현재 MVP에는 넣지 않는다.

Tenor API는 2026-06-30 종료 계약 때문에 새로운 기본 공급자로 사용하지 않는다. 공급자 상태는
구현 시점에 공식 문서로 다시 검증한다.

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
| KLIPY production 승인 | `GATE` | source에 key를 넣지 않음. 공개 배포 전 전용 production key·partner 약관 승인 필요 |

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

### 8.4 AI text MVP 구현·검증 증거 (2026-07-26)

| 범위 | 결과 | 증거 |
| --- | --- | --- |
| 공급자 profile | `PASS` | OpenAI·OpenAI-compatible HTTPS endpoint, Fast/Balanced/Quality model tier와 loopback 개발 예외를 pure model로 검증 |
| API key vault | `PASS` | Android Keystore AES-GCM과 `noBackupFilesDir/ai/provider.bin`; SharedPreferences·user ZIP·log에 key를 저장하지 않음 |
| Responses client | `PASS` | `/responses`, `store=false`, JSON suggestion parse, redirect 금지, prompt/result 비로그와 sanitized error 구현 |
| text/action test | `PASS` | AI 5 suites·12 tests, failure 0. action prompt, provider validation, selection/문단 source, 응답 parse, usage 원문 비저장을 검증 |
| Privacy dashboard | `PASS` | A35에서 현재 provider, 전송 원칙, 기능별 집계 usage, usage 삭제와 GIF cache 삭제 UI 렌더 확인 |
| 명시적 network action | `PASS` | AI window를 열 때는 원문 preview만 표시하고 `문장 3개`를 누른 뒤에만 Responses request 실행 |
| A35 생성 결과 | `PASS` | `meeting 30 minutes late polite` 선택 범위로 한국어 지각 안내 초안 생성, 결과 card와 공급자 표시 확인 |
| 교체 exactly-once·undo | `PASS` | Chrome URL editor에서 결과를 한 번 교체한 뒤 `실행 취소`로 원문이 정확히 복원됨 |
| Z Fold6 UI | `PASS` | cover 화면에서 AI toolbar, 원문 preview와 전체 action group이 잘림 없이 표시됨 |
| 두 기기 최종 설치 | `PASS` | A35·Z Fold6에 동일 arm64 app/plugin 재설치, debug Fcitx IME 재선택 |
| AI-01 diff·부분 적용 | `GATE` | 전체 교체·뒤에 붙이기·undo는 동작하나 맞춤법 diff와 부분 적용 UI가 남음 |
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
2. `SEC-03` offline network gate. (`IN_PROGRESS`: 실제 기기 zero-request gate)
3. `AI-01` 맞춤법·띄어쓰기. (`IN_PROGRESS`: diff·부분 적용)
4. `AI-02` 말투 변환. (`IN_PROGRESS`: action별 품질 matrix)
5. `AI-03` 문장 생성. (`IN_PROGRESS`: A35 live 통과, 3후보 보장)
6. `AI-05` 번역. (`IN_PROGRESS`: 언어별 matrix)
7. `AI-04` 답장 초안. (`IN_PROGRESS`: clipboard/share intake)

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
| 2026-07-26 | 실사용 실패를 근거로 GIF 검색을 인라인 한글·영문 자판으로 교체하고 기본 provider를 Animated Noto Emoji로 변경. Commons는 별도 공개 미디어 tab backlog로 이동 |
| 2026-07-26 | Animated Noto의 밈·리액션 부족을 근거로 KLIPY를 실사용 catalog로 구현. key 없는 공개 build는 Noto fallback을 유지하고 production key·partner 승인은 별도 gate로 둠 |
| 2026-07-26 | GIF attach 실패 뒤 link 자동 삽입 금지 |
| 2026-07-26 | 표준 API key의 일반 mobile direct 저장은 기본 경로로 사용하지 않음 |
| 2026-07-26 | AI text action은 선택/현재 문단 preview 후에만 network를 호출하고 결과 교체·추가·undo를 명시적 동작으로 제한 |
| 2026-07-26 | 동적 빠른 문구는 기존 `.mb` 형식을 유지하고, 명시적 미리보기 뒤 정확히 한 번 삽입 |
