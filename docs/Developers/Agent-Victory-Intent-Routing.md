# Agent Victory-Intent Routing

This document captures the next design goal for the agent after the objective, campaign-control, and decision-contract refactors.

The goal is not to make the agent generally less aggressive. The goal is to make the agent correctly understand what kind of game it is in, then play that mode well without losing the current tiny-duel domination strength.

## Core Goal

We want the agent to play well under different game settings.

Examples:

- On tiny `1v1` with all standard victory types enabled, the agent should still naturally identify domination as a strong path and play it well.
- On tiny `1v1` with only tech victory enabled, the agent should treat science snowball as the primary path, while still defending itself and using military only as an instrument of defense, deterrence, or tempo protection.

The architecture must stay general. Tiny duel is the first proving ground, not the only target.

## Current Mismatch

The current stack already contains some general victory-aware logic:

- the engine knows which victories are enabled
- if only one victory type is enabled, `getPreferredVictoryTypes()` already returns that victory directly
- the empire observation computes a heuristic victory plan from enabled victories only
- research, city builds, policies, gold, and diplomacy are all surfaced as legal choices
- some legacy scoring already benefits from the forced victory preference, especially policy priorities and non-pressure city automation

But the full packet is still asymmetric:

- domination has a strong positive scaffold
- science mostly exists as raw context

Today the domination line is reinforced by several layers at once:

- a tiny-duel domination strategist cheat sheet
- war-facing campaign framing once rival targets and war options appear
- lower-level city and unit surfacing that becomes pressure-oriented quickly
- tactical combat doctrine once the packet enters a war-facing mode

This means the agent can still drift into a domination-shaped story even in settings where domination is disabled or should only be instrumental.

The key point is:

- the base victory signal is better than it first appears
- the main problem is that downstream routing keeps overriding or war-shaping that signal

## Deeper Code Findings

After a deeper pass through the agent stack, the most important hidden couplings are:

- Tiny duel contact currently amplifies war bias twice:
  - once because the planner only surfaces one diplomacy choice after contact in duel games
  - again because diplomacy ranking gives `declare war` the highest diplomacy score
- The strongest computed victory-routing facts are not actually threaded through the strategist or tactician packets in typed form:
  - `preferredVictoryTypes`
  - `heuristicVictoryGoal`
  - `heuristicVictoryFocus`
  - `victoryNextMilestone`
  are computed in empire observation, but strategist brief only gets `enabledVictoryTypes`, and planner brief mostly falls back to memo `winPath`
- `AgentEmpireObservation.victoryGoal` is itself memo-shaped, not heuristic:
  - it mirrors strategist memo `winPath`
  - it does not expose the fresh heuristic victory plan
- The code currently uses one `primaryRivalCiv` for two different concepts:
  - the rival leading the race
  - the rival city that becomes the military target
- `campaignContext` becomes war-like too easily:
  - a visible rival city
  - a remembered rival city anchor
  - or a surfaced war declaration
  can be enough to create a military objective packet even without a real conquest posture
- the observation layer also starts shaping an objective theater early:
  - unit salience is boosted around an `objectiveTheaterHint`
  - that hint can be activated by nonblank campaign objective text, staging-like stages, visible rival cities, or rival anchors
  - so packet emphasis can become target-centric before the planner makes a fresh military commitment
- The unit layer contains a stronger hidden conquest bias than the prompt alone:
  - operational city-target assignments can be surfaced whenever an enemy city is resolved
  - untouched combat units can be auto-filled into city-target assignments
  - the auto-fill order prefers `attack_target_city` and `stage_near_target_city` over passive holding for combat units
  - those operational roles are `deferred_heuristic` assignments with `completionPolicy = until_switched`
  - untouched units with those roles keep advancing automatically after planning
- The city layer can bypass more general legacy construction logic once `objectivePressure` becomes true
- `objectivePressure` itself is currently derived from campaign-stage labels and free-text memo wording such as `capture`, `take`, `war`, or `frontier`
- The current peaceful-growth checks are too brittle for tech-focused tiny-duel play:
  - top-level observation peaceful logic turns off as soon as a known major civ exists
  - city-level peaceful logic turns off as soon as a major civ is known
  - memory-level peaceful snowball logic turns off as soon as foreign cities are visible
- campaign-control and emergency refresh logic still rely on string-shaped military cues:
  - `looksWarAware()` checks memo text for words like `war`, `assault`, `front`, and `pressure`
  - campaign-control objective inference depends on mode-family string routing
- the current campaign-control objective inference is fragile:
  - `campaignModeFamily()` maps war-like modes to `military`
  - `deriveCampaignObjectiveKind()` currently checks for `war`
  - so default war-objective inference depends heavily on explicit labels instead of a robust typed mapping
- strategist review-contract metrics are still heavily opener/conquest flavored:
  - contact, city founding, war declaration, rival visibility, and action-surface mismatch are first-class
  - science-specific milestones and deterrence-break signals are not
- planner decision-mode inference is also conquest-friendly by default:
  - recognized modes mostly cover expansion, assault, recovery, and staging
  - unrecognized stages currently fall back to `stage_briefly`
- some of the strongest generic empire choice surfaces already do the right thing:
  - research candidates use existing AI tech weights
  - policy candidates use preferred-victory-aware branch priorities
  - non-pressure city automation still leans on legacy construction logic
- the strategist prompt itself still carries domination-shaped priors:
  - the schema example uses `winPath = Domination`
  - the example decision mode is `stage_briefly`
  - tiny-duel prompt framing explicitly mentions pressure on the only rival
- There is no real typed mode for:
  - defend while booming
  - deter while expanding
  - protect science tempo without converting into conquest

This means a tech-only game is currently vulnerable to becoming war-shaped in several different layers even if the top-level victory heuristic is pointing at science.

## Packet-Level Gaps

The biggest routing issue is not that the engine fails to compute a victory preference. It is that the victory preference is not preserved clearly enough across packet boundaries.

Today:

- `AgentEmpireObservation` computes:
  - enabled victories
  - preferred victories
  - heuristic victory goal
  - heuristic victory focus
  - next milestone
- `AgentStrategistBrief` only exposes:
  - `enabledVictoryTypes`
  - broad rival threat summaries
- `AgentPlannerBrief.strategy.winPath` currently resolves to:
  - strategist memo `winPath`, or
  - `empireObservation.victoryGoal`
  but not the richer heuristic victory packet
- raw `AgentEmpireObservation` is not directly provided to either model prompt:
  - strategist sees `AgentStrategistBrief`
  - tactician sees `AgentPlannerBrief`
  so any victory-routing fact that is not copied into those briefs is effectively invisible to the LLM stack

This matters because it means the most informed victory routing is computed early, but the strategist and tactician are still asked to infer too much from archetype, rival visibility, and war-shaped packet sections.

There is also a surface-shaping problem:

- after contact on duel maps, planner brief currently truncates diplomacy choices to one item
- diplomacy ranking places `declare war` above `research agreement`

So even before the LLM reasons about the turn, the action surface can already be silently nudged toward conquest.

## What Already Works

Some important pieces are already stronger than they first looked, and the design should preserve them:

- single-victory settings already influence preferred-victory routing in the base civ logic
- policy priorities already read preferred victory types
- research candidates already come from existing AI tech weighting
- non-pressure city build ranking can still use general construction automation
- observability already records turn-start economy and packet context richly enough to support better regression analysis

This is useful because it means the safest path is not to replace the whole empire stack. It is to stop war-shaped packet routing from drowning out the good general-purpose signals that already exist.

## Design Intent

The engine should own constraints and mode routing.

The LLM should still do the real strategic and tactical reasoning, but inside a packet that already answers:

- what victory types are legal
- whether one victory type is effectively forced by settings
- what the primary win path is likely to be
- what military is for in this game state
- what the next concrete checkpoint is for that win path

The packet should not make the model infer all of that indirectly from map size plus a pile of mixed cues.

## Guiding Principles

- Do not globally nerf aggression.
- Do not remove the current domination machinery when domination is the live plan.
- Do not rely on one giant prompt with every doctrine mixed together.
- Prefer typed routing and selective priors over soft prompt wording.
- Keep the architecture symmetric: science mode should get coherent positive scaffolding, not only the absence of domination bias.
- Preserve the stateless design by making the per-turn packet and memory slices carry the important mode information explicitly.

## Current Implementation Status

The first additive routing slice is now implemented in code.

The broader refactor is not complete yet.

The current state is:

- complete:
  - victory-intent routing and legality
  - selective tiny-duel cheat-sheet routing
  - initial diplomacy / campaign-context / combat auto-fill gating
- partially complete:
  - race-rival vs campaign-rival separation
  - explicit non-conquest `militaryPurpose` propagation in live planner packets
  - war-facing packet suppression in non-conquest games
  - the science-side behavior slice
  - observability and dashboard truthfulness for the new packet fields
- not complete yet:
  - fresh trace validation of the new science-side tactical posture
  - science-friendly review cadence and recovery logic
  - domination conversion / force-composition cleanup
  - full cleanup of string-shaped military inference in lower layers

What landed:

- a new typed victory-intent layer:
  - `AgentVictoryIntentObservation`
  - `AgentVictoryIntentMemory`
  - `AgentVictoryIntentResolver`
- strategist `winPath` sanitization at memo-apply time:
  - illegal or disabled victory labels no longer persist into durable memory
  - if only one victory type is enabled, that legal victory becomes the stored routed win path
- victory intent is now threaded into:
  - `AgentMemory`
  - `AgentStrategistBrief`
  - `AgentPlannerBrief`
  - observability turn-start logs
- planner routing now reads `victoryIntent.effectiveWinPath` instead of falling back only to memo-shaped `victoryGoal`
- duel diplomacy truncation is now victory-aware:
  - the planner keeps the old `take(1)` duel behavior only when `militaryPurpose == conquest`
  - otherwise the packet keeps a wider diplomacy surface
- combat auto-fill into city-target roles is now gated:
  - untouched combat units no longer auto-inherit `attack_target_city` or `stage_near_target_city` unless war is live or `militaryPurpose == conquest`
- race rival and campaign rival are now split in memory:
  - `raceRivalCiv`
  - `campaignRivalCiv`
  - legacy `primaryRivalCiv` is still dual-written as a compatibility shadow
- the traces show this split is not behaviorally complete yet:
  - in the fresh tech-only run, first contact still collapsed `raceRivalCiv` and `campaignRivalCiv` onto Persia immediately
  - the live planner packet also still omitted an explicit non-conquest `militaryPurpose`, which helps explain why defensive ranged units still blob into a generic anchor posture
- the next behavior slice is now partially implemented in code:
  - `militaryPurpose` and `economicPosture` are now always encoded into the live `victoryIntent` packet, even when they sit on default values like `deterrence` or `boom`
  - non-conquest routing no longer auto-persists a `campaignRivalCiv` just because first contact happened
  - planner decision focus can now rewrite stale war-like memo modes into non-conquest modes such as `defend_and_boom` and `deter_while_expanding`
  - city scoring is now victory-intent-aware for non-conquest science games, with new penalties for excess military churn, Barracks drift, and unit upkeep overshoot
  - unit auto-fill is now more willing to push excess deterrence units into exploration instead of letting them all inherit local `hold_position` anchors
  - the tiny-duel science cheat sheet now explicitly teaches one-Scout tempo, active Warrior scouting, and non-blob deterrence posture
- war-facing packet shaping is partially gated by victory intent:
  - `campaignContext.objectiveTarget` is only surfaced when war is live or `militaryPurpose == conquest`
  - pressure-context surfacing in the planner now consults `militaryPurpose`
- strategist prompt routing is less overfit for tech-only tiny duel:
  - the strategist prompt now receives explicit legality rules around `memo.winPath`
  - the tiny-duel strategist cheat sheet is now victory-aware
  - a tiny-duel science-snowball cheat sheet now replaces the domination cheat sheet when the routed win path is `Science`
- observability detail capture was widened after the refactor:
  - the new `victoryIntent*` turn-start fields pushed `worldFactsJson`, `plannerBriefJson`, and other late detail keys past the old 30-key cap
  - turn-start replay traces now retain the full packet payload again so dashboard world facts and timeline views can render
- dashboard truthfulness was tightened for live runs:
  - live status labels now prefer `fallback_legacy` and `plan_missing` over a generic `Replanning` label
  - this prevents turns that actually fell back from being misread as clean replans in the frontend

What is intentionally not done yet:

- a dedicated science-side middle posture such as `defend_and_boom` / `deter_while_expanding`
- science-specific review-trigger vocabulary and cadence
- deeper science-mode packet facts such as worker sufficiency, deterrence threshold, and army overspend
- full migration away from string-scanned war cues in campaign-control and city routing
- scorer cleanup beyond the first routing gates

## Evaluation Snapshot

The fresh rebuilt traces below are still the pre-second-slice baseline. They explain why the next behavior patch was needed.

The newest code changes in this document have not been re-evaluated on fresh traces yet.

### What the fresh traces proved

- the same tiny-duel map now routes differently under different victory settings:
  - the all-victories run still commits to `Domination`
  - the tech-only run still commits to `Scientific`
- the refactor did not collapse the domination opener into a softer generic line
- the tech-only run no longer drifted into an explicit conquest packet just because the rival eventually appeared

### What the fresh tech-only trace exposed

- the run did build exactly one Scout, but too late:
  - Scout arrived on turn `8`
  - the opening still felt worker / checkpoint driven rather than information-seeking
  - the starting Warrior oscillated between `autoexplore` and `stopautoexplore` instead of providing a clean first-contact scouting pattern
- the overbuild starts before contact, not because of contact:
  - `Writing` was delayed until turn `35`
  - by turn `35`, the empire already had `5` cities, `13` military units, and only `2` gold left
  - repeated Archer choices began well before the rival was visible, so the core issue is science-mode packet/scoring shape, not only first-contact drift
- military stayed too sticky for a science-first game:
  - by turn `40`, the empire had `13` military units across `5` cities
  - by turn `70`, it had `20` military units despite the game being tech-only
- the defensive posture was still unnatural:
  - Archers were repeatedly anchored in a Berlin/Hamburg cluster via `hold_position` style assignments
  - that created the exact "road-blocking capital blob" problem seen in the frontend
  - later turns began retasking some of those units to `auto_explore`, but that happened after the army had already become too expensive
- the economy eventually collapsed under the defensive blob:
  - gold fell from `-36` on turn `60` to `-223` on turn `70`
  - military count then crashed from `20` on turn `70` to `13`, `8`, and then `0` by turn `73`
  - this was not the planner deliberately disbanding units; it was a bankruptcy-style collapse after the army budget had already gotten out of control
- planner quality still degraded into brittle science recovery loops:
  - the run got trapped in Library / treasury recovery checkpoints
  - the live packet still collapsed `raceRivalCiv` and `campaignRivalCiv` onto Persia at first contact
  - the live `victoryIntent` packet still lacked an explicit non-conquest `militaryPurpose`, which is likely why the posture kept defaulting to generic anchoring behavior

### What the fresh all-victories trace exposed

- the domination route is intact, but the conversion is still weak:
  - Germany declared war on Persia on turn `21`
  - it stayed in a domination-shaped memo for the entire run
  - but it had not captured a Persian city by turn `85`
- the opener remained strong at first:
  - Germany was ahead on score, cities, force, and science through the midgame
  - for example, on turn `40` Germany led `236` to `189` on score and `524` to `10` on force
- the later game showed the real weakness:
  - Germany kept churning Warriors and Spearmen and repeatedly staging units toward Parsagadae
  - strategist memos became fixated on "escorted frontline replacement stream" and "fresh captor" maintenance
  - planner packets repeatedly reported very high capture-unit counts but `0 ranged support near Susa`
  - so the system kept preserving and feeding melee pressure without assembling the sort of ranged-backed assault package that actually converts into a city capture
  - meanwhile Persia eventually passed Germany on score, population, and science
  - by turn `84`, Germany trailed `394` to `335` on score and `23` to `15` on science despite still having the larger army
- this means the refactor preserved domination routing, but it did not solve the older domination conversion problem:
  - the packet still knows how to stage and sustain pressure better than it knows how to finish the conquest
  - the force mix is still too melee-heavy and replacement-heavy once war is live

So the routing refactor is still ahead of the behavior refactor:

- packet truth is improved
- science-side play quality is still too defensive and too brittle
- domination-side play quality is still too feeder-loop oriented and not decisive enough

## Status Against The Plan

Against the implementation order below, the current status is:

- effectively done:
  - steps `1` through `5`
- partially done:
  - steps `6` through `10`
- next:
  - validate the newly-landed science-side behavior slice on fresh traces
  - then start the domination-conversion cleanup
- later:
  - steps `11` through `13`

In plain terms:

- the routing layer is in place
- the first science-side behavior-quality slice is now in code
- the next work is to validate that slice on fresh traces, then move to domination conversion
- exact score tuning should still wait until after that validation

## Next Slice

The science-side behavior slice described above is now partially implemented. The immediate next step is validation, not another large refactor.

Immediate validation goals:

1. Re-run fresh `7071` / `7072` traces and check whether the new packet now carries explicit `militaryPurpose` in the tech-only planner brief.
2. Check whether the tech-only opener now:
   - gets one Scout out earlier
   - keeps the Warrior scouting more naturally
   - delays or suppresses repeated Archer spam
   - avoids the Berlin/Hamburg ranged blob
3. Check whether the tech-only midgame now keeps military closer to a deterrence budget instead of climbing to `13` units by turn `35`.
4. Check whether first contact in tech-only now keeps `campaignRivalCiv` empty unless real war or emergency defense exists.

If that validation is positive enough, the next implementation slice after it should be the domination-conversion cleanup.

What should happen just after this slice, but not be mixed into it unless necessary:

- a domination-conversion cleanup slice
- specifically:
  - reduce over-fixation on feeder / escort churn once a real attack package already exists
  - improve "front converts into capture" logic so all-victories domination does not spend 60 turns staging and stabilizing without taking a city
  - add composition awareness so domination packets stop treating "more melee replacements" as the default answer when ranged support near the target is still missing

What should *not* happen yet:

- broad military score tuning
- global peaceful-heuristic expansion
- weakening the domination stack in all-victories games

Recommended validation after that slice:

- rerun tech-only and all-victories tiny-duel games on the rebuilt docker stack
- let both runs reach first contact
- confirm that:
  - tech-only stays science-primary after contact
  - military remains instrumental unless defense becomes necessary
  - all-victories still keeps the domination opener and later conversion posture

## Proposed Architecture

### 1. Add A Typed Victory-Intent Layer

Add a small engine-owned typed layer that sits beside the current notebook and campaign-control state.

Suggested fields:

- `allowedVictoryTypes`
- `forcedVictoryType`
- `heuristicPrimaryVictory`
- `heuristicSecondaryVictory`
- `primaryVictoryFocus`
- `nextVictoryMilestone`
- `militaryPurpose`
- `economicPosture`
- `pivotTriggers`

Suggested meanings:

- `forcedVictoryType`: set when game settings leave only one legal win type
- `militaryPurpose`: values such as `conquest`, `deterrence`, `defense`, `containment`
- `economicPosture`: values such as `expand`, `boom`, `convert`, `recover`

This layer should be visible in both strategist and tactician packets every turn.

It should not live only inside `AgentEmpireObservation`. It should be explicitly threaded into:

- strategist brief
- planner brief
- stored memory summary
- selective cheat-sheet routing
- lower-level surface builders that currently infer posture from memo wording

### 2. Sanitize Strategist Win Path At The Engine Boundary

Do not rely only on prompt compliance.

When strategist output is applied:

- if `memo.winPath` is not in `enabledVictoryTypes`, discard or rewrite it
- if only one victory type is enabled, prefer that as the stored `winPath`
- preserve the model's natural language reasoning, but keep the stored typed routing legal

This prevents illegal or stale victory labels from becoming durable memory truth.

### 3. Split Race Rival From Military Rival

The current `primaryRivalCiv` is doing too much work.

We should separate:

- `raceRivalCiv`: the civ that matters most for the current victory race
- `campaignRivalCiv`: the civ that is the current military target or frontier concern

In tech-oriented games, it is correct for `raceRivalCiv` to exist without automatically creating a conquest target.

### 4. Make Prompt Contracts Obey Victory Intent Explicitly

The strategist should not have to infer legality softly.

Prompt rules should say:

- `memo.winPath` must be one of `enabledVictoryTypes`
- if exactly one victory type is enabled, `memo.winPath` must be that type unless the memo is describing a short emergency defense state
- when domination is disabled, military action may still be correct, but only as support for the legal win path rather than as the terminal story

The tactician packet should also carry the current victory intent directly rather than depending only on older memo wording or old campaign residue.

This should include fixing the current semantic trap where downstream code reads `victoryGoal` as if it were the fresh engine judgment even though it currently mirrors the strategist memo.

### 5. Route Cheat Sheets By Victory Profile Plus Archetype

Cheat sheets should be selective overlays, not the root router.

Split current priors into:

- a shared `tiny duel tempo` baseline
- a `tiny duel domination conversion` overlay
- a `tiny duel science snowball` overlay
- later, optional overlays for other settings if needed

Selection should depend on:

- map archetype
- allowed or forced victory types
- current victory intent
- current phase

This preserves current domination strength when domination is the live path while avoiding domination overfitting in tech-only settings.

### 6. Narrow Military Campaign Context To Real Military Posture

Not every known rival city should become an active military objective.

The system should distinguish:

- rival awareness
- victory-race awareness
- active military campaign context

`campaignContext`, `objectiveTheater`, `captureReadiness`, and tactical combat doctrine should normally appear only when:

- the empire is at war
- the strategist intent is truly conquest-oriented
- or there is a real emergency defense state

Visible enemy cities and remembered anchors can still be stored in the notebook, but they should not automatically flip the whole packet into war-facing mode.

In practice this means `campaignContext`, `objectiveTheater`, `captureReadiness`, operational city-target unit candidates, and combat auto-fill should all consult the same typed routing instead of activating independently.

### 6A. Make Diplomacy Surface Routing Victory-Aware

The duel diplomacy surface is currently too lossy.

For victory-aware routing:

- do not blindly truncate duel diplomacy to one choice when the victory profile is non-conquest
- if diplomacy must be truncated, rank it using victory intent
- in tech-oriented games, research agreements and low-cost economic diplomacy should not disappear behind a higher-scored war declaration by default

This is not about removing war from the surface. It is about preventing the surface itself from forcing a conquest-shaped story too early.

### 7. Give Science Mode A Real Typed Scaffold

Domination currently has richer typed support than science. That asymmetry should be fixed.

Science-oriented packets should surface compact facts such as:

- expansion checkpoint status
- worker sufficiency
- science tempo
- key building windows
- deterrence threshold
- army overspend
- holding costs of non-converting military

The agent should be able to tell the difference between:

- "I need enough military to stay safe while I snowball"
- "I should pivot into conquest because conquest is my actual win path"

### 7A. Make Review Cadence Victory-Aware

If science posture is meant to survive in a stateless loop, the review contract vocabulary also has to support it.

Today the canonical review metrics are mostly about:

- contact
- city founding
- war declaration
- rival city visibility
- rival capital visibility
- action-surface mismatch

Science-oriented routing likely needs additional script-verifiable boundaries such as:

- next victory milestone reached
- key science infrastructure completed
- deterrence posture broken
- rival science threat spiked

This does not need to be solved entirely up front, but the design should acknowledge that science play needs refresh boundaries just as much as conquest play does.

### 8. Replace Fragile Free-Text Routing With Typed Posture

Lower-level systems should depend less on free-text phrases like `capture`, `war`, or `frontier` embedded in memo wording.

Instead, city and tactical surfacing should read typed posture from victory intent and campaign control:

- `militaryPurpose`
- `economicPosture`
- `campaignStage`
- `battleReadiness`
- `supplyHealth`

This reduces overfitting to domination-shaped memo language.

This should also reduce dependence on other stringly checks such as:

- `looksWarAware()`
- free-text objective token overlap
- checkpoint inference that depends on mode-family string labels

### 9. Keep Legacy General-Purpose Scoring As The Default In Non-Conquest Modes

Some of the existing code already has useful general-purpose weighting:

- forced single-victory preference
- policy priorities
- research candidate weighting
- non-pressure city construction automation

The safest design is not to replace all of that with new custom logic.

Instead:

- keep legacy-style general ranking as the default
- only switch into custom conquest-heavy overrides when typed military posture really says to do so

This reduces the risk of damaging current domination play while still improving tech routing.

### 10. Gate Auto-Filled Combat Assignments By Military Purpose

Auto-fill is a major hidden source of behavior in a stateless loop.

In non-conquest modes, untouched combat units should not automatically inherit city-assault or prewar staging roles just because an enemy city is known.

Minimum safe rule:

- do not auto-fill `stage_near_target_city` or `attack_target_city` unless the empire is at war, explicitly launching, or in a real conquest campaign

Longer-term improvement:

- add lighter-weight defensive roles such as frontier screen, escort, or protect-expansion posture

### 11. Keep War Legal In Tech Mode, But Reclassify Its Role

Do not remove war declarations, unit builds, or defensive operations from the action surface in tech-oriented games.

Instead, make the live packet communicate that military is:

- for self-defense
- for deterrence
- for protecting expansion and science tempo
- for emergency punishment when needed

and not automatically for conquest unless the victory profile or strategist memo truly makes that the main line.

### 12. Add A Real Defense Or Deterrence Decision Mode

The current packet is strongest at:

- expansion
- launch timing
- assault
- recovery

It is weak at the middle ground that tech play often needs:

- defend efficiently
- deter without overbuilding
- keep booming while screening the frontier

Add a decision mode and packet language for that middle ground so the model is not forced to express every non-peaceful posture as staging for conquest.

## Graceful Refactor Shape

The safest implementation is additive and layered, not a rewrite.

### Source Of Truth

The clean ownership split should be:

- engine facts:
  - enabled victories
  - preferred victories
  - heuristic victory plan
  - threat snapshot
  - current economy and military state
- routed intent:
  - effective win path
  - military purpose
  - economic posture
  - race rival
  - campaign rival
  - next checkpoint
- strategist memo:
  - teammate-facing narrative and bounded expectations
  - not the sole source of truth for legality or routing

In other words:

- the strategist can describe and reinforce the line
- the engine should decide what line is actually legal and how that line should gate downstream packet shaping

### Add New Types, Do Not Replace Old Ones First

The graceful move is to introduce a new typed layer beside the existing memo and campaign memory rather than rewriting those structures immediately.

A good shape would be:

- `AgentVictoryIntentObservation`
- `AgentVictoryIntentMemory`
- `AgentVictoryIntentResolver`

The resolver should consume:

- current settings and enabled victories
- preferred-victory heuristics
- sanitized memo win path
- current empire/threat snapshot
- current campaign-control state

and produce one compact typed routing object.

That object can then be embedded into:

- strategist brief
- planner brief
- memory
- observability traces

without requiring every old subsystem to be rewritten at once.

### Keep Strategist Output Schema Mostly Stable At First

A graceful refactor should avoid expanding the strategist output contract too early.

Why:

- the current strategist output already has a lot of required structure
- adding many new authored fields increases parse risk
- the most important missing information is mostly engine-computable anyway

So the better first step is:

- keep strategist schema mostly unchanged
- sanitize `winPath`
- derive typed victory intent engine-side
- expose the new typed intent to both model prompts as input

Only after the routing layer is stable should we decide whether the strategist needs to author any new fields.

### Dual-Write, Single-Read Migration

During the transition, new typed routing should coexist with legacy fields.

Recommended migration pattern:

1. compute `victoryIntent`
2. store it in memory
3. keep writing existing memo/campaign fields for compatibility
4. migrate one consumer at a time to read `victoryIntent`
5. once consumers are moved, shrink the number of places that depend on old string fields

This avoids a brittle big-bang cutover.

In practice that means legacy fields such as:

- `lastStrategistMemo.winPath`
- `campaign.primaryRivalCiv`

should remain as compatibility shadows for a while rather than being renamed or deleted immediately.

### Move Consumers Behind Small Routing Helpers

Do not make every subsystem interpret victory intent on its own.

Instead, add small helper functions that answer questions like:

- should this turn surface war-facing campaign context?
- should duel diplomacy truncate to one choice?
- should combat units auto-fill into city-target roles?
- should objective theater highlighting activate?
- which rival is the race rival?
- which rival is the campaign rival?

That lets us migrate behavior one gate at a time while keeping the logic centralized.

### Prefer Gate Changes Before Score Changes

Where possible, keep existing scorers and just change whether their conquest-specific branches activate.

That means:

- leave research and policy weighting alone initially
- leave general city ranking alone initially
- stop conquest-specific overlays and auto-fill from firing in the wrong settings

This is the core of the graceful approach: route better first, retune later only if needed.

### Add Observability Before Heavy Behavior Changes

The new routing layer should be visible in logs before it becomes a hard behavioral dependency.

At minimum, observability should record:

- effective win path
- military purpose
- economic posture
- race rival
- campaign rival
- whether conquest packet gates activated and why

This makes the batch evaluator much more useful for catching accidental domination regressions.

### Suggested Migration Phases

Phase 0: additive routing and telemetry

- add resolver and typed victory-intent object
- log it
- expose it in briefs
- do not change gameplay gates yet

Phase 1: legality and packet truthfulness

- sanitize strategist `winPath`
- feed typed victory intent into strategist and tactician prompts
- split race rival from campaign rival

Phase 2: surface routing

- route cheat sheets by intent
- make duel diplomacy routing intent-aware
- gate campaign context, objective theater, and combat auto-fill by typed military purpose

Phase 3: non-conquest posture support

- add defend/deter mode
- add science-oriented packet fields
- add science-friendly review metrics

Phase 4: cleanup

- remove or reduce string-scanning fallbacks
- clean up campaign-control inference mismatches
- retire legacy fields that are no longer needed as routing inputs

## Regression Safety

These changes should not degrade domination if they are implemented as routing, not as global dampening.

Low-risk changes:

- typed victory-intent routing
- strategist win-path sanitization
- split race-rival versus campaign-rival state
- explicit prompt legality rules
- selective cheat-sheet injection
- science-mode typed scaffolding
- gating auto-fill and war-facing packet construction by typed military purpose

High-risk changes:

- globally reducing military scoring
- globally extending peaceful heuristics across all duel games
- suppressing war declarations everywhere
- replacing strong domination heuristics with vague generic wording

The intended safety rule is simple:

- when domination is the live path, the current domination stack should still fire
- when domination is not the live path, the packet should stop pretending that conquest is the default story

The safest way to preserve domination performance is to change routing before changing scores:

- keep existing domination overlays and conquest packet sections intact
- only change when they are activated
- avoid broad numerical tuning until the activation logic is correct

## Implementation Order

To minimize regression risk, the work should land in this order:

1. Add a resolver-backed typed victory-intent object and expose it in memory, strategist brief, planner brief, and observability without changing downstream gates yet.
2. Sanitize strategist `winPath` at apply-time and add prompt legality rules.
3. Expose the richer victory packet everywhere it matters:
   - preferred victories
   - heuristic victory goal
   - heuristic victory focus
   - next milestone
4. Split `raceRivalCiv` from `campaignRivalCiv`.
5. Rework cheat-sheet selection to route by victory profile plus archetype.
6. Make duel diplomacy surface routing victory-aware before touching broader scoring.
7. Narrow war-facing packet construction so visible rival cities alone do not create conquest posture.
8. Gate auto-filled combat assignments and operational city-target surfacing by typed military purpose.
9. Add science-oriented packet fields and a defend-or-deter middle mode.
10. Make review-contract metrics and refresh boundaries more victory-aware.
11. Clean up string-based routing leftovers and inference mismatches in campaign control.
12. Update lower-level scorers and surfacing to read typed posture instead of domination-shaped free text.
13. Only then tune exact scoring values if evaluation shows more adjustment is needed.

Current progress:

- completed: `1` to `8`
- next target: `9`
- held until after `9` and `10`: `11` to `13`

## First Safe Implementation Slice

This slice is now landed.

What shipped in this slice:

1. Add the typed victory-intent object and log it in observability.
2. `winPath` sanitization at strategist memo apply-time.
3. Thread victory-intent packet fields into strategist brief and planner brief.
4. Split race rival from campaign rival in memory and packet views.
5. Make duel diplomacy truncation conditional on victory intent instead of unconditional.
6. Block combat auto-fill into city-target roles unless military purpose is conquest-like or war is already live.
7. Gate planner pressure-context shaping and objective-target surfacing behind routed military purpose.
8. Make the tiny-duel strategist cheat sheet victory-aware and add a science-snowball variant for routed science games.

Why this slice was still low risk:

- it mostly changes routing and packet truthfulness
- it gives us visibility into the new router before we make it a hard dependency
- it preserves the existing domination machinery when domination remains the active path
- it reduces silent conquest drift in tech-only games before we touch deeper scorer behavior

One more near-term cleanup that is still worth tracking is the campaign-control mode-family mismatch:

- `campaignModeFamily()` currently returns `military`
- `deriveCampaignObjectiveKind()` currently looks for `war`

That should be cleaned up, but it is slightly more behavior-shaping than the packet-plumbing slice above, so it can safely land just after the routing changes.

## Evaluation Plan

Do not evaluate this work against only one setup.

Use a matrix that includes at least:

- tiny `1v1`, all victories enabled
- tiny `1v1`, tech only
- tiny `1v1`, domination only
- one or two non-duel control settings

Track more than win rate. Also track:

- war declaration timing
- war declarations per game
- early military count
- city count over time
- science per turn over time
- research timing
- key build timing
- whether tech-only games drift into unnecessary conquest posture
- whether untouched combat units accumulate offensive auto-filled assignments

The existing batch runner is already a good base for regression testing. We do not need a brand-new harness first.

Important note:

- observability already records turn-start packet details such as science per turn, gold, happiness, city count, unit count, and candidate counts
- match and turn summaries do not yet expose those metrics directly

So the likely follow-up is:

- extend the evaluation analyzer and summaries to derive science-tempo and posture metrics from existing observability traces
- add new metrics only where the existing trace truly does not carry enough information

The main regression rule is:

- all-victories tiny duel should not lose its current domination conversion strength
- tech-only tiny duel should stop treating early conquest as the primary story

## Acceptance Criteria

We should consider this successful when:

- the strategist consistently chooses a legal and setting-appropriate win path
- the tactician packet carries that win path clearly enough that it does not fall back to stale domination habits
- tiny-duel all-victories games still produce strong domination play
- tiny-duel tech-only games produce expansion-plus-science snowball with only instrumental military use
- the system remains general enough to add future victory profiles without rewriting the whole agent around one archetype

## Short Version

The fix is not "make the agent less domination-focused."

The fix is:

- make victory intent first-class
- route priors selectively
- give science the same quality of scaffold that domination already has
- preserve current domination machinery when domination is actually the right plan
