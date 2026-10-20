package expo.modules.foregroundservice

// BENSON NEXT — consolidare (19.10.2026): FIX_AGENT_OPEN_APP_HONESTY_1. BUG REAL gasit in
// `runAgentToolLocked` (BensonForegroundService.kt), ramura OPEN_APP: `BensonAgentTools.toolResult
// (resp != null, resp ?: ...)` trata orice raspuns NENUL de la `tryNativeOpenApp` ca succes —
// inclusiv textele proprii de ESEC ale acelei functii ("Am găsit X, dar n-am putut să o deschid.",
// "Nu pornesc video cât conduci."). Agentul LLM putea deci raporta "gata" exact cand lansarea
// esuase sau fusese refuzata — incalcare directa a doctrinei 9 (succesul se declara abia dupa
// verificare), pe autoritatea deja existenta (tryAgent), nu intr-un orchestrator nou.
//
// Clasificare pura, reutilizand EXACT tiparul pe care tryNativeOpenApp il produce la succes
// ("Deschid $label.") — niciun text nou, nicio presupunere, doar citirea corecta a ce functia
// deja intoarce.
fun classifyOpenAppResponseText(response: String?): Boolean =
  response != null && response.startsWith("Deschid ")
