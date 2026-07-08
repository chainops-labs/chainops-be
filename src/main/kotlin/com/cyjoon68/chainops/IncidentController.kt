package com.cyjoon68.chainops

import jakarta.validation.constraints.NotBlank
import java.time.Instant
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
class IncidentController(private val service: IncidentService = IncidentService()) {
    @GetMapping("/incidents")
    fun incidents(): List<Incident> = service.incidents()

    @PostMapping("/incidents")
    fun create(@RequestBody request: CreateIncidentRequest): Incident = service.create(request)

    @PatchMapping("/incidents/{id}")
    fun transition(@PathVariable id: String, @RequestBody request: IncidentTransitionRequest): Incident = service.transition(id, request.status)

    @GetMapping("/metrics/mttr")
    fun mttr(): MttrResponse = service.mttr()
}

class IncidentService {
    private val incidents = mutableListOf(
        Incident("inc-42", "Verifier API latency after deploy", "SEV2", "MITIGATING", 18, Instant.parse("2026-07-08T10:20:00Z")),
        Incident("inc-41", "Argo CD sync drift", "SEV3", "RESOLVED", 9, Instant.parse("2026-07-08T09:30:00Z")),
    )

    fun incidents(): List<Incident> = incidents.toList()

    fun create(request: CreateIncidentRequest): Incident {
        val incident = Incident("inc-${40 + incidents.size + 1}", request.title, request.severity, "OPEN", 0, Instant.now())
        incidents += incident
        return incident
    }

    fun transition(id: String, status: String): Incident {
        val index = incidents.indexOfFirst { it.id == id }
        require(index >= 0) { "incident not found: $id" }
        val updated = incidents[index].copy(status = status, mttrMinutes = if (status == "RESOLVED") 12 else incidents[index].mttrMinutes)
        incidents[index] = updated
        return updated
    }

    fun mttr(): MttrResponse {
        val resolved = incidents.filter { it.mttrMinutes > 0 }
        return MttrResponse(resolved.map { it.mttrMinutes }.average(), resolved.size)
    }
}

data class CreateIncidentRequest(@field:NotBlank val title: String, @field:NotBlank val severity: String)
data class IncidentTransitionRequest(val status: String)
data class Incident(val id: String, val title: String, val severity: String, val status: String, val mttrMinutes: Int, val startedAt: Instant)
data class MttrResponse(val averageMinutes: Double, val sampleSize: Int)
