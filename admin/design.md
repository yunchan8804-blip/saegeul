# AVENUE Control 디자인 시스템

> 적용 범위: `admin/` 내부 운영 콘솔
> 최종 감사: 2026-08-22
> 경로: 기존 `design.md`가 없고 새 운영 화면과 토큰을 만들었으므로 designpaca FULL 0→6 적용

## 1. 브리프

광고 운영자, 개발자, 재무 담당자가 광고가 나타나는 경로인 AVENUE를 안전하게 발행하고 수익을 대사하는 내부 도구다.

한 문장 목표:

> 광고 운영자가 예상 매출보다 정책 위험을 먼저 보고 금지된 AVENUE를 발행하지 못하게 한다.

느낌은 정밀한 계기판, 과장 없는 신뢰, 고밀도 운영 도구다. 모든 수치가 데모인 현재 단계에서는 이를 화면에 반복해 표시하며 실제 연결처럼 보이게 하지 않는다.

### 설계 다이얼

- `DESIGN_VARIANCE = 6`: shadcn의 정보 구조는 쓰되 균등 카드 모음은 피한다.
- `MOTION_INTENSITY = 3`: 폼과 표의 반복 작업을 방해하지 않는 피드백만 쓴다.
- `VISUAL_DENSITY = 8`: 한 화면에서 위험, 예상 수익, AVENUE, 실시간 이벤트를 함께 읽는다.
- 프리셋: `dark-instrument`
- 가장 강한 시각 아이디어: 데스크톱 오른쪽 276px 실시간 이벤트 레일
- 감수한 리스크: 1180px 이상에서 이벤트 레일이 본문 폭을 줄인다. 1180px 아래에서는 본문 아래로 이동한다.

## 2. 레퍼런스 블록

### Brief

`[광고 운영자]가 위험을 먼저 확인하고 AVENUE를 안전하게 발행하게. 느낌은 정밀하고 신뢰형.`

### R1 Structure: shadcn dashboard-01

- URL: <https://ui.shadcn.com/view/new-york-v4/dashboard-01>
- 관찰 뷰포트: 1280×720
- 실측: 사이드바 272px, 본문 969px, 14px 텍스트 117개, 12px 38개, 8px gap 108회, 4px gap 32회, 8px radius가 지배적.
- 가져온 것: 사이드바 → KPI → 추세 → 표의 정보 흐름, 오픈 컴포넌트 방식.
- 변형: 사이드바를 232px로 줄이고, 같은 폭 카드 대신 `위험 패널 + 2×2 계기판`으로 중요도 차이를 만들었다. 데스크톱에는 276px 실시간 레일을 추가했다.

### R2 Tone: Teenage Engineering OP-XY

- URL: <https://teenage.engineering/products/op-xy>
- 관찰 뷰포트: 1280×720
- 실측: 지배 배경 `rgb(15 14 18)`, 텍스트 단계 `rgb(229) / rgb(178) / rgb(127)`, 글자 크기 약 11.8 / 17 / 20.9 / 47px.
- 가져온 것: 검은 계기판, 절제된 밝은 표시, 모노 숫자, 좁은 상태 빛.
- 변형: 제품의 붉은 포인트를 복제하지 않고 기존 새글 브랜드의 네이비와 제이드로 바꿨다. 발광은 실제 live·paid 상태에만 좁게 썼다.

### R3 Motion: Motion layout animations

- URL: <https://motion.dev/docs/react-layout-animations>
- 검토한 것: `layout`, `layoutId`, spring 기반 패널 연속성.
- 최종 결정: Motion 런타임을 제거했다. 관리자 반복 작업에 FLIP이 핵심이 아니고 초기 JS 예산을 152.21KB에서 110.54KB gzip으로 낮추는 편이 더 가치가 컸다.
- 대체: 패널은 즉시 교체한다. 버튼, 토글, 시트는 CSS `opacity`, `translate`, `scale`만 사용한다.

### Grayscale hierarchy

색을 제거해도 다음 순서가 유지돼야 한다.

1. 발행 차단과 정책 위험
2. 예상 수익과 AVENUE 운영 상태
3. 네트워크 건강과 실시간 이벤트
4. 보조 메타데이터와 감사 코드

## 3. 토큰

### Color

| 역할 | Dark | Light |
|---|---|---|
| canvas | `#060e1a` | `#edf3f0` |
| surface | `#0a172a` | `#f8fbf9` |
| raised | `#0f223d` | `#fbfdfc` |
| ink | `#edf8f5` | `#0a211b` |
| secondary | `#a9bfba` | `#405a53` |
| muted | `#78928d` | `#637b75` |
| action / live | `#79f1c2` | `#087a59` |
| info | `#82b5ff` | `#2368b9` |
| warning | `#f0c466` | `#8b5d00` |
| danger | `#ff7c78` | `#b83934` |

강조색은 제이드 하나다. 파랑, 노랑, 빨강은 정보·주의·차단이라는 기능에만 쓴다.

### Typography

- 기본: Pretendard Variable dynamic subset. SIL Open Font License.
- 폴백: system-ui, Apple·Windows 시스템 sans.
- 수치와 ID: 시스템 monospace, `tabular-nums`.
- 한글: `word-break: keep-all`, `overflow-wrap: break-word`.
- 페이지 제목은 16px, 콘텐츠 큰 제목은 25~38px, 표의 주 정보는 13px다.
- 8~11px는 단위, 시간, 코드, 장비 레이블에만 제한한다. 설명 문장과 정책 이유는 13~14px로 올렸다.

고밀도 한국어 운영 UI라는 맥락 때문에 일반 콘텐츠 사이트의 16px 본문 하한을 모든 셀에 적용하지 않았다. 대신 핵심 판단 문장은 13px 이상, 대비 AA, 1.3배 글꼴 확대와 모바일 수축을 게이트로 검증했다.

### Spacing and shape

- 4px 기준: 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64px
- radius: 6px 인터랙티브, 12px 패널, pill은 상태·토글에만
- 그림자: 시트와 부동 추가 버튼에만. 데이터 패널은 1px 선과 표면 명도로 구분.
- 이중 베젤: 위험 패널, 계기판, 수익 그래프 등 주요 instrument panel에만 한 단계 적용.

### Motion

- instant 100ms, quick 200ms, normal 350ms, slow 600ms
- 진입 `ease-out`, 퇴장 `ease-in`, 화면 내 이동 `ease-soft`
- 실제 애니메이션 속성: `opacity`, `translate`, `scale`만
- `prefers-reduced-motion`에서는 이동 거리를 0으로 하고 빠른 상태 피드백은 남긴다.
- 숫자, 표, 실시간 수익 값은 의도적으로 움직이지 않는다. 계기판 값이 흔들리면 신뢰가 떨어진다.

## 4. 구조

```text
232px sidebar
└─ workspace
   ├─ 72px sticky topbar
   └─ grid
      ├─ main content, max 1180px
      └─ 276px live event rail, desktop only
```

- 1180px 이하: 이벤트 레일을 본문 아래로 이동
- 1080px 이하: 위험 패널과 KPI rack을 세로로 배치
- 900px 이하: 사이드바를 숨기고 접이식 2열 모바일 메뉴 사용
- 680px 이하: 표를 터치 가능한 행 카드로 바꿈
- 420px 이하: KPI를 한 열로 배치

## 5. 컴포넌트

- shadcn 스타일의 소유 가능한 컴포넌트: `Button`, `Badge`, `Toggle`
- Radix primitives: Dialog, Switch, TooltipProvider
- CVA: 버튼 변형과 크기
- Phosphor: 단일 아이콘 시스템
- 사용자 정의: instrument panel, live event rail, policy preview, revenue reconciliation

카드 전체를 클릭 가능한 곳은 AVENUE 행뿐이며 명확한 편집 affordance를 가진다. 장식 이미지는 사용하지 않았다. 이 제품에서는 실제 관리 UI 자체가 설명해야 할 시각 자료다.

## 6. 시각 효과 결정

- SVG filter: 사용하지 않음. 운영 도구의 표면은 CSS 배경, 선, 좁은 상태 그림자로 충분하다.
- WebGL·three.js: 사용하지 않음. 데이터 판독과 무관하고 번들·GPU 비용만 늘어난다.
- backdrop blur: sticky topbar와 modal chrome에만 제한.
- 무한 모션: 없음. 실시간 이벤트는 4.8초에 한 번 내용만 교체하고 일시정지 버튼을 제공한다.
- 그레인, 수차, gooey, 광역 글로우: 사용하지 않음.

모든 filter, backdrop-filter, animation, transition을 꺼도 정보 구조와 상호작용이 성립해야 한다.

## 7. 접근성과 상태

- 모든 입력은 위에 표시된 label에 연결된다.
- 키보드 포커스는 2px 제이드 outline으로 즉시 표시된다.
- 모바일 독립 컨트롤은 최소 44px다.
- 차단·경고·통과는 색뿐 아니라 아이콘과 텍스트를 함께 쓴다.
- 로딩: 짧은 초기 렌더는 즉시 콘텐츠를 보여 JS 지연 때문에 빈 화면을 만들지 않는다.
- 빈 상태: AVENUE 필터 결과가 없으면 설명과 다음 행동을 보여준다.
- 오류 상태: localStorage 파싱·저장 실패 시 기본 구성 복구와 alert를 보여준다.
- 축소 모션: 위치 이동과 반복을 제거하되 상태 변화는 남긴다.

## 8. 성능 예산과 실측

| 항목 | 예산 | 실측 | 결과 |
|---|---:|---:|---|
| 초기 JS gzip | ≤150KB | 122.00KB | 통과 |
| CSS gzip | ≤20KB | 7.60KB | 통과 |
| WebGL 추가분 | 0KB | 0KB | 통과 |
| 초기 화면 이미지 | 0 | 0 | 통과 |
| CLS | ≤0.1 | 레이아웃 애니메이션 없음 | 구조상 통과, 운영 배포에서 Lighthouse 재측정 |
| LCP | ≤2.5s | 로컬 운영 데모만 확인 | 운영 배포에서 4G 재측정 필요 |

Pretendard는 dynamic subset CDN을 쓰며 시스템 폴백을 먼저 안정적으로 유지한다. 운영 배포에서는 CSP와 공급망 통제를 위해 폰트를 자체 호스팅하는 편이 낫다.

## 9. 안티 슬롭 감사

| 카테고리 | 걸린 항목 | 조치 |
|---|---:|---|
| 최우선 3종 | 1 | 영어 대문자 섹션 레이블을 한국어로 교체 |
| 컬러 | 1 | light surface의 순백을 `#fbfdfc`로 변경 |
| 타이포 | 1 | 이유 없는 Inter 폴백 제거, 수치만 monospace 유지 |
| 레이아웃 | 0 | 위험 패널과 계기판의 비대칭 위계 유지 |
| 컴포넌트 | 0 | 6/12px radius, 카드 전체 blur·그림자 없음 |
| 모션 | 0 | CSS 합성 속성만 사용, reduced motion 제공 |
| 카피 | 0 | 제품명 치환 시 성립하지 않는 정책·대사 중심 문장 |
| 이미지 | 0 | 장식 이미지 없음 |
| 한글 조판 | 0 | Pretendard, keep-all, 핵심 설명 line-height 1.65~1.7 |

수정 뒤 금지 grep은 0건이다.

## 10. 최종 게이트

`admin/gate-report.md` 결과:

- L0 stylelint 통과
- L0 html-validate 통과
- dashboard, avenues, networks, revenue, campaigns, acquisition, guardrails, audit 전 화면 통과
- 320 / 375 / 390 / 768 / 1024 / 1440px 오버플로 0
- 전 화면 h1 1줄, 수축 0, 리듬·트랙 통과, 대비 AA
- 375 / 390px에서 글꼴 1.0 / 1.3배 h1 통과
- 모바일 브랜드 1줄

브라우저 실제 흐름으로 금지 AVENUE 저장 disabled, 안전한 AVENUE 추가, 밝은 테마, 320px 메뉴 44px 타깃을 확인했다. 홍보 확장에서는 일 예산과 목표 CAC를 입력해 드라이런을 생성한 뒤에도 Play·계정·API·소재·측정 게이트가 남으면 운영 집행 요청이 disabled인 것을 확인했다.

## 11. 운영 시 남은 조건

현재 화면은 브라우저 localStorage 데모다. 운영 완료를 선언하려면 다음이 필요하다.

- Cloudflare Access 또는 OIDC
- Worker API, D1, KV, 서명 구성
- 실제 SSE 이벤트와 AdMob 보고서 수집
- RBAC와 2인 승인
- 운영 URL Lighthouse와 WebKit 회귀
- 광고 계정·SDK·개인정보 릴리스 게이트

운영 백엔드가 없는 상태에서 화면을 “실시간 연결 완료”라고 표현하면 안 된다.

## 12. 2026-08-22 홍보 캠페인 연장

- 경로: 기존 토큰·`dark-instrument` 방향을 유지한 designpaca 연장 경로.
- 국소 기준: 현재 AVENUE 화면의 `232px sidebar + 비대칭 위험 패널 + 오른쪽 결정 레일`을 실측 기준으로 유지하고, Google Ads의 공식 `Campaign → Ad group → Ad` 흐름을 캠페인 명령 → 적합성 → 승인 → 제출 순서로 번역했다.
- 추가 화면: `홍보 캠페인`, `효과 분석`과 대시보드의 수익화↔사용자 획득 이중 루프.
- 시각적 대담함은 기존 오른쪽 실시간 레일 하나만 유지한다. 새 화면은 예산 상한과 차단 사유가 가장 먼저 읽히게 했다.
- 새 토큰은 만들지 않았다. 기존 색·타입·4px 간격·6/12px radius·합성 속성 모션을 그대로 사용한다.
- 실제 광고 계정과 측정이 없으므로 성과 그래프와 그럴듯한 샌드박스 수치를 넣지 않았다. `-`, `미연결`, `₩0 실제 집행`으로 빈 상태를 정직하게 표시한다.
- 운영 집행 버튼은 외부 게이트가 닫히기 전까지 disabled다. 브라우저에는 광고 플랫폼 비밀값을 넣지 않는다.
