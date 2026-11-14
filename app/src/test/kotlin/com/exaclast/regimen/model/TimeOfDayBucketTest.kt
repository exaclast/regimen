package com.exaclast.regimen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeOfDayBucketTest {

  @Test
  fun validBucketConstructsSuccessfully() {
    val bucket = TimeOfDayBucket(
      id = "morning",
      name = "Morning",
      hour = 7,
      minute = 30,
      endHour = 10,
      endMinute = 0,
    )
    assertEquals(7, bucket.hour)
    assertEquals(30, bucket.minute)
    assertEquals(10, bucket.endHour)
    assertEquals(0, bucket.endMinute)
    assertFalse(bucket.crossesMidnight)
  }

  @Test
  fun overnightBucketCrossesMidnight() {
    val overnight = TimeOfDayBucket(
      id = "night",
      name = "Overnight",
      hour = 22,
      minute = 0,
      endHour = 2,
      endMinute = 0,
    )
    assertTrue(overnight.crossesMidnight)
  }

  @Test
  fun invalidHourIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      TimeOfDayBucket(id = "b", name = "Test", hour = 24)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TimeOfDayBucket(id = "b", name = "Test", hour = -1)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TimeOfDayBucket(id = "b", name = "Test", hour = 8, endHour = 25)
    }
  }

  @Test
  fun invalidMinuteIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      TimeOfDayBucket(id = "b", name = "Test", hour = 8, minute = 60)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TimeOfDayBucket(id = "b", name = "Test", hour = 8, endMinute = -1)
    }
  }

  @Test
  fun zeroDurationWindowIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      TimeOfDayBucket(id = "b", name = "Test", hour = 8, minute = 30, endHour = 8, endMinute = 30)
    }
  }

  @Test
  fun defaultEndHourHandlesNearMidnight() {
    val bucket = TimeOfDayBucket(id = "b", name = "Late", hour = 23, minute = 0)
    assertEquals(1, bucket.endHour)
    assertTrue(bucket.crossesMidnight)
  }
}
