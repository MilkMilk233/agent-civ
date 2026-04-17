import type { RunnerFormOptions, RunnerMapSizeOption, RunnerRulesetOptions } from "./types";

export interface BatchLaunchFormState {
  name: string;
  games: number;
  maxTurns: number;
  seedStart: number;
  pairMatchesBySeed: boolean;
  saveFinalGames: boolean;
  saveOnlyInterestingGames: boolean;
  baseRuleset: string;
  difficulty: string;
  speed: string;
  enabledVictoryTypes: string[];
  mapType: string;
  mapShape: string;
  mapSizeName: string;
  agentCiv: string;
  legacyCiv: string;
  noBarbarians: boolean;
  numberOfCityStates: number;
  noRuins: boolean;
  noNaturalWonders: boolean;
  strategicBalance: boolean;
  legendaryStart: boolean;
}

const VANILLA_RULESET = "Civ V - Vanilla";

const fallbackOptions: RunnerFormOptions = {
  defaultBaseRuleset: VANILLA_RULESET,
  baseRulesets: [VANILLA_RULESET],
  playerTypes: ["AI_AGENT", "AI"],
  rulesets: [
    {
      name: VANILLA_RULESET,
      difficulties: ["Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity"],
      speeds: ["Quick", "Standard", "Epic", "Marathon"],
      civilizations: ["Random", "America", "Babylon", "Egypt", "England", "France", "Germany", "Persia", "Rome"],
      victoryTypes: ["Domination", "Scientific", "Cultural", "Diplomatic", "Time"],
    },
  ],
  mapTypes: ["Pangaea", "Continents", "Archipelago", "Fractal", "Lakes"],
  mapShapes: ["Hexagonal", "Rectangular", "Flat Earth"],
  mapSizes: [
    { name: "Tiny", radius: 10, width: 23, height: 15 },
    { name: "Small", radius: 15, width: 33, height: 21 },
    { name: "Medium", radius: 20, width: 43, height: 27 },
  ],
  supportedCompetitivePlayerCount: 2,
  supportsAdditionalCompetitivePlayers: false,
};

export function getRunnerOptions(options: RunnerFormOptions | null): RunnerFormOptions {
  const resolved = options ?? fallbackOptions;
  const vanillaRuleset = resolved.rulesets.find((entry) => entry.name === VANILLA_RULESET) ?? fallbackOptions.rulesets[0];
  return {
    ...resolved,
    defaultBaseRuleset: vanillaRuleset.name,
    baseRulesets: [vanillaRuleset.name],
    rulesets: [vanillaRuleset],
  };
}

export function createDefaultLaunchForm(options: RunnerFormOptions | null): BatchLaunchFormState {
  const resolved = getRunnerOptions(options);
  const ruleset = rulesetOptions(resolved, resolved.defaultBaseRuleset);
  const tinyMap = resolved.mapSizes.find((size) => size.name === "Tiny") ?? resolved.mapSizes[0];
  const civs = ruleset.civilizations.filter((civ) => civ !== "Random");

  return {
    name: "small-loop-1",
    games: 1,
    maxTurns: 100,
    seedStart: 2000,
    pairMatchesBySeed: false,
    saveFinalGames: false,
    saveOnlyInterestingGames: false,
    baseRuleset: resolved.defaultBaseRuleset,
    difficulty: ruleset.difficulties.includes("Settler") ? "Settler" : ruleset.difficulties[0] ?? "Settler",
    speed: ruleset.speeds.includes("Quick") ? "Quick" : ruleset.speeds[0] ?? "Quick",
    enabledVictoryTypes: [...ruleset.victoryTypes],
    mapType: resolved.mapTypes.includes("Pangaea") ? "Pangaea" : resolved.mapTypes[0] ?? "Pangaea",
    mapShape: resolved.mapShapes.includes("Hexagonal") ? "Hexagonal" : resolved.mapShapes[0] ?? "Hexagonal",
    mapSizeName: tinyMap?.name ?? "Tiny",
    agentCiv: civs.includes("Germany") ? "Germany" : civs[0] ?? "Random",
    legacyCiv: civs.includes("Persia") ? "Persia" : civs[1] ?? civs[0] ?? "Random",
    noBarbarians: true,
    numberOfCityStates: 0,
    noRuins: true,
    noNaturalWonders: true,
    strategicBalance: true,
    legendaryStart: true,
  };
}

export function normalizeLaunchForm(
  current: BatchLaunchFormState,
  options: RunnerFormOptions | null,
): BatchLaunchFormState {
  const resolved = getRunnerOptions(options);
  const ruleset = rulesetOptions(resolved, current.baseRuleset);
  const mapSize = mapSizeOptions(resolved).find((size) => size.name === current.mapSizeName) ?? resolved.mapSizes[0];

  return {
    ...current,
    baseRuleset: choose(current.baseRuleset, resolved.baseRulesets, resolved.defaultBaseRuleset),
    difficulty: choose(current.difficulty, ruleset.difficulties, ruleset.difficulties[0] ?? "Settler"),
    speed: choose(current.speed, ruleset.speeds, ruleset.speeds[0] ?? "Quick"),
    enabledVictoryTypes: normalizeSelection(current.enabledVictoryTypes, ruleset.victoryTypes),
    mapType: choose(current.mapType, resolved.mapTypes, resolved.mapTypes[0] ?? "Pangaea"),
    mapShape: choose(current.mapShape, resolved.mapShapes, resolved.mapShapes[0] ?? "Hexagonal"),
    mapSizeName: mapSize?.name ?? current.mapSizeName,
    agentCiv: choose(current.agentCiv, ruleset.civilizations, ruleset.civilizations[0] ?? "Random"),
    legacyCiv: choose(current.legacyCiv, ruleset.civilizations, ruleset.civilizations[0] ?? "Random"),
  };
}

export function buildBatchConfig(form: BatchLaunchFormState, options: RunnerFormOptions | null): string {
  const resolved = getRunnerOptions(options);
  const chosenRuleset = rulesetOptions(resolved, form.baseRuleset);
  const normalized = normalizeLaunchForm({ ...form, baseRuleset: chosenRuleset.name }, resolved);
  const mapSize = mapSizeOptions(resolved).find((size) => size.name === normalized.mapSizeName) ?? mapSizeOptions(resolved)[0];

  return JSON.stringify(
    {
      name: normalized.name.trim() || "agent-vs-legacy-smoke",
      games: clampNumber(normalized.games, 1, 200),
      maxTurns: clampNumber(normalized.maxTurns, 10, 2000),
      seedStart: clampNumber(normalized.seedStart, 1, Number.MAX_SAFE_INTEGER),
      pairMatchesBySeed: normalized.pairMatchesBySeed,
      saveFinalGames: normalized.saveFinalGames,
      saveOnlyInterestingGames: normalized.saveOnlyInterestingGames,
      gameParameters: {
        baseRuleset: normalized.baseRuleset,
        difficulty: normalized.difficulty,
        speed: normalized.speed,
        victoryTypes: normalizeSelection(normalized.enabledVictoryTypes, chosenRuleset.victoryTypes),
        numberOfCityStates: clampNumber(normalized.numberOfCityStates, 0, 64),
        noBarbarians: normalized.noBarbarians,
        maxTurns: clampNumber(normalized.maxTurns, 10, 2000),
        players: [
          { chosenCiv: normalized.agentCiv, playerType: "AI_AGENT" },
          { chosenCiv: normalized.legacyCiv, playerType: "AI" },
        ],
      },
      mapParameters: {
        type: normalized.mapType,
        shape: normalized.mapShape,
        mapSize: mapSize ?? { name: normalized.mapSizeName, radius: 10, width: 23, height: 15 },
        noRuins: normalized.noRuins,
        noNaturalWonders: normalized.noNaturalWonders,
        strategicBalance: normalized.strategicBalance,
        legendaryStart: normalized.legendaryStart,
      },
    },
    null,
    2,
  );
}

export function rulesetOptions(options: RunnerFormOptions | null, name: string): RunnerRulesetOptions {
  const resolved = getRunnerOptions(options);
  return resolved.rulesets.find((entry) => entry.name === name) ?? resolved.rulesets[0] ?? fallbackOptions.rulesets[0];
}

export function mapSizeOptions(options: RunnerFormOptions | null): RunnerMapSizeOption[] {
  return getRunnerOptions(options).mapSizes;
}

function choose(value: string, allowed: string[], fallback: string): string {
  return allowed.includes(value) ? value : fallback;
}

function clampNumber(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.max(min, Math.min(max, Math.round(value)));
}

function normalizeSelection(selected: string[], allowed: string[]): string[] {
  const aliases = new Map<string, string>([
    ["Science", "Scientific"],
    ["Scientific", "Science"],
  ]);
  const normalized = selected
    .map((value) => (allowed.includes(value) ? value : aliases.get(value)))
    .filter((value): value is string => Boolean(value && allowed.includes(value)));
  return normalized.length ? normalized : [...allowed];
}
