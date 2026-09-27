# GPT-6 Astra 전환 계약과 검증

작성일: 2026-09-10. 대상 모델은 정확히 `gpt-6-astra`다.

## 범위와 전환 순서

Android 글쓰기 AI의 Astra 요청 호환성과 컴패니언 Codex 실행 모델 지정을 구현한다. Fast의 기존 모델, Gemma 온디바이스 재료 생성, 음성 전사, OAuth 저장 형식, 한글 조합 엔진은 이번 전환 대상이 아니다. 기존 사용자 프로필의 명시적인 모델 선택을 덮어쓰지 않는다.

기본 모델의 일괄 전환은 실제 API 품질·지연·비용 평가 뒤에 한다. Balanced의 현재 기본값 `gpt-5.6-terra`, Quality의 `gpt-5.6-sol`을 유지하면서 빌드 옵션 `-PsaegeulAiBalancedModel=gpt-6-astra -PsaegeulAiQualityModel=gpt-6-astra` 또는 기존 모델 설정 입력으로 선택 적용한다. 현재 작성·답장·정중한 표현 등의 실제 동작은 Balanced를 사용하므로 Quality만 바꾸면 해당 동작은 Astra로 전환되지 않는다. 액션의 tier 분류는 변경하지 않는다. 빌드 옵션은 새 기본 프로필에만 적용되며 이미 저장된 사용자 프로필을 바꾸지 않는다. OpenAI-compatible/Gemini 프로필의 자체 기본 모델은 보존한다.

## Android 요청 계약

- 정확한 Astra 모델 ID에 Responses `reasoning.effort=low`를 적용한다. 다른 모델의 추론 설정은 보존한다.
- Responses API, `store=false`, `text.format`의 `suggestions` JSON 스키마를 유지한다.
- Astra의 Chat Completions 경로에는 `reasoning_effort=low`, `max_completion_tokens=1024`, `store=false`를 전달하고 `temperature`와 `max_tokens`를 보내지 않는다. 도구 호출은 추가하지 않는다.
- 출력 한도는 1,024토큰, 연결/읽기 제한은 기존 15초/90초다. 한도 초과를 성공 처리하거나 자동으로 요청 예산을 늘리지 않는다.
- Responses의 불완전 응답과 거부, Chat의 `length`/`content_filter`/`refusal`은 정상 제안으로 표시하지 않는다.
- Astra 요청에 공급자가 명시적으로 다른 모델을 반환하면 실패 처리한다. 응답의 모델 필드가 없을 때 요청 모델명을 표시하는 기존 동작은 실제 사용 모델의 증거가 아니다.
- 실패 시 다른 모델이나 공급자로 자동 재요청하지 않는다. 개인정보·오프라인·민감 입력 차단과 기존 결과 적용 검사를 유지한다.

## 컴패니언 계약

프로토콜의 `codex`는 백엔드 식별자이며 OpenAI 모델 ID가 아니다. 기존 manifest의 `codex`/`claude`/`agy`와 tier 매핑을 유지한다.

Codex 실행에는 모델과 추론 강도를 명시적으로 전달한다. 기본 설정은 `gpt-6-astra`/`low`이며 `--codex-model`, `--codex-effort`로 변경할 수 있다. 환경변수는 `FCITX_AI_CODEX_MODEL`, `FCITX_AI_CODEX_EFFORT`다. 명시한 CLI 옵션이 환경변수보다 우선한다. 현재 확인된 Codex 설정 계약에 맞춰 effort는 `low`, `medium`, `high`, `xhigh`를 허용한다. API의 `max`와 Codex 앱의 `ultra`를 자동 치환하지 않는다.

모델 ID는 `[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}` 형식으로 제한한다. 빈 모델, 공백·제어 문자·셸 메타문자가 포함된 모델, 120자를 초과한 모델, 지원하지 않는 effort는 실행 전에 오류로 알린다. 사용자 지정 모델을 기본값으로 조용히 교체하지 않는다. 기존 75초 실행 제한과 단일 실행 잠금을 유지한다.

Windows의 현재 npm shim은 마지막 bare `-` 인자를 PowerShell `-File` 경계에서 거부하고, 해당 인자를 제거해도 한국어 stdin을 `?`로 변환한다. `codex exec --help`가 명시하는 동일 stdin 계약에 따라 positional prompt를 생략하고 입력은 stdin에만 전달한다. 선택된 npm shim 옆에 공식 `node_modules/@openai/codex/bin/codex.js`가 있으면 동일 설치의 공식 bootstrap을 Node로 직접 실행해 PowerShell의 재인코딩을 피한다. 로그인 확인과 생성에 같은 실행 prefix를 사용한다. 공식 bootstrap이 있는데 Node를 찾지 못하면 명시적으로 실패한다. 사용자 전역 wrapper와 모델 버전은 바꾸지 않는다.

시작 시 출력하는 모델·effort는 구성값이다. HTTP 응답의 `model=codex` 또는 구성값만으로 실제 Astra 실행을 검증했다고 기록하지 않는다. 실제 CLI 호출 결과와 모델 증거를 별도로 확인한다.

## 검증 명령

```powershell
python scripts/test_ai_provider_companion.py
.\gradlew.bat :app:testDebugUnitTest --tests "org.fcitx.fcitx5.android.input.ai.*" --tests "org.fcitx.fcitx5.android.input.profile.AppKeyboardProfileTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest --tests "org.fcitx.fcitx5.android.input.ai.AiProviderProfileTest" --tests "org.fcitx.fcitx5.android.input.ai.OpenAiResponsesClientTest" :app:assembleDebug -PsaegeulAiBalancedModel=gpt-6-astra -PsaegeulAiQualityModel=gpt-6-astra
```

로컬 로그는 `outputs/astra-migration/`에 보존한다. Windows 샌드박스에서는 임시 디렉터리 ACL과 Gradle 사용자 캐시 접근 오류가 발생할 수 있다. 테스트를 바꾸거나 스킵하지 않고 동일 명령의 실행 권한을 확보해 구분한다.

## 기본값 전환 완료 조건

1. 요청 모델·추론 강도·출력 계약·저장된 사용자 선택·오프라인 차단 테스트에 실패가 없다.
2. 실제 공급자에서 Astra 요청이 성공하고 실제 실행 모델을 확인한다. API 직접 연결과 CLI 컴패니언의 성공을 서로 대체하지 않는다.
3. 고정된 비개인 한국어 평가셋에서 기존 Quality 모델과 의미 보존·조사·어미·띄어쓰기·지시 준수를 비교한다. 기본값 변경 전에 품질 비열등, p95 지연 90초 미만 및 기존 대비 20% 이내, 요청당 실제 비용 기존 대비 20% 이내를 기준으로 평가한다. 기준 조정은 측정 결과와 제품 판단을 명시하고 결정한다.
4. 소수의 smoke 요청은 접근성과 출력 형식의 증거일 뿐 p95·비용·한국어 품질 평가의 완료가 아니다. API 계정 접근과 비용 정보가 없으면 해당 항목은 미검증으로 남긴다.

되돌릴 때는 Balanced/Quality 설정을 이전 저장값으로 명시적으로 복원한다. 컴패니언은 `--codex-model`/`--codex-effort`로 검증한 이전 조합을 지정한다. OAuth 자격증명과 Gemma 자료를 삭제하지 않는다. 배포와 서비스 재시작은 검토된 변경을 적용하는 별도 단계다.

## 근거

- [OpenAI GPT-6 Astra 마이그레이션](https://developers.openai.com/api/docs/guides/latest-model)
- [OpenAI Responses 전환](https://developers.openai.com/api/docs/guides/migrate-to-responses)
- [Codex 설정 참조](https://learn.chatgpt.com/docs/config-file/config-reference)
- 저장소 `AGENTS.md`, `docs/wiki/09-Developer-and-Build-Guide.md`, `docs/independent-fork/privacy-data-safety.md`.

## 현재 검증 상태

컴패니언 Python 단위 테스트는 43개 모두 통과했다. 근거는 `outputs/astra-migration/companion-tests-node-prefix.log`다. 테스트의 Windows 파일 접근 오류는 동일 코드를 권한 확보 후 실행하여 구분했고 테스트를 스킵하지 않았다.

같은 비개인 한국어 입력 3개로 실제 컴패니언 `_run_codex()`와 `normalize_suggestions()` 경로를 실행했다. 두 모델 모두 3개 요청의 JSON 제안 계약을 통과했다. 각 응답의 CLI 헤더에서 Codex `0.153.4`, `provider=openai`, 지정 모델과 `reasoning effort=low`를 확인했다.

| 과제 | Astra low | Sol low |
|---|---:|---:|
| 띄어쓰기 | 8.208초 | 5.502초 |
| 정중한 표현 | 6.752초 | 5.291초 |
| 회의 일정 문안 3개 | 7.889초 | 8.492초 |

띄어쓰기 결과는 두 모델 모두 `내일 오후 세 시에 다시 연락드릴게요.`였다. 정중한 표현 결과도 두 모델 모두 `자료를 오늘 보내 주시면 감사하겠습니다.`였다. 일정 문안은 구체적인 일시를 발명하지 않고 가능한 일정을 물었다. 이는 이 세 과제에서의 관찰이며 품질 우월성이나 전체 한국어 자연스러움의 증거로 일반화하지 않는다.

원문 증거는 `outputs/astra-migration/live-cli-bounded-evidence.jsonl`, 재현 스크립트는 `live_check_bounded.py`다. 첫 실패에서 전체 실행을 중단하고 최대 6건으로 제한했다. 이전 wrapper 경로의 실패 기록 `live-cli-evidence.jsonl`은 별도로 보존했다. 이전 검증 스크립트가 실패 후에도 다음 요청을 진행한 결과는 성공 증거로 채택하지 않았다.

OpenAI API 직접 연결은 실행 환경에 API 키가 없어 미검증이다. 앱 HTTP/OAuth를 거친 전체 연결, 기기 UI, 실제 과금 비용 및 충분한 표본의 p95·품질 평가는 남아 있다. 운영 서비스 재시작과 설치된 앱의 프로필 변경은 수행하지 않았다.

Android 최종 검증은 다음과 같다.

- 기본 설정 회귀: 테스트 686개 중 679개 통과, 실패·오류 0개, 기존 스킵 7개. `default-final-tests.log`와 `default-final-test-results/`에 원문을 보존했다. 전체 Android 테스트가 아니라 위 명령의 AI·앱별 프로필 범위다.
- Astra Balanced/Quality 선택 설정: `OpenAiResponsesClientTest` 32개와 `AiProviderProfileTest` 12개, 합계 44개 모두 통과. 실제 `generate(AiAction.Compose, input)` 경로가 Balanced 설정을 요청 모델로 전달하는 테스트를 포함한다.
- Astra 선택 Debug APK: `BUILD SUCCESSFUL`. 생성 BuildConfig는 Fast=`gpt-5.6-luna`, Balanced=`gpt-6-astra`, Quality=`gpt-6-astra`다.
- 두 모델 property의 빈 값과 Quality의 공백 값은 구성 단계에서 기대한 오류로 중단됨을 확인했다. 검증 과정의 Gradle `--tests` 위치 오류는 옵션 순서를 수정한 재실행 결과와 구분해 보존했다.

설치 가능한 arm64 Debug APK는 `outputs/astra-migration/apk/saegeul-astra-arm64-debug.apk`에 보존했다. SHA-256은 `05547BF0EF7938EF6250FBCE8AE89EFCB03FC1498DD0865CA34B2DE7DD56D2FE`다. 이 APK에는 현재 작업 트리의 기존 Gemma·UI 변경도 포함돼 있으며, 이번 Astra 변경만 분리한 릴리스 아티팩트가 아니다.

구현·테스트 워커의 diff와 검증 원문을 오케스트레이터가 직접 확인했다. 예외 검증이 부족한 테스트와 실패 후 계속 진행하던 실호출 스크립트는 반려·보강 후 재검증했다. 최종 코드와 위 증거를 수용했다. 커밋·push·릴리스·설치된 컴패니언 교체는 수행하지 않았다.
