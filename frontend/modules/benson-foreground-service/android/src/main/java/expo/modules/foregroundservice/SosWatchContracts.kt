package expo.modules.foregroundservice

// BENSON SOS WATCH — contractele pure (19.10.2026). SOS WATCH NU e un chatbot si NU e un
// orchestrator — e exclusiv o interfata de DECLANSARE a EmergencyProtocol-ului existent (deja
// definit, neimplementat, in BACKLOG.md). BENSON LIVE ramane unicul Mission Owner.
//
// Acest fisier NU creeaza un modul Wear OS (proiect Gradle separat, dependente Play Services
// Wearable, manifest de tile/complicatie) — asta e o schimbare structurala reala (modul nou,
// dependente noi, posibil fara retea pentru rezolvare Gradle in acest mediu) si ramane un pas
// separat, explicit, dupa ce stratul de contract de mai jos e aprobat. Aici doar FORMA datelor si
// LOGICA de autentificare/anti-duplicare - pura, testabila fara ceas, fara telefon, fara retea.

enum class SosTriggerSource { WATCH_BUTTON, WATCH_TILE, BLE_BUTTON }

data class SosTrigger(
  val triggerId: String,          // unic per activare - vezi isDuplicateTrigger()
  val source: SosTriggerSource,
  val deviceId: String,           // dispozitivul asociat care a emis declansarea
  val emittedAtMs: Long,
  val heldDurationMs: Long,       // cat a durat apasarea lunga - 0 pentru tile/BLE instant
)

// Trei evenimente distincte, cerute explicit (19.10.2026) - niciodata confundate intre ele:
//   WATCH_EVENT_RECEIVED - telefonul a primit evenimentul de la ceas (DataLayer/MessageClient a
//     livrat ceva) - nu spune NIMIC despre ce s-a intamplat cu alerta SOS insasi.
//   SOS_ALERT_DELIVERED - alerta a fost livrata printr-un canal VERIFICABIL (ex. apelul video
//     WhatsApp a pornit efectiv, SURVIVOR a confirmat - nu doar "s-a trimis un intent").
//   SOS_ALERT_UNKNOWN - rezultatul alertarii nu poate fi confirmat (canal indisponibil, timeout de
//     verificare, SURVIVOR nu a primit dovada) - NICIODATA tratat ca succes implicit.
enum class SosWatchEventStage { WATCH_EVENT_RECEIVED, SOS_ALERT_DELIVERED, SOS_ALERT_UNKNOWN }

// Pastrat pentru compatibilitate cu round-ul anterior (PHONE_UNAVAILABLE e un caz specific de
// SOS_ALERT_UNKNOWN - vezi toStage()); nu se sterge, se mapeaza.
enum class SosDeliveryStatus { DELIVERED, UNCONFIRMED, PHONE_UNAVAILABLE }

fun SosDeliveryStatus.toStage(): SosWatchEventStage = when (this) {
  SosDeliveryStatus.DELIVERED -> SosWatchEventStage.SOS_ALERT_DELIVERED
  SosDeliveryStatus.UNCONFIRMED, SosDeliveryStatus.PHONE_UNAVAILABLE -> SosWatchEventStage.SOS_ALERT_UNKNOWN
}

data class SosDeliveryResult(
  val triggerId: String,
  val status: SosDeliveryStatus,
  val stage: SosWatchEventStage,
  val hapticConfirmed: Boolean,   // true DOAR daca telefonul a confirmat primirea - vezi mai jos
)

// Autentificare + anti-duplicare: un SosTrigger e acceptat DOAR de la un dispozitiv deja asociat
// (deviceId cunoscut) si DOAR o singura data per triggerId. Nu e o lista noua de "aplicatii" -
// e o lista mica, locala, de dispozitive companion autorizate explicit de utilizator.
class SosWatchTriggerAuthority(private val authorizedDeviceIds: Set<String>) {
  private val seenTriggerIds = mutableSetOf<String>()

  fun isAuthorizedDevice(deviceId: String): Boolean = deviceId in authorizedDeviceIds

  // Prima conditie de respins: dispozitiv necunoscut. A doua: triggerId deja vazut (reluare de
  // retea, dublura de MessageClient, etc.) - NU se re-proceseaza, dar nici nu se trateaza ca eroare.
  fun accept(trigger: SosTrigger): Boolean {
    if (!isAuthorizedDevice(trigger.deviceId)) return false
    if (trigger.triggerId in seenTriggerIds) return false
    seenTriggerIds += trigger.triggerId
    return true
  }
}

// "Nu declara o alerta trimisa doar pentru ca ceasul a transmis evenimentul" — rezultatul de
// livrare se construieste DOAR dupa o dovada reala (vezi EmergencyProtocol/SURVIVOR), niciodata
// din simpla primire a SosTrigger-ului. hapticConfirmed=true cere status=DELIVERED explicit.
fun buildDeliveryResult(triggerId: String, status: SosDeliveryStatus): SosDeliveryResult =
  SosDeliveryResult(triggerId, status, stage = status.toStage(), hapticConfirmed = status == SosDeliveryStatus.DELIVERED)
