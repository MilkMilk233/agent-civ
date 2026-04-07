package com.unciv.logic.automation.agent

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.stats.Stat

object AgentCityProjectPolicy {
    fun enforceSingleProject(civInfo: Civilization) {
        civInfo.cities.forEach(::enforceSingleProject)
    }

    fun enforceSingleProject(city: City): Boolean {
        val hadQueuedProjects = city.cityConstructions.constructionQueue.size > 1
        city.cityConstructions.collapseQueueToSingleProject()
        return hadQueuedProjects
    }

    fun selectProject(city: City, constructionName: String): Boolean {
        val currentName = city.cityConstructions.currentConstructionName()
        val hadQueuedProjects = enforceSingleProject(city)
        if (currentName == constructionName && !hadQueuedProjects) return false
        city.cityConstructions.setCurrentConstruction(constructionName)
        city.cityConstructions.collapseQueueToSingleProject()
        return currentName != constructionName || hadQueuedProjects
    }

    fun purchaseWithGold(city: City, constructionName: String): Boolean {
        val purchased = city.cityConstructions.purchaseConstruction(constructionName, -1, true, Stat.Gold)
        city.cityConstructions.collapseQueueToSingleProject()
        return purchased
    }
}
