package expo.modules.foregroundservice

import java.util.Locale

// BENSON NEXT — anuntul rutei/traficului, strict legat de ce RouteInfo chiar contine. Reutilizeaza
// RouteInfo/RouteDataSource/isHonestAboutTraffic()/honestRouteOrNull() din D0 (corectia 9) — nu
// redefineste sursa de adevar. Sursa reala de date (Waze/Directions live) ramane neconectata aici
// (cere retea + autorizare separata); aceasta e doar logica pura ce decide CE se spune/noteaza
// cand o ruta sosește, fara sa inventeze niciodata trafic live sau un ETA care nu exista.

// Text vorbit, pentru raportul catre Master. Null = nimic onest de spus (ruta lipseste sau
// pretinde trafic live fara sursa live) — apelantul nu trebuie sa produca o fraza alternativa.
fun announceRoute(route: RouteInfo?): String? {
  val honest = honestRouteOrNull(route) ?: return null
  val distancePart = if (honest.distanceMeters >= 1000) {
    String.format(Locale.ROOT, "%.1f km", honest.distanceMeters / 1000.0)
  } else {
    "${honest.distanceMeters} m"
  }
  val etaPart = honest.etaMinutes?.let { ", aproximativ $it minute" } ?: ""
  val trafficPart = if (honest.trafficAware) " (cu trafic live)" else ""
  return "$distancePart$etaPart$trafficPart"
}

// Evidenta de jurnal/executie (acelasi stil ca ExecutionOutcome.Success.evidence existent, ex.
// numele pachetului) — null = nu se ataseaza nimic (consistent cu restul executorului, care nu
// ataseaza niciodata evidenta inventata).
fun formatRouteEvidence(route: RouteInfo?): String? {
  val honest = honestRouteOrNull(route) ?: return null
  val etaSuffix = honest.etaMinutes?.let { ";etaMinutes=$it;etaSource=${honest.etaSource}" } ?: ""
  return "distanceMeters=${honest.distanceMeters};trafficAware=${honest.trafficAware}$etaSuffix"
}
