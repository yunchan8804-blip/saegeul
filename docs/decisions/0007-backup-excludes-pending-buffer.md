# 0007. 백업에서 분석 전 원문 버퍼 제외

- 상태: 채택
- 날짜: 2026-10-09

## 맥락

`typing_dna_pending.json`은 분석을 기다리는 입력 원문 문장 버퍼다. 개인정보 선언은 원문을 분석 전까지만 보관한다고 했고, 백업에 넣어 복원하면 손으로 만든 백업이 문장을 포인트가 걸린 분석에 밀어 넣을 수 있다.

## 결정

`.saegeulbackup`에 `typing_dna_pending.json`을 넣지 않는다. 옛 버전이 만든 백업에 이 항목이 있어도 가져오기에서 검증 없이 건너뛰고, 기기의 기존 버퍼 파일은 건드리지 않는다.

## 결과

- 구현: `app/src/main/java/org/fcitx/fcitx5/android/data/backup/VaultBackupTargets.kt`(`LEGACY_IGNORED_ENTRY_NAMES`).
- 선언 문서 `docs/independent-fork/privacy-data-safety.md`와 어긋나지 않는다(수정 없음).
