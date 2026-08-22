export type AcquisitionPlatform = "google_ads" | "meta_ads" | "tiktok_ads";

export type CampaignGoal = "installs" | "activated_users";

export type CampaignState =
  | "draft"
  | "blocked"
  | "awaiting_approval"
  | "ready"
  | "submitted"
  | "active"
  | "paused";

export type GateCode =
  | "STORE_NOT_PRODUCTION"
  | "PRIVACY_CONTRACT_FAILED"
  | "MEASUREMENT_NOT_READY"
  | "ACCOUNT_NOT_CONNECTED"
  | "PLATFORM_API_NOT_APPROVED"
  | "CREATIVE_MISSING"
  | "BUDGET_MISSING"
  | "BUDGET_NOT_APPROVED"
  | "SEPARATION_OF_DUTIES"
  | "POLICY_REVIEW_REQUIRED";

export interface CampaignGate {
  code: GateCode;
  level: "block" | "approval";
  title: string;
  detail: string;
}

export interface PlatformReadiness {
  id: AcquisitionPlatform;
  label: string;
  accountConnected: boolean;
  apiApproved: boolean;
  creativeReady: boolean;
  measurementReady: boolean;
  note: string;
}

export interface CampaignCommand {
  command: string;
  goal: CampaignGoal;
  countries: string[];
  dailyBudgetWon: number | null;
  durationDays: number;
  maxCacWon: number | null;
  selectedPlatforms: AcquisitionPlatform[];
}

export interface DispatchContext {
  storeProduction: boolean;
  privacyContractPassed: boolean;
  policyReviewed: boolean;
  budgetApproved: boolean;
  operatorId: string;
  approverId: string | null;
  platforms: PlatformReadiness[];
}

export interface ChannelAllocation {
  platform: AcquisitionPlatform;
  label: string;
  share: number;
  dailyBudgetWon: number | null;
  state: "eligible" | "blocked";
  reasons: string[];
}

export interface CampaignPlan {
  state: CampaignState;
  gates: CampaignGate[];
  allocations: ChannelAllocation[];
  totalBudgetWon: number | null;
  dispatchMode: "dry-run";
}

export interface CampaignObservation {
  spendWon: number;
  installs: number;
  activatedUsers: number;
  d7RetainedUsers: number;
  attributedRevenueWon: number;
}

export interface CampaignMetrics {
  cpiWon: number | null;
  cacWon: number | null;
  d7RetentionRate: number | null;
  roas: number | null;
  netContributionWon: number;
}

export const platformCatalog: PlatformReadiness[] = [
  {
    id: "google_ads",
    label: "Google Ads",
    accountConnected: false,
    apiApproved: false,
    creativeReady: true,
    measurementReady: false,
    note: "Play·Firebase·Google Ads 링크와 developer token 승인 필요",
  },
  {
    id: "meta_ads",
    label: "Meta Ads",
    accountConnected: false,
    apiApproved: false,
    creativeReady: true,
    measurementReady: false,
    note: "광고 계정·Meta 앱·App Events 또는 MMP 연결 필요",
  },
  {
    id: "tiktok_ads",
    label: "TikTok Ads",
    accountConnected: false,
    apiApproved: false,
    creativeReady: false,
    measurementReady: false,
    note: "광고 계정·등록 앱·MMP SAN·세로 영상 소재 필요",
  },
];

const gateCopy: Record<GateCode, Omit<CampaignGate, "code">> = {
  STORE_NOT_PRODUCTION: {
    level: "block",
    title: "Play 프로덕션 미확인",
    detail:
      "설치 가능한 공개 스토어 상세 페이지가 확인되기 전에는 집행할 수 없어.",
  },
  PRIVACY_CONTRACT_FAILED: {
    level: "block",
    title: "릴리스 개인정보 계약 실패",
    detail:
      "Data Safety와 공개 개인정보처리방침의 현재 계약을 먼저 통과해야 해.",
  },
  MEASUREMENT_NOT_READY: {
    level: "block",
    title: "활성 사용자 측정 미연결",
    detail: "설치 수가 아니라 첫 활성화와 D7을 최적화할 측정 경로가 필요해.",
  },
  ACCOUNT_NOT_CONNECTED: {
    level: "block",
    title: "광고 계정 미연결",
    detail: "선택한 플랫폼의 광고주 계정과 서버측 OAuth 연결이 필요해.",
  },
  PLATFORM_API_NOT_APPROVED: {
    level: "block",
    title: "운영 API 권한 미승인",
    detail:
      "테스트 계정 권한만으로는 비용이 발생하는 운영 캠페인을 만들지 않아.",
  },
  CREATIVE_MISSING: {
    level: "block",
    title: "필수 소재 누락",
    detail:
      "플랫폼 규격과 광고 정책을 통과한 텍스트·이미지·영상 묶음이 필요해.",
  },
  BUDGET_MISSING: {
    level: "approval",
    title: "예산 미입력",
    detail:
      "드라이런은 만들 수 있지만 일 예산과 기간 없이는 승인 요청을 보낼 수 없어.",
  },
  BUDGET_NOT_APPROVED: {
    level: "approval",
    title: "예산 승인 대기",
    detail: "광고비 상한은 재무 승인자가 명시적으로 승인해야 해.",
  },
  SEPARATION_OF_DUTIES: {
    level: "block",
    title: "작성자와 승인자 동일",
    detail: "캠페인 작성자와 광고비 승인자는 달라야 해.",
  },
  POLICY_REVIEW_REQUIRED: {
    level: "approval",
    title: "소재·타기팅 검토 대기",
    detail: "과장 표현, 연령, 지역, 개인정보 고지를 사람 검토로 확정해야 해.",
  },
};

function gate(code: GateCode): CampaignGate {
  return { code, ...gateCopy[code] };
}

export function evaluateCampaignDispatch(
  command: CampaignCommand,
  context: DispatchContext,
): CampaignGate[] {
  const gates: CampaignGate[] = [];

  if (!context.storeProduction) gates.push(gate("STORE_NOT_PRODUCTION"));
  if (!context.privacyContractPassed)
    gates.push(gate("PRIVACY_CONTRACT_FAILED"));
  if (!context.policyReviewed) gates.push(gate("POLICY_REVIEW_REQUIRED"));
  if (!command.dailyBudgetWon || command.dailyBudgetWon <= 0)
    gates.push(gate("BUDGET_MISSING"));
  else if (!context.budgetApproved) gates.push(gate("BUDGET_NOT_APPROVED"));
  if (
    context.approverId &&
    context.operatorId.trim().toLowerCase() ===
      context.approverId.trim().toLowerCase()
  )
    gates.push(gate("SEPARATION_OF_DUTIES"));

  const selected = context.platforms.filter((platform) =>
    command.selectedPlatforms.includes(platform.id),
  );
  if (selected.some((platform) => !platform.accountConnected))
    gates.push(gate("ACCOUNT_NOT_CONNECTED"));
  if (selected.some((platform) => !platform.apiApproved))
    gates.push(gate("PLATFORM_API_NOT_APPROVED"));
  if (selected.some((platform) => !platform.creativeReady))
    gates.push(gate("CREATIVE_MISSING"));
  if (selected.some((platform) => !platform.measurementReady))
    gates.push(gate("MEASUREMENT_NOT_READY"));

  return [...new Map(gates.map((item) => [item.code, item])).values()];
}

export function planCampaign(
  command: CampaignCommand,
  context: DispatchContext,
): CampaignPlan {
  const gates = evaluateCampaignDispatch(command, context);
  const selected = context.platforms.filter((platform) =>
    command.selectedPlatforms.includes(platform.id),
  );
  const eligible = selected.filter(
    (platform) =>
      platform.accountConnected &&
      platform.apiApproved &&
      platform.creativeReady &&
      platform.measurementReady,
  );
  const share = eligible.length ? 1 / eligible.length : 0;

  const allocations = selected.map((platform) => {
    const reasons = [
      !platform.accountConnected && "계정",
      !platform.apiApproved && "API 권한",
      !platform.creativeReady && "소재",
      !platform.measurementReady && "측정",
    ].filter(Boolean) as string[];
    const ready = reasons.length === 0;
    return {
      platform: platform.id,
      label: platform.label,
      share: ready ? share : 0,
      dailyBudgetWon:
        ready && command.dailyBudgetWon
          ? Math.floor(command.dailyBudgetWon * share)
          : null,
      state: ready ? ("eligible" as const) : ("blocked" as const),
      reasons,
    };
  });

  const hasBlock = gates.some((item) => item.level === "block");
  const hasApproval = gates.some((item) => item.level === "approval");
  return {
    state: hasBlock ? "blocked" : hasApproval ? "awaiting_approval" : "ready",
    gates,
    allocations,
    totalBudgetWon: command.dailyBudgetWon
      ? command.dailyBudgetWon * command.durationDays
      : null,
    dispatchMode: "dry-run",
  };
}

export function calculateCampaignMetrics(
  observation: CampaignObservation,
): CampaignMetrics {
  return {
    cpiWon:
      observation.installs > 0
        ? observation.spendWon / observation.installs
        : null,
    cacWon:
      observation.activatedUsers > 0
        ? observation.spendWon / observation.activatedUsers
        : null,
    d7RetentionRate:
      observation.activatedUsers > 0
        ? observation.d7RetainedUsers / observation.activatedUsers
        : null,
    roas:
      observation.spendWon > 0
        ? observation.attributedRevenueWon / observation.spendWon
        : null,
    netContributionWon: observation.attributedRevenueWon - observation.spendWon,
  };
}
