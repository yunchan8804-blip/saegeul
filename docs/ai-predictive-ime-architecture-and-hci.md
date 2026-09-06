# Saegeul AI Predictive IME: Architecture & HCI Engineering Specification
## 한국어 모바일 환경을 위한 생성형 AI·초저지연 예측 입력 하이브리드 아키텍처 및 인지적 상호작용 표준

- **문서 번호**: `SAEGEUL-ENG-2026-09`
- **상태**: `APPROVED / LIVING SPEC`
- **적용 대상**: `org.fcitx.fcitx5.android.input.ai.*`, `HorizontalCandidateComponent`, `KawaiiBarComponent`
- **최종 갱신일**: 2026-09-05

---

## 1. 서론 및 연구 배경

모바일 가상 키보드(Soft Keyboard IME)는 화면 공간의 제약, 작은 터치 타깃, 촉각 피드백의 부재로 인해 기계식 키보드에 비해 높은 오타율과 느린 입력 속도라는 고유한 한계를 안고 있다.
최근 대형 언어 모델(LLM)의 급격한 발전으로 인해 키보드에 생성형 AI를 결합하여 사용자 입력을 예측하고 문장을 대신 완성하려는 시도가 활발해지고 있다.

그러나 **"키보드 입력"이라는 특수한 HCI 도메인에 LLM을 단순 연동하는 방식은 반드시 실패한다.**
- **지연 시간(Latency)**: 클라우드 LLM 추론은 최소 수백 밀리초에서 수 초가 소요되지만, 사용자 타이핑 인터벌은 150~250ms 수준이다.
- **인지 부하(Cognitive Load)**: 시각적 탐색 비용(Cost of Looking)을 고려하지 않고 제안을 남발하거나 레이아웃이 요동치면, 사용자는 키를 덜 치더라도 총 입력 시간(WPM)이 오히려 감소하고 심각한 피로를 느낀다.
- **한국어 교착어 특성**: 음절 단위 단순 룩업으로는 11,172개 음절 조합 및 어간-어미 활용, 쿼티 터치 인접 오타를 포착할 수 없다.

본 문서는 세계적 학술 연구(Google Research, ACM CHI, KDD) 및 국내 한국어 NLP/HCI 권위자(국민대 강승식 교수, KAIST 이기혁 교수)의 연구를 기반으로, 새글(Saegeul) 키보드가 채택한 **3계층 지연시간 예산(Tiered Latency Budget)**, **한국어 자모 분해 오타 교정**, **2열 컴팩트 UI 표준**의 이론적·공학적 근거를 명시한다.

---

## 2. 학술적·산업계 권위 연구 및 이론적 프레임워크

### 2.1 보는 비용(Cost of Looking)과 개입도(Assertiveness)의 역설
> **Philip Quinn & Shumin Zhai (Google Research, ACM CHI 2016)**
> *"A Cost-Benefit Study of Text Entry Suggestion Interaction"*

- **핵심 이론**:
  - 예측 텍스트의 이점은 키 입력 횟수를 줄이는 **키스트로크 절감률(Keystroke Savings Rate, KSR)**로 측정된다:
    $$KSR = \frac{\text{Keys Saved}}{\text{Total Keys}} \times 100\%$$
  - 그러나 제안은 **인지적으로 공짜(Cognitively Free)가 아니다.** 사용자는 다음과 같은 인지적 단계 비용을 지불해야 한다:
    1. **시선 이동 (Saccadic Eye Movement)**: 하단 키패드에서 상단 제안 바로 시선 이동 (~80ms).
    2. **지각 및 평가 (Perception & Evaluation)**: 제시된 텍스트가 자신의 의도와 맞는지 읽고 판단 (~150-250ms).
    3. **선택 결정 (Decision & Motor Action)**: 제안을 탭할지, 타이핑을 계속할지 결정 (Hick-Hyman Law, ~100ms).
- **연구 결과 및 경고**:
  - 시스템의 **개입도(Assertiveness)**가 너무 높거나 후보가 수시로 깜빡이면(flicker), "보는 비용(Cost of Looking)"이 키 절감 혜택을 압도하여 **분당 단어 입력 수(WPM)가 하락**하고 오류율이 증가한다.
- **새글 키보드 설계 원칙**:
  - **Visual Anchor(시각적 닻) 고정**: 뷰의 깜빡임(Flicker)을 원천 차단(Anti-Flicker Latch).
  - **신뢰도 임계치**: 신뢰도(Confidence)가 명확한 상위 후보만 노출하며, 미완성 상태의 저신뢰도 제안은 노출을 유예함.

---

### 2.2 제안 단위의 분리와 인지 스위트스팟 (Word vs. Sentence Granularity)
> **Daniel Buschek et al. (ACM CHI 2021) & Kenneth C. Arnold et al. (ACM CHI 2016, 2018)**
> *"Impact of Suggestion Length on Text Entry"*, *"Suggesting Phrases in Mobile Typing"*

- **핵심 이론**:
  - **단어 단위 제안(Word-level)**: 작업 기억(Working Memory)을 교란하지 않고 즉각적이고 반사적인 반응(Reflexive Choice)을 유도함. 최적 개수는 **3~4개**(Miller의 마법의 숫자 $7 \pm 2$ 하한선 및 Hick-Hyman Law 최적 구간).
  - **문장 단위 제안(Sentence-level)**: 사용자가 문맥 전체를 읽어야 하므로 작업 기억 소모가 큼. 화면에 문장 후보가 3개 이상 길게 나열되면 사용자는 가로 스크롤과 긴 텍스트 읽기를 포기하는 **인지적 포기(Cognitive Abandonment)** 상태에 빠짐. 문장 후보는 **1~2개가 최적 한계**.
- **새글 키보드 2열 컴팩트 UI 설계**:
  - **상단 행 (Top Row, 28dp)**: 단어/어절 후보 (최대 3~4개, 작은 폰트, 오타 교정 단어 `[✏️ 싶은지]` 포함). 타이핑 흐름을 방해하지 않는 빠른 보조.
  - **하단 행 (Bottom Row, 30dp)**: 완성 문장 후보 (최대 1~2개, 칩 스타일, `[✏️ 뭘 하고 싶은지 내가 어떻게 알아]`). 확고한 의도 완성.
  - 1열 모드와 2열 모드 전환 시 높이 오버랩을 방지하기 위한 View Holder Height 강제 지정.

---

### 2.3 초저지연 서빙 예산과 다계층 하이브리드 파이프라인
> **Mia Xu Chen et al. (Google Research, ACM KDD 2019)**
> *"Gmail Smart Compose: Real-Time Assisted Writing"*

- **핵심 요구사항**:
  - 90% 이상의 요청이 **60ms 이내(Sub-60ms)**에 서빙되어야 사용자가 타이핑 랙을 체감하지 않음.
  - 디바이스 자원과 네트워크 대역폭 한계로 인해, 모든 키 입력마다 LLM을 호출하는 것은 불가능함.
- **새글 키보드 3계층(3-Tier) 레이턴시 예산 아키텍처**:

```
+-------------------------------------------------------------------------+
|                       User Input (Stroke/Commit)                         |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Tier 0: Instant On-Device Rule & Lexicon Engine (Latency: < 5ms)        |
| - 자모 단위 분해 (Jamo Decomposition)                                     |
| - O(1) 빈출 오타 사전 & 어미 정규화 (Colloquial Normalization)            |
| - 쿼티 인접 키 가중 편집거리 (Adjacent Jaso Levenshtein)                  |
| - 절(Clause) 단위 원자적 치환 길이 계산                                   |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Tier 1: On-Device Semantic Context & Markov Transitions (Latency: < 20ms)|
| - 최근 문맥(History) 기반 개인화 N-gram 전이 확률                        |
| - 온디바이스 시맨틱 문맥 추천 (KoreanSemanticSentencePredictor)            |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Tier 2: Speculative Background LLM & Smart Cache (Perceived: 0ms)       |
| - 400ms Idle Pause 감지 시 비동기 코루틴 발송 (Speculative Prefetch)       |
| - Context Key 정규화 (다중 공백·문장부호 정규화)                          |
| - LRU Memory Cache (30건 용량)에서 타이핑 시 즉시 O(1) 히트 서빙          |
+-------------------------------------------------------------------------+
```

---

### 2.4 한국어 교착어 특성과 자모 분해 오타 교정
> **강승식 교수 (국민대 자연어처리 연구실, 한국정보과학회) & 옹윤지 (2018)**
> *"터치스크린 환경에서 쿼티 자판 오타 교정을 위한 n-gram 언어 모델"*
> *"음절 정보와 복수어 단위 한국어 형태소 분석"*

- **한국어의 고유한 언어학적 특성**:
  - 한국어는 어간(Stem)에 조사나 어미가 결합하는 **교착어(Agglutinative Language)**이며, 현대 한글은 11,172개의 음절 블록 결합 구조를 취한다.
  - 따라서 영어나 중국어 같은 고립어/굴절어와 달리, 음절 단위 단순 철자 검사는 다음과 같은 이유로 동작하지 않는다:
    - `"시프지"` $\rightarrow$ 초성 `ㅅ` + 중성 `ㅣ` + 종성 없음 / 초성 `ㅍ` + 중성 `ㅡ` + 종성 없음 / 초성 `ㅈ` + 중성 `ㅣ`
    - `"싶은지"` $\rightarrow$ 초성 `ㅅ` + 중성 `ㅣ` + 종성 `ㅍ` / 초성 `ㅇ` + 중성 `ㅡ` + 종성 없음 / 초성 `ㅈ` + 중성 `ㅣ`
    - 음절 단위로는 완전히 다른 글자(3음절 중 2음절 상이)로 보여 편집거리가 2.0 이상으로 벌어지지만, **자모 단위로 분해하면 종성 `ㅍ`이 다음 음절 초성으로 연음화된 1개 자모 오타**에 불과함.
- **새글 키보드의 구현**:
  1. **자모 단위 편집거리(Jaso Edit Distance)**: 텍스트를 초성·중성·종성으로 분해하고, 2벌식 쿼티 자판에서 물리적으로 인접한 키(`ㅂ-ㅈ`, `ㅈ-ㄷ`, `ㅗ-ㅓ` 등)에 대해 페널티를 절반(0.5)으로 경감.
  2. **절(Clause) 단위 원자적 치환(Atomic Replacement)**:
     - 커서가 문장 끝에 있더라도 문장 중간의 오타(`시프지 내가 어떻게 알아` $\rightarrow$ `싶은지 내가 어떻게 알아`)를 감지하고, 해당 절 전체를 원자적으로 교체함으로써 한글 자모 조합이 깨지는 문제를 완벽히 방어.

---

### 2.5 모바일 터치스크린 입력 인터랙션 최적화
> **이기혁 교수 (KAIST 전산학부 HCI 연구실, ACM CHI / UIST)**
> *"SplitBoard: A Gesture-Based Text Entry Technique for Smartwatches"*, *"Touch Interaction Optimization"*

- 터치스크린 가상 키보드에서는 엄지손가락의 가림(Occlusion)과 타깃 접촉면의 가변성으로 인해 필연적으로 인접 키 터치 오류가 발생함.
- 텍스트 라벨 대신 명확한 **시각적 아이콘(Micro Badge)**을 제공할 때 사용자의 시각 탐색 시간이 단축됨.
- 새글 키보드는 긴 글자 뱃지(`[수정]`, `[업무/보고]`)를 제거하고 직관적인 단일 아이콘(`✏️` 교정, `✨` AI추천, `⭐` 즐겨찾기)으로 정제하여 시각 인지 소모를 최소화함.

---

## 3. 세부 엔지니어링 구현 사양

### 3.1 투기적 프리페처 (AiSentenceCompletionPrefetcher) 규격
1. **문맥 캐시 키 정규화 (Context Normalization)**:
   - 사용자가 문장 끝에 공백을 여러 번 누르거나 마침표를 찍더라도 동일한 문맥으로 인식해야 캐시 히트율이 보장된다.
   ```kotlin
   fun normalizeContextKey(context: String): String {
       return context.trim()
           .replace(Regex("\\s+"), " ")
           .trimEnd('.', '?', '!', ',', '~', ' ')
           .takeLast(120)
   }
   ```
2. **디바운스 인터벌**:
   - `400ms` (사용자 타이핑 일시 정지 감지).
3. **스마트 트리거링 조건**:
   - 문맥 길이 5자 이상.
   - 단어 완성(Trailing space) 또는 문장 종결 부호(`.`, `?`, `!`, `\n`) 감지 시 우선 스케줄링.

### 3.2 인지 부하 제약 (Suggestion Thresholds)
- **단어 행 (Top Row)**: 최대 4개 (`limit = 4`).
- **문장 행 (Bottom Row)**: 최대 2개 (`limit = 2`).
- 신뢰도 0.80 미만의 불확실한 문장 후보는 하단 행에 노출하지 않음.

### 3.3 안티 플리커 래치 (Anti-Flicker Latch) 규격
- Fcitx 한글 조합 엔진이 음절 완성 시 `PreeditEmpty = true` 이벤트를 즉각 방출하더라도, 직전 컨텍스트 추천 후보가 존재할 경우 KawaiiBar 상태머신이 `Candidate -> Idle`로 떨어지는 것을 1프레임 동안 지연(Hold)하여 화면 깜빡임을 차단.

---

## 4. 결론 및 향후 로드맵

새글 키보드의 AI 예측 입력 시스템은 단순한 프롬프트 엔지니어링이 아니라, **HCI 인지 모델, 초저지연 시스템 엔지니어링, 한국어 언어학적 특수성을 융합한 체계적 솔루션**이다.

향후 본 문서를 바탕으로:
1. 온디바이스 경량 SLM(Small Language Model, 예: Gemma-2-2B Quantized) NPU 탑재 연구.
2. 실사용자 KSR(Key Stroke Reduction) 및 WPM(Words Per Minute) A/B 테스트 지표 측정 파이프라인을 구축한다.
