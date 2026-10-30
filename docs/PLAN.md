# El Termino: piano di progetto

Ultimo aggiornamento: 30 ottobre 2026 (Fase 7 e rifiniture)

## Obiettivo

Un client SSH e SFTP per uso personale, nello stile di Termius, scritto in Kotlin Multiplatform con Compose Multiplatform. Serve a collegarsi alle circa 48 macchine dei miei server con tutte le opzioni di autenticazione e a tenere aperte più sessioni SSH e SFTP insieme, anche sulla stessa macchina.

## Decisioni prese

| Tema | Decisione |
|---|---|
| Piattaforme | Android, macOS, Windows, Linux. iOS escluso. |
| Stile | B "Carta": chiaro, nativo, accento blu. Una variante scura si può aggiungere con gli stessi token. |
| Sincronizzazione | Nessuna, tutto locale. Backup manuale su file cifrato per spostare la configurazione tra dispositivi. |
| Distribuzione | Solo uso personale: APK installato a mano su Android, pacchetti desktop dmg / msi / deb senza store. |
| Import | Da Termius: host e gruppi, chiavi, identità. |
| Autenticazione | Tutti i metodi: password, chiavi Ed25519 / RSA / ECDSA anche con passphrase, OTP, jump host, SSH agent, certificati OpenSSH. |
| Funzioni extra | Port forwarding, snippet, broadcast input, monitor risorse, editor remoto, pannello Docker, registrazione sessioni, Mosh. |

La conseguenza più importante: tutte e quattro le piattaforme girano su JVM (ART su Android, JVM sul desktop). Basta quindi una sola implementazione SSH, senza libssh2 e senza cinterop.

## Architettura

```
app-android        entry point Android (Activity, Foreground Service)
app-desktop        entry point desktop (finestra, menu, tray)
shared             UI Compose, tema Carta, navigazione, feature
  └─ feature/      hosts · terminal · sftp · http · keys · import · snippets
                   forwarding · monitor · docker · recordings · settings
core/ssh           interfaccia comune + implementazione JVM
core/terminal      emulatore VT/xterm + renderer Compose Canvas
core/vault         segreti cifrati, Keystore / keychain di sistema
core/data          database: host, gruppi, identità, chiavi, known_hosts, snippet
core/mosh          client Mosh (fase 9)
```

Le feature partono come package dentro `shared`. Diventano moduli Gradle separati solo se crescono.

Stack: Kotlin 2.x, Compose Multiplatform, Coroutines/Flow, Koin, Room KMP (o SQLDelight), kotlinx.serialization, Okio, Kermit, BouncyCastle.

### Libreria SSH

Candidati da confrontare nello spike della fase 0:

| Libreria | Pro | Contro |
|---|---|---|
| Apache MINA SSHD | Matura e molto completa: certificati, ProxyJump, SFTP, port forwarding | API pesante. Per agent e chiavi FIDO2 lato client serve codice nostro. |
| connectbot/cbssh | Supporta già chiavi FIDO2 `sk-ssh-ed25519`, certificati con CA e KRL, forwarding locale/remoto/SOCKS, SFTP | Versione 0.5, poca diffusione |
| sshj | Leggera, API semplice | Meno completa sugli scenari avanzati |

L'interfaccia `core/ssh` nasconde la libreria: se serve, si cambia o si combinano due librerie.

### Sessioni multiple

- Un `SessionManager` globale tiene N connessioni aperte, ognuna nel suo `CoroutineScope` con `SupervisorJob`. Se una connessione cade, le altre continuano.
- Una connessione SSH per host, con più canali sopra: terminali, SFTP, comandi exec (monitor, Docker) e port forward. Aprire un'altra sessione sulla stessa macchina non richiede una nuova autenticazione.
- Stati della connessione esposti come `StateFlow`: `Connecting → VerifyingHostKey → Authenticating → Connected → Reconnecting / Closed`.
- Keepalive, riconnessione automatica con backoff, Foreground Service su Android.

```kotlin
interface SshConnection {
    val state: StateFlow<ConnectionState>
    suspend fun openShell(pty: PtyConfig): ShellChannel
    suspend fun openSftp(): SftpClient
    suspend fun exec(command: String): ExecResult
    suspend fun forward(rule: ForwardRule): ForwardHandle
    suspend fun close()
}

interface ShellChannel {
    val output: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun resize(cols: Int, rows: Int)
}

interface SftpClient {
    suspend fun list(path: String): List<RemoteFile>
    suspend fun stat(path: String): RemoteFile
    fun download(remote: String, sink: Sink, offset: Long = 0): Flow<TransferProgress>
    fun upload(source: Source, remote: String, offset: Long = 0): Flow<TransferProgress>
    suspend fun rename(from: String, to: String)
    suspend fun delete(path: String, recursive: Boolean = false)
    suspend fun mkdir(path: String)
    suspend fun chmod(path: String, mode: Int)
}
```

### Emulatore di terminale

Core in `commonMain` (parser VT/xterm, buffer dello schermo, scrollback), disegnato con Compose Canvas. Candidati nello spike: [kmp-terminal-emulator](https://klibs.io/project/JohNan/kmp-terminal-emulator) (Apache 2.0, Android e JVM), il core di JediTerm (JetBrains), oppure un core scritto da noi.

Requisiti: vim, htop, tmux e mc devono funzionare. Servono quindi alternate screen, 256 colori e truecolor, mouse reporting, bracketed paste, caratteri larghi ed emoji, resize, selezione e copia, ricerca nello scrollback. Su Android c'è la barra di tasti extra (Esc, Tab, Ctrl, Alt, frecce, `|`, `~`, `/`).

## Autenticazione

| Metodo | Come | Fase |
|---|---|---|
| Password | Salvata nel vault o chiesta ogni volta | 1 |
| Chiavi Ed25519 / RSA (rsa-sha2-256/512) / ECDSA, con passphrase | Import da file OpenSSH / PEM / PuTTY `.ppk`, generazione nell'app | 1 |
| OTP / 2FA | Keyboard-interactive. Opzionale: segreto TOTP salvato nel vault e codice compilato in automatico. | 1 |
| Jump host / ProxyJump | Catena di bastion, ogni salto con la sua identità | 1 |
| SSH agent | macOS/Linux con `SSH_AUTH_SOCK` (socket Unix di JDK 16+), Windows con la named pipe di OpenSSH e Pageant. Più l'agent forwarding. | 7 |
| Certificati OpenSSH | Chiave + `-cert.pub`, host certificate con CA fidata | 7 |
| Sblocco biometrico | BiometricPrompt su Android, Touch ID su macOS | 7 |

Verifica della host key sempre attiva: alla prima connessione mostra il fingerprint SHA256 e chiede conferma. Se la chiave cambia, la connessione si blocca con un avviso esplicito. Sul desktop si possono importare `~/.ssh/known_hosts` e `~/.ssh/config`.

## Vault e sicurezza

- Password, chiavi private, passphrase e segreti TOTP vivono solo nel vault, cifrati con AES-256-GCM. Il database contiene solo riferimenti.
- La chiave master è protetta dal sistema: Android Keystore, Keychain su macOS, Credential Manager su Windows, Secret Service su Linux (per esempio con java-keyring). Master password opzionale (Argon2id) dove il keychain non c'è.
- Backup su file cifrato con una password di backup: serve a spostare la configurazione tra Android e desktop, dato che non c'è sync.
- Niente segreti nei log.

## SFTP con più sessioni contemporanee

È un requisito centrale.

- Workspace SFTP con N pannelli: tab, griglia affiancata sul desktop (2, 3, 4 pannelli), elenco sessioni su Android.
- Ogni pannello è una sessione SFTP indipendente. Più pannelli possono puntare a host diversi o allo stesso host, sopra la stessa connessione SSH.
- Trasferimenti tra qualsiasi coppia di pannelli: locale ↔ remoto e remoto ↔ remoto, in streaming senza file temporanei.
- Un `TransferManager` unico per tutte le sessioni: coda con concorrenza regolabile per host e totale, progresso e velocità, pausa e ripresa dall'offset, conflitti (sovrascrivi, salta, rinomina), cartelle ricorsive.
- Operazioni: rinomina, elimina, crea cartella, chmod, link simbolici, file nascosti, ordinamento, segnalibri per host.
- Desktop: drag & drop dal sistema e tra pannelli. Android: Storage Access Framework.

## Schede unite e gruppi

- **Unire due sessioni in una scheda:** trascini una scheda sopra un'altra, sul bordo sinistro, destro, alto o basso, e diventano una sola scheda con lo schermo diviso. "Separa" le riporta in schede distinte. La disposizione dei riquadri diventa di ogni scheda invece che globale: una scheda può essere web-01 con i log accanto, un'altra db-01 da sola. Vale per terminali, pannelli SFTP e richieste HTTP.
- **Gruppi di schede**, come i gruppi di Chrome: nome e colore, si chiudono in un'etichetta nella barra e si riaprono. Un gruppo si crea dalle schede scelte, oppure con "Raggruppa per host", che mette insieme tutte le sessioni della stessa macchina.
- **Azioni sul gruppo:** broadcast a tutto il gruppo, snippet su tutto il gruppo, "Apri in griglia" (unisce le schede del gruppo in una sola scheda divisa), chiudi gruppo.
- **Spazi di lavoro salvati:** un gruppo si salva con host, tipo di sessione e disposizione, e si riapre con un clic. Per esempio "Deploy": due terminali su web-01 e web-02 più le richieste di salute.
- **Android:** i gruppi compaiono come sezioni nell'elenco delle sessioni; l'unione in una scheda divisa c'è solo sui tablet.

## Primo avvio, temi e avvisi

- **Benvenuto al primo avvio:** l'app chiede "Usi Termius?". Se sì, cerca i dati di Termius sul computer:
  - macOS: `~/Library/Application Support/Termius`;
  - Windows: `%APPDATA%\Termius`;
  - Linux: `~/.config/Termius`.

  Se li sa leggere, propone host, gruppi e identità da importare con l'anteprima che c'è già. Altrimenti guida all'export (CSV) e all'import di `~/.ssh/config`. **Da verificare:** come Termius tiene i dati in locale. È un'app Electron, quindi probabilmente IndexedDB/LevelDB, forse cifrati con la chiave dell'account. Serve guardare la cartella sul tuo Mac, con il tuo permesso.
- **Temi:**
  - "Notte": scuro che tende al blu (blu notte, non nero), con lo stesso accento azzurro;
  - altri temi dai colori iconici, per l'app e per il terminale (per esempio Nord, Solarized, Dracula, Gruvbox, Tokyo Night);
  - scelta nelle impostazioni, anche "segui il sistema" per chiaro e scuro.

  I token di Carta permettono di aggiungere temi senza toccare i componenti.
- **Avvisi dei trasferimenti:**
  - notifica di sistema quando un trasferimento finisce o non riesce: notifiche di macOS, Windows o Linux sul desktop, notifica su Android;
  - un avviso prima di chiudere l'app con trasferimenti in corso;
  - un breve messaggio nell'app per ogni coda completata.
- **Trascinare file su un terminale:** li carica nella cartella corrente della shell, se la shell la comunica (OSC 7), altrimenti nella home. Oggi si trascinano solo sui pannelli file.

## Richieste HTTP (tipo Postman)

Servono a provare le API che girano sulle macchine senza esporle in rete. Le richieste partono dal client ma viaggiano dentro la connessione SSH già aperta.

- **Il tunnel:** un canale `direct-tcpip` sulla connessione dell'host, lo stesso meccanismo del port forwarding, porta a `localhost:8080` visto dal server, o a qualunque indirizzo raggiungibile da lì (per esempio `10.0.0.5:9200` dal bastion). Il client HTTP (OkHttp, che gira su Android e desktop) usa il tunnel come socket. Sul server non serve installare niente, e HTTPS verifica il certificato con il nome giusto.
- **In alternativa, curl sul server:** "Esegui con curl sul server" lancia `curl` via exec, per riprodurre esattamente quello che vede la macchina (DNS, proxy, certificati). Ogni richiesta si può anche copiare come comando `curl`.
- **Editor:** metodo, URL, parametri, header, corpo (JSON, form, testo) e autenticazione (Bearer, Basic, API key in un header). Variabili `{{nome}}` come negli snippet; token e password stanno nel vault.
- **Risposta:** stato, tempi (apertura del tunnel, primo byte, totale), dimensione, header, corpo formattato (JSON con rientri e ricerca) o grezzo. Cronologia delle ultime risposte per host.
- **Collezioni:** richieste salvate in collezioni, legate a un host o a un gruppo di host. Ogni host ha le sue variabili (porta, token), così la stessa collezione serve web-01, web-02 e web-03.
- **Verifiche per i test:** condizioni senza scrivere codice, come stato uguale a 200, corpo che contiene un testo, campo JSON uguale a un valore, tempo sotto N ms. "Esegui la collezione" o "Esegui su tutto il gruppo" mostrano una tabella host × richiesta con l'esito di ognuna.
- **Import:** collezioni Postman v2.1 (JSON) e comandi `curl` incollati.
- **Dove sta:** è un tipo di scheda come il terminale e l'SFTP. Si apre dal menu dell'host o dal "+", e può stare in una scheda divisa accanto a un terminale con i log.

**Piano di lavoro** (29 ottobre 2026, fatto: vedi Stato):

1. **Tunnel nel nucleo SSH:** un forward locale sulla connessione dell'host, lo stesso che servirà al port forwarding della Fase 8. Per provarlo, ai server di test va aggiunto un piccolo servizio HTTP raggiungibile solo dall'interno della macchina, anche dietro il bastion.
2. **Motore delle richieste:** OkHttp, che gira su Android e desktop, con il tunnel come presa di rete, così HTTPS verifica il nome giusto. Variabili `{{nome}}`; risposta con stato, tempi e dimensione.
3. **La scheda "Richieste":** un nuovo tipo di scheda, nella split view accanto a un terminale.
4. **Collezioni salvate:** variabili per host, token nel vault, cronologia delle ultime risposte.
5. **Verifiche ed esecuzione su un gruppo:** la tabella host × richiesta (il criterio della fase).
6. **Rifiniture:** import da Postman e da `curl`, "Copia come curl", "Esegui con curl sul server".

**Scelte fatte:** collezioni indipendenti dagli host con variabili per host; tunnel di default, con curl sul server come opzione per richiesta; tutta la fase insieme, 5a e 5b.

## Funzioni extra

| Funzione | Come | Fase |
|---|---|---|
| Snippet | Comandi salvati con variabili, da lanciare su uno o più host | 2 |
| Broadcast input | Scrivi in più terminali insieme (tab scelte o un gruppo intero), con indicatore ben visibile | 2 |
| Editor remoto | Editor integrato con evidenziazione per yaml, json, sh, conf. Salvataggio atomico (file temporaneo + rename), controllo di modifiche remote prima di sovrascrivere. Sul desktop anche "apri con editor esterno" e upload a ogni salvataggio. | 3 |
| Port forwarding | Locale, remoto e dinamico (SOCKS5). Regole salvate per host, avvio automatico alla connessione. | 8 |
| Monitor risorse | Canale exec sulla connessione esistente: legge `/proc/loadavg`, `/proc/meminfo`, `/proc/stat` e `df` ogni pochi secondi, solo con il pannello aperto. Nessun agente da installare. | 8 |
| Pannello Docker | `docker ps` / `docker stats` con output JSON, log in streaming, start / stop / restart, `docker exec` che apre un nuovo terminale. L'utente SSH deve poter usare docker. | 8 |
| Registrazione sessioni | Formato asciicast v2 con replay nell'app ed export. File locali, con durata di conservazione configurabile. | 8 |
| Mosh | Nessuna libreria JVM disponibile e niente mosh-client nativo su Windows: va scritto in Kotlin (bootstrap via SSH con `mosh-server`, UDP, AES-128-OCB, protobuf, sincronizzazione dello stato). Servono `mosh-server` sugli host e le porte UDP aperte. È la funzione più costosa e rischiosa. | 9 |

## Import da Termius

Termius non ha un formato di export documentato pubblicamente. L'importer prova queste strade:

1. Export nativo di Termius. La pagina GitHub di Termius cita JSON, CSV e OpenSSH config. **Da verificare nell'app installata:** quale export c'è e cosa contiene. Con un file di esempio a dati finti scrivo il parser.
2. OpenSSH config: host, porta, utente, `IdentityFile`, `ProxyJump`. Serve comunque per `~/.ssh/config`.
3. CSV con le colonne del template di Termius: label, indirizzo, porta, gruppo, sottogruppo, tag.
4. Chiavi: import dal file (per esempio da `~/.ssh`) se l'export non le include.

Password e chiavi private probabilmente non escono dall'export. Dopo l'import una procedura guidata elenca gli host senza credenziali e permette di assegnare identità e chiavi a più host insieme. Prima di confermare c'è un'anteprima con gestione dei duplicati e i gruppi vengono mantenuti.

## Stile "Carta"

- Token di design (colori, font, spaziature, raggi) in un unico tema Compose: si può aggiungere una variante scura senza toccare i componenti.
- Il tema colori del terminale è separato da quello dell'app.
- Font monospace inclusi nell'app (per esempio JetBrains Mono) per avere lo stesso rendering ovunque.
- Layout desktop: sidebar degli host, tab in alto, area centrale divisibile, barra di stato con connessioni e trasferimenti attivi. Layout Android: elenco host, terminale a schermo intero, SFTP con pannello dei trasferimenti in basso.

## Stato

- Fase 0: completata il 29 ottobre 2026.
- Fase 1: implementata il 29 ottobre 2026. Il criterio "tutte le 48 macchine raggiungibili" va verificato con i tuoi host veri. Restano da provare a mano:
  - la digitazione nella finestra desktop;
  - il primo uso del portachiavi di sistema;
  - le chiavi PuTTY.
- Fase 2: implementata il 29 ottobre 2026 ([decisione 0006](decisions/0006-sessions-and-reconnect.md)).
  - Le schede di un host condividono una connessione.
  - Split view a 1, 2 o 4 riquadri ridimensionabili.
  - Riconnessione automatica nello stesso terminale.
  - Broadcast su schede scelte o su un gruppo.
  - Snippet con variabili `{{nome}}`, da lanciare nella sessione attiva, in più schede o su nuovi host.
  - Foreground Service su Android.
  - Il soak test con 10 sessioni gira per 20 secondi a ogni esecuzione dei test; la prova di ore va lanciata a parte con `-PsoakSeconds`.
  - Da provare su un telefono vero: Doze a schermo spento e cambio di rete.
- Fase 3: implementata il 29 ottobre 2026 ([decisione 0007](decisions/0007-files-and-transfers.md)).
  - Pannelli file (locale e remoti) come schede, anche affiancati nella split view.
  - Una coda unica di trasferimenti: locale ↔ remoto e remoto ↔ remoto in streaming, limite di trasferimenti paralleli in totale e per host.
  - Pausa e ripresa dall'offset; quattro scelte sui conflitti; cartelle ricorsive; attesa automatica se la connessione cade.
  - Operazioni: rinomina, elimina, nuova cartella, permessi, file nascosti, ordinamento, segnalibri per host.
  - Copia trascinando tra pannelli o con "Copia in…"; sul desktop anche trascinando dal Finder o da Esplora risorse.
  - Editor remoto con evidenziazione, salvataggio atomico che mantiene i permessi e avviso se il file è cambiato sul server.
  - Criterio raggiunto: `TransferFlowTest` copia in parallelo tra 3 host e il computer locale in una sola coda.
  - Restano da fare:
    - "Apri con editor esterno";
    - il trascinamento dall'app al Finder;
    - su Android, il pannello locale fuori dalla cartella dell'app (selettore di sistema).
- Fase 4: implementata il 29 ottobre 2026 ([decisione 0008](decisions/0008-tabs-groups-and-layouts.md)).
  - Ogni scheda ha la sua divisione in riquadri.
  - Unire due schede trascinandone una sull'altra (sinistra, destra, sopra, sotto) o dal menu della scheda; separarle; spostare un riquadro in una scheda nuova.
  - Gruppi di schede con nome e colore, comprimibili; raggruppa per host; dal gruppo broadcast, snippet, "Apri in griglia" e chiudi.
  - Spazi di lavoro salvati per nome, anche di un solo gruppo; l'ultima sessione è offerta di nuovo all'avvio.
  - Criterio raggiunto: `TabLayoutFlowTest` salva due gruppi e una scheda divisa, riavvia l'app e li ritrova uguali, con le sessioni ricollegate.
- Fase 5: completata il 29 ottobre 2026 ([decisione 0010](decisions/0010-http-requests.md)).
  - Tunnel nel nucleo SSH (`forwardLocal`), anche dietro un jump host; ai server di test si è aggiunto un servizio HTTP raggiungibile solo dall'interno.
  - Scheda "Richieste" dal menu dell'host: metodo, URL, header, corpo (JSON, form, testo), auth (Bearer, Basic, API key) con i segreti nel vault, variabili `{{nome}}` per host.
  - Form multipart con campi di testo e file (percorsi sul desktop, documenti scelti su Android).
  - Risposta con stato, tempi, dimensione, header e corpo formattato con ricerca; ultime 10 risposte per host, solo in memoria.
  - "Esegui con curl sul server" e "Copia come curl".
  - Verifiche senza codice ed esecuzione della collezione su un host o su un gruppo, con la tabella host × richiesta.
  - Import da Postman v2.1 e da comandi curl.
  - Criterio raggiunto: `HttpRunFlowTest` esegue una collezione di verifiche su tutti gli host di un gruppo, uno dietro il bastion, e mostra l'esito per host.
  - Punti aperti:
    - da provare a mano: le finestre della scheda (variabili, esecuzione su un gruppo, import) e l'editor dei campi multipart nell'app vera;
    - da provare su un telefono: la scelta di un file per un form multipart su Android.
- Fase 6: implementata il 29 ottobre 2026 ([decisione 0009](decisions/0009-first-run-themes-and-termius.md)).
  - Benvenuto con "Usi Termius?" e import diretto dai dati locali di Termius (LevelDB e V8, campi decifrati con la chiave del portachiavi), tutorial in sei passi.
  - Temi Carta, Notte (blu notte), Nord, Solarized chiaro, Dracula e Tokyo Night, per app e terminale, scelti in Impostazioni o dal sistema.
  - Avvisi a fine trasferimento, dentro l'app e come notifiche di sistema; conferma prima di chiudere con trasferimenti in corso.
  - File trascinati su un terminale caricati nella cartella della shell.
  - Controllo di design di spaziature, padding e stile su tutte le schermate.
  - Icona dell'app a scelta in Impostazioni: El Bigote (quella del pacchetto) o Píxel. Sul desktop cambia finestra e Dock mentre l'app gira; su Android il launcher mostra El Bigote.
  - Criterio da verificare sul tuo Mac: il lettore è provato su dati costruiti nel formato di Termius, non ancora su un Termius vero.
- Fase 8: fatta il 30 ottobre 2026.
  - Port forwarding fatto ([decisione 0011](decisions/0011-port-forwarding.md)): regole locali, remote e dinamiche (SOCKS) salvate per host, avvio a mano o automatico alla connessione, riaperte dopo una riconnessione. Provato contro i server di test da `PortForwardFlowTest`.
  - Monitor risorse fatto ([decisione 0012](decisions/0012-resource-monitor.md)): scheda "Risorse" dal menu dell'host con CPU, carico, memoria e swap, rete e dischi, letti da `/proc` e `df` ogni 3 secondi solo con la scheda aperta. Provato da `MonitorFlowTest`.
  - Pannello Docker fatto ([decisione 0013](decisions/0013-docker-panel.md)): scheda "Docker" dal menu dell'host con i container, CPU e memoria, avvio, stop e riavvio; log e shell nel container si aprono in un terminale nuovo. Provato da `DockerFlowTest` contro un finto `docker` nei server di test.
  - Registrazione sessioni fatta ([decisione 0014](decisions/0014-session-recording.md)): file asciicast v2 con il solo output del terminale, avviata dal menu della scheda o sempre dalle Impostazioni, pallino rosso mentre registra, schermata Registrazioni con riproduzione a velocità scelta, esportazione e pulizia dopo 30 giorni. Provata da `RecordingFlowTest`.
  - Nessuna delle quattro funzioni è ancora stata provata a mano nell'app o sul telefono.
- Fase 7: fatta il 30 ottobre 2026 ([progetto](superpowers/specs/2026-10-30-fase7-e-punti-aperti-design.md)), insieme ai punti aperti delle fasi precedenti. YubiKey e FIDO2 sono usciti dal piano.
  - Backup cifrato fatto ([decisione 0015](decisions/0015-encrypted-backup.md)): un file con workspace e segreti, password con Argon2id, ripristino con anteprima, "Unisci" o "Sostituisci tutto", copia di sicurezza prima di cambiare i dati. Provato da `BackupFlowTest`.
  - Master password dove manca il portachiavi fatta ([decisione 0004](decisions/0004-workspace-and-vault.md)). Provata da `PasswordMasterKeyProviderTest`; la finestra all'avvio va provata su un Linux senza Secret Service.
  - CA per i certificati host fatta ([decisione 0016](decisions/0016-host-certificate-authorities.md)): elenco nelle Impostazioni, import da `known_hosts`, blocco netto se il certificato non è valido. Provata da `HostCaTrustTest` e `ConnectionFlowTest`, anche con un server dal certificato scaduto.
  - SSH agent e forwarding fatti ([decisione 0017](decisions/0017-ssh-agent.md)): spunta nell'identità, forwarding per host spento di default. Provati da `AgentLoginTest` contro un `ssh-agent` vero; su Windows vanno provati a mano.
  - Blocco biometrico fatto ([decisione 0018](decisions/0018-biometric-lock.md)): impronta o password del dispositivo all'avvio e dopo un'assenza. Blocca l'interfaccia, non il vault. Logica provata da `AppLockTest`; Touch ID e BiometricPrompt vanno provati a mano.
  - Punti aperti chiusi: redirect e certificati HTTPS accettati a mano; indirizzo di ascolto dei forward ed elenco globale dei forward attivi; "Apri con editor esterno" e trascinamento verso il Finder o Esplora risorse; selettore di sistema su Android; processi più pesanti nel monitor; Docker con rimozione, immagini, volumi, reti e Compose; riproduzione che segue i ridimensionamenti; icona del launcher su Android.
  - Rifiniture dopo la revisione finale: il ripristino distingue un backup di una versione più nuova da una password sbagliata e l'anteprima conta le CA e i certificati che farebbe fidare; l'ultima copia di sicurezza si salva dalle Impostazioni; Pageant si trova elencando le pipe; i file che il sistema eseguirebbe non si aprono con "Apri con editor esterno"; un trascinamento bloccato finisce dopo 5 minuti con un messaggio; le destinazioni scelte da Android ripartono da zero; i certificati CA firmati con SHA-1 non valgono; con il blocco attivo le app recenti non mostrano il contenuto.
  - Da provare a mano: Touch ID e BiometricPrompt su hardware vero; Pageant e named pipe su Windows; la finestra della master password su un Linux senza Secret Service; il selettore di sistema e l'icona su un telefono; "Apri con editor esterno" e il trascinamento verso il Finder, dall'icona della riga. La vista del terminale può ridimensionare una riproduzione se cambia il riquadro.

## Roadmap

| Fase | Contenuto | Fatto quando |
|---|---|---|
| 0. Fondamenta | Progetto KMP (Android + desktop), git, version catalog. Ambiente di test docker-compose con server OpenSSH per ogni metodo di autenticazione. Spike libreria SSH e spike terminale. Tema Carta. | Decisioni scritte in `docs/decisions/` sulla base di prove vere |
| 1. MVP SSH | Host, gruppi, tag, ricerca, preferiti, identità. Password, chiavi, OTP, jump host. Verifica host key. Un terminale completo. Vault base. Import da Termius e da `~/.ssh/config`. | Tutte le 48 macchine raggiungibili dall'app |
| 2. Multi-sessione SSH | `SessionManager`, tab, split view, riconnessione, Foreground Service. Broadcast input, snippet. | 10 sessioni stabili in parallelo per ore |
| 3. SFTP multi-sessione | Workspace a N pannelli, `TransferManager`, remoto ↔ remoto, pausa e ripresa. Editor remoto. | Trasferimenti paralleli tra 3 o più host in un'unica coda |
| 4. Schede unite e gruppi | Disposizione dei riquadri per scheda, unione e separazione trascinando, gruppi di schede con nome e colore, raggruppa per host, azioni sul gruppo, spazi di lavoro salvati | Uno spazio di lavoro con due gruppi e una scheda divisa si riapre uguale dopo un riavvio |
| 5. Richieste HTTP ✓ | Scheda di richieste per host via tunnel SSH, risposta formattata, collezioni con variabili per host, verifiche, esecuzione su un gruppo, import da Postman e curl | Una collezione di verifiche gira su tutti gli host di un gruppo e mostra l'esito per host |
| 6. Primo avvio, temi e avvisi | Benvenuto con ricerca dei dati di Termius, tema scuro "Notte" e temi iconici, notifiche dei trasferimenti, file trascinati sul terminale | Al primo avvio su un Mac con Termius gli host si importano senza export manuale |
| 7. Autenticazione avanzata ✓ | SSH agent e forwarding, certificati, biometria, backup cifrato | Tutti i metodi passano nell'ambiente di test |
| 8. Strumenti ✓ | Port forwarding, monitor risorse, pannello Docker, registrazione sessioni | Ogni strumento provato contro i server di test |
| 9. Mosh | Client Mosh in Kotlin | Sessione che sopravvive a un cambio di rete |

## Test

- Integrazione su JVM con Testcontainers: un container OpenSSH per password, Ed25519, RSA, keyboard-interactive via PAM, certificato con CA, bastion.
- Toxiproxy per simulare cadute di rete e verificare la riconnessione.
- Terminale: snapshot test (output registrato di vim e htop confrontato con lo schermo atteso) e vttest a mano.
- SFTP: file grandi (1 GB e oltre), molti file piccoli, interruzione e ripresa.

## Rischi

1. Qualità del terminale: è la differenza che si nota subito rispetto a Termius. Va provato con programmi reali fin dalla fase 0.
2. Mosh: protocollo da implementare da zero. Resta l'ultima fase, ed è separabile se non vale lo sforzo.
3. Libreria SSH giovane (se vince cbssh): l'interfaccia `core/ssh` permette di sostituirla.

## Da verificare

- [ ] La lettura diretta dei dati di Termius sul tuo Mac (Importa → "Da Termius su questo computer"). Se non riesce, i "Dettagli tecnici" elencano solo nomi di store e campi, utili per adattare il lettore.
- [ ] Quale export offre il Termius installato. Termius non documenta un formato di export; l'importer CSV usa le colonne dei tool di export della community (`Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password`). Serve un file di esempio con dati finti.
- [x] Esito dello spike sulla libreria SSH: Apache MINA SSHD ([decisione 0001](decisions/0001-ssh-library.md))
- [x] Esito dello spike sul terminale: kmp-terminal-emulator dietro `core/terminal` ([decisione 0002](decisions/0002-terminal-emulator.md))
- [ ] Fase 5 nell'app vera: finestre della scheda Richieste, campi multipart, file scelto su Android

## Riferimenti

- connectbot/cbssh: https://github.com/connectbot/cbssh
- kmp-terminal-emulator: https://klibs.io/project/JohNan/kmp-terminal-emulator
- Termius su GitHub: https://github.com/Termius-SSH
- Termius, import da CSV: https://termius.com/documentation/importing-from-csv
