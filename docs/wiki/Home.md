# 새글 (Saegeul) 공식 문서 위키

<p align="center">
  <img src="../brand/saegeul-icon.svg" width="96" height="96" alt="새글 로고">
</p>

<p align="center">
  <strong>한국어의 결을 아는 자판. 내 컴퓨터와 연결하는 AI.</strong><br>
  새글(Saegeul) 프로젝트의 공식 기술 사양 및 사용자 가이드 포털입니다.
</p>

---

## 📚 위키 문서 둘러보기

### 🚀 사용자 가이드
- **[01. 시작하기 (Getting Started)](01-Getting-Started.md):** APK 다운로드, Play Protect 무시 설치, 기본 키보드 설정 및 SHA-256 서명 검증
- **[02. 17가지 한국어 자판 (Keyboard Layouts)](02-Keyboard-Layouts-and-Engine.md):** 천지인, 모아키, 나랏글, 두벌식, 세벌식(390/최종/순이 등), 안마태 상세 설명
- **[03. 지능형 한글 입력 (Smart Input)](03-Korean-Smart-Input.md):** 한/영 오타 복구(`dkssud` ➔ `안녕`), 초성 검색, 조사 받침 판별, 한자 음훈, 오프라인 사전
- **[04. AI 글쓰기 & PC Companion (AI & Companion)](04-AI-Writing-and-Companion.md):** 내 Windows PC의 Codex/Claude를 활용한 추가 비용 0원 Tailscale HTTPS 연동

### 🛠️ 고급 기능 & 기기 호환성
- **[05. 한글 버퍼 호환 모드 (Buffer Compatibility)](05-Hangul-Buffer-Compatibility.md):** 웹뷰, 게임, Flutter, 원격 데스크톱 등 특수 환경의 글자 깨짐 해결 기술 규격
- **[06. 음성, 온디바이스 OCR & GIF (Voice, OCR & Media)](06-Voice-OCR-and-Media.md):** 로컬 받아쓰기, 사진 속 글자 추출(OCR), Noto/KLIPY/GIPHY/Wikimedia GIF
- **[07. 개인정보 및 보안 (Privacy & Keystore)](07-Privacy-Security-and-Keystore.md):** 100% 로컬 데이터 처리, 0바이트 오프라인 모드, Android Keystore 하드웨어 암호화
- **[08. 기기 폼팩터 및 테마 (Form Factor & Theming)](08-Form-Factor-and-Theming.md):** 폴더블/태블릿 분할 키보드, 단청/한지 테마, 앱별 맞춤 프로필

### 💻 개발자 및 오픈소스
- **[09. 빌드 및 개발자 가이드 (Build & Developer Guide)](09-Developer-and-Build-Guide.md):** Android SDK/NDK/CMake 빌드 환경, 재귀 서브모듈, 릴리스 검증 게이트

---

## 🌟 프로젝트 정체성 및 계약

| 항목 | 내용 |
| :--- | :--- |
| **제품명** | 새글 (Saegeul) |
| **메인 패키지 ID** | `net.chanpaca.saegeul` |
| **한글 플러그인 ID** | `net.chanpaca.saegeul.plugin.hangul` |
| **공식 서비스 도메인** | [https://saegul.chanpaca.net](https://saegul.chanpaca.net) |
| **소스 저장소** | [GitHub @yunchan8804-blip/saegeul](https://github.com/yunchan8804-blip/saegeul) |
| **오픈소스 라이선스** | `LGPL-2.1-or-later` |
