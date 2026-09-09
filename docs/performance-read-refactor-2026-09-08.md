# 반복 로딩 비용 리팩터링 작업 패킷

방법: [refactor-governance](C:/Users/encep/.agents/skills/refactor-governance/SKILL.md).
읽기 전용 조사 후 Edit Pass로 전환한다. 기존 작업 트리와 금고 데이터를 보존한다.

## 목표와 불변량

- 비동기 대기 표시와 별도로 실제 파일 읽기·복호화·통계 재계산 횟수와 데이터 준비 시간을 줄인다.
- 암호, 키, AAD, 파일 형식, atomic write/backup 복구, 즉시 staging 저장, 개인정보 경계는 유지한다.
- 저장·초기화·외부 파일 변경 뒤 오래된 통계를 표시하지 않는다. 명시적 강제 재로드 API는 유지한다.
- credential/session 평문 캐시, 외부 LLM 요청, 모델 변경, 데이터 삭제, commit/push는 범위 밖이다.
- 화면과 저장소의 기존 값·오류 처리 의미를 보존한다. 백업 복구·캐시 정합성 수정은 별도 회귀 검증한다.

## 근거와 소유권

| 위치 | 확인된 낭비 | 처리 방향 |
| --- | --- | --- |
| TypingDnaCardSnapshot, DashboardSnapshot, PrivacyAiSettingsFragment | 동일 singleton repository에 forceReload=true 반복 | repository가 재사용과 무효화를 소유 |
| TypingDnaRepository.getStats/getSummary | 같은 profile의 집계 재실행, mtime <= 캐시 판정 | 정확한 파일 버전 비교와 버전별 파생 통계 재사용 |
| VaultFile 및 저장소 load | migrateIfLegacy 뒤 readText로 committed bytes 두 번 읽음 | 하나의 path lock 안에서 한 번 읽고 해석·필요 시 마이그레이션 |
| KeystoreVaultCipher 보안 표시 | 키는 캐시하나 KeyInfo는 매 접근 조회 | 기준 측정 뒤 후속 패킷 여부 결정 |
| MainViewModel | 홈 표시만으로 native connection 생성 | 별도 시작 계측 뒤 후속 패킷 여부 결정 |

## 순서와 파일 경계

| 그룹 | 파일 | 선행 조건 / 게이트 |
| --- | --- | --- |
| P0 기준 측정 | 신규 androidTest 읽기 비용 테스트 | 생산 코드 변경 전 같은 기기·데이터의 전체 reader 시간과 데이터 지문 기록 |
| P1 단일 파일 읽기 | VaultFile, 인접 migrate/read 호출부, vault 테스트 | P0 기록; 기존 read/migration/backup/fault 테스트와 read 횟수 검증 |
| P2 캐시 소유권 | VaultFile 경로 버전 지원, TypingDnaRepository, UI read 호출부, repository 테스트 | P1 게이트 통과; 저장/clear/강제 재로드/다른 인스턴스/backup/mtime 역행 회귀 |
| P3 결과 비교 | P0 테스트 재실행, 기존 UI 응답성 테스트 | P2 게이트와 전체 app 단위 테스트; 동일 지문일 때만 시간 비교 |

P1과 P2는 VaultFile 및 일부 load 호출부가 겹치므로 순차 적용한다. 탐색·검증 준비는 병렬로 수행한다.
삭제 전용 변경이나 계층 추출을 먼저 하지 않는 이유는 현재 병목이 도달 가능한 반복 읽기이고,
파일 경계와 기존 API가 이미 명확하기 때문이다. 무관한 추상화·정리 작업은 추가하지 않는다.

P2의 버전은 canonical path의 프로세스 내 쓰기 세대와 committed base/backup의 존재·길이·mtime을
함께 비교한다. 암호화 파일이 권위 원본이며, repository가 profile과 파생 통계 캐시를 소유한다.
성공한 save/clear 및 명시적 invalidate/forceReload는 캐시를 갱신·무효화한다. 파일 잠금 아래에서
버전 확인과 캐시 갱신을 묶어 동일 프로세스의 다른 인스턴스 쓰기도 감지한다.
외부 변경 중 metadata가 완전히 동일한 경우에는 명시적 forceReload가 필요하며, 이는 별도 한계로 기록한다.

## 완료 판정

- 생산 경로의 중복 read와 강제 재로드 잔여 검색을 확인한다.
- 통과 수·실패·skip을 명시하고 기존 실패를 새 성공으로 덮지 않는다.
- 시간 비교는 같은 기기/데이터 지문/반복 수에서 수행하고 첫 읽기와 반복 읽기를 분리한다.
- 초기화/암호화를 미루기만 한 결과를 전체 작업량 감소로 보고하지 않는다.
- 아래에 한정한 P0~P3 검증을 완료했다. 전체 앱 성능과 기존 추천/LLM 백로그 완료를 뜻하지 않는다.

## P0 기준 기록

- 유선 A35(SM-A356N), 기존 설치 APK 보존: `.artifacts/performance-read-refactor-20260908/before-app.apk`.
- APK SHA256: `C3E9203050FE562A2AADB0D4EAE11B54B25AA7D8DB8115B2BCE6DD1E2777D2CE`.
- `DashboardReadCostDeviceTest`: OK 1 test, 0.693s. 생산 코드 변경 전 실행했다.
- 홈/상세 reader 첫 호출 합계 349ms(106/242ms), 후속 6회 합계 `[103,70,26,26,23,23]`ms, 중앙값 26ms.
- 지문: `5b91d8d9bfdec607bdc4ab695187ae4cb8cfd486bcc105bd9269ff1467cc4290`.
- 이는 reader 작업 시간이며 프로세스 cold start나 전체 화면 로딩 시간은 아니다. 20초 지연 원인 전체를 설명하지 않는다.
- 원문: `.artifacts/performance-read-refactor-20260908/baseline-read-cost.log`.
- 별도 프로세스 종료 후 `am start -W` 3회: TotalTime `[2025,2022,1925]`ms. 내부 데이터 준비 시점은 이 수치에 포함하지 않는다.

## P1 게이트

- `VaultFile.readTextAndMigrate` 및 인접 호출 8곳을 수용했다. 기존 독립 read/migration API는 유지했다.
- `:app:testDebugUnitTest --tests 'org.fcitx.fcitx5.android.input.ai.vault.*' -PbuildABI=arm64-v8a`: BUILD SUCCESSFUL, 47 tests / 0 failures / 0 errors / 0 skipped.
- 원문: `.artifacts/performance-read-refactor-20260908/p1-vault-tests.log`.
- P2는 repository monitor 다음 canonical path lock 순서로 load/save/updatePersona/clear의 버전·파일·캐시 발행을 묶는다. 파생 통계 집계는 확보한 profile snapshot으로 수행하며 추가로 경로 잠금을 유지하지 않는다.
- 읽기 실패를 유효 캐시로 고정하지 않고 다음 호출에서 재시도한다. clear 뒤에는 캐시를 무효화해 삭제 실패를 빈 데이터 성공으로 고정하지 않는다.

## P2 게이트

- repository 버전/파생 통계 캐시와 UI/즉시 동기화 강제 재로드 제거를 수용했다.
- `:app:testDebugUnitTest --tests 'org.fcitx.fcitx5.android.input.ai.TypingDna*' --tests 'org.fcitx.fcitx5.android.input.ai.vault.*' -PbuildABI=arm64-v8a`: BUILD SUCCESSFUL, 109 tests / 0 failures / 0 errors / 0 skipped.
- 원문: `.artifacts/performance-read-refactor-20260908/p2-cache-tests.log`.
- 기존 minSdk 23 API 경계를 유지하도록 lock state 생성은 기존 putIfAbsent를 재사용했다.
- 실제 파일 복호화 호출 횟수, 같은 통계 인스턴스 재사용, 저장/강제 재로드/파일 변경/백업/읽기·쓰기·삭제 실패를 검증했다.

## 카드 높이 회귀 병렬 수정

- 로딩 분기가 mini chart를 INVISIBLE로 유지하나 compact 설정은 정상 데이터 분기에만 있었다.
- 기본 상세 차트 높이 480dp가 첫 로딩에서도 측정됐다. 홈 bind 시작에 compact=true를 적용하고, 로딩/첫 읽기 실패 동안 차트를 GONE으로 접는다. 데이터가 준비되면 118dp의 홈 차트를 표시한다.
- 전역 차트 기본값·상세 화면·데이터 처리 계약은 바꾸지 않는다. 실제 Preference loading bind의 기기 측정으로 검증한다.
- 중간 118dp 공간 유지안은 실제 렌더에서 빈 공간이 커서 보완했다. 최종 로딩 카드 검증 기준은 차트 0px, 카드 300dp 미만이다.

## P3 최종 결과와 한계

- 전체 app 단위 테스트: 1,070 tests / 2 failures / 0 errors / 7 skipped. 기존 `ContextualLearningAndTypingGroundingTest` 판교(47행)·회의(69행) 실패를 유지했다. `--continue`로 APK 생성은 완료됐으며 이 통합 명령의 exit 1을 성공으로 처리하지 않았다.
- 최종 UI 보완 뒤 `:app:assembleDebug :app:assembleDebugAndroidTest -PbuildABI=arm64-v8a`: BUILD SUCCESSFUL 12s, exit 0. 생산 저장소 코드는 전체 테스트 이후 변경하지 않았다.
- 최종 app SHA256: `C5D2F69C7FA47C61256D9D5810AF1D730917A975430138F23C9C1C89AC62E11D`. 유선 A35에 app/test update-install 모두 Success.
- `final-read-cost.log`: OK 1 test. 기준과 데이터 지문 동일. 첫 reader pair 349→150ms, 후속 6회 중앙값 26→14ms. 최종 후속 값 `[18,15,13,13,14,14]`ms. 홈 반복 0ms 표시는 밀리초 버림으로 1ms 미만을 뜻한다. 첫 호출은 프로세스 전체 cold start가 아니다.
- `final-card-geometry.log`: OK 1 test. 폭 945px, density 2.625, 로딩 차트 0px/GONE, 카드 493px(약 188dp). `final-loading-card.png` 실제 뷰 렌더를 직접 검토했다.
- 최초 geometry 검증은 테스트 Context의 Material 테마 누락으로 실패했다. 앱 테마를 적용해 정정했으며 생산 테마나 조건을 완화하지 않았다.
- Main 검증 중 시스템 CellBroadcast 팝업 가림을 확인했다. 또한 기본 IME가 삼성 키보드인 상태에서는 ACTION_MAIN이 초기 설정 화면을 열어 홈 가시성 조건이 성립하지 않았다. 팝업만 닫고 새글 IME를 다시 선택한 후 같은 테스트 조건으로 검증했다. 완전 종료 측정 뒤에는 기본 IME 선택 상태를 다시 확인해야 한다.
- `final-main-selected-ime.log`: OK 1 test, 4.226s. 홈 첫 UI 1,645ms/데이터 준비 1,646ms, 상세 첫 UI 906ms, 복귀 985ms. 메인 큐 최대 지연은 각각 1,388/851/586ms다. 테스트의 1,500ms 기준 통과를 지연이 없다는 의미로 해석하지 않는다.
- 실패 원문과 중간 산출물은 모두 `.artifacts/performance-read-refactor-20260908/`에 보존했다. 메인 시작 구간 최대 1.39초, 간헐 20초 및 모든 앱 IME 프리징은 해결 완료로 선언하지 않는다. native 초기화/시작 단계별 계측이 후속 과제다.
- 2026-09-08 13:19:45, Fold6 `z-fold6`에 `saegeul-debug-read-cache-card-fix-20260908.apk` Taildrop 전송 `sent`, exit 0. Fold6 설치·실행 확인은 아니며 이번 실기 검증 기기는 A35다.
