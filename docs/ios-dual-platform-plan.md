# 새글 iOS 듀얼 플랫폼 계획

상태: 설계 잠금, Phase 0 착수
조사 기준일: 2026-09-13
제품 결정일: 2026-09-13
SSOT 포인터: [korean-smart-input-ssot.md](korean-smart-input-ssot.md) 3절
실행 백로그(정본 TODO): [ios-port-backlog.md](ios-port-backlog.md) / [ios-port-backlog.json](ios-port-backlog.json)
인수인계: [HANDOFF-ios-port.md](HANDOFF-ios-port.md)

이 문서는 Android 새글을 아이폰에서도 쓰게 만들 때의 **가능성 판단, 확정 설계, 단계**의 정본이다. 구현 워커는 여기 잠근 경계를 바꾸지 않는다.

---

## 1. 결론

Android 앱을 그대로 옮겨 **기능 100% 동일한 아이폰 앱**을 만드는 일은 불가능하다.

가능한 경로는 **한 제품, 두 네이티브 셸**이다.

- 공유: 한글 조합 엔진, 자판·사전 데이터, 한국어 NLP, 개인정보 계약
- 비공유: Fcitx5, Android IME 셸, 키보드 확장 안 Gemma, 키보드 면 광고, 플러그인 APK

업계도 100% 패리티를 하지 않는다. Gboard는 Android 916언어 vs iOS 125언어이고, Gboard iOS는 2022-05 이후 사실상 중단됐다.

---

## 2. 2026-09-13 제품 결정

| ID | 결정 |
|---|---|
| P1 | 1차 성공 정의는 **자판 + 로컬 스마트 입력**. Full Access 없이 동작. |
| P2 | 1차 공개 자판은 위키 17종에서 **모아키만 제외**. 스토어 1차에 나머지 자판을 함께 싣는다. |
| P3 | 모아키는 삼성 특허(KR 1020110078022) 법률 검토 뒤에만 iOS에 올린다. Android 모아키는 유지. |
| P4 | iOS 1차는 **무료 입력기**. StoreKit·호스트 광고는 뒤 단계. |
| P5 | 키보드 확장 안 Gemma 추론, 실시간 문맥 완성, 호스트 AI/컴패니언은 1차 범위 밖. |

1차 공개 자판 목록 (코드 정본은 `HangulKeyboard` enum + `MobileHangulLayout`, 모아키 제외):

- 모바일: 천지인, 천지인+, 나랏글, 베가, 단모음 (중앙 배치 변형 포함 여부는 스파이크 후)
- 쿼티: 두벌식, 두벌식 옛글
- 세벌식: 390, 최종, 순이, 옛글, 세벌식 두벌 배치
- 인체공학: 안마태

위키의 “세벌식 3-91/3-93/세벌 단모음”은 엔진 enum과 1:1이 아니다. iOS 라벨은 코드 enum을 따른다.

1차 로컬 스마트 입력: 한/영 오타 복구, 초성 검색, 조사 후보, 어절 자동완성, 개인 단어장. 네트워크 없음.

---

## 3. 100%가 불가능한 이유 (요약)

Apple 문서 확인일 2026-09-13.

- 키보드는 별도 프로세스(`UIInputViewController`). Fcitx5 상주 데몬을 둘 자리가 없다.
- 확장 메모리는 앱보다 훨씬 낮다. fcitx5-ios 실측 ActiveHard 77MB. Gemma E2B는 CPU ~607MB.
- 가이드 4.4.1: Full Access 없이도 입력되어야 한다. 확장은 광고·IAP 금지(4.4, 2.5.18).
- 비밀번호·Phone Pad는 시스템 키보드 강제. 은행 앱은 서드파티 키보드를 거절할 수 있다.
- 현재 IME UI는 LGPL-2.1(fcitx5-android 계보). iOS UI는 클린룸 재구현.

상세 표와 출처는 세션 조사에 있다. 핵심 공식 문서:

- [Creating a custom keyboard](https://developer.apple.com/documentation/uikit/creating-a-custom-keyboard)
- [App Store Review Guidelines 4.4.1, 2.5.18](https://developer.apple.com/app-store/review/guidelines/)
- [Configuring open access](https://developer.apple.com/documentation/uikit/configuring-open-access-for-a-custom-keyboard)

---

## 4. 잠근 기술 설계

1. **공유 코어 + 듀얼 네이티브 셸.** Flutter/RN/CMP로 키보드 UI를 올리지 않는다.
2. **Fcitx5를 iOS에 올리지 않는다.** `libhangul` 직접 호출. Android fcitx 경로는 유지.
3. **Gemma는 키보드 프로세스에 넣지 않는다.** 나중에도 호스트 생성 + 확장 조회만.
4. **Full Access 기본 끔.** 1차 기능은 전부 오프라인.
5. **iOS UI 클린룸.** 스키마·불변식만 스펙으로 옮긴다.
6. **조합 전달:** `setMarkedText` 기본, 호스트 깨지면 마킹 토글/폴백. Android Buffered Hangul paste 경로는 이식하지 않는다.
7. **최소 OS: iOS 18.** iPad 1차는 도킹만.
8. **수익화 1차 없음.** 확장에 광고 SDK 링크 없음.

### 프로세스 분할

```
Containing App (1차는 설정·온보딩·개인정보)     Keyboard Extension
온보딩, 자판 선택, 높이, 마킹 토글               키면 + Globe
개인 단어장 편집(호스트)                          libhangul + MobileHangulComposer
                                                 오타·초성·조사·어절 조회
                                                 insertText / setMarkedText
```

호스트 앱은 빈 껍데기면 4.2 거절이다. 1차 호스트는 온보딩·자판 미리보기·개인정보 안내·단어장 관리까지다. 대시보드 광고·StoreKit·AI 설정은 빼다.

### 라이선스

- `libhangul` LGPL-2.1: 동적 프레임워크 또는 유니코드 클린룸 compositor. 구현 전 법률 메모.
- 모아키 특허: 1차 바이너리에 제스처 자판 코드 넣지 않음.

---

## 5. 단계 (1차 공개 = Phase 0–3을 한 릴리스 트레인)

내부 순서는 여전히 엔진 → 자판 → 스마트 입력이다. **스토어에 두벌식만 먼저 올리지 않는다**(P2).

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| 0 스파이크 | libhangul iOS 링크, 두벌식 조합, marked text, 보안 필드, jetsam | 실기기 Notes/Safari에서 `ㄱ+ㅏ=가`, 비밀번호에서 시스템 키보드, 메모리 헤드룸 |
| 1 셸 | 호스트 온보딩, Globe, 숫자/이메일 타입, Full Access 끔 | 비행기 모드 입력 |
| 2 자판 | P2 목록 전부. 세벌 전각 그리드 | 레이아웃별 golden + 실기기 한 바퀴 |
| 3 스마트 입력 | 오타·초성·조사·어절·개인 단어장 | 기존 Android JVM 테스트와 같은 시퀀스. 민감 필드는 시스템 교체 |
| 4 이후 | 테마, 금고 대시보드, 위젯 | 1차 후 |
| 5 이후 | Full Access 옵트인 AI/컴패니언 | 1차 후 |
| 6 이후 | 호스트 Gemma/Foundation Models 재료 | 1차 후 |
| 7 이후 | StoreKit | 1차 후 |

### 스파이크 환경

Phase 0는 **Mac + Xcode + 실기기**가 필요하다. 이 Windows 작업 공간만으로 `ios/` 앱을 검증할 수 없다. 스파이크 전에 Apple Developer Program, bundle id, LGPL 배포 방식을 고른다.

### 검증

- 공유 키 시퀀스 → preedit/commit 스냅샷을 Android와 비교
- Full Access OFF + 비행기 모드 입력
- 확장 바이너리에 광고 SDK 없음
- 모아키 심볼/제스처 코드 없음
- 금고·단어장이 iCloud 백업 대상이 아님

Android `:app:testDebugUnitTest`와 hangul assemble을 iOS 작업이 깨뜨리면 안 된다.

---

## 6. 1차에 넣지 않는 것

- 모아키
- Gemma 확장 추론, 입력 중 문맥 완성
- 글쓰기 AI, 컴패니언, OCR, STT, GIF
- 테마 상점, 포인트, 광고
- Fcitx5, 병음, 플러그인 APK, Direct Boot
- 비밀번호 필드 커스텀 키보드(불가)
- Flutter 키보드 UI

---

## 7. 방법론

정석은 Rime/Hamster와 같다. **C 엔진 먼저, 키 UI는 UIKit.** 키 히트 경로를 KMP/Skia로 올리지 않는다. NLP만 KMP 또는 Swift 이식이 가능하다.

Android fcitx 경로를 iOS 스파이크와 동시에 리팩터하지 않는다.

현실 감각(견적 아님, 2인 숙련 가정): 스파이크 2–4주, 자판 전량+스마트 입력 스토어 후보 6–9개월. Mac 실기기 게이트가 일정 바닥이다.
