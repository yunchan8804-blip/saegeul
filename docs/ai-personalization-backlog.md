# AI 개인화 백로그

이 문서는 AI 개인화(앵커 RAG·리랭커·컴패니언 강화) 작업 중 드러났지만 **원래 계획(Phase 1→다듬기→2a→2b→3)을 먼저 끝내기 위해 미룬** 항목의 정본 목록이다. 기준일 2026-09-07. 항목을 착수·완료하면 이 파일을 갱신한다.

우선순위: **P1** = 정확성·개인정보에 직접 영향, **P2** = 품질·UX, **P3** = 구조·정리.

## P1 — 정확성·개인정보

| # | 항목 | 발견 근거 | 위치 | 규모 |
|---|---|---|---|---|
| B1 | ~~**대시보드 개인정보 문구 모순**~~ — **해소(Phase 3-d)**: 레이아웃 900행 문구와 `TypingDnaChartView` 게이지 "클라우드 전송 0B"를 온디바이스 학습 한정으로 분리하고 「지금 강화」·자동 강화는 사용자가 켤 때만 본인 컴퓨터로 보냄을 명시. | 기기 검증(Fold6) 스크린샷 | `activity_typing_dna_dashboard.xml`, `TypingDnaChartView.kt` | 완료 |
| B2 | **augmenter가 PII 미치환 raw 문맥을 송출**: `PersonalizedSentenceAugmenter.augmentContext`가 `getRecentContext()`(미치환)를 컴패니언에 보냄. 오프라인 게이트는 닫았으나(fc8f6032) 송출 전 `KoreanPiiScrubber.scrub` 적용 필요. | 탐색 워커 보고 | `FcitxInputMethodService` augmenter 경로, `UserTypingContextCollector` | 소 |
| B3 | **프리페처가 사용자 내용을 logcat에 기록**: `AiSentenceCompletionPrefetcher`가 `result.suggestions`와 문맥을 `Log.i/d`로 찍음. 릴리스에서 개인정보 노출 소지. 디버그 빌드 한정 또는 제거. | 코드 읽기 | `AiSentenceCompletionPrefetcher.kt` | 소 |

## P2 — 품질·UX

| # | 항목 | 발견 근거 | 위치 | 규모 |
|---|---|---|---|---|
| B4 | **자모 깨진 노이즈 학습**: "허허ㅇㄴㅎㅅ", "ㅎㅎㅎ나리도", "즐거웠오", "먼가" 같은 고립 자음 덩어리·오타가 "자주 쓰는 어절"과 n-gram에 학습됨. 영문 섞인 토큰 필터(`PersonalNgramTokenizer.isDroppable`)는 있으나 자모 노이즈·오타는 못 거름. 고립 자음/모음 덩어리(ㅋㅎㅠㅜ 감탄 제외) 필터 확장, 오타는 교정 저장소와 대조. | 기기 검증 스크린샷 | `PersonalNgramTokenizer.kt`, `TypingDnaVault` | 중 |
| B5 | **TPO(앱·시간·상황) 문맥 조건화**: 앱 카테고리(메신저/업무/일반)는 이미 있고 RAG ×1.3·n-gram 카테고리 테이블로 쓰임. 더 세밀한 앱 유형(이메일·검색·문서·SNS), 시간대 버킷, 필드 유형(제목/본문)으로 예측·검색·그래프를 조건화. 컴패니언 그래프 노드에 문맥 태그. 전부 기기 파생이라 개인정보 안전. | 사용자 제안 | `TypingDnaVault.categorizePackage`, `PersonalSentenceVault.retrieve`, `PersonalNgramModel`, `PersonalGraphEnricher` 프롬프트 | 중~대 |
| B6 | **강화 프롬프트·매칭 실데이터 튜닝**: `GraphEnrich` 프롬프트가 suggestions[0]에 그래프 JSON 문자열을 넣는 계약이라 LLM 준수도에 의존. 실제 컴패니언 강화를 여러 번 돌려 실 노드 id 분포·파싱 실패율을 보고 프롬프트·`proximityBoost` 가중을 조정. (파싱 견고화·stem 별칭은 Phase 3에서 처리) | Phase 2b 설계 | `AiAction.GraphEnrich`, `PersonalGraphEnricher`, `PersonalGraphStore` | 중, 컴패니언 필요 |

## P3 — 구조·정리

| # | 항목 | 발견 근거 | 위치 | 규모 |
|---|---|---|---|---|
| B7 | **소스 간 점수 정규화 계층 부재**: 예측 소스마다 confidence를 손튜닝 상수(0.85~0.999)로 꽂고 하나의 정렬로 섞음. 원시 관련도→공통 0~1 매핑 계층을 두고 9곳 이상의 `addPrediction`·리랭커·톤 예측기가 거치게 하는 설계 작업. | /simplify 고도 리뷰 F4 | `AiContextualPredictor`, `AiToneAdaptivePredictor`, `SentenceRelevanceReranker` | 대, 설계 결정 필요 |
| B8 | **half-life 감쇠 수식 중복**: `PersonalSentenceVault`, `PersonalNgramModel`, `CorrectionPatternStore`가 같은 `count·2^(-Δt/halfLife)`를 각자 구현. 공용 헬퍼로 추출. | /simplify 재사용 리뷰 | `input/ai/` 3개 저장소 | 소 |
| B9 | **문맥별 retrieve 메모이제이션**: `predict()`가 stroke마다 재실행돼 같은 단어 타이핑 중 동일 BM25 조회(PII 스크럽 정규식 포함)가 반복. `(context, pkg)` 최근 1건 캐시. | /simplify 효율 리뷰 | `AiContextualPredictor` / `PersonalSentenceVault` | 소 |
| B10 | **fcitx→saegul 네임스페이스 전면 치환(권하지 않음)**: `org.fcitx.fcitx5.android`가 738파일·2666회, JNI 심볼 `Java_org_fcitx_..._Fcitx_*`(native-lib.cpp)와 lockstep, 서브모듈은 실제 fcitx5 엔진, applicationId는 이미 `net.chanpaca.saegeul`. 실익은 내부 브랜딩뿐이고 업스트림 머지가 끊김. 하려면 AI 작업 랜딩 후 독립 브랜치에서 스크립트 리네임+JNI 동시 수정. 대안: 새 Saegeul 고유 코드만 `net.chanpaca.saegeul.*`로 격리. | 사용자 질문 | 전역 | 대, 결정 필요 |

## Phase 3에서 처리 중(백로그 아님)

- `agy` CLI를 Flash 3.8 high effort(`gemini-3.8-flash-high`, `--effort high`)로 호출하도록 컴패니언 설정.
- `PersonalGraphStore.proximityBoost`의 stem 불일치("회의"→"회") 해소: 노드 별칭 인덱스.
- `PersonalGraphEnricher.parseChunk` 견고화: 코드펜스·앞뒤 설명문 허용.
- 강화 루프 자동화(opt-in, 기본 꺼짐): 새 문장 임계치·최소 간격·게이트 통과 시 IME에서 기회 트리거. 이때 B1 문구도 함께 정리.
