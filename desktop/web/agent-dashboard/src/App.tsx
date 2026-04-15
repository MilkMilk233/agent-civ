import { useEffect, useMemo, useState } from "react";
import { api } from "./api";
import { buildBatchConfig, createDefaultLaunchForm, getRunnerOptions, mapSizeOptions, normalizeLaunchForm, rulesetOptions } from "./batchLaunch";
import type { BatchLaunchFormState } from "./batchLaunch";
import { deriveTurns } from "./derive";
import type {
  BatchSummary,
  MatchSummary,
  ReplayResponse,
  RunnerFormOptions,
  RunnerStatus,
  SnapshotResponse,
  TurnRecord,
} from "./types";
import { formatNumber, formatRelative, parseJsonValue } from "./utils";

type Mode = "live" | "replay";
type WorldFactsMetricKey =
  | "score"
  | "force"
  | "technologies"
  | "cities"
  | "population"
  | "units"
  | "militaryUnits"
  | "civilianUnits"
  | "gold"
  | "happiness"
  | "sciencePerTurn"
  | "culturePerTurn"
  | "faithPerTurn";

const WORLD_FACTS_METRIC_OPTIONS: Array<{ key: WorldFactsMetricKey; label: string }> = [
  { key: "score", label: "Total score" },
  { key: "force", label: "Military force" },
  { key: "technologies", label: "Technologies" },
  { key: "cities", label: "Cities" },
  { key: "population", label: "Population" },
  { key: "units", label: "Total units" },
  { key: "militaryUnits", label: "Military units" },
  { key: "civilianUnits", label: "Civilian units" },
  { key: "gold", label: "Gold" },
  { key: "happiness", label: "Happiness" },
  { key: "sciencePerTurn", label: "Science / turn" },
  { key: "culturePerTurn", label: "Culture / turn" },
  { key: "faithPerTurn", label: "Faith / turn" },
];

export default function App() {
  const [mode, setMode] = useState<Mode>("live");
  const [snapshot, setSnapshot] = useState<SnapshotResponse | null>(null);
  const [runnerStatus, setRunnerStatus] = useState<RunnerStatus | null>(null);
  const [runnerOptions, setRunnerOptions] = useState<RunnerFormOptions | null>(null);
  const [batches, setBatches] = useState<BatchSummary[]>([]);
  const [matches, setMatches] = useState<MatchSummary[]>([]);
  const [replay, setReplay] = useState<ReplayResponse | null>(null);
  const [selectedBatchId, setSelectedBatchId] = useState<string>("");
  const [selectedMatchId, setSelectedMatchId] = useState<string>("");
  const [selectedTurnKey, setSelectedTurnKey] = useState<string>("");
  const [launchForm, setLaunchForm] = useState<BatchLaunchFormState>(() => createDefaultLaunchForm(null));
  const [launchModalOpen, setLaunchModalOpen] = useState(false);
  const [selectedTimelineMetric, setSelectedTimelineMetric] = useState<WorldFactsMetricKey>("score");
  const [error, setError] = useState<string>("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void Promise.all([loadBatches(), refreshRunner(), loadRunnerOptions()]);
  }, []);

  useEffect(() => {
    if (mode !== "live") return;
    void loadSnapshot();
    const interval = window.setInterval(() => {
      void loadSnapshot();
      void refreshRunner();
    }, 5000);
    return () => window.clearInterval(interval);
  }, [mode]);

  useEffect(() => {
    if (!selectedBatchId) return;
    void api.matches(selectedBatchId)
      .then((items) => {
        setMatches(items);
        if (!items.some((item) => item.matchId === selectedMatchId)) {
          setSelectedMatchId(items[0]?.matchId ?? "");
        }
      })
      .catch((err: Error) => setError(err.message));
  }, [selectedBatchId, selectedMatchId]);

  useEffect(() => {
    if (mode !== "replay" || !selectedBatchId || !selectedMatchId) return;
    void api.replay(selectedBatchId, selectedMatchId)
      .then((data) => {
        setReplay(data);
        setError("");
      })
      .catch((err: Error) => setError(err.message));
  }, [mode, selectedBatchId, selectedMatchId]);

  const turns = useMemo(() => {
    if (mode === "live") return deriveTurns(snapshot?.recentEvents ?? []);
    return deriveTurns(replay?.recentEvents ?? [], replay?.turnSummaries ?? []);
  }, [mode, replay, snapshot]);

  useEffect(() => {
    if (!turns.length) {
      setSelectedTurnKey("");
      return;
    }
    if (!turns.some((turn) => turn.key === selectedTurnKey)) {
      setSelectedTurnKey(turns[0].key);
    }
  }, [selectedTurnKey, turns]);

  const selectedTurn = turns.find((turn) => turn.key === selectedTurnKey) ?? turns[0] ?? null;
  const resolvedRunnerOptions = getRunnerOptions(runnerOptions);
  const selectedRuleset = rulesetOptions(resolvedRunnerOptions, launchForm.baseRuleset);
  const launchPreviewJson = useMemo(
    () => buildBatchConfig(launchForm, resolvedRunnerOptions),
    [launchForm, resolvedRunnerOptions],
  );
  const scoreTimeline = useMemo(
    () => buildMetricTimeline(turns, selectedTimelineMetric),
    [selectedTimelineMetric, turns],
  );
  const selectedBatch = batches.find((batch) => batch.batchId === selectedBatchId) ?? null;
  const selectedMatch = matches.find((match) => match.matchId === selectedMatchId) ?? null;

  async function loadSnapshot() {
    try {
      setSnapshot(await api.snapshot(4000));
      setError("");
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function loadBatches() {
    try {
      const items = await api.batches();
      setBatches(items);
      setSelectedBatchId((current) => current || items[0]?.batchId || "");
      setError("");
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function refreshRunner() {
    try {
      setRunnerStatus(await api.runnerStatus());
      setError("");
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function loadRunnerOptions() {
    try {
      const options = await api.runnerOptions();
      setRunnerOptions(options);
      setLaunchForm((current) => normalizeLaunchForm(current.baseRuleset ? current : createDefaultLaunchForm(options), options));
      setError("");
    } catch (err) {
      setRunnerOptions(null);
    }
  }

  function updateLaunchForm<K extends keyof BatchLaunchFormState>(key: K, value: BatchLaunchFormState[K]) {
    setLaunchForm((current) => normalizeLaunchForm({ ...current, [key]: value }, resolvedRunnerOptions));
  }

  async function startBatch() {
    setBusy(true);
    try {
      await api.startBatch(launchPreviewJson);
      await Promise.all([refreshRunner(), loadBatches()]);
      setLaunchModalOpen(false);
      setMode("replay");
      setError("");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function cancelBatch() {
    setBusy(true);
    try {
      await api.cancelBatch();
      await refreshRunner();
      setError("");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Unciv Agent Observability</p>
          <h1>Observability Dashboard</h1>
          <p className="hero-copy">
            Launch batches, watch live turns, and inspect strategist and tactical behavior without digging through raw files.
          </p>
        </div>
        <div className="topbar-actions">
          <div className="mode-switch">
            <button className={mode === "live" ? "active" : ""} onClick={() => setMode("live")}>Live</button>
            <button className={mode === "replay" ? "active" : ""} onClick={() => setMode("replay")}>Replay</button>
          </div>
          <button className="primary large" disabled={busy || !!runnerStatus?.running} onClick={() => setLaunchModalOpen(true)}>
            Start batch
          </button>
        </div>
      </header>

      <section className="hero-row">
        <Card
          className="hero-status-card"
          title="Runner status"
          subtitle={runnerStatus?.running ? "A batch is currently in progress." : "Ready for the next experiment."}
        >
          <div className="summary-grid compact hero-summary-grid hero-summary-grid-three">
            <SummaryStat label="Current state" value={runnerStatus?.running ? "Running" : "Idle"} />
            <SummaryStat label="Current batch" value={runnerStatus?.currentBatchName || runnerStatus?.currentBatchId || "None"} />
            <SummaryStat label="Last result" value={runnerStatus?.lastCompletedBatchStatus || "None"} />
          </div>

          <div className="hero-meta-block">
            <span>Storage</span>
            <strong>{runnerStatus?.storageDir || "Unavailable"}</strong>
          </div>

          <div className="button-row hero-actions">
            <button onClick={() => void refreshRunner()}>Refresh status</button>
            <button onClick={() => void loadBatches()}>Refresh history</button>
            <button className="danger" disabled={busy || !runnerStatus?.running} onClick={() => void cancelBatch()}>
              Cancel running batch
            </button>
          </div>
        </Card>

        <Card
          className="hero-status-card"
          title={mode === "live" ? "Live session" : "Replay session"}
          subtitle={mode === "live" ? "Current in-memory observability stream." : "Stored evaluations from disk."}
        >
          {mode === "live" ? (
            <>
              <div className="summary-grid compact hero-summary-grid">
                <SummaryStat label="Buffered events" value={formatNumber(snapshot?.totalBufferedEvents)} />
                <SummaryStat label="Visible turns" value={formatNumber(turns.length)} />
                <SummaryStat label="Last refresh" value={snapshot ? formatRelative(snapshot.generatedAtEpochMs) : "Not loaded"} />
                <SummaryStat label="Current civ" value={selectedTurn?.civName || "None"} />
              </div>

              <div className="hero-embedded-panel">
                <div className="button-row hero-actions">
                  <button onClick={() => void loadSnapshot()}>Refresh snapshot now</button>
                  <button onClick={() => setMode("replay")}>Open replay mode</button>
                </div>
                <div className="mini-note">
                  Use replay mode when you want the full stored trace instead of the rolling in-memory window.
                </div>
              </div>
            </>
          ) : (
            <>
              <div className="summary-grid compact hero-summary-grid">
                <SummaryStat label="Selected batch" value={selectedBatch?.name || selectedBatch?.batchId || "None"} />
                <SummaryStat label="Selected match" value={selectedMatch?.label || selectedMatch?.matchId || "None"} />
                <SummaryStat label="Winner" value={selectedMatch?.winnerCivName || selectedMatch?.winnerSide || "Unknown"} />
                <SummaryStat label="Total turns" value={formatNumber(selectedMatch?.totalTurns)} />
              </div>

              <div className="hero-embedded-panel">
                <div className="field-grid hero-replay-grid">
                  <label>
                    Batch
                    <select value={selectedBatchId} onChange={(event) => setSelectedBatchId(event.target.value)}>
                      {batches.map((batch) => (
                        <option key={batch.batchId} value={batch.batchId}>
                          {batch.name || batch.batchId}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Match
                    <select value={selectedMatchId} onChange={(event) => setSelectedMatchId(event.target.value)}>
                      {matches.map((match) => (
                        <option key={match.matchId} value={match.matchId}>
                          {match.label || match.matchId}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
                {selectedBatch ? (
                  <div className="mini-note">
                    {selectedBatch.baseRuleset} · {selectedBatch.mapSize} {selectedBatch.mapType} · max {selectedBatch.maxTurns} turns
                  </div>
                ) : null}
              </div>
            </>
          )}
          {error ? <p className="error-text">{error}</p> : null}
        </Card>

        <ScoreTimelineCard
          selectedMetric={selectedTimelineMetric}
          timeline={scoreTimeline}
          onMetricChange={setSelectedTimelineMetric}
        />
      </section>

      <section className="workspace-grid">
        <aside className="control-rail">
          <Card title="Turn watchlist" subtitle={`${turns.length} turn snapshots loaded`}>
            <div className="watchlist-list">
              {turns.map((turn) => (
                <button
                  key={turn.key}
                  className={`turn-card ${selectedTurn?.key === turn.key ? "selected" : ""}`}
                  onClick={() => setSelectedTurnKey(turn.key)}
                >
                  <div className="turn-card-top">
                    <strong>{turn.civName}</strong>
                    <div className="turn-card-badges">
                      {hasStrategistInference(turn) ? (
                        <span className="turn-card-flag" role="img" aria-label="Strategist inference ran" title="Strategist inference ran on this turn">
                          📘
                        </span>
                      ) : null}
                      <StatusPill tone={turn.statusTone}>{turn.statusLabel}</StatusPill>
                    </div>
                  </div>
                  <div className="turn-card-meta">Turn {turn.turn} · {formatRelative(turn.latestEpochMs)}</div>
                  <p>{turn.synopsis}</p>
                </button>
              ))}
            </div>
          </Card>
        </aside>

        <section className="detail-pane">
          {selectedTurn ? <TurnDetail turn={selectedTurn} /> : <EmptyState />}
        </section>
      </section>

      {launchModalOpen ? (
        <LaunchModal
          busy={busy}
          form={launchForm}
          options={resolvedRunnerOptions}
          selectedRuleset={selectedRuleset}
          previewJson={launchPreviewJson}
          onClose={() => setLaunchModalOpen(false)}
          onChange={updateLaunchForm}
          onLaunch={() => void startBatch()}
        />
      ) : null}
    </div>
  );
}

function LaunchModal({
  busy,
  form,
  options,
  selectedRuleset,
  previewJson,
  onClose,
  onChange,
  onLaunch,
}: {
  busy: boolean;
  form: BatchLaunchFormState;
  options: RunnerFormOptions;
  selectedRuleset: ReturnType<typeof rulesetOptions>;
  previewJson: string;
  onClose: () => void;
  onChange: <K extends keyof BatchLaunchFormState>(key: K, value: BatchLaunchFormState[K]) => void;
  onLaunch: () => void;
}) {
  const sizes = mapSizeOptions(options);
  const toggleVictoryType = (victoryType: string) => {
    const next = form.enabledVictoryTypes.includes(victoryType)
      ? form.enabledVictoryTypes.filter((value) => value !== victoryType)
      : [...form.enabledVictoryTypes, victoryType];
    onChange("enabledVictoryTypes", next);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div>
            <p className="eyebrow">Batch setup</p>
            <h2>Start a new evaluation batch</h2>
            <p className="hero-copy">Use the guided form for the common setup, and keep the JSON preview as a final sanity check.</p>
          </div>
          <button className="ghost-button" onClick={onClose}>Close</button>
        </div>

        <div className="modal-body">
          <section className="modal-section">
            <h3>Scenario</h3>
            <div className="field-grid">
              <label>
                Batch name
                <input value={form.name} onChange={(event) => onChange("name", event.target.value)} />
              </label>
              <NumberField label="Games" value={form.games} onChange={(value) => onChange("games", value)} />
              <NumberField label="Max turns" value={form.maxTurns} onChange={(value) => onChange("maxTurns", value)} />
              <NumberField label="Seed start" value={form.seedStart} onChange={(value) => onChange("seedStart", value)} />
            </div>
            <ToggleRow>
              <Toggle checked={form.pairMatchesBySeed} onChange={(checked) => onChange("pairMatchesBySeed", checked)} label="Swap roles per seed" />
              <Toggle checked={form.saveFinalGames} onChange={(checked) => onChange("saveFinalGames", checked)} label="Save final games" />
              <Toggle checked={form.saveOnlyInterestingGames} onChange={(checked) => onChange("saveOnlyInterestingGames", checked)} label="Save only interesting games" />
            </ToggleRow>
          </section>

          <section className="modal-section">
            <h3>Rules and map</h3>
            <div className="field-grid">
              <label>
                Ruleset
                <div className="static-field">{selectedRuleset.name}</div>
                <p className="field-hint">Agent evaluation is locked to Vanilla-only mode.</p>
              </label>
              <ChoiceGroup
                label="Difficulty"
                value={form.difficulty}
                options={selectedRuleset.difficulties}
                onChange={(value) => onChange("difficulty", value)}
              />
              <ChoiceGroup
                label="Speed"
                value={form.speed}
                options={selectedRuleset.speeds}
                onChange={(value) => onChange("speed", value)}
              />
              <div className="choice-group field-span-two">
                <p>Enabled victory types</p>
                <div className="choice-grid">
                  {selectedRuleset.victoryTypes.map((victoryType) => (
                    <button
                      key={victoryType}
                      type="button"
                      className={`choice-chip ${form.enabledVictoryTypes.includes(victoryType) ? "selected" : ""}`}
                      onClick={() => toggleVictoryType(victoryType)}
                    >
                      {victoryType}
                    </button>
                  ))}
                </div>
                <p className="field-hint">Default is all enabled. If you deselect everything, the launcher restores all victory types.</p>
              </div>
              <ChoiceGroup
                label="Map size"
                value={form.mapSizeName}
                options={sizes.map((size) => size.name)}
                onChange={(value) => onChange("mapSizeName", value)}
              />
              <ChoiceGroup
                label="Map type"
                value={form.mapType}
                options={options.mapTypes}
                onChange={(value) => onChange("mapType", value)}
              />
              <ChoiceGroup
                label="Map shape"
                value={form.mapShape}
                options={options.mapShapes}
                onChange={(value) => onChange("mapShape", value)}
              />
            </div>
          </section>

          <section className="modal-section">
            <h3>Players</h3>
            <div className="field-grid">
              <label>
                Agent civilization
                <select value={form.agentCiv} onChange={(event) => onChange("agentCiv", event.target.value)}>
                  {selectedRuleset.civilizations.map((civilization) => (
                    <option key={civilization} value={civilization}>{civilization}</option>
                  ))}
                </select>
              </label>
              <label>
                Opponent civilization
                <select value={form.legacyCiv} onChange={(event) => onChange("legacyCiv", event.target.value)}>
                  {selectedRuleset.civilizations.map((civilization) => (
                    <option key={civilization} value={civilization}>{civilization}</option>
                  ))}
                </select>
              </label>
              <NumberField
                label="City-states"
                value={form.numberOfCityStates}
                onChange={(value) => onChange("numberOfCityStates", value)}
              />
            </div>
          </section>

          <section className="modal-section">
            <h3>Map toggles</h3>
            <ToggleRow>
              <Toggle checked={form.noBarbarians} onChange={(checked) => onChange("noBarbarians", checked)} label="No barbarians" />
              <Toggle checked={form.noRuins} onChange={(checked) => onChange("noRuins", checked)} label="No ruins" />
              <Toggle checked={form.noNaturalWonders} onChange={(checked) => onChange("noNaturalWonders", checked)} label="No natural wonders" />
              <Toggle checked={form.strategicBalance} onChange={(checked) => onChange("strategicBalance", checked)} label="Strategic balance" />
              <Toggle checked={form.legendaryStart} onChange={(checked) => onChange("legendaryStart", checked)} label="Legendary start" />
            </ToggleRow>
          </section>

          <section className="modal-section">
            <h3>Submitted JSON</h3>
            <pre>{previewJson}</pre>
          </section>
        </div>

        <div className="modal-footer">
          <button onClick={onClose}>Cancel</button>
          <button className="primary large" disabled={busy} onClick={onLaunch}>
            {busy ? "Starting..." : "Launch batch"}
          </button>
        </div>
      </div>
    </div>
  );
}

type WorldFactsCellTone = "neutral" | "accent" | "warning";

interface WorldFactsCell {
  primary: string;
  secondary?: string;
  tone?: WorldFactsCellTone;
}

interface WorldFactsRow {
  label: string;
  values: WorldFactsCell[];
}

interface WorldFactsMatrix {
  title: string;
  subtitle: string;
  rows: WorldFactsRow[];
}

interface WorldFactsColumn {
  civName: string;
  subtitle?: string;
  emphasis?: boolean;
}

interface WorldFactsViewModel {
  columns: WorldFactsColumn[];
  overview: Array<{ label: string; value: string; note?: string }>;
  matrices: WorldFactsMatrix[];
  notes: string[];
}

function WorldFactsLaunchSection({
  onOpen,
}: {
  onOpen: () => void;
}) {
  return (
    <section id="section-world-facts" className="section-shell world-facts-shell">
      <button type="button" className="world-facts-launch" onClick={onOpen}>
        <p className="eyebrow">Reality</p>
        <h2>World facts</h2>
      </button>
    </section>
  );
}

function WorldFactsModal({
  turn,
  data,
  onClose,
}: {
  turn: TurnRecord;
  data: WorldFactsViewModel;
  onClose: () => void;
}) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel world-facts-modal" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div>
            <p className="eyebrow">Reality</p>
            <h2>World facts</h2>
            <p className="card-subtitle">
              Exact cross-civilization snapshot at the start of {turn.civName}&apos;s turn {formatNumber(turn.turn)}.
              This is for dashboard comparison only and is not part of the agent&apos;s prompt.
            </p>
          </div>
          <button className="ghost-button" onClick={onClose}>Close</button>
        </div>

        <div className="modal-body">
          <section className="modal-section">
            <div className="summary-grid compact">
              {data.overview.map((item) => (
                <SummaryStat key={item.label} label={item.label} value={item.value} note={item.note} />
              ))}
            </div>
          </section>

          {data.matrices.map((matrix) => (
            <section key={matrix.title} className="modal-section">
              <h3>{matrix.title}</h3>
              <p className="card-subtitle">{matrix.subtitle}</p>
              <WorldFactsMatrixTable columns={data.columns} matrix={matrix} />
            </section>
          ))}

          {data.notes.length ? (
            <section className="modal-section">
              <h3>Reading guide</h3>
              <div className="structured-list">
                {data.notes.map((note) => (
                  <article key={note} className="structured-item">
                    <p className="card-paragraph">{note}</p>
                  </article>
                ))}
              </div>
            </section>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function WorldFactsMatrixTable({
  columns,
  matrix,
}: {
  columns: WorldFactsColumn[];
  matrix: WorldFactsMatrix;
}) {
  return (
    <div className="world-facts-table-wrap">
      <table className="world-facts-table">
        <thead>
          <tr>
            <th>Metric</th>
            {columns.map((column) => (
              <th key={column.civName} className={column.emphasis ? "is-emphasis" : ""}>
                <div className="world-facts-column-head">
                  <strong>{column.civName}</strong>
                  {column.subtitle ? <small>{column.subtitle}</small> : null}
                </div>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {matrix.rows.map((row) => (
            <tr key={row.label}>
              <th>{row.label}</th>
              {row.values.map((cell, index) => (
                <td key={`${row.label}-${columns[index]?.civName ?? index}`}>
                  <div className={`world-facts-cell${cell.tone ? ` ${cell.tone}` : ""}`}>
                    <strong>{cell.primary}</strong>
                    {cell.secondary ? <small>{cell.secondary}</small> : null}
                  </div>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface ScoreTimelineSeriesPoint {
  turn: number;
  value: number;
}

interface ScoreTimelineSeries {
  civName: string;
  color: string;
  points: ScoreTimelineSeriesPoint[];
  latestScore: number;
}

interface ScoreTimelineView {
  metricKey: WorldFactsMetricKey;
  metricLabel: string;
  series: ScoreTimelineSeries[];
  minTurn: number;
  maxTurn: number;
  minValue: number;
  maxValue: number;
}

function ScoreTimelineCard({
  selectedMetric,
  timeline,
  onMetricChange,
}: {
  selectedMetric: WorldFactsMetricKey;
  timeline: ScoreTimelineView | null;
  onMetricChange: (metric: WorldFactsMetricKey) => void;
}) {
  const width = 640;
  const height = 250;
  const padding = { top: 18, right: 22, bottom: 34, left: 44 };

  if (!timeline || !timeline.series.length) {
    return (
      <Card
        className="hero-chart-card"
        title="World metric over time"
        subtitle="Pick any tracked world-facts metric and compare all civilizations over the run."
      >
        <label className="chart-metric-picker">
          <span>Y-axis metric</span>
          <select value={selectedMetric} onChange={(event) => onMetricChange(event.target.value as WorldFactsMetricKey)}>
            {WORLD_FACTS_METRIC_OPTIONS.map((option) => (
              <option key={option.key} value={option.key}>{option.label}</option>
            ))}
          </select>
        </label>
        <EmptyCardState
          title="No timeline history yet"
          body="This card will light up once the trace contains world-facts snapshots for multiple turns."
        />
      </Card>
    );
  }

  const xRange = Math.max(1, timeline.maxTurn - timeline.minTurn);
  const yAxis = buildNiceNumericAxis(timeline.minValue, timeline.maxValue, 5);
  const yRange = Math.max(1, yAxis.max - yAxis.min);
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const xForTurn = (turn: number) => padding.left + ((turn - timeline.minTurn) / xRange) * plotWidth;
  const yForScore = (value: number) => padding.top + plotHeight - ((value - yAxis.min) / yRange) * plotHeight;
  const yTicks = yAxis.ticks;
  const xTicks = buildIntegerTicks(timeline.minTurn, timeline.maxTurn, 5);

  return (
    <Card
      className="hero-chart-card"
      title={`${timeline.metricLabel} over time`}
      subtitle="X-axis is turn number, Y-axis is your selected world-facts metric, and each civilization keeps its own color."
    >
      <label className="chart-metric-picker">
        <span>Y-axis metric</span>
        <select value={selectedMetric} onChange={(event) => onMetricChange(event.target.value as WorldFactsMetricKey)}>
          {WORLD_FACTS_METRIC_OPTIONS.map((option) => (
            <option key={option.key} value={option.key}>{option.label}</option>
          ))}
        </select>
      </label>

      <div className="summary-grid compact">
        <SummaryStat label="Civs tracked" value={formatNumber(timeline.series.length)} />
        <SummaryStat label="Turns covered" value={`${formatNumber(timeline.minTurn)}–${formatNumber(timeline.maxTurn)}`} />
        <SummaryStat label="Top value" value={formatNumber(timeline.maxValue)} note={timeline.metricLabel} />
      </div>

      <div className="score-chart-shell">
        <svg className="score-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Civilization score history line chart">
          <rect x="0" y="0" width={width} height={height} rx="20" fill="rgba(250,252,255,0.92)" />

          {yTicks.map((tick) => {
            const y = yForScore(tick);
            return (
              <g key={`y-${tick}`}>
                <line x1={padding.left} x2={width - padding.right} y1={y} y2={y} className="score-chart-grid-line" />
                <text x={padding.left - 10} y={y + 4} textAnchor="end" className="score-chart-axis-label">
                  {formatAxisValue(tick)}
                </text>
              </g>
            );
          })}

          {xTicks.map((tick) => {
            const x = xForTurn(tick);
            return (
              <g key={`x-${tick}`}>
                <line x1={x} x2={x} y1={padding.top} y2={height - padding.bottom} className="score-chart-grid-line vertical" />
                <text x={x} y={height - 10} textAnchor="middle" className="score-chart-axis-label">
                  {formatNumber(tick)}
                </text>
              </g>
            );
          })}

          {timeline.series.map((series) => (
            <g key={series.civName}>
              <path
                d={buildLinePath(series.points, xForTurn, yForScore)}
                fill="none"
                stroke={series.color}
                strokeWidth="3"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              {series.points.map((point) => (
                <circle
                  key={`${series.civName}-${point.turn}`}
                  cx={xForTurn(point.turn)}
                  cy={yForScore(point.value)}
                  r="3.5"
                  fill={series.color}
                  className="score-chart-point"
                />
              ))}
            </g>
          ))}
        </svg>
      </div>

      <div className="score-chart-legend">
        {timeline.series.map((series) => (
          <div key={series.civName} className="score-chart-legend-item">
            <span className="score-chart-legend-swatch" style={{ backgroundColor: series.color }} />
            <strong>{series.civName}</strong>
            <small>{formatNumber(series.latestScore)}</small>
          </div>
        ))}
      </div>
    </Card>
  );
}

function TurnDetail({
  turn,
}: {
  turn: TurnRecord;
}) {
  const observation = asRecord(turn.observation);
  const empireObservation = asRecord(turn.empireObservation);
  const worldFacts = asRecord(turn.worldFacts);
  const plannerBrief = asRecord(turn.plannerBrief);
  const strategistBrief = asRecord(turn.strategistBrief);
  const strategistMemo = asRecord(turn.strategistMemo);
  const parsedPlan = asRecord(turn.parsedPlan);
  const empireSummary = asRecord(observation?.empireSummary);
  const strategy = asRecord(plannerBrief?.strategy);
  const plannerDecisionFocus = asRecord(plannerBrief?.decisionFocus);
  const memoryContext = asRecord(plannerBrief?.memoryContext);
  const campaignContext = asRecord(plannerBrief?.campaignContext);
  const objectiveTheater = asRecord(plannerBrief?.objectiveTheater);
  const captureReadiness = asRecord(plannerBrief?.captureReadiness);
  const attentionFacts = objectArray(plannerBrief?.attentionFacts);
  const progressInMotion = objectArray(plannerBrief?.progressInMotion);
  const strategistRivalThreats = objectArray(strategistBrief?.rivalThreats);
  const strategistRivalCities = objectArray(strategistBrief?.rivalCities);
  const strategistRivalUnits = objectArray(strategistBrief?.rivalUnits);
  const strategistCampaignPicture = asRecord(strategistBrief?.campaignPicture);
  const strategistCitySnapshots = objectArray(strategistBrief?.citySnapshots);
  const strategistUnitSnapshots = objectArray(strategistBrief?.unitSnapshots);
  const threatHighlights = objectArray(plannerBrief?.threatHighlights);
  const cityHighlights = objectArray(plannerBrief?.cityHighlights);
  const unitHighlights = objectArray(plannerBrief?.unitHighlights);
  const perceptionSummary = asRecord(observation?.perceptionSummary);
  const empireChoices = asRecord(plannerBrief?.empireChoices);
  const suppressedContext = stringList(plannerBrief?.suppressedContext);
  const plannedActions = objectArray(parsedPlan?.actions);
  const memoCreatedTurn = numberValue(strategistMemo?.createdTurn);
  const memoLastReviewedTurn = numberValue(strategistMemo?.lastReviewedTurn);
  const turnMetrics = deriveTurnMetrics(turn);
  const candidateLookup = buildCandidateLookup(empireChoices, cityHighlights, unitHighlights);
  const tacticalAttempts = buildTacticalAttempts(turn.events);
  const strategistArtifacts = buildStrategistArtifacts(turn.events);
  const latestTerminalEvent = findLatestEvent(turn.events, ["plan_applied", "fallback_legacy", "plan_missing"]);
  const hiddenCities = Math.max(0, (numberValue(perceptionSummary?.totalCities) ?? cityHighlights.length) - cityHighlights.length);
  const hiddenUnits = Math.max(0, (numberValue(perceptionSummary?.totalUnits) ?? unitHighlights.length) - unitHighlights.length);
  const hiddenThreats = Math.max(0, arrayLength(observation?.visibleThreatsAndTargets) - threatHighlights.length);
  const memoryRecord = asRecord(turn.memory);
  const worldModelMemory = asRecord(memoryRecord?.worldModel);
  const campaignMemory = asRecord(memoryRecord?.campaign);
  const empirePlanMemory = asRecord(memoryRecord?.empirePlan);
  const recentChangesMemory = objectArray(memoryRecord?.recentChanges);
  const lessonsMemory = objectArray(memoryRecord?.lessons);
  const tacticianTurnLogMemory = objectArray(memoryRecord?.tacticianTurnLog);
  const lastStrategistMemoMemory = asRecord(memoryRecord?.lastStrategistMemo);
  const memoryCampaignControl = asRecord(memoryRecord?.campaignControl);
  const strategistCampaignControl = asRecord(strategistBrief?.campaignControl);
  const plannerCampaignControl = asRecord(plannerBrief?.campaignControl);
  const worldModelNotes = objectArray(worldModelMemory?.notes);
  const worldModelAnchors = objectArray(worldModelMemory?.anchors);
  const campaignNotes = objectArray(campaignMemory?.notes);
  const empirePlanNotes = objectArray(empirePlanMemory?.notes);
  const memoryCityIntents = arrayLength(memoryRecord?.cityIntents);
  const memoryUnitAssignments = arrayLength(memoryRecord?.unitAssignments);
  const memoryRecentFailures = arrayLength(memoryRecord?.recentFailures);
  const cityIntentsMemory = objectArray(memoryRecord?.cityIntents);
  const unitAssignmentsMemory = objectArray(memoryRecord?.unitAssignments);
  const recentFailuresMemory = objectArray(memoryRecord?.recentFailures);
  const worldFactsCivs = objectArray(worldFacts?.civs);
  const [worldFactsOpen, setWorldFactsOpen] = useState(false);
  const worldFactsView = useMemo(
    () => buildWorldFactsViewModel({
      civName: turn.civName,
      worldFacts,
      worldFactsCivs,
    }),
    [turn.civName, worldFacts, worldFactsCivs],
  );
  const quickScanCards = [
    {
      label: "Strategist thesis",
      text: firstNonEmptyText(
        stringValue(strategistMemo?.thesis),
        stringValue(strategy?.thesis),
        turn.synopsis,
      ) || "No strategist thesis recorded.",
    },
    {
      label: "Priority window",
      text: firstNonEmptyText(
        stringValue(asRecord(strategy?.decisionFrame)?.whyNow),
        stringValue(asRecord(strategy?.decisionFrame)?.nextCheckpoint),
        stringValue(strategistMemo?.futurePlan),
      ) || "No active priority window was surfaced.",
    },
    {
      label: "Campaign focus",
      text: firstNonEmptyText(
        stringValue(campaignMemory?.summary),
        stringValue(memoryContext?.campaignSummary),
        stringValue(campaignContext?.primaryRivalCiv),
      ) || "No campaign summary was carried into this turn.",
    },
    {
      label: "This turn in one line",
      text: firstNonEmptyText(
        stringValue(parsedPlan?.notes),
        stringValue(latestTerminalEvent?.message),
        turn.turnSummary?.notes,
      ) || "No compact action summary was recorded.",
    },
  ];

  return (
    <div className="detail-stack">
      {worldFactsOpen && worldFactsView ? (
        <WorldFactsModal
          turn={turn}
          data={worldFactsView}
          onClose={() => setWorldFactsOpen(false)}
        />
      ) : null}

      <Card
        title={`${turn.civName} · Turn ${turn.turn}`}
        subtitle={turn.turnSummary?.notes || turn.synopsis}
      >
        <div className="turn-summary-heading">
          <div className="tag-list">
            <StatusPill tone={turn.statusTone}>{turn.statusLabel}</StatusPill>
            {turnMetrics.fallback ? <span className="tag warning">Fallback used</span> : null}
            {turnMetrics.blocked ? <span className="tag warning">Blocked turn</span> : null}
            {turnMetrics.retryCount > 0 ? <span className="tag neutral">{formatNumber(turnMetrics.retryCount)} retries</span> : null}
            {turnMetrics.providerRetryCount > 0 ? <span className="tag neutral">{formatNumber(turnMetrics.providerRetryCount)} provider retries</span> : null}
          </div>
          <p className="mini-note">This page is organized around the agent's real flow: what existed in the game, what was surfaced into the prompts, what the models chose, and what survived validation.</p>
        </div>

        <div className="story-strip">
          <StoryStage
            title="Reality"
            subtitle={`${formatNumber(numberValue(empireSummary?.cityCount))} cities · ${formatNumber(numberValue(empireSummary?.unitCount))} units · ${formatNumber(numberValue(empireSummary?.visibleHostileUnits))} visible hostiles`}
            detail="The full board and empire state before any compression."
          />
          <StoryStage
            title="Perception"
            subtitle={`${formatNumber(attentionFacts.length)} reminders · ${formatNumber(cityHighlights.length)} city cards · ${formatNumber(unitHighlights.length)} unit cards`}
            detail="What the tactician actually saw in the planner brief."
          />
          <StoryStage
            title="Decision"
            subtitle={`${formatNumber(plannedActions.length)} actions · ${formatNumber(turnMetrics.tacticalPasses)} tactical passes`}
            detail="The structured plan returned by the LLM across attempts."
          />
          <StoryStage
            title="Result"
            subtitle={
              turnMetrics.fallback
                ? "Legacy fallback"
                : turnMetrics.blocked
                  ? "Blocked"
                  : `${formatNumber(turnMetrics.executedActions)} applied / ${formatNumber(turnMetrics.plannedActions)} planned`
            }
            detail={stringValue(latestTerminalEvent?.message) || "Outcome was not recorded."}
          />
        </div>

        <div className="summary-grid compact">
          <SummaryStat
            label="Planner outcome"
            value={describePlannerOutcome(turnMetrics)}
            note={describePlannerOutcomeNote(turnMetrics)}
          />
          <SummaryStat
            label="LLM time"
            value={formatDurationMs(turnMetrics.totalLatencyMs)}
            note={buildLatencyNote(turnMetrics.strategistLatencyMs, turnMetrics.tacticalLatencyMs)}
          />
          <SummaryStat
            label="Inference passes"
            value={`${formatNumber(turnMetrics.tacticalPasses)} tactical`}
            note={`${formatNumber(turnMetrics.strategistPasses)} strategist`}
          />
          <SummaryStat
            label="Provider retries"
            value={formatNumber(turnMetrics.providerRetryCount)}
            note={`Tactical ${formatNumber(turnMetrics.tacticalProviderRetryCount)} · Strategist ${formatNumber(turnMetrics.strategistProviderRetryCount)}`}
          />
          <SummaryStat
            label="Actions applied"
            value={`${formatNumber(turnMetrics.executedActions)} / ${formatNumber(turnMetrics.plannedActions)}`}
            note={turnMetrics.intentionalNoOp ? "intentional no-op" : "executed / planned"}
          />
          <SummaryStat
            label="Validation failures"
            value={formatNumber(turnMetrics.validationFailureCount)}
            note={`${formatNumber(turnMetrics.retryCount)} replans requested`}
          />
          <SummaryStat
            label="Live execution rejects"
            value={formatNumber(turnMetrics.executionRejectedActions)}
            note={formatPercent(turnMetrics.executionRejectRate)}
          />
          <SummaryStat
            label="Turn safety"
            value={turnMetrics.fallback ? "Fallback" : turnMetrics.blocked ? "Blocked" : "Stable"}
            note={`illegal ${formatPercent(turnMetrics.illegalActionRate)}`}
          />
          <SummaryStat
            label="Memory carried in"
            value={`${formatNumber(memoryCityIntents)} city · ${formatNumber(memoryUnitAssignments)} unit`}
            note={`${formatNumber(memoryRecentFailures)} recent failures`}
          />
          <SummaryStat label="Latest event" value={formatRelative(turn.latestEpochMs)} />
        </div>

        <div className="quick-scan-grid">
          {quickScanCards.map((item) => (
            <article key={item.label} className="quick-scan-card">
              <span>{item.label}</span>
              <p>{item.text}</p>
            </article>
          ))}
        </div>
      </Card>

      <TurnSectionNav
        items={[
          { id: "section-world-facts", label: "World facts" },
          { id: "section-memory", label: "Memory" },
          { id: "section-strategist-input", label: "Strategist saw" },
          { id: "section-strategist-output", label: "Strategist output" },
          { id: "section-perception", label: "Tactician saw" },
          { id: "section-decision", label: "Decision" },
          { id: "section-literal", label: "Literal" },
          { id: "section-events", label: "Events" },
        ]}
      />

      {worldFactsView ? <WorldFactsLaunchSection onOpen={() => setWorldFactsOpen(true)} /> : null}

      <SectionShell
        id="section-memory"
        eyebrow="Memory"
        title="What's in the memory"
        body="This is the shared notebook carried into the turn before any new strategist or tactical inference. It shows the durable world model, campaign notes, campaign-control state, and carried intents/failures."
      >
        <div className="compact-card-grid">
          <Card className="full-span" title="Memory overview" subtitle="High-level shape of the notebook the agent brought into this turn.">
            <div className="summary-grid compact">
              <SummaryStat label="World notes" value={formatNumber(worldModelNotes.length)} note={`${formatNumber(worldModelAnchors.length)} anchors`} />
              <SummaryStat label="Campaign notes" value={formatNumber(campaignNotes.length)} note={`${formatNumber(stringList(campaignMemory?.doNotDo).length)} cautions`} />
              <SummaryStat label="Empire notes" value={formatNumber(empirePlanNotes.length)} />
              <SummaryStat label="Recent changes" value={formatNumber(recentChangesMemory.length)} />
              <SummaryStat label="Lessons" value={formatNumber(lessonsMemory.length)} />
              <SummaryStat label="Tactician log" value={formatNumber(tacticianTurnLogMemory.length)} />
              <SummaryStat label="Campaign holding costs" value={formatNumber(stringList(memoryCampaignControl?.holdingCosts).length)} />
              <SummaryStat label="Campaign pivot triggers" value={formatNumber(stringList(memoryCampaignControl?.pivotTriggers).length)} />
              <SummaryStat label="City intents" value={formatNumber(cityIntentsMemory.length)} />
              <SummaryStat label="Unit assignments" value={formatNumber(unitAssignmentsMemory.length)} />
              <SummaryStat label="Recent failures" value={formatNumber(recentFailuresMemory.length)} />
              <SummaryStat label="Last memo review" value={formatNumber(numberValue(lastStrategistMemoMemory?.lastReviewedTurn))} />
            </div>
          </Card>

          <Card title="World model" subtitle="What the agent currently believes about the map and board-level reality across turns.">
            {worldModelMemory ? (
              <>
                {stringValue(worldModelMemory.summary) ? <p className="card-paragraph">{stringValue(worldModelMemory.summary)}</p> : null}
                <div className="summary-grid compact">
                  <SummaryStat label="Last updated" value={formatNumber(numberValue(worldModelMemory.lastUpdatedTurn))} />
                  <SummaryStat label="Notes" value={formatNumber(worldModelNotes.length)} />
                  <SummaryStat label="Anchors" value={formatNumber(worldModelAnchors.length)} />
                </div>
                <MemoryNotesSection title="World notes" notes={worldModelNotes} />
                <MemoryAnchorsSection title="World anchors" anchors={worldModelAnchors} />
              </>
            ) : (
              <EmptyCardState title="No world model" body="No world-model notebook was stored for this turn." />
            )}
          </Card>

          <Card title="Campaign notebook" subtitle="What operation the agent thinks it is running right now.">
            {campaignMemory ? (
              <>
                <div className="summary-grid compact">
                  <SummaryStat label="Title" value={stringValue(campaignMemory.title) || "—"} />
                  <SummaryStat label="Stage" value={stringValue(campaignMemory.stage) || "—"} />
                  <SummaryStat label="Objective" value={stringValue(campaignMemory.decisiveObjective) || "—"} />
                  <SummaryStat label="Primary rival" value={stringValue(campaignMemory.primaryRivalCiv) || "—"} />
                  <SummaryStat label="Last updated" value={formatNumber(numberValue(campaignMemory.lastUpdatedTurn))} />
                </div>
                {stringValue(campaignMemory.summary) ? <p className="card-paragraph">{stringValue(campaignMemory.summary)}</p> : null}
                {stringValue(campaignMemory.conversionBlocker) ? (
                  <>
                    <SectionLabel text="Conversion blocker" />
                    <p className="card-paragraph">{stringValue(campaignMemory.conversionBlocker)}</p>
                  </>
                ) : null}
                {stringValue(campaignMemory.reinforcementPlan) ? (
                  <>
                    <SectionLabel text="Reinforcement plan" />
                    <p className="card-paragraph">{stringValue(campaignMemory.reinforcementPlan)}</p>
                  </>
                ) : null}
                {stringList(campaignMemory.doNotDo).length ? (
                  <>
                    <SectionLabel text="Do not do" />
                    <TagList values={stringList(campaignMemory.doNotDo)} tone="warning" />
                  </>
                ) : null}
                <MemoryNotesSection title="Campaign notes" notes={campaignNotes} embedded />
              </>
            ) : (
              <EmptyCardState title="No campaign notebook" body="No campaign memory was stored for this turn." />
            )}
          </Card>

          <Card title="Empire plan notebook" subtitle="How the empire's production and purchases are supposed to support the current game plan.">
            {empirePlanMemory ? (
              <>
                <div className="summary-grid compact">
                  <SummaryStat label="Last updated" value={formatNumber(numberValue(empirePlanMemory.lastUpdatedTurn))} />
                  <SummaryStat label="Notes" value={formatNumber(empirePlanNotes.length)} />
                </div>
                {stringValue(empirePlanMemory.summary) ? <p className="card-paragraph">{stringValue(empirePlanMemory.summary)}</p> : null}
                {stringValue(empirePlanMemory.purchaseIntent) ? (
                  <>
                    <SectionLabel text="Purchase intent" />
                    <p className="card-paragraph">{stringValue(empirePlanMemory.purchaseIntent)}</p>
                  </>
                ) : null}
                <MemoryNotesSection title="Empire notes" notes={empirePlanNotes} embedded />
              </>
            ) : (
              <EmptyCardState title="No empire plan" body="No empire-plan notebook was stored for this turn." />
            )}
          </Card>

          <MemoryNotesCard
            title="Recent changes"
            subtitle="What changed recently enough to still matter as context."
            notes={recentChangesMemory}
            emptyTitle="No recent changes"
            emptyBody="No recent change notes were carried into this turn."
          />
          <MemoryNotesCard
            title="Lessons and cautions"
            subtitle="Short reminders of what not to repeat."
            notes={lessonsMemory}
            emptyTitle="No lessons"
            emptyBody="No lessons or cautions were carried into this turn."
          />
          <CampaignControlCard
            title="Campaign control"
            subtitle="Script-managed campaign state that tracks commitment, readiness, supply, checkpoints, and why the current line may need to launch or pivot."
            campaignControl={memoryCampaignControl}
            emptyTitle="No campaign-control memory"
            emptyBody="No typed campaign-control state was carried into this turn."
          />
          <StrategistMemoMemorySection memo={lastStrategistMemoMemory} />
          <TacticianTurnLogSection entries={tacticianTurnLogMemory} />

          <CityIntentMemorySection intents={cityIntentsMemory} />
          <UnitAssignmentMemorySection assignments={unitAssignmentsMemory} />
          <RecentFailureMemorySection failures={recentFailuresMemory} />
        </div>
      </SectionShell>

      <SectionShell
        id="section-strategist-input"
        eyebrow="Strategist"
        title="What the strategist saw"
        body="This is the strategist's input space: broad current state, the previous memo, factual changes since then, and compact city/unit snapshots."
      >
        <div className="compact-card-grid">
          <div className="full-span">
            <StrategistContextSection
              title="Strategist inputs"
              brief={strategistBrief}
              campaignControl={strategistCampaignControl}
              threats={strategistRivalThreats}
              campaignPicture={strategistCampaignPicture}
            />
          </div>
          <StrategistRivalCitiesSection title="Visible rival cities" cities={strategistRivalCities} />
          <StrategistRivalUnitsSection title="Visible rival units" units={strategistRivalUnits} />
          <StrategistCitySnapshotsSection title="Strategist city snapshots" cities={strategistCitySnapshots} />
          <StrategistUnitSnapshotsSection title="Strategist unit snapshots" units={strategistUnitSnapshots} />
        </div>
      </SectionShell>

      <SectionShell
        id="section-strategist-output"
        eyebrow="Strategist"
        title="What the strategist output"
        body="This section contains only the strategist-generated memo and notebook handoff. It is kept separate from strategist inputs so you can compare what the strategist saw against what it concluded."
      >
        <Card title="Strategist memo" subtitle="A compact report with fixed Past / Now / Future subtitles.">
          {strategistMemo ? (
            <>
              <div className="summary-grid compact">
                <SummaryStat label="Win path" value={stringValue(strategistMemo.winPath)} />
                <SummaryStat label="Stage" value={stringValue(strategistMemo.campaignStage)} />
                <SummaryStat label="Objective" value={stringValue(strategistMemo.decisiveObjective)} />
                <SummaryStat label="Strategist pass" value={strategistArtifacts ? "Ran this turn" : "Carried memo"} />
                <SummaryStat label="Max age" value={formatNumber(numberValue(asRecord(strategistMemo.reviewContract)?.maxAgeTurns))} />
                <SummaryStat label="Created" value={memoCreatedTurn === null ? "Unknown" : `Turn ${formatNumber(memoCreatedTurn)}`} />
                <SummaryStat label="Last reviewed" value={memoLastReviewedTurn === null ? "Unknown" : `Turn ${formatNumber(memoLastReviewedTurn)}`} />
              </div>
              <p className="mini-note">
                {strategistArtifacts
                  ? "A fresh strategist LLM pass ran on this turn. Use the literal artifacts section below to inspect the exact request, raw response, and parsed strategist plan."
                  : "No strategist LLM pass ran on this turn. The memo shown here was carried forward from earlier turns, and the tactician relied on current observation plus memory deltas."}
              </p>
              <p className="card-paragraph">{stringValue(strategistMemo.thesis) || "No strategist thesis recorded."}</p>
              {stringValue(strategistMemo.conversionBlocker) ? (
                <>
                  <SectionLabel text="Conversion blocker" />
                  <p className="card-paragraph">{stringValue(strategistMemo.conversionBlocker)}</p>
                </>
              ) : null}
              <DecisionFrameBlock frame={asRecord(strategistMemo.decisionFrame)} />
              <ControlLanesBlock lanes={asRecord(strategistMemo.controlLanes)} />
              <ReviewContractBlock contract={asRecord(strategistMemo.reviewContract)} />
              <CampaignControlLabelsBlock labels={asRecord(strategistMemo.campaignControl)} />
              {stringValue(strategistMemo.pastSummary) ? (
                <>
                  <SectionLabel text="Past" />
                  <p className="card-paragraph">{stringValue(strategistMemo.pastSummary)}</p>
                </>
              ) : null}
              {stringValue(strategistMemo.currentSituation) ? (
                <>
                  <SectionLabel text="Now" />
                  <p className="card-paragraph">{stringValue(strategistMemo.currentSituation)}</p>
                </>
              ) : null}
              {stringValue(strategistMemo.futurePlan) ? (
                <>
                  <SectionLabel text="Future" />
                  <p className="card-paragraph">{stringValue(strategistMemo.futurePlan)}</p>
                </>
              ) : null}
              {stringValue(strategistMemo.tacticianHandoff) ? (
                <>
                  <SectionLabel text="Tactician handoff" />
                  <p className="card-paragraph">{stringValue(strategistMemo.tacticianHandoff)}</p>
                </>
              ) : null}
            </>
          ) : (
            <p className="muted-text">No strategist memo was recorded for this turn.</p>
          )}
        </Card>
      </SectionShell>

      <SectionShell
        id="section-perception"
        eyebrow="Tactician"
        title="What the tactician saw"
        body="This is the compressed tactical packet that the planner actually used. It separates full board reality from the smaller planner brief so you can see what was surfaced and what stayed hidden."
      >
        <div className="compact-card-grid">
          <Card title="Tactical brief" subtitle="Only the tactician-specific tactical context that shaped this turn. The strategist's Past / Now / Future report is shown only in the strategist output section.">
            {plannerBrief ? (
              <>
                <div className="summary-grid compact">
                  <SummaryStat label="Archetype" value={stringValue(strategy?.gameArchetype)} />
                  <SummaryStat label="Stage" value={stringValue(strategy?.campaignStage)} />
                  <SummaryStat label="Victory goal" value={stringValue(strategy?.winPath)} />
                </div>
                {stringValue(strategy?.decisiveObjective) ? (
                  <>
                    <SectionLabel text="Decisive objective" />
                    <p className="card-paragraph">{stringValue(strategy?.decisiveObjective)}</p>
                  </>
                ) : null}
                {stringValue(strategy?.conversionBlocker) ? (
                  <>
                    <SectionLabel text="Conversion blocker" />
                    <p className="card-paragraph">{stringValue(strategy?.conversionBlocker)}</p>
                  </>
                ) : null}
                <DecisionFrameBlock frame={asRecord(strategy?.decisionFrame)} />
                <ControlLanesBlock lanes={asRecord(strategy?.controlLanes)} />
                {memoryContext ? (
                  <>
                    <SectionLabel text="Notebook context" />
                    <div className="structured-list">
                      {stringValue(memoryContext.worldModelSummary) ? <p className="card-paragraph">{stringValue(memoryContext.worldModelSummary)}</p> : null}
                      {stringValue(memoryContext.mainRivalCiv) ? <p className="card-paragraph"><strong>Main rival:</strong> {stringValue(memoryContext.mainRivalCiv)}</p> : null}
                      {stringList(memoryContext.recentChanges).length ? (
                        <>
                          <SectionLabel text="Recent changes" />
                          <TagList values={stringList(memoryContext.recentChanges)} />
                        </>
                      ) : null}
                      {stringList(memoryContext.lessons).length ? (
                        <>
                          <SectionLabel text="Lessons" />
                          <TagList values={stringList(memoryContext.lessons)} tone="warning" />
                        </>
                      ) : null}
                    </div>
                  </>
                ) : null}
              </>
            ) : (
              <p className="muted-text">Planner brief missing from this turn.</p>
            )}
          </Card>
          <CampaignControlCard
            title="Campaign control seen by tactician"
            subtitle="The typed campaign-control scaffold surfaced into the tactical brief for this turn."
            campaignControl={plannerCampaignControl}
            emptyTitle="No tactical campaign control"
            emptyBody="The tactical brief did not surface any campaign-control observation on this turn."
          />
          <DecisionFocusCard
            title="Decision focus"
            subtitle="The mode-aware cockpit that separates critical choices from background chores and highlights where the packet may not support the strategist frame cleanly."
            decisionFocus={plannerDecisionFocus}
          />

          <Card title="Objective theater" subtitle="The full surfaced battlefield slice around the current decisive objective, plus a compact reserve summary.">
            {objectiveTheater ? (
              <>
                <div className="summary-grid compact">
                  <SummaryStat label="Target" value={stringValue(asRecord(objectiveTheater.target)?.name)} />
                  <SummaryStat label="Stage" value={stringValue(objectiveTheater.campaignStage)} />
                  <SummaryStat label="Surfaced units" value={formatNumber(numberValue(objectiveTheater.surfacedUnits))} />
                  <SummaryStat label="Hidden rear units" value={formatNumber(numberValue(objectiveTheater.hiddenRearUnits))} />
                </div>
                <div className="summary-grid compact">
                  <SummaryStat label="Combat" value={formatNumber(numberValue(objectiveTheater.surfacedCombatUnits))} />
                  <SummaryStat label="Melee" value={formatNumber(numberValue(objectiveTheater.surfacedMeleeUnits))} />
                  <SummaryStat label="Ranged" value={formatNumber(numberValue(objectiveTheater.surfacedRangedUnits))} />
                </div>
                <div className="summary-grid compact">
                  <SummaryStat label="Reserve combat" value={formatNumber(numberValue(objectiveTheater.reserveCombatUnits))} />
                  <SummaryStat label="Reserve melee" value={formatNumber(numberValue(objectiveTheater.reserveMeleeUnits))} />
                  <SummaryStat label="Reserve ranged" value={formatNumber(numberValue(objectiveTheater.reserveRangedUnits))} />
                </div>
                {stringList(objectiveTheater.supportCities).length ? (
                  <>
                    <SectionLabel text="Support cities" />
                    <TagList values={stringList(objectiveTheater.supportCities)} tone="accent" />
                  </>
                ) : null}
              </>
            ) : (
              <p className="muted-text">No objective theater was surfaced on this turn.</p>
            )}
          </Card>

          <Card title="Capture readiness" subtitle="Whether the current objective package can actually convert into a city take or worker capture soon.">
            {captureReadiness ? (
              <>
                <div className="summary-grid compact">
                  <SummaryStat label="Target" value={stringValue(asRecord(captureReadiness.target)?.name)} />
                  <SummaryStat label="Kind" value={stringValue(captureReadiness.targetKind)} />
                  <SummaryStat label="Visible now" value={booleanText(captureReadiness.targetVisible)} />
                  <SummaryStat label="Status" value={stringValue(captureReadiness.status)} />
                </div>
                <div className="summary-grid compact">
                  <SummaryStat label="Target health" value={formatNumber(numberValue(captureReadiness.targetHealth))} />
                  <SummaryStat label="Target strength" value={formatNumber(numberValue(captureReadiness.targetStrength))} />
                  <SummaryStat label="Healthy capture units" value={formatNumber(numberValue(captureReadiness.healthyCaptureUnits))} />
                  <SummaryStat label="Damaged capture units" value={formatNumber(numberValue(captureReadiness.damagedCaptureUnits))} />
                  <SummaryStat label="Ranged support" value={formatNumber(numberValue(captureReadiness.rangedSupportUnits))} />
                  <SummaryStat label="Worker capture chances" value={formatNumber(numberValue(captureReadiness.workerCaptureOpportunities))} />
                </div>
                {stringValue(captureReadiness.summary) ? (
                  <>
                    <SectionLabel text="Summary" />
                    <p className="card-paragraph">{stringValue(captureReadiness.summary)}</p>
                  </>
                ) : null}
              </>
            ) : (
              <p className="muted-text">No capture-readiness read was surfaced on this turn.</p>
            )}
          </Card>

          <PerceptionCoverageSection
            observation={observation}
            perceptionSummary={perceptionSummary}
            attentionFacts={attentionFacts}
            cityHighlights={cityHighlights}
            unitHighlights={unitHighlights}
            threatHighlights={threatHighlights}
            hiddenCities={hiddenCities}
            hiddenUnits={hiddenUnits}
            hiddenThreats={hiddenThreats}
            suppressedContext={suppressedContext}
          />
          <Card title="Empire picture" subtitle="Macro state shared around the tactical turn.">
            {empireObservation ? (
              <div className="summary-grid compact">
                <SummaryStat label="Ruleset" value={stringValue(asRecord(empireObservation.gameContext)?.rulesetName)} />
                <SummaryStat label="Map" value={`${stringValue(asRecord(empireObservation.gameContext)?.mapSize)} ${stringValue(asRecord(empireObservation.gameContext)?.mapType)}`.trim()} />
                <SummaryStat label="Victory goal" value={stringValue(empireObservation.victoryGoal)} />
                <SummaryStat label="Research" value={stringValue(empireObservation.currentResearch)} />
                <SummaryStat label="Gold" value={formatNumber(numberValue(empireObservation.gold))} />
                <SummaryStat label="Happiness" value={formatNumber(numberValue(empireObservation.happiness))} />
              </div>
            ) : (
              <p className="muted-text">No empire observation captured.</p>
            )}
          </Card>
          <Card title="Campaign context" subtitle="The factual rival/frontier packet the tactician received for pressure and war decisions.">
            {campaignContext ? (
              <>
                <div className="summary-grid compact summary-grid-four">
                  <SummaryStat label="Primary rival" value={stringValue(campaignContext.primaryRivalCiv)} />
                  <SummaryStat label="At war" value={booleanText(campaignContext.atWar)} />
                  <SummaryStat label="War choice surfaced" value={booleanText(campaignContext.warChoiceAvailable)} />
                  <SummaryStat label="Visible rival cities" value={formatNumber(numberValue(campaignContext.visibleRivalCities))} />
                  <SummaryStat label="Visible rival units" value={formatNumber(numberValue(campaignContext.visibleRivalUnits))} />
                  <SummaryStat label="Frontline combat" value={formatNumber(numberValue(campaignContext.frontlineFriendlyCombatUnits))} />
                  <SummaryStat label="Melee near objective" value={formatNumber(numberValue(campaignContext.meleeUnitsNearObjective))} />
                  <SummaryStat label="Ranged near objective" value={formatNumber(numberValue(campaignContext.rangedUnitsNearObjective))} />
                </div>
                <div className="structured-list">
                  {campaignTargetItem("Visible target", asRecord(campaignContext.visibleTarget))}
                  {campaignTargetItem("Visible capital", asRecord(campaignContext.visibleCapital))}
                  {campaignTargetItem("Last-known target", asRecord(campaignContext.lastKnownTarget))}
                  {campaignTargetItem("Last-known capital", asRecord(campaignContext.lastKnownCapital))}
                </div>
              </>
            ) : (
              <EmptyCardState
                title="No campaign context"
                body="No rival/frontier packet was surfaced to the tactician on this turn."
              />
            )}
          </Card>

          <AlertSection title="Attention facts" facts={attentionFacts} />
          <ProgressSection title="Progress already in motion" items={progressInMotion} />
          <ThreatHighlightsSection title="Threats the tactician could see" threats={threatHighlights} />

          <EmpireChoicesSection title="Empire choices" choices={empireChoices} />

          <CityHighlightsSection title="Surfaced city cards" cities={cityHighlights} />
          <UnitHighlightsSection title="Surfaced unit cards" units={unitHighlights} />
        </div>
      </SectionShell>

      <SectionShell
        id="section-decision"
        eyebrow="Decision"
        title="What the agent decided"
        body="These are the structured actions the tactician returned, plus the retry and validation story that determined whether they stuck."
      >
        <div className="compact-card-grid">
          <ActionPlanSection
            title="Planned actions"
            actions={plannedActions}
            notes={stringValue(parsedPlan?.notes)}
            candidateLookup={candidateLookup}
          />
          <DomainSummarySection title="Applied outcome" value={turn.outcomeDomainSummary} />
          <div className="full-span">
            <AttemptLadderSection attempts={tacticalAttempts} validationFailures={turn.validationFailures} />
          </div>
        </div>
      </SectionShell>

      <SectionShell
        id="section-literal"
        eyebrow="Literal"
        title="Exact prompt artifacts"
        body="Use this when you need the honest answer to 'did the agent really see this?' These blocks come from logged request events and the stored JSON payloads, not from the curated UI summaries."
      >
        <details className="section-disclosure">
          <summary>Open literal prompt and JSON artifacts</summary>
          <LiteralArtifactsSection
            memory={turn.memory}
            plannerBrief={turn.plannerBrief}
            strategistBrief={turn.strategistBrief}
            tacticalAttempts={tacticalAttempts}
            strategistArtifacts={strategistArtifacts}
          />
        </details>
      </SectionShell>

      <SectionShell
        id="section-events"
        eyebrow="Events"
        title="Raw event log"
        body="Everything else is derived from these events. Keep this for deep debugging and schema audits."
      >
        <details className="section-disclosure">
          <summary>Open raw events</summary>
          <div className="event-list">
            {turn.events.map((event) => (
              <details key={event.id} className="event-item">
                <summary>
                  <span>{event.type}</span>
                  <span>{event.message}</span>
                </summary>
                <pre>{JSON.stringify(event.details ?? {}, null, 2)}</pre>
              </details>
            ))}
          </div>
        </details>
      </SectionShell>
    </div>
  );
}

function Card({
  title,
  subtitle,
  className,
  children,
}: {
  title: string;
  subtitle?: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={`card${className ? ` ${className}` : ""}`}>
      <div className="card-header">
        <div>
          <h3>{title}</h3>
          {subtitle ? <p className="card-subtitle">{subtitle}</p> : null}
        </div>
      </div>
      {children}
    </section>
  );
}

function SummaryStat({
  label,
  value,
  note,
  subtle = false,
}: {
  label: string;
  value: string;
  note?: string;
  subtle?: boolean;
}) {
  return (
    <div className={`summary-stat ${subtle ? "subtle" : ""}`}>
      <span>{label}</span>
      <strong>{value || "None"}</strong>
      {note ? <small>{note}</small> : null}
    </div>
  );
}

function StatusPill({ children, tone }: { children: React.ReactNode; tone: "success" | "warning" | "muted" }) {
  return <span className={`status-pill ${tone}`}>{children}</span>;
}

function hasStrategistInference(turn: TurnRecord): boolean {
  return turn.events.some((event) => event.type === "strategist_llm_request");
}

function SectionLabel({ text }: { text: string }) {
  return <p className="section-label">{text}</p>;
}

function TagList({ values, tone = "neutral" }: { values: string[]; tone?: "neutral" | "accent" | "warning" }) {
  if (!values.length) return <p className="muted-text">None</p>;
  return (
    <div className="tag-list">
      {values.map((value) => (
        <span key={value} className={`tag ${tone}`}>{value}</span>
      ))}
    </div>
  );
}

function EmptyCardState({
  title = "Nothing surfaced",
  body,
}: {
  title?: string;
  body: string;
}) {
  return (
    <div className="empty-card-state">
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  );
}

function CampaignControlCard({
  title,
  subtitle,
  campaignControl,
  emptyTitle,
  emptyBody,
}: {
  title: string;
  subtitle: string;
  campaignControl: Record<string, unknown> | null;
  emptyTitle: string;
  emptyBody: string;
}) {
  return (
    <Card title={title} subtitle={subtitle}>
      {campaignControl && Object.keys(campaignControl).length ? (
        <CampaignControlBody campaignControl={campaignControl} />
      ) : (
        <EmptyCardState title={emptyTitle} body={emptyBody} />
      )}
    </Card>
  );
}

function CampaignControlEmbeddedSection({ campaignControl }: { campaignControl: Record<string, unknown> | null }) {
  if (!campaignControl || !Object.keys(campaignControl).length) return null;
  return (
    <>
      <SectionLabel text="Campaign control" />
      <div className="structured-item">
        <CampaignControlBody campaignControl={campaignControl} embedded />
      </div>
    </>
  );
}

function CampaignControlLabelsBlock({ labels }: { labels: Record<string, unknown> | null }) {
  if (!labels || !Object.keys(labels).length) return null;
  const values = [
    stringValue(labels.commitmentLevel) ? `commitment ${stringValue(labels.commitmentLevel)}` : "",
    stringValue(labels.battleReadiness) ? `readiness ${stringValue(labels.battleReadiness)}` : "",
    stringValue(labels.supplyHealth) ? `supply ${stringValue(labels.supplyHealth)}` : "",
    stringValue(labels.nextCheckpointKind) ? `checkpoint ${stringValue(labels.nextCheckpointKind)}` : "",
    numberValue(labels.checkpointHorizonTurns) !== null ? `horizon ${formatNumber(numberValue(labels.checkpointHorizonTurns))}` : "",
    stringValue(labels.pivotTriggerKind) ? `pivot ${stringValue(labels.pivotTriggerKind)}` : "",
  ].filter(Boolean);
  if (!values.length && !stringValue(labels.nextCheckpointSummary)) return null;
  return (
    <>
      <SectionLabel text="Typed campaign labels" />
      {values.length ? <TagList values={values} tone="accent" /> : null}
      {stringValue(labels.nextCheckpointSummary) ? <p className="card-paragraph">{stringValue(labels.nextCheckpointSummary)}</p> : null}
    </>
  );
}

function DecisionFrameBlock({ frame }: { frame: Record<string, unknown> | null }) {
  if (!frame || !Object.keys(frame).length) return null;
  const mode = stringValue(frame.decisionMode);
  const targetFrame = stringValue(frame.targetFrame);
  const whyNow = stringValue(frame.whyNow);
  const nextCheckpoint = stringValue(frame.nextCheckpoint);
  const expiryCondition = stringValue(frame.expiryCondition);
  if (!mode && !targetFrame && !whyNow && !nextCheckpoint && !expiryCondition) return null;
  return (
    <>
      <SectionLabel text="Decision frame" />
      <div className="structured-item">
        <div className="summary-grid compact">
          <SummaryStat label="Mode" value={mode || "—"} />
          <SummaryStat label="Target frame" value={targetFrame || "—"} />
        </div>
        {whyNow ? (
          <>
            <SectionLabel text="Why now" />
            <p className="card-paragraph">{whyNow}</p>
          </>
        ) : null}
        {nextCheckpoint ? (
          <>
            <SectionLabel text="Next checkpoint" />
            <p className="card-paragraph">{nextCheckpoint}</p>
          </>
        ) : null}
        {expiryCondition ? (
          <>
            <SectionLabel text="Expiry condition" />
            <p className="card-paragraph">{expiryCondition}</p>
          </>
        ) : null}
      </div>
    </>
  );
}

function ControlLanesBlock({ lanes }: { lanes: Record<string, unknown> | null }) {
  if (!lanes || !Object.keys(lanes).length) return null;
  const buildControl = stringValue(lanes.buildControl);
  const unitControl = stringValue(lanes.unitControl);
  const workerControl = stringValue(lanes.workerControl);
  const purchaseControl = stringValue(lanes.purchaseControl);
  const techControl = stringValue(lanes.techControl);
  const policyControl = stringValue(lanes.policyControl);
  const driftWarnings = stringList(lanes.driftWarnings);
  if (!buildControl && !unitControl && !workerControl && !purchaseControl && !techControl && !policyControl && !driftWarnings.length) {
    return null;
  }
  return (
    <>
      <SectionLabel text="Control lanes" />
      <div className="structured-item">
        {buildControl ? (
          <>
            <SectionLabel text="Build control" />
            <p className="card-paragraph">{buildControl}</p>
          </>
        ) : null}
        {unitControl ? (
          <>
            <SectionLabel text="Unit control" />
            <p className="card-paragraph">{unitControl}</p>
          </>
        ) : null}
        {workerControl ? (
          <>
            <SectionLabel text="Worker control" />
            <p className="card-paragraph">{workerControl}</p>
          </>
        ) : null}
        {purchaseControl ? (
          <>
            <SectionLabel text="Purchase control" />
            <p className="card-paragraph">{purchaseControl}</p>
          </>
        ) : null}
        {techControl ? (
          <>
            <SectionLabel text="Tech control" />
            <p className="card-paragraph">{techControl}</p>
          </>
        ) : null}
        {policyControl ? (
          <>
            <SectionLabel text="Policy control" />
            <p className="card-paragraph">{policyControl}</p>
          </>
        ) : null}
        {driftWarnings.length ? (
          <>
            <SectionLabel text="Drift warnings" />
            <TagList values={driftWarnings} tone="warning" />
          </>
        ) : null}
      </div>
    </>
  );
}

function ReviewContractBlock({ contract }: { contract: Record<string, unknown> | null }) {
  if (!contract || !Object.keys(contract).length) return null;
  const maxAgeTurns = numberValue(contract.maxAgeTurns);
  const triggers = objectArray(contract.triggers);
  if (maxAgeTurns === null && !triggers.length) return null;
  return (
    <>
      <SectionLabel text="Review contract" />
      <div className="structured-item">
        <div className="summary-grid compact">
          <SummaryStat label="Max age" value={maxAgeTurns === null ? "—" : `${formatNumber(maxAgeTurns)} turns`} />
          <SummaryStat label="Triggers" value={formatNumber(triggers.length)} />
        </div>
        {triggers.length ? (
          <div className="stacked-list">
            {triggers.map((trigger, index) => {
              const kind = stringValue(trigger.kind);
              const metric = stringValue(trigger.metric);
              const summary = stringValue(trigger.summary);
              const withinTurns = numberValue(trigger.withinTurns);
              const tags = [
                kind ? `kind ${kind}` : "",
                metric ? `metric ${metric}` : "",
                withinTurns === null ? "" : `within ${formatNumber(withinTurns)}`,
              ].filter(Boolean);
              return (
                <div className="structured-item" key={`${kind}-${metric}-${index}`}>
                  {tags.length ? <TagList values={tags} tone="accent" /> : null}
                  {summary ? <p className="card-paragraph">{summary}</p> : null}
                </div>
              );
            })}
          </div>
        ) : (
          <p className="card-paragraph">No strategist review triggers were recorded for this memo.</p>
        )}
      </div>
    </>
  );
}

function DecisionFocusCard({
  title,
  subtitle,
  decisionFocus,
}: {
  title: string;
  subtitle: string;
  decisionFocus: Record<string, unknown> | null;
}) {
  return (
    <Card title={title} subtitle={subtitle}>
      {decisionFocus && Object.keys(decisionFocus).length ? (
        <DecisionFocusBody decisionFocus={decisionFocus} />
      ) : (
        <EmptyCardState title="No decision focus" body="The tactical brief did not surface a mode-aware decision-focus block on this turn." />
      )}
    </Card>
  );
}

function DecisionFocusBody({ decisionFocus }: { decisionFocus: Record<string, unknown> }) {
  const criticalChoices = objectArray(decisionFocus.criticalChoicesNow);
  const backgroundChores = stringList(decisionFocus.backgroundChores);
  const mismatch = stringList(decisionFocus.actionSurfaceMismatch);
  const launchCohort = asRecord(decisionFocus.launchCohort);
  const supplySnapshot = asRecord(decisionFocus.supplySnapshot);
  return (
    <>
      <div className="summary-grid compact">
        <SummaryStat label="Mode" value={stringValue(decisionFocus.mode) || "—"} />
        <SummaryStat label="Target frame" value={stringValue(decisionFocus.targetFrame) || "—"} />
        <SummaryStat label="Next checkpoint" value={stringValue(decisionFocus.nextCheckpoint) || "—"} />
      </div>
      {stringValue(decisionFocus.whyNow) ? (
        <>
          <SectionLabel text="Why now" />
          <p className="card-paragraph">{stringValue(decisionFocus.whyNow)}</p>
        </>
      ) : null}
      {stringValue(decisionFocus.expiryCondition) ? (
        <>
          <SectionLabel text="Expiry condition" />
          <p className="card-paragraph">{stringValue(decisionFocus.expiryCondition)}</p>
        </>
      ) : null}
      {criticalChoices.length ? (
        <>
          <SectionLabel text="Critical choices now" />
          <div className="structured-list">
            {criticalChoices.map((item, index) => (
              <article key={`${stringValue(item.kind)}-${index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(item.headline) || "Critical choice"}</strong>
                  {stringValue(item.kind) ? (
                    <div className="tag-list compact">
                      <span className="tag accent">{stringValue(item.kind)}</span>
                    </div>
                  ) : null}
                </div>
                {stringValue(item.detail) ? <p className="card-paragraph">{stringValue(item.detail)}</p> : null}
              </article>
            ))}
          </div>
        </>
      ) : null}
      {launchCohort ? (
        <>
          <SectionLabel text="Launch cohort" />
          <div className="structured-item">
            <div className="summary-grid compact">
              <SummaryStat label="War state" value={stringValue(launchCohort.warState) || "—"} />
              <SummaryStat label="Target" value={stringValue(asRecord(launchCohort.target)?.name) || "—"} />
              <SummaryStat label="Healthy capture" value={formatNumber(numberValue(launchCohort.healthyCaptureUnits))} />
              <SummaryStat label="Damaged capture" value={formatNumber(numberValue(launchCohort.damagedCaptureUnits))} />
              <SummaryStat label="Ranged support" value={formatNumber(numberValue(launchCohort.rangedSupportUnits))} />
              <SummaryStat label="Surfaced melee" value={formatNumber(numberValue(launchCohort.surfacedMeleeUnits))} />
              <SummaryStat label="Surfaced ranged" value={formatNumber(numberValue(launchCohort.surfacedRangedUnits))} />
            </div>
            {stringValue(launchCohort.summary) ? <p className="card-paragraph">{stringValue(launchCohort.summary)}</p> : null}
            {stringList(launchCohort.supportCities).length ? <TagList values={stringList(launchCohort.supportCities)} tone="accent" /> : null}
          </div>
        </>
      ) : null}
      {supplySnapshot ? (
        <>
          <SectionLabel text="Supply snapshot" />
          <div className="structured-item">
            <div className="summary-grid compact">
              <SummaryStat label="Gold" value={formatNumber(numberValue(supplySnapshot.gold))} />
              <SummaryStat label="Happiness" value={formatNumber(numberValue(supplySnapshot.happiness))} />
              <SummaryStat label="Science / turn" value={formatNumber(numberValue(supplySnapshot.sciencePerTurn))} />
              <SummaryStat label="Cities" value={formatNumber(numberValue(supplySnapshot.cityCount))} />
              <SummaryStat label="Military units" value={formatNumber(numberValue(supplySnapshot.militaryUnitCount))} />
              <SummaryStat label="Supply health" value={stringValue(supplySnapshot.supplyHealth) || "—"} />
            </div>
            {stringValue(supplySnapshot.summary) ? <p className="card-paragraph">{stringValue(supplySnapshot.summary)}</p> : null}
          </div>
        </>
      ) : null}
      {backgroundChores.length ? (
        <>
          <SectionLabel text="Background chores" />
          <TagList values={backgroundChores} />
        </>
      ) : null}
      {mismatch.length ? (
        <>
          <SectionLabel text="Action-surface mismatch" />
          <TagList values={mismatch} tone="warning" />
        </>
      ) : null}
    </>
  );
}

function CampaignControlBody({
  campaignControl,
  embedded = false,
}: {
  campaignControl: Record<string, unknown>;
  embedded?: boolean;
}) {
  const holdingCosts = stringList(campaignControl.holdingCosts);
  const pivotTriggers = stringList(campaignControl.pivotTriggers);
  const summary = (
    <>
      <div className="summary-grid compact">
        <SummaryStat label="Commitment" value={stringValue(campaignControl.commitmentLevel) || "—"} />
        <SummaryStat label="Readiness" value={stringValue(campaignControl.battleReadiness) || "—"} />
        <SummaryStat label="Supply" value={stringValue(campaignControl.supplyHealth) || "—"} />
        <SummaryStat label="Checkpoint" value={stringValue(campaignControl.nextCheckpointKind) || "—"} />
        <SummaryStat label="Checkpoint status" value={stringValue(campaignControl.checkpointStatus) || "—"} />
        <SummaryStat label="Launch window" value={booleanText(campaignControl.launchWindowOpen)} />
        <SummaryStat label="Commitment age" value={nullableNumberText(campaignControl.commitmentAgeTurns)} />
        <SummaryStat label="Since checkpoint" value={nullableNumberText(campaignControl.turnsSinceCheckpoint)} />
      </div>
      {stringValue(campaignControl.nextCheckpointSummary) ? (
        <>
          <SectionLabel text="Next checkpoint summary" />
          <p className="card-paragraph">{stringValue(campaignControl.nextCheckpointSummary)}</p>
        </>
      ) : null}
      {stringValue(campaignControl.pivotTriggerKind) ? (
        <>
          <SectionLabel text="Pivot trigger kind" />
          <TagList values={[stringValue(campaignControl.pivotTriggerKind)]} tone="warning" />
        </>
      ) : null}
      {holdingCosts.length ? (
        <>
          <SectionLabel text="Holding costs" />
          <TagList values={holdingCosts} tone="warning" />
        </>
      ) : null}
      {pivotTriggers.length ? (
        <>
          <SectionLabel text="Pivot triggers" />
          <TagList values={pivotTriggers} tone="warning" />
        </>
      ) : null}
    </>
  );

  if (embedded) return summary;
  return <>{summary}</>;
}

function MemoryNotesSection({
  title,
  notes,
  embedded = false,
}: {
  title: string;
  notes: Record<string, unknown>[];
  embedded?: boolean;
}) {
  if (!notes.length) return null;

  const content = (
    <div className="structured-list">
      {notes.map((note, index) => (
        <article key={`${stringValue(note.text)}-${index}`} className="structured-item">
          <div className="structured-item-header">
            <strong>{stringValue(note.text) || "Untitled memory note"}</strong>
            <div className="tag-list compact">
              {stringValue(note.kind) ? <span className="tag neutral">{stringValue(note.kind)}</span> : null}
              {stringValue(note.topic) ? <span className="tag neutral">{stringValue(note.topic)}</span> : null}
              {stringValue(note.confidence) ? <span className="tag neutral">{stringValue(note.confidence)}</span> : null}
            </div>
          </div>
          {formatMemoryNoteMeta(note) ? <p className="mini-note">{formatMemoryNoteMeta(note)}</p> : null}
        </article>
      ))}
    </div>
  );

  if (embedded) {
    return (
      <>
        <SectionLabel text={title} />
        {content}
      </>
    );
  }

  return <Card title={title}>{content}</Card>;
}

function MemoryAnchorsSection({
  title,
  anchors,
}: {
  title: string;
  anchors: Record<string, unknown>[];
}) {
  if (!anchors.length) return null;
  return (
    <>
      <SectionLabel text={title} />
      <div className="structured-list">
        {anchors.map((anchor, index) => (
          <article key={`${stringValue(anchor.label)}-${index}`} className="structured-item">
            <div className="structured-item-header">
              <strong>{stringValue(anchor.label) || "Untitled anchor"}</strong>
              <div className="tag-list compact">
                {stringValue(anchor.kind) ? <span className="tag neutral">{stringValue(anchor.kind)}</span> : null}
                {stringValue(anchor.civName) ? <span className="tag neutral">{stringValue(anchor.civName)}</span> : null}
              </div>
            </div>
            <p className="mini-note">
              {formatAnchorLocation(anchor)}
              {formatAnchorLocation(anchor) && formatAnchorTurns(anchor) ? " · " : ""}
              {formatAnchorTurns(anchor)}
            </p>
          </article>
        ))}
      </div>
    </>
  );
}

function MemoryNotesCard({
  title,
  subtitle,
  notes,
  emptyTitle,
  emptyBody,
}: {
  title: string;
  subtitle: string;
  notes: Record<string, unknown>[];
  emptyTitle: string;
  emptyBody: string;
}) {
  return (
    <Card title={title} subtitle={subtitle}>
      {notes.length ? (
        <MemoryNotesSection title={title} notes={notes} embedded />
      ) : (
        <EmptyCardState title={emptyTitle} body={emptyBody} />
      )}
    </Card>
  );
}

function StrategistMemoMemorySection({ memo }: { memo: Record<string, unknown> | null }) {
  return (
    <Card title="Last strategist memo" subtitle="The strategist memo snapshot currently stored in shared memory, before any new strategist pass on this turn.">
      {memo && Object.keys(memo).length ? (
        <>
          <div className="summary-grid compact">
            <SummaryStat label="Archetype" value={stringValue(memo.gameArchetype) || "—"} />
            <SummaryStat label="Win path" value={stringValue(memo.winPath) || "—"} />
            <SummaryStat label="Stage" value={stringValue(memo.campaignStage) || "—"} />
            <SummaryStat label="Objective" value={stringValue(memo.decisiveObjective) || "—"} />
            <SummaryStat label="Created" value={formatNumber(numberValue(memo.createdTurn))} />
            <SummaryStat label="Last reviewed" value={formatNumber(numberValue(memo.lastReviewedTurn))} />
            <SummaryStat label="Max age" value={formatNumber(numberValue(asRecord(memo.reviewContract)?.maxAgeTurns))} />
            <SummaryStat label="Refresh reason" value={stringValue(memo.lastRefreshReason) || "—"} />
          </div>
          {stringValue(memo.thesis) ? <p className="card-paragraph">{stringValue(memo.thesis)}</p> : null}
          {stringValue(memo.conversionBlocker) ? (
            <>
              <SectionLabel text="Conversion blocker" />
              <p className="card-paragraph">{stringValue(memo.conversionBlocker)}</p>
            </>
          ) : null}
          <DecisionFrameBlock frame={asRecord(memo.decisionFrame)} />
          <ControlLanesBlock lanes={asRecord(memo.controlLanes)} />
          <ReviewContractBlock contract={asRecord(memo.reviewContract)} />
          {stringValue(memo.pastSummary) ? (
            <>
              <SectionLabel text="Past" />
              <p className="card-paragraph">{stringValue(memo.pastSummary)}</p>
            </>
          ) : null}
          {stringValue(memo.currentSituation) ? (
            <>
              <SectionLabel text="Now" />
              <p className="card-paragraph">{stringValue(memo.currentSituation)}</p>
            </>
          ) : null}
          {stringValue(memo.futurePlan) ? (
            <>
              <SectionLabel text="Future" />
              <p className="card-paragraph">{stringValue(memo.futurePlan)}</p>
            </>
          ) : null}
          <CampaignControlLabelsBlock labels={asRecord(memo.campaignControl)} />
          {stringValue(memo.tacticianHandoff) ? (
            <>
              <SectionLabel text="Tactician handoff" />
              <p className="card-paragraph">{stringValue(memo.tacticianHandoff)}</p>
            </>
          ) : null}
          <div className="summary-grid compact">
            <SummaryStat label="Cities at review" value={formatNumber(numberValue(memo.reviewCityCount))} />
            <SummaryStat label="Military units at review" value={formatNumber(numberValue(memo.reviewMilitaryUnitCount))} />
            <SummaryStat label="At war at review" value={booleanText(memo.reviewIsAtWar)} />
            <SummaryStat label="Contact complete" value={booleanText(memo.reviewContactComplete)} />
            <SummaryStat label="Visible rival cities" value={formatNumber(numberValue(memo.reviewVisibleRivalCities))} />
            <SummaryStat label="Visible rival units" value={formatNumber(numberValue(memo.reviewVisibleRivalUnits))} />
            <SummaryStat label="Primary rival" value={stringValue(memo.reviewPrimaryRivalCiv) || "—"} />
            <SummaryStat label="Research at review" value={stringValue(memo.reviewResearch) || "—"} />
          </div>
          {stringList(memo.reviewCityNames).length ? (
            <>
              <SectionLabel text="City names at review" />
              <TagList values={stringList(memo.reviewCityNames)} tone="accent" />
            </>
          ) : null}
        </>
      ) : (
        <EmptyCardState title="No stored strategist memo" body="The shared memory did not carry a last-strategist-memo snapshot into this turn." />
      )}
    </Card>
  );
}

function TacticianTurnLogSection({ entries }: { entries: Record<string, unknown>[] }) {
  return (
    <Card title="Tactician turn log" subtitle="Per-turn delta written after execution so later stateless calls can see what changed, what completed, and what is now stale.">
      {entries.length ? (
        <details className="inline-disclosure" open={entries.length <= 2}>
          <summary>Open {formatNumber(entries.length)} tactician log entries</summary>
          <div className="inline-disclosure-body">
            <div className="structured-list">
              {entries
                .slice()
                .reverse()
                .map((entry, index) => (
                  <article key={`${formatNumber(numberValue(entry.turn))}-${index}`} className="structured-item">
                    <div className="structured-item-header">
                      <strong>{`Turn ${formatNumber(numberValue(entry.turn))}`}</strong>
                      <div className="tag-list compact">
                        {stringValue(entry.campaignStage) ? <span className="tag neutral">{stringValue(entry.campaignStage)}</span> : null}
                      {numberValue(entry.basedOnStrategistTurn) !== null ? (
                          <span className="tag neutral">{`memo ${formatNumber(numberValue(entry.basedOnStrategistTurn))}`}</span>
                        ) : null}
                        {stringValue(entry.memoValidity) ? (
                          <span className={`tag ${tagToneFromMemoValidity(stringValue(entry.memoValidity))}`}>{stringValue(entry.memoValidity)}</span>
                        ) : null}
                        {stringValue(entry.commitmentLevel) ? <span className="tag neutral">{`commit ${stringValue(entry.commitmentLevel)}`}</span> : null}
                        {stringValue(entry.battleReadiness) ? <span className="tag neutral">{`ready ${stringValue(entry.battleReadiness)}`}</span> : null}
                        {stringValue(entry.supplyHealth) ? <span className="tag neutral">{`supply ${stringValue(entry.supplyHealth)}`}</span> : null}
                      </div>
                    </div>
                    {stringValue(entry.summary) ? <p className="card-paragraph">{stringValue(entry.summary)}</p> : null}
                    <TacticianTurnLogGroup label="What changed" values={stringList(entry.whatChanged)} />
                    <TacticianTurnLogGroup label="Completed" values={stringList(entry.completed)} tone="accent" />
                    <TacticianTurnLogGroup label="Still blocked" values={stringList(entry.stillBlocked)} tone="warning" />
                    <TacticianTurnLogGroup label="Obsolete" values={stringList(entry.obsolete)} />
                    <TacticianTurnLogGroup label="Action-surface mismatch" values={stringList(entry.actionSurfaceMismatch)} tone="warning" />
                  </article>
                ))}
            </div>
          </div>
        </details>
      ) : (
        <EmptyCardState title="No tactician log yet" body="No per-turn tactical delta had been carried into this turn." />
      )}
    </Card>
  );
}

function TacticianTurnLogGroup({
  label,
  values,
  tone = "neutral",
}: {
  label: string;
  values: string[];
  tone?: "neutral" | "warning" | "accent";
}) {
  if (!values.length) return null;
  return (
    <>
      <SectionLabel text={label} />
      <TagList values={values} tone={tone} />
    </>
  );
}

function CityIntentMemorySection({ intents }: { intents: Record<string, unknown>[] }) {
  return (
    <Card title="City intents" subtitle="Carry-over purpose attached to cities from earlier turns.">
      {intents.length ? (
        <details className="inline-disclosure">
          <summary>Open {formatNumber(intents.length)} carried city intents</summary>
          <div className="inline-disclosure-body">
            <div className="structured-list">
              {intents.map((intent, index) => (
                <article key={`${stringValue(intent.cityName)}-${index}`} className="structured-item">
                  <div className="structured-item-header">
                    <strong>{stringValue(intent.cityName) || "Unknown city"}</strong>
                    <div className="tag-list compact">
                      {stringValue(intent.intent) ? <span className="tag neutral">{stringValue(intent.intent)}</span> : null}
                      {stringValue(intent.target) ? <span className="tag neutral">{stringValue(intent.target)}</span> : null}
                    </div>
                  </div>
                  {stringList(intent.reasons).length ? <p className="card-paragraph">{stringList(intent.reasons).join(" ")}</p> : null}
                  <p className="mini-note">
                    {formatCoordinateText(numberValue(intent.cityX), numberValue(intent.cityY))}
                    {` · last progress ${formatNumber(numberValue(intent.lastProgressTurn))}`}
                    {` · stale after ${formatNumber(numberValue(intent.staleAfterTurn))}`}
                  </p>
                </article>
              ))}
            </div>
          </div>
        </details>
      ) : (
        <EmptyCardState title="No city intents" body="No city-level intent memory was carried into this turn." />
      )}
    </Card>
  );
}

function UnitAssignmentMemorySection({ assignments }: { assignments: Record<string, unknown>[] }) {
  return (
    <Card title="Unit assignments" subtitle="Carry-over role and target hints attached to units from earlier turns.">
      {assignments.length ? (
        <details className="inline-disclosure">
          <summary>Open {formatNumber(assignments.length)} carried unit assignments</summary>
          <div className="inline-disclosure-body">
            <div className="structured-list">
              {assignments.map((assignment, index) => (
                <article key={`${stringValue(assignment.unitName)}-${index}`} className="structured-item">
                  <div className="structured-item-header">
                    <strong>{stringValue(assignment.unitName) || `Unit ${formatNumber(numberValue(assignment.unitId))}`}</strong>
                    <div className="tag-list compact">
                      {stringValue(assignment.role) ? <span className="tag neutral">{stringValue(assignment.role)}</span> : null}
                      <span className="tag neutral">#{formatNumber(numberValue(assignment.unitId))}</span>
                    </div>
                  </div>
                  {stringValue(assignment.detail) ? <p className="card-paragraph">{stringValue(assignment.detail)}</p> : null}
                  <p className="mini-note">
                    {`target ${formatCoordinateText(numberValue(assignment.targetX), numberValue(assignment.targetY))}`}
                    {` · last progress ${formatNumber(numberValue(assignment.lastProgressTurn))}`}
                    {` · stale after ${formatNumber(numberValue(assignment.staleAfterTurn))}`}
                  </p>
                </article>
              ))}
            </div>
          </div>
        </details>
      ) : (
        <EmptyCardState title="No unit assignments" body="No unit-assignment memory was carried into this turn." />
      )}
    </Card>
  );
}

function RecentFailureMemorySection({ failures }: { failures: Record<string, unknown>[] }) {
  return (
    <Card title="Recent failures" subtitle="Short memory of plan/execution failures the agent was carrying forward.">
      {failures.length ? (
        <details className="inline-disclosure">
          <summary>Open {formatNumber(failures.length)} recent failures</summary>
          <div className="inline-disclosure-body">
            <div className="structured-list">
              {failures.map((failure, index) => (
                <article key={`${stringValue(failure.summary)}-${index}`} className="structured-item">
                  <div className="structured-item-header">
                    <strong>{stringValue(failure.summary) || "Untitled failure"}</strong>
                    <div className="tag-list compact">
                      {stringValue(failure.kind) ? <span className="tag warning">{stringValue(failure.kind)}</span> : null}
                      {stringValue(failure.actionType) ? <span className="tag neutral">{stringValue(failure.actionType)}</span> : null}
                    </div>
                  </div>
                  <p className="mini-note">
                    {`turn ${formatNumber(numberValue(failure.turn))}`}
                    {numberValue(failure.unitId) !== null ? ` · unit #${formatNumber(numberValue(failure.unitId))}` : ""}
                    {(numberValue(failure.cityX) !== null || numberValue(failure.cityY) !== null) ? ` · city ${formatCoordinateText(numberValue(failure.cityX), numberValue(failure.cityY))}` : ""}
                  </p>
                </article>
              ))}
            </div>
          </div>
        </details>
      ) : (
        <EmptyCardState title="No recent failures" body="No recent-failure memory was carried into this turn." />
      )}
    </Card>
  );
}

function AlertSection({ title, facts }: { title: string; facts: Record<string, unknown>[] }) {
  return (
    <Card title={title}>
      {facts.length ? (
        <div className="structured-list">
          {facts.map((fact, index) => (
            <article key={`${stringValue(fact.headline)}-${index}`} className="structured-item">
              <div className="structured-item-header">
                <strong>{stringValue(fact.headline) || "Untitled alert"}</strong>
                <div className="tag-list compact">
                  <StatusPill tone={toneFromSeverity(stringValue(fact.severity))}>{stringValue(fact.severity) || "info"}</StatusPill>
                  <span className="tag neutral">{stringValue(fact.category) || "misc"}</span>
                </div>
              </div>
              <p className="card-paragraph">{stringValue(fact.detail)}</p>
            </article>
          ))}
        </div>
      ) : (
        <p className="muted-text">No attention facts were captured for this turn.</p>
      )}
    </Card>
  );
}

function ProgressSection({
  title,
  items,
  embedded = false,
}: {
  title: string;
  items: Record<string, unknown>[];
  embedded?: boolean;
}) {
  const content = items.length ? (
    <div className="structured-list">
      {items.map((item, index) => (
        <article key={`${stringValue(item.label)}-${index}`} className="structured-item">
          <div className="structured-item-header">
            <strong>{stringValue(item.label) || "Unnamed progress item"}</strong>
            <span className="tag neutral">{stringValue(item.category) || "progress"}</span>
          </div>
          <p className="card-paragraph">{stringValue(item.detail)}</p>
        </article>
      ))}
    </div>
  ) : (
    <EmptyCardState title="No in-flight progress" body="The planner brief did not carry any active research, city, or unit progress reminders for this turn." />
  );

  if (embedded) {
    return (
      <>
        <SectionLabel text={title} />
        {content}
      </>
    );
  }

  return <Card title={title}>{content}</Card>;
}

function StrategistContextSection({
  title,
  brief,
  campaignControl,
  threats,
  campaignPicture,
}: {
  title: string;
  brief: Record<string, unknown> | null;
  campaignControl: Record<string, unknown> | null;
  threats: Record<string, unknown>[];
  campaignPicture: Record<string, unknown> | null;
}) {
  const gameContext = asRecord(brief?.gameContext);
  const empireSummary = asRecord(brief?.empireSummary);
  const lastStrategistMemo = asRecord(brief?.lastStrategistMemo);
  const worldModel = asRecord(brief?.worldModel);
  const campaign = asRecord(brief?.campaign);
  const empirePlan = asRecord(brief?.empirePlan);
  const recentChanges = objectArray(brief?.recentChanges);
  const lessons = objectArray(brief?.lessons);
  const progressInMotion = objectArray(brief?.progressInMotion);
  const recentFailures = stringList(brief?.recentFailures);
  const enabledVictories = stringList(brief?.enabledVictoryTypes);

  return (
    <Card title={title} subtitle="Broad current state plus the notebook context the strategist inherited before writing a fresh memo.">
      {brief ? (
        <>
          <div className="summary-grid compact summary-grid-four">
            <SummaryStat label="Map archetype" value={stringValue(gameContext?.archetype)} />
            <SummaryStat label="Exploration value" value={stringValue(gameContext?.explorationValue)} />
            <SummaryStat label="Expansion window" value={stringValue(gameContext?.expansionWindow)} />
            <SummaryStat label="Current research" value={stringValue(brief.currentResearch)} />
            <SummaryStat label="Research status" value={stringValue(brief.currentResearchStatus)} />
            <SummaryStat label="Empire size" value={`${formatNumber(numberValue(empireSummary?.cityCount))} cities · ${formatNumber(numberValue(empireSummary?.unitCount))} units`} />
          </div>

          <SectionLabel text="Enabled victories" />
          <TagList values={enabledVictories} tone="accent" />

          <CampaignControlEmbeddedSection campaignControl={campaignControl} />

          {lastStrategistMemo ? (
            <>
              <SectionLabel text="Last strategist memo" />
              <div className="structured-item">
              <div className="summary-grid compact">
                  <SummaryStat label="Stage" value={stringValue(lastStrategistMemo.campaignStage)} />
                  <SummaryStat label="Win path" value={stringValue(lastStrategistMemo.winPath)} />
                  <SummaryStat label="Objective" value={stringValue(lastStrategistMemo.decisiveObjective)} />
                </div>
                <DecisionFrameBlock frame={asRecord(lastStrategistMemo.decisionFrame)} />
                <ControlLanesBlock lanes={asRecord(lastStrategistMemo.controlLanes)} />
                <ReviewContractBlock contract={asRecord(lastStrategistMemo.reviewContract)} />
                {stringValue(lastStrategistMemo.conversionBlocker) ? (
                  <>
                    <SectionLabel text="Conversion blocker" />
                    <p className="card-paragraph">{stringValue(lastStrategistMemo.conversionBlocker)}</p>
                  </>
                ) : null}
                {stringValue(lastStrategistMemo.pastSummary) ? (
                  <>
                    <SectionLabel text="Past" />
                    <p className="card-paragraph">{stringValue(lastStrategistMemo.pastSummary)}</p>
                  </>
                ) : null}
                {stringValue(lastStrategistMemo.currentSituation) ? (
                  <>
                    <SectionLabel text="Now" />
                    <p className="card-paragraph">{stringValue(lastStrategistMemo.currentSituation)}</p>
                  </>
                ) : null}
                {stringValue(lastStrategistMemo.futurePlan) ? (
                  <>
                    <SectionLabel text="Future" />
                    <p className="card-paragraph">{stringValue(lastStrategistMemo.futurePlan)}</p>
                  </>
                ) : null}
                <CampaignControlLabelsBlock labels={asRecord(lastStrategistMemo.campaignControl)} />
              </div>
            </>
          ) : null}

          {stringValue(worldModel?.summary) ? (
            <>
              <SectionLabel text="World model" />
              <p className="card-paragraph">{stringValue(worldModel?.summary)}</p>
            </>
          ) : null}
          {stringValue(campaign?.summary) ? (
            <>
              <SectionLabel text="Campaign notebook" />
              <p className="card-paragraph">{stringValue(campaign?.summary)}</p>
            </>
          ) : null}
          {stringValue(empirePlan?.summary) ? (
            <>
              <SectionLabel text="Empire plan notebook" />
              <p className="card-paragraph">{stringValue(empirePlan?.summary)}</p>
            </>
          ) : null}
          {recentChanges.length ? (
            <>
              <SectionLabel text="Recent changes" />
              <TagList values={recentChanges.map((item) => stringValue(item.text)).filter(Boolean)} />
            </>
          ) : null}
          {lessons.length ? (
            <>
              <SectionLabel text="Lessons" />
              <TagList values={lessons.map((item) => stringValue(item.text)).filter(Boolean)} />
            </>
          ) : null}

          {campaignPicture ? (
            <>
              <SectionLabel text="Campaign picture" />
              <div className="summary-grid compact summary-grid-four">
                <SummaryStat label="Primary rival" value={stringValue(campaignPicture.primaryRivalCiv)} />
                <SummaryStat label="Visible rival cities" value={formatNumber(numberValue(campaignPicture.visibleRivalCities))} />
                <SummaryStat label="Visible rival units" value={formatNumber(numberValue(campaignPicture.visibleRivalUnits))} />
                <SummaryStat label="Visible capitals" value={formatNumber(numberValue(campaignPicture.visibleRivalCapitals))} />
                <SummaryStat label="Frontline combat" value={formatNumber(numberValue(campaignPicture.frontlineFriendlyCombatUnits))} />
                <SummaryStat label="Frontline melee" value={formatNumber(numberValue(campaignPicture.frontlineMeleeUnits))} />
                <SummaryStat label="Frontline ranged" value={formatNumber(numberValue(campaignPicture.frontlineRangedUnits))} />
                <SummaryStat label="Frontline damaged" value={formatNumber(numberValue(campaignPicture.frontlineDamagedUnits))} />
                <SummaryStat label="City bombard ready" value={formatNumber(numberValue(campaignPicture.frontlineCityBombards))} />
                <SummaryStat label="Melee near nearest city" value={formatNumber(numberValue(campaignPicture.meleeUnitsNearNearestRivalCity))} />
                <SummaryStat label="Ranged near nearest city" value={formatNumber(numberValue(campaignPicture.rangedUnitsNearNearestRivalCity))} />
              </div>
              <div className="structured-list">
                {campaignTargetItem("Nearest rival city", asRecord(campaignPicture.nearestRivalCity))}
                {campaignTargetItem("Nearest rival capital", asRecord(campaignPicture.nearestRivalCapital))}
              </div>
            </>
          ) : null}

          <ThreatHighlightsSection title="Rival threats" threats={threats} embedded />
          <ProgressSection title="Strategist progress cues" items={progressInMotion.slice(0, 4)} embedded />

          {recentFailures.length ? (
            <>
              <SectionLabel text="Recent failures to avoid" />
              <TagList values={recentFailures} tone="warning" />
            </>
          ) : null}
        </>
      ) : (
        <p className="muted-text">No strategist brief was captured for this turn.</p>
      )}
    </Card>
  );
}

function StrategistRivalCitiesSection({
  title,
  cities,
}: {
  title: string;
  cities: Record<string, unknown>[];
}) {
  return (
    <Card title={title} subtitle="Factual foreign city sightings available to the strategist.">
      {cities.length ? (
        <div className="structured-list structured-list-grid">
          {cities.map((city, index) => (
            <article key={`${stringValue(city.civName)}-${stringValue(city.name)}-${index}`} className="structured-item">
              <div className="structured-item-header">
                <strong>{stringValue(city.civName)} · {stringValue(city.name) || "Unnamed city"}</strong>
                <div className="tag-list compact">
                  <span className="tag neutral">({formatNumber(numberValue(city.x))}, {formatNumber(numberValue(city.y))})</span>
                  {booleanValue(city.isCapital) ? <span className="tag warning">capital</span> : null}
                  <span className="tag neutral">{stringValue(city.relation) || "foreign"}</span>
                </div>
              </div>
              <div className="mini-metric-grid">
                <MiniMetric label="Health" value={nullableNumberText(city.health)} />
                <MiniMetric label="Strength" value={nullableNumberText(city.combatStrength)} />
                <MiniMetric label="Nearest city" value={nullableNumberText(city.distanceToClosestCity)} />
                <MiniMetric label="Nearest unit" value={nullableNumberText(city.distanceToClosestUnit)} />
              </div>
              {stringList(city.facts).length ? (
                <>
                  <SectionLabel text="Facts" />
                  <TagList values={stringList(city.facts)} />
                </>
              ) : null}
            </article>
          ))}
        </div>
      ) : (
        <EmptyCardState title="No visible rival cities" body="No foreign city sightings were available to the strategist on this turn." />
      )}
    </Card>
  );
}

function StrategistRivalUnitsSection({
  title,
  units,
}: {
  title: string;
  units: Record<string, unknown>[];
}) {
  return (
    <Card title={title} subtitle="Factual foreign unit sightings available to the strategist.">
      {units.length ? (
        <div className="structured-list structured-list-grid">
          {units.map((unit, index) => (
            <article key={`${stringValue(unit.civName)}-${stringValue(unit.name)}-${index}`} className="structured-item">
              <div className="structured-item-header">
                <strong>{stringValue(unit.civName)} · {stringValue(unit.name) || "Unnamed unit"}</strong>
                <div className="tag-list compact">
                  <span className="tag neutral">({formatNumber(numberValue(unit.x))}, {formatNumber(numberValue(unit.y))})</span>
                  <span className="tag neutral">{stringValue(unit.relation) || "foreign"}</span>
                </div>
              </div>
              <div className="mini-metric-grid">
                <MiniMetric label="Health" value={nullableNumberText(unit.health)} />
                <MiniMetric label="Combat" value={nullableNumberText(unit.combatStrength)} />
                <MiniMetric label="Nearest city" value={nullableNumberText(unit.distanceToClosestCity)} />
                <MiniMetric label="Nearest unit" value={nullableNumberText(unit.distanceToClosestUnit)} />
              </div>
              {stringList(unit.facts).length ? (
                <>
                  <SectionLabel text="Facts" />
                  <TagList values={stringList(unit.facts)} />
                </>
              ) : null}
            </article>
          ))}
        </div>
      ) : (
        <EmptyCardState title="No visible rival units" body="No foreign unit sightings were available to the strategist on this turn." />
      )}
    </Card>
  );
}

function campaignTargetItem(label: string, target: Record<string, unknown> | null) {
  if (!target) return null;
  return (
    <article key={label} className="structured-item">
      <div className="structured-item-header">
        <strong>{label}</strong>
        <div className="tag-list compact">
          <span className="tag neutral">{stringValue(target.civName) || "Unknown rival"}</span>
          <span className="tag neutral">{stringValue(target.name) || "Unknown target"}</span>
          <span className="tag neutral">({formatNumber(numberValue(target.x))}, {formatNumber(numberValue(target.y))})</span>
        </div>
      </div>
      <div className="mini-metric-grid">
        <MiniMetric label="Health" value={nullableNumberText(target.health)} />
        <MiniMetric label="Strength" value={nullableNumberText(target.combatStrength)} />
        <MiniMetric label="Nearest city" value={nullableNumberText(target.distanceToClosestCity)} />
        <MiniMetric label="Nearest unit" value={nullableNumberText(target.distanceToClosestUnit)} />
      </div>
    </article>
  );
}

function StrategistCitySnapshotsSection({
  title,
  cities,
}: {
  title: string;
  cities: Record<string, unknown>[];
}) {
  return (
    <Card title={title} subtitle="All current cities shared with the strategist in compact form.">
      {cities.length ? (
        <div className="structured-list structured-list-grid">
          {cities.map((city, index) => {
            const projectOptions = objectArray(city.projectOptions);
            const optionLabels = projectOptions.map((option) => formatStrategistProjectOption(option)).filter(Boolean);
            const signals = stringList(city.signals);
            const needsProjectChoice = stringValue(city.projectStatus) === "needs_choice";

            return (
              <article key={`${stringValue(city.name)}-${index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(city.name) || "Unnamed city"}</strong>
                  <div className="tag-list compact">
                    <span className="tag neutral">({formatNumber(numberValue(city.x))}, {formatNumber(numberValue(city.y))})</span>
                    <span className="tag neutral">Pop {formatNumber(numberValue(city.population))}</span>
                    <span className="tag neutral">
                      {needsProjectChoice ? "Needs project choice" : stringValue(city.currentProject) || "Needs project"}
                    </span>
                  </div>
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Food" value={formatNumber(numberValue(city.foodPerTurn))} />
                  <MiniMetric label="Prod" value={formatNumber(numberValue(city.productionPerTurn))} />
                  <MiniMetric label="Growth" value={nullableNumberText(city.turnsToGrowth)} />
                  <MiniMetric label="Focus" value={stringValue(city.focus)} />
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Project turns" value={nullableNumberText(city.projectTurnsLeft)} />
                  <MiniMetric label="Invested" value={nullableNumberText(city.projectProductionInvested)} />
                  <MiniMetric label="Remaining" value={nullableNumberText(city.projectProductionRemaining)} />
                  <MiniMetric label="Threats" value={`${formatNumber(numberValue(city.nearbyHostileUnits))}u · ${formatNumber(numberValue(city.nearbyHostileCities))}c`} />
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Nearest rival city" value={nullableNumberText(city.distanceToNearestRivalCity)} />
                  <MiniMetric label="Nearest rival capital" value={nullableNumberText(city.distanceToNearestRivalCapital)} />
                </div>

                {signals.length ? (
                  <>
                    <SectionLabel text="Signals" />
                    <TagList values={signals.slice(0, 5)} />
                  </>
                ) : null}

                {optionLabels.length ? (
                  <>
                    <SectionLabel text={needsProjectChoice ? "Available project choices" : "Top project alternatives"} />
                    <TagList values={optionLabels} tone="accent" />
                  </>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <EmptyCardState title="No strategist city snapshots" body="No city snapshots were captured for the strategist on this turn." />
      )}
    </Card>
  );
}

function StrategistUnitSnapshotsSection({
  title,
  units,
}: {
  title: string;
  units: Record<string, unknown>[];
}) {
  return (
    <Card title={title} subtitle="All current units shared with the strategist in compact form.">
      {units.length ? (
        <div className="structured-list structured-list-grid">
          {units.map((unit, index) => {
            const progress = asRecord(unit.assignmentProgress);
            const signals = [...stringList(unit.reasons), ...stringList(unit.localFacts)].slice(0, 6);

            return (
              <article key={`${stringValue(unit.name)}-${numberValue(unit.id) ?? index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(unit.name) || "Unnamed unit"}</strong>
                  <div className="tag-list compact">
                    <span className="tag neutral">({formatNumber(numberValue(unit.x))}, {formatNumber(numberValue(unit.y))})</span>
                    <span className="tag neutral">{stringValue(unit.role) || "unit"}</span>
                    <span className="tag neutral">{stringValue(unit.detailLevel) || "compact"}</span>
                    <span className="tag neutral">{stringValue(unit.movementPoints) || "0/0"}</span>
                  </div>
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="HP" value={formatNumber(numberValue(unit.health))} />
                  <MiniMetric label="Strength" value={nullableNumberText(unit.strength)} />
                  <MiniMetric label="Ranged" value={nullableNumberText(unit.rangedStrength)} />
                  <MiniMetric label="Range" value={nullableNumberText(unit.range)} />
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Has move" value={booleanText(unit.hasMovement)} />
                  <MiniMetric label="Hostile units" value={formatNumber(numberValue(unit.nearbyHostileUnits))} />
                  <MiniMetric label="Hostile cities" value={formatNumber(numberValue(unit.nearbyHostileCities))} />
                  <MiniMetric label="Assigned" value={progress ? "yes" : "no"} />
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Nearest rival city" value={nullableNumberText(unit.distanceToNearestRivalCity)} />
                  <MiniMetric label="Nearest rival capital" value={nullableNumberText(unit.distanceToNearestRivalCapital)} />
                </div>

                {progress ? (
                  <div className="tag-list compact">
                    <span className="tag neutral">{humanizeKey(stringValue(progress.role) || "assignment")}</span>
                    <span className="tag neutral">{humanizeKey(stringValue(progress.status) || "unknown")}</span>
                    <span className="tag neutral">Switch {humanizeKey(stringValue(progress.switchCost) || "unknown")}</span>
                  </div>
                ) : null}

                {progress ? <p className="card-paragraph">{stringValue(progress.progressNote)}</p> : null}

                {signals.length ? (
                  <>
                    <SectionLabel text="Signals" />
                    <TagList values={signals} />
                  </>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <EmptyCardState title="No strategist unit snapshots" body="No unit snapshots were captured for the strategist on this turn." />
      )}
    </Card>
  );
}

function ThreatHighlightsSection({
  title,
  threats,
  embedded = false,
}: {
  title: string;
  threats: Record<string, unknown>[];
  embedded?: boolean;
}) {
  const content = threats.length ? (
    <div className={`structured-list${embedded ? "" : " structured-list-grid"}`}>
      {threats.map((threat, index) => (
        <article key={`${stringValue(threat.civName)}-${stringValue(threat.name)}-${index}`} className="structured-item">
          <div className="structured-item-header">
            <strong>{stringValue(threat.civName) || "Unknown rival"}</strong>
            <div className="tag-list compact">
              {stringValue(threat.threatLevel) ? (
                <StatusPill tone={toneFromSeverity(stringValue(threat.threatLevel))}>
                  {stringValue(threat.threatLevel)}
                </StatusPill>
              ) : null}
              {stringValue(threat.likelyVictoryType) ? <span className="tag warning">{stringValue(threat.likelyVictoryType)}</span> : null}
              {stringValue(threat.kind) ? <span className="tag warning">{stringValue(threat.kind)}</span> : null}
              {stringValue(threat.focus) ? <span className="tag neutral">{stringValue(threat.focus)}</span> : null}
              {stringValue(threat.relation) ? <span className="tag neutral">{stringValue(threat.relation)}</span> : null}
            </div>
          </div>
          {hasThreatMetrics(threat) ? (
            <div className="mini-metric-grid">
              <MiniMetric label="Tech delta" value={signedNumberText(threat.technologyDeltaVsUs)} />
              <MiniMetric label="Score delta" value={signedNumberText(threat.scoreDeltaVsUs)} />
              <MiniMetric label="Force delta" value={signedNumberText(threat.forceDeltaVsUs)} />
              <MiniMetric label="Milestones" value={`${formatNumber(numberValue(threat.completedMilestones) ?? 0)}/${formatNumber(numberValue(threat.totalMilestones) ?? 0)}`} />
            </div>
          ) : (
            <div className="mini-metric-grid">
              <MiniMetric label="Target" value={stringValue(threat.name) || "Unknown"} />
              <MiniMetric label="Health" value={nullableNumberText(threat.health)} />
              <MiniMetric label="Combat" value={nullableNumberText(threat.combatStrength)} />
              <MiniMetric label="Distance" value={nullableNumberText(threat.distanceToClosestUnit)} />
            </div>
          )}
          {stringValue(threat.nextMilestone) ? <p className="card-paragraph">Next milestone: {stringValue(threat.nextMilestone)}</p> : null}
          <p className="card-paragraph">{stringValue(threat.detail) || stringList(threat.facts).join(" · ") || "No additional threat detail recorded."}</p>
        </article>
      ))}
    </div>
  ) : (
    <EmptyCardState title="No threat highlights" body="No visible rivals, hostile units, or targetable cities were lifted into this turn's tactical brief." />
  );

  if (embedded) {
    return (
      <>
        <SectionLabel text={title} />
        {content}
      </>
    );
  }

  return <Card title={title}>{content}</Card>;
}

function SectionIntro({
  eyebrow,
  title,
  body,
}: {
  eyebrow: string;
  title: string;
  body: string;
}) {
  return (
    <div className="section-intro">
      <p className="eyebrow">{eyebrow}</p>
      <h2>{title}</h2>
      <p>{body}</p>
    </div>
  );
}

function SectionShell({
  id,
  eyebrow,
  title,
  body,
  children,
}: {
  id: string;
  eyebrow: string;
  title: string;
  body: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} className="section-shell">
      <SectionIntro eyebrow={eyebrow} title={title} body={body} />
      <div className="section-shell-body">{children}</div>
    </section>
  );
}

function TurnSectionNav({
  items,
}: {
  items: Array<{ id: string; label: string }>;
}) {
  return (
    <nav className="turn-section-nav" aria-label="Turn sections">
      {items.map((item) => (
        <a key={item.id} href={`#${item.id}`} className="turn-section-link">
          {item.label}
        </a>
      ))}
    </nav>
  );
}

function buildWorldFactsViewModel({
  civName,
  worldFacts,
  worldFactsCivs,
}: {
  civName: string;
  worldFacts: Record<string, unknown> | null;
  worldFactsCivs: Record<string, unknown>[];
}): WorldFactsViewModel | null {
  if (!worldFactsCivs.length) return null;

  const exactFactsByCiv = new Map<string, Record<string, unknown>>();
  for (const fact of worldFactsCivs) {
    const name = stringValue(fact.civName);
    if (name) exactFactsByCiv.set(name, fact);
  }

  const columns = worldFactsCivs
    .slice()
    .sort((left, right) => compareWorldFactsColumns(left, right, civName))
    .map((fact) => ({
      civName: stringValue(fact.civName),
      subtitle: describeWorldFactsColumn(fact, stringValue(fact.civName) === civName),
      emphasis: stringValue(fact.civName) === civName,
    }))
    .filter((column) => column.civName);

  if (!columns.length) return null;

  const selfFacts = exactFactsByCiv.get(civName);
  const selfScore = numberValue(selfFacts?.score);
  const selfForce = numberValue(selfFacts?.force);
  const selfTech = numberValue(selfFacts?.technologies);
  const snapshotTurn = numberValue(worldFacts?.turn);
  const scoreLeader = worldFactsLeader(worldFactsCivs, "score");
  const forceLeader = worldFactsLeader(worldFactsCivs, "force");
  const techLeader = worldFactsLeader(worldFactsCivs, "technologies");
  const scienceLeader = worldFactsLeader(worldFactsCivs, "sciencePerTurn");
  const cityLeader = worldFactsLeader(worldFactsCivs, "cities");

  const overview = [
    { label: "Civs compared", value: formatNumber(columns.length) },
    {
      label: "Major civs",
      value: formatNumber(columns.filter((column) => booleanValue(exactFactsByCiv.get(column.civName)?.isMajorCiv)).length),
    },
    {
      label: "City-states",
      value: formatNumber(columns.filter((column) => booleanValue(exactFactsByCiv.get(column.civName)?.isCityState)).length),
    },
    {
      label: "Score leader",
      value: scoreLeader ? `${stringValue(scoreLeader.civName)} · ${formatNumber(numberValue(scoreLeader.score))}` : "—",
    },
    {
      label: "Force leader",
      value: forceLeader ? `${stringValue(forceLeader.civName)} · ${formatNumber(numberValue(forceLeader.force))}` : "—",
    },
    {
      label: "Tech leader",
      value: techLeader ? `${stringValue(techLeader.civName)} · ${formatNumber(numberValue(techLeader.technologies))}` : "—",
    },
    {
      label: "Science leader",
      value: scienceLeader ? `${stringValue(scienceLeader.civName)} · ${formatNumber(numberValue(scienceLeader.sciencePerTurn))}` : "—",
    },
    {
      label: "City leader",
      value: cityLeader ? `${stringValue(cityLeader.civName)} · ${formatNumber(numberValue(cityLeader.cities))}` : "—",
    },
    {
      label: "Snapshot turn",
      value: snapshotTurn === null ? "—" : formatNumber(snapshotTurn),
    },
  ];

  const exactNumberRow = (label: string, field: string): WorldFactsRow => ({
    label,
    values: columns.map((column) => {
      const exact = exactFactsByCiv.get(column.civName);
      const value = numberValue(exact?.[field]);
      return worldFactsCell(value === null ? "—" : formatNumber(value), value === null ? "not logged" : "exact", column.civName === civName ? "accent" : "neutral");
    }),
  });

  const exactTextRow = (label: string, field: string): WorldFactsRow => ({
    label,
    values: columns.map((column) => {
      const exact = exactFactsByCiv.get(column.civName);
      const value = stringValue(exact?.[field]);
      return worldFactsCell(value || "—", value ? "exact" : "not logged", column.civName === civName ? "accent" : "neutral");
    }),
  });

  const deltaRow = (label: string, field: "score" | "force" | "technologies"): WorldFactsRow => ({
    label,
    values: columns.map((column) => {
      if (column.civName === civName) return worldFactsCell("0", "baseline", "accent");
      const exact = exactFactsByCiv.get(column.civName);
      const value = numberValue(exact?.[field]);
      const exactDelta = (() => {
        if (value === null) return null;
        if (field === "score" && selfScore !== null) return value - selfScore;
        if (field === "force" && selfForce !== null) return value - selfForce;
        if (field === "technologies" && selfTech !== null) return value - selfTech;
        return null;
      })();
      return worldFactsCell(
        exactDelta === null ? "—" : signedNumberText(exactDelta),
        exactDelta !== null ? "exact delta" : "unavailable",
        deltaTone(exactDelta),
      );
    }),
  });

  const matrices: WorldFactsMatrix[] = [
    {
      title: "Standings",
      subtitle: "Exact empire ranking and relative gaps against your current civilization.",
      rows: [
        exactNumberRow("Score", "score"),
        exactNumberRow("Force", "force"),
        exactNumberRow("Technologies", "technologies"),
        deltaRow("Score gap vs us", "score"),
        deltaRow("Force gap vs us", "force"),
        deltaRow("Tech gap vs us", "technologies"),
      ],
    },
    {
      title: "Empire size and military",
      subtitle: "Exact structural stats for each civilization in this world snapshot.",
      rows: [
        exactTextRow("Capital", "capitalName"),
        exactNumberRow("Cities", "cities"),
        exactNumberRow("Population", "population"),
        exactNumberRow("Units", "units"),
        exactNumberRow("Military units", "militaryUnits"),
        exactNumberRow("Civilian units", "civilianUnits"),
      ],
    },
    {
      title: "Economy and research",
      subtitle: "Exact current macro state per civilization from the world snapshot.",
      rows: [
        exactNumberRow("Gold", "gold"),
        exactNumberRow("Happiness", "happiness"),
        exactNumberRow("Science / turn", "sciencePerTurn"),
        exactNumberRow("Culture / turn", "culturePerTurn"),
        exactNumberRow("Faith / turn", "faithPerTurn"),
        exactTextRow("Current research", "currentResearch"),
        exactNumberRow("Research turns left", "currentResearchTurnsLeft"),
        {
          label: "Relation",
          values: columns.map((column) => {
            const exact = exactFactsByCiv.get(column.civName);
            return worldFactsCell(stringValue(exact?.relation) || (column.civName === civName ? "self" : "foreign"), "world facts", column.civName === civName ? "accent" : "neutral");
          }),
        },
        {
          label: "At war with us",
          values: columns.map((column) => {
            const exact = exactFactsByCiv.get(column.civName);
            const atWar = column.civName === civName ? false : booleanValue(exact?.isAtWarWithUs);
            return worldFactsCell(atWar ? "yes" : "no", "world facts", atWar ? "warning" : column.civName === civName ? "accent" : "neutral");
          }),
        },
      ],
    },
  ];

  const notes = [
    "This modal is a pure dashboard god view: the numbers here are exact world-state values captured only for inspection and are not part of the agent's prompt.",
    "Columns are real existing civilizations from the logged world snapshot; if a civilization is absent here, it was not present in that snapshot.",
    "Positive gap rows mean that civilization is ahead of you in that metric; negative means you are ahead.",
  ];

  return {
    columns,
    overview,
    matrices,
    notes,
  };
}

function buildMetricTimeline(turns: TurnRecord[], metricKey: WorldFactsMetricKey): ScoreTimelineView | null {
  const snapshots = new Map<number, Record<string, unknown>>();

  for (const turn of turns) {
    const worldFacts = asRecord(turn.worldFacts);
    const snapshotTurn = numberValue(worldFacts?.turn);
    const civs = objectArray(worldFacts?.civs);
    if (snapshotTurn === null || !civs.length) continue;
    if (!snapshots.has(snapshotTurn)) {
      snapshots.set(snapshotTurn, worldFacts);
    }
  }

  const orderedSnapshots = Array.from(snapshots.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([, snapshot]) => snapshot);

  if (!orderedSnapshots.length) return null;

  const metricLabel = WORLD_FACTS_METRIC_OPTIONS.find((option) => option.key === metricKey)?.label ?? metricKey;
  const colors = ["#1d7cf2", "#15c7b8", "#ff8f4d", "#ef5a72", "#7f69f6", "#2f9b5f", "#d18b00", "#7a5a46"];
  const seriesMap = new Map<string, ScoreTimelineSeriesPoint[]>();

  for (const snapshot of orderedSnapshots) {
    const turn = numberValue(snapshot.turn);
    const civs = objectArray(snapshot.civs);
    if (turn === null) continue;
    for (const civ of civs) {
      const civName = stringValue(civ.civName);
      const value = numberValue(civ[metricKey]);
      if (!civName || value === null) continue;
      const series = seriesMap.get(civName) ?? [];
      if (series[series.length - 1]?.turn !== turn) {
        series.push({ turn, value });
      }
      seriesMap.set(civName, series);
    }
  }

  const series = Array.from(seriesMap.entries())
    .map(([civName, points], index) => ({
      civName,
      color: colors[index % colors.length],
      points,
      latestScore: points[points.length - 1]?.value ?? 0,
    }))
    .filter((entry) => entry.points.length >= 1)
    .sort((left, right) => right.latestScore - left.latestScore);

  if (!series.length) return null;

  const turnsCovered = series.flatMap((entry) => entry.points.map((point) => point.turn));
  const values = series.flatMap((entry) => entry.points.map((point) => point.value));

  return {
    metricKey,
    metricLabel,
    series,
    minTurn: Math.min(...turnsCovered),
    maxTurn: Math.max(...turnsCovered),
    minValue: Math.min(...values),
    maxValue: Math.max(...values),
  };
}

function buildLinePath(
  points: ScoreTimelineSeriesPoint[],
  xForTurn: (turn: number) => number,
  yForScore: (score: number) => number,
) {
  return points
    .map((point, index) => `${index === 0 ? "M" : "L"} ${xForTurn(point.turn).toFixed(2)} ${yForScore(point.value).toFixed(2)}`)
    .join(" ");
}

function buildNiceNumericAxis(min: number, max: number, maxTickCount: number) {
  if (!Number.isFinite(min) || !Number.isFinite(max)) {
    return { min: 0, max: 1, ticks: [0, 1] };
  }

  if (min === max) {
    if (min === 0) return { min: 0, max: 1, ticks: [0, 1] };
    const constantMin = min > 0 ? 0 : min;
    const spacing = niceNumber(Math.abs(min) / Math.max(1, maxTickCount - 1), true) || 1;
    const constantMax = min > 0 ? Math.ceil(min / spacing) * spacing : constantMin + spacing * 2;
    const ticks = [];
    for (let value = constantMin; value <= constantMax + spacing / 2; value += spacing) {
      ticks.push(roundAxisValue(value));
    }
    return { min: constantMin, max: constantMax, ticks };
  }

  const preferZeroFloor = min >= 0;
  const preferZeroCeiling = max <= 0;
  const boundedMin = preferZeroFloor ? 0 : min;
  const boundedMax = preferZeroCeiling ? 0 : max;
  const roughRange = Math.max(Math.abs(boundedMax - boundedMin), Number.EPSILON);
  const spacing = niceNumber(roughRange / Math.max(1, maxTickCount - 1), true);
  const axisMin = preferZeroFloor ? 0 : Math.floor(boundedMin / spacing) * spacing;
  const axisMax = preferZeroCeiling ? 0 : Math.ceil(boundedMax / spacing) * spacing;
  const ticks = [];

  for (let value = axisMin; value <= axisMax + spacing / 2; value += spacing) {
    ticks.push(roundAxisValue(value));
  }

  return {
    min: roundAxisValue(axisMin),
    max: roundAxisValue(axisMax <= axisMin ? axisMin + spacing : axisMax),
    ticks,
  };
}

function niceNumber(value: number, round: boolean) {
  if (value <= 0 || !Number.isFinite(value)) return 1;
  const exponent = Math.floor(Math.log10(value));
  const fraction = value / 10 ** exponent;
  let niceFraction = 1;

  if (round) {
    if (fraction < 1.5) niceFraction = 1;
    else if (fraction < 3) niceFraction = 2;
    else if (fraction < 7) niceFraction = 5;
    else niceFraction = 10;
  } else {
    if (fraction <= 1) niceFraction = 1;
    else if (fraction <= 2) niceFraction = 2;
    else if (fraction <= 5) niceFraction = 5;
    else niceFraction = 10;
  }

  return niceFraction * 10 ** exponent;
}

function roundAxisValue(value: number) {
  if (Math.abs(value) >= 1) return Math.round(value * 1000) / 1000;
  return Math.round(value * 1000000) / 1000000;
}

function formatAxisValue(value: number) {
  if (Number.isInteger(value)) return formatNumber(value);
  return value.toFixed(Math.abs(value) >= 10 ? 1 : 2).replace(/\.?0+$/, "");
}

function buildIntegerTicks(min: number, max: number, steps: number) {
  if (min === max) return [min];
  const values = new Set<number>();
  for (let index = 0; index <= steps; index += 1) {
    values.add(Math.round(min + ((max - min) * index) / steps));
  }
  return Array.from(values).sort((left, right) => left - right);
}

function worldFactsCell(primary: string, secondary?: string, tone: WorldFactsCellTone = "neutral"): WorldFactsCell {
  return { primary, secondary, tone };
}

function deltaTone(value: number | null): WorldFactsCellTone {
  if (value === null) return "neutral";
  if (value > 0) return "warning";
  if (value < 0) return "accent";
  return "neutral";
}

function compareWorldFactsColumns(left: Record<string, unknown>, right: Record<string, unknown>, selfCivName: string) {
  const leftName = stringValue(left.civName);
  const rightName = stringValue(right.civName);
  if (leftName === selfCivName && rightName !== selfCivName) return -1;
  if (rightName === selfCivName && leftName !== selfCivName) return 1;
  const leftMajor = booleanValue(left.isMajorCiv);
  const rightMajor = booleanValue(right.isMajorCiv);
  if (leftMajor !== rightMajor) return leftMajor ? -1 : 1;
  const leftScore = numberValue(left.score) ?? -1;
  const rightScore = numberValue(right.score) ?? -1;
  if (leftScore !== rightScore) return rightScore - leftScore;
  return leftName.localeCompare(rightName);
}

function worldFactsLeader(civs: Record<string, unknown>[], field: string) {
  return civs
    .filter((civ) => numberValue(civ[field]) !== null)
    .sort((left, right) => (numberValue(right[field]) ?? -1) - (numberValue(left[field]) ?? -1))[0] ?? null;
}

function describeWorldFactsColumn(exact: Record<string, unknown> | undefined, isSelf: boolean) {
  if (isSelf) return "you";
  if (!exact) return "foreign";
  if (booleanValue(exact.isCityState)) return "city-state";
  if (booleanValue(exact.isAtWarWithUs)) return "at war";
  return stringValue(exact.relation) || "foreign";
}

function StoryStage({
  title,
  subtitle,
  detail,
}: {
  title: string;
  subtitle: string;
  detail: string;
}) {
  return (
    <article className="story-stage">
      <span className="story-stage-title">{title}</span>
      <strong>{subtitle}</strong>
      <p>{detail}</p>
    </article>
  );
}

function PerceptionCoverageSection({
  observation,
  perceptionSummary,
  attentionFacts,
  cityHighlights,
  unitHighlights,
  threatHighlights,
  hiddenCities,
  hiddenUnits,
  hiddenThreats,
  suppressedContext,
}: {
  observation: Record<string, unknown> | null;
  perceptionSummary: Record<string, unknown> | null;
  attentionFacts: Record<string, unknown>[];
  cityHighlights: Record<string, unknown>[];
  unitHighlights: Record<string, unknown>[];
  threatHighlights: Record<string, unknown>[];
  hiddenCities: number;
  hiddenUnits: number;
  hiddenThreats: number;
  suppressedContext: string[];
}) {
  return (
    <Card title="Perception coverage" subtitle="How full observation was compressed before the tactical model planned the turn.">
      {observation ? (
        <>
          <div className="summary-grid compact summary-grid-four">
            <SummaryStat
              label="World cities"
              value={formatNumber(numberValue(perceptionSummary?.totalCities))}
              note={`${formatNumber(cityHighlights.length)} surfaced in brief`}
            />
            <SummaryStat
              label="World units"
              value={formatNumber(numberValue(perceptionSummary?.totalUnits))}
              note={`${formatNumber(numberValue(perceptionSummary?.expandedUnits))} expanded · ${formatNumber(unitHighlights.length)} surfaced`}
            />
            <SummaryStat
              label="Visible threats"
              value={formatNumber(arrayLength(observation.visibleThreatsAndTargets))}
              note={`${formatNumber(threatHighlights.length)} surfaced`}
            />
            <SummaryStat
              label="Attention facts"
              value={formatNumber(attentionFacts.length)}
              note="Neutral reminders in the planner brief"
            />
          </div>

          <div className="summary-grid compact">
            <SummaryStat label="Hidden city cards" value={formatNumber(hiddenCities)} />
            <SummaryStat label="Hidden unit cards" value={formatNumber(hiddenUnits)} />
            <SummaryStat label="Hidden threat cards" value={formatNumber(hiddenThreats)} />
            <SummaryStat label="Suppressed notes" value={formatNumber(suppressedContext.length)} />
          </div>

          <SectionLabel text="Suppressed context" />
          <TagList values={suppressedContext} />
        </>
      ) : (
        <p className="muted-text">No tactical observation captured.</p>
      )}
    </Card>
  );
}

function EmpireChoicesSection({
  title,
  choices,
}: {
  title: string;
  choices: Record<string, unknown> | null;
}) {
  const sections = [
    { label: "Research", items: objectArray(choices?.researchChoices) },
    { label: "Policies", items: objectArray(choices?.policyChoices) },
    { label: "Macro", items: objectArray(choices?.macroChoices) },
    { label: "Diplomacy", items: objectArray(choices?.diplomacyChoices) },
  ].filter((section) => section.items.length);

  return (
    <Card title={title} subtitle="Empire-level options the tactical planner could choose from this turn.">
      {sections.length ? (
        <div className="structured-list">
          {sections.map((section) => (
            <article key={section.label} className="structured-item">
              <div className="structured-item-header">
                <strong>{section.label}</strong>
                <span className="tag neutral">{formatNumber(section.items.length)} surfaced</span>
              </div>
              <TagList
                values={section.items.slice(0, 5).map((item) => stringValue(item.title)).filter(Boolean)}
                tone="accent"
              />
            </article>
          ))}
        </div>
      ) : (
        <EmptyCardState title="No empire choices" body="The tactical brief did not expose any research, policy, macro, or diplomacy choices for this turn." />
      )}
    </Card>
  );
}

function CityHighlightsSection({ title, cities }: { title: string; cities: Record<string, unknown>[] }) {
  return (
    <Card title={title}>
      {cities.length ? (
        <div className="structured-list structured-list-grid">
          {cities.map((city, index) => {
            const state = asRecord(city.state);
            const project = asRecord(city.project);
            const actions = asRecord(city.actions);
            const signals = stringList(city.signals);
            const projectChoices = objectArray(actions?.chooseProject).map(formatCityActionCandidateLabel).filter(Boolean);
            const purchaseChoices = objectArray(actions?.purchase).map(formatCityActionCandidateLabel).filter(Boolean);
            const tileChoices = objectArray(actions?.buyTile).map(formatCityActionCandidateLabel).filter(Boolean);
            const focusChoices = objectArray(actions?.focus).map(formatCityActionCandidateLabel).filter(Boolean);
            const growthChoices = objectArray(actions?.growthMode).map(formatCityActionCandidateLabel).filter(Boolean);
            const needsProjectChoice = stringValue(project?.status) === "needs_choice";

            return (
              <article key={`${stringValue(city.name)}-${index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(city.name) || "Unnamed city"}</strong>
                  <div className="tag-list compact">
                    <span className="tag neutral">({formatNumber(numberValue(city.x))}, {formatNumber(numberValue(city.y))})</span>
                    <span className="tag neutral">Pop {formatNumber(numberValue(state?.population))}</span>
                    <span className="tag neutral">
                      {needsProjectChoice ? "Needs project choice" : stringValue(project?.name) || "Needs project"}
                    </span>
                  </div>
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Food" value={formatNumber(numberValue(state?.foodPerTurn))} />
                  <MiniMetric label="Prod" value={formatNumber(numberValue(state?.productionPerTurn))} />
                  <MiniMetric label="Growth" value={nullableNumberText(state?.turnsToGrowth)} />
                  <MiniMetric label="Strength" value={formatNumber(numberValue(state?.cityStrength))} />
                </div>

                {project ? (
                  <>
                    <div className="mini-metric-grid">
                      <MiniMetric label="Status" value={humanizeKey(stringValue(project.status) || "unknown")} />
                      <MiniMetric label="Turns left" value={nullableNumberText(project.turnsLeft)} />
                      <MiniMetric label="Invested" value={nullableNumberText(project.productionInvested)} />
                      <MiniMetric label="Remaining" value={nullableNumberText(project.productionRemaining)} />
                    </div>
                    <p className="card-paragraph">{stringValue(project.note)}</p>
                  </>
                ) : null}

                {signals.length ? (
                  <>
                    <SectionLabel text="Signals" />
                    <TagList values={signals} />
                  </>
                ) : null}

                {projectChoices.length ? (
                  <>
                    <SectionLabel text={needsProjectChoice ? "Available project choices" : "Choose project"} />
                    <TagList values={needsProjectChoice ? projectChoices : projectChoices.slice(0, 4)} tone="accent" />
                  </>
                ) : null}

                {purchaseChoices.length ? (
                  <>
                    <SectionLabel text="Purchase" />
                    <TagList values={purchaseChoices.slice(0, 3)} />
                  </>
                ) : null}

                {tileChoices.length ? (
                  <>
                    <SectionLabel text="Buy tile" />
                    <TagList values={tileChoices.slice(0, 3)} />
                  </>
                ) : null}

                {focusChoices.length ? (
                  <>
                    <SectionLabel text="Focus" />
                    <TagList values={focusChoices.slice(0, 2)} />
                  </>
                ) : null}

                {growthChoices.length ? (
                  <>
                    <SectionLabel text="Growth mode" />
                    <TagList values={growthChoices.slice(0, 2)} />
                  </>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <EmptyCardState title="No surfaced city cards" body="No cities were lifted into the tactical brief for focused city planning on this turn." />
      )}
    </Card>
  );
}

function UnitHighlightsSection({ title, units }: { title: string; units: Record<string, unknown>[] }) {
  return (
    <Card title={title}>
      {units.length ? (
        <div className="structured-list structured-list-grid">
          {units.map((unit, index) => {
            const progress = asRecord(unit.assignmentProgress);
            const reasons = [...stringList(unit.reasons), ...stringList(unit.localFacts)].slice(0, 6);
            const candidates = [
              ...objectArray(unit.unitOptionCandidates).map((candidate) => stringValue(candidate.title)).filter(Boolean),
              ...objectArray(unit.legalActionCandidates).map((candidate) => stringValue(candidate.title)).filter(Boolean),
            ].slice(0, 5);

            return (
              <article key={`${stringValue(unit.name)}-${numberValue(unit.id) ?? index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(unit.name) || "Unnamed unit"}</strong>
                  <div className="tag-list compact">
                    <span className="tag neutral">({formatNumber(numberValue(unit.x))}, {formatNumber(numberValue(unit.y))})</span>
                    <span className="tag neutral">{stringValue(unit.role) || "unit"}</span>
                    <span className="tag neutral">HP {formatNumber(numberValue(unit.health))}</span>
                    <span className="tag neutral">{stringValue(unit.movementPoints) || "0/0"}</span>
                  </div>
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Strength" value={nullableNumberText(unit.strength)} />
                  <MiniMetric label="Ranged" value={nullableNumberText(unit.rangedStrength)} />
                  <MiniMetric label="Range" value={nullableNumberText(unit.range)} />
                  <MiniMetric label="Nearby hostiles" value={formatNumber(numberValue(unit.nearbyHostileUnits))} />
                </div>

                {progress ? (
                  <div className="tag-list compact">
                    <span className="tag neutral">{humanizeKey(stringValue(progress.role) || "assignment")}</span>
                    <span className="tag neutral">{humanizeKey(stringValue(progress.status) || "unknown")}</span>
                    <span className="tag neutral">Switch {humanizeKey(stringValue(progress.switchCost) || "unknown")}</span>
                  </div>
                ) : null}

                {progress ? <p className="card-paragraph">{stringValue(progress.progressNote)}</p> : null}

                {reasons.length ? (
                  <>
                    <SectionLabel text="Why it matters" />
                    <TagList values={reasons} />
                  </>
                ) : null}

                {candidates.length ? (
                  <>
                    <SectionLabel text="Best surfaced options" />
                    <TagList values={candidates} tone="accent" />
                  </>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <EmptyCardState title="No surfaced unit cards" body="No units were lifted into the tactical brief for focused unit planning on this turn." />
      )}
    </Card>
  );
}

function ActionPlanSection({
  title,
  actions,
  notes,
  candidateLookup,
}: {
  title: string;
  actions: Record<string, unknown>[];
  notes: string;
  candidateLookup: Record<string, CandidateLookupEntry>;
}) {
  return (
    <Card title={title} subtitle="Ordered actions exactly as the tactical planner returned them.">
      {notes ? <p className="card-paragraph">{notes}</p> : null}
      {actions.length ? (
        <div className="structured-list">
          {actions.map((action, index) => (
            <article key={`${stringValue(action.type)}-${index}`} className="structured-item">
              <div className="structured-item-header">
                <strong>{describeAction(action, candidateLookup)}</strong>
                <div className="tag-list compact">
                  <span className="tag neutral">{stringValue(action.type) || "action"}</span>
                  <span className="tag neutral">Priority {formatNumber(numberValue(action.priority) ?? 0)}</span>
                </div>
              </div>
              <p className="card-paragraph">{describeActionDetail(action, candidateLookup)}</p>
            </article>
          ))}
        </div>
      ) : (
        <EmptyCardState title="No parsed actions" body="The tactician did not return any structured actions for this turn." />
      )}
    </Card>
  );
}

function DomainSummarySection({ title, value }: { title: string; value: unknown }) {
  const record = asRecord(value);
  return (
    <Card title={title} subtitle="What validation or live execution says actually happened across domains.">
      {record ? (
        <div className="summary-grid compact">
          {Object.entries(record).map(([key, raw]) => (
            <SummaryStat key={key} label={humanizeKey(key)} value={summarizeUnknown(raw)} />
          ))}
        </div>
      ) : value ? (
        <pre>{JSON.stringify(value, null, 2)}</pre>
      ) : (
        <EmptyCardState title="No execution summary" body="No validation or live execution summary was recorded for this turn." />
      )}
    </Card>
  );
}

function AttemptLadderSection({
  attempts,
  validationFailures,
}: {
  attempts: TacticalAttemptView[];
  validationFailures: unknown;
}) {
  return (
    <Card title="Retry and validation ladder" subtitle="Each tactical attempt, the parsed plan it produced, and why the next retry was requested.">
      {attempts.length ? (
        <div className="structured-list">
          {attempts.map((attempt) => (
            <article key={`attempt-${attempt.attemptNumber}`} className="structured-item">
              <div className="structured-item-header">
                <strong>Attempt {formatNumber(attempt.attemptNumber)}</strong>
                <div className="tag-list compact">
                  <span className="tag neutral">{attempt.parsedPlan ? `${formatNumber(arrayLength(attempt.parsedPlan.actions))} actions` : "No parsed plan"}</span>
                  {attempt.validationFailure ? <StatusPill tone="warning">Retry requested</StatusPill> : <StatusPill tone="success">Final or clean pass</StatusPill>}
                </div>
              </div>

              <div className="mini-metric-grid">
                <MiniMetric label="Retry context" value={attempt.retryContext ? "present" : "none"} />
                <MiniMetric label="Provider retries" value={attempt.providerRetries.length ? formatNumber(attempt.providerRetries.length) : "0"} />
                <MiniMetric label="Prompt size" value={attempt.prompt ? `${formatNumber(attempt.prompt.length)} chars` : "—"} />
                <MiniMetric label="Brief JSON" value={attempt.plannerBriefJson ? "captured" : "—"} />
                <MiniMetric label="Raw response" value={attempt.rawResponse ? "captured" : "—"} />
                <MiniMetric label="Parsed plan JSON" value={attempt.parsedPlanJson ? "captured" : "—"} />
              </div>

              {attempt.parsedPlan ? (
                <p className="card-paragraph">
                  {stringValue(attempt.parsedPlan.notes) || "Parsed plan recorded without planner notes."}
                </p>
              ) : (
                <p className="card-paragraph">No parsed plan was decoded for this attempt.</p>
              )}

              {attempt.validationFailure ? (
                <details className="code-disclosure">
                  <summary>Why retry {formatNumber(attempt.attemptNumber + 1)} was requested</summary>
                  <pre>{attempt.validationFailure}</pre>
                </details>
              ) : null}

              {attempt.providerRetries.length ? (
                <details className="code-disclosure">
                  <summary>Provider retry timeline</summary>
                  <pre>{formatProviderRetryTimeline(attempt.providerRetries)}</pre>
                </details>
              ) : null}

              {attempt.rawResponse ? <CodeDisclosure title="Raw tactical response" content={attempt.rawResponse} /> : null}
              {attempt.parsedPlanJson ? <CodeDisclosure title="Parsed tactical plan JSON" content={attempt.parsedPlanJson} /> : null}

              {attempt.requestError ? (
                <details className="code-disclosure">
                  <summary>Final provider error</summary>
                  <pre>{attempt.requestError}</pre>
                </details>
              ) : null}
            </article>
          ))}
        </div>
      ) : (
        <EmptyCardState title="No tactical attempts" body="No tactical request/response attempts were captured for this turn." />
      )}

      {validationFailures ? (
        <details className="code-disclosure">
          <summary>Latest validation failure JSON</summary>
          <pre>{JSON.stringify(validationFailures, null, 2)}</pre>
        </details>
      ) : null}
    </Card>
  );
}

function LiteralArtifactsSection({
  memory,
  plannerBrief,
  strategistBrief,
  tacticalAttempts,
  strategistArtifacts,
}: {
  memory: unknown;
  plannerBrief: unknown;
  strategistBrief: unknown;
  tacticalAttempts: TacticalAttemptView[];
  strategistArtifacts: StrategistArtifactsView | null;
}) {
  const latestAttempt = tacticalAttempts[tacticalAttempts.length - 1] ?? null;

  return (
    <div className="panel-grid panel-grid-asymmetric">
      <Card title="Tactical prompt artifacts" subtitle="Exact payloads and prompt text from the most recent tactical request.">
        {latestAttempt ? (
          <>
            <CodeDisclosure title="Memory JSON" content={latestAttempt.memoryJson || JSON.stringify(memory, null, 2)} />
            <CodeDisclosure title="Planner Brief JSON" content={latestAttempt.plannerBriefJson || JSON.stringify(plannerBrief, null, 2)} />
            {latestAttempt.retryContext ? <CodeDisclosure title="Retry Context JSON" content={latestAttempt.retryContext} /> : null}
            {latestAttempt.rawResponse ? <CodeDisclosure title="Raw Tactical Response" content={latestAttempt.rawResponse} /> : null}
            {latestAttempt.parsedPlanJson ? <CodeDisclosure title="Parsed Tactical Plan JSON" content={latestAttempt.parsedPlanJson} /> : null}
            <CodeDisclosure title="Exact Tactical Prompt" content={latestAttempt.prompt} />
          </>
        ) : (
          <p className="muted-text">No tactical prompt artifacts were captured for this turn.</p>
        )}
      </Card>

      <Card title="Strategist prompt artifacts" subtitle="Exact strategist brief and prompt text when a strategist pass happened on this turn.">
        {strategistArtifacts ? (
          <>
            <div className="summary-grid compact">
              <SummaryStat label="Strategist pass on this turn" value="Yes" />
              <SummaryStat label="Raw response" value={strategistArtifacts.rawResponse ? "Captured" : "Missing"} />
              <SummaryStat label="Parsed plan JSON" value={strategistArtifacts.parsedPlanJson ? "Captured" : "Missing"} />
            </div>
            {strategistArtifacts.retryEvents.length ? (
              <CodeDisclosure title="Strategist provider retry timeline" content={formatProviderRetryTimeline(strategistArtifacts.retryEvents)} />
            ) : null}
            {strategistArtifacts.requestError ? <CodeDisclosure title="Strategist final provider error" content={strategistArtifacts.requestError} /> : null}
            <CodeDisclosure title="Strategist Brief JSON" content={strategistArtifacts.briefJson || JSON.stringify(strategistBrief, null, 2)} />
            {strategistArtifacts.refreshRequest ? <CodeDisclosure title="Refresh Request JSON" content={strategistArtifacts.refreshRequest} /> : null}
            {strategistArtifacts.rawResponse ? <CodeDisclosure title="Raw Strategist Response" content={strategistArtifacts.rawResponse} /> : null}
            {strategistArtifacts.parsedPlanJson ? <CodeDisclosure title="Parsed Strategist Plan JSON" content={strategistArtifacts.parsedPlanJson} /> : null}
            <CodeDisclosure title="Exact Strategist Prompt" content={strategistArtifacts.prompt} />
          </>
        ) : (
          <>
            <div className="summary-grid compact">
              <SummaryStat label="Strategist pass on this turn" value="No" />
            </div>
            <p className="muted-text">No strategist prompt ran on this turn. Any strategist memo shown elsewhere on the page is carried forward from earlier turns.</p>
          </>
        )}
      </Card>
    </div>
  );
}

function CodeDisclosure({ title, content }: { title: string; content: string }) {
  if (!content) return null;
  const formatted = useMemo(() => formatLiteralContent(title, content), [title, content]);
  const [wrapped, setWrapped] = useState(true);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return undefined;
    const timeout = window.setTimeout(() => setCopied(false), 1400);
    return () => window.clearTimeout(timeout);
  }, [copied]);

  const handleCopy = async () => {
    if (!navigator.clipboard) return;
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <details className="code-disclosure">
      <summary>{title}</summary>
      <div className="code-disclosure-toolbar">
        <div className="code-disclosure-meta">
          <span className="tag neutral">{formatted.formatLabel}</span>
          <span className="tag neutral">{formatLiteralLineCount(formatted.display)}</span>
          {formatted.isFormatted ? <span className="tag accent">formatted for readability</span> : null}
        </div>
        <div className="code-disclosure-actions">
          <button
            type="button"
            className={`toggle-chip ${wrapped ? "selected" : ""}`}
            onClick={() => setWrapped((value) => !value)}
          >
            {wrapped ? "Wrapped" : "Raw width"}
          </button>
          <button type="button" className="toggle-chip" onClick={handleCopy}>
            {copied ? "Copied exact" : "Copy exact"}
          </button>
        </div>
      </div>
      <div className={`code-disclosure-body ${wrapped ? "wrapped" : "scrollable"}`}>
        <pre>
          <code>{formatted.display}</code>
        </pre>
      </div>
    </details>
  );
}

function MiniMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="mini-metric">
      <span>{label}</span>
      <strong>{value || "—"}</strong>
    </div>
  );
}

function ChoiceGroup({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="choice-group">
      <p>{label}</p>
      <div className="choice-grid">
        {options.map((option) => (
          <button
            key={option}
            type="button"
            className={`choice-chip ${option === value ? "selected" : ""}`}
            onClick={() => onChange(option)}
          >
            {option}
          </button>
        ))}
      </div>
    </div>
  );
}

function Toggle({
  checked,
  onChange,
  label,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
}) {
  return (
    <button type="button" className={`toggle-chip ${checked ? "selected" : ""}`} onClick={() => onChange(!checked)}>
      <span className="toggle-dot" />
      {label}
    </button>
  );
}

function ToggleRow({ children }: { children: React.ReactNode }) {
  return <div className="toggle-row">{children}</div>;
}

function NumberField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
}) {
  return (
    <label>
      {label}
      <input
        type="number"
        value={Number.isFinite(value) ? value : 0}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </label>
  );
}

function EmptyState() {
  return (
    <Card title="No turn selected">
      <p className="muted-text">Launch a batch or open a replay to inspect the strategist and tactical turns.</p>
    </Card>
  );
}

function firstNonEmptyText(...values: Array<string | undefined | null>): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return "";
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function objectArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => !!item && typeof item === "object" && !Array.isArray(item))
    : [];
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function stringNumber(value: string | undefined): number | null {
  if (!value) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function numberValue(value: unknown): number | null {
  return typeof value === "number" ? value : null;
}

function formatLiteralContent(title: string, content: string): { display: string; formatLabel: string; isFormatted: boolean } {
  const trimmed = content.trim();
  const looksJson = title.toLowerCase().includes("json") || trimmed.startsWith("{") || trimmed.startsWith("[");
  if (looksJson) {
    try {
      const parsed = JSON.parse(content) as unknown;
      if (parsed !== null && typeof parsed === "object") {
        return {
          display: JSON.stringify(parsed, null, 2),
          formatLabel: "JSON",
          isFormatted: true,
        };
      }
    } catch {
      // Fall through to plain-text display when the payload is not valid JSON.
    }
  }

  return {
    display: content,
    formatLabel: looksJson ? "Text" : "Prompt / text",
    isFormatted: false,
  };
}

function formatLiteralLineCount(content: string): string {
  const lineCount = content.split("\n").length;
  return `${formatNumber(lineCount)} ${lineCount === 1 ? "line" : "lines"}`;
}

function deriveTurnMetrics(turn: TurnRecord) {
  const summary = turn.turnSummary;
  const tacticalLatencyMs = sumEventPairDurations(turn.events, "llm_request", "llm_response");
  const strategistLatencyMs = sumEventPairDurations(turn.events, "strategist_llm_request", "strategist_llm_response");
  const totalLatencyMs = (tacticalLatencyMs ?? 0) + (strategistLatencyMs ?? 0) || summary?.llmLatencyMs || 0;
  const plannedActions = summary?.plannedActions ?? countActions(turn.parsedPlan);
  const executedActions = summary?.executedActions ?? plannedActions;
  const executionRejectedActions = summary?.rejectedActions ?? 0;
  const fallback = summary?.fallback ?? turn.statusLabel.toLowerCase().includes("fallback");
  const blocked = summary?.blocked ?? turn.statusLabel.toLowerCase().includes("blocked");
  const retryCount = countEvents(turn.events, "plan_validation_failed");
  const validationFailureCount = countValidationFailures(turn.events);
  const tacticalPasses = countEvents(turn.events, "llm_request");
  const strategistPasses = countEvents(turn.events, "strategist_llm_request");
  const tacticalProviderRetryCount = countEvents(turn.events, "llm_retry_scheduled");
  const strategistProviderRetryCount = countEvents(turn.events, "strategist_llm_retry_scheduled");
  const providerRetryCount = tacticalProviderRetryCount + strategistProviderRetryCount;
  const executionRejectDenominator = Math.max(1, executedActions + executionRejectedActions);
  const intentionalNoOp = plannedActions === 0 && !fallback && !blocked;

  return {
    plannedActions,
    executedActions,
    executionRejectedActions,
    executionRejectRate: executionRejectedActions / executionRejectDenominator,
    fallback,
    blocked,
    retryCount,
    validationFailureCount,
    tacticalPasses,
    strategistPasses,
    tacticalProviderRetryCount,
    strategistProviderRetryCount,
    providerRetryCount,
    intentionalNoOp,
    cleanFirstPass: retryCount === 0 && !fallback && !blocked,
    illegalActionRate: summary?.illegalActionRate ?? 0,
    tacticalLatencyMs,
    strategistLatencyMs,
    totalLatencyMs: totalLatencyMs || null,
  };
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function arrayLength(value: unknown): number {
  return Array.isArray(value) ? value.length : 0;
}

function countActions(plan: Record<string, unknown> | null): number {
  return Array.isArray(plan?.actions) ? plan.actions.length : 0;
}

function countValidationFailures(events: TurnRecord["events"]): number {
  return events.reduce((sum, event) => {
    if (event.type !== "plan_validation_failed") return sum;
    const retryContext = parseJsonValue<Record<string, unknown>>(event.details?.validationFailuresJson);
    const failures = Array.isArray(retryContext?.failures) ? retryContext.failures.length : 0;
    return sum + Math.max(1, failures);
  }, 0);
}

function countEvents(events: TurnRecord["events"], type: string): number {
  return events.filter((event) => event.type === type).length;
}

function sumEventPairDurations(events: TurnRecord["events"], requestType: string, responseType: string): number | null {
  let pendingRequestEpochMs: number | null = null;
  let totalMs = 0;
  let pairs = 0;

  for (const event of events) {
    if (event.type === requestType) {
      pendingRequestEpochMs = event.epochMs;
      continue;
    }

    if (event.type === responseType && pendingRequestEpochMs !== null) {
      totalMs += Math.max(0, event.epochMs - pendingRequestEpochMs);
      pendingRequestEpochMs = null;
      pairs += 1;
    }
  }

  return pairs ? totalMs : null;
}

function toneFromSeverity(severity: string): "success" | "warning" | "muted" {
  const normalized = severity.toLowerCase();
  if (normalized === "critical" || normalized === "warning") return "warning";
  if (normalized === "good" || normalized === "success") return "success";
  return "muted";
}

function tagToneFromMemoValidity(status: string): "neutral" | "accent" | "warning" {
  const normalized = status.toLowerCase();
  if (normalized === "healthy") return "accent";
  if (normalized === "strained" || normalized === "contradicted") return "warning";
  return "neutral";
}

function nullableNumberText(value: unknown): string {
  const number = numberValue(value);
  return number === null ? "—" : formatNumber(number);
}

function formatCoordinateText(x: number | null, y: number | null): string {
  if (x === null || y === null) return "—";
  return `(${formatNumber(x)}, ${formatNumber(y)})`;
}

function formatMemoryNoteMeta(note: Record<string, unknown>): string {
  const parts: string[] = [];
  if (stringValue(note.civName)) parts.push(stringValue(note.civName));
  const coordinateText = formatCoordinateText(numberValue(note.x), numberValue(note.y));
  if (coordinateText !== "—") parts.push(coordinateText);
  if (numberValue(note.firstTurn) !== null) parts.push(`from turn ${formatNumber(numberValue(note.firstTurn))}`);
  if (numberValue(note.lastUpdatedTurn) !== null) parts.push(`updated ${formatNumber(numberValue(note.lastUpdatedTurn))}`);
  if (numberValue(note.staleAfterTurn) !== null) parts.push(`stale after ${formatNumber(numberValue(note.staleAfterTurn))}`);
  return parts.join(" · ");
}

function formatAnchorLocation(anchor: Record<string, unknown>): string {
  const civName = stringValue(anchor.civName);
  const coordinateText = formatCoordinateText(numberValue(anchor.x), numberValue(anchor.y));
  if (civName && coordinateText !== "—") return `${civName} ${coordinateText}`;
  if (civName) return civName;
  if (coordinateText !== "—") return coordinateText;
  return "";
}

function formatAnchorTurns(anchor: Record<string, unknown>): string {
  const parts: string[] = [];
  if (numberValue(anchor.firstSeenTurn) !== null) parts.push(`first seen ${formatNumber(numberValue(anchor.firstSeenTurn))}`);
  if (numberValue(anchor.lastConfirmedTurn) !== null) parts.push(`confirmed ${formatNumber(numberValue(anchor.lastConfirmedTurn))}`);
  return parts.join(" · ");
}

function formatDurationMs(value: number | null): string {
  if (value === null || value <= 0) return "—";
  if (value < 1000) return `${formatNumber(Math.round(value))} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

function formatPercent(value: number | null): string {
  if (value === null || Number.isNaN(value)) return "—";
  return `${(value * 100).toFixed(value >= 0.1 ? 0 : 1)}%`;
}

function buildLatencyNote(strategistLatencyMs: number | null, tacticalLatencyMs: number | null): string {
  const strategist = formatDurationMs(strategistLatencyMs);
  const tactical = formatDurationMs(tacticalLatencyMs);
  if (strategist === "—" && tactical === "—") return "";
  return `Strategist ${strategist} · Tactical ${tactical}`;
}

function describePlannerOutcome(metrics: ReturnType<typeof deriveTurnMetrics>): string {
  if (metrics.fallback) return "Fallback";
  if (metrics.blocked) return "Blocked";
  if (metrics.retryCount > 0) return `Replanned ×${formatNumber(metrics.retryCount)}`;
  if (metrics.intentionalNoOp) return "Intentional no-op";
  return "First pass";
}

function describePlannerOutcomeNote(metrics: ReturnType<typeof deriveTurnMetrics>): string {
  if (metrics.fallback) return "Agent handed the turn to legacy logic.";
  if (metrics.blocked) return "The turn could not be resolved cleanly.";
  if (metrics.retryCount > 0) {
    return `${formatNumber(metrics.validationFailureCount)} validation failures before the final plan stuck.`;
  }
  if (metrics.intentionalNoOp) return "The planner chose to preserve progress instead of acting.";
  return "No retry loop was needed.";
}

function signedNumberText(value: unknown): string {
  const number = numberValue(value);
  if (number === null) return "—";
  if (number > 0) return `+${formatNumber(number)}`;
  return formatNumber(number);
}

function hasThreatMetrics(threat: Record<string, unknown>): boolean {
  return (
    numberValue(threat.technologyDeltaVsUs) !== null ||
    numberValue(threat.scoreDeltaVsUs) !== null ||
    numberValue(threat.forceDeltaVsUs) !== null ||
    numberValue(threat.completedMilestones) !== null ||
    numberValue(threat.totalMilestones) !== null
  );
}

function humanizeKey(value: string): string {
  return value
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function summarizeUnknown(value: unknown): string {
  if (typeof value === "string") return value;
  if (typeof value === "number") return formatNumber(value);
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (Array.isArray(value)) return `${value.length} items`;
  if (value && typeof value === "object") return `${Object.keys(value).length} fields`;
  return "—";
}

function formatCityActionCandidateLabel(candidate: Record<string, unknown>): string {
  const title = stringValue(candidate.title) || stringValue(candidate.candidateId) || "Unnamed action";
  const estimatedTurns = numberValue(candidate.estimatedTurns);
  const goldCost = numberValue(candidate.goldCost);
  const parts = [title];
  if (estimatedTurns !== null) parts.push(`${formatNumber(estimatedTurns)}t`);
  if (goldCost !== null) parts.push(`${formatNumber(goldCost)}g`);
  return parts.join(" · ");
}

function formatStrategistProjectOption(option: Record<string, unknown>): string {
  const title = stringValue(option.title) || "Unnamed project";
  const estimatedTurns = numberValue(option.estimatedTurns);
  const goldCost = numberValue(option.goldCost);
  const yieldHints = stringList(option.yieldHints);
  const parts = [title];
  if (estimatedTurns !== null) parts.push(`${formatNumber(estimatedTurns)}t`);
  if (goldCost !== null) parts.push(`${formatNumber(goldCost)}g`);
  if (yieldHints.length) parts.push(yieldHints.slice(0, 2).join("/"));
  return parts.join(" · ");
}

function booleanText(value: unknown): string {
  return value === true ? "Yes" : value === false ? "No" : "—";
}

function booleanValue(value: unknown): boolean {
  return value === true;
}

type CandidateLookupEntry = {
  title: string;
  detail: string;
  source: string;
};

type TacticalAttemptView = {
  attemptNumber: number;
  prompt: string;
  memoryJson: string;
  plannerBriefJson: string;
  retryContext: string;
  rawResponse: string;
  parsedPlanJson: string;
  parsedPlan: Record<string, unknown> | null;
  validationFailure: string;
  providerRetries: ProviderRetryView[];
  requestError: string;
};

type StrategistArtifactsView = {
  prompt: string;
  briefJson: string;
  refreshRequest: string;
  rawResponse: string;
  parsedPlanJson: string;
  retryEvents: ProviderRetryView[];
  requestError: string;
};

type ProviderRetryView = {
  attempt: number;
  nextAttempt: number;
  maxAttempts: number;
  requestTimeoutMs: number | null;
  delayMs: number | null;
  reason: string;
  status: string;
  error: string;
};

function describeAction(action: Record<string, unknown>, candidateLookup: Record<string, CandidateLookupEntry>): string {
  const candidateId = stringValue(action.candidateId);
  const candidate = candidateId ? candidateLookup[candidateId] : undefined;

  switch (stringValue(action.type)) {
    case "select_empire_option":
      return candidate ? `Empire choice: ${candidate.title}` : `Select empire option ${candidateId}`;
    case "select_city_option":
      return candidate ? `City choice: ${candidate.title}` : `Select city option ${candidateId}`;
    case "select_unit_option":
      return candidate ? `Unit choice: ${candidate.title}` : `Select unit option ${candidateId}`;
    case "unit_move":
      return `Move unit ${formatNumber(numberValue(action.unitId))} to (${formatNumber(numberValue(action.destinationX))}, ${formatNumber(numberValue(action.destinationY))})`;
    case "unit_action":
      return `Use ${stringValue(action.actionType)} with unit ${formatNumber(numberValue(action.unitId))}`;
    case "end_turn":
      return "End the turn";
    default:
      return stringValue(action.type) || "Unknown action";
  }
}

function describeActionDetail(action: Record<string, unknown>, candidateLookup: Record<string, CandidateLookupEntry>): string {
  const candidateId = stringValue(action.candidateId);
  const candidate = candidateId ? candidateLookup[candidateId] : undefined;

  switch (stringValue(action.type)) {
    case "select_empire_option":
    case "select_city_option":
    case "select_unit_option":
      return candidate
        ? `${candidate.detail || "Chosen from the surfaced legal options."} Source: ${candidate.source}. Candidate ID: ${candidateId}.`
        : `Candidate ${candidateId} was chosen from the surfaced legal options.`;
    case "unit_move":
      return "Pure movement action selected for this unit.";
    case "unit_action":
      return "Direct unit command selected from current legal actions.";
    case "end_turn":
      return "Planner decided the turn could safely end after higher-priority actions.";
    default:
      return "Structured action chosen by the tactical planner.";
  }
}

function findLatestEvent(events: ObservabilityEvent[], types: string[]): ObservabilityEvent | null {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (types.includes(events[index].type)) return events[index];
  }
  return null;
}

function findEvents(events: ObservabilityEvent[], type: string): ObservabilityEvent[] {
  return events.filter((event) => event.type === type).sort((left, right) => left.id - right.id);
}

function buildCandidateLookup(
  empireChoices: Record<string, unknown> | null,
  cityHighlights: Record<string, unknown>[],
  unitHighlights: Record<string, unknown>[],
): Record<string, CandidateLookupEntry> {
  const lookup: Record<string, CandidateLookupEntry> = {};
  const empireSections = [
    ["researchChoices", "Research"],
    ["policyChoices", "Policy"],
    ["macroChoices", "Macro"],
    ["diplomacyChoices", "Diplomacy"],
  ] as const;

  for (const [key, label] of empireSections) {
    objectArray(empireChoices?.[key]).forEach((candidate) => {
      const candidateId = stringValue(candidate.candidateId);
      if (!candidateId) return;
      lookup[candidateId] = {
        title: stringValue(candidate.title) || candidateId,
        detail: stringValue(candidate.detail),
        source: `${label} candidate`,
      };
    });
  }

  cityHighlights.forEach((city) => {
    const cityName = stringValue(city.name) || "City";
    const actions = asRecord(city.actions);
    const sections = [
      ["chooseProject", "project"],
      ["purchase", "purchase"],
      ["buyTile", "tile"],
      ["focus", "focus"],
      ["growthMode", "growth"],
    ] as const;
    for (const [key, label] of sections) {
      objectArray(actions?.[key]).forEach((candidate) => {
        const candidateId = stringValue(candidate.candidateId);
        if (!candidateId) return;
        lookup[candidateId] = {
          title: stringValue(candidate.title) || candidateId,
          detail: stringValue(candidate.detail),
          source: `${cityName} ${label} candidate`,
        };
      });
    }
  });

  unitHighlights.forEach((unit) => {
    const unitName = stringValue(unit.name) || "Unit";
    const unitId = formatNumber(numberValue(unit.id));
    objectArray(unit.unitOptionCandidates).forEach((candidate) => {
      const candidateId = stringValue(candidate.candidateId);
      if (!candidateId) return;
      lookup[candidateId] = {
        title: stringValue(candidate.title) || candidateId,
        detail: stringValue(candidate.detail) || stringValue(candidate.rationale),
        source: `${unitName} #${unitId} unit option`,
      };
    });
  });

  return lookup;
}

function buildTacticalAttempts(events: ObservabilityEvent[]): TacticalAttemptView[] {
  const requests = findEvents(events, "llm_request");
  const parsedEvents = findEvents(events, "llm_plan_parsed");
  const validationEvents = findEvents(events, "plan_validation_failed");
  const responses = findEvents(events, "llm_response");
  const retryEvents = findEvents(events, "llm_retry_scheduled");
  const requestErrors = findEvents(events, "llm_request_error");

  return requests.map((request, index) => {
    const nextRequestId = requests[index + 1]?.id ?? Number.MAX_SAFE_INTEGER;
    const attemptNumber = (stringNumber(request.details?.retryAttempt) ?? 0) + 1;
    const parsed = parsedEvents.find((event) => event.id > request.id && event.id < nextRequestId);
    const validation = validationEvents.find((event) => event.id > request.id && event.id < nextRequestId);
    const response = responses.find((event) => event.id > request.id && event.id < nextRequestId);
    const providerRetries = retryEvents
      .filter((event) => event.id > request.id && event.id < nextRequestId)
      .map(buildProviderRetryView)
      .filter((value): value is ProviderRetryView => value !== null);
    const requestError = requestErrors.find((event) => event.id > request.id && event.id < nextRequestId);

    return {
      attemptNumber,
      prompt: request.details?.prompt ?? "",
      memoryJson: request.details?.memoryJson ?? "",
      plannerBriefJson: request.details?.plannerBriefJson ?? "",
      retryContext: request.details?.retryContextJson ?? "",
      rawResponse: response?.details?.rawResponse ?? "",
      parsedPlanJson: parsed?.details?.parsedPlan ?? "",
      parsedPlan: parseJsonValue(parsed?.details?.parsedPlan),
      validationFailure: validation?.details?.validationFailuresJson ?? "",
      providerRetries,
      requestError: requestError ? JSON.stringify(requestError.details ?? {}, null, 2) : "",
    };
  });
}

function buildStrategistArtifacts(events: ObservabilityEvent[]): StrategistArtifactsView | null {
  const request = findLatestEvent(events, ["strategist_llm_request"]);
  if (!request) return null;
  const response = events.find((event) => event.type === "strategist_llm_response" && event.id > request.id);
  const parsedPlan = events.find((event) => event.type === "strategist_llm_plan_parsed" && event.id > request.id);
  const retryEvents = events
    .filter((event) => event.type === "strategist_llm_retry_scheduled" && event.id > request.id)
    .map(buildProviderRetryView)
    .filter((value): value is ProviderRetryView => value !== null);
  const requestError = events.find((event) => event.type === "strategist_llm_request_error" && event.id > request.id);
  return {
    prompt: request.details?.prompt ?? "",
    briefJson: request.details?.strategistBriefJson ?? "",
    refreshRequest: request.details?.refreshRequestJson ?? "",
    rawResponse: response?.details?.rawResponse ?? "",
    parsedPlanJson: parsedPlan?.details?.parsedPlan ?? "",
    retryEvents,
    requestError: requestError ? JSON.stringify(requestError.details ?? {}, null, 2) : "",
  };
}

function buildProviderRetryView(event: ObservabilityEvent): ProviderRetryView | null {
  const details = event.details;
  if (!details) return null;
  return {
    attempt: stringNumber(details.attempt) ?? 0,
    nextAttempt: stringNumber(details.nextAttempt) ?? 0,
    maxAttempts: stringNumber(details.maxAttempts) ?? 0,
    requestTimeoutMs: stringNumber(details.requestTimeoutMs),
    delayMs: stringNumber(details.delayMs),
    reason: details.reason ?? "",
    status: details.status ?? "",
    error: details.error ?? "",
  };
}

function formatProviderRetryTimeline(retries: ProviderRetryView[]): string {
  return retries.map((retry) => {
    const parts = [
      `attempt ${formatNumber(retry.attempt)} -> ${formatNumber(retry.nextAttempt)} of ${formatNumber(retry.maxAttempts)}`,
    ];
    if (retry.reason) parts.push(`reason=${retry.reason}`);
    if (retry.status) parts.push(`status=${retry.status}`);
    if (retry.requestTimeoutMs !== null) parts.push(`timeout=${formatDurationMs(retry.requestTimeoutMs)}`);
    if (retry.delayMs !== null) parts.push(`delay=${formatDurationMs(retry.delayMs)}`);
    if (retry.error) parts.push(`error=${retry.error}`);
    return parts.join(" | ");
  }).join("\n");
}
