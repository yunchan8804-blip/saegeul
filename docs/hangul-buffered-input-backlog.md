# 한글 버퍼 호환 입력 백로그

## 1. 문서 목적

이 문서는 한글 키캡 표시와 한글 버퍼 호환 입력의 현재 구현을 기준선으로 고정하고, 실제 사용자 배포까지 남은 작업을 우선순위별로 관리하기 위한 실행 백로그다.

이 문서가 다루는 핵심 목표는 다음 두 가지다.

1. Hangul 플러그인의 현대 두벌식 입력을 사용할 때 화면 키캡에 정확한 한글 자모를 표시한다.
2. Android 한글 조합 span을 제대로 처리하지 못하는 앱에서 Fcitx 내부로 조합을 격리하고, 완성된 문자열을 한 번에 전달한다.

이 기능은 모든 앱에서 입력을 보장하는 범용 접근성 자동화가 아니다. Android 표준 붙여넣기, Ctrl+V 키 전달, InputConnection.commitText 중 대상 앱이 실제로 지원하는 경로를 명시적으로 선택하는 호환 모드다.

관련 문서: [기능·빌드 개요](hangul-buffered-input.md), [상세 인수인계](hangul-buffered-input-handoff.md)

## 2. 기준선

### 2.1 소스 기준

| 항목 | 값 |
| --- | --- |
| 저장소 | D:\workspace\fcitx5-android |
| 브랜치 | feat/hangul-buffered-input |
| 기준 커밋 | 5338d80ac247a21b6831626d4b3ae09710f1c65b |
| 기준 커밋 제목 | Add buffered Hangul compatibility mode |
| 기준 커밋 작성자 | Yun Chan |
| 작업 트리 | 기준선 확인 시 clean |
| 주요 설계 문서 | docs/hangul-buffered-input.md |

Windows clone의 Git symlink 설정은 현재 true이며, 대표적으로 build-logic/gradle/wrapper/gradle-wrapper.jar가 실제 심볼릭 링크로 복구된 상태다.

### 2.2 구현된 범위

- Hangul addon이면서 언어 코드가 ko로 시작하는 입력 방법만 버퍼 모드 대상이 된다.
- 기능은 기본값 false인 실험 설정으로 제공된다.
- 현대 두벌식 Dubeolsik의 일반 자모와 one-shot Shift 자모를 화면 키캡과 popup preview에 표시한다.
- 키캡만 한글로 바꾸고 실제 엔진 액션은 Latin QWERTY keysym을 유지한다.
- Caps Lock은 현대 두벌식 일반 자모를 표시하고 one-shot Shift만 쌍자음 및 ㅒ, ㅖ 표시로 취급한다.
- 지원하지 않는 Hangul 레이아웃은 추정 매핑을 적용하지 않고 Latin 키캡으로 안전하게 돌아간다.
- 버퍼 모드가 활성화되면 Fcitx capability에서 Preedit만 제거해 대상 앱에 setComposingText 경로가 노출되지 않게 한다.
- Fcitx CommitString은 앱으로 즉시 전달하지 않고 BufferedInputController의 메모리 버퍼에 축적한다.
- 내부 표시에는 확정 prefix와 현재 Hangul engine preedit를 결합한다.
- Backspace는 engine preedit가 비었을 때 버퍼의 마지막 Unicode code point를 삭제한다.
- Space, 숫자, 문장부호 같은 전달 가능한 구분자는 현재 구간에 포함한 뒤 제출한다.
- Return 및 좌우 화살표는 버퍼를 먼저 제출한 뒤 원래 editor action을 수행한다.
- 전달 방식은 System paste, Ctrl+V, Direct commit 중 사용자가 명시적으로 선택한다.
- System paste 및 Ctrl+V는 임시 clipboard entry를 만들고, Fcitx 자체 clipboard history에는 이 entry가 저장되지 않게 표시한다.
- Android가 지원하는 경우 임시 clipboard entry를 sensitive로 표시한다.
- Password 또는 Sensitive capability가 있는 editor는 설정과 관계없이 Direct commit을 사용한다.
- paste dispatch의 true는 실제 대상 앱 입력 성공이 아니라 원격 요청 수락으로만 해석한다.
- 성공 여부가 모호하므로 paste 실패 뒤 commitText를 자동 실행하는 fallback은 구현하지 않았다.
- 입력 방법 전환, input view 종료, input 종료 시 제출 및 Hangul engine reset을 시도한다.
- 대상 editor의 예상하지 못한 selection 이동이 감지되면 잘못된 위치로 보내거나 다른 editor로 유출하는 대신 미전송 버퍼를 폐기한다.
- 물리 키의 modifier shortcut은 pending Hangul을 Direct commit으로 먼저 정리한 뒤 원래 shortcut을 전달한다.

### 2.3 현재 도구 기준

검증에 사용한 기준 도구는 다음과 같다.

| 도구 | 기준 |
| --- | --- |
| JDK | Temurin 17 |
| Android SDK Platform | 36 |
| Android Build Tools | 36.1.0 |
| Android NDK | 28.0.13004108 |
| CMake | 3.31.6 |
| 호스트 native 도구 | MSYS2 UCRT64 Gettext, ECM, pkgconf |
| 개발 ABI | arm64-v8a |

### 2.4 검증 결과

| 검증 | 결과 | 증거 및 해석 |
| --- | --- | --- |
| 기능 전용 JVM 테스트 | PASS, 총 10개 | CapabilityFlagsTest, BufferedHangulModeTest, BufferedInputControllerTest, HangulKeyLegendsTest |
| app debug APK 조립 | PASS | :app:assembleDebug -PbuildABI=arm64-v8a |
| Hangul plugin debug APK 조립 | PASS | :plugin:hangul:assembleDebug -PbuildABI=arm64-v8a, native fcitx5-hangul 포함 |
| Hangul plugin lint | PASS | :plugin:hangul:lintDebug -PbuildABI=arm64-v8a |
| 전체 app JVM 테스트 | 15개 중 14 PASS, 1 FAIL | 기존 ThemeSerializationTest.version2가 theme 2.0을 기대하지만 현재 serializer version은 2.1인 stale test |
| app lint | FAIL | upstream baseline 부재로 266 errors, 49 warnings. 이번 변경 파일과 리소스에는 finding 0 |
| 실제 기기 키캡 | PASS | Samsung SM-F956N, Android 16, API 36에서 현대 두벌식 일반 및 one-shot Shift 표시 확인 |
| 실제 기기 내부 버퍼 | PASS | Android Settings 검색 입력에서 한글 조합 중 target editor가 바뀌지 않고 Fcitx 내부 panel만 갱신됨 |
| 실제 기기 단일 paste | PASS | 한글 입력 뒤 Space로 한글과 공백이 정확히 한 번 삽입되고 내부 panel이 비워짐 |
| 실제 기기 hide/show lifecycle | PASS | hide 시 한글이 정확히 한 번 제출되고, 같은 editor를 다시 열어 다음 테스트 구간이 target에 미리 노출되지 않다가 Space에서 한 번 제출됨 |

plugin assembly와 plugin lint를 같은 Gradle invocation에 넣으면 현재 task graph의 implicit-dependency validation 문제가 발생한다. 위 PASS 결과는 두 명령을 분리해 얻은 것이다.

## 3. 우선순위 정의

| 우선순위 | 의미 | 종료 기준 |
| --- | --- | --- |
| P0 | 데이터 유실, 중복 입력, 개인정보 노출, 원래 문제 앱 미검증, 배포 불가처럼 알파 사용 전에 반드시 해결할 항목 | 모든 P0 완료 전 일반 사용자 배포 금지 |
| P1 | 반복 사용에 필요한 UX, 자동 회귀 검증, 앱별 운용, 유지보수 구조 | 제한된 실사용 그룹 확대 전 완료 |
| P2 | 입력 방식 확대, 플랫폼 조합 확대, 품질과 접근성 보강 | 기능 안정화 이후 순차 적용 |
| P3 | 범용화, upstream 장기 구조, 확장 생태계 | 별도 설계 승인 후 추진 |

## 4. 변경 불가 원칙

다음 원칙은 이후 구현에서도 회귀하면 안 된다.

1. 버퍼 모드 중 대상 editor에 Hangul composing span을 보내지 않는다.
2. 제출된 한 구간은 성공 경로에서 정확히 한 번만 전달한다.
3. dispatch acknowledgement를 실제 editor 반영 성공으로 과장하지 않는다.
4. 결과를 확인할 수 없는 paste 뒤 commitText 자동 fallback을 실행하지 않는다.
5. Password 및 Sensitive editor에는 clipboard transport를 사용하지 않는다.
6. 미전송 텍스트를 다른 editor나 다른 앱으로 넘기지 않는다.
7. 사용자가 볼 수 있는 key legend와 engine에 전달하는 keysym을 분리한다.
8. 모르는 Hangul layout을 현대 두벌식으로 추정하지 않는다.
9. 실패를 성공처럼 표시하지 않는다.
10. 로그, 테스트 보고서, 진단 정보에 실제 사용자가 입력한 문자열을 남기지 않는다.

---

## P0 — 알파 사용 전 차단 항목

### P0-01. 원래 문제 앱에서 전송 방식별 최종 인수 검증

**배경**

이 기능의 존재 이유는 일반 Android EditText가 아니라 Hangul composing span을 잘못 처리하는 실제 문제 앱을 우회하는 것이다. 표준 editor에서 성공한 결과만으로 원래 문제를 해결했다고 결론 내릴 수 없다.

**현재 상태**

- Samsung SM-F956N의 Android Settings 검색 editor에서 System paste의 내부 버퍼 및 단일 삽입 경로는 확인했다.
- 알려진 문제 앱, 원격 데스크톱, raw-key 입력 표면에서는 아직 직접 검증하지 않았다.
- System paste, Ctrl+V, Direct commit 세 방식 중 어떤 방식이 원래 대상에서 실제 성공하는지 결정되지 않았다.

**제안**

- 원래 문제 앱의 동일한 입력 surface에서 세 transport를 각각 독립적으로 시험한다.
- 최소 입력 묶음은 현대 두벌식 일반 입력, one-shot Shift 쌍자음, 숫자, 문장부호, Space, Return, Backspace, 좌우 이동, 긴 문장, emoji 포함 문자열로 구성한다.
- 각 성공 후보는 동일 조건에서 최소 10회 반복해 누락, 중복, 순서 역전, 잘못된 cursor 위치가 없는지 확인한다.
- target 앱이 어느 경로도 지원하지 않으면 지원 실패를 명확한 결과로 기록하고, 키보드만으로 해결 가능한 범위가 아님을 문서화한다.
- 성공 transport와 실패 transport를 앱 버전, Android 버전, 입력 surface 종류와 함께 호환성 표에 기록한다.

**완료 조건**

- 원래 문제 surface에서 적어도 한 transport가 한글 구간을 정확히 한 번 전달하거나, 세 transport 모두 불가능하다는 재현 가능한 결론이 있다.
- 성공 transport는 10회 반복에서 중복 0회, 누락 0회, 문자 손상 0회다.
- 실패 transport가 자동 fallback으로 중복을 만들지 않는다.
- 선택해야 할 사용자 설정이 명확히 문서화된다.
- 검증 결과가 docs/hangul-buffered-input.md의 device matrix에 반영된다.

**의존성/위험**

- 대상 앱 업데이트가 입력 구현을 바꿀 수 있다.
- 원격 앱은 Android editor가 아닌 영상 또는 raw-key surface일 수 있다.
- performContextMenuAction의 true는 실제 입력 성공을 증명하지 않으므로 화면 결과를 직접 확인해야 한다.
- 테스트용 문장은 민감 정보가 아닌 고정 샘플만 사용해야 한다.

**검증 증거**

- 현재 증거는 Android Settings 검색 editor의 System paste PASS뿐이다.
- Ctrl+V와 Direct commit의 실제 문제 앱 증거는 없다.
- 따라서 이 항목은 미완료다.

### P0-02. Recording InputConnection 기반 계측 회귀 테스트

**배경**

현재 핵심 정책은 FcitxInputMethodService의 여러 callback과 비동기 Fcitx event, InputConnection 결과에 걸쳐 있다. 순수 JVM 테스트만으로는 Android editor와의 호출 순서, exactly-once, lifecycle 경계를 증명할 수 없다.

**현재 상태**

- capability masking, buffer 결합, Unicode code-point 삭제, legend mapping은 JVM 테스트가 있다.
- InputConnection.setComposingText 미호출, clipboard 준비와 paste 순서, commitText 횟수, key down/up 순서, lifecycle 종료 동작은 자동화되지 않았다.
- 관련 경로는 코드 리뷰와 실제 기기 수동 검증에 의존한다.

**제안**

- 호출 기록이 가능한 Recording InputConnection 또는 전용 테스트 editor를 androidTest에 추가한다.
- 다음 이벤트를 순서와 인자까지 기록한다: setComposingText, commitText, performContextMenuAction, sendKeyEvent, finishComposingText, selection update.
- fake clipboard 경계 또는 테스트 가능한 transport adapter를 두어 clip 생성, sensitive 표시, history exclusion을 검증한다.
- false dispatch에서 버퍼가 보존되는지, true acknowledgement에서 버퍼가 한 번만 비워지는지 검증한다.
- onStartInput restarting, onFinishInputView, onFinishInput, input method change, 외부 selection 이동, keyboard hide/show를 각각 독립 테스트한다.
- Ctrl+V key-down 성공 후 key-up 실패처럼 결과가 모호한 경우 자동 재전송하지 않는지 검증한다.
- 버퍼 모드에서 setComposingText 호출이 0회임을 명시적 assertion으로 둔다.

**완료 조건**

- 위 transport 및 lifecycle 시나리오가 emulator 또는 실제 기기에서 자동 반복된다.
- 버퍼 모드 시 setComposingText 호출 수는 0이다.
- 성공한 구간마다 target 전달 호출은 정확히 1회다.
- 명확한 dispatch 실패에서는 버퍼가 보존된다.
- 모호한 acknowledgement 뒤 자동 fallback 호출은 0회다.
- Password 및 Sensitive editor에서 clipboard 접근 호출은 0회다.
- 테스트는 일반 app CI와 분리 가능하더라도 명령 하나로 재현된다.

**의존성/위험**

- InputMethodService 테스트는 일반 Activity 테스트보다 lifecycle 구성이 복잡하다.
- RemoteInputConnection 동작을 단순 fake가 완전히 재현하지 못할 수 있다.
- fake 테스트와 별개로 실제 기기 smoke test를 유지해야 한다.

**검증 증거**

- 기능 전용 JVM 테스트 10개는 PASS다.
- 실제 기기에서 한 번의 hide/show 및 Space 제출은 PASS다.
- Recording InputConnection 테스트 파일과 자동 lifecycle 증거는 현재 없다.

### P0-03. 실패 및 폐기 시 무음 데이터 유실 제거

**배경**

다른 editor로 텍스트가 유출되는 것보다 버퍼를 폐기하는 편이 안전하지만, 사용자가 입력 중인 문장을 설명 없이 잃는 것도 제품 결함이다. 특히 focus 종료나 selection 변경은 앱이 자동으로 발생시킬 수 있다.

**현재 상태**

- 일반 submit에서 dispatch false가 반환되면 버퍼를 보존하도록 구현돼 있다.
- current preedit가 있으면 engine reset 전에 prefix로 회수해 retry 중복을 막는다.
- 외부 selection 이동 시 원래 insertion anchor를 신뢰할 수 없어 버퍼를 의도적으로 폐기한다.
- onFinishInputView와 onFinishInput은 submit을 시도하지만 최종 종료 경로에서 실패 결과를 사용자에게 보여주지 못하고 상태를 정리할 수 있다.
- 명시적 Retry, Copy, Cancel UI가 없다.

**제안**

- 모든 버퍼 종료 경로를 Submitted, PreservedForRetry, DiscardedByPolicy 세 결과로 분류한다.
- 실패 또는 정책 폐기 시 내부 panel이나 keyboard bar에 비민감 상태 표시를 제공한다.
- 같은 editor binding이 유효할 때만 Retry를 허용하고, editor가 바뀌면 Paste 재시도 대신 Copy 또는 명시적 폐기를 제공한다.
- 사용자가 직접 누르는 Submit, Retry, Cancel 동작을 추가한다.
- lifecycle 종료 시 cross-editor leak 방지 규칙을 유지하면서도 무음 폐기가 발생하지 않게 한다.
- 실제 문자열은 로그에 남기지 않고 길이, transport, 결과 분류만 기록한다.

**완료 조건**

- 미전송 텍스트가 폐기되는 모든 코드 경로에 사용자 인지 또는 명시적 사용자 선택이 있다.
- editor identity가 달라진 뒤 자동 제출은 0회다.
- 같은 editor에서 명확한 실패가 나면 retry 가능한 상태가 유지된다.
- Cancel은 target editor와 clipboard를 변경하지 않는다.
- instrumentation test가 submit false, selection 이동, finishInput, editor 전환을 모두 검증한다.

**의존성/위험**

- IME window가 닫힌 뒤 상태 UI를 보여줄 수 없는 lifecycle이 있다.
- process death에서는 메모리 버퍼를 복구할 수 없다.
- 버퍼를 오래 보존하면 다른 editor로 잘못 제출될 위험이 커진다.
- Copy 기능 역시 clipboard 개인정보 정책을 따라야 한다.

**검증 증거**

- 외부 selection 이동 시 폐기하는 코드와 주석은 존재한다.
- ordinary dispatch false에서 buffer preservation 로직은 존재한다.
- 사용자에게 실패 또는 폐기를 알리는 UI와 자동 회귀 테스트는 없다.

### P0-04. 시스템 클립보드 개인정보 및 소유권 계약 확정

**배경**

System paste와 Ctrl+V는 전역 clipboard를 transport로 사용한다. 비동기 remote editor 때문에 즉시 이전 clip을 복원하면 잘못된 값이 붙여넣어질 수 있어 현재 구현은 제출 문자열을 clipboard에 남긴다. 이는 기술적으로 합리적이지만 사용자 데이터 소유권과 명확한 동의가 필요하다.

**현재 상태**

- transport clip은 sensitive로 표시된다.
- Fcitx 자체 clipboard history에는 저장되지 않는다.
- Password 및 Sensitive editor는 Direct commit을 강제한다.
- 일반 editor에서 제출한 문자열은 system clipboard에 남는다.
- 설정 설명은 실험 기능임을 알리지만 clipboard 교체와 잔존을 구체적으로 알리지 않는다.

**제안**

- System paste와 Ctrl+V 선택 화면에 전역 clipboard가 교체되고 제출 문자열이 남는다는 설명을 표시한다.
- 최초 활성화 시 transport별 개인정보 차이를 한 번 명확히 안내한다.
- Direct commit을 clipboard 미사용 선택지로 눈에 띄게 유지한다.
- Incognito, no-personalized-learning, password variation 등 추가 editor signal을 검토해 sensitive 판정을 강화한다.
- clipboard preview, history, sync가 활성화된 주요 Android/OEM 조합에서 실제 노출을 시험한다.
- 이전 clip 복원 기능은 asynchronous paste 완료를 증명할 수 있는 방법이 생기기 전까지 자동 도입하지 않는다.
- 실제 입력 문자열을 Timber 또는 분석 로그에 기록하지 않는다는 테스트 및 코드 리뷰 규칙을 둔다.

**완료 조건**

- 사용자가 clipboard 교체 및 잔존을 활성화 전에 알 수 있다.
- Password, Sensitive 및 합의한 추가 민감 field에서 clipboard write가 0회다.
- transport clip이 Fcitx clipboard DB에 남지 않는다.
- 주요 지원 Android 버전에서 sensitive 표시 동작이 확인된다.
- privacy 문구가 영어와 한국어에 있고 다른 번역 정책도 정해져 있다.
- 자동 clip 복원으로 잘못된 내용이 붙여넣어지는 경로가 없다.

**의존성/위험**

- Android 버전과 OEM clipboard UI가 sensitive flag를 다르게 처리할 수 있다.
- system clipboard sync는 IME가 통제할 수 없다.
- 민감 field 분류가 EditorInfo 품질에 의존한다.

**검증 증거**

- sensitive flag, history marker, Password/Sensitive Direct commit 코드와 JVM 테스트가 있다.
- clipboard 잔존은 현재 설계 문서에 명시돼 있다.
- 사용자 설정 UI의 구체적 개인정보 고지와 OEM matrix는 아직 없다.

### P0-05. 전체 JVM 테스트의 기존 ThemeSerialization 실패 정리

**배경**

기능 전용 테스트가 통과하더라도 전체 test task가 red이면 새 회귀와 기존 실패를 자동으로 구분하기 어렵다. 알려진 stale test를 장기간 허용하면 CI 신뢰도가 떨어진다.

**현재 상태**

- 전체 app JVM 테스트 15개 중 14개가 통과한다.
- ThemeSerializationTest.version2 한 개가 실패한다.
- 실패 원인은 test fixture가 theme version 2.0을 기준으로 하지만 CustomThemeSerializer의 현재 version이 2.1인 기존 불일치다.
- 이번 Hangul 변경과 직접 관련된 실패는 아니다.

**제안**

- 2.0 theme이 2.1로 migrate되어야 하는 현재 계약을 serializer 구현과 release history로 확인한다.
- serializer가 맞다면 stale fixture와 기대값을 2.1 계약에 맞게 갱신하고, 2.0에서 2.1 migration을 별도 테스트한다.
- serializer가 틀렸다면 production migration을 고치되 기존 사용자 theme 호환성을 먼저 증명한다.
- 실패 테스트를 skip하거나 단순 삭제하지 않는다.

**완료 조건**

- :app:testDebugUnitTest가 전체 green이다.
- 2.0 입력의 2.1 migration 동작이 명시적 테스트로 남는다.
- 기존 theme JSON round-trip 테스트가 유지된다.
- Hangul feature test 10개도 계속 green이다.

**의존성/위험**

- theme migration 계약을 잘못 해석하면 기존 사용자 custom theme을 손상할 수 있다.
- Hangul 변경 커밋과 별도 concern으로 분리하는 편이 review에 유리하다.

**검증 증거**

- 현재 수치는 15개 중 14 PASS, 1 FAIL이다.
- 실패 테스트와 version 불일치가 특정돼 있으므로 원인 미상 blocker는 아니다.

### P0-06. 배포 가능한 variant 및 ABI 조합 검증

**배경**

현재 검증은 arm64-v8a debug main APK와 debug Hangul plugin APK에 집중돼 있다. Hangul plugin 발견은 variant별 package와 intent action에 의존하므로 debug main에는 debug plugin, release main에는 release plugin이 필요하다. Hangul은 `hasService=false`라 main과 같은 signer를 요구하지 않지만, 동일 package 업데이트와 signature-protected service plugin에는 별도의 서명 호환성 검증이 필요하다.

**현재 상태**

- arm64-v8a debug app assembly가 PASS다.
- arm64-v8a debug Hangul plugin native assembly가 PASS다.
- 동일 debug 조합은 실제 기기에 설치돼 동작했다.
- release build, 업그레이드, 나머지 지원 ABI, 기존 배포판과의 signature 호환성은 검증하지 않았다.
- repository가 선언한 지원 ABI는 armeabi-v7a, arm64-v8a, x86, x86_64다.

**제안**

- CI 또는 release candidate 환경에서 main과 Hangul plugin의 release assembly를 같은 toolchain으로 만든다.
- 네 ABI의 native configure 및 link를 검증한다.
- debug main/debug plugin, release main/release plugin처럼 허용된 조합을 명문화한다.
- variant 조합, 동일 package 업데이트의 signer 호환성, service plugin의 signature permission을 서로 다른 계약으로 문서화한다.
- 서명 비밀은 저장소나 문서에 넣지 않고 기존 안전한 배포 경로를 사용한다.
- 기능 브랜치를 승인된 원격이나 검증된 Git bundle/patch로 먼저 보존한 뒤, 별도 clean checkout과 전체 submodule에서 재현 빌드한다.

**완료 조건**

- 네 지원 ABI의 app 및 Hangul plugin assembly가 PASS다.
- release variant의 main/plugin descriptor 탐색과 로딩이 확인된다.
- 허용되지 않은 variant 조합과 동일 package signer 불일치는 각각 명확한 오류 또는 설치 안내를 제공한다.
- 보존된 기능 브랜치의 clean checkout에서 문서화된 명령으로 동일 결과를 재현한다.
- 산출물에 잘못 체크아웃된 symlink placeholder가 포함되지 않는다.

**의존성/위험**

- 전체 ABI native build는 시간과 저장 공간을 많이 사용한다.
- release signing은 별도 권한과 안전한 credential 경로가 필요하다.
- x86 계열은 실제 기기 대신 emulator 검증이 필요할 수 있다.

**검증 증거**

- 현재 증거는 arm64-v8a debug main/plugin PASS와 실제 기기 설치뿐이다.
- symlink는 현재 clone에서 실제 링크로 복구돼 있다.
- release 및 전체 ABI 증거는 없다.

---

## P1 — 제한된 실사용 확대 전 핵심 작업

### P1-01. 명시적 Submit, Retry, Cancel 사용자 인터페이스

**배경**

현재 delimiter 중심 제출은 빠르지만 사용자가 전송 시점을 직접 통제하기 어렵다. target 앱이 delimiter를 특별하게 처리하거나 긴 문장을 한 번에 보내고 싶은 경우 명시적 조작이 필요하다.

**현재 상태**

- Space, 숫자, 문장부호, Return, 좌우 화살표와 lifecycle이 제출을 유발한다.
- 실패 시 내부적으로 buffer를 보존할 수 있지만 사용자가 retry 상태를 알기 어렵다.
- keyboard bar에 dedicated submit/retry/cancel control이 없다.

**제안**

- buffered session이 비어 있지 않을 때만 나타나는 Submit action을 제공한다.
- 명확한 dispatch false 후 Submit을 Retry로 바꾸고 transport 변경 진입점을 제공한다.
- Cancel은 engine preedit와 prefix를 함께 비우되 target과 clipboard를 건드리지 않는다.
- 긴 누르기나 별도 action으로 Copy를 제공할지는 P0 개인정보 정책과 함께 결정한다.
- 접근성 label, haptic feedback, 상태 색상은 일반 keyboard action과 일관되게 설계한다.

**완료 조건**

- 사용자가 delimiter 없이 현재 구간을 제출할 수 있다.
- dispatch false 후 같은 editor에서 retry할 수 있다.
- cancel 이후 target editor 변경은 0이다.
- 버튼은 buffer empty 상태에서 노출되지 않거나 disabled다.
- TalkBack으로 상태와 동작이 구분된다.
- instrumentation과 실제 기기에서 exactly-once가 확인된다.

**의존성/위험**

- 상단 bar 공간과 candidate UI가 충돌할 수 있다.
- dispatch 성공 여부가 모호한 true 상태에서는 Retry를 함부로 제공하면 중복될 수 있다.
- Retry는 명확한 false 또는 사용자가 target 결과를 확인한 뒤에만 허용해야 한다.

**검증 증거**

- 현재 전용 control은 없다.
- delimiter 및 hide/show 경로의 실제 기기 PASS만 있다.

### P1-02. 앱별 transport 프로필

**배경**

System paste는 표준 editor에 적합하고 Ctrl+V는 remote/raw-key surface에 적합하며 Direct commit은 clipboard를 피한다. 전역 설정 하나로 모든 앱을 다루면 사용자가 앱을 바꿀 때마다 수동 전환해야 한다.

**현재 상태**

- Advanced 설정에 전역 transport enum이 있다.
- active editor의 package identity를 기반으로 한 override는 없다.
- Password/Sensitive만 전역 설정보다 우선한다.

**제안**

- 기본 transport와 앱별 override를 분리한다.
- EditorInfo package identity와 선택한 transport만 저장하고 입력 내용은 저장하지 않는다.
- unknown surface는 기본값 System paste 또는 사용자가 정한 전역 기본값을 따른다.
- 앱별 설정에서 마지막 검증 결과와 날짜를 사용자가 볼 수 있게 하는 방안을 검토한다.
- 앱 업데이트 후 동작이 바뀔 수 있으므로 override reset과 진단 진입점을 제공한다.

**완료 조건**

- 두 개 이상의 앱에서 서로 다른 transport가 자동 선택된다.
- Password/Sensitive 강제 Direct commit이 앱별 override보다 항상 우선한다.
- 앱 삭제 또는 identity 변경 시 stale profile이 안전하게 처리된다.
- profile 데이터에 입력 문자열이 포함되지 않는다.
- unit 및 instrumentation test가 우선순위 규칙을 검증한다.

**의존성/위험**

- 일부 editor가 신뢰하기 어려운 package 정보를 제공할 수 있다.
- package 이름만으로 같은 앱 안의 서로 다른 surface를 구분하지 못할 수 있다.
- 앱별 자동 선택이 잘못되면 clipboard 사용 여부가 사용자의 예상과 달라질 수 있다.

**검증 증거**

- 현재 전역 enum과 sensitive override만 구현돼 있다.
- 앱별 profile 저장소와 테스트는 없다.

### P1-03. Buffered session을 명시적 상태 머신으로 분리

**배경**

현재 구현은 FcitxInputMethodService 안의 event callback, lifecycle, selection tracker, clipboard, physical key 처리에 분산돼 있다. 기능이 확장될수록 암묵적 boolean 조합이 회귀를 만들 가능성이 높다.

**현재 상태**

- BufferedInputController는 문자열 prefix만 단순하게 관리한다.
- session active, engine reset pending, physical keys down, transport 결과는 service의 여러 필드와 분기에 나뉘어 있다.
- 이번 커밋에서 FcitxInputMethodService 변경량이 크다.

**제안**

- Idle, Composing, Dispatching, RetryableFailure, AmbiguousDispatch, Cancelled 같은 명시적 상태를 정의한다.
- EngineCommit, EnginePreedit, Delimiter, Submit, DispatchResult, SelectionChanged, EditorChanged, FinishView, ProcessEnd를 event로 모델링한다.
- 상태 전이는 Android API 호출과 분리된 순수 reducer로 테스트한다.
- side effect는 clipboard write, paste action, key event, direct commit, engine reset 명령으로 반환한다.
- cross-editor leak 금지와 no-auto-fallback을 state invariant로 둔다.

**완료 조건**

- 핵심 transition의 표 기반 단위 테스트가 있다.
- service callback은 event 변환과 side-effect 실행 위주로 단순화된다.
- boolean 조합만으로 해석해야 하는 상태가 제거된다.
- 기존 실제 기기 동작과 transport 의미가 바뀌지 않는다.
- failure 및 ambiguous result가 서로 다른 상태로 유지된다.

**의존성/위험**

- 큰 refactor는 동작을 바꾸지 않는 characterization test가 먼저 필요하다.
- Fcitx event가 비동기라 event ordering을 정확히 유지해야 한다.
- refactor와 새로운 UX를 한 커밋에 섞으면 원인 추적이 어려워진다.

**검증 증거**

- 현재 pure controller 테스트는 3개 동작만 다룬다.
- lifecycle/transport 상태 전이 전체를 표현하는 독립 모델은 없다.

### P1-04. 물리 키보드와 modifier shortcut 실기기 검증

**배경**

원격 앱 사용자는 물리 키보드나 hardware-like key event를 함께 쓰는 경우가 많다. key down/up 비대칭, repeat, modifier ordering은 가상 키보드 테스트로 확인할 수 없다.

**현재 상태**

- forwarded text/navigation key 처리 코드가 있다.
- Ctrl, Alt, Meta, Super shortcut 전에 Direct commit으로 pending Hangul을 정리한다.
- handled physical key의 release를 추적하는 집합이 있다.
- 실제 물리 키보드 검증은 하지 않았다.

**제안**

- 실제 물리 키보드로 현대 두벌식 입력, Shift, Caps Lock, Backspace, repeat, Enter, arrows를 시험한다.
- Ctrl+C, Ctrl+V, Ctrl+A, Alt 조합, Meta 조합에서 pending Hangul ordering을 확인한다.
- key down 성공/key up 실패, focus change 중 held key, repeat event를 recording test로 재현한다.
- shortcut 전 Direct commit이 대상 앱에서 중복을 만들지 않는지 transport별로 확인한다.

**완료 조건**

- 물리 키보드로 일반 Hangul 입력과 shortcut matrix가 PASS다.
- key release가 literal text나 두 번째 submit을 만들지 않는다.
- Ctrl+V transport와 사용자가 실제 누른 Ctrl+V가 충돌하지 않는다.
- repeat 및 focus loss 후 stuck key 상태가 남지 않는다.

**의존성/위험**

- OEM과 keyboard firmware가 key event를 다르게 보낼 수 있다.
- 원격 앱이 modifier를 host로 그대로 전달할 수 있다.
- 자동 테스트만으로 hardware event timing을 완전히 재현하기 어렵다.

**검증 증거**

- 코드 리뷰 범위는 존재하지만 기존 설계 문서도 물리 키보드 실기기 미검증을 명시한다.

### P1-05. 표준 editor 유형 및 Android 버전 호환성 행렬

**배경**

EditText, Jetpack Compose, WebView, contenteditable은 InputConnection과 paste action을 다르게 구현한다. 한 OEM의 한 editor 성공만으로 Android 전체 지원을 선언할 수 없다.

**현재 상태**

- Samsung Android 16의 Settings 검색 editor만 수동 PASS다.
- Compose, WebView, contenteditable, 다른 OEM, 낮은 API는 미검증이다.
- minSdk는 23이고 targetSdk는 36이다.

**제안**

- 전용 test harness 앱에 EditText, Compose text field, WebView input, contenteditable, custom InputConnection 표면을 만든다.
- 최소 API 구간을 23, 최근 중간 버전, 35, 36으로 나눠 emulator 또는 기기에서 시험한다.
- 각 surface에서 세 transport, Backspace, delimiter, Return, selection, rotation을 기록한다.
- 성공 여부를 API acknowledgement가 아니라 실제 editor text로 assertion한다.

**완료 조건**

- 지원 표면별 transport 결과가 자동 또는 반복 가능한 수동 matrix로 남는다.
- supported, degraded, unsupported 상태가 구분된다.
- target text가 정확히 한 번 변경됐는지 검증한다.
- minSdk에서 사용한 sensitive clipboard API guard가 crash 없이 동작한다.

**의존성/위험**

- WebView 버전은 OS와 별도로 업데이트된다.
- Compose 버전에 따라 구현이 바뀔 수 있다.
- 모든 OEM 조합을 CI에서 유지할 수 없으므로 대표군 선정이 필요하다.

**검증 증거**

- 현재 device matrix 표는 문서에 있지만 실제 결과는 Settings editor 한 종류뿐이다.

### P1-06. Gradle task graph와 lint gate 정리

**배경**

검증 명령이 서로 충돌하거나 전체 lint가 항상 red이면 개발자가 실제 회귀를 놓치기 쉽다.

**현재 상태**

- Hangul plugin assembly와 lint는 각각 PASS다.
- 둘을 같은 invocation에 넣으면 generateDataDescriptor와 generateDebugLintReportModel 사이 implicit-dependency validation 문제가 발생한다.
- app lint는 266 errors, 49 warnings의 기존 부채로 red지만 변경 파일 finding은 0이다.

**제안**

- custom build-logic에서 data descriptor와 lint report model의 실제 dependency를 선언한다.
- plugin assemble와 lint를 같은 invocation에서 실행하는 regression test 또는 CI job을 추가한다.
- app lint는 upstream 부채를 숨기지 않는 baseline 또는 changed-file gate 전략을 합의한다.
- 새 코드가 기존 숫자를 증가시키지 못하게 한다.
- baseline 생성 시 각 finding의 소유권과 감축 계획을 별도 이슈로 남긴다.

**완료 조건**

- plugin assemble와 lint가 한 invocation에서 PASS다.
- app 변경 파일의 lint 0이 자동 gate가 된다.
- 전체 lint 부채가 숫자로 추적되며 새 finding 증가가 차단된다.
- task dependency가 실행 순서 우연에 의존하지 않는다.

**의존성/위험**

- baseline이 기존 문제를 영구적으로 숨기는 수단이 되면 안 된다.
- Android Gradle Plugin 내부 task 이름 변화에 취약할 수 있다.
- build-logic 변경은 모든 plugin module에 영향을 준다.

**검증 증거**

- 분리 invocation PASS와 통합 invocation 실패가 재현됐다.
- app lint 수치는 266 errors, 49 warnings이며 feature 변경 finding은 0이다.

### P1-07. 내용 비노출 진단 이벤트와 호환성 리포트

**배경**

paste acknowledgement가 실제 결과를 말해주지 않기 때문에 현장 실패를 분석하려면 transport 선택, editor capability, dispatch 단계, lifecycle 정보를 알아야 한다. 그러나 입력 문자열을 기록하면 안 된다.

**현재 상태**

- Timber warning과 일부 lifecycle log가 있다.
- 실제 buffer content를 진단용으로 구조화해 기록하지는 않는다.
- 사용자가 export할 수 있는 feature-specific 진단 요약은 없다.

**제안**

- session ID, editor 유형 범주, selected transport, sensitive override 여부, buffer UTF-16 length와 code-point count, dispatch acknowledgement, lifecycle result만 기록한다.
- 실제 문자열, clipboard 내용, 앱 내 field text는 기록하지 않는다.
- Developer 화면에서 최근 N개 상태 전이를 복사 가능한 redacted report로 제공한다.
- 로그 보존 기간과 opt-in 정책을 정한다.
- exception과 expected unsupported result를 구분한다.

**완료 조건**

- 실패 재현 시 입력 내용 없이 상태 전이와 선택 transport를 확인할 수 있다.
- automated test가 report에 샘플 입력 문자열이 포함되지 않음을 검증한다.
- Password/Sensitive session은 더 제한된 metadata만 기록한다.
- release build의 기본 로그 수준과 보존 정책이 명확하다.

**의존성/위험**

- 앱 package identity도 개인정보로 취급될 수 있다.
- 지나친 logging은 IME 성능에 영향을 줄 수 있다.
- acknowledgement를 success metric으로 표시하면 안 된다.

**검증 증거**

- 현재 구조화된 redacted report는 없다.
- 일부 warning은 있지만 end-to-end session 분석에는 부족하다.

### P1-08. Selection 및 insertion anchor 정책 개선

**배경**

버퍼는 target editor에 composing marker를 만들지 않으므로 target cursor가 움직였을 때 원래 insertion anchor를 복구할 근거가 없다. 현재 폐기 정책은 잘못된 위치 삽입을 막지만 자동 selection 변화에서도 입력을 잃을 수 있다.

**현재 상태**

- selection prediction이 맞으면 정상 update로 소비한다.
- 예상하지 못한 selection 변화 중 pending text가 있으면 버퍼를 폐기하고 engine reset을 예약한다.
- 원래 anchor를 보존하거나 사용자에게 선택권을 주는 UI는 없다.

**제안**

- editor identity, initial selection, predicted selection, update sequence를 session anchor로 명시한다.
- 같은 editor에서 단순한 cursor bounce인지 실제 사용자 이동인지 구분 가능한 신호를 조사한다.
- 확신할 수 없으면 자동 paste 대신 PreservedForRetry 상태로 전환하고 사용자에게 제출 위치를 다시 선택하게 한다.
- selection range가 있는 상태에서 replace semantics를 별도 테스트한다.
- target-visible marker를 도입하는 설계는 broken composing 우회의 목적과 충돌하므로 기본안으로 채택하지 않는다.

**완료 조건**

- 외부 selection 변화가 무음 폐기로 끝나지 않는다.
- 다른 editor로 자동 제출되지 않는다.
- 선택 영역 replace, cursor start/end, RTL 또는 surrogate 포함 text 주변에서 offset이 정확하다.
- recording test가 predicted update와 unexpected update를 구분한다.

**의존성/위험**

- Android editor가 selection callback을 중복 또는 지연 전송할 수 있다.
- anchor 복구를 위해 target text를 읽는 것은 개인정보 및 capability 제한이 있다.
- 너무 공격적인 복구는 잘못된 위치 삽입보다 위험할 수 있다.

**검증 증거**

- 현재 의도적 discard 코드가 존재한다.
- selection recovery 또는 사용자 retry UX 증거는 없다.

---

## P2 — 입력 방식 및 품질 확대

### P2-01. 두벌식 옛글 Dubeolsik Yetgeul 전용 legend

**배경**

Dubeolsik Yetgeul은 현대 두벌식과 키 위치가 일부 비슷하지만 Shift 조합을 포함한 여러 키가 옛자모로 다르다. 현대 매핑을 그대로 표시하면 입력 결과와 keycap이 불일치한다.

**현재 상태**

- HangulKeyLegends는 Dubeolsik만 지원한다.
- Dubeolsik Yetgeul은 명시적으로 unsupported 처리돼 Latin fallback을 사용한다.
- 이 fail-safe는 잘못된 한글 legend보다 안전하다.

**제안**

- libhangul의 2y keyboard definition을 authoritative source로 삼아 normal/shift legend를 별도 작성한다.
- 지원할 수 없는 glyph의 font rendering과 fallback을 실제 기기에서 확인한다.
- modern Dubeolsik과 공통 map을 무리하게 공유하지 않는다.
- engine action은 기존 Latin keysym 유지 원칙을 따른다.

**완료 조건**

- 모든 26키 normal/shift 결과가 libhangul 2y 정의와 일치한다.
- glyph가 keycap과 popup preview에서 깨지지 않는다.
- 현대 두벌식 테스트와 분리된 Yetgeul test가 있다.
- 모르는 2y 변형은 계속 fail-safe한다.

**의존성/위험**

- Android 기본 font가 일부 옛자모를 적절히 표시하지 못할 수 있다.
- upstream libhangul definition 변경과 drift가 생길 수 있다.

**검증 증거**

- 현재 테스트는 Dubeolsik Yetgeul이 null fallback임을 확인한다.
- 실제 옛글 legend 구현은 없다.

### P2-02. 세벌식 계열 전용 keyboard surface

**배경**

세벌식 390, Final, Noshift, Yetgeul, Dubeol Layout은 26개 alphabet key만으로 정확히 표현할 수 없다. 숫자와 문장부호 위치까지 engine mapping의 일부이므로 단순 label replacement로 해결하면 안 된다.

**현재 상태**

- TextKeyboard는 QWERTY 중심 surface다.
- 세벌식 layout은 모두 Latin fallback이다.
- Hangul plugin은 여러 세벌식 engine layout을 이미 제공한다.

**제안**

- 각 세벌식 layout의 key geometry, normal action, shift action, label을 포함한 전용 BaseKeyboard 정의를 만든다.
- 숫자 row, punctuation, long-press popup과 충돌을 설계 단계에서 해결한다.
- libhangul keyboard data를 SSOT로 삼되 Android UI 표현에 필요한 metadata를 별도 둔다.
- 우선 사용자 수요와 검증 가능성을 기준으로 390과 Final 순서를 정한다.

**완료 조건**

- 선택한 세벌식 layout에서 모든 engine key가 UI로 접근 가능하다.
- label과 실제 libhangul 결과가 normal/shift 전체에서 일치한다.
- 숫자 및 punctuation 입력이 기존 TextKeyboard 동작을 깨뜨리지 않는다.
- layout 전환과 buffered transport가 함께 동작한다.
- 실제 세벌식 사용자의 인수 검증을 받는다.

**의존성/위험**

- keyboard geometry 변경은 touch target과 한 손 사용성에 영향을 준다.
- 기존 popup 및 number row 설정과 충돌할 수 있다.
- 각 layout을 정확히 검증할 도메인 사용자가 필요하다.

**검증 증거**

- 현재 세벌식 UI 구현과 테스트는 없다.
- 단순 legend 치환으로 불충분하다는 구조 분석은 완료됐다.

### P2-03. Ahnmatae 및 Romaja 표시 정책

**배경**

Romaja는 Latin legend가 자연스럽고 Ahnmatae는 전용 배열이 필요하다. 모든 Hangul engine을 한글 keycap 대상으로 묶으면 오히려 잘못된 UX가 된다.

**현재 상태**

- Dubeolsik 외 layout은 Latin fallback이다.
- Romaja와 Ahnmatae를 구분하는 사용자 설명은 없다.

**제안**

- Romaja는 Latin keycap 유지가 의도된 동작임을 문서와 테스트로 고정한다.
- Ahnmatae는 authoritative mapping을 조사한 뒤 전용 layout surface로 구현한다.
- input method 설정에서 현재 UI 지원 수준을 표시한다.

**완료 조건**

- Romaja가 불필요하게 한글 legend로 바뀌지 않는다.
- Ahnmatae를 지원할 경우 모든 key action과 label이 검증된다.
- unsupported 상태가 사용자에게 오작동처럼 보이지 않는다.

**의존성/위험**

- layout 이름 번역과 실제 engine ID를 혼동할 수 있다.
- Ahnmatae 사용성 검증자가 필요하다.

**검증 증거**

- 현재는 두 layout 모두 safe Latin fallback이다.
- 전용 UI와 acceptance test는 없다.

### P2-04. 번역, 접근성, 설정 설명 확대

**배경**

IME 설정과 transport 용어는 기술적이다. clipboard 위험과 unsupported surface를 사용자 언어로 설명하지 못하면 잘못된 선택이 늘어난다.

**현재 상태**

- 새 설정 문자열은 영어와 한국어가 있다.
- 영어 문자열 일부는 MissingTranslation lint ignore를 사용한다.
- 다른 locale 번역, TalkBack 상태 설명, help link는 없다.

**제안**

- transport를 기술명뿐 아니라 적합한 surface와 개인정보 차이로 설명한다.
- 모든 지원 locale의 번역 workflow에 문자열을 추가한다.
- Submit, Retry, Cancel, buffer 상태에 contentDescription과 stateDescription을 제공한다.
- keyboard keycap의 옛자모 및 shift 상태가 screen reader에서 읽히는 방식을 시험한다.
- 설정에서 상세 문서로 이동할 수 있게 한다.

**완료 조건**

- 지원 locale에서 누락 문자열이 관리 정책에 따라 처리된다.
- TalkBack으로 mode 활성, transport, pending, failure 상태를 구분할 수 있다.
- clipboard 잔존과 실험 기능 제한이 설정 안에서 이해 가능하다.
- 새 accessibility finding이 0이다.

**의존성/위험**

- 기술 용어 번역이 실제 Android UI 용어와 어긋날 수 있다.
- keycap contentDescription이 너무 장황하면 typing 사용성을 해칠 수 있다.

**검증 증거**

- 현재 영어/한국어 기본 문자열은 존재한다.
- 다국어 및 TalkBack 검증 증거는 없다.

### P2-05. 장시간 입력 및 lifecycle 스트레스 검증

**배경**

현재 버퍼는 process memory에 있고 길이 제한이 없다. 긴 입력, rotation, multi-window, low-memory kill, rapid app switching은 짧은 수동 smoke test와 다른 실패를 만들 수 있다.

**현재 상태**

- buffer는 StringBuilder 기반이며 process death에서 복구되지 않는다.
- hide/show 한 번의 lifecycle은 실제 기기에서 PASS다.
- rotation, multi-window, service recreation, 장문 성능은 미검증이다.

**제안**

- 수천 code point, emoji, combining mark, surrogate pair가 섞인 입력을 stress test한다.
- 빠른 hide/show, app switching, rotation, multi-window, screen off/on을 반복한다.
- process death에서는 복구하지 않는 정책을 사용자에게 알릴지, 안전한 임시 persistence가 가능한지 별도 threat model로 판단한다.
- buffer 크기 상한과 UI truncation 정책을 정하되 실제 제출 text는 손상하지 않는다.
- input panel render와 snapshot 복사 비용을 측정한다.

**완료 조건**

- 합의한 최대 길이에서 ANR, OOM, 눈에 띄는 key latency가 없다.
- Unicode sequence가 손상되지 않는다.
- lifecycle 반복에서 중복 및 cross-editor leak이 0이다.
- process death 결과가 문서화된 정책과 일치한다.
- stress test가 재현 가능한 스크립트 또는 androidTest로 남는다.

**의존성/위험**

- buffer persistence는 IME 입력 내용을 저장하는 개인정보 위험이 매우 크다.
- UI 축약과 실제 buffer 축약을 혼동하면 데이터 손실이 생긴다.
- OEM lifecycle 차이가 크다.

**검증 증거**

- Unicode code-point 단위 Backspace JVM 테스트는 PASS다.
- 장문 및 복잡 lifecycle 증거는 없다.

### P2-06. Transport 선택 진단 도우미

**배경**

사용자는 어떤 transport가 대상 앱에 맞는지 알기 어렵다. 자동 fallback은 중복 위험 때문에 금지돼 있으므로, 결과를 사용자가 직접 확인하는 안전한 진단 흐름이 필요하다.

**현재 상태**

- transport는 설정 목록에서 직접 선택한다.
- 앱별 test wizard나 guided sample은 없다.
- API acknowledgement만으로 실제 결과를 판정할 수 없다.

**제안**

- 고정된 비민감 샘플을 사용자가 대상 field에 보내고 화면 결과를 직접 확인하는 단계형 도우미를 설계한다.
- 한 번에 한 transport만 시험하고 자동 재전송하지 않는다.
- 성공 여부는 사용자가 확인하며 결과를 앱별 profile에 저장할 수 있게 한다.
- 실패 시 keyboard-only 한계를 설명하고 다음 transport를 사용자가 명시적으로 선택하게 한다.

**완료 조건**

- 자동 fallback 없이 세 transport를 독립 시험할 수 있다.
- 샘플이 중복될 가능성을 사용자에게 알린다.
- 사용자가 확인한 결과만 profile에 저장된다.
- 민감 field에서는 도우미를 실행하지 않는다.

**의존성/위험**

- IME가 target editor text를 신뢰성 있게 읽어 자동 검증할 수 없다.
- wizard가 target 앱과 Fcitx 설정 사이를 오가는 UX를 복잡하게 만들 수 있다.

**검증 증거**

- 현재는 수동 설정과 문서 matrix만 있다.
- 진단 도우미 구현은 없다.

---

## P3 — 장기 구조 및 범용화

### P3-01. Hangul 전용 구현을 engine-agnostic buffered composition으로 일반화

**배경**

broken composing 문제는 Hangul 외 다른 조합형 입력기에도 생길 수 있다. 그러나 현재 정책은 Hangul addon과 ko 언어로 의도적으로 제한돼 있어 안전하다.

**현재 상태**

- BufferedHangulMode가 Hangul addon과 ko 언어를 명시적으로 검사한다.
- buffer controller 자체는 문자열 기반이지만 lifecycle과 UI는 Hangul에 결합돼 있다.
- 다른 engine에서의 commit/preedit 의미는 조사하지 않았다.

**제안**

- engine capability contract, delimiter policy, candidate interaction, reset semantics를 먼저 정의한다.
- generic BufferedCompositionPolicy와 Hangul policy를 분리한다.
- 한 engine씩 opt-in하며 unknown engine에는 적용하지 않는다.
- candidate 선택이 있는 engine에서 prefix와 preedit 결합이 정확한지 별도 연구한다.

**완료 조건**

- generic core와 Hangul-specific policy 경계가 명확하다.
- Hangul 동작과 테스트가 회귀하지 않는다.
- 두 번째 engine이 실제 문제 surface와 함께 검증되기 전에는 범용 설정으로 노출하지 않는다.
- unsupported engine은 기존 direct IME 경로를 유지한다.

**의존성/위험**

- engine마다 commit boundary와 reset 의미가 다르다.
- 무리한 일반화가 현재 안정된 Hangul path를 복잡하게 만들 수 있다.

**검증 증거**

- 현재 Hangul 한정 guard와 테스트가 있다.
- 다른 engine의 실증 자료는 없다.

### P3-02. Host 또는 원격 앱 전용 transport 확장 지점

**배경**

Canvas, Unity, OpenGL, remote video surface는 표준 InputConnection도 Android paste action도 제공하지 않을 수 있다. 이 경우 키보드 내부의 일반 transport만으로 해결할 수 없다.

**현재 상태**

- System paste, Ctrl+V, Direct commit 세 transport만 있다.
- raw-key surface에는 Ctrl+V를 시도할 수 있지만 host 결과는 알 수 없다.
- 앱별 전용 API나 plugin transport는 없다.

**제안**

- 검증된 대상 앱이 공식 text injection API, broadcast, intent 또는 host clipboard channel을 제공하는지 조사한다.
- transport interface를 표준 경로와 app-specific adapter로 분리할 수 있게 설계한다.
- app-specific adapter는 명시적 사용자 선택과 권한 설명이 있을 때만 활성화한다.
- 접근성 자동화는 별도 권한과 threat model이 필요하므로 기본 IME transport에 암묵적으로 포함하지 않는다.

**완료 조건**

- 실제 API가 존재하는 대상에 한해 adapter contract와 보안 검토가 완료된다.
- adapter 실패가 표준 transport 자동 재시도를 유발하지 않는다.
- 사용자에게 필요한 권한과 데이터 흐름이 명확하다.
- 지원하지 않는 Canvas surface를 지원한다고 표시하지 않는다.

**의존성/위험**

- 대상 앱의 비공개 API에 의존하면 업데이트에 매우 취약하다.
- 접근성 권한은 광범위하고 개인정보 위험이 크다.
- host clipboard sync는 Android clipboard와 별도 상태를 가질 수 있다.

**검증 증거**

- 현재 문서는 Canvas, Unity, remote video surface가 실패할 수 있음을 명시한다.
- 전용 adapter 구현과 실제 API 증거는 없다.

### P3-03. Keyboard layout 데이터의 단일 원천 및 생성 파이프라인

**배경**

layout 수가 늘면 Kotlin에 수동 복사한 legend와 libhangul engine 정의가 어긋날 수 있다. 특히 옛글과 세벌식은 key 수와 modifier 규칙이 복잡하다.

**현재 상태**

- 현대 두벌식 legend는 작은 Kotlin map으로 유지된다.
- engine action은 Latin keysym을 유지해 현대 두벌식에서는 안전하다.
- 다른 layout의 Android UI metadata SSOT는 없다.

**제안**

- libhangul keyboard definition에서 검증 가능한 부분을 build-time data로 추출하는 방안을 연구한다.
- Android touch geometry, display label, action keysym, popup을 명시하는 schema를 만든다.
- generated data와 source engine definition의 drift test를 추가한다.
- upstream의 planned customizable keyboard layout 방향과 충돌하지 않는지 설계 리뷰한다.

**완료 조건**

- 지원 layout의 label/action이 authoritative definition과 자동 대조된다.
- 수동 중복 map이 최소화된다.
- schema 오류가 build 또는 test에서 fail-closed된다.
- 사용자 custom layout과 built-in verified layout의 신뢰 경계가 분리된다.

**의존성/위험**

- libhangul data 형식이 Android build에 바로 적합하지 않을 수 있다.
- generation pipeline이 native submodule 버전에 강하게 결합될 수 있다.
- geometry는 engine data만으로 생성할 수 없다.

**검증 증거**

- 현재 현대 두벌식 map과 unit test는 존재한다.
- 다중 layout generator와 drift test는 없다.

### P3-04. Upstream 제출 및 장기 유지보수 계약

**배경**

현재 branch는 upstream 대비 큰 service 변경과 새 experimental UX를 포함한다. 장기 fork로 남기면 Android API, Fcitx event, plugin release와 빠르게 어긋날 수 있다.

**현재 상태**

- 기능 구현과 영어 설계 문서, Windows build 문서가 한 커밋에 정리돼 있다.
- upstream에는 전체 customizable layout 요구가 존재하지만 이 branch의 buffered transport 정책은 별도 성격이다.
- 전체 app lint와 stale theme test 같은 기존 부채가 review 신호를 흐릴 수 있다.

**제안**

- 제출 단위를 keyboard legend, pure buffer primitives, transport/lifecycle, docs로 나눌 수 있는지 검토한다.
- experimental default-off, no-auto-fallback, sensitive direct-commit 원칙을 PR 설명의 핵심 contract로 둔다.
- upstream maintainer가 원하는 preference 위치, naming, test surface를 먼저 확인한다.
- submodule update와 Android target update 때 실행할 regression checklist를 만든다.
- fork 전용 app-specific adapter는 core upstream 변경과 분리한다.

**완료 조건**

- review 가능한 concern 단위와 commit history가 정리된다.
- upstream 또는 fork의 명시적 owner가 정해진다.
- Android/Fcitx 업데이트용 회귀 체크리스트가 문서화된다.
- default-off와 개인정보 정책이 release note에 포함된다.
- 유지하지 않을 실험 경로는 dead code로 방치하지 않는다.

**의존성/위험**

- upstream이 대규모 service 변경을 한 번에 받지 않을 수 있다.
- refactor 중 이미 검증한 behavior가 바뀔 수 있다.
- fork-specific 요구와 범용 upstream 요구가 다를 수 있다.

**검증 증거**

- 기준 커밋은 21개 파일, 1,014 insertions, 40 deletions 규모다.
- 동작과 문서는 함께 존재하지만 upstream review 결과는 아직 없다.

---

## 5. 권장 실행 순서

1. P0-01로 원래 문제 앱에서 실제 성공 transport 또는 명확한 unsupported 결론을 확보한다.
2. P0-02의 Recording InputConnection characterization test를 먼저 만들고 현재 동작을 고정한다.
3. P0-03과 P0-04로 무음 유실 및 clipboard 개인정보 계약을 확정한다.
4. P0-05로 전체 JVM test gate를 green으로 만든다.
5. P0-06으로 release 및 전체 ABI 결과를 확보한 뒤 제한된 알파 배포 여부를 판단한다.
6. P1-03 상태 머신 refactor는 characterization test 이후에만 진행한다.
7. P1 UX와 앱별 profile을 실제 문제 앱 결과에 맞춰 구현한다.
8. P2 layout 확대는 현대 두벌식 안정화와 별도 concern으로 진행한다.
9. P3 일반화는 두 번째 실제 engine 또는 공식 host API라는 구체적 수요가 생긴 뒤 시작한다.

## 6. 알파 배포 게이트

다음 조건을 모두 만족하기 전에는 일반 사용자용 APK로 배포하지 않는다.

- 원래 문제 앱의 결과가 P0-01에 기록돼 있다.
- exactly-once와 no-composing을 자동화한 P0-02가 green이다.
- 실패 또는 폐기가 무음으로 일어나지 않는다.
- clipboard 사용과 잔존을 사용자가 사전에 이해할 수 있다.
- 민감 editor에서 clipboard write가 0회다.
- 전체 app JVM 테스트가 green이다.
- main과 Hangul plugin의 배포 variant 및 signature 조합이 검증됐다.
- debug arm64 한 종류가 아닌 합의한 release 및 ABI 범위가 빌드됐다.
- 알려진 unsupported surface를 성공으로 표현하지 않는다.

## 7. 각 작업 완료 시 남길 증거

각 backlog 항목을 완료할 때 다음을 함께 남긴다.

- 실행한 정확한 Gradle task와 결과.
- 사용한 Android API level, editor 유형, app 버전 범주.
- transport 종류와 sensitive override 여부.
- 반복 횟수, 중복 수, 누락 수, 문자 손상 수.
- 자동 테스트 이름과 assertion 대상.
- 실패 시 실제 원인과 의도된 fallback 또는 non-fallback 정책.
- 입력 원문을 제외한 redacted log.
- 문서와 테스트가 함께 갱신됐는지 여부.

입력한 실제 문자열, 개인정보, clipboard 원문, 디버깅 연결 비밀은 증거 문서에 기록하지 않는다.
