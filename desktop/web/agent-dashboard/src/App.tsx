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
}: {
  turn: TurnRecord;
}) {
  const observation = asRecord(turn.observation);
  const empireObservation = asRecord(turn.empireObservation);
  const plannerBrief = asRecord(turn.plannerBrief);
  const strategistBrief = asRecord(turn.strategistBrief);
  const strategistMemo = asRecord(turn.strategistMemo);
  const parsedPlan = asRecord(turn.parsedPlan);
  const empireSummary = asRecord(observation?.empireSummary);
  const strategy = asRecord(plannerBrief?.strategy);
  const memoryContext = asRecord(plannerBrief?.memoryContext);
  const campaignContext = asRecord(plannerBrief?.campaignContext);
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
  const memoryCityIntents = arrayLength(memoryRecord?.cityIntents);
  const memoryUnitAssignments = arrayLength(memoryRecord?.unitAssignments);
  const memoryRecentFailures = arrayLength(memoryRecord?.recentFailures);

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
      </Card>

      <TurnSectionNav
        items={[
          { id: "section-strategist-input", label: "Strategist saw" },
          { id: "section-strategist-output", label: "Strategist output" },
          { id: "section-perception", label: "Tactician saw" },
          { id: "section-decision", label: "Decision" },
          { id: "section-literal", label: "Literal" },
          { id: "section-events", label: "Events" },
        ]}
      />

      <SectionShell
        id="section-strategist-input"
        eyebrow="Strategist"
        title="What the strategist saw"
        body="This is the strategist's input space: broad current state, the previous memo, factual changes since then, and compact city/unit snapshots."
      >
        <StrategistContextSection
          title="Strategist inputs"
          brief={strategistBrief}
          threats={strategistRivalThreats}
          campaignPicture={strategistCampaignPicture}
        />

        <div className="reading-flow">
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
                <SummaryStat label="Phase" value={stringValue(strategistMemo.phase)} />
                <SummaryStat label="Review turn" value={formatNumber(numberValue(strategistMemo.reviewAfterTurn))} />
                <SummaryStat label="Created" value={memoCreatedTurn === null ? "Unknown" : `Turn ${formatNumber(memoCreatedTurn)}`} />
                <SummaryStat label="Last reviewed" value={memoLastReviewedTurn === null ? "Unknown" : `Turn ${formatNumber(memoLastReviewedTurn)}`} />
              </div>
              <p className="card-paragraph">{stringValue(strategistMemo.thesis) || "No strategist thesis recorded."}</p>
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
        <div className="reading-flow">
          <Card title="Tactical brief" subtitle="Only the tactician-specific tactical context that shaped this turn. The strategist's Past / Now / Future report is shown only in the strategist output section.">
            {plannerBrief ? (
              <>
                <div className="summary-grid compact">
                  <SummaryStat label="Archetype" value={stringValue(strategy?.gameArchetype)} />
                  <SummaryStat label="Phase" value={stringValue(strategy?.phase)} />
                  <SummaryStat label="Victory goal" value={stringValue(strategy?.winPath)} />
                </div>
                {stringValue(strategy?.thesis) ? <p className="card-paragraph">{stringValue(strategy?.thesis)}</p> : null}
                {stringValue(strategy?.tacticianHandoff) ? (
                  <>
                    <SectionLabel text="Direct handoff" />
                    <p className="card-paragraph">{stringValue(strategy?.tacticianHandoff)}</p>
                  </>
                ) : null}
                {memoryContext ? (
                  <>
                    <SectionLabel text="Notebook context" />
                    <div className="structured-list">
                      {stringValue(memoryContext.worldModelSummary) ? <p className="card-paragraph">{stringValue(memoryContext.worldModelSummary)}</p> : null}
                      {stringValue(memoryContext.campaignSummary) ? <p className="card-paragraph">{stringValue(memoryContext.campaignSummary)}</p> : null}
                      {stringValue(memoryContext.mainRivalSummary) ? <p className="card-paragraph">{stringValue(memoryContext.mainRivalSummary)}</p> : null}
                    </div>
                  </>
                ) : null}
              </>
            ) : (
              <p className="muted-text">Planner brief missing from this turn.</p>
            )}
          </Card>

          <div className="reading-flow">
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
          </div>

          <div className="reading-flow">
            <AlertSection title="Attention facts" facts={attentionFacts} />
            <ProgressSection title="Progress already in motion" items={progressInMotion} />
          </div>

          <div className="reading-flow">
            <ThreatHighlightsSection title="Threats the tactician could see" threats={threatHighlights} />
          </div>

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
        <div className="reading-flow">
          <ActionPlanSection
            title="Planned actions"
            actions={plannedActions}
            notes={stringValue(parsedPlan?.notes)}
            candidateLookup={candidateLookup}
          />
          <DomainSummarySection title="Applied outcome" value={turn.outcomeDomainSummary} />
        </div>

        <AttemptLadderSection attempts={tacticalAttempts} validationFailures={turn.validationFailures} />
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
  threats,
  campaignPicture,
}: {
  title: string;
  brief: Record<string, unknown> | null;
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

          {lastStrategistMemo ? (
            <>
              <SectionLabel text="Last strategist memo" />
              <div className="structured-item">
                <div className="summary-grid compact">
                  <SummaryStat label="Phase" value={stringValue(lastStrategistMemo.phase)} />
                  <SummaryStat label="Win path" value={stringValue(lastStrategistMemo.winPath)} />
                </div>
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
        <div className="structured-list">
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
        <div className="structured-list">
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
        <div className="structured-list">
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
        <div className="structured-list">
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
            <CodeDisclosure title="Exact Tactical Prompt" content={latestAttempt.prompt} />
          </>
        ) : (
          <p className="muted-text">No tactical prompt artifacts were captured for this turn.</p>
        )}
      </Card>

      <Card title="Strategist prompt artifacts" subtitle="Exact strategist brief and prompt text when a strategist pass happened on this turn.">
        {strategistArtifacts ? (
          <>
            {strategistArtifacts.retryEvents.length ? (
              <CodeDisclosure title="Strategist provider retry timeline" content={formatProviderRetryTimeline(strategistArtifacts.retryEvents)} />
            ) : null}
            {strategistArtifacts.requestError ? <CodeDisclosure title="Strategist final provider error" content={strategistArtifacts.requestError} /> : null}
            <CodeDisclosure title="Strategist Brief JSON" content={strategistArtifacts.briefJson || JSON.stringify(strategistBrief, null, 2)} />
            {strategistArtifacts.refreshRequest ? <CodeDisclosure title="Refresh Request JSON" content={strategistArtifacts.refreshRequest} /> : null}
            <CodeDisclosure title="Exact Strategist Prompt" content={strategistArtifacts.prompt} />
          </>
        ) : (
          <p className="muted-text">No strategist prompt ran on this turn.</p>
        )}
      </Card>
    </div>
  );
}

function CodeDisclosure({ title, content }: { title: string; content: string }) {
  if (!content) return null;
  return (
    <details className="code-disclosure">
      <summary>{title}</summary>
      <pre>{content}</pre>
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
  parsedPlan: Record<string, unknown> | null;
  validationFailure: string;
  providerRetries: ProviderRetryView[];
  requestError: string;
};

type StrategistArtifactsView = {
  prompt: string;
  briefJson: string;
  refreshRequest: string;
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
  const retryEvents = events
    .filter((event) => event.type === "strategist_llm_retry_scheduled" && event.id > request.id)
    .map(buildProviderRetryView)
    .filter((value): value is ProviderRetryView => value !== null);
  const requestError = events.find((event) => event.type === "strategist_llm_request_error" && event.id > request.id);
  return {
    prompt: request.details?.prompt ?? "",
    briefJson: request.details?.strategistBriefJson ?? "",
    refreshRequest: request.details?.refreshRequestJson ?? "",
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
