# 새글 iOS (Phase 0 스파이크)

Windows 저장소 `ios/`가 소스 진실이다. 빌드와 테스트는 맥에서 한다.

## 구성

| 경로 | 역할 |
|---|---|
| `HangulCore/` | 두벌식 한글 조합 Swift 패키지 |
| `App/` | 온보딩 호스트 앱 (`net.chanpaca.saegeul.ios`) |
| `Keyboard/` | 키보드 확장 (`net.chanpaca.saegeul.ios.keyboard`) |
| `Saegeul.xcodeproj` | 앱 + 확장 + 로컬 SPM |

전체 접근(`RequestsOpenAccess`)은 꺼져 있다. 키보드 확장에 네트워크 코드가 없다.

## 요구 환경

- macOS + Xcode (실측: Xcode 26.6, iOS 18+)
- 팀 `L6BZF5NB99`, Automatic signing
- 맥 작업 경로: `/Users/yunchan/workspace/saegeul-ios`

Windows에는 Swift가 없다. `swift test`와 `xcodebuild`는 맥에서 실행한다.

## 동기화 (Windows → 맥)

PowerShell에서 `ios/` **내용**을 맥 작업 경로로 복사한다.

```
scp -r D:\workspace\Saegul\ios\* vd-mac:/Users/yunchan/workspace/saegeul-ios/
```

SSH: `ssh vd-mac` (Tailscale `100.88.141.44`, user `yunchan`).

## HangulCore 테스트

맥에서:

```
cd /Users/yunchan/workspace/saegeul-ios/HangulCore
swift test
```

원격:

```
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios/HangulCore && swift test"
```

## 앱 빌드

Xcode에서 `Saegeul.xcodeproj`를 열고 Saegeul 스킴으로 실행한다.

실기기 (iPhone 14 Pro Max):

```
ssh vd-mac "cd /Users/yunchan/workspace/saegeul-ios && xcodebuild -project Saegeul.xcodeproj -scheme Saegeul -destination 'id=8364243E-3479-5C43-B825-E11EED1A4A37' -allowProvisioningUpdates"
```

## 키보드 활성화

설정 → 일반 → 키보드 → 키보드 → 새 키보드 추가… → 새글

전체 접근은 켜지 않는다.
