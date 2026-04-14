# Agent Campaign-Control Refactor

This refactor adds a second small typed layer beside the strategist notebook and the newer plan-health scaffold. The goal is to help a stateless agent manage the middle ground that strong human players live in: committed enough to finish a live campaign, but not so rigid that it postpones forever or so reckless that it burns supply for no gain.

The architecture should stay general. Tiny `1v1` domination is the first proving ground because it exposed the failure most clearly, but the schema is meant to support other setups too.

## Principles

- Scripts manage freshness and the typed control state.
- LLMs still do the real strategic and tactical reasoning in natural language.
- The typed layer should summarize campaign commitment, battle readiness, supply health, and the next checkpoint without hardcoding exact moves.
- Natural-language memo fields remain the real teammate briefing; the typed layer exists so scripts can safely track continuity.
- Each pass should leave the live agent path compilable and free of backward-compat clutter.

## Shared Typed Layer

- `campaignControl`: typed labels plus script-managed state for campaign commitment and sustainability.
- `tacticianReflection` gains compact labels for commitment, battle readiness, and supply health when the tactician learns something material from the turn.

## Passes

### Pass 1: Schema And Contracts

Scope:

- Add typed `campaignControl` objects to strategist memo memory, strategist memo draft, shared memory, and planner/strategist briefs.
- Extend tactician reflection and tactician turn-log schema with compact commitment/readiness/supply labels.
- Thread the new fields through governors, memo/report objects, and prompt schemas.
- Add a durable tracker for the campaign-control refactor.

Status:

- Completed.

Notes:

- This pass only installs the typed skeleton. It does not yet derive battle-readiness, supply-health, or checkpoint behavior from board truth.
- Later passes should keep the layer small and avoid turning it into a hardcoded domination script.

### Pass 2: Memory-Manager Reconciliation

Scope:

- Reconcile `campaignControl` against observable board truth each turn.
- Derive campaign commitment age, checkpoint status, launch-window state, holding costs, and pivot triggers.
- Keep the logic evidence-based and legal-information-only.

Status:

- Completed.

Notes:

- `campaignControl` now gets reconciled from legal board truth each turn inside `AgentMemoryManager`, not just carried labels from the strategist memo.
- The live memory path now derives checkpoint status, launch-window state, holding costs, and pivot triggers from observable facts like visible target state, city count, army size, treasury pressure, and known rival deltas.
- Emergency strategist refresh can now also be triggered by campaign-control failure states such as a missed checkpoint, collapsing supply, or a ready launch window that is still being held too long.

### Pass 3: Brief And Prompt Pressure

Scope:

- Surface campaign-control signals into strategist and tactician briefs as usable judgment aids.
- Teach the models how to interpret readiness, supply, and checkpoint pressure without turning the prompts into scripts.

Status:

- Completed.

Notes:

- The tactical brief now turns campaign-control into operational pressure instead of passive metadata: missed checkpoints, open launch windows, and supply strain can now surface through `mustActNow` and `attentionFacts`.
- The tactician prompt now explicitly treats missed checkpoints, launch-window stalls, and supply pressure as reasons to stop preserving a stale campaign line.
- The strategist prompt now frames campaign control in a more human-like way: commit for a short window, name the next checkpoint, and rewrite the memo plainly when the campaign is no longer converting.

### Pass 4: Observability And Cleanup

Scope:

- Expose campaign-control state clearly in the dashboard.
- Clean any stale seams from the temporary transition period once the live path is fully switched.

Status:

- Completed.

Notes:

- The dashboard now surfaces `campaignControl` alongside `planHealth` in the memory view, strategist input view, strategist output memo, and tactician brief view.
- The tactician turn log now also shows compact commitment/readiness/supply tags, making it easier to inspect how campaign posture shifted across stateless turns.
- This pass stayed on the live path only and did not add old-trace compatibility branches back into the UI.
