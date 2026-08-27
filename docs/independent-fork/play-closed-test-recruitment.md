<!--
SPDX-License-Identifier: LGPL-2.1-or-later
SPDX-FileCopyrightText: Copyright 2026 Yun Chan
-->

# 새글 Play 비공개 테스트 모집·운영 계획

상태: `READY_FOR_RECRUITMENT`
기준일: 2026-08-25
대상 버전: `0.1.0-rc.15` (`versionCode 152`)

## 목표와 현재값

- Google의 최소 조건은 12명이 비공개 테스트 참여를 14일 이상 연속 유지하는 것이다.
- Play Console 실시간 값은 `1 / 12`다. 이메일 목록에는 2명이 있지만 한 명만 실제로 참여를 선택했다.
- 이탈에 대비해 최소값이 아니라 **15명 이상 실제 옵트인**을 모집 목표로 삼는다.
- 15명 이상이 참여한 날을 Day 0으로 기록하고, 운영은 15일을 기준으로 한다.
- 평점·공개 리뷰·유료 설치를 요구하지 않는다. 설치, 실제 사용, 비공개 피드백만 요청한다.

## 모집 전 Play 설정 게이트

현재 Alpha는 대한민국 한 곳과 이메일 목록만 사용한다. 해외 개발자 모집글을 게시하기 전에 다음 변경이 필요하다.

1. Alpha 국가/지역을 전체 지원 국가로 확대한다.
2. `saegeul-closed-testers` Google 그룹을 만들고 누구나 직접 가입할 수 있도록 설정한다.
3. 기존 이메일 목록 2명을 그룹에 초대하여 현재 접근자를 보존한다.
4. Alpha 테스터 대상을 이메일 목록에서 Google 그룹으로 전환한다.
5. 의견 수집 채널은 공개 개인정보 노출을 줄이기 위해 새글 GitHub Issues 또는 그룹 내 대화로 제공한다.
6. 변경사항을 Google 검토에 제출하고 테스트 링크의 실제 접근을 다시 확인한다.

위 작업은 Play 접근 범위와 외부 Google 계정 상태를 바꾸므로 실행 직전 승인 후 수행한다.

## 참여 링크

- 웹 옵트인: <https://play.google.com/apps/testing/net.chanpaca.saegeul>
- Android 설치: <https://play.google.com/store/apps/details?id=net.chanpaca.saegeul>
- 제품 소개: <https://saegul.chanpaca.net/>
- 소스 및 이슈: <https://github.com/yunchan8804-blip/saegeul>
- Google 그룹: 생성 후 링크를 기입한다.

## 5분 테스트 체크리스트

테스터는 15일 동안 참여를 유지하고, 첫날과 기간 중 두 차례 이상 아래 항목을 확인한다.

1. Google 그룹 가입 후 웹 옵트인과 Play 설치가 정상적으로 되는지 확인한다.
2. Android 설정에서 새글을 키보드로 활성화하고 기본 입력기로 전환한다.
3. 메모 앱이나 메시지 입력창에서 영문, 숫자, 기호를 입력한다.
4. 한국어 사용자는 두벌식 또는 익숙한 배열로 `안녕하세요 새글 테스트입니다`를 입력한다.
5. 화면 회전, 앱 전환, 키보드 닫기·다시 열기를 한 번씩 확인한다.
6. 오류, 멈춤, 잘림, 입력 누락이 있으면 기기 모델·Android 버전·재현 순서와 함께 비공개 피드백을 남긴다.

## 채널별 게시 계획

### 1차 게시

- `r/AndroidClosedTesting`: Google 그룹과 두 Play 링크를 포함한 상호 테스트 모집글
- `r/TestersCommunity`: `Testers Needed` 플레어, 확인할 기능과 구체적 피드백 요청 포함
- `r/AndroidTesting`: 명확한 제목과 QA 중심 설명으로 게시
- GitHub Issues: 프로젝트 공식 모집·진행 현황 스레드

### 2차 보강

- Twelve Testers: 상호 QA 목록 등록. 계정 생성과 이메일 인증은 소유자 승인 및 직접 인증 후 진행한다.
- Day 3에도 실제 옵트인이 12명 미만이면 Reddit 글에 진행 상황을 갱신하고 추가 상호 테스트를 모집한다.

동일 문구를 무차별 복제하지 않는다. 각 커뮤니티 규칙에 맞춰 제목과 피드백 항목을 조정한다.

## 영어 모집글

### Title

`[Closed Test] Saegeul Korean Keyboard — looking for 15-day Android testers`

### Body

```text
Hi! I am preparing Saegeul, an independent open-source Korean keyboard for Android, for its first Google Play production release.

I am looking for reliable Android testers who can stay opted in for 15 days and use the keyboard a few times during the test. Korean fluency is not required: you can still verify installation, keyboard activation, English/number/symbol input, screen rotation, and app switching.

How to join
1. Join the Google Group: [GROUP_LINK]
2. Opt in on the web: https://play.google.com/apps/testing/net.chanpaca.saegeul
3. Install from Google Play: https://play.google.com/store/apps/details?id=net.chanpaca.saegeul
4. Keep the app installed and remain opted in for at least 15 days.
5. Open and use it on at least three separate days and send one specific piece of private feedback.

Suggested 5-minute check
- Enable Saegeul in Android keyboard settings.
- Switch to it in a notes or messaging app.
- Type English, numbers, and symbols.
- If you use Korean, type: 안녕하세요 새글 테스트입니다
- Rotate the screen and reopen the keyboard after switching apps.

This is a request for genuine private QA only — no ratings or public reviews.

Product: https://saegul.chanpaca.net/
Source: https://github.com/yunchan8804-blip/saegeul
```

상호 테스트를 약속하는 문장은 실제로 반환 테스트를 수행할 범위가 승인된 뒤에만 추가한다.

## 한국어 모집글

### 제목

`[안드로이드 비공개 테스트] 새글 한국어 키보드 15일 테스터를 모집합니다`

### 본문

```text
안녕하세요. 독립 오픈소스 안드로이드 한국어 키보드 새글의 첫 Google Play 정식 출시를 준비하고 있습니다.

Google Play 비공개 테스트에 참여하여 15일 동안 참여 상태를 유지하고, 기간 중 세 차례 정도 실제 키보드 입력을 확인해 주실 안드로이드 사용자를 모집합니다. 평점이나 공개 리뷰를 요청하지 않으며, 설치·실사용·비공개 피드백만 부탁드립니다.

참여 방법
1. Google 그룹 가입: [GROUP_LINK]
2. 웹에서 테스트 참여: https://play.google.com/apps/testing/net.chanpaca.saegeul
3. Google Play 설치: https://play.google.com/store/apps/details?id=net.chanpaca.saegeul
4. 15일 이상 설치 및 참여 상태 유지
5. 첫날과 기간 중 두 차례 이상 입력해 보고 구체적인 의견 한 가지 남기기

5분 확인 항목
- Android 설정에서 새글 키보드 활성화
- 메모 또는 메시지 앱에서 새글로 전환
- 영문·숫자·기호 입력
- 익숙한 한글 배열로 `안녕하세요 새글 테스트입니다` 입력
- 화면 회전과 앱 전환 후 키보드 다시 열기

제품 소개: https://saegul.chanpaca.net/
소스 및 이슈: https://github.com/yunchan8804-blip/saegeul
```

## 운영 기록

모든 수치는 이메일 목록 인원이 아니라 Play 대시보드의 실제 참여 선택 인원을 기준으로 기록한다.

| 날짜 | 실제 옵트인 | 신규 | 이탈 | 수행한 조치 | 확인 근거 |
|---|---:|---:|---:|---|---|
| 2026-08-25 | 1 | - | 1 | 모집 계획 확정 | Play Console 대시보드 |

프로덕션 신청 시에는 모집 경로, 테스트한 기능, 받은 피드백, 반영한 변경사항을 이 기록에서 요약한다.
