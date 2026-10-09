# 결정 기록

되돌리기 어렵거나 여러 문서에 걸치는 결정을 한 건씩 파일로 남긴다. 정본 문서(`AGENTS.md`, `docs/korean-input/korean-smart-input-ssot.md` 등)에 반영된 내용의 이유와 날짜를 찾는 곳이다.

## 형식

- 파일 이름: `NNNN-제목.md` (네 자리 일련번호, 영문 소문자와 하이픈).
- 머리말 항목: `상태`(`채택` | `대체됨(→NNNN)` | `폐기`), `날짜`.
- 본문 절: `맥락`(왜 정해야 했나), `결정`(무엇을 하기로 했나), `결과`(무엇이 바뀌었고 무엇을 확인했나, 근거 문서 경로).
- 결정을 바꿀 때는 옛 파일의 상태만 `대체됨(→NNNN)`으로 고치고 새 파일을 만든다. 옛 파일의 본문은 고치지 않는다.

## 목록

| 번호 | 제목 | 상태 | 날짜 |
|---|---|---|---|
| [0001](0001-git-host-forgejo.md) | git 호스트는 Forgejo | 채택 | 2026-10-09 |
| [0002](0002-external-writing-ai-removed.md) | 외부 글쓰기 AI 제거, 기기 안 Gemma만 | 채택 | 2026-09-24 |
| [0003](0003-unreadable-vault-files-preserved.md) | 읽을 수 없는 금고 파일은 덮어쓰지 않는다 | 채택 | 2026-10-09 |
| [0004](0004-dictation-cancel-on-keyboard-hide.md) | 정밀 받아쓰기 녹음 중 키보드 숨김은 취소 | 채택 | 2026-10-09 |
| [0005](0005-level-reward-prefers-loss-over-duplicate.md) | 레벨업 포인트는 중복 지급보다 유실을 택한다 | 채택 | 2026-10-09 |
| [0006](0006-buffered-delete-request-in-buffer.md) | 버퍼 모드의 엔진 삭제 요청은 버퍼 안에서만 처리 | 채택 | 2026-10-09 |
| [0007](0007-backup-excludes-pending-buffer.md) | 백업에서 분석 전 원문 버퍼 제외 | 채택 | 2026-10-09 |
