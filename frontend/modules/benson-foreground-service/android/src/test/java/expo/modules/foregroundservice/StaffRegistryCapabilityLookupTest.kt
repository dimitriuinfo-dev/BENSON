package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffRegistryCapabilityLookupTest {

  private fun member(pkg: String, caps: Map<String, CapLevel>) = StaffMember(
    packageName = pkg, displayName = pkg, category = "", launcher = true,
    firstInstallTime = 0L, lastUpdateTime = 0L, versionName = "1.0", versionCode = 1L, caps = caps,
  )

  // --- Proveniența + nivelul de validare — nu se confunda inregistrarea cu verificarea ---

  @Test fun deviceVerifiedCapabilityIsUsable() {
    val lookup = StaffRegistryCapabilityLookup {
      listOf(member("com.waze", mapOf(StaffRegistryCore.CAP_NAVIGATE to CapLevel.VERIFIED)))
    }
    val record = lookup.selectCapability(StaffRegistryCore.CAP_NAVIGATE)
    assertEquals("com.waze", record?.appPackage)
    assertEquals(CapabilityVerificationState.DEVICE_VERIFIED, record?.state)
  }

  @Test fun onlyKnownNeverVerifiedIsNotUsableEvenThoughItExists() {
    // CapLevel.KNOWN -> DECLARED: capabilitatea EXISTA in registru (tabelul din cod), dar nu a
    // fost niciodata verificata pe telefon - AKA nu o ofera ca utilizabila.
    val lookup = StaffRegistryCapabilityLookup {
      listOf(member("com.some.app", mapOf("search_products" to CapLevel.KNOWN)))
    }
    assertNull(lookup.selectCapability("search_products"))
  }

  @Test fun reverifyAfterAppUpdateIsNotUsableUntilReconfirmed() {
    val lookup = StaffRegistryCapabilityLookup {
      listOf(member("com.whatsapp", mapOf(StaffRegistryCore.CAP_CALL to CapLevel.REVERIFY)))
    }
    assertNull(lookup.selectCapability(StaffRegistryCore.CAP_CALL))
  }

  @Test fun noAppAtAllHasTheCapabilityReturnsNullNotAGuess() {
    val lookup = StaffRegistryCapabilityLookup { emptyList() }
    assertNull(lookup.selectCapability(StaffRegistryCore.CAP_NAVIGATE))
  }

  @Test fun picksTheMostAdvancedAcrossMultipleAppsWithTheSameCapability() {
    val lookup = StaffRegistryCapabilityLookup {
      listOf(
        member("com.google.android.apps.maps", mapOf(StaffRegistryCore.CAP_NAVIGATE to CapLevel.KNOWN)),
        member("com.waze", mapOf(StaffRegistryCore.CAP_NAVIGATE to CapLevel.VERIFIED)),
      )
    }
    assertEquals("com.waze", lookup.selectCapability(StaffRegistryCore.CAP_NAVIGATE)?.appPackage)
  }

  @Test fun realSeedDataWhatsAppIsImmediatelyUsableSpotifyNever() {
    // Datele reale de start (StaffRegistryCore.initialCaps) - nu fixturi inventate aici.
    val lookup = StaffRegistryCapabilityLookup {
      listOf(
        member("com.whatsapp", StaffRegistryCore.initialCaps("com.whatsapp")),
        member("com.spotify.music", StaffRegistryCore.initialCaps("com.spotify.music")),
      )
    }
    assertEquals(CapabilityVerificationState.DEVICE_VERIFIED, lookup.selectCapability(StaffRegistryCore.CAP_CALL)?.state)
    assertNull(lookup.selectCapability(StaffRegistryCore.CAP_MEDIA_SEARCH)) // Spotify ramane PARTIAL, niciodata oferit ca sigur
  }

  // --- SURVIVOR obligatoriu: AKA ofera capabilitatea, dar EXECUTORUL tot cere verificare reala ---

  @Test fun akaSelectedCapabilityStillRequiresSurvivorVerificationAtExecution() {
    val lookup = StaffRegistryCapabilityLookup {
      listOf(member("com.waze", mapOf(StaffRegistryCore.CAP_NAVIGATE to CapLevel.VERIFIED)))
    }
    // EXECUTOR simulat: prim-planul NU confirma Waze (ex. lansarea a esuat in realitate) -
    // rezultatul simulat NU poate deveni succes real doar pentru ca AKA a aprobat capabilitatea.
    val executor = NavigationExecutionEngine(
      expectedPackagesFor = { setOf("com.waze") },
      observeForegroundPackage = { "com.android.launcher" }, // altceva, nu Waze
      maxPolls = 1,
    )
    val live = BensonLiveOrchestrator(
      modelProvider = object : ModelProvider {
        override val providerId = "fake"
        override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>) =
          BrainOutput.ActionRequest(StaffRegistryCore.CAP_NAVIGATE, listOf(DomainEntity.Destination("aeroport")), SemanticConfidence.HIGH, "ro")
      },
      knownActions = setOf(StaffRegistryCore.CAP_NAVIGATE),
      actionToAgent = mapOf(StaffRegistryCore.CAP_NAVIGATE to "TRAVEL"),
      confirmRequiredActions = setOf(StaffRegistryCore.CAP_NAVIGATE),
      capabilityLookup = lookup,
      executionEngine = executor,
    )
    val result = live.handleTextInput("du-ma la aeroport", "m-aka-1")
    assertTrue(result is MissionResult.Executed)
    assertTrue((result as MissionResult.Executed).outcome is ExecutionOutcome.Failed)
  }
}
