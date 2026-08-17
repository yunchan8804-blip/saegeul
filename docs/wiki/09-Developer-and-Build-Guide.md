# 09. 빌드 및 개발자 가이드 (Build & Developer Guide)

새글(Saegeul)의 소스 코드 구조, 로컬 개발 환경 구성, 서브모듈 관리 및 릴리스 빌드 절차를 안내합니다.

---

## 1. 개발 환경 요구사항

- **OS:** Windows 10/11, macOS, Linux (Ubuntu 22.04+)
- **JDK:** OpenJDK 17 이상 (Eclipse Temurin 또는 Azul Zulu 권장)
- **Android SDK:** API Level 34 (Compile), API 26+ (Min)
- **Android NDK:** `26.1.10909125`
- **CMake:** `3.22.1` 이상
- **Git:** Git 2.30+ (서브모듈 재귀 지원)

---

## 2. 저장소 클론 및 서브모듈 동기화

새글은 Fcitx5 코어와 libhangul 등 C/C++ 네이티브 하위 모듈을 서브모듈로 포함합니다.

```bash
# 서브모듈을 포함하여 재귀 클론
git clone --recurse-submodules https://github.com/yunchan8804-blip/saegeul.git
cd saegeul

# 이미 클론한 경우 서브모듈 갱신
git submodule update --init --recursive
```

---

## 3. Gradle 빌드 명령어

### Debug 빌드 (개발 및 로그 진단용)
```bash
./gradlew :app:assembleDebug :plugin:hangul:assembleDebug
```
- 생성 위치: `app/build/outputs/apk/debug/` 및 `plugin/hangul/build/outputs/apk/debug/`

### Release 빌드 (공식 서명 배포용)
```bash
./gradlew :app:assembleRelease :plugin:hangul:assembleRelease
```

### 특정 ABI 단일 아키텍처 빌드
```bash
./gradlew :app:assembleRelease :plugin:hangul:assembleRelease -PbuildABI=arm64-v8a
```

---

## 4. 릴리스 필수 검증 게이트

공식 릴리스 전 아래 스크립트로 번들 무결성과 라이선스 적합성을 검증합니다.

```powershell
# 1. 라이선스 누락 검사
.\scripts\verify-release-licenses.ps1 -ApkPath .\app\build\outputs\apk\release\net.chanpaca.saegeul.apk

# 2. 공개 식별자 및 개인정보 일치성 검사
.\scripts\verify-release-identity.ps1 -ApkPath .\app\build\outputs\apk\release\net.chanpaca.saegeul.apk

# 3. 전체 릴리스 번들 및 CycloneDX SBOM 검증
.\scripts\verify-release-bundle.ps1 `
  -ReleaseDirectory .\artifacts\release `
  -MainApkPath .\artifacts\release\net.chanpaca.saegeul.apk `
  -MainAabPath .\artifacts\release\net.chanpaca.saegeul.aab `
  -HangulApkPath .\artifacts\release\net.chanpaca.saegeul.plugin.hangul.apk `
  -BuildMetadataPath .\artifacts\release\build-metadata.json `
  -SourceArchivePath .\artifacts\release\saegeul-v0.1.0-source.tar.gz `
  -SourceTag saegeul-v0.1.0 `
  -SbomOutputPath .\artifacts\release\saegeul-v0.1.0.cdx.json `
  -ProductName "새글 (Saegeul)" `
  -ApplicationId "net.chanpaca.saegeul" `
  -SourceRepositoryUrl "https://github.com/yunchan8804-blip/saegeul" `
  -PrivacyPolicyUrl "https://saegul.chanpaca.net/privacy/" `
  -SourceArchiveUrl "https://github.com/yunchan8804-blip/saegeul/releases/download/saegeul-v0.1.0/saegeul-v0.1.0-source.tar.gz" `
  -SigningCertificateSha256 "3B:08:8B:5C:6A:69:E3:6C:62:80:2E:F5:D4:33:BD:9D:84:5B:8E:98:09:27:8E:13:13:36:A5:03:DA:90:1A:66"
```
