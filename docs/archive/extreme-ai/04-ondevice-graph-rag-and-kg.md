# 온디바이스 지식 그래프(GraphRAG) 구축 & Gemma 로컬 그래프화 (Research & SSOT)

- **문서 식별자**: `docs/research/extreme-ai/04-ondevice-graph-rag-and-kg.md`
- **대상 모델**: Gemma 2 2B, Gemma 3 1B/4B 및 온디바이스 초경량 엔진
- **타깃 환경**: 안드로이드 모바일 온디바이스 (ARM64 NDK, 서브그래프 질의 < 5ms)
- **최종 갱신일**: 2026-09-17

---

## 1. 온디바이스 Gemma 기반 그래프 추출 (Local Knowledge Graph Extraction)

### 1.1 소형 모델(Gemma 2 2B / Gemma 3 1B)의 정보 추출(OpenIE) 신뢰성
- **Zero-shot의 불안정성**: 1B~2B 급 초소형 언어 모델은 복잡한 지시문 수행 시 Format Breakage(JSON 구문 붕괴), Entity Over-segmentation(단어 경계 오인), Relation Hallucination(허위 관계 생성) 비율이 35~45%에 달함.
- **Fine-tuning(LoRA/QLoRA)의 효과**: 최신 소형 모델 OpenIE 벤치마크(GCIE, SLCoLM 2024; Gemma 2 2B LoRA 연구)에 따르면, 1~2개 에포크의 맞춤형 LoRA 미세조정 시 Triplet Extraction F1-score가 72.4% ~ 81.8%까지 도달하여 상위 모델에 근접함.
- **SmartRAG 아키텍처 (arXiv:2607.14661, 2026)**: 모바일 온디바이스 그래프 RAG 프레임워크인 SmartRAG는 가벼운 엔티티 추출기(EvoNER)와 증류(Distillation) 파이프라인을 결합하여 1.7B 백본에서도 18배 큰 모델에 필적하는 멀티홉 지식 구조화를 달성함.

### 1.2 소형 모델 프롬프팅 및 JSON 강제 기법 (Constrained Decoding)

| 기법 / 엔진 | 구동 계층 | 원리 | 모바일 지연 오버헤드 | 메모리 영향 | 안드로이드 적합도 |
|---|---|---|---|---|---|
| **GBNF (llama.cpp)** | C++ / NDK 네이티브 | FSM 기반 Logit Masking | 8% ~ 15% | < 1MB | **최상 (의존성 제로, 검증됨)** |
| **XGrammar** | C++ 코어 / FSM 캐시 | 압축 FSM 및 토큰 비트셋 사전계산 | 2% ~ 5% | ~5MB | **상 (초고속 요구 시 채택)** |
| **Outlines / Jsonformer** | Python / PyTorch | 정규식·JSON 스키마 인덱싱 토큰 필터 | 20% ~ 40% | 10MB+ | **하 (모바일 런타임 탑재 곤란)** |

- **비대칭 배치 파이프라인 (Asymmetric Offline Pipeline)**:
  1. 사용자의 타이핑 텍스트는 SQLite 기반 수집 큐(Pending Ingestion Buffer)에 저장.
  2. Android **WorkManager**를 통해 기기가 **충전 중(Charging) + 화면 꺼짐(Device Idle) + 배터리 30% 이상**일 때 백그라운드 Worker 실행.
  3. 수집 큐에서 10~20문장 단위로 묶어(Batch Processing), `llama.cpp` NDK 또는 LiteRT LLM 엔진에서 GBNF JSON Schema를 적용하여 `{"entities": [...], "triplets": [{"subject": "...", "predicate": "...", "object": "..."}]}` 형태로 일괄 추출·적재.

### 1.3 교착어(한국어) 극복을 위한 하이브리드 파이프라인
- **1단계 (형태소 분절 및 후보 태깅)**: Kiwi C++(또는 MeCab-ko C/NDK) 초경량 엔진(< 10MB RAM)을 통해 한국어 조사를 분리하고 명사구/고유명사/시제 표현을 1차 필터링.
- **2단계 (시맨틱 관계 분류)**: 1단계에서 분리된 정제 개체쌍을 Gemma 프롬프트에 주입하여 개체 간 관계(Predicate: `약속_장소`, `회의_시간`, `동료`)만을 선택하도록 유도함으로써 환각 및 경계 분절 오류를 원천 차단.

---

## 2. 온디바이스 초경량 임베디드 그래프 엔진 및 벡터 결합

### 2.1 임베디드 그래프 엔진 비교 분석

| 엔진 | 언어 / 런타임 | 스토리지 및 인덱스 | 메모리 풋프린트 | 1~2-hop 서브그래프 지연 | 안드로이드 배포 평가 |
|---|---|---|---|---|---|
| **SQLite (Recursive CTE + sqlite-vec)** | **C / NDK 네이티브** | **B-Tree + Vector Index (IVF/HNSW)** | **< 8MB** | **0.2ms ~ 1.5ms** | **[최우수] 표준 안드로이드 환경, 의존성 제로, ACID 보장** |
| **Kùzu (KùzuDB / SiafuDB)** | C++17 | Columnar Disk, CSR Graph | 40MB ~ 120MB+ | 0.05ms ~ 0.5ms | **[주의]** Apple 인수 후 원본 아카이브(2025.10). 모바일 메모리 부담 큼 |
| **CozoDB** | Rust / C API | Datalog, SQLite/RocksDB | 15MB ~ 30MB | 1.0ms ~ 3.5ms | **[보통]** C FFI 경유 시 JSON 직렬화 오버헤드 존재 |
| **TinyGraph / libigraph** | Pure C | In-memory Adjacency List | < 2MB | < 0.1ms | **[제한적]** 영속성 부재, L1 캐시 용도로만 적합 |

### 2.2 하이브리드 스토리지 구조
- **영속 계층**: SQLite + `sqlite-vec` (플래시 메모리에 안전하게 트랜잭션 보장, 텍스트 벡터 검색 결합).
- **L1 인메모리 핫 캐시**: C++ NDK 레벨에서 최근 30일간 접근된 활성 엔티티/트리플을 인접 리스트(Adjacency Array, < 2MB)로 상주시켜, 키보드 이벤트 발생 시 SQLite 디스크 I/O 없이 **0.05ms(50µs)** 내에 서브그래프 획득.

---

## 3. 키보드 입력용 Ego-Graph (개인 중심 시맨틱 네트워크)

```
         [User (Ego Node)]
             │
   ┌─────────┼──────────────┐
   │(대화/협업)│(약속/일정)     │(거주/방문)
   ▼         ▼              ▼
[Person]  [Time/Event]  [Location]
   │         │              ▲
   └─────────┼──────────────┘
      (회의_장소/동행)
             │
             ▼
      [Topic / Project]
```

### 3.1 엣지 가중치 시간 감쇄 함수
- 엣지 가중치 W는 접근 빈도(Frequency)와 시간 경과(Recency)를 반영한 지수 감쇄 적용:
  W(e) = sum_k exp(-lambda * (t_current - t_k))
- 오래된 약속이나 종결된 프로젝트는 가중치가 자연 소멸하여 탐색 우선순위에서 밀려남.

### 3.2 HippoRAG 경량화 (Personalized PageRank)
- **HippoRAG (NeurIPS 2024 / ICML 2025: HippoRAG 2)**의 PPR 기반 서브그래프 추출을 NDK C++로 경량화.
- Seed 벡터 s (활성 앱 카톡 상대방 + 현재 입력 버퍼 키워드)에 대해 Random Walk with Restart(RWR) 수행:
  p^(t+1) = (1 - alpha) * s + alpha * M * p^(t)   (alpha = 0.85, t=2~3회 반복)
- N <= 5,000 희소 행렬 연산은 C++ NEON 벡터화로 **0.3ms 미만** 완료.

---

## 4. Graph-Augmented Decoding & Hallucination Elimination

### 4.1 서브그래프 프롬프트 주입: Linearized Triplets
- **형태**: `[지식: (철수, 회의_장소, 강남역 투썸), (철수, 안건, 배포)]`
- **장점**: 토큰 소모 극소 (15~25 토큰), 프리필 지연 < 15ms, 사전 자연어 요약에 따른 환각 왜곡(Narrative Poisoning) 원천 차단.

### 4.2 Graph-Constrained Reasoning (GCR) & Zero-Latency Direct Strip
1. **KG-Trie 기반 제약 디코딩 (GCR, arXiv:2410.13080)**:
   - 검색된 서브그래프의 엔티티 명칭(`강남역 투썸`, `새글 로드맵`)을 바이트/토큰 단위의 Prefix Tree(Trie)로 구성.
   - 엔티티 생성 슬롯에서 Trie 경로 외 토큰 Logit을 -inf 마스킹하여 100% 팩트 일치 보장.
2. **Zero-Latency Direct Suggestion Strip (단축 경로)**:
   - LLM 디코딩을 거치지 않고도, Ego-Graph 검색 결과의 1-hop 타깃 엔티티(`강남역`, `오후 7시`)를 **키보드 상단 추천 툴바의 칩(Chip)으로 0ms에 다이렉트 렌더링**하여 사용자에게 즉각 노출.

---

## 5. 자원 풋프린트 요약 및 결론

| 구성 요소 | 런타임 상주 메모리(RAM) | 디스크/저장소 공간 | CPU/NPU 사용 시점 |
|---|---|---|---|
| **SQLite + sqlite-vec** | 4MB ~ 8MB | 5MB ~ 20MB (수만 트리플) | 쿼리 시점 순간 사용 (< 1ms) |
| **C++ L1 Ego-Graph 캐시** | 1MB ~ 2MB | 0MB (RAM 상주) | 타이핑 이벤트마다 (< 0.1ms) |
| **Kiwi/MeCab 형태소 엔진** | 10MB ~ 15MB | 15MB (사전 바이너리) | 키워드 분절 시 (< 2ms) |
| **Gemma 2 2B / 3 1B INT4** | **상시 상주 0MB** (WorkManager 시 ~1.8GB 로드 후 해제) | ~1.5GB ~ 2.5GB | 야간/충전 중 백그라운드 배치 |
