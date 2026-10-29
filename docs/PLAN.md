# El Termino: piano di progetto

Ultimo aggiornamento: 29 ottobre 2026

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
| Autenticazione | Tutti i metodi: password, chiavi Ed25519 / RSA / ECDSA anche con passphrase, OTP, jump host, SSH agent, certificati OpenSSH, YubiKey / FIDO2. |
| Funzioni extra | Port forwarding, snippet, broadcast input, monitor risorse, editor remoto, pannello Docker, registrazione sessioni, Mosh. |

La conseguenza più importante: tutte e quattro le piattaforme girano su JVM (ART su Android, JVM sul desktop). Basta quindi una sola implementazione SSH, senza libssh2 e senza cinterop.

## Architettura

```
app-android        entry point Android (Activity, Foreground Service)
app-desktop        entry point desktop (finestra, menu, tray)
shared             UI Compose, tema Carta, navigazione, feature
  └─ feature/      hosts · terminal · sftp · keys · import · snippets
                   forwarding · monitor · docker · recordings · settings
core/ssh           interfaccia comune + implementazione JVM
core/terminal      emulatore VT/xterm + renderer Compose Canvas
core/vault         segreti cifrati, Keystore / keychain di sistema
core/data          database: host, gruppi, identità, chiavi, known_hosts, snippet
core/mosh          client Mosh (fase 6)
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
| SSH agent | macOS/Linux con `SSH_AUTH_SOCK` (socket Unix di JDK 16+), Windows con la named pipe di OpenSSH e Pageant. Più l'agent forwarding. | 4 |
| Certificati OpenSSH | Chiave + `-cert.pub`, host certificate con CA fidata | 4 |
| YubiKey / FIDO2 | Desktop: tramite l'agent di sistema, poi libfido2 diretto se serve. Android: YubiKit via USB/NFC, da verificare FIDO2 o PIV. | 4 |
| Sblocco biometrico | BiometricPrompt su Android, Touch ID su macOS | 4 |

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

## Funzioni extra

| Funzione | Come | Fase |
|---|---|---|
| Snippet | Comandi salvati con variabili, da lanciare su uno o più host | 2 |
| Broadcast input | Scrivi in più terminali insieme (tab scelte o un gruppo intero), con indicatore ben visibile | 2 |
| Editor remoto | Editor integrato con evidenziazione per yaml, json, sh, conf. Salvataggio atomico (file temporaneo + rename), controllo di modifiche remote prima di sovrascrivere. Sul desktop anche "apri con editor esterno" e upload a ogni salvataggio. | 3 |
| Port forwarding | Locale, remoto e dinamico (SOCKS5). Regole salvate per host, avvio automatico alla connessione. | 5 |
| Monitor risorse | Canale exec sulla connessione esistente: legge `/proc/loadavg`, `/proc/meminfo`, `/proc/stat` e `df` ogni pochi secondi, solo con il pannello aperto. Nessun agente da installare. | 5 |
| Pannello Docker | `docker ps` / `docker stats` con output JSON, log in streaming, start / stop / restart, `docker exec` che apre un nuovo terminale. L'utente SSH deve poter usare docker. | 5 |
| Registrazione sessioni | Formato asciicast v2 con replay nell'app ed export. File locali, con durata di conservazione configurabile. | 5 |
| Mosh | Nessuna libreria JVM disponibile e niente mosh-client nativo su Windows: va scritto in Kotlin (bootstrap via SSH con `mosh-server`, UDP, AES-128-OCB, protobuf, sincronizzazione dello stato). Servono `mosh-server` sugli host e le porte UDP aperte. È la funzione più costosa e rischiosa. | 6 |

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
- Fase 1: implementata il 29 ottobre 2026; il criterio "tutte le 48 macchine raggiungibili" va verificato con i tuoi host veri. Restano da provare a mano la digitazione nella finestra desktop, il primo uso del portachiavi di sistema e le chiavi PuTTY.

## Roadmap

| Fase | Contenuto | Fatto quando |
|---|---|---|
| 0. Fondamenta | Progetto KMP (Android + desktop), git, version catalog. Ambiente di test docker-compose con server OpenSSH per ogni metodo di autenticazione. Spike libreria SSH e spike terminale. Tema Carta. | Decisioni scritte in `docs/decisions/` sulla base di prove vere |
| 1. MVP SSH | Host, gruppi, tag, ricerca, preferiti, identità. Password, chiavi, OTP, jump host. Verifica host key. Un terminale completo. Vault base. Import da Termius e da `~/.ssh/config`. | Tutte le 48 macchine raggiungibili dall'app |
| 2. Multi-sessione SSH | `SessionManager`, tab, split view, riconnessione, Foreground Service. Broadcast input, snippet. | 10 sessioni stabili in parallelo per ore |
| 3. SFTP multi-sessione | Workspace a N pannelli, `TransferManager`, remoto ↔ remoto, pausa e ripresa. Editor remoto. | Trasferimenti paralleli tra 3 o più host in un'unica coda |
| 4. Autenticazione avanzata | SSH agent e forwarding, certificati, YubiKey / FIDO2, biometria, backup cifrato | Tutti i metodi passano nell'ambiente di test |
| 5. Strumenti | Port forwarding, monitor risorse, pannello Docker, registrazione sessioni | |
| 6. Mosh | Client Mosh in Kotlin | Sessione che sopravvive a un cambio di rete |

## Test

- Integrazione su JVM con Testcontainers: un container OpenSSH per password, Ed25519, RSA, keyboard-interactive via PAM, certificato con CA, bastion.
- Toxiproxy per simulare cadute di rete e verificare la riconnessione.
- Terminale: snapshot test (output registrato di vim e htop confrontato con lo schermo atteso) e vttest a mano.
- SFTP: file grandi (1 GB e oltre), molti file piccoli, interruzione e ripresa.

## Rischi

1. Qualità del terminale: è la differenza che si nota subito rispetto a Termius. Va provato con programmi reali fin dalla fase 0.
2. FIDO2 su Android: dipende da cosa supporta YubiKit e da come la libreria SSH accetta firme esterne. Va verificato nello spike.
3. Mosh: protocollo da implementare da zero. Resta l'ultima fase, ed è separabile se non vale lo sforzo.
4. Libreria SSH giovane (se vince cbssh): l'interfaccia `core/ssh` permette di sostituirla.

## Da verificare

- [ ] Quale export offre il Termius installato. Termius non documenta un formato di export; l'importer CSV usa le colonne dei tool di export della community (`Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password`). Serve un file di esempio con dati finti.
- [x] Esito dello spike sulla libreria SSH: Apache MINA SSHD ([decisione 0001](decisions/0001-ssh-library.md))
- [x] Esito dello spike sul terminale: kmp-terminal-emulator dietro `core/terminal` ([decisione 0002](decisions/0002-terminal-emulator.md))
- [ ] YubiKey su Android: FIDO2 o PIV

## Riferimenti

- connectbot/cbssh: https://github.com/connectbot/cbssh
- kmp-terminal-emulator: https://klibs.io/project/JohNan/kmp-terminal-emulator
- Termius su GitHub: https://github.com/Termius-SSH
- Termius, import da CSV: https://termius.com/documentation/importing-from-csv
