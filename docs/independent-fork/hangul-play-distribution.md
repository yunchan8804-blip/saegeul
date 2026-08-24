<!--
SPDX-License-Identifier: LGPL-2.1-or-later
SPDX-FileCopyrightText: Copyright 2026 Yun Chan
-->

# 한글 엔진 Play 배포 SSOT

상태: `CLOSED_ALPHA_LIVE`
기준일: 2026-08-25
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

## 현재 Play 배포

- 소스: `bdcc7f33`, 태그 `saegeul-v0.1.0-rc.15`
- Play 버전: `0.1.0-rc.15`, versionCode `152`
- AAB SHA-256: `1fd8979fb63943a0b4b540c9ea7c35f82f829bf5cad5e2c117ba0f317e3d7e34`
- 내부 테스트: 2026-08-24 19:11 KST부터 전체 출시, 내부 테스터에게 제공됨
- 비공개 테스트 Alpha: 2026-08-24 19:40 KST부터 전체 출시, 대한민국의 선택된 테스터에게 제공됨
- 독립 검증: 한글 자산·descriptor·4 ABI native library·법적 고지·개인정보 파일 포함, 금지된 중국어 자산 없음
- Play App Signing: 활성, Play 앱 서명 SHA-256 `9F:22:43:69:28:D5:DC:AE:08:EF:26:57:DF:D7:C3:AB:88:F6:B7:E3:04:8F:B7:1D:2D:41:E9:C2:25:6F:E9:11`

따라서 “한글 엔진이 포함된 AAB가 Play 테스트 트랙에 실제 게시됐는가” 게이트는 `PASS`다.

## 남은 프로덕션 게이트

- `2 / 12` 테스터가 Alpha 참여를 실제 선택했다. Google 계정 테스터 10명을 더 등록하고 각 계정이 웹 참여 링크에서 직접 옵트인해야 한다.
- Play가 요구하는 기준은 12명 이상이 참여한 비공개 테스트를 14일 이상 연속 실행하는 것이다. 현재 프로덕션 신청 버튼은 비활성화 상태다.
- Alpha 의견 수집 채널 변경 1건이 저장되어 있으나 아직 Google 검토 제출 전이다. 실제 제출은 최종 승인 뒤에만 한다.
- 현재 ADB 연결 기기가 없다. Play 서명 설치본을 실제 휴대전화에 설치하여 키보드 활성화, 기본 IME 선택, 콜드 스타트 한글 조합을 확인해야 한다.
- 사전 출시 보고서는 아직 생성된 결과를 표시하지 않고 “아티팩트를 업로드하여 생성” 안내만 표시한다. 자동 보고서를 제품 GREEN 증거로 계산하지 않는다.
- Play 발견 항목은 Android 15+ edge-to-edge와 지원 중단 API 2개다. 직접 호출 시작점은 `androidx.activity.EdgeToEdgeApi35.setUp`과 `NavigationBarManager.evaluate`, API는 `Window.setStatusBarColor`와 `Window.setNavigationBarColor`다. 게시 차단은 아니지만 다음 RC에서 실기기 인셋·3버튼 탐색 동작과 함께 정리한다.

프로덕션 신청이나 공개 출시는 위 조건이 닫히고 명시적 최종 승인을 받기 전에는 실행하지 않는다.
