package com.unciv.logic.automation.agent

data class AgentStrategicCheatSheet(
    val title: String,
    val bullets: List<String>,
)

object AgentStrategicCheatSheetBank {
    fun resolve(
        gameContext: AgentPublicGameContextObservation,
        memo: AgentStrategistMemoMemory?,
    ): AgentStrategicCheatSheet? {
        val archetype = memo?.gameArchetype?.ifBlank { null } ?: gameContext.archetype
        return when (archetype) {
            "tiny_duel_pangaea_no_city_states_no_barbs" -> tinyDuelDomination()
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
            "After contact is secured and the second-city site is known, stop investing in recon and convert production into Settler, Worker, or military instead.",
            "Do not treat a fresh default project or a weak in-progress project as a reason to keep it. Re-evaluate from scratch when the current build is not serving scout -> settler -> military tempo.",
            "In this archetype, Worker-first is usually the wrong opening unless immediate tile improvement timing is clearly more important than contact and second-city tempo.",
            "Monument- or Granary-first is also usually wrong here unless the second-city site and rival contact are already solved. Passive infrastructure should not delay the scouting opener or the first Settler.",
            "The starting Warrior should normally stay active and scout. Do not let it sit idle or fortify early unless immediate local safety truly requires it.",
            "After the scouting opener, the next serious city-production priority is usually Settler, not Worker and not passive infrastructure.",
            "Be specific about the policy roadmap: in Civ V Vanilla, the Liberty tree's Citizenship policy gives a free Worker. If the opener is leaning Liberty and Citizenship is realistically reachable soon, count that as part of the default economy and avoid building an early Worker too soon.",
            "Keep early Worker count lean; about one Worker per city is enough. Add or buy the second Worker at a sensible time, but only after the second-city timing is secure.",
            "Once two cities are online, the empire should stop playing like a peaceful opener and convert production into units that move toward the rival.",
            "When the first serious army is assembled, move it toward the rival border and prepare to declare instead of sitting in a long peaceful buildup.",
            "After war conversion starts, the normal aim is to seize the frontier city if needed and then the rival capital, while keeping reinforcements flowing from the rear.",
            "The main losing pattern to avoid is drifting into Worker, Monument, or Granary while contact is still unresolved and the second city is not yet secured.",
            "Only deviate from the default opener if contact and second-city certainty are already solved unusually early or if the rival is close enough that immediate military is clearly required.",
        ),
    )
}
