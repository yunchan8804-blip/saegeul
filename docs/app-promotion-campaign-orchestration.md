# 새글 수익화·홍보 캠페인 통합 운영 설계

기준일: 2026-08-22
대상: 새글(Saegeul) Android 앱, `AVENUE Control` 관리자, 광고 운영·재무·개발 담당자

## 1. 결론

새글의 광고 운영은 하나의 콘솔 안에 두 개의 서로 다른 원장을 둔다.

| 루프 | 돈의 방향 | 관리 단위 | 첫 판단 지표 |
|---|---|---|---|
| 앱 수익화 | 광고 플랫폼 → 새글 | `AVENUE` | 사용자 신뢰를 해치지 않은 순 광고 수익 |
| 유료 사용자 획득 | 새글 → 광고 플랫폼 | `Acquisition Campaign` | 첫 활성 사용자 CAC와 회수 가능한 LTV |

둘을 같은 `광고` 테이블에 섞지 않는다. 수익은 미수금·확정액·입금으로 대사하고, 홍보비는 승인 예산·플랫폼 지출·청구·환불로 대사한다. 최종적으로만 아래 식으로 연결한다.

```text
순 기여 = 신규 사용자 코호트의 회수 가능 광고 순수익 - 유료 획득 비용
활성 CAC = 캠페인 지출 / 첫 활성 사용자 수
회수 기간 = 활성 CAC / 사용자당 월 광고 순수익
```

관리자에게 “광고해줘”라고 입력했다고 즉시 돈이 나가면 안 된다. 시스템은 먼저 드라이런을 만들고 `스토어 프로덕션 → 개인정보 계약 → 측정 → 광고 계정/API → 소재 → 예산 → 2인 승인`을 모두 통과한 뒤에만 서버가 캠페인을 제출한다.

## 2. 2026-08-22 현재 사실

- 최신 소유 릴리스는 [GitHub의 `saegeul-v0.1.0-rc.12`](https://github.com/yunchan8804-blip/saegeul/releases/tag/saegeul-v0.1.0-rc.12) 프리릴리스다.
- 공개 웹사이트는 Cloudflare Pages `saegul`, 운영 도메인은 `https://saegul.chanpaca.net`이다.
- Google Play의 `net.chanpaca.saegeul` 프로덕션 공개 상태는 이 저장소에서 확인되지 않았다. [Play의 첫 프로덕션 롤아웃](https://support.google.com/googleplay/android-developer/answer/9859348)은 선택한 국가의 일반 사용자에게 앱을 공개하는 별도 콘솔 작업이다.
- 공개 개인정보처리방침과 Data Safety 계약은 로컬 검증 스크립트가 요구한 6개 유형으로 맞췄다. 그러나 Play Console의 실제 입력과 검토 증거는 여전히 외부 게이트다.
- 앱의 현재 공개 약속은 광고 SDK와 분석 추적 라이브러리를 탑재하지 않는다는 것이다. 광고 수익화 SDK나 캠페인 측정 SDK를 넣으려면 코드·앱 내 고지·개인정보처리방침·Data Safety를 같은 릴리스에서 함께 바꿔야 한다.
- Google Ads, Meta Ads, TikTok Ads 계정 또는 운영 API 자격 증명은 저장소에 없다. 실제 광고비는 집행하지 않았다.

## 3. 운영 모델

```text
운영자 자연어 명령
  -> Campaign Planner
      -> 목표·지역·기간·상한 구조화
      -> Platform Eligibility Resolver
      -> 소재 규격·정책 검사
      -> 측정 가능성 검사
      -> 콜드 스타트 배분안
  -> Budget Approval
      -> 작성자와 다른 승인자
      -> 일·총액·통화·중단선 고정
  -> Platform Adapters
      -> PAUSED/DISABLED 초안 생성
      -> 플랫폼 응답 ID와 payload hash 기록
      -> 최종 집행 승인 후 ENABLE
  -> Reporting Ingest
      -> 지출·설치·첫 활성·D7·광고 수익 대사
  -> Optimizer
      -> 증액·동결·중단 제안
      -> 정책 범위 안에서만 자동 적용
```

브라우저는 광고 계정 refresh token, developer token, client secret을 절대 받지 않는다. 관리자 정적 앱은 Worker API만 호출하고, 실제 플랫폼 호출은 Worker 또는 별도 백엔드의 서버측 어댑터가 수행한다.

## 4. 캠페인 명령 계약

자연어는 감사 가능한 구조로 정규화한다.

```json
{
  "commandId": "cmd_01...",
  "rawCommand": "한국에서 첫 활성 사용자를 확보해",
  "appId": "net.chanpaca.saegeul",
  "goal": "activated_users",
  "countries": ["KR"],
  "dailyBudget": null,
  "durationDays": 7,
  "maxCac": null,
  "platformCandidates": ["google_ads", "meta_ads", "tiktok_ads"],
  "mode": "DRY_RUN"
}
```

LLM이 채워도 되는 값은 카피 초안, 소재 변형, 플랫폼별 규격 매핑, 설명 가능한 추천이다. LLM이 정하면 안 되는 값은 예산, 결제 수단, 법적 게시자, 목표 연령, 실제 성과 수치, 최종 집행 승인이다.

입력에서 예산이 없으면 `BUDGET_MISSING`으로 남긴다. 시스템이 업계 평균이나 임의 금액을 대신 넣지 않는다.

## 5. 플랫폼 적합성 판정

플랫폼은 인기 순서가 아니라 현재 캠페인에 실제로 쓸 수 있는지로 고른다.

```text
eligible(platform) =
  store_listing_available(country)
  AND advertiser_account_connected
  AND production_api_permission
  AND required_creatives_present
  AND conversion_measurement_ready
  AND policy_review_passed
```

부적합 플랫폼은 예산 비중을 0으로 만들고 이유를 기록한다. 단순 오류로 전체 계획을 잃지 않고, 연결 가능한 플랫폼만 별도 승인 후보로 남긴다. 다만 `store`, `privacy`, `budget approval`처럼 캠페인 전체에 걸친 게이트는 모든 제출을 차단한다.

### 5.1 Google Ads

[Google Ads API App Campaign](https://developers.google.com/google-ads/api/docs/app-campaigns/create-campaign)은 `advertising_channel_type=MULTI_CHANNEL`이다. Google이 Search, Play, YouTube, Display, AdMob 등 내부 배치를 자동화하므로 우리 시스템은 이 채널을 각각 별도 Google 캠페인으로 쪼개 재최적화하지 않는다.

서버 어댑터 순서:

1. Google Ads manager/client customer ID와 developer token의 운영 접근 수준을 확인한다. [공식 접근 수준](https://developers.google.com/google-ads/api/docs/api-policy/access-levels)에 따르면 테스트 계정 전용 권한으로 운영 계정에 영향을 주면 안 된다.
2. Play 앱 ID `net.chanpaca.saegeul`, 스토어 `GOOGLE_APP_STORE`, 대상 국가의 production listing을 확인한다.
3. 목표에 맞는 conversion action을 확인한다. 설치 최적화와 첫 활성 사용자 최적화를 구분한다.
4. 공유되지 않는 `CampaignBudget(explicitly_shared=false)`를 만든다.
5. `Campaign`을 `PAUSED`로 생성한다.
6. `advertising_channel_sub_type=APP_CAMPAIGN`, `app_campaign_setting.app_id`, 목표 유형, `target_cpa` 또는 허용된 bidding strategy를 설정한다.
7. AdGroup과 `AppAdInfo`를 만들고 텍스트·이미지·YouTube 영상 asset resource를 연결한다.
8. mutate 응답의 resource name, request ID, payload hash를 감사 로그에 쓴다.
9. 승인 버전과 payload hash가 같을 때만 `ENABLED`로 바꾼다.

첫 활성·앱 내 행동 입찰은 측정 선행 조건이 있다. [Google의 GA4/Firebase 연결 절차](https://support.google.com/analytics/answer/13823256)는 Firebase SDK, 이벤트, key event, Play·Google Ads 링크, auto-tagging, conversion 생성을 요구한다. 현재 개인정보 계약을 바꾸지 않고 이 SDK를 몰래 추가하면 안 된다.

### 5.2 Meta Ads

Meta는 캠페인 → 광고 세트 → 광고의 세 단계다. [Meta의 공식 캠페인 구조 안내](https://www.facebook.com/help/messenger-app/621956575422138/)도 캠페인에서 objective, 광고 세트에서 앱·performance goal·audience, 광고에서 identity와 creative를 고르게 한다.

어댑터 원칙:

1. Business Manager, 광고 계정, Facebook Page/Instagram identity, Meta app을 연결한다.
2. 현재 Graph API 버전에서 App Promotion objective와 앱 promoted object 필드를 메타데이터로 검증한다. Graph 버전 enum을 프런트엔드에 영구 하드코딩하지 않는다.
3. Campaign, Ad Set, Ad를 모두 `PAUSED`로 만든다.
4. 앱 스토어 URL, Android application ID, 지역, 연령, 예산, 최적화 이벤트, 소재를 연결한다.
5. App Events via Meta SDK/API 또는 선택한 MMP에서 설치·활성 이벤트가 수신되는지 확인한다. Meta의 [Business Tools 설명](https://www.facebook.com/help/331509497253087/)에는 Facebook SDK App Events와 App Events API가 측정 도구로 포함된다.
6. 플랫폼 review와 우리 2인 승인을 모두 통과한 뒤에만 활성화한다.

Meta의 앱 캠페인 자동화는 플랫폼 안의 학습을 맡긴다. 우리 optimizer가 광고 세트 예산을 매시간 흔들어 학습을 초기화하지 않도록 최소 관찰 기간과 변경 쿨다운을 둔다.

### 5.3 TikTok Ads

[TikTok Business API의 현재 앱 프로모션 계약](https://business-api.tiktok.com/gateway/docs/index?doc_id=1739499616346114&identify_key=c0138ffadd90a955c1f0670a56fe348d1d40680b3c89461e09f78ed26785164b&language=ENGLISH)은 `objective_type=APP_PROMOTION`, 설치 캠페인은 `app_promotion_type=APP_INSTALL`, Android는 `promotion_type=APP_ANDROID`를 사용한다. 앱은 `/app/list/`로 얻은 TikTok app ID여야 하고 일부 최적화에는 MMP SAN 활성화가 필요하다.

어댑터 순서:

1. advertiser ID와 OAuth access token scope를 확인한다.
2. 등록 앱, Play URL, MMP/SAN 상태를 확인한다.
3. 세로 영상, 썸네일, 제목, CTA와 AI 생성물 고지를 검사한다.
4. Campaign/Ad Group/Ad를 `DISABLE` 상태로 생성한다.
5. API request ID, TikTok entity ID, payload hash를 저장한다.
6. 플랫폼 심사와 내부 승인 뒤에만 `ENABLE`로 전환한다.

### 5.4 초기 제외

- Apple Search Ads는 현재 Android 앱에 부적합하다.
- Google App Campaign 내부의 YouTube·Play·Display는 별도 플랫폼 어댑터로 중복 생성하지 않는다.
- Reddit, Pinterest 등은 계정·한국 타기팅·앱 딥링크·측정·API 운영 권한이 확인될 때 adapter catalog에 추가한다.
- 대행사나 비공식 자동화가 계정 비밀번호를 대신 보관하는 방식은 사용하지 않는다.

## 6. 예산 배분과 자동 최적화

### 6.1 콜드 스타트

성과 데이터가 없을 때 특정 플랫폼이 더 좋다고 꾸며내지 않는다. 적합 판정을 통과한 플랫폼에만 균등 탐색안을 만들고 운영자가 예산 비중을 바꿀 수 있게 한다. 모든 플랫폼이 부적합하면 비중은 0이다.

### 6.2 학습 이후

```text
platform score =
  P(활성 CAC <= 승인 상한)
  × D7 품질 계수
  × 측정 신뢰도
  × 정책·운영 안정성 계수
```

자동 변경 규칙:

- 하루 총액과 캠페인 총액은 승인값을 절대 넘지 않는다.
- 한 번에 한 플랫폼 비중을 승인된 변화폭 안에서만 바꾼다.
- 관찰 기간과 최소 활성 사용자 수를 채우기 전에는 `판단 보류`다.
- 지출 데이터 지연, conversion 중복, 통화 불일치가 있으면 자동 최적화를 멈춘다.
- CAC 상한 초과, 크래시/ANR 증가, 별점·지원 문의 악화, 정책 경고가 있으면 pause를 제안하거나 승인 정책에 따라 자동 중지한다.
- 예산 증액은 언제나 새 승인이다. 시스템의 성공 판단이 결제 권한 확장을 의미하지 않는다.

## 7. 측정 계약

### 7.1 이벤트 계층

```text
ad_impression / ad_click       플랫폼 보고서
install_referrer_received      Google Play Install Referrer
first_open                     앱 최초 실행
ime_setup_complete             Android 설정에서 새글 활성화 완료
first_real_input               비민감 입력창에서 첫 실제 입력 완료
d7_active                      설치 코호트 7일차 재사용
ad_revenue_d30                 해당 코호트의 30일 광고 순수익
```

[Play Install Referrer API](https://developer.android.com/google/play/installreferrer)는 referrer URL과 클릭·설치 시작 시각, 최초 설치 앱 버전을 제공한다. 원문 referrer를 무기한 저장하지 말고 검증된 campaign key로 정규화한 뒤 보존 기간을 적용한다.

IME 특성상 입력 내용, 앱 패키지별 타이핑 내용, 클립보드, 음성 원문은 캠페인 측정에 절대 쓰지 않는다. `first_real_input`은 성공 여부와 시각만 기록하며 입력 문자열을 포함하지 않는다.

### 7.2 귀속과 인과

- 플랫폼 attributed install/conversion은 각 플랫폼 최적화와 청구 대사용이다.
- 통합 보고는 동일 timezone, currency, attribution window로 정규화한다.
- last-click 숫자를 광고가 만든 순증 효과라고 부르지 않는다.
- 초기에는 지역 또는 기간 홀드아웃을 설계한다. Google Ads의 [Experiments 안내](https://support.google.com/google-ads/answer/10682377)는 App asset/custom experiment로 원본과 실험을 비교할 수 있게 한다.
- 표본이 작으면 p-value만 보지 않고 효과 크기·신뢰구간·사업상 최소 차이를 함께 저장한다.

### 7.3 MMP 결정

AppsFlyer, Adjust 같은 MMP는 Meta·TikTok을 함께 크게 운영할 때 유용하지만 또 하나의 SDK·계약·비용·Data Safety 변경이다. 초기 Google-only 설치 실험에 자동 도입하지 않는다. 다음 조건을 모두 만족할 때 재결정한다.

- 두 개 이상의 유료 플랫폼이 실제 집행 준비됨
- 중복 귀속 문제가 재무 대사를 방해함
- MMP 데이터 처리 계약과 보존·삭제 경로 검토 완료
- SDK 성능·IME 민감성·Data Safety 변경 E2E 완료

## 8. 백엔드와 보안

권장 배치:

```text
Cloudflare Access / OIDC
  -> 별도 Pages project: AVENUE Control
      -> Campaign Worker API
          -> D1: commands, plans, approvals, platform entities, spend, audit
          -> Queue: platform mutate/report jobs
          -> Cron: 보고서 동기화·예산 감시
          -> R2: 원본 보고서와 소재 버전
          -> Worker secrets: OAuth refresh tokens, API secrets, signing key
```

Cloudflare Access는 [공개 hostname 앞에서 identity-aware proxy](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/choose-application-type/)로 동작한다. Access 정책이 실제로 적용됐다는 증거 없이 Pages URL을 공개하면 안 된다.

### 8.1 역할

| 역할 | 가능 | 불가 |
|---|---|---|
| viewer | 계획·성과 읽기 | 생성·승인·집행 |
| campaign-operator | 명령·소재·드라이런 작성 | 자기 예산 승인 |
| finance-approver | 총액·통화·결제 책임 승인 | 소재 수정 |
| policy-reviewer | 카피·연령·지역·개인정보 검토 | 예산 증액 |
| publisher | 승인된 hash 제출·pause | 승인값 변경 |
| incident-admin | 전역 획득 캠페인 kill switch | 감사 로그 수정 |
| auditor | 추가 전용 로그 조회 | 모든 mutation |

### 8.2 핵심 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/v1/campaign-commands:plan` | 자연어를 구조화하고 드라이런 생성 |
| `GET` | `/v1/platforms/readiness` | 계정·API·소재·측정 적합성 조회 |
| `POST` | `/v1/campaign-plans/:id/approval-requests` | 예산·정책 승인 요청 |
| `POST` | `/v1/approvals/:id:approve` | WebAuthn 재인증 후 승인 |
| `POST` | `/v1/campaign-plans/:id:dispatch` | 승인 hash와 일치하는 PAUSED 초안 제출 |
| `POST` | `/v1/platform-campaigns/:id:enable` | 최종 활성화 |
| `POST` | `/v1/platform-campaigns/:id:pause` | 단일 캠페인 즉시 중지 |
| `POST` | `/v1/acquisition-kill-switch` | 모든 유료 획득 중지 |
| `GET` | `/v1/acquisition/performance` | 지출·설치·활성·D7·LTV 통합 보고 |
| `GET` | `/v1/audit` | 추가 전용 변경 로그 |

`Idempotency-Key`를 모든 mutate에 요구한다. 재시도는 동일 플랫폼 entity를 다시 만들지 않고 이전 응답을 돌려준다. platform request ID와 response hash는 비밀값을 제거한 뒤 저장한다.

### 8.3 D1 스키마 요약

```sql
CREATE TABLE campaign_plans (
  plan_id TEXT PRIMARY KEY,
  command_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  state TEXT NOT NULL,
  goal TEXT NOT NULL,
  daily_budget_micros INTEGER,
  total_budget_micros INTEGER,
  currency_code TEXT NOT NULL,
  plan_json TEXT NOT NULL,
  plan_hash TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE approvals (
  approval_id TEXT PRIMARY KEY,
  plan_id TEXT NOT NULL,
  plan_hash TEXT NOT NULL,
  kind TEXT NOT NULL,
  approver_id TEXT NOT NULL,
  decision TEXT NOT NULL,
  decided_at TEXT NOT NULL
);

CREATE TABLE platform_campaigns (
  platform TEXT NOT NULL,
  external_campaign_id TEXT NOT NULL,
  plan_id TEXT NOT NULL,
  status TEXT NOT NULL,
  approved_budget_micros INTEGER NOT NULL,
  request_hash TEXT NOT NULL,
  last_synced_at TEXT,
  PRIMARY KEY (platform, external_campaign_id)
);

CREATE TABLE spend_ledger (
  platform TEXT NOT NULL,
  external_campaign_id TEXT NOT NULL,
  report_date TEXT NOT NULL,
  currency_code TEXT NOT NULL,
  spend_micros INTEGER NOT NULL,
  source_report_hash TEXT NOT NULL,
  imported_at TEXT NOT NULL,
  PRIMARY KEY (platform, external_campaign_id, report_date, source_report_hash)
);
```

금액은 원본 통화와 micros 정수로 보존한다. 관리자 표시용 KRW 환산값은 적용 환율 ID와 함께 별도 파생값으로 만든다.

## 9. 수익금과 광고비 관리

한 화면에 보여도 계정과 책임은 분리한다.

```text
Monetization ledger
  ILRD 추정 -> 네트워크 확정 -> 지급 명세 -> 은행 입금

Acquisition spend ledger
  승인 상한 -> 플랫폼 일별 지출 -> invoice -> 카드/계좌 결제 -> 환불·크레딧

Contribution view
  설치 코호트별 광고 순수익 - 같은 코호트 획득비
```

- 매출과 광고비를 서로 상계해 원장을 지우지 않는다.
- VAT, 원천세, 플랫폼 수수료, 환전 손익은 별도 계정으로 둔다.
- “예상 ROAS”와 “확정 회계 수익률”을 다른 필드로 둔다.
- 광고 플랫폼 지출 지연이 있으면 budget watcher는 더 보수적인 값인 `승인 상한 - 확인 지출 - 미확정 예약`을 사용한다.
- 카드 결제 실패, account suspension, spend anomaly는 실시간 레일과 별도 incident channel로 보낸다.

## 10. 관리자 UI 구현 상태

`admin/`에 다음을 구현했다.

- 관제 대시보드의 `앱 안 수익화 ↔ 앱 밖 사용자 획득` 이중 루프
- 자연어 캠페인 명령, 목표, 기간, 일 예산, 목표 CAC 입력
- Google Ads·Meta Ads·TikTok Ads 적합성 카드
- 드라이런 계획과 총 광고비 상한
- Play·개인정보·측정·계정·API·소재·예산·2인 승인 가드
- 운영 집행 버튼의 fail-closed 비활성화
- 지출 → 설치 → 첫 실행 → IME 활성화 → 첫 입력 → D7 측정 계약
- 활성 CAC, D7, 순 기여와 홀드아웃 원칙
- 모든 수치가 미연결임을 드러내는 빈 상태

현재 UI와 도메인은 로컬 데모다. 실제 Worker, D1, Access, 광고 계정과 연결됐다고 표현하면 안 된다.

## 11. 배포 순서

### 11.1 공개 사이트와 GitHub RC

1. `verify-play-data-safety-contract.ps1`을 통과한다.
2. release contract 전체를 현재 HEAD에서 통과시킨다.
3. 공개 privacy 페이지를 Cloudflare Pages `saegul`에 배포한다.
4. `/privacy/` 본문과 CSS/JS MIME·hash를 운영 URL에서 확인한다.
5. 새 앱 릴리스는 RC가 아닌 Play 제출용 AAB, SBOM, source archive, 서명·privacy 검증을 다시 만든다.

### 11.2 Google Play

1. 패키지와 Play App Signing 업로드 인증서를 확인한다.
2. 목표 연령, 광고 포함 여부, Data Safety, 개인정보 URL, 콘텐츠 등급을 제출한다.
3. 내부/비공개 트랙에서 설치, 기본 IME 전환, 한글 플러그인, 개인정보 고지, 실제 입력을 검증한다.
4. production review를 통과하고 선택 국가의 공개 Play URL을 확인한다.
5. 캠페인 시스템의 `STORE_NOT_PRODUCTION` 게이트를 증거 기반으로 닫는다.

### 11.3 내부 관리자

1. 공개 `saegul` 프로젝트와 다른 Pages project·hostname을 만든다.
2. 배포 전에 Cloudflare Access self-hosted application과 allow policy를 적용한다.
3. Access 미인증 302/login, 승인 사용자 200, 비승인 사용자 차단을 확인한다.
4. Worker JWT audience 검증, RBAC, D1 migrations, Queue dead-letter를 검증한다.
5. 그 뒤에만 관리자 URL을 운영으로 취급한다.

### 11.4 광고 플랫폼

1. Google Ads만 test account와 테스트 token으로 adapter contract test를 통과시킨다.
2. 운영 developer token, 결제 프로필, Play/Firebase 링크가 준비되면 Google-only 최소 캠페인을 `PAUSED`로 만든다.
3. 운영자가 Ads UI의 예산·지역·소재·conversion을 대조하고 활성화한다.
4. Meta·TikTok은 앱 이벤트/MMP와 세로 영상 소재가 준비된 뒤 별도 실험으로 연다.
5. 첫 실험부터 영구 홀드아웃과 중단선을 설정한다.

## 12. 테스트와 운영 중단 조건

단위 테스트:

- Play production 미확인 시 제출 차단
- 예산이 없거나 승인자가 작성자와 같을 때 차단
- 모든 외부 조건이 준비돼도 반환 모드는 먼저 `dry-run`
- 분모가 0일 때 CAC·D7·ROAS를 0으로 꾸미지 않고 `null`
- 일·총액 상한, 통화, rounding, timezone 경계
- API 재시도 멱등성과 중복 entity 방지

통합 테스트:

- 각 플랫폼 sandbox/test account에 PAUSED/DISABLED만 생성
- token 만료·scope 부족·rate limit·부분 성공 보상 처리
- 광고 플랫폼 지출 보고 지연과 중복 행
- Access JWT 만료, 잘못된 audience, RBAC 우회
- D1 transaction 실패와 Queue 중복 delivery

브라우저 E2E:

- 드라이런 생성 뒤 차단 이유가 보임
- 예산을 입력해도 외부 게이트가 남으면 집행 버튼은 disabled
- 플랫폼을 빼도 최소 하나는 유지
- 320px부터 1440px, 글꼴 1.3배, 키보드 탐색, reduced motion
- 숫자와 실시간 이벤트가 실제 연결처럼 보이지 않음

즉시 중단:

- 승인 총액 초과 가능성 또는 보고 지연으로 잔여 예산 계산 불가
- Play/광고 플랫폼 정책 경고·게재 제한·계정 정지
- 활성 CAC가 승인 상한을 관찰 기간 이후에도 초과
- D1/D7, 앱 삭제, 별점, 지원 문의가 사전 중단선을 초과
- 크래시·ANR·IME 활성화 실패 증가
- 측정 동의 또는 Data Safety 불일치
- 소재가 승인 hash와 다름

## 13. 실제 집행 전 받아야 할 값

- Play production 공개 URL과 국가
- 목표 연령과 광고 포함 여부 최종 답변
- Google Ads manager/client customer ID와 developer token 접근 수준
- Meta Business/광고 계정/Page/Instagram/Meta app ID
- TikTok advertiser/app ID와 MMP/SAN 상태
- 사용할 측정 방식과 명시적 동의 문구
- 광고 소재 원본과 사용 권리
- 일 예산, 총 예산, 통화, 기간, 활성 CAC 상한
- 작성자와 다른 재무 승인자
- 결제·세금·invoice 책임자

이 값이 없으면 시스템은 계획과 체크리스트까지만 만들고 광고비를 쓰지 않는다.
