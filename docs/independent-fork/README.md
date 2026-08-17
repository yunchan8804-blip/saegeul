# 독립 포크 전환 SSOT (Single Source of Truth)

이 문서는 새글(Saegeul)의 독립 한국어 IME 포크 전환에 관한 단일 기준 규격입니다. 제품명과 공개 식별자, 소유권·배포·라이선스·검증 계약을 여기서 확정하고 코드와 배포물을 이 규격에 맞춥니다.

---

## 1. 기준 상태 (Baseline State)

기준 조사일: **2026-07-30**

| 항목 | 기준값 | 설명 |
| :--- | :--- | :--- |
| **84개 작업의 기존 프로젝트 기준 커밋** | `0eb0e0699b0309b5f197dfb2db5fb92eabbb7dfa` | 분기 기준점 |
| **기존 작업 HEAD** | `be796c3905e8148e37402846a8c274cab232eccb` | 원본 작업 최종 커밋 |
| **저장돼 있던 기존 원격 기준 추가 커밋** | 정확히 84개, 당시 behind 0 | 로컬 변경점 |
| **84개 커밋 작성자** | `Yun Chan <yunchan@twentyoz.kr>` | 독립 작업 작성자 |
| **2026-07-30 새로 확인한 upstream HEAD** | `bcb694384de8462302448cab6a3dfb1853ba5d5e` | upstream 변경점 |
| **최신 upstream과의 차이** | 기준 분기점 이후 upstream 15개, 포크 85개 | 병합 격차 |
| **미커밋 제품 변경 정리** | 독립 포크 브랜치의 별도 커밋으로 보존 | 변경 무결성 |
| **기준 태그** | `fork-baseline-2026-07-30` | 독립 브랜드 전환 전 보존점 |
| **기준 태그 성격** | 공개 제품 릴리스가 아닌 자산 보존점 | 계약상 보존점 |

> [!NOTE]
> 태그의 최종 커밋과 서브모듈 해시는 문서에 중복 기록하지 않습니다. `scripts/create-source-archive.ps1`이 태그에서 생성하는 `SOURCE-MANIFEST.json`을 기계 판독 가능한 기준으로 사용합니다.

---

## 2. Git 소유권 계약

- **`upstream`:** `https://github.com/fcitx5-android/fcitx5-android.git` (기존 프로젝트 변경 추적용, push 비활성화)
- **`origin`:** `https://github.com/yunchan8804-blip/saegeul.git` (Yun Chan 소유, 제품 브랜치 및 릴리스 태그 관리)
- 제품 브랜치와 태그는 `origin`에만 게시합니다.
- `fork-baseline-2026-07-30` 태그는 서명된 제품 릴리스가 아니라 현재 자산의 보존 기준입니다.

---

## 3. 소스 아카이브 계약

배포 소스는 체크아웃 폴더를 압축하지 않고 태그의 Git 객체만을 정확히 내보냅니다.

- 최상위 저장소의 정확한 태그 커밋을 포함합니다.
- 재귀 서브모듈의 태그 기록 해시를 확인하고 각 소스 트리를 같은 아카이브에 포함합니다.
- `SOURCE-MANIFEST.json`에 태그, 최상위 커밋, 트리, 서브모듈 경로·해시·URL을 기록합니다.
- 아카이브 SHA-256 sidecar를 함께 생성합니다.
- `.tmp-*`, 캡처, 빌드 폴더, APK/AAB, 로그, 객체 파일, 자격 증명은 포함하지 않습니다.
- `gradlew`, `gradlew.bat`, Gradle 설정, 라이선스와 빌드 스크립트를 포함합니다.
- 바이너리 릴리스와 같은 태그의 아카이브만 같은 다운로드 위치에 게시합니다.

### 소스 아카이브 생성
```powershell
.\scripts\create-source-archive.ps1 -Ref fork-baseline-2026-07-30
```

### 소스 아카이브 검증
```powershell
.\scripts\verify-source-archive.ps1 `
  -ArchivePath .\artifacts\source\fork-baseline-2026-07-30-source.tar.gz `
  -Ref fork-baseline-2026-07-30
```

---

## 4. 공개 제품 정체성 (Identity Contract)

다음 값은 Play 패키지·OAuth·외부 플러그인 호환성의 공개 계약입니다.

| 항목 | 결정 상태 | 확정값 / 규칙 |
| :--- | :--- | :--- |
| **제품명** | `DECIDED` | `새글 (Saegeul)` |
| **제품 slug** | `DECIDED` | `saegeul` |
| **소유 도메인** | `DECIDED` | `chanpaca.net`, 제품 호스트 `saegul.chanpaca.net` |
| **applicationId** | `DECIDED` | `net.chanpaca.saegeul` |
| **저장소 URL** | `DECIDED` | `https://github.com/yunchan8804-blip/saegeul` |
| **소스 다운로드 URL** | `DECIDED` | `https://github.com/yunchan8804-blip/saegeul/releases` |
| **개인정보처리방침 URL** | `DECIDED` | `https://saegul.chanpaca.net/privacy/` |
| **저작권자** | `DECIDED` | 새 파일은 우선 `Yun Chan`, 별도 법인 양도 시 일괄 갱신 |

첫 공개 분리 단계에서는 내부 Kotlin namespace `org.fcitx.fcitx5.android`를 유지하며 공개 경계만을 분리합니다.

- `main applicationId` 및 debug suffix (`net.chanpaca.saegeul.debug`)
- `Hangul 플러그인 applicationId` (`net.chanpaca.saegeul.plugin.hangul`)
- OAuth callback scheme/URI, FileProvider authority, IPC signature permission
- 플러그인 manifest action, metadata key, 대상 package ID
- 앱 이름, 아이콘, 저장소·도메인·스토어 링크

---

## 5. 표시와 라이선스 계약

앱과 저장소의 눈에 띄는 위치에 다음 고지를 필수 제공합니다.

> **새글은 Fcitx5를 기반으로 한 비공식 독립 포크이며, 원 프로젝트와 제휴하거나 원 프로젝트의 보증을 받지 않습니다.**

- 원 저작권과 라이선스 표시는 온전히 유지합니다.
- 새로 만든 파일에는 `SPDX-License-Identifier: LGPL-2.1-or-later` 및 `SPDX-FileCopyrightText: Copyright 2026 Yun Chan`을 사용합니다.
- 공개 APK/AAB에는 LGPL 2.1, GPL 2.0, Apache 2.0 및 실제 의존성에 필요한 모든 라이선스 전문을 포함합니다.
- Release 의존성 라이선스의 `Unknown`과 누락은 0이어야 합니다.

### 라이선스 무결성 검증
```powershell
.\scripts\verify-release-licenses.ps1 `
  -ApkPath .\app\build\outputs\apk\release\<release-apk>.apk
```

---

## 6. 첫 독립 버전의 구성 경계

첫 독립 버전은 한국어 제품에 필요하지 않은 다음 요소를 제외합니다.

- `fcitx5-chinese-addons`
- `pinyin.lua`
- Anthy, Chewing, Jyutping, Rime, Sayura, Thai, Unikey 등 미사용 언어 플러그인

Fcitx5 핵심 코어와 libhangul 기반 한글 플러그인은 유지하며, 현재 빌드 그래프는 `app`과 `plugin:hangul`만을 제품 APK로 산출합니다.

---

## 7. 릴리스 필수 게이트 (Release Gates)

모든 공개 Release는 다음 11개 항목을 전부 통과해야 합니다.

1. Release 의존성 라이선스 누락·Unknown 0개
2. APK 안의 GPL/LGPL 구성요소와 라이선스 전문 1:1 일치
3. 정확한 소스 태그, 재귀 서브모듈 해시, SBOM, SHA-256 생성
4. API 키, OAuth client secret, 개인 키, 로컬 자격 증명 미포함
5. 기존 앱 이름·applicationId·FileProvider·IPC·플러그인 ID의 공개 경계 잔존 0개
6. 공식 Fcitx 배포·후원·스토어 링크를 독립 제품 링크처럼 표시한 잔존 0개
7. APK/AAB 설치와 정상 실행
8. 한글 플러그인 자동 검색 및 바인딩
9. 두벌식 한글 조합·확정 무결성
10. 명시적 내보내기/가져오기 데이터 이전
11. 공개 개인정보처리방침·앱 내 고지·Data Safety 답변 일치

### 릴리스 번들 전체 검증 명령
```powershell
.\scripts\verify-release-bundle.ps1 `
  -ReleaseDirectory <release-directory> `
  -MainApkPath <release-directory>\<main>.apk `
  -MainAabPath <release-directory>\<main>.aab `
  -HangulApkPath <release-directory>\<hangul>.apk `
  -BuildMetadataPath <release-directory>\build-metadata.json `
  -SourceArchivePath <release-directory>\<tag>-source.tar.gz `
  -SourceTag <tag> `
  -SbomOutputPath <release-directory>\<tag>.cdx.json `
  -ProductName "새글 (Saegeul)" `
  -ApplicationId "net.chanpaca.saegeul" `
  -SourceRepositoryUrl "https://github.com/yunchan8804-blip/saegeul" `
  -PrivacyPolicyUrl "https://saegul.chanpaca.net/privacy/" `
  -SourceArchiveUrl <same-download-location-source-url> `
  -SigningCertificateSha256 "3B:08:8B:5C:6A:69:E3:6C:62:80:2E:F5:D4:33:BD:9D:84:5B:8E:98:09:27:8E:13:13:36:A5:03:DA:90:1A:66"
```
