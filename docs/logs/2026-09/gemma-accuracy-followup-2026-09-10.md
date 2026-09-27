# Gemma 정확도 후속 — 2026-09-10

## 판정

**부분 개선 수용. 전체 한국어 정확도 게이트는 미완료다.** 생성 프롬프트의 해요체 지시와 조사·어미·시점 점검을 명시했다. 저장 필터, 기존 문장, 모델, 개인정보 경계와 입력 중 로컬 조회 계약은 변경하지 않았다. 기준은 [한국어 스마트 입력 SSOT](korean-smart-input-ssot.md)다.

Fold6의 새 공개 20문맥 × 2문장, 총 40문장을 직접 검토했다. 40개 모두 해요체였고 요청 개수는 20/20 준수했다. 저장 거부 0개, 신규 32개, 중복 8개다. **이 수치는 의미 정확도 100%를 뜻하지 않는다.** 아래의 명확한 호응 오류 2개와 추가 검토 사례가 있다.

## 변경 전후 비교

같은 모델·CPU·요청 2개·문맥 0~3의 새 응답에서 해요체 준수는 변경 전 6/8, 변경 후 8/8이었다. 변경 전 동일 출력이 두 번 나왔지만 독립적인 16개 품질 표본으로 세지 않는다. 기존 검증 워커가 잘못 지정한 문맥 번호 대신 실제 JSON에 기록된 0~3 범위로만 해석한다.

| 문맥 | 변경 전 새 응답 | 변경 후 새 응답 |
| --- | --- | --- |
| 오늘 저녁 | 오늘 저녁 뭐 먹을까? | 오늘 저녁 뭐 같이 먹을까요? |
| 오늘 저녁 | 오늘 저녁 같이 영화 볼래? | 오늘 저녁 시간 괜찮으세요? |

0~3 비교는 소표본이다. 변경 후 전체 20문맥 검토와 구분하며 모델 전체의 정확도나 인과적인 성능 향상을 주장하지 않는다. 기존 저장 문장을 다시 생성한 중복도 새 생성 응답 관측에는 포함하되 신규 저장 수와 구분했다.

## 남은 정확도 결함

| 식별자 | 새 공개 응답 근거 | 판정 및 다음 작업 | 완료 조건 |
| --- | --- | --- | --- |
| ACC-01 | 문맥 5: `답장이 늦어서 무슨 일인가요?` | 이유절과 질문의 연결이 어색하다. 이유·조건절의 주체와 결과 호응을 보존하는 생성 개선이 필요하다. | 같은 문맥의 새 출력에서 이 오류가 재발하지 않고 다른 조건·이유 문맥에도 회귀가 없는지 직접 검토 |
| ACC-02 | 문맥 13: `고마운 마음을 정말 감사해요.` | 목적어와 서술어 호응이 부자연스럽다. 목적어를 자연스럽게 받는 서술어 선택을 개선해야 한다. | 문맥 13의 새 출력과 다른 목적격 문맥을 함께 검토하고 정상 표현을 임의 차단하지 않음 |
| ACC-03 | 문맥 6: `지금 출발하면 같이 갈까요?`, `지금 출발하면 제가 먼저 갈게요.` | 문맥 없이 읽을 때 조건과 결과 연결이 약하다. 오류 2개와 별도로 추가 검토가 필요하다. | 발화 주체·상황에 따른 허용 기준을 정의하고 별도 표본 평가 |
| ACC-04 | 문맥 10: `점심 먹고 같이 이야기해요?`, 문맥 17: `잘 이해가 안 돼서 같이 이야기해 봐요?` | 제안과 질문의 종결 선택을 추가 검토해야 한다. 단순 물음표 존재만으로 자연스러움을 판정하지 않는다. | 문장 의도와 종결어미·문장부호가 함께 자연스러운지 검토 |

형식 필터는 위 응답을 모두 수용했다. 단순 어미 정규식이나 단어 반복 차단으로 한국어 의미 정확성을 보장하지 않는다. 기존 은행에는 이전 프롬프트의 문장도 남아 있으므로 이번 새 응답 결과를 기존 전체 은행의 품질로 확대하지 않는다.

## 검증과 보존

- 기기: `SM-F956N`, Android 16, 무선 ADB `100.109.125.97:35315`. 주소와 열 상태는 실행 당시 값이다.
- 기준 빌드: 전체 단위 테스트 1,175개, 실패·오류 0, 기존 스킵 7, 실제 통과 1,168. XML 사본을 보존했다. 빌드는 2분 9초에 성공했다.
- 수정 빌드: 프롬프트 계약 테스트 4개, 실패·오류·스킵 0. 앱·AndroidTest arm64 빌드는 19초에 성공했다.
- 앱 SHA-256: `49579666A5777CFC39D57817A66A841C3E58B2440F80F282CDA4A6D1C61C9F4F`.
- AndroidTest SHA-256: `CAEE8CECF0BCFA4D26FE9E88941458FD8199083C8D434A74D7513186BBE3A756`.
- `install -r` 전후 기존 은행 89개·20/20 coverage와 재로드 일치를 확인했다. 앱 데이터 clear·uninstall·은행 삭제를 하지 않았다.
- 수정 후 문맥 0~11은 4문맥 단위, 실제 thermal 2가 관측된 뒤 문맥 12~19는 1문맥 단위로 실행했다. 각 로그의 실제 prefixStart·maxRequests·requestCount·prefixIndex와 `OK (1 test)`를 확인했다. 새 출력 검토 범위는 중복 없는 20문맥이다.
- 최종 은행 125개·coverage 20/20·`reloaded=true`·`generationActive=false`. 자동 준비 `enabled=false`, thermal 2, override false다. 89→125의 증가 36개 중 성공한 품질 표본의 신규는 32개이며, 최초 충돌한 부분 실행 뒤 추가되어 있던 4개를 별도로 구분한다.
- 저장 문장 실제 표시: 421/349/384ms, `ondevice_generated`, 세 문맥 모두 실제 touch 삽입 일치. 이는 입력 시 로컬 조회 시간이며 모델 생성 지연이 아니다.
- `ContextualReplacementDeviceTest`: `OK (6 tests)`, exit 0. 표시·터치 검증은 수정 APK 설치 후, 전체 20문맥 생성 완료 전에 수행했다. 마지막 생성 뒤에는 coverage·재로드·종료 상태를 다시 확인했다.

## 실행 오류와 남은 안정성 확인

첫 빌드는 샌드박스 밖 Gradle 캐시 lock 접근 거부로 실행되지 않았다. JDK 17 경로를 명시하고 캐시 접근 권한으로 재실행해 성공했다. 캐시를 지우지 않았다.

첫 검증 워커는 `prefixStart` 대신 `prefixIndex`를 전달하고 `maxRequests`를 생략했다. 해당 단일 문맥 검증 주장은 반려했다. 실제 실행은 두 번 모두 기본 문맥 0~3이었다. 메서드의 인자 처리 오류가 아니며 실행 명령 오류다.

수정 APK의 첫 문맥 0 실행은 `shortMsg=Process crashed.`로 실패했다. 다음 실행의 성공으로 최초 실패를 덮지 않는다. 충돌 원인은 아직 확인되지 않았다. 워커의 미보고 재시도는 반려하고 남은 16문맥의 실행·인자 대조를 오케스트레이터가 직접 수행했다. 이후 20문맥 품질 표본은 모두 내부 성공이지만 장기 안정성 통과를 뜻하지 않는다. 재현 시 앱 crash 원인과 생명주기·native 자원 종료 근거를 수집해야 한다.

## 증거와 재현

원문은 Git에서 제외되는 `outputs/gemma-accuracy-20260910/`에 보존했다. `quality-summary.json`에 20문맥의 새 공개 응답과 계측을 모았다. 개인 입력이나 기존 은행 본문을 추출하지 않았다.

- 기준 출력: `generation-prefix-1.log`, `generation-prefix-8.log` — 파일 이름과 달리 실제 문맥은 모두 0~3.
- 수정 출력: `device-generation-prefix0-retry.log`, `quality-prefix4.log`, `quality-prefix8.log`, `quality-prefix12.log`부터 `quality-prefix19.log`.
- 종료 증거: `quality-final-coverage.log`, `quality-final-state.log`.
- 회귀 검증: `device-followup.log`. 최초 충돌: `device-generation-prefix0.log`.
- 빌드: `gradle-jdk17-test-build-retry.log`, `revised-build.log`, `baseline-test-results-20260910-124145/`.

현재 기기 주소를 재확인한 후 아래처럼 한 문맥씩 재현한다. ACC-02는 prefixStart를 13으로 바꾼다. 자동 시작·중지 조건과 데이터 보존은 유지하며, 실패하거나 실제 실행 범위가 다르면 후속 생성을 중단한다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 100.109.125.97:35315 shell am instrument -w -r -e class 'org.fcitx.fcitx5.android.GemmaMaterialPerformanceDeviceTest#measurePublicMaterialGenerationOnDevice' -e prefixStart 5 -e maxRequests 1 -e sentenceCount 2 -e intervalMs 0 -e reviewFreshPublicResponse true net.chanpaca.saegeul.debug.test/androidx.test.runner.AndroidJUnitRunner
```

탐색 근거와 프롬프트 구현 diff는 수용했다. 기기 검증의 범위 오기와 실패 미보고는 반려했다. 커밋·push는 하지 않았다.
