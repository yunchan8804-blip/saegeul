# 한글 키캡·버퍼 입력 호환 모드 상세 인수인계

작성 기준일: 2026-07-25

저장소: `D:\workspace\fcitx5-android`

대상 독자: 이 브랜치를 이어서 개발·검증·배포할 작업자

이 문서는 현재 구현을 다시 역추적하지 않고도 바로 이어서 작업할 수 있도록 만든 상세 인수인계다. 간단한 사용자 설명과 공개용 빌드 절차는 `docs/hangul-buffered-input.md`를 보고, 구현 의도·상태 전이·검증 근거·디버깅 절차는 이 문서를 기준으로 삼아라.

관련 문서: [기능·빌드 개요](hangul-buffered-input.md), [우선순위 백로그](hangul-buffered-input-backlog.md)

## 1. 한눈에 보는 현재 상태

| 항목 | 현재 값 |
| --- | --- |
| 공식 원격 저장소 | `https://github.com/fcitx5-android/fcitx5-android.git` |
| 기본 브랜치 | `master` |
| 작업 브랜치 | `feat/hangul-buffered-input` |
| 기준 커밋 | `0eb0e0699b0309b5f197dfb2db5fb92eabbb7dfa` |
| 구현 커밋 | `5338d80ac247a21b6831626d4b3ae09710f1c65b` |
| 구현 커밋 제목 | `Add buffered Hangul compatibility mode` |
| 기준 대비 변경량 | 21개 파일, 1,014줄 추가, 40줄 삭제 |
| 공개 상태 | 기능 브랜치와 구현 커밋은 공식 `origin`에 push되지 않은 로컬 전용 상태 |
| 브랜치 upstream | 설정되지 않음 |
| 구현 기본값 | 한글 버퍼 호환 모드 꺼짐 |
| 지원 키캡 | 현대 두벌식 `Dubeolsik`만 |
| 버퍼 전달 방식 | System paste, Ctrl+V, Direct commit |
| 실기기 검증 | Samsung SM-F956N, Android 16 / API 36에서 핵심 System paste 흐름 통과 |

구현 커밋 자체는 깨끗한 상태로 만들어졌고, 백로그·인수인계·문서 링크는 그 뒤의 문서 전용 커밋으로 분리한다. `D:\workspace\fcitx5-android`는 공식 원격에 없는 기능 커밋을 보유한 현재 작업본이므로, 승인된 원격 push나 검증된 bundle/patch 백업 전에는 삭제하거나 새 clone으로 교체하지 마라. 다음 작업 시작 시 아래 명령으로 동적 상태를 다시 확인해라.

```powershell
Set-Location D:\workspace\fcitx5-android
git status --short
git branch -vv
git log -3 --oneline --decorate
git rev-list --left-right --count origin/master...HEAD
git submodule status --recursive
```

문서 커밋까지 완료된 정상 인수 상태라면 working tree가 깨끗해야 한다. 구현 소스나 문서가 추가로 수정되어 있으면 먼저 변경 주체와 의도를 확인하고 보존해라.

## 2. 해결하려는 문제와 범위

문제 앱 중에는 일반 영문 입력은 받지만 Android IME의 한글 조합 문자열, 즉 `setComposingText()`로 전달되는 composing span을 잘못 처리하는 앱이 있다. 흔한 예는 일부 원격 데스크톱 클라이언트, 게임 엔진 기반 입력창, 자체 렌더링 텍스트 필드, 불완전한 WebView/커스텀 `InputConnection`이다.

이번 구현의 목표는 두 가지다.

1. 한글 입력기인데 영문 QWERTY 키캡만 보이는 문제를 현대 두벌식에 한해 고친다.
2. 한글 조합은 fcitx5-hangul/libhangul 내부에서 계속 처리하되, 대상 앱에는 composing span을 보내지 않고 완성된 구간을 한 번에 전달한다.

중요한 비목표도 명확히 해둔다.

- 자체 한글 조합기를 새로 구현하지 않았다.
- fcitx5-hangul 또는 libhangul 네이티브 코드는 수정하지 않았다.
- 모든 게임·Canvas·OpenGL·Unity·원격 영상 화면에서 입력된다고 보장하지 않는다.
- Android가 실제 paste 성공 여부를 알려주지 않으므로 자동 다중 fallback은 구현하지 않았다.
- 세벌식, 옛글, 안마태 등 다른 한글 배열을 두벌식처럼 보이게 속이지 않는다.
- 버퍼를 디스크에 저장하거나 프로세스 재시작 뒤 복원하지 않는다.

## 3. 저장소와 플러그인 구조

### 3.1 주요 Gradle 모듈

`settings.gradle.kts` 기준으로 앱, 공통 라이브러리, 네이티브 fcitx5 계층, 입력기 플러그인이 분리되어 있다.

| 모듈 | 역할 |
| --- | --- |
| `:app` | Android IME 서비스, 키보드 UI, 설정, 클립보드, JNI 진입점 |
| `:lib:common` | 앱과 서비스 플러그인이 공유하는 Android 코드 |
| `:lib:fcitx5` | fcitx5 코어와 Android용 네이티브 의존성 |
| `:lib:plugin-base` | 플러그인 APK의 공통 manifest·소개 화면 기반 |
| `:plugin:hangul` | fcitx5-hangul과 libhangul을 담는 별도 APK |
| 그 외 `:plugin:*` | Rime, Anthy, Unikey 등 별도 입력기 플러그인 |

Hangul 플러그인은 `plugin/hangul/build.gradle.kts`에서 다음 convention plugin을 사용한다.

- Android 앱/플러그인 APK convention
- 네이티브 빌드 convention
- 빌드 메타데이터와 data descriptor 생성
- fcitx component 설치

`plugin/hangul/src/main/cpp/CMakeLists.txt`는 미리 빌드된 libhangul 정적 라이브러리를 가져오고, `fcitx5-hangul` 서브모듈을 빌드해 `hangul` addon을 설치한다. 입력기 엔진의 실제 조합 로직은 다음 파일에 있다.

```text
plugin/hangul/src/main/cpp/fcitx5-hangul/src/engine.cpp
```

여기서 `hangul_ic_process()`가 Latin QWERTY 위치의 keysym을 받아 조합하고, `ic_->commitString()`과 `inputPanel().setPreedit()`/`updatePreedit()`로 결과를 내보낸다. Caps Lock 상태는 엔진이 Latin 대소문자 변환을 한 번 되돌린 뒤 libhangul에 넘기므로, 화면 키캡도 Caps Lock을 쌍자음 Shift 상태로 취급하면 안 된다.

### 3.2 플러그인 APK 발견과 로딩

Hangul 플러그인의 메타데이터는 다음 파일에 있다.

```text
plugin/hangul/src/main/res/xml/plugin.xml
```

현재 API 버전은 `0.1`, gettext domain은 `fcitx5-hangul`이다. `DataManager.detectPlugins()`는 현재 앱 build variant에 맞는 `${applicationId}.plugin.MANIFEST` intent를 노출한 APK를 찾고 `plugin.xml`을 파싱한다. 그 뒤 플러그인의 native library directory와 data descriptor를 fcitx 런타임에 연결한다.

Debug와 release는 패키지와 intent action이 다르다.

```text
Debug main app:      org.fcitx.fcitx5.android.debug
Debug Hangul plugin: org.fcitx.fcitx5.android.plugin.hangul.debug
Release main app:    org.fcitx.fcitx5.android
Release Hangul:      org.fcitx.fcitx5.android.plugin.hangul
```

따라서 main app과 Hangul plugin은 반드시 같은 build variant로 설치해야 한다. 다른 서명으로 이미 설치된 동일 패키지를 업데이트하면 Android가 설치를 거부할 수 있고, 서비스형 플러그인은 signature permission도 고려해야 한다. 현재 Hangul plugin은 `hasService=false`인 네이티브 addon이라 main/plugin 간 동일 signer를 로딩 조건으로 요구하지 않는다. Variant 혼합은 지원하지 않으며, 서로 다른 배포 소스를 섞을 때는 로딩 자체와 동일 package 업데이트 호환성을 구분해서 검증해라.

### 3.3 입력 이벤트의 원래 경로

정상 모드의 핵심 경로는 다음과 같다.

```text
TextKeyboard / physical KeyEvent
  -> CommonKeyActionListener
  -> Fcitx.sendKey(...)
  -> JNI: app/src/main/cpp/native-lib.cpp
  -> AndroidFrontend::keyEvent(...)
  -> fcitx5-hangul engine.cpp
  -> libhangul
  -> AndroidFrontend callbacks
  -> FcitxEvent.CommitString / ClientPreedit / InputPanel / KeyEvent
  -> FcitxInputMethodService
  -> target InputConnection
```

기존 클립보드 창과 키보드 툴바의 문구 삽입은 이름과 달리 대체로 `service.commitText()` 경로다. 실제 Android paste action을 호출하던 기존 예는 편집용 `TextEditingWindow`의 `performContextMenuAction(android.R.id.paste)` 정도였다. 따라서 기존 Fcitx 클립보드 항목 선택만으로는 깨진 IME 앱을 우회한다고 볼 수 없다.

## 4. 현재 구현 아키텍처

이번 구현은 표시, 조합, 버퍼, 전달을 분리했다.

```text
┌──────────────────────────────────────────────────────────────┐
│ Keyboard UI                                                 │
│ HangulKeyLegends + KeyboardWindow + TextKeyboard            │
│ - 키캡만 한글로 그림                                         │
│ - key action은 원래 Latin QWERTY keysym 유지                 │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Native Hangul composition                                   │
│ fcitx5-hangul + libhangul                                   │
│ - 조합 중 preedit                                            │
│ - 확정된 CommitString                                        │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Compatibility buffer in :app                               │
│ BufferedHangulMode + BufferedInputController                │
│ - target에는 Preedit capability를 숨김                       │
│ - CommitString을 프로세스 메모리에 누적                       │
│ - prefix + engine preedit을 Fcitx 내부 패널에만 표시          │
└──────────────────────────────┬───────────────────────────────┘
                               │ segment boundary
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Explicit delivery transport                                 │
│ FcitxInputMethodService                                     │
│ - System paste / Ctrl+V / Direct commit                     │
│ - 성공 여부가 모호하면 자동 fallback 금지                    │
└──────────────────────────────────────────────────────────────┘
```

### 4.1 활성화 조건

버퍼 모드는 아래 조건이 모두 참일 때만 활성화된다.

```text
설정 buffered_hangul_input == true
AND InputMethodEntry.addon == "hangul"
AND languageCode의 첫 구간 == "ko"
```

`ko`, `ko-KR`, `ko_KR`은 허용한다. 다른 addon이나 언어에서는 설정이 켜져 있어도 정상 경로를 유지한다.

### 4.2 Preedit 차단

활성 세션에서는 `CapabilityFlag.Preedit`만 제거한 capability mask를 fcitx에 전달한다. `ClientUnfocusCommit`, `SurroundingText` 등 다른 capability는 그대로 유지한다.

그 결과 Hangul preedit은 대상 `InputConnection.setComposingText()`로 가지 않고 Fcitx input panel에 남는다. 서비스의 `updateComposingText()`도 버퍼 세션에서는 즉시 반환해 capability 변경과 경합한 오래된 `ClientPreeditEvent`가 대상 앱으로 새어 나가지 않게 한다.

### 4.3 내부 버퍼

`BufferedInputController`는 엔진이 확정한 `CommitString`을 `StringBuilder`에 누적한다. 현재 화면에 보여줄 텍스트는 다음 두 부분을 이어 붙인다.

```text
finalized prefix + current engine preedit
```

예를 들어 libhangul이 앞 음절 `한`을 commit하고 현재 `글`을 preedit으로 들고 있다면 대상 앱은 빈 상태로 유지되고 Fcitx 패널에는 `한글`이 보인다.

Backspace 규칙은 다음과 같다.

- 엔진 preedit이 있으면 fcitx5-hangul이 먼저 조합 단계를 되돌린다.
- 엔진 preedit이 비었고 prefix가 있으면 prefix의 마지막 Unicode code point 하나를 지운다.
- 둘 다 비었으면 대상 editor의 일반 Backspace로 전달한다.

UTF-16 `Char` 하나가 아니라 code point 단위로 지우므로 surrogate pair인 emoji도 반쪽만 남기지 않는다. 다만 grapheme cluster 전체를 지우는 구현은 아니어서 ZWJ emoji나 결합 문자는 여러 번 눌러야 할 수 있다.

### 4.4 구간 제출 경계

현재 구현은 다음 이벤트에서 버퍼를 제출한다.

- Hangul 엔진이 소비하지 않고 forward한 Unicode 문자: 해당 문자를 prefix에 붙인 뒤 제출한다. 일반적으로 공백, 숫자, 문장부호가 여기에 들어간다.
- Return: 먼저 제출에 성공한 뒤 기존 Return 동작을 실행한다.
- Left/Right: 먼저 제출에 성공한 뒤 커서를 이동한다.
- 툴바·emoji·클립보드 항목 등 `service.commitText()`를 직접 호출하는 삽입: 기존 버퍼를 먼저 제출하고 직접 삽입한다.
- 입력기 전환, input view 종료, input 종료: 누수 방지를 위해 제출을 시도하고 엔진을 reset한다.
- 버퍼 모드 설정 끄기: 기존 editor-owned composing을 끝내고 버퍼를 제출한 뒤 capability를 갱신한다.
- Ctrl/Alt/Meta/Super/Hyper shortcut: 보류 중 한글을 `DirectCommit`으로 먼저 비우고 shortcut을 전달한다.

Up/Down, Tab, Home/End, 선택 확장 등 모든 navigation 동작을 별도로 정의한 것은 아니다. 실제 문제 앱에서 이 키들이 필요하면 상태 전이와 중복 제출 여부를 먼저 instrumentation test로 고정한 뒤 확장해라.

### 4.5 엔진 reset 경합 방지

현재 preedit을 snapshot해 제출한 뒤에는 같은 문자열이 다음 Fcitx event로 다시 들어오지 않도록 엔진 reset을 순차 작업 큐에 넣는다. `bufferedHangulEngineResetPending` 동안에는 오래된 cached preedit을 빈 값으로 취급한다.

전달이 명확하게 실패했을 때 현재 preedit 부분은 prefix로 옮긴 후 엔진을 reset한다. 그래야 사용자가 재시도할 때 동일 tail을 두 번 합치지 않는다. `postFcitxJob()`이 닫힌 channel에 lazy job을 넣지 못하면 해당 job을 취소하도록 보완한 것도 이 순차성 유지와 관련된 방어다.

## 5. 전달 방식과 절대 지켜야 할 불변조건

### 5.1 System paste

순서는 다음과 같다.

1. 완성된 구간 전체를 `ClipData.newPlainText()`로 만든다.
2. Fcitx 전용 transient label을 붙인다.
3. 지원 API에서는 `ClipDescription.EXTRA_IS_SENSITIVE=true`를 넣는다.
4. system clipboard의 primary clip으로 설정한다.
5. `InputConnection.performContextMenuAction(android.R.id.paste)`를 한 번 호출한다.
6. dispatch가 `true`면 예상 selection을 삽입 길이만큼 전진시킨다.

표준 Android editor에서 composing 처리만 깨진 경우 가장 먼저 시험할 방식이다.

### 5.2 Ctrl+V

System paste와 같은 transient clipboard를 준비한 다음 가상 Ctrl down, V down/up, Ctrl up을 전송한다. 성공 판정은 shortcut을 실제로 발생시키는 V key-down의 전송 결과만 사용한다. key-up 실패는 이미 paste가 실행됐는지 알 수 없으므로 자동 재전송하지 않는다.

이 방식은 표준 context-menu paste는 없지만 raw key shortcut을 받는 원격 클라이언트용 후보이다.

### 5.3 Direct commit

완성 구간 전체를 `InputConnection.commitText()`로 한 번 전달한다. 클립보드를 쓰지 않고 composing span도 만들지 않는다. 다만 `InputConnection` 자체가 없거나 `commitText()`를 무시하는 앱은 고칠 수 없다.

Password 또는 Sensitive capability가 있으면 사용자가 다른 전달 방식을 선택했어도 무조건 Direct commit으로 강제한다. 이번 변경은 숫자 비밀번호 variation도 `CapabilityFlag.Password`로 잡도록 보완했다.

### 5.4 핵심 불변조건

다음 규칙은 버그처럼 보여도 근거 없이 바꾸면 안 된다.

1. **Paste 후 자동 DirectCommit fallback을 넣지 마라.**
   `performContextMenuAction()`의 Boolean은 remote 호출 dispatch를 인정했는지 나타낼 뿐 대상 editor가 실제로 붙여넣었다는 뜻이 아니다. `true` 뒤 fallback하면 한 번 늦게 paste가 실행되어 중복 입력될 수 있다.

2. **`false`와 `true`를 다르게 해석해라.**
   `false`는 명확한 dispatch 실패이므로 버퍼를 보존할 수 있다. `true`는 결과 불명 상태지만 현재 구현은 한 번 전송된 것으로 보고 버퍼를 비운다. 대상이 무시하면 사용자에게는 입력 손실로 보일 수 있다. 이것이 향후 명시적 retry UI가 필요한 이유다.

3. **Paste 직후 이전 클립보드를 복원하지 마라.**
   remote `InputConnection` 처리는 비동기일 수 있다. 즉시 복원하면 대상이 원래 clip 또는 제3의 clip을 붙일 수 있다. 현재는 제출한 문자열을 system clipboard에 남긴다.

4. **민감 editor에서 global clipboard를 쓰지 마라.**
   sensitive flag는 OS와 clipboard UI에 주는 힌트일 뿐 완전한 보안 경계가 아니다. editor가 자신을 password/sensitive로 제대로 선언하지 않으면 자동 보호도 작동하지 않는다.

5. **한글 키캡을 보이게 하려고 key action을 자모 문자로 바꾸지 마라.**
   libhangul은 QWERTY 물리 위치에 해당하는 Latin keysym을 기대한다. 현재 변경은 label과 popup preview만 바꾼다.

6. **지원하지 않는 배열을 두벌식 label로 표시하지 마라.**
   세벌식은 26개 alphabet surface 바깥 키도 필요하고 Shift map도 다르다. 정확한 action map까지 준비되지 않으면 Latin fallback이 맞다.

7. **외부 selection 변화 뒤 옛 위치에 paste하지 마라.**
   버퍼가 대상 editor에 보이지 않기 때문에 원래 insertion anchor를 신뢰성 있게 복원할 수 없다. 현재는 예상하지 못한 selection 변화가 오면 미전송 구간을 버리고 엔진을 reset한다.

8. **버퍼를 다른 editor로 넘기지 마라.**
   focus/input lifecycle에서 전송 실패 후 clear하는 것은 데이터 보존보다 cross-editor 누수 방지를 우선한 정책이다.

관련 Android 구현을 다시 확인할 때는 아래 AOSP 코드를 출발점으로 삼아라.

- `IRemoteInputConnectionInvoker.performContextMenuAction()`
  - https://android.googlesource.com/platform/frameworks/base/+/419475c52973320045a86c20026adf08f9bd27c4/core/java/android/inputmethodservice/IRemoteInputConnectionInvoker.java
- `RemoteInputConnectionImpl.performContextMenuAction()`
  - https://android.googlesource.com/platform/frameworks/base/+/3ccbe8c15ac7b6800ea90dffcd5386fcce58116e/core/java/com/android/internal/inputmethod/RemoteInputConnectionImpl.java
- InputConnection end-to-end CTS
  - https://android.googlesource.com/platform/cts/+/f7679a054c594286f0b2692e69a2d7ebe2831685/tests/inputmethod/src/android/view/inputmethod/cts/InputConnectionEndToEndTest.java

## 6. Lifecycle과 상태 전이

### 6.1 입력 시작과 restart

`onStartInput()`은 새 `EditorInfo`에서 capability를 다시 만든다. 기존 버퍼 세션이 있고 같은 editor의 restart라면 먼저 제출을 시도한다. 이전 또는 새 editor가 sensitive이면 이 restart flush도 Direct commit으로 강제한다.

전달이 명확히 실패했고 다음 세션도 buffered Hangul이면 버퍼를 보존한다. 그 외에는 새 editor가 이전 텍스트를 받지 않도록 clear한다. 그 뒤 selection과 composing 상태를 초기화하고 `Preedit`가 제거된 capability를 fcitx에 전달한다.

### 6.2 input method 변경

기존 입력기가 buffered Hangul이고 새 입력기는 아니라면 먼저 제출한다. 새 `InputMethodEntry`로 활성 여부를 다시 계산하고 buffer를 정리한다. Android 14+ subtype 동기화에서 subtype을 찾지 못했을 때 함수 전체를 조기 종료하던 흐름도 `?.let`으로 바꿔, 이후 buffered 상태 갱신이 빠지지 않도록 했다.

### 6.3 selection 변경

System paste, Ctrl+V, Direct commit은 Android가 사용하는 UTF-16 offset 기준으로 예상 selection을 등록한다. 다음 `onUpdateSelection()`이 예상과 맞으면 정상 전송 결과로 소비한다.

예상과 다른 selection이 오고 prefix 또는 engine preedit이 남아 있으면 다음을 수행한다.

- `Discarding buffered Hangul after an external selection change` 로그 기록
- prefix clear
- 필요 시 engine reset 예약
- 내부 preedit UI 갱신
- 새 selection을 기준 위치로 저장

대상 앱의 `InputFilter`, 자동 포맷팅, 비정상 cursor report가 예상 offset을 바꾸는 앱에서는 이 정책이 과도하게 discard할 수 있다. 해당 앱을 지원하려면 editor별 추측을 넣기 전에 recording `InputConnection`과 실제 앱 재현 테스트를 추가해라.

### 6.4 키보드 숨김과 input 종료

`onFinishInputView()`는 buffered 세그먼트를 한 번 제출한다. 단순 키보드 hide라서 `finishingInput=false`이면 세션 활성 상태는 유지한다. 실제 input 종료이면 buffer와 physical-key tracking을 clear한다. 이어 fcitx engine을 reset하고 focus를 정리한다.

`onFinishInput()`도 제출을 시도한 뒤 무조건 세션을 끄고 clear한다. `onUnbindInput()`은 다른 client로 넘어갈 가능성이 있으므로 제출하지 않고 buffer와 physical key 상태를 제거한다. 프로세스가 죽어도 buffer는 사라진다.

실기기에서 hide/show 경로가 정확히 한 번만 flush되는 것은 확인했지만, rotation·multi-window·프로세스 kill·브라우저 tab 이동까지 모두 확인한 것은 아니다.

## 7. 한글 키캡 구현 세부사항

### 7.1 배열 판별

`KeyboardWindow.updateInputMethod()`가 현재 Hangul `InputMethodEntry.uniqueName`으로 `getImConfig()`를 호출하고 `cfg/Keyboard` 값을 읽는다. 비동기 요청 도중 입력기가 바뀌는 경우를 막기 위해 증가하는 request 번호를 사용하며, 오래된 응답은 버린다.

현재 정확히 문자열 `Dubeolsik`만 지원한다. 값이 없거나 다른 배열이면 `HangulKeyLegends.legend()`가 `null`을 반환하고 기존 Latin label을 쓴다.

### 7.2 표시와 action 분리

`TextKeyboard`는 alphabet main label과 popup preview를 `HangulKeyLegends`로 변환한다. `KeyAction`에 들어 있는 Latin label/keysym은 그대로 유지된다.

Shift 상태는 다음과 같다.

- 평상시: `ㅂㅈㄷㄱㅅ...`
- one-shot Shift: `ㅃㅉㄸㄲㅆ`, `ㅒ`, `ㅖ`
- Caps Lock: 평상시 한글 label 유지

Caps Lock을 normal label로 두는 이유는 앞서 설명한 것처럼 fcitx5-hangul 엔진이 raw Caps Lock 변환을 되돌리기 때문이다. 이 동작을 바꾸려면 반드시 실제 key event와 libhangul 결과를 함께 시험해라.

## 8. 파일별 변경 내역

### 8.1 앱 소스와 리소스

| 파일 | 변경 내용 | 다음 작업 시 주의점 |
| --- | --- | --- |
| `README.md` | 현재 pinned Android 도구 버전과 상세 Hangul 문서 링크 추가 | upstream 버전이 바뀌면 `Versions.kt`와 함께 갱신 |
| `app/src/main/java/org/fcitx/fcitx5/android/core/CapabilityFlag.kt` | 숫자 password variation에도 Password capability 설정 | clipboard 보호가 이 flag에 의존함 |
| `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt` | transient buffered paste clip을 Fcitx 자체 history에서 제외 | system clipboard나 타사 history까지 막지는 못함 |
| `app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardMarkers.kt` | transient label 상수와 판별 extension 추가 | label은 보안 토큰이 아니라 내부 분류 표식임 |
| `app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt` | Advanced에 enable switch와 transport enum preference 추가 | 기본값은 off, 기본 transport는 SystemPaste |
| `app/src/main/java/org/fcitx/fcitx5/android/input/BufferedHangulMode.kt` | 활성화 정책, Preedit capability 제거, 민감 필드 판단 | 가능한 한 pure policy로 유지해 unit test 가능하게 할 것 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/BufferedInputController.kt` | finalized prefix 누적·snapshot·code-point 삭제·clear | editor나 Android API를 넣지 말고 순수 상태 객체로 유지 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/BufferedInputTransport.kt` | SystemPaste/CtrlV/DirectCommit enum과 문자열 리소스 연결 | 자동 fallback 순서를 나타내는 enum이 아님 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt` | 버퍼 세션, event interception, UI decoration, 세 transport, selection 예측, lifecycle, shortcut·physical key 처리 | 가장 위험한 파일. 변경 전 상태 전이와 중복/누수 시나리오를 테스트로 고정할 것 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt` | InputPanel event를 service에서 decorate하고 강제 refresh 가능하게 함 | target composing이 아니라 Fcitx 내부 표시용 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt` | buffered session에서 CommitAction의 기존 `commitAndReset` 경합을 피하고 service가 순서를 책임지게 함 | emoji·toolbar insert 순서가 buffer보다 앞서지 않아야 함 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/HangulKeyLegends.kt` | modern Dubeolsik normal/Shift label map, IM 판별, 안전한 fallback | 세벌식 지원 시 별도 action/layout 모델 필요 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt` | active IM config의 `cfg/Keyboard` 비동기 조회와 stale request guard | input method를 빠르게 바꿀 때 오래된 배열이 적용되지 않아야 함 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt` | main key와 popup preview에 Hangul legend 적용, Caps 상태 반영 | 실제 key action은 Latin으로 유지 |
| `app/src/main/res/values/strings.xml` | 영어 실험 기능·transport 문자열 추가, 신규 문자열의 미번역 lint 억제 | 전체 번역 정책을 정하면 `tools:ignore` 재검토 |
| `app/src/main/res/values-ko/strings.xml` | 동일 설정의 한국어 번역 추가 | 사용자에게 clipboard overwrite를 더 명확히 알리는 문구는 후속 과제 |

### 8.2 테스트와 문서

| 파일 | 검증 내용 |
| --- | --- |
| `app/src/test/java/org/fcitx/fcitx5/android/core/CapabilityFlagsTest.kt` | 숫자 비밀번호가 Password capability로 분류되는지 |
| `app/src/test/java/org/fcitx/fcitx5/android/input/BufferedHangulModeTest.kt` | Hangul에서만 활성화, Preedit만 제거, Password/Sensitive clipboard 금지 |
| `app/src/test/java/org/fcitx/fcitx5/android/input/BufferedInputControllerTest.kt` | prefix+preedit 결합, Unicode code point 삭제, clear |
| `app/src/test/java/org/fcitx/fcitx5/android/input/keyboard/HangulKeyLegendsTest.kt` | 두벌식 normal/Shift mapping, unsupported fallback, 언어·addon 판별 |
| `docs/hangul-buffered-input.md` | 사용자·개발자용 요약, 빌드 절차, test matrix, 알려진 위험 |
| `docs/hangul-buffered-input-handoff.md` | 현재 파일. 다음 작업자를 위한 상세 SSOT |

### 8.3 수정하지 않은 중요 영역

다음 영역은 빌드·분석은 했지만 코드 변경은 없다.

- `plugin/hangul/src/main/cpp/fcitx5-hangul/src/engine.cpp`
- libhangul prebuilt와 data
- `app/src/main/cpp/androidfrontend/*`
- `app/src/main/cpp/native-lib.cpp`
- 기본 Android keyboard layout XML/action map

따라서 문제가 native 조합 결과 자체에 있으면 이번 Kotlin buffer 계층만 고쳐서는 해결되지 않는다. 반대로 현재 목표는 정상적인 libhangul 조합 결과를 다른 방식으로 전달하는 것이므로, native 코드를 먼저 fork하지 않은 결정은 의도적이다.

## 9. Windows 빌드 환경

### 9.1 검증된 버전

| 구성요소 | 검증 값 |
| --- | --- |
| OS | Windows 11 x64 |
| JDK | Temurin 17.0.14 |
| Gradle wrapper | 9.4.1 |
| Kotlin | 2.3.21 |
| Java bytecode target | 11 |
| Android compile/target SDK | 36 |
| Android min SDK | 23 |
| Android Build Tools | 36.1.0 |
| Android NDK | 28.0.13004108 |
| Android CMake | 3.31.6 |
| 개발 ABI | `arm64-v8a` |
| MSYS2 | UCRT64 |
| GNU gettext tools | 1.0 |
| extra-cmake-modules | 6.28.0-1 |
| pkgconf | 3.0.4 |
| adb | 1.0.41 / platform-tools 37.0.0 |

Android 버전의 source of truth는 `build-logic/convention/src/main/kotlin/Versions.kt`다. README보다 이 파일을 먼저 확인해라.

### 9.2 로컬 기능 브랜치 보존과 upstream clone

공식 저장소를 clone하면 upstream `master` 기준선만 받을 수 있다. 현재 `feat/hangul-buffered-input`은 공식 원격에 없으므로 아래 clone 명령만으로 구현 커밋이나 이 문서를 복구할 수 없다. 현재 `D:\workspace\fcitx5-android`를 보존한 채 별도 baseline이 필요할 때만 다른 빈 경로에 clone해라.

```powershell
git config --global core.symlinks true
git clone --recurse-submodules https://github.com/fcitx5-android/fcitx5-android.git D:\workspace\fcitx5-android-upstream
Set-Location D:\workspace\fcitx5-android-upstream
git submodule update --init --recursive
```

기능 브랜치를 다른 장비나 새 checkout으로 넘기려면 먼저 다음 중 하나를 완료해야 한다.

1. 사용자 승인을 받은 fork 또는 원격에 `feat/hangul-buffered-input`을 push한다.
2. 현재 브랜치와 `master`를 포함한 Git bundle을 별도 위치에 만들고 `git bundle verify`로 검증한다.
3. 두 로컬 커밋을 포함한 patch series를 만들고 별도 저장소에 적용 시험한다.

예를 들어 승인된 로컬 백업 위치에 bundle을 만들 때는 덮어쓰기를 피하도록 새 파일명을 사용한다.

```powershell
Set-Location D:\workspace\fcitx5-android
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Bundle = "D:\workspace\fcitx5-hangul-buffered-$Stamp.bundle"
git bundle create $Bundle feat/hangul-buffered-input master
git bundle verify $Bundle
```

Bundle이 없고 원격에도 push되지 않은 상태에서 현재 checkout을 잃으면 기능 브랜치는 공식 origin에서 복구할 수 없다.

Windows Developer Mode와 Git symlink 지원을 먼저 켜라. symlink가 일반 텍스트 파일로 checkout되면 네이티브 빌드에서 이해하기 어려운 오류가 연쇄적으로 난다.

현재 주요 submodule 중 Hangul 엔진은 다음 revision이다.

```text
plugin/hangul/src/main/cpp/fcitx5-hangul
487cd25d20cd2e32296c3191de486275be8d99cd
```

submodule이 `-`로 표시되거나 modified marker가 있으면 바로 빌드하지 말고 먼저 원인을 확인해라.

### 9.3 MSYS2 도구 설치

```powershell
winget install --exact --id MSYS2.MSYS2
C:\msys64\usr\bin\pacman.exe -Syu --noconfirm
# msys2-runtime 갱신으로 첫 실행이 끝났다면 한 번 더 실행
C:\msys64\usr\bin\pacman.exe -Syu --noconfirm
C:\msys64\usr\bin\pacman.exe -S --needed --noconfirm `
  mingw-w64-ucrt-x86_64-gettext `
  mingw-w64-ucrt-x86_64-extra-cmake-modules `
  mingw-w64-ucrt-x86_64-pkgconf
```

설치 확인:

```powershell
C:\msys64\usr\bin\pacman.exe -Q `
  mingw-w64-ucrt-x86_64-gettext `
  mingw-w64-ucrt-x86_64-extra-cmake-modules `
  mingw-w64-ucrt-x86_64-pkgconf
C:\msys64\ucrt64\bin\msgfmt.exe --version
C:\msys64\ucrt64\bin\pkgconf.exe --version
```

패키지 질의 결과에서 gettext가 `mingw-w64-ucrt-x86_64-gettext-tools`라는 실제 패키지명으로 보일 수 있다.

### 9.4 Android SDK 설치

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
  throw 'Android SDK Command-line Tools are missing. Install them from Android Studio SDK Manager first.'
}
& $sdkManager --install `
  "platforms;android-36" `
  "build-tools;36.1.0" `
  "ndk;28.0.13004108" `
  "cmake;3.31.6" `
  "platform-tools"
```

### 9.5 매 PowerShell 세션에서 고정할 환경

```powershell
Set-Location D:\workspace\fcitx5-android

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "C:\msys64\ucrt64\bin;$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

java -version
.\gradlew.bat --version
```

주의: 이 장비에서는 한 시점에 `java -version`은 PATH의 JDK 17을 가리키지만 `JAVA_HOME`은 JDK 24를 가리켜 Gradle Launcher/Daemon이 JDK 24를 쓰는 환경 drift가 관찰됐다. `java -version`만 믿지 말고 `gradlew --version`의 `Launcher JVM`과 `Daemon JVM`을 모두 확인해라. Java를 바꾼 뒤 이상하면 다음을 실행하고 다시 시작한다.

```powershell
.\gradlew.bat --stop
.\gradlew.bat --version
```

## 10. 빌드·테스트 명령과 현재 결과

### 10.1 feature 전용 unit test

다음 4개 class, 총 10개 테스트가 통과했다.

```powershell
.\gradlew.bat :app:testDebugUnitTest -PbuildABI=arm64-v8a `
  --tests 'org.fcitx.fcitx5.android.core.CapabilityFlagsTest' `
  --tests 'org.fcitx.fcitx5.android.input.BufferedHangulModeTest' `
  --tests 'org.fcitx.fcitx5.android.input.BufferedInputControllerTest' `
  --tests 'org.fcitx.fcitx5.android.input.keyboard.HangulKeyLegendsTest'
```

결과: **PASS, 10/10**

### 10.2 전체 app unit test

```powershell
.\gradlew.bat :app:testDebugUnitTest -PbuildABI=arm64-v8a
```

결과: **14/15 PASS, 1 FAIL**

실패 항목:

```text
org.fcitx.fcitx5.android.ThemeSerializationTest.version2
```

이 실패는 이번 변경과 무관한 upstream stale test다. `CustomThemeSerializer.CURRENT_VERSION`은 `2.1`인데 테스트는 version `2.0` 입력이 migration되지 않는다고 기대한다. 관련 파일은 구현 커밋에서 수정하지 않았다. 이 feature 브랜치에서 억지로 고치지 말고 별도 upstream 정리로 분리해라.

### 10.3 APK assembly

```powershell
.\gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a
.\gradlew.bat :plugin:hangul:assembleDebug -PbuildABI=arm64-v8a
```

결과:

- `:app:assembleDebug`: **PASS**
- `:plugin:hangul:assembleDebug`: **PASS**, fcitx5-hangul native build 포함

실기기 smoke test에 사용한 역사적 로컬 산출물:

| 산출물 | 크기 | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/org.fcitx.fcitx5.android-0.1.2-79-g0eb0e069-arm64-v8a-debug.apk` | 60,963,942 bytes | `BB6914AC5A22B4AA9786A5154B7E981F09CC26F943B20B56631B20240A4E47BD` |
| `plugin/hangul/build/outputs/apk/debug/org.fcitx.fcitx5.android.plugin.hangul-0.1.2-79-g0eb0e069-arm64-v8a-debug.apk` | 5,322,581 bytes | `E1AB647418FD5C1A0195101691944396699BA6C64EE14945F47EF868C0C0820E` |

이 APK들은 구현 변경이 아직 commit되지 않은 작업 트리에서 빌드되어 archive 이름의 Git suffix가 기준 커밋 `g0eb0e069`로 남아 있다. 위 hash는 현재 실기기에 설치해 smoke test한 로컬 파일을 식별하는 값일 뿐, 커밋 `5338d80a`의 재현 가능 산출물이나 배포 무결성 증거가 아니다. 구현 커밋에서 clean rebuild하면 파일명 또는 hash가 달라지는 것이 정상이다. 이 파일을 배포하지 말고, 새 배포 산출물은 clean commit에서 다시 빌드해 새 hash를 기록해라.

```powershell
Get-ChildItem app\build\outputs\apk\debug\*.apk,
  plugin\hangul\build\outputs\apk\debug\*.apk |
  ForEach-Object {
    [PSCustomObject]@{
      Path = $_.FullName
      Length = $_.Length
      SHA256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
    }
  } | Format-List
```

### 10.4 lint

현재 결과를 분리해서 읽기 쉽도록 app과 plugin lint를 별도 invocation으로 실행하는 방식을 권장한다.

```powershell
.\gradlew.bat :plugin:hangul:lintDebug -PbuildABI=arm64-v8a
.\gradlew.bat :app:lintDebug -PbuildABI=arm64-v8a
```

현재 결과:

- `:plugin:hangul:lintDebug`: **PASS**. 보고서에는 warning 5개가 있으나 error는 없다.
- `:app:lintDebug`: **FAIL**, 266 errors / 49 warnings.
- app lint는 upstream baseline이 없고 기존 번역·resource 문제를 대량 보고한다.
- 이번 feature가 추가·수정한 코드와 새 resource line에 연결된 finding은 0개로 확인했다.

보고서 위치:

```text
app/build/reports/lint-results-debug.html
app/build/reports/lint-results-debug.txt
app/build/reports/lint-results-debug.xml
plugin/hangul/build/reports/lint-results-debug.html
```

확인된 필수 제한은 Hangul plugin assembly와 Hangul plugin lint를 같은 Gradle invocation에 넣지 않는 것이다. 현재 upstream task graph에서 `generateDataDescriptor`와 `generateDebugLintReportModel` 사이의 implicit-dependency 검증 오류가 날 수 있다. app lint와 plugin lint만 함께 실행하는 것은 이 제한과 다르지만, 알려진 app lint 부채와 plugin 결과를 분리하려고 위에서는 별도로 실행한다.

### 10.5 정적 확인

```powershell
git diff --check 0eb0e0699b0309b5f197dfb2db5fb92eabbb7dfa..HEAD
```

결과: **PASS**

최종 코드 리뷰에서는 P1/P2급 결함을 발견하지 못했다. 다만 이는 실제 `InputConnection` instrumentation test나 문제 앱 검증을 대신하지 않는다.

## 11. 실기기 설치와 실행

### 11.1 설치 전 확인

```powershell
adb version
adb devices -l
adb shell getprop ro.product.cpu.abi
```

장치 ABI가 `arm64-v8a`인지 확인해라. 이 문서에는 무선 디버깅 연결 정보나 인증 정보를 기록하지 않는다.

### 11.2 설치

Gradle 설치 task를 쓰는 방법:

```powershell
.\gradlew.bat :app:installDebug -PbuildABI=arm64-v8a
.\gradlew.bat :plugin:hangul:installDebug -PbuildABI=arm64-v8a
```

또는 assemble 후 각각 `adb install -r <정확한 APK 경로>`를 실행해도 된다. main app과 Hangul plugin을 둘 다 설치한 뒤 main app을 열어 plugin sync가 일어나게 해라.

`INSTALL_FAILED_UPDATE_INCOMPATIBLE`가 나오면 같은 package의 기존 debug APK가 다른 signer로 설치된 상태일 가능성이 높다. 제거는 앱 설정과 사용자 데이터를 잃는 파괴적 조치이므로 바로 실행하지 말고 먼저 다음으로 정확한 package를 확인해라.

```powershell
adb shell pm list packages | Select-String 'org.fcitx.fcitx5.android'
```

기존 debug package 삭제가 정말 필요한 경우에만 대상 package를 명시해 제거하고, release Fcitx나 다른 배포판은 건드리지 마라.

### 11.3 Android에서 설정

1. Android 설정에서 `Fcitx5 (Debug)` 키보드를 활성화한다.
2. Fcitx5 debug 앱을 연다.
3. plugin 목록에서 Hangul debug plugin이 인식되는지 확인한다.
4. 입력 방법에 Hangul을 추가한다.
5. Hangul 옵션의 keyboard를 정확히 `Dubeolsik`으로 선택한다.
6. Fcitx5 debug IME로 전환한다.
7. `설정 > 고급 > 한글 버퍼 호환 모드`를 켠다.
8. 전달 방식을 하나씩 명시적으로 선택해 시험한다.

Debug IME component를 확인할 때는 다음 명령을 쓸 수 있다.

```powershell
adb shell ime list -s
```

가능하면 IME 활성화와 전환은 Android 설정 UI에서 해라. 강제 ADB 전환은 사용자가 쓰던 기본 키보드 상태를 바꿀 수 있다.

## 12. 완료된 실기기 검증

검증 장치:

```text
Samsung SM-F956N
Android 16
API 36
arm64-v8a
```

확인한 항목:

1. 현대 두벌식 normal key legend가 올바르게 보였다.
2. one-shot Shift에서 쌍자음과 `ㅒ`, `ㅖ` legend가 올바르게 보였다.
3. Android Settings 검색창에서 `한글`을 조합하는 동안 대상 editor 내용은 바뀌지 않았다.
4. 같은 시점에 Fcitx 내부 panel에는 `한글`이 보였다.
5. Space를 누르면 System paste로 정확히 한 번 `한글 `이 들어갔다.
6. 제출 뒤 내부 panel이 비워졌다.
7. `한글`을 내부 조합한 뒤 키보드를 숨기면 정확히 한 번 flush됐다.
8. 같은 editor에서 키보드를 다시 열어도 buffered mode가 유지됐다.
9. 다음 `테스트`는 다시 target에 노출되지 않고 내부 panel에만 남았다.
10. Space를 누른 뒤 최종 target 내용이 `한글 테스트 `가 됐다.

이 검증으로 **System paste + 표준 Android editor + 기본 lifecycle**은 동작한다고 볼 수 있다. 다음 항목은 아직 실기기 통과로 기록하면 안 된다.

- Ctrl+V transport의 실제 원격 앱 검증
- Direct commit transport의 실기기 matrix
- 실제 문제로 지목된 broken-IME 앱
- WebView/contenteditable
- Compose text field
- Unity/Canvas/game 입력창
- password와 numeric password에서 clipboard 불변 확인
- physical keyboard
- Caps Lock 실제 입력 결과
- rotation, multi-window, process kill
- Hanja mode와 WordCommit 옵션
- emoji ZWJ/combining mark 삭제 동작

## 13. 다음 실기기 검증 매트릭스

각 transport를 별도로 선택해서 시험해라. System paste가 되었다고 Ctrl+V나 Direct commit도 된다고 추정하지 마라.

| 대상 surface | System paste | Ctrl+V | Direct commit | 필수 관찰 |
| --- | --- | --- | --- | --- |
| Android `EditText` | 필수 | 선택 | 필수 | 한 번만 삽입, cursor 위치 |
| Jetpack Compose field | 필수 | 선택 | 필수 | composing span이 target에 없는지 |
| WebView input | 필수 | 선택 | 필수 | 공백·문장부호·Return |
| contenteditable | 필수 | 선택 | 필수 | selection report와 자동 포맷 |
| 실제 broken-IME 앱 | 필수 | 필수 | 필수 | 완성 음절 보존, 손실·중복 |
| 원격 데스크톱/client | 시도 | 필수 | 시도 | host에 구간 하나만 전달 |
| Unity/Canvas/game | 시도 | 시도 | 시도 | usable endpoint 존재 여부부터 확인 |
| text password | 금지 확인 | 금지 확인 | 필수 | clipboard sentinel 유지 |
| numeric password | 금지 확인 | 금지 확인 | 필수 | 새 Password flag 검증 |

권장 수동 시나리오:

```text
한글
한글 테스트
값123, 기호!?
겹받침: 닭 값 앉다
Shift: 까 따 빠 싸 짜 얘 예
emoji: 한😀글
긴 문장 500자 이상
```

각 시나리오에서 다음을 기록해라.

- target이 조합 중 바뀌었는가
- 내부 panel의 prefix와 preedit이 정확한가
- delimiter가 segment에 포함되어 한 번만 들어갔는가
- cursor가 삽입 뒤 정확한가
- target이 입력을 필터링/변환했는가
- 다음 글자를 입력할 때 이전 segment가 재등장하는가
- keyboard hide/show 또는 focus 이동 뒤 cross-editor 누수가 있는가
- clipboard가 어떻게 바뀌었는가

## 14. 디버깅 레시피

### 14.1 빌드가 JDK 때문에 이상할 때

증상:

- Gradle이 예상과 다른 Java를 사용한다.
- 같은 명령이 shell에 따라 통과/실패한다.

확인:

```powershell
Get-Command java | Format-List Source
java -version
"JAVA_HOME=$env:JAVA_HOME"
.\gradlew.bat --version
```

조치:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;C:\msys64\ucrt64\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat --stop
.\gradlew.bat --version
```

### 14.2 CMake가 ECM, gettext, pkgconf를 못 찾을 때

확인:

```powershell
Test-Path C:\msys64\ucrt64\bin\msgfmt.exe
Test-Path C:\msys64\ucrt64\share\ECM\cmake
Test-Path C:\msys64\ucrt64\bin\pkgconf.exe
C:\msys64\usr\bin\pacman.exe -Q `
  mingw-w64-ucrt-x86_64-gettext `
  mingw-w64-ucrt-x86_64-extra-cmake-modules `
  mingw-w64-ucrt-x86_64-pkgconf
```

UCRT64 `bin`을 PATH 앞쪽에 둔 새 PowerShell에서 다시 실행해라. 무작정 build directory를 지우기 전에 Gradle error가 host tool 탐색인지 Android CMake/NDK 문제인지 구분해라.

### 14.3 Hangul plugin이 보이지 않을 때

1. main과 plugin이 둘 다 debug인지 확인한다.
2. package 목록에서 `.debug` suffix를 확인한다.
3. Fcitx 앱의 plugin 화면을 다시 연다.
4. main app을 재실행해 data sync를 유도한다.
5. merged manifest에서 debug plugin action이 `org.fcitx.fcitx5.android.debug.plugin.MANIFEST`인지 확인한다.

```powershell
adb shell pm list packages | Select-String 'fcitx5.android'
Get-Content `
  plugin\hangul\build\intermediates\merged_manifests\debug\processDebugManifest\arm64-v8a\AndroidManifest.xml |
  Select-String 'package=|plugin.MANIFEST'
```

### 14.4 한글 입력기인데 QWERTY legend가 보일 때

의도된 fallback인지 먼저 판별한다.

- Hangul addon이고 언어가 `ko*`인가
- Hangul config의 `cfg/Keyboard`가 정확히 `Dubeolsik`인가
- `Dubeolsik Yetgeul`, `Sebeolsik 390` 등 unsupported 배열은 아닌가
- 입력기 전환 직후 비동기 config 응답이 도착했는가

중단점을 둘 위치:

```text
KeyboardWindow.updateInputMethod()
TextKeyboard.onInputMethodUpdate()
TextKeyboard.onHangulKeyboardLayoutUpdate()
HangulKeyLegends.legend()
```

Unsupported 배열에서 Latin이 보이는 것은 현재 정상이다.

### 14.5 버퍼 모드를 켰는데 target에 조합 중 한글이 보일 때

중단점/관찰 위치:

```text
BufferedHangulMode.isActive()
FcitxInputMethodService.bufferedHangulInputListener
FcitxInputMethodService.onStartInput()
FcitxInputMethodService.updateComposingText()
```

확인할 값:

```text
preference == true
ime.addon == "hangul"
ime.languageCode starts with ko
bufferedHangulSessionActive == true
effective capability에 Preedit 없음
```

설정 변경 직후 기존 editor composing을 끝내는 과정이 있으므로 새 입력 세션에서 다시 시험해라.

### 14.6 Space를 눌러도 target에 안 들어갈 때

1. 내부 panel에서 segment가 사라졌는지 확인한다.
2. 사라졌다면 API dispatch는 받아들였지만 target이 paste를 무시했을 수 있다.
3. 같은 target에서 Android의 수동 붙여넣기가 되는지 먼저 시험한다.
4. transport를 Direct commit 또는 Ctrl+V로 **사용자가 직접** 바꿔 각각 시험한다.
5. 자동 fallback 코드를 임시로라도 넣지 마라. 중복 여부를 판별할 수 없게 된다.

관련 warning 로그:

```text
Unable to dispatch buffered system paste
Unable to prepare buffered clipboard transport
```

### 14.7 입력이 두 번 들어갈 때

우선 확인할 사항:

- 한 segment에 두 transport가 실행되었는가
- `submitBufferedHangul()` 호출 뒤 lifecycle callback이 다시 제출했는가
- engine reset 전 cached preedit을 다시 snapshot했는가
- Ctrl+V key-down 뒤 임의 fallback을 추가했는가
- 대상 앱 자체가 paste와 key event를 중복 처리하는가

현재 설계에서는 dispatch `true` 뒤 buffer를 비우고 engine reset pending 동안 cached preedit을 무시한다. 이 규칙이 깨졌는지 먼저 본다.

### 14.8 입력이 갑자기 사라질 때

다음은 의도적 discard 조건이다.

- buffered composition 중 외부 selection 변화
- 다른 editor로 focus 이동
- input 종료 또는 unbind
- process death
- lifecycle flush가 실패한 뒤 cross-editor 누수 방지 clear

Logcat에서 다음 메시지를 찾아라.

```text
Discarding buffered Hangul after an external selection change
```

대상 앱이 자동으로 cursor를 보정하거나 text filter를 적용해 false-positive discard가 나는지 selection callback 값을 함께 기록해라.

### 14.9 Logcat 수집

```powershell
adb logcat -c
adb logcat -v time |
  Select-String 'FcitxInputMethodService|Fcitx|hangul|AndroidRuntime'
```

재현할 때 최소한 아래 순서를 시간과 함께 기록해라.

```text
onStartInput
IMChangeEvent
InputPanelEvent / CommitStringEvent
submitBufferedHangul
transport dispatch
onUpdateSelection
onFinishInputView / onFinishInput / onUnbindInput
```

현재 모든 내부 상태 전이에 Timber log가 있는 것은 아니다. 로그를 추가할 경우 입력 문자열 원문, 비밀번호, clipboard 내용을 release log에 남기지 마라. 길이·상태·transport·event type 중심으로 기록해라.

## 15. 보안·개인정보·클립보드 위험

### 15.1 global clipboard overwrite

System paste와 Ctrl+V는 사용자의 기존 primary clipboard를 새 segment로 교체하고, 제출 뒤 그 문자열을 남긴다. 이는 버그가 아니라 비동기 paste race를 피하기 위한 현재 정책이다. 하지만 사용자 경험과 개인정보 측면에서 큰 비용이 있다.

현재 완화책:

- API가 지원하면 sensitive clip flag 설정
- Fcitx 자체 clipboard history에는 transient clip 저장 금지
- Password/Sensitive editor는 Direct commit 강제

남은 위험:

- 타사 clipboard manager가 clip을 수집할 수 있음
- OS 또는 OEM이 sensitive hint를 완전히 준수한다고 보장할 수 없음
- editor가 password/sensitive flag를 잘못 선언하면 보호가 작동하지 않음
- paste dispatch와 target 소비 사이에 다른 앱이 clipboard를 바꾸면 다른 문자열이 들어갈 수 있음
- 제출한 일반 텍스트가 사용자의 이전 clipboard를 영구적으로 덮음

사용자 공개 기능으로 만들기 전에는 설정 설명에 이 동작을 명확히 밝혀야 한다. 기본값 off는 유지하는 편이 맞다.

### 15.2 transient label의 한계

`TRANSIENT_BUFFERED_PASTE_LABEL`은 Fcitx 자신의 history listener가 이 clip을 건너뛰게 하는 표식이다. 인증, 암호화, 접근 통제가 아니다. 다른 앱은 label을 읽거나 무시할 수 있다.

### 15.3 메모리 버퍼

버퍼는 process memory에만 있고 디스크에 저장하지 않는다. 장점은 cross-session 잔존과 백업 유출을 줄인다는 점이다. 단점은 process death와 강제 종료에서 입력이 복구되지 않는다는 점이다. 복구 기능을 추가하더라도 평문 영속화부터 하지 마라.

### 15.4 로그와 테스트 데이터

실제 사용자 문장, 계정, 비밀번호, 원격 호스트 정보는 test fixture와 log에 넣지 마라. recording `InputConnection` test는 고정된 무해한 문자열을 사용하고, 실기기 로그 공유 전 내용을 검토해라.

## 16. 알려진 기능 한계와 실패 모드

1. **Paste 결과를 확인할 수 없음**
   dispatch `true`인데 target이 무시하면 buffer는 비워져 입력이 사라진 것처럼 보인다.

2. **완전히 비표준 surface**
   Canvas, OpenGL, Unity, game, 원격 영상 화면은 context paste, raw Ctrl+V, usable InputConnection을 모두 제공하지 않을 수 있다.

3. **Global clipboard 손실**
   일반 editor에서 System paste/Ctrl+V를 쓰면 이전 clipboard가 사라진다.

4. **외부 cursor 변화 시 discard**
   안전을 위해 원래 insertion anchor 복구를 포기했다.

5. **프로세스 종료 시 buffer 손실**
   영속화하지 않는다.

6. **두벌식만 localized legend 지원**
   Yetgeul, 세벌식, 안마태 등은 Latin fallback이다.

7. **Physical keyboard 미검증**
   forwarded down/up deduplication 코드는 있으나 실기기 테스트가 없다.

8. **Grapheme 삭제 미지원**
   code point 단위라 복합 emoji/결합 문자를 한 번에 지우지 않는다.

9. **Hanja/WordCommit 미검증**
   native engine의 commit 경계가 달라질 수 있으므로 후보 선택과 buffer snapshot을 따로 시험해야 한다.

10. **Target-side 변환과 selection 예측**
    자동 대문자화, filter, formatting, maxLength가 실제 삽입 길이를 바꾸면 selection 예측과 어긋날 수 있다.

11. **명시적 submit/cancel/retry UI 없음**
    현재 구간 경계는 delimiter와 lifecycle에 의존한다.

12. **Instrumentation test 없음**
    service와 실제 InputConnection 사이의 비동기·lifecycle·key down/up은 unit test만으로 충분히 보장할 수 없다.

## 17. 알려진 upstream 빌드 실패

이 feature와 무관한 실패를 숨기거나 feature 성공으로 포장하지 마라.

### 17.1 전체 unit test 1건

```text
ThemeSerializationTest.version2
expected: migration shouldn't happen
actual cause: CURRENT_VERSION is 2.1 while fixture is 2.0
```

총 15개 중 14개가 통과하고 이 1개가 실패한다. feature 전용 10개는 모두 통과한다.

### 17.2 app lint

```text
266 errors
49 warnings
baseline 없음
```

대표적으로 기존 번역 누락, format type 불일치, resource lint가 포함된다. 새 feature line의 finding은 없었다. 이 브랜치에서 lint 315건을 한꺼번에 고치면 feature diff와 리뷰 범위가 오염된다. 별도 cleanup branch가 맞다.

### 17.3 plugin task graph

Plugin assemble와 lint를 한 invocation에 섞으면 Gradle implicit dependency validation이 실패할 수 있다. 명령을 나누면 각각 통과한다. 빌드 스크립트의 task dependency를 고치는 작업도 별도 concern으로 분리해라.

## 18. Git 기준선과 배포 상태

변하지 않는 구현 기준선은 다음과 같다.

```text
branch: feat/hangul-buffered-input
base:   0eb0e0699b0309b5f197dfb2db5fb92eabbb7dfa
implementation commit: 5338d80ac247a21b6831626d4b3ae09710f1c65b
branch upstream: none
official origin contains implementation commit: no
```

이 문서를 포함하는 브랜치 tip은 문서 수정이나 후속 작업에 따라 달라질 수 있으므로 hash나 ahead 수를 본문에 고정하지 않는다. 아래 명령을 현재 상태의 단일 근거로 사용해라.

```powershell
git status --short --branch
git rev-parse HEAD
git rev-list --left-right --count origin/master...HEAD
git branch -vv
```

원격 push나 PR 생성은 아직 하지 않았다. 다음 작업자는 사용자 승인 없이 공개 원격에 push하지 마라. 작업 브랜치가 로컬에만 있으므로 현재 checkout을 보존하고, 장기 보관이나 다른 장비 인계가 필요하면 9.2절의 승인된 원격 또는 검증된 bundle/patch 절차를 먼저 완료해라.

배포 전 최소 gate:

1. branch와 diff scope 확인
2. feature 10 tests PASS
3. app assemble PASS
4. Hangul plugin assemble PASS
5. Hangul plugin lint PASS
6. `git diff --check` PASS
7. 새 APK hash 기록
8. main/plugin 동일 variant 확인
9. 최소 표준 editor System paste 실기기 재검증
10. 실제 목표 앱에서 transport별 결과 기록
11. clipboard overwrite와 experimental 성격을 사용자에게 고지

Debug APK는 개발·검증용이다. release 배포에는 release signing, update compatibility, 앱·플러그인 배포 조합, 개인정보 안내, Play/F-Droid 정책을 별도로 검토해야 한다.

## 19. 다음 작업 우선순위

### P0: 실제 문제 앱에서 transport matrix 작성

앱 이름·버전·Android 버전·surface 종류·transport·결과를 표로 남겨라. “붙여넣기 됨”만 적지 말고 중복, 손실, cursor, clipboard, lifecycle을 기록한다.

### P0: recording InputConnection instrumentation test

최소한 다음 contract를 자동화해라.

- buffered mode에서는 `setComposingText()` 호출 0회
- System paste는 clip 설정 후 paste action 1회
- paste action `false`면 buffer 보존
- paste action `true` 뒤 Direct commit 자동 호출 0회
- Ctrl+V main key-down 1회와 modifier 순서
- Direct commit 1회
- Password/Sensitive/Numeric password에서 clipboard 변경 0회
- Return/Left/Right는 flush 성공 뒤 editor action
- physical key down/up이 중복 전달되지 않음
- input restart/finish/unbind에서 cross-editor leak 없음
- 예상 selection과 외부 selection 변화 분리

Service private method를 억지 reflection으로 시험하기보다 transport dispatcher와 session state machine을 작은 interface/class로 추출하는 편이 장기적으로 낫다. 단, 리팩터링 전에 현재 동작을 characterization test로 고정해라.

### P1: 사용자 제어 추가

실제 앱에서 delimiter 기반 제출이 부족하면 다음을 검토해라.

- “지금 보내기” 버튼
- “버리기” 버튼
- 마지막 명확한 실패에 대한 retry
- 현재 transport와 clipboard 사용 여부 표시
- target 결과를 확인할 수 없다는 짧은 안내

자동 fallback은 여전히 넣지 않는다.

### P1: clipboard UX와 보안 강화

- 설정 summary에서 기존 clipboard가 교체된다는 사실 고지
- sensitive field detection 추가 점검
- 다른 clipboard observer와 OEM 동작 조사
- 사용자가 Direct commit을 기본으로 선택하기 쉽게 설명
- clipboard를 쓰지 않는 앱별 transport profile 가능성 검토

즉시 restore는 금지한다. 지연 restore도 target 소비 완료를 증명할 수 없다면 같은 race를 늦출 뿐이다.

### P1: physical keyboard와 shortcut 검증

현재 코드는 forwarded physical key의 down/up을 추적하고 shortcut 전에 Direct commit flush를 한다. 실제 Bluetooth/USB keyboard에서 다음을 시험해라.

- 한글 조합과 Backspace
- Ctrl+C/V/X/Z
- Alt/Meta/Super 조합
- key repeat
- modifier sticky state
- input method 전환 shortcut

### P2: 추가 한글 배열

배열마다 다음 세 가지를 함께 구현해야 한다.

1. 표시 legend
2. Shift/AltGr 등 상태별 action map
3. 현재 keyboard surface에 없는 숫자·기호 키 배치

세벌식 390/최종, 옛글, 안마태를 `supportedLayouts`에 이름만 추가하지 마라. libhangul keymap과 실제 입력 결과를 근거로 별도 테스트를 만들어라.

### P2: upstream 정리 분리

- stale theme serialization test
- app lint baseline 또는 기존 315개 issue
- plugin assemble/lint task dependency

이 세 가지는 feature 기능 검증과 분리된 커밋 또는 별도 PR이 적절하다.

## 20. 재개 체크리스트

다음 작업 시작 시 위에서부터 순서대로 확인해라.

- [ ] `D:\workspace\fcitx5-android`가 로컬 기능 커밋을 보유한 현재 작업본인지 확인
- [ ] branch가 `feat/hangul-buffered-input`인지 확인
- [ ] HEAD와 base hash 확인
- [ ] 예상하지 못한 working-tree 변경 보존 및 소유자 확인
- [ ] 모든 submodule initialized/clean 확인
- [ ] `JAVA_HOME`과 Gradle Launcher/Daemon이 JDK 17인지 확인
- [ ] SDK 36, Build Tools 36.1.0, NDK 28.0.13004108, CMake 3.31.6 확인
- [ ] MSYS2 UCRT64 gettext/ECM/pkgconf 확인
- [ ] feature 10 unit tests 재실행
- [ ] app과 Hangul plugin을 별도 assemble
- [ ] Hangul plugin lint 실행
- [ ] app lint의 known upstream 수치가 달라졌는지 기록
- [ ] APK hash 새로 계산
- [ ] main/plugin debug variant 둘 다 설치
- [ ] Hangul plugin과 Dubeolsik config 인식 확인
- [ ] normal/one-shot Shift legend 확인
- [ ] System paste 표준 editor smoke test
- [ ] 실제 broken-IME 앱에서 세 transport 각각 확인
- [ ] clipboard와 password privacy 확인
- [ ] hide/show, focus, cursor, restart, rotation 확인
- [ ] 새 실패는 logcat과 정확한 event 순서로 기록
- [ ] 자동 fallback·즉시 clipboard restore를 추가하지 않았는지 리뷰
- [ ] 구현·테스트·문서가 같은 사실을 말하는지 확인

## 21. 용어집

| 용어 | 이 프로젝트에서의 의미 |
| --- | --- |
| IME | Android 입력기 서비스. 여기서는 Fcitx5 for Android |
| InputConnection | IME가 대상 editor에 텍스트·key·selection 요청을 보내는 Android 인터페이스 |
| composing span | 아직 확정되지 않은 조합 문자열을 target editor 안에 표시하는 범위 |
| preedit | 입력 엔진이 조합 중인 문자열. 정상 모드에서는 client preedit, 버퍼 모드에서는 Fcitx panel에만 표시 |
| CommitString | fcitx 엔진이 확정 문자열을 Android frontend에 알리는 event |
| input panel | Fcitx 내부의 preedit·candidate 표시 모델 |
| forward key | 입력 엔진이 소비하지 않고 frontend/editor 쪽으로 넘긴 key event |
| capability | editor 특성과 frontend 기능을 fcitx에 알리는 bit flag |
| Preedit capability | target editor에 client preedit을 표시할 수 있음을 뜻하는 flag |
| buffered prefix | 엔진이 이미 commit했지만 target에는 아직 보내지 않은 문자열 |
| segment | prefix와 현재 preedit, 필요 시 delimiter를 합쳐 한 번에 전달하는 단위 |
| transport | segment를 target에 전달하는 명시적 방법 |
| System paste | system clipboard를 설정하고 Android context-menu paste action 호출 |
| Ctrl+V | system clipboard를 설정하고 Ctrl+V key sequence 전송 |
| Direct commit | composing 없이 `commitText()` 한 번으로 segment 전달 |
| dispatch success | Android remote 요청을 보냈다는 뜻. target 반영 성공과 동일하지 않음 |
| selection prediction | 전송 뒤 예상 cursor 위치를 미리 기록해 다음 callback과 대조하는 것 |
| transient clip | buffer 전달용으로 만든 임시 성격의 clip. 현재 system clipboard에는 남음 |
| Dubeolsik | 현대 한국어 표준 두벌식 QWERTY 배열 |
| one-shot Shift | 한 글자 입력 후 풀리는 Shift 상태 |
| code point | Unicode 문자 값 단위. UTF-16 code unit 또는 grapheme cluster와 다름 |
| input restart | 같은 editor가 `onStartInput(..., restarting=true)`로 session 정보를 갱신하는 상황 |
| cross-editor leak | 한 editor에서 조합한 미전송 문자열이 다른 editor에 들어가는 보안·정확성 문제 |

## 22. 최종 판단

현재 브랜치는 “현대 두벌식 키캡 표시”와 “대상 editor에 composing span을 보내지 않는 실험적 buffered Hangul 경로”의 첫 작동 버전이다. 표준 Android editor의 System paste 흐름과 키보드 hide/show lifecycle은 실제 장치에서 확인됐다.

다만 아직 범용 우회 키보드라고 부를 단계는 아니다. 가장 큰 미해결점은 Android paste API가 실제 target 반영 결과를 제공하지 않는다는 점, global clipboard를 덮는다는 점, 실제 문제 앱과 Ctrl+V/Direct commit/physical keyboard matrix가 비어 있다는 점이다. 다음 작업의 최우선은 기능을 더 넓히는 것이 아니라 recording `InputConnection` test와 실제 문제 앱 결과로 현재 상태 전이를 고정하는 것이다.
