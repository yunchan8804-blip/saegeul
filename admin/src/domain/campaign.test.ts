import { describe, expect, it } from "vitest";
import {
  calculateCampaignMetrics,
  evaluateCampaignDispatch,
  planCampaign,
  platformCatalog,
  type CampaignCommand,
  type DispatchContext,
} from "./campaign";

const command: CampaignCommand = {
  command: "한국에서 첫 활성 사용자를 확보해",
  goal: "activated_users",
  countries: ["KR"],
  dailyBudgetWon: 100_000,
  durationDays: 7,
  maxCacWon: 3_000,
  selectedPlatforms: ["google_ads", "meta_ads"],
};

const context: DispatchContext = {
  storeProduction: true,
  privacyContractPassed: true,
  policyReviewed: true,
  budgetApproved: true,
  operatorId: "operator",
  approverId: "finance",
  platforms: platformCatalog.map((platform) => ({
    ...platform,
    accountConnected: true,
    apiApproved: true,
    creativeReady: true,
    measurementReady: true,
  })),
};

describe("campaign dispatch guard", () => {
  it("blocks spend when the Play listing is not in production", () => {
    const gates = evaluateCampaignDispatch(command, {
      ...context,
      storeProduction: false,
    });
    expect(gates.map((item) => item.code)).toContain("STORE_NOT_PRODUCTION");
  });

  it("requires a different budget approver", () => {
    const gates = evaluateCampaignDispatch(command, {
      ...context,
      approverId: "operator",
    });
    expect(gates.map((item) => item.code)).toContain("SEPARATION_OF_DUTIES");
  });

  it("creates only a dry-run and divides cold-start budget evenly", () => {
    const plan = planCampaign(command, context);
    expect(plan.state).toBe("ready");
    expect(plan.dispatchMode).toBe("dry-run");
    expect(plan.allocations.map((item) => item.share)).toEqual([0.5, 0.5]);
    expect(plan.totalBudgetWon).toBe(700_000);
  });

  it("does not invent ratios when denominators are zero", () => {
    const metrics = calculateCampaignMetrics({
      spendWon: 0,
      installs: 0,
      activatedUsers: 0,
      d7RetainedUsers: 0,
      attributedRevenueWon: 0,
    });
    expect(metrics.cpiWon).toBeNull();
    expect(metrics.cacWon).toBeNull();
    expect(metrics.d7RetentionRate).toBeNull();
    expect(metrics.roas).toBeNull();
  });
});
