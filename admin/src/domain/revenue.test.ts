import { describe, expect, it } from "vitest";
import {
  demoLedgerFixture,
  insertImpressions,
  reconcileLedgers,
  unconnectedLedger,
  type ImpressionEvent,
} from "./revenue";

function impression(
  overrides: Partial<ImpressionEvent> & Pick<ImpressionEvent, "eventId">,
): ImpressionEvent {
  return {
    source: "admob",
    venueId: "support-rewarded",
    valueMicros: 18_234,
    currencyCode: "KRW",
    precision: "ESTIMATED",
    occurredAt: "2026-08-22T12:34:56.123Z",
    ...overrides,
  };
}

describe("three-ledger revenue reconciliation", () => {
  it("preserves original valueMicros integers and currency separate from reporting conversion", () => {
    const event = impression({
      eventId: "evt-usd-1",
      valueMicros: 1_250_000,
      currencyCode: "USD",
      reporting: { valueMicros: 1_700_000_000, currencyCode: "KRW" },
    });
    const inserted = insertImpressions([], [event]);
    const reconciliation = reconcileLedgers({
      connection: "demo",
      impressions: inserted.items,
      confirmedReports: [
        {
          reportId: "rpt-usd-1",
          source: "admob",
          valueMicros: 1_200_000,
          currencyCode: "USD",
          periodStart: "2026-08-01",
          periodEnd: "2026-08-31",
        },
      ],
      deposits: [
        {
          depositId: "dep-usd-1",
          source: "admob",
          valueMicros: 1_100_000,
          currencyCode: "USD",
          depositedAt: "2026-08-21T00:00:00.000Z",
        },
      ],
    });

    expect(inserted.items[0].valueMicros).toBe(1_250_000);
    expect(inserted.items[0].currencyCode).toBe("USD");
    expect(inserted.items[0].reporting).toEqual({
      valueMicros: 1_700_000_000,
      currencyCode: "KRW",
    });
    expect(reconciliation.estimated).toEqual({
      valueMicros: 1_250_000,
      currencyCode: "USD",
    });
    expect(reconciliation.confirmed).toEqual({
      valueMicros: 1_200_000,
      currencyCode: "USD",
    });
    expect(reconciliation.deposited).toEqual({
      valueMicros: 1_100_000,
      currencyCode: "USD",
    });
    expect(reconciliation.totalsByCurrency.map((item) => item.currencyCode)).toEqual([
      "USD",
    ]);
    expect(reconciliation.perSource[0]?.estimatedMicros).toBe(1_250_000);
    expect(reconciliation.perSource[0]?.currencyCode).toBe("USD");
  });

  it("computes estimated vs confirmed vs deposited totals and per-source deltas", () => {
    const fixture = demoLedgerFixture();
    const reconciliation = reconcileLedgers(fixture);
    const bySource = Object.fromEntries(
      reconciliation.perSource.map((row) => [row.source, row]),
    );

    expect(reconciliation.connection).toBe("demo");
    expect(reconciliation.estimated).toEqual({
      valueMicros: 93_000_000,
      currencyCode: "KRW",
    });
    expect(reconciliation.confirmed).toEqual({
      valueMicros: 80_000_000,
      currencyCode: "KRW",
    });
    expect(reconciliation.deposited).toEqual({
      valueMicros: 61_000_000,
      currencyCode: "KRW",
    });

    expect(bySource.admob.estimatedMicros).toBe(75_000_000);
    expect(bySource.admob.confirmedMicros).toBe(70_000_000);
    expect(bySource.admob.depositedMicros).toBe(60_000_000);
    expect(bySource.admob.estimatedMinusConfirmedMicros).toBe(5_000_000);
    expect(bySource.admob.confirmedMinusDepositedMicros).toBe(10_000_000);

    expect(bySource.unity.status).toBe("pending");
    expect(bySource.applovin.status).toBe("pending");
    expect(bySource["bank-adjust"].status).toBe("unmatched");
    expect(reconciliation.pending.map((row) => `${row.kind}:${row.source}`)).toEqual(
      expect.arrayContaining(["confirmation:unity", "deposit:applovin"]),
    );
    expect(reconciliation.unmatched.map((row) => row.source)).toEqual(["bank-adjust"]);
  });

  it("does not double-count a duplicate eventId on the second insert", () => {
    const first = impression({ eventId: "evt-dup", valueMicros: 18_234 });
    const replay = impression({ eventId: "evt-dup", valueMicros: 99_999 });
    const afterFirst = insertImpressions([], [first]);
    const afterReplay = insertImpressions(afterFirst.items, [replay]);
    const reconciliation = reconcileLedgers({
      connection: "demo",
      impressions: [...afterReplay.items, replay],
      confirmedReports: [],
      deposits: [],
    });

    expect(afterFirst.insertedIds).toEqual(["evt-dup"]);
    expect(afterReplay.duplicateIds).toEqual(["evt-dup"]);
    expect(afterReplay.items).toHaveLength(1);
    expect(afterReplay.items[0].valueMicros).toBe(18_234);
    expect(reconciliation.duplicateEventIds).toEqual(["evt-dup"]);
    expect(reconciliation.estimated).toEqual({
      valueMicros: 18_234,
      currencyCode: "KRW",
    });
  });

  it("does not invent live KRW totals for an unconnected or empty ledger", () => {
    const stuffed = reconcileLedgers({
      ...unconnectedLedger(),
      impressions: [
        impression({
          eventId: "should-be-ignored",
          valueMicros: 82_538_000_000,
          currencyCode: "KRW",
        }),
      ],
    });
    const emptyDemo = reconcileLedgers({
      connection: "demo",
      impressions: [],
      confirmedReports: [],
      deposits: [],
    });

    expect(stuffed.connection).toBe("unconnected");
    expect(stuffed.empty).toBe(true);
    expect(stuffed.estimated).toBeNull();
    expect(stuffed.confirmed).toBeNull();
    expect(stuffed.deposited).toBeNull();
    expect(stuffed.totalsByCurrency).toEqual([]);
    expect(stuffed.perSource).toEqual([]);

    expect(emptyDemo.empty).toBe(true);
    expect(emptyDemo.estimated).toBeNull();
    expect(emptyDemo.confirmed).toBeNull();
    expect(emptyDemo.deposited).toBeNull();
    expect(emptyDemo.totalsByCurrency).toEqual([]);
  });
});
