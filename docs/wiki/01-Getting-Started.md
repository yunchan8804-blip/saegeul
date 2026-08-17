# 01. 시작하기 (Getting Started)

새글(Saegeul)의 설치, 초기 설정, 릴리스 서명 지문 검증 절차를 안내합니다.

---

## 1. 다운로드 (Download)

[GitHub Releases](https://github.com/yunchan8804-blip/saegeul/releases)에서 동일한 버전 태그의 바이너리 2개를 내려받아야 합니다.

- **새글 메인 앱:** `net.chanpaca.saegeul-vX.Y.Z.apk`
- **한글 플러그인:** `net.chanpaca.saegeul.plugin.hangul-vX.Y.Z.apk`

> [!IMPORTANT]
> 새글은 모듈화된 `libhangul` C 엔진 아키텍처를 따르므로, 메인 앱과 한글 플러그인을 모두 설치해야 한글 입력 엔진이 정상 검색됩니다. 두 파일은 항상 동일한 릴리스 태그에서 받아야 버전 불일치가 발생하지 않습니다.

---

## 2. 설치 및 Play Protect 경고 대처

Google Play Store 외부에서 APK를 직접 내려받았을 때 Google Play Protect가 아직 등록되지 않은 서명으로 인식하여 경고 창을 띄울 수 있습니다.

### 대처 방법
1. 경고 창에서 **자세히 보기 > 무시하고 설치**를 누르면 정상 설치됩니다.
2. 만약 기기 설정에서 설치가 차단될 경우:
   - **Google Play 스토어 앱 > 우측 상단 프로필 > Play Protect > 설정(톱니바퀴)**
   - *"Play Protect로 앱 검사"*를 일시 해제 후 설치를 완료합니다.

---

## 3. 키보드 활성화 및 기본 키보드 설정

설치가 완료되면 Android 설정에서 새글을 켭니다.

1. Android **설정 > 일반 관리 > 키보드 목록 및 기본값**으로 이동합니다.
2. 키보드 목록에서 **새글** 스위치를 켭니다.
3. **기본 키보드** 항목을 눌러 **새글**로 선택합니다.
4. 이제 문자, 카카오톡, 메모장 등 아무 입력창을 누르면 새글 키보드가 즉시 열립니다.

---

## 4. 공식 서명 지문 검증 (SHA-256)

새글의 공식 릴리스는 변조되지 않은 단일 릴리스 키로 서명됩니다.

```text
3B:08:8B:5C:6A:69:E3:6C:62:80:2E:F5:D4:33:BD:9D:84:5B:8E:98:09:27:8E:13:13:36:A5:03:DA:90:1A:66
```

### 터미널에서 직접 서명 검증하기
Android SDK의 `apksigner` 도구를 사용하여 APK의 무결성을 검증할 수 있습니다:

```bash
apksigner verify --verbose --print-certs net.chanpaca.saegeul-v0.1.0.apk
```
출력되는 인증서 SHA-256 지문이 위 값과 일치하고 `Verified using v1/v2/v3 scheme: true`이면 공식 배포 바이너리임이 보장됩니다.
