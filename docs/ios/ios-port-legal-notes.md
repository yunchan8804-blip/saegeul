# iOS 스파이크 법률·서명 메모

날짜: 2026-09-13
상태: 초안. 법률 자문 아님. 스토어 제출 전 `IOS-7-SIGN`에서 다시 연다.
스파이크(TestFlight/디바이스 개발 설치)를 막지 않는다.

## 1. 서명 주체

맥 미니 개발 인증서:

- CN: `Apple Development: Created via API (G26H6V2446)`
- OU/Team: `L6BZF5NB99`
- O: `YONGSEOK LEE`

제품 게시자(Android/사이트): `Yun Chan`, 도메인 `chanpaca.net`, 패키지 `net.chanpaca.saegeul`.

스토어에 올리기 전에 팀·계정·법적 게시자를 맞출 것. 지금은 이 맥에서 실기기 설치만 한다.

## 2. 한글 조합기 라이선스

- Android는 `libhangul` LGPL-2.1-or-later 정적 라이브러리 + fcitx5-hangul.
- iOS 스파이크는 **유니코드 한글 음절 공식**(UAX #15 / 한글 음절 블록 AC00–D7A3)과 공개 두벌식 배열을 쓰는 **클린룸 Swift compositor**.
- Android `MobileHangulComposer.kt` 등 LGPL 헤더 Kotlin을 복사하지 않는다.
- `libhangul`을 나중에 링크하면 동적 프레임워크 + 소스 제공 또는 오브젝트 제공을 `IOS-0-14`/`IOS-7-SIGN`에서 결정한다.

## 3. 모아키

삼성 특허 KR 1020110078022. iOS 1차 바이너리에 제스처 자판 코드를 넣지 않는다. `IOS-2-MOA`.

## 4. App Store 키보드

4.4.1 Full Access 없이도 입력, 확장 안 광고·IAP 금지, Globe 키. 상세는 계획 문서.
