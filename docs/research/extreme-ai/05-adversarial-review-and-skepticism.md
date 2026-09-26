# 극한 최적화 & 극한 정확성에 대한 비판적 의심과 적대적 검토 (Adversarial Review)

- **문서 식별자**: `docs/research/extreme-ai/05-adversarial-review-and-skepticism.md`
- **검토 관점**: 상용 모바일 환경의 가혹한 물리 법칙(메모리, 발열, 배터리, OS 생명주기)과의 충돌 검증
- **작성 원칙**: "아름다운 이론을 가장 가혹하게 의심하고, 현실에서 살아남을 수 있는 방어선만을 채택한다."
- **최종 갱신일**: 2026-09-17

---

## 1. 제1의심: "Gemma 3 1B(650MB)조차 중저가폰에서는 치명적인 폭탄이 아닌가?"

### 1.1 공격 (Adversarial Attack)
- 대한민국 및 글로벌 시장에는 12GB RAM의 갤럭시 S24/Fold6만 존재하는 것이 아니다. 대량 보급형인 갤럭시 A35(6GB RAM), A24/A15(4GB RAM)가 존재한다.
- 4GB RAM 기기에서 안드로이드 OS 커널과 시스템 UI가 기본 2.2GB를 차지하고, 카카오톡이 400MB, 인스타그램/유튜브가 600MB를 먹는 상태에서, 키보드가 650MB(Gemma 3 1B)를 점유하면 가용 RAM은 100MB 미만으로 추락한다.
- 사용자가 카메라를 켜거나 고사양 게임을 전환하는 순간 LMK(Low Memory Killer)는 키보드 데몬을 즉각 사살할 뿐만 아니라, 시스템 전체 UI 렉(Jank)과 앱 튕김의 주범으로 Saegeul을 지목하게 된다.

### 1.2 비판적 방어 및 설계 수정 (Defensive Resolution)
1. **Tiered Model Strategy (기기 램 용량별 적응형 배포)**:
   - **Tier A (RAM 12GB+ 플래그십)**: Gemma 2 2B (W4A16, ~1.4GB) 또는 Gemma 3 1B 고정밀 모드.
   - **Tier B (RAM 8GB 표준 기기)**: Gemma 3 1B (W4A16, ~650MB) 표준 구동.
   - **Tier C (RAM 4GB~6GB 중저가 기기)**: Gemma 1B 진입 금지. **Qwen2.5-0.5B (W4A16, ~320MB)** 또는 **순수 신경망 배제 모드(Ego-Graph + Kiwi 형태소 + KenLM 5-gram, 총 RAM < 35MB)**로 자동 강등(Graceful Degradation).
2. **동적 가상 메모리 상한선 (Strict Memory Cap)**:
   - 기기 가용 메모리가 500MB 이하로 떨어지면, 상주 데몬은 LLM 가중치 페이지 전체를 즉시 `madvise(MADV_DONTNEED)`로 언로드하고 L1 Ego-Graph 캐시만으로 타이핑 칩을 제공한다.

---

## 2. 제2의심: "mmap 0초 로딩은 이론일 뿐, 실제로는 Storage Page Fault Stutter가 발생하지 않는가?"

### 2.1 공격 (Adversarial Attack)
- `mmap`은 가상 메모리 매핑만 1ms에 끝날 뿐, 실제로 신경망의 1~26번째 레이어 가중치를 곱하는 순간 커널 레벨의 **Major Page Fault**가 발생하여 UFS 스토리지에서 플래시 블록을 읽어와야 한다.
- 만약 기기 스토리지가 저가 eMMC 5.1이거나, 백그라운드 앱 설치 등으로 스토리지 I/O 큐가 꽉 차 있다면, 가중치 읽기 대기(IO Wait)로 인해 첫 토큰 생성 중 **50ms ~ 200ms의 치명적 블로킹**이 발생한다. TTFT < 50ms는 허구가 된다.

### 2.2 비판적 방어 및 설계 수정 (Defensive Resolution)
1. **Asymmetric Pre-faulting (선별적 비동기 예열)**:
   - 650MB 전체를 읽어올 필요는 없다. 첫 토큰 생성(TTFT)에 즉각 필요한 **임베딩 테이블(64k trimmed: ~150MB)과 첫 4개 트랜스포머 레이어(~90MB)**에 대해서만, 키보드가 팝업되는 시점(`onStartInputView`)에 POSIX `posix_fadvise` / `madvise(MADV_WILLNEED)`를 백그라운드 I/O 풀에서 비동기로 당겨온다.
2. **Flash Storage Benchmark Gate**:
   - 앱 최초 설치 시점에 스토리지 읽기 속도를 1회 벤치마크하여, 순차 읽기 속도가 400MB/s 미만(노후 기기/저가 플래시)인 경우 실시간 LLM 자동완성을 비활성화하고 배치 사전 생성 모드로 전환한다.

---

## 3. 제3의심: "XGrammar/GBNF 문법 제약이 오히려 생성 붕괴(Degenerate Loop)를 부르지 않는가?"

### 3.1 공격 (Adversarial Attack)
- 언어 모델이 특정 문법이나 JSON 형식을 출력하도록 강제할 때, 모델 내부에서 가장 확률이 높은 토큰들이 마스킹되면 확률 분포의 꼬리(Tail distribution)에 있는 엉뚱한 토큰이 선택된다.
- 이로 인해 토큰 생성이 헛돌거나 `네 네 네 네...` 같은 반복 루프(Degeneration)에 빠지거나, 문법은 맞지만 의미적으로 완전히 헛소리인 문장이 출력될 위험이 크다.

### 3.2 비판적 방어 및 설계 수정 (Defensive Resolution)
1. **이중 안전장치: 마스킹 후 엔트로피 감시**:
   - Logit Masking 적용 후, 살아남은 토큰들의 Softmax 확률 합이 $10^{-4}$ 이하로 극단적으로 낮아진 경우, 모델이 문법적 탈출구를 찾지 못하고 있음을 감지.
   - 이 경우 즉시 제약 디코딩을 강제 종료하고, **사전 정의된 템플릿(Fallback Phrase)으로 조기 탈출(Early Termination)**.
2. **구조적 제약과 의미적 제약의 분리**:
   - 조사 호응(은/는, 이/가)은 유니코드 종성 규칙 기반으로 **100% 하드 마스킹** 적용.
   - 고유명사나 엔티티(Ego-Graph)는 하드 마스킹이 아닌 **Soft Logit Biasing (+3.0 ~ +5.0)**을 적용하여 모델의 자연스러운 연결어 생성을 방해하지 않음.

---

## 4. 제4의심: "모바일 온디바이스 LoRA 역전파 학습은 기기 배터리를 망치고 화재 위험을 키우지 않는가?"

### 4.1 공격 (Adversarial Attack)
- 고속 충전(25W~45W) 중인 스마트폰은 충전 회로 자체에서 이미 상당한 열(38°C~42°C)이 발생한다.
- 여기에 CPU/GPU 100% 풀로드로 역전파 행렬 연산을 5~10분간 돌리면 배터리 온도가 45°C를 초과하여 배터리 수명이 영구 손상되거나 충전이 강제 차단된다. 사용자는 키보드 앱이 휴대폰을 뜨겁게 달군다며 즉각 삭제할 것이다.

### 4.2 비판적 방어 및 설계 수정 (Defensive Resolution)
1. **Thermal Budget Hard-Cap (온도 절대 상한제)**:
   - Android `BatteryManager.EXTRA_TEMPERATURE` 모니터링: **배터리 온도가 36.5°C를 초과하면 충전 중이라도 LoRA 학습 즉시 정지**.
2. **Duty-Cycle 인터벌 학습**:
   - 연속으로 50문장을 한 번에 돌리지 않고, **1문장 역전파 후 2초간 스레드 Sleep**을 부여하여 칩셋 방열 시간을 보장.
3. **학습 데이터 최소화 (High-Utility Sample Selection)**:
   - 일상적인 모든 타이핑을 학습하지 않고, 사용자가 "추천을 수락하지 않고 직접 끝까지 타이핑한 문장", "새로운 고유 엔티티가 포함된 문장" 상위 10~20개만 선별하여 단 1~2분 내에 학습 완료.

---

## 5. 제5의심: "Ego-Graph의 SQLite와 C++ L1 인메모리 캐시 간의 동기화 붕괴 문제는?"

### 5.1 공격 (Adversarial Attack)
- 백그라운드 WorkManager에서 Gemma가 새 문장을 분석해 SQLite DB에 새 엔티티와 엣지를 추가하고 있을 때, 키보드 프로세스의 C++ L1 캐시는 이전 상태를 유지하고 있다면 동기화 불일치(Stale Cache)가 발생한다.
- 반대로 키보드 프로세스가 열려 있는 동안 SQLite WAL 모드에서 체크포인트 락이나 충돌이 발생하면 입력기 전체가 블로킹될 수 있다.

### 5.2 비판적 방어 및 설계 수정 (Defensive Resolution)
1. **단방향 Epoch 버전 관리 (Lock-Free Epoch Invalidation)**:
   - SQLite DB 헤더에 단일 정수 `graph_version (64-bit Epoch)` 관리.
   - WorkManager가 트랜잭션 커밋 완료 시 `graph_version++` 원자적 증가.
   - 키보드 IME의 C++ L1 캐시는 타이핑 세션 시작 시 `graph_version`이 변경되었을 때만 차분(Delta) 엣지 데이터를 1ms 내에 페이징 인(Lazy Invalidation).
2. **SQLite WAL (Write-Ahead Logging) + Read-Only Connection**:
   - 키보드 프로세스는 오직 **Read-Only 연결**로만 접근하여 백그라운드 쓰기 작업과 락 경합(Lock Contention)이 0%로 완전히 격리됨.

---

## 6. 결론: 의심을 통과한 불변의 원칙 5가지

1. **상시 상주의 본질은 메모리 점유가 아니라 0초 복구다 (`mmap` + Pre-warming).**
2. **하드웨어 체급을 속이지 마라 (플래그십은 Gemma 3 1B, 보급형은 Qwen 0.5B / 경량 규칙 모드).**
3. **타이핑 순간에 무거운 연산을 하지 마라 (추출·학습은 충전 중 유휴 배치, 타이핑 순간은 캐시 조회만).**
4. **조사 호응은 확률에 맡기지 말고 수학적 유니코드 비트마스크로 100% 강제하라.**
5. **발열과 배터리는 언제나 AI 성능보다 상위 가치다 (온도 36.5°C 상한선 준수).**
