export type LedgerConnection = "unconnected" | "demo" | "live";

export type ImpressionPrecision =
  | "ESTIMATED"
  | "PRECISE"
  | "PUBLISHER_PROVIDED"
  | "UNKNOWN";

export interface MoneyMicros {
  valueMicros: number;
  currencyCode: string;
}

export interface ImpressionEvent {
  eventId: string;
  source: string;
  venueId: string;
  valueMicros: number;
  currencyCode: string;
  precision: ImpressionPrecision;
  occurredAt: string;
  reporting?: MoneyMicros;
}

export interface ConfirmedReport {
  reportId: string;
  source: string;
  valueMicros: number;
  currencyCode: string;
  periodStart: string;
  periodEnd: string;
  reporting?: MoneyMicros;
}

export interface DepositRecord {
  depositId: string;
  source: string;
  valueMicros: number;
  currencyCode: string;
  depositedAt: string;
  reporting?: MoneyMicros;
}

export interface LedgerInput {
  connection: LedgerConnection;
  impressions: readonly ImpressionEvent[];
  confirmedReports: readonly ConfirmedReport[];
  deposits: readonly DepositRecord[];
}

export type SourceRowStatus = "ok" | "pending" | "unmatched";

export interface SourceVariance {
  source: string;
  currencyCode: string;
  estimatedMicros: number;
  confirmedMicros: number;
  depositedMicros: number;
  estimatedMinusConfirmedMicros: number;
  confirmedMinusDepositedMicros: number;
  status: SourceRowStatus;
}

export interface UnmatchedRow {
  kind: "impression" | "confirmed" | "deposit";
  id: string;
  source: string;
  valueMicros: number;
  currencyCode: string;
}

export interface PendingRow {
  kind: "confirmation" | "deposit";
  source: string;
  currencyCode: string;
  valueMicros: number;
}

export interface CurrencyTotal {
  currencyCode: string;
  estimatedMicros: number;
  confirmedMicros: number;
  depositedMicros: number;
}

export interface Reconciliation {
  connection: LedgerConnection;
  empty: boolean;
  estimated: MoneyMicros | null;
  confirmed: MoneyMicros | null;
  deposited: MoneyMicros | null;
  totalsByCurrency: CurrencyTotal[];
  perSource: SourceVariance[];
  unmatched: UnmatchedRow[];
  pending: PendingRow[];
  duplicateEventIds: string[];
  insertedEventIds: string[];
}

export interface InsertResult<T> {
  items: T[];
  insertedIds: string[];
  duplicateIds: string[];
}

function requireIntegerMicros(valueMicros: number, label: string): void {
  if (!Number.isInteger(valueMicros)) {
    throw new Error(`${label} must be an integer`);
  }
}

function requireId(id: string, label: string): void {
  if (!id.trim()) {
    throw new Error(`${label} is required`);
  }
}

function requireCurrency(currencyCode: string): void {
  if (!currencyCode.trim()) {
    throw new Error("currencyCode is required");
  }
}

function insertByKey<T>(
  existing: readonly T[],
  incoming: readonly T[],
  keyOf: (item: T) => string,
  validate: (item: T) => void,
): InsertResult<T> {
  const items = [...existing];
  const seen = new Set(existing.map(keyOf));
  const insertedIds: string[] = [];
  const duplicateIds: string[] = [];

  for (const item of incoming) {
    validate(item);
    const key = keyOf(item);
    if (seen.has(key)) {
      duplicateIds.push(key);
      continue;
    }
    seen.add(key);
    items.push(item);
    insertedIds.push(key);
  }

  return { items, insertedIds, duplicateIds };
}

export function insertImpressions(
  existing: readonly ImpressionEvent[],
  incoming: readonly ImpressionEvent[],
): InsertResult<ImpressionEvent> {
  return insertByKey(existing, incoming, (item) => item.eventId, (item) => {
    requireId(item.eventId, "eventId");
    requireIntegerMicros(item.valueMicros, "valueMicros");
    requireCurrency(item.currencyCode);
    if (item.reporting) {
      requireIntegerMicros(item.reporting.valueMicros, "reporting.valueMicros");
      requireCurrency(item.reporting.currencyCode);
    }
  });
}

export function insertConfirmedReports(
  existing: readonly ConfirmedReport[],
  incoming: readonly ConfirmedReport[],
): InsertResult<ConfirmedReport> {
  return insertByKey(
    existing,
    incoming,
    (item) => item.reportId,
    (item) => {
      requireId(item.reportId, "reportId");
      requireIntegerMicros(item.valueMicros, "valueMicros");
      requireCurrency(item.currencyCode);
    },
  );
}

export function insertDeposits(
  existing: readonly DepositRecord[],
  incoming: readonly DepositRecord[],
): InsertResult<DepositRecord> {
  return insertByKey(
    existing,
    incoming,
    (item) => item.depositId,
    (item) => {
      requireId(item.depositId, "depositId");
      requireIntegerMicros(item.valueMicros, "valueMicros");
      requireCurrency(item.currencyCode);
    },
  );
}

function emptyUnconnected(): Reconciliation {
  return {
    connection: "unconnected",
    empty: true,
    estimated: null,
    confirmed: null,
    deposited: null,
    totalsByCurrency: [],
    perSource: [],
    unmatched: [],
    pending: [],
    duplicateEventIds: [],
    insertedEventIds: [],
  };
}

function moneyOrNull(
  totals: CurrencyTotal[],
  field: keyof Omit<CurrencyTotal, "currencyCode">,
): MoneyMicros | null {
  if (totals.length !== 1) return null;
  return {
    valueMicros: totals[0][field],
    currencyCode: totals[0].currencyCode,
  };
}

function addInteger(left: number, right: number): number {
  const sum = left + right;
  if (!Number.isInteger(sum)) {
    throw new Error("micros overflow");
  }
  return sum;
}

export function unconnectedLedger(): LedgerInput {
  return {
    connection: "unconnected",
    impressions: [],
    confirmedReports: [],
    deposits: [],
  };
}

export function demoLedgerFixture(): LedgerInput {
  const impressions: ImpressionEvent[] = [
    {
      eventId: "evt-admob-1",
      source: "admob",
      venueId: "support-rewarded",
      valueMicros: 50_000_000,
      currencyCode: "KRW",
      precision: "ESTIMATED",
      occurredAt: "2026-08-01T00:00:00.000Z",
    },
    {
      eventId: "evt-admob-1",
      source: "admob",
      venueId: "support-rewarded",
      valueMicros: 50_000_000,
      currencyCode: "KRW",
      precision: "ESTIMATED",
      occurredAt: "2026-08-01T00:00:00.000Z",
    },
    {
      eventId: "evt-admob-2",
      source: "admob",
      venueId: "support-native",
      valueMicros: 25_000_000,
      currencyCode: "KRW",
      precision: "ESTIMATED",
      occurredAt: "2026-08-02T00:00:00.000Z",
    },
    {
      eventId: "evt-applovin-1",
      source: "applovin",
      venueId: "support-rewarded",
      valueMicros: 10_000_000,
      currencyCode: "KRW",
      precision: "ESTIMATED",
      occurredAt: "2026-08-03T00:00:00.000Z",
    },
    {
      eventId: "evt-unity-1",
      source: "unity",
      venueId: "theme-export-complete",
      valueMicros: 8_000_000,
      currencyCode: "KRW",
      precision: "ESTIMATED",
      occurredAt: "2026-08-04T00:00:00.000Z",
    },
  ];

  return {
    connection: "demo",
    impressions,
    confirmedReports: [
      {
        reportId: "rpt-admob-2026-08",
        source: "admob",
        valueMicros: 70_000_000,
        currencyCode: "KRW",
        periodStart: "2026-08-01",
        periodEnd: "2026-08-31",
      },
      {
        reportId: "rpt-applovin-2026-08",
        source: "applovin",
        valueMicros: 10_000_000,
        currencyCode: "KRW",
        periodStart: "2026-08-01",
        periodEnd: "2026-08-31",
      },
    ],
    deposits: [
      {
        depositId: "dep-admob-2026-07",
        source: "admob",
        valueMicros: 60_000_000,
        currencyCode: "KRW",
        depositedAt: "2026-08-21T00:00:00.000Z",
      },
      {
        depositId: "dep-orphan-adjust",
        source: "bank-adjust",
        valueMicros: 1_000_000,
        currencyCode: "KRW",
        depositedAt: "2026-08-22T00:00:00.000Z",
      },
    ],
  };
}

export function reconcileLedgers(input: LedgerInput): Reconciliation {
  if (input.connection === "unconnected") {
    return emptyUnconnected();
  }

  const impressions = insertImpressions([], input.impressions);
  const reports = insertConfirmedReports([], input.confirmedReports);
  const deposits = insertDeposits([], input.deposits);

  const keys = new Set<string>();
  const estimated = new Map<string, number>();
  const confirmed = new Map<string, number>();
  const deposited = new Map<string, number>();

  function bucket(source: string, currencyCode: string): string {
    return `${source}\0${currencyCode}`;
  }

  function add(
    map: Map<string, number>,
    source: string,
    currencyCode: string,
    valueMicros: number,
  ) {
    const key = bucket(source, currencyCode);
    keys.add(key);
    map.set(key, addInteger(map.get(key) ?? 0, valueMicros));
  }

  for (const event of impressions.items) {
    add(estimated, event.source, event.currencyCode, event.valueMicros);
  }
  for (const report of reports.items) {
    add(confirmed, report.source, report.currencyCode, report.valueMicros);
  }
  for (const deposit of deposits.items) {
    add(deposited, deposit.source, deposit.currencyCode, deposit.valueMicros);
  }

  const perSource: SourceVariance[] = [...keys]
    .sort()
    .map((key) => {
      const separator = key.indexOf("\0");
      const source = key.slice(0, separator);
      const currencyCode = key.slice(separator + 1);
      const estimatedMicros = estimated.get(key) ?? 0;
      const confirmedMicros = confirmed.get(key) ?? 0;
      const depositedMicros = deposited.get(key) ?? 0;
      let status: SourceRowStatus = "ok";
      if (depositedMicros > 0 && confirmedMicros === 0 && estimatedMicros === 0) {
        status = "unmatched";
      } else if (
        (estimatedMicros > 0 && confirmedMicros === 0) ||
        (confirmedMicros > 0 && depositedMicros === 0)
      ) {
        status = "pending";
      }
      return {
        source,
        currencyCode,
        estimatedMicros,
        confirmedMicros,
        depositedMicros,
        estimatedMinusConfirmedMicros: estimatedMicros - confirmedMicros,
        confirmedMinusDepositedMicros: confirmedMicros - depositedMicros,
        status,
      };
    });

  const unmatched: UnmatchedRow[] = perSource
    .filter((row) => row.status === "unmatched")
    .map((row) => ({
      kind: "deposit" as const,
      id: row.source,
      source: row.source,
      valueMicros: row.depositedMicros,
      currencyCode: row.currencyCode,
    }));

  const pending: PendingRow[] = perSource.flatMap((row) => {
    const rows: PendingRow[] = [];
    if (row.estimatedMicros > 0 && row.confirmedMicros === 0) {
      rows.push({
        kind: "confirmation",
        source: row.source,
        currencyCode: row.currencyCode,
        valueMicros: row.estimatedMicros,
      });
    }
    if (row.confirmedMicros > 0 && row.depositedMicros === 0) {
      rows.push({
        kind: "deposit",
        source: row.source,
        currencyCode: row.currencyCode,
        valueMicros: row.confirmedMicros,
      });
    }
    return rows;
  });

  const totalsByCurrencyMap = new Map<string, CurrencyTotal>();
  for (const row of perSource) {
    const current = totalsByCurrencyMap.get(row.currencyCode) ?? {
      currencyCode: row.currencyCode,
      estimatedMicros: 0,
      confirmedMicros: 0,
      depositedMicros: 0,
    };
    current.estimatedMicros = addInteger(
      current.estimatedMicros,
      row.estimatedMicros,
    );
    current.confirmedMicros = addInteger(
      current.confirmedMicros,
      row.confirmedMicros,
    );
    current.depositedMicros = addInteger(
      current.depositedMicros,
      row.depositedMicros,
    );
    totalsByCurrencyMap.set(row.currencyCode, current);
  }
  const totalsByCurrency = [...totalsByCurrencyMap.values()].sort((a, b) =>
    a.currencyCode.localeCompare(b.currencyCode),
  );

  const empty =
    impressions.items.length === 0 &&
    reports.items.length === 0 &&
    deposits.items.length === 0;

  return {
    connection: input.connection,
    empty,
    estimated: empty ? null : moneyOrNull(totalsByCurrency, "estimatedMicros"),
    confirmed: empty ? null : moneyOrNull(totalsByCurrency, "confirmedMicros"),
    deposited: empty ? null : moneyOrNull(totalsByCurrency, "depositedMicros"),
    totalsByCurrency,
    perSource,
    unmatched,
    pending,
    duplicateEventIds: impressions.duplicateIds,
    insertedEventIds: impressions.insertedIds,
  };
}
