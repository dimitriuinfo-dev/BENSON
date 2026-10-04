package expo.modules.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

// RUNDA CAR-3a — one Screen for the whole session (no navigation stack needed for a single
// state+dialog display).
class BensonCarSession : Session() {
  override fun onCreateScreen(intent: Intent): Screen = BensonCarScreen(carContext)
}
