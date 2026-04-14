# Agent Plan-Health Refactor

This refactor adds a small typed continuity layer beside the natural-language strategist and tactician notebook. The goal is to help a stateless agent notice when a plan is still healthy, when it is slipping, and when later passes should trigger a pivot, without asking scripts to parse fuzzy prose.

The tiny `1v1` duel setup is the first proving ground because it exposed the failure most clearly, but the architecture should stay general.

## Principles

- Scripts manage memory freshness and typed continuity state.
- LLMs contribute interpretation, prose, and a small typed label layer.
- Natural-language notebook fields remain for reasoning and handoff.
- Scripts should never rely on parsing vague prose for control logic.
- Each pass should leave the live agent path internally consistent, compilable, and free of backward-compat clutter.

## Shared Typed Layer

- `planHealth`: small typed labels plus script-managed status/history.
- `tacticianReflection`: per-turn delta that records what changed, what completed, what is obsolete, and whether the old memo still feels healthy.

The typed layer is intentionally small. It exists so scripts can manage continuity safely while the LLM still does the actual strategic and tactical thinking in natural language.

## Passes

### Pass 1: Schema And Contracts

Scope:

- Add typed `planHealth` objects to strategist memo memory, strategist memo draft, shared memory, and planner/strategist briefs.
- Add typed `tacticianReflection` to the tactical action-plan schema and store its `memoValidity` in the tactician turn log.
- Thread the new fields through the agent governors, memory manager, and prompt schemas.
- Clean up dead seams instead of preserving old compatibility paths.

Status:

- Completed.

Notes:

- This pass does not yet add the full contradiction, opportunity-cost, or pivot behavior.
- It only adds the typed skeleton so later passes can build behavior cleanly.

### Pass 2: Memory-Manager Reconciliation

Scope:

- Reconcile `planHealth` against board truth each turn instead of only carrying strategist labels forward.
- Derive basic progress signals, contradiction signals, and opportunity-cost signals from observable state.
- Record tactician `memoValidity` into the live plan-health model.
- Trigger strategist refresh early when the current campaign thread is clearly contradicted or should pivot.

Status:

- Completed.

Notes:

- This pass keeps the logic general and evidence-based. It does not script exact gameplay responses such as "declare war now."
- Later passes can still improve thresholds, richer blockers, and observability for the new signals.

### Pass 3: Brief And Prompt Pressure

Scope:

- Surface plan-health pressure into the tactical brief as actionable warnings instead of background data only.
- Make stale-plan and opportunity-cost signals visible through `mustActNow` and `attentionFacts`.
- Tighten strategist and tactician prompt semantics so strained or contradicted plans push toward conversion or pivoting instead of passive continuity.

Status:

- Completed.

Notes:

- This pass still avoids hardcoded gameplay scripts. It changes how the agents should interpret stale-plan evidence, not the exact move they must choose.
- The next pass should focus on observability so the new plan-health signals are easy to inspect in the dashboard.

### Pass 4: Observability And Cleanup

Scope:

- Surface plan-health state in the dashboard where it matters:
  - shared memory
  - strategist input
  - tactician input
  - stored strategist memo
  - tactician turn log
- Show contradictions, opportunity costs, pivot recommendation, and memo validity directly instead of leaving them hidden in raw JSON.
- Keep the live dashboard path aligned with the new schema without adding old-trace compatibility seams.

Status:

- Completed.

Notes:

- The dashboard now exposes the typed continuity layer as first-class observability instead of forcing diagnosis through literal artifacts only.
- Tactician log entries now show `memoValidity`, which makes stale-plan drift much easier to spot in replay.
