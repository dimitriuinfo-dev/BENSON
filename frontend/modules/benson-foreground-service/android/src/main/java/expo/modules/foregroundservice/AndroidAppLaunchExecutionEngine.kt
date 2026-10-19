package expo.modules.foregroundservice

import android.content.Context
import android.content.Intent

// BENSON NEXT — primul EXECUTOR Android REAL (19.10.2026). Actiune nepericuloasa: deschide o
// aplicatie deja cunoscuta/dovedita in LEGACY (Calculator, CAP_CALCULATE, DEVICE_PASS_CALC1_...
// 22.09.2026, 9/9). Verificarea e REALA, nu doar "startActivity n-a aruncat": foloseste
// BensonAccessibilityService.instance (modulul benson-accessibility, deja existent, deja
// dependinta a acestui modul — vezi build.gradle, "implementation project(\":benson-accessibility\")")
// — NU un AccessibilityService nou, NU un al doilea executor paralel cu cel din LEGACY.
// SURVIVOR decide rezultatul (classifyLaunchVerification, acelasi folosit si la navigatie —
// un singur Verification Engine, mai multe domenii).
//
// Context-dependent -> fara test JUnit (acelasi motiv/conventie ca StaffRegistry.kt/
// PersonalMemoryStorage.kt in aceasta sesiune). NETESTAT PE TELEFON — doar compilat.
//
// Threading: execute() e SINCRON si blocheaza firul apelant cat polleaza (acelasi comportament ca
// un pas dintr-un lant deja existent) — la integrarea reala in BensonForegroundService.kt trebuie
// chemat de pe un fir dedicat (Thread(...).start(), tiparul deja folosit peste tot in acel fisier),
// NICIODATA de pe thread-ul principal.
internal class AndroidAppLaunchExecutionEngine(
  private val context: Context,
  private val maxPolls: Int = 5,
  private val pollDelayMs: Long = 300L,
) : ExecutionEngine {

  override fun execute(request: ExecutionRequest): ExecutionOutcome {
    if (request.action != "CALCULATE") return ExecutionOutcome.Failed("unsupported_action:${request.action}")
    val pkg = request.params["packageName"] ?: "com.oneplus.calculator"

    val launched = try {
      val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        ?: return ExecutionOutcome.Failed("package_not_launchable:$pkg")
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (_: Exception) {
      false
    }
    if (!launched) return ExecutionOutcome.Failed("launch_threw_exception")

    var attempt = 0
    while (true) {
      Thread.sleep(pollDelayMs)
      val observed = try {
        expo.modules.accessibility.BensonAccessibilityService.instance?.currentForegroundPackageLive()
      } catch (_: Exception) {
        null
      }
      val outcome = classifyLaunchVerification(setOf(pkg), observed, attempt, maxPolls)
      if (outcome !is ExecutionOutcome.Unverified) return outcome
      attempt += 1
    }
  }
}
