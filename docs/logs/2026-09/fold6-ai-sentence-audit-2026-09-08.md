# Fold6 AI 문장 추천 실기기 감사 (2026-09-08)

## 범위와 기기

유선 기기 `R3CX70NE9VH`(SM-F956N)에서 새글 debug APK의 실제 자동 문장 추천 경로를 확인했다. 기준 app APK SHA-256은 `6F16C6C211D8D11E0A49D40442DDDB14AAA36766363164704DE515C338DF3B70`이며, 이전 기준 APK와 같다.

OAuthPkce provider는 설정되어 있었고, 실제 AI 검증에서 `offlineMode=false`, `networkAllowed=true`, `aiAllowed=true`, `textInspectionAllowed=true`를 확인했다. 자격증명, provider, model은 변경하지 않았다. 별도 문장팩 검증은 offlineMode를 잠시 켜고 finally에서 원래 값으로 복원했다.

검증은 합성 editor 문맥에서 production prefetch cache 도착, 최종 snapshot의 `llm_cached` source와 같은 텍스트, 실제 보이는 chip, chip 클릭 뒤 `ContextualAppend` 계약의 예상 editor 전체 텍스트 일치를 순서대로 확인한다. full screenshot은 저장하지 않고 후보 chip crop만 저장했으며, root가 두 crop을 직접 확인했다.

## 실제 AI 문장 결과

| case | 합성 문맥 | 확인된 suffix | cache 관측 시간 | dispatch 시간 | 결과 |
|---|---|---|---:|---:|---|
| 2 | `오늘 저녁에는 ` | `맛있는 거 먹을래?` | 65,422ms | 48,860ms | OK 1 |
| 3 | `자료를 검토한 뒤 ` | `피드백을 전달해 드리겠습니다.` | 63,632ms | 47,308ms | OK 1 |

두 case 모두 `llm_cached` source, 후보의 실제 화면 표시, 후보 클릭, 예상 전체 editor 텍스트와의 정확한 한 번 삽입을 확인했다. root는 후보 crop을 직접 검토해 두 문장을 각 문맥에서 자연스러운 문장으로 수용했다.

이 결과는 **두 합성 문맥에서 실제 AI 완성 문장이 동작했다는 기능 증거**다. 모든 문맥·모든 앱·일반 문장 품질의 보장은 아니다.

## 지연 판정

자동 추천에는 47~49초 dispatch 시간이 부적합하다. cache 관측 시간 64~65초에는 test 대기와 초기화가 함께 포함되므로 모델 또는 네트워크 지연이라고 단정하지 않는다. cache 관측과 dispatch 사이의 약 16초 원인은 아직 확인하지 못했다.

## 오프라인 문장팩 matrix 관측

초기 Fold6 matrix 26문맥 중 문장팩 후보는 11건에서 표시·클릭·정확한 한 번 삽입을 확인했다. 첫 8개 문맥은 repository lookup 결과가 있어도 sentence snapshot이 비어 있었다.

조사 중 빈 snapshot이 500ms 뒤 동일하다는 이유만으로 안정 완료로 기록되는 테스트 관측 문제가 발견됐다. `SentencePackMatrixDeviceTest`의 안정 반환 조건에 문장 후보가 하나 이상 있어야 한다는 조건만 추가했다. 이제 빈 snapshot은 기존 2초 deadline까지 관측하고 `stable=false`로 남긴다. 이는 엔진 완료 판단이나 제공률 개선이 아니며, timeout과 production 동작은 바꾸지 않았다.

matrix r2는 79.638초 후 `FAILURES!!! Tests run: 1, Failures: 1`로 끝났다. case 11·12·14·20·25·26에서 후보가 보였지만 접근성 `ACTION_CLICK`이 false를 반환했다. 이 실패는 삽입 후 문자열 불일치와 구분한다. 접근성 노드 갱신과 실제 후보 클릭 경로를 추가로 분리 검증해야 하며 앱 결함으로 아직 확정하지 않는다. 로그는 `fold6-matrix-observation-r2.log`다. 일부 관측은 목표 2초 deadline보다 길었으므로 실제 대기 상한을 보장했다고 주장하지 않는다. 기존 테스트 클릭으로 개인 학습 상태가 달라졌을 수 있어 첫 실행과 동일 조건의 개선율로 비교하지 않는다.

## 수용한 검증 변경과 증거

root는 다음 두 test-only 변경의 diff와 AndroidTest build 로그를 직접 검토해 수용했다.

- `AutomaticContinuationDeviceTest`: live cache 시간·source·append 삽입 계약·visible click·crop 증거를 기록한다.
- `SentencePackMatrixDeviceTest`: 빈 snapshot의 조기 안정 완료 기록을 막는다.

관련 증거는 `.artifacts/sentence-pack-20260908/`의 `fold6-live-case2.log`, `fold6-live-case3.log`, 각 후보 crop PNG, `fold6-live-dispatch-times.log`, `fold6-matrix.log`, `fold6-summary.json`이다. 이번 검증 중 production 코드 수정, 커밋, push는 없었다.

초기화가 Main thread에서 실행된다는 탐색 주장은 test가 해당 코드를 main thread 밖에서 실행한다는 사실 때문에 반려·정정했다.

## 남은 항목

- AI provider 응답 지연의 원인과 자동 추천에 맞는 응답 시간 확보
- cold predictor의 실제 기기 관측
- 반복 문장팩 검증의 접근성 클릭 실패 원인 분리 및 재현
- 문맥 범위가 바뀔 때의 cache·dispatch 관계 확인
- 빈 개인 금고와 기본 문장팩만 설치한 조건의 추천 제공률 확인
