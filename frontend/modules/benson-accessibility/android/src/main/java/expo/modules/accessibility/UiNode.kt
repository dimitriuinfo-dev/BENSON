package expo.modules.accessibility

// RUNDA G-1 (2026-10-03) — abstract UI tree node. The same shape backs both a real
// AccessibilityNodeInfo snapshot (production, via ref) and a hand-built or uiautomator-dump-
// derived tree (JUnit) — ControlLocator only ever sees this, never the Android framework type.
// FAZA 3 / SCREEN OBSERVATION (14.10.2026) — campurile de mai jos sunt ADAUGATE, toate cu valoare
// implicita, exact ca sa nu schimbe niciun apelant existent: ControlLocator si testele G-1
// construiesc UiNode pe nume de parametru, deci ramân valide neatinse. Nimic din G-1 nu le
// citeste; le consuma doar ScreenDigest. Revert: sterge cele cinci linii marcate FAZA 3.
data class UiNode(
  val text: String = "",
  val desc: String = "",
  val viewId: String = "",
  val hint: String = "",
  val className: String = "",
  val clickable: Boolean = false,
  val editable: Boolean = false,
  val children: List<UiNode> = emptyList(),
  val ref: Any? = null,
  val bounds: String = "",            // FAZA 3 — "l,t,r,b" din AccessibilityNodeInfo.getBoundsInScreen
  val scrollable: Boolean = false,    // FAZA 3
  val enabled: Boolean = true,        // FAZA 3
  val focused: Boolean = false,       // FAZA 3
  val password: Boolean = false,      // FAZA 3 — isPassword(); textul nu pleaca NICIODATA mai departe
)
