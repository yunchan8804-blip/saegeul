# 온디바이스 개인화의 치명적 한계 극복 & 극한 개인화 방법론 (Research & SSOT)

- **문서 식별자**: `docs/research/extreme-ai/03-personalization-pitfalls-and-breakthroughs.md`
- **대상 모듈**: Saegeul 개인화 파이프라인 (TypingDNA, PersonalNgramModel, PersonalSentenceVault 등)
- **타깃 환경**: 안드로이드 모바일 온디바이스 (메모리 제약, Doze/Charging 백그라운드 워커)
- **최종 갱신일**: 2026-09-17

---

## 1. 현행 키보드 개인화의 근본적 병폐 분석 (Saegeul 실측 기반)

### 1.1 Saegeul 백로그 실측 장애 심층 해부

| 결함 번호 | 백로그 명칭 및 실측 현상 | 코드상 발생 지점 및 메커니즘 | 구조적 딜레마 및 한계 |
|:---|:---|:---|:---|
| **B4** | **자모 깨진 노이즈의 n-gram 오염**<br>• "허허ㅇㄴㅎㅅ", "ㅎㅎㅎ나리도", "즐거웠오" 등 고립 자모 및 오타 영구 박제 | `PersonalNgramTokenizer.tokenize`<br>`PersonalNgramModel.learn`<br>`CorrectionPatternStore.recordCorrection` | **학습 vs 교정의 이율배반**: 노이즈 제거를 위해 토크나이저 수준(`isDroppable`)에서 자모를 차단하자 오타 원문을 기록해야 하는 교정기(`CorrectionPatternStore`)와 "반복 오타 억제" 테스트 7건 파괴.<br>결국 학습단은 방치하고 화면 노출(`KoreanSuggestionSurface`)에서만 호환자모를 거르는 땜질식 임시 조치로 귀결. |
| **B19** | **통사·시맨틱 결여로 인한 기괴한 문장 추천**<br>• `내가 뭘` 입력 시 `내가 뭘 회의 참석합니다` 추천 | `KoreanSentenceContinuation.kt:81-113`<br>`AiContextualPredictor.kt` | **마르코프 체이닝의 무의미한 합성**: `collectChain()`에서 `내가 뭘` 뒤에 unigram 빈도로 나온 명사(`회의`)를 물고, `회의`가 `HADA_NOUNS`에 포함되자 `HONORIFIC_ENDINGS`(`합니다`)를 기계적으로 접합.<br>주어/의문대명사와 술어 간 문법적 호응 및 시맨틱 적합성을 판별할 수단 전무. |
| **B22** | **어미 수집·표시 0건 실패 현상**<br>• 문장 24건 누적 분석에도 추출된 종결 어미 0건 유지 | `TypingDnaProfiler.kt:152-200`<br>`KoreanSentenceEndingExtractor.kt` | **규칙 기반 정적 형태소 매칭의 붕괴**: 구두점 미제거 상태의 `endsWith` 비교, `ㅂ니다` 등 불규칙/축약 음운 변동 미처리, 정적 어미 리스트의 한계. 한국어 교착어 특성상 형태소 분리 엔진 없이 순수 문자열 슬라이싱은 매칭률 0%로 수렴. |
| **B23** | **입력 세션 경계 혼합 및 오학습**<br>• A앱 입력 문장 잔여 버퍼가 B앱으로 전이되어 학습 | `UserTypingContextCollector.kt`<br>`FcitxInputMethodService.kt`<br>`TypingDnaVault.kt:recordSentence` | **IME 수명주기 비동기성과 삭제 의미론 부재**: `onStartInput(restarting)` 경계, 커서 임의 이동, 백스페이스 연속 타건 시 버퍼 소거 기준 모호. 취소/삭제된 오타 조각이나 타 앱 미확정 텍스트가 staging buffer에 잔존해 commit됨. |

### 1.2 단순 n-gram 카운팅 및 BM25 RAG의 본질적 한계
1. **BM25의 어휘 불일치(Vocabulary Mismatch)**:
   - 키워드 중복(Overlap)에만 의존하므로, 사용자가 "일정 변경할게요"를 입력할 때 과거 "약속 시간 미룹시다"는 공통 토큰이 없어 검색 스코어가 0이 됨.
2. **n-gram의 3단어 이상 장기 의존성 상실**:
   - $P(w_t \mid w_{t-n+1}, \dots, w_{t-1})$ 마르코프 가정으로 인해 주어("제가 어제 주문한 물건이")와 서술어("도착했나요?") 사이에 수식어가 끼어들면 연결이 끊어짐.
3. **최신성(Recency) vs 빈도(Frequency) 왜곡**:
   - 지수 반감기($2^{-\Delta t / \tau}$) 모델은 과거 누적된 정적 표현을 과대평가하거나, 일시적 오타가 직전 버프로 상위 추천을 독점하는 핑퐁 현상을 방지하지 못함.

---

## 2. 온디바이스 지속 학습(Continual Learning) & LoRA 미세조정

### 2.1 모바일 하드웨어(NPU / GPU / CPU)에서의 LoRA 학습 실현 가능성

| 항목 | 메모리 점유 요인 | 0.5B 모델 (FP16/INT4) | 1.1B 모델 (INT4 Base + FP16 LoRA) |
|:---|:---|:---:|:---:|
| Base Model Weights | INT4 양자화 고정 | ~350 MB | ~700 MB |
| LoRA Parameters | $A, B$ 가중치 (FP16, $r=8$) | ~2.5 MB | ~5.2 MB |
| Optimizer States | AdamW ($m, v$ 벡터, FP32) | ~10 MB | ~21 MB |
| Gradients & Activations | $\nabla_A, \nabla_B$ 및 SeqLen 64 역전파 | ~47.5 MB | ~100.2 MB |
| **최소 학습 RAM 피크** | 순전파 + 역전파 합계 | **~410 MB** | **~825 MB** |

- **모바일 NPU의 냉혹한 현실**:
  - Qualcomm QNN, MediaTek NeuroPilot, Exynos NPU 등 상용 모바일 NPU는 **Inference-Only AOT 컴파일 전용 가속기**임.
  - Autograd 역전파 연산자(Backward Graph) 드라이버가 Android에 존재하지 않아, **NPU 기반 역전파 학습은 기술적으로 불가능함**.
- **CPU / GPU 기반 학습 현실**:
  - CPU(ARM NEON) 및 GPU(OpenCL)로 역전파를 수행해야 하므로 문장당 120~300ms 소요.
  - 실시간 타이핑 중 학습은 발열 쓰로틀링과 배터리 드레인으로 절대 불가하며, **오프라인 유휴 배치 학습만 가능**.

### 2.2 Idle + Charging 백그라운드 학습 파이프라인
- **트리거 조건 (WorkManager)**:
  1. `RequiresCharging(true)`
  2. `DeviceIdle(true)` (화면 꺼짐 + Doze 모드 진입)
  3. `BatteryNotLow` (배터리 30% 이상)
  4. `ThermalState <= THERMAL_STATUS_LIGHT`
- **프레임워크**: **ExecuTorch Training Extension** (`extension/training`) 채택. PyTorch forward/backward 동시 컴파일 모바일 C++ 런타임.

### 2.3 파괴적 망각(Catastrophic Forgetting) 방지 3대 전략
1. **EWC-LoRA (Elastic Weight Consolidation)**: 대각 Fisher 정보 행렬 $F$를 통해 사전 지식 가중치 변화에 페널티 부여.
2. **LoRA-MoE (도메인별 분리 어댑터)**: `lora_work.bin`(업무용), `lora_social.bin`(일상/SNS용), `lora_general.bin`을 분리하고 컨텍스트에 따라 라우팅.
3. **Experience Replay Buffer**: 정제된 표준 한국어 코퍼스(200문장)를 학습 시 3:7 비율로 항상 인터리빙.

---

## 3. Test-Time Training (TTT) 및 4계층 동적 메모리

### 3.1 Test-Time Training (TTT) 최신 패러다임 (Sun et al., 2024)
- 기존 RNN의 고정 벡터 은닉 상태 대신, **은닉 상태 자체를 신경망 가중치 $W_t$로 정의**.
- 순전파 도중 단일 스텝 온라인 경사하강법으로 은닉 가중치를 실시간 업데이트:
  $W_t = W_{t-1} - \eta \nabla_W \ell(W_{t-1}; x_t)$
- 역전파 그래프나 옵티마이저 없이 순전파 내에서 방금 전 문체에 즉시 적응(In-place Adaptation).

### 3.2 4-Tier Mobile Memory Architecture

| 계층 | 역할 및 내용 | 저장소 및 메모리 | 지연시간 |
|:---|:---|:---|:---|
| **Tier 1: Working Context (L1)** | 직전 2~3턴 버퍼, 커서 주변 텍스트 | RAM ~50 KB | **< 1ms** |
| **Tier 2: Short-term Persona (L2)** | 세션별 동적 분위기 벡터, 가상 토큰(Soft Prompt) | RAM ~500 KB | **< 5ms** |
| **Tier 3: Episodic Memory (L3)** | EM-LLM 스타일 날짜/주제별 요약 세션 청크 | Flash DB ~10 MB | **15ms** |
| **Tier 4: Semantic Knowledge (L4)** | PII 스크러빙된 고유 엔티티 Ego-Graph (TypingDNA Vault) | Encrypted SQLite ~20 MB | **< 2ms** |

---

## 4. TPO(Time, Place, Occasion) & 다차원 문맥 임베딩 & 보안 격리

### 4.1 다차원 문맥 조건화 벡터 (Gated LoRA)
- 입력 신호: App Type (카톡/슬랙/메일), Time Bucket (출근/업무/심야), Input Field (제목/본문), Recipient (상사/친구/가족).
- **Gated LoRA 수식**:
  $h = W_0 x + \sum_{k=1}^{K} \alpha_k(c_{\text{TPO}}) \cdot (B_k A_k x)$
  ($c_{\text{TPO}}$에 따라 업무/일상 어댑터 게이트 $\alpha_k$가 자동 스케일링됨).

### 4.2 Differential Privacy (DP) 및 TEE 하드웨어 격리
- **DP-SGD (Google Gboard 표준)**: 클리핑 $\|\mathbf{g}\|_2 \le C$ 및 가우시안 노이즈 주입으로 민감 정보 암기 차단.
- **Android Keystore (StrongBox Keymaster)**: AES-256-GCM 암호화 키를 하드웨어 TEE에 보관하여 OS 루트 탈취 시에도 금고 보호.

---

## 5. Saegeul 도입 로드맵 요약

1. **단기 (Phase 1)**: B4/B19/B22/B23 근절을 위해 마르코프 체이닝(`collectChain`) 퇴출, Kiwi 형태소 분석기 도입, 4-Tier 메모리 중 L1/L2 Context Buffer + L4 SQLite Vault 연동.
2. **중기 (Phase 2)**: TPO 컨텍스트 게이팅(Gated Routing)으로 앱/시간대별 사전 및 소프트 프롬프트 분리.
3. **장기 (Phase 3)**: WorkManager 충전 중 배치 파이프라인 기반 ExecuTorch 온디바이스 LoRA 어댑터 미세조정.
