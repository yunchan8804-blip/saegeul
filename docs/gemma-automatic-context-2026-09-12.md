# 앱·편집 문맥 기반 Gemma 자동 추천

상태: 구현 진행 중. 목표는 사용자가 기능을 켠 뒤 입력 중 다음 단어와 다음 문장을 빠르게 받는 것이다. 기존 명시 실행의 성공이나 메모리 캐시 단위 테스트만으로 이 목표를 완료 처리하지 않는다.

현재 확인 지점: Fold6 wireless ADB에서 공개 개발 입력 한 개의 실제 자동 opt-in·수집·단어/문장 표시·문장 터치 적용을 통과했다. 초기 모델 준비를 포함한 첫 후보 표시는 18,795ms였다. 기능 연결 증거이며 빠른 추천 목표, 새 held-out 품질, 자동 경로의 전체 안전성 회귀 완료는 아니다.

## 목표와 측정

- 현재 입력 대상 앱과 필드 종류, 같은 편집 세션에서 앞서 작성한 문맥을 반영한다. 현재 편집기의 문맥과 실제로 생성한 이어쓰기를 제한된 메모리에 유지한다.
- 다음 단어와 짧은 다음 문장을 실제 Gemma 추론으로 제공하고 후보 막대에서 표시·적용한다. 공개 저장 은행 조회, 캐시 잔여분 재사용, 새 추론의 출처와 지연을 구분한다.
- 최적화 목표는 준비된 엔진에서 입력이 안정된 시점부터 첫 단어 표시까지 p50 300ms / p95 700ms, 캐시 잔여 후보 갱신 p95 50ms다. 이는 아직 측정된 성능이 아니다. 콜드 시작, debounce, 첫 출력, 적용 가능한 후보, 전체 종료를 따로 측정하며 미달이면 수치를 그대로 보고하고 최적화를 계속한다.
- 새 held-out 입력, 여러 문장이 누적된 입력, 동일 끝구절·다른 앞 문맥, 앱/필드 전환, 실제 연속 한글 조합을 평가한다. 후보 존재와 의미·조사·어미·띄어쓰기 품질을 분리한다.

## 확정한 개인정보·상태 경계

- 기능 활성화는 별도 opt-in이며 설정에서 처리 범위를 설명한다. 비밀번호·민감 필드·학습 금지·앱 차단 정책을 그대로 적용한다. 완전 오프라인에서 동작하며 외부 공급자 fallback이 없다.
- 앱 정보는 현재 EditorInfo의 패키지와 필드 종류 등 입력 대상 메타데이터로 한정한다. 화면 수집, 클립보드, 위치, 다른 앱 기록, 개인 금고를 자동으로 읽지 않는다. 앱 종류를 확실히 알 수 없으면 일반 문맥으로 처리한다.
- 원문·응답·세션 캐시는 메모리 전용이다. 공개 은행, 학습 저장소, 디스크 캐시, 로그에 넣지 않는다. 기기 증거에는 사전에 정한 공개 입력만 기록한다.
- 캐시 키는 앱·field ID·IME 세션·편집 revision·정확한 원문·커서다. 세션 전환, 숨김, 개인정보 제한, 선택·커서 이동, 삭제·치환은 요청과 캐시를 폐기한다. 원문이 되돌아와도 이전 요청을 되살리지 않는다.
- 이미 완료한 추론에서 사용자가 정확히 같은 접미부를 순서대로 입력한 경우에만 남은 접미부를 새 snapshot의 후보로 재사용한다. 저장 문장의 prefix/last-word 검색은 하지 않는다. 이 재사용은 새 의미 추론으로 집계하지 않는다. 최초 생성 후 30초 TTL, 최대 120자 접미부, 최대 2048자 문맥을 적용한다.
- 자동 관측이 한글 조합을 강제로 확정해서는 안 된다. 조합 중 상태를 포함한 snapshot·적용 계약은 별도 통합 검증을 거친다. 후보 적용은 사용자 터치와 현재 snapshot 재검증이 필요하다.

## native 최적화와 소유권

기존 명시 경로는 요청마다 모델 검증·Engine 초기화·Conversation 생성·원문 재출력·종료를 수행한다. 새 경로는 준비된 Engine을 재사용하고 짧은 접미부만 생성하는 방향으로 구현한다. Conversation/KV 재사용 가능 범위는 사용 중인 LiteRT-LM 0.13.1의 실제 API와 취소 계약을 확인해 결정한다. 지원이 확인되지 않은 KV cache를 사용한다고 주장하지 않는다.

첫 구현에서는 Engine만 재사용하고 요청마다 Conversation을 새로 만들어 정상 종료 뒤 닫는다. 취소·실행 실패 시 Engine도 폐기한다. 정상 대화 history를 반복 누적해 미채택 후보가 다음 입력의 사실 문맥으로 섞이지 않게 한다. 엔진의 파일 캐시는 `:nocache`로 비활성화한다. 모델은 엔진을 처음 열 때 검증하고, 보유 중 파일 식별 정보가 달라지면 닫고 재검증한다. idle 보유 한도는 30초다.

근거: [0.13.1 Config.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.13.1/kotlin/java/com/google/ai/edge/litertlm/Config.kt)의 `maxNumTokens`는 입력+출력 합계이며 Kotlin 요청별 출력 토큰 제한이 아니다. CPU 스레드 수와 캐시 비활성화는 공개 API다. [0.13.1 Conversation 계약](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.13.1/runtime/conversation/conversation.h)은 취소 시 이전 입력으로 rollback하지 않음을 명시한다. 짧은 출력은 프롬프트와 앱 길이 검사로 제한하며 token 용량을 출력 상한으로 표시하지 않는다.

공개 재료 생성은 계속 키보드 활성 중 취소·차단한다. 자동 문맥 추론은 사용자 opt-in에 귀속한 별도 목적이며 기존 명시 요청으로 위장하지 않는다. 단일 native 소유권과 실제 종료 확인은 유지한다. idle Engine 보유와 실행 중 decode를 상태상 구분하며 엔진 보유 중 다른 public/explicit 생성이 중복 메모리를 할당하지 않도록 한다. 세션 종료·기능 끄기·자원 제한에서 메모리와 native 자원을 해제한다. 배터리·발열·메모리 가드를 완화하지 않는다.

## 완료 증거

1. 앱/필드·앞 문맥이 프롬프트와 출력에 반영되는 실제 호출 및 공개 반사실 비교.
2. 콜드·웜·연속 입력·캐시 재사용 각각의 실제 기기 첫 후보 지연과 p50/p95, CPU/GPU 및 입력 지연 영향 비교.
3. 실제 IME 단어·문장 후보 표시와 적용, 조합 보존, 이중 적용 방지.
4. 오프라인, 개인정보 제한, 앱/커서/선택/원문 변경, 취소와 늦은 결과 차단, 공개 native 중지 회귀.
5. 모델 없는 상태·발열 제한·초기화 실패·native 종료 실패의 명시적 상태와 복구.
6. 새 held-out 한국어 품질 평가, 기존 dirty 보존, 필요한 빌드·단위·계측 검증.

## 구현·검증 진행 기록

- `OnDeviceSuggestionSession`: 정확한 편집 snapshot과 세션에 묶인 요청·일회 적용·생성 접미부 잔여 캐시를 구현했다. native 수명주기를 대신 보장하는 클래스는 아니다.
- `OnDeviceSuggestionPolicy`: 현재 앱·필드 메타데이터와 여러 문장 문맥을 입력하고 WORD/SENTENCE 접미부만 요청한다. 형식 검사를 언어 품질 판정으로 사용하지 않는다.
- `OnDeviceSuggestionEngine`: debug에서 실제 LiteRT-LM Engine을 정상 요청 사이 재사용하며 Conversation은 매번 닫는다. AUTO_CONTEXT 목적, 숨김 취소, 자원 감시, idle 해제, 종료 실패 시 소유권 보존을 구현했다. 자동 IME 연결과 opt-in UI 코드는 추가했으며 통합 빌드·실제 표시·적용은 검증 중이다.
- `GemmaSuggestionEngineDeviceTest`: 6개 공개 입력의 cold/warm 엔진 비용을 분리하는 하위 runtime 계측이다. 실제 IME·후보 표시·오프라인 증거가 아니며 JSON에 그 범위를 명시한다.
- 새 held-out은 12개의 고유 문맥 × WORD/SENTENCE = 24개다. 앞 문맥 효과는 같은 모드끼리 비교한다. `gemma-automatic-heldout-20260912.json`의 SHA-256은 `11B842696DBCFEBE147F811801C7B5195E86FF6985A2CCBFEB61D3A566F1A226`이다. 모델 응답 관측 전에 고정했으며 정답 접미부는 없다.
- 첫 통합 빌드는 새 테스트 helper 기본 인자와 테스트 Context 의존성 참조 오류로 실패했다. 해당 오류를 수정하고 재검증한다. 실패 로그는 `outputs/gemma-automatic-context-20260912/build-arm64-01.log`에 보존한다.
- 재검증 `build-arm64-02.log`는 `BUILD SUCCESSFUL in 39s`, exit 0이다. 실제 XML에서 세션 15개·출력 정책 7개·소유권 10개, 총 32개 테스트의 failure/error/skipped가 모두 0임을 확인했다. arm64 debug 앱과 계측 APK를 `apk-arm64-02/`에 SHA-256과 함께 보존했다. 이 검증은 실제 추론 속도·자동 IME 동작의 증거를 대신하지 않는다.
- x86 빌드는 `build-x86-01.log`에서 41초·exit 0이다. 전용 AVD에 앱·계측 APK를 설치할 때 각각 `Success`를 직접 확인했다.
- 첫 CPU 2스레드 하위 benchmark는 `emulator-cpu2-01/log`의 `OK (1 test)`, 146.204초다. 최초 검증 46,833ms·초기화 48,480ms·첫 출력 110,519ms였으며, 이후 5회는 동일 Engine 재사용과 검증/초기화 0ms를 확인했다. 웜 첫 출력은 5,430 / 5,570 / 6,832 / 8,130 / 6,246ms로 속도 목표 미달이다. 호스트 부하가 포함된 에뮬레이터 결과이며 실기기 성능으로 환산하지 않는다.
- 이 개발용 6입력에서 형식상 후보는 2/6이었지만, 두 후보 `들어와서`, `말라서`도 이미 쓴 끝말의 반복이었다. 직접 원문·응답을 읽은 의미 평가에서는 유용한 추천 0/6으로 판정한다. 엔진 재사용 성공을 추천 품질 성공으로 간주하지 않는다. 프롬프트를 단축·명확화하고 일반적인 반복 출력 검사를 보완한 뒤 다시 측정한다. 새 held-out 24입력은 이 개발용 6입력과 분리한다.
- benchmark 종료 시 Engine 닫힘·native lease 반납·내부 키보드 gate 복원을 확인했다. 실제 IME 연결과 네트워크 차단 검사는 하지 않았으며 `offlineProof=false`다. 로그 최초 위치가 이전 증거 루트였으므로 원본을 남기고 새 루트에 복사했으며 `source-note.txt`에 경로를 기록했다.
- Fold6는 새 무선 ADB 포트로 연결됐으나 실행 직전 Thermal Status 2로 바뀌어 `fold6-cpu2-01`에서는 추론을 시작하지 않았다. 설치 명령의 원문 성공 증거도 없으므로 해당 실행을 최신 설치나 성능 검증으로 세지 않는다.

이 문서는 새 자동 추천 목표의 정본이다. [이전 명시 경로 보고서](gemma-context-ime-2026-09-12.md)의 결과는 비교 기준으로 보존하며 자동 기능의 완료 증거로 대체하지 않는다.

### 실기기 개발 표본과 후속 출력 계약

자동 연결 구현은 조정기와 편집기 어댑터로 나눈다. 조정기는 opt-in 기본 OFF, 300ms debounce, 취소 종료와 새 실행의 직렬화, 현재 snapshot 재검증, 불투명 후보 identity와 일회 소비를 담당한다. 생성 문장이 형식을 통과하면 전체를 RAM 캐시에 보관하고 단어·문장 표시를 함께 만든다. 문장 형식은 실패하지만 단어 투영이 가능하면 그 단어만 보관한다. native 종료 실패는 ERROR로 남기고 다음 실행을 차단한다.

편집기 어댑터는 일반 조합에서 InputConnection 원문에 이미 포함된 composing text를 다시 붙이지 않는다. buffered Hangul에서는 물리 원문과 버퍼 prefix·native preedit를 정확히 합친 논리 원문을 별도로 보유하며, 양쪽 커서와 조합 상태를 함께 검증한다. 자동 수집은 조합을 확정하지 않는다. 사용자가 후보를 누를 때만 먼저 후보 identity를 소비하고, 현재 편집 상태 검증 → 조합 확정 → 확정된 전체 원문·세션·커서 재검증 → 정확한 접미부 삽입 순서로 적용한다. 공백 보정이나 기존 추천 학습 경로를 거치지 않는다. 명시 완성 화면과 자동 완성은 ExtractedText monitor를 동시에 소유하지 않는다. 이 문단은 구현 계약이며 실제 IME 통과 증거가 아니다.

- Fold6 CPU 2스레드 첫 프롬프트는 `fold6-cpu2-02/log`에서 `OK (1 test)`, 82.53초였다. 웜 첫 출력은 9,258 / 9,471 / 9,967 / 10,475 / 10,538ms다. 6개 응답 모두 원문 끝을 반복했고, 형식상 2/6과 의미상 0/6을 구분한다.
- 수정 프롬프트의 GPU 실행은 `fold6-gpu-v2-01/log`에서 `OK (1 test)`, 26.77초였다. 웜 첫 출력은 817 / 779 / 822 / 807 / 808ms, 전체 응답은 1,282 / 1,255 / 1,533 / 1,461 / 1,216ms다. 첫 출력은 유효 후보 준비 시간이 아니다. CPU와 프롬프트 버전도 달라 GPU만의 개선 폭으로 해석하지 않는다.
- GPU v2의 WORD 형식 후보는 0/6이다. 실제 응답은 `그림을 그릴 수 없어`, `사람들이 많이 몰리니`, `느낌을 받았다`, `따뜻한 기분이 든다`, `마땅히 물을 주어야 해요`, `책을 반납하고`였다. 여러 어절 출력 때문에 WORD 검사에서 탈락했으며 마지막 응답은 원문 반복이다. 후보 없음의 원인이 첫 프롬프트와 같다고 간주하지 않는다. 의미 적합성과 완결성도 이 응답 전체를 기준으로 별도 평가해야 한다.
- 후속 계약: 모델에는 짧은 문장 완성 접미부를 한 번 요청한다. WORD는 안전성·반복 검사를 통과한 생성 접미부의 첫 어절을 정확한 substring으로 투영하고, SENTENCE는 기존 문장 형식 검사에 통과한 전체 접미부를 사용한다. 원문이나 저장 은행의 prefix 조회가 아니며, 한 번의 생성에서 나온 두 표시 형태다. 전체 raw 검사를 먼저 수행하므로 뒤쪽의 금지 문자나 원문 반복을 첫 어절 추출로 숨기지 않는다. 형식 통과는 의미 품질 통과가 아니다.
- 위 실행은 모두 하위 runtime benchmark다. 실제 자동 IME 표시·적용 및 오프라인 증거는 아니며, 새 held-out 24개는 아직 추론하지 않았다. 두 Fold6 실행 모두 native 종료·lease 반납·내부 gate 복원을 확인했다. ADB 프로세스 OS 종료 코드는 포착되지 않아 계측 `OK`와 별도로 미확인으로 남긴다.
- v3 정책은 `build-x86-v3-01.log`에서 30초·exit 0, 정책 XML 8개 failure/error/skipped 0을 확인했다. 전용 AVD 설치는 앱·계측 각각 `Success`, exit 0이다. `emulator-cpu2-v3-01/benchmark.log`는 `OK (1 test)`, 87.685초이며 단어 6/6·문장 6/6의 형식 후보를 기록한다. 웜 5회 첫 출력은 3,822 / 3,797 / 3,671 / 3,754 / 3,967ms, 전체 응답은 4,555 / 4,501 / 4,050 / 4,305 / 4,355ms다. 매우 빠른 추천 목표에는 미달한다.

v3 개발 표본은 다음처럼 직접 평가했다. 이는 새 held-out 평가가 아니며, 단어 투영의 의미 품질을 전체 문장 점수로 대신하지 않는다.

| 현재 입력 | 생성 문장 접미부 | 의미 | 조사·어미·띄어쓰기 | 추가 지적 | 종합 |
|---|---|---|---|---|---|
| 물감이 아직 마르지 않아서 | 그림을 더 칠해야겠어요. | 실패 | 통과 | 덜 마른 상태가 더 칠해야 하는 이유로 자연스럽게 연결되지 않음 | 실패 |
| 행사장 입구가 붐비니 | 사람들이 몰려들어 통행이 매우 불편하다. | 통과 | 통과 | 붐비는 상황을 일부 반복함 | 통과 |
| 초안을 읽고 나서 | 전반적인 방향성을 잡을 수 있을 것 같다. | 통과 | 통과 | 없음 | 통과 |
| 창문으로 햇빛이 들어와서 | 방 안이 환해졌다. | 통과 | 통과 | 없음 | 통과 |
| 화분의 흙이 말라서 | 물기를 흠뻑 주어야 해요. | 통과 | 통과 | `물기를 주다`라는 어휘 결합이 부자연스러움 | 실패 |
| 도서관 책을 반납하고 | 집으로 돌아가려고 해요. | 통과 | 통과 | 없음 | 통과 |

문장 종합은 4/6이다. 모든 후보가 존재한다는 사실과 분리하며, 위 문장이나 수정 예시를 생성 프롬프트·공개 은행·held-out 정답에 넣지 않는다.

### 준비와 추론의 수명 분리

입력 변경이 콜드 모델 검증·초기화까지 반복 취소하지 않도록 `prepare()`는 편집 revision과 분리된 준비 요청으로 둔다. 준비에는 개인 prompt와 Conversation이 없고, 이후 `suggest()`가 같은 Engine에서 새 Conversation을 만든다. 타이핑에 따른 `cancel()`은 추론 요청만 취소한다. 반면 키보드 숨김·자원 위반·명시 `close()`는 준비와 추론을 모두 중지한다. 준비 지연은 별도 verification/initialization/total로 측정하며 입력 지연에서 숨기지 않는다. 해당 API와 실제 조정기 연결·실기기 증거는 각각 확인해야 한다.

현재 모델은 Gemma 4 E2B다. [Gemma 4 공식 카드](https://ai.google.dev/gemma/docs/core/model_card_4)는 system 역할과 temperature=1.0, top_p=0.95, top_k=64를 안내한다. 이전 Gemma용 system 미지원 지침을 이 모델에 적용하지 않는다. 현 측정의 greedy 설정은 그대로 보존했으며 권장 샘플러와의 한국어 품질 비교는 아직 하지 않았다. 일반 다국어 지원이나 권장 설정만으로 이 앱의 조사·어미·문맥 품질을 통과 처리하지 않는다.

### 자동 UI 통합 검증 범위

- `build-coordinator-prepare-x86-02`는 27초·exit 0이며 XML에서 조정기 13개, 정책 8개, 세션 15개, 소유권 10개, 총 46개 테스트의 실패·오류·스킵 0을 확인했다. 이후 추가한 서비스·후보 바 연결은 별도 통합 검증 대상이다.
- `emulator-preparation-cpu2-01`은 실제 CPU 준비/취소 계측 `OK (1 test)`, 14.88초다. 준비 10,336ms, 숨김 후 중지 4,509ms이며 `CANCELLED`, Engine 종료·lease 반납을 확인했다. 실제 IME 숨김이나 오프라인 검증으로 집계하지 않는다.
- 서비스 검토에서 비활성 상태의 반복 refresh/close를 발견해 gate 전이별 일회 무효화로 수정했다. 후보 바 검토에서는 기존 후보 클릭이 자동 생성 문장을 무시 학습으로 넘길 수 있어 legacy identity만 집계하도록 수정했다. Runtime close는 아직 시작하지 않은 준비 작업도 취소한 뒤 native 종료를 기다리도록 수정했다.
- 장기화에 따라 추가 성능 최적화는 보류한다. 다음 검증 범위는 공개 개발 입력 한 개의 실제 패널 opt-in → 자동 수집 → 후보 바 표시 → 터치 → EditText 일치로 동결한다. 새 held-out과 실기기 속도·품질 완료는 별도 미완료 조건으로 유지한다.
- 무선 ADB `100.109.125.97:40049` 재확인은 연결 거부 10061이다. 명령 exit 0을 연결 성공으로 해석하지 않는다. 증거는 `reconnect-40049-current.log`다.
- 자동 UI 연결 후 `build-automatic-ui-x86-01`은 40초·exit 0이며 같은 46개 테스트의 실패·오류·스킵 0, debug APK 빌드와 release Kotlin 컴파일 성공을 확인했다. APK SHA-256은 `3EC2355E45D1D0C1D0D6F3291AA89EE89351DFE0A81021EEEC0D408077669231`이다.
- 이 APK를 전용 AVD `127.0.0.1:5581`에 데이터 보존 설치했다. `install-automatic-ui-x86-01.log`에서 모델 `sdk_gphone64_x86_64`, `Success`, exit 0을 확인했다. 설치 성공은 자동 후보 표시·적용 성공을 뜻하지 않는다.
- 계측 테스트 첫 빌드는 suspend 호출 오류 6개로 실패했다. 테스트 entry의 `runBlocking<Unit>`과 준비·복원 helper의 suspend 전파 후 `build-automatic-ime-test-x86-02`가 47초·exit 0으로 통과했다. 테스트 APK SHA-256은 `FD5471BD4859FE57F459CB988E54AEAB05333C0C68818D51F8667F948EA02575`다. 설치 로그에는 잘못 분리된 ADB 인자 오류와 수정 후 `Success`, exit 0을 함께 보존했다.
- **첫 실제 자동 IME gate는 미통과**다. `emulator-automatic-ime-01/instrumentation.log`는 23.568초, `FAILURES!!!`, 1개 중 1개 실패다. 오류는 `AI writing 버튼과 scroll 가능한 toolbar를 찾지 못했습니다.`이며 패널 opt-in·공개 입력 주입·자동 생성·표시·적용 전에 멈췄다. ADB exit 0은 계측 통과가 아니다.
- 실패 PNG에는 새글 키보드와 접힌 도구 모음이 보인다. 서비스 editor 연결은 확인했으나 접근성에서 도구 모음 준비를 기다리고 펼치는 테스트 경로는 아직 해결하지 못했다. 생성 후보 없음이나 모델 품질 실패로 분류하지 않는다. `finished-10678902.json`에서 status OFF, 원래 IME 복원, native lease 없음, `offlineProof=false`를 확인했다. 이 실패 뒤 실행은 반복하지 않았다.
- 다음 재개 범위는 **도구 모음 접근성 준비·펼치기만 수정하여 같은 공개 입력 한 개 gate를 재실행**하는 것이다. 자동 IME 실제 표시·적용, 조합·무효화 통합 회귀, 새 held-out 12문맥/24투영 평가, 실기기 지연 목표는 모두 미완료다. 성능·UI 기능 확장은 이 gate 뒤로 미룬다.

### Fold6 wireless ADB 첫 자동 추천 통과

위 재개 gate는 다음 실행에서 진행했다. 도구 모음이 실제로 준비될 때까지 기다리는 기존 성공 테스트의 동기화를 복구했다. 이후 드러난 SwitchCompat 초기화 크래시와 clickable=false/스위치 손잡이 미표시를 실제 계측 로그·화면에서 확인하여, IME의 플랫폼 테마에 맞는 Android Switch와 명시적 터치·포커스 속성으로 수정했다. 에뮬레이터 실패 02·03은 보존한다.

- 연결: `100.109.125.97:35137`, `SM-F956N`, API 36, arm64-v8a. `reconnect-35137.log`에서 실제 연결·장치 응답을 확인했다.
- 빌드: `build-automatic-ime-fold6-01.log`, 45초·exit 0. debug 앱·계측 APK 빌드와 release Kotlin 컴파일 통과. 앱 SHA-256 `27BAE98B9B3722A895B7B432CCDA49655E6CE99B37722AD6D78155ADE13B6067`, 계측 APK SHA-256 `2DD10D601877C7E48C3D724748CC2A75D63DA63079EDDB33D6FF6A10A36326DA`.
- 실제 계측: `fold6-automatic-ime-01/instrumentation.log`, `OK (1 test)`, 25.519초. GPU를 사용하며 CPU 전환이나 네트워크 fallback을 하지 않았다.
- 입력 `창문으로 햇빛이 들어와서 ` → 생성 WORD `방`, SENTENCE `방 안이 환해졌다.`. 화면에 두 후보가 나타난 뒤에만 서비스 getter를 identity 확인용으로 호출했다. 테스트의 직접 생성 호출로 후보를 만드는 경로는 사용하지 않았다.
- 문장 후보 터치 후 EditText가 `창문으로 햇빛이 들어와서 방 안이 환해졌다.`와 정확히 일치했다. 이전 후보의 재적용은 `EditorChanged`였고 문자는 바뀌지 않았다. 단어 후보는 표시만 확인했으며 별도 터치 적용은 이번 실행 범위가 아니다.
- 첫 표시 18,795ms, 입력 commit 9ms, 문장 터치부터 적용 확인까지 48ms. 첫 표시에는 초기 모델 준비가 포함되어 있으며 세부 native timing과 warm p50/p95는 이 테스트에서 측정하지 않았다. 매우 빠른 추천 완료로 표시하지 않는다.
- `before-candidate-118714774.png`와 `after-apply-118715000.png`를 직접 확인했다. 두 후보의 터치 영역 높이는 각각 108px였다. 조합 중 한글 타이핑이 아닌 공개 문자열의 IME commit 경로이며, 한글 조합 보존 검증으로 확대하지 않는다.
- 이 한 문장의 의미·조사·어미·띄어쓰기는 직접 읽어 자연스럽다고 판단했다. 개발 표본 한 개의 판단이며, frozen held-out 12문맥/24투영의 언어 품질 점수로 세지 않는다.
- 실제 패널 opt-out 후 native lease 없음과 원래 IME·공개 준비 설정 복원을 확인했다. 무선 연결을 유지했으므로 `offlineProof=false`다. 공개 JSON과 PNG만 수집했다.

사용자의 마무리 요청에 따라 추가 실행은 여기서 끝낸다. 전체 목표는 미완료다. 남은 항목은 warm 실제 입력 지연 최적화·측정, frozen held-out 의미/문법 평가, 단어 터치·조합·앱/필드/커서/개인정보·오프라인 자동 경로 회귀다. 기존 dirty 변경과 실패·성공 증거를 보존하고 커밋·push는 하지 않았다.

### 2026-09-14 연속 추천을 막던 종료·취소 경로 재설계

폴드6 통합 빌드 E2E가 웜업 단계에서 실패했다. 로그에서 `startAutomaticSuggestionWarmupIfAllowed`가 세 번 시작돼 각각 356ms·44ms·61ms 만에 취소됐다. 원인을 추적해 다음을 확정했다.

- 게이트 재검사 경로(`getAutomaticSuggestionCandidates`, 웜업 게이트 불통과, 스냅샷 무효화 콜백)가 매번 조정기 `invalidate()`를 호출했고, 이 함수는 항상 `backend.close()`를 예약해 웜 Engine을 닫았다. 옵트인 전에는 "부적격" 판정이라 스냅샷 무효화가 올 때마다 웜업 job까지 취소됐다.
- Engine은 취소된 요청을 Engine 폐기로 처리한다(위 "취소·실행 실패 시 Engine도 폐기"). 조정기가 새 관측마다 `backend.cancel()`을 호출했으므로, 생성 중 한 글자만 더 쳐도 다음 요청이 20초대 재준비를 치렀다. 문서 87행의 "타이핑 취소는 추론만 취소한다"는 의도와 구현이 어긋나 있었다.
- 에디터 재접속(`onFinishInputView(finishingInput=true)` → 4ms 뒤 `onStartInput`)마다 하드 종료가 실행돼 같은 앱 안에서 필드를 옮기기만 해도 Engine이 닫혔다.

확정 설계와 구현:

1. `OnDeviceSuggestionCoordinator.invalidate(closeBackend)`로 소프트·하드 무효화를 분리했다. 소프트는 epoch 증가·세션·후보만 비우고 진행 중 job과 backend를 건드리지 않는다. 하드(`setEnabled(false)`, 입력 불일치, 서비스 파기)만 `runningJob.cancel()`·`backend.cancel()`·`close()`를 수행한다.
2. 새 관측은 진행 중 요청을 취소하지 않는다. 새 job은 이전 job 완료를 `join`으로 기다린 뒤 디바운스·생성하고, 이전 결과는 epoch 불일치로 폐기된다. 타이핑 중 Engine 폐기가 사라진다.
3. 서비스의 게이트 무효화는 소프트 경로에서 웜업 job과 웜업 UI 상태를 건드리지 않는다. 조기 반환 가드는 `closeBackend`일 때만 런타임·웜업 상태를 본다(소프트 경로의 무한 갱신 루프 방지).
4. 키보드 숨김(`onFinishInputView`, `onFinishInput`)은 소프트 무효화 뒤 2초(`AUTOMATIC_SUGGESTION_HIDE_GRACE_MS`) 후에 하드 종료와 `OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)`를 실행한다. 유예 안에 `onStartInputView`가 오면 예약을 취소해 Engine과 웜업을 유지한다. `onDestroy`는 즉시 하드 종료한다. 세션 텍스트는 숨김 즉시 비워지므로 개인정보 경계는 그대로다.
5. `OnDeviceSuggestionPolicy.parseSuffix`는 모델이 입력 원문 전체나 마지막 어절(최대 4개, 2자 이상)을 되풀이한 뒤 이어쓴 경우 되풀이 부분을 벗겨 이어쓰기만 남긴다. 되풀이만 있는 응답은 그대로 거부한다. 문맥이 공백으로 끝나면 남은 부분의 앞 공백을 제거한다.
6. Engine `MAX_RESPONSE_CHARS`를 120에서 400으로 올렸다. 되풀이가 포함된 응답이 `OUTPUT_TOO_LONG`으로 Engine 폐기까지 이어지지 않게 하기 위해서다. 정책의 120자 상한은 되풀이 제거 뒤 적용된다.

단위 테스트: `input.ai.ondevice.*` 전부 통과(조정기 16개, 정책 11개 포함). `input.ai.*`+`input.candidates.*` 801개 중 실패는 기존 `TypingDnaStatsTest` 1건뿐이다.

추가 확정(같은 날 오후, 폴드6·A35 실측 뒤):

7. 폴드6 GPU 웜업 12.3초, A35 GPU 웜업 19.6~21.4초, 웜 상태 생성 1.1~1.6초(폴드6)·4.8~5.6초(A35). 고정 공개 입력 6개의 응답은 모두 10~28자 단일 문장이었다(`GemmaSuggestionEngineDeviceTest`).
8. 자동 추천 옵트인 기본값을 ON으로 바꿨다(`automatic_ondevice_suggestions_opt_in` 기본 true). 키보드를 열면 조건이 맞는 필드에서 자동으로 웜업이 시작된다. E2E는 시작 시 false로 바꾸고 끝날 때 이전 값을 복원한다.
9. Engine `IDLE_CLOSE_MS`를 30초에서 10분으로 올렸다. 30초는 사용자가 읽는 동안 웜 Engine을 닫아 다음 입력에서 20초 재준비를 치르게 했다. 키보드 숨김 2초 유예 뒤 하드 종료와 자원 감시가 메모리 해제를 맡는다.
10. 웜업이 `BUSY`(다른 목적의 lease 보유)로 실패하면 키보드가 활성인 동안 0.5초 간격으로 최대 20회 재시도한다. `OnDeviceGenerationControl`은 lease 시작·거부·종료를 목적과 함께 로그한다(텍스트 없음).
11. 후보 칩(`CandidateItemUi`)은 AI 배지 후보에 `"<텍스트> <출처>"` contentDescription을 붙인다. 접근성용이며, E2E도 이 라벨로 자동 후보를 찾는다(어제 배지를 아이콘으로 바꾸면서 텍스트 기반 검출이 깨져 있었고, 후보는 실제로 30초 동안 READY 상태로 표시되고 있었다).
12. 조정기는 상태 전이마다 epoch와 함께 로그하고, 생성 완료 시 경과·응답 길이·현재성·파싱 성공 여부를, 파싱 실패 시 문장·단어 모드의 거부 사유 코드를 로그한다. 프롬프트·응답 텍스트는 어떤 로그에도 남기지 않는다.

같은 날 저녁 결과:

13. 스냅샷 무효화 콜백(`onAutomaticSuggestionInvalidated`)의 `runtime.cancel()`을 제거했다. 진행 중 생성을 네이티브로 취소하면 Engine이 폐기돼 다음 요청이 재준비를 치렀다(A35에서 첫 후보 19.4초). 제거 후 첫 후보는 커밋 뒤 약 9초(stale 생성 5.2초 + 재생성 3.6초)다.
14. 자원 게이트 완화: 발열은 `THERMAL_STATUS_SEVERE` 이상만 차단, 절전 모드는 충전 중이면 통과, 감시 루프는 0.5초 간격으로 3초 이상 지속 위반일 때만 Engine을 닫고, 생성 완료 직후의 자원 재검사는 제거했다. 명시 문맥 완성 런타임도 같은 규칙이다.
15. 끄기(`setAutomaticSuggestionsEnabled(false)`)는 옵트인이 켜진 적 없어도 웜업으로 살아 있는 Engine을 하드 종료해 lease를 반납한다. 웜업 스피너는 옵트인과 무관하게 Preparing 동안 표시하고, 차단 아이콘(툴바, 탭하면 사유 다이얼로그)은 옵트인이 켜졌을 때만 표시한다.
16. 설정 앱 "개인정보·AI → 언어 금고"에 자동 추천 스위치, 온보딩에 "AI 문장 추천" 단계(모델 준비 버튼 + ON/OFF 스위치, 선택 단계, release 제외)를 추가했다.
17. 2행 후보 바는 높이를 `HEIGHT*2+1`로 고정하고, 단어 행이 비면 "추천 단어 없음", 문장 행이 비면 상태 행 → 연결 힌트 → "추천 문장 없음 · 계속 쓰면 개인화가 쌓여요" 순으로 안내 칩을 채운다.
18. A35 `GemmaAutomaticImeDeviceTest` 통과: 06:01 빌드로 `OK (1 test)`, 41.7초. 웜업 21.1초, 후보 생성·표시·문장 터치 적용·재적용 거부·옵트아웃 후 lease 해제 확인. 같은 APK를 z-fold6에 Taildrop으로 전달했다.
19. 남은 개선: 프로그램적 커밋 직후 revision만 바뀐 동일 텍스트 관측이 한 번 더 들어와 첫 생성이 stale 처리된다(약 5초 손실). 동일 텍스트·선택이면 관측을 같은 것으로 보는 완화는 ABA 가드와 상충하므로 별도 판단이 필요하다.

2026-09-14 저녁 추가(에뮬레이터 전환 뒤):

20. 19항 해소: `OnDeviceSuggestionCoordinator.observe()`는 scope·`textBeforeCursor`·선택 범위가 직전 관측과 같고 revision만 다른 관측을 "같은 관측"으로 보고, 진행 중 job이나 READY 후보가 있으면 epoch를 올리지 않고 무시한다(`observation=duplicate` 로그). 결과 현재성 검사(`isRequestCurrent`/`publishCached`)는 job 시작 시 고정한 스냅샷 대신 조정기의 최신 관측 스냅샷을 서비스 `isCurrent`에 넘긴다(epoch가 같으면 위치 동일이 보장된다). 조정기 테스트 20개(신규 4) 통과.
21. 개발 기기를 Android 에뮬레이터(`SaegulGemma_Isolated_20260912`, x86_64, API 34, 모델 탑재)로 옮겼다. 에뮬레이터에서는 GPU 델리게이트가 `CreateSharedMemoryManager is not implemented`로 실패해 웜업이 0.5초 만에 `ENGINE_INITIALIZATION_FAILED`가 되고, `-e backend cpu` E2E는 문맥 완성 패널의 "CPU 호환 모드" 토글이 스크롤 아래에 있어 찾지 못한다. 후속: GPU 실패 시 CPU 자동 폴백과 useGpu 영속화, 패널 오류 문구, E2E 스크롤.
22. 게이트웨이 이어쓰기 경로 제거(사용자 방향: 추천은 온디바이스 Gemma만): `AiSentenceCompletionPrefetcher`·`PrefetchedContinuation`·`contextualPrefetcherInstance`·`schedulePrefetchOnPredict`·`aiBearerTokenProvider`·후보 바 연결 힌트(`connectionHintResource`, `ai_connection_*` 문자열)·`maybeAutoEnrichGraph`·`graphEnrichAuto`/`GraphEnrichAutoPolicy`를 삭제했다. 이 기능의 기기 테스트 `AutomaticContinuationDeviceTest`·`KananaLiveContinuationDeviceTest`와 프리페처 단위 테스트·`kanana-prefix-wire-replay.json` 픽스처도 함께 삭제했다(RedTeam 테스트 아님). 툴바 AI 버튼의 명시 액션(BYOK/OAuth)은 별개 기능으로 유지. 전체 단위 테스트 1281개 중 실패는 기존 `TypingDnaStatsTest` 1건뿐.
23. GPU→CPU 자동 폴백: `AppPrefs.internal.automaticOnDeviceSuggestionsUseGpu`(기본 true)로 백엔드 선택을 영속화하고, 웜업이 GPU에서 `ENGINE_INITIALIZATION_FAILED`로 끝나면 `OnDeviceBackendFallbackPolicy.shouldFallbackToCpu`(순수 함수, 서비스 인스턴스당 1회) 판정으로 useGpu=false 저장 후 CPU 런타임으로 웜업을 한 번 재시작한다. 문맥 완성 패널은 CPU 호환 모드 설명 아래 "GPU를 쓸 수 없어 CPU 호환 모드로 전환했습니다."를 보인다. E2E는 이미 CPU면 토글 클릭을 건너뛰고, 필요하면 패널을 스크롤해 토글을 찾는다. 반말 문구 "완성할 수 있는 문맥이 없어."는 존댓말로 고쳤다.
24. 에뮬레이터 E2E 통과(2026-09-14 09:39·09:43, 2회 연속 `OK (1 test)`): GPU 델리게이트 실패 0.75초 → gpu→cpu 폴백 → CPU 웜업 10.8~15.4초 → 옵트인 → 생성 약 11초 → READY("방" / "방 안이 환해졌다.") → 문장 탭 95ms → 본문 삽입 확인 → 옵트아웃 후 lease 해제. 중간에 20항의 `observation=duplicate`가 두 번 찍혔고 적용은 21항 회귀 수정(`OnDeviceSuggestionSession.rebase`)으로 정상. E2E 상수는 한국어 리터럴 대신 앱 리소스 조회로 바꿔 로케일 무관하게 했다(후보 바 문자열 ko/en 분리 후 필요).
25. 후보 바 보정: 문장 행의 AI 후보는 pill 칩(`keyBackgroundColor` 배경, `dividerColor` 1dp 테두리, 반경 12dp, 최소 높이 40dp, 좌우 여백 8dp), 상태 행 스피너는 `progressBarStyleSmall` 16dp `candidateLabelColor`. `gemma_*strings.xml`은 `values/` 영어·`values-ko/` 한국어로 분리(배지 분류용 "Gemma" 부분 문자열 유지).
26. 상태 행 스피너: 코드로 만든 `ProgressBar(progressBarStyleSmall)`는 IME Context에서 그려지지 않았다(에뮬레이터 실측). `IndeterminateRingDrawable`(Paint 직접 칠, `scheduleSelf` 회전, 정적 프레임 항상 그림)로 교체해 웜업·생성 상태 행 모두에서 링이 보이는 것을 확인했다(`shouldShowStatusSpinner` 순수 함수 테스트 3개).
27. 에뮬레이터 E2E 전제: `GemmaContextImeDeviceTest`는 기본으로 오프라인을 요구한다(`svc wifi disable`·`svc data disable` 뒤 실행, 끝나면 복구). `runtimeModelAbsentPreflight…`는 모델이 있으면 건너뛴다(assume). 패널 안 버튼은 에뮬레이터 화면에서 스크롤 아래에 있으므로 테스트는 패널 스크롤 헬퍼로 찾는다. 같은 호스트에서 Gradle 빌드가 병행되면 CPU 웜업이 80초·생성 66초까지 늘어난다(정상 11~15초).
28. 에뮬레이터 크래시 원인: `GemmaMaterialGenerator`가 `EngineConfig.cacheDir`에 앱 캐시를 넘겨 XNNPack이 모델 크기(2.5GB)의 가중치 캐시를 쓰다 `/data`(여유 1.4GB)에서 `cannot append buffer to cache file` → `nativeCreateEngine` SIGABRT로 앱이 죽었다(자동 추천 엔진은 `NO_CACHE_DIRECTORY`). 결정: 재료 생성기도 캐시를 끈다. E2E는 시작 시 축적 스케줄러를 정지한다(테스트 사이 두 번째 엔진 생성 방지). 명시 문맥 완성의 `initialization_cancel` 시나리오는 옵트인 기본 ON으로 엔진이 이미 웜 상태라 초기화 단계가 없어 실패했을 가능성이 커 테스트가 콜드 상태를 만들도록 보정한다(조사 중).
29. 제품 결함 발견(28항 조사 중): `OnDeviceGenerationControl.tryBegin`은 활성 lease가 있으면 목적과 무관하게 거부한다. 자동 추천은 웜 상태 동안 `AUTO_CONTEXT` lease를 계속 쥐므로(옵트인 기본 ON이면 키보드가 뜨자마자 웜), 명시 "기기에서 문맥 완성"(`EXPLICIT_CONTEXT`)이 엔진 생성 전에 즉시 실패해 초기화 단계조차 보이지 않는다. 결정: **lease 선점**. EXPLICIT 요청 시 AUTO가 생성 중이 아니면 서비스 콜백으로 자동 엔진을 하드 종료·반납하고 부여한다(생성 중이면 `BUSY_GENERATING` 거부). 명시 완성 뒤에는 기존 게이트로 자동 웜업이 재개된다(재웜업 비용 11~20초 감수). 웜 엔진을 두 목적이 공유하는 구조는 후속 설계. 재료 생성기(`GemmaMaterialGenerator`)는 `NO_CACHE_DIRECTORY`로 통일했고, E2E는 `GemmaAccumulationScheduler.pauseForInstrumentation()`으로 축적을 멈춘다.


20. 키보드 숨김 뒤 Engine 유지 시간(`AUTOMATIC_SUGGESTION_HIDE_GRACE_MS`)을 2초에서 10분으로 늘렸다. 사용자가 앱을 오갈 때마다 12~20초 재로딩이 반복됐기 때문이다. 숨김 즉시 세션 텍스트는 비우고, 모델 가중치만 최대 10분(또는 Engine 유휴 10분) 동안 남긴다. 그동안 `OnDeviceGenerationControl`의 keyboardActive가 유지되므로 배경 재료 생성은 KEYBOARD_ACTIVE 대기 후 재시도한다. 메모리 부족·발열 심함은 3초 지속 시 감시 루프가 Engine을 닫는다. 서비스 파기(`onDestroy`)와 끄기는 즉시 닫는다.
21. 폴드6(무선 ADB, Tailscale)에서도 최종 빌드(11:39, 숨김 유지 10분 포함)로 `GemmaAutomaticImeDeviceTest` 통과: `OK (1 test)`, 20.6초. 웜업 13.4초, 웜 생성 1.66초, 후보 표시·문장 터치 적용·재적용 거부·옵트아웃 후 lease 해제 확인. 첫 실행은 기기에 남아 있던 수동 공개 준비 요청 플래그(`gemma_accumulation_state.manual_requested`) 때문에 테스트 전제에서 멈췄고, 플래그를 지운 뒤 통과했다.
22. 문장 후보가 떠 있는 동안 재생성이 돌 때(DEBOUNCING/GENERATING) 문장 행 오른쪽 끝에 16dp 스피너를 표시한다(상태 행 스피너가 보일 때는 중복하지 않음). 이 빌드(13:34)로 폴드6 무선 E2E 재통과: `OK (1 test)`, 23.9초, 웜 생성 1.6초.

2026-09-14 밤 추가(배경 재료 생성 기아 수정):

30. **제품 결함**: 배경 재료 생성(`PUBLIC_MATERIAL`)이 사실상 영구 기아 상태였다. 20항에서 키보드 숨김 뒤 Engine 유지 시간을 10분으로 늘리면서 `OnDeviceGenerationControl.keyboardActive`를 그 10분 동안 유지했는데, 재료 생성의 중지 경계가 바로 그 `keyboardActive`를 읽고 있었다. `keyboardActive`가 false가 되는 경로는 서비스의 10분 유예 러너블과 `onDestroy` 둘뿐이고, 그 러너블은 `onStartInputView`마다 취소·재예약되므로 **10분 안에 키보드를 한 번이라도 다시 열면 영원히 false가 되지 않는다**. `GemmaGenerationEligibility.evaluate`가 `KEYBOARD_ACTIVE`를 돌려주고 `GemmaGenerationWorker`가 `store.recordBlocked("키보드 사용 중에는 생성하지 않습니다.")`를 남긴다(실기기 잔존 상태와 일치). 재료 은행이 비어 후보 바에 "기기 AI 재료" 문장이 뜨지 않는다.

    결정: **경계 신호를 분리한다.** `keyboardActive`는 자동 추천 전용(웜 유지 유예 포함)으로 남기고, 재료 생성의 중지 경계는 새 신호 `inputViewVisible`로 옮긴다. `inputViewVisible`은 `onStartInputView`에서 true, `onFinishInputView`·`onFinishInput`·`onDestroy`에서 **즉시** false다. 즉 경계는 "키보드가 지금 화면에 있는 동안"이고, 재웜 유예는 자동 추천 엔진의 수명 정책일 뿐 재료 생성의 경계가 아니다.

    함께 확정한 것:
    - `tryBegin(PUBLIC_MATERIAL)`의 허용 조건은 `!inputViewVisible`이다. `keyboardActive`를 함께 보지 않는다(보면 결함이 그대로 재발한다).
    - 입력 뷰가 화면에 나타나는 상승 에지에서 진행 중인 `PUBLIC_MATERIAL` lease를 취소한다. 키보드가 유예 중에 다시 열리는 경우 `keyboardActive`의 상승 에지가 발생하지 않으므로, `onKeyboardVisibilityChanged`의 `PUBLIC_MATERIAL` 분기는 제거하고 새 신호 쪽으로 옮긴다(`onKeyboardVisibilityChanged`는 자동 추천 게이트만 담당한다).
    - 웜 상태의 `AUTO_CONTEXT` 보유자도 선점 대상에 넣는다(29항의 선점 메커니즘 재사용). 29항은 `EXPLICIT_CONTEXT`만 선점했는데, 웜 엔진은 키보드를 숨긴 뒤에도 최대 10분 lease를 쥐고 있으므로(Engine `IDLE_CLOSE_MS`) 이 규칙이 없으면 경계를 고쳐도 재료 생성은 `BUSY`로 계속 막힌다. 생성 중(`isBusy`)이면 종전대로 `BUSY_GENERATING` 거부하고 다음 주기에 재시도한다.
    - `GemmaGenerationSnapshot.keyboardActive`는 `inputViewVisible`로 교체한다(대기 사유 `KEYBOARD_ACTIVE`와 문구는 그대로 둔다 — 화면에 키보드가 있으면 "키보드 사용 중"이 맞다).

    부수 관찰(이번 범위 아님): 자동 추천 웜업의 `BUSY` 재시도 예산은 0.5초×20회=10초인데 재료 런타임의 취소 후 네이티브 정지 예산은 `NATIVE_STOP_BUDGET_MS`=30초다. 재료 실행 중에 키보드가 열리면 취소는 걸리지만 반납까지의 간극이 예산을 넘을 수 있다. 실측으로 간극을 확인한 뒤 필요하면 별도 항목으로 다룬다.
