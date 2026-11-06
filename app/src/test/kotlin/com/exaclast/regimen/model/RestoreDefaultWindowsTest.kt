package com.exaclast.regimen.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreDefaultWindowsTest {

  private val lunch = TimeOfDayBucket(id = "lunch", name = "Lunch", hour = 12)
  private val unused = TimeOfDayBucket(id = "unused", name = "Unused", hour = 15)
  private val editedMorning = TimeOfDayBucket(id = TimeOfDayBucket.BUCKET_MORNING, name = "Early", hour = 5)

  private fun restore(current: List<TimeOfDayBucket>, used: Set<String>) =
    TimeOfDayBucket.windowsAfterRestoringDefaults(current, used)

  @Test
  fun customWindowInUseIsKept() {
    val result = restore(listOf(lunch), setOf("lunch"))
    assertEquals(lunch, result.single { it.id == "lunch" })
  }

  @Test
  fun unusedCustomWindowIsRemoved() {
    val ids = restore(listOf(unused), emptySet()).map { it.id }
    assertEquals(TimeOfDayBucket.defaultBuckets().map { it.id }, ids)
  }

  @Test
  fun editedDefaultGoesBackToItsDefaultTimes() {
    val morning = restore(listOf(editedMorning), setOf(TimeOfDayBucket.BUCKET_MORNING))
      .single { it.id == TimeOfDayBucket.BUCKET_MORNING }
    assertEquals("Morning", morning.name)
    assertEquals(7, morning.hour)
  }

  @Test
  fun deletedDefaultComesBack() {
    val ids = restore(emptyList(), emptySet()).map { it.id }
    assertEquals(TimeOfDayBucket.defaultBuckets().map { it.id }, ids)
  }
}
