import {
  useEffect,
  useMemo,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import * as Dialog from "@radix-ui/react-dialog";
import {
  ArrowDownRight,
  ArrowUpRight,
  BellSimple,
  Broadcast,
  CaretRight,
  CheckCircle,
  ChartLineUp,
  ClockCounterClockwise,
  Coins,
  Command,
  DownloadSimple,
  Flask,
  Gauge,
  Info,
  List,
  LockKey,
  Megaphone,
  Moon,
  PaperPlaneTilt,
  Path,
  Pause,
  Play,
  Plus,
  Pulse,
  RocketLaunch,
  ShieldCheck,
  SlidersHorizontal,
  Storefront,
  Sun,
  Target,
  Warning,
  Wallet,
  X,
} from "@phosphor-icons/react";
import { Badge } from "./components/ui/badge";
import { Button } from "./components/ui/button";
import { Toggle } from "./components/ui/toggle";
import {
  seedAvenues,
  type Avenue,
  type AvenueFormat,
  type AvenueStatus,
} from "./domain/avenue";
import { canPublish, evaluateAvenue } from "./domain/policy";
import {
  calculateCampaignMetrics,
  planCampaign,
  platformCatalog,
  type AcquisitionPlatform,
  type CampaignCommand,
  type CampaignPlan,
} from "./domain/campaign";
import {
  demoLedgerFixture,
  reconcileLedgers,
  unconnectedLedger,
  type Reconciliation,
  type SourceVariance,
} from "./domain/revenue";
import {
  evaluateServing,
  localDemoSignedConfig,
} from "./domain/serving";

type ViewId =
  | "dashboard"
  | "avenues"
  | "networks"
  | "revenue"
  | "campaigns"
  | "acquisition"
  | "guardrails"
  | "audit";

const navItems: Array<{ id: ViewId; label: string; icon: typeof Gauge }> = [
  { id: "dashboard", label: "관제 대시보드", icon: Gauge },
  { id: "avenues", label: "AVENUE", icon: Path },
  { id: "networks", label: "광고 네트워크", icon: Broadcast },
  { id: "revenue", label: "수익 정산", icon: Coins },
  { id: "campaigns", label: "홍보 캠페인", icon: Megaphone },
  { id: "acquisition", label: "효과 분석", icon: ChartLineUp },
  { id: "guardrails", label: "정책 가드레일", icon: ShieldCheck },
  { id: "audit", label: "감사 로그", icon: ClockCounterClockwise },
];

const liveEvents = [
  ["paid", "₩18", "AdMob", "응원하고 광고 보기"],
  ["served", "노출", "Unity bidding", "후원 파트너 안내"],
  ["blocked", "차단", "Policy engine", "설정 진입 전면 광고"],
  ["consent", "동의", "UMP", "EEA 동의 상태 갱신"],
  ["paid", "₩11", "AdMob", "응원하고 광고 보기"],
  ["holdout", "홀드아웃", "Experiment", "테마 내보내기 완료"],
  ["campaign", "차단", "Campaign guard", "Play 프로덕션 상태 미확인"],
  ["approval", "대기", "Budget control", "광고비 승인자 지정 필요"],
] as const;

const revenuePoints = [
  42, 48, 45, 61, 58, 72, 69, 83, 78, 92, 88, 101, 97, 112,
];

function usePersistentAvenues() {
  const [{ initialAvenues, initialError }] = useState(() => {
    try {
      const saved = localStorage.getItem("saegeul-avenues-v1");
      return {
        initialAvenues: saved ? (JSON.parse(saved) as Avenue[]) : seedAvenues,
        initialError: false,
      };
    } catch {
      return { initialAvenues: seedAvenues, initialError: true };
    }
  });
  const [storageError, setStorageError] = useState(initialError);
  const [avenues, setAvenues] = useState<Avenue[]>(initialAvenues);

  useEffect(() => {
    try {
      localStorage.setItem("saegeul-avenues-v1", JSON.stringify(avenues));
    } catch {
      setStorageError(true);
    }
  }, [avenues]);

  return { avenues, setAvenues, storageError };
}

function formatWon(value: number) {
  return new Intl.NumberFormat("ko-KR", {
    style: "currency",
    currency: "KRW",
    maximumFractionDigits: 0,
  }).format(value);
}

function formatMoneyMicros(valueMicros: number, currencyCode: string) {
  const negative = valueMicros < 0;
  const major = Math.trunc(Math.abs(valueMicros) / 1_000_000);
  let formatted: string;
  try {
    formatted = new Intl.NumberFormat("ko-KR", {
      style: "currency",
      currency: currencyCode,
      maximumFractionDigits: currencyCode === "KRW" ? 0 : 2,
    }).format(major);
  } catch {
    formatted = `${major} ${currencyCode}`;
  }
  return negative ? `-${formatted}` : formatted;
}

function ledgerFieldLabel(
  reconciliation: Reconciliation,
  field: "estimated" | "confirmed" | "deposited",
) {
  if (reconciliation.connection === "unconnected") return "미연결";
  const amount = reconciliation[field];
  if (!amount) return "-";
  return formatMoneyMicros(amount.valueMicros, amount.currencyCode);
}

function sourceStatusLabel(row: SourceVariance) {
  if (row.status === "unmatched") return "미대사";
  if (row.status === "pending") {
    return row.confirmedMicros === 0 ? "보고 대기" : "입금 대기";
  }
  return "차이 확인";
}

function evaluateDemoServing(
  avenues: Avenue[],
  killSwitch: boolean,
  venueId: string,
  nowMs = Date.now(),
) {
  return evaluateServing({
    config: localDemoSignedConfig(avenues, {
      nowMs,
      globalKillSwitch: killSwitch,
      version: Math.max(1, ...avenues.map((item) => item.version)),
    }),
    lastAcceptedVersion: 0,
    nowMs,
    venueId,
  });
}

function formatLabel(format: AvenueFormat) {
  return { rewarded: "보상형", native: "네이티브", interstitial: "전면" }[
    format
  ];
}

function statusLabel(status: AvenueStatus) {
  return { active: "운영", experiment: "실험", paused: "중지" }[status];
}

function statusTone(status: AvenueStatus) {
  return status === "active"
    ? "live"
    : status === "experiment"
      ? "warn"
      : "neutral";
}

function App() {
  const { avenues, setAvenues, storageError } = usePersistentAvenues();
  const [view, setView] = useState<ViewId>("dashboard");
  const [dark, setDark] = useState(true);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [killSwitch, setKillSwitch] = useState(false);
  const [eventsPaused, setEventsPaused] = useState(false);
  const [eventIndex, setEventIndex] = useState(3);
  const [editor, setEditor] = useState<Avenue | "new" | null>(null);
  const blockedCount = avenues.filter((avenue) => !canPublish(avenue)).length;

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }, [dark]);

  useEffect(() => {
    if (eventsPaused) return;
    const tick = window.setInterval(() => {
      if (!document.hidden)
        setEventIndex((current) => (current + 1) % liveEvents.length);
    }, 4800);
    return () => window.clearInterval(tick);
  }, [eventsPaused]);

  function saveAvenue(next: Avenue) {
    setAvenues((current) => {
      const exists = current.some((item) => item.id === next.id);
      return exists
        ? current.map((item) => (item.id === next.id ? next : item))
        : [next, ...current];
    });
    setEditor(null);
  }

  const currentTitle = navItems.find((item) => item.id === view)!.label;

  return (
    <div className="app-shell">
      <aside className="sidebar" aria-label="주요 메뉴">
        <button
          className="brand"
          onClick={() => setView("dashboard")}
          aria-label="관제 대시보드로 이동"
        >
          <span className="brand__mark">
            <Command weight="bold" />
          </span>
          <span>
            <strong>AVENUE</strong>
            <small>SAEGEUL CONTROL</small>
          </span>
        </button>

        <div className="environment">
          <span className="signal" aria-hidden="true" /> 운영 데모{" "}
          <kbd>로컬</kbd>
        </div>

        <nav className="nav-list">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.id}
                className="nav-item"
                data-view={item.id}
                data-active={view === item.id || undefined}
                onClick={() => setView(item.id)}
              >
                <Icon
                  size={18}
                  weight={view === item.id ? "fill" : "regular"}
                />
                <span>{item.label}</span>
                {item.id === "guardrails" && blockedCount > 0 && (
                  <span className="nav-count">{blockedCount}</span>
                )}
              </button>
            );
          })}
        </nav>

        <div className="sidebar__footer">
          <div>
            <span>구성 스냅샷</span>
            <strong>v0.1.0 demo</strong>
          </div>
          <div>
            <span>마지막 동기화</span>
            <strong>방금 전</strong>
          </div>
          <Badge tone="warn">백엔드 미연결</Badge>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div className="topbar__title">
            <button
              className="mobile-brand"
              onClick={() => setView("dashboard")}
              aria-label="관제 대시보드로 이동"
            >
              <Command weight="bold" />
            </button>
            <div>
              <span>새글 키보드 광고 운영</span>
              <h1>{currentTitle}</h1>
            </div>
          </div>
          <div className="topbar__tools">
            <Badge tone="info">모든 수치 데모</Badge>
            <Button
              className="mobile-menu-trigger"
              size="icon"
              variant="ghost"
              onClick={() => setMobileMenuOpen((value) => !value)}
              aria-label="모바일 메뉴"
              aria-expanded={mobileMenuOpen}
            >
              <List size={20} />
            </Button>
            <Button
              size="icon"
              variant="ghost"
              onClick={() => setDark((value) => !value)}
              aria-label={dark ? "밝은 테마 사용" : "어두운 테마 사용"}
            >
              {dark ? <Sun size={19} /> : <Moon size={19} />}
            </Button>
            <Button size="icon" variant="ghost" aria-label="알림 보기">
              <BellSimple size={19} />
              <span className="notification-dot" />
            </Button>
            <div className="operator">
              <span>YC</span>
              <div>
                <strong>Yun Chan</strong>
                <small>운영 관리자</small>
              </div>
            </div>
          </div>
        </header>

        {mobileMenuOpen && (
          <div
            className="mobile-nav"
            role="navigation"
            aria-label="모바일 메뉴"
          >
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.id}
                  data-view={item.id}
                  data-active={view === item.id || undefined}
                  onClick={() => {
                    setView(item.id);
                    setMobileMenuOpen(false);
                  }}
                >
                  <Icon size={18} />
                  <span>{item.label}</span>
                </button>
              );
            })}
          </div>
        )}

        {storageError && (
          <div className="error-banner" role="alert">
            <Warning weight="fill" /> 브라우저 저장소를 읽지 못해 기본 AVENUE로
            복구했어.
          </div>
        )}
        {killSwitch && (
          <div className="kill-banner" role="alert">
            <Pause weight="fill" /> 전역 광고 송출이 중지된 상태야. 앱은 모든
            광고 요청을 건너뛰어야 해.
          </div>
        )}

        <div className="workspace-grid">
          <main className="main-content">
            <div className="view" key={view}>
              {view === "dashboard" && (
                <Dashboard
                  avenues={avenues}
                  killSwitch={killSwitch}
                  setKillSwitch={setKillSwitch}
                  setView={setView}
                />
              )}
              {view === "avenues" && (
                <AvenueList avenues={avenues} setEditor={setEditor} />
              )}
              {view === "networks" && <Networks />}
              {view === "revenue" && <Revenue />}
              {view === "campaigns" && <Campaigns />}
              {view === "acquisition" && <AcquisitionAnalytics />}
              {view === "guardrails" && (
                <Guardrails
                  avenues={avenues}
                  killSwitch={killSwitch}
                  setKillSwitch={setKillSwitch}
                />
              )}
              {view === "audit" && <Audit />}
            </div>
          </main>
          <LiveRail
            eventIndex={eventIndex}
            paused={eventsPaused}
            onPausedChange={setEventsPaused}
          />
        </div>
      </div>

      {view === "avenues" && (
        <button
          className="quick-add"
          onClick={() => setEditor("new")}
          aria-label="새 AVENUE 추가"
        >
          <Plus weight="bold" />
        </button>
      )}
      <AvenueEditor
        value={editor}
        onClose={() => setEditor(null)}
        onSave={saveAvenue}
      />
    </div>
  );
}

function Dashboard({
  avenues,
  killSwitch,
  setKillSwitch,
  setView,
}: {
  avenues: Avenue[];
  killSwitch: boolean;
  setKillSwitch: (value: boolean) => void;
  setView: (view: ViewId) => void;
}) {
  const active = avenues.filter((avenue) => avenue.status === "active").length;
  const blocked = avenues.filter((avenue) => !canPublish(avenue));
  const liveRevenue = useMemo(() => reconcileLedgers(unconnectedLedger()), []);
  const liveEstimated = ledgerFieldLabel(liveRevenue, "estimated");
  return (
    <>
      <section className="hero-grid" aria-label="운영 요약">
        <div className="risk-panel instrument-panel">
          <div className="panel-heading">
            <div>
              <Badge tone={blocked.length ? "danger" : "live"}>
                {blocked.length ? "발행 차단" : "위험 없음"}
              </Badge>
              <h2>수익보다 먼저 볼 위험</h2>
            </div>
            <ShieldCheck size={28} />
          </div>
          <div className="risk-readout">
            <strong>{blocked.length.toString().padStart(2, "0")}</strong>
            <span>
              발행 차단
              <br />
              AVENUE
            </span>
          </div>
          <p>
            {blocked.length
              ? `“${blocked[0].name}”은 목적지 진입을 가로막아 발행할 수 없어.`
              : "현재 발행을 막는 정책 위험이 없어."}
          </p>
          <Button variant="secondary" onClick={() => setView("guardrails")}>
            차단 근거 확인 <CaretRight />
          </Button>
        </div>
        <div className="metric-rack instrument-panel">
          <Metric
            label="오늘 예상 수익"
            value={liveEstimated}
            delta="원장 미연결"
          />
          <Metric
            label="운영 AVENUE"
            value={`${active} / ${avenues.length}`}
            delta="1 실험"
          />
          <Metric label="매치율" value="82.4%" delta="2.1%" up />
          <Metric label="사용자 이탈" value="0.34%" delta="0.06%" />
        </div>
      </section>

      <section
        className="growth-loop instrument-panel"
        aria-label="수익화와 홍보 흐름"
      >
        <div className="growth-loop__side">
          <span className="growth-loop__icon">
            <Coins weight="fill" />
          </span>
          <div>
            <small>수익화 · 앱 안</small>
            <strong>{liveEstimated}</strong>
            <span>오늘 예상 수익 · 미연결</span>
          </div>
        </div>
        <div className="growth-loop__net">
          <span>순 기여</span>
          <strong>계산 대기</strong>
          <small>실제 광고비·활성 사용자 LTV 연결 후 계산</small>
        </div>
        <button
          className="growth-loop__side growth-loop__side--action"
          onClick={() => setView("campaigns")}
        >
          <span className="growth-loop__icon">
            <Megaphone weight="fill" />
          </span>
          <div>
            <small>사용자 획득 · 앱 밖</small>
            <strong>{formatWon(0)}</strong>
            <span>승인된 집행액 · 현재 0</span>
          </div>
          <CaretRight />
        </button>
      </section>

      <section className="chart-panel instrument-panel">
        <div className="panel-heading">
          <div>
            <span className="eyebrow">14일 예상 수익</span>
            <h2>{formatWon(91840)}</h2>
            <p>AdMob 보고서 형식의 데모 데이터야. 실제 정산액이 아니야.</p>
          </div>
          <div className="chart-legend">
            <span>
              <i className="legend-estimated" />
              예상 수익
            </span>
            <span>
              <i className="legend-holdout" />
              홀드아웃
            </span>
          </div>
        </div>
        <RevenueChart />
        <div className="chart-axis">
          <span>08.09</span>
          <span>08.13</span>
          <span>08.17</span>
          <span>08.22</span>
        </div>
      </section>

      <section className="section-block">
        <div className="section-heading">
          <div>
            <span className="eyebrow">운영 표면</span>
            <h2>AVENUE 상태</h2>
          </div>
          <Button variant="ghost" onClick={() => setView("avenues")}>
            전체 관리 <CaretRight />
          </Button>
        </div>
        <div className="avenue-strip">
          {avenues.map((avenue) => (
            <AvenueCompact key={avenue.id} avenue={avenue} />
          ))}
        </div>
      </section>

      <section className="control-strip instrument-panel">
        <div className="kill-copy">
          <span className="control-icon">
            <Pause weight="fill" />
          </span>
          <div>
            <strong>전역 송출 킬 스위치</strong>
            <p>수익보다 안전이 우선일 때 모든 광고 요청을 즉시 중단해.</p>
          </div>
        </div>
        <div className="control-action">
          <span>{killSwitch ? "중지됨" : "정상 운영"}</span>
          <Toggle
            checked={killSwitch}
            onCheckedChange={setKillSwitch}
            label="전역 광고 송출 중지"
          />
        </div>
      </section>
    </>
  );
}

function Metric({
  label,
  value,
  delta,
  up = false,
}: {
  label: string;
  value: string;
  delta: string;
  up?: boolean;
}) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
      <small data-up={up || undefined}>
        {up ? <ArrowUpRight /> : <ArrowDownRight />}
        {delta}
      </small>
    </div>
  );
}

function RevenueChart() {
  const width = 800;
  const height = 170;
  const min = Math.min(...revenuePoints) - 10;
  const max = Math.max(...revenuePoints) + 8;
  const points = revenuePoints
    .map(
      (value, index) =>
        `${(index / (revenuePoints.length - 1)) * width},${height - ((value - min) / (max - min)) * height}`,
    )
    .join(" ");
  return (
    <div
      className="chart-wrap"
      aria-label="14일 예상 수익이 42에서 112로 증가한 데모 선 그래프"
    >
      <svg viewBox={`0 0 ${width} ${height}`} role="img">
        <defs>
          <linearGradient id="area" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="var(--accent)" stopOpacity=".28" />
            <stop offset="1" stopColor="var(--accent)" stopOpacity="0" />
          </linearGradient>
        </defs>
        <g className="grid-lines">
          <line x1="0" y1="28" x2={width} y2="28" />
          <line x1="0" y1="85" x2={width} y2="85" />
          <line x1="0" y1="142" x2={width} y2="142" />
        </g>
        <polygon
          points={`0,${height} ${points} ${width},${height}`}
          fill="url(#area)"
        />
        <polyline
          points={points}
          fill="none"
          stroke="var(--accent)"
          strokeWidth="3"
          vectorEffect="non-scaling-stroke"
        />
        <circle
          cx={width}
          cy={height - ((revenuePoints.at(-1)! - min) / (max - min)) * height}
          r="5"
          fill="var(--accent)"
        />
      </svg>
    </div>
  );
}

function AvenueCompact({ avenue }: { avenue: Avenue }) {
  const publishable = canPublish(avenue);
  return (
    <article
      className="avenue-compact"
      data-blocked={!publishable || undefined}
    >
      <div className="avenue-compact__top">
        <Badge tone={statusTone(avenue.status)}>
          {statusLabel(avenue.status)}
        </Badge>
        <span>{formatLabel(avenue.format)}</span>
      </div>
      <h3>{avenue.name}</h3>
      <p>{avenue.screen}</p>
      <footer>
        <span>일 {avenue.dailyCap}회</span>
        <span>
          {avenue.cooldownMinutes >= 1440
            ? `${avenue.cooldownMinutes / 1440}일`
            : `${avenue.cooldownMinutes}분`}{" "}
          간격
        </span>
        {!publishable && <Warning weight="fill" />}
      </footer>
    </article>
  );
}

function AvenueList({
  avenues,
  setEditor,
}: {
  avenues: Avenue[];
  setEditor: (value: Avenue | "new") => void;
}) {
  const [filter, setFilter] = useState<"all" | AvenueStatus>("all");
  const visible =
    filter === "all"
      ? avenues
      : avenues.filter((avenue) => avenue.status === filter);
  return (
    <section>
      <div className="page-intro">
        <div>
          <Badge tone="info">구성 관리</Badge>
          <h2>광고가 나타날 수 있는 모든 길</h2>
          <p>
            트리거와 노출 상한, 동의, 정책 위험을 AVENUE 단위로 묶어서 관리해.
          </p>
        </div>
        <Button variant="primary" onClick={() => setEditor("new")}>
          <Plus weight="bold" /> AVENUE 추가
        </Button>
      </div>
      <div className="filter-row" role="group" aria-label="AVENUE 상태 필터">
        {(["all", "active", "experiment", "paused"] as const).map((item) => (
          <button
            key={item}
            data-active={filter === item || undefined}
            onClick={() => setFilter(item)}
          >
            {item === "all" ? "전체" : statusLabel(item)}{" "}
            <span>
              {item === "all"
                ? avenues.length
                : avenues.filter((a) => a.status === item).length}
            </span>
          </button>
        ))}
      </div>
      <div className="data-table" role="table" aria-label="AVENUE 목록">
        <div className="data-row data-row--head" role="row">
          <span>AVENUE</span>
          <span>형식</span>
          <span>노출 규칙</span>
          <span>정책</span>
          <span>상태</span>
          <span className="sr-only">작업</span>
        </div>
        {visible.length === 0 ? (
          <EmptyState
            title="이 상태의 AVENUE가 없어"
            description="필터를 바꾸거나 새 AVENUE를 추가해."
          />
        ) : (
          visible.map((avenue) => {
            const findings = evaluateAvenue(avenue);
            const blocked = findings.some((item) => item.level === "block");
            return (
              <button
                className="data-row"
                role="row"
                key={avenue.id}
                onClick={() => setEditor(avenue)}
              >
                <span className="avenue-cell">
                  <i data-status={avenue.status} />
                  <span>
                    <strong>{avenue.name}</strong>
                    <small>{avenue.screen}</small>
                  </span>
                </span>
                <span>{formatLabel(avenue.format)}</span>
                <span>
                  <strong>일 {avenue.dailyCap}회</strong>
                  <small>
                    {avenue.cooldownMinutes >= 1440
                      ? `${avenue.cooldownMinutes / 1440}일`
                      : `${avenue.cooldownMinutes}분`}{" "}
                    간격
                  </small>
                </span>
                <span>
                  <Badge
                    tone={
                      blocked ? "danger" : findings.length ? "warn" : "live"
                    }
                  >
                    {blocked ? "차단" : findings.length ? "검토" : "통과"}
                  </Badge>
                </span>
                <span>
                  <Badge tone={statusTone(avenue.status)}>
                    {statusLabel(avenue.status)}
                  </Badge>
                </span>
                <span className="row-caret">
                  <CaretRight />
                </span>
              </button>
            );
          })
        )}
      </div>
    </section>
  );
}

function Networks() {
  const networks = [
    {
      name: "Google AdMob",
      role: "단일 미디에이션 컨트롤 플레인",
      state: "준비",
      latency: "142 ms",
      share: "61.8%",
      health: 98,
    },
    {
      name: "AppLovin",
      role: "Bidding demand source",
      state: "샌드박스",
      latency: "188 ms",
      share: "18.4%",
      health: 91,
    },
    {
      name: "Unity Ads",
      role: "Bidding demand source",
      state: "샌드박스",
      latency: "206 ms",
      share: "12.7%",
      health: 87,
    },
    {
      name: "ironSource",
      role: "Bidding demand source",
      state: "미연결",
      latency: "-",
      share: "0%",
      health: 0,
    },
  ];
  return (
    <section>
      <div className="page-intro">
        <div>
          <Badge tone="info">단일 미디에이터</Badge>
          <h2>하나의 경매, 여러 수요원</h2>
          <p>
            앱에는 단일 미디에이터만 두고 지원되는 파트너는 bidding으로 연결해.
          </p>
        </div>
        <Button>
          <SlidersHorizontal /> 파트너 설정
        </Button>
      </div>
      <div className="network-grid">
        {networks.map((network) => (
          <article className="network-card instrument-panel" key={network.name}>
            <header>
              <div className="network-logo">{network.name.slice(0, 1)}</div>
              <div>
                <h3>{network.name}</h3>
                <p>{network.role}</p>
              </div>
              <Badge
                tone={
                  network.state === "준비"
                    ? "live"
                    : network.state === "샌드박스"
                      ? "warn"
                      : "neutral"
                }
              >
                {network.state}
              </Badge>
            </header>
            <div className="network-stats">
              <div>
                <span>응답 지연</span>
                <strong>{network.latency}</strong>
              </div>
              <div>
                <span>수익 기여</span>
                <strong>{network.share}</strong>
              </div>
            </div>
            <div className="health-bar">
              <span style={{ transform: `scaleX(${network.health / 100})` }} />
            </div>
            <footer>
              <span>데모 상태 점수</span>
              <strong>{network.health || "-"}</strong>
            </footer>
          </article>
        ))}
      </div>
      <Notice>
        <strong>클라이언트 가격 라우팅은 하지 않아.</strong> 여러 최상위
        미디에이터를 동시에 초기화하거나 실시간 가격으로 Google 호출을 반복하는
        구조는 정책과 운영 안정성 모두에 불리해.
      </Notice>
    </section>
  );
}

function Revenue() {
  const live = useMemo(() => reconcileLedgers(unconnectedLedger()), []);
  const demo = useMemo(() => reconcileLedgers(demoLedgerFixture()), []);
  return (
    <section data-ledger-connection={live.connection}>
      <div className="page-intro">
        <div>
          <Badge tone="warn">미연결</Badge>
          <h2>예상액과 실제 입금을 분리해</h2>
          <p>ILRD 원장, 네트워크 확정 보고서, 은행 입금을 세 단계로 대사해.</p>
        </div>
        <Button>
          <DownloadSimple /> CSV 내보내기
        </Button>
      </div>
      <div
        className="reconcile-hero instrument-panel"
        data-ledger-empty={live.empty || undefined}
      >
        <div>
          <span>이번 달 예상 수익</span>
          <strong>{ledgerFieldLabel(live, "estimated")}</strong>
          <small>연결된 광고 계정 없음</small>
        </div>
        <CaretRight />
        <div>
          <span>네트워크 확정액</span>
          <strong>{ledgerFieldLabel(live, "confirmed")}</strong>
          <small>보고 API 미연결</small>
        </div>
        <CaretRight />
        <div>
          <span>입금 완료</span>
          <strong>{ledgerFieldLabel(live, "deposited")}</strong>
          <small>지급 명세 없음</small>
        </div>
      </div>
      {live.empty ? (
        <EmptyState
          title="연결된 수익 원장이 없어"
          description="광고 계정과 보고 API가 붙기 전에는 예상 KRW 합계를 채우지 않아."
        />
      ) : (
        <LedgerTable reconciliation={live} />
      )}
      <Notice>
        <strong>원화로 덮어쓰지 않아.</strong> 원본 통화와 micros 값을 보존하고,
        보고용 환산 통화는 별도 필드로 저장해야 월말 환율과 조정을 다시 계산할
        수 있어.
      </Notice>
      <div className="page-intro page-intro--follow">
        <div>
          <Badge tone="info">데모</Badge>
          <h2>같은 대사기로 본 예시 원장</h2>
          <p>
            아래 숫자는 로컬 데모 픽스처를 같은 대사 함수에 넣은 결과야. 운영
            원장이 아니야.
          </p>
        </div>
      </div>
      <div className="reconcile-hero instrument-panel" data-ledger-demo="true">
        <div>
          <span>이번 달 예상 수익</span>
          <strong>{ledgerFieldLabel(demo, "estimated")}</strong>
          <small>
            {demo.estimated
              ? `${demo.estimated.valueMicros} micros · ${demo.estimated.currencyCode}`
              : "비어 있음"}
          </small>
        </div>
        <CaretRight />
        <div>
          <span>네트워크 확정액</span>
          <strong>{ledgerFieldLabel(demo, "confirmed")}</strong>
          <small>
            {demo.pending.length
              ? `대기 ${demo.pending.length}건`
              : "대기 없음"}
          </small>
        </div>
        <CaretRight />
        <div>
          <span>입금 완료</span>
          <strong>{ledgerFieldLabel(demo, "deposited")}</strong>
          <small>
            {demo.unmatched.length
              ? `미대사 ${demo.unmatched.length}건`
              : "미대사 없음"}
          </small>
        </div>
      </div>
      <LedgerTable reconciliation={demo} />
    </section>
  );
}

function LedgerTable({ reconciliation }: { reconciliation: Reconciliation }) {
  return (
    <div className="ledger-table">
      <div className="ledger-row ledger-head">
        <span>수요원</span>
        <span>ILRD 합계</span>
        <span>확정 보고</span>
        <span>차이</span>
        <span>수금 책임</span>
      </div>
      {reconciliation.perSource.map((row) => {
        const status = sourceStatusLabel(row);
        return (
          <div className="ledger-row" key={`${row.source}-${row.currencyCode}`}>
            <span>
              {row.source}
              {row.currencyCode !== "KRW" ? ` · ${row.currencyCode}` : ""}
            </span>
            <span>
              {formatMoneyMicros(row.estimatedMicros, row.currencyCode)}
            </span>
            <span>
              {row.confirmedMicros === 0 && row.status === "pending"
                ? "-"
                : formatMoneyMicros(row.confirmedMicros, row.currencyCode)}
            </span>
            <span>
              {formatMoneyMicros(
                row.estimatedMinusConfirmedMicros,
                row.currencyCode,
              )}
            </span>
            <Badge
              tone={
                status === "보고 대기" || status === "입금 대기"
                  ? "warn"
                  : status === "미대사"
                    ? "danger"
                    : "neutral"
              }
            >
              {status}
            </Badge>
          </div>
        );
      })}
    </div>
  );
}

const initialCampaignCommand: CampaignCommand = {
  command:
    "한국에서 새글을 처음 활성화하고 실제 입력까지 완료하는 사용자를 확보해",
  goal: "activated_users",
  countries: ["KR"],
  dailyBudgetWon: null,
  durationDays: 7,
  maxCacWon: null,
  selectedPlatforms: ["google_ads", "meta_ads", "tiktok_ads"],
};

function Campaigns() {
  const [command, setCommand] = useState(initialCampaignCommand);
  const [plan, setPlan] = useState<CampaignPlan | null>(null);
  const dispatchContext = useMemo(
    () => ({
      storeProduction: false,
      privacyContractPassed: true,
      policyReviewed: false,
      budgetApproved: false,
      operatorId: "yun-chan",
      approverId: null,
      platforms: platformCatalog,
    }),
    [],
  );

  const preview = useMemo(
    () => planCampaign(command, dispatchContext),
    [command, dispatchContext],
  );
  const visiblePlan = plan ?? preview;
  const blockingCount = visiblePlan.gates.filter(
    (item) => item.level === "block",
  ).length;

  function submitDryRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPlan(planCampaign(command, dispatchContext));
  }

  function togglePlatform(platform: AcquisitionPlatform) {
    setPlan(null);
    setCommand((current) => {
      const selected = current.selectedPlatforms.includes(platform)
        ? current.selectedPlatforms.filter((item) => item !== platform)
        : [...current.selectedPlatforms, platform];
      return {
        ...current,
        selectedPlatforms: selected.length ? selected : [platform],
      };
    });
  }

  return (
    <section>
      <div className="page-intro campaign-intro">
        <div>
          <Badge tone="danger">실제 집행 차단</Badge>
          <h2>한 문장으로 계획하고, 돈은 승인 뒤에만</h2>
          <p>
            유효한 플랫폼을 고르고 소재·측정·예산을 검사한 뒤 서버 어댑터에
            제출해. 현재는 계정과 Play 프로덕션이 없어 드라이런만 가능해.
          </p>
        </div>
        <div className="release-state">
          <Storefront />
          <div>
            <span>공식 배포 상태</span>
            <strong>GitHub RC12 · Play 미확인</strong>
          </div>
          <Badge tone="danger">집행 불가</Badge>
        </div>
      </div>

      <div className="campaign-layout">
        <form
          className="campaign-command instrument-panel"
          onSubmit={submitDryRun}
        >
          <header>
            <div>
              <span className="eyebrow">캠페인 명령</span>
              <h3>어떤 사용자를 확보할지 적어</h3>
            </div>
            <Command size={24} />
          </header>
          <label className="field campaign-prompt">
            <span>운영 명령</span>
            <textarea
              value={command.command}
              onChange={(event) => {
                setPlan(null);
                setCommand((current) => ({
                  ...current,
                  command: event.target.value,
                }));
              }}
              rows={4}
            />
          </label>
          <div className="campaign-fields">
            <label className="field">
              <span>최적화 목표</span>
              <select
                value={command.goal}
                onChange={(event) => {
                  setPlan(null);
                  setCommand((current) => ({
                    ...current,
                    goal: event.target.value as CampaignCommand["goal"],
                  }));
                }}
              >
                <option value="activated_users">첫 활성 사용자</option>
                <option value="installs">설치</option>
              </select>
            </label>
            <label className="field">
              <span>일 예산 · 원</span>
              <input
                type="number"
                min="0"
                step="1000"
                value={command.dailyBudgetWon ?? ""}
                placeholder="승인 전 미입력"
                onChange={(event) => {
                  setPlan(null);
                  setCommand((current) => ({
                    ...current,
                    dailyBudgetWon: event.target.value
                      ? Number(event.target.value)
                      : null,
                  }));
                }}
              />
            </label>
            <label className="field">
              <span>기간 · 일</span>
              <input
                type="number"
                min="1"
                max="90"
                value={command.durationDays}
                onChange={(event) => {
                  setPlan(null);
                  setCommand((current) => ({
                    ...current,
                    durationDays: Number(event.target.value),
                  }));
                }}
              />
            </label>
            <label className="field">
              <span>목표 CAC · 원</span>
              <input
                type="number"
                min="0"
                step="100"
                value={command.maxCacWon ?? ""}
                placeholder="LTV 확인 후 입력"
                onChange={(event) => {
                  setPlan(null);
                  setCommand((current) => ({
                    ...current,
                    maxCacWon: event.target.value
                      ? Number(event.target.value)
                      : null,
                  }));
                }}
              />
            </label>
          </div>
          <fieldset className="platform-picker">
            <legend>검토할 플랫폼</legend>
            {platformCatalog.map((platform) => (
              <label key={platform.id}>
                <input
                  type="checkbox"
                  checked={command.selectedPlatforms.includes(platform.id)}
                  onChange={() => togglePlatform(platform.id)}
                />
                <span>
                  <strong>{platform.label}</strong>
                  <small>{platform.note}</small>
                </span>
              </label>
            ))}
          </fieldset>
          <footer>
            <div>
              <Flask />
              <span>API 호출과 결제 없이 계획만 생성해.</span>
            </div>
            <Button type="submit" variant="primary">
              <RocketLaunch /> 드라이런 생성
            </Button>
          </footer>
        </form>

        <aside className="dispatch-console instrument-panel">
          <header>
            <div>
              <Badge tone={blockingCount ? "danger" : "warn"}>
                {blockingCount ? `${blockingCount} 차단` : "승인 대기"}
              </Badge>
              <h3>집행 전 점검</h3>
            </div>
            <LockKey size={25} />
          </header>
          <div className="dispatch-budget">
            <span>총 광고비 상한</span>
            <strong>
              {visiblePlan.totalBudgetWon === null
                ? "미입력"
                : formatWon(visiblePlan.totalBudgetWon)}
            </strong>
            <small>기간 × 일 예산 · 이 금액을 넘겨 제출하지 않음</small>
          </div>
          <div className="gate-stack">
            {visiblePlan.gates.map((item) => (
              <article key={item.code} data-level={item.level}>
                {item.level === "block" ? (
                  <Warning weight="fill" />
                ) : (
                  <ClockCounterClockwise weight="fill" />
                )}
                <div>
                  <strong>{item.title}</strong>
                  <p>{item.detail}</p>
                </div>
              </article>
            ))}
          </div>
          <Button disabled variant="primary">
            <PaperPlaneTilt /> 운영 집행 요청
          </Button>
          <p className="dispatch-note">
            브라우저는 광고 계정 토큰을 받지 않아. 운영 제출은 RBAC와 2인 승인을
            통과한 Worker만 수행해.
          </p>
        </aside>
      </div>

      <section className="section-block channel-plan">
        <div className="section-heading">
          <div>
            <span className="eyebrow">플랫폼 적합성</span>
            <h2>연결 가능한 곳만 예산 후보가 돼</h2>
          </div>
          <Badge tone="info">콜드 스타트는 균등 탐색</Badge>
        </div>
        <div className="channel-plan__grid">
          {visiblePlan.allocations.map((allocation) => (
            <article
              className="channel-allocation instrument-panel"
              key={allocation.platform}
            >
              <header>
                <div className="network-logo">
                  {allocation.label.slice(0, 1)}
                </div>
                <div>
                  <h3>{allocation.label}</h3>
                  <span>{allocation.platform}</span>
                </div>
                <Badge
                  tone={allocation.state === "eligible" ? "live" : "danger"}
                >
                  {allocation.state === "eligible" ? "후보" : "차단"}
                </Badge>
              </header>
              <div className="allocation-readout">
                <span>계획 비중</span>
                <strong>{Math.round(allocation.share * 100)}%</strong>
              </div>
              <footer>
                {allocation.reasons.length
                  ? `${allocation.reasons.join(" · ")} 준비 필요`
                  : `${formatWon(allocation.dailyBudgetWon ?? 0)} / 일`}
              </footer>
            </article>
          ))}
        </div>
      </section>
    </section>
  );
}

function AcquisitionAnalytics() {
  const metrics = calculateCampaignMetrics({
    spendWon: 0,
    installs: 0,
    activatedUsers: 0,
    d7RetainedUsers: 0,
    attributedRevenueWon: 0,
  });
  const channels = [
    ["Google Ads", "계정·Firebase 미연결", "-", "-", "-"],
    ["Meta Ads", "Meta 앱·Events 미연결", "-", "-", "-"],
    ["TikTok Ads", "앱·MMP SAN 미연결", "-", "-", "-"],
  ];
  const measurementSteps = [
    ["01", "광고 노출·클릭", "플랫폼 보고서"],
    ["02", "Play 설치 출처", "Install Referrer"],
    ["03", "첫 실행", "first_open"],
    ["04", "키보드 활성화", "ime_setup_complete"],
    ["05", "첫 실제 입력", "first_real_input"],
    ["06", "7일 재사용", "d7_active"],
  ];
  return (
    <section>
      <div className="page-intro">
        <div>
          <Badge tone="info">실데이터 0건</Badge>
          <h2>설치가 아니라 살아남은 사용자를 계산해</h2>
          <p>
            플랫폼 귀속 수치와 제품 활성화, D7, 광고 수익을 같은 코호트로 묶고
            마지막 클릭과 인과 효과를 분리해.
          </p>
        </div>
        <Button disabled>
          <DownloadSimple /> 데이터 연결 후 내보내기
        </Button>
      </div>

      <div className="acquisition-metrics">
        <Metric label="실제 광고비" value={formatWon(0)} delta="계정 미연결" />
        <Metric label="첫 활성 사용자" value="-" delta="이벤트 미연결" />
        <Metric
          label="활성 CAC"
          value={metrics.cacWon === null ? "-" : formatWon(metrics.cacWon)}
          delta="분모 없음"
        />
        <Metric
          label="순 기여"
          value={formatWon(metrics.netContributionWon)}
          delta="실데이터 없음"
        />
      </div>

      <div className="measurement-layout">
        <section className="measurement-funnel instrument-panel">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">측정 계약</span>
              <h2>한 사람의 여섯 단계</h2>
            </div>
            <Target size={26} />
          </div>
          <div className="funnel-steps">
            {measurementSteps.map(([index, label, source]) => (
              <article key={index}>
                <span>{index}</span>
                <div>
                  <strong>{label}</strong>
                  <small>{source}</small>
                </div>
              </article>
            ))}
          </div>
        </section>
        <aside className="break-even instrument-panel">
          <span className="eyebrow">손익 기준</span>
          <h3>지출을 늘리는 조건</h3>
          <div className="formula-block">
            <span>활성 CAC</span>
            <strong>광고비 ÷ 첫 활성 사용자</strong>
          </div>
          <div className="formula-block">
            <span>회수 가능 LTV</span>
            <strong>D30 광고 순수익 × 보수 계수</strong>
          </div>
          <div className="break-even__decision">
            <Wallet />
            <div>
              <strong>지금은 증액하지 않음</strong>
              <p>CAC와 LTV 둘 다 실측되기 전에는 ROAS 목표를 만들지 않아.</p>
            </div>
          </div>
        </aside>
      </div>

      <div className="ledger-table acquisition-ledger">
        <div className="ledger-row ledger-head">
          <span>플랫폼</span>
          <span>연결 상태</span>
          <span>지출</span>
          <span>활성 CAC</span>
          <span>D7</span>
        </div>
        {channels.map((row) => (
          <div className="ledger-row" key={row[0]}>
            {row.map((cell, index) =>
              index === 1 ? (
                <Badge key={cell} tone="warn">
                  {cell}
                </Badge>
              ) : (
                <span key={`${index}-${cell}`}>{cell}</span>
              ),
            )}
          </div>
        ))}
      </div>
      <Notice>
        <strong>마지막 클릭을 인과 효과로 부르지 않아.</strong> 초기 예산은
        지역·기간 홀드아웃이나 플랫폼 실험을 함께 설계하고, 표본이 부족하면
        “판단 보류”로 남겨.
      </Notice>
    </section>
  );
}

function Guardrails({
  avenues,
  killSwitch,
  setKillSwitch,
}: {
  avenues: Avenue[];
  killSwitch: boolean;
  setKillSwitch: (value: boolean) => void;
}) {
  const supportServing = evaluateDemoServing(
    avenues,
    killSwitch,
    "support-rewarded",
  );
  const settingsServing = evaluateDemoServing(
    avenues,
    killSwitch,
    "settings-entry",
  );
  const checks = [
    [
      "설정 진입 전면 광고",
      "목적지 진입을 가로막는 예기치 않은 전면 광고",
      "block",
    ],
    ["IME 입력 표면", "다른 앱의 핵심 기능을 방해할 수 있는 위치", "block"],
    ["UMP 동의", "광고 요청 전 canRequestAds 확인", "pass"],
    ["아동 대상 연령", "Play Console 목표 연령이 아직 확정되지 않음", "warn"],
    ["Data Safety", "현재 개인정보 문서는 광고 SDK 없음으로 명시", "warn"],
  ] as const;
  return (
    <section>
      <div className="page-intro">
        <div>
          <Badge tone="danger">출시 게이트</Badge>
          <h2>정책과 신뢰를 코드로 잠가</h2>
          <p>
            관리자 설정이 잘못돼도 앱이 금지된 노출을 거부하는 이중 방어가
            필요해.
          </p>
        </div>
      </div>
      <div className="guardrail-layout">
        <div className="guardrail-list">
          {checks.map(([name, description, state]) => (
            <article key={name}>
              <span className={`guardrail-icon guardrail-icon--${state}`}>
                {state === "pass" ? (
                  <CheckCircle weight="fill" />
                ) : (
                  <Warning weight="fill" />
                )}
              </span>
              <div>
                <h3>{name}</h3>
                <p>{description}</p>
              </div>
              <Badge
                tone={
                  state === "pass"
                    ? "live"
                    : state === "block"
                      ? "danger"
                      : "warn"
                }
              >
                {state === "pass"
                  ? "통과"
                  : state === "block"
                    ? "차단"
                    : "결정 필요"}
              </Badge>
            </article>
          ))}
        </div>
        <aside className="guard-console instrument-panel">
          <span className="eyebrow">전역 제어</span>
          <h3>송출 안전 장치</h3>
          <div className="console-readout">
            <Pulse />
            <strong>{killSwitch ? "중지" : "감시 중"}</strong>
            <span>{killSwitch ? "광고 요청 중지" : "정책 엔진 감시 중"}</span>
          </div>
          <div className="console-row">
            <span>발행 차단</span>
            <strong>{avenues.filter((a) => !canPublish(a)).length}</strong>
          </div>
          <div className="console-row">
            <span>응원 송출</span>
            <strong>{supportServing.allow ? "허용" : "거부"}</strong>
          </div>
          <div className="console-row">
            <span>설정 진입 송출</span>
            <strong>{settingsServing.allow ? "허용" : "차단"}</strong>
          </div>
          <div className="console-row">
            <span>결정 필요</span>
            <strong>2</strong>
          </div>
          <div className="console-control">
            <div>
              <strong>킬 스위치</strong>
              <small>새 구성 요청도 차단</small>
            </div>
            <Toggle
              checked={killSwitch}
              onCheckedChange={setKillSwitch}
              label="전역 광고 송출 중지"
            />
          </div>
        </aside>
      </div>
    </section>
  );
}

function Audit() {
  const logs = [
    [
      "09:42:16",
      "Yun Chan",
      "support-rewarded",
      "일일 상한 2에서 3으로 변경",
      "config.update",
    ],
    [
      "09:36:02",
      "Policy engine",
      "settings-entry",
      "발행 요청 거부",
      "publish.block",
    ],
    [
      "09:22:41",
      "Revenue worker",
      "admob-report",
      "예상 수익 128건 수집",
      "report.sync",
    ],
    [
      "09:18:08",
      "Yun Chan",
      "support-native",
      "AVENUE 운영 시작",
      "avenue.activate",
    ],
    [
      "08:52:33",
      "Experiment",
      "theme-export",
      "1% 홀드아웃 생성",
      "experiment.create",
    ],
  ];
  return (
    <section>
      <div className="page-intro">
        <div>
          <Badge tone="info">변경 불가 원장</Badge>
          <h2>누가 무엇을 바꿨는지 남겨</h2>
          <p>
            구성 발행, 정책 차단, 수익 보고서 동기화를 변경 불가 원장으로
            기록해.
          </p>
        </div>
        <Button>
          <DownloadSimple /> 감사 로그 내보내기
        </Button>
      </div>
      <div className="audit-timeline">
        {logs.map(([time, actor, target, action, code]) => (
          <article key={`${time}-${code}`}>
            <time>{time}</time>
            <span className="timeline-pin" />
            <div>
              <header>
                <strong>{actor}</strong>
                <Badge>{code}</Badge>
              </header>
              <p>{action}</p>
              <small>{target}</small>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

function LiveRail({
  eventIndex,
  paused,
  onPausedChange,
}: {
  eventIndex: number;
  paused: boolean;
  onPausedChange: (value: boolean) => void;
}) {
  const ordered = Array.from(
    { length: 5 },
    (_, offset) =>
      liveEvents[(eventIndex - offset + liveEvents.length) % liveEvents.length],
  );
  return (
    <aside className="live-rail" aria-label="실시간 데모 이벤트">
      <header>
        <div>
          <span className="signal" aria-hidden="true" />
          <strong>실시간 데모</strong>
          <small>4.8초 간격</small>
        </div>
        <Button
          size="icon"
          variant="ghost"
          onClick={() => onPausedChange(!paused)}
          aria-label={paused ? "실시간 이벤트 재생" : "실시간 이벤트 일시정지"}
        >
          {paused ? <Play size={17} /> : <Pause size={17} />}
        </Button>
      </header>
      <div className="rail-meters">
        <div>
          <span>분당 요청</span>
          <strong>128</strong>
        </div>
        <div>
          <span>수익 이벤트</span>
          <strong>82</strong>
        </div>
      </div>
      <div className="event-list">
        {ordered.map((event, index) => (
          <article key={`${event[0]}-${event[3]}-${eventIndex - index}`}>
            <i data-event={event[0]} />
            <div>
              <header>
                <strong>{event[1]}</strong>
                <time>{index === 0 ? "지금" : `${index * 2}분 전`}</time>
              </header>
              <p>{event[3]}</p>
              <small>{event[2]}</small>
            </div>
          </article>
        ))}
      </div>
      <footer>
        <Info />
        <span>실제 SDK, 서버, 광고 계정과 연결되지 않은 결정론적 데모야.</span>
      </footer>
    </aside>
  );
}

function AvenueEditor({
  value,
  onClose,
  onSave,
}: {
  value: Avenue | "new" | null;
  onClose: () => void;
  onSave: (avenue: Avenue) => void;
}) {
  const source = value === "new" ? undefined : (value ?? undefined);
  const [draft, setDraft] = useState<Avenue>(() => source ?? makeNewAvenue());

  useEffect(() => setDraft(source ?? makeNewAvenue()), [source, value]);
  const findings = useMemo(() => evaluateAvenue(draft), [draft]);

  function update<K extends keyof Avenue>(key: K, next: Avenue[K]) {
    setDraft((current) => ({ ...current, [key]: next }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    onSave({
      ...draft,
      version: source ? source.version + 1 : 1,
      updatedAt: new Date().toISOString(),
    });
  }

  return (
    <Dialog.Root
      open={value !== null}
      onOpenChange={(open) => !open && onClose()}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content
          className="dialog-content"
          aria-describedby="avenue-editor-description"
        >
          <form onSubmit={submit}>
            <header className="dialog-header">
              <div>
                <Badge
                  tone={
                    findings.some((f) => f.level === "block")
                      ? "danger"
                      : "info"
                  }
                >
                  {source ? `버전 ${source.version}` : "새 AVENUE"}
                </Badge>
                <Dialog.Title>
                  {source ? "AVENUE 편집" : "AVENUE 추가"}
                </Dialog.Title>
                <Dialog.Description id="avenue-editor-description">
                  광고가 나타나는 맥락과 안전 상한을 하나의 구성으로 묶어.
                </Dialog.Description>
              </div>
              <Dialog.Close asChild>
                <Button size="icon" variant="ghost" aria-label="편집기 닫기">
                  <X />
                </Button>
              </Dialog.Close>
            </header>
            <div className="editor-body">
              <div className="editor-grid">
                <Field label="이름">
                  <input
                    value={draft.name}
                    onChange={(e) => update("name", e.target.value)}
                    required
                  />
                </Field>
                <Field label="상태">
                  <select
                    value={draft.status}
                    onChange={(e) =>
                      update("status", e.target.value as AvenueStatus)
                    }
                  >
                    <option value="active">운영</option>
                    <option value="experiment">실험</option>
                    <option value="paused">중지</option>
                  </select>
                </Field>
                <Field label="화면">
                  <input
                    value={draft.screen}
                    onChange={(e) => update("screen", e.target.value)}
                    required
                  />
                </Field>
                <Field label="광고 형식">
                  <select
                    value={draft.format}
                    onChange={(e) =>
                      update("format", e.target.value as AvenueFormat)
                    }
                  >
                    <option value="rewarded">보상형</option>
                    <option value="native">네이티브</option>
                    <option value="interstitial">전면</option>
                  </select>
                </Field>
                <Field label="트리거" wide>
                  <textarea
                    value={draft.trigger}
                    onChange={(e) => update("trigger", e.target.value)}
                    required
                    rows={3}
                  />
                </Field>
                <Field label="일일 상한">
                  <input
                    type="number"
                    min="0"
                    max="20"
                    value={draft.dailyCap}
                    onChange={(e) => update("dailyCap", Number(e.target.value))}
                  />
                </Field>
                <Field label="쿨다운, 분">
                  <input
                    type="number"
                    min="0"
                    value={draft.cooldownMinutes}
                    onChange={(e) =>
                      update("cooldownMinutes", Number(e.target.value))
                    }
                  />
                </Field>
                <Field label="최소 세션">
                  <input
                    type="number"
                    min="0"
                    value={draft.minSessions}
                    onChange={(e) =>
                      update("minSessions", Number(e.target.value))
                    }
                  />
                </Field>
                <Field label="최소 행동">
                  <input
                    type="number"
                    min="0"
                    value={draft.minActions}
                    onChange={(e) =>
                      update("minActions", Number(e.target.value))
                    }
                  />
                </Field>
                <Field label="네트워크 그룹">
                  <input
                    value={draft.networkGroup}
                    onChange={(e) => update("networkGroup", e.target.value)}
                  />
                </Field>
                <Field label="동의 확인">
                  <label className="switch-field">
                    <span>광고 요청 전 동의 상태 확인</span>
                    <Toggle
                      checked={draft.requiresConsent}
                      onCheckedChange={(checked) =>
                        update("requiresConsent", checked)
                      }
                      label="광고 요청 전 동의 상태 확인"
                    />
                  </label>
                </Field>
                <Field label="운영 메모" wide>
                  <textarea
                    value={draft.notes}
                    onChange={(e) => update("notes", e.target.value)}
                    rows={3}
                  />
                </Field>
              </div>
              <aside className="policy-preview">
                <span className="eyebrow">정책 미리보기</span>
                <h3>
                  {findings.length ? `${findings.length}개 판정` : "발행 가능"}
                </h3>
                {findings.length ? (
                  findings.map((finding) => (
                    <div key={finding.code} data-level={finding.level}>
                      <Warning weight="fill" />
                      <span>
                        <strong>
                          {finding.level === "block"
                            ? "발행 차단"
                            : "운영 경고"}
                        </strong>
                        <p>{finding.message}</p>
                      </span>
                    </div>
                  ))
                ) : (
                  <div data-level="pass">
                    <CheckCircle weight="fill" />
                    <span>
                      <strong>정책 엔진 통과</strong>
                      <p>현재 입력에는 알려진 차단 조건이 없어.</p>
                    </span>
                  </div>
                )}
                <p className="policy-note">
                  서버와 앱 양쪽에서 같은 불변식을 검사해야 해.
                </p>
              </aside>
            </div>
            <footer className="dialog-footer">
              <Button variant="ghost" onClick={onClose}>
                취소
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={!canPublish(draft)}
              >
                구성 저장
              </Button>
            </footer>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function makeNewAvenue(): Avenue {
  return {
    id: `avenue-${Date.now()}`,
    name: "",
    screen: "",
    trigger: "",
    format: "rewarded",
    status: "paused",
    dailyCap: 1,
    cooldownMinutes: 1440,
    minSessions: 3,
    minActions: 1,
    requiresConsent: true,
    networkGroup: "AdMob bidding",
    policyRisk: "low",
    notes: "",
    version: 0,
    updatedAt: new Date().toISOString(),
  };
}

function Field({
  label,
  wide = false,
  children,
}: {
  label: string;
  wide?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="field" data-wide={wide || undefined}>
      <span>{label}</span>
      {children}
    </label>
  );
}

function Notice({ children }: { children: ReactNode }) {
  return (
    <div className="notice">
      <Info weight="fill" />
      <p>{children}</p>
    </div>
  );
}

function EmptyState({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="empty-state">
      <Path size={30} />
      <strong>{title}</strong>
      <p>{description}</p>
    </div>
  );
}

export default App;
