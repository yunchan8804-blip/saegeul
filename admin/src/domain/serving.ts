import type { Avenue } from "./avenue";
import { canPublish, evaluateAvenue, type PolicyCode } from "./policy";

export type ServingDenyReason =
  | "CONFIG_MISSING"
  | "CONFIG_UNSIGNED"
  | "CONFIG_EXPIRED"
  | "CONFIG_NON_MONOTONIC"
  | "GLOBAL_KILL_SWITCH"
  | "VENUE_NOT_FOUND"
  | PolicyCode;

export interface SignedAvenueConfig {
  version: number;
  issuedAt: string;
  expiresAt: string;
  globalKillSwitch: boolean;
  signature: string | null;
  avenues: Avenue[];
}

export interface ServingRequest {
  config: SignedAvenueConfig | null;
  lastAcceptedVersion: number | null;
  nowMs: number;
  venueId: string;
}

export interface ServingDecision {
  allow: boolean;
  reason: ServingDenyReason | null;
  configVersion: number | null;
}

function deny(
  reason: ServingDenyReason,
  configVersion: number | null,
): ServingDecision {
  return { allow: false, reason, configVersion };
}

export function evaluateConfigValidity(
  config: SignedAvenueConfig | null,
  lastAcceptedVersion: number | null,
  nowMs: number,
): ServingDenyReason | null {
  if (!config) return "CONFIG_MISSING";
  if (!Number.isInteger(config.version) || config.version <= 0) {
    return "CONFIG_NON_MONOTONIC";
  }
  if (!config.signature?.trim()) return "CONFIG_UNSIGNED";
  const expiresAt = Date.parse(config.expiresAt);
  if (!Number.isFinite(expiresAt) || nowMs >= expiresAt) return "CONFIG_EXPIRED";
  const issuedAt = Date.parse(config.issuedAt);
  if (!Number.isFinite(issuedAt)) return "CONFIG_EXPIRED";
  if (config.version <= (lastAcceptedVersion ?? 0)) return "CONFIG_NON_MONOTONIC";
  return null;
}

export function evaluateServing(request: ServingRequest): ServingDecision {
  const validity = evaluateConfigValidity(
    request.config,
    request.lastAcceptedVersion,
    request.nowMs,
  );
  if (validity) {
    return deny(validity, request.config?.version ?? null);
  }

  const config = request.config!;
  if (config.globalKillSwitch) {
    return deny("GLOBAL_KILL_SWITCH", config.version);
  }

  const avenue = config.avenues.find((item) => item.id === request.venueId);
  if (!avenue) {
    return deny("VENUE_NOT_FOUND", config.version);
  }

  const block = evaluateAvenue(avenue).find((finding) => finding.level === "block");
  if (block) {
    return deny(block.code, config.version);
  }

  return { allow: true, reason: null, configVersion: config.version };
}

export function canPublishSignedConfig(
  config: SignedAvenueConfig,
  lastAcceptedVersion: number | null,
  nowMs: number,
): boolean {
  if (evaluateConfigValidity(config, lastAcceptedVersion, nowMs)) return false;
  return config.avenues.every((avenue) => canPublish(avenue));
}

export function localDemoSignedConfig(
  avenues: Avenue[],
  options: {
    nowMs: number;
    globalKillSwitch?: boolean;
    signature?: string | null;
    version?: number;
    ttlMs?: number;
  },
): SignedAvenueConfig {
  const ttlMs = options.ttlMs ?? 86_400_000;
  return {
    version: options.version ?? 1,
    issuedAt: new Date(options.nowMs).toISOString(),
    expiresAt: new Date(options.nowMs + ttlMs).toISOString(),
    globalKillSwitch: options.globalKillSwitch ?? false,
    signature: options.signature === undefined ? "local-demo" : options.signature,
    avenues,
  };
}
