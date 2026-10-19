# BENSON NEXT — criterii de acceptare per capacitate

Convenție: **UNIT_TEST_PASS** = compilat + testat cu fakes/dry-run (acest mediu). **DEVICE_PASS** =
confirmat real, pe telefon, de proprietar. Niciun UNIT_TEST_PASS nu se declară DEVICE_PASS.

| Capacitate | Criteriu de acceptare | Stare |
|---|---|---|
| BENSON LIVE | TEXT valid → Mission executată → rezultat verificat, pentru ≥2 domenii (TRAVEL, COMMS) fără cod special per domeniu în orchestrator | UNIT_TEST_PASS |
| AKA | O capabilitate DECLARED/INFERRED/UNIT_TESTED nu e oferită ca utilizabilă; doar DEVICE_VERIFIED/RUNTIME_VERIFIED | UNIT_TEST_PASS |
| MEMORY | Aceeași destinație salvată, regăsită identic din RO și DE | UNIT_TEST_PASS |
| SURVIVOR | Un executor simulat cu verificare eșuată produce FAILED prin tot lanțul, chiar cu AKA aprobat | UNIT_TEST_PASS |
| EXECUTOR real #1 | Calculator se deschide prin Intent real + `currentForegroundPackageLive()` confirmă prim-planul | **URMEAZĂ — cere DEVICE_PASS pentru a conta** |
| VOICE | Indisponibil → text, niciodată altă voce | UNIT_TEST_PASS (decizie); client real TTS **URMEAZĂ** |
| LEGACY fix (voce/TV) | „Benson, taci" oprește imediat; fără trezire falsă de la TV; voce ro-RO consistentă tastat+vocal | **Cod gata, cere DEVICE_PASS** |

Nicio linie din acest tabel nu trece la DEVICE_PASS fără un test real, descris explicit (comandă,
rezultat observat, log), confirmat de proprietar pe telefon.
