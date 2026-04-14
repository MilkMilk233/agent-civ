export interface ObservabilityEvent {
  id: number;
  epochMs: number;
  type: string;
  message: string;
  civName?: string | null;
  turn?: number | null;
  details?: Record<string, string>;
}

export interface SnapshotResponse {
  startedAtEpochMs: number;
  generatedAtEpochMs: number;
  totalBufferedEvents: number;
  recentEvents: ObservabilityEvent[];
  counters: Record<string, number>;
}

export interface BatchSummary {
  batchId: string;
  name: string;
  configPath: string;
  startedAtEpochMs: number;
  finishedAtEpochMs?: number | null;
  status: string;
  requestedGames: number;
  plannedMatches: number;
  completedMatches: number;
  failedMatches: number;
  cancelledMatches: number;
  pairMatchesBySeed: boolean;
  maxTurns: number;
  baseRuleset: string;
  difficulty: string;
  mapType: string;
  mapSize: string;
  agentLabel: string;
  legacyLabel: string;
  agentWins: number;
  legacyWins: number;
  draws: number;
  avgTurns: number;
  avgInferenceLatencyMs?: number | null;
  fallbackRate: number;
  blockedRate: number;
  illegalActionRate: number;
}

export interface MatchSummary {
  batchId: string;
  matchId: string;
  label: string;
  seed: number;
  pairIndex: number;
  rolesSwapped: boolean;
  startedAtEpochMs: number;
  finishedAtEpochMs?: number | null;
  status: string;
  gameId: string;
  agentCivName: string;
  legacyCivName: string;
  winnerCivName?: string | null;
  winnerSide: string;
  victoryType?: string | null;
  totalTurns: number;
  agentTurnCount: number;
  fallbackTurns: number;
  blockedTurns: number;
  avgInferenceLatencyMs?: number | null;
  illegalActionRate: number;
  maxRejectedActionsOnTurn: number;
  interesting: boolean;
  topConcerns: string[];
  finalSaveFileName?: string | null;
}

export interface TurnSummary {
  civName: string;
  turn: number;
  status: string;
  statusLabel: string;
  plannedActions: number;
  executedActions: number;
  rejectedActions: number;
  llmLatencyMs?: number | null;
  fallback: boolean;
  blocked: boolean;
  illegalActionRate: number;
  notes?: string | null;
  topConcern?: string | null;
}

export interface ReplayResponse {
  batch: BatchSummary;
  match: MatchSummary;
  turnSummaries: TurnSummary[];
  recentEvents: ObservabilityEvent[];
}

export interface RunnerStatus {
  launchEnabled: boolean;
  running: boolean;
  cancelRequested: boolean;
  currentBatchId?: string | null;
  currentBatchName?: string | null;
  startedAtEpochMs?: number | null;
  finishedAtEpochMs?: number | null;
  lastCompletedBatchId?: string | null;
  lastCompletedBatchStatus?: string | null;
  lastError?: string | null;
  storageDir: string;
}

export interface RunnerMapSizeOption {
  name: string;
  radius: number;
  width: number;
  height: number;
}

export interface RunnerRulesetOptions {
  name: string;
  difficulties: string[];
  speeds: string[];
  civilizations: string[];
  victoryTypes: string[];
}

export interface RunnerFormOptions {
  defaultBaseRuleset: string;
  baseRulesets: string[];
  playerTypes: string[];
  rulesets: RunnerRulesetOptions[];
  mapTypes: string[];
  mapShapes: string[];
  mapSizes: RunnerMapSizeOption[];
  supportedCompetitivePlayerCount: number;
  supportsAdditionalCompetitivePlayers: boolean;
}

export interface TurnRecord {
  key: string;
  civName: string;
  turn: number;
  events: ObservabilityEvent[];
  latestEpochMs: number;
  turnSummary?: TurnSummary;
  observation: unknown | null;
  empireObservation: unknown | null;
  worldFacts: unknown | null;
  memory: unknown | null;
  plannerBrief: unknown | null;
  strategistBrief: unknown | null;
  strategistMemo: unknown | null;
  parsedPlan: Record<string, unknown> | null;
  outcomeDomainSummary: unknown | null;
  validationFailures: unknown | null;
  synopsis: string;
  statusLabel: string;
  statusTone: "success" | "warning" | "muted";
}
