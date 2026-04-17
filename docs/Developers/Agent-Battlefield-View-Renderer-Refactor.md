# Agent Battlefield View Renderer Refactor

## Current Decision

We should stop generating battlefield screenshots during the live batch run.

Instead:

1. At the end of each resolved agent turn, save a **post-turn checkpoint**.
2. Do **not** render any PNG during the live run.
3. In replay mode, when a turn is opened, render the battlefield image from that saved checkpoint.
4. Cache the PNG after rendering so later replay opens are fast.

This keeps battlefield view as a derived replay artifact, not part of the live game loop.

## Why

The current live screenshot path is too invasive:

- it runs GL / screen-render work inside the live dashboard process
- it swaps the global runtime into a screenshot clone
- it depends on a render-capable desktop window and valid GL context
- if the render path fails badly, it can kill the whole desktop JVM instead of producing a normal batch failure

That makes battlefield view a reliability risk for overnight or long-running batches.

## Preferred Model

### During the batch

For each resolved agent turn:

- save one `.uncivsave` checkpoint
- use a deterministic filename such as:
  - `render-snapshots/germany-turn-0047.uncivsave`
- label it clearly as a **post-turn** snapshot

The live batch process should only:

- simulate the turn
- write trace data
- write checkpoint files

It should not:

- load screenshot clones
- render `WorldScreen`
- grab framebuffers
- produce PNGs

### During replay

When the user opens a replay turn:

1. If a cached PNG already exists, show it.
2. Otherwise:
   - load the saved `.uncivsave`
   - render a battlefield image from that checkpoint
   - save the PNG
   - show it

This can happen:

- on demand when the replay turn is opened
- or in a background renderer queue

Either way, it should happen outside the live match loop.

## Why Post-Turn

We are choosing **post-turn** snapshots, not planner-time snapshots.

That means the battlefield image should be interpreted as:

- "what the board looked like after this turn resolved"

not:

- "what the tactician saw before choosing actions"

This is acceptable as long as the UI labels it clearly.

Recommended replay wording:

- `Post-turn battlefield view`
- `Board state after turn N resolved`

That avoids confusion when text decisions and the board image are compared.

## Benefits

This design gives us:

- much better run stability
- no screenshot GL work inside the live batch critical path
- replay support even for interrupted matches, as long as checkpoints were already saved
- the ability to regenerate missing PNGs later
- cleaner separation between simulation and visualization

## Tradeoffs

- disk usage increases because we keep one checkpoint per resolved turn
- replay image generation may be slower the first time a turn is opened
- live mode no longer gets free instant screenshots from the active run

These are good tradeoffs. They are much cheaper than letting battlefield view destabilize the simulation process.

## Recommended Phases

### Phase 1

- keep saving per-turn post-turn checkpoints
- remove live in-process screenshot capture entirely
- disable live battlefield screenshot generation

### Phase 2

- implement replay-time rendering from saved checkpoints
- cache generated PNGs on disk
- restore replay `Battlefield view` above `World facts`

### Phase 3

- if needed, add a background renderer worker for replay cache warming
- only revisit live-mode battlefield view after replay rendering is stable

## Practical Rule

The live game process should never again be the camera.

The live batch runner owns:

- simulation
- trace
- checkpoint persistence

The renderer owns:

- loading checkpoints
- producing battlefield PNGs

That separation is the main architectural rule that keeps the agent pipeline safe.
