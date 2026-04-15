import type { ObservabilityEvent, TurnRecord, TurnSummary } from "./types";
import { parseJsonValue } from "./utils";

function groupKey(event: ObservabilityEvent): string | null {
  if (event.turn === null || event.turn === undefined) return null;
  const civName = event.civName ?? "Unknown";
  return `${civName}::${event.turn}`;
}

function latestEventOfType(events: ObservabilityEvent[], type: string): ObservabilityEvent | undefined {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (events[index].type === type) return events[index];
  }
  return undefined;
}

function parseStrategistMemoFromResponse(event: ObservabilityEvent | undefined): unknown | null {
  if (!event) return null;
  const raw = parseJsonValue<Record<string, unknown>>(event.details?.rawResponse);
  const memo = raw && typeof raw === "object" ? raw.memo : null;
  return memo ?? null;
}

function summarizeStatus(
  turnSummary: TurnSummary | undefined,
  events: ObservabilityEvent[],
): Pick<TurnRecord, "statusLabel" | "statusTone" | "synopsis"> {
  if (turnSummary) {
    return {
      statusLabel: turnSummary.statusLabel,
      statusTone: turnSummary.fallback || turnSummary.blocked ? "warning" : turnSummary.status === "applied" ? "success" : "muted",
      synopsis: turnSummary.notes || turnSummary.topConcern || "No turn summary note recorded.",
    };
  }

  const latest = events[events.length - 1];
  const parsedPlan = latestEventOfType(events, "llm_plan_parsed");
  const plan = parseJsonValue<Record<string, unknown>>(parsedPlan?.details?.parsedPlan);
  const notes = typeof plan?.notes === "string" ? plan.notes : null;

  if (events.some((event) => event.type === "plan_applied")) {
    return {
      statusLabel: "Applied",
      statusTone: "success",
      synopsis: notes || latest.message,
    };
  }

  if (events.some((event) => event.type === "plan_validation_failed")) {
    return {
      statusLabel: "Replanning",
      statusTone: "warning",
      synopsis: notes || "Plan validation failed and requested another attempt.",
    };
  }

  if (events.some((event) => event.type === "llm_request")) {
    return {
      statusLabel: "Thinking",
      statusTone: "muted",
      synopsis: notes || latest.message,
    };
  }

  return {
    statusLabel: "Observed",
    statusTone: "muted",
    synopsis: latest.message,
  };
}

export function deriveTurns(events: ObservabilityEvent[], turnSummaries: TurnSummary[] = []): TurnRecord[] {
  const summariesByKey = new Map(turnSummaries.map((summary) => [`${summary.civName}::${summary.turn}`, summary]));
  const grouped = new Map<string, ObservabilityEvent[]>();

  for (const event of events) {
    const key = groupKey(event);
    if (!key) continue;
    const group = grouped.get(key) ?? [];
    group.push(event);
    grouped.set(key, group);
  }

  return Array.from(grouped.entries())
    .map(([key, group]) => {
      group.sort((left, right) => left.id - right.id);
      const [civName, turnValue] = key.split("::");
      const turn = Number(turnValue);
      const turnStart = latestEventOfType(group, "turn_start");
      const planParsed = latestEventOfType(group, "llm_plan_parsed");
      const strategistRequest = latestEventOfType(group, "strategist_llm_request");
      const strategistResponse = latestEventOfType(group, "strategist_llm_response");
      const planApplied = latestEventOfType(group, "plan_applied");
      const validationFailed = latestEventOfType(group, "plan_validation_failed");
      const details = turnStart?.details ?? {};
      const summary = summariesByKey.get(key);
      const status = summarizeStatus(summary, group);
      const strategistMemoFromResponse = parseStrategistMemoFromResponse(strategistResponse);

      return {
        key,
        civName,
        turn,
        events: group,
        latestEpochMs: group[group.length - 1]?.epochMs ?? 0,
        turnSummary: summary,
        observation: parseJsonValue(details.observationJson),
        empireObservation: parseJsonValue(details.empireObservationJson),
        worldFacts: parseJsonValue(details.worldFactsJson),
        memory: parseJsonValue(details.memoryJson),
        plannerBrief: parseJsonValue(details.plannerBriefJson),
        strategistBrief: parseJsonValue(strategistRequest?.details?.strategistBriefJson),
        strategistMemo: strategistMemoFromResponse ?? parseJsonValue(details.strategistMemoJson),
        parsedPlan: parseJsonValue(planParsed?.details?.parsedPlan),
        outcomeDomainSummary: parseJsonValue(planApplied?.details?.outcomeDomainSummaryJson),
        validationFailures: parseJsonValue(validationFailed?.details?.validationFailuresJson),
        synopsis: status.synopsis,
        statusLabel: status.statusLabel,
        statusTone: status.statusTone,
      };
    })
    .sort((left, right) => {
      if (left.turn !== right.turn) return right.turn - left.turn;
      return right.latestEpochMs - left.latestEpochMs;
    });
}
