package expo.modules.accessibility

// P0 FIX V2 (21.10.2026, incident real: "Benson, finde eine Tankstelle in der Nähe" -> FAIL) —
// CAUZA REALA (nu presupusa): submitAndVerifySearch (BensonAccessibilityService.kt) avea un
// singur verdict binar `ok = changed && contentCount >= 3` — un ecran care s-a schimbat dar arata
// EXPLICIT zero rezultate cadea in ACELASI "ok=false" ca un ecran care nu s-a schimbat deloc (căutare
// nereușită mecanic). Modelul nu putea distinge "am cautat, nu exista nimic prin apropiere" de "nu
// am reusit sa caut" — exact contopirea pe care mandatul o interzice explicit.
//
// Patru verdicte distincte, pure, testabile — SurvivorDiagnosis-style (benson-foreground-service),
// dar specifice cautarii de locuri/continut, asa ca stau aici, langa InteractionVerification.kt.
enum class PoiSearchVerdict { SEARCH_SUCCESS_RESULTS, SEARCH_SUCCESS_EMPTY, SEARCH_UNVERIFIED, SEARCH_FAILED }

// `searchFieldFound`/`textEntered` sunt pasii MECANICI deja executati (sau nu) de runUniversalHandCommand
// inainte sa ajunga la submitAndVerifySearch — o lipsa aici e o dovada EXPLICITA de esec (camp
// inexistent sau textul n-a putut fi scris), nu doar incertitudine.
// `screenChangedAfterSubmit` + `contentElementCount` vin din screenSignature() de dupa submit.
fun classifyPoiSearch(
  searchFieldFound: Boolean,
  textEntered: Boolean,
  screenChangedAfterSubmit: Boolean,
  contentElementCount: Int,
): PoiSearchVerdict = when {
  !searchFieldFound || !textEntered -> PoiSearchVerdict.SEARCH_FAILED
  !screenChangedAfterSubmit -> PoiSearchVerdict.SEARCH_UNVERIFIED
  contentElementCount <= 0 -> PoiSearchVerdict.SEARCH_SUCCESS_EMPTY
  else -> PoiSearchVerdict.SEARCH_SUCCESS_RESULTS
}

// Pentru raportare catre Master (doctrina 9 — niciodata "eroare tehnica" pentru un rezultat gol):
// doar FAILED si UNVERIFIED justifica incercarea unei rute alternative; EMPTY e un succes de
// executie, fara locuri gasite — LIVE poate ALEGE sa incerce alta sursa (buget separat), dar nu
// pentru ca asta ar fi un "esec".
fun poiSearchJustifiesAlternativeRoute(verdict: PoiSearchVerdict): Boolean =
  verdict == PoiSearchVerdict.SEARCH_FAILED || verdict == PoiSearchVerdict.SEARCH_UNVERIFIED
