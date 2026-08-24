import type { Avenue } from "./avenue";

export type PolicyCode =
  | "DESTINATION_INTERRUPT"
  | "IME_SURFACE"
  | "PERMISSION_INTERRUPT"
  | "FIRST_LAUNCH_INTERRUPT"
  | "CONSENT_REQUIRED"
  | "CAP_TOO_HIGH"
  | "COOLDOWN_TOO_SHORT";

export interface PolicyFinding {
  code: PolicyCode;
  level: "block" | "warn";
  message: string;
}

const blockedVenueIds: Record<string, PolicyCode> = {
  "settings-entry": "DESTINATION_INTERRUPT",
  "ime-surface": "IME_SURFACE",
  "first-launch": "FIRST_LAUNCH_INTERRUPT",
  "permission-interstitial": "PERMISSION_INTERRUPT",
};

function pushBlock(
  findings: PolicyFinding[],
  code: PolicyCode,
  message: string,
) {
  if (findings.some((finding) => finding.code === code)) return;
  findings.push({ code, level: "block", message });
}

export function evaluateAvenue(avenue: Avenue): PolicyFinding[] {
  const target = `${avenue.screen} ${avenue.trigger}`.toLowerCase();
  const findings: PolicyFinding[] = [];
  const blockedById = blockedVenueIds[avenue.id];

  if (blockedById === "DESTINATION_INTERRUPT") {
    pushBlock(
      findings,
      "DESTINATION_INTERRUPT",
      "사용자가 요청한 목적지보다 먼저 전면 광고를 표시할 수 없어.",
    );
  }
  if (blockedById === "IME_SURFACE") {
    pushBlock(
      findings,
      "IME_SURFACE",
      "IME 입력 표면에는 광고를 배치할 수 없어.",
    );
  }
  if (blockedById === "PERMISSION_INTERRUPT") {
    pushBlock(
      findings,
      "PERMISSION_INTERRUPT",
      "권한 요청 전후에 광고를 끼워 넣을 수 없어.",
    );
  }
  if (blockedById === "FIRST_LAUNCH_INTERRUPT") {
    pushBlock(
      findings,
      "FIRST_LAUNCH_INTERRUPT",
      "첫 실행 콘텐츠보다 먼저 전면 광고를 표시할 수 없어.",
    );
  }

  if (
    avenue.format === "interstitial" &&
    /(설정.*열|설정.*진입|settings.*entry)/i.test(target)
  ) {
    pushBlock(
      findings,
      "DESTINATION_INTERRUPT",
      "사용자가 요청한 목적지보다 먼저 전면 광고를 표시할 수 없어.",
    );
  }
  if (/(키보드 입력|ime|composition)/i.test(target)) {
    pushBlock(
      findings,
      "IME_SURFACE",
      "IME 입력 표면에는 광고를 배치할 수 없어.",
    );
  }
  if (/(권한|permission)/i.test(target)) {
    pushBlock(
      findings,
      "PERMISSION_INTERRUPT",
      "권한 요청 전후에 광고를 끼워 넣을 수 없어.",
    );
  }
  if (
    /(첫 실행|first launch)/i.test(target) &&
    avenue.format === "interstitial"
  ) {
    pushBlock(
      findings,
      "FIRST_LAUNCH_INTERRUPT",
      "첫 실행 콘텐츠보다 먼저 전면 광고를 표시할 수 없어.",
    );
  }
  if (!avenue.requiresConsent) {
    findings.push({
      code: "CONSENT_REQUIRED",
      level: "block",
      message: "동의 상태를 확인하지 않는 AVENUE는 발행할 수 없어.",
    });
  }
  if (avenue.format === "interstitial" && avenue.dailyCap > 1) {
    findings.push({
      code: "CAP_TOO_HIGH",
      level: "warn",
      message: "전면 광고의 일일 상한은 1회를 권장해.",
    });
  }
  if (avenue.format === "interstitial" && avenue.cooldownMinutes < 1440) {
    findings.push({
      code: "COOLDOWN_TOO_SHORT",
      level: "warn",
      message: "전면 광고 사이에는 최소 24시간을 권장해.",
    });
  }
  return findings;
}

export function canPublish(avenue: Avenue) {
  return !evaluateAvenue(avenue).some((finding) => finding.level === "block");
}
