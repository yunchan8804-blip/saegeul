# 새글 (Saegeul)

<p align="center">
  <img src="docs/brand/saegeul-icon.svg" width="144" alt="새글 앱 아이콘">
</p>

새글은 Android용 독립 오픈소스 한국어 입력기다. 한글 입력을 중심으로 클립보드,
음성 입력, OCR, GIF 검색, 선택형 AI 글쓰기 도구를 한 키보드 안에서 제공한다.

> 새글은 Fcitx5를 기반으로 한 비공식 독립 포크이며, 원 프로젝트와 제휴하거나
> 원 프로젝트의 보증을 받지 않는다.

## 제품 계약

| 항목 | 값 |
| --- | --- |
| 제품명 | 새글 (Saegeul) |
| Android applicationId | `kr.twentyoz.saegeul` |
| 한글 플러그인 applicationId | `kr.twentyoz.saegeul.plugin.hangul` |
| 소스 저장소 | <https://github.com/yunchan8804-blip/saegeul> |
| 릴리스와 대응 소스 | <https://github.com/yunchan8804-blip/saegeul/releases> |
| 개인정보처리방침 | <https://saegeul.twentyoz.kr/privacy/> |

첫 독립 버전은 공개 앱 ID와 브랜드를 먼저 분리하고 내부 Kotlin namespace는
호환성을 위해 유지한다. 제품 빌드에는 메인 앱과 libhangul 기반 한글 플러그인만
포함하며, 중국어 애드온과 사용하지 않는 언어 플러그인은 APK/AAB에서 제외한다.

## 다운로드와 설치

[GitHub Releases](https://github.com/yunchan8804-blip/saegeul/releases)에서 같은
릴리스 태그의 다음 파일을 함께 받을 수 있다.

- 새글 메인 APK
- 한글 플러그인 APK
- Google Play 제출용 AAB
- 최상위 저장소와 재귀 서브모듈을 포함한 전체 소스 아카이브
- CycloneDX SBOM, 빌드 메타데이터, SHA-256 체크섬

첫 독립 버전에서는 메인 APK와 한글 플러그인 APK를 모두 설치해야 한다. 설치 뒤
Android의 `설정 > 일반 관리 > 키보드 목록 및 기본값`에서 새글을 켜고 기본
키보드로 선택한다. 출처를 확인할 수 없는 APK나 다른 인증서로 서명된 변형은
설치하지 않는 것을 권한다.

## 개인정보와 네트워크 기능

새글은 IME이므로 사용자가 입력하는 텍스트를 처리한다. 기본 한글 조합은 기기에서
동작한다. 인터넷, 마이크, 클립보드, 선택형 AI 기능은 각각 사용자가 해당 기능을
설정하고 실행할 때만 사용하며, 외부 전송 전 앱 안에서 대상과 제공자를 고지한다.
API 키와 OAuth 토큰은 Android Keystore로 보호되는 백업 제외 저장소에 둔다.

실제 데이터 처리 범위와 삭제·문의 방법은
[개인정보처리방침](https://saegeul.twentyoz.kr/privacy/)에서 확인할 수 있다.

## 소스와 빌드

```shell
git clone --recurse-submodules https://github.com/yunchan8804-blip/saegeul.git
cd saegeul
./gradlew :app:assembleRelease :plugin:hangul:assembleRelease
```

정확한 Android SDK, NDK, CMake, JDK 버전은
[`Versions.kt`](build-logic/convention/src/main/kotlin/Versions.kt)에 고정돼 있다.
서명된 배포물 재현 절차와 필요한 환경 값은
[`docs/independent-fork/signing.md`](docs/independent-fork/signing.md), 독립 포크의
전체 배포 계약과 검증 명령은
[`docs/independent-fork/README.md`](docs/independent-fork/README.md)를 따른다.

## 라이선스와 소스 제공

저장소의 기존 저작권 표시는 유지한다. 새글에서 새로 작성한 파일은 원칙적으로
`LGPL-2.1-or-later`와 `Copyright 2026 Yun Chan`을 명시한다. 각 서브모듈과 제3자
구성요소에는 해당 구성요소의 원 라이선스가 적용된다.

APK 안의 `정보` 화면에서 다음 배포물을 직접 볼 수 있다.

- 오픈소스 라이선스와 제3자 저작권 고지
- 독립 포크 고지와 원 프로젝트 귀속
- 바이너리와 정확히 대응하는 소스 태그·아카이브 링크
- 데이터·개인정보 처리 고지

배포 번들은 LGPL 2.1, GPL 2.0, Apache License 2.0 및 실제 의존성에 필요한
라이선스 전문을 포함한다. 릴리스 태그의 전체 소스, 빌드 스크립트, 재귀 서브모듈
소스는 바이너리와 같은 GitHub Release에 게시한다.

## 계보와 기여

새글은 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)와
[Fcitx5](https://github.com/fcitx/fcitx5), [libhangul](https://github.com/libhangul/libhangul)의
작업을 기반으로 한다. 이 링크는 원 프로젝트의 출처와 저작권을 밝히기 위한 것이며,
새글의 공식 배포·지원·보증 채널을 뜻하지 않는다.

버그와 기여 제안은 [새글 저장소의 Issues](https://github.com/yunchan8804-blip/saegeul/issues)에
남기면 된다. 보안 문제나 개인정보 문의는 개인정보처리방침의 연락처를 사용한다.
