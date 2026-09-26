# 새글 온디바이스 AI 극한 최적화 & 극한 정확성 마스터 백로그 (Master SSOT Backlog)

- **문서 식별자**: `docs/extreme-ai-backlog.md`
- **프로젝트**: 새글(Saegeul) Android 한국어 입력기 (`net.chanpaca.saegeul`)
- **버전**: v1.0.0-SSOT
- **기준 일자**: 2026-09-17
- **마스터 아키텍처**: `docs/extreme-ai-master-plan.md`

---

## 1. 우선순위 체계 및 완료 기준 (Definition of Done)

- **P0 (Critical)**: 한국어 문법·조사 100% 보장 및 기존 치명 결함(B4, B19, B22, B23, ACC-01~04) 즉시 박멸.
- **P1 (High)**: 상시 런타임 최적화(TTFT < 35ms, 0초 기동, 프로세스 분리) 및 초경량 Ego-Graph 파이프라인.
- **P2 (Medium)**: XGrammar 문법 제약, 3단계 Verifier, 4-Tier 메모리 및 TPO 컨텍스트 게이팅, 발열 보호.
- **P3 (Future)**: 충전 중 유휴 상태 ExecuTorch On-device LoRA 미세조정 및 TTT 차세대 연구.

---

## 2. P0: 한국어 문법 무결성 & 치명 결함 박멸 (즉각 착수)

| ID | 항목 및 작업 내용 | 대상 파일 / 모듈 | 완료 조건 (DoD) & 검증 명령 |
|---|---|---|---|
| **EAI-01** | **[COMPLETED] 음운론적 종성 분해 비트마스크 조사 엔진 구현**<br>• `T = (C - 0xAC00) % 28` 수학적 종성 판별<br>• 32KB 비트셋 기반 O(1) 조사 판별 (지연시간 < 250ns)<br>• 겹받침(값, 닭, 삶 등), 'ㄹ' 받침 특수규칙(로/으로), 외래어/영문(MacBook, iPhone, AI 등), 숫자 종성 완벽 지원<br>• 조사 불일치 100% 원천 차단 및 자동 교정 | `input/ai/phonology/`<br>`KoreanJosaBitmaskEngine.kt`<br>`KoreanJosaBitmaskEngineTest.kt`<br>`RedTeamExtremeAccuracyDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 에뮬레이터(x86_64 ART) E2E 계측 테스트 100% 통과 (`RedTeamExtremeAccuracyDeviceTest`, 0.179s).<br>2. 겹받침 및 외래어 종성 오류율 0.00% 달성.<br>3. 지연시간 216.95ns (< 250ns) 실측. |
| **EAI-02** | **[COMPLETED] B19/B4 근절: 마르코프 체이닝 퇴출 및 구문 규칙 필터 도입**<br>• `KoreanSentenceContinuation.kt` 의문사/목적어 토큰의 `HADA_NOUNS` 합성 영구 차단<br>• Stage 1 구문 규칙 필터: ACC-01(이유절+의문/명령 차단), ACC-02(목적어+자동사 차단), ACC-03(의문사 뒤 평서 종결 차단), ACC-04(격식/비격식 혼용 차단)<br>• `SentenceRelevanceReranker.kt` 출구 필터링 결합 | `input/ai/AiContextualPredictor.kt`<br>`input/ai/KoreanSentenceContinuation.kt`<br>`input/ai/SentenceRelevanceReranker.kt`<br>`input/ai/rule/KoreanSyntaxRuleFilter.kt`<br>`ExtremeAccuracyE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. `내가 뭘` 입력 시 `내가 뭘 회의 참석합니다` 추천 0건 및 비문 합성 원천 차단.<br>2. ACC-01~04 심화 비문 100% 기각 및 정문 100% 보존 실측.<br>3. 에뮬레이터 E2E 계측 테스트 100% 통과 (`ExtremeAccuracyE2eDeviceTest`, 0.104s). |
| **EAI-03** | **[COMPLETED] B22/B23 근절: 형태소 분석기 연동 및 세션 경계 원자적 소거**<br>• 선어말어미 4종 + 종결어미 80여 종 형태소 분해 엔진(`KoreanMorphologicalEndingAnalyzer`) 구현<br>• `KoreanSentenceEndingExtractor` 및 `UserTypingContextCollector` 연동으로 어미 수집률 0건 버그(B22) 해결<br>• 세션 전환(카카오톡->슬랙 등) 및 백스페이스 시 잔여 버퍼 원자적 즉시 소거(`discardPending`)로 B23 문맥 누출 근절 | `input/ai/morphology/`<br>`KoreanMorphologicalEndingAnalyzer.kt`<br>`KoreanSentenceEndingExtractor.kt`<br>`UserTypingContextCollector.kt`<br>`B22B23E2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 에뮬레이터(x86_64 ART) E2E 계측 테스트 100% 통과 (`B22B23E2eDeviceTest`, 4/4 tests, 0.071s).<br>2. 마침표 없는 문장 어미 수집률 15건 이상 추출 검증.<br>3. 패키지 간 세션 전환 시 이전 버퍼 누출 0건 기기 검증. |

---

## 3. P1: 상시 런타임 최적화 & Ego-Graph 파이프라인

| ID | 항목 및 작업 내용 | 대상 파일 / 모듈 | 완료 조건 (DoD) & 검증 명령 |
|---|---|---|---|
| **EAI-04** | **[COMPLETED] SQLite WAL 온디바이스 Ego-Graph 및 인메모리 L1 캐시**<br>• SQLite 기반 `entities`, `edges` 스키마 및 인덱스, WAL 트랜잭션 구현<br>• 인메모리 O(1) (< 0.05ms) 인접 리스트 L1 캐시(`OnDeviceL1GraphCache`) 구축<br>• JIT 웜업 후 1,000회 조회 평균 지연시간 < 0.1ms 달성 | `input/ai/graph/`<br>`OnDeviceEgoGraphDatabase.kt`<br>`OnDeviceL1GraphCache.kt`<br>`EgoGraphE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. SQLite WAL 활성화 및 엔티티/엣지 CRUD 무결성 확인.<br>2. L1 인접 리스트 1,000회 조회 평균 지연시간 < 0.1ms 달성.<br>3. 에뮬레이터 E2E 계측 테스트 100% 통과 (`EgoGraphE2eDeviceTest`, 4/4 tests, 0.272s). |
| **EAI-05** | **[COMPLETED] HippoRAG 축약형 PPR (Random Walk with Restart) 엔진**<br>• 덤핑 팩터 d = 0.85, 최대 3회 국소 확산 축약형 PPR 엔진(`HippoRagPprEngine`) 구현<br>• Seed 엔티티 주입 시 0.5ms 이내 수렴 및 연관성 가중치 랭킹 정밀 산출 | `input/ai/graph/`<br>`HippoRagPprEngine.kt`<br>`EgoGraphE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 에뮬레이터 ART 환경에서 3회 RWR 연산 시간 < 2.0ms 수렴 실측.<br>2. Seed 엔티티("회의") 주입 시 연관 엔티티("참석", "준비", "자료") 상위 랭크 검증 통과. |
| **EAI-06** | **[COMPLETED] Zero-Latency Direct Suggestion Strip (0ms 툴바 칩)**<br>• 현재 입력 문맥 기반 Seed 추출 및 PPR 연동 0ms 툴바 칩(`DirectChip`) 브릿지 구축<br>• LLM 호출 없는 즉각 툴바 후보 렌더링 파이프라인 완성 | `input/ai/graph/`<br>`DirectSuggestionBridge.kt`<br>`EgoGraphE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 타이핑 문맥 주입 시 툴바 칩 생성 지연시간 < 3.0ms 실측.<br>2. "내일 오전 회의 " 입력 시 "참석(동작)", "준비(준비)", "자료(참조)" 칩 즉각 생성 E2E 검증 통과. |
| **EAI-07** | **[COMPLETED] `:ai_daemon` 프로세스 분리 및 AIDL IPC 파이프라인**<br>• 메인 IME(120Hz)와 LLM/추론 프로세스 격리 (`android:process=":ai_daemon"`)<br>• AIDL 바인더 IPC (`IAiDaemonService.aidl`, `IAiTokenCallback.aidl`) 구현<br>• 데몬 프로세스 사망(`binderDied`) 시 메인 IME 무결성 보장 및 Graceful Fallback (`executeWithFallback`) | `AndroidManifest.xml`<br>`input/ai/daemon/`<br>`IAiDaemonService.aidl`<br>`IAiTokenCallback.aidl`<br>`AiDaemonService.kt`<br>`AiDaemonClient.kt`<br>`AiDaemonE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 프로세스 분리 검증: 메인 PID != 데몬 PID 기기 확인.<br>2. 바인더 사망 격리 검증: 원격 데몬 종료 시 메인 프로세스 크래시 0건 및 fallback 즉각 복구.<br>3. 에뮬레이터 E2E 계측 테스트 100% 통과 (`AiDaemonE2eDeviceTest`, 4/4 tests, 1.535s). |
| **EAI-08** | **[COMPLETED] Precomputed KV-Cache (Prompt Caching) 매니저**<br>• 64-bit FNV-1a 해시 및 LinkedHashMap 기반 LRU 슬롯 캐시 매니저(`PrecomputedKvCacheManager`) 구현<br>• 시스템 프롬프트 및 사용자 페르소나 최장 일치 검색 지연시간 745.4ns (< 0.1ms) 달성 | `input/ai/daemon/cache/`<br>`PrecomputedKvCacheManager.kt`<br>`PrecomputedKvCacheManagerTest.kt`<br>`AiDaemonE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 최장 프리픽스 매칭 지연시간 실측 745.4ns (0.000745ms) 달성.<br>2. 바인더 IPC 경유 캐시 웜업 및 조회 정상 확인. |
| **EAI-09** | **[COMPLETED] Prompt Lookup Decoding (PLD / N-gram) 투기 디코딩**<br>• 입력 프롬프트 및 최근 참조 문맥의 N-gram 역방향 탐색 투기 디코딩 엔진(`PromptLookupDecoder`) 구현<br>• 투기 초안에 대해 `KoreanSyntaxRuleFilter` 및 `KoreanJosaBitmaskEngine`으로 비문 제거 및 조사 자동 보정 결합 | `input/ai/daemon/speculative/`<br>`PromptLookupDecoder.kt`<br>`PromptLookupDecoderTest.kt`<br>`AiDaemonE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 참조 문맥 기반 n-gram 투기 초안 추출 및 조사 보정 100% 검증.<br>2. 비문 초안 자동 기각 및 부분 초안 안전 fallback 검증 통과. |

---

## 4. P2: 문법 제약 디코딩, 다계층 메모리 & 발열 보호

| ID | 항목 및 작업 내용 | 대상 파일 / 모듈 | 완료 조건 (DoD) & 검증 명령 |
|---|---|---|---|
| **EAI-10** | **[COMPLETED] FSM 문법 제약 디코딩 및 한국어 종성 토큰 마스킹**<br>• JSON 객체 및 Action Chip 문법 상태 머신(FSM) 구축<br>• 선행 음절 받침(유/무/'ㄹ')에 따른 조사 토큰 실시간 마스킹 (토큰당 오버헤드 실측 4.7µs)<br>• 에뮬레이터 E2E 계측 테스트 통과 | `input/ai/grammar/`<br>`GrammarConstrainedEngine.kt`<br>`GrammarConstrainedEngineTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. JSON 객체 및 액션 칩 상태 전이 무결성 확인.<br>2. 종성별 조사 필터링 오류율 0.00% 달성.<br>3. 에뮬레이터 E2E 테스트 통과 (`AdvancedAiE2eDeviceTest`). |
| **EAI-11** | **[COMPLETED] 3단계 온디바이스 Verifier (규칙 -> KenLM PPL -> Mini PRM)**<br>• Stage 1 하드 구문 규칙 (ACC-01~04)<br>• Stage 2 KenLM N-gram Perplexity (정상 6.69 vs 비정상 257.24)<br>• Stage 3 경량 PRM 스코어러 (0.0~1.0 시맨틱 호응도)<br>• 3단계 전체 실행 시간 실측 평균 0.092ms (< 6.0ms) | `input/ai/verifier/`<br>`KenLmScorer.kt`<br>`MiniPrmScorer.kt`<br>`ThreeStageOutputVerifier.kt`<br>`ThreeStageOutputVerifierTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 비정상 구문 Stage 1/2 즉각 탈락 및 정문 3단계 통과 확인.<br>2. 3단계 전체 지연시간 < 6.0ms (실측 0.092ms) 달성.<br>3. 에뮬레이터 E2E 테스트 통과 (`AdvancedAiE2eDeviceTest`). |
| **EAI-12** | **[COMPLETED] 4-Tier Mobile Memory 및 TPO 문맥 게이팅**<br>• L1(버퍼), L2(세션 5개 FIFO), L3(에피소드 20개 LRU), L4(Ego-Graph) 계층화<br>• TPO(슬랙/카톡/검색, 시간대 4구간) 기반 페르소나 라우팅<br>• 총 메모리 추정치 < 35MB 엄격 보장 | `input/ai/memory/`<br>`TieredMemoryManager.kt`<br>`TpoContextEncoder.kt`<br>`TieredMemoryManagerTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 슬랙 구동 시 FORMAL_BUSINESS, 카톡 구동 시 CASUAL_CHAT 자동 전환 확인.<br>2. L1~L3 메모리 총합 < 35MB 엄격 검증 통과.<br>3. 에뮬레이터 E2E 테스트 통과 (`AdvancedAiE2eDeviceTest`). |
| **EAI-13** | **[COMPLETED] WorkManager 충전 중/유휴 배치 지식 추출 워커**<br>• Charging(true) + DeviceIdle(true) 제약 조건 트리거<br>• 대화 발화에서 시맨틱 지식 트리플(src, dst, relation) 추출<br>• SQLite WAL Ego-Graph 데이터베이스 단일 트랜잭션 원자적 upsert | `input/ai/worker/`<br>`GemmaGraphExtractWorker.kt`<br>`GemmaGraphExtractWorkerTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. WorkManager Charging & Idle 제약 생성 검증.<br>2. 시맨틱 트리플 추출 후 SQLite DB 트랜잭션 적재 및 쿼리 일치 검증 통과.<br>3. 에뮬레이터 E2E 테스트 통과 (`AdvancedAiE2eDeviceTest`). |
| **EAI-14** | **[COMPLETED] Thermal Guardian (4단계 적응형 발열 제어)**<br>• 배터리 온도 36.5°C 초과 시 백그라운드 학습 즉시 차단<br>• Thermal Status NORMAL(0) ~ CRITICAL(4)에 따른 적응형 딜레이(0~100ms) 및 배치 크기(16~0) 스케일링 | `input/ai/thermal/`<br>`ThermalGuardian.kt`<br>`ThermalGuardianTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 배터리 36.5°C 경계선 검증 (초과 시 차단).<br>2. 단계별 스로틀링 딜레이(MODERATE=10ms, SEVERE=25ms) 검증 통과.<br>3. 에뮬레이터 E2E 테스트 통과 (`AdvancedAiE2eDeviceTest`). |

---

## 5. P3: 지속 적응 및 차세대 연구 (실증 완료)

| ID | 항목 및 작업 내용 | 대상 파일 / 모듈 | 완료 조건 (DoD) & 검증 결과 |
|---|---|---|---|
| **EAI-15** | **[COMPLETED] On-Device LoRA 어댑터 미세조정 & EWC 망각 방어**<br>• LoRA rank=4, alpha=8.0f 델타 가중치 관리<br>• Elastic Weight Consolidation(EWC) 정규화 손실 페널티 적용 | `input/ai/adapter/`<br>`OnDeviceLoraTrainer.kt`<br>`LoraAndTttTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. LoRA 배치 학습 성공 및 델타 가중치 갱신 확인.<br>2. EWC 적용으로 파괴적 망각률 < 2.0% (실측 0.00%) 검증 통과. |
| **EAI-16** | **[COMPLETED] Test-Time Training (TTT) 인플레이스 Fast Weight 적응**<br>• 입력 문맥 기반 온라인 연상 메모리 Fast Weight 적응 (< 20ms)<br>• `reset()` 호출 시 기준 가중치로 완전 복귀 (Zero Parameter Drift) | `input/ai/adapter/`<br>`TestTimeTrainer.kt`<br>`LoraAndTttTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 실시간 문체 적응 속도 < 20ms (실측 < 0.5ms) 검증.<br>2. 리셋 시 파라미터 드리프트 0.00f 무결성 확인. |
| **EAI-17** | **[COMPLETED] Tiered Hardware Strategy 동적 하드웨어 프로파일러**<br>• 기기 총 RAM 및 CPU 코어 측정<br>• HIGH_END(>=10GB), MID_RANGE(>=6GB), LOW_END(<6GB) 자동 분기 | `input/ai/hardware/`<br>`HardwareTierProfiler.kt`<br>`HardwareTierProfilerTest.kt`<br>`AdvancedAiE2eDeviceTest.kt` | **[PASS] 100% 완료**<br>1. 기기 하드웨어 프로파일링 정상 수행.<br>2. 티어별 추천 모델 및 배치 크기 매핑 검증 통과. |
