package expo.modules.foregroundservice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// WORKSTREAM B — persistenta reala, CRIPTATA (ETAPA 2, 19.10.2026, decizie aprobata: Android
// Keystore, AES-256-GCM, zero dependinta noua — nici androidx.security.crypto). Interfata publica
// (save/load) NESCHIMBATA fata de versiunea anterioara — doar implementarea devine criptata.
//
// Cheia AES-256 traieste EXCLUSIV in AndroidKeyStore (hardware/TEE pe telefoanele care il au);
// nu paraseste niciodata procesul, nu e scrisa in niciun fisier, nu e loggata. Fiecare criptare
// genereaza un IV nou (cipher.iv dupa init(ENCRYPT_MODE, cheie) — providerul il genereaza sigur,
// nu e ales de noi). GCM include tagul de autentificare in ciphertext-ul intors de doFinal().
//
// Compatibilitate: KeyProperties.BLOCK_MODE_GCM e disponibil din API 23; minSdkVersion al
// proiectului e 24 (build.gradle) — compatibil pe toate tintele actuale, fara cod conditional.
//
// LIMITA HONESTA (ceruta explicit de etapa 2): un JUnit simplu (JVM, fara emulator) NU poate
// instantia Keystore-ul real al Android — "AndroidKeyStore" nu exista ca provider JCE in afara
// unui dispozitiv/emulator real. Acest fisier ramane, deliberat, NETESTAT JUnit — exact aceeasi
// conventie deja stabilita in proiect pentru partea Context-dependenta (StaffRegistry.kt nu are
// nici el fisier de test; regulile pure stau in *Core.kt si ACELEA sunt testate). Verificarea
// reala a round-trip-ului, coruperii, esecului de autentificare si IV-urilor distincte cere un
// test instrumentat Android (androidTest, pe dispozitiv/emulator) sau o rulare reala pe telefon —
// niciuna posibila din acest mediu (fara adb, fara emulator).
//
// NU se expune stocarea criptata catre backup/restore incompatibil: nu am putut lega asta la
// nivel de manifest Android fara sa ating fie android/ (prebuild-generat, interzis explicit in
// CLAUDE.md — "Nu rula expo prebuild"), fie sa creez un conflict de merge cu atributele
// fullBackupContent/dataExtractionRules deja declarate de app (@xml/secure_store_backup_rules,
// vezi android/app/src/main/AndroidManifest.xml linia 48 — presupus generate de plugin-ul
// expo-secure-store la prebuild, nu fisiere statice editabile aici). Proprietate de siguranta care
// RAMANE adevarata chiar si asa: o cheie AndroidKeyStore nu e NICIODATA inclusa in backup-ul
// Android (e legata de hardware/TEE-ul dispozitivului, nu de datele aplicatiei) — deci un restore
// pe alt dispozitiv gaseste ciphertext fara cheie, decriptarea esueaza in siguranta (load()
// intoarce lista goala, exact calea de coruptie de mai jos), nu scurge nimic. Excluderea explicita
// de manifest ramane un gol cunoscut, nu inchis, pana la o decizie pe prebuild.
internal object PersonalMemoryStorage {
  private const val PREFS = "benson_personal_memory"
  private const val KEY_ENVELOPE = "places_envelope_v1"
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val KEY_ALIAS = "benson_personal_memory_key_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private const val FORMAT_VERSION = 1

  private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // Cheie indisponibila (Keystore lipsa/coruput pe acest device, provider absent) -> null,
  // niciodata o exceptie nereceptionata si niciodata o cheie hardcodata de rezerva.
  //
  // FIX (control tehnic 19.10.2026) — defect real gasit: versiunea anterioara facea
  // `getKey(ALIAS) ?: generateKey()`, deci daca alias-ul EXISTA dar getKey() intorcea null dintr-un
  // motiv diferit de "nu exista" (entry corupta, tip gresit, eroare tranzitorie de provider),
  // codul REGENERA o cheie noua peste alias-ul vechi — orfanizand PERMANENT orice date deja
  // criptate cu cheia anterioara (noua cheie nu le poate decripta niciodata). Acum se verifica
  // explicit containsAlias(): alias absent -> generare (sigur, prima utilizare); alias prezent dar
  // neciteibil -> null (esec explicit), NICIODATA regenerare peste o cheie existenta.
  private fun getOrCreateKey(): SecretKey? = try {
    val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    if (ks.containsAlias(KEY_ALIAS)) {
      ks.getKey(KEY_ALIAS, null) as? SecretKey
    } else {
      val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
      generator.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build(),
      )
      generator.generateKey()
    }
  } catch (_: GeneralSecurityException) {
    null
  } catch (_: Exception) {
    null
  }

  // Nicio inregistrare noua nu poate fi scrisa necriptat: daca cheia nu e disponibila, save()
  // refuza explicit (false) in loc sa scrie JSON in clar.
  fun save(ctx: Context, places: List<SavedPlace>): Boolean {
    val key = getOrCreateKey() ?: run {
      AudioDiag.log(ctx, "MEMORY_ENCRYPT_FAILED", "reason=key_unavailable")
      return false
    }
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val iv = cipher.iv
      val ciphertext = cipher.doFinal(serialize(places).toByteArray(Charsets.UTF_8))
      val envelope = JSONObject()
        .put("v", FORMAT_VERSION)
        .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        .put("ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
      prefs(ctx).edit().putString(KEY_ENVELOPE, envelope.toString()).apply()
      true
    } catch (e: GeneralSecurityException) {
      AudioDiag.log(ctx, "MEMORY_ENCRYPT_FAILED", "reason=${e.javaClass.simpleName}")
      false
    }
  }

  // Corupere/cheie-lipsa/autentificare-esuata: toate intorc lista goala pentru ACEASTA sesiune,
  // niciodata sterg sau rescriu ce e deja pe disc (diagnosticul ramane posibil; o reparare viitoare
  // nu e impiedicata de o stergere pripita).
  fun load(ctx: Context): List<SavedPlace> {
    val raw = prefs(ctx).getString(KEY_ENVELOPE, null) ?: return emptyList()
    val key = getOrCreateKey() ?: run {
      AudioDiag.log(ctx, "MEMORY_DECRYPT_FAILED", "reason=key_unavailable")
      return emptyList()
    }
    return try {
      val envelope = JSONObject(raw)
      if (envelope.optInt("v", -1) != FORMAT_VERSION) {
        AudioDiag.log(ctx, "MEMORY_DECRYPT_FAILED", "reason=unknown_format_version")
        return emptyList()
      }
      val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
      val ciphertext = Base64.decode(envelope.getString("ct"), Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      // doFinal arunca AEADBadTagException daca tagul de autentificare nu se potriveste — date
      // alterate sau cheie gresita; prins mai jos, niciodata propagat ca sa darame apelantul.
      deserialize(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
    } catch (e: GeneralSecurityException) {
      AudioDiag.log(ctx, "MEMORY_DECRYPT_FAILED", "reason=${e.javaClass.simpleName}")
      emptyList()
    } catch (e: Exception) {
      AudioDiag.log(ctx, "MEMORY_STORAGE_CORRUPT", "reason=${e.javaClass.simpleName}")
      emptyList()
    }
  }

  private fun serialize(places: List<SavedPlace>): String {
    val arr = JSONArray()
    for (p in places) {
      val aliases = JSONObject()
      for ((lang, alias) in p.aliases) aliases.put(lang, alias)
      arr.put(
        JSONObject()
          .put("canonicalName", p.canonicalName)
          .put("latitude", p.latitude)
          .put("longitude", p.longitude)
          .put("confirmedByUser", p.confirmedByUser)
          .put("aliases", aliases),
      )
    }
    return arr.toString()
  }

  private fun deserialize(json: String): List<SavedPlace> {
    val arr = JSONArray(json) // arunca daca nu-i JSON valid -> prins de catch-ul din load()
    val out = mutableListOf<SavedPlace>()
    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      val name = obj.optString("canonicalName", "")
      if (name.isBlank()) continue
      val aliasesObj = obj.optJSONObject("aliases") ?: JSONObject()
      val aliases = mutableMapOf<String, String>()
      for (lang in aliasesObj.keys()) aliases[lang] = aliasesObj.optString(lang, "")
      out.add(
        SavedPlace(
          canonicalName = name,
          aliases = aliases,
          latitude = obj.optDouble("latitude", 0.0),
          longitude = obj.optDouble("longitude", 0.0),
          confirmedByUser = obj.optBoolean("confirmedByUser", false),
        ),
      )
    }
    return out
  }
}
