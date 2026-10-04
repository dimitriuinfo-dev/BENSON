package expo.modules.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

// RUNDA CAR-3a (2026-10-04, user-directed) — "fără nimic din aplicație în afara stării și a
// dialogului: fără liste, fără setări, fără plăți." One screen, one template (PaneTemplate):
// current state + last "Tu:"/"BENSON:" exchange, Acasă/Înapoi actions. BensonForegroundService
// pushes updates via the companion `instance`, Kotlin-to-Kotlin (no JS, no Intent round-trip) —
// same loose-singleton pattern as BensonAccessibilityService.instance.
class BensonCarScreen(carContext: CarContext) : Screen(carContext) {
  companion object {
    @Volatile var instance: BensonCarScreen? = null
  }

  @Volatile private var state: CarScreenState = CarScreenState.IDLE
  @Volatile private var lastUser: String = ""
  @Volatile private var lastBenson: String = ""

  init {
    instance = this
    lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onDestroy(owner: LifecycleOwner) {
        if (instance === this@BensonCarScreen) instance = null
      }
    })
  }

  private fun stateLabel(s: CarScreenState): String = when (s) {
    CarScreenState.IDLE -> "Benson"
    CarScreenState.LISTENING -> "Ascult..."
    CarScreenState.THINKING, CarScreenState.EXECUTING -> "..."
    CarScreenState.DONE -> "Gata"
    CarScreenState.ERROR -> "Eroare"
  }

  /** Called from BensonForegroundService (Kotlin-to-Kotlin) on every relevant state change. */
  fun updateState(newState: CarScreenState, newLastUser: String? = null, newLastBenson: String? = null) {
    state = newState
    if (newLastUser != null) lastUser = newLastUser
    if (newLastBenson != null) lastBenson = newLastBenson
    Log.i("BENSON_AUDIO", "CAR_SCREEN state=${newState.name.lowercase()}")
    invalidate()
  }

  fun setIdle() = updateState(CarScreenState.IDLE)

  override fun onGetTemplate(): Template {
    val rows = mutableListOf<Row>()
    if (lastUser.isNotBlank()) rows.add(Row.Builder().setTitle("Tu: $lastUser").build())
    if (lastBenson.isNotBlank()) rows.add(Row.Builder().setTitle("BENSON: $lastBenson").build())
    if (rows.isEmpty()) rows.add(Row.Builder().setTitle("Spune \"Benson\" ca să începi.").build())

    val pane = Pane.Builder().apply { rows.forEach { addRow(it) } }.build()

    val header = Header.Builder()
      .setStartHeaderAction(Action.APP_ICON)
      .setTitle(stateLabel(state))
      .build()

    val homeAction = Action.Builder()
      .setTitle("Acasă")
      .setOnClickListener {
        expo.modules.accessibility.BensonAccessibilityService.instance?.performGlobalNav("home")
      }
      .build()

    val backAction = Action.Builder()
      .setTitle("Înapoi")
      .setOnClickListener {
        expo.modules.accessibility.BensonAccessibilityService.instance?.performGlobalNav("back")
      }
      .build()

    // PaneTemplate.Builder has no addAction — verified via javap against the real 1.7.0 AAR
    // (modules/benson-car/android/build.gradle). Actions go through an ActionStrip instead.
    val actionStrip = ActionStrip.Builder()
      .addAction(homeAction)
      .addAction(backAction)
      .build()

    return PaneTemplate.Builder(pane)
      .setHeader(header)
      .setActionStrip(actionStrip)
      .build()
  }
}
