# 0002. 외부 글쓰기 AI 제거, 기기 안 Gemma만

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

외부 글쓰기 AI(API 키, OAuth 로그인, 내 컴퓨터 컴패니언 연결, 키보드 안 AI 글쓰기 창)가 앱에 있었다. 사용자는 2026-09-14에 AI 추천을 온디바이스 Gemma만으로 하기로 확정했다.

## 결정

새글의 AI는 기기 안 Gemma(LiteRT-LM)만 쓴다. 외부 글쓰기 AI는 앱에서 제거한다. 음성 OpenAI 전사(STT 전용 키)는 유지한다.

## 결과

- 앱에서 외부 글쓰기 AI 기능과 저장된 키·세션 파일을 제거했고, 업그레이드 때 남은 자격증명을 지우는 코드(`data/ExternalAiCredentialPurge.kt`)가 있다.
- `docs/korean-input/korean-smart-input-ssot.md`의 관련 서술은 지우지 않고 "2026-09-24 제거된 기능의 기록"으로 표시했고, 이 결정에 대체된 결정 로그 행에는 `SUPERSEDED 2026-09-24`를 붙였다.
- 근거: `AGENTS.md` 9.1, `docs/korean-input/korean-smart-input-ssot.md` 6.3·8.2.3·11절.
