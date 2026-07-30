# 독립 포크 전환 SSOT

이 문서는 독립 한국어 IME 포크 전환의 단일 기준이다. 제품명이나 공개 식별자를
코드에서 먼저 바꾸지 않고, 소유권·배포·라이선스·검증 계약을 여기서 먼저 확정한다.

## 1. 기준 상태

기준 조사일은 2026-07-30이다.

| 항목 | 기준 |
| --- | --- |
| 84개 작업의 기존 프로젝트 기준 커밋 | `0eb0e0699b0309b5f197dfb2db5fb92eabbb7dfa` |
| 기존 작업 HEAD | `be796c3905e8148e37402846a8c274cab232eccb` |
| 저장돼 있던 기존 원격 기준 추가 커밋 | 정확히 84개, 당시 behind 0 |
| 84개 커밋 작성자 | `Yun Chan <yunchan@twentyoz.kr>` |
| 2026-07-30 새로 확인한 upstream HEAD | `bcb694384de8462302448cab6a3dfb1853ba5d5e` |
| 최신 upstream과의 차이 | 기준 분기점 이후 upstream 15개, 포크 85개 |
| 미커밋 제품 변경 정리 | 독립 포크 브랜치의 별도 커밋으로 보존 |
| 기준 태그 | `fork-baseline-2026-07-30` |
| 기준 태그 성격 | 독립 브랜드 전환 전 보존점, 공개 제품 릴리스가 아님 |

태그의 최종 커밋과 서브모듈 해시는 문서에 중복 기록하지 않는다.
`scripts/create-source-archive.ps1`이 태그에서 생성하는
`SOURCE-MANIFEST.json`을 기계 판독 가능한 기준으로 사용한다.

84개 작업은 `0eb0e069...`에서 갈라진 사실 그대로 보존한다. 기준 태그를 만들기 위해
최신 upstream 15개를 먼저 merge/rebase하면 "84개와 미커밋 변경의 정확한 보존점"이
달라지므로, 최신 upstream 통합은 기준 태그 이후 별도 호환성 패치로 수행한다.

## 2. Git 소유권 계약

- `upstream`: 기존 `https://github.com/fcitx5-android/fcitx5-android.git`
- `origin`: Yun Chan이 소유하는 새 독립 저장소
- 제품 브랜치와 태그는 `origin`에만 게시한다.
- `upstream`에서는 기존 프로젝트의 변경을 가져오기만 한다.
- `fork-baseline-2026-07-30` 태그는 서명된 제품 릴리스가 아니라 현재 자산의
  보존 기준이다.

새 저장소 URL은 제품명과 저장소 slug를 확정한 뒤 채운다. 소유 원격이 없는 상태를
임시 URL로 위장하지 않는다.

## 3. 소스 아카이브 계약

배포 소스는 체크아웃 폴더를 압축하지 않고 태그의 Git 객체만 내보낸다.

- 최상위 저장소의 정확한 태그 커밋을 포함한다.
- 재귀 서브모듈의 태그 기록 해시를 확인하고 각 소스 트리를 같은 아카이브에 포함한다.
- `SOURCE-MANIFEST.json`에 태그, 최상위 커밋, 트리, 서브모듈 경로·해시·URL을 기록한다.
- 아카이브 SHA-256 sidecar를 함께 생성한다.
- `.tmp-*`, 캡처, 빌드 폴더, APK/AAB, 로그, 객체 파일, 자격 증명은 포함하지 않는다.
- `gradlew`, `gradlew.bat`, Gradle 설정, 라이선스와 빌드 스크립트를 포함한다.
- 바이너리 릴리스와 같은 태그의 아카이브만 같은 다운로드 위치에 게시한다.

생성:

```powershell
.\scripts\create-source-archive.ps1 -Ref fork-baseline-2026-07-30
```

검증:

```powershell
.\scripts\verify-source-archive.ps1 `
  -ArchivePath .\artifacts\source\fork-baseline-2026-07-30-source.tar.gz `
  -Ref fork-baseline-2026-07-30
```

## 4. 공개 제품 정체성

다음 값은 한 번 확정하면 Play 패키지·OAuth·외부 플러그인 호환성에 영향을 주므로
소유자 승인 전까지 코드에 임의의 값을 넣지 않는다.

| 결정 | 상태 | 확정 규칙 |
| --- | --- | --- |
| 제품명 | `GATE` | 기존 Fcitx5 명칭과 혼동되지 않는 독립 이름 |
| 제품 slug | `GATE` | 소문자 ASCII, 저장소·패키지에 공통 사용 |
| 소유 도메인 | `GATE` | 실제 제어 가능한 도메인 |
| applicationId | `GATE` | `kr.<소유도메인>.<제품slug>` |
| 저장소 URL | `GATE` | 소유 계정의 새 공개 원격 |
| 소스 다운로드 URL | `GATE` | 바이너리와 동일 태그의 소스·SBOM 제공 |
| 개인정보처리방침 URL | `GATE` | 실제 데이터 처리와 일치하는 공개 HTTPS 문서 |
| 저작권자 | `DECIDED` | 새 파일은 우선 `Yun Chan`, 별도 법인 양도 시 일괄 갱신 |

첫 공개 분리 단계에서는 내부 Kotlin namespace
`org.fcitx.fcitx5.android`를 유지한다. 공개 경계만 먼저 바꾼다.

- main applicationId 및 debug suffix
- Hangul 플러그인 applicationId
- OAuth callback scheme/URI
- FileProvider authority
- IPC signature permission
- 플러그인 manifest action, metadata key, 대상 package ID
- 앱 이름, 아이콘, 저장소·도메인·스토어 링크

데이터 이전은 기존 공식 앱의 저장소를 몰래 읽지 않는다. 사용자가 명시적으로
내보내고 새 앱에서 가져오는 버전 있는 이전 파일을 기본 경로로 삼는다. 민감한
클립보드 기록은 Android 백업·기기 이전·새 ZIP 내보내기와 가져오기에서 제외한다.
새 ZIP은 공개 applicationId와 독립적인 archive lineage version 2를 기록하며, 기존
`org.fcitx.fcitx5.android` 및 debug 변형의 version 1 ZIP만 명시적 legacy 입력으로
허용한다. 가져올 때 기본 SharedPreferences 파일을 새 package 이름으로 바꾸고
클립보드 활성화 값은 제거해 새 앱에서 다시 직접 켜도록 한다.

## 5. 표시와 라이선스 계약

앱과 저장소의 눈에 띄는 위치에 다음 의미의 고지를 제공한다.

> 이 제품은 Fcitx5를 기반으로 한 비공식 독립 포크이며, 원 프로젝트와
> 제휴하거나 원 프로젝트의 보증을 받지 않는다.

원 저작권과 라이선스 표시는 유지한다. 새로 만든 파일에는
`SPDX-License-Identifier: LGPL-2.1-or-later`와
`SPDX-FileCopyrightText: Copyright 2026 Yun Chan`을 사용한다.

공개 APK/AAB에는 최소한 다음을 포함한다.

- LGPL 2.1 전문
- GPL 2.0 전문
- Apache License 2.0 전문
- 실제 Release 의존성에 필요한 제3자 라이선스 전문과 저작권 고지
- `NOTICE`
- `FORK-NOTICE`
- 앱 안의 `오픈소스 라이선스`, `독립 포크 고지`, `소스 코드` 화면

Release 의존성 라이선스의 `Unknown`과 누락은 모두 0이어야 한다.

APK 산출물 검증:

```powershell
.\scripts\verify-release-licenses.ps1 `
  -ApkPath .\app\build\outputs\apk\release\<release-apk>.apk
```

## 6. 첫 독립 버전의 구성 경계

첫 독립 버전은 한국어 제품에 필요하지 않은 다음 표면을 기본적으로 제외한다.

- `fcitx5-chinese-addons`
- `pinyin.lua`
- Anthy, Chewing, Jyutping, Rime, Sayura, Thai, Unikey 등 미사용 언어 플러그인

Fcitx5 핵심과 libhangul 기반 한글 플러그인은 유지한다. 한글 플러그인은 첫 버전에서
별도 APK 패키지로 유지하고, 독립 applicationId/action 계약만 맞춘다. 메인 APK 번들링은
별도 설치·검색·데이터 이전 회귀 검증 뒤 다음 단계에서 수행한다.

현재 빌드 그래프는 `app`과 `plugin:hangul`만 제품 APK로 만든다.
`fcitx5-chinese-addons`와 Hangul 외 언어 플러그인은 Gradle 프로젝트에서 제외했으며,
upstream 추적과 소스 의무 이행을 위해 해당 소스와 서브모듈 기록은 저장소에 보존한다.
APK 검사는 Chinese Addons 라이선스 레코드, `pinyin.lua`, 관련 설정·번역·데이터의
잔존이 하나라도 있으면 실패한다.

## 7. 릴리스 필수 게이트

모든 공개 Release는 다음을 전부 통과해야 한다.

1. Release 의존성 라이선스 누락·Unknown 0
2. APK 안의 GPL/LGPL 구성요소와 라이선스 전문 일대일 대응
3. 정확한 소스 태그, 재귀 서브모듈 해시, SBOM, SHA-256 생성
4. API 키, OAuth client secret, 개인 키, 로컬 자격 증명 미포함
5. 기존 앱 이름·applicationId·FileProvider·IPC·플러그인 ID의 공개 경계 잔존 0
6. 공식 Fcitx 배포·후원·스토어 링크를 독립 제품 링크처럼 표시한 잔존 0
7. APK/AAB 설치와 실행
8. 한글 플러그인 검색·연결
9. 두벌식 한글 조합·확정
10. 명시적 내보내기/가져오기 데이터 이전
11. 공개 개인정보처리방침·앱 내 고지·Data Safety 답변 일치

APK split과 AAB는 같은 ABI 계약을 사용한다. `-PbuildABI=<abi>`를 주면 앱과 모든
네이티브 하위 모듈의 `ndk.abiFilters`를 함께 제한하고, override 없이 bundle을 만들면
지원 ABI 전체를 포함한다. 앱만 bundle 모드로 보고 하위 모듈은 split 모드로 남기는
혼합 구성은 허용하지 않는다.

릴리스 파일은 한 디렉터리에 APK, AAB, Hangul 플러그인 APK, `build-metadata.json`,
같은 태그의 재귀 소스 아카이브와 SHA-256 sidecar를 먼저 배치한다. 다음 검사는
소스 태그·바이너리 커밋 일치, 라이선스, 비밀값, 공개 식별자, 서명 인증서,
소스 URL, 앱 내부 개인정보 고지, 마이크·네트워크 권한, 클립보드 백업 제외를
검사하고 CycloneDX 1.6 SBOM을 생성한다.

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
  -ProductName <product-name> `
  -ApplicationId <kr.owned-domain.product> `
  -SourceRepositoryUrl <owned-repository-url> `
  -PrivacyPolicyUrl <owned-privacy-policy-url> `
  -SourceArchiveUrl <same-download-location-source-url> `
  -SigningCertificateSha256 <release-certificate-sha256>
```

`verify-release-identity.ps1`은 기존 Fcitx 공개 앱 ID, OAuth scheme, IPC permission,
provider authority, 플러그인 action/metadata, 공식 앱의 배포·스토어 링크가 남아 있으면
실패한다. 내부 Kotlin namespace와 원 구성요소의 저작권·소스 링크는 귀속과 호환성을
위해 허용한다.

실기기 게이트는 공식 `bundletool` JAR과 같은 빌드의 APK/AAB/한글 플러그인 APK/
instrumentation APK를 사용한다. AAB 설치와 APK 재설치 후 Android 패키지 관리자의
플러그인 action 검색, Fcitx 엔진의 `hangul` 검색, 두벌식 `가` 조합·확정, 구
`org.fcitx.fcitx5.android` 내보내기 데이터의 설정 이전과 클립보드 제외를 검사한다.

```powershell
.\scripts\verify-release-device.ps1 `
  -Serial <adb-serial> `
  -MainApkPath <main.apk> `
  -MainAabPath <main.aab> `
  -HangulApkPath <hangul.apk> `
  -AndroidTestApkPath <app-debug-androidTest.apk> `
  -BundletoolJarPath <bundletool-all.jar> `
  -KeystorePath <release-keystore> `
  -KeyAlias <release-key-alias> `
  -KeystorePasswordFile <outside-repository-password-file> `
  -KeyPasswordFile <outside-repository-password-file>
```

## 8. 스토어 정책 게이트

라이선스 준수와 Play 사용자 데이터 정책은 별도 게이트다. IME가 접근하거나 전송할
수 있는 입력 텍스트, 클립보드, 마이크 오디오, OCR 이미지, AI 요청을 실제 코드 경로와
대조해 다음을 작성한다.

- 개인정보처리방침
- 앱 내 수집·전송 전 고지와 동의
- Google Play Data Safety
- 데이터 보존·삭제·보안 전송 설명
- 기능별 네트워크/마이크/클립보드 비활성화 방법

정책 문구는 구현보다 넓거나 좁게 쓰지 않는다. 실제 Release APK와 런타임 네트워크
증거를 기준으로 최종 제출한다.

코드 기준 데이터 흐름, 구현된 고지, Data Safety 답변 초안과 공개 릴리스 차단 조건은
[`privacy-data-safety.md`](privacy-data-safety.md)를 단일 기준으로 사용한다. 현재
AI·음성·GIF 고지, 클립보드 기본 off·백업 제외, 앱 내부 개인정보·데이터 화면은
구현됐고, 법적 게시자·소유 개인정보처리방침 URL·공급자 계약·Play Console 제출은
제품 결정 `GATE`로 남아 있다.
