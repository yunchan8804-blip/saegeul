# 한국어 경계 preedit와 이어쓰기 추천 후속

상태: 제품 코드와 focused 단위 검증을 반영했고, A35에서 engine direct virtual key와 UI accessibility click으로 실행한 최종 6개 사례도 통과했다. 이 결과는 이 계측 범위의 완료이며, 모든 앱·물리 터치키 입력 전체의 증명이나 Gemma 문장 품질 완료는 아니다.

## 요구와 적용 경계

입력 중 조합이 있으면 client preedit을 우선 사용한다. client preedit이 비어 있을 때만 현재 engine panel preedit을 읽는다. 어느 경우에도 buffered 접두사를 유지하고, 같은 구절을 두 번 붙이거나 후보마다 서로 다른 접두사를 보지 않게 한다. 후보 선택도 이 동일한 접두사와 편집기 상태를 기준으로 일관되게 적용한다.

IME 초성 `ㅅ`, `ㄱ`, `ㅇ` 각각의 단일 초성처럼 아직 완성되지 않은 한글 조합은 client preedit 우선 경로에서 즉시 후보를 찾는다. client preedit이 없는 편집기에서는 engine panel preedit을 같은 목적의 대체 입력으로 쓴다. 이 우선순위는 현재 조합 문자열을 잃지 않기 위한 것이며, panel 값을 새 입력·학습 재료·공개 생성 입력으로 저장하지 않는다.

같은 문장 안의 prefix 검색·index·terminal-empty 역사와 기존 lookup 계약은 유지한다. 완성 문장 뒤 제품 전체 후보를 비우던 과거 조건은 이번 요구로 superseded되며, 완성 문장 경계와 공백 뒤 추천은 별도 경계로 처리한다. 기존 독립 평가 fixture와 수치도 변경하지 않는다.

## 공백 뒤 이어쓰기

완성 문장 뒤 공백에서는 로컬 discourse 기반의 다음 단어 후보를 낮은 우선순위 `이어쓰기`로 제공한다. 이 후보는 Gemma 생성 결과와 별개이며, 학습 입력이나 공개 재료 생성 입력을 사용하지 않는다. 기존 문장 안 lookup 후보보다 우선하지 않고, 후보가 없으면 빈 결과를 자연스러운 상태로 둔다.

연결 어미 `-는데`, `-은데`, `-지만` 뒤에서는 같은 문장을 계속 쓸 단어를 이어쓰기 후보로 찾는다. 한글 종결 문장 뒤에서만 다음 문장을 시작할 수 있는 단어를 찾는다. 적용은 `ContextualAppend`를 사용해 앞 문장을 보존한다. 기존 문장을 대체하거나 prefix를 다시 삽입하지 않으며, 선택 뒤에도 공백·조사·어미·종결 상태가 일관돼야 한다.

## 남은 구현과 검증

client preedit 우선, panel preedit 대체, buffered 접두사 중복 방지, 이어쓰기 우선순위와 `ContextualAppend` 연결은 제품 코드에 반영됐다. Gemma 또는 개인 금고 문장을 이 경로의 입력으로 보내지 않으며, 공개 재료·독립 평가 fixture·측정 수치를 바꾸지 않는다.

## 현재 검증 증거와 남은 결함

focused 단위 검증의 첫 실행은 40개 통과와 기존 `Ignore` 1개였고, overlap 수정 뒤 `rebuild-02`는 40개 실행에서 실패·오류·스킵 0개로 끝났다. A35에는 SHA-256 `8AA6ECF25153D3C807034F0BBED6D466B9204F280CB6F69F97444E117A948F74` 앱을 설치했다.

첫 실기기 6개 사례는 모두 실패했다. 단일 초성 후보 자체는 보였지만 선택 뒤 `ㅅ선생님`처럼 초성이 남았다. 문장 입력은 harness가 물리 키 `state=0`, `code=0`으로 보내 `ㅁ`으로 바뀌었다. 제품 코드는 `calculateReplacementOverlap`에 초성만인 `chos` 입력, 혼합 초성의 분해, 끝 공백 앞 단어 보존을 추가했다. 계측은 실제 `VirtualKeyEvent`·`Scancode`와 space key를 사용하고 main idle을 기다리도록 고쳤다.

두 번째 실기기 6개 사례도 실패했지만, 후보는 58~151ms에 `available` 상태였고 source도 기록됐다. 실패 원인은 candidate UI 검사였다. 단일 diagnostic은 source가 56ms에 `discourse_continuation`으로 기록됐으나, 검사기가 `그리고`만 정확히 찾았다. 원본 PNG와 IME node JSON에는 실제 `그리고 이어쓰기` badge가 보인다. 따라서 후보가 화면에 없었던 것이 아니라 badge 전체 텍스트와 맞지 않는 검사 오류로 확정했다.

이 증거는 로컬 `이어쓰기` 단어 후보의 표시 범위만 확인한다. 후보 전체 문장의 생성 품질이나 Gemma 생성 품질을 완료로 판단하지 않는다.

## A35 6개 사례 완료 결과

제품 APK SHA-256 `8AA6ECF25153D3C807034F0BBED6D466B9204F280CB6F69F97444E117A948F74`와 테스트 APK SHA-256 `B9E3489285A85D164051EE9C5834EE4B9629E2D9C9814941F28376F266476AA6`를 A35에 설치했다. `device-third.log`는 13.379초, `OK (6 tests)`, instrumentation exit 0을 기록했다.

| 사례 | 후보 가능 / 표시 |
| --- | --- |
| 종결문장 뒤 공백 | 83ms / 128ms |
| `-는데` 뒤 공백 | 98ms / 115ms |
| 단일 초성 `ㅇ` | 155ms / 170ms |
| 종결문장 직후 | 91ms / 115ms |
| 단일 초성 `ㄱ` | 67ms / 77ms |
| 단일 초성 `ㅅ` | 151ms / 158ms |

여섯 사례 모두 `candidateVisible`과 `candidateSelected`가 `true`다. 세 초성 사례는 `selectedTextMatchesCandidate`와 `residualInitialAbsent`도 `true`이며, 문장 경계 사례는 `contextPreserved`와 `duplicateSpaceAbsent`가 `true`다. 따라서 다음 A35 계측 gate는 완료했다.

- 단일 초성 `ㅅ`, `ㄱ`, `ㅇ` 각각의 조합 직후 후보가 현재 preedit을 기준으로 표시된다.
- 완성 문장 뒤 공백과 연결 어미 뒤에서 이어쓰기 후보가 기존 lookup보다 낮은 우선순위로 표시된다.
- 후보 선택이 `ContextualAppend`로 앞 문장을 보존하고, 접두사 중복이나 선택 불일치가 없다.

이 gate는 engine direct virtual key와 UI accessibility click에서만 확인했다. 다른 앱 편집기, 실제 물리·터치키의 전체 경로, 온라인·오프라인 상태와 Gemma 준비 여부 조합은 이 실행의 증거 범위 밖이다.

## Fold6 전달

같은 제품 APK SHA-256 `8AA6ECF25153D3C807034F0BBED6D466B9204F280CB6F69F97444E117A948F74`를 `.artifacts/korean-boundary-preedit-20260910/Saegeul-hangul-continuation-20260910.apk`에서 Fold6 `100.109.125.97`로 Taildrop 전송했고 명령은 exit 0으로 끝났다. 이는 파일 전달만 증명하며 Fold6 설치나 실기기 검증을 뜻하지 않는다.

## 사용자 재현 질의 판정(2026-09-11, A35 메시지 앱)

사용자가 메시지 앱에서 "그런데" 입력 뒤 공백에서 후보가 비었다고 보고했다. 확인 결과 입력 칸은 대화 목록 **검색창**(`search_src_text`)이었고, 덤프한 EditorInfo는 `inputType=0x10001`(`TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS`)다. `EditorPrivacyPolicy.isConversationalTextField`는 `NO_SUGGESTIONS` 편집기를 비대화형으로 판정해 문맥 후보를 일부러 비운다. 검색어는 추천·학습 재료로 쓰지 않기 위한 fail-closed 계약이므로 결함이 아니며, 후보가 나오는 곳은 일반 대화 작성 칸과 debug editor다.

- 동일 APK의 이어쓰기 게이트는 A35에서 `OK (6 tests)`(dangneun-space 후보 117ms 포함)로 재확인했다. `그런데 `도 같은 `endsWith("는데")` 분기로 계약상 후보("아직·생각보다·그래도")를 내야 한다.
- 사용자 내용이 담긴 화면 덤프는 원문 보존 없이 즉시 삭제했고, 판정에 쓴 EditorInfo 수치만 남긴다.
