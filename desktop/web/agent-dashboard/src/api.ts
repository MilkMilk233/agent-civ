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

export type BattlefieldViewFetchResult =
  | { kind: "image"; blob: Blob }
  | { kind: "status"; status: string; message?: string };

function parseBattlefieldStatus(text: string): { status: string; message?: string } {
  try {
    const value = JSON.parse(text) as { status?: string; message?: string; error?: string };
    return {
      status: value.status || "error",
      message: value.message || value.error,
    };
  } catch {
    return {
      status: "error",
      message: text || "Unknown battlefield render error",
    };
  }
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
  async battlefieldView(batchId: string, matchId: string, civName: string, turn: number): Promise<BattlefieldViewFetchResult> {
    const response = await fetch(
      `/api/history/battlefield-view?batchId=${encodeURIComponent(batchId)}&matchId=${encodeURIComponent(matchId)}&civName=${encodeURIComponent(civName)}&turn=${turn}`,
    );
    const contentType = response.headers.get("content-type") || "";
    if (response.ok && contentType.includes("image/png")) {
      return { kind: "image", blob: await response.blob() };
    }

    const text = await response.text();
    if (response.status === 202 || response.status === 404 || response.status === 500) {
      const status = parseBattlefieldStatus(text);
      return { kind: "status", status: status.status, message: status.message };
    }
    if (!response.ok) {
      throw new Error(text || `Request failed: ${response.status}`);
    }
    throw new Error("Unexpected battlefield render response");
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
