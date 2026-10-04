package expo.modules.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

// RUNDA CAR-3a (2026-10-04, user-directed) — "Aprobat: dependență nouă androidx.car.app... uz
// personal, Android Auto dezvoltator." ALLOW_ALL_HOSTS_VALIDATOR is documented by Google as
// "intended to be used only during development" — exactly this case, never a published app.
class BensonCarAppService : CarAppService() {
  override fun onCreateSession(): Session = BensonCarSession()

  override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
}
