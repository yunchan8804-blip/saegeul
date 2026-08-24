import { describe, expect, it } from "vitest";
import { seedAvenues, type Avenue } from "./avenue";
import { canPublish, evaluateAvenue } from "./policy";
import {
  canPublishSignedConfig,
  evaluateServing,
  localDemoSignedConfig,
  type SignedAvenueConfig,
} from "./serving";

const nowMs = Date.parse("2026-08-22T12:00:00.000Z");

function config(
  overrides: Partial<SignedAvenueConfig> = {},
  avenues: Avenue[] = seedAvenues,
): SignedAvenueConfig {
  return {
    ...localDemoSignedConfig(avenues, { nowMs }),
    ...overrides,
  };
}

function blockedPlacement(partial: Partial<Avenue> & Pick<Avenue, "id">): Avenue {
  return {
    name: partial.id,
    screen: "실험 화면",
    trigger: "사용자가 직접 선택",
    format: "rewarded",
    status: "active",
    dailyCap: 1,
    cooldownMinutes: 1440,
    minSessions: 3,
    minActions: 1,
    requiresConsent: true,
    networkGroup: "AdMob bidding",
    policyRisk: "high",
    notes: "",
    version: 1,
    updatedAt: "2026-08-22T00:00:00.000Z",
    ...partial,
  };
}

describe("fail-closed AVENUE serving", () => {
  it("denies a missing signed config", () => {
    const decision = evaluateServing({
      config: null,
      lastAcceptedVersion: null,
      nowMs,
      venueId: "support-rewarded",
    });
    expect(decision.allow).toBe(false);
    expect(decision.reason).toBe("CONFIG_MISSING");
  });

  it("denies an unsigned config", () => {
    const decision = evaluateServing({
      config: config({ signature: null }),
      lastAcceptedVersion: 0,
      nowMs,
      venueId: "support-rewarded",
    });
    expect(decision.allow).toBe(false);
    expect(decision.reason).toBe("CONFIG_UNSIGNED");
    expect(canPublishSignedConfig(config({ signature: "" }), 0, nowMs)).toBe(false);
  });

  it("denies an expired config", () => {
    const decision = evaluateServing({
      config: config({ expiresAt: "2026-08-22T11:59:59.000Z" }),
      lastAcceptedVersion: 0,
      nowMs,
      venueId: "support-rewarded",
    });
    expect(decision.allow).toBe(false);
    expect(decision.reason).toBe("CONFIG_EXPIRED");
  });

  it("denies a non-monotonic config version", () => {
    const decision = evaluateServing({
      config: config({ version: 3 }),
      lastAcceptedVersion: 3,
      nowMs,
      venueId: "support-rewarded",
    });
    expect(decision.allow).toBe(false);
    expect(decision.reason).toBe("CONFIG_NON_MONOTONIC");
    expect(canPublishSignedConfig(config({ version: 3 }), 3, nowMs)).toBe(false);
  });

  it("denies serving when the global kill switch is on", () => {
    const decision = evaluateServing({
      config: config({ globalKillSwitch: true, version: 4 }),
      lastAcceptedVersion: 3,
      nowMs,
      venueId: "support-rewarded",
    });
    expect(decision.allow).toBe(false);
    expect(decision.reason).toBe("GLOBAL_KILL_SWITCH");
    expect(canPublish(seedAvenues[0])).toBe(true);
    expect(
      canPublishSignedConfig(
        config(
          { globalKillSwitch: true, version: 4 },
          seedAvenues.filter((item) => canPublish(item)),
        ),
        3,
        nowMs,
      ),
    ).toBe(true);
  });

  it("cannot publish or allow a settings-entry interstitial", () => {
    const renamed = blockedPlacement({
      id: "settings-entry",
      trigger: "사용자가 응원 버튼을 직접 선택",
      screen: "정보 > 개발자 응원",
      format: "interstitial",
    });
    const signed = config({ version: 2 }, [...seedAvenues.filter((item) => item.id !== "settings-entry"), renamed]);
    const decision = evaluateServing({
      config: signed,
      lastAcceptedVersion: 1,
      nowMs,
      venueId: "settings-entry",
    });

    expect(canPublish(renamed)).toBe(false);
    expect(evaluateAvenue(renamed).map((item) => item.code)).toContain(
      "DESTINATION_INTERRUPT",
    );
    expect(canPublishSignedConfig(signed, 1, nowMs)).toBe(false);
    expect(decision.allow).toBe(false);
    expect(decision.reason).toBe("DESTINATION_INTERRUPT");
  });

  it("cannot allow IME, first-launch, or permission placements", () => {
    const placements: Avenue[] = [
      blockedPlacement({
        id: "ime-surface",
        screen: "키보드 입력",
        trigger: "composition",
        format: "native",
      }),
      blockedPlacement({
        id: "first-launch",
        screen: "첫 실행",
        trigger: "first launch interstitial",
        format: "interstitial",
      }),
      blockedPlacement({
        id: "permission-interstitial",
        screen: "권한 요청",
        trigger: "permission prompt",
        format: "interstitial",
      }),
    ];

    for (const avenue of placements) {
      const signed = config({ version: 2 }, [avenue, seedAvenues[0]]);
      const decision = evaluateServing({
        config: signed,
        lastAcceptedVersion: 1,
        nowMs,
        venueId: avenue.id,
      });
      expect(canPublish(avenue)).toBe(false);
      expect(decision.allow).toBe(false);
      expect(decision.reason).not.toBeNull();
    }
  });

  it("allows an opt-in rewarded support placement with a valid signed config", () => {
    const rewarded = seedAvenues.find((item) => item.id === "support-rewarded")!;
    const signed = config(
      { version: 2 },
      seedAvenues.filter((item) => canPublish(item)),
    );
    const decision = evaluateServing({
      config: signed,
      lastAcceptedVersion: 1,
      nowMs,
      venueId: "support-rewarded",
    });

    expect(canPublish(rewarded)).toBe(true);
    expect(canPublishSignedConfig(signed, 1, nowMs)).toBe(true);
    expect(decision).toEqual({
      allow: true,
      reason: null,
      configVersion: 2,
    });
  });
});
