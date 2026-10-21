package expo.modules.foregroundservice

// FAST SYMBIOSIS (21.10.2026) — PRIMA ruta FAST concreta, pe o intentie simpla, independenta,
// deja demonstrabila pe dispozitiv (ordinea ceruta: "valideaza mai intai pe intentii simple").
// Pur: transforma o comanda romaneasca de calcul intr-o secventa de ETICHETE de buton REALE
// (vazute pe ecranul Calculator-ului OnePlus, "Rechner" — vezi dovada device 21.10.2026), fara sa
// decida ea insasi daca ruta e eligibila (asta e FastPathGate.kt, pe baza cunostintei AKA reale).

data class FastPathButtonStep(val label: String)

private val DIGIT_OR_OPERATOR_LABEL: Map<Char, String> = mapOf(
  '0' to "0", '1' to "1", '2' to "2", '3' to "3", '4' to "4",
  '5' to "5", '6' to "6", '7' to "7", '8' to "8", '9' to "9",
  '+' to "Hinzufügen",
  '-' to "Subtrahieren",
  '*' to "Multiplizieren", 'x' to "Multiplizieren", '×' to "Multiplizieren",
  '/' to "Teilen", '÷' to "Teilen",
  '=' to "Ist gleich",
)

// Doar comenzi explicite de calcul — "calculeaza X", "cat e X", "cat face X" — nimic altceva nu
// trece prin aceasta ruta (altfel ar risca sa inghita comenzi nelegate de calculator).
private val CALC_COMMAND_PATTERN = Regex(
  """^(?:calculeaz[ăa]|c[âa]t (?:e|face|fac))\s+(.+?)\s*[?.!]*$""", RegexOption.IGNORE_CASE
)

// Extrage expresia aritmetica simpla (doar cifre + operatorii cunoscuti) dintr-o comanda. Null =
// nu e deloc o cerere de calcul SIMPLU, sau contine ceva neinteles (paranteze, radical, procent) —
// cade pe calea veche (LIVE/OpenAI), niciodata ghicit.
fun parseArithmeticExpression(command: String): String? {
  val m = CALC_COMMAND_PATTERN.find(command.trim()) ?: return null
  var expr = m.groupValues[1].lowercase()
    .replace("ori", "*")
    .replace("plus", "+")
    .replace("minus", "-")
    .replace("împărțit la", "/")
    .replace("impartit la", "/")
    .replace(Regex("\\s+"), "")
  if (expr.isBlank()) return null
  if (!Regex("^[0-9+\\-*x×/÷=]+$").matches(expr)) return null
  if (expr.none { it in "0123456789" }) return null
  if (expr.startsWith("=") || expr.endsWith("+") || expr.endsWith("-") || expr.endsWith("*") || expr.endsWith("/")) return null
  return if (expr.endsWith("=")) expr else expr + "="
}

// Extrage DOAR valoarea numerica afisata pe ecran (dovada device 21.10.2026: dupa "Ist gleich",
// digest-ul contine o linie `[5] text "18"`) — nu intregul digest brut. Prima valoare numerica
// gasita intr-un element "text"/"input"/"list"; null = n-a putut citi niciun rezultat clar (NU
// se declara niciun numar ghicit — doctrina 9).
private val DISPLAY_ELEMENT_PATTERN = Regex("""\[\d+]\s+(?:text|input|list)\s+"([0-9][0-9.,\-]*)"""")

fun extractCalculatorDisplayValue(renderedDigest: String): String? =
  DISPLAY_ELEMENT_PATTERN.find(renderedDigest)?.groupValues?.get(1)

// Secventa de etichete reale de buton — mereu precedata de "löschen" (sterge), ca o expresie
// veche ramasa pe ecran sa nu se adauge la cea noua. Null = un caracter necunoscut (nu ar trebui
// sa se intample dupa parseArithmeticExpression, dar functia ramane sigura independent).
fun arithmeticExpressionToButtonSteps(expr: String): List<FastPathButtonStep>? {
  val steps = mutableListOf(FastPathButtonStep("löschen"))
  for (c in expr) {
    val label = DIGIT_OR_OPERATOR_LABEL[c] ?: return null
    steps.add(FastPathButtonStep(label))
  }
  return steps
}
