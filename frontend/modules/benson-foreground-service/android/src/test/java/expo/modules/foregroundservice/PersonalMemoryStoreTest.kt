package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryStoreTest {

  private fun authorizedRequest(nowMs: Long, place: SavedPlace): MemoryWriteRequest {
    val proof = TurnAuthorizationProof(turnOrigin = "wake", authorizedAtMs = nowMs)
    val auth = MemoryWriteAuthorization.fromTurnProof(proof, nowMs = nowMs, maxAgeMs = 90_000L)!!
    return MemoryWriteRequest(requesterAgent = "MEMORY", authorization = auth, place = place)
  }

  private val cabinet = SavedPlace(
    canonicalName = "cabinet medical",
    aliases = mapOf("ro" to "cabinet", "de" to "Praxis", "en" to "my office"),
    latitude = 46.77, longitude = 23.59, confirmedByUser = true,
  )

  @Test fun writeThenResolveByCanonicalName() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    assertEquals(cabinet, store.resolve("TRAVEL", "cabinet medical"))
  }

  @Test fun resolvesMultilingualAliasesToSamePlace() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    val ro = store.resolve("TRAVEL", "cabinet")
    val de = store.resolve("TRAVEL", "Praxis")
    val en = store.resolve("TRAVEL", "my office")
    assertEquals(cabinet, ro)
    assertEquals(cabinet, de)
    assertEquals(cabinet, en)
  }

  @Test fun resolveIsCaseInsensitive() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    assertEquals(cabinet, store.resolve("TRAVEL", "PRAXIS"))
  }

  @Test fun resolveReturnsNullForUnknownQuery() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    assertNull(store.resolve("TRAVEL", "aeroport"))
  }

  @Test fun resolveDeniedForAgentWithoutPlacesPermission() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    assertNull(store.resolve("AKA", "cabinet")) // AKA nu are permisiune pe MemoryDomain.PLACES
  }

  @Test fun writeRejectedWhenAuthorizationExpiredBetweenAuthAndWrite() {
    val store = PersonalMemoryStore()
    val request = authorizedRequest(nowMs = 0L, place = cabinet)
    assertNull(store.write(request, nowMs = 200_000L, maxAgeMs = 90_000L))
    assertNull(store.resolve("TRAVEL", "cabinet medical"))
  }

  @Test fun allPlacesScopedToPermission() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    assertEquals(1, store.allPlaces("TRAVEL").size)
    assertEquals(0, store.allPlaces("AKA").size)
  }

  @Test fun correctOverwritesPreviousCoordinatesForSameCanonicalName() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    val corrected = cabinet.copy(latitude = 46.80, longitude = 23.60)
    store.correct(authorizedRequest(2_000L, corrected), nowMs = 2_000L)
    val resolved = store.resolve("TRAVEL", "cabinet medical")
    assertEquals(46.80, resolved!!.latitude, 0.0001)
    assertEquals(1, store.allPlaces("TRAVEL").size) // nu s-a creat o a doua intrare
  }

  @Test fun deleteRemovesPlaceWhenAuthorized() {
    val store = PersonalMemoryStore()
    val write = authorizedRequest(1_000L, cabinet)
    store.write(write, nowMs = 1_000L)
    val deleted = store.delete(write.authorization, "cabinet medical", nowMs = 1_500L)
    assertTrue(deleted)
    assertNull(store.resolve("TRAVEL", "cabinet medical"))
  }

  @Test fun deleteIsNoOpForUnknownPlace() {
    val store = PersonalMemoryStore()
    val write = authorizedRequest(1_000L, cabinet)
    assertFalse(store.delete(write.authorization, "nu exista", nowMs = 1_500L))
  }

  @Test fun snapshotAndLoadFromRoundTrip() {
    val store = PersonalMemoryStore()
    store.write(authorizedRequest(1_000L, cabinet), nowMs = 1_000L)
    val snapshot = store.snapshot()

    val reloaded = PersonalMemoryStore()
    reloaded.loadFrom(snapshot)
    assertEquals(cabinet, reloaded.resolve("TRAVEL", "Praxis"))
  }
}
