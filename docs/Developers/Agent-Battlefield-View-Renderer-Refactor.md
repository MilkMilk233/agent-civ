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

## Service Split

We should separate the system into two roles:

1. **Producer / live mode**
2. **Replay reader / replay mode**

This is the key step that lets us run one or more live batches safely while inspecting results elsewhere.

### Producer / live mode

The producer is the process that actually runs games.

It should:

- accept batch launches
- simulate matches
- write trace artifacts
- write post-turn `.uncivsave` checkpoints
- optionally expose a live `/api/snapshot` view for the currently running batch

It should not:

- render battlefield PNGs
- own replay screenshot generation
- depend on a render-capable desktop window

The safest launcher shape for producers is the server-only path:

- `--agentreplay`

not:

- `--agentdashboard`

because the producer should be a batch server, not a live GL-backed camera.

### Replay reader / replay mode

The replay reader is a separate process that only reads artifacts from disk.

It should:

- list stored batches
- list matches within a batch
- load replay JSON from stored files
- later render battlefield PNGs from saved post-turn checkpoints
- cache those rendered PNGs on disk

It should not:

- launch new matches
- own the live in-memory observability stream
- depend on the game simulation loop still being alive

In other words:

- live mode is about **producing artifacts**
- replay mode is about **reading and enriching artifacts**

## Docker / Multi-Instance Model

Once live and replay are separated, parallel batch runs become much cleaner.

### Producer containers

Each producer container can run one batch server instance.

Each producer needs:

- its own port for live status / controls
- its own artifact root, or a guaranteed unique batch namespace

Example:

- `producer-a`
  - port `7071`
  - `UNCIV_AGENT_EVAL_DIR=/data/agent-evaluations`
  - mounted host path `/host/runs/producer-a:/data`
- `producer-b`
  - port `7072`
  - `UNCIV_AGENT_EVAL_DIR=/data/agent-evaluations`
  - mounted host path `/host/runs/producer-b:/data`

This avoids collisions and keeps cleanup straightforward.

### Replay service

Replay should be a separate backend process or container.

It can:

- mount one shared artifact root
- or mount several producer roots read-only

Then the frontend can connect to the replay service and browse completed or interrupted runs without touching the live producers.

### Why this is better

This gives us:

- one or more live producers running safely in parallel
- replay inspection even after producers stop
- no renderer logic in the live batch critical path
- the option to render replay battlefield images lazily and cache them

## API Shape

### Live producer API

Producer mode should own:

- `/api/snapshot`
- `/api/history/runner/status`
- `/api/history/runner/start`
- `/api/history/runner/cancel`

It may also expose batch history for convenience, but that is not the core reason it exists.

### Replay API

Replay mode should own:

- `/api/history/batches`
- `/api/history/matches`
- `/api/history/replay`
- later, replay-only battlefield render endpoints

That means replay can be served from stored artifacts alone.

## Implementation Order

To keep the work safe and incremental, we should do this in order:

### Step 1

- keep the live launcher on the server-only path
- keep battlefield rendering out of the live run

### Step 2

- save one post-turn checkpoint per resolved turn
- attach deterministic checkpoint filenames to turn summaries

### Step 3

- add a replay-only backend mode
- point it at one or more artifact roots
- make replay browsing work without needing a live producer process

### Step 4

- add replay-time battlefield rendering from saved checkpoints
- render lazily on demand
- cache PNGs after first render

### Step 5

- optionally add a background replay render worker to prewarm commonly viewed turns

## Open Choice

There are two viable ways to organize artifact roots:

1. **One shared parent directory**
- simplest operationally
- all producers write under one host-mounted root
- replay reads one root

2. **Multiple producer roots**
- safer isolation
- clearer ownership per producer
- replay needs to aggregate several roots

I would start with **one shared parent directory if batch IDs are guaranteed unique**, and move to multi-root aggregation only if operational separation becomes important.

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

There is a second rule that follows from this:

- the process that runs live batches should not be the process that defines replay as a product

Replay should be able to stand on stored artifacts alone.
