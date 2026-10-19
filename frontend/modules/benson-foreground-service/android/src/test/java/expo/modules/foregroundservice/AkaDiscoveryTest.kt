package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AkaDiscoveryTest {

  private fun member(pkg: String, name: String, category: String, archived: Boolean = false) = StaffMember(
    packageName = pkg, displayName = name, category = category, launcher = true,
    firstInstallTime = 0L, lastUpdateTime = 0L, versionName = "1.0", versionCode = 1L, archived = archived,
  )

  @Test fun noCategoryKeywordInCommandFindsNothing() {
    val members = listOf(member("de.swm.parken", "HandyParken", "Navigation"))
    assertTrue(AkaDiscovery.discoverByPurpose(members, "deschide calculatorul").isEmpty())
  }

  @Test fun matchesByStaffCategoryField() {
    val members = listOf(
      member("de.swm.parken", "HandyParken München", "parcare"),
      member("com.spotify.music", "Spotify", "muzica"),
    )
    val found = AkaDiscovery.discoverByPurpose(members, "caută aplicația de parcare")
    assertEquals(1, found.size)
    assertEquals("de.swm.parken", found.first().packageName)
  }

  @Test fun matchesByDisplayNameWhenCategoryFieldDiffers() {
    val members = listOf(member("com.example.parking", "Parcare Facilă", "Tools"))
    val found = AkaDiscovery.discoverByPurpose(members, "deschide aplicația de parcare")
    assertEquals(1, found.size)
  }

  @Test fun archivedAppsAreNeverDiscovered() {
    val members = listOf(member("de.swm.parken", "HandyParken", "parcare", archived = true))
    assertTrue(AkaDiscovery.discoverByPurpose(members, "caută aplicația de parcare").isEmpty())
  }

  @Test fun multipleMatchesAreAllReturnedForDisambiguation() {
    val members = listOf(
      member("de.swm.parken", "HandyParken München", "parcare"),
      member("at.handyparken", "HANDY PARKEN Austria", "parcare"),
    )
    assertEquals(2, AkaDiscovery.discoverByPurpose(members, "caută aplicația de parcare").size)
  }

  // --- O descoperire NU e o capabilitate — nu poate deveni executabila singura ---

  @Test fun discoveredAppWithNoRegisteredCapabilityIsNeverSelectableByAka() {
    val member = member("de.swm.parken", "HandyParken", "parcare") // caps = emptyMap() implicit
    val discovered = AkaDiscovery.discoverByPurpose(listOf(member), "caută aplicația de parcare")
    assertEquals(1, discovered.size)

    val lookup = StaffRegistryCapabilityLookup { listOf(member) }
    // Descoperirea a gasit aplicatia, dar AKA tot refuza sa o ofere ca executabila - nicio
    // capabilitate inregistrata/verificata pentru ea.
    assertEquals(null, lookup.selectCapability("open_parking_app"))
  }
}
