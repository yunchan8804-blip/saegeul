# 새글 온디바이스 AI 극한 최적화 & 극한 정확성 지식 아카이브 (Knowledge Vault)

본 디렉토리는 새글(Saegeul) 한국어 온디바이스 입력기의 로컬 AI(Gemma 및 경량 SLM)를 **극한의 최적화(상시 프로세스, 레이턴시, 메모리, 배터리)**와 **극한의 정확성(한국어 호응, 초개인화, 온디바이스 그래프 추론)**으로 끌어올리기 위한 연구 자료, 논문 분석, 아키텍처 비판 및 정본 실행 계획을 아카이빙하는 정본 지식 저장소다.

## 디렉토리 및 문서 구성

1. **지식 아카이브 (Deep Research & Analysis)**:
   - [01-ondevice-runtime-daemon-optimization.md](01-ondevice-runtime-daemon-optimization.md): Gemma 로컬 모바일 상시 프로세스 상주, LMK 극복, mmap 가중치 공유, 런타임 엔진 벤치마크(LiteRT vs llama.cpp vs QNN 등), TTFT/TPS 극한 단축.
   - [02-extreme-accuracy-slm-methodologies.md](02-extreme-accuracy-slm-methodologies.md): 소형 모델(1B~4B)의 정확도 한계 극복, 한국어 문법·의미 호응 보장(GBNF/Outlines 등 Constrained Decoding), Speculative Decoding, Test-time Scaling.
   - [03-personalization-pitfalls-and-breakthroughs.md](03-personalization-pitfalls-and-breakthroughs.md): 기존 Saegeul 개인화 파이프라인의 근본 결함 분석, 온디바이스 어댑터/LoRA 학습 가능성, Test-Time Training(TTT), 다차원 TPO(시간/앱/상대방) 개인화.
   - [04-ondevice-graph-rag-and-kg.md](04-ondevice-graph-rag-and-kg.md): Gemma 기반 온디바이스 지식 그래프 추출 및 GraphRAG, 초경량 임베디드 그래프 엔진(SQLite-vec, Kùzu, TinyGraph), Ego-Graph 실시간 1-hop 주입.
   - [05-adversarial-review-and-skepticism.md](05-adversarial-review-and-skepticism.md): 끊임없는 의심과 비판적 검토. 모바일 물리적 한계(Thermal, OOM, Doze, 권한)와의 정면 충돌 분석 및 현실적 극복 방안.

2. **정본 계획서 및 백로그 (Master SSOT Plan & Backlog)**:
   - [extreme-ai-master-plan.md](extreme-ai-master-plan.md): 극한 최적화 & 극한 정확성 통합 아키텍처 정본 계획서.
   - [extreme-ai-backlog.md](extreme-ai-backlog.md): 단계별(Phase 0 ~ Phase 4) 정본 마스터 백로그 및 수용 기준(DoD).

---
*최종 갱신 시점: 2026-09-17*
