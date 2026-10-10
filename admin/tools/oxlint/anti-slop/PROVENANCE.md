# 출처 기록

이 폴더는 dmmulroy/anti-slop의 Oxlint 규칙을 이 저장소가 직접 관리하려고 복사한 것이다. npm 패키지가 아니라 소스 복사본이며, 이후 수정은 이 저장소에서 한다.

- 출처: https://github.com/dmmulroy/anti-slop
- 브랜치: `main`
- 커밋 SHA: `c44ef22ca116d0ba62a3ff663a0bd13a3f3fa40b`
- 복사 날짜: 2026-10-10
- 라이선스: MIT (같은 폴더의 `LICENSE`)
- 원본 버전: `oxlint-plugin-anti-slop` 0.1.2, 호환 Oxlint 1.78.0 (`oxlint`와 `@oxlint/plugins`는 `admin/package.json`에 같은 정확한 버전으로 고정)

## 복사 범위

- 원본 `src/` 중 일반 규칙(`index.ts`, `rules/`, `shared/`, `vendor/eslint-stylistic/`)만 복사했다.
- Effect 전용 규칙(`src/effect/`)은 이 프로젝트가 Effect를 쓰지 않아 복사하지 않았다.
- 규칙별 테스트(`*.test.ts`)는 원본의 `tsx`와 `oxlint/plugins-dev` 실행 환경이 필요해 복사하지 않았다.
- `require-readable-spacing`이 쓰는 ESLint Stylistic 코드는 원본 그대로 `vendor/eslint-stylistic/`에 있고, 그 출처와 라이선스는 해당 폴더의 `UPSTREAM.md`와 `LICENSE`에 있다.
- 복사한 소스는 수정하지 않았다.
