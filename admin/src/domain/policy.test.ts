import { describe, expect, it } from "vitest";
import { seedAvenues } from "./avenue";
import { canPublish, evaluateAvenue } from "./policy";

describe("AVENUE policy engine", () => {
  it("blocks an interstitial before settings", () => {
    const avenue = seedAvenues.find((item) => item.id === "settings-entry")!;
    expect(canPublish(avenue)).toBe(false);
    expect(evaluateAvenue(avenue).map((item) => item.code)).toContain("DESTINATION_INTERRUPT");
  });

  it("allows an opt-in rewarded support placement", () => {
    const avenue = seedAvenues.find((item) => item.id === "support-rewarded")!;
    expect(canPublish(avenue)).toBe(true);
    expect(evaluateAvenue(avenue)).toEqual([]);
  });

  it("requires consent checks", () => {
    const avenue = { ...seedAvenues[0], requiresConsent: false };
    expect(evaluateAvenue(avenue).map((item) => item.code)).toContain("CONSENT_REQUIRED");
  });

  it("still blocks settings-entry after the trigger copy is renamed", () => {
    const avenue = {
      ...seedAvenues.find((item) => item.id === "settings-entry")!,
      screen: "정보 > 개발자 응원",
      trigger: "사용자가 보상형 광고 버튼을 직접 선택",
      format: "rewarded" as const,
    };
    expect(canPublish(avenue)).toBe(false);
    expect(evaluateAvenue(avenue).map((item) => item.code)).toContain(
      "DESTINATION_INTERRUPT",
    );
  });
});
