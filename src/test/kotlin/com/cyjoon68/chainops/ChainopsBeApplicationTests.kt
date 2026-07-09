package com.cyjoon68.chainops

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.web.server.ResponseStatusException

class ChainopsBeApplicationTests {
    @Test
    fun calculatesMttrFromResolvedIncidentTimestamps() {
        val service = testService(
            incidentStore = InMemoryIncidentStore(
                listOf(
                    Incident(
                        id = UUID.randomUUID(),
                        title = "deploy latency",
                        severity = "SEV2",
                        status = "RESOLVED",
                        traceId = "trace-test-1",
                        elkUrl = "http://localhost:5601/app/discover#/trace-test-1",
                        mttrMinutes = 18.0,
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
                        mttrMinutes = 9.0,
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
    fun recordsIncidentFromTargetHealthObservation() {
        val service = testService(
            incidentStore = InMemoryIncidentStore(emptyList()),
            deployTargetStore = InMemoryDeployTargetStore(listOf(liveTarget())),
            targetVerifier = FakeTargetVerifier(healthy = false),
        )
        val incident = service.incidents().single()

        assertEquals("OPEN", incident.status)
        assertEquals("credleaf-be health check failed", incident.title)
        assertTrue(incident.traceId.startsWith("trace-"))
        assertEquals("http://localhost:5601/app/discover", incident.elkUrl)
        assertTrue(incident.startedAt <= Instant.now())
    }

    @Test
    fun resolvesObservedIncidentWhenTargetRecovers() {
        val targetVerifier = FakeTargetVerifier(healthy = false)
        val service = testService(
            incidentStore = InMemoryIncidentStore(emptyList()),
            deployTargetStore = InMemoryDeployTargetStore(listOf(liveTarget())),
            targetVerifier = targetVerifier,
        )

        service.incidents()
        targetVerifier.healthy = true
        val incident = service.incidents().single()

        assertEquals("RESOLVED", incident.status)
        assertTrue(incident.resolvedAt != null)
    }

    @Test
    fun recordsDeployEventAndRollbackChecklistState() {
        val incident = Incident(
            id = UUID.randomUUID(),
            title = "verifier deploy latency",
            severity = "SEV2",
            status = "OPEN",
            traceId = "trace-test",
            elkUrl = "http://localhost:5601/app/discover",
            mttrMinutes = 0.0,
            startedAt = Instant.now(),
            resolvedAt = null,
        )
        val service = testService(InMemoryIncidentStore(listOf(incident)))
        val deployEvent = service.createDeployEvent(
            CreateDeployEventRequest("verifier-api", "abc1234", "verifier-api:test", "DEGRADED"),
        )
        val check = service.rollbackChecks(incident.id).first { !it.checked }
        val updated = service.updateRollbackCheck(check.id, true)

        assertEquals("verifier-api", deployEvent.serviceName)
        assertEquals("DEGRADED", deployEvent.status)
        assertTrue(updated.checked)
    }

    @Test
    fun registersAndRemovesDeployTarget() {
        val deployTargetStore = InMemoryDeployTargetStore()
        val service = testService(
            incidentStore = InMemoryIncidentStore(emptyList()),
            deployTargetStore = deployTargetStore,
        )

        val target = service.createDeployTarget(
            CreateDeployTargetRequest(
                "audit-api",
                "https://github.com/cyjoon68/audit-api",
                "http://localhost:18080/actuator/health",
                "chainops-prod",
                "production",
            ),
        )
        service.deleteDeployTarget(target.id)

        assertEquals("audit-api", target.serviceName)
        assertTrue(service.deployTargets().isEmpty())
    }

    @Test
    fun observesDeployEventFromRegisteredLiveTarget() {
        val service = testService(InMemoryIncidentStore(emptyList()))
        service.createDeployTarget(
            CreateDeployTargetRequest(
                "credleaf-be",
                "https://github.com/credleaf-labs/credleaf-be",
                "http://localhost:8084/actuator/health",
                "credleaf-local",
                "local",
            ),
        )
        val event = service.deployEvents().single()

        assertEquals("credleaf-be", event.serviceName)
        assertEquals("abc1234", event.commitSha)
        assertEquals("credleaf-be:abc1234", event.imageTag)
        assertEquals("RUNNING", event.status)
    }

    @Test
    fun rejectsMissingRepositoryOrStoppedApiTarget() {
        val missingRepo = testService(
            incidentStore = InMemoryIncidentStore(emptyList()),
            targetVerifier = FakeTargetVerifier(repositoryExists = false, healthy = true),
        )
        val stoppedApi = testService(
            incidentStore = InMemoryIncidentStore(emptyList()),
            targetVerifier = FakeTargetVerifier(repositoryExists = true, healthy = false),
        )

        assertFailsWith<ResponseStatusException> {
            missingRepo.createDeployTarget(
                CreateDeployTargetRequest(
                    "missing-api",
                    "https://github.com/missing/missing-api",
                    "http://localhost:18080/actuator/health",
                    "chainops-prod",
                    "production",
                ),
            )
        }
        assertFailsWith<ResponseStatusException> {
            stoppedApi.createDeployTarget(
                CreateDeployTargetRequest(
                    "stopped-api",
                    "https://github.com/cyjoon68/stopped-api",
                    "http://localhost:18080/actuator/health",
                    "chainops-prod",
                    "production",
                ),
            )
        }
    }
}

private fun testService(
    incidentStore: IncidentStore,
    deployTargetStore: DeployTargetStore = InMemoryDeployTargetStore(),
    deployEventStore: DeployEventStore = InMemoryDeployEventStore(),
    rollbackCheckStore: RollbackCheckStore = InMemoryRollbackCheckStore(),
    targetVerifier: TargetVerifier = FakeTargetVerifier(),
): IncidentService = IncidentService(incidentStore, deployTargetStore, deployEventStore, rollbackCheckStore, targetVerifier)

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

private fun liveTarget(): DeployTarget = DeployTarget(
    id = UUID.randomUUID(),
    serviceName = "credleaf-be",
    repositoryUrl = "https://github.com/credleaf-labs/credleaf-be",
    healthUrl = "http://localhost:8084/actuator/health",
    namespace = "credleaf-local",
    environment = "local",
    runtimeStatus = "UNKNOWN",
    createdAt = Instant.now(),
)

private class InMemoryDeployTargetStore(initialTargets: List<DeployTarget> = emptyList()) : DeployTargetStore {
    private val targets = initialTargets.toMutableList()

    override fun findAll(): List<DeployTarget> = targets.toList()

    override fun create(target: DeployTarget): DeployTarget {
        targets += target
        return target
    }

    override fun delete(id: UUID) {
        targets.removeIf { it.id == id }
    }
}

private class InMemoryDeployEventStore : DeployEventStore {
    private val events = mutableListOf<DeployEvent>()

    override fun findAll(): List<DeployEvent> = events.toList()

    override fun create(event: DeployEvent): DeployEvent {
        events += event
        return event
    }
}

private class InMemoryRollbackCheckStore : RollbackCheckStore {
    private val checks = mutableMapOf<UUID, MutableList<RollbackCheck>>()

    override fun findByIncidentId(incidentId: UUID): List<RollbackCheck> = checks.getOrPut(incidentId) {
        mutableListOf(
            RollbackCheck(UUID.randomUUID(), incidentId, "직전 정상 image tag 확인", checked = true),
            RollbackCheck(UUID.randomUUID(), incidentId, "Argo CD sync 상태 확인", checked = false),
            RollbackCheck(UUID.randomUUID(), incidentId, "ELK trace link로 에러 범위 확인", checked = false),
        )
    }.toList()

    override fun update(id: UUID, checked: Boolean): RollbackCheck {
        val current = checks.values.flatten().first { it.id == id }
        val incidentChecks = checks.getValue(current.incidentId)
        val index = incidentChecks.indexOfFirst { it.id == id }
        val updated = current.copy(checked = checked)
        incidentChecks[index] = updated
        return updated
    }
}

private class FakeTargetVerifier(
    private val repositoryExists: Boolean = true,
    var healthy: Boolean = true,
    private val commitSha: String? = "abc1234",
) : TargetVerifier {
    override fun repositoryExists(url: String): Boolean = repositoryExists

    override fun isHealthy(url: String): Boolean = healthy

    override fun latestCommitSha(url: String): String? = commitSha
}
