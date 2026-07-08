package com.cyjoon68.chainops

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ChainopsBeApplicationTests {

	@Test
	fun calculatesMttrFromIncidents() {
		val service = IncidentService()
		val mttr = service.mttr()

		assertTrue(mttr.averageMinutes > 0.0)
		assertTrue(mttr.sampleSize > 0)
	}

}
