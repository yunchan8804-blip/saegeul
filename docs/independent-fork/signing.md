<!--
  SPDX-License-Identifier: LGPL-2.1-or-later
  SPDX-FileCopyrightText: Copyright 2026 Yun Chan
-->

# 새글 서명 SSOT

생성일: 2026-07-30

첫 독립 릴리스는 기존 Fcitx5 for Android 또는 개발용 키를 재사용하지 않는다.
GitHub/F-Droid 직접 배포 APK와 Google Play 업로드에는 다음 새 업로드 키를 사용한다.

| 항목 | 값 |
| --- | --- |
| alias | `saegeul-upload` |
| 알고리즘 | RSA 4096 / SHA384withRSA |
| 주체 | `CN=Yun Chan, O=Yun Chan, C=KR` |
| 인증서 SHA-256 | `3B:08:8B:5C:6A:69:E3:6C:62:80:2E:F5:D4:33:BD:9D:84:5B:8E:98:09:27:8E:13:13:36:A5:03:DA:90:1A:66` |

개인 키와 비밀번호는 저장소 밖에만 둔다. 현재 작업 기기의 비밀번호 파일은 Windows
current-user DPAPI로 암호화했으며 평문을 Git, CI 로그, Gradle property 또는 릴리스
아카이브에 기록하지 않는다.

Google Play App Signing을 사용하면 위 인증서는 업로드 인증서다. Play가 관리하는 앱
서명 인증서는 패키지 등록 뒤 이 문서에 별도로 기록하고, OAuth·API 공급자에 필요한
SHA-256도 두 인증서의 역할을 구분해 등록한다.

Play Console의 `kr.twentyoz.saegeul` 패키지 생성, App Signing 등록, 업로드 인증서
확인은 외부 콘솔 증거가 있어야 `DONE`으로 표시한다.
