# Gemma UI 재구성 및 검증

작성일: 2026-09-09

## 범위와 원칙

이 변경은 `app/design.md`의 차분한 준비 도구 방향을 Android 홈, Gemma 준비 화면, 온보딩, AI 설정에 반영한다. 기존 Android Views와 Preference 구조, 한국어 UI, 모델 동의와 개인정보 경계를 유지한다.

Gemma 진입과 안내는 debug 전용이다. release에는 Gemma 기능이나 안내를 노출하지 않는다. 생성 정책, 실제 저장 데이터 계산, 외부 AI 제공자, 음성, GIF, 네트워크와 오프라인 동작, 개인 언어 금고의 데이터는 이 UI 변경으로 바꾸지 않는다.

## 사용자 흐름

### 홈

debug 홈의 첫 부분에 `AI 문장 준비` 카드를 둔다. 카드에는 Gemma를 보조 이름으로 표시하고, 기기에서 문장을 미리 준비해 입력할 때 바로 제안한다는 목적과 준비 화면 열기 행동을 보여 준다. 준비 완료 여부나 저장 개수는 카드에서 추측하지 않는다.

`내 언어 금고` 카드는 개인 데이터 기능으로 남긴다. 실제 레벨, 기존 제목·요약, 진행 표시와 상세 대시보드 진입은 유지하고, 홈에서 중복되는 미니 차트와 네 개의 지표 행은 숨긴다.

### Gemma 준비

준비 화면은 모델 준비·다운로드 또는 가져오기, 자동 준비, 실제 저장 개수와 현재 대기 이유를 우선해서 보여 준다. 충전은 필수가 아니며, 배터리가 30% 이상일 때 기기가 따뜻하면 소량만 준비한다. 심한 발열, 절전 모드, 입력 중에는 준비를 멈춘다는 이유를 함께 설명한다.

CPU/GPU, 메모리, 생성 시간, 원문 출력, 고정 생성, 모델 삭제 같은 실험 상세는 `고급 실험 설정` 안에 둔다. 취소와 삭제 확인 동작은 유지한다.

### 온보딩

입력기 활성화 후 선택하는 기존 두 단계를 보존한다. SetupTextButton의 안내를 ConstraintLayout 제약 안에서 안정적으로 배치하도록 보정했다. debug 안내에서는 Gemma 모델을 별도로 준비해야 함을 알리되, 다운로드나 자동 생성을 강제하지 않는다.

### AI 설정

`기기 안에서 문장 준비`를 debug 전용 최상단 카테고리로 두고 `AI 문장 준비`에서 Gemma 모델과 준비 상태를 관리한다. 개인 언어 금고 내부에 있던 Gemma 진입은 이 위치로 옮긴다.

기존 제공자 설정은 `외부 글쓰기 AI`로 구분한다. 외부 제공자, 음성, GIF 기능을 Gemma 기능으로 재명명하지 않으며 계정·자격증명·외부 통신 동작도 유지한다.

## 검증 기록

아래 로그와 캡처는 저장소의 `.artifacts/gemma-ui-redesign-20260909/`에 보존했다. 최종 온보딩·한국어 요약 보정은 ui-r13 APK에서 재확인했으며, Gemma 네 구성 검증은 해당 화면 코드가 같은 ui-r1 APK에서 수행했다.

| 항목 | 확정 상태 |
| --- | --- |
| UI 빌드 ui-r1 | `BUILD SUCCESSFUL` |
| 최종 UI 빌드 ui-r13 | `BUILD SUCCESSFUL`, 35초, exit 0 |
| 최종 앱 SHA-256 | `A328759FCA3047C628B995311D59A91306A58F82B7183A6D19074C76B6703BC2` |
| diff 검사 | 0개 오류 |
| 단위 테스트 합산 | 전체 1160개 중 1153개 통과, 7개 건너뜀, 0개 실패 |
| AndroidTest ui-r2 | 빌드 성공만 확인됨 |
| compact 밝은 화면 글꼴 100% ui-r4 | `OK 1`, 2.808초 |
| compact 어두운 화면 글꼴 100% ui-r7 | `OK 1`, 2.253초 |
| compact 밝은 화면 글꼴 200% ui-r7 | `OK 1`, 2.436초 |
| wide 800dp 밝은 화면 글꼴 100% ui-r7 | `OK 1`, 4.381초 |
| setup ui-r14 | skip shadow 제거와 다음 버튼 오른쪽 정렬을 직접 시각 확인 |
| AI 설정 ui-r14b | 한국어 요약 수정 화면을 직접 시각 확인 |
| setup 스크롤 ui-r15 | 200% 본문, Gemma 안내, 입력기 선택, 이전 버튼 표시와 스크롤을 직접 시각 확인 |

ui-r7 첫 캡처의 밝은 화면, 어두운 화면, 글꼴 200%, wide 800dp는 직접 시각 확인했다. Gemma 준비 화면의 네 구성은 48dp 터치 영역, 텍스트 잘림 없음, 고급 설정 접힘, 다운로드 동의 취소를 통과했다. 불량 splash 캡처 `r4bfirst`는 검증에서 제외했다.

`ui-r11c-home.png`의 실제 홈과 `ui-r13-font2-top.png`의 홈 글꼴 200%를 직접 시각 확인했다. `ui-r11e-gemma.xml`은 홈에서 Gemma 준비 화면으로 들어가는 hierarchy 확인이며 시각 검증은 아니다. `ui-r11f-ai-settings.png`는 한국어 제목에 영어 요약이 섞이는 결함을 발견한 증거다. 원인은 `refreshSummaries()`가 `applicationContext`의 locale로 요약을 만들던 것이며, Activity resources의 configuration을 복사한 독립 context로 요약을 생성하도록 수정했다. `ui-r14b-ai-settings.png`에서 한국어 요약 수정을 확인했다.

검증 범위는 Gemma 준비 화면 네 구성, 홈 밝은 화면과 200% 상단, setup 표시·스크롤, AI 설정 한국어 요약 확인까지다. 홈의 dark/wide 검증은 실행하지 않았으며 Gemma 준비 화면의 네 구성 검증과 구분한다. setup 초기 200% top hierarchy는 null root 오류로 확보하지 못했고, 이전 버튼을 눌렀을 때 실제 복귀 결과도 보고되지 않았다. 따라서 모든 온보딩 전이가 통과했다고 기록하지 않는다.

초기 재시도 표시 검증은 visibility 테스트 계약을 수정했다. 공유 에뮬레이터 `5554`의 Maestro 동시 등록 충돌 뒤 별도 Pixel 7 API 34 에뮬레이터 `5580`에 read-only, `-gpu swiftshader`, `-feature -Vulkan`을 적용한 환경에서 위 검증을 수행했다. 반복 종료 원인은 확정하지 않았으며, 관련 환경 점검은 [Android Emulator troubleshooting](https://developer.android.com/studio/run/emulator-troubleshooting)을 따른다. 마지막 화면 검증은 PTY 세션을 유지하여 수행했다. 검증 종료 전 LatinIME와 Voice는 enabled, 새글은 disabled, 글꼴은 unset, 야간 모드는 no로 복구했고 `adb -s emulator-5580 emu kill`로 임시 인스턴스를 종료했다. 다른 작업의 `5554`는 종료하지 않았다.

Fold6은 Wi-Fi 연결이 끊겨 있어 실기기 Gemma 추론 검증을 대기 중이다. 이 문서는 Gemma의 완벽한 예측이나 실기기 추론 성공을 주장하지 않는다.
