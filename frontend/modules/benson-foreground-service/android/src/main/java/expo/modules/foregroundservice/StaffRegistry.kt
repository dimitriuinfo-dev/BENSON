package expo.modules.foregroundservice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

// LEGEA PERSONALULUI BENSON — partea dependenta de Context: enumerarea PackageManager (UNA
// SINGURA in tot codul, vezi M1), persistenta locala, reconcilierea si jurnalul de miscari.
// Regulile si datele pure stau in StaffRegistryCore.kt.
//
// M1: porneste in BensonForegroundService la start, mereu activ. APP_REGISTRY e DOAR revert de
//     urgenta (OFF = comportamentul de dinainte, adica enumerare live la fiecare apel).
// P1.3/M3: persistent local, recalculat la PACKAGE_ADDED/REMOVED/REPLACED + reconciliere completa
//     la fiecare pornire (prinde ce s-a schimbat cat BENSON a fost oprit).
// P1.6: nicio explorare automata. Registrul inregistreaza doar CINE e instalat si CE ROL are;
//     nu deschide nimic, nu citeste date din alte aplicatii, nimic nu pleaca din telefon.
internal object StaffRegistry {
  private const val PREFS = "benson_staff_registry"
  private const val KEY_STAFF = "staff_v1"
  private const val KEY_JOURNAL = "journal_v1"
  private const val KEY_BOOTSTRAPPED_AT = "bootstrapped_at"
  private const val MAX_JOURNAL = 200

  @Volatile private var cache: List<StaffMember>? = null

  private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // Singura enumerare de aplicatii instalate din registru. Identica, intentionat, cu cea pe care
  // findLaunchablePackage o folosea direct inainte (MAIN+LAUNCHER) — ca sa nu existe doua liste.
  private fun enumerate(ctx: Context): List<StaffMember> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { return emptyList() }
    val seen = linkedSetOf<String>()
    val out = mutableListOf<StaffMember>()
    for (ri in apps) {
      val pkg = ri.activityInfo?.packageName ?: continue
      if (!seen.add(pkg)) continue
      val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
      var firstInstall = 0L
      var lastUpdate = 0L
      var versionName = ""
      var versionCode = 0L
      var category = ""
      try {
        val pi = pm.getPackageInfo(pkg, 0)
        firstInstall = pi.firstInstallTime
        lastUpdate = pi.lastUpdateTime
        versionName = pi.versionName ?: ""
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val ai = pi.applicationInfo
        if (ai != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          category = categoryName(ai.category)
        }
      } catch (_: PackageManager.NameNotFoundException) {
      } catch (_: Exception) {
      }
      out.add(
        StaffMember(
          packageName = pkg,
          displayName = label,
          category = category,
          launcher = true,
          firstInstallTime = firstInstall,
          lastUpdateTime = lastUpdate,
          versionName = versionName,
          versionCode = versionCode,
        )
      )
    }
    return out
  }

  private fun categoryName(category: Int): String = when (category) {
    android.content.pm.ApplicationInfo.CATEGORY_GAME -> "game"
    android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "audio"
    android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "video"
    android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "image"
    android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "social"
    android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "news"
    android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "maps"
    android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
    else -> ""
  }

  fun load(ctx: Context): List<StaffMember> {
    cache?.let { return it }
    val list = StaffRegistryCore.deserialize(prefs(ctx).getString(KEY_STAFF, null))
    cache = list
    return list
  }

  private fun save(ctx: Context, list: List<StaffMember>) {
    cache = list
    prefs(ctx).edit().putString(KEY_STAFF, StaffRegistryCore.serialize(list)).apply()
  }

  fun isBootstrapped(ctx: Context): Boolean = prefs(ctx).getLong(KEY_BOOTSTRAPPED_AT, 0L) > 0L

  // Se aplica automat la prima instalare pe orice telefon, dupa ce serviciul porneste (adica dupa
  // acordarea permisiunilor din onboarding). Idempotent: la pornirile urmatoare e reconciliere.
  // Returneaza true daca registrul a fost scris.
  fun bootstrapAndReconcile(ctx: Context, reason: String): Boolean {
    val previous = load(ctx)
    val fresh = enumerate(ctx)
    val previousActive = previous.count { !it.archived }
    if (!StaffRegistryCore.isPlausibleEnumeration(fresh.size, previousActive)) {
      // Nu stricam un registru bun cu o enumerare filtrata (vezi MIN_PLAUSIBLE_STAFF).
      AudioDiag.log(ctx, "REGISTRY_DEFERRED", "reason=implausible_enumeration fresh=${fresh.size} prev=$previousActive")
      return false
    }
    val events = StaffRegistryCore.diff(previous, fresh)
    val reconciled = StaffRegistryCore.reconcile(previous, fresh)
    save(ctx, reconciled)
    if (!isBootstrapped(ctx)) {
      prefs(ctx).edit().putLong(KEY_BOOTSTRAPPED_AT, System.currentTimeMillis()).apply()
      AudioDiag.log(ctx, "REGISTRY_BOOTSTRAP", "staff=${fresh.size} reason=$reason")
    }
    appendJournal(ctx, events)
    val (total, known, verified) = StaffRegistryCore.counts(reconciled)
    // P1.3: in log doar numere si niveluri, NICIODATA nume de aplicatii.
    AudioDiag.log(ctx, "REGISTRY", "count=$total known=$known verified=$verified reason=$reason")
    for (kind in StaffEventKind.values()) {
      val n = events.count { it.kind == kind }
      if (n > 0) AudioDiag.log(ctx, "REGISTRY_EVENT", "kind=${kind.name.lowercase()} n=$n")
    }
    return true
  }

  // Jurnal local de miscari de personal (M3). Local, fara nume in logcat.
  private fun appendJournal(ctx: Context, events: List<StaffEvent>) {
    if (events.isEmpty()) return
    val now = System.currentTimeMillis()
    val existing = prefs(ctx).getString(KEY_JOURNAL, "") ?: ""
    val added = events.joinToString("\n") {
      listOf(now.toString(), it.packageName, it.kind.name.lowercase(), it.fromVersion, it.toVersion).joinToString("\t")
    }
    val merged = (if (existing.isBlank()) added else "$existing\n$added")
      .split('\n').filter { it.isNotBlank() }.takeLast(MAX_JOURNAL).joinToString("\n")
    prefs(ctx).edit().putString(KEY_JOURNAL, merged).apply()
  }

  fun journal(ctx: Context): List<String> =
    (prefs(ctx).getString(KEY_JOURNAL, "") ?: "").split('\n').filter { it.isNotBlank() }

  // Vederea folosita de resolverul de aplicatii (F2): (eticheta afisata, packageName).
  // Asta e sensul lui "APP_INDEX devine o vedere a registrului, nu o a doua lista" (M1).
  // Gol => apelantul face enumerare live (comportamentul de dinainte), ca sa nu existe nicio
  // fereastra in care "deschide X" sa nu mai functioneze.
  fun launchableSnapshot(ctx: Context): List<Pair<String, String>> =
    load(ctx).filter { !it.archived && it.launcher }.map { it.displayName to it.packageName }

  // L-SERVICE-001 punctul 1.2 — rezolvare dupa SCOP, nu dupa numele aplicatiei: "cine din personal
  // poate face asta?". Ordinea: VERIFIED inaintea lui REVERIFY inaintea lui KNOWN (legea spune ca
  // operational se declara doar verified, dar pentru ALEGEREA executantului un known instalat e
  // mai bun decat nimic — nivelul se raporteaza apelantului, care decide ce spune utilizatorului).
  // Fara candidat => null, iar apelantul trece la pasul urmator din L-SERVICE-002 (WEB, apoi
  // recomandare Play Store). Zero index nou: totul din registru.
  fun staffForCapability(ctx: Context, capabilityId: String): List<Pair<StaffMember, CapLevel>> =
    load(ctx)
      .filter { !it.archived && it.caps.containsKey(capabilityId) }
      .filter { !StaffRegistryCore.isRefused(it.packageName, it.category) }
      .map { it to (it.caps[capabilityId] ?: CapLevel.KNOWN) }
      .sortedBy {
        when (it.second) {
          CapLevel.VERIFIED -> 0
          CapLevel.REVERIFY -> 1
          CapLevel.KNOWN -> 2
        }
      }

  fun capsOf(ctx: Context, packageName: String): Map<String, CapLevel> =
    load(ctx).firstOrNull { it.packageName == packageName }?.caps ?: emptyMap()

  fun dossier(ctx: Context, packageName: String): StaffMember? =
    load(ctx).firstOrNull { it.packageName == packageName }

  // Promovarea la verified se face DOAR dupa o verificare reala a efectului pe telefon
  // (verify_action_result), niciodata dintr-un click reusit sau un apel fara eroare (doctrina 9).
  fun markVerified(ctx: Context, packageName: String, capabilityId: String) {
    val list = load(ctx)
    val idx = list.indexOfFirst { it.packageName == packageName }
    if (idx < 0) return
    val m = list[idx]
    if (m.caps[capabilityId] == CapLevel.VERIFIED) return
    val updated = list.toMutableList()
    updated[idx] = m.copy(caps = StaffRegistryCore.promoteVerified(m.caps, capabilityId))
    save(ctx, updated)
    AudioDiag.log(ctx, "REGISTRY_EVENT", "kind=verified cap=$capabilityId")
  }

  // AKA — INVENTARIERE AUTOMATA (20.10.2026, punctul 1 al ordinului: "AKA inventariaza automat
  // aplicatiile vizibile... fara lista inchisa"). Inregistreaza o functie candidata DESCOPERITA
  // (vazuta, clickabila, inca neapasata) — DOAR daca nu exista deja NICIO dovada pentru acea
  // cheie. Nu retrogradeaza NICIODATA un VERIFIED/REVERIFY existent inapoi la KNOWN — descoperirea
  // repetata a aceleiasi functii nu sterge o dovada mai buna, deja castigata.
  fun markDiscovered(ctx: Context, packageName: String, capabilityId: String) {
    val list = load(ctx)
    val idx = list.indexOfFirst { it.packageName == packageName }
    if (idx < 0) return
    val m = list[idx]
    if (m.caps.containsKey(capabilityId)) return
    val updated = list.toMutableList()
    val newCaps = LinkedHashMap(m.caps)
    newCaps[capabilityId] = CapLevel.KNOWN
    updated[idx] = m.copy(caps = newCaps)
    save(ctx, updated)
  }

  fun invalidate() { cache = null }
}
