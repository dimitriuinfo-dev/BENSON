package expo.modules.foregroundservice

// P0 FIX V2 (21.10.2026, "AUTONOMOUS RECOVERY & CONTINUOUS DIALOGUE") — buget PUR, testabil,
// pentru incercarea mai multor rute candidate la aceeasi misiune de gasire a unui loc (POI).
// Nu e FAST PATH (acela ramane neschimbat, strict VERIFIED) — e extinderea orchestrarii NORMALE
// LIVE: o ruta KNOWN/REVERIFY poate fi incercata (read-only, reversibila), dar niciodata marcata
// VERIFIED doar pentru ca a functionat o data aici.

data class RouteAttemptBudget(
  val distinctRoutesTried: Int = 0,
  val retriesOnCurrentRoute: Int = 0,
  val totalAttempts: Int = 0,
)

private const val MAX_DISTINCT_ROUTES = 3
private const val MAX_RETRIES_PER_ROUTE = 1
private const val MAX_TOTAL_ATTEMPTS = 6

// O ruta NOUA (alta aplicatie) poate fi incercata doar daca nu am epuizat nici numarul de rute
// distincte, nici bugetul total de incercari.
fun canTryNewRoute(budget: RouteAttemptBudget): Boolean =
  budget.distinctRoutesTried < MAX_DISTINCT_ROUTES && budget.totalAttempts < MAX_TOTAL_ATTEMPTS

// O REINCERCARE pe ACEEASI ruta e permisa STRICT cand exista un motiv OBSERVABIL (ex.
// SEARCH_UNVERIFIED — ecranul nu a raspuns, posibil intarziere, nu o incompatibilitate dovedita),
// niciodata orb, si niciodata peste 1 reincercare per ruta sau peste bugetul total.
fun canRetrySameRoute(budget: RouteAttemptBudget, observableReasonToRetry: Boolean): Boolean =
  observableReasonToRetry &&
    budget.retriesOnCurrentRoute < MAX_RETRIES_PER_ROUTE &&
    budget.totalAttempts < MAX_TOTAL_ATTEMPTS

// O ruta CONFIRMATA incompatibila (SEARCH_FAILED dupa ce campul de cautare chiar nu exista, sau
// domeniul semantic nu se potriveste deloc) nu se reincearca niciodata — doar urmatoarea ruta
// distincta, daca mai exista buget.
fun shouldAbandonRoutePermanently(verdict: expo.modules.accessibility.PoiSearchVerdict): Boolean =
  verdict == expo.modules.accessibility.PoiSearchVerdict.SEARCH_FAILED

fun nextBudgetAfterNewRoute(budget: RouteAttemptBudget): RouteAttemptBudget =
  budget.copy(distinctRoutesTried = budget.distinctRoutesTried + 1, retriesOnCurrentRoute = 0, totalAttempts = budget.totalAttempts + 1)

fun nextBudgetAfterRetry(budget: RouteAttemptBudget): RouteAttemptBudget =
  budget.copy(retriesOnCurrentRoute = budget.retriesOnCurrentRoute + 1, totalAttempts = budget.totalAttempts + 1)
