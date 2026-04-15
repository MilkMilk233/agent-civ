# Agent Strategist Trigger Refactor

## Goal

Move strategist refresh away from repeated state warnings such as "checkpoint still missed"
and toward strategist-authored, script-verifiable review contracts.

The intent is:

- strategist owns the plan horizon
- scripts verify small typed phase-boundary triggers
- tactician executes inside that horizon
- strategist is not summoned every turn just because a warning remains true

## Current model

Each strategist memo now carries:

- `decisionFrame`
- `reviewContract`

`decisionFrame` is for the tactician. It explains what kind of turn range this is and what
checkpoint should prove the line is working.

`reviewContract` is for scripts. It tells the engine when this strategist memo should be
reconsidered.

## Review contract

Shape:

- `maxAgeTurns`
- `triggers[]`

Each trigger has:

- `kind`
- `metric`
- optional `summary`
- optional `withinTurns`

Supported trigger kinds:

- `milestone_reached`
- `deadline_missed`
- `assumption_broken`
- `contract_broken`

Examples of supported metrics:

- `contact_made`
- `city_founded`
- `second_city_founded`
- `war_declared`
- `city_captured`
- `rival_city_visible`
- `rival_capital_visible`
- `target_site_contested`
- `primary_rival_changed`
- `action_surface_mismatch`
- `supply_collapsing`

## Important design rule

Warnings are not triggers.

These may still matter in plan health, campaign control, or the tactician brief:

- fragile supply
- launch window open
- still one city
- checkpoint missed

But they should not repeatedly trigger strategist refresh by themselves.

Good triggers are phase boundaries:

- the milestone happened
- the milestone failed by a promised short horizon
- a key assumption broke
- the tactician could not execute the strategist frame

## Backstop

`maxAgeTurns` is only a safety valve.

If no explicit trigger fires, the memo is still refreshed once the contract becomes too old.
This prevents a weak memo from living forever, without making strategist turn-by-turn by
default.

## Hard emergency override

Hard emergencies still bypass the contract:

- war began and the memo is not war-aware
- the critical rival changed abruptly
- tactician explicitly requests an emergency refresh

These are reserved for real strategic breaks, not ordinary drift.
