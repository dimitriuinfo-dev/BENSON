package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationCapabilityRegistryTest {

  private fun record(state: CapabilityVerificationState) = CapabilityRecord(
    appPackage = "com.waze", operation = StaffRegistryCore.CAP_NAVIGATE, state = state, requiresConfirmation = true,
  )

  @Test fun canPromoteOnlyOneStepAtATime() {
    assertTrue(canPromote(CapabilityVerificationState.DECLARED, CapabilityVerificationState.INFERRED))
    assertTrue(canPromote(CapabilityVerificationState.UNIT_TESTED, CapabilityVerificationState.DEVICE_VERIFIED))
    assertFalse(canPromote(CapabilityVerificationState.DECLARED, CapabilityVerificationState.DEVICE_VERIFIED))
    assertFalse(canPromote(CapabilityVerificationState.DEVICE_VERIFIED, CapabilityVerificationState.DECLARED))
  }

  @Test fun promoteRejectsSkippedLevel() {
    assertNull(promote(record(CapabilityVerificationState.DECLARED), CapabilityVerificationState.DEVICE_VERIFIED))
  }

  @Test fun promoteAdvancesOneLevelAndStampsBuild() {
    val promoted = promote(record(CapabilityVerificationState.UNIT_TESTED), CapabilityVerificationState.DEVICE_VERIFIED, build = "abc123")
    assertEquals(CapabilityVerificationState.DEVICE_VERIFIED, promoted!!.state)
    assertEquals("abc123", promoted.lastVerifiedBuild)
    assertNull(promoted.invalidatedReason)
  }

  @Test fun onlyDeviceOrRuntimeVerifiedIsSafeToStateAsOperational() {
    assertFalse(isSafeToStateAsOperational(record(CapabilityVerificationState.DECLARED)))
    assertFalse(isSafeToStateAsOperational(record(CapabilityVerificationState.INFERRED)))
    assertFalse(isSafeToStateAsOperational(record(CapabilityVerificationState.UNIT_TESTED)))
    assertTrue(isSafeToStateAsOperational(record(CapabilityVerificationState.DEVICE_VERIFIED)))
    assertTrue(isSafeToStateAsOperational(record(CapabilityVerificationState.RUNTIME_VERIFIED)))
  }

  @Test fun appUpdateDowngradesVerifiedToUnitTestedNotToDeclared() {
    val downgraded = invalidateAfterAppUpdate(record(CapabilityVerificationState.DEVICE_VERIFIED), "version_changed")
    assertEquals(CapabilityVerificationState.UNIT_TESTED, downgraded.state)
    assertEquals("version_changed", downgraded.invalidatedReason)
  }

  @Test fun appUpdateIsNoOpBelowVerified() {
    val unchanged = invalidateAfterAppUpdate(record(CapabilityVerificationState.DECLARED), "version_changed")
    assertEquals(CapabilityVerificationState.DECLARED, unchanged.state)
    assertNull(unchanged.invalidatedReason)
  }

  @Test fun selectBestCapabilityPicksMostAdvancedMatchingOperation() {
    val records = listOf(
      CapabilityRecord("com.google.android.apps.maps", StaffRegistryCore.CAP_NAVIGATE, CapabilityVerificationState.UNIT_TESTED, true),
      CapabilityRecord("com.waze", StaffRegistryCore.CAP_NAVIGATE, CapabilityVerificationState.DEVICE_VERIFIED, true),
      CapabilityRecord("com.whatsapp", StaffRegistryCore.CAP_CALL, CapabilityVerificationState.RUNTIME_VERIFIED, true),
    )
    val best = selectBestCapability(records, StaffRegistryCore.CAP_NAVIGATE)
    assertEquals("com.waze", best!!.appPackage)
  }

  @Test fun selectBestCapabilityReturnsNullWhenNoneMatch() {
    assertNull(selectBestCapability(emptyList(), StaffRegistryCore.CAP_NAVIGATE))
  }

  // --- Puntea catre StaffRegistryCore real — nu fixturi, datele seminal ale registrului ---

  @Test fun capLevelMappingIsConservative() {
    assertEquals(CapabilityVerificationState.DECLARED, verificationStateFromCapLevel(CapLevel.KNOWN))
    assertEquals(CapabilityVerificationState.UNIT_TESTED, verificationStateFromCapLevel(CapLevel.REVERIFY))
    assertEquals(CapabilityVerificationState.DEVICE_VERIFIED, verificationStateFromCapLevel(CapLevel.VERIFIED))
  }

  @Test fun whatsappRealSeedDataMapsToDeviceVerifiedForCall() {
    // StaffRegistryCore.initialCaps e sursa REALA (deja testata in StaffRegistryCoreTest ca
    // seamana VERIFIED exclusiv din comportamente dovedite pe telefon) — nu o fixtura inventata
    // aici pentru acest test.
    val caps = StaffRegistryCore.initialCaps("com.whatsapp")
    val member = StaffMember(
      packageName = "com.whatsapp", displayName = "WhatsApp", category = "", launcher = true,
      firstInstallTime = 0L, lastUpdateTime = 0L, versionName = "1.0", versionCode = 1L, caps = caps,
    )
    val records = capabilityRecordsFromStaffMember(member)
    val callRecord = records.first { it.operation == StaffRegistryCore.CAP_CALL }
    assertEquals(CapabilityVerificationState.DEVICE_VERIFIED, callRecord.state)
    assertTrue(isSafeToStateAsOperational(callRecord))
  }

  @Test fun spotifyRealSeedDataNeverMapsToDeviceVerified() {
    // CLAUDE.md declara Spotify PARTIAL explicit — StaffRegistryCoreTest il tine la "known";
    // puntea trebuie sa respecte asta, nu sa-l promoveze singura.
    val caps = StaffRegistryCore.initialCaps("com.spotify.music")
    val member = StaffMember(
      packageName = "com.spotify.music", displayName = "Spotify", category = "", launcher = true,
      firstInstallTime = 0L, lastUpdateTime = 0L, versionName = "1.0", versionCode = 1L, caps = caps,
    )
    val records = capabilityRecordsFromStaffMember(member)
    assertTrue(records.none { isSafeToStateAsOperational(it) })
  }
}
