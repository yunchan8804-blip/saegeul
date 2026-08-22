export type AvenueFormat = "rewarded" | "native" | "interstitial";
export type AvenueStatus = "active" | "experiment" | "paused";
export type PolicyRisk = "low" | "medium" | "high";

export interface Avenue {
  id: string;
  name: string;
  screen: string;
  trigger: string;
  format: AvenueFormat;
  status: AvenueStatus;
  dailyCap: number;
  cooldownMinutes: number;
  minSessions: number;
  minActions: number;
  requiresConsent: boolean;
  networkGroup: string;
  policyRisk: PolicyRisk;
  notes: string;
  version: number;
  updatedAt: string;
}

export const seedAvenues: Avenue[] = [
  {
    id: "support-rewarded",
    name: "응원하고 광고 보기",
    screen: "정보 > 개발자 응원",
    trigger: "사용자가 보상형 광고 버튼을 직접 선택",
    format: "rewarded",
    status: "active",
    dailyCap: 3,
    cooldownMinutes: 20,
    minSessions: 2,
    minActions: 0,
    requiresConsent: true,
    networkGroup: "AdMob bidding",
    policyRisk: "low",
    notes: "광고를 보지 않아도 모든 키보드 기능을 사용할 수 있어야 함",
    version: 4,
    updatedAt: "2026-08-22T09:42:00+09:00",
  },
  {
    id: "support-native",
    name: "후원 파트너 안내",
    screen: "정보 > 개발자 응원",
    trigger: "전용 후원 화면의 콘텐츠 사이",
    format: "native",
    status: "active",
    dailyCap: 8,
    cooldownMinutes: 10,
    minSessions: 1,
    minActions: 0,
    requiresConsent: true,
    networkGroup: "AdMob native",
    policyRisk: "low",
    notes: "설정 행처럼 보이지 않도록 광고 라벨과 별도 표면을 사용",
    version: 2,
    updatedAt: "2026-08-22T09:18:00+09:00",
  },
  {
    id: "theme-export-complete",
    name: "테마 내보내기 완료",
    screen: "테마 > 내보내기 결과",
    trigger: "선택 기능이 성공적으로 끝난 뒤 다음 행동 전에",
    format: "interstitial",
    status: "experiment",
    dailyCap: 1,
    cooldownMinutes: 1440,
    minSessions: 7,
    minActions: 3,
    requiresConsent: true,
    networkGroup: "AdMob bidding",
    policyRisk: "medium",
    notes: "1% 홀드아웃과 이탈률 가드레일이 준비된 뒤에만 파일럿",
    version: 1,
    updatedAt: "2026-08-22T08:52:00+09:00",
  },
  {
    id: "settings-entry",
    name: "설정 진입 전면 광고",
    screen: "설정 루트",
    trigger: "설정 화면을 열 때",
    format: "interstitial",
    status: "paused",
    dailyCap: 0,
    cooldownMinutes: 10080,
    minSessions: 99,
    minActions: 99,
    requiresConsent: true,
    networkGroup: "차단",
    policyRisk: "high",
    notes: "목적지 진입을 막는 예기치 않은 전면 광고라 운영 정책에서 영구 차단",
    version: 3,
    updatedAt: "2026-08-22T08:36:00+09:00",
  },
];
