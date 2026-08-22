# 새글 키보드 광고 수익화와 AVENUE 운영 설계

> 조사 기준일: 2026-08-22
> 상태: 제품·정책·기술 설계 완료, 광고 SDK와 운영 백엔드는 아직 미연결
> 관리자 UI: `admin/`의 로컬 운영 데모

## 1. 결론

설정 화면을 열 때 가끔 전면 광고를 보여주는 방식은 채택하면 안 된다. 사용자가 설정을 눌렀다면 다음에 나와야 하는 것은 설정이다. Google Play는 사용자가 다른 행동을 선택한 직후 예기치 않게 나타나는 전면 삽입 광고를 허용하지 않으며, 키보드는 다른 앱 위에서 동작하는 IME라 신뢰 손실도 일반 앱보다 크다.

새글 키보드에 맞는 수익화 우선순위는 다음과 같다.

1. **명시적 보상형 응원 광고**: `정보 > 개발자 응원`에서 사용자가 직접 “응원하고 광고 보기”를 선택한다.
2. **전용 후원 화면의 네이티브 광고**: 설정 행과 혼동되지 않는 별도 후원 표면에 광고 라벨을 붙인다.
3. **선택 기능 완료 뒤의 제한적 전면 광고 실험**: 예를 들어 테마 내보내기 완료 뒤, 최소 7세션 이후, 일 1회 이하, 24시간 쿨다운, 홀드아웃과 이탈 중단 조건을 갖춘 경우에만 파일럿한다.
4. **구독 또는 1회 후원**: 광고 없는 후원 선택지를 함께 제공한다. 광고 매출만 강제하는 것보다 충성 사용자에게 정직하다.

다음 위치는 제품 코드와 관리자 정책 엔진에서 이중으로 차단한다.

- 설정·상세·사전 등 사용자가 요청한 목적지에 들어가기 전
- 실제 키보드 입력과 조합이 일어나는 IME 표면
- 첫 실행 콘텐츠보다 전
- 권한 요청 전후
- 오류 복구, 데이터 가져오기, 삭제 확인과 같은 긴장도가 높은 흐름
- 광고를 보지 않으면 핵심 키보드 기능을 사용할 수 없게 만드는 위치

## 2. 근거

### 2.1 정책

- [Google Play 광고 정책](https://support.google.com/googleplay/android-developer/answer/9857753?hl=ko)은 사용자가 다른 행동을 선택한 직후의 예기치 않은 전면 삽입 광고와 다른 앱 또는 기기 기능을 방해하는 광고를 금지한다.
- [Google Play 광고 정책 FAQ](https://support.google.com/googleplay/android-developer/answer/12271244?hl=ko)는 전면 광고를 콘텐츠의 자연스러운 중단점에 두라고 설명한다. 사용자가 명시적으로 선택한 보상형 광고는 별도 범주다.
- [Coalition for Better Ads 모바일 앱 기준](https://www.betterads.org/standards/)과 [연구 개요](https://www.betterads.org/research/)는 인터럽트형 전면 광고, 인터럽트형 동영상, 건너뛸 수 없는 동영상, 앱 시작 동영상을 사용자가 특히 싫어하는 형식으로 분류한다.
- 앱 리뷰 120만 건을 분석한 [Why People Hate Your App](https://arxiv.org/abs/1702.07681)은 광고 불만이 빈도, 시점, 위치에 집중됨을 보고한다.

### 2.2 현재 저장소와의 충돌

현재 새글 키보드의 공개 약속은 광고가 없는 상태를 전제로 한다.

- `site/privacy/index.html`: 광고 SDK나 분석 추적을 넣지 않는다고 명시한다.
- `site/faq/index.html`: 개발자 서버, 광고 SDK, 분석 추적기가 없다고 설명한다.
- `docs/independent-fork/privacy-data-safety.md`: 광고와 데이터 판매가 없다는 계약을 담고 있다.
- `docs/independent-fork/privacy-data-safety-contract.json`: 현재 네트워크 제공자와 데이터 처리 경계를 기계 판독 가능한 계약으로 고정한다.

따라서 광고 SDK를 추가하는 일은 단순 기능 추가가 아니라 개인정보 계약 변경이다. 이 문서와 관리자 데모를 만들었다고 기존 개인정보 문구를 미리 바꾸면 안 된다. 실제 SDK, 동의 흐름, 목표 연령, Data Safety 답변, 테스트가 모두 준비된 릴리스에서 같은 변경 집합으로 갱신해야 한다.

## 3. AVENUE 모델

AVENUE는 “광고가 나타날 수 있는 하나의 운영 경로”다. 광고 단위 ID만 저장하지 않고 사용자 맥락과 안전 규칙을 함께 묶는다.

```ts
type Avenue = {
  id: string;
  name: string;
  screen: string;
  trigger: string;
  format: "rewarded" | "native" | "interstitial";
  status: "active" | "experiment" | "paused";
  dailyCap: number;
  cooldownMinutes: number;
  minSessions: number;
  minActions: number;
  requiresConsent: boolean;
  networkGroup: string;
  policyRisk: "low" | "medium" | "high";
  version: number;
};
```

### 3.1 권장 초기 AVENUE

| ID | 위치와 트리거 | 형식 | 초기 상한 | 상태 | 판단 |
|---|---|---:|---:|---|---|
| `support-rewarded` | 정보 > 개발자 응원에서 직접 선택 | 보상형 | 일 3회, 20분 간격 | 운영 후보 | 가장 낮은 적대감 |
| `support-native` | 전용 후원 화면의 별도 광고 블록 | 네이티브 | 일 8회, 10분 간격 | 운영 후보 | 설정 행처럼 위장 금지 |
| `theme-export-complete` | 선택적 테마 내보내기가 성공한 뒤 | 전면 | 일 1회, 24시간 간격 | 실험 | 가드레일 준비 후만 |
| `settings-entry` | 설정을 열 때 | 전면 | 0회 | 영구 중지 | 정책·신뢰 위험으로 차단 |

### 3.2 정책 엔진 불변식

관리자 화면에서 금지 설정을 저장하지 못하게 하는 것만으로는 부족하다. 앱이 마지막 방어선이어야 한다.

```kotlin
enum class BlockReason {
    DESTINATION_INTERRUPT,
    IME_SURFACE,
    PERMISSION_FLOW,
    FIRST_LAUNCH,
    CONSENT_UNAVAILABLE,
    GLOBAL_KILL_SWITCH,
    FREQUENCY_CAPPED,
    CONFIG_EXPIRED,
}

data class AdDecision(
    val allow: Boolean,
    val reason: BlockReason? = null,
    val configVersion: Long,
)

interface AdPolicyEngine {
    fun evaluate(venue: AdVenueId, context: AdContext): AdDecision
}
```

필수 규칙은 다음과 같다.

- 원격 구성이 없거나 서명·만료 검증에 실패하면 `allow=false`다.
- 전역 킬 스위치가 켜져 있으면 SDK 호출 자체를 하지 않는다.
- EEA 등 필요한 지역에서 동의가 확보되지 않았다면 광고 요청을 만들지 않는다.
- 앱 로컬 일일 상한과 서버 상한 중 더 낮은 값을 사용한다.
- 기기 시간이 되돌아가거나 저장소가 손상되면 상한을 초기화하지 않고 노출을 차단한다.
- 한 번의 노출 기회에 여러 네트워크를 앱 코드가 직접 반복 호출하지 않는다.

## 4. Android 구현

### 4.1 모듈 경계

기존 설정 Fragment에 SDK 코드를 직접 넣지 않는다.

```text
app navigation
  -> AdOpportunityCoordinator
      -> AdPolicyEngine
      -> ConsentGate
      -> LocalFrequencyStore
      -> AdGateway
          -> AdMobGateway
      -> RevenueEventSink
```

```kotlin
interface AdGateway {
    suspend fun preload(venue: AdVenueId): AdLoadResult
    suspend fun showRewarded(activity: Activity, venue: AdVenueId): AdShowResult
    suspend fun showInterstitial(activity: Activity, venue: AdVenueId): AdShowResult
    fun nativeAd(venue: AdVenueId): Flow<NativeAdState>
    fun destroy()
}
```

이 추상화는 나중에 미디에이터를 바꿀 수 있게 해 주지만, 한 릴리스에서 구현체는 하나만 활성화한다.

### 4.2 의존성

조사 시점의 Google 문서는 Google Mobile Ads SDK `25.4.0`, UMP SDK `4.0.0` 예시를 제공한다. 실제 구현 직전에 공식 릴리스 노트와 어댑터 호환표를 다시 확인한다.

```toml
# gradle/libs.versions.toml 예시
googleMobileAds = "25.4.0"
googleUmp = "4.0.0"

[libraries]
google-mobile-ads = { module = "com.google.android.gms:play-services-ads", version.ref = "googleMobileAds" }
google-ump = { module = "com.google.android.ump:user-messaging-platform", version.ref = "googleUmp" }
```

개발과 자동화에서는 반드시 Google의 테스트 광고 단위 또는 테스트 기기 설정만 사용한다. 운영 광고를 개발자가 반복해서 보거나 클릭하면 무효 트래픽이 된다. 관련 지침은 [무효 트래픽 방지](https://support.google.com/admob/answer/3342054?hl=ko)와 [광고 게재 제한](https://support.google.com/admob/answer/6213019?hl=ko)을 따른다.

### 4.3 동의 순서

[UMP 시작 가이드](https://developers.google.com/admob/android/privacy?hl=ko)의 순서를 그대로 지킨다.

1. 앱 시작마다 `requestConsentInfoUpdate()`를 호출한다.
2. 필요하면 동의 폼을 로드해 보여준다.
3. `consentInformation.canRequestAds()`가 `true`일 때만 광고 SDK 초기화와 로드를 시작한다.
4. 개인정보 옵션 진입점이 필요하면 설정에 별도 메뉴를 노출한다.
5. 미디에이션 파트너까지 동의 범위에 포함되는지 [GDPR 미디에이션 지침](https://developers.google.com/admob/android/privacy/gdpr?hl=ko)을 확인한다.

```kotlin
consentInformation.requestConsentInfoUpdate(
    activity,
    params,
    {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
            if (consentInformation.canRequestAds()) adRuntime.initializeOnce()
        }
    },
    { error -> consentAudit.record(error); adRuntime.disableForSession() },
)
```

실패 시 비개인화 광고로 임의 강등하지 않는다. 법적 근거와 Google 설정이 확인되지 않은 상태에서는 세션 광고를 끄는 쪽이 맞다.

### 4.4 보상형 응원 흐름

```text
개발자 응원 화면 진입
  -> 광고를 보지 않아도 사용할 수 있음을 표시
  -> 사용자가 “응원하고 광고 보기” 선택
  -> 정책·동의·상한 평가
  -> 준비된 광고만 표시
  -> 보상 콜백 후 감사 메시지
  -> 실패하면 재촉 없이 일반 후원 옵션으로 복귀
```

보상은 핵심 기능 해제가 아니라 작은 비소모성 표현으로 둔다. 예: 응원 횟수, 감사 배지, 개발자 메시지. 키보드 기능, 사전, 테마 색상 같은 핵심 기능을 광고 시청 뒤에 잠그지 않는다.

### 4.5 제한적 전면 광고 파일럿

[AdMob 전면 광고 가이드](https://developers.google.com/admob/android/interstitial?hl=ko)는 자연스러운 전환점에서 표시하라고 안내한다. 다음 조건을 모두 만족하는 한 AVENUE만 파일럿한다.

- 선택 기능이 성공적으로 끝났다.
- 사용자는 이미 결과를 확인했다.
- 다음 핵심 목적지로 가기 전에 화면이 광고 때문에 지연되지 않는다.
- 최소 7세션과 해당 기능 3회 사용 이후다.
- 앱 로컬 상한 일 1회, 최소 24시간 쿨다운이다.
- AdMob 광고 단위 상한도 일 1회로 설정한다. [AdMob 빈도 제한](https://support.google.com/admob/answer/6244508?hl=ko)은 앱·광고 단위 제한 중 낮은 값을 적용하지만 변경 반영 지연과 약간의 초과 가능성이 있어 로컬 상한을 함께 둔다.
- 1% 이상 영구 홀드아웃으로 장기 이탈을 비교한다.
- 설정 완료율, D1/D7 잔존율, 앱 제거 프록시, 별점과 지원 문의가 악화되면 자동 중지한다.

앱 오픈 광고는 기술적으로 가능하지만 새글 키보드에는 권장하지 않는다. 사용자가 설정 앱을 여는 빈도가 낮고 대기 맥락이 없기 때문이다. [AdMob 앱 오픈 권장사항](https://developers.google.com/admob/android/app-open?hl=ko)도 사용자가 이미 기다리는 동안, 여러 번 사용한 뒤에 노출하라고 설명한다.

### 4.6 생명주기와 메모리

- 광고 객체는 `Activity`보다 오래 보존하지 않는다.
- `onDestroy`에서 native 광고와 어댑터 자원을 해제한다.
- 구성 변경 중 중복 `show()`를 막는 원자적 상태를 둔다.
- 백그라운드로 간 뒤 복귀한 오래된 광고는 만료 시간을 검사한다.
- 광고 표시 콜백이 오지 않아도 앱 탐색이 막히지 않도록 타임아웃과 복구 경로를 둔다.
- IME 프로세스에 광고 SDK 초기화를 섞지 않는다. 설정용 Activity 프로세스 경계에서만 동작시킨다.

## 5. 여러 광고 시스템 통합

### 5.1 권장 구조

초기 컨트롤 플레인은 **AdMob Mediation 하나**로 둔다. [AdMob 미디에이션 광고 소스](https://developers.google.com/admob/android/mediation/choose-networks?hl=ko)에서 지원되는 AppLovin, Unity, ironSource 등 수요원을 bidding 우선으로 연결한다.

```text
새글 앱
  -> AdGateway
      -> AdMob Mediation
          -> Google demand
          -> AppLovin bidding adapter
          -> Unity bidding adapter
          -> ironSource bidding adapter
```

하지 않을 것:

- AdMob, MAX, LevelPlay를 최상위 오케스트레이터로 동시에 초기화
- 앱 또는 자체 서버가 실시간 단가를 보고 노출 기회를 임의 분배
- 동일 노출 기회에 Google 광고 요청을 반복 호출
- AVENUE별 SDK를 제각각 직접 호출

[AdMob 미디에이션 행동 정책](https://support.google.com/admob/answer/2753860?hl=ko)은 게시자 소유 중개자가 실시간 가격을 이용해 노출 기회를 동적으로 배분하거나 동일 기회에 Google을 반복 호출하는 행위를 제한한다. 관리자 페이지는 트리거, 상한, 킬 스위치, 실험 비율을 관리하되 실시간 경매 가격을 라우팅하지 않는다.

### 5.2 어댑터 운영

각 앱 릴리스에 다음을 고정한다.

- Google Mobile Ads SDK 버전
- 미디에이션 어댑터 버전과 해당 네트워크 SDK 버전
- ProGuard/R8 규칙
- 네트워크별 테스트 광고 단위
- UMP 파트너 목록과 개인정보 링크
- 초기화 상태와 adapter response latency 수집

[ResponseInfo](https://developers.google.com/admob/android/response-info?hl=ko)로 선택된 광고 소스, 각 어댑터의 지연, 오류를 수집한다. 이 값은 개인 식별자가 아니라 운영 진단 이벤트로 제한하고 보존 기간을 정한다.

## 6. 수익 이벤트와 회계 대사

### 6.1 ILRD 수집

[Impression-level ad revenue](https://developers.google.com/admob/android/impression-level-ad-revenue?hl=ko)의 `OnPaidEventListener`를 광고 객체를 만든 직후 연결하고 이벤트를 지연 없이 서버로 보낸다.

```json
{
  "eventId": "01J...",
  "provider": "admob",
  "venueId": "support-rewarded",
  "adUnitAlias": "support_rewarded_prod",
  "valueMicros": 18234,
  "currencyCode": "KRW",
  "precision": "ESTIMATED",
  "adSource": "Google Ads",
  "adSourceInstance": "bidding-default",
  "responseId": "redacted-or-hashed",
  "configVersion": 42,
  "occurredAt": "2026-08-22T12:34:56.123Z"
}
```

원칙:

- 금액은 부동소수점이 아니라 `valueMicros` 정수로 보존한다.
- 원본 통화와 환산 통화를 분리한다.
- `precision`을 보존해 예상값과 확정값을 구분한다.
- 사용자 광고 ID, 입력 내용, 앱 사용 중인 타사 앱 이름은 보내지 않는다.
- 세션 연결이 필요하면 짧게 회전하는 임의 식별자를 사용하고 재식별에 쓰지 않는다.
- `eventId`로 재전송을 멱등 처리한다.

### 6.2 세 원장

| 원장 | 출처 | 용도 |
|---|---|---|
| 노출 원장 | 앱 ILRD | AVENUE와 실시간 추세, 이상 탐지 |
| 확정 보고 원장 | AdMob·파트너 보고 API | 월말 예상액 조정 |
| 입금 원장 | 지급 명세와 은행 | 실제 현금 수금 확인 |

[AdMob 미디에이션 보고서 API](https://developers.google.com/admob/api/reference/rest/v1/accounts.mediationReport/generate)와 [네트워크 보고서 API](https://developers.google.com/admob/api/reference/rest/v1/accounts.networkReport/generate)를 매일 수집한다. API는 OAuth를 사용하며 토큰은 앱이나 브라우저에 두지 않고 Worker secret으로 보관한다.

대사 식:

```text
ILRD 추정액 합계
  -> 네트워크 확정 보고 차이 = 무효 트래픽, 환율, 지연 보고, 조정
  -> 지급 명세 차이 = 지급 기준액, 보류, 세금, 이전월 이월
  -> 은행 입금 차이 = 수수료, 환전, 송금 지연
```

[AdMob 지급 일정](https://support.google.com/admob/answer/2772140?hl=ko)은 일반적으로 월초에 전월 수익을 확정하고 지급 조건이 충족되면 21일 무렵 지급을 시작한다. [지급 기준액](https://support.google.com/admob/answer/2772208?hl=ko)은 통화와 계정에 따라 다르므로 관리자에 하드코딩하지 않는다. [AdMob과 미디에이션 비교](https://support.google.com/admob/answer/9234653?hl=ko)에 따라 Google 외 제3자 네트워크 몫은 게시자가 해당 파트너에서 직접 수금해야 할 수 있다.

### 6.3 수익 시나리오

수익을 보장하는 숫자는 만들지 않는다. 다음 식에 실제 운영 데이터를 넣는다.

```text
예상 수익 = 노출수 / 1,000 × 관측 eCPM
보상형 노출수 = DAU × 응원 화면 도달률 × 자발 시청률 × 인당 시청 횟수
전면 노출수 = 파일럿 대상 행동 완료수 × 자격 충족률 × 홀드아웃 제외율
```

| 시나리오 입력 | 보수적 예시 | 중간 예시 | 설명 |
|---|---:|---:|---|
| DAU | 5,000 | 20,000 | 실제 분석 전 가정 |
| 응원 화면 도달률 | 2% | 4% | 제품 내 전용 진입점 |
| 자발 시청률 | 20% | 30% | 강제 노출 아님 |
| 인당 월 시청 | 1.5 | 2.0 | 로컬 상한 이내 |
| 월 보상형 노출 | 450 | 14,400 | 산식 예시 |

eCPM은 국가, 계절, 광고 형식, 채움률, 사용자 동의에 따라 크게 달라진다. 실제 ILRD가 쌓이기 전에는 외부 평균을 매출 예측으로 취급하지 않는다.

## 7. 관리자 시스템

### 7.1 현재 구현

`admin/`에는 다음이 구현돼 있다.

- 관제 대시보드: 정책 차단을 매출보다 먼저 표시
- AVENUE 목록, 상태 필터, 추가·편집, 브라우저 로컬 저장
- 금지 AVENUE의 저장을 막는 정책 미리보기
- 네트워크 상태 화면
- ILRD, 확정 보고, 입금의 대사 화면
- 전역 킬 스위치
- 정책 가드레일과 감사 로그
- 일시정지 가능한 결정론적 실시간 데모 이벤트 레일
- 어두운·밝은 테마와 모바일 레이아웃

모든 수치는 화면에 데모임을 명시했다. 광고 계정, SDK, 운영 서버와 연결된 것처럼 가장하지 않는다.

### 7.2 운영 백엔드 권장안

기존 공개 `site/`와 관리자를 섞지 않는다.

```text
Cloudflare Access 또는 OIDC
  -> Cloudflare Pages: admin 정적 앱
      -> Worker API
          -> D1: AVENUE 버전, 감사 로그, 수익 집계, 대사
          -> KV: 최신 서명 구성과 킬 스위치 캐시
          -> Durable Object 또는 SSE: 운영 이벤트 스트림
          -> Cron Trigger: AdMob·파트너 보고서 수집
          -> Worker secrets: OAuth refresh token, signing key
```

앱이 받는 구성은 서버가 서명한다.

```json
{
  "version": 42,
  "issuedAt": "2026-08-22T00:00:00Z",
  "expiresAt": "2026-08-23T00:00:00Z",
  "globalKillSwitch": false,
  "avenues": [],
  "signature": "base64-ed25519"
}
```

앱에 공개키를 내장하고 서명, 만료, 버전 단조 증가를 확인한다. 검증 실패 시 광고는 꺼진다.

### 7.3 핵심 API

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| `GET` | `/v1/avenues` | viewer | 운영 구성과 버전 조회 |
| `POST` | `/v1/avenues` | operator | 초안 생성 |
| `PUT` | `/v1/avenues/:id` | operator | 새 버전 저장 |
| `POST` | `/v1/avenues/:id/publish` | publisher | 정책 검사 뒤 원자적 발행 |
| `POST` | `/v1/global-kill-switch` | incident-admin | 전역 송출 중지 |
| `POST` | `/v1/revenue-events:batch` | app attestation | ILRD 멱등 수집 |
| `GET` | `/v1/revenue/summary` | finance | 예상·확정·입금 대사 |
| `GET` | `/v1/events/stream` | viewer | SSE 운영 이벤트 |
| `GET` | `/v1/audit` | auditor | 추가 전용 감사 로그 |

### 7.4 D1 스키마 요약

```sql
CREATE TABLE avenue_versions (
  avenue_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  status TEXT NOT NULL,
  config_json TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (avenue_id, version)
);

CREATE TABLE revenue_events (
  event_id TEXT PRIMARY KEY,
  venue_id TEXT NOT NULL,
  provider TEXT NOT NULL,
  value_micros INTEGER NOT NULL,
  currency_code TEXT NOT NULL,
  precision TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  received_at TEXT NOT NULL
);

CREATE TABLE audit_log (
  audit_id TEXT PRIMARY KEY,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  before_hash TEXT,
  after_hash TEXT,
  created_at TEXT NOT NULL
);
```

원시 ILRD를 D1에 무기한 쌓지 않는다. 일별·AVENUE별 집계를 만든 뒤 원시 이벤트는 보존 정책에 따라 R2 또는 별도 분석 저장소로 내보낸다.

### 7.5 권한과 보안

- `viewer`: 읽기만
- `operator`: 초안 편집
- `publisher`: 발행 승인
- `finance`: 보고서·지급 대사
- `incident-admin`: 킬 스위치
- `auditor`: 변경 불가 감사 로그

고위험 AVENUE 활성화, 상한 상향, 킬 스위치 해제는 작성자와 승인자를 분리한다. 모든 변경은 이전·이후 해시와 구성 버전을 기록한다. 브라우저에는 광고 네트워크 OAuth 토큰이나 서명 개인키를 내려보내지 않는다.

## 8. 개인정보, 연령, Play Console

출시 전에 반드시 결정할 것:

1. [Google Play 목표 연령과 콘텐츠](https://support.google.com/googleplay/android-developer/answer/9867159?hl=ko)에서 실제 대상 연령을 확정한다.
2. 아동이 대상에 포함되면 [가족 정책](https://support.google.com/googleplay/android-developer/answer/17190352?hl=ko), [가족 데이터 관행](https://support.google.com/googleplay/android-developer/answer/11043825?hl=ko), [자체 인증 광고 SDK](https://support.google.com/googleplay/android-developer/answer/12955712?hl=ko)를 적용한다.
3. 광고 ID와 SDK 데이터 처리를 [광고 ID 정책](https://support.google.com/googleplay/android-developer/answer/6048248?hl=ko)과 [Data Safety SDK 책임](https://support.google.com/googleplay/android-developer/answer/10787469?hl=ko)에 맞춰 선언한다.
4. 공개 개인정보처리방침, FAQ, Play Data Safety, 앱 내 개인정보 옵션을 같은 릴리스에서 갱신한다.
5. SDK 공급자 목록, 데이터 종류, 목적, 보존 기간, 삭제·옵트아웃 경로를 문서화한다.

목표 연령이 확정되지 않았고 현재 공개 약속이 “광고 SDK 없음”인 동안 프로덕션 광고 릴리스는 차단 상태다.

## 9. 테스트 전략

### 9.1 단위 테스트

- 설정 진입 전면 광고 차단
- IME·권한·첫 실행 위치 차단
- 동의 미확보 차단
- 앱 로컬 상한과 쿨다운 경계
- 기기 시간 역행과 저장소 손상
- 킬 스위치와 만료 구성
- `valueMicros` 합산과 통화 분리
- 이벤트 재전송 멱등성

### 9.2 Android 통합 테스트

- UMP 동의 필요·불필요·오류 분기
- 테스트 광고 로드 성공·실패·타임아웃
- Activity 재생성과 백그라운드 복귀
- 광고 닫힘 뒤 원래 탐색과 포커스 복구
- 네트워크 없음, 느린 네트워크, 어댑터 초기화 실패
- 보상 콜백 중복·누락
- 프로덕션 광고 단위가 디버그 빌드에서 절대 로드되지 않음

### 9.3 실제 기기 E2E

- 삼성 Fold의 외부·내부 화면과 글꼴 1.3배
- 설정에서 응원 화면까지 접근성 탐색
- 광고를 거절해도 키보드의 모든 기능이 동일하게 동작
- 광고 표시 중 회전, 홈 이동, 통화 등 Activity 중단
- VPN·DNS 차단·광고 차단기 환경에서 빠른 실패
- TalkBack에서 광고 라벨, 닫기, 보상 결과가 읽힘

### 9.4 운영 실험 중단 조건

절대 매출만 성공 지표로 두지 않는다.

- 설정·지원 화면 완료율이 기준군보다 2%p 이상 악화
- D1 또는 D7 잔존율의 통계적·실무적 악화
- 광고 관련 별점·지원 문의가 사전 기준을 넘음
- 무효 트래픽, 정책 경고, 게재 제한 발생
- 응답 지연 또는 ANR·크래시 증가
- 개인정보 동의 오류율 증가

조건을 넘으면 원격 킬 스위치로 즉시 중지하고 사후 검토 전 재개하지 않는다.

## 10. 단계별 출시 계획

### 0단계: 제품·법적 결정

- 목표 연령과 아동 대상 여부 확정
- 광고 없는 후원 옵션과 광고형 응원 UX 확정
- 개인정보처리방침 초안과 Data Safety delta 검토
- AdMob 조직, 지급 프로필, 세금·은행 담당자 지정

### 1단계: 백엔드와 관리자

- Cloudflare Access/OIDC
- D1 스키마, 감사 로그, AVENUE 버전 발행
- 서명 구성, 만료, 킬 스위치
- AdMob 보고서 수집과 ILRD 수집
- 데모 adapter를 운영 API adapter로 교체

### 2단계: Android 테스트 통합

- UMP와 Mobile Ads SDK를 별도 빌드 flavor에 추가
- `AdGateway`, `AdPolicyEngine`, 로컬 상한 구현
- 테스트 광고 단위만 사용
- 현재 개인정보 계약과 코드 차이 보고서 생성

### 3단계: 자발적 보상형만 제한 출시

- 내부 테스트와 비공개 트랙
- 1% 영구 홀드아웃
- ILRD와 확정 보고 대사 확인
- 실제 기기 접근성·수명주기 E2E
- 정책 경고와 무효 트래픽 감시

### 4단계: 네이티브 후원 표면

- 광고 라벨과 설정 행 오인 가능성 사용성 테스트
- 빈도 상한과 성능 확인
- 기준군 대비 이탈·별점 평가

### 5단계: 전면 광고 파일럿 여부 재결정

보상형과 네이티브만으로 운영 목표가 충족되면 전면 광고는 넣지 않는다. 매출 부족만으로 사용자 신뢰를 희생할 이유는 없다. 파일럿이 필요하더라도 한 AVENUE, 일 1회, 24시간 쿨다운, 완료 뒤의 자연스러운 중단점으로 제한한다.

## 11. 프로덕션 릴리스 게이트

- [ ] 설정 진입·IME·권한·첫 실행 전면 광고가 코드와 서버에서 모두 차단됨
- [ ] 목표 연령과 Families 적용 여부 확정
- [ ] UMP 실제 지역 시나리오 검증
- [ ] Play Data Safety와 개인정보처리방침 동시 갱신
- [ ] SDK·어댑터·R8 release 빌드 검증
- [ ] 테스트 광고 ID가 production에, production ID가 debug에 섞이지 않음
- [ ] 관리자 OIDC, RBAC, 2인 승인, 감사 로그 검증
- [ ] 서명 구성 실패·만료가 fail-closed
- [ ] ILRD, 확정 보고, 지급 명세의 월말 대사 리허설
- [ ] 실제 삼성 기기와 TalkBack E2E
- [ ] 킬 스위치가 앱의 다음 구성 조회에서 실제 송출을 중단함
- [ ] Play 비공개 트랙에서 정책·크래시·ANR 이상 없음

하나라도 비어 있으면 프로덕션 광고 릴리스가 아니다.

## 12. 공식 자료

- [Google Play 광고 정책](https://support.google.com/googleplay/android-developer/answer/9857753?hl=ko)
- [Google Play 광고 정책 FAQ](https://support.google.com/googleplay/android-developer/answer/12271244?hl=ko)
- [Coalition for Better Ads 표준](https://www.betterads.org/standards/)
- [AdMob Android 전면 광고](https://developers.google.com/admob/android/interstitial?hl=ko)
- [AdMob UMP 시작](https://developers.google.com/admob/android/privacy?hl=ko)
- [AdMob 미디에이션 광고 소스](https://developers.google.com/admob/android/mediation/choose-networks?hl=ko)
- [AdMob ILRD](https://developers.google.com/admob/android/impression-level-ad-revenue?hl=ko)
- [AdMob ResponseInfo](https://developers.google.com/admob/android/response-info?hl=ko)
- [AdMob API 시작](https://developers.google.com/admob/api/v1/getting-started?hl=ko)
- [AdMob 미디에이션 보고서 API](https://developers.google.com/admob/api/reference/rest/v1/accounts.mediationReport/generate)
- [AdMob 네트워크 보고서 API](https://developers.google.com/admob/api/reference/rest/v1/accounts.networkReport/generate)
- [AdMob 빈도 제한](https://support.google.com/admob/answer/6244508?hl=ko)
- [AdMob 미디에이션 행동 정책](https://support.google.com/admob/answer/2753860?hl=ko)
- [Google Play Data Safety와 SDK](https://support.google.com/googleplay/android-developer/answer/10787469?hl=ko)
