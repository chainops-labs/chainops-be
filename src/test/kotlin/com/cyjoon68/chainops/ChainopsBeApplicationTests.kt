package com.cyjoon68.chainops

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChainopsBeApplicationTests {
    @Test
    fun calculatesMttrFromResolvedIncidentTimestamps() {
        val service = IncidentService(
            InMemoryIncidentStore(
                listOf(
                    Incident(
                        id = UUID.randomUUID(),
                        title = "deploy latency",
                        severity = "SEV2",
                        status = "RESOLVED",
                        traceId = "trace-test-1",
                        elkUrl = "http://localhost:5601/app/discover#/trace-test-1",
                        mttrMinutes = 18,
                        startedAt = Instant.parse("2026-07-08T10:20:00Z"),
                        resolvedAt = Instant.parse("2026-07-08T10:38:00Z"),
                    ),
                    Incident(
                        id = UUID.randomUUID(),
                        title = "gitops drift",
                        severity = "SEV3",
                        status = "RESOLVED",
                        traceId = "trace-test-2",
                        elkUrl = "http://localhost:5601/app/discover#/trace-test-2",
                        mttrMinutes = 9,
                        startedAt = Instant.parse("2026-07-08T09:30:00Z"),
                        resolvedAt = Instant.parse("2026-07-08T09:39:00Z"),
                    ),
                ),
            ),
        )

        val mttr = service.mttr()

        assertEquals(13.5, mttr.averageMinutes)
        assertEquals(2, mttr.sampleSize)
    }

    @Test
    fun createsOpenIncidentForCurrentDrill() {
        val service = IncidentService(InMemoryIncidentStore(emptyList()))
        val incident = service.create(CreateIncidentRequest("api saturation", "SEV2"))

        assertEquals("OPEN", incident.status)
        assertTrue(incident.traceId.startsWith("trace-"))
        assertEquals("http://localhost:5601/app/discover", incident.elkUrl)
        assertTrue(incident.startedAt <= Instant.now())
    }

    @Test
    fun recordsDeployEventAndRollbackChecklistState() {
        val service = IncidentService(InMemoryIncidentStore(emptyList()))
        val incident = service.create(CreateIncidentRequest("verifier deploy latency", "SEV2"))
        val deployEvent = service.createDeployEvent(
            CreateDeployEventRequest("verifier-api", "abc1234", "verifier-api:test", "DEGRADED"),
        )
        val check = service.rollbackChecks(incident.id).first { !it.checked }
        val updated = service.updateRollbackCheck(check.id, true)

        assertEquals("verifier-api", deployEvent.serviceName)
        assertEquals("DEGRADED", deployEvent.status)
        assertTrue(updated.checked)
    }
}

private class InMemoryIncidentStore(initialIncidents: List<Incident>) : IncidentStore {
    private val incidents = initialIncidents.toMutableList()

    override fun findAll(): List<Incident> = incidents.toList()

    override fun create(incident: Incident): Incident {
        incidents += incident
        return incident
    }

    override fun transition(id: UUID, status: String): Incident {
        val index = incidents.indexOfFirst { it.id == id }
        val current = incidents[index]
        val updated = current.copy(status = status, resolvedAt = if (status == "RESOLVED") Instant.now() else current.resolvedAt)
        incidents[index] = updated
        return updated
    }

    override fun mttr(): MttrResponse {
        val resolvedDurations = incidents.mapNotNull { incident ->
            incident.resolvedAt?.let { java.time.Duration.between(incident.startedAt, it).toMinutes().toDouble() }
        }

        return MttrResponse(
            averageMinutes = resolvedDurations.average(),
            sampleSize = resolvedDurations.size,
        )
    }
}
