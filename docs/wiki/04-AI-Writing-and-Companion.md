# 04. AI 글쓰기 & Windows Companion 0원 연동 (AI & Companion)

새글(Saegeul)은 모바일 키보드 최초로 **내 컴퓨터의 AI CLI 구독을 모바일과 직결하는 로컬 프록시 아키텍처**를 제공합니다. 별도의 유료 API 키를 새로 발급받지 않고도 기존 Codex 또는 Claude 구독을 그대로 활용할 수 있습니다.

---

## 1. 아키텍처 및 0원 연동 원리

```
┌───────────────────────────────┐
│          Android 새글         │
│  - 텍스트 캡처 (선택 영역/전체)    │
│  - Diff 하이라이트 검토 창      │
└───────────────┬───────────────┘
                │
                │ Tailscale Serve HTTPS (종단간 mTLS 암호화)
                ▼
┌───────────────────────────────┐
│     Windows Companion 트레이   │
│  - scripts/ai-provider-       │
│    companion.py               │
│  - 1회용 디바이스 인증 키 관리     │
└───────────────┬───────────────┘
                │
                │ 로컬 stdio 파이프 (PC 메모리 내)
                ▼
┌───────────────────────────────┐
│      Codex / Claude CLI       │
│  - PC에 기 로그인된 CLI 세션 재사용 │
│  - 추가 API 결제 0원           │
└───────────────────────────────┘
```

---

## 2. PC Companion 설치 및 설정 (Windows / macOS / Linux 지원)

### 1단계: Tailscale 네트워크 연결
- 휴대폰과 컴퓨터에 각각 [Tailscale](https://tailscale.com/)을 설치하고 동일한 계정(Tailnet)으로 로그인합니다.

### 2단계: OS별 Companion 원클릭 설치 및 실행

#### 🪟 Windows
- [GitHub Releases](https://github.com/yunchan8804-blip/saegeul/releases)에서 `saegeul-companion-windows.zip`을 다운로드하여 압축을 푼 뒤 **`install.bat`**을 더블클릭합니다.
- Windows 작업 스케줄러에 백그라운드 서비스가 등록되어 부팅 시 자동 시작됩니다.

#### 🍎 macOS
- `saegeul-companion-macos.tar.gz`를 다운로드하여 압축 해제 후 **`install.command`**를 더블클릭합니다 (또는 터미널에서 `./install.sh`).
- macOS `LaunchAgent` 데몬으로 등록되어 로그인 시 백그라운드에서 자동 실행됩니다.

#### 🐧 Linux
- `saegeul-companion-linux.tar.gz`를 다운로드하여 압축 해제 후 터미널에서 **`./install.sh`**를 실행합니다.
- systemd user 서비스(`saegeul-companion.service`)로 등록되어 자동 실행됩니다.

### 3단계: 휴대폰에서 기기 1회 승인
1. 새글 키보드의 **개인정보·AI > 내 컴퓨터 연결** 메뉴를 엽니다.
2. Tailnet 상에서 검색된 내 PC를 선택하고 **연결 승인**을 누릅니다.
3. 연결이 완료되면 키보드 툴바의 AI 버튼을 누를 때마다 내 PC의 CLI가 글쓰기 작업을 처리합니다.

---

## 3. 6대 글쓰기 AI 기능

키보드 높이 그대로 열리는 AI 패널에서 다음 작업을 원클릭으로 수행할 수 있습니다.

1. **맞춤법 및 문맥 교정:** 띄어쓰기, 오탈자, 어색한 어휘를 표준 문법에 맞게 교정하고 전/후 변경점을 초록색 Diff로 표시합니다.
2. **업무 메일체 변환:** 구어체나 거친 메모를 격식 있는 비즈니스 이메일 톤앤매너로 정중하게 다듬습니다.
3. **정중한 거절문 생성:** 상대방의 기분을 상하게 하지 않으면서 명확하게 거절하는 완곡한 문장 생성.
4. **다국어 실시간 번역:** 한국어 ➔ 영어, 일본어, 중국어 등 목표 언어로 자연스럽게 번역 후 즉시 치환.
5. **자유 지시 (Custom Prompt):** "3줄 요약해줘", "글머리 기호로 정리해줘" 등 사용자가 원하는 프롬프트를 자유롭게 작성.
6. **결과 검토 후 4가지 동작:**
   - **교체:** 에디터의 기존 글을 AI 결과물로 치환
   - **뒤에 넣기:** 기존 글 뒤에 줄바꿈 후 이어 쓰기
   - **클립보드 복사:** 입력창을 건드리지 않고 클립보드에만 저장
   - **취소:** 에디터 원본 유지

---

## 4. 모바일 단독 BYOK (Bring Your Own Key)

Windows PC가 없는 환경에서는 모바일 단독으로 API 키를 등록하여 사용할 수 있습니다.

- **설정 위치:** **개인정보·AI > AI 공급자 설정**
- **지원 공급자:** OpenAI (GPT-4o / GPT-4o-mini), Anthropic (Claude 3.5 Sonnet), OpenAI 호환 커스텀 엔드포인트
- **보안:** 등록된 API 키는 **Android Keystore 하드웨어 암호화** 영역에 보관되며 클라우드 백업에서 완전 제외됩니다.
