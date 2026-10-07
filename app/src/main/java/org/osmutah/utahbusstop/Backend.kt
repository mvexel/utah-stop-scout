package org.osmutah.utahbusstop

import org.osmutah.utahbusstop.BuildConfig
import org.maproulette.sdk.ChallengeId

/** Fixed campaign allowlist. Production stays empty until its Utah challenge IDs are imported. */
enum class Backend(
    val title: String,
    val baseUrl: String,
    val clientId: String,
    val challenges: List<CampaignChallenge>,
) {
    STAGE("Stage · field pilot", "https://mr-stage.osm.lol", BuildConfig.STAGE_CLIENT_ID,
        listOf(CampaignChallenge(1, "Salt Lake County"), CampaignChallenge(2, "Outside Salt Lake County"))),
    PRODUCTION("Production · OpenStreetMap", "https://mr-prod.osm.lol", BuildConfig.PROD_CLIENT_ID, emptyList());

    companion object { fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: STAGE }
}

data class CampaignChallenge(val id: Long, val area: String) {
    val challengeId get() = ChallengeId(id)
}
