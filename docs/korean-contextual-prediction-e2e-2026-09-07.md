# 한국어 문맥 추천·내 언어 금고 E2E 기록 (2026-09-07)

## 범위와 기기

- 기기: USB `RFCX60GBL3D` A35.
- 기준 설치본: `versionName saegeul-v0.1.0-rc.23-22-g2e7e266f`, `versionCode 242`.
- 수정 APK: `net.chanpaca.saegeul-saegeul-v0.1.0-rc.23-23-ga29b897d-arm64-v8a-debug.apk`.
- `adb install -r` 결과: `Success`.
- 증거 폴더: `.artifacts/contextual-quality-e2e`.

APK 설치·실행 성공은 빌드 wrapper의 종료 코드 원문이 없으므로 assemble 성공 주장과 분리한다. 단위 테스트 결과는 `ai-personalization-backlog.md`의 B2·B3 근거에 한정한다.

## 문맥 추천

`context-space-before.png/xml`에서 실제 입력 `내가 뭘 ` 뒤에 `회의`, `참석합니다`, 그리고 조립 문장 `내가 뭘 회의 참석합니다`가 표시됐다.

`context-space-after.png/xml`에서는 원문 입력이 같고 무관 단어와 조립 문장은 사라졌다. 그러나 `오늘 회의 참석합니다`, `내일 판교에서 봐요`가 남았다. 따라서 B19의 다양한 미학습 문맥 top-k 자연스러움·의미 연결 완료 조건을 충족하지 못했으며, **B19는 FAIL 및 미완료**다.

## 추천 수락 기록

`acceptance-candidate-space.png`에서 실제 키로 `감사 `를 입력하고 `감사하겠습니다` 후보를 탭했다. `accepted-editor.xml`에서 `감사하겠습니다 `가 한 번만 삽입된 것을 확인했다. 이 시나리오는 PASS다.

`vault-after-install.png`는 기록 전 0문장을 보인다. 수락 뒤 `vault-after-accept.xml`은 추천 수락률 2%, 개인 활용 0%, 최근 30일 그래프 1문장/최대 1을 보인다. force-stop 후 재실행한 `vault-restarted.xml`에서도 수락률 2%와 1문장이 유지돼, 이 입력·수락 시나리오의 저장과 재표시는 PASS다.

## 레벨 화면

`vault-font130.png`에서 font scale 1.3으로 레벨·타이틀·설명이 잘리지 않는 것을 확인했고, 마지막에 font scale 1.0으로 복원했다. 이 범위의 렌더링은 PASS다.

## 보존·미완료 게이트

- 기존 사용자 금고는 삭제하지 않았고 seed도 만들지 않았다.
- 기기 테스트 전체 통과 주장은 하지 않는다.
- B19는 private/candidate 거절, stale 후보, 320dp, 일반 문맥의 품질 게이트가 남아 있다.
- B20은 추천 수락률 기록 일부만 증명됐으며, 전체 운영 경로 및 수치·그래프 갱신 완료는 별도 검증이 필요하다.
- B21은 font scale 1.3 한 기기에서의 비절단만 증명됐으며, 실제 산정값·진행 근거 및 추가 화면 크기 검증이 남아 있다.
- B22는 당시 A35 실제 분석에서 24문장·어미 0건을 확인했다. 원인 확정과 후속 실기기 확인은 아래 추가 기록에 남긴다.

## 최종 설치·어미 추출 추가 확인

`final-build.log`는 `BUILD SUCCESSFUL`(31초)과 `GRADLE_EXIT_CODE=0`을 기록한다. 최신 app APK(65,220,039B)는 `adb install -r`로 `Success`를 받았다.

`ending-typed-valid.xml`에서 실제 새글 키로 `감사합니다. `를 입력했다. 그 전 좌표 실수로 `감사ㅘㅂ니다.`도 입력됐지만, Clear 뒤 정확한 입력으로 다시 검증했다. 테스트 입력은 실제 수집돼 수치에 포함될 수 있으며, 금고를 임의로 삭제하지 않았다.

`ending-presync.xml`은 분석 문장 24·어미 0·pending 4를 보인다. 동기화 UI `ending-sync-result.xml`은 오프라인이라 AI 강화를 실행하지 않았음을 보인다. 이후 `ending-postsync.xml/png`는 분석 문장 28·어미 2를 보이며, force-stop 뒤 재실행한 `ending-restarted.xml`에서도 28·2가 유지됐다. 제한된 어미 추출 누락 수정과 수치 저장은 PASS다. 이는 완전 형태소 처리 증명이 아니다. `ending-offline-restored.xml`에서 `OFFLINE=false` 원상복구도 확인했다.

## 2026-09-08 프로필 재수화 코드 검증

이전 startup 재수화 근거는 `compileFullProfile`의 `compilePersona(persist=false)`가 미분석 category를 purge하던 문제와 Compiler·`TypingDnaProfilerAndCompilerTest` 3 suite XML 17건(fail/error/skip 0), `typing-dna-compiler-rehydrate-tests.log`의 `BUILD SUCCESSFUL`(15초)이다. 최신 구현은 Compiler의 vault 의존과 purge 책임을 제거하고, Vault `processPending`이 성공 category만 제거하며 실패 category는 예외 전파·보존한다. Service 자동 경로와 InstantSync는 같은 현재 pending을 소비하고, 자동 실행은 `profileOnDevice`만 사용하며 LLM caller는 제거했다.

stale callback 중복 count 방지·새 입력 동시성·실패 category 부분 성공·LLM 호출 0·startup 보존은 Vault 8·Compiler 8·Sync 6, 총 22건(fail/error/skip 0)으로 검증됐다. 최종 테스트 실행은 `compileDebugKotlin`을 포함해 `BUILD SUCCESSFUL`(13초)이다. root가 코드 diff·테스트 보완·로그·summary를 직접 검토했다. AndroidTest의 생성자 호출 1줄만 수정했고 기기 테스트는 실행하지 않았다. Repository 신규 우선 최근 15개 어미 기억 목록(누적 관측 횟수 아님)은 EndingRetention 5·Stats 5, 총 10건(fail/error/skip 0), `typing-dna-ending-retention-tests.log`의 13초·exit 0으로 검증됐다. 파일 저장 실패 복구와 실제 기기 검증은 남아 있다.

persistence 실패를 2건 재현한 뒤 disk write 성공 후에만 cache를 갱신하도록 수정했다. 실패한 pending을 보존하며 저장이 복구된 뒤 다시 동기화했을 때 한 번만 반영됨을 검증했다. 실패 Toast를 표시하도록 구현했으며 기기에서는 미검증이다. `typing-dna-persistence-fix-xml-summary.txt`의 집중 29건은 fail/error/skip 0이고 `typing-dna-persistence-fix-tests.log`는 `BUILD SUCCESSFUL`(16초)이다. `VaultFile`은 tmp fsync→base의 bak 보존→rename commit을 수행하고, 실패 시 base/bak 이전 바이트를 보존한다. rollback 실패 backup 읽기·동일 프로세스 canonical path 직렬화·cleanup 실패 새 base 정본은 `vaultfile-recovery-xml-summary.txt`의 26건(fail/error/skip 0), `vaultfile-recovery-tests.log`의 `BUILD SUCCESSFUL`(19초)으로 검증됐다. 앞선 512 통합과 해당 APK는 VaultFile 수정 전이며, 후속 528 통합·x86 빌드는 VaultFile 수정을 포함한다. OS 전원 차단·디렉터리 fsync·멀티프로세스·crash 완전 보장과 `TypingDnaVault.persistStaging` 예외 삼킴은 미해결이다.

`isolated-avd-setup.log`는 isolated emulator `Saegeul_Quality_20260908`(serial `emulator-5580`, PID 63648, API 34 x86_64)의 부팅 완료를 기록한다. 실폰은 조작하지 않았다. 이후 root가 app/test APK를 설치하고 IME를 enable해 instrumentation을 실행했다.

| 실행 | 결과 | 한계 |
| --- | --- | --- |
| `isolated-append-continuity-instrumentation.log` | 7건 중 5 pass·2 fail | 첫 IME 연결과 DEL 실패 |
| `isolated-delete-stale-recheck.log` | 2건 중 1 pass·1 fail | 이번에는 첫 Delete가 IME 연결 실패 |
| debug window focus hook 뒤 `isolated-append-continuity-harness-fixed.log` | 7건 중 5 pass·2 fail | hook 효과가 없어 제거 |
| 최신 `isolated-append-delete-diagnostics.log` | 6건 중 5 pass·1 fail, 14.399초 | 첫 stale snapshot만 `activeInstance=false`; append 4·physical Delete 1 pass |

두 AndroidTest는 main-thread 상태 snapshot과 실제 framework DEL dispatch를 유지한다. deadline 5초와 기존 assert는 완화하지 않았다. 최신 APK SHA-256은 app `BED38B109C99364B73C5EE8488F21E29AFF2E030B573646D6704ADEC982145CD`, test `0FBB7804BCD9A0DD641BA8249FB708FD9BCE36917B948C7E27E680B6DFB94034`이며 plugin은 기존 `7fba…548`이다. 최신 실행 전 viewport는 기본 1080×1920/480에서 1080×2340/420으로 바뀌어 DELETE pass 원인을 단정할 수 없고 간헐 IME 연결 실패도 해결되지 않았다. `isolated-harness-fixed-logcat.txt`에서 coldstart 5초 시점 IME가 없고 daemon 시작이 이후인 것은 확인했으나 전체 앱 일반 성능 결론은 아니다. 모델 품질 E2E와 B19·B23 전체 완료 근거가 아니다.

`isolated-vault-stats-runtime.log`의 `testDeviceVaultAccumulationAndPurge`와 `testDeviceStatsDtoPrivacyGaugeAndLiveSentenceIncrement` instrumentation은 OK(2 tests, 0.034초)다. 실제 Main→상세 그래프→「지금 즉시 분석 및 동기화」 흐름에서 `isolated-vault-before-sync.xml/png`는 분석 0·ngram 학습 14단어·graph 11·main pending 11을 보이고, `isolated-vault-after-sync-bottom.xml`은 `No AI provider...` 대화상자를 기록한다. `isolated-vault-after-sync-stats.xml`은 온디바이스 분석 뒤 분석 11·단어쌍 8·어미 2·상용구 5·pending 0·graph 11이다. synthetic 테스트 누적 데이터만 사용했고 실폰은 조작하지 않았다. 이는 graph Ngram 즉시 학습과 분석 완료 집계를 구분한 관찰일 뿐 전체 키보드 E2E·문장 추천 품질·각 원문 일대일 대응·재시작 유지·후보 선택 full cycle은 미검증이다. B20 명칭 수정은 검토 중이며 이 실행은 새 APK 런타임이 아니다.

후속 B20 문구 수정은 root 검토에서 runtime 접근성 설명 누락을 반려한 뒤 보완·수용했다. `b20-timeline-accessibility-x86-build.log`는 compile+assemble 성공(11초, 175 tasks, exit 0)이며 app SHA-256은 `a6078efffed9e8afcaf9a7e8da11cb003828f983922e8cbfcdd602a64f8555fa`다. root가 emulator-5580에 `install -r`하고 Main→상세 그래프를 다시 열었다. `isolated-vault-label-new-apk.xml/png`에서 입력 학습과 말투·어미 분석을 구분하는 새 제목·설명·runtime 접근성 문구를 확인했다. 영어 화면 1080×2340/density 420에서 설명과 그래프가 잘리지 않았고, 업데이트 후 분석 11·레벨 1·진행률 73%·입력 학습 그래프 11이 유지됐다. 한국어 렌더링·전체 글꼴 배율·전원 차단 보존·추천 품질 통과 근거는 아니다.

## 문맥 추천 최종 추가 확인

`context-space-final.xml/png`에서 실제 `내가 뭘 ` 뒤에 `감사`와 `내가 뭘 감사 감사하겠습니다`가 새 오답으로 표시됐다. 이전 회의 원문이 사라졌다는 사실만으로 품질을 통과시킬 수 없으므로 B19는 여전히 FAIL·미완료다. Clear와 입력 경계 이전의 pending 혼합으로 학습이 오염됐을 가능성은 조사 중이며 원인은 확정하지 않았다.

## 입력 세션 경계 추가 확인

`.artifacts/b23-b24-tests.log`에서 B23·B24 통합 단위검증 35건이 PASS했다(collector 16·session 4·ending 7·profiler 6·observed 2, `GRADLE_EXIT_CODE=0`). observed 추가 assert 2건을 넣은 뒤 재실행도 PASS·exit 0이다.

`session-boundary-build.log`는 `BUILD SUCCESSFUL`(22초)과 exit 0을 기록한다. APK 65,311,290B는 A35에 `adb install -r`로 `Success`를 받았다.

실제 A35에서 `session-before-clear.xml`은 `우리가 언제 ` 입력을, `session-cleared.xml`은 빈 editor를, `session-after-clear.xml`은 `고맙습니다. ` 입력을 보인다. `session-requery.xml/png`에서 다시 `우리가 언제 `를 입력했을 때 후보 줄은 비어 있었고 두 문맥이 합쳐진 추천도 표시되지 않았다.

이 결과는 해당 Clear/restart 흐름의 비혼합 **표시** 증거만 뜻한다. 저장 원문 조회, 일반 편집, 자연스러운 추천은 PASS가 아니며 B19는 미완료다. 이 시점의 B23 기록에서는 범용 커서 삭제와 후보 교체 추적이 남아 있었다.

## 후보 원자 교체 추가 확인

서비스의 `commitContextualCandidateText`는 선삭제 없이 `replaceAiRange`로 후보를 교체하며, 성공한 경우에만 collector suffix를 조정한다. 관련 단위검증 28건은 XML 기준 pass 28·fail 0·skip 0이다. 최초 AndroidTest는 공백이 든 method name으로 D8에 실패한 `.artifacts/contextual-quality-e2e/atomic-candidate-build.log`가 있으나, 이름을 고친 뒤 실제 루트 로그 `D:\workspace\Saegul\atomic-candidate-build-fixed.log`는 `BUILD SUCCESSFUL`(17초)을 기록했다. exit 0은 로그 본문이 아니라 워커의 shell 실행 보고다.

APK 설치는 두 번 모두 `adb install -r`로 성공했다. USB `RFCX60GBL3D` A35에서 `ContextualCandidateRejectDeviceTest`를 직접 실행한 `atomic-candidate-reject-device.log`는 OK(1 test, 3.88초)다. 데이터 clear나 seed는 사용하지 않았다.

정상 UI에서 실제 `감사 ` 입력(`atomic-before.xml`, chars 3) 뒤 `감사하겠습니다` 후보가 `atomic-before.png`에 표시됐다. XML 접근성에는 후보가 포함되지 않았다. root tap(170,1470) 뒤 `atomic-after.xml`은 정확히 `감사하겠습니다 `, length 8, selection 8..8이며 중복 삽입이 없음을 보이고 `atomic-after.png`가 이를 보완한다.

이 기록은 후보 선택 성공과 실패 시 원문 보존 범위만의 PASS다. 기기 테스트는 direct commit 테스트이며, 일반 커서 편집과 자연스러운 추천 품질은 미완료다.

## 편집 연속성 추가 확인

외부 커서 이동·선택으로 이어쓰기 근거가 끊기면 current package의 collector pending만 폐기하고 history와 다른 package 데이터는 보존하도록 hook을 추가했다. buffered consume 불일치, normal composing 밖, 범위 선택에만 적용하며 composing 안 collapsed·예측 match·internal prompt는 보존한다.

`editor-continuity-tests.log`는 XML 26건(pass 26·fail 0·skip 0·error 0), `BUILD SUCCESSFUL`(17초), 워커 shell exit 0을 보고한다. `.artifacts/contextual-quality-e2e/editor-continuity-build.log`는 `BUILD SUCCESSFUL`(13초)·`GRADLE_EXIT_CODE=0`을 기록하고 `adb install -r`는 성공했다. A35 `editor-continuity-reject-device.log`의 OK(1 test, 4.172초)는 기존 후보 거절 회귀 시험이다.

따라서 이 기기 시험은 커서 이동 뒤 collector 동작의 직접 증거가 아니다. 일반 backspace/delete가 정확히 반영되는지도 미완료다.

## 외부 커서 이동 추가 확인

`cursor-continuity-device.log`에서 A35 직접 기기 시험은 OK(1 test, 3.976초)다. 실제 Normal editor에 `내가 뭘 해야 할지`를 direct commit한 뒤 cursor를 이동했을 때 pending은 false가 되고 원문은 유지됐다.

이는 단순 키보드 tap 시험도 학습 품질 증거도 아니다. 시험 입력이 reinforce 경로를 통과할 수 있으며, 일반 backspace/delete의 정확한 반영은 계속 미완료다.

## 일반 삭제 연속성 기기 검증 대기

실제 editor의 일반 backspace·`deleteSurrounding`·forwarded physical DEL/FORWARD_DEL 전달 직전에 current package의 미확정 pending 연속성 폐기를 연결했다. 기존 history·저장 학습은 보존하며 내부 AI prompt early return과 buffered Hangul 버퍼만 삭제하는 경로는 제외한다.

삭제 성공과 정확한 삭제 범위는 확정하지 못해 pending만 보수적으로 폐기한다. 이미 학습된 문장 취소나 편집 뒤 전체 문장 재구성은 하지 않는다. `TypingDnaDeleteContinuityDeviceTest`는 framework `sendKeyDownUpSync`로 서비스를 경유해 editor 끝 공백 삭제·pending 소거·새 문장 독립 history를 확인하도록 작성됐고, 이 시점에는 아직 실행하지 않았다. 따라서 당시 기기 증거와 B23 전체 완료는 대기 상태다.

## typed 후보·프리페처 추가 확인

B25·typed 초기 APK가 A35에 설치됐다. `prefetch-readiness-device.log`에서 providerConfigured·aiAllowed·networkAllowed·textInspectionAllowed는 모두 true였지만, 이는 유효한 OAuth 세션을 뜻하지 않는다. `prefetch-auth-failure.log`는 `schedulePrefetch: dispatching` 뒤 `AiReauthenticationRequiredException`이 반복되는 것을 보인다.

`contextual-append-device.log`는 OK 3건(7.395초)이다. 그러나 `typed-korean.png/xml`에서 실제 키 입력 `내가 뭘` 뒤에 `내가 뭘 잘못`, `내가 뭘 해야 하겠습니다`가 표시돼 문맥 품질은 FAIL이다. `잘못`은 이전 direct commit 검증에 따른 학습 오염 가능성이 있어 coldstart 증거가 아니다.

`typed-prefetch-cancellation-tests-fixed2.log`의 cancellation은 21건, `prefetch-completion-refresh-tests.log`의 callback-refresh는 22건이며 각각 fail/error/skip 0이다. 두 결과를 합산하지 않는다. 두 소스와 재로그인 UI를 포함한 최신 통합 APK는 app/test 모두 `adb install -r`로 설치됐다.

## 연결 상태 추가 확인

설정 화면에는 OAuth 연결됨과 달라진 profile 이름이 보였지만 root는 저장·로그인을 실행하지 않았다. 외부 변경 가능성이 있어 원인은 미확정이다. `prefetch-connected-failure.log`는 22:30:59 dispatch 뒤 22:31:01 `AiProviderException`을 기록한다.

재로그인 UI는 session missing일 때만 표시돼 현재 session이 있는 상태에서는 보이지 않았다. actual missing-session UI·login E2E는 미검증이다. 테스트 중 foreground가 다른 앱으로 전환돼 device interaction을 중단했으며, 사용자 동시 사용 확인도 pending이다.

## 구조화 진단·scoped prefetch 코드 검증

`AiProviderFailureKind`·HTTP status 구조화 진단의 단위검증은 client 16건과 prefetch 9건, 총 25건(pass 25·fail/error/skip 0)이다. 원문 로그 파일은 없고 XML과 워커 실행 stdout만 근거다.

`Scope(packageName,inputSessionEpoch)`와 `RequestKey(normalizedContext)`로 cache·callback을 분리하고, request origin과 다른 앱·세션 completion을 거절하도록 Service·predictor에 scope를 명시했다. `scoped-prefetch-cache-tests.log`는 39건(pass 39·fail/error/skip 0), `BUILD SUCCESSFUL`(19초)이다. 이 소스는 기기에 설치하지 않았으므로 scope 분리의 기기 증거는 없다.

## 컴패니언 health·synthetic 생성 추가 확인

`companion-health-readonly.json`은 agy·claude·codex backend health가 `ok`임을 보이지만 scope가 health only이므로 생성 성공 증거가 아니다.

Qwen3-4B-Q4_K_M 공식 GGUF의 local SHA-256·크기(2,497,280,256B)는 metadata의 공식 값과 일치했다. v1 첫 `내가 뭘` raw 응답은 24,829.628ms(load 8.718초·prompt 15.757초·eval 0.328초) 뒤 `WORD소나요.Word`·`WORD알아요.Word`·`WORD말해요.Word`를 반환했고, 이후 `POST /api/ps` 오류로 나머지 2개는 실행하지 않았다. 이는 `AiAction.kt:68`의 Kotlin `WORD${'\t'}` 보간을 기존 script `instruction()`이 실제 탭으로 변환하지 못한 v1 Qwen harness 결함이 확인된 원본이라 모델 품질 판정에서 분리한다. 교정한 v2 `qwen4b-continuation-v2-metadata.json`은 3/3 `invalid_wire`였다. `내가 뭘` 응답은 `내가 뭘을`·`내가 뭘해`·`내가 뭘했`이고, 입력별 wall 시간은 `내가 뭘` 3,559.136ms(load 3,224.918ms)·`내일 판교에서` 730.359ms·`오후 2시 회의` 501.671ms였다. GPU `size_vram`은 2,874,062,929였고 owned PID 29356은 종료 returncode 0이지만 shell exit은 수집하지 않았다. v3 `qwen4b-v3-simple-clause-metadata.json`은 세 입력 모두 prefix를 보존하고 JSON 형식을 통과했다. `내가 뭘`→`내가 뭘을 하고 있는지 말해줄 수 있어?`(5,470.156ms), `내일 판교에서`→`내일 판교에서 쇼핑하러 가요.`(426.218ms), `오후 2시 회의`→`오후 2시 회의를 진행하겠습니다.`(372.107ms)였으나 첫 응답은 목적격 중복 비문이다. owned PID 55908은 종료 returncode 0이고 `qwen4b-v3-simple-clause-exitcode.txt`의 exitcode도 0이다. 형식 통과와 품질을 분리하며, 몇 건의 품질 통과 수치는 산정하지 않는다. 현 설정의 제품 채택은 반려하고 추가 Qwen 튜닝은 중단한다. 이 결함을 AGY 실험 전체에 일반화하지 않으며 기기 통과 근거가 아니다.

공식 [Kanana 1.5 2.1B Instruct 2505 카드](https://huggingface.co/kakaocorp/kanana-1.5-2.1b-instruct-2505)의 pinned revision `7df4bc35ccd610e451809d7106e1c3cf82bfd44c`를 BF16 PyTorch GPU로 단순 한국어 1문장 진단했다(`constrained_decoding=false`). init 13.241초는 generation과 분리했다. `.artifacts/contextual-quality-e2e/kanana-2.1b-simple-clause-results.json`의 raw는 `내가 뭘`→`내가 뭘 도와드릴까요?<|eot_id|>`(5,040.9801ms), `내일 판교에서`→`내일 판교에서 만나요.<|eot_id|>`(2,557.6725ms), `오후 2시 회의`→`"오후 2시 회의는 5층 회의실에서 진행됩니다."<|eot_id|>`(5,793.7814ms)다. raw EOS는 보존했다. 표시에 decoded를 사용할 수는 있지만 JSON을 따르지 않으며, 회의 응답은 앞 따옴표로 원문 prefix가 일치하지 않고 `5층` 정보를 임의로 넣었다. 판교 연결의 자연스러움은 단일 관찰일 뿐 일반 품질 PASS가 아니다. Qwen은 강제 JSON, Kanana는 지시문만 사용해 형식 조건도 다르다. 카드의 공식 Apache-2.0 표기를 기록할 뿐 법적 결론은 내리지 않는다. 현 계약의 제품 채택은 유보하며 추가 추론은 하지 않았고 기기 증거도 없다.

`.artifacts/contextual-quality-e2e/kanana-prefix-prefill-{harness.py,results.json,preflight.json,exitcode.txt}`의 prefix-prefill은 공식 chat template 뒤 assistant에 exact raw prefix를 고정하고 한국어·특정 사실 금지 system 지시를 사용했다. BF16 greedy 24 token으로 12개를 각 1회 실행해 12 EOS·token-limit 0·exit 0이며 init은 8.178초, generation은 1.118~4.283초다. 전 12개 raw는 results artifact에 보존하고 본문에는 `내가 뭘 하고 있었지?`(2,537.9227ms), `내일 판교에서 만나요.`(1,117.7969ms), `비가 많이 와서 우산을 챙겨야겠어.`(3,079.8257ms)만 든다. 3번·10번은 두 문장으로 확장됐고, 4번은 입력 끝 공백과 suffix 선행 공백이 중복됐다. 사용자 의도 적합성은 검증하지 않았다. prefix 고정과 prompt 변경을 동시에 했으므로 개선을 단일 요인으로 단정하지 않는다. preflight GPU는 기존 점유 9,520/12,288MiB였고 공유 메모리·oversubscription은 측정하지 않아 전용 GPU 성능으로 일반화하지 않는다. 이 raw 실험 당시에는 별도 0..3 protocol을 구현하지 않았고 앱·companion이 정확히 3개를 요구했다. 이후 추가한 v2 capability와 captured replay의 근거는 아래 후속 단계로 구분한다. 제품 미통합·기기 미검증이고 B19 완료 근거가 아니다.

captured-output replay는 `scripts/korean_continuation_surface.py` adapter의 `korean-continuation-surface-tests.log` 11건 `OK`·exit 0과 `.artifacts/contextual-quality-e2e/kanana-wire-replay.exit.log` exit 0을 기록했다. source `kanana-prefix-prefill-results.json` SHA-256 `0228b75bb9e8612bffbaba13d89791becb69f931ddefb708de0cc4559a9545ca`를 내용 수정·재생성 없이 `kanana-wire-replay.json` 12 record로 재생해 `NEXT_WORD` 10·`ATTACH` 2, accepted 12를 얻었다. accepted는 wire 형식 수용일 뿐이다. full 12 editor text를 검토했으며, raw `내가 뭘 `의 끝 공백과 suffix 선행 공백이 겹친 경계만 하나 제거했고 두 문장 출력은 그대로 보존했다. 실제 모델 HTTP·Android IME·문장 품질 PASS는 아니다. 후속 `CapturedPrefixContinuationContractTest`는 실제 앱의 `OpenAiResponsesClient.parseResponse` → `PrefetchedContinuation.parse` → `ContextualAppend.insertionFor`를 통과시켜 12개 최종 삽입 문자열을 확인했다. `captured-prefix-contract-tests.log`의 최종 실행은 `BUILD SUCCESSFUL in 14s`, exit 0이며 XML은 12 tests·0 failures·0 errors·0 skipped다. 최초 테스트 클래스 접근 범위 컴파일 오류와 수정 후 성공 로그를 함께 보존했다. fixture는 replay 원본과 SHA-256 `BDDA4C4E2B99D67C8FDA536F6604B06B49FCCE6434D4C3EEE689E488C2B2464E`가 일치한다. 이는 저장된 모델 출력의 앱 코드 계약 검증이며 HTTP 전송·실시간 추론·IME 화면 클릭은 실행하지 않았다.

Kiwi v0.23.2 `cong` 형태소 언어모델의 `.artifacts/contextual-quality-e2e/kiwi-cong-raw-24.jsonl`은 12개 prefix에 `openEnding=false/true` 각 12건, 총 24건 모두 prediction available이며 raw top 20을 480개 기록했다(raw SHA-256 `fe25559413aa068868d391c352198942bd8f3b07467562094acd0b6568a70c07`). threads 1, `forwardLMids`, BOS/EOS 미삽입 조건에서 model load 611.035ms, `KiwiBuilder.build` 1,114.05ms, reverse index 48.0804ms(형태소 657,472개·key 208,051개), context 생성 포함 predict 0.6389~1.2284ms였다. `내가 뭘` top 5는 내부 분해 표기를 읽기 쉽게 적으면 `하/VV`·`잘못/MAG`·`알/VV`·`어쩌/VV`·`보/VV`이고 top 20에는 `어떻-`·`잘-`·`모르-`도 있다. `내일 판교에서`는 `은/JX`·`벗어나/VV`·`도/JX`·`나오/VV`·`열리/VV`가 상위다. affinity 점수는 확률이 아니며, 화면 후보 어절·완결 문장·조사/어미 표면화·공백 경계·형태소 이어 생성·문맥 적합성·Android 성능은 검증하지 않았다. 초기 `Get-FileHash` 실패는 모델 실행 전이고 .NET SHA 뒤 attempt2 원본 JSON을 사용했다. PID 18088 종료·24건 산출물·JSON 정상·stderr 0B는 확인했지만 종료 코드는 수집하지 않았다. 제품 채택은 유보다.

앞선 raw ranking 단계의 화면 후보·문장·표면화·Android 미검증과 별개로, 후속 Kiwi surface `.artifacts/contextual-quality-e2e/kiwi-surface-60.jsonl`은 `openEnding=true`, threads 1, canonical exact mapping, baseline의 분석 `token.str/tag` 사용, `inferRegularity=false`, `wordPosition`에 따른 명시적 `Space` 지정, raw affinity 누적 없음 조건에서 12개 문맥의 초기 top 5마다 greedy top 1을 최대 8 형태소까지 이었다. harness·outer launcher exit 0과 JSON strict parse를 확인했으며 12 case·60 branch 중 exact prefix 55, EF/SF terminal 36, empty form stop 20, morpheme limit 4, empty suffix 8이었다. 이 수치는 문법·품질 PASS가 아니다. `배송이 아직 안 왔어요`, `이거 어떻게 생각하십니까`는 연결 관찰 사례이나 `혹시 시간 괜찮으시면 안 돼요`는 의미 부적합, `늦어서 미안성을 가지고 있다`와 `그렀더니`는 형태 오류다. `그건 내가 한 게` 5 branch는 baseline이 `그건 내가 한 것이`로 바뀌어 원문을 보존하지 못했고 suffix가 모두 null이었다. Android 측정·제품 통합은 없으며, 실행 증거는 수용하되 제품 문장 추천 엔진 채택은 반려한다.

defaultRunner의 synthetic `내가 뭘` AGY 단일 실행은 v1 total 53.397초(초기화 포함)였고, source·instruction SHA가 같은 v2는 `backend-probe-v2-result.json`에서 generation 46.441초·init 4.240초·total 50.752초, normalized WORD `잘못했어`·`했다고`, suffix `잘못했다고 그래`를 보인다. 이는 client/UI E2E가 아니며 이 실험 지연은 실시간 다음 어절에 부적합하다. 모든 provider의 일반 API 지연으로 일반화하지 않는다.

`scoped-prefetch-apk-build.log`는 최신 scoped APK의 `BUILD SUCCESSFUL`(31초)·exit 0을 기록하지만 APK는 아직 설치하지 않았다.

## B14 prefetch 단일 pump 코드 검증

프리페처는 단일 pump로 최신 desired만 직렬 생성하고, clear·짧은 입력의 stale은 폐기하며 cache의 app/session scope를 유지하도록 수정됐다. focused 6개 테스트 42건은 pass 42·fail/error/skip 0, `BUILD SUCCESSFUL`(17초)이며 root가 로그·코드·Prefetcher XML을 직접 확인했다.

이는 코드·focused 검증 범위다. 실제 provider 경합 E2E는 아직 없으므로 B14 전체 완료 증거가 아니다.

## 통합 AI 회귀 미통과

`.artifacts/contextual-quality-e2e/ai-integrated-regression.log`는 `BUILD FAILED`(18초), 총 491건 중 실패 4·skip 7·error 0을 기록한다. 실패는 `ContextualLearningAndTypingGroundingTest`의 coldstart 문장 존재 2건과 `SemanticSentenceRecommendationE2ETest`의 ToneConsistency·StrokeVsBlank 2건이다. 기존 ignore 7건은 관련 3파일이 HEAD 대비 변경되지 않았음을 verifier가 확인했고 신규 skip은 0이다.

focused 18건 통과는 이 통합 회귀 실패를 덮지 않으므로 전체 성공 주장이 불가하다.

`.artifacts/contextual-quality-e2e/typing-dna-integrated-summary.json`과 unit log의 당시 통합은 74 suite·512건(기존 500건 대비 12건 추가) 중 기존 coldstart 실패 2·skip 7·error 0으로 18초·exit 1이다. 실패는 판교 47행과 회의 69행에 그대로 남아 전체 품질은 미완료다. 해당 debug APK와 AndroidTest 빌드는 211 task·`BUILD SUCCESSFUL`(19초)·exit 0이고 hash path는 summary에 있으나, 해당 APK는 당시 아직 설치하지 않았다. 후속 528/x86 빌드의 설치·instrumentation은 위 isolated emulator 실행 표를 참조한다. 이는 기존 500건 실패 기록과 별도 역사 근거이며 기기 테스트 통과를 뜻하지 않는다.

`vaultfile-integrated-unit-summary.json`과 log의 후속 통합은 76 suite·528건 중 기존 `ContextualLearningAndTypingGroundingTest` 판교·회의 coldstart 실패 2·skip 7·error 0으로 exit 1이다. x86 app+hangul+AndroidTest 빌드는 43초·exit 0으로 끝났다. 이는 앞 512건 및 해당 APK보다 뒤의 빌드 근거지만, 모델 품질이나 전체 통합 통과를 뜻하지 않는다.

## B4 출력 전용 노이즈 필터 코드 검증

호환자모 U+3131–U+3163 중 `ㅋ`·`ㅎ`·`ㅠ`·`ㅜ` 외 문자를 출력 전용으로 차단하며 학습·tokenizer·교정 원문은 보존한다. n-gram 3경로는 limit 전, predictor는 공통 경로, Dashboard는 `take(12)` 전에 적용한다. focused 38건은 pass 38·fail/error/skip 0, 17초였고 `.artifacts/contextual-quality-e2e/b4-dashboard-compile.log`는 `BUILD SUCCESSFUL` 9초·exit 0이다. UI diff 최종 확인과 실제 기기 검증은 아직 없다.

root가 직접 검토한 backend 3문맥 생성은 46,942·49,602·47,404ms였으며 자연스러운 연결 2개가 있었지만, `회의`+`에` suffix에 앱이 공백을 추가하는 새 오류도 확인됐다. 수정 중인 문제라 생성 품질 통과 근거가 아니다.

후속으로 `SemanticSentenceRecommendationE2ETest` fixture를 운영의 n-gram+vault 기록 방식에 맞췄다. 제품 `learnSentence`는 바꾸지 않았고 positive assert와 기존 ignore 5건을 유지했다. `.artifacts/contextual-quality-e2e/semantic-sentence-recommendation-tests.log`의 focused 3 클래스는 총 13건 중 실행 8건 pass·skip 5·fail/error 0, `BUILD SUCCESSFUL`(12초)로 Tone/Stroke 두 실패를 이 범위에서 해소했다. 앞선 통합 491건·실패 4건 기록은 전체 재실행 없이 보존하며 coldstart 문장 존재 2건은 검사 미수정·미해결이고, 실제 기기 E2E도 아니다.

## 조사·어미 붙임 코드 검증

`continuation-attach-tests-final.log`의 focused 8클래스 69건은 pass 69·fail/error/skip 0, `BUILD SUCCESSFUL`(17초)이다. `CONTINUATION_ATTACH` wire·`JoinMode.ATTACH`, 끝 공백 거절·숨김, 끝 공백 cache 구분, ContinueTyping client 끝 공백 보존, reranker 다음 어절 bridge 제외를 포함한다.

root가 `ContextualAppendDeviceTest` 5개를 직접 읽어 기존 3개를 보존한 새 ATTACH 2사례를 수용했다. `.artifacts/contextual-quality-e2e/attachment-device-apk-build.log`는 211 task, `BUILD SUCCESSFUL`(25초), exit 0을 기록한다. 이 APK는 아직 기기에 설치하거나 이 기기 테스트를 실행하지 않았으므로 실기기 근거는 아니다.

`backend-attachment-boundaries.json`의 AGY 단일 관찰에서 no-space `오후 2시 회의`는 44,294ms 뒤 `CONTINUATION_ATTACH` `에`가 붙어 `오후 2시 회의에 `가 됐지만 문장 품질은 부족했다. trailing-space `오후 2시 회의 `는 45,299ms 뒤 `CONTINUATION` `참석 부탁드립니다`를 받았다. `backend-attachment-low-effort.json`의 low는 42,922ms에 ATTACH `에`만 반환해 high와 단일 1,372ms 차이여서 지연 해결 근거가 아니다. 두 artifact는 구버전 prompt SHA이고 당시 prompt 본문을 저장하지 않아 현재 prompt와 완전히 재검증할 수 없다. 이는 client/UI E2E나 실제 기기 통과가 아니다.

`backend-complete-clause-one-context.json`의 새 완결 문장 prompt high 단일 호출은 46,028ms에 `WORD` `참석`·`일정`과 ATTACH `에 참석해주세요.`를 반환했고, 적용 문장은 `오후 2시 회의에 참석해 주세요. `였다. 현재 `AiAction.kt` source SHA-256은 `e29eff0761e8a1896ce34c17cb6becf10d2e83ab0cd2e34b9517d1a9995e8d4d`, prompt SHA-256은 `e4b581e212bbaa90d899a1a8eef4fa541266372f1f5a62d9ba8ec2b98c654e22`와 일치한다. 기존 AGY 세 extractor는 placeholder를 실제 탭으로 바르게 변환한 것을 확인했다. 이 사례에서 prompt 수정 효과는 관찰됐지만 일반 품질·지연·Android HTTP·실기기 통과를 뜻하지 않는다.

## 최신 통합 회귀와 Polyglot 문장 생성

`.artifacts/contextual-quality-e2e/ai-integrated-attachment-regression.log`는 `BUILD FAILED`(19초), 500건 중 coldstart 실패 2·skip 7·error 0을 기록한다. 앞선 491건·실패 4건 기록은 역사 근거로 보존한다.

root가 직접 읽은 `polyglot-sentence-three-contexts.py/json`의 GPU 32-token greedy 3문맥은 1,637.295·964.969·931.233ms였고 반복·물결 반복·관계없는 2019 기사 생성을 보였다. 다음 어절 실험과 다른 문장 생성 실험이며 기기 결과가 아니다. 제품 도입은 반려한다.
