package com.unciv.logic.automation.agent

data class AgentStrategicCheatSheet(
    val title: String,
    val bullets: List<String>,
)

object AgentStrategicCheatSheetBank {
    fun resolve(
        gameContext: AgentPublicGameContextObservation,
        memo: AgentStrategistMemoMemory?,
        victoryIntent: AgentVictoryIntentObservation? = null,
    ): AgentStrategicCheatSheet? {
        val archetype = memo?.gameArchetype?.ifBlank { null } ?: gameContext.archetype
        return when (archetype) {
            "tiny_duel_pangaea_no_city_states_no_barbs" -> when (victoryIntent?.effectiveWinPath) {
                "Science" -> tinyDuelScience()
                "Domination", null -> tinyDuelDomination()
                else -> null
            }
            else -> null
        }
    }

    private fun tinyDuelDomination() = AgentStrategicCheatSheet(
        title = "Tiny 1v1 domination opener prior",
        bullets = listOf(
            "This archetype is usually won by tempo, not by passive infrastructure.",
            "The normal plan is: found the capital immediately, use the starting Warrior plus 1-2 Scouts to reveal terrain, find the rival, and confirm the second-city site, then build a Settler for the second city, then convert two-city production into military pressure.",
            "The usual opening backbone is Scout -> Scout if needed -> Settler -> military. This is the default line unless the current board gives a strong reason to break it.",
            "One Scout is normal. A second Scout is often correct while contact and the forward site are both still unresolved. More than two Scouts is usually wasteful.",
            "Treat unresolved contact as an uncertainty, not automatically as a hard blocker. The real question is whether another recon unit changes the next few turns enough to justify delaying Settler or military tempo.",
            "On a tiny duel Pangaea, contact is likely to happen soon anyway once a Warrior plus 1-2 Scouts are already moving. Do not act as if the empire must solve every map unknown before it can choose a robust next step.",
            "A strong player asks about the marginal value of more information. If the current recon package is already likely to reveal the rival soon, another Scout is usually lower value than the next city or the first real military conversion.",
            "The opportunity cost of another Scout is very high in this archetype. Every extra recon build slot delays the second city, delays the first meaningful army, and delays the moment pressure can actually convert into territory.",
            "A third Scout needs a concrete exceptional reason, not just the sentence 'contact is still unresolved'. Good reasons are things like losing a Scout, both recon units being trapped on the wrong axis, or unusually bad terrain that clearly prevents current units from resolving the uncertainty soon.",
            "When recon is already sufficient, the right play is usually to let existing units continue revealing while city production pivots to the best robust tempo action.",
            "A robust tempo action is one that remains good even if the rival is a little farther or a little closer than expected. In this archetype, Settler and early military are often robust; another Scout often is not.",
            "After contact is secured and the second-city site is known, stop investing in recon and convert production into Settler, Worker, or military instead.",
            "Do not wait for perfect certainty before moving on. Once the empire has enough information to identify the most likely best line, take the line and let current units keep resolving the remaining uncertainty in parallel.",
            "If the rival is still unseen after multiple turns of active probing, do not automatically double down on recon. Ask whether the better response is to found the second city anyway, use a home Settler line, or begin a more conservative military conversion while existing recon continues.",
            "Do not treat a fresh default project or a weak in-progress project as a reason to keep it. Re-evaluate from scratch when the current build is not serving scout -> settler -> military tempo.",
            "In this archetype, Worker-first is usually the wrong opening unless immediate tile improvement timing is clearly more important than contact and second-city tempo.",
            "Monument- or Granary-first is also usually wrong here unless the second-city site and rival contact are already solved. Passive infrastructure should not delay the scouting opener or the first Settler.",
            "If the city screen offers Scout, Worker, Monument, and Settler at the same time, remember that the best choice is not determined by what finished fastest. It is determined by what most directly converts the current tempo window into growth or pressure.",
            "The starting Warrior should normally stay active and scout. Do not let it sit idle or fortify early unless immediate local safety truly requires it.",
            "Existing units should carry as much of the information-gathering burden as possible. A city build slot should not keep paying for recon when already-built units can keep exploring while the capital moves on to higher-value work.",
            "After the scouting opener, the next serious city-production priority is usually Settler, not Worker and not passive infrastructure.",
            "A one-city capital at population 1 often cannot start a Settler yet. Treat that as a temporary growth or rules gate, not as evidence that more recon suddenly became the best use of production.",
            "If Settler is temporarily unavailable because the capital is still size 1, the pro response is usually to wait for population 2 while using a short harmless placeholder or focus adjustment. It is usually not to chain more Scouts.",
            "When a temporary size gate is blocking Settler, ask: what is the best bridge action that preserves tempo until the gate unlocks? Good bridges are short military placeholders, harmless provisional projects, or growth-friendly focus changes. Bad bridges are repeated recon builds that consume the same city slot over and over.",
            "Do not misread 'Settler is not surfaced this turn' as 'therefore Scout is the new best build'. Very often it only means 'the city is not ready yet; keep current units scouting and be ready to swap to Settler as soon as the gate clears'.",
            "If Settler is surfaced and the second city is still the most robust checkpoint, treat that as the default conversion move even if contact is not fully resolved yet.",
            "When deciding between Settler and more recon, remember that a second city itself generates tempo: more production, more map control, more military output, and more flexibility once contact is made.",
            "Be specific about the policy roadmap: in Civ V Vanilla, the Liberty tree's Citizenship policy gives a free Worker. If the opener is leaning Liberty and Citizenship is realistically reachable soon, count that as part of the default economy and avoid building an early Worker too soon.",
            "Keep early Worker count lean; about one Worker per city is enough. Add or buy the second Worker at a sensible time, but only after the second-city timing is secure.",
            "Do not let the absence of a Worker become a false urgency while the true tempo race is still second city or early pressure. In this archetype, labor can wait longer than lost expansion tempo.",
            "If a Settler option is temporarily missing, prefer a short-lived hedge such as a military placeholder over drifting into Monument or Granary. But the hedge should stay a hedge, not become the new long-term plan.",
            "Short-lived hedges should be described declaratively: 'Settler is still the next build priority once surfaced' is better than a brittle script like 'finish Warrior, then maybe Settler'.",
            "Once 2 Scouts already exist, city production should almost never return to Scout unless one of them dies or the board presents a truly exceptional information bottleneck.",
            "If the strategist keeps reusing the same unresolved-contact story without city growth or military conversion, that is evidence the line is stale and should pivot, not evidence that recon should continue forever.",
            "Once two cities are online, the empire should stop playing like a peaceful opener and convert production into units that move toward the rival.",
            "When the first serious army is assembled, move it toward the rival border and prepare to declare instead of sitting in a long peaceful buildup.",
            "Once the rival capital or its likely approach is known, combat units should start orienting toward that capital axis. Do not leave melee units sitting fortified in the rear when they could already be walking toward the future battlefield.",
            "Fortify is usually a temporary local-safety tool, not a default stance for healthy combat units in this archetype. If a unit has no immediate threat to hold, it should usually be scouting, screening, escorting, gathering, or marching toward the rival capital corridor.",
            "Even before war, existing military should usually orient toward the likely frontier axis once the broad direction of the rival is known. Avoid keeping rear units passive with no relationship to the likely target corridor.",
            "When both scouting and military staging matter, use Scouts to reveal and use Warriors or other melee units to start closing distance toward the rival capital. Do not make every unit act like pure recon forever.",
            "Do not separate production posture from movement posture. If cities are already converting toward pressure, existing units should usually already be screening, gathering, or marching on the same axis.",
            "After war conversion starts, the normal aim is to seize the frontier city if needed and then the rival capital, while keeping reinforcements flowing from the rear.",
            "City conquest is not won by touching the target first; it is won by arriving with a package that can keep pressure on the city for multiple turns without collapsing.",
            "Treat city assault as a formation problem. Keep ranged support, healthy melee capture units, and a small replacement line together on the same approach instead of feeding units in one by one.",
            "A city usually falls fastest when ranged units focus fire to reduce health while melee units protect them, rotate, and preserve one healthy capture unit for the final blow.",
            "Do not spend your only healthy melee unit recklessly before the city is actually ready to fall. A damaged or dead capture unit often means several wasted turns of bombardment with nothing to take the city.",
            "Keep at least one fresh or reasonably healthy melee unit near the front as the designated capture piece. If the front melee is too damaged, rotate another one up before the city reaches low health.",
            "Rotation matters. When a frontline unit is too damaged to trade efficiently, pull it back and replace it with a healthier unit instead of letting it die in place.",
            "Backup replacement matters just as much as the first wave. A pro assault keeps another body close enough to step into the front line on the next turn, not five turns behind the army.",
            "Do not leave ranged units hanging without a screen. Use melee units, terrain, and formation spacing to stop the city or enemy units from reaching your fragile damage dealers for free.",
            "Surrounding a city is useful when it is safe, because it cuts off easy escape, improves pressure, and often creates more attack angles. But do not chase a full surround if it means exposing units one by one to bad trades.",
            "A partial surround with solid health and replacement depth is usually better than a dramatic full surround with wounded units and no backup.",
            "Focus fire is the default. Concentrated damage that removes a defender or rapidly drops the city is usually better than spreading attacks across multiple targets unless a specific enemy unit must be stopped immediately.",
            "Do not let the assault stall into idle fortify turns in bombard range. If the army is on the city axis, each turn should usually be advancing the assault, rotating units, healing with purpose, or setting up the next pressure turn.",
            "Healing is not the same as retreating forever. Heal when a unit can no longer contribute safely, but keep the line moving by replacing that unit with another one rather than pausing the whole assault.",
            "If a city cannot be taken soon with the current package, either reinforce quickly or step back to a better posture. The worst pattern is bleeding units slowly while pretending the assault is still healthy.",
            "Use terrain like a pro: hills, forests, rivers, and choke points should shape where melee units stand, where ranged units fire from, and which tiles are worth contesting on the approach.",
            "When the rival capital is the true prize, treat frontier cities as either stepping stones or distractions. Capture them if they unlock the capital assault or remove a real obstacle; do not get trapped in side objectives that do not improve the capital kill.",
            "Once the assault phase begins, reinforcements from the rear should already be walking toward the capital corridor. Do not let newly built combat units sit around the core when they are supposed to replace losses and keep the attack alive.",
            "A successful city conquest line alternates three jobs smoothly: soften the target, preserve the capture unit, and refill the front with backups. If any of those three jobs is missing, the assault is probably weaker than it looks.",
            "The frontier city is often only a means to the real objective, not the reason to drift. Always ask whether the current build and movement package is bringing the empire closer to actual territory gain.",
            "The main losing pattern to avoid is drifting into Worker, Monument, or Granary while contact is still unresolved and the second city is not yet secured.",
            "Another major losing pattern is paying for more and more recon after the empire already has enough pieces on the board to reveal the rival naturally. This wastes the narrow expansion window and leaves the army and second city late.",
            "If the rival is still not found after a reasonable search window, the pro response is usually to choose the best robust expansion line and continue scouting with existing units, not to keep sacrificing city tempo to new Scouts.",
            "Only deviate from the default opener if contact and second-city certainty are already solved unusually early or if the rival is close enough that immediate military is clearly required.",
        ),
    )

    private fun tinyDuelScience() = AgentStrategicCheatSheet(
        title = "Tiny 1v1 science snowball prior",
        bullets = listOf(
            "When science is the only legal win, the primary job is to snowball cities, science tempo, and safe expansion instead of treating early conquest as the default story.",
            "The normal backbone is still tempo first: found the capital immediately, use the starting Warrior plus only as much recon as needed, secure the second city, and convert production into growth, labor, and science rather than a conquest-sized army.",
            "Do not overbuild Scouts once the map is reasonably understood. Extra recon is only worth it when it clearly changes the next few turns more than growth, Settler timing, or economy.",
            "On tiny duel maps, military still matters, but mostly as deterrence and self-defense. Build enough to stay safe and protect expansion tempo, not enough to carry a stalled prewar package with no legal conquest payoff.",
            "If the rival is visible, read that as a race and threat signal first, not automatically as permission to turn the empire into a city-assault machine.",
            "A second city is still a major checkpoint because it improves production, science, and flexibility. Do not let fear or unnecessary staging delay that city without a concrete immediate threat.",
            "Worker timing matters, but do not let labor urgency crowd out second-city timing. Use Workers to unlock food, production, and connection tempo once the expansion line is secure.",
            "In this mode, passive infrastructure is not automatically wrong. The question is whether each build accelerates science tempo, city growth, or safe expansion enough to beat its opportunity cost.",
            "When military is needed, prefer efficient deterrence and defensive posture. A compact force that can punish overextension is better than an oversized army that slows research and city development.",
            "If war starts anyway, defend efficiently and protect the snowball. Fight to keep cities, workers, and science tempo safe; do not drift into a conquest campaign unless the routed win path actually changes later.",
            "Gold should usually support tempo: city growth, key infrastructure, defensive necessity, or science acceleration. Avoid floating gold while also paying upkeep for military that is not converting into safety.",
            "The main losing pattern to avoid is carrying domination posture costs in a science game: too much early army, too much border staging, and too many turns where expansion or science timing is delayed for a war that is not the win condition.",
        ),
    )
}
