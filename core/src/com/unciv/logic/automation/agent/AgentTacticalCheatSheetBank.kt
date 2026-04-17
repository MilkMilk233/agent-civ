package com.unciv.logic.automation.agent

data class AgentTacticalCheatSheet(
    val title: String,
    val bullets: List<String>,
)

object AgentTacticalCheatSheetBank {
    fun resolve(
        gameContext: AgentPublicGameContextObservation,
        campaignStage: String?,
        isAtWar: Boolean,
    ): AgentTacticalCheatSheet? {
        val normalizedStage = campaignStage?.lowercase().orEmpty()
        val warFacing = isAtWar || normalizedStage in setOf("declaration", "staging", "pressure", "assault", "rebuild")
        if (!warFacing) return null

        val commonBullets = arrayListOf(
            "In a city assault, preserve at most one healthy melee unit as the captor. Do not preserve multiple frontline melee as reserve captors.",
            "If reinforcements are already stacked behind the front, healthy melee on the city axis should usually keep pressure or trade forward instead of sitting on key approach tiles.",
            "When a frontline unit is too damaged to contribute, rotate it out and replace it quickly. Do not stabilize the whole assault just because the first wave took damage.",
            "Hold position is a dangerous order on critical frontline assault tiles when friendly attackers are queued behind. Avoid roadblocking your own surround.",
            "Do not wait forever for perfect ranged support if the current force can already tighten the ring or improve assault angles. Lost tempo can be worse than an imperfect push.",
            "Rear reinforcements should keep marching toward the target city axis instead of idling in the rear once the assault package is already committed.",
        )

        if (gameContext.duelLike) {
            commonBullets += "On a tiny duel map, a live city-assault window is precious. Be skeptical of another passive stabilization turn once the frontline is already in contact."
        }

        return AgentTacticalCheatSheet(
            title = "Tactical combat doctrine",
            bullets = commonBullets,
        )
    }
}
