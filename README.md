# 새글 (Saegeul)

<p align="center">
  <img src="docs/brand/saegeul-icon.svg" width="128" height="128" alt="새글 로고">
</p>

<p align="center">
  <strong>한국어의 결을 아는 자판. 내 컴퓨터와 연결하는 AI.</strong><br>
  Android를 위한 독립 오픈소스 한국어 입력기
</p>

<p align="center">
  <a href="https://github.com/yunchan8804-blip/saegeul/releases"><img src="https://img.shields.io/github/v/release/yunchan8804-blip/saegeul?style=flat-square&color=55D6A6&label=Release" alt="Latest Release"></a>
  <a href="https://saegul.chanpaca.net"><img src="https://img.shields.io/badge/Website-saegul.chanpaca.net-0a172a?style=flat-square&logo=cloudflare&logoColor=55D6A6" alt="Official Website"></a>
  <a href="docs/wiki/Home.md"><img src="https://img.shields.io/badge/Wiki-Documentation-0c5b48?style=flat-square&logo=gitbook&logoColor=white" alt="Wiki Documentation"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-LGPL--2.1--or--later-blue?style=flat-square" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-success?style=flat-square" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Offline-100%25%20On--Device%20Core-55D6A6?style=flat-square" alt="100% On-Device Core">
  <img src="https://img.shields.io/badge/AI%20Companion-0%20Won%20Addon-79F1C2?style=flat-square" alt="0 Won AI Companion">
</p>

---

## 📖 목차

- [✨ 핵심 특징 (Key Highlights)](#-핵심-특징-key-highlights)
- [⌨️ 17가지 한국어 자판 지원](#️-17가지-한국어-자판-지원)
- [🖥️ AI와 내 컴퓨터 연결 (Companion 0원 아키텍처)](#️-ai와-내-컴퓨터-연결-companion-0원-아키텍처)
- [📦 다운로드 및 설치 (Quick Start)](#-다운로드-및-설치-quick-start)
- [🔒 개인정보 및 보안 원칙 (Data Boundary)](#-개인정보-및-보안-원칙-data-boundary)
- [🛠️ 소스 코드 및 빌드 (Build Guide)](#️-소스-코드-및-빌드-build-guide)
- [📚 [공식 상세 위키 문서 전체 보기 (Wiki)](docs/wiki/Home.md)](#-공식-상세-위키-문서-전체-보기-wiki)
- [📜 오픈소스 계보 및 독립 포크 고지](#-오픈소스-계보-및-독립-포크-고지)
- [🌐 관련 링크 & 커뮤니티](#-관련-링크--커뮤니티)

---

## ✨ 핵심 특징 (Key Highlights)

새글(Saegeul)은 타협 없는 한글 입력 엔진과 안전한 AI 글쓰기를 결합한 Android 독립 오픈소스 키보드입니다.

- **100% 온디바이스 한글 조합:** `libhangul` C 엔진이 기기 내 메모리에서 직접 글자를 조합하여 딜레이와 프레임 드랍이 없습니다.
- **17가지 정밀 자판 배열:** 천지인, 나랏글, 모아키, 두벌식, 세벌식, 안마태 등 스마트폰부터 태블릿까지 완벽 대응합니다.
- **지능형 한글 입력 도구:** 한/영 오타 자동 복구(`dkssud` ➔ `안녕`), 초성 검색, 조사 받침 자동 판별, 31,808 표제어 오프라인 표준국어대사전 내장.
- **추가 요금 0원 AI 글쓰기:** 내 Windows PC에 로그인된 Codex CLI 또는 Claude Code를 Tailscale HTTPS 종단간 암호화로 연동하여 별도 API 비용 없이 모바일에서 바로 사용합니다.
- **기기 내 온디바이스 OCR:** 카메라나 갤러리에서 선택한 이미지 속 한글·영어 텍스트를 기기 안에서 100% 로컬 인식합니다.
- **철저한 데이터 격리 & 0바이트 오프라인 검증:** 개발자 운영 서버, 광고 SDK, 분석 트래커가 전혀 없습니다. 원클릭으로 모든 네트워크를 차단하는 완전 오프라인 모드를 제공합니다.
- **폴더블 & 태블릿 분할 키보드:** 기기 화면을 펼치면 양손 타이핑에 최적화된 분할 키보드로 전환되며, 접힘/펼침과 가로/세로 레이아웃을 독립 기억합니다.

---

## ⌨️ 17가지 한국어 자판 지원

새글은 현대 한국어 입력에 쓰이는 주요 표준 및 특수 자판 17가지를 기본 지원합니다.

| 분류 | 지원 자판 배열 | 특징 및 용도 |
| :--- | :--- | :--- |
| **모바일 표준 (천지인 계열)** | `천지인`, `천지인 플러스` | 한 손 타이핑에 최적화된 모음 획 추가 조합 |
| **획 기반 (나랏글/베가 계열)** | `나랏글 (EZ한글)`, `베가 (단키)` | 획 추가 및 쌍자음 버튼을 통한 빠른 모바일 입력 |
| **제스처/슬라이드 계열** | `모아키 (MoAKey)` | 자음에서 모음 방향으로 밀어서 한 번에 한 음절 완성 |
| **표준 쿼티 (두벌식 계열)** | `두벌식`, `단모음 (두벌식 기반)` | 표준 물리 자판 배열 및 오타 방지용 단모음 배열 |
| **세벌식 계열** | `세벌식 390`, `세벌식 최종`, `세벌식 순이`, `세벌식 3-91`, `세벌식 3-93`, `세벌식 단모음` | 초성·중성·종성을 동시 조합하는 고속 전문 타이핑 |
| **인체공학 계열** | `안마태 (Ahnmatae)` | 열 손가락의 피로도를 최소화한 자음/모음 대칭 배열 |

---

## 🖥️ AI와 내 컴퓨터 연결 (Companion 0원 아키텍처)

새글은 추가 유료 API 키 결제 없이, 내 PC에 이미 구독 중인 Codex CLI나 Claude Code를 안전하게 끌어와 쓸 수 있습니다.

```
┌─────────────────┐        Tailscale Serve HTTPS         ┌─────────────────────────┐
│   Android 새글   │ ─────────────────────────────────> │    Windows Companion    │
│  (모바일 키보드) │ <───────────────────────────────── │  (로컬 시스템 트레이 프록시) │
└─────────────────┘        종단간 암호화 터널 (mTLS)       └────────────┬────────────┘
                                                                       │ 로컬 stdio/CLI
                                                                       ▼
                                                          ┌─────────────────────────┐
                                                          │   Codex / Claude CLI    │
                                                          │    (기존 구독 재사용 0원) │
                                                          └─────────────────────────┘
```

1. **PC Companion 실행 (OS별 원클릭 설치 지원):**
   - **Windows:** [Releases](https://github.com/yunchan8804-blip/saegeul/releases)에서 `saegeul-companion-windows.zip` 다운로드 후 `install.bat` 실행 (또는 `companion/windows/install.bat`)
   - **macOS:** `saegeul-companion-macos.tar.gz` 다운로드 후 `install.command` 실행 (또는 `companion/macos/install.command`)
   - **Linux:** `saegeul-companion-linux.tar.gz` 다운로드 후 `./install.sh` 실행 (systemd 자동 등록)
2. **동일 Tailnet 자동 감지:** 휴대폰과 PC가 동일한 Tailscale 네트워크에 연결되어 있으면 새글이 자동으로 PC를 발견합니다.
3. **1회 기기 승인:** 휴대폰에서 PC를 승인하면 즉시 글쓰기 AI(맞춤법 교정, 문체 변환, 실시간 번역, 자유 프롬프트)를 추가 비용 없이 쓸 수 있습니다.
4. **보안 격리:** CLI 세션 토큰은 PC에만 남고 모바일로 복사되지 않으며, 통신 내용은 디스크 로그에 남지 않습니다.

> [!TIP]
> PC 없이 모바일 단독으로 사용하려는 경우, **개인정보·AI > AI 공급자** 메뉴에서 OpenAI, Anthropic 또는 호환 엔드포인트의 API 키를 직접 등록하여 사용할 수 있습니다. 등록된 키는 Android Keystore 하드웨어 암호화로 보호됩니다.

---

## 📦 다운로드 및 설치 (Quick Start)

### 1. 다운로드
[GitHub Releases](https://github.com/yunchan8804-blip/saegeul/releases)에서 동일한 버전 태그의 APK 2개를 함께 내려받습니다.
- **새글 메인 앱:** `net.chanpaca.saegeul-vX.Y.Z.apk`
- **한글 플러그인:** `net.chanpaca.saegeul.plugin.hangul-vX.Y.Z.apk`

### 2. 설치 및 Play Protect
- 다운로드한 두 APK를 순서대로 설치합니다.
- 설치 시 *"출처를 알 수 없는 앱"* 또는 *"Play Protect 경고"*가 뜰 경우, **자세히 보기 > 무시하고 설치**를 누릅니다.

### 3. 활성화 및 기본 키보드 설정
- Android **설정 > 일반 관리 > 키보드 목록 및 기본값**에서 **새글**을 켭니다.
- 입력창에서 기본 키보드를 **새글**로 선택합니다.

### 🔐 공식 릴리스 서명 지문 (SHA-256)
공식 배포 바이너리의 무결성은 다음 서명 지문으로 검증할 수 있습니다.

```text
3B:08:8B:5C:6A:69:E3:6C:62:80:2E:F5:D4:33:BD:9D:84:5B:8E:98:09:27:8E:13:13:36:A5:03:DA:90:1A:66
```

---

## 🔒 개인정보 및 보안 원칙 (Data Boundary)

새글은 데이터 경계를 코드로 엄격하게 통제합니다.

| 데이터 항목 | 처리 위치 | 외부 전송 조건 | 보관 및 보안 정책 |
| :--- | :--- | :--- | :--- |
| **일반 키 입력 & 한글 조합** | 기기 내 로컬 (libhangul) | **절대 전송 안 함 (0%)** | 입력 원문 비보관, 비밀번호창 자동 차단 |
| **클립보드 기록** | 기기 내 로컬 Room DB | **전송 안 함** | 기본값 OFF, Android 클라우드 백업 제외 |
| **온디바이스 OCR** | 기기 내 Tesseract/ONNX | **전송 안 함** | 이미지 원본 및 인식 결과 비저장 |
| **개인 단어장** | 기기 내 암호화 저장소 | **전송 안 함** | 사용자 직접 삭제 시 영구 파기 |
| **글쓰기 AI (클라우드/PC)** | 승인된 AI 공급자 / 내 PC | 사용자가 **작업 버튼을 누른 순간에만 1회** | `store=false` 요청, 토큰/키 Keystore 암호화 |
| **정밀 음성 전사** | 선택한 STT 공급자 | 마이크 버튼 활성화 시에만 | 스트림 완료 즉시 메모리 해제, 파일 비저장 |

> [!IMPORTANT]
> 설정에서 **전체 오프라인 모드**를 켜면 AI, 음성, GIF 검색, OCR 모델 다운로드 기능이 하드웨어 레벨에서 일괄 차단되며 네트워크 통신량이 0바이트로 유지됩니다.

---

## 🛠️ 소스 코드 및 빌드 (Build Guide)

### 요구 사양
- **JDK:** OpenJDK 17 이상
- **Android SDK:** API Level 34 (Compile SDK), API 26+ (Min SDK)
- **NDK:** 26.1.10909125
- **CMake:** 3.22.1 이상

### 클론 및 빌드

```bash
# 1. 서브모듈을 포함하여 저장소 클론
git clone --recurse-submodules https://github.com/yunchan8804-blip/saegeul.git
cd saegeul

# 2. 메인 앱 및 한글 플러그인 릴리스 APK 빌드
./gradlew :app:assembleRelease :plugin:hangul:assembleRelease
```

### 아키텍처별 빌드 지정 (선택)
특정 ABI(예: `arm64-v8a`)만 빌드하려는 경우 `-PbuildABI` 플래그를 사용합니다.

```bash
./gradlew :app:assembleRelease :plugin:hangul:assembleRelease -PbuildABI=arm64-v8a
```

---

## 📜 오픈소스 계보 및 독립 포크 고지

### 독립 포크 안내 (Fork Notice)
> **새글(Saegeul)은 Fcitx5를 기반으로 한 비공식 독립 포크입니다.**  
> Fcitx 프로젝트 또는 원 Fcitx5 for Android 프로젝트와 공식 제휴 관계가 없으며, 원 프로젝트의 보증·승인·후원을 받지 않습니다.  
> 독립 포크에서 추가·수정한 기능과 공식 배포물에 대한 모든 책임은 개발자(Yun Chan)에게 있습니다.

### 기반 오픈소스 프로젝트
- [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) — Android 입력기 프레임워크 기반 (LGPL-2.1-or-later)
- [Fcitx5](https://github.com/fcitx/fcitx5) — 차세대 다국어 입력기 코어 엔진 (LGPL-2.1-or-later)
- [libhangul](https://github.com/libhangul/libhangul) — 한국어 자소 조합 엔진 (LGPL-2.1-or-later)

### 라이선스 (License)
새글의 신규 작성 소스 코드는 **GNU Lesser General Public License v2.1 이상 (`LGPL-2.1-or-later`)** 및 `Copyright 2026 Yun Chan`을 따릅니다. 브랜드 벡터 자산(`docs/brand/saegeul-icon.svg`)은 `CC-BY-4.0`으로 배포됩니다.

---

## 🌐 관련 링크 & 커뮤니티

- 🏠 **공식 웹사이트:** [https://saegul.chanpaca.net](https://saegul.chanpaca.net)
- ❓ **사용자 도움말 (FAQ):** [https://saegul.chanpaca.net/faq/](https://saegul.chanpaca.net/faq/)
- 🛡️ **개인정보처리방침:** [https://saegul.chanpaca.net/privacy/](https://saegul.chanpaca.net/privacy/)
- 📜 **소스 및 라이선스:** [https://saegul.chanpaca.net/source/](https://saegul.chanpaca.net/source/)
- 🐛 **버그 신고 및 기능 제안:** [GitHub Issues](https://github.com/yunchan8804-blip/saegeul/issues)
- ✉️ **보안 및 문의 이메일:** [yunchan@chanpaca.net](mailto:yunchan@chanpaca.net)
