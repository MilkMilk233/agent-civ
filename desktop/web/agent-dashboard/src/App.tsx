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
          {selectedTurn ? <TurnDetail turn={selectedTurn} allTurns={turns} matchSummary={selectedMatch} /> : <EmptyState />}
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

function TurnDetail({
  turn,
  allTurns,
  matchSummary,
}: {
  turn: TurnRecord;
  allTurns: TurnRecord[];
  matchSummary: MatchSummary | null;
}) {
  const observation = asRecord(turn.observation);
  const empireObservation = asRecord(turn.empireObservation);
  const plannerBrief = asRecord(turn.plannerBrief);
  const strategistBrief = asRecord(turn.strategistBrief);
  const roadmap = asRecord(turn.strategicRoadmap);
  const strategicPlan = asRecord(turn.strategicPlan);
  const parsedPlan = asRecord(turn.parsedPlan);
  const empireSummary = asRecord(observation?.empireSummary);
  const doctrine = asRecord(plannerBrief?.doctrine);
  const criticalAlerts = objectArray(plannerBrief?.criticalAlerts ?? observation?.priorityFacts);
  const progressInMotion = objectArray(plannerBrief?.progressInMotion);
  const strategistMacroFacts = objectArray(strategistBrief?.macroFacts);
  const strategistRivalThreats = objectArray(strategistBrief?.rivalThreats ?? empireObservation?.victoryThreats);
  const threatHighlights = objectArray(plannerBrief?.threatHighlights ?? observation?.visibleThreatsAndTargets);
  const opportunityHighlights = objectArray(plannerBrief?.opportunityHighlights ?? observation?.opportunities);
  const cityHighlights = objectArray(plannerBrief?.cityHighlights ?? observation?.cities);
  const unitHighlights = objectArray(plannerBrief?.unitHighlights ?? observation?.units);
  const perceptionSummary = asRecord(observation?.perceptionSummary);
  const empireChoices = asRecord(plannerBrief?.empireChoices);
  const suppressedContext = stringList(plannerBrief?.suppressedContext);
  const plannedActions = objectArray(parsedPlan?.actions);
  const roadmapNotes = stringValue(strategicPlan?.notes);
  const roadmapCreatedTurn = numberValue(roadmap?.createdTurn);
  const roadmapLastReviewedTurn = numberValue(roadmap?.lastReviewedTurn);
  const roadmapRefreshReason = stringValue(roadmap?.lastRefreshReason);
  const roadmapSwitchTriggers = stringList(roadmap?.switchTriggers);
  const turnMetrics = deriveTurnMetrics(turn);
  const averageMetrics = deriveAverageMetrics(allTurns, matchSummary);
  const strategistRefresh = latestEventDetails(turn.events, [
    "strategist_refresh_requested_by_tactical",
    "strategist_refresh_requested",
  ]);

  return (
    <div className="detail-stack">
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
          </div>
          <p className="mini-note">
            Retries happen before execution. This card separates planning churn from live execution rejects so you can see whether the planner is thinking twice or actually failing live.
          </p>
        </div>

        <div className="summary-grid summary-grid-wide">
          <SummaryStat label="Latest event" value={formatRelative(turn.latestEpochMs)} />
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
          <SummaryStat label="First-pass success" value={formatPercent(averageMetrics.firstPassRate)} />
          <SummaryStat
            label="Retry-turn rate"
            value={formatPercent(averageMetrics.retryTurnRate)}
            note={`${formatNumber(averageMetrics.retryTurns)} of ${formatNumber(averageMetrics.sampleTurns)} turns`}
          />
          <SummaryStat
            label="Avg retries / turn"
            value={averageMetrics.avgRetriesPerTurn.toFixed(2)}
            note={averageMetrics.retryTurns > 0 ? `${averageMetrics.avgRetriesOnRetryTurns.toFixed(2)} on retry turns` : "no retry turns yet"}
          />
          <SummaryStat label="Avg live reject rate" value={formatPercent(averageMetrics.avgExecutionRejectRate)} />
          <SummaryStat label="Avg LLM time" value={formatDurationMs(averageMetrics.avgLatencyMs)} />
          <SummaryStat label="Avg fallback rate" value={formatPercent(averageMetrics.avgFallbackRate)} />
          <SummaryStat label="Avg blocked rate" value={formatPercent(averageMetrics.avgBlockedRate)} />
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
                <SummaryStat label="Created" value={roadmapCreatedTurn === null ? "Unknown" : `Turn ${formatNumber(roadmapCreatedTurn)}`} />
                <SummaryStat label="Last reviewed" value={roadmapLastReviewedTurn === null ? "Unknown" : `Turn ${formatNumber(roadmapLastReviewedTurn)}`} />
              </div>
              <p className="card-paragraph">{stringValue(roadmap.thesis) || "No roadmap thesis recorded."}</p>
              {roadmapNotes ? <p className="mini-note">{roadmapNotes}</p> : null}
              {roadmapRefreshReason || strategistRefresh ? (
                <>
                  <SectionLabel text="Refresh context" />
                  <div className="tag-list">
                    {roadmapRefreshReason ? <span className="tag accent">{roadmapRefreshReason}</span> : null}
                    {stringValue(strategistRefresh?.urgency) ? (
                      <StatusPill tone={toneFromUrgency(stringValue(strategistRefresh?.urgency))}>
                        {stringValue(strategistRefresh?.urgency)}
                      </StatusPill>
                    ) : null}
                    {stringValue(strategistRefresh?.reason) ? <span className="tag neutral">{stringValue(strategistRefresh?.reason)}</span> : null}
                  </div>
                </>
              ) : null}
              <SectionLabel text="Mid-term goals" />
              <TagList values={stringList(roadmap.midTermGoals)} />
              <SectionLabel text="Must maintain" />
              <TagList values={stringList(roadmap.mustMaintain)} tone="accent" />
              <SectionLabel text="Watch-outs" />
              <TagList values={stringList(roadmap.watchOuts)} tone="warning" />
              <SectionLabel text="Switch triggers" />
              <TagList values={roadmapSwitchTriggers} />
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
                <SummaryStat label="Phase" value={stringValue(doctrine?.phase)} />
                <SummaryStat label="Victory goal" value={stringValue(doctrine?.victoryGoal)} />
                <SummaryStat label="Rival" value={stringValue(doctrine?.rivalCiv)} />
                <SummaryStat label="Rival plan" value={stringValue(doctrine?.rivalVictoryGoal)} />
              </div>
              <p className="card-paragraph">{stringValue(doctrine?.thesis) || "No tactical thesis recorded."}</p>
              <SectionLabel text="Commitments" />
              <TagList values={stringList(doctrine?.commitments)} />
              <SectionLabel text="Watch-outs" />
              <TagList values={stringList(doctrine?.watchOuts)} tone="warning" />
              <SectionLabel text="Suppressed context" />
              <TagList values={suppressedContext} />
            </>
          ) : (
            <p className="muted-text">Planner brief missing from this turn.</p>
          )}
        </Card>
      </div>

      <div className="panel-grid">
        <StrategistContextSection
          title="Strategist inputs"
          brief={strategistBrief}
          threats={strategistRivalThreats}
          macroFacts={strategistMacroFacts}
        />

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
      </div>

      <div className="panel-grid">
        <AlertSection title="Critical alerts" facts={criticalAlerts} />
        <ProgressSection title="Progress in motion" items={progressInMotion} />
      </div>

      <div className="panel-grid">
        <ThreatHighlightsSection title="Threat highlights" threats={threatHighlights} />
        <ObservationFactSection title="Opportunity highlights" facts={opportunityHighlights} emptyText="No opportunity highlights were surfaced for this turn." />
      </div>

      <div className="panel-grid">
        <CityHighlightsSection title="City highlights" cities={cityHighlights} />
        <UnitHighlightsSection title="Unit highlights" units={unitHighlights} />
      </div>

      <div className="panel-grid">
        <EmpireChoicesSection title="Empire choices" choices={empireChoices} />
        <Card title="Current board" subtitle="What mattered locally on this turn.">
          {observation ? (
            <div className="summary-grid compact">
              <SummaryStat label="Cities" value={formatNumber(numberValue(empireSummary?.cityCount))} />
              <SummaryStat label="Units" value={formatNumber(numberValue(empireSummary?.unitCount))} />
              <SummaryStat label="Known civs" value={formatNumber(numberValue(empireSummary?.knownCivs))} />
              <SummaryStat label="Threats surfaced" value={formatNumber(arrayLength(observation.visibleThreatsAndTargets))} />
              <SummaryStat
                label="Expanded cities"
                value={formatNumber(numberValue(perceptionSummary?.expandedCities))}
                note={`${formatNumber(numberValue(perceptionSummary?.totalCities))} total`}
              />
              <SummaryStat
                label="Expanded units"
                value={formatNumber(numberValue(perceptionSummary?.expandedUnits))}
                note={`${formatNumber(numberValue(perceptionSummary?.totalUnits))} total`}
              />
            </div>
          ) : (
            <p className="muted-text">No tactical observation captured.</p>
          )}
        </Card>
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
    <p className="muted-text">No in-flight progress was recorded for this turn.</p>
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
  threats,
  macroFacts,
}: {
  title: string;
  brief: Record<string, unknown> | null;
  threats: Record<string, unknown>[];
  macroFacts: Record<string, unknown>[];
}) {
  const gameContext = asRecord(brief?.gameContext);
  const empireSummary = asRecord(brief?.empireSummary);
  const progressInMotion = objectArray(brief?.progressInMotion);
  const recentFailures = stringList(brief?.recentFailures);
  const enabledVictories = stringList(brief?.enabledVictoryTypes);

  return (
    <Card title={title} subtitle="Setup-aware strategist facts before the roadmap was chosen or refreshed.">
      {brief ? (
        <>
          <div className="summary-grid compact">
            <SummaryStat label="Map archetype" value={stringValue(gameContext?.archetype)} />
            <SummaryStat label="Exploration value" value={stringValue(gameContext?.explorationValue)} />
            <SummaryStat label="Expansion window" value={stringValue(gameContext?.expansionWindow)} />
            <SummaryStat label="Current research" value={stringValue(brief.currentResearch)} />
            <SummaryStat label="Research status" value={stringValue(brief.currentResearchStatus)} />
            <SummaryStat label="Empire size" value={`${formatNumber(numberValue(empireSummary?.cityCount))} cities · ${formatNumber(numberValue(empireSummary?.unitCount))} units`} />
          </div>

          <SectionLabel text="Enabled victories" />
          <TagList values={enabledVictories} tone="accent" />

          <ThreatHighlightsSection title="Rival threats" threats={threats} embedded />
          <ObservationFactSection title="Strategic facts" facts={macroFacts.slice(0, 6)} embedded emptyText="No strategist facts were recorded." />
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

function ObservationFactSection({
  title,
  facts,
  emptyText,
  embedded = false,
}: {
  title: string;
  facts: Record<string, unknown>[];
  emptyText: string;
  embedded?: boolean;
}) {
  const content = facts.length ? (
    <div className="structured-list">
      {facts.map((fact, index) => (
        <article key={`${stringValue(fact.headline)}-${index}`} className="structured-item">
          <div className="structured-item-header">
            <strong>{stringValue(fact.headline) || "Untitled fact"}</strong>
            <div className="tag-list compact">
              {stringValue(fact.severity) ? (
                <StatusPill tone={toneFromSeverity(stringValue(fact.severity))}>{stringValue(fact.severity)}</StatusPill>
              ) : null}
              {stringValue(fact.category) ? <span className="tag neutral">{stringValue(fact.category)}</span> : null}
            </div>
          </div>
          <p className="card-paragraph">{stringValue(fact.detail)}</p>
        </article>
      ))}
    </div>
  ) : (
    <p className="muted-text">{emptyText}</p>
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
    <div className="structured-list">
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
    <p className="muted-text">No threat highlights were surfaced for this turn.</p>
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
        <p className="muted-text">No empire-level choices were surfaced in the tactical brief.</p>
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
            const state = asRecord(city.state);
            const project = asRecord(city.project);
            const actions = asRecord(city.actions);
            const signals = stringList(city.signals);
            const projectChoices = objectArray(actions?.chooseProject).map(formatCityActionCandidateLabel).filter(Boolean);
            const purchaseChoices = objectArray(actions?.purchase).map(formatCityActionCandidateLabel).filter(Boolean);
            const tileChoices = objectArray(actions?.buyTile).map(formatCityActionCandidateLabel).filter(Boolean);
            const focusChoices = objectArray(actions?.focus).map(formatCityActionCandidateLabel).filter(Boolean);
            const growthChoices = objectArray(actions?.growthMode).map(formatCityActionCandidateLabel).filter(Boolean);

            return (
              <article key={`${stringValue(city.name)}-${index}`} className="structured-item">
                <div className="structured-item-header">
                  <strong>{stringValue(city.name) || "Unnamed city"}</strong>
                  <div className="tag-list compact">
                    <span className="tag neutral">Pop {formatNumber(numberValue(state?.population))}</span>
                    <span className="tag neutral">{stringValue(project?.name) || "Needs project"}</span>
                  </div>
                </div>

                <div className="mini-metric-grid">
                  <MiniMetric label="Food" value={formatNumber(numberValue(state?.foodPerTurn))} />
                  <MiniMetric label="Prod" value={formatNumber(numberValue(state?.productionPerTurn))} />
                  <MiniMetric label="Growth" value={nullableNumberText(state?.turnsToGrowth)} />
                  <MiniMetric label="Strength" value={formatNumber(numberValue(state?.cityStrength))} />
                </div>

                {project ? <p className="card-paragraph">{stringValue(project.note)}</p> : null}

                {signals.length ? (
                  <>
                    <SectionLabel text="Signals" />
                    <TagList values={signals} />
                  </>
                ) : null}

                {projectChoices.length ? (
                  <>
                    <SectionLabel text="Choose project" />
                    <TagList values={projectChoices.slice(0, 4)} tone="accent" />
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

function latestEventDetails(events: TurnRecord["events"], eventTypes: string[]): Record<string, unknown> | null {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (eventTypes.includes(events[index].type) && events[index].details) {
      return events[index].details as Record<string, unknown>;
    }
  }
  return null;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown): number | null {
  return typeof value === "number" ? value : null;
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
    intentionalNoOp,
    cleanFirstPass: retryCount === 0 && !fallback && !blocked,
    illegalActionRate: summary?.illegalActionRate ?? 0,
    tacticalLatencyMs,
    strategistLatencyMs,
    totalLatencyMs: totalLatencyMs || null,
  };
}

function deriveAverageMetrics(allTurns: TurnRecord[], matchSummary: MatchSummary | null) {
  const turnMetrics = allTurns.map((turn) => deriveTurnMetrics(turn));
  const sampleTurns = Math.max(1, turnMetrics.length);
  const totalExecutionRejected = turnMetrics.reduce((sum, metrics) => sum + metrics.executionRejectedActions, 0);
  const totalDecisionPoints = turnMetrics.reduce((sum, metrics) => sum + Math.max(1, metrics.executedActions + metrics.executionRejectedActions), 0);
  const latencies = turnMetrics.map((metrics) => metrics.totalLatencyMs).filter((value): value is number => value !== null);
  const fallbackCount = turnMetrics.filter((metrics) => metrics.fallback).length;
  const blockedCount = turnMetrics.filter((metrics) => metrics.blocked).length;
  const retryTurns = turnMetrics.filter((metrics) => metrics.retryCount > 0).length;
  const totalRetries = turnMetrics.reduce((sum, metrics) => sum + metrics.retryCount, 0);
  const cleanFirstPassTurns = turnMetrics.filter((metrics) => metrics.cleanFirstPass).length;
  const totalIllegalRate = turnMetrics.reduce((sum, metrics) => sum + metrics.illegalActionRate, 0);

  return {
    sampleTurns,
    retryTurns,
    totalRetries,
    firstPassRate: cleanFirstPassTurns / sampleTurns,
    retryTurnRate: retryTurns / sampleTurns,
    avgRetriesPerTurn: totalRetries / sampleTurns,
    avgRetriesOnRetryTurns: retryTurns > 0 ? totalRetries / retryTurns : 0,
    avgExecutionRejectRate: totalExecutionRejected / Math.max(1, totalDecisionPoints),
    avgFallbackRate:
      matchSummary && matchSummary.agentTurnCount > 0
        ? matchSummary.fallbackTurns / matchSummary.agentTurnCount
        : fallbackCount / sampleTurns,
    avgBlockedRate:
      matchSummary && matchSummary.agentTurnCount > 0
        ? matchSummary.blockedTurns / matchSummary.agentTurnCount
        : blockedCount / sampleTurns,
    avgLatencyMs:
      matchSummary?.avgInferenceLatencyMs && matchSummary.avgInferenceLatencyMs > 0
        ? matchSummary.avgInferenceLatencyMs
        : average(latencies),
    avgIllegalRate:
      matchSummary?.illegalActionRate && matchSummary.illegalActionRate > 0
        ? matchSummary.illegalActionRate
        : totalIllegalRate / sampleTurns,
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

function toneFromUrgency(urgency: string): "success" | "warning" | "muted" {
  const normalized = urgency.toLowerCase();
  if (normalized === "emergency") return "warning";
  if (normalized === "scheduled") return "success";
  return "muted";
}

function nullableNumberText(value: unknown): string {
  const number = numberValue(value);
  return number === null ? "—" : formatNumber(number);
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

function average(values: number[]): number | null {
  if (!values.length) return null;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
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
  if (estimatedTurns !== undefined) parts.push(`${formatNumber(estimatedTurns)}t`);
  if (goldCost !== undefined) parts.push(`${formatNumber(goldCost)}g`);
  return parts.join(" · ");
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
    case "end_turn":
      return "Planner decided the turn could safely end after higher-priority actions.";
    default:
      return "Structured action chosen by the tactical planner.";
  }
}
