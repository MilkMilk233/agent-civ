# Agent Unit-Assignment Refactor

## Goal

Refactor unit control so important unit jobs behave like real ongoing assignments instead of
one-turn selections with a memory note attached.

The intended model is closer to city production than to repeated tile micromanagement:

- each unit can carry one active assignment
- the tactician can keep, replace, or clear that assignment
- untouched units advance one heuristic step of their assignment each turn
- finite assignments complete when their completion condition is met
- persistent assignments continue until switched or invalidated

This should reduce the amount of repeated low-level movement the stateless tactician has to
reconstruct every turn.

## Why this refactor is needed

The current unit system is split across two different models:

- `auto-explore` is a real ongoing engine mode
- most military roles are only one-turn candidate actions plus `UnitAssignmentMemory`

That split is causing the exact failure we have been seeing in traces:

- the LLM can pick good-sounding military assignments
- the assignments appear in memory and in `assignmentProgress`
- but many units still end turns with movement left because the assignment is not actually
  executing as a standing behavior

Symptoms:

- `assignment_at_risk` appears often even when the assignment is still strategically correct
- units show `Idle with movement left` while still carrying stage or reinforce roles
- only `auto-explore` keeps progressing without being re-selected
- explicit tactician touches and carried assignment memory do not have one clean override rule

## Design principles

- The tactician should assign roles, not rebuild every march from zero.
- Ongoing assignments should be interruptible.
- A unit touched by tactician this turn should treat that as a real override.
- The lower heuristic should own repetitive execution, not the LLM.
- Assignment state should be inspectable in memory, observation, and trace output.
- The first pass should improve the live path without requiring human-perfect battlefield AI.

## Target model

Each unit carries one active assignment with a small typed contract.

An assignment should answer:

- what job is this unit doing
- what target or theater anchor is it tied to
- how long should the assignment live
- whether the engine can auto-advance it when untouched

Suggested fields:

- `role`
- `targetX` / `targetY`
- `detail`
- `executionMode`
- `completionPolicy`
- `lastProgressTurn`
- `staleAfterTurn`

### Execution modes

- `memory_only`
  - carried intent only; no automatic step
- `deferred_heuristic`
  - after planning, if the unit was untouched, run one heuristic step
- `engine_auto`
  - unit already has a native engine mode and should keep using it

### Completion policies

- `until_arrival`
  - completes when the target tile or target condition is reached
- `until_switched`
  - remains active until tactician explicitly replaces or clears it
- `until_invalid`
  - remains active until the target or context becomes invalid

## Assignment families

### Persistent exploration / movement

- `auto_explore`
  - persistent until switched
  - uses the game's existing explore automation
- `move_to_tile`
  - finite; completes on arrival
  - useful for exact long walks that should not require repeated `unit_move`

### Prewar / war operational roles

- `stage_outside_border`
  - move toward a staging ring outside the target city
  - then hold that ring until switched
- `reinforce_assault`
  - march toward the target city axis as replacement depth
- `assault_city_ring`
  - move into the useful assault ring and keep pressure on the city
- `recover_then_rejoin`
  - fall back to safer tiles, heal, and rejoin the assault
- `preserve_capture_unit`
  - keep one healthy melee close enough to take the city later

### Civilian / finite job roles

- `settle_city_site`
- `improve_tile`

These can later use the same lifecycle, but they are not the first focus of the military pass.

## Lifecycle

Each turn should follow this order:

1. Build observation from the current board and current assignment memory.
2. Let the tactician inspect each unit's current assignment and decide whether to:
   - keep it
   - replace it
   - clear it
   - issue a one-turn direct action
3. Execute the tactician plan.
4. After planning, auto-advance untouched units whose assignments support deferred execution.
5. Reconcile memory:
   - preserve untouched ongoing assignments
   - replace assignments for touched units
   - complete finished finite assignments
   - prune invalid or stale assignments

## Override rule

A touched unit should not quietly keep its old assignment unless the new command explicitly
re-establishes that assignment.

That means:

- if the tactician touched a unit this turn, the old carried assignment is considered replaced
- if the new command implies a new ongoing assignment, persist that new assignment
- if the new command is transient and does not imply a new ongoing assignment, clear the old one

This rule is important because it matches the mental model we want:

- assignments are real modes
- tactician intervention should mean something

## Why this is better for a stateless LLM

The tactician call is stateless. That means repeated exact `unit_move` reconstruction is fragile.

Ongoing assignments give the LLM a healthier control interface:

- pick the right job
- inspect whether the job is still healthy
- retask only when needed

This is much closer to how a human thinks at the operational level.

## Pass plan

### Pass 1: Shared assignment lifecycle

Scope:

- add explicit execution/completion semantics to `UnitAssignmentMemory`
- make untouched units auto-advance one step for supported assignments after planning
- unify `auto-explore` with this lifecycle instead of treating it as a completely separate system
- make direct tactician touch replace or clear old assignments cleanly
- improve `assignmentProgress` so it reflects ongoing assignment viability instead of only
  matching a surfaced candidate

Status:

- Completed.

Notes:

- This pass should support at least:
  - `auto_explore`
  - `move_to_tile`
  - `stage_outside_border`
  - `reinforce_assault`
- The live path now gives carried unit assignments explicit execution/completion semantics and
  auto-advances untouched supported assignments after planning.
- Tactician touch now replaces or clears the previous carried unit assignment instead of silently
  preserving it.
- `assignmentProgress` now recognizes ongoing assignment viability from the assignment engine, not
  only from re-surfaced matching candidates.
- `assault_city_ring`, `recover_then_rejoin`, and `preserve_capture_unit` now participate in the same
  deferred assignment-step framework, but their heuristics are still intentionally simple.

### Pass 2: Military-role automation

Scope:

- upgrade assault-family roles into stronger ongoing heuristics
- keep one healthy captor
- let damaged units recover and rejoin
- let rear replacements keep stepping toward the line

Status:

- Completed.

Notes:

- Assault-family roles now execute as objective-aware ongoing heuristics instead of one-turn
  move wrappers.
- `reinforce_assault` keeps marching toward the target city axis and can auto-transition into
  the assault role when the unit reaches the useful range band.
- `assault_city_ring` now looks for target-city attacks first and otherwise keeps stepping into
  useful firing or surround tiles.
- `recover_then_rejoin` now falls back, heals in place when appropriate, and automatically
  returns to the reinforcement flow once the unit is healthy enough.
- `preserve_capture_unit` now avoids low-value trades and only attacks when the capture window
  looks safe enough.
- Military `assignmentProgress` no longer depends on the same operational candidate resurfacing
  next turn; assignment health is now driven by the assignment engine itself.

### Pass 3: Prompt / observability cleanup

Scope:

- make prompts describe unit assignments as real ongoing modes
- improve dashboard and trace visibility for assignment mode, completion policy, and current status
- remove old special-case wording once the unified assignment path is stable

Status:

- Completed.

Notes:

- assignment progress now exposes the real assignment contract in observation: role, status,
  target, execution mode, completion policy, and assignment timing.
- tactician and strategist prompts now explicitly describe assignment progress as a real ongoing
  mode rather than a last-turn note.
- the dashboard now shows assignment execution/completion semantics directly instead of only
  showing role and switch cost.
- observability now records per-role counts for each post-planning assignment tick so traces show
  what the deferred assignment engine actually advanced.

## Non-goals for pass 1

- perfect group tactics
- globally optimal slot assignment for every melee and ranged unit
- human-level city assault sequencing

The first success bar is simpler:

- scouts keep scouting
- move orders can persist across turns
- staged or reinforcing units keep marching when untouched
- explicit tactician intervention cleanly replaces old assignment state

## Current battle-performance gap

The assignment refactor gives us the right control shape, but the current battle execution still
has an important limitation:

- assignments are now good at saying what each unit is supposed to do
- but execution is still mostly driven by local per-unit heuristics

That means the system is much healthier than raw `unit_move`, but it still is not the same thing
as a competent city-conquest engine.

In practice, the current weak spots are:

- only light per-turn slot reservation around the city; no persistent or globally optimal slot solve
- no coordinated surround planning
- no group-level attack ordering
- no strong policy for exactly one protected captor plus backups
- no explicit post-capture or post-failed-assault transition
- too much reliance on each unit greedily choosing its own best next tile

This is why the architecture now feels correct, but battle quality can still plateau.

## Why the next step should be a theater planner

In the classic setup we care about most:

- 2 cities are mainly building military units
- the strategist memo is healthy
- the tactician keeps assigning the right military roles
- one visible target city matters more than everything else

the LLM no longer needs to do the hard part.

The hard part becomes:

- how the lower heuristic coordinates 6-12 units around one target city efficiently

This is exactly where heuristics should take over.

The next architectural step should be:

- keep the current assignment model as the LLM-facing API
- add a target-city battle planner under it

That planner should work at the theater level rather than letting each unit independently solve
its own next move.

## Theater-planner progress

The first implementation step under this plan is now in place:

- explicit city-theater slots already exist for staging, reserve, recovery, ranged assault,
  melee assault, and captor positions
- deferred military assignment execution is now ordered by theater and role so recovery /
  captor / frontline jobs claim space before backline movement
- a light per-theater slot-reservation pass now runs during deferred assignment ticks

This is intentionally still a small planner, not a full group solver. The current behavior is:

- once one untouched unit claims a useful slot around a target city, later untouched units in
  the same theater are penalized away from that exact slot
- slot claims are per-turn and per-theater; they do not persist as long-term stored reservations
- the system still relies on local heuristics for exact movement, but it no longer treats every
  unit as if it were choosing in isolation

This should reduce the most obvious clustering failure:

- several units independently preferring the same "best" ring or reserve tile

## Recommended next architecture

### Keep the current assignment interface

The LLM-facing assignments are still the right abstraction:

- `stage_outside_border`
- `reinforce_assault`
- `assault_city_ring`
- `recover_then_rejoin`
- `preserve_capture_unit`

These roles are understandable, durable, and easy for the stateless tactician to use.

The improvement should happen underneath them, not by replacing them with lower-level commands.

### Add a per-target battle theater planner

For each turn, after the tactician has chosen assignments:

1. group untouched units by target city
2. build a tactical model for each target city
3. solve the current turn for that city as a group
4. execute the chosen steps for the units in that theater

This should replace the current "each untouched unit advances its assignment independently"
behavior for the assault-family roles.

## What the theater planner should do

### 1. Build explicit local slots around the city

The planner should generate and score tactical slots such as:

- prewar staging slots
- frontline melee assault slots
- ranged firing slots
- reserve / refill slots behind the line
- recovery slots
- captor reserve slots

This is better than only reasoning in broad distance bands like "1..2" or "2..3".

Distance bands are still useful, but slots should become explicit objects.

### 2. Assign units to slots without conflicts

This is the single biggest improvement.

Today, multiple units can still prefer the same strong tile. The next version should:

- score unit-to-slot fit
- then choose a non-conflicting allocation

Even a simple greedy matching with occupancy penalties would already be much better.

If needed, we can later use a small assignment solver, since the battle package size is still
tiny enough to be cheap.

### 3. Order attacks at the group level

City conquest usually depends on attack sequence more than on any one unit's local preference.

The group planner should think in a simple staged order:

- ranged softening first
- safe defender-clearing hits second
- melee city hits only when the trade is justified
- captor only when the capture window is safe or strategically required

This is much stronger than letting every unit greedily pick its own best attack in isolation.

### 4. Maintain one protected captor policy

The current `preserve_capture_unit` role is a good start, but the planner should make this more
explicit:

- choose one primary captor
- optionally designate one backup captor
- everyone else should treat preserving that captor as a shared battlefield constraint

This is especially important in the classic early-war melee-heavy setup.

### 5. Treat reserves and recovery as real line-management jobs

Reserves should not merely "move closer".

They should:

- move into explicit backup slots
- fill vacancies after frontline movement or attacks
- avoid clogging the same lane as the captor

Recovery should not merely "go farther away".

It should:

- prefer tiles that remain on the objective axis
- prefer tiles with lower expected punishment
- automatically re-enter the reserve or assault flow when healthy enough

### 6. Add fallback logic when the line clogs

A robust assault planner needs good degraded behavior.

If a unit cannot reach its preferred slot because of occupancy, terrain, or pathing, it should:

- try an alternate slot
- else take a reserve slot
- else hold a useful tile on the axis
- else fortify or recover safely

It should not collapse directly into:

- `assignment_at_risk`
- `Idle with movement left`

### 7. Add explicit post-capture and post-collapse transitions

After the city falls, old city-target assignments should not simply go inert.

The planner should support transitions like:

- captor -> hold / consolidate / garrison
- frontline assault -> recover / hold / advance to next objective
- reserves -> move into the captured city ring or prepare for the next target

Likewise, if the assault stalls badly, there should be a clear downgrade path:

- assault -> recover / stabilize / regroup

instead of many units silently keeping outdated city-assault roles.

## What "robust and smart" should mean here

For this project, "robust and smart" does not need to mean human-perfect battlefield AI.

It should mean:

- the army keeps advancing pressure when the role and target are still valid
- units do not fight each other for the same tile unnecessarily
- one healthy captor is preserved consistently
- damaged frontline units cycle out naturally
- reserves actually refill the line
- ranged units soften before melee wastes itself
- city capture becomes a normal result in clean, favorable setups

That is the real bar we should optimize for first.

## Why this should work well in the classic setup

In the common early domination shape:

- 2 productive cities
- compact map
- one clear rival capital
- mostly melee and a little ranged support

the problem is small enough that stronger heuristics are cheap.

We do not need full-search tactics.

We need:

- a small city-centered battle planner
- explicit slots
- explicit attack ordering
- explicit captor preservation
- explicit refill behavior

Because the units are already assigned by the tactician, the planner does not need to solve the
whole empire. It only needs to solve one battlefield cluster well.

That is a very favorable architecture for heuristic quality.

## Recommended implementation order after the assignment refactor

1. add explicit city-ring / reserve / recovery / captor slot generation
2. add occupancy-aware unit-to-slot matching
3. add group-level attack ordering for units assigned to the same city
4. add explicit captor and backup-captor selection
5. add post-capture and post-stall transitions
6. update observability so each theater tick shows:
   - target city
   - units in theater
   - selected slots
   - chosen attacks
   - blocked units and fallback behavior

## Next refactor: assignment-only unit surface

The assignment engine is now healthy enough that the next major cleanup should not be another
small tactical patch. It should be a contract cleanup:

- the tactician should choose unit assignments only
- the lower heuristic should own all concrete unit execution
- raw unit micromanagement should stop being part of the normal tactician API

This is a different goal from the battle-theater planner work above.

- the theater planner improves how assignments execute
- the assignment-only refactor improves what the tactician is allowed to choose

Both matter, but they solve different problems.

### Why the mixed surface is still a problem

Even after the assignment-engine work, the current unit surface is still split across:

- assignment-like candidates
- raw `unit_move`
- raw `unit_action`
- raw `unitActions`
- raw `legalActionCandidates`
- raw `reachableTiles`
- leftover memory roles that only exist because older raw paths still exist

That mixed surface creates three recurring problems:

1. It makes the tactician contract harder to understand.
   - Sometimes the model is choosing a multi-turn job.
   - Sometimes it is choosing an exact tile.
   - Sometimes it is choosing a raw engine verb.

2. It makes validation and retries brittle.
   - Assignment-type choices and one-turn micro orders obey different rules.
   - Retry logic has to reason about both.

3. It keeps stale implementation categories alive.
   - Some memory roles only exist as bookkeeping for the mixed interface rather than as
     meaningful unit jobs.

The cleaner end state is:

- every meaningful unit choice is an assignment choice
- some assignments are one-shot
- some assignments are finite
- some assignments are persistent
- all assignments are allowed to switch midstream

### Canonical assignment taxonomy

The long-term unit API should revolve around one small canonical list.

#### Persistent assignments

- `auto_explore`
- `hold_position`
- `heal_and_hold`
- `stage_outside_border`
- `reinforce_assault`
- `assault_city_ring`
- `recover_then_rejoin`
- `preserve_capture_unit`

These remain active until switched or invalidated.

#### Finite assignments

- `move_to_tile`
- `settle_city_site`
- `improve_tile`
- `attack_target`

These complete when their target condition is met.

#### One-shot assignments

- `stop_auto_explore`
- `upgrade_self`
- `pillage_here`
- `use_special_ability`

These are still assignments in the API sense, but they resolve immediately and then clear.

### Roles that should be removed rather than preserved

The following roles should not survive as part of the public assignment model:

- `reposition`
- `automation_change`
- `execute_action`
- `transient_wait`

These are residual bridge categories from the older mixed control path.

Their functionality should be absorbed into canonical assignments:

- vague repositioning should become `move_to_tile` or a real operational role
- automation switches should become explicit assignments like `auto_explore` or
  `stop_auto_explore`
- generic execute-action buckets should be replaced by one-shot semantic assignments
- transient wait should not be treated as a real carried assignment

This should be a replacement, not a compatibility layer.

### What the tactician should see after this refactor

For each unit, the tactician should only see:

- current assignment
- assignment category
- assignment status
- surfaced assignment candidates

It should not normally see:

- raw reachable tiles for freeform `unit_move`
- raw engine action names as primary commands
- direct `unit_action` plumbing

The main tactician question should become:

- what job should this unit have now?

not:

- which exact tile or engine verb should I click?

### What validation should mean after this refactor

Assignment validation should stay shallow and semantic.

The validator should check:

- the unit still exists
- the assignment candidate still exists
- the target or objective is still meaningful
- the unit is compatible with that assignment family

It should not reject the whole plan because:

- this exact turn has no immediate good move
- the unit is blocked for one turn
- the lower heuristic chooses to hold instead of stepping

That is execution behavior, not assignment invalidity.

### What execution should mean after this refactor

Once the tactician assigns a unit:

- one-shot assignments resolve immediately and clear
- finite assignments persist until complete
- persistent assignments continue until switched or invalidated

The lower heuristic should own:

- pathing
- exact tile choice
- attack timing
- temporary holds
- fallback behavior when blocked
- auto-transitions like recover -> rejoin

This preserves the intended division of labor:

- tactician chooses intent
- heuristics choose execution

### How many passes this should take

This should be done in 4 focused passes.

That is enough to replace the old design cleanly without trying to squeeze the entire migration
into one risky patch.

#### Pass A: Canonicalize memory and roles

Scope:

- remove residual unit-assignment role categories from memory
- normalize all unit-assignment derivation onto the canonical taxonomy
- introduce explicit assignment category semantics:
  - one-shot
  - finite
  - persistent
- make assignment memory the only real source of truth for unit jobs

Why this pass comes first:

- if memory still carries stale categories, the rest of the migration stays muddy
- observation, validation, and UI all depend on this taxonomy being stable

Status:

- Completed.

Notes:

- `UnitAssignmentMemory` now carries an explicit assignment category field so the canonical
  distinction between persistent, finite, and one-shot assignments exists in the data model
  rather than only in surrounding code comments.
- Fresh turn derivation no longer produces the residual role buckets:
  - `reposition`
  - `automation_change`
  - `execute_action`
  - `transient_wait`
- Semantic direct-action carry-over is now normalized into canonical roles such as:
  - `auto_explore`
  - `stop_auto_explore`
  - `upgrade_self`
  - `pillage_here`
  - `use_special_ability`
- Existing carried unit assignments are now reconciled back through the canonical role defaults,
  so execution mode, completion policy, and assignment category stay aligned even when old
  serialized memory survives across turns.
- Old strategic scoring branches that still referenced the legacy `explore` / `reposition`
  wording were removed so the governor logic now speaks the canonical role vocabulary.

Success bar:

- `UnitAssignmentMemory` only uses canonical roles
- no new turns create `reposition`, `automation_change`, `execute_action`, or
  `transient_wait`

#### Pass B: Convert the unit surface to assignment-only candidates

Scope:

- remove raw unit-control concepts from the tactician-facing observation
- stop surfacing raw `reachableTiles`, `unitActions`, and `legalActionCandidates` as the
  main unit interface
- convert remaining meaningful direct unit choices into assignment-form candidates
- ensure every surfaced unit choice maps to a canonical assignment

Why this is its own pass:

- this is the true API migration
- it changes what the tactician sees and therefore what the LLM can emit

Status:

- Completed.

Notes:

- The tactician-facing unit observation no longer surfaces raw unit-control lists in practice:
  - `unitActions`
  - `legalActionCandidates`
  - `reachableTiles`
  are now emitted as empty lists for the planner packet.
- Remaining semantic direct unit choices are now surfaced as explicit assignment-style candidates
  instead of generic `unitspecial` actions, including:
  - `unitautoexplore`
  - `unitstopautoexplore`
  - `unitheal`
  - `unithold`
  - `unitupgrade`
  - `unitpillage`
  - `unitability`
- Assignment carry-over and assignment progress matching were updated to understand the new
  explicit candidate IDs instead of the old generic direct-action bucket.
- Strategist/governor heuristics that previously used raw unit-action visibility for settlement
  or exploration cues now read assignment/candidate semantics instead.

Success bar:

- the tactician chooses unit jobs only
- there is no longer a mixed unit surface

#### Pass C: Remove raw unit commands from prompt, action plan, and executor

Scope:

- remove `unit_move` and `unit_action` from the tactician plan schema
- update prompt instructions so unit control is assignment-only
- simplify executor logic so unit plans resolve through assignment candidates only
- remove unit-level mixed-command conflict rules that only existed because raw and assignment
  paths coexisted

Why this pass is separate:

- prompt, schema, and executor must change together or the surface becomes inconsistent

Status:

- Completed.

Notes:

- `unit_move` and `unit_action` were removed from the tactician action schema.
- The tactician prompt now describes unit control as assignment-only and no longer teaches raw
  unit micromanagement as part of the supported planning contract.
- The unit executor path now accepts unit plans through `select_unit_option` only.
- The old mixed-command conflict logic was removed because there is no longer a raw unit-command
  path for tactician plans to mix with assignment candidates.
- Assignment derivation, touched-unit tracking, retry matching, and domain diagnostics were all
  updated to remove the dead raw-command branches rather than preserving compatibility shims.

Success bar:

- unit planning uses `select_unit_option` only
- executor no longer needs raw unit command handling for tactician plans

#### Pass D: Clean frontend, observability, and old compatibility paths

Scope:

- remove raw unit-control sections from the dashboard
- make the dashboard show only assignment state and assignment candidates
- update observability and trace rendering to match the new assignment-only unit contract
- remove leftover compatibility wording and dead backend branches that only existed for the old
  mixed design

Why this pass is worth doing explicitly:

- otherwise the code may be clean while the UI and traces still teach the old model

Status:

- Completed.

Notes:

- The unit observation schema no longer includes the dead raw-control fields that only existed for
  the previous mixed unit interface.
- The dashboard no longer renders raw unit-control sections such as:
  - raw unit actions
  - legal action candidates
  - reachable tiles for raw unit movement
- Action-plan rendering in the dashboard no longer describes the removed raw unit command types.
- The rebuilt dashboard bundle now reflects the assignment-only unit surface end to end.

Success bar:

- the user-facing trace and dashboard speak the same assignment language as the backend
- no meaningful backend compatibility shims remain for the previous mixed unit-control model

### Things this refactor intentionally does not solve

This refactor should not be overloaded with battle-quality goals.

It is about making the unit control abstraction clean and stable.

It does not by itself guarantee:

- better theater coordination
- better group attack ordering
- smarter city conquest

Those remain the job of the battle-theater planner work above.

### Recommended execution order

1. complete Pass A before touching prompt/schema
2. complete Pass B before removing raw commands from the action plan
3. complete Pass C before trusting retry behavior as representative
4. complete Pass D immediately after the backend migration so UI and traces do not teach the
   wrong abstraction

If this order is followed, the resulting system should finally match the intended mental model:

- tactician chooses assignments
- heuristics execute them
- assignments may be one-shot, finite, or persistent
- any assignment may be switched when the turn demands it

## Bottom line

The current assignment architecture is now strong enough that the next gains should come from
better battle heuristics, not from more prompt work.

The right next leap is:

- from per-unit greedy execution
- to theater-level coordinated execution

If we do that well, the same assignment interface should be able to drive noticeably stronger and
more reliable city conquest without asking the LLM to micromanage tactical details.
