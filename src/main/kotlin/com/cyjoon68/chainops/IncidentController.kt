package com.cyjoon68.chainops

import jakarta.validation.constraints.NotBlank
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping("/api")
class IncidentController(private val service: IncidentService) {
    @GetMapping("/incidents")
    fun incidents(): List<Incident> = service.incidents()

    @PostMapping("/incidents")
    fun create(@RequestBody request: CreateIncidentRequest): Incident = service.create(request)

    @PatchMapping("/incidents/{id}")
    fun transition(@PathVariable id: UUID, @RequestBody request: IncidentTransitionRequest): Incident =
        service.transition(id, request.status)

    @GetMapping("/metrics/mttr")
    fun mttr(): MttrResponse = service.mttr()
}

@Service
class IncidentService(private val incidentStore: IncidentStore) {
    fun incidents(): List<Incident> = incidentStore.findAll()

    fun create(request: CreateIncidentRequest): Incident {
        val incident = Incident(
            id = UUID.randomUUID(),
            title = request.title,
            severity = request.severity,
            status = "OPEN",
            traceId = "trace-${UUID.randomUUID()}",
            elkUrl = "http://localhost:5601/app/discover",
            mttrMinutes = 0,
            startedAt = Instant.now(),
            resolvedAt = null,
        )

        return incidentStore.create(incident)
    }

    fun transition(id: UUID, status: String): Incident = incidentStore.transition(id, status)

    fun mttr(): MttrResponse = incidentStore.mttr()
}

interface IncidentStore {
    fun findAll(): List<Incident>
    fun create(incident: Incident): Incident
    fun transition(id: UUID, status: String): Incident
    fun mttr(): MttrResponse
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
            .param("startedAt", incident.startedAt)
            .param("resolvedAt", incident.resolvedAt)
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
        mttrMinutes = rs.getDouble("mttr_minutes").toInt(),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
    )
}

data class CreateIncidentRequest(@field:NotBlank val title: String, @field:NotBlank val severity: String)
data class IncidentTransitionRequest(val status: String)
data class Incident(
    val id: UUID,
    val title: String,
    val severity: String,
    val status: String,
    val traceId: String,
    val elkUrl: String,
    val mttrMinutes: Int,
    val startedAt: Instant,
    val resolvedAt: Instant?,
)
data class MttrResponse(val averageMinutes: Double, val sampleSize: Int)
