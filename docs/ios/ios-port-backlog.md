# 새글 iOS 포팅 실행 백로그

정본 TODO. 기계 판독 쌍은 [ios-port-backlog.json](ios-port-backlog.json).
설계 정본은 [ios-dual-platform-plan.md](ios-dual-platform-plan.md).
다음 세션은 [HANDOFF-ios-port.md](HANDOFF-ios-port.md).

상태: `pending` | `in_progress` | `done` | `blocked` | `cancelled`
우선순위: 같은 phase 안에서는 id 오름차순.
1차 공개 릴리스 = Phase 0–3을 한 트레인으로 묶는다(스토어에 두벌식만 올리지 않음).

---

## 잠긴 계약 (항목이 이걸 어기면 폐기)

D1 공유 코어 + 듀얼 네이티브 셸. Flutter/RN/CMP 키보드 금지.
D2 Fcitx5 iOS 금지. libhangul 또는 유니코드 동등 compositor.
D3 키보드 프로세스 Gemma 금지.
D4 Full Access 기본 끔. 1차 기능은 오프라인.
D5 1차 수익화 없음. 확장 광고/IAP 금지.
D6 iOS UI 클린룸. Android Kotlin UI 복사 금지.
D7 `setMarkedText` 기본 + 마킹 토글 폴백. Buffered paste 이식 금지.
D8 iOS 18+, iPad 1차는 도킹만.
P1 1차 = 자판 + 로컬 스마트 입력.
P2 모아키 제외 자판 전량.
P3 모아키는 법률 후.
P4 무료.
P5 AI/컴패니언/Gemma는 1차 밖.

번들: 호스트 `net.chanpaca.saegeul.ios`, 키보드 `net.chanpaca.saegeul.ios.keyboard`.
개발 팀: `L6BZF5NB99` (스파이크). 게시자 불일치는 `IOS-7-SIGN`.

---

## Phase 0 — 스파이크 (go/no-go)

목표: 실기기에서 두벌식 `ㄱ+ㅏ=가`, 보안 필드 시스템 키보드, 확장 메모리 생존.
비목표: 17종, AI, 테마, 스토어 제출.

| ID | 상태 | 제목 | 의존 |
|---|---|---|---|
| IOS-0-00 | done | 백로그·인수인계 문서 | |
| IOS-0-01 | done | 맥 미니·아이폰 환경 실측 | |
| IOS-0-02 | done | 서명 주체·LGPL 메모 (스토어 전 게이트, 스파이크 비차단) | |
| IOS-0-03 | done | 맥에 `saegeul-ios` 트리 동기화 | IOS-0-01 |
| IOS-0-04 | done | HangulCore Swift 패키지 + 두벌식 조합기 | |
| IOS-0-05 | done | HangulCore golden 테스트 (`dkssud`→안녕 등) | IOS-0-04 |
| IOS-0-06 | done | Xcode 호스트 앱 + Keyboard Extension 프로젝트 | IOS-0-03 |
| IOS-0-07 | done | 두벌식 키면 UI + Globe | IOS-0-06 IOS-0-04 |
| IOS-0-08 | done | `setMarkedText`/`insertText`/`deleteBackward` 어댑터 | IOS-0-07 |
| IOS-0-09 | done | `RequestsOpenAccess=false`, 네트워크 호출 0 | IOS-0-06 |
| IOS-0-10 | done | 시뮬레이터 두벌식 입력 | IOS-0-08 IOS-0-05 |
| IOS-0-11 | done | 아이폰 14 Pro Max 설치·Notes `ㄱ+ㅏ=가` | IOS-0-10 |
| IOS-0-12 | done | 비밀번호 필드에서 시스템 키보드 교체 | IOS-0-11 |
| IOS-0-13 | pending | 확장 메모리 베이스라인 (빈 키면) | IOS-0-11 |
| IOS-0-14 | pending | libhangul xcframework 스파이크 (클린룸 조합기와 병렬, 1차 필수는 동등 API) | IOS-0-03 |
| IOS-0-GO | pending | go/no-go 판정 | IOS-0-11 IOS-0-12 IOS-0-13 |

### IOS-0-00 백로그 문서

목표: 다음 에이전트가 이 파일만으로 착수 가능하게 한다.
완료: `HANDOFF-ios-port.md`, `ios-port-backlog.md`, `ios-port-backlog.json`, SSOT·위키 포인터.

### IOS-0-01 환경

실측 2026-09-13: SSH `vd-mac`, Xcode 26.6, iPhone 14 Pro Max paired, 팀 L6BZF5NB99, brew/cmake 없음, 여유 24GB.
완료 기준: 이 값이 HANDOFF에 기록됨.

### IOS-0-02 서명·LGPL 메모

목표: 스토어 전 쟁점만 적는다. 스파이크를 막지 않는다.
- 인증서 조직 `YONGSEOK LEE` vs 제품 게시자 `Yun Chan`.
- libhangul LGPL 정적 링크 vs 동적 프레임워크 vs 유니코드 클린룸.
- 스파이크는 클린룸 Swift 조합기를 쓴다. libhangul 링크는 IOS-0-14.
완료: `docs/ios/ios-port-legal-notes.md` 초안 1페이지. 법률 자문 아님을 명시.

### IOS-0-03 맥 트리

경로: `/Users/yunchan/workspace/saegeul-ios`.
Windows `ios/`를 scp/rsync. `.git` 전체·Android build 산출물은 올리지 않는다.
완료: 맥에서 `ls ios` 또는 해당 경로에 App/Keyboard/HangulCore가 있다.

### IOS-0-04 HangulCore 두벌식 조합기

파일: `ios/HangulCore/Sources/HangulCore/`
- `HangulSyllable.swift` — 유니코드 음절 공식: `0xAC00 + ((cho*21)+jung)*28+jong`
- `HangulComposer.swift` — 상태: empty / cho / cho+jung / cho+jung+jong. 복합모음·겹받침 분리.
- `DubeolsikMap.swift` — KS X 5002 두벌식. 쉬프트 쌍자음·ㅒ·ㅖ.
- `ComposerOutput.swift` — `preedit`, `commit`, `reset`, `backspace`.

플랫폼 I/O 없음. `Foundation`만. Android `MobileHangulComposer`를 복사하지 말 것(LGPL UI 파일). 알고리즘은 유니코드+공개 두벌식.

API (바꾸지 말 것):

```swift
public struct ComposerEvent: Equatable {
    public var commit: String
    public var preedit: String
}
public final class HangulComposer {
    public init()
    public func process(jamo: Character) -> ComposerEvent
    public func process(dubeolsikKey: Character, shift: Bool) -> ComposerEvent
    public func backspace() -> ComposerEvent
    public func flush() -> ComposerEvent  // commit remaining preedit
    public func reset()
    public var preedit: String { get }
}
```

복합모음: ㅗ+ㅏ=ㅘ, ㅗ+ㅐ=ㅙ, ㅗ+ㅣ=ㅚ, ㅜ+ㅓ=ㅝ, ㅜ+ㅔ=ㅞ, ㅜ+ㅣ=ㅟ, ㅡ+ㅣ=ㅢ.
겹받침: ㄳㄵㄶㄺㄻㄼㄽㄾㄿㅀㅄ. 모음이 오면 마지막 자모를 다음 초성으로 분리.

### IOS-0-05 Golden

파일: `ios/HangulCore/Tests/HangulCoreTests/DubeolsikGoldenTests.swift`
시퀀스 (두벌식 영문 키, 쉬프트 없음):

| 키 | 기대 commit+preedit 최종 |
|---|---|
| `k` (ㅏ 중성만) | preedit `ㅏ` (중성만 표시 가능) 또는 조합 불가 시 정책 명시 |
| `rk` | preedit `가` |
| `rks` | preedit `간` |
| `rksk` | commit `간` preedit `가` ? 아니요: 간+ㅏ 불가, ㄴ이 다음 초성 → commit `가` preedit `나` |
| `dkssud` | `안녕` |
| `qkf` | `발` |
| `gks` | `한` |
| backspace on `가` | preedit `ㄱ` |
| flush on `가` | commit `가` preedit empty |

`dkssud`: d=ㅇ k=ㅏ s=ㄴ s=ㄴ u=ㅕ d=ㅇ → 안녕.
검증: 맥에서 `cd HangulCore && swift test`. 실패를 테스트 삭제로 숨기지 말 것.

중성만 입력(`k`) 정책: 호환 자모 `ㅏ`를 preedit로 둔다. 초성 없이 중성이 오면 한글 음절로 만들지 않는다.

### IOS-0-06 Xcode 프로젝트

- `ios/Saegeul.xcodeproj`
- 타깃 `Saegeul` (앱), `SaegeulKeyboard` (app-extension keyboard)
- Embed Foundation Extension
- iOS 18.0, Swift 5.9+, `DEVELOPMENT_TEAM=L6BZF5NB99`, Automatic signing
- HangulCore를 로컬 Swift Package로 두 타깃이 링크. 확장은 HangulCore만, UIKit.
- 앱 Display Name `새글`, 확장 `새글 키보드`
- `INFOPLIST_KEY_NSHumanReadableCopyright` Yun Chan
- 확장 Info: `PrimaryLanguage=ko-KR`, `RequestsOpenAccess=false`, `IsASCIICapable=true`(영문 키 필요), `PrefersRightToLeft=false`
- `NSExtensionPointIdentifier=com.apple.keyboard-service`
- `PrincipalClass=$(PRODUCT_MODULE_NAME).KeyboardViewController`
- 앱 ATS 기본. 확장에 `UIBackgroundModes` 넣지 말 것.

### IOS-0-07 두벌식 키면

`ios/Keyboard/KeyboardViewController.swift` + `KeyboardView.swift`
- 숫자 없는 3행 두벌식 한글 레전드 + 4행 스페이스/쉬프트/백스페이스/엔터/123/Globe
- Globe: `needsInputModeSwitchKey`가 true면 버튼, `advanceToNextInputMode()`
- 터치 → `HangulComposer.process(dubeolsikKey:shift:)`
- 키 지연: 조합은 메인 스레드 동기. 애니메이션 남용 금지
- 모아키 제스처 코드 없음
- 광고 뷰 없음

### IOS-0-08 문서 어댑터

`ios/Keyboard/DocumentProxyAdapter.swift`
- preedit가 바뀌면 `setMarkedText(_:selectedRange:)` (iOS 13+)
- commit은 `unmarkText()` 후 `insertText` 또는 marked를 확정하는 순서를 한 함수로 고정
- backspace: preedit 있으면 composer.backspace, 없으면 `deleteBackward()`
- 호스트가 marked text를 깨면 나중에 토글(IOS-1-06). 스파이크는 marked 경로만.

호출 순서 불변:
1. composer 이벤트 수신
2. `commit` 비어 있지 않으면 현재 marked를 지우고 `insertText(commit)`
3. `preedit` 있으면 `setMarkedText(preedit, selectedRange: NSRange(location: preedit.utf16.count, length: 0))`
4. preedit 비면 `unmarkText()`

### IOS-0-09 Full Access off

- Info.plist `RequestsOpenAccess=false`
- 코드에 `URLSession` / `NWConnection` / `WKWebView` 없음
- 완료: 소스 grep `URLSession|http://|https://` 가 Keyboard 타깃에서 0 (예외: 주석 금지, 그냥 0)

### IOS-0-10 시뮬레이터

**완료 2026-09-14**: iPhone 17(iOS 26.5) 시뮬레이터에서 XCUITest 2/2 통과(`outputs/ios-sim-base`). 키보드 활성화는 설정 앱 대신 `simctl spawn … defaults write -g AppleKeyboards`, 테스트는 `SIMULATOR_UDID`가 있으면 설정 단계를 건너뛴다. **이후 iOS 개발은 시뮬레이터 기준(사용자 지시)**.

```
xcrun simctl boot F800E1B5-5A65-4E5C-B916-682EF3372C31
xcodebuild -scheme Saegeul -destination 'platform=iOS Simulator,id=F800E1B5-5A65-4E5C-B916-682EF3372C31' -allowProvisioningUpdates
```

수동: 설정 → 키보드 → 새글 켜기. Notes에서 `rk` → `가`.
완료 증거: 명령 로그 + (가능하면) 스크린샷 `outputs/ios-spike/`.

### IOS-0-11 실기기

**완료 2026-09-14**: iPhone 14 Pro Max(iOS 26.6.2)에서 지구본 팝업 → 새글 선택 → `ㄱ`·`ㅏ` 탭 → 입력칸 `가`(XCUITest `testAddKeyboardAndComposeGa`의 `가` 단언·`onboarding.step.3.done` 단언 통과, 스크린샷 `outputs/ios-final2/*04-composed-ga*`). 원인이었던 문제는 같은 화면의 `SecureField`가 서드파티 키보드를 팝업에서 숨기던 것(→ 시트로 분리). 확장 UI를 UIKit으로 전환한 뒤 스페이스·백스페이스·초성 삭제까지 포함해 XCUITest 2/2 통과(`outputs/ios-final4`).

Destination: `id=8364243E-3479-5C43-B825-E11EED1A4A37`
개발자 모드·신뢰. Notes에서 `dkssud` → `안녕`, `rk` → `가`.
완료: 설치 성공 로그 + 입력 확인 메모. 카카오는 있으면 하고 없으면 Notes/Safari로 충분(스파이크).

### IOS-0-12 보안 필드

**완료 2026-09-14**: 온보딩의 "비밀번호 칸 예시 열기" 시트 안 `SecureField`에서 `key.r` 미출현(`testSecureFieldDoesNotShowSaegeulKeyboard` 통과, 스크린샷 `e01/e02`). 주의: 보안 필드를 일반 입력칸과 같은 화면에 두면 iOS가 그 화면 전체에서 서드파티 키보드를 숨긴다.

설정 암호 필드 또는 Notes가 아닌 비밀번호 텍스트필드에서 새글이 안 보이고 시스템 키보드인지 확인.
완료: 한 줄 증거. 실패(커스텀이 뜨면) no-go.

### IOS-0-13 메모리

실기기에서 키보드 올린 뒤 Console `memorystatus` / Xcode memory. 빈 두벌식 키면이 즉시 jetsam이면 no-go.
숫자를 HANDOFF에 기록. Apple이 공식 MB를 안 주므로 실측만.

### IOS-0-14 libhangul (병렬, 스파이크 필수 아님)

맥에서 `git clone https://github.com/libhangul/libhangul.git` (라이선스 LGPL).
clang -isysroot iphoneos로 `hangul/*.c` 정적 라이브러리.
Swift에서 `hangul_ic_new("2")` 호출 실험.
실패해도 IOS-0-GO는 클린룸 조합기로 진행. 결과는 메모.

### IOS-0-GO

Go: 11+12+13 통과, golden 통과.
No-go: 조합이 호스트 절반에서 깨지거나 빈 키면 jetsam.
판정은 오케스트레이터. 워커는 증거만.

---

## Phase 1 — 호스트 셸

| ID | 상태 | 제목 | 의존 |
|---|---|---|---|
| IOS-1-01 | done | 온보딩: 시스템 설정에서 키보드 추가 안내 | IOS-0-GO |
| IOS-1-02 | pending | 앱 안 개인정보 안내 (오프라인 기본, FA 설명) | IOS-1-01 |
| IOS-1-03 | in_progress | `UIKeyboardType` 이메일/URL/숫자 키면 | IOS-0-07 |
| IOS-1-04 | done | 높이 constraint 단일 진실 (폭주 버그 방지) | IOS-0-07 |
| IOS-1-05 | pending | 비행기 모드 입력 | IOS-0-09 |
| IOS-1-06 | pending | 마킹(setMarkedText) ON/OFF 설정. 기본 ON | IOS-0-08 |
| IOS-1-07 | pending | 호스트에서 자판 미리보기 (두벌식) | IOS-1-01 |
| IOS-1-08 | pending | 확장 바이너리 광고 SDK·StoreKit 링크 0 검사 | IOS-0-06 |

상세:

- IOS-1-01: `UIApplication.openSettingsURLString`은 가이드 4.4.1이 Settings를 허용. 다른 앱 실행 금지. 온보딩 3스텝: 키보드 추가 / 허용 / 입력창에서 새글 선택.
- IOS-1-02: Full Access를 켜라고 강요하지 않음. 시스템 경고 문구를 덮지 않음.
- IOS-1-03: numberPad/phonePad는 시스템이 가로챌 수 있음. phonePad는 시스템 강제 — 문서화만.
- IOS-1-04: 높이 한 constraint. 스마트보드·한글10키 선례(무한 증가) 회귀 테스트: appear 3회 높이가 같음.
- IOS-1-06: OFF면 delete+insert 시뮬레이션. 기본 ON.

---

## Phase 2 — 자판 (모아키 제외 전량)

레이아웃 데이터는 `HangulCore`에 둔다. UIKit은 그리드만 그린다.
Android `MobileHangulLayout` / `HangulKeyboard` enum이 이름 정본. 위키 3-91/3-93 라벨을 새로 만들지 않음.

| ID | 상태 | 제목 | 엔진 |
|---|---|---|---|
| IOS-2-01 | done | 천지인 | composer 앞단 멀티탭 `ㅣㆍㅡ` |
| IOS-2-02 | pending | 천지인+ | 천지인 확장 키 |
| IOS-2-03 | done | 나랏글 | 획추가·쌍자음 |
| IOS-2-04 | pending | 나랏글 중앙 | 배치만 다름 |
| IOS-2-05 | pending | 베가 | |
| IOS-2-06 | pending | 베가 중앙 | |
| IOS-2-07 | done | 단모음 (두벌식 기반) | ㅏ+ㅏ=ㅑ 등 |
| IOS-2-08 | pending | 두벌식 옛글 | libhangul id `2y` 또는 동등 |
| IOS-2-09 | pending | 세벌식 390 | 전각 그리드 |
| IOS-2-10 | pending | 세벌식 최종 | 전각 그리드 |
| IOS-2-11 | pending | 세벌식 순이(Noshift) | 전각 그리드 |
| IOS-2-12 | pending | 세벌식 옛글 | 전각 그리드 |
| IOS-2-13 | pending | 세벌식 두벌 배치 | |
| IOS-2-14 | pending | 안마태 | |
| IOS-2-15 | done | 스페이스 롱프레스 자판 전환 | 두벌식 엔진 표면만 모바일 전환 (Android `MobileHangulSurfaceSwitcher`와 같은 제약) |
| IOS-2-16 | pending | 각 자판 golden 시퀀스 | 해당 자판 |
| IOS-2-MOA | blocked | 모아키 한손/양손 | 법률 `KR 1020110078022` |

천지인/나랏글/베가/단모음은 Android처럼 **두벌식 엔진 앞단 토큰**이거나, 자모를 compositor에 직접 넣는다. 후자가 iOS에 더 단순하면 후자를 쓰고, 출력 음절만 Android와 golden으로 맞춘다. Fcitx 키심 왕복을 재현할 필요는 없다.

세벌식은 26키 밖 자리가 필요하다. `HangulKeyboard` 전각 그리드. iPhone 폭에서 키 최소 44pt 터치.

Romaja(`ro`)는 제품 17종에 없음. 넣지 않음.

---

## Phase 3 — 로컬 스마트 입력 (Full Access 불필요)

알고리즘은 Android 순수 Kotlin을 **재구현**한다. 파일 복사 금지(LGPL 헤더). 입출력 golden은 공유 가능.

| ID | 상태 | 제목 | Android 참조 (읽기만) |
|---|---|---|---|
| IOS-3-01 | pending | 한/영 오타 `dkssud`↔`안녕` | `KoreanTypoRecovery.kt` |
| IOS-3-02 | pending | 초성 검색 | `KoreanInitials.kt` |
| IOS-3-03 | pending | 조사 은/는 이/가 을/를 으로/로 | `KoreanParticleSuggester.kt` |
| IOS-3-04 | pending | 어절 자동완성 사전 | `completion.txt` 데이터 + KOGL 고지 |
| IOS-3-05 | pending | 개인 단어장 (확장 컨테이너, 백업 제외) | `KO-06` 계약 |
| IOS-3-06 | pending | 후보 가로바 (키보드 뷰 내부만) | |
| IOS-3-07 | pending | 보안 필드에서 후보 0 (시스템이 키보드를 빼므로 방어적) | |
| IOS-3-08 | pending | 한자 음훈 1회 변환 | `hanja.txt` |
| IOS-3-09 | pending | 명시 선택 1회 삽입, 자동 적용 금지 | SSOT 원칙 1 |

데이터 파일은 라이선스 고지와 함께 `ios/HangulCore/Resources/`로 복사 가능(데이터 계약). Kotlin 파서 복사는 하지 말고 Swift 파서를 새로 쓴다.

개인 단어장: App Group 쓰기는 FA가 필요할 수 있음. 1차는 확장 자체 컨테이너 + 호스트 문서 디렉터리(`isExcludedFromBackup`). UserDefaults에 원문 금지.

---

## Phase 4 — 호스트 제품 (1차 후)

| ID | 상태 | 제목 |
|---|---|---|
| IOS-4-01 | pending | 테마 JSON 3.0 서브셋 로더 (스키마만 공유) |
| IOS-4-02 | pending | 테마 목록·적용 |
| IOS-4-03 | pending | Typing DNA 대시보드 집계 UI |
| IOS-4-04 | pending | Envelope DEK + Secure Enclave wrap |
| IOS-4-05 | pending | Keychain `ThisDeviceOnly`, 파일 `isExcludedFromBackup` |
| IOS-4-06 | pending | WidgetKit 집계만 (원문 금지) |
| IOS-4-07 | pending | 앱 프로필 (bundle id) |

---

## Phase 5 — 옵트인 네트워크 (1차 후)

| ID | 상태 | 제목 |
|---|---|---|
| IOS-5-01 | pending | Full Access 목적 문자열 = 실제 호출 |
| IOS-5-02 | pending | 컴패니언 redirect URI allowlist iOS 스킴 |
| IOS-5-03 | pending | 호스트 OAuth/BYOK. 확장은 삽입만 |
| IOS-5-04 | pending | `store=false` 강제 |
| IOS-5-05 | pending | 오프라인 모드가 소켓보다 먼저 |
| IOS-5-06 | pending | OCR 호스트 앱 (Vision). 키보드에서 카메라 실행 금지 |
| IOS-5-07 | pending | STT 호스트 앱 |
| IOS-5-08 | pending | GIF는 더 뒤. Noto 로컬만 후보 |

타자마다 서버 예측 금지 (4.4.1).

---

## Phase 6 — 온디바이스 재료 (1차 후, 선택)

| ID | 상태 | 제목 |
|---|---|---|
| IOS-6-01 | pending | 호스트 LiteRT-LM Swift 또는 Foundation Models |
| IOS-6-02 | pending | 확장 조회만. 확장 로드 금지 |
| IOS-6-03 | pending | 입력 중 문맥 완성 1차 이후에도 기본 끔 |

---

## Phase 7 — 수익화·스토어 (1차 후)

| ID | 상태 | 제목 |
|---|---|---|
| IOS-7-01 | pending | StoreKit 테마 (광고 포인트 모델 금지) |
| IOS-7-02 | pending | Privacy Nutrition Label |
| IOS-7-03 | pending | 한국 전자상거래 표시 |
| IOS-7-SIGN | pending | 게시자 Yun Chan vs 팀 L6BZF5NB99/YONGSEOK LEE 정리 |
| IOS-7-04 | pending | 4.4.1 체크리스트 리뷰 노트 |
| IOS-7-05 | pending | Apple emoji 미포함 (5.2.5) |

---

## 검증 명령 (워커가 실행)

HangulCore:

```
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios/HangulCore && swift test"
```

시뮬레이터 빌드:

```
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios && xcodebuild -scheme Saegeul -destination 'platform=iOS Simulator,name=iPhone 17' -allowProvisioningUpdates CODE_SIGNING_ALLOWED=YES"
```

실기기:

```
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios && xcodebuild -scheme Saegeul -destination 'id=8364243E-3479-5C43-B825-E11EED1A4A37' -allowProvisioningUpdates"
```

Keyboard 타깃 네트워크 grep (Windows 저장소):

```
# Keyboard 소스에 URLSession/http 없어야 함
```

Android 회귀: iOS 작업이 `app/` `plugin/hangul/`를 바꾸면 안 된다. 바꾸면 오케스트레이터 반려.

---

## 갱신 규칙

- 항목을 시작하면 json `status=in_progress`, 끝나면 `done`, `updated` ISO 날짜.
- md 표와 json을 함께 고친다.
- 설계 변경이 필요하면 멈추고 오케스트레이터/사용자에게 보고. 워커가 D/P를 수정하지 않는다.
