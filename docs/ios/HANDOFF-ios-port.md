# 새글 iOS 포팅 — 다음 세션·에이전트 인수인계

기준일: 2026-09-14
이 파일을 **먼저** 읽는다. 설계를 바꾸지 않는다.

## 읽을 순서

1. 이 파일
2. [ios-dual-platform-plan.md](ios-dual-platform-plan.md) — 잠근 설계 D1–D8, 제품 결정 P1–P5
3. [ios-port-backlog.md](ios-port-backlog.md) — 사람용 상세 백로그
4. [ios-port-backlog.json](ios-port-backlog.json) — 기계용 TODO 정본 (`status` 필드)
5. [korean-smart-input-ssot.md](../korean-input/korean-smart-input-ssot.md) 3절 iOS 단락

## 다음 할 일 고르는 법

`ios-port-backlog.json`에서 다음을 만족하는 항목 중 `id` 오름차순 1~3개:

- `status` ∈ `pending` | `in_progress`
- `blocked_by`의 모든 id가 `done` 또는 `cancelled` 또는 `blocked`(영구 제외가 아님) — 실제로는 blocked_by가 전부 `done`일 때만 착수
- `phase`가 가장 작은 것 우선

완료하면 **json과 md 표를 같이** 갱신한다. json만 고치면 안 된다.

## 잠금 (위반하면 폐기)

- Fcitx5를 iOS에 올리지 않는다. Flutter/RN/CMP 키보드 UI 금지.
- 키보드 확장 안 Gemma·광고 SDK·IAP·네트워크(1차) 금지. `RequestsOpenAccess=false`.
- 모아키 코드 금지(특허 게이트 `IOS-2-MOA`).
- Android `input/**` Kotlin UI를 복사하지 않는다. 클린룸.
- 커밋·push는 사용자 요청 시에만. `Versions.kt` / `ProductIdentity.kt` 금지.
- 1차 스토어 범위는 모아키 제외 자판 전량 + 로컬 스마트 입력. 두벌식만 스토어에 올리지 않는다. 내부 스파이크는 두벌식부터.

## 빌드 환경 (실측 2026-09-13)

| 항목 | 값 |
|---|---|
| SSH | `ssh vd-mac` (Tailscale `100.88.141.44`, user `yunchan`, key `~/.ssh/id_ed25519_vd`) |
| 호스트 | `YunChanui-Macmini.local`, macOS 26.5.2, arm64 |
| Xcode | 26.6 (17F113), Swift 6.3.3 |
| 팀 | `L6BZF5NB99` (인증서 CN: Apple Development: Created via API, O=YONGSEOK LEE) |
| 아이폰 | iPhone 14 Pro Max, CoreDevice `8364243E-3479-5C43-B825-E11EED1A4A37`, UDID `00008120-000E09462238C01E`, iOS 26.6.2, paired/available |
| 시뮬레이터 | iPhone 17 (26.5) `F800E1B5-5A65-4E5C-B916-682EF3372C31` (꺼져 있을 수 있음) |
| 맥 작업 경로 | `/Users/yunchan/workspace/saegeul-ios` (Windows 저장소 `ios/`를 동기화) |
| Windows 저장소 | `D:\workspace\Saegul` 브랜치 `YunChan/korean-accuracy-followup` |
| 디스크 | 맥 미니 `/` 여유 ~24GB. DerivedData 남용 금지 |
| brew/cmake | 없음. libhangul은 clang/SPM C 타깃으로 |

서명 주체가 제품 게시자 `Yun Chan`과 다르다(`YONGSEOK LEE`). 스파이크는 이 팀으로 설치한다. 스토어 제출 전 `IOS-7-SIGN` 게이트.

## 다음 세션이 이어서 할 일 (2026-09-14 세션 종료 시점)

현재 상태: iPhone 17 시뮬레이터 XCUITest **6/6 통과**(두벌식 `가`, 높이 안정, 라틴·기호 층, 롱프레스 대체 문자·스페이스 스와이프, 천지인 "안녕"+온보딩 상단, 보안 필드). `swift test` 45/45. 확장 UI는 UIKit, 온보딩은 체크리스트 3단계 자동 감지.

1. 이 문서 끝 "마지막 워커 보고"에서 진행 중이던 패킷(자판 전환 시트 디자인 · 나랏글 · 단모음)의 완료 정도를 확인한다. 미완성이면 그 지점부터 같은 설계(격차표 4절·백로그 IOS-2-03/2-07)로 잇는다. 시뮬레이터 XCUITest는 기존 6개가 통과하는 상태여야 한다.
2. 그다음 격차표(`docs/ios-port-parity/ios-gap-table.md`) 4절 순서: 천지인 플러스 → 베가 → 세벌식(전각 그리드 설계 결정 필요: 가로 모드 전용 또는 2층 시프트) → 옛글·안마태 → Phase 3 로컬 스마트 입력(후보 바 → 오타 복구 → 조사 → 자동완성 사전).
3. 스파이크 잔여: IOS-0-13 확장 메모리 측정(시뮬레이터에서 `xcrun simctl spawn … log stream` 또는 Instruments), IOS-0-GO 판정 기록.
4. 릴리스 전 확인: `ContentView`의 DEBUG 진단 라벨(`diag.inputModes`)이 release에서 빠지는지, 온보딩 카피 검토.
5. 맥 디스크: `/` 여유가 1GB 미만이다. 세션 시작 시 `outputs/*.xcresult`와 `DerivedData*`를 정리한다. 시뮬레이터 `hangulLayout` plist 오염이 의심되면 두벌식으로 되돌린다(테스트 teardown이 복원하지만 중단 시 남을 수 있다).

## 2026-09-14 현재 상태 (먼저 읽을 것)

- **개발 기기: 이제 iPhone 17 시뮬레이터(`F800E1B5-…`, iOS 26.5)다(사용자 지시). 실기기 아이폰은 쓰지 않는다. 2026-09-14 저녁 기준 아이폰은 맥미니와 다른 장소에 있어 물리 연결 자체가 불가능하다(무선 시도 절차는 아래 절).** 시뮬레이터 실행: `xcodebuild … -destination "platform=iOS Simulator,id=F800E1B5-5A65-4E5C-B916-682EF3372C31" -derivedDataPath DerivedData-sim -only-testing:SaegeulUITests test`. 키보드 활성화는 `xcrun simctl spawn F800E1B5-5A65-4E5C-B916-682EF3372C31 defaults write -g AppleKeyboards -array "ko_KR@sw=Korean;hw=Automatic" "en_US@sw=QWERTY;hw=Automatic" "net.chanpaca.saegeul.ios.keyboard"`. 시뮬레이터 XCUITest 2/2 통과(07:5x).

- **2026-09-14 07:37 결과: 아이폰 14 Pro Max에서 새글 키보드가 뜨고 `ㄱ+ㅏ=가`가 조합된다.** 증거 `outputs/ios-final2/*04-composed-ga*.png`(온보딩 "새글이 준비됐어요 3/3"). IOS-0-11·0-12·1-01 done.
- **원인 확정**: 같은 화면의 `SecureField`가 그 화면의 다른 입력칸에서도 서드파티 키보드를 지구본 팝업에서 숨겼다. 비밀번호 예시는 별도 시트로 옮겼다. 아래 기록은 원인 추적 과정이다.
- 빌드·설치·`swift test`(9/9)·XCUITest 인프라는 된다. (해결됨) 키보드 확장이 설정에는 등록되지만 지구본 전환 팝업에 나타나지 않던 문제.
  - 증거: 실기기 XCUITest 팝업 덤프 5회(`키보드 설정… / 한국어 / English (US) / 이모지 / (한손 아이콘 줄)`), 스크린샷 `outputs/ios-spike`, `outputs/ios-bisect/*`.
  - 기각된 가설: SwiftUI·HangulCore(최소 UIKit 키보드도 동일), `ENABLE_DEBUG_DYLIB`(꺼도 동일), 번들 id 접두 `net`→`com`(동일), `PrimaryLanguage` `ko-KR`→`ko`(동일), 재부팅(동일).
  - 확인된 사실: 호스트 앱의 `UITextInputMode.activeInputModes`에는 자동화 안팎 모두 `UIKeyboardExtensionInputMode` 2개(net.*·com.* 실험 앱)가 있다(`modes=5 ext=2`). 즉 시스템은 확장을 인식하며, 팝업만 이를 뺀다.
  - **결정적 관찰(06:3x)**: 같은 XCUITest 세션에서 메모 앱(`com.apple.mobilenotes`)을 띄워 지구본을 길게 누르면 팝업에 "새글, 한국어"가 나온다(`outputs/ios-bisect/notes`). 즉 확장은 유효하고 자동화도 원인이 아니다. **우리 호스트 앱 화면에서만 숨는다.** 시뮬레이터(iOS 26.5)도 같은 증상.
  - 확정: 같은 화면의 `SecureField`가 원인(`outputs/ios-bisect/nosecure`에서 제거하자 팝업에 새글 등장). 테스트는 지구본 길게 누르기 → 팝업 "새글" 셀 탭 방식으로 전환.
  - **07:5x 최종**: 확장 UI를 UIKit(`KeyboardView: UIView`, `KeyButton`)으로 전환했고 지구본은 `.allTouchEvents`→`handleInputModeList(from:with:)`. 어댑터 확정은 "마킹 빈 문자열 → unmarkText → insertText(commit)"(그 전엔 캐럿이 확정 문자열 앞에 남아 `" 가"`가 됐다). **실기기 XCUITest 2/2 통과**(`outputs/ios-final4`): 추가·전환·`가`·스페이스·백스페이스 비우기·초성 하나 지우기·보안 필드.
  - 남은 일: IOS-0-13 메모리 측정, IOS-0-GO 판정, 시뮬레이터 두벌식(IOS-0-10, 설정 자동화가 시뮬레이터 설정 앱 구조와 달라 미완), 릴리스 전 `ContentView`의 DEBUG 진단 라벨 제거 확인, 격차표 4절 순서로 Phase 1.
- 기기에 실험용 `com.chanpaca.saegeul.ios`가 설치돼 있다. 블로커 해소 후 `xcrun devicectl device uninstall app --device <id> com.chanpaca.saegeul.ios`로 지운다. 맥 `saegeul-ios-verify` 트리는 일회용 실험 복사본이다.
- 호스트 앱 첫 화면은 온보딩 체크리스트(`ios/App/Onboarding/*`, `ios/App/Theme/SaegeulPalette.swift`)다. 1단계(설정 추가)는 `AppleKeyboards` 목록으로 자동 감지되고 실기기에서 동작 확인. 2단계는 입력 모드 클래스명(`…ExtensionInputMode`)+언어로 판별, 3단계는 한글 음절 입력.
- Android 1:1 이식 기준 문서: `docs/ios-port-parity/android-inventory-*.md`(3개), 격차표 `docs/ios-port-parity/ios-gap-table.md`. 다음 개발 순서는 격차표 4절.

## 실기기 무선 디버깅(Tailscale) 시도 절차 — 아직 미검증 (2026-09-14 기록)

현재 상황: 사용자가 아이폰을 맥미니와 다른 장소로 가져가 있어(집이 아님) 실기기는 당분간 연결할 수 없다. 개발·검증은 시뮬레이터로만 한다. 아이폰이 맥미니 옆으로 돌아오면 아래를 한 번 시도해 결과를 이 절에 기록한다.

원리상 제약: Xcode 무선 디버깅은 같은 LAN의 Bonjour(mDNS 멀티캐스트) 탐색을 쓰는데 Tailscale은 멀티캐스트를 전달하지 않는다. Xcode 15+ CoreDevice 페어링은 링크로컬 IPv6 터널이라 다른 서브넷에서는 불안정하다는 보고가 많고, `devicectl`에는 IP로 붙는 옵션이 없다. 성공 사례가 있으나 iOS 17 이후로는 엇갈린다.

1. 아이폰에 Tailscale 앱을 설치하고 맥미니(`100.88.141.44`)와 같은 테일넷에 로그인한다.
2. **아이폰을 맥미니에 USB로 한 번 페어링**하고 Xcode › Window › Devices and Simulators에서 "Connect via network"를 켠다(이 단계는 같은 장소에서만 가능).
3. USB를 뽑고, Devices 창에서 기기 우클릭 › "Connect via IP Address…"에 아이폰의 Tailscale IP(100.x.x.x)를 입력한다. 이 단계는 Xcode GUI에서만 되므로 맥미니 화면(VNC 등)이 필요하다. SSH만으로는 불가.
4. 연결되면 `xcrun devicectl list devices`에 `available`로 보이고 `ios/scripts/mac-build-device.sh install/uitest`가 그대로 동작한다. 화면은 켜진 채 잠금 해제여야 한다.
5. 되더라도 XCUITest처럼 왕복이 많은 작업은 느리다. 개발은 시뮬레이터, 실기기는 최종 확인용으로만.
6. 확실한 대안: 아이폰을 맥미니와 같은 Wi‑Fi에 두고 표준 무선 디버깅(Bonjour) 사용.

결과 기록란: (시도 날짜 / 성공 여부 / 연결까지 걸린 시간 / 실패 시 Xcode 오류 원문)

## 키보드 확장 입력 계약에서 실측으로 확정한 규칙 (2026-09-14)

- 확정 문자열은 `setMarkedText("")` → `unmarkText()` → `insertText(commit)` 순서로 넣는다(`setMarkedText(commit)`+`unmarkText()`는 캐럿이 앞에 남았다).
- **커밋과 새 preedit가 같은 이벤트에 있으면 `unmarkText()`를 부르지 않는다.** 부르면 호스트가 직후의 `setMarkedText(preedit)`를 확정 텍스트로 처리한다("안ㄴ녕"). `unmarkText()`는 preedit가 빌 때만.
- 같은 화면에 `SecureField`가 있으면 서드파티 키보드가 전환 목록에서 사라진다. 호스트 앱 화면 설계 제약.
- SwiftUI 호스팅 키보드는 접근성에 키를 노출하지 않는다. 확장 UI는 UIKit.

## 빌드 스크립트·함정 (2026-09-14 실측)

- `ios/scripts/mac-build-device.sh [build|install|launch|uitest]` — SSH 세션의 잠긴 로그인 키체인 문제(`errSecInternalComponent`)를 HaramLog가 만든 빌드 키체인(`~/Library/Keychains/haramlog-build.keychain-db`, 비밀번호 파일 `~/workspace/HaramLog-w11/.secrets/apple/build-keychain.pw`)로 우회한다. 비밀번호 내용은 읽거나 프롬프트에 넣지 않는다.
- 확장 타깃 `ENABLE_DEBUG_DYLIB = NO`, `ENABLE_PREVIEWS = NO` 고정(단일 바이너리).
- `SaegeulUITests` 타깃: 설정 앱 자동화로 키보드 추가 → 호스트 앱에서 `가` 조합·배지 확인·보안 필드 검사. 실기기 화면이 켜진 채 잠금 해제여야 한다("Timed out while enabling automation mode"). 결과 번들 `outputs/*.xcresult`, 첨부는 `xcrun xcresulttool export attachments`.
- 실기기는 1대. 두 xcodebuild가 동시에 붙으면 깨진다. 워커에게 재부팅·앱 삭제·설정 변경을 금지한다(재부팅 후 물리 잠금 해제가 필요했다).
- 콘솔 진단: `xcrun devicectl device process launch --console --terminate-existing --device <id> net.chanpaca.saegeul.ios`로 `SAEGEUL_DIAG` 줄(활성 입력 모드·AppleKeyboards)을 읽을 수 있다.

## 동기화

Windows에서 소스 진실. 맥은 빌드·실기기.

```
scp -r ios vd-mac:/Users/yunchan/workspace/saegeul-ios/
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios && ./scripts/mac-build-device.sh install"
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios && ./scripts/mac-build-device.sh uitest"
```

`HangulCore`만 검증:

```
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios/HangulCore && swift test"
```

## 코드 위치

| 경로 | 역할 |
|---|---|
| `ios/HangulCore/` | 순수 Swift 조합·자판 데이터. 맥 `swift test` |
| `ios/App/` | 호스트 앱 (온보딩) |
| `ios/Keyboard/` | Keyboard Extension |
| `ios/SaegeulUITests/` | 실기기 XCUITest(설정 자동화·조합·보안 필드) |
| `ios/scripts/` | 맥 빌드·설치·테스트 스크립트 |
| `ios/Saegeul.xcodeproj/` | Xcode 프로젝트 |
| Android `app/` `plugin/hangul/` | iOS 작업이 건드리면 안 됨 |

## 보고 형식

변경 파일, diff 요약, 실행한 명령과 원문(통과/실패), json에서 어떤 id를 done으로 바꿨는지, 미해결, 설계 변경 요청(기본 없음).

## 마지막 워커 보고

워커 `ios-phase1-layers` 최종 상태(12:0x, 오케스트레이터 수용):
- 패킷 4 완료: 자판 전환 시트(제목 "한글 자판", 4행 목록, 제이드 체크마크, `accessibilityValue="selected"`로 테스트), 나랏글(`NaratgulInput`, ㅣ 결합은 천지인 표 공유, 골든 15), 단모음(`DanmoumInput`, 300ms 멀티탭, 골든 11), `processMobileEvent` 일반화, 한글 층 스페이스 라벨 "스페이스 ▾".
- 발견·수정한 버그: `ChunjiinInput.iCombinations`가 Android 10항목 중 7항목만 있었음(ㅠ→ㅝ, ㅘ→ㅙ, ㅝ→ㅞ 보충). ㅘ/ㅝ에 ㅣ 결합 시 `backspaces=2` 필요(`HangulComposer.jungHead`가 한 단계만 되돌림).
- 검증: `swift test` 83/83, iPhone 17 시뮬레이터 XCUITest **7/7**(`outputs/sim-p4-final.xcresult`), Windows·맥 md5 일치, 맥 디스크 여유 806Mi.
- 스크린샷: `outputs/ios-sim-p4/layout-switcher-sheet.png`, `naratgul-layout.png`, `danmoum-layout.png`, `space-shows-layout-switcher-arrow.png`.
- **오케스트레이터 감사에서 잡힌 결함(다음 세션 1순위)**: 전환 시트의 두벌식 행 가운데에 정체불명의 파란 사각형이 떠 있고, 4번째 항목(단모음)이 키보드 높이(260)에 잘려 보이지 않는다. 행 높이 44로 줄이고 목록을 스크롤 가능하게 하거나 시트 높이를 늘려라. 파란 사각형은 `UIButton` 서브뷰(체크마크 `UIImageView`) 배치 실수로 추정.
- 미해결: 시뮬레이터 `hangulLayout` plist에 원인 불명의 "naratgul"이 남아 있던 일이 한 번 있었다(teardown이 복원하지만 중단 시 오염 가능).

