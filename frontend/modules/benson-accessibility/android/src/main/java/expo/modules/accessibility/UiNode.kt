package expo.modules.accessibility

// RUNDA G-1 (2026-10-03) — abstract UI tree node. The same shape backs both a real
// AccessibilityNodeInfo snapshot (production, via ref) and a hand-built or uiautomator-dump-
// derived tree (JUnit) — ControlLocator only ever sees this, never the Android framework type.
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
)
