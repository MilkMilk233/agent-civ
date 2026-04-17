import type {
  BatchSummary,
  MatchSummary,
  ReplayResponse,
  RunnerFormOptions,
  RunnerStatus,
  SnapshotResponse,
} from "./types";

async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  snapshot(limit = 2000) {
    return requestJson<SnapshotResponse>(`/api/snapshot?limit=${limit}`);
  },
  batches() {
    return requestJson<BatchSummary[]>("/api/history/batches");
  },
  matches(batchId: string) {
    return requestJson<MatchSummary[]>(`/api/history/matches?batchId=${encodeURIComponent(batchId)}`);
  },
  replay(batchId: string, matchId: string) {
    return requestJson<ReplayResponse>(
      `/api/history/replay?batchId=${encodeURIComponent(batchId)}&matchId=${encodeURIComponent(matchId)}`,
    );
  },
  runnerStatus() {
    return requestJson<RunnerStatus>("/api/history/runner/status");
  },
  runnerOptions() {
    return requestJson<RunnerFormOptions>("/api/history/runner/options");
  },
  runnerTemplate() {
    return fetch("/api/history/runner/template").then(async (response) => {
      if (!response.ok) throw new Error(await response.text());
      return response.text();
    });
  },
  startBatch(rawConfig: string) {
    return requestJson<RunnerStatus>("/api/history/runner/start", {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=utf-8" },
      body: rawConfig,
    });
  },
  cancelBatch() {
    return requestJson<RunnerStatus>("/api/history/runner/cancel", {
      method: "POST",
    });
  },
};
