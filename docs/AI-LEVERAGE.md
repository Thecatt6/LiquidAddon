# PROMPT — "L'AI come leva": l'unica parte della pipeline che porta una distribuzione umana

Copia questo testo in una nuova sessione sullo stesso repository.

---

## Contesto

Repo: `Thecatt6/LiquidAddon` — add-on per LiquidBounce (Fabric, MC 1.21.x, Kotlin 2.x, JDK 25).
PR #33–#49 già mergiate. Non riscrivere ciò che c'è, **estendi**.

Stato rilevante:

- `aiming/AimEngine.kt` — motore condiviso (Aimassist, Aimbot, Fireball*). Lato AI: `aiStep`,
  `AiReach`, `AIStrength`, `AICorrection`, `AIYawMultiplier`, `AIPitchMultiplier`. Lato non-AI:
  `PatternGuard`, reticolo GCD, `handOnLattice`, `HandGate`.
- **PR #49 appena mergiata**: con `Engine = AI` il passo del modello non viene più moltiplicato per
  `Strength`. `AIStrength 100` = il modello decide il passo; `PitchAssist` non dimezza più il
  verticale; il clamp permette l'overshoot (`1 + Overshoot%`); `AiReach` sfuma la risposta fuori dai
  6 blocchi / 30°.
- `configs/model_data.py` — stadio dati della pipeline AI (**non toccarlo**, è a posto).
- `configs/check_config.py` — validatore config contro i sorgenti del client.
- Test: `kotlin.test` + JUnit 5, `src/test/kotlin/com/liquidaddon/...`.

## L'ipotesi da verificare (leggere prima di scrivere codice)

L'utente riporta, **testando**, che con l'AI il bypass è migliore. Prendetela come ipotesi seria, non
come errore da correggere — e c'è un meccanismo plausibile che la sostiene:

> La curva matematica calcola `passo = Δ × strength × f(Δ)`. È una **legge**: a parità di Δ produce
> sempre lo stesso numero, e avvicinandosi al bersaglio genera una progressione geometrica
> (21, 15, 10, 7, 5, 3, 2, 1 counts) con rapporto costante — esattamente la forma che i check
> statistici ("pattern", "constant rotation", `Baritone`) cercano. Il modello, invece, è una
> regressione addestrata su tick **umani**: porta con sé la distribuzione dei passi di una mano
> (quanto accelerare, quando fermarsi corti, quanto overshoot), non una formula. Dopo la #49 quella
> distribuzione arriva al server quasi intatta.

Quindi l'AI **è** la leva giusta da esplorare — purché la si smetta di trattare come "rumore in più
sopra la curva". La direzione del lavoro è: **il modello come autore, il codice come guardrail.**

## Regole della casa (non negoziabili)

1. **Nessun numero inventato.** Ogni affermazione su "miglior bypass" va misurata o dichiarata non
   misurata. Il report finale deve separare le due cose.
2. **Non alzare la capacità di colpire.** Il lavoro è su *come* la mira si muove, non su quanto
   lontano o quanto veloce arriva. `Range`, `MaxSpeed`, `Strength` non si toccano al rialzo.
3. Codice e KDoc in **inglese**; PR, chat in game e report finale in **italiano**.
4. Niente compilazione locale (niente rete verso maven/gradle). Verifica così:
   - classi pure → `kotlinc` usa-e-getta (`pip download jdk4py`, `npm i kotlin-compiler`) + runner
     che invoca i `@Test` via reflection;
   - `AimEngine.kt` → `kotlinc` standalone, contano solo gli errori **non** `unresolved reference` /
     `cannot access` / `cannot infer` (attesi: manca il classpath del client). Riporta prima/dopo;
   - JSON → sempre `json.load` dopo la modifica;
   - build vera → GitHub Actions (`gh run watch <id> --exit-status`).
5. Una PR per deliverable, branch `arena/<hash>-liquidaddon`, `gh pr merge <n> --merge`, poi verifica
   la release `snapshot` e riporta i byte del jar.
6. Ogni regola isolabile va in una **classe pura senza dipendenze dal client** (come `HandGate`,
   `AiReach`, `JumpResetHud`) con il suo test.

## Deliverable

### A1 — Misurare prima di toccare  *(must, primo)*

Due pezzi, entrambi preliminari a tutto il resto.

**(a) `configs/ai_eval.py`** — valutazione **offline**, sola stdlib, sulle registrazioni che
`configs/model_data.py report` sa già leggere (`.liquidbounce/debug-recorder/{Combat,CombatTrainer}`).

Deve:
- ricostruire i **6 input** esattamente come `CombatSample.fillAsInput` (Δyaw, Δpitch, Δ precedente
  yaw/pitch, `speed = |playerDiff| + |targetDiff|` orizzontale, `distance` **al quadrato**), e opzionale
  +2 (`hurtTime`, `age`) dietro flag;
- addestrare una **ridge regression** (equazioni normali, eliminazione di Gauss, niente numpy) e
  valutare su uno split temporale (ultimo 20% delle sessioni, non righe a caso: le righe sono
  correlate nel tempo);
- riportare **MAE per asse** contro tre baseline:
  1. `zero` (non correggere),
  2. **legge proporzionale con α ottimizzato** — cioè la famiglia della curva `Math`,
  3. la curva spedita (`BEZIER`/`SIGMOID` con gli stessi parametri del profilo);
- riportare la **correlazione residua** e una metrica di "quanto è ripetitivo": passi distinti, run
  identiche più lunga, entropia della distribuzione dei passi quantizzati.

La domanda a cui risponde, in una riga: **il modello batte una legge proporzionale ben tarata?**
Se no, nessuna epoca di training lo salverà e tutto il resto è cosmetico. Se sì, dice *di quanto*, e
quello è il numero da inseguire.

**(b) `/liquidaddon ai`** — telemetria in gioco (nuovo `CommandRegistrar`, vedi `CommandExample.kt`):

```
ai 94% used | fb: init 0, model 0, nan 0, reach 6% | step 1.20° vs curve 0.85° | corr 0.71 | aiStr 100
```

Contatori in `AimassistCore`, formattazione in una classe pura testabile (`AiStats.format(...)`, test
sul modello di `JumpResetHudTest`). Deve essere leggibile **a fine partita senza aprire la GUI**.

### A2 — Input coerente con il training  *(must)*

Il modello è stato addestrato con `targetVector = Rotation.lookingAt(target.eyePosition, me.eyePosition)`.
L'engine gli passa `goalRotation`, che usa `AimPoint = CLOSEST` + `Predict`: a 3 blocchi il punto più
vicino nel box dista dagli occhi fino a ~13–17° di **pitch**. Il modello risponde quindi a un
`totalDelta` che non ha mai visto, **su ogni tick**.

- Il **punto del modello** (`eyePosition` del target) va separato dal **punto dell'assist**
  (`AimPoint`/`Predict`, che resta all'utente e al termine di correzione).
- Nuovo valore `AIInputPoint` (`Eyes` default | `Assist`), così si può tornare indietro e misurare.
- Estrarre la costruzione degli input in una classe pura `AiFeatures.kt`:
  `features(deltaYaw, deltaPitch, prevDYaw, prevDPitch, speed, distance, hurtTime, age)` che
  restituisce un `FloatArray` nell'ordine documentato di `CombatSample`, con guardie su NaN/Infiniti.
  Test: ordine degli indici, clamp, casi degeneri.

### A3 — Il modello come autore: togliere i doppioni  *(must)*

Oggi, sopra una risposta che **è già** un tick umano, si applicano `MouseNoise` (±1–2 counts),
`Overshoot` (10%) e `StrengthSpread`. Rumore sopra una distribuzione umana la rende *meno* umana: due
randomizzazioni indipendenti non si sommano in credibilità, si sommano in varianza.

- Nuovo valore `AIHand` (`On` default) che, con `Engine = AI`, azzera i layer ridondanti e lascia
  attivi solo `PatternGuard`, reticolo GCD, carry e `HandGate`.
- `Overshoot` va a 0 **solo** per l'AI (il modello overshoota da sé); `MouseNoise` scende a 1.
- I profili giornalieri: **Profile A intatto**, Profile B con `AIHand = On`.
- Test: classe pura `AiHand.kt` che decide i tre pesi dati `(engine, aiHand, values)`.

### A4 — Seguire il modello attivo del client  *(must)*

`AiModel` è un enum fisso (`19KC8KP`, `21KC11KP`): chi addestra un modello con `/models improve Nome`
non lo può selezionare. Aggiungere il valore **`Client`** che mappa su `ModelManager.models.activeMode`
(il modello che LiquidBounce ha già attivo nel suo menu AI, incluso uno addestrato dall'utente) e farlo
diventare il default di Profile B. `activeModel()` deve degradare con la stessa catena di
`aiUnavailable` se il gruppo non è inizializzato.

### A5 — Guardare la divergenza, non solo l'errore  *(should)*

Una regressione non sbaglia "rumorosamente": sbaglia **con coerenza**. Se il passo del modello diverge
sistematicamente dalla curva sullo stesso input, è quello il momento di non fidarsi.

- Su una finestra di N tick (default 20), misurare la **correlazione** tra passo del modello e passo
  della curva sullo stesso Δ. Se scende sotto soglia (`AiAudit`-style, classe pura `AiDrift.kt`),
  sfumare verso la curva — non a zero: **metà**, e solo per la durata della finestra.
- Vale come `AiReach`: è un guardrail, deve essere visibile in `/liquidaddon ai` (`corr 0.71`).

### A6 — Report + documentazione  *(must)*

- README: sezione "The model as the author, the code as the guardrail" — chi decide cosa (tabella),
  e un paragrafo **onesto** che dica: *il modello porta la distribuzione di una mano, la curva porta
  una formula; nessuno dei due rende invisibili, e non c'è una misura pubblica di "bypass".*
- `en_us.json`: descrizione per ogni nuovo valore.
- `configs/check_config.py` verde, output incollato.
- `configs/liquidaddon-pvp189-ghost.json`: aggiungere i nuovi valori che servono a Profile B.

## Definition of done

- [ ] `ai_eval.py` eseguito **sui dati reali dell'utente** (o su un dataset sintetico dichiarato
      tale, se non ci sono registrazioni): riportare i numeri, incluse le baseline;
- [ ] test nuovi verdi, con il conteggio esatto dei check;
- [ ] `AimEngine.kt` senza errori diversi da quelli attesi (due numeri: prima / dopo);
- [ ] CI verde, PR mergiata, release `snapshot` aggiornata: riportare i byte del jar;
- [ ] **nessuna modifica a `configs/model_data.py`**;
- [ ] report finale in italiano con due liste separate: **misurato** e **non misurato**.

## Cosa NON fare

- Non dichiarare miglioramenti di bypass senza una misura: è la ragione per cui A1 viene prima.
- Non alzare `Range`, `MaxSpeed`, `Strength`, né aggiungere valori che li alzano.
- Non toccare quantizzazione, reticolo GCD, `handOnLattice` o `PatternGuard`: restano il pavimento.
- Non riscrivere Profile A. B cambia solo dove l'AI lo richiede.
- Non committare artefatti, jar, `__pycache__`, registrazioni o backup di config.
