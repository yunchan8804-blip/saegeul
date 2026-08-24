<!--
SPDX-License-Identifier: LGPL-2.1-or-later
SPDX-FileCopyrightText: Copyright 2026 Yun Chan
-->

# 한글 엔진 Play 배포 SSOT

상태: `DECIDED`
기준일: 2026-08-24
게이트: `GATE-HANGUL-PLAY-01`

## 결정

Play에 올리는 새글 사용자 빌드는 **메인 AAB 한 번에 한글 엔진이 포함**되어야 한다.

- 두 번째 Play 앱(`net.chanpaca.saegeul.plugin.hangul`)을 만들지 않는다. 한글은 독립 앱이 아니라 제품 엔진이다.
- Play 사용자에게 GitHub 사이드로드나 알 수 없는 출처 설치를 안내하지 않는다.
- 사용자 빌드에서 전체 플러그인 관리자 화면은 계속 숨긴다.
- GitHub/F-Droid 직접 배포는 메인 APK만으로 한글 입력이 가능해야 한다. 한글 플러그인 APK는 이전 배포 호환과 독립 검증용 선택 산출물로만 함께 둘 수 있다.

## 적용

앱 Gradle이 `:plugin:hangul`의 한글 애드온 자산과 `libhangul.so`를 메인 앱 자산/JNI로 복사한다. `generateDataDescriptor`는 이 복사 뒤에 돌아야 한다. `usr/share/fcitx5/inputmethod` 정리 작업이 한글 `hangul.conf`를 지우지 않도록 복사는 그 삭제 이후에 실행한다.

이미 설치된 한글 플러그인 APK와 경로가 겹치면 DataManager는 플러그인 쪽을 실패로 두고, 메인 앱에 동봉된 한글 엔진을 사용한다.

## 현재 업로드 빌드

내부 테스트에 올라가 있는 `saegeul-v0.1.0-rc.13` AAB는 이 동봉 전 빌드다. 공개 프로덕션 트랙은 동봉이 들어간 다음 AAB가 아니면 `PASS`로 표시하지 않는다.
