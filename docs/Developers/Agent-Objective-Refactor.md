# Agent Objective Refactor

This document is the durable tracker for the multi-pass agent refactor. It exists so the work stays coherent even if chat history is compacted.

## Goal

Improve the agent in a general way so it can better identify the decisive objective, convert an advantage into action, and finish games without overfitting to "always rush the capital".

The tiny `1v1` duel setup is the first proving ground, but the architecture should stay general.

## Design Principles

- Keep the engine general; keep setup-specific bias in cheat sheets and strategist priors.
- Prefer natural-language campaign memory over large rigid schemas.
- Distinguish current strategic intent from current factual battlefield state.
- Remove stale architecture rather than carrying backward compatibility for old traces.
- Each pass should leave the codebase internally consistent and compilable.

## Shared Vocabulary

- `campaignStage`: the current operational posture, such as `scouting`, `expansion`, `staging`, `assault`, `rebuild`, or `consolidation`.
- `decisiveObjective`: the next objective that most directly advances the current win path.
- `conversionBlocker`: the main thing preventing the current objective from converting into concrete progress.
- `campaignSummary`: the short human-readable description of the current operation.
- `reinforcementPlan`: how fresh production, gold, or movement should feed the operation.

These concepts replace the older over-broad `phase` framing.

## Four Passes

### Pass 1: Architecture

Scope:

- Replace the old `phase` backbone with `campaignStage`.
- Add `decisiveObjective` and `conversionBlocker` to the strategist memo and notebook.
- Rewire memory, strategist brief, planner brief, and prompts to use the new architecture.
- Remove pass-1 dead code and stale compatibility paths.

Pass 1 should not yet redesign the whole war packet or unit-action surfacing.

### Pass 2: Theater Packet

Scope:

- Replace the tiny lossy war-unit cap with objective-theater surfacing.
- Surface all units and cities that can materially affect the decisive objective soon.
- Add compact reserve summaries instead of hiding the relevant battlefield.

### Pass 3: Conversion Actions

Scope:

- Improve action surfacing for melee buys/builds, worker capture, and objective conversion.
- Add capture-readiness facts to the tactical brief.
- Reduce drift into side targets, passive upkeep, and ranged-only chip damage.

### Pass 4: Execution Hardening

Scope:

- Harden decisive turns against malformed or stale actions.
- Clean remaining dead code created by the refactor.
- Align observability and dashboard language with the new model.

## Current Status

- Pass 1: completed
- Pass 2: completed
- Pass 3: completed
- Pass 4: completed

## Pass 1 Delivered

- Replaced the old `phase` backbone with `campaignStage`.
- Added `decisiveObjective` and `conversionBlocker` to strategist memo memory and planner-facing notebook slices.
- Removed pass-1 compatibility reliance on the old memo `phase` and campaign `objective` fields.
- Updated strategist and tactician prompts, core memory plumbing, observability payloads, and dashboard labels to the new vocabulary.

## Pass 2 Delivered

- Added an explicit `objectiveTheater` section to the tactical brief.
- Added `objectiveTarget` and `objectiveSource` to the campaign context so the tactician no longer has to reconstruct the current target indirectly.
- Replaced the war/objective global unit cap with theater-aware unit surfacing:
  - all surfaced units that can materially affect the current objective theater
  - compact reserve summaries for off-axis combat units
- Expanded more units at the observation layer when they are part of the active objective theater, so the governor has a richer battlefield slice to work with.
- Updated tactician prompt and dashboard wording to explain the objective-theater packet.

## Pass 3 Delivered

- Added `captureReadiness` to the tactical brief so the tactician can see whether the current objective package actually has healthy capture-capable melee, ranged support, and safe worker-capture chances.
- Reworked city highlighting during pressure/war states so objective-adjacent support cities are less likely to disappear from the tactical packet.
- Made city build and gold-purchase surfacing campaign-aware:
  - frontline melee and conversion units rise during `pressure`, `staging`, `assault`, and `rebuild`
  - ranged support stays visible when the campaign still needs bombard help
  - Scouts, Settlers, Workers, and passive infrastructure are pushed down when the current blocker is objective conversion
- Reworked unit attack scoring so the packet prefers:
  - attacks that advance the resolved objective
  - city attacks on the decisive target
  - safe melee civilian captures over low-value ranged kills
- Updated tactician prompt and dashboard wording to explain `captureReadiness`.

## Pass 4 Delivered

- Hardened execution by freezing surfaced empire and city candidate contexts during validation/execution, matching the existing frozen unit-option behavior so prompt-visible candidate IDs stay stable across the turn.
- Reworked soft-prune validation salvage so it can remove non-critical rejected unit-side actions without requiring every rejected action to be soft first.
- Broadened live-execution tolerance for non-critical divergence:
  - stale frontier explore candidates
  - no-op exact moves
  - malformed or stale non-critical unit options when the turn still contains more important strategic or conversion actions
- Added a lightweight notion of `criticalAction` so decisive actions such as war declaration, research/policy selection, core build/purchase actions, and immediate city founding are protected while brittle side actions can be dropped first.
- Extended observability on salvaged turns with explicit `prunedActions` counts so the dashboard/trace makes it obvious when the engine preserved a good turn by dropping stale tactical noise.

## Notes For Future Passes

- Do not hardcode "always attack the capital".
- The correct abstraction is "identify and convert the decisive objective".
- In tiny duel domination, that often becomes the rival capital, but the architecture should not assume that globally.

## Continuity Follow-up

After the four-pass objective refactor, the next exposed weakness was continuity inside the stateless tactician loop:

- strategist guidance could stay live for several turns after the board had already changed
- tactician writeback was too thin to tell the next call what had completed, what was obsolete, and what still mattered
- city and unit carry-over memory was expiring by age more than by truth

The continuity follow-up adds a `tacticianTurnLog` as the short per-turn delta layer:

- strategist memo = durable base report
- tactician turn log = execution delta

Design intent:

- keep the strategist memo broad and human-readable
- let the tactician append turn-numbered summaries of what changed, what completed, what is still blocked, what became obsolete, and what should carry forward
- teach later stateless calls to read the log as the delta since the last strategist memo, rather than blindly repeating stale instructions

This follow-up also includes state-based invalidation:

- old city intents are cleared once the city is no longer on that project/focus
- old unit assignments are cleared once the unit reaches the target or disappears
- the city construction shortlist no longer loses the real next option just because the current nearly-finished project consumed a shortlist slot first
