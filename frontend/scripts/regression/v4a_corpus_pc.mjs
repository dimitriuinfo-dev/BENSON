#!/usr/bin/env node
// V4a corpus — varianta PC, cerută de pivotul "Rareș pleacă ACUM cu telefonul" (08.10.2026).
// ACELAȘI prompt + ACEEAȘI schemă ca interpretorul din BensonForegroundService.kt
// (INTERP_SCHEMA/INTERP_SYSTEM_PROMPT, copiate verbatim — nu reformulate), apelate direct la
// OpenAI. Fără parser vechi pe PC, deci NU există "match=" — raportul e clasificarea
// interpretorului pe fiecare comandă, pentru revizuire manuală/listă de răspunsuri-așteptate,
// ca dovadă indirectă înainte de testul real cu Rareș. Contorizare proprie, separată de contorul
// de pe telefon (V2), ca să nu amestece cheltuiala de test cu cea reală.
//
// Cheie: niciodată în fișier/git (doctrina "nicio cheie în cod"). Citită din env OPENAI_API_KEY.
// Rulare: OPENAI_API_KEY=sk-... node scripts/regression/v4a_corpus_pc.mjs

const API_KEY = process.env.OPENAI_API_KEY || "";
const BASE_URL = (process.env.BRAIN_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
const MODEL = "gpt-5-nano";

const INTERP_SCHEMA = {
  type: "object",
  properties: {
    action: { type: "string", enum: ["WA_CALL","WA_VIDEO","WA_MESSAGE","APP_OPEN","YT_SEARCH","MEDIA_STOP","CLARIFY","CHAT","UNKNOWN"] },
    contact: { type: ["string","null"] }, message: { type: ["string","null"] }, app: { type: ["string","null"] },
    query: { type: ["string","null"] }, question: { type: ["string","null"] },
  },
  required: ["action","contact","message","app","query","question"],
  additionalProperties: false,
};

const INTERP_SYSTEM_PROMPT = "Ești interpretorul BENSON. Clasifici o comandă rostită " +
  "(română/germană/engleză) într-un singur obiect JSON, exact schema dată. action: WA_CALL/" +
  "WA_VIDEO (contact=numele persoanei), WA_MESSAGE (contact+message), APP_OPEN (app=numele " +
  "aplicației), YT_SEARCH (query=ce se caută), MEDIA_STOP (fără alte câmpuri), CLARIFY " +
  "(question=o întrebare scurtă, dacă nu e clar ce vrea), CHAT (conversație liberă, întrebare " +
  "sau afirmație fără acțiune de executat), UNKNOWN (nimic din astea/comandă fără sens). " +
  "Câmpurile nefolosite = null.";

// Aceleași 50 de comenzi din scripts/regression/v4a_corpus.sh, plus coloana "expected" —
// răspunsul așteptat, scris independent de interpretor, pentru o comparație reală (singura
// posibilă pe PC, fără parserul vechi).
const CMDS = [
  ["suna-o pe Hannah pe whatsapp", "WA_CALL"],
  ["video cu Hannah", "WA_VIDEO"],
  ["scrie-i lui Hannah ca ajung tarziu", "WA_MESSAGE"],
  ["ruf Hannah auf WhatsApp an", "WA_CALL"],
  ["call Hannah on whatsapp", "WA_CALL"],
  ["cauta madonna pe youtube", "YT_SEARCH"],
  ["cauta anime pe youtube", "YT_SEARCH"],
  ["search cats on youtube", "YT_SEARCH"],
  ["suche auf youtube nach musik", "YT_SEARCH"],
  ["opreste muzica", "MEDIA_STOP"],
  ["stop the music", "MEDIA_STOP"],
  ["pune pauza", "MEDIA_STOP"],
  ["deschide calculatorul", "APP_OPEN"],
  ["deschide setarile", "APP_OPEN"],
  ["open chrome", "APP_OPEN"],
  ["offne die kamera", "APP_OPEN"],
  ["deschide spotify", "APP_OPEN"],
  ["cat e ceasul", "CHAT"],
  ["wie spaet ist es", "CHAT"],
  ["what time is it", "CHAT"],
  ["cum e vremea azi", "CHAT"],
  ["cine esti tu", "CHAT"],
  ["povesteste-mi o gluma", "CHAT"],
  ["ce zi este azi", "CHAT"],
  ["spune-mi ceva interesant", "CHAT"],
  ["esti ocupat", "CHAT"],
  ["multumesc", "CHAT"],
  ["bine", "CHAT"],
  ["ok", "CHAT"],
  ["nu stiu", "CHAT"],
  ["fa ceva", "CLARIFY"],
  ["ajuta-ma", "CLARIFY"],
  ["suna-o pe mama", "WA_CALL"],
  ["trimite un mesaj lui Ingrid", "WA_MESSAGE"],
  ["cauta pisici dragute pe youtube", "YT_SEARCH"],
  ["deschide whatsapp", "APP_OPEN"],
  ["pune muzica", "CLARIFY"],
  ["next song", "MEDIA_STOP"],
  ["skip", "MEDIA_STOP"],
  ["volum mai mare", "UNKNOWN"],
  ["inchide aplicatia", "UNKNOWN"],
  ["mergi acasa", "UNKNOWN"],
  ["suna-l pe tata pe whatsapp", "WA_CALL"],
  ["trimite-i un mesaj Hannei ca am ajuns", "WA_MESSAGE"],
  ["video call cu Hannah", "WA_VIDEO"],
  ["ruf mal Hannah an", "WA_CALL"],
  ["cauta retete de prajituri pe youtube", "YT_SEARCH"],
  ["opreste tot", "MEDIA_STOP"],
  ["esti treaz", "CHAT"],
  ["benson esti acolo", "CHAT"],
];

// Prețuri V2 (OpenAiUsageMeter.kt), aceleași constante, EUR ca referință.
const GPT5_NANO_INPUT_PER_1M_USD = 0.05;
const GPT5_NANO_OUTPUT_PER_1M_USD = 0.40;
const USD_TO_EUR = 0.89;

async function classify(transcript) {
  const body = {
    model: MODEL,
    messages: [
      { role: "system", content: INTERP_SYSTEM_PROMPT },
      { role: "user", content: transcript },
    ],
    response_format: {
      type: "json_schema",
      json_schema: { name: "benson_intent", strict: true, schema: INTERP_SCHEMA },
    },
  };
  const resp = await fetch(`${BASE_URL}/chat/completions`, {
    method: "POST",
    headers: { "Authorization": `Bearer ${API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => "");
    return { error: `http=${resp.status} ${text.slice(0, 200)}` };
  }
  const json = await resp.json();
  const content = json?.choices?.[0]?.message?.content;
  const usage = json?.usage;
  let costUsd = 0;
  if (usage) {
    costUsd = (usage.prompt_tokens / 1_000_000) * GPT5_NANO_INPUT_PER_1M_USD +
      (usage.completion_tokens / 1_000_000) * GPT5_NANO_OUTPUT_PER_1M_USD;
  }
  if (!content) return { error: "empty_content" };
  try {
    return { parsed: JSON.parse(content), costUsd };
  } catch (e) {
    return { error: `parse_fail: ${content.slice(0, 200)}` };
  }
}

async function main() {
  if (!API_KEY) {
    console.error("OPENAI_API_KEY nesetat. Rulare: OPENAI_API_KEY=sk-... node " + import.meta.url);
    process.exit(1);
  }
  let matched = 0, errored = 0, totalCostUsd = 0;
  const lines = [];
  for (let i = 0; i < CMDS.length; i++) {
    const [text, expected] = CMDS[i];
    const result = await classify(text);
    if (result.error) {
      errored++;
      lines.push(`[${i + 1}/${CMDS.length}] FAIL text="${text}" error=${result.error}`);
      console.log(lines[lines.length - 1]);
      continue;
    }
    totalCostUsd += result.costUsd || 0;
    const got = result.parsed.action;
    const match = got === expected;
    if (match) matched++;
    lines.push(`[${i + 1}/${CMDS.length}] ${match ? "MATCH" : "DIFF "} text="${text}" expected=${expected} got=${got} costUsd=${(result.costUsd || 0).toFixed(6)}`);
    console.log(lines[lines.length - 1]);
  }
  const summary = `\n=== V4a CORPUS PC SUMMARY ===\ntotal=${CMDS.length} matched=${matched} errored=${errored} ` +
    `matchRate=${((matched / CMDS.length) * 100).toFixed(1)}% totalCostUsd=${totalCostUsd.toFixed(4)} ` +
    `totalCostEur=${(totalCostUsd * USD_TO_EUR).toFixed(4)}`;
  console.log(summary);
  const fs = await import("node:fs");
  fs.writeFileSync(new URL("./v4a_corpus_pc_last_run.log", import.meta.url), lines.join("\n") + "\n" + summary + "\n");
}

main();
