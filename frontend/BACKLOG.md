# BACKLOG

Orice cerere în afara listei închise „BENSON 1.0" (vezi CLAUDE.md) se notează aici, nu se
implementează pe loc. Fără dată de livrare — se reconsideră abia după ce lista de 10 e toată verde.

- CAR-3a/CAR-3b: ecranul Android Auto (BensonCarAppService) și microfonul mașinii (CarAudioRecord,
  la atingere). Validatorul Android Auto încă respinge pachetul („failed all other checks", cauză
  nerezolvată) după fix-ul de `<uses name="template"/>`.
- Microfon prin HFP (ideea „ca Android Auto") — respinsă azi: ocupă canalul Bluetooth exclusiv,
  ar opri muzica din boxele mașinii cât ascultă. De investigat separat, cu mașina oprită.
- W-NS (NoiseSuppressor + AutomaticGainControl pe bucla HEED) — cerut, neconstruit azi (prioritizat
  sub RT-1a, apoi abandonat în favoarea verificatorului local personal).
- Verificator local personal (enrollment din clipurile de wake ale lui Rareș) — prioritate mare
  pentru rezolvarea offline-ului pe autostradă, dar separat de lista de 10.
- FIX_WAKE_OFFLINE_CAR_ACCEPT_1 — încercat și REVERTIT azi (scorul HEED nu separă „Benson" de fals,
  0,960 fals vs 0,917-0,99 real, se suprapun). Nu reîncerca fără verificator local.
- „apasă play" — CONTROL_ACT action=press poate eșua (ok=false) fără lanț de fallback (buton găsit
  → părinte clickable → primul rezultat de pe ecran). Bug real, confirmat pe teren, nereparat.
- U-1: codul G-1 mutat sub interfețele Observe/Resolve/Act/Verify/SafetyGate, fără schimbare de
  comportament. Refactor pur, nicio urgență funcțională.
- Extragerea „pe/în <aplicație>" (AppMentionStripper) — implementată, dar a introdus o regresie
  reală azi („caută Madonna pe YouTube" interceptat greșit de fluxul nou YouTube cu confirmare,
  în loc de drumul vechi `yt_search`); de reconciliat când se reia capacitatea 3 din lista de 10.
