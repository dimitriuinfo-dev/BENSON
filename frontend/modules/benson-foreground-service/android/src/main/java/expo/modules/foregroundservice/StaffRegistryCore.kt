package expo.modules.foregroundservice

// LEGEA PERSONALULUI BENSON — nucleul pur (zero Context, zero org.json, testabil JUnit).
// Sursa normativa: docs/specs/LEGEA_PERSONALULUI_BENSON.md, derivata verbatim din
// docs/specs/PASUL_1_REGISTRY_2026-10-14.md (P1.1-P1.3) + docs/specs/PASUL_1_MOTOR_2026-10-14.md
// (M2-M4), deciziile Rares 14.10.2026 09:24 si 09:25.
// Partea dependenta de Context (enumerare PackageManager, persistenta, receiver) sta in
// StaffRegistry.kt — aici doar date si reguli, ca sa fie verificabile fara dispozitiv.

internal enum class CapLevel { KNOWN, REVERIFY, VERIFIED }

// Doctrina 3 (fara plati) + P1.2: bancar = REFUSE (refuz, nu confirmare); clasele ireversibile
// cer confirmare explicita prin ConfirmationGate-ul existent.
internal enum class CapRisk { NONE, CONFIRM, REFUSE }

internal enum class StaffEventKind { SOSIT, PLECAT, ACTUALIZAT }

internal data class StaffEvent(
  val packageName: String,
  val kind: StaffEventKind,
  val fromVersion: String = "",
  val toVersion: String = "",
)

internal data class StaffMember(
  val packageName: String,
  val displayName: String,
  val category: String,
  val launcher: Boolean,
  val firstInstallTime: Long,
  val lastUpdateTime: Long,
  val versionName: String,
  val versionCode: Long,
  val archived: Boolean = false,
  // capabilityId -> nivel. Nivelul VERIFIED se obtine DOAR prin verificare pe telefon.
  val caps: Map<String, CapLevel> = emptyMap(),
  val versionHistory: List<String> = emptyList(),
)

internal object StaffRegistryCore {
  const val CAP_NAVIGATE = "navigate"
  const val CAP_MEDIA_SEARCH = "media_search"
  const val CAP_MEDIA_CONTROL = "media_control"
  const val CAP_MESSAGE = "message"
  const val CAP_CALL = "call"
  const val CAP_VIDEO_CALL = "video_call"
  const val CAP_CALCULATE = "calculate"
  const val CAP_SEARCH_PRODUCTS = "search_products"
  const val CAP_WEB_SEARCH = "web_search"

  // AKA V1 (20.10.2026, "BENSON care știe CE FAC aplicațiile, nu doar numele lor"), CORECTAT
  // (20.10.2026, "nu confunda CAP_UI_CONTROL cu demonstrarea tuturor funcțiilor aplicației") —
  // prima versiune folosea UN SINGUR flag generic per aplicație; un singur buton apasat ar fi
  // promovat intreaga aplicatie, ascunzand ce anume s-a verificat de fapt. Corectia: cheia
  // insasi codifica CONTROLUL/OPERATIA specifica, in ACELASI map `caps` (niciun registru nou,
  // niciun fisier nou — serialize/deserialize sunt deja generice pe orice cheie string).
  // "ui_control:7" != "ui_control:cauta" != "ui_control:multiplizieren" — fiecare e dovada
  // PROPRIE, cu propriul ei nivel KNOWN/REVERIFY/VERIFIED, promovata independent.
  const val CAP_UI_CONTROL_PREFIX = "ui_control:"

  private fun normalizeCapLabel(label: String): String =
    java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .lowercase()
      .trim()
      .take(40)

  /** Cheia specifica, per control/operatie, pentru `caps` — nu un flag generic pe toata aplicatia. */
  fun uiControlCapabilityId(label: String): String = CAP_UI_CONTROL_PREFIX + normalizeCapLabel(label)

  fun isUiControlCapability(capabilityId: String): Boolean = capabilityId.startsWith(CAP_UI_CONTROL_PREFIX)

  // LIVE consulta AKA (punctul 5, 20.10.2026): formatare pura a cunostintelor deja VERIFICATE,
  // ca sa poata fi atasate la rezultatul `observe_screen` — "ce stiu deja sigur despre acest ecran,
  // din rulari anterioare", nu doar ce vad acum. Null cand nu exista nimic de aratat.
  fun formatKnownUiControls(caps: Map<String, CapLevel>): String? {
    val known = caps.entries
      .filter { it.value == CapLevel.VERIFIED && isUiControlCapability(it.key) }
      .map { it.key.removePrefix(CAP_UI_CONTROL_PREFIX) }
      .filter { it.isNotBlank() }
    if (known.isEmpty()) return null
    return "Cunoștințe AKA anterioare, deja verificate pentru acest ecran: ${known.joinToString(", ")}."
  }

  // CONTRACTUL COMPLET prin care LIVE primeste cunostintele AKA (20.10.2026, cerinta explicita:
  // "verifica local contractul complet", nu doar functia de formatare izolat). Pura — exact
  // compunerea pe care runAgentToolLocked o aplica inainte sa trimita textul catre model, extrasa
  // aici ca sa fie testabila JUnit fara Context. Cand nu exista nicio cunostinta verificata,
  // textul observarii ramane EXACT neschimbat (zero regresie pe ecranele/aplicatiile fara istoric
  // AKA — inclusiv Calculator/WhatsApp/Waze, deja dovedite, care nu trebuie sa vada text nou).
  // AKA — CUNOASTEREA FUNCTIILOR SI A NAVIGATIEI, nu doar a etichetelor de buton (corectie
  // arhitecturala, 20.10.2026: "AKA nu e memorarea apasarilor, e cunoasterea autonoma a
  // aplicatiilor"). Doua familii noi de chei, in ACELASI map `caps` (niciun registru nou):
  //   aka_fn:<semnatura_ecran>:<eticheta>  — "acest ecran are o functie candidata numita X".
  //     KNOWN    = descoperita prin observare (clickabila, in afara unei liste derulabile —
  //                AkaKnowledge.discoverCandidateFunctions, benson-accessibility, pur).
  //     VERIFIED = EXECUTOR a apasat-o si SURVIVOR a confirmat o tranzitie reala de ecran.
  //   aka_nav:<semnatura_sursa>:<eticheta>:<semnatura_destinatie> — harta de navigare (punctul 4
  //     al ordinului): "apasarea X de pe ecranul sursa duce la ecranul destinatie", VERIFIED
  //     doar dupa o tranzitie REALA, observata inainte si dupa apasare.
  // Semnatura vine din AkaKnowledge.ScreenSignature — aici doar cheile si formatarea.
  const val CAP_AKA_FUNCTION_PREFIX = "aka_fn:"
  const val CAP_AKA_NAV_PREFIX = "aka_nav:"

  fun akaFunctionCapabilityId(screenSignature: String, label: String): String =
    CAP_AKA_FUNCTION_PREFIX + screenSignature + ":" + normalizeCapLabel(label)

  fun akaNavigationCapabilityId(fromSignature: String, label: String, toSignature: String): String =
    CAP_AKA_NAV_PREFIX + fromSignature + ":" + normalizeCapLabel(label) + ":" + toSignature

  fun isAkaFunctionCapability(capabilityId: String): Boolean = capabilityId.startsWith(CAP_AKA_FUNCTION_PREFIX)
  fun isAkaNavigationCapability(capabilityId: String): Boolean = capabilityId.startsWith(CAP_AKA_NAV_PREFIX)

  // Functiile candidate/verificate ale ECRANULUI CURENT (nu ale aplicatiei intregi, punctul 2:
  // "nu confunda memorarea unei etichete cu intelegerea functiilor") — separa explicit ce e
  // DOAR DESCOPERIT (KNOWN) de ce e CONFIRMAT printr-o apasare reala, verificata (VERIFIED).
  fun formatKnownFunctions(caps: Map<String, CapLevel>, screenSignature: String): String? {
    val prefix = CAP_AKA_FUNCTION_PREFIX + screenSignature + ":"
    val verified = caps.entries.filter { it.key.startsWith(prefix) && it.value == CapLevel.VERIFIED }
      .map { it.key.removePrefix(prefix) }.filter { it.isNotBlank() }
    val discovered = caps.entries.filter { it.key.startsWith(prefix) && it.value == CapLevel.KNOWN }
      .map { it.key.removePrefix(prefix) }.filter { it.isNotBlank() }
    if (verified.isEmpty() && discovered.isEmpty()) return null
    val parts = mutableListOf<String>()
    if (verified.isNotEmpty()) parts.add("funcții confirmate prin apăsare reală: ${verified.joinToString(", ")}")
    if (discovered.isNotEmpty()) parts.add("funcții descoperite, neconfirmate încă: ${discovered.joinToString(", ")}")
    return "Cunoștințe AKA pentru acest ecran — ${parts.joinToString("; ")}."
  }

  // Harta de navigare cunoscuta DIN acest ecran — "daca apesi X, ajungi la un ecran deja cunoscut".
  fun formatNavigationOptions(caps: Map<String, CapLevel>, fromSignature: String): String? {
    val prefix = CAP_AKA_NAV_PREFIX + fromSignature + ":"
    val edges = caps.entries.filter { it.key.startsWith(prefix) && it.value == CapLevel.VERIFIED }
      .mapNotNull { e ->
        val remainder = e.key.removePrefix(prefix)
        val label = remainder.substringBefore(':')
        if (label.isBlank() || label == remainder) null else label
      }.distinct()
    if (edges.isEmpty()) return null
    return "Trasee de navigare deja confirmate de pe acest ecran: ${edges.joinToString(", ")}."
  }

  // CONTRACTUL COMPLET prin care LIVE primeste cunostintele AKA (testat JUnit, fara Context —
  // exact compunerea pe care runAgentToolLocked o aplica inainte sa trimita textul catre model).
  // `screenSignature` opțional (null = comportamentul vechi, doar ui_control:*, zero regresie).
  fun attachKnownUiControls(screenText: String, caps: Map<String, CapLevel>, screenSignature: String? = null): String {
    val lines = mutableListOf<String>()
    formatKnownUiControls(caps)?.let { lines.add(it) }
    if (screenSignature != null) {
      formatKnownFunctions(caps, screenSignature)?.let { lines.add(it) }
      formatNavigationOptions(caps, screenSignature)?.let { lines.add(it) }
    }
    if (lines.isEmpty()) return screenText
    return screenText + "\n" + lines.joinToString("\n")
  }

  // AKA — "LIVE solicită informații despre o aplicație... identifică lipsurile" (ordin 20.10.2026,
  // sectiunea "Colaborarea cu LIVE"). Rezumat pur, pe TOATĂ aplicația (nu doar ecranul curent) —
  // cheile aka_fn/aka_nav sunt filtrate explicit pe acest pachet (semnatura de ecran incepe cu
  // packageName), ca sa nu apara dovezi de pe alt pachet. Cand nu exista NICIO cunostinta, spune
  // explicit ca aplicatia n-a fost inca explorata — identificarea lipsei e la fel de importanta
  // ca raportarea cunostintei.
  fun formatAppKnowledgeSummary(member: StaffMember): String {
    val caps = member.caps
    val named = caps.filterKeys { !isUiControlCapability(it) && !isAkaFunctionCapability(it) && !isAkaNavigationCapability(it) }
    val fnForThisApp = caps.filterKeys { isAkaFunctionCapability(it) && it.removePrefix(CAP_AKA_FUNCTION_PREFIX).startsWith("${member.packageName}:") }
    val navForThisApp = caps.filterKeys { isAkaNavigationCapability(it) && it.removePrefix(CAP_AKA_NAV_PREFIX).startsWith("${member.packageName}:") }
    val ui = caps.filterKeys { isUiControlCapability(it) }

    val namedText = if (named.isEmpty()) null else
      "funcții numite: " + named.entries.joinToString(", ") { "${it.key} (${it.value.name.lowercase()})" }
    val fnVerified = fnForThisApp.values.count { it == CapLevel.VERIFIED }
    val fnKnown = fnForThisApp.values.count { it == CapLevel.KNOWN }
    val fnText = if (fnForThisApp.isEmpty()) null else
      "$fnVerified funcții de interfață confirmate prin apăsare, $fnKnown descoperite dar neconfirmate încă"
    val navVerified = navForThisApp.values.count { it == CapLevel.VERIFIED }
    val navText = if (navVerified == 0) null else "$navVerified trasee de navigare confirmate"
    val uiVerified = ui.values.count { it == CapLevel.VERIFIED }
    val uiText = if (uiVerified == 0) null else "$uiVerified controale individuale confirmate"

    val parts = listOfNotNull(namedText, fnText, navText, uiText)
    return if (parts.isEmpty()) {
      "„${member.displayName}” nu are încă nicio cunoștință AKA — nu a fost explorată."
    } else {
      "„${member.displayName}”: ${parts.joinToString("; ")}."
    }
  }

  const val MAX_VERSION_HISTORY = 10

  // "known" = fisa postului din tabelul din cod (P1.2). NU implica faptul ca merge pe acest
  // telefon — doar ca rolul e cunoscut.
  private val KNOWN: Map<String, List<String>> = mapOf(
    "com.waze" to listOf(CAP_NAVIGATE),
    "com.google.android.apps.maps" to listOf(CAP_NAVIGATE),
    "com.google.android.youtube" to listOf(CAP_MEDIA_SEARCH, CAP_MEDIA_CONTROL),
    "com.spotify.music" to listOf(CAP_MEDIA_SEARCH, CAP_MEDIA_CONTROL),
    "com.whatsapp" to listOf(CAP_MESSAGE, CAP_CALL, CAP_VIDEO_CALL),
    "com.oneplus.calculator" to listOf(CAP_CALCULATE),
    "com.amazon.mShop.android.shopping" to listOf(CAP_SEARCH_PRODUCTS),
    "com.android.chrome" to listOf(CAP_WEB_SEARCH),
    "com.opera.browser" to listOf(CAP_WEB_SEARCH),
  )

  // "verified" se seminteaza EXCLUSIV din "Comportamente dovedite" (CLAUDE.md), adica din teste
  // trecute pe acest telefon, cu data. Spotify NU intra: CLAUDE.md il declara explicit PARTIAL
  // ("prototip functional partial dovedit"), deci ramane doar known.
  private val SEEDED_VERIFIED: Map<String, List<String>> = mapOf(
    "com.whatsapp" to listOf(CAP_CALL, CAP_VIDEO_CALL, CAP_MESSAGE),
    "com.google.android.youtube" to listOf(CAP_MEDIA_SEARCH, CAP_MEDIA_CONTROL),
    "com.waze" to listOf(CAP_NAVIGATE),
    "com.oneplus.calculator" to listOf(CAP_CALCULATE),
  )

  private val RISK: Map<String, CapRisk> = mapOf(
    CAP_MESSAGE to CapRisk.CONFIRM,
    CAP_CALL to CapRisk.CONFIRM,
    CAP_VIDEO_CALL to CapRisk.CONFIRM,
    CAP_NAVIGATE to CapRisk.NONE,
    CAP_MEDIA_SEARCH to CapRisk.NONE,
    CAP_MEDIA_CONTROL to CapRisk.NONE,
    CAP_CALCULATE to CapRisk.NONE,
    CAP_SEARCH_PRODUCTS to CapRisk.NONE,
    CAP_WEB_SEARCH to CapRisk.NONE,
  )

  // Clase pe care BENSON nu le executa deloc (doctrina 3 + P1.6). Potrivire pe pachet/categorie.
  private val BANKING_HINTS = listOf("bank", "banking", "revolut", "paypal", "sparkasse", "n26", "wise")

  fun knownCapsFor(packageName: String): List<String> = KNOWN[packageName] ?: emptyList()

  // "ui_control:<eticheta>" e o familie DINAMICĂ de chei (una per control descoperit), nu poate
  // sta într-un map static — recunoscută prin prefix. Apasarea insasi e deja verificata de
  // ButtonSafetyClassifier la momentul actiunii (plata/cumparare refuzate acolo); aceasta
  // capabilitate doar inregistreaza dovada, deci risc NONE.
  fun riskOf(capabilityId: String): CapRisk =
    if (isUiControlCapability(capabilityId) || isAkaFunctionCapability(capabilityId) || isAkaNavigationCapability(capabilityId)) {
      CapRisk.NONE
    } else {
      RISK[capabilityId] ?: CapRisk.CONFIRM
    }

  fun isRefused(packageName: String, category: String): Boolean {
    val p = packageName.lowercase()
    if (category.equals("banking", true) || category.equals("finance", true)) return true
    return BANKING_HINTS.any { p.contains(it) }
  }

  // Fisa initiala a unui angajat nou: known din tabel, verified doar din seminte.
  fun initialCaps(packageName: String): Map<String, CapLevel> {
    val out = linkedMapOf<String, CapLevel>()
    for (c in knownCapsFor(packageName)) out[c] = CapLevel.KNOWN
    for (c in SEEDED_VERIFIED[packageName].orEmpty()) out[c] = CapLevel.VERIFIED
    return out
  }

  // M4 — dupa un update, tot ce era VERIFIED devine REVERIFY. KNOWN ramane KNOWN.
  fun demoteAfterUpdate(caps: Map<String, CapLevel>): Map<String, CapLevel> =
    caps.mapValues { (_, lvl) -> if (lvl == CapLevel.VERIFIED) CapLevel.REVERIFY else lvl }

  fun promoteVerified(caps: Map<String, CapLevel>, capabilityId: String): Map<String, CapLevel> {
    val out = LinkedHashMap(caps)
    out[capabilityId] = CapLevel.VERIFIED
    return out
  }

  // M3 — diferenta fata de starea salvata. "Plecat" NU sterge fisa; o arhiveaza (vezi reconcile).
  fun diff(old: List<StaffMember>, fresh: List<StaffMember>): List<StaffEvent> {
    val oldByPkg = old.filter { !it.archived }.associateBy { it.packageName }
    val freshByPkg = fresh.associateBy { it.packageName }
    val events = mutableListOf<StaffEvent>()
    for ((pkg, f) in freshByPkg) {
      val o = oldByPkg[pkg]
      if (o == null) {
        events.add(StaffEvent(pkg, StaffEventKind.SOSIT, "", f.versionName))
      } else if (o.versionCode != f.versionCode || o.versionName != f.versionName) {
        events.add(StaffEvent(pkg, StaffEventKind.ACTUALIZAT, o.versionName, f.versionName))
      }
    }
    for ((pkg, o) in oldByPkg) {
      if (!freshByPkg.containsKey(pkg)) events.add(StaffEvent(pkg, StaffEventKind.PLECAT, o.versionName, ""))
    }
    return events
  }

  // Reconciliere: pastreaza fisele (caps, istoric, firstInstallTime) peste enumerarea proaspata,
  // arhiveaza plecatii, aplica M4 pe actualizati. Rezultatul e noua stare completa a registrului.
  fun reconcile(old: List<StaffMember>, fresh: List<StaffMember>): List<StaffMember> {
    val oldByPkg = old.associateBy { it.packageName }
    val out = mutableListOf<StaffMember>()
    for (f in fresh) {
      val o = oldByPkg[f.packageName]
      if (o == null) {
        out.add(f.copy(caps = initialCaps(f.packageName), versionHistory = listOf(f.versionName)))
        continue
      }
      val updated = o.versionCode != f.versionCode || o.versionName != f.versionName
      val caps = if (updated) demoteAfterUpdate(o.caps) else o.caps
      val history = if (updated && f.versionName != o.versionHistory.lastOrNull()) {
        (o.versionHistory + f.versionName).takeLast(MAX_VERSION_HISTORY)
      } else o.versionHistory.ifEmpty { listOf(f.versionName) }
      out.add(
        f.copy(
          firstInstallTime = if (o.firstInstallTime > 0) o.firstInstallTime else f.firstInstallTime,
          archived = false,
          caps = caps.ifEmpty { initialCaps(f.packageName) },
          versionHistory = history,
        )
      )
    }
    val freshPkgs = fresh.map { it.packageName }.toSet()
    for (o in old) {
      if (!freshPkgs.contains(o.packageName)) out.add(o.copy(archived = true))
    }
    return out
  }

  fun counts(list: List<StaffMember>): Triple<Int, Int, Int> {
    val active = list.filter { !it.archived }
    val known = active.count { m -> m.caps.isNotEmpty() }
    val verified = active.count { m -> m.caps.values.any { it == CapLevel.VERIFIED } }
    return Triple(active.size, known, verified)
  }

  // Garda de vizibilitate, scrisa din bug-ul dovedit pe 14.10.2026: pe un APK fara blocul
  // <queries> MAIN+LAUNCHER, enumerarea intoarce ~49 din ~850 de aplicatii reale. Un registru
  // construit pe o enumerare atat de mica ar arhiva sute de angajati existenti si ar raporta
  // capabilitati inexistente. Sub acest prag nu scriem nimic si reincercam la urmatoarea pornire.
  const val MIN_PLAUSIBLE_STAFF = 60

  fun isPlausibleEnumeration(freshCount: Int, previousActiveCount: Int): Boolean {
    if (freshCount >= MIN_PLAUSIBLE_STAFF) return true
    // Prima pornire pe un telefon curat chiar poate avea putine aplicatii: acceptam doar daca nu
    // contrazice o stare anterioara mai bogata.
    return previousActiveCount == 0
  }

  private fun esc(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

  fun serialize(list: List<StaffMember>): String = buildString {
    append("v1\n")
    for (m in list) {
      append(esc(m.packageName)).append('\t')
      append(esc(m.displayName)).append('\t')
      append(esc(m.category)).append('\t')
      append(if (m.launcher) "1" else "0").append('\t')
      append(m.firstInstallTime).append('\t')
      append(m.lastUpdateTime).append('\t')
      append(esc(m.versionName)).append('\t')
      append(m.versionCode).append('\t')
      append(if (m.archived) "1" else "0").append('\t')
      append(m.caps.entries.joinToString(",") { it.key + ":" + it.value.name }).append('\t')
      append(m.versionHistory.joinToString(",") { esc(it) })
      append('\n')
    }
  }

  fun deserialize(raw: String?): List<StaffMember> {
    if (raw.isNullOrBlank()) return emptyList()
    val lines = raw.split('\n').filter { it.isNotBlank() }
    if (lines.firstOrNull()?.trim() != "v1") return emptyList()
    val out = mutableListOf<StaffMember>()
    for (line in lines.drop(1)) {
      val p = line.split('\t')
      if (p.size < 11) continue
      val caps = linkedMapOf<String, CapLevel>()
      for (entry in p[9].split(',')) {
        if (entry.isBlank()) continue
        // FIX_AKA_CAP_KEY_COLON_1 (20.10.2026, CAUZA REALA gasita pe dispozitiv) — `split(':')`
        // presupunea exact DOUA bucati, valabil cat cheile erau simple ("call", "navigate"). AKA V1
        // introduce chei DINAMICE care contin ele insele ':' ("ui_control:5") — "ui_control:5:VERIFIED"
        // produce 3 bucati, kv.size!=2 arunca inregistrarea TACUT la fiecare reincarcare (dovedit:
        // scrierea reusea, dar un StaffRegistry.invalidate()+reload gol imediat dupa). Impartire pe
        // ULTIMUL ':' (safe: CapLevel.name nu contine niciodata ':'), nu pe primul.
        val sep = entry.lastIndexOf(':')
        if (sep <= 0) continue
        val key = entry.substring(0, sep)
        val value = entry.substring(sep + 1)
        val lvl = runCatching { CapLevel.valueOf(value) }.getOrNull() ?: continue
        caps[key] = lvl
      }
      out.add(
        StaffMember(
          packageName = p[0],
          displayName = p[1],
          category = p[2],
          launcher = p[3] == "1",
          firstInstallTime = p[4].toLongOrNull() ?: 0L,
          lastUpdateTime = p[5].toLongOrNull() ?: 0L,
          versionName = p[6],
          versionCode = p[7].toLongOrNull() ?: 0L,
          archived = p[8] == "1",
          caps = caps,
          versionHistory = p[10].split(',').filter { it.isNotBlank() },
        )
      )
    }
    return out
  }
}
