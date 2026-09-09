# 한국어 문맥 추천·금고 품질 개선 계약

기준일: 2026-09-07. 작업 상태: 진행 중. 백로그 정본은 [AI 개인화 백로그](ai-personalization-backlog.md)다.

## 사용자 인수 기준

- 첫 사용 품질은 AI 박스·API·OAuth와 개인 학습 이력이 없는 상태에서 별도로 판정한다. 기본 로컬 추천이 일상 입력을 돕고, 선택 다운로드 팩은 범위를 넓히며, AI 연결은 긴 문맥·개인화 품질을 추가한다. 연결 전 빈 추천을 정상적인 온보딩으로 간주하지 않는다.
- 기본팩만 설치된 조건과 선택팩 설치 조건을 나누어 제공률·상위 후보의 자연스러움·무관 후보·지연을 측정한다. 다운로드하지 않은 사용자의 기본 기능과 설정 접근을 막지 않는다. 추가 콘텐츠의 동의 다운로드·삭제 계약은 유지한다.
- 공백 전 완성 어절, 공백 뒤 다음 어절, 조합 중 부분 어절을 구분한다. 정확히 같은 완성 어절의 후속 근거는 공백 전에도 사용할 수 있지만, 마지막 단어만으로 조사·어미를 바꾸거나 임의 문장을 접합하지 않는다.
- 현재 기본216/선택5261의 제한적 일치와 두 개의 IME 성공 사례는 이 첫 사용 품질 기준을 충족했다는 증거가 아니다. 다양한 일상·업무·질문·반응의 미학습 문맥 평가와 실제 입력 품질을 완료 조건으로 유지한다.

- `내가 뭘` 뒤에 `회의 참석합니다`처럼 관계없는 내용을 붙이지 않는다.
- 다음 어절에는 `어떻게`, `하면`, `잘못`, `그렇게`처럼 앞 문맥에 이어질 수 있는 후보가 필요하다. 이 네 단어를 특정 입력에 하드코딩하는 것은 해결이 아니다.
- 문장 추천은 원문 보존뿐 아니라 조사·어미·의미 연결·말투가 자연스러워야 한다.
- 빈 개인 금고에서도 다양한 한국어 문맥을 지원해야 한다. 모든 추천을 숨겨 오답률만 낮추는 것은 완료가 아니다.
- 내 언어 금고의 학습·추천 통계는 실제 이벤트에 따라 갱신되어야 하고, 무엇을 측정하는지 설명해야 한다.
- 레벨을 크게 보여 주고, 좌측에 `Lv.n | 칭호`와 설명을 함께 배치한다. 예시 숫자 20을 실제 값으로 고정하지 않는다.

## 확인된 현재 구조

`PersonalNgramModel`의 유니그램은 개인 어휘 빈도이며, 해당 문맥 뒤에서 관측된 전이와 다르다. 기존 문장 연쇄는 이 빈도 후보와 고정 collocation을 이어 붙인 뒤 어미만 검사했다. 기존 리랭커도 유니그램을 문맥 근거로 사용했다.

현재 `AiContextualPredictor`는 input continuation 생성·주입을 제거했다. HADA 고정 접합과 서로 다른 관측 n-gram chain으로 문장을 합성하는 경로는 제품 predictor에서 사용하지 않는다. 실제 user phrase·RAG·typed LLM·단어 n-gram은 보존한다. `KoreanSentenceContinuation`은 회귀 원인 재현용 standalone으로 남아 있지만 제품 predictor가 호출하지 않는다. 이것은 잘못된 근거를 제거하는 조치이며, 일반 한국어 의미 모델을 도입하거나 문법성을 보장한 상태가 아니다.

`.artifacts/contextual-quality-e2e/predictor-input-continuation-tests-final.log`의 최종 18건은 pass 18·fail/error/skip 0, `BUILD SUCCESSFUL`(14초)이다. legacy 합성 양성, predictor blank/`해야` 입력 부재 회귀, typed predictor XML을 포함하는 코드 gate다. 자연스러운 coldstart 추천, 빈 금고 지원, 다양한 미학습 문맥의 top-k 품질, 실제 IME E2E는 아직 완료되지 않았다.

금고의 `PredictionMetricsStore`는 조회 함수만 연결되어 있었고, 운영 표시·선택·학습 기록과 저장 호출이 빠져 있었다. 문장 학습은 별도 경로이므로 빈 그래프를 학습 전체 실패로 해석하지 않는다.

## 추천 계약

### Prefix-prefill 출력의 앱 연결 계약

원문 prefix 뒤에서 실제로 생성한 decoded suffix만 변환한다. suffix의 선행 ASCII 공백 또는 prefix의 후행 ASCII 공백이 있으면 `CONTINUATION`, 둘 다 없으면 `CONTINUATION_ATTACH`로 보낸다. payload의 양끝 ASCII 공백만 제거하고 내부 문자열과 원문 prefix는 보존한다. 앱의 기존 삽입 경로가 어절 경계와 선택 후 공백을 처리한다. 모델 special token, 잘못된 제어문자, 빈 결과, 반복된 전체 prefix는 명시적 사유와 빈 후보로 기록한다. 이 변환은 문법·의미 평가가 아니며, 여러 문장을 자르거나 다음 단어 후보를 임의로 만들지 않는다.

저장된 Kanana 출력 12건의 재생은 HTTP 응답 파싱·typed 후보·삽입 문자열의 계약 검증이다. 실시간 모델 호출, 후보 화면 클릭, 실제 IME 입력, Android 성능은 각각 별도의 실행 근거가 필요하다. 실험 adapter를 작성하는 것만으로 기본 provider나 제품 모델을 바꾸지 않는다.

1. 현재 어절 완성과 다음 어절 추천을 분리한다. 조합 중인 `뭘`, 확정했지만 공백이 없는 `내가 뭘`, 공백이 있는 `내가 뭘 `을 각각 검증한다.
2. 다음 어절 추천의 선택은 기존 조합을 보존하고 필요한 어절 경계 공백을 정확히 한 번 넣어야 한다. 후보의 표시에 쓰는 문자열만으로 덮어쓰기 범위를 추측하는 동작은 개선 대상이다.
3. 추천 근거는 개인 관측 전이, 일반 한국어 모델, 실제 문장 검색, 명시적으로 허용한 연결형 모델로 구별한다. 어휘 빈도를 문맥 확률이라고 표시하지 않는다.
4. 문장 후보는 단어별 빈도 순서로 조립하는 것만으로 채택하지 않는다. 구 또는 문장 단위 후보를 문맥과 함께 평가해야 한다.
5. 일반 모델·말뭉치 채택 전 동일 평가 입력에서 현재 기준선과 비교한다. 모델 크기·라이선스·실기기 지연시간·한국어 결과를 확인하기 전 기본 앱 의존성으로 추가하지 않는다.
6. 기존 opt-in, private/sensitive editor, 오프라인·외부 전송 정책을 유지한다. 품질 개선을 이유로 네트워크를 자동 활성화하지 않는다.

## 금고 계측 계약

- 화면 명칭은 **추천 수락률**이다. 분자는 성공적으로 선택한 문맥 추천 수, 분모는 실제 화면에 표시한 문맥 추천 수다. 문법 정확도나 AI 지능 점수가 아니다.
- 표시 목록 생성이나 getter 호출은 노출이 아니다. 화면의 보이는 후보만 세고 같은 추천 세대의 재렌더·두 행 중복·스크롤 왕복은 중복 집계하지 않는다.
- 성공한 삽입만 수락으로 기록한다. 실패, 미표시 후보, 오래된 추천 세대, 민감 입력에서는 수락을 기록하지 않는다.
- 학습량은 확정 문장이 실제 개인 모델에 반영된 증분이다. 프로필 분석 배치 완료량과 구분한다. 신규 어절은 개인 어휘 수의 양의 증분이다.
- isolated emulator에서 Main→상세 그래프→「지금 즉시 분석 및 동기화」를 실행한 수치는 graph Ngram의 즉시 학습과 분석 완료 집계를 구분해야 한다. synthetic 테스트 누적 데이터에서 동기화 전 분석 0·ngram 학습 14단어·graph 11·main pending 11이었고, AI provider 없음 대화상자 뒤 분석 11·단어쌍 8·어미 2·상용구 5·pending 0·graph 11이었다. 이는 온디바이스 분석·집계 경계의 단일 런타임 관찰이며 문장 추천 품질, 각 원문의 일대일 대응, 재시작 보존, 후보 선택 full cycle의 증거가 아니다.
- 원문과 후보 내용은 통계 파일에 저장하지 않는다. 기존 집계만 지연 저장하며 화면 재진입·프로세스 재생성에서 보존을 검증한다.
- 절약 키 입력 수는 실제 산정 근거가 확보되기 전 임의 추산하지 않는다. 화면에서 제공할 수 없는 측정값은 그 상태를 분명히 설명한다.

## 어미 수집 계약

- 수집기는 문장 조각별로 실제 표면 종결 표현을 최장 1개만 수집한다. 비교 전 구두점과 닫는 기호를 제거한다.
- 기존 상용구와 bigram 수집은 보존한다. `ㅂ니다` 같은 표기와 완성형 불일치, 좁은 고정 목록, 구두점이 붙은 `endsWith` 비교로 어미를 누락하지 않는다.
- LLM 결과가 빈 배열일 때만 로컬 후보를 보강한다. LLM 결과가 있는 경우 대체하거나 합성하지 않는다.
- 이는 완전 형태소 분석이나 일반 한국어 언어모델을 제공하는 해결이 아니다. 과거 원문은 보존하지 않았으므로 기존 24문장을 자동 재추출하지 않으며 이후 입력과 pending 대상만 처리한다.
- 코드·단위검증은 13건 PASS(skip/fail/error 0, `GRADLE_EXIT_CODE=0`)까지 확인됐다. A35에서 `감사합니다. ` 입력 뒤 분석 문장·어미가 24·0에서 28·2로 바뀌고 force-stop 재시작 후에도 28·2가 유지된 것은 제한된 추출 누락 수정과 수치 저장의 실기기 PASS다. 이는 완전 형태소 처리나 추천 결과를 포함한 전체 B22 완료가 아니며, B22는 `IN_PROGRESS`다.

## 입력 세션과 관측 근거 계약

- `onStartInput`과 restart 입력 경계에서는 먼저 기존 buffered submit을 정리하고, 미확정 collector pending만 폐기한다. 기존 history와 저장 데이터는 보존한다.
- 1~3자 짧은 flush도 buffer를 비워 다음 입력 세션으로 혼합되지 않게 한다. 정상 submit과 `onFinish` flush는 그대로 유지한다.
- 일반 커서 이동과 교체 편집의 추적은 아직 해결하지 않았으며, 입력 세션 경계 처리의 완료로 넓혀 판정하지 않는다.
- `profile()`의 LLM 파싱이 성공해도 `cannedPhrases`와 `frequentBigrams`는 실제 input sentences의 로컬 집계만 사용한다. LLM은 tone과 ending 보강만 한다.
- parse API, 저장 schema, 기존 사용자 데이터는 변경하지 않는다. 과거 데이터의 provenance도 복구하지 않는다.
- PC의 Qwen 0.6 공식 GGUF 로컬 모델 12개 prefix raw/chat 비교 실험은 최신 run에서 제품 도입 반려로 판정됐다. 제품 모델을 채택하지 않았고 품질 통과 근거도 아니다.
- Qwen3-4B-Q4_K_M 공식 GGUF의 local SHA-256·크기 2,497,280,256B는 공식 metadata와 일치했다. v1 `내가 뭘` raw의 24,829.628ms(load 8.718초·prompt 15.757초·eval 0.328초)와 `WORD소나요.Word`·`WORD알아요.Word`·`WORD말해요.Word`는 `AiAction.kt:68`의 Kotlin `WORD${'\t'}` 보간을 기존 Qwen harness `instruction()`이 실제 탭으로 변환하지 못한 결함이 확인된 원본이라 모델 품질 판정에서 분리한다. 교정한 v2는 3/3 `invalid_wire`이며 `내가 뭘` 응답은 `내가 뭘을`·`내가 뭘해`·`내가 뭘했`이다. 입력별 wall 시간은 `내가 뭘` 3,559.136ms(load 3,224.918ms)·`내일 판교에서` 730.359ms·`오후 2시 회의` 501.671ms였다. GPU `size_vram`은 2,874,062,929이고 owned PID 29356 종료 returncode는 0이지만 shell exit은 수집하지 않았다. v3은 세 입력의 prefix와 JSON 형식을 보존했으나 `내가 뭘`→`내가 뭘을 하고 있는지 말해줄 수 있어?`(5,470.156ms)가 목적격 중복 비문이었다. 나머지는 `내일 판교에서 쇼핑하러 가요.`(426.218ms), `오후 2시 회의를 진행하겠습니다.`(372.107ms)였고 owned PID 55908 종료 returncode 및 `qwen4b-v3-simple-clause-exitcode.txt` exitcode는 모두 0이다. 형식 통과와 품질을 분리해 몇 건의 품질 통과 수치를 산정하지 않는다. 현 설정의 제품 채택은 반려하고 추가 Qwen 튜닝은 중단한다. AGY 실험 전체의 결함이나 기기 지연·기기 품질 통과로 일반화하지 않는다.
- Kanana 1.5 2.1B Instruct 2505 공식 카드의 pinned revision `7df4bc35ccd610e451809d7106e1c3cf82bfd44c`는 BF16 PyTorch GPU 단순 한국어 1문장 진단에서 `constrained_decoding=false`였다. init 13.241초를 generation과 분리한 raw 결과는 `내가 뭘 도와드릴까요?<|eot_id|>`(5,040.9801ms), `내일 판교에서 만나요.<|eot_id|>`(2,557.6725ms), `"오후 2시 회의는 5층 회의실에서 진행됩니다."<|eot_id|>`(5,793.7814ms)다. raw EOS는 보존하고 UI 표시에 decoded를 사용할 수 있으나 JSON을 따르지 않는다. 회의 응답은 앞 따옴표로 원문 prefix가 불일치하고 `5층`을 임의로 넣었다. 판교 연결의 자연스러움은 단일 관찰이며 일반 품질 PASS가 아니다. Qwen은 강제 JSON, Kanana는 지시문만 사용해 형식 조건이 다르다. 카드의 공식 Apache-2.0 표기는 법적 결론이 아니다. 제품 채택은 유보하고 추가 추론·기기 검증은 하지 않았다.
- Kanana prefix-prefill은 공식 chat template 뒤 assistant에 exact raw prefix를 고정하고, 한국어·특정 사실 금지 system 지시로 BF16 greedy 24 token을 12개 prefix에 각 1회 실행했다. `.artifacts/contextual-quality-e2e/kanana-prefix-prefill-results.json`의 전 raw는 보존하며 대표 결과는 `내가 뭘 하고 있었지?`(2,537.9227ms), `내일 판교에서 만나요.`(1,117.7969ms), `비가 많이 와서 우산을 챙겨야겠어.`(3,079.8257ms)다. 12 EOS·token-limit 0·exit 0이지만 3번·10번은 두 문장으로 확장됐고 4번은 입력 끝 공백과 suffix 선행 공백이 중복됐다. 사용자 의도 적합성은 미검증이다. prefix 고정과 prompt 변경을 동시에 했으므로 단일 요인 개선으로 단정하지 않는다. init 8.178초, generation 1.118~4.283초였으며 preflight GPU 기존 점유는 9,520/12,288MiB다. 공유 메모리·oversubscription을 측정하지 않아 전용 GPU 성능으로 일반화하지 않는다. 기존 wire와 다른 raw 출력 방식이며 별도 코드 0..3 protocol은 구현하지 않았다. 원래 앱·companion의 정확히 3개 요구는 유지한다. 제품 미통합·기기 미검증이고 B19 완료 근거가 아니다.
- Kiwi v0.23.2 `cong` 형태소 언어모델의 `.artifacts/contextual-quality-e2e/kiwi-cong-raw-24.jsonl`은 12개 prefix에 `openEnding=false/true` 각 12건, 총 24건 모두 prediction available이며 raw top 20을 480개 기록했다(raw SHA-256 `fe25559413aa068868d391c352198942bd8f3b07467562094acd0b6568a70c07`). threads 1, `forwardLMids`, BOS/EOS 미삽입 조건에서 metadata의 model load 611.035ms, `KiwiBuilder.build` 1,114.05ms, reverse index 48.0804ms(형태소 657,472개·key 208,051개), context 생성 포함 predict 0.6389~1.2284ms다. `내가 뭘` top 5는 내부 분해 표기를 읽기 쉽게 적으면 `하/VV`·`잘못/MAG`·`알/VV`·`어쩌/VV`·`보/VV`이고 top 20에는 `어떻-`·`잘-`·`모르-`도 있다. `내일 판교에서`는 `은/JX`·`벗어나/VV`·`도/JX`·`나오/VV`·`열리/VV`가 상위다. 점수는 확률이 아닌 affinity이며, 화면에 바로 쓸 어절·완결 문장·조사/어미 표면화·공백 경계·형태소 이어 생성·문맥 적합성·Android 성능을 검증하지 않았다. 초기 `Get-FileHash` 실패는 모델 실행 전이며 .NET SHA 뒤 attempt2의 원본 JSON을 사용했다. PID 18088 종료·24건 산출물·JSON 정상·stderr 0B는 확인했지만 종료 코드는 수집하지 않았다. 제품 채택은 유보다.

앞선 raw ranking 단계의 화면 후보·문장·표면화·Android 미검증과 별개로, 후속 Kiwi surface `.artifacts/contextual-quality-e2e/kiwi-surface-60.jsonl`은 `openEnding=true`, threads 1, canonical exact mapping, baseline의 분석 `token.str/tag` 사용, `inferRegularity=false`, `wordPosition`에 따른 명시적 `Space` 지정, raw affinity 누적 없음 조건에서 12개 문맥의 초기 top 5마다 greedy top 1을 최대 8 형태소까지 이었다. harness·outer launcher exit 0과 JSON strict parse를 확인했으며 12 case·60 branch 중 exact prefix 55, EF/SF terminal 36, empty form stop 20, morpheme limit 4, empty suffix 8이었다. 이 수치는 문법·품질 PASS가 아니다. `배송이 아직 안 왔어요`, `이거 어떻게 생각하십니까` 같은 연결은 관찰됐지만 `혹시 시간 괜찮으시면 안 돼요`는 의미에 맞지 않고 `늦어서 미안성을 가지고 있다`, `그렀더니`는 형태 오류다. `그건 내가 한 게`의 5 branch는 baseline이 `그건 내가 한 것이`로 바뀌어 exact prefix를 잃고 suffix가 모두 null이었다. Android 측정과 제품 통합은 없으며, 실행 증거는 수용하되 제품 문장 추천 엔진 채택은 반려한다.

## 조사 결과와 적용 한계

- [Next Phrase Prediction, EMNLP 2021](https://aclanthology.org/2021.findings-emnlp.378/): 구 단위 완성 학습을 다룬다. 이메일·학술 영어 결과이며 한국어 적용 성능은 별도 검증해야 한다.
- [KoSEnd, ACL 2025](https://aclanthology.org/2025.acl-srw.29/): 한국어 문장 종결의 자연스러움을 별도로 평가한다. 키보드 다음 단어 벤치마크 자체는 아니다.
- [Kiwi 공식 저장소](https://github.com/bab2min/Kiwi): 한국어 형태소 분석과 Android 바이너리 경로가 있다. 형태 분석 정확도를 문장 추천 정확도로 대체하지 않는다.
- [KenLM 공식 저장소](https://github.com/kpu/kenlm): 말뭉치 기반 언어모델 학습·조회 후보다. 현재 새글 Android 통합 및 한국어 자동완성 성능은 미검증이다.

현재 결정은 후보 생성과 문맥 평가를 분리해 비교 실험하는 것이다. 특정 모델을 채택했다는 결정은 아직 없다. 개인 데이터 없는 기준선과 개인 데이터 적응 결과를 따로 보고한다.

## 로컬 언어모델 PC 실험 평가(2026-09-07)

최신 근거는 `.artifacts/korean-lm-benchmark/runs/20260907T205651/results.jsonl`과 같은 run의 metadata다. 24개 raw 결과는 직접 읽어 평가했다. 공식 Qwen 0.6 Q8 모델(revision `23749fefcc72300e3a2ad315e1317431b06b590a`, 639,446,688B)을 RTX 3080 Ti PC에서 실험했으며, 기존 데이터나 앱의 제품 모델 채택은 변경하지 않았다.

판정은 **제품 도입 반려**다. raw `내가 뭘`은 `해야 할지 선택하는 게 중요하다면...`처럼 시작 가능한 결과도 있었지만, `나는 지금`은 `까지 쓰여写的 글...`로 중국어가 섞였고, `점심은`은 `높은 수준의 산소를 필요로...`로 의미가 어긋났다. structured 12개는 JSON 4+2 형식을 통과했으나 다음 어절 대신 전체 문장·문맥 반복이 다수였고 suffix 중복도 있었다. API 24건 성공은 품질 통과가 아니다.

RTX 3080 Ti PC 기준 raw 첫 총 지연은 2,226ms(load 2,108ms 포함), 이후 raw는 264~370ms였다. 이는 휴대전화 지연시간 측정이 아니다. 첫 실험(run `205301`)은 raw `true` 누락으로 채팅 템플릿이 적용된 한계가 있어 보존하되, 이번 판정은 이를 교정한 최신 run을 기준으로 한다.

## Polyglot token-boundary PC 실험 평가(2026-09-07)

`.artifacts/polyglot-benchmark/token-boundary-runs/20260907T212227/`의 metadata/results와 `.artifacts/polyglot-benchmark/next-eojeol-runs/20260907T211804/`의 이전 결과를 직접 읽어 평가했다. 최신 12개 실행·48개 후보에서 관찰된 후보 대부분은 문맥에 연결될 수 있었으나, `나는 지금 이시간이`는 띄어쓰기 문제가 있었고 일부는 짧은 기능어로 편향됐다. 이는 의미 평가의 PASS 수가 아니며 제품 채택은 미결정이다.

초기 후행 공백 prefix 실험은 `내가 뭘` 뒤에 `.`·`!`·`^^`·`^` 등이 나와 실패했다. tokenizer 경계 조건 차이가 결과에 영향을 주므로 이를 모델 품질만의 문제로 단정하지 않는다. 최신 run은 `prefix.rstrip()` 뒤 tokenize하고 생성 결과의 선행 whitespace를 요구한다. 이전 raw run에서 확인한 beam suffix 반복과 zero-width 한계도 남아 있다.

RTX 3080 Ti GPU의 지연은 191~663ms이며 휴대전화에서는 측정하지 않았다. 기존 데이터와 앱 제품 통합은 하지 않았다.

## Polyglot GGUF 변환·Android standalone 상태(2026-09-07)

`.artifacts/polyglot-benchmark/gguf-conversion/metadata.json`과 `conversion.log`에서 GPTNeoX architecture 처리는 통과했으나 BPE pretokenizer를 인식하지 못해 exit 1로 끝났고 GGUF는 생성되지 않았다. Android standalone build는 성공했고, A35의 `.artifacts/polyglot-benchmark/native-build/a35-help.log`에서 `--help` exit 0을 확인했다. 이는 추론 가능성이나 추론 속도 측정이 아니다.

tokenizer-only adapter 검증은 진행 중이며, 제품 통합과 모델 채택은 하지 않았다.

## Polyglot tokenizer adapter·A35 추론 탐색(2026-09-07)

원본 native tokenizer 43개 중 38개는 일치했고 5개는 split mismatch였다. `.artifacts/polyglot-benchmark/tokenizer-adapter/segmented-comparison/20260907T214116/metadata.json`과 `segmented-native-comparison.json`의 53/53 비교는 코드 검토 범위의 결과이며, 필수 전처리는 split U+0CF1 segment를 concat하는 것이다.

`.artifacts/polyglot-benchmark/gguf-adapter-conversion/20260907T214456/metadata.json`에서 full weights adapter 변환 F16/Q4는 exit 0이다. Q4는 859,539,552B, SHA-256 `7057dde...93a7`이다. 이는 tokenizer adapter metadata가 필요하다는 전제의 변환 결과이며 제품 통합 증거가 아니다.

`a35-bench-p32-n16-t4.json`에서 A35 CPU 4 threads, repeat 3은 pp32 평균 1,571,640,704ns·20.390085 tok/s, tg16 평균 1,119,798,153ns·14.358516 tok/s다. IME 지연시간, KV 재사용, full beam을 측정한 결과가 아니다. `a35-greedy-naega.txt`와 `a35-greedy-naega.stderr.log`는 exit 0이며 `내가 뭘 잘못했는지 모르겠어.”“내가 뭘 잘못했는데?`에서 첫 연결은 자연스럽지만 뒤 반복이 남았다. warm load 379.12ms, prompt 190.63ms, 3 token eval 1,296.42ms, 15 runs total 1,677.23ms/18 tokens도 제품 채택 근거가 아니다.

## 완료 게이트

### 미분석 문장 소비 계약 (2026-09-08)

어미 목록은 카테고리별 최대 15개의 기억 목록이다. 최근 분석 묶음의 어미를 먼저 두고 기존 목록을 이어 붙여 중복을 제거한다. 최초 저장에도 같은 상한을 적용한다. 신규 어미가 비어 있으면 기존 목록을 유지한다. 기존 파일을 읽는 것만으로 목록을 변경하지 않는다. 표시 개수는 현재 기억하는 표현 수이며 누적 관측 횟수가 아니다.

- 저장 프로필의 런타임 복원은 미분석 문장 버퍼와 분석 횟수를 변경하지 않는다.
- 자동 문장 분석과 대시보드 즉시 분석은 같은 버퍼의 현재 문장 묶음을 직렬로 소비한다. 예약된 콜백의 과거 snapshot을 다시 분석하지 않는다.
- 기기 내 분석과 컴파일이 정상 반환된 문장 묶음만 버퍼에서 제거한다. 처리 중 예외가 발생하면 해당 묶음을 보존한다. 먼저 성공한 다른 카테고리의 소비는 유지한다.
- 분석 중 다른 스레드의 입력은 버퍼 잠금 뒤에 추가되어 다음 분석 대상으로 남는다. 컴파일러는 카테고리 전체 버퍼를 독자적으로 삭제하지 않는다.
- 버퍼 잠금 안에서는 기기 내 분석만 실행한다. 자동 수집에서 LLM을 호출하지 않으며, 네트워크 지식 그래프 강화는 별도 사용자 설정과 실행 경로를 따른다.
- 동일 배치 중복 분석, 분석 중 새 입력 보존, 실패 후 재처리, 프로필 복원 시 보존을 검증한다. 이 계약만으로 파일 저장 실패의 복구나 기기 E2E 완료를 주장하지 않는다.
- persistence 실패 재현 2건 뒤에는 disk write 성공 후에만 cache를 갱신하고, 실패한 pending을 보존하며 저장이 복구된 뒤 다시 동기화했을 때 한 번만 반영됨을 검증한다. 실패 Toast를 표시하도록 구현했으며 기기에서는 미검증이다. 집중 29건(fail/error/skip 0, `typing-dna-persistence-fix-tests.log`의 `BUILD SUCCESSFUL` 16초)은 이 경로를 검증한다. `VaultFile`은 tmp fsync→base의 bak 보존→rename commit을 수행하고 실패 시 base/bak 이전 바이트를 보존하며, rollback 실패 backup 읽기·동일 프로세스 canonical path 직렬화·cleanup 실패 새 base 정본을 26건(fail/error/skip 0, `vaultfile-recovery-tests.log`의 `BUILD SUCCESSFUL` 19초)으로 검증했다. 앞선 512 통합과 해당 APK는 VaultFile 수정 전이고, 후속 528 통합·x86 빌드는 VaultFile 수정을 포함한다. OS 전원 차단·디렉터리 fsync·멀티프로세스·crash 완전 보장과 `TypingDnaVault.persistStaging` 예외 삼킴은 별도 미해결이다.

### 조사·어미 이어 붙임 계약

실제 CLI 생성 실험 `backend-quality-three-contexts.json`에서 `오후 2시 회의` 뒤 `에 참석해 주세요.`가 반환됐다. 기존 삽입기의 강제 공백은 이를 `회의 에`로 바꿨다. 생성 결과의 의미 평가와 별도로 삽입 경계를 보존해야 한다.

- `WORD`는 다음 어절, `CONTINUATION`은 공백을 두고 잇는 문장, `CONTINUATION_ATTACH`는 직전 어절에 붙이는 조사·어미 suffix다. 모델이 명시적으로 유형을 선택하며 제품에서 조사 목록으로 추측하지 않는다.
- 붙임 삽입은 실제 커서 앞이 공백이면 거절한다. 사용자가 입력한 공백을 지우거나 원문을 교체하지 않는다. 일반 이어쓰기는 기존 공백을 중복 추가하지 않는다.
- 요청과 캐시는 끝 공백의 존재를 구분한다. 입력 시작의 공백과 내부 연속 공백 정규화는 유지하되, 끝 공백을 제거해 어절 경계를 합치지 않는다.
- 두 단어와 한 문장이라는 응답 개수, 앱·입력 세션 격리, 오래된 결과 폐기, 민감 입력 게이트는 유지한다. `CONTINUATION_ATTACH` wire, `JoinMode.ATTACH`, 끝 공백 시 거절·숨김, 끝 공백 cache 구분, ContinueTyping client 끝 공백 보존, reranker의 다음 어절 bridge 제외까지 코드와 focused 8클래스 69건(pass 69·fail/error/skip 0, `BUILD SUCCESSFUL` 17초)으로 검증됐다. 실제 기기 통과는 뜻하지 않는다.

### 선택 capability: `continuation_abstention` v2

`continuation_abstention`을 광고한 provider의 `ContinueTyping` action에만 `Responses` format.name `saegeul_continuation_v2`와 suggestions 배열의 `minItems=0`, `maxItems=3` schema를 적용한다. `WORD`는 0~2개, `CONTINUATION` 또는 `CONTINUATION_ATTACH`는 합계 0~1개이며 전체는 0~3개다. 단어를 먼저 배치하고, 후보가 없을 때는 `[]`로 기권할 수 있다. capability가 없는 provider와 `ContinueTyping` 이외 action은 기존 exact 계약을 그대로 유지한다. 외부 모델 공급자가 이 capability를 광고하려면 이 계약을 지원해야 한다. Chat Completions에서는 기존 `json_object` 형식을 유지하며 동일한 capability/action 조건으로 지시문과 응답 파서를 선택한다.

backend는 capability marker와 정확한 schema를 검증한 뒤에만 prompt·normalize 단계까지 부분 허용한다. marker 오류는 거부한다. 명시적 `suggestions: []`만 빈 성공이고, invalid array·누락·blank·중복·3개 초과는 오류다. 정상 응답의 suggestions 배열이 비어 있을 때만 prefetch LRU에 빈 결과를 cache하며, 비어 있지 않은 wire를 필터링한 결과가 0개면 폐기한다. 기존 scope·clear·eviction은 유지하고 TTL은 추가하지 않는다. v2는 strict JSON만 허용하며 prose/code fence에서 객체를 추출하는 legacy 복구를 사용하지 않는다.

이는 강제 후보 개수가 약한 후보를 채우도록 만드는 압력을 없애기 위한 계약이다. 추천의 문맥 적합성·의미를 검증하거나 보장하는 장치는 아니며, 모델 교체나 배포를 뜻하지 않는다. Android 구현은 집중 57건(fail/error/skip 0, `continuation-abstention-tests.log`, 17초)과 후속 ClientTest 23건(동일하게 0, `continuation-abstention-client-tests.log`, 13초)으로 검증했다. 두 실행은 중복되므로 합산하지 않는다. companion은 `continuation-abstention-companion-tests.log`의 26건·OK·exit 0으로 검증했다. root는 malformed JSON 복구 경로를 반려한 뒤 strict 파싱 재작업과 실제 로그를 확인해 수용했다. 실행 중인 공급자·모델 설정은 바꾸지 않았고 새 계약의 Android HTTP·모델 품질 E2E는 아직 미검증이다.

### 품질 검증 원칙

1. 메시지·업무·검색·일상 문맥, 반말·존댓말, 조사 생략, 조합 중 입력을 포함한 평가 목록을 고정한다. 사용자 예문은 회귀 목록에 포함하되 학습용 정답 주입으로 통과시키지 않는다.
2. 학습·검증 자료는 출처와 중복 그룹 기준으로 분리하고, 평가 문장 및 유사 복제 문장이 학습에 섞이지 않았음을 확인한다.
3. top-k 문맥 적합성, 추천 제공 비율, 비문·무관 후보 비율을 함께 보고한다. 단일 정답 문자열 일치만으로 자연스러움을 판정하지 않는다.
4. 생성·조회·랭킹의 단위 테스트와 실제 IME 표시·선택·원문 보존·공백·정확히 한 번 삽입을 모두 확인한다.
5. 금고는 실제 입력 → 학습 → 그래프, 실제 표시 → 성공 선택 → 수락률, 재진입 및 재시작 보존을 검증한다.
6. 큰 레벨·칭호·설명은 작은 화면과 큰 글꼴에서 렌더링을 확인한다. 기존 레벨 상한은 5이며 상한 확장은 별도 산정 계약 없이 임의 구현하지 않는다.
7. 관련 단위 테스트 통과만으로 B19~B26 또는 전체 백로그 완료를 표시하지 않는다.
8. B23은 세션 경계의 pending 혼합만, B24는 LLM이 사용자 관측 근거를 대체하지 않는 경계만 대상으로 검증한다. 각각 일반 편집 추적·과거 provenance 복구·제품 모델 채택을 완료로 주장하지 않는다.
9. isolated emulator의 append·physical Delete 최신 진단은 6건 중 5 pass·1 fail이며, 첫 stale snapshot의 `activeInstance=false`와 viewport 변경 전후의 차이가 남아 있다. 5초 deadline·기존 assert·framework DEL dispatch를 유지한 결과지만 B23 전체 완료나 일반 편집 품질의 증거가 아니다.
10. B25의 readiness 설정값은 유효 OAuth 세션이나 실제 프리페치 성공의 증거가 아니다. B26 단위검증·기기 append 성공도 실제 다음 어절의 문맥 적합성을 보장하지 않으며, `내가 뭘`의 부자연스러운 후보는 실패 증거로 유지한다.
11. OAuth 연결됨·profile 표시가 세션 유효성이나 prefetch 성공을 보장하지 않는다. `AiProviderException` 관찰과 missing-session 재로그인 UI·login E2E 미검증을 별도 게이트로 유지한다.
12. `Scope(packageName,inputSessionEpoch)`·`RequestKey(normalizedContext)` cache 분리는 다른 앱·세션 completion 거절 계약이며 앱별 학습 분포 schema나 TPO 학습 분류 완료를 뜻하지 않는다. 실제 학습 앱 분포와 기기 scope E2E는 별도로 검증한다.
13. Native rootfix 결과는 `.artifacts/polyglot-benchmark/native-next-eojeol/20260907T224430/`의 `results-boundary-rootfix.json`, exit 0, self-test passed로 확인됐다. Windows CPU standalone Q4·새 surface filter에서 2개 prefix만 측정했다: `내가 뭘` 2,887.116ms에 `할`·`하고`·`잘못하고`·`잘못했지`, `오늘 날씨가` 2,794.299ms에 `너무`·`참`·`정말`·`많이`. HF 상위 3개 불일치는 남아 있다. 이전 `results-boundary-final.json`은 검증 우회 오류로 유보하며 최신 rootfix와 구분한다. 이는 일반 품질·휴대전화 지연·제품 통합 근거가 아니다. 후속 batched 결과는 15항을 참조한다.
14. 단일 AGY synthetic 생성의 46.441초 generation·50.752초 total은 client/UI E2E나 모든 provider의 일반 API 지연을 뜻하지 않는다. 이 실험 조건에서는 실시간 다음 어절에 부적합하며, 기기 검증은 별도다.
15. `.artifacts/polyglot-benchmark/native-next-eojeol/20260907T224430/q4-12prefix-candidate-quality-comparison.md`의 batched Windows CPU Q4 12-prefix 후보는 886.988~1,115.670ms였고, 12개 중 4개에서 sequential과 top4 순위가 달랐다. `내가 뭘`의 `할`·`하고`·`잘못했는지`·`잘못하고`, `오늘 날씨가`의 `너무`·`참`·`정말`·`많이`처럼 접속 가능한 후보는 관찰됐지만, 부분 어절만으로 완성 문장의 자연스러움을 입증하지 않는다. prior two-prefix의 numeric 1e-3 equivalence 실패는 유지되며 F16 same-path도 한계를 초과해 단순 Q4 오류로 단정하지 않는다. 모바일 beam 지연과 일반 품질은 미검증이라 제품 채택은 유보한다.
16. `polyglot-sentence-three-contexts.py/json`의 GPU 32-token greedy 문장 생성은 3문맥에서 1,637.295·964.969·931.233ms였지만 반복·물결 반복·관계없는 2019 기사 생성을 보여 도입을 반려한다. 이는 다음 어절 실험과 구분되며 기기 지연이나 일반 추천 품질의 대체 증거가 아니다.
17. `backend-complete-clause-one-context.json`의 새 완결 문장 prompt high 단일 호출은 46,028ms에 `오후 2시 회의`를 `오후 2시 회의에 참석해 주세요. `로 완결했다(`AiAction.kt` source SHA-256 `e29eff0761e8a1896ce34c17cb6becf10d2e83ab0cd2e34b9517d1a9995e8d4d`, prompt SHA-256 `e4b581e212bbaa90d899a1a8eef4fa541266372f1f5a62d9ba8ec2b98c654e22`). 기존 AGY 세 extractor의 placeholder→탭 변환은 확인됐다. 반면 boundaries·low artifact는 구버전 prompt SHA이며 당시 본문이 없어 현재 prompt와 완전 재검증할 수 없다. 단일 사례의 prompt 효과이며 일반 품질·지연·Android HTTP·실기기 통과로 확대하지 않는다.
