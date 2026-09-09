# 1초 추천 성능 변경 계약

## 목표와 기준

입력 문맥을 입력창에 반영한 시점부터 실제 후보가 보이기까지 1,000ms 이내를 목표로 한다. 로컬 문장팩·이미 도착한 AI 캐시·새 AI 생성의 성능은 별도로 판정한다. 적합한 문장이 없는 입력에 무관한 문장을 만들어 제공률을 높이지 않는다. 실제 AI 공급자의 기존 47~49초 응답은 미해결이며 로컬 결과로 이를 통과 처리하지 않는다.

## 변경 전 근거

- `FcitxInputMethodService.getRawContextualPredictions`: 비동기 전체 예측이 끝날 때까지 최초 결과가 빈 목록이다.
- `AiContextualPredictor.predict`: 개인 기록·교정·RAG 조회를 거친 뒤 읽기 전용 문장팩과 AI 캐시를 조회한다.
- `contextualPredictor` 생성이 개인 저장소 초기화와 결합되어 있고, prefetch 생성 및 완료 콜백도 이 객체에 의존한다.
- Fold6 최초 및 재검증에서 준비된 팩 lookup이 있어도 초반 문맥의 최종 후보가 비었다. cold 초기화의 정확한 시간 비중은 미확정이다.

## 설계와 불변량

메모리에 준비된 문장팩과 AI 캐시의 후보 변환을 하나의 독립된 collector로 추출한다. 이 collector는 디스크·네트워크·개인 저장소를 사용하지 않는다. 기존 predictor도 같은 변환을 사용한다. IME는 개인정보·선택·입력필드 검사를 모두 통과한 뒤 이 결과를 먼저 캐시에 게시하고 기존 무거운 전체 예측을 백그라운드에서 계속한다. 완료 결과의 순위 결정은 기존 predictor가 유지한다.

prefetcher 수명은 전체 predictor 초기화와 분리한다. IME는 입력별 prefetch 예약의 단일 소유자가 되고, 완료 콜백도 전체 predictor 초기화를 요구하지 않는다. 독립 predictor 호출자는 기존 예약 기본 동작을 유지한다.

동일 텍스트가 팩과 실제 AI 캐시에 모두 있으면 더 높은 기존 신뢰도인 AI 캐시의 source를 유지한다. 즉시 후보에 개인화 순위를 새로 합성하지 않으며, 전체 예측이 돌아온 뒤의 개인화 순위는 기존 reranker가 결정한다.

source, badge, 원문 공백, ATTACH/NEXT_WORD, stale 입력 방지, 개인 데이터 보존, 앱·입력 세션·epoch·팩 revision 캐시 경계, 네트워크 차단과 OAuth 오류 안내를 보존한다. 모델·공급자·인증·다운로드 동의는 변경하지 않는다. 초기 표시 시점이 달라지므로 순수 구조 정리만이 아닌 명시적인 동작 변경이다.

## 패치 경계와 순서

| 그룹 | 파일 경계 | 순서·위험 |
|---|---|---|
| P1 | `input/ai` collector, predictor, 해당 단위 테스트 | 후보 변환을 한 곳으로 이동, 기존 source/append 보존 |
| P2 | `FcitxInputMethodService.kt` | P1 계약에 맞춰 선게시·예약 소유권 연결, stale 비동기 게시 방어 |
| P3 | AndroidTest 성능 증거 | P1/P2 통합 후 빌드, Fold6에서 실제 보이는 후보와 입력 시간을 관측 |

P1/P2는 파일이 겹치지 않는다. 기존 무관한 dirty 변경과 서브모듈은 보존한다. 문제가 생기면 이번 선게시 경로만 되돌릴 수 있도록 한정한다.

## 검증 게이트

collector의 문맥·캐시 scope·ATTACH·빈결과·source/순서 및 기존 predictor 단위 테스트를 실행한다. 앱·테스트 APK 빌드 후 Fold6에서 AI 네트워크를 일시 차단한 합성 입력의 실제 첫 후보 표시 시간을 기록하고 원래 설정을 복원한다. 1초를 넘기면 실패로 남긴다. cold/warm, 준비 전/후, 개인 학습 상태를 구분한다. 실제 AI 생성의 1초 달성은 별도 실제 공급자 검증 없이는 주장하지 않는다.

절차 기준: `C:/Users/encep/.agents/skills/refactor-governance/SKILL.md`.

## 1차 구현 검증

P1/P2 코드와 P3 실기기 검증을 루트가 직접 검토했다. wire 테스트가 실제 탭 대신 문자 `\\t`를 넣은 문제, 각 입력 사이 문맥 초기화 누락, Kotlin nullable smart-cast 문제를 반려 후 수정했다. UI와 동일한 문장 후보 2개 범위로 검증한다.

관련 단위 테스트는 56건 수집, 55건 통과, 기존 `AiContextualPredictorPersonalNgramTest`의 `@Ignore` 1건, 실패·오류 0건이다. 원문은 `.artifacts/sentence-pack-20260908/one-second-unit.log`에 있다. 통합 빌드 성공 후 app SHA-256 `A6A61D468E862B1ADBB025CBC2BD058D0733F332ADD2FCC62C3797DB0B50F050`를 유선 Fold6에 업데이트 설치했다.

1차 기기 검증은 준비된 기본216/선택팩이 있는 기존 사용자 기기에서 수행했다. 첫 문맥은 실패했고, 이어지는 세 문맥은 접근성 후보 노드가 각각 59ms·112ms·188ms에 관측됐다. 그중 뒤의 두 문맥은 접근성 `ACTION_CLICK`이 false여서 전체 검증은 `FAILURES!!!`다. `.artifacts/sentence-pack-20260908/one-second-fold6.log`를 보존한다. 후속 검사에서 접근성 좌표의 crop이 비어 있어 이 시간을 실제 렌더 완료 시간으로 인정하지 않는다. 첫 입력·일반 제공률·실제 AI 생성 1초 달성을 뜻하지 않는다.

후속 검증은 첫 실패의 단계와 source 개수만 기록하고 개인 후보 내용은 기록하지 않는다. 접근성 `ACTION_CLICK`과 실제 MotionEvent 터치는 명시적으로 다른 모드로 검증하며, 실패 후 다른 방식으로 자동 재시도하여 성공 처리하지 않는다. 앞선 실패 로그를 그대로 보존한다.

## P4: 표시 통계의 메인 스레드 파일 읽기 제거

후보 표시 callback은 `recordContextualCandidateShown`에서 `predictionMetricsStore`를 처음 접근할 수 있으며, 생성자의 파일 읽기·복호화·파싱이 main에서 수행되는 구조다. 초기 timeout 진단에서는 raw 문맥이 정확하고 캐시에 문장팩 후보8개가 있었다. 이때 snapshot 관측 전 대기에서 deadline이 소진됐을 가능성을 별도 계측한다. 통계 초기화가 지연 전체의 확정 원인이라고 주장하지 않는다.

P4는 서비스의 shown/accepted 기록 두 함수만 바꾼다. 중복·현재 generation·commit 성공 판단은 main에 유지하고 실제 metrics store 접근은 기존 applicationScope(IO)로 넘긴다. update가 끝난 뒤 main에서 기존 저장 예약을 호출한다. source/count 의미, 개인 기록, 저장 파일 형식은 유지한다. P3에 입력 반영 및 커서 준비 완료 시각과 snapshot poll 횟수를 추가해 초기 지연 구간을 구분한다. P1/P2와 같은 서비스 파일이므로 순차 적용한다.

P4 metrics 단위 테스트 14건은 실패·스킵 없이 통과했고 앱·계측 APK 빌드도 성공했다. 앱 SHA-256은 `9886D68D97E62845A13472896A0D9C60796DF5DADF03B30A8C9C16E407CED8B6`이며 Fold6에 업데이트했다. 첫 입력의 editor 준비37ms, service selection54ms, snapshot55ms를 확인했다. 후속 4문맥에서도 snapshot47/51/41/69ms였지만, 실제 터치 입력은 2/4만 성공해 전체 검증은 실패다. 원문은 `one-second-fold6-metrics-first.log`, `one-second-fold6-metrics-matrix.log`다.

## 화면 좌표 검증 보강

접근성 노드 관측195ms에 캡처한 후보 crop을 루트가 열었으나 단색 빈 영역이었다. 접근성 `isVisibleToUser`만으로 실제 표시를 인정했던 계측을 반려했다. 생산 UI 변경 전에 계측에서 노드 refresh, 동일 suffix의 실제 TextView 및 clickable ancestor 좌표, layout 완료와 표시 상태를 함께 확인한다. 기존 1초·3초 기준은 유지하고, 접근성 최초 관측과 실제 View 일치 시각을 분리한다. 좌표가 다르면 다른 좌표로 자동 보정해서 터치 성공 처리하지 않는다. 합성 입력의 후보 영역만 캡처하고 이전 실패 원문을 보존한다.

## 실제 AI 회귀 확인

P4 앱에서 선택된 공급자·모델을 그대로 사용한 실제 요청 1건은 `OK (1 test)`였다. 합성 입력 `오늘 저녁에는 `에 `맛있는 거 먹자.`가 도착했고, production source `llm_cached`, 실제 화면 crop 및 선택 후 정확한 입력까지 루트가 확인했다. 입력 시작부터 캐시 결과 관측까지 46,085ms이며 순수 공급자 처리 시간과 동일한 수치가 아니다. `one-second-live-ai-regression.log`, `one-second-live-ai-chip.png`를 보존한다. 새 AI 생성의 1초 목표는 여전히 미달이다.

## P5: 문장 저장 중 입력 잠금 제거

geometry 첫 실기기 실행은 10초 MotionEvent ANR로 종료됐으며 해당 실행의 스택은 아직 확보하지 못했다. 별도로 같은 기기의 오늘16:46:59 기록을 확인했다. main은 `ReinforcementTracker.onCandidateSelected → PersonalizedSentenceStore.get`에서 Store monitor를 기다렸고, 소유 thread50은 `PersonalizedSentenceStore.save → VaultFile.writeText → AndroidKeyStoreCipher`에 있었다. 이 과거 근거를 최신 ANR의 동일 원인으로 단정하지 않는다.

P5는 `PersonalizedSentenceStore.save()`의 잠금 범위만 줄인다. 별도 save 직렬화 잠금 안에서 records monitor를 짧게 잡아 record와 keywords를 복사한 뒤, records monitor를 해제하고 기존 JSON·Vault 암호화·파일 쓰기를 수행한다. 동시 save는 순서대로 snapshot을 얻고 쓰도록 유지한다. get/upsert/query는 암호화 완료를 기다리지 않는다. load·파일 형식·암호화·데이터 삭제·강화 수식은 변경하지 않는다. 지연 cipher를 사용하는 결정적 동시성 테스트로 저장 중 get/upsert 완료와 후속 save의 최신 데이터 보존을 검증한다. 타임아웃을 늘리거나 저장 오류를 삼키지 않는다.

## P6: 후보 선택 후 학습을 입력 처리와 분리

최초 개인 저장소 lazy load 자체도 후보 선택의 `reinforcementTracker` 접근을 통해 main에서 기다릴 수 있다. 따라서 저장 잠금 축소와 별도로 실제 editor commit, selection, 즉시 predictionEpoch 갱신과 성공 판정은 main에 유지하고, 성공 후 tracker 선택 기록 및 개인 n-gram 강화만 기존 applicationScope(IO)에서 수행한다. 무시·거절 이벤트도 같은 서비스 진입점으로 보낸다. 서비스는 이전 feedback Job을 join하는 순서 연결로 세 이벤트의 순서를 유지하며, 실패를 삼키거나 이벤트를 버리지 않는다. 문자열·package·문맥은 main에서 캡처한다. 학습 완료 후 main에서 epoch·저장 예약을 갱신하고 살아 있는 입력창만 다시 표시한다. 앱에 반영된 입력의 완료를 학습 완료까지 지연시키지 않는다. tracker의 점수 수식과 저장 형식은 그대로다.

서비스가 이미 DESTROYED이면 취소된 lifecycleScope에 저장을 예약하지 않는다. 해당 applicationScope IO 작업에서 personalizedStore와 personalNgramModel 저장을 직접 마쳐 이미 성공한 입력의 학습을 보존한다. 살아 있는 서비스의 기존 저장 예약은 유지한다.

## P7: 실제 터치 ANR의 문장 저장 경로 제거

이벤트 직후 자동 JDWP 수집으로 최신 앱의 원인 스택을 확보했다. pre-touch 관측4,130ms, debugger 시작4,456ms, detach6,214ms에 main은 `commitConfirmedContextualAppend → observeCommittedEditorText → UserTypingContextCollector.emitSentence → TypingDnaVault.recordSentence → persistStaging → AndroidKeyStoreCipher.engineInit`에 있었다. 이 실행은 디버거로 중단했으므로 성능 판정에서 제외한다. 스택은 `one-second-timed-jvm-stacks.log`, 시각은 `one-second-timed-stack-events.log`에 있다.

collector의 순수 메모리 문장 경계·삭제·flush 동작은 main에 유지한다. `onSentenceCommitted`의 vault 기록, n-gram 학습, 학습 통계, 개인 문장 기록, 오타 어휘 갱신만 P6와 같은 순서 큐로 옮긴다. 큐 이름은 개인 학습 작업을 나타내도록 정리한다. 선택된 완성 문장의 기록이 선택 강화보다 먼저 처리되는 기존 순서를 보존한다. 완료 후 main에서 epoch·저장 예약·후보 갱신과 기존 자동 강화 조건 검사를 수행한다. 이미 종료된 서비스에서는 RAG vault·metrics 저장까지 IO에서 마친다.

수동 금고 분석의 `triggerInstantTypingDnaSyncAsync`는 main에서 collector flush 후 큐의 현재 Job을 캡처하고, 이를 suspend join한 다음 IO 동기화를 수행한다. pending 문장이 저장되기 전에 분석이 실행되는 회귀를 막는다. 호출자가 없는 동기식 `triggerInstantTypingDnaSync`는 저장소 전체 참조 확인 후 제거한다. 암호화 방식·파일 형식·수집 동의·네트워크 게이트는 그대로 유지한다.

## 최종 기기 검증과 남은 범위

P7 단위 테스트는 `TypingDnaVaultTest` 8건, `UserTypingContextCollectorTest` 16건 모두 통과했다. 지정한 `TypingDnaCommitSinkTest` 이름의 클래스는 없어 수집되지 않았으며 통과 수에 포함하지 않는다. P5의 저장 경합·Tracker 테스트9건, P6 통합의 append·Tracker 테스트8건도 통과했다. 각 원문과 XML은 단계별 아티팩트에 보존했다. 루트는 코드·XML·빌드 결과를 직접 확인해 수용했고, 좌표계와 서비스 종료 후 저장 누락은 반려 후 수정했다.

최종 app SHA-256은 `9886EF3B3C340328268166749EE8EEF29408B7F369B7F91C835D6DD2AFB25A6B`, test APK는 `6F0E7B2FBD94E8D52BF76D467C2524FD2DFEBF5CEC8BD6700DF3D4CF1D8471AB`다. JDK17 빌드41초 성공 후 유선 Fold6 `R3CX70NE9VH`의 debug 앱에 업데이트했다. 커밋·push·모델 변경은 하지 않았다.

디버거 없이 첫 입력1건은 실제 View/접근성 좌표가 일치한 후보 표시433ms, 터치 후 정확한 입력까지 `OK (1 test)`였다. 이어 네 문맥의 검증도 `OK (1 test)`이며 결과는 아래와 같다. 루트가 네 TextView crop을 각각 열어 실제 한글 표시를 확인했다.

| 합성 입력 | 표시된 이어쓰기 | 후보 표시 | 터치 후 정확한 입력 |
|---|---|---:|---|
| 회의 자료를 | 검토한 뒤 의견을 드리겠습니다. | 407ms | 통과 |
| 오늘 저녁 | 뭐 먹을까 | 352ms | 통과 |
| 회의가 끝나 | 면 결정된 사항을 알려주세요. | 312ms | 통과, 붙여쓰기 |
| 약속을 | 다음으로 미뤄도 될까요? | 374ms | 통과 |

원문은 `one-second-fold6-p7-first.log`, `one-second-fold6-p7-matrix.log`이며 `one-second-p7-case-1.png`부터 `case-4.png`까지 후보 crop이 있다. 두 실행 모두 `restoredOfflineMode=false`를 확인했다. ANR로 중단됐던 임시 오프라인 설정을 명시적으로 원래 값으로 복구했으며 production 앱 설정은 바꾸지 않았다. 이후 계측은 IME decor 전체를 캡처하지 않고 합성 suffix TextView만 캡처한다.

**완료 범위:** 기본팩을 준비한 기존 기기의 위 합성 문맥4건에서 로컬 `sentence_pack` 후보의 1초 표시·실제 터치 입력, 해당 경로의 ANR 비재현. 이를 신규 설치·모든 앱·모든 문맥의 보장으로 확대하지 않는다. 과거 실패 로그는 폐기하지 않았다.

**남은 범위:** 새 AI 생성은 선택된 공급자·모델을 유지한 실제 측정에서46.085초였다. 1초 목표 미달이며 로컬 문장팩 성능으로 통과 처리하지 않는다. 새 모델·공급자 변경을 허용할지에 대한 사용자 선택은 아직 받지 않았다. 일반 문장 제공률·자연스러움의 확대 검증과 앱 최초 진입·금고 분석 전체 시간의 1초 보장도 이 결과로 주장하지 않는다.
