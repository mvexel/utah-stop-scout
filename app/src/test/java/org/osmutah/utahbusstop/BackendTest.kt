package org.osmutah.utahbusstop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maproulette.sdk.ChoiceQuestion
import org.maproulette.sdk.ErrorKind
import org.maproulette.sdk.MapRouletteException
import org.osmutah.utahbusstop.auth.AppSession
import org.osmutah.utahbusstop.auth.SessionView

class BackendTest {
    @Test fun onlyStageChallengesAreConfiguredUntilProductionImport() {
        assertEquals(listOf(1L, 2L), Backend.STAGE.challenges.map { it.id })
        assertTrue(Backend.PRODUCTION.challenges.isEmpty())
    }

    @Test fun writeOriginGateIsExactAndNeverIncludesUnrelatedDomains() {
        assertTrue(AppSession.writesAllowedFor("https://mr-stage.osm.lol", false))
        assertTrue(AppSession.writesAllowedFor("https://mr-prod.osm.lol", false))
        assertFalse(AppSession.writesAllowedFor("https://maproulette.org", false))
        assertFalse(AppSession.writesAllowedFor("https://attacker.example", false))
    }

    @Test fun liveQuestionFilterDistinguishesMissingFilterFromEmptyAndSubset() {
        val questions = listOf(question("shelter"), question("bench"), question("bin"), question("departures_board"))
        assertEquals(questions, filterLiveQuestions(questions, null))
        assertTrue(filterLiveQuestions(questions, emptySet()).isEmpty())
        assertEquals(listOf("bench", "departures_board"), filterLiveQuestions(questions, setOf("bench", "departures_board")).map { it.id })
    }

    @Test fun submissionRequiresBothGrantedScopesAndSurfacesBackendWriteBlock() {
        assertFalse(accountCanSubmit(SessionView(0, true, 8909, "read only", canWriteTasks = true, canEditOsm = false)))
        assertFalse(accountCanSubmit(SessionView(0, true, 8909, "no write scope", canWriteTasks = false, canEditOsm = false)))
        assertTrue(accountCanSubmit(SessionView(0, true, 8909, "ready", canWriteTasks = true, canEditOsm = true)))
        assertTrue(isBackendWriteRefusal(MapRouletteException(ErrorKind.HTTP, status = 403)))
        assertTrue(isBackendWriteRefusal(MapRouletteException(ErrorKind.PERMISSION)))
        assertFalse(isBackendWriteRefusal(MapRouletteException(ErrorKind.NETWORK)))
    }

    private fun question(id: String) = ChoiceQuestion(id, id, null, mapOf(id to null), emptyList())
}
