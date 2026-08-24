<!--
  SPDX-License-Identifier: LGPL-2.1-or-later
  SPDX-FileCopyrightText: Copyright 2026 Yun Chan
-->

# 새글 서명 SSOT

상태: `PLAY_APP_SIGNING_ACTIVE`
기준일: 2026-08-25

첫 독립 릴리스는 기존 Fcitx5 for Android 또는 개발용 키를 재사용하지 않는다.
GitHub/F-Droid 직접 배포 APK와 Google Play 업로드에는 다음 새 업로드 키를 사용한다.

| 역할 | 값 |
| --- | --- |
| alias | `saegeul-upload` |
| 알고리즘 | RSA 4096 / SHA384withRSA |
| 주체 | `CN=Yun Chan, O=Yun Chan, C=KR` |
| GitHub 직접 배포 APK·Play 업로드 인증서 SHA-256 | `3B:08:8B:5C:6A:69:E3:6C:62:80:2E:F5:D4:33:BD:9D:84:5B:8E:98:09:27:8E:13:13:36:A5:03:DA:90:1A:66` |
| Google Play 앱 서명 인증서 SHA-256 | `9F:22:43:69:28:D5:DC:AE:08:EF:26:57:DF:D7:C3:AB:88:F6:B7:E3:04:8F:B7:1D:2D:41:E9:C2:25:6F:E9:11` |

개인 키와 비밀번호는 저장소 밖에만 둔다. 현재 작업 기기의 비밀번호 파일은 Windows
current-user DPAPI로 암호화했으며 평문을 Git, CI 로그, Gradle property 또는 릴리스
아카이브에 기록하지 않는다.

Google Play App Signing이 활성화되어 있으므로 Play 배포본과 GitHub 직접 배포 APK의
서명 지문은 의도적으로 다르다. OAuth·API 공급자와 Digital Asset Links에는 설치 경로에
맞는 인증서를 등록해야 하며, Play가 제공한 Digital Asset Links 지문은 Play 앱 서명
인증서 `9F:22:...:E9:11`이다.

Play Console의 `net.chanpaca.saegeul` 패키지, App Signing, 업로드 인증서와 Play 앱
서명 인증서는 2026-08-25 실콘솔에서 확인했다. 키 변경·재설정은 별도 승인 없이는 하지 않는다.
