# 새글 온디바이스 AI 극한 최적화 & 극한 정확성 마스터 계획서 (Master SSOT Architecture)

- **문서 식별자**: `docs/extreme-ai-master-plan.md`
- **프로젝트**: 새글(Saegeul) Android 한국어 입력기 (`net.chanpaca.saegeul`)
- **버전**: v1.0.0-SSOT
- **기준 일자**: 2026-09-17
- **관련 아카이브**: `docs/research/extreme-ai/`

---

## 1. 개요 및 설계 비전

본 문서는 새글(Saegeul) 모바일 키보드에서 온디바이스 언어 모델(Gemma 및 경량 SLM)을 구동함에 있어,
1. **극한의 최적화 (Extreme Optimization)**: 0초 기동(Zero Cold-Start), 첫 토큰 반응시간(TTFT) < 50ms, 120Hz 키보드 터치 무결성, 상주 메모리 650MB(플래그십)/35MB(보급형) 엄수, 무발열(Thermal Guard).
2. **극한의 정확성 (Extreme Accuracy)**: 한국어 격조사/어미 불일치(은/는, 이/가, 을/를) 100% 수학적 원천 제거, ACC-01~04 호응 오류 근절, 사용자 개인 지식(Ego-Graph) 100% 팩트 기반 추천.
을 달성하기 위한 엔지니어링 정본 계획서다.

---

## 2. 통합 아키텍처 다이어그램 (Asymmetric Hybrid Pipeline)

새글의 극단적 성능은 **"온라인 타이핑 순간(Online Real-time)"**과 **"오프라인 유휴/충전 순간(Offline Idle/Charging)"**의 책임을 물리적으로 분리하는 **비대칭 하이브리드 아키텍처**로부터 도출된다.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [온라인 계층: 키보드 타이핑 시점] ── 절대 지연시간 < 5ms (LLM 역전파/무거운 연산 절대 금지)          │
│                                                                                                  │
│  [사용자 터치 이벤트] ──► [fcitx5 한글 조합 엔진]                                                │
│                                  │                                                               │
│                   ┌──────────────┴──────────────────────────┐                                    │
│                   ▼                                         ▼                                    │
│       [L1 C++ Ego-Graph 캐시]                     [32KB 음운론적 비트마스크]                     │
│       - 인메모리 인접 리스트 (<2MB)               - U+AC00 종성 O(1) 검사                        │
│       - 0.05ms 내 1-hop 지식 추출                 - 은/는, 이/가 오류 100% 차단 (1.5µs)          │
│                   │                                         │                                    │
│                   ▼                                         ▼                                    │
│       [Zero-Latency 툴바 칩]                      [XGrammar C++ 디코딩 제약]                     │
│       - 0ms 다이렉트 렌더링                       - EBNF / JSON 스키마 강제                      │
│                                                             │                                    │
│                                                             ▼                                    │
│                                                   [:ai_daemon 프로세스] (Gemma 3 1B INT4)        │
│                                                   - Pre-computed KV-Cache (TTFT < 35ms)          │
│                                                   - Prompt Lookup Decoding (48~60 tok/s)         │
│                                                   - AIDL One-way 콜백 토큰 스트리밍              │
└───────────────────────────────────┬──────────────────────────────────────────────────────────────┘
                                    │ (타이핑 완료 문장 PII 스크러빙 후 적재)
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [오프라인 계층: 충전 중 + 화면 꺼짐 + Doze 모드] ── 백그라운드 배치 프로세싱 (WorkManager)        │
│                                                                                                  │
│  [조건 감시: Charging(true) && DeviceIdle(true) && Battery > 30% && Temp <= 36.5°C]              │
│                                  │                                                               │
│                   ┌──────────────┴──────────────────────────┐                                    │
│                   ▼                                         ▼                                    │
│       [지식 그래프 추출 파이프라인]               [ExecuTorch LoRA 미세조정]                     │
│       - Kiwi 형태소 전처리 (<10MB)                - Base INT4 고정, LoRA FP16 매핑              │
│       - Gemma 1B GBNF JSON 배치 추출              - EWC-LoRA + Replay Buffer (망각 방지)        │
│       - SQLite + sqlite-vec 영속 적재             - Duty-Cycle (1문장 학습 후 2초 Sleep)         │
│       - Epoch 버전 증가 (graph_version++)         - 도메인별 분리 어댑터 (Work / Social)         │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 5대 핵심 서브시스템 상세 설계

### 3.1 시스템 1: Resident Daemon & Predictive Pre-warming
- **프로세스 격리**: `android:process=":ai_daemon"`. NPU/GPU 크래시나 OOM 시에도 IME 메인 120Hz 무결성 보장.
- **IPC 프로토콜**: Android Binder AIDL one-way 인터페이스. 왕복 지연 30~80µs (디코딩 간격 대비 0.2% 미만).
- **0초 기동 (`mmap`)**: GGUF / Flatbuffer 가중치를 `mmap(MAP_SHARED)`로 가상 메모리 매핑 (1~3ms 완료).
- **선제적 워밍 (Predictive Pre-warming)**: `onStartInputView` 호출 시 키보드 인플레이션 애니메이션(150~250ms) 동안 백그라운드에서 데몬 바인딩 및 정적 프롬프트 KV-Cache 적재.
- **자발적 동면 (Voluntary Hibernation)**: 무입력 60초 경과 시 `madvise(MADV_DONTNEED)` 호출로 물리 메모리(RSS)를 50MB 미만으로 축소하여 LMK 표적 회피.
- **TTFT < 50ms (Prompt Caching)**: 시스템 프롬프트 및 사용자 페르소나(400토큰)를 사전 계산된 KV 캐시 슬롯에 영구 고정. 동적 입력(15토큰)만 연산하여 TTFT **18~35ms** 달성.

### 3.2 시스템 2: Deterministic Phonological & Grammar-Constrained Decoding
- **유니코드 종성 분해 O(1) 비트마스크**:
  - 수식: $T = (C - 0\text{xAC00}) \pmod{28}$.
  - $T=0$(받침 없음) $\implies$ `[는, 가, 를, 와, 로]` 허용.
  - $T \neq 0$(받침 있음) $\implies$ `[은, 이, 을, 과, 으로]` 허용 (ㄹ 받침 특수 처리).
  - 32KB BPE 토큰 비트셋을 통해 C++ NDK 레벨에서 **1.5µs 내에 로짓 마스킹**. 격조사 불일치 100% 원천 제거.
- **XGrammar C++ 엔진 통합**:
  - MLSys 2025 아키텍처 채택. 256k 거대 어휘집에 대해 토큰당 15~30µs 오버헤드로 EBNF/JSON 제약 강제.
- **3단계 초경량 온디바이스 Verifier (총 지연 < 7ms)**:
  - Stage 1 (0.05ms): 구문 규칙 필터 (ACC-01 이유절 의문문 금지, ACC-02 목적어 자동사 금지).
  - Stage 2 (0.3ms): KenLM Modified Kneser-Ney 5-gram (15MB mmap Trie, 비정상 연어 기각).
  - Stage 3 (4.8ms): KoELECTRA-Small INT8 PRM (ONNX/QNN NPU, 담화 자연스러움 채점).

### 3.3 시스템 3: 4-Tier Memory & TPO Gated Personalization
- **4계층 메모리 구조**:
  - Tier 1 (L1, RAM <50KB, <1ms): 직전 2~3턴 타이핑 버퍼 및 커서 주변 텍스트.
  - Tier 2 (L2, RAM <500KB, <5ms): 활성 앱/수신자 기반 세션 페르소나 벡터 (Soft Prompt).
  - Tier 3 (L3, Flash ~10MB, 15ms): EM-LLM 스타일 날짜/주제별 요약 세션 청크.
  - Tier 4 (L4, Encrypted SQLite ~20MB): PII 스크러빙된 지식 그래프 Ego-Graph (TypingDNA Vault).
- **TPO 문맥 게이팅 (Gated LoRA)**:
  - 수식: $h = W_0 x + \sum_{k=1}^K \alpha_k(c_{\text{TPO}}) \cdot (B_k A_k x)$
  - 앱(카톡 vs 슬랙 vs 메일), 시간대, 수신자 관계에 따라 업무용/일상용 어댑터 게이트 $\alpha_k$ 동적 블렌딩.
- **프라이버시 & 보안**:
  - DP-SGD (Google Gboard 표준) 기반 노이즈 주입으로 특정 고유정보 암기 원천 차단.
  - Android KeyStore (StrongBox Keymaster) 기반 AES-256-GCM 하드웨어 TEE 암호화.

### 3.4 시스템 4: On-Device Ego-Graph & HippoRAG Lite
- **엔진**: SQLite + `sqlite-vec` (표준 C/NDK, RAM < 8MB, 트랜잭션 ACID 보장).
- **L1 핫 캐시**: C++ NDK 인접 리스트 배열 (<2MB)로 상주하여 0.05ms 내 서브그래프 획득.
- **HippoRAG 축약형 PPR**: Random Walk with Restart(RWR) 알고리즘을 C++ NEON 벡터화로 0.3ms 내 연산.
- **Zero-Latency Direct Suggestion Strip**: Gemma를 호출하지 않고도 Ego-Graph의 1-hop 타깃 엔티티(`강남역`, `오후 7시`)를 키보드 상단 툴바 칩으로 0ms 다이렉트 노출.

### 3.5 시스템 5: Offline Continual Adaptation & Thermal Guardian
- **WorkManager 트리거 조건**: 충전 중(`RequiresCharging`) + 화면 꺼짐(`DeviceIdle`) + 배터리 30% 이상 + 저발열.
- **배터리 보호 절대 상한제**: `BatteryManager.EXTRA_TEMPERATURE > 36.5°C` 시 학습 즉시 강제 중단. Duty-cycle (1문장 학습 후 2초 Sleep).
- **학습 프레임워크**: ExecuTorch Training Extension. Base INT4 고정, LoRA 어댑터만 역전파. EWC + 200문장 표준 코퍼스 인터리빙으로 파괴적 망각 방지.

---

## 4. 기기 체급별 적응형 배포 전략 (Tiered Hardware Strategy)

| 기기 등급 | 대표 기종 및 하드웨어 사양 | AI 구동 모드 | 주력 모델 / 백엔드 | 상주 RAM |
|---|---|---|---|---|
| **Tier A (플래그십)** | Galaxy S24/S25 Ultra, Fold6/7 (RAM 12GB+, Snapdragon 8 Gen 3/Elite) | Full Ultra Mode | **Gemma 3 1B (W4A16 QNN NPU)** + XGrammar + Ego-Graph | ~650 MB |
| **Tier B (표준형)** | Galaxy S23/S24 기본형, A55 (RAM 8GB, Exynos 2400 / SD 8 Gen 2) | Balanced AI Mode | **Gemma 3 1B (W4A16 KleidiAI CPU/Vulkan)** + XGrammar + Ego-Graph | ~650 MB |
| **Tier C (보급형)** | Galaxy A35, A24, A15 (RAM 4GB~6GB) | Ultra-Lite Mode | **Qwen2.5-0.5B (W4A16 ~320MB)** 또는 **순수 규칙 모드 (Ego-Graph + Kiwi + KenLM)** | **< 35 MB** |

---

## 5. 결합 소스코드 모듈 경계

- `app/src/main/java/org/fcitx/fcitx5/android/input/ai/daemon/`: AIDL 정의, `:ai_daemon` 서비스, Pre-warming 바인더.
- `app/src/main/cpp/phonology/`: 유니코드 한글 음절 분해기, 32KB 비트마스크, C++ Dynamic Logit Masker.
- `app/src/main/cpp/xgrammar/`: XGrammar NDK aarch64 바인딩.
- `app/src/main/java/org/fcitx/fcitx5/android/input/ai/graph/`: SQLite Ego-Graph 저장소, HippoRAG C++ JNI 브릿지, Direct Suggestion Strip.
- `app/src/main/java/org/fcitx/fcitx5/android/input/ai/memory/`: 4-Tier Memory Manager, TPO Feature Encoder.
- `app/src/main/java/org/fcitx/fcitx5/android/input/ai/worker/`: WorkManager 기반 충전 중 배치 지식 추출 및 ExecuTorch LoRA Worker.
