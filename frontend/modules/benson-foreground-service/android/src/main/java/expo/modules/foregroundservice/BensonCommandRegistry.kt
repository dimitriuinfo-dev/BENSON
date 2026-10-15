package expo.modules.foregroundservice

// BENSON ISI CUNOASTE PROPRIILE COMENZI (15.10.2026, ordin direct al Masterului).
//
// NU e un al doilea registru: e o VEDERE peste aceleasi id-uri de capabilitate din
// StaffRegistryCore (CAP_*), exact ca APP_INDEX care e o vedere peste registrul personalului
// (LEGEA PERSONALULUI, punctul 1). Legatura e la COMPILARE — daca un id de capabilitate se
// redenumeste in StaffRegistryCore, acest fisier nu mai compileaza, deci documentatia nu poate
// rămâne in urma implementarii (punctul 6 din ordin). Testul JUnit cere acoperire completa.
//
// `executor` e numele functiei reale care face treaba; `state` spune adevarul despre ea:
//   VALIDAT      = dovedit pe telefon (CLAUDE.md „Comportamente dovedite" / G3)
//   IMPLEMENTAT  = codul exista si merge in teste, dar nu e dovedit 5/5 pe telefon
//   PLANIFICAT   = contract definit, cod inexistent — BENSON spune explicit ca nu stie inca
//   INDISPONIBIL = blocat de Android, de permisiuni sau de aplicatia terta
//
// Limba comenzilor: BENSON ascultă in ROMANA (tiparele native sunt romanesti). Descrierile sunt
// in ro/de/en ca sa poata EXPLICA in trei limbi — nu ca sa pretinda ca accepta comenzi in germana.
internal enum class CmdState { VALIDAT, IMPLEMENTAT, PLANIFICAT, INDISPONIBIL }

internal data class BensonCommand(
  val action: String,
  val capId: String?,
  val descRo: String,
  val descDe: String,
  val descEn: String,
  val phrasings: List<String>,
  val executor: String,
  val requires: List<String>,
  val state: CmdState,
  val note: String = "",
)

internal object BensonCommandRegistry {
  const val SPOKEN_LANGUAGE = "ro"

  val ALL: List<BensonCommand> = listOf(
    BensonCommand(
      "APP_LAUNCH", null,
      "Deschid orice aplicatie instalata, dupa nume sau dupa rol.",
      "Ich öffne jede installierte App, nach Name oder Rolle.",
      "I open any installed app, by name or by role.",
      listOf("deschide WhatsApp", "deschide calculatorul", "pornește Waze"),
      "tryNativeAppLaunch", listOf("permisiune de accesibilitate"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "APP_CATEGORY_SEARCH", null,
      "Caut aplicatia potrivita pentru un scop si, daca sunt mai multe, ti le enumar.",
      "Ich suche die passende App für einen Zweck und zähle Alternativen auf.",
      "I find the right app for a purpose and list the alternatives.",
      listOf("caută aplicația de parcare", "ai ceva pentru radio?"),
      "tryNativeAppCategorySearch", listOf(), CmdState.VALIDAT,
    ),
    BensonCommand(
      "MEDIA_SEARCH", StaffRegistryCore.CAP_MEDIA_SEARCH,
      "Caut ceva pe YouTube si il pornesc dupa confirmarea ta.",
      "Ich suche auf YouTube und starte nach deiner Bestätigung.",
      "I search YouTube and start it after your confirmation.",
      listOf("caută Madonna pe YouTube", "pune-mi un film cu ..."),
      "tryNativeYoutubeOpenSearch", listOf("YouTube instalat"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "MEDIA_CONTROL", StaffRegistryCore.CAP_MEDIA_CONTROL,
      "Controlez redarea: pauza, continuare, piesa urmatoare, inapoi.",
      "Ich steuere die Wiedergabe: Pause, weiter, nächster Titel, zurück.",
      "I control playback: pause, resume, next, previous.",
      listOf("oprește muzica", "oprește videoclipul", "pauză", "mai departe", "dă-i drumul"),
      "tryNativeMediaControl", listOf("o aplicatie care reda ceva"), CmdState.VALIDAT,
      "«oprește» SINGUR inseamna oprirea actiunii mele (UNIVERSAL_ACTION_STOP); cu obiect " +
        "(«oprește muzica») inseamna oprirea redarii.",
    ),
    BensonCommand(
      "MUSIC_PLAY", StaffRegistryCore.CAP_MEDIA_SEARCH,
      "Pornesc o piesa anume in Spotify.",
      "Ich starte einen bestimmten Titel in Spotify.",
      "I start a specific track in Spotify.",
      listOf("pune Justify My Love", "pune Madonna pe Spotify"),
      "tryNativeMusicPlay", listOf("Spotify instalat si autentificat"), CmdState.IMPLEMENTAT,
      "Prototip: artistul iese corect, dar uneori porneste alta piesa din colectie.",
    ),
    BensonCommand(
      "MESSAGE", StaffRegistryCore.CAP_MESSAGE,
      "Scriu un mesaj pe WhatsApp. Il vezi scris pe ecran si il trimit numai dupa «da».",
      "Ich schreibe eine WhatsApp-Nachricht und sende sie erst nach deinem «Ja».",
      "I write a WhatsApp message and send it only after your explicit yes.",
      listOf("scrie-i lui Hannah că ajung târziu", "trimite un mesaj lui ..."),
      "tryNativeWhatsApp", listOf("WhatsApp instalat", "confirmare rostita"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "CALL", StaffRegistryCore.CAP_CALL,
      "Sun pe cineva pe WhatsApp, dupa confirmarea ta.",
      "Ich rufe jemanden über WhatsApp an, nach deiner Bestätigung.",
      "I call someone on WhatsApp, after your confirmation.",
      listOf("sună la Hannah pe WhatsApp", "sun-o pe mama"),
      "tryNativeWhatsApp", listOf("WhatsApp instalat", "confirmare rostita"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "VIDEO_CALL", StaffRegistryCore.CAP_VIDEO_CALL,
      "Pornesc un apel video pe WhatsApp, dupa confirmarea ta.",
      "Ich starte einen WhatsApp-Videoanruf, nach deiner Bestätigung.",
      "I start a WhatsApp video call, after your confirmation.",
      listOf("video cu Hannah", "apel video cu ..."),
      "tryNativeWhatsApp", listOf("WhatsApp instalat", "confirmare rostita"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "NAVIGATE", StaffRegistryCore.CAP_NAVIGATE,
      "Te duc la o destinatie cu Waze sau Google Maps.",
      "Ich navigiere dich mit Waze oder Google Maps.",
      "I navigate you there with Waze or Google Maps.",
      listOf("du-mă la aeroport", "navighează la Marienplatz"),
      "tryNativeNavigate", listOf("Waze sau Maps instalat"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "WEB_SEARCH", StaffRegistryCore.CAP_WEB_SEARCH,
      "Caut pe internet si deschid rezultatul.",
      "Ich suche im Internet und öffne das Ergebnis.",
      "I search the web and open the result.",
      listOf("caută farmacie deschisă", "caută pe internet cât costă ..."),
      "tryExplicitWebSearch", listOf("conexiune la internet"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "SEARCH_PRODUCTS", StaffRegistryCore.CAP_SEARCH_PRODUCTS,
      "Caut un produs intr-un magazin si iti citesc primele rezultate. Nu cumpar nimic.",
      "Ich suche ein Produkt im Shop und lese die Ergebnisse vor. Ich kaufe nichts.",
      "I search a product in the shop and read the results. I never buy.",
      listOf("caută un robot de ferestre pe Amazon"),
      "agent search_in_app", listOf("aplicatia magazinului instalata"), CmdState.IMPLEMENTAT,
      "Nu pregatesc si nu finalizez nicio plata — doctrina produsului.",
    ),
    BensonCommand(
      "CALCULATE", StaffRegistryCore.CAP_CALCULATE,
      "Calculez si iti spun rezultatul.",
      "Ich rechne und sage dir das Ergebnis.",
      "I calculate and tell you the result.",
      listOf("cât fac 9 ori 6", "cât e rădăcina din 144"),
      "CalculatorRecipe", listOf(), CmdState.VALIDAT,
    ),
    BensonCommand(
      "GLOBAL_NAV", null,
      "Te scot din ecranul curent sau te duc acasa.",
      "Ich gehe zurück oder zum Startbildschirm.",
      "I go back or to the home screen.",
      listOf("înapoi", "acasă", "închide", "ieși"),
      "tryGlobalNav", listOf("permisiune de accesibilitate"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "CONVERSATION", null,
      "Discutam normal: intrebari, explicatii, comparatii, sfaturi. Nu ating telefonul.",
      "Wir unterhalten uns normal: Fragen, Erklärungen, Vergleiche, Ratschläge.",
      "We just talk: questions, explanations, comparisons, advice.",
      listOf("ce părere ai despre ...", "explică-mi de ce ...", "pe scurt, ce e ..."),
      "agent say", listOf("conexiune la internet"), CmdState.VALIDAT,
    ),
    BensonCommand(
      "UNIVERSAL_ACTION_STOP", null,
      "Opresc imediat ce fac: vorbitul, gandirea, apasarile pe ecran si redarea. Nu raspund nimic.",
      "Ich stoppe sofort alles, was ich tue — ohne Antwort.",
      "I immediately stop whatever I am doing, without answering.",
      listOf("taci", "stop", "oprește", "mute"),
      "interruptAll", listOf(), CmdState.IMPLEMENTAT,
      "Rostite SINGURE. Nu inchid o convorbire conectata si nu anulez un SOS activ. " +
        "Testat cu comenzi injectate (G3 14/14); barge-in-ul rostit cat vorbesc nu e inca " +
        "confirmat de Master pe telefon.",
    ),
    BensonCommand(
      "END_CALL", null,
      "Inchiderea unei convorbiri deja conectate.",
      "Ein bereits verbundenes Gespräch beenden.",
      "Hanging up a call that is already connected.",
      listOf("închide apelul", "termină apelul"),
      "—", listOf("de stabilit: TelecomManager sau butonul din aplicatia de apel"),
      CmdState.PLANIFICAT,
      "Inca nu stiu sa fac asta. Contract: docs/specs/POLITICA_OPRIRE_APEL_SOS_2026-10-15.md. " +
        "«Benson, închide» va insemna END_CALL doar cand exista o convorbire activa; altfel " +
        "rămâne ieșirea din ecran.",
    ),
    BensonCommand(
      "CANCEL_SOS", null,
      "Anularea unei proceduri SOS active.",
      "Abbruch eines aktiven SOS-Vorgangs.",
      "Cancelling an active SOS procedure.",
      listOf("anulează SOS", "anulează alarma"),
      "—", listOf("confirmare explicita, cu expirare"), CmdState.PLANIFICAT,
      "SOS nu exista inca. «stop» sau «taci» NU anuleaza niciodata un SOS.",
    ),
  )

  private fun norm(s: String): String = TextNormalization.stripSymbolsAndEmoji(s.lowercase())
    .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
    .replace(Regex("\\p{Mn}+"), "")

  /** Potrivire pe intrebarea Masterului: actiune, descriere sau formulare. Gol = tot registrul. */
  fun find(query: String): List<BensonCommand> {
    val q = norm(query).trim()
    if (q.isEmpty()) return ALL
    val words = q.split(Regex("\\s+")).filter { it.length >= 4 }
    if (words.isEmpty()) return ALL
    return ALL.filter { c ->
      val hay = norm(c.action + " " + c.descRo + " " + c.phrasings.joinToString(" ") + " " + c.note)
      words.any { hay.contains(it) }
    }.ifEmpty { ALL }
  }

  fun describe(c: BensonCommand): String = buildString {
    append(c.action).append(" [").append(c.state.name).append("] ")
    append(c.descRo)
    append(" | spui: ").append(c.phrasings.joinToString(" / "))
    if (c.requires.isNotEmpty()) append(" | cere: ").append(c.requires.joinToString(", "))
    if (c.note.isNotEmpty()) append(" | ").append(c.note)
  }

  /** Raspunsul instrumentului: date din cod (canal SYSTEM), nu din ecran sau din web. */
  fun explain(query: String): String {
    val hits = find(query)
    val head = "Comenzile BENSON (limba comenzilor: $SPOKEN_LANGUAGE; explic in ro/de/en). " +
      "PLANIFICAT = nu stiu inca, spune-o explicit Masterului.\n"
    return head + hits.joinToString("\n") { "- " + describe(it) }
  }
}
