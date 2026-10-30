# Fase 7 e punti aperti: progetto

Data: 30 ottobre 2026. Stato: da approvare.

## Perché

El Termino è un client SSH e SFTP per uso personale, senza sync. Mancano ancora l'autenticazione avanzata e il modo di spostare la configurazione tra Mac e telefono, e le fasi concluse hanno lasciato limiti elencati in `docs/PLAN.md` (sezione Stato) e nelle decisioni 0011-0014.

Il progetto chiude quei limiti. Resta fedele al piano: niente server propri, niente cloud, una sola implementazione SSH su JVM.

## Obiettivi

Lavori indipendenti, uno alla volta, ognuno con commit, test e decisione in `docs/decisions/`, nell'ordine qui sotto.

### A. Backup cifrato

- Esporta in un solo file `workspace.json` e tutti i segreti del vault, cifrati con una password scelta al momento.
- Cifratura: Argon2id per derivare la chiave e AES-256-GCM, con BouncyCastle che è già tra le dipendenze. Nessuna libreria nuova.
- Il file è portabile: un backup fatto su Mac si ripristina su Android e viceversa.
- Ripristino: anteprima di cosa contiene, poi una scelta tra "sostituisci tutto" e "unisci". Unire significa: gli elementi con lo stesso id prendono la versione del backup, gli altri restano.
- Known hosts in "unisci": si aggiungono solo le voci nuove. Se host, porta e tipo coincidono ma la chiave è diversa resta quella locale, e l'anteprima segnala il conflitto.
- Impostazioni del dispositivo (`ui`: tema, benvenuto, segnalibri locali, registrazioni, blocco biometrico) e `lastSession` non viaggiano nel backup e non vengono toccate dal ripristino, nemmeno con "sostituisci tutto". Un backup del Mac non può attivare su un altro dispositivo un blocco che lì non esiste.
- Prima di sostituire, l'app salva una copia dei dati attuali. Un file manomesso o una password sbagliata non cambiano nulla.
- Stanno fuori dal backup le registrazioni, perché sono solo locali e possono pesare molto.
- Da Impostazioni, con il selettore di file del sistema.

### B. Master password dove non c'è il portachiavi

Linux senza Secret Service oggi non ha ripiego (decisione 0004). Si aggiunge un `MasterKeyProvider` che deriva la chiave da una master password con Argon2id, chiesta all'avvio. Si attiva solo se il portachiavi manca. Si sta attenti alla regola di 0004: una chiave nuova non deve mai sostituirne una che apre un vault esistente.

### C. Certificati host con CA fidata

- Un elenco di CA fidate nel workspace: chiave pubblica della CA e host a cui si applica (schema tipo `*.example.com` e `[host]:porta`), come `@cert-authority` di OpenSSH.
- Se il server presenta un certificato firmato da una CA fidata, valido nel tempo e con il nome dell'host tra i principal, ci si collega senza chiedere conferma.
- Certificato scaduto, firma non valida o nome sbagliato: blocco netto, senza un bottone per passare oltre e senza ripiegare sulla fiducia al primo uso. L'unica uscita è togliere o cambiare la CA dall'elenco, cioè una scelta deliberata. La finestra di oggi per la chiave cambiata resta com'è.
- Host coperto da una CA che presenta una chiave semplice: si accetta solo se è già tra le chiavi fidate, altrimenti blocco. Così un attaccante non evita il controllo offrendo una chiave senza certificato.
- Certificato valido ma chiave diversa da quella salvata: ci si collega, perché la CA garantisce il rinnovo, e un avviso nell'app dice che la chiave è cambiata. La vecchia voce viene sostituita.
- Senza CA corrispondente tutto resta come oggi: fiducia al primo uso sulla chiave certificata.
- Import delle righe `@cert-authority` da `~/.ssh/known_hosts` sul desktop.
- L'ambiente di test ha già host certificato e `known_hosts_ca`: il test passa da lì.

### D. SSH agent e agent forwarding (solo desktop)

- macOS e Linux: socket Unix di `SSH_AUTH_SOCK` con le API del JDK. Windows: named pipe di OpenSSH e Pageant.
- `AuthMethod.Agent` esiste già nel modello ma il connettore lo rifiuta (`MinaSshConnector.kt:65`): va implementato, anche per i salti di un jump host.
- Nell'identità c'è una spunta "Usa l'SSH agent", che si affianca a chiave e password. Quando è attiva l'agent si prova per primo, poi chiave e password come oggi in `ConnectionPlanner`.
- Agent forwarding: opzione per host, spenta di default, come in OpenSSH, perché chi è root sul server può usare l'agent inoltrato.
- Se l'agent non c'è o è vuoto si passa al metodo successivo, compresa la richiesta di password. Se alla fine il login fallisce, l'errore dice anche che l'agent era vuoto o assente.
- Limite noto: un agent con molte chiavi può esaurire `MaxAuthTries` del server, come con OpenSSH.
- Su Android l'opzione non compare.

### E. Sblocco biometrico

- Android con BiometricPrompt, macOS con Touch ID.
- Opzione nelle Impostazioni, spenta di default: all'avvio l'app chiede l'impronta prima di mostrare host e segreti, e la chiede di nuovo dopo un'attesa in background scelta dall'utente (default 5 minuti). In background vuol dire: su Android app non visibile; su desktop nessuna finestra dell'app attiva. Il computer in stop conta come tempo passato.
- Windows e Linux: l'opzione non compare.
- Se la biometria non è disponibile o fallisce, si ripiega sul codice del dispositivo o sulla password del sistema, così non si resta chiusi fuori.
- È un blocco dell'interfaccia: la chiave del vault resta dov'è ora. Le sessioni aperte continuano a girare sotto il blocco.

### F. Punti aperti delle fasi concluse

Nell'ordine, dai più piccoli:

1. **HTTP, redirect:** opzione per richiesta, seguiti solo se restano sullo stesso host e porta. Quelli verso altri host non si seguono e la risposta lo dice. Massimo 10.
2. **HTTP, certificati non validi:** accettazione a mano legata a host e impronta SHA-256 del certificato, salvata come una host key. Se il certificato cambia si chiede di nuovo. Mai "accetta tutto".
3. **Port forwarding:** indirizzo di ascolto a scelta. Tutto ciò che non è loopback mostra un avviso, perché la porta diventa raggiungibile dalla rete. Elenco globale dei forward attivi.
4. **File, editor esterno (desktop):** "Apri con editor esterno", con upload a ogni salvataggio e lo stesso controllo di modifiche remote dell'editor integrato.
5. **File, trascinamento verso il Finder o Esplora risorse.** File e selezioni multiple, fino a 200 MB in totale: si scaricano in una cartella temporanea durante il trascinamento. Oltre il limite, e per le cartelle, un messaggio rimanda a "Copia in…".
6. **Android, selettore di sistema** per il pannello locale: caricare e salvare fuori dalla cartella dell'app.
7. **Monitor:** processi più pesanti (`ps`).
8. **Docker:** rimozione di container con conferma, immagini, volumi e reti in sola lettura più rimozione, Compose (`docker compose ls`, up, down, restart).
9. **Registrazioni:** la riproduzione rispetta i ridimensionamenti.
10. **Icona del launcher su Android.**

### G. Documenti

Aggiornare `docs/PLAN.md` (Stato, Roadmap, data) e il README, che dice ancora che la Fase 8 è da fare. Va fatto subito all'inizio per la Fase 8 e poi man mano.

## Non obiettivi

- **YubiKey e FIDO2:** esclusi, l'utente non ne ha una. Si tolgono dal piano, insieme alla voce "YubiKey su Android" in "Da verificare".
- **Mosh:** è la Fase 9, resta fuori da questo giro.
- Monitor per host macOS e BSD: le macchine dell'utente sono tutte Linux.
- Nuove porte aperte di default, sync, cloud, account.

## Da evitare

- Segreti in chiaro su disco o nei log, compresi i file temporanei di un backup.
- Un ripristino a metà che lascia dati incoerenti.
- Qualunque cambiamento che faccia passare in silenzio una host key o un certificato prima rifiutati.
- Rompere i dati esistenti: i campi nuovi di `workspace.json` hanno un default, come da decisione 0004.

## Vincoli

- Kotlin Multiplatform; codice comune in `shared` e `core`, le differenze dietro `Platform` o `expect`/`actual`.
- Una nuova dipendenza solo se serve davvero: `androidx.biometric` è attesa.
- Le prove passano dai server in `testenv`, come per le fasi precedenti. Test di flusso per ogni funzione.
- Testi dell'interfaccia in italiano, come il resto dell'app.

## Scelte di chi ha scritto il progetto (non dell'utente)

Tutto il contenuto di A-F nasce dal piano o dalle decisioni esistenti. I dettagli che seguono sono proposte: formato del file di backup, "unisci" per id, biometria come blocco dell'interfaccia e non del vault, 5 minuti di attesa, forwarding spento di default, comportamento della CA nei casi di conflitto, blocco netto sui certificati non validi, cosa viaggia nel backup, spunta "Usa l'SSH agent" per prima, limite di 200 MB nel trascinamento, redirect solo sullo stesso host, accettazione dei certificati per impronta, ordine dei punti aperti.

Lasciati a chi costruisce: come chiamare Touch ID dalla JVM (per esempio un piccolo aiutante nativo nel pacchetto), parametri Argon2id, come implementare il protocollo agent sopra MINA, come si presentano le nuove schermate purché seguano lo stile Carta.

## Prove che restano all'utente

Non si possono fare qui: Touch ID e BiometricPrompt su hardware vero, Pageant su Windows, selettore di sistema e icona su un telefono, trascinamento verso il Finder a mano.
