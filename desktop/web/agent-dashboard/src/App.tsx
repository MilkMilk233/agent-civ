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
import { formatNumber, formatRelative } from "./utils";

type Mode = "live" | "replay";

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
          <h1>7071 Dashboard</h1>
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
        <Card title="Runner status" subtitle={runnerStatus?.running ? "A batch is currently in progress." : "Ready for the next experiment."}>
          <div className="summary-grid">
            <SummaryStat label="Current state" value={runnerStatus?.running ? "Running" : "Idle"} />
            <SummaryStat label="Current batch" value={runnerStatus?.currentBatchName || runnerStatus?.currentBatchId || "None"} />
            <SummaryStat label="Last result" value={runnerStatus?.lastCompletedBatchStatus || "None"} />
            <SummaryStat label="Storage" value={runnerStatus?.storageDir || "Unavailable"} subtle />
          </div>
          <div className="button-row">
            <button onClick={() => void refreshRunner()}>Refresh status</button>
            <button onClick={() => void loadBatches()}>Refresh history</button>
            <button className="danger" disabled={busy || !runnerStatus?.running} onClick={() => void cancelBatch()}>
              Cancel running batch
            </button>
          </div>
        </Card>

        <Card title={mode === "live" ? "Live session" : "Replay session"} subtitle={mode === "live" ? "Current in-memory observability stream." : "Stored evaluations from disk."}>
          {mode === "live" ? (
            <div className="summary-grid">
              <SummaryStat label="Buffered events" value={formatNumber(snapshot?.totalBufferedEvents)} />
              <SummaryStat label="Visible turns" value={formatNumber(turns.length)} />
              <SummaryStat label="Last refresh" value={snapshot ? formatRelative(snapshot.generatedAtEpochMs) : "Not loaded"} />
              <SummaryStat label="Current civ" value={selectedTurn?.civName || "None"} />
            </div>
          ) : (
            <div className="summary-grid">
              <SummaryStat label="Selected batch" value={selectedBatch?.name || selectedBatch?.batchId || "None"} />
              <SummaryStat label="Selected match" value={selectedMatch?.label || selectedMatch?.matchId || "None"} />
              <SummaryStat label="Winner" value={selectedMatch?.winnerCivName || selectedMatch?.winnerSide || "Unknown"} />
              <SummaryStat label="Total turns" value={formatNumber(selectedMatch?.totalTurns)} />
            </div>
          )}
          {error ? <p className="error-text">{error}</p> : null}
        </Card>
      </section>

      <section className="workspace-grid">
        <aside className="control-rail">
          {mode === "replay" ? (
            <Card title="Replay picker" subtitle="Choose a batch and match, then inspect turns on the right.">
              <div className="form-stack">
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
                {selectedBatch ? (
                  <div className="mini-note">
                    {selectedBatch.baseRuleset} · {selectedBatch.mapSize} {selectedBatch.mapType} · max {selectedBatch.maxTurns} turns
                  </div>
                ) : null}
              </div>
            </Card>
          ) : (
            <Card title="Live feed" subtitle="Auto-refreshes every 5 seconds while you stay on the Live tab.">
              <div className="button-row">
                <button onClick={() => void loadSnapshot()}>Refresh snapshot now</button>
                <button onClick={() => setMode("replay")}>Open replay mode</button>
              </div>
              <div className="mini-note">
                Use replay mode when you want the full stored trace instead of the rolling in-memory window.
              </div>
            </Card>
          )}

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
                    <StatusPill tone={turn.statusTone}>{turn.statusLabel}</StatusPill>
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
                <select value={form.baseRuleset} onChange={(event) => onChange("baseRuleset", event.target.value)}>
                  {options.baseRulesets.map((ruleset) => (
                    <option key={ruleset} value={ruleset}>{ruleset}</option>
                  ))}
                </select>
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

function TurnDetail({ turn }: { turn: TurnRecord }) {
  const observation = asRecord(turn.observation);
  const empireObservation = asRecord(turn.empireObservation);
  const plannerBrief = asRecord(turn.plannerBrief);
  const roadmap = asRecord(turn.strategicRoadmap) ?? asRecord(turn.strategicPlan?.roadmap);
  const parsedPlan = asRecord(turn.parsedPlan);
  const empireSummary = asRecord(observation?.empireSummary);
  const doctrine = asRecord(plannerBrief?.doctrine);
  const criticalAlerts = objectArray(plannerBrief?.criticalAlerts ?? observation?.priorityFacts);
  const progressInMotion = objectArray(plannerBrief?.progressInMotion);
  const cityHighlights = objectArray(plannerBrief?.cityHighlights ?? observation?.citiesNeedingAttention);
  const unitHighlights = objectArray(plannerBrief?.unitHighlights ?? observation?.actionableUnits);
  const plannedActions = objectArray(parsedPlan?.actions);

  return (
    <div className="detail-stack">
      <Card
        title={`${turn.civName} · Turn ${turn.turn}`}
        subtitle={turn.turnSummary?.notes || turn.synopsis}
      >
        <div className="summary-grid">
          <SummaryStat label="Status" value={turn.statusLabel} />
          <SummaryStat label="Latest event" value={formatRelative(turn.latestEpochMs)} />
          <SummaryStat label="Planned actions" value={formatNumber(turn.turnSummary?.plannedActions ?? countActions(parsedPlan))} />
          <SummaryStat label="Rejected actions" value={formatNumber(turn.turnSummary?.rejectedActions ?? countRejected(turn.validationFailures))} />
        </div>
      </Card>

      <div className="panel-grid">
        <Card title="Strategist roadmap" subtitle="The long and mid-term plan that tactical turns should serve.">
          {roadmap ? (
            <>
              <div className="summary-grid compact">
                <SummaryStat label="Doctrine" value={stringValue(roadmap.doctrine)} />
                <SummaryStat label="Win path" value={stringValue(roadmap.winPath)} />
                <SummaryStat label="Phase" value={stringValue(roadmap.phase)} />
                <SummaryStat label="Review turn" value={formatNumber(numberValue(roadmap.reviewAfterTurn))} />
              </div>
              <p className="card-paragraph">{stringValue(roadmap.thesis) || "No roadmap thesis recorded."}</p>
              <SectionLabel text="Mid-term goals" />
              <TagList values={stringList(roadmap.midTermGoals)} />
              <SectionLabel text="Must maintain" />
              <TagList values={stringList(roadmap.mustMaintain)} tone="accent" />
              <SectionLabel text="Watch-outs" />
              <TagList values={stringList(roadmap.watchOuts)} tone="warning" />
            </>
          ) : (
            <p className="muted-text">No strategist roadmap was recorded for this turn.</p>
          )}
        </Card>

        <Card title="Tactical brief" subtitle="The filtered turn brief the planner saw before choosing actions.">
          {plannerBrief ? (
            <>
              <div className="summary-grid compact">
                <SummaryStat label="Archetype" value={stringValue(doctrine?.gameArchetype)} />
                <SummaryStat label="Doctrine" value={stringValue(doctrine?.doctrine)} />
                <SummaryStat label="Victory goal" value={stringValue(doctrine?.victoryGoal)} />
                <SummaryStat label="Rival" value={stringValue(doctrine?.rivalCiv)} />
              </div>
              <p className="card-paragraph">{stringValue(doctrine?.thesis) || "No tactical thesis recorded."}</p>
              <SectionLabel text="Commitments" />
              <TagList values={stringList(doctrine?.commitments)} />
              <SectionLabel text="Watch-outs" />
              <TagList values={stringList(doctrine?.watchOuts)} tone="warning" />
            </>
          ) : (
            <p className="muted-text">Planner brief missing from this turn.</p>
          )}
        </Card>
      </div>

      <div className="panel-grid">
        <Card title="Empire picture" subtitle="High-level state the strategist and tactical planner should keep in view.">
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

        <Card title="Current board" subtitle="What mattered locally on this turn.">
          {observation ? (
            <div className="summary-grid compact">
              <SummaryStat label="Cities" value={formatNumber(numberValue(empireSummary?.cityCount))} />
              <SummaryStat label="Units" value={formatNumber(numberValue(empireSummary?.unitCount))} />
              <SummaryStat label="Known civs" value={formatNumber(numberValue(empireSummary?.knownCivs))} />
              <SummaryStat label="Priority facts" value={formatNumber(arrayLength(observation.priorityFacts))} />
              <SummaryStat label="City highlights" value={formatNumber(arrayLength(observation.citiesNeedingAttention))} />
              <SummaryStat label="Unit highlights" value={formatNumber(arrayLength(observation.actionableUnits))} />
            </div>
          ) : (
            <p className="muted-text">No tactical observation captured.</p>
          )}
        </Card>
      </div>

      <div className="panel-grid">
        <AlertSection title="Critical alerts" facts={criticalAlerts} />
        <ProgressSection title="Progress in motion" items={progressInMotion} />
      </div>

      <div className="panel-grid">
        <CityHighlightsSection title="City highlights" cities={cityHighlights} />
        <UnitHighlightsSection title="Unit highlights" units={unitHighlights} />
      </div>

      <div className="panel-grid">
        <ActionPlanSection title="Planned actions" actions={plannedActions} notes={stringValue(parsedPlan?.notes)} />
        <DomainSummarySection title="Execution by domain" value={turn.outcomeDomainSummary ?? turn.plannedDomainCounts} />
      </div>

      <Card title="Raw events" subtitle="Keep this for deep debugging when the curated view is not enough.">
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
      </Card>
    </div>
  );
}

function Card({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="card">
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

function SummaryStat({ label, value, subtle = false }: { label: string; value: string; subtle?: boolean }) {
  return (
    <div className={`summary-stat ${subtle ? "subtle" : ""}`}>
      <span>{label}</span>
      <strong>{value || "None"}</strong>
    </div>
  );
}

function StatusPill({ children, tone }: { children: React.ReactNode; tone: "success" | "warning" | "muted" }) {
  return <span className={`status-pill ${tone}`}>{children}</span>;
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

function JsonSection({ title, value }: { title: string; value: unknown }) {
  return (
    <Card title={title}>
      {value ? <pre>{JSON.stringify(value, null, 2)}</pre> : <p className="muted-text">Nothing recorded here for this turn.</p>}
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
        <p className="muted-text">No critical alerts were captured for this turn.</p>
      )}
    </Card>
  );
}

function ProgressSection({ title, items }: { title: string; items: Record<string, unknown>[] }) {
  return (
    <Card title={title}>
      {items.length ? (
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
        <p className="muted-text">No in-flight progress was recorded for this turn.</p>
      )}
    </Card>
  );
}

function CityHighlightsSection({ title, cities }: { title: string; cities: Record<string, unknown>[] }) {
  return (
    <Card title={title}>
      {cities.length ? (
        <div className="structured-list">
          {cities.map((city, index) => {
            const progress = asRecord(city.constructionProgress);
            const topChoices = stringList(city.topConstructionChoices);
            const optionCandidates = objectArray(city.cityOptionCandidates).map((candidate) => stringValue(candidate.title)).filter(Boolean);
            const reasons = [...stringList(city.reasons), ...stringList(city.localFacts)].slice(0, 6);

            return (
              <article key={`${stringValue(city.name)}-${index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(city.name) || "Unnamed city"}</strong>
                  <div className="tag-list compact">
                    <span className="tag neutral">Pop {formatNumber(numberValue(city.population))}</span>
                    <span className="tag neutral">{stringValue(city.currentConstruction) || "No build"}</span>
                  </div>
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Food" value={formatNumber(numberValue(city.foodPerTurn))} />
                  <MiniMetric label="Prod" value={formatNumber(numberValue(city.productionPerTurn))} />
                  <MiniMetric label="Growth" value={nullableNumberText(city.turnsToGrowth)} />
                  <MiniMetric label="Strength" value={formatNumber(numberValue(city.cityStrength))} />
                </div>

                {progress ? <p className="card-paragraph">{stringValue(progress.progressNote)}</p> : null}

                {reasons.length ? (
                  <>
                    <SectionLabel text="Why it matters" />
                    <TagList values={reasons} />
                  </>
                ) : null}

                {topChoices.length ? (
                  <>
                    <SectionLabel text="Top build choices" />
                    <TagList values={topChoices.slice(0, 4)} tone="accent" />
                  </>
                ) : null}

                {optionCandidates.length ? (
                  <>
                    <SectionLabel text="Immediate city options" />
                    <TagList values={optionCandidates.slice(0, 4)} />
                  </>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : (
        <p className="muted-text">No city highlights were lifted into the turn brief.</p>
      )}
    </Card>
  );
}

function UnitHighlightsSection({ title, units }: { title: string; units: Record<string, unknown>[] }) {
  return (
    <Card title={title}>
      {units.length ? (
        <div className="structured-list">
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
        <p className="muted-text">No unit highlights were lifted into the turn brief.</p>
      )}
    </Card>
  );
}

function ActionPlanSection({
  title,
  actions,
  notes,
}: {
  title: string;
  actions: Record<string, unknown>[];
  notes: string;
}) {
  return (
    <Card title={title}>
      {notes ? <p className="card-paragraph">{notes}</p> : null}
      {actions.length ? (
        <div className="structured-list">
          {actions.map((action, index) => (
            <article key={`${stringValue(action.type)}-${index}`} className="structured-item">
              <div className="structured-item-header">
                <strong>{describeAction(action)}</strong>
                <div className="tag-list compact">
                  <span className="tag neutral">{stringValue(action.type) || "action"}</span>
                  <span className="tag neutral">Priority {formatNumber(numberValue(action.priority) ?? 0)}</span>
                </div>
              </div>
              <p className="card-paragraph">{describeActionDetail(action)}</p>
            </article>
          ))}
        </div>
      ) : (
        <p className="muted-text">No structured actions were parsed for this turn.</p>
      )}
    </Card>
  );
}

function DomainSummarySection({ title, value }: { title: string; value: unknown }) {
  const record = asRecord(value);
  return (
    <Card title={title}>
      {record ? (
        <div className="summary-grid compact">
          {Object.entries(record).map(([key, raw]) => (
            <SummaryStat key={key} label={humanizeKey(key)} value={summarizeUnknown(raw)} />
          ))}
        </div>
      ) : value ? (
        <pre>{JSON.stringify(value, null, 2)}</pre>
      ) : (
        <p className="muted-text">No execution summary was recorded for this turn.</p>
      )}
    </Card>
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

function numberValue(value: unknown): number | null {
  return typeof value === "number" ? value : null;
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

function countRejected(value: unknown): number {
  return Array.isArray(value) ? value.length : 0;
}

function toneFromSeverity(severity: string): "success" | "warning" | "muted" {
  const normalized = severity.toLowerCase();
  if (normalized === "critical" || normalized === "warning") return "warning";
  if (normalized === "good" || normalized === "success") return "success";
  return "muted";
}

function nullableNumberText(value: unknown): string {
  const number = numberValue(value);
  return number === null ? "—" : formatNumber(number);
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

function describeAction(action: Record<string, unknown>): string {
  switch (stringValue(action.type)) {
    case "select_empire_option":
      return `Select empire option ${stringValue(action.candidateId)}`;
    case "select_city_option":
      return `Select city option ${stringValue(action.candidateId)}`;
    case "select_unit_option":
      return `Select unit option ${stringValue(action.candidateId)}`;
    case "unit_move":
      return `Move unit ${formatNumber(numberValue(action.unitId))} to (${formatNumber(numberValue(action.destinationX))}, ${formatNumber(numberValue(action.destinationY))})`;
    case "unit_action":
      return `Use ${stringValue(action.actionType)} with unit ${formatNumber(numberValue(action.unitId))}`;
    case "city_choose_construction":
      return `Choose ${stringValue(action.constructionName)} in city (${formatNumber(numberValue(action.cityX))}, ${formatNumber(numberValue(action.cityY))})`;
    case "end_turn":
      return "End the turn";
    default:
      return stringValue(action.type) || "Unknown action";
  }
}

function describeActionDetail(action: Record<string, unknown>): string {
  switch (stringValue(action.type)) {
    case "select_empire_option":
    case "select_city_option":
    case "select_unit_option":
      return `Candidate ${stringValue(action.candidateId)} was chosen from the surfaced legal options.`;
    case "unit_move":
      return "Pure movement action selected for this unit.";
    case "unit_action":
      return "Direct unit command selected from current legal actions.";
    case "city_choose_construction":
      return "City production was changed explicitly this turn.";
    case "end_turn":
      return "Planner decided the turn could safely end after higher-priority actions.";
    default:
      return "Structured action chosen by the tactical planner.";
  }
}
