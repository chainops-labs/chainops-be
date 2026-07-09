package com.cyjoon68.chainops

import jakarta.validation.constraints.NotBlank
import java.sql.ResultSet
import java.sql.Timestamp
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping("/api")
class IncidentController(private val service: IncidentService) {
    @GetMapping("/deploy-targets")
    fun deployTargets(): List<DeployTarget> = service.deployTargets()

    @PostMapping("/deploy-targets")
    fun createDeployTarget(@RequestBody request: CreateDeployTargetRequest): DeployTarget = service.createDeployTarget(request)

    @DeleteMapping("/deploy-targets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDeployTarget(@PathVariable id: UUID) = service.deleteDeployTarget(id)

    @GetMapping("/deploy-events")
    fun deployEvents(): List<DeployEvent> = service.deployEvents()

    @PostMapping("/deploy-events")
    fun createDeployEvent(@RequestBody request: CreateDeployEventRequest): DeployEvent = service.createDeployEvent(request)

    @GetMapping("/incidents")
    fun incidents(): List<Incident> = service.incidents()

    @PatchMapping("/incidents/{id}")
    fun transition(@PathVariable id: UUID, @RequestBody request: IncidentTransitionRequest): Incident =
        service.transition(id, request.status)

    @GetMapping("/incidents/{id}/rollback-checks")
    fun rollbackChecks(@PathVariable id: UUID): List<RollbackCheck> = service.rollbackChecks(id)

    @PatchMapping("/rollback-checks/{id}")
    fun updateRollbackCheck(@PathVariable id: UUID, @RequestBody request: RollbackCheckRequest): RollbackCheck =
        service.updateRollbackCheck(id, request.checked)

    @GetMapping("/metrics/mttr")
    fun mttr(): MttrResponse = service.mttr()
}

@Service
class IncidentService(
    private val incidentStore: IncidentStore,
    private val deployTargetStore: DeployTargetStore,
    private val deployEventStore: DeployEventStore,
    private val rollbackCheckStore: RollbackCheckStore,
    private val targetVerifier: TargetVerifier,
) {
    fun deployTargets(): List<DeployTarget> = deployTargetStore.findAll().map(::withRuntimeStatus)

    fun createDeployTarget(request: CreateDeployTargetRequest): DeployTarget {
        if (!targetVerifier.repositoryExists(request.repositoryUrl)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "repository url is not reachable")
        }
        if (!targetVerifier.isHealthy(request.healthUrl)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "health url is not UP")
        }

        return deployTargetStore.create(
            DeployTarget(
                id = UUID.randomUUID(),
                serviceName = request.serviceName,
                repositoryUrl = request.repositoryUrl,
                healthUrl = request.healthUrl,
                namespace = request.namespace,
                environment = request.environment,
                runtimeStatus = "UNKNOWN",
                createdAt = Instant.now(),
            ),
        ).let(::withRuntimeStatus)
    }

    fun deleteDeployTarget(id: UUID) = deployTargetStore.delete(id)

    fun deployEvents(): List<DeployEvent> = deployTargetStore.findAll().mapNotNull(::observedDeployEvent) + deployEventStore.findAll()

    fun createDeployEvent(request: CreateDeployEventRequest): DeployEvent = deployEventStore.create(
        DeployEvent(
            id = UUID.randomUUID(),
            serviceName = request.serviceName,
            commitSha = request.commitSha,
            imageTag = request.imageTag,
            status = request.status,
            deployedAt = Instant.now(),
        ),
    )

    fun incidents(): List<Incident> {
        observeTargetHealth()
        return incidentStore.findAll()
    }

    private fun create(request: CreateIncidentRequest): Incident {
        val incident = Incident(
            id = UUID.randomUUID(),
            title = request.title,
            severity = request.severity,
            status = "OPEN",
            traceId = "trace-${UUID.randomUUID()}",
            elkUrl = "http://localhost:5601/app/discover",
            mttrMinutes = 0.0,
            startedAt = Instant.now(),
            resolvedAt = null,
        )

        return incidentStore.create(incident)
    }

    fun transition(id: UUID, status: String): Incident = incidentStore.transition(id, status)

    fun rollbackChecks(id: UUID): List<RollbackCheck> = rollbackCheckStore.findByIncidentId(id)

    fun updateRollbackCheck(id: UUID, checked: Boolean): RollbackCheck = rollbackCheckStore.update(id, checked)

    fun mttr(): MttrResponse {
        observeTargetHealth()
        return incidentStore.mttr()
    }

    private fun withRuntimeStatus(target: DeployTarget): DeployTarget = target.copy(
        runtimeStatus = if (targetVerifier.isHealthy(target.healthUrl)) "UP" else "DOWN",
    )

    private fun observeTargetHealth() {
        deployTargetStore.findAll().forEach { target ->
            val title = "${target.serviceName} health check failed"
            val openIncident = incidentStore.findAll().firstOrNull { it.title == title && it.status != "RESOLVED" }

            if (targetVerifier.isHealthy(target.healthUrl)) {
                if (openIncident != null) transition(openIncident.id, "RESOLVED")
            } else if (openIncident == null) {
                create(CreateIncidentRequest(title, "SEV2"))
            }
        }
    }

    private fun observedDeployEvent(target: DeployTarget): DeployEvent? {
        val runtimeStatus = if (targetVerifier.isHealthy(target.healthUrl)) "RUNNING" else return null
        val commitSha = targetVerifier.latestCommitSha(target.repositoryUrl) ?: return null

        return DeployEvent(
            id = target.id,
            serviceName = target.serviceName,
            commitSha = commitSha,
            imageTag = "${target.serviceName}:$commitSha",
            status = runtimeStatus,
            deployedAt = Instant.now(),
        )
    }
}

interface TargetVerifier {
    fun repositoryExists(url: String): Boolean
    fun isHealthy(url: String): Boolean
    fun latestCommitSha(url: String): String?
}

@Service
class HttpTargetVerifier : TargetVerifier {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun repositoryExists(url: String): Boolean = requestOk(url) { it.statusCode() in 200..399 }

    override fun isHealthy(url: String): Boolean = requestOk(url) {
        it.statusCode() in 200..299 && it.body().contains("\"status\":\"UP\"")
    }

    override fun latestCommitSha(url: String): String? {
        val path = URI.create(url).path.trim('/').split('/')
        if (path.size < 2) return null
        val repoApi = "https://api.github.com/repos/${path[0]}/${path[1]}"
        val repoBody = requestBody(repoApi) { it.statusCode() in 200..299 } ?: return null
        val defaultBranch = Regex(""""default_branch"\s*:\s*"([^"]+)"""").find(repoBody)?.groupValues?.get(1) ?: return null
        val commitBody = requestBody("$repoApi/commits/$defaultBranch") { it.statusCode() in 200..299 } ?: return null

        return Regex(""""sha"\s*:\s*"([0-9a-f]{7})[0-9a-f]*"""").find(commitBody)?.groupValues?.get(1)
    }

    private fun requestOk(url: String, accepts: (HttpResponse<String>) -> Boolean): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        accepts(response)
    } catch (_: Exception) {
        false
    }

    private fun requestBody(url: String, accepts: (HttpResponse<String>) -> Boolean): String? = try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "chainops-local")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (accepts(response)) response.body() else null
    } catch (_: Exception) {
        null
    }
}

interface IncidentStore {
    fun findAll(): List<Incident>
    fun create(incident: Incident): Incident
    fun transition(id: UUID, status: String): Incident
    fun mttr(): MttrResponse
}

interface DeployTargetStore {
    fun findAll(): List<DeployTarget>
    fun create(target: DeployTarget): DeployTarget
    fun delete(id: UUID)
}

interface DeployEventStore {
    fun findAll(): List<DeployEvent>
    fun create(event: DeployEvent): DeployEvent
}

interface RollbackCheckStore {
    fun findByIncidentId(incidentId: UUID): List<RollbackCheck>
    fun update(id: UUID, checked: Boolean): RollbackCheck
}

@Repository
class JdbcIncidentStore(private val jdbcClient: JdbcClient) : IncidentStore {
    override fun findAll(): List<Incident> = jdbcClient.sql(
        """
        select id, title, severity, status, started_at, resolved_at,
               trace_id, elk_url,
               coalesce(extract(epoch from (resolved_at - started_at)) / 60, 0) as mttr_minutes
        from incident
        order by started_at desc
        """.trimIndent(),
    ).query(::mapIncident).list()

    override fun create(incident: Incident): Incident {
        jdbcClient.sql(
            """
            insert into incident (id, title, severity, status, trace_id, elk_url, started_at, resolved_at)
            values (:id, :title, :severity, :status, :traceId, :elkUrl, :startedAt, :resolvedAt)
            """.trimIndent(),
        )
            .param("id", incident.id)
            .param("title", incident.title)
            .param("severity", incident.severity)
            .param("status", incident.status)
            .param("traceId", incident.traceId)
            .param("elkUrl", incident.elkUrl)
            .param("startedAt", Timestamp.from(incident.startedAt))
            .param("resolvedAt", incident.resolvedAt?.let(Timestamp::from))
            .update()

        return incident
    }

    override fun transition(id: UUID, status: String): Incident {
        jdbcClient.sql(
            """
            update incident
            set status = :status,
                resolved_at = case
                  when :status = 'RESOLVED' then coalesce(resolved_at, now())
                  else resolved_at
                end
            where id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .param("status", status)
            .update()

        return jdbcClient.sql(
            """
            select id, title, severity, status, started_at, resolved_at,
                   trace_id, elk_url,
                   coalesce(extract(epoch from (resolved_at - started_at)) / 60, 0) as mttr_minutes
            from incident
            where id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .query(::mapIncident)
            .single()
    }

    override fun mttr(): MttrResponse = jdbcClient.sql(
        """
        select coalesce(avg(extract(epoch from (resolved_at - started_at)) / 60), 0) as average_minutes,
               count(*) as sample_size
        from incident
        where resolved_at is not null
        """.trimIndent(),
    ).query { rs, _ ->
        MttrResponse(
            averageMinutes = rs.getDouble("average_minutes"),
            sampleSize = rs.getInt("sample_size"),
        )
    }.single()

    private fun mapIncident(rs: ResultSet, rowNumber: Int): Incident = Incident(
        id = rs.getObject("id", UUID::class.java),
        title = rs.getString("title"),
        severity = rs.getString("severity"),
        status = rs.getString("status"),
        traceId = rs.getString("trace_id"),
        elkUrl = rs.getString("elk_url"),
        mttrMinutes = rs.getDouble("mttr_minutes"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
    )
}

@Repository
class JdbcDeployTargetStore(private val jdbcClient: JdbcClient) : DeployTargetStore {
    override fun findAll(): List<DeployTarget> = jdbcClient.sql(
        """
        select id, service_name, repository_url, health_url, namespace, environment, created_at
        from deploy_target
        order by created_at desc
        """.trimIndent(),
    ).query(::mapDeployTarget).list()

    override fun create(target: DeployTarget): DeployTarget {
        jdbcClient.sql(
            """
            insert into deploy_target (id, service_name, repository_url, health_url, namespace, environment, created_at)
            values (:id, :serviceName, :repositoryUrl, :healthUrl, :namespace, :environment, :createdAt)
            """.trimIndent(),
        )
            .param("id", target.id)
            .param("serviceName", target.serviceName)
            .param("repositoryUrl", target.repositoryUrl)
            .param("healthUrl", target.healthUrl)
            .param("namespace", target.namespace)
            .param("environment", target.environment)
            .param("createdAt", Timestamp.from(target.createdAt))
            .update()

        return target
    }

    override fun delete(id: UUID) {
        jdbcClient.sql("delete from deploy_target where id = :id")
            .param("id", id)
            .update()
    }

    private fun mapDeployTarget(rs: ResultSet, rowNumber: Int): DeployTarget = DeployTarget(
        id = rs.getObject("id", UUID::class.java),
        serviceName = rs.getString("service_name"),
        repositoryUrl = rs.getString("repository_url"),
        healthUrl = rs.getString("health_url"),
        namespace = rs.getString("namespace"),
        environment = rs.getString("environment"),
        runtimeStatus = "UNKNOWN",
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}

@Repository
class JdbcDeployEventStore(private val jdbcClient: JdbcClient) : DeployEventStore {
    override fun findAll(): List<DeployEvent> = jdbcClient.sql(
        """
        select id, service_name, commit_sha, image_tag, status, deployed_at
        from deploy_event
        order by deployed_at desc
        """.trimIndent(),
    ).query(::mapDeployEvent).list()

    override fun create(event: DeployEvent): DeployEvent {
        jdbcClient.sql(
            """
            insert into deploy_event (id, service_name, commit_sha, image_tag, status, deployed_at)
            values (:id, :serviceName, :commitSha, :imageTag, :status, :deployedAt)
            """.trimIndent(),
        )
            .param("id", event.id)
            .param("serviceName", event.serviceName)
            .param("commitSha", event.commitSha)
            .param("imageTag", event.imageTag)
            .param("status", event.status)
            .param("deployedAt", Timestamp.from(event.deployedAt))
            .update()

        return event
    }

    private fun mapDeployEvent(rs: ResultSet, rowNumber: Int): DeployEvent = DeployEvent(
        id = rs.getObject("id", UUID::class.java),
        serviceName = rs.getString("service_name"),
        commitSha = rs.getString("commit_sha"),
        imageTag = rs.getString("image_tag"),
        status = rs.getString("status"),
        deployedAt = rs.getTimestamp("deployed_at").toInstant(),
    )
}

@Repository
class JdbcRollbackCheckStore(private val jdbcClient: JdbcClient) : RollbackCheckStore {
    override fun findByIncidentId(incidentId: UUID): List<RollbackCheck> = jdbcClient.sql(
        """
        select id, incident_id, item, checked
        from rollback_check
        where incident_id = :incidentId
        order by checked desc, item
        """.trimIndent(),
    )
        .param("incidentId", incidentId)
        .query(::mapRollbackCheck)
        .list()

    override fun update(id: UUID, checked: Boolean): RollbackCheck {
        jdbcClient.sql(
            """
            update rollback_check
            set checked = :checked
            where id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .param("checked", checked)
            .update()

        return jdbcClient.sql(
            """
            select id, incident_id, item, checked
            from rollback_check
            where id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .query(::mapRollbackCheck)
            .single()
    }

    private fun mapRollbackCheck(rs: ResultSet, rowNumber: Int): RollbackCheck = RollbackCheck(
        id = rs.getObject("id", UUID::class.java),
        incidentId = rs.getObject("incident_id", UUID::class.java),
        item = rs.getString("item"),
        checked = rs.getBoolean("checked"),
    )
}

data class CreateIncidentRequest(@field:NotBlank val title: String, @field:NotBlank val severity: String)
data class IncidentTransitionRequest(val status: String)
data class CreateDeployTargetRequest(
    @field:NotBlank val serviceName: String,
    @field:NotBlank val repositoryUrl: String,
    @field:NotBlank val healthUrl: String,
    @field:NotBlank val namespace: String,
    @field:NotBlank val environment: String,
)
data class CreateDeployEventRequest(
    @field:NotBlank val serviceName: String,
    @field:NotBlank val commitSha: String,
    @field:NotBlank val imageTag: String,
    @field:NotBlank val status: String,
)
data class Incident(
    val id: UUID,
    val title: String,
    val severity: String,
    val status: String,
    val traceId: String,
    val elkUrl: String,
    val mttrMinutes: Double,
    val startedAt: Instant,
    val resolvedAt: Instant?,
)
data class MttrResponse(val averageMinutes: Double, val sampleSize: Int)
data class DeployTarget(
    val id: UUID,
    val serviceName: String,
    val repositoryUrl: String,
    val healthUrl: String,
    val namespace: String,
    val environment: String,
    val runtimeStatus: String,
    val createdAt: Instant,
)
data class DeployEvent(
    val id: UUID,
    val serviceName: String,
    val commitSha: String,
    val imageTag: String,
    val status: String,
    val deployedAt: Instant,
)
data class RollbackCheck(val id: UUID, val incidentId: UUID, val item: String, val checked: Boolean)
data class RollbackCheckRequest(val checked: Boolean)
