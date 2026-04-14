# Agent Decision-Contract Refactor

This refactor tightens the strategist/tactician handoff so a stateless agent can keep one coherent story without drowning the tactician in mixed-priority clutter. The goal is to improve what each agent reads and writes, not to hardcode one tiny-map doctrine.

The architecture should stay general. Tiny `1v1` domination exposed the failure most clearly, but the contract should help any story the strategist needs to tell: expansion, recovery, launch timing, defensive war, or consolidation.

## Principles

- The strategist should write one clear high-level mode and one clear campaign story.
- The tactician should read a sharp briefing, not a growing pile of overlapping caveats.
- Scripts should manage freshness, pruning, and continuity, not decide the actual battle plan.
- Natural-language memo fields still matter; the typed contract exists to keep the handoff clean and durable across stateless calls.
- Each pass should leave the live agent path compilable and free of backward-compat clutter.

## Shared Contract

- `decisionFrame`: a compact strategist-written frame that answers what kind of turn this is, what target matters, what checkpoint proves progress, and what would make the line stale.

## Passes

### Pass 1: Strategist Write Contract

Scope:

- Add a typed `decisionFrame` to strategist memo draft/memory/report structures.
- Thread it through the live strategist brief and planner strategy packet.
- Tighten the strategist prompt so it always writes one clear high-level mode, target frame, checkpoint, and expiry condition.
- Add the durable tracker for this refactor.

Status:

- Completed.

Notes:

- This pass only installs the decision-frame skeleton and updates the write/read contract.
- Later passes should use the new frame to simplify tactician packets, strengthen tactician writeback, and prune stale campaign threads.

### Pass 2: Mode-Aware Tactician Read Contract

Scope:

- Make the tactical packet explicitly mode-aware.
- Separate critical decisions from background chores.
- Surface target, launch cohort, and recovery context in a cleaner way.

Status:

- Completed.

Notes:

- The planner brief now exposes a compact `decisionFocus` block that turns the strategist frame into a cleaner tactical cockpit instead of leaving the tactician to infer mode from scattered fields.
- `decisionFocus` separates `criticalChoicesNow` from `backgroundChores`, adds a compact `launchCohort` and `supplySnapshot`, and explicitly reports `actionSurfaceMismatch` when the current surfaced options do not cleanly support the strategist frame.
- This pass stays general: it shapes packet salience and coherence, but it still leaves the actual launch/pivot judgment to the LLM.

### Pass 3: Tactician Delta And Memory Truth

Scope:

- Sharpen tactician writeback so it reports whether the strategist frame still matches reality.
- Preserve one stable campaign thread instead of letting the same stale story restart with fresh wording.
- Track surfaced-action mismatches and invalidated carry-forward more cleanly.

Status:

- Completed.

Notes:

- `tacticianReflection` can now explicitly report `actionSurfaceMismatch`, which gets persisted into the tactician turn log instead of disappearing into free-form notes.
- The memory manager now treats those mismatches as real continuity evidence: they can automatically strain plan health even if the tactician forgets to write a strong `memoValidity` label.
- Campaign-thread continuity is now more resilient to small wording changes by matching the strategist frame on campaign axis and mode family instead of resetting the thread on every exact-string drift.

### Pass 4: Observability And Cleanup

Scope:

- Expose the strategist decision frame and downstream packet interpretation clearly in the dashboard.
- Remove temporary overlap once the new live path is fully switched.

Status:

- Completed.

Notes:

- The dashboard now surfaces strategist `decisionFrame`, tactician `decisionFocus`, and persisted tactician `actionSurfaceMismatch` in the places where diagnosis naturally happens: strategist input/output, shared memory, tactician brief, and tactician turn log.
- This makes the new contract inspectable without dropping straight into raw JSON, which should help us tell whether bad play is coming from strategist framing, tactician judgment, or packet/action-surface mismatch.
- The live path stays additive and clean; this pass did not reintroduce old-trace compatibility branches just to support the new observability fields.
