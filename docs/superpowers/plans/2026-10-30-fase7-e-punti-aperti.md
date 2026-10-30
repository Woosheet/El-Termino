# Fase 7 e punti aperti: piano di lavoro

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backup cifrato, master password, CA per i certificati host, SSH agent e forwarding, blocco biometrico e tutti i punti aperti delle fasi 3-8.

**Architecture:** Ogni blocco è indipendente. La crittografia nuova sta in `core/vault`, i modelli in `core/data`, il protocollo in `core/ssh`, la logica d'app in `shared/.../app`, le differenze di piattaforma dietro `Platform`. Niente moduli nuovi.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Apache MINA SSHD 2.20.1, BouncyCastle 1.86, OkHttp 5.3.2, kotlinx.serialization. Unica dipendenza nuova: `androidx.biometric`.

**Spec:** `docs/superpowers/specs/2026-10-30-fase7-e-punti-aperti-design.md`

## Global Constraints

- Lavorare sul ramo `fase-7`, creato da `main` dopo un `git pull --ff-only`. Non fare push senza richiesta.
- Messaggi di commit in italiano, stile `Fase 7: …`, che terminano con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Testi dell'interfaccia in italiano; codice e commenti in inglese, come il resto del repo.
- Campi nuovi di `Workspace` con un default, senza cambiare `CURRENT_VERSION` (decisione 0004).
- Segreti mai in chiaro su disco o nei log; `ByteArray` azzerati dopo l'uso.
- Mai sostituire una chiave master che apre un vault esistente.
- Nessuna dipendenza nuova oltre `androidx.biometric`.
- Una decisione nuova in `docs/decisions/` per ogni blocco (0015 in poi) e voce aggiornata in `docs/PLAN.md` nello stesso commit dell'ultima attività del blocco.
- Test desktop: `./gradlew :<modulo>:desktopTest --tests '<Classe>'`. I test di flusso richiedono Docker per `testenv`. Prima dell'ultimo commit di ogni blocco, `./gradlew :shared:desktopTest :core:ssh:desktopTest :core:vault:desktopTest :core:data:desktopTest`.

## Review Focus

- Ripristino interrotto a metà (crash o disco pieno tra vault e workspace): i dati restano quelli di prima o quelli del backup, mai un misto. Test in Task 3.
- Backup di una versione più nuova dell'app: rifiutato con un messaggio, nessuna modifica. Test in Task 3.
- Jump host con CA o con agent: ogni salto ha i suoi controlli e il suo metodo. Test in Task 7 e Task 10.
- App bloccata dalla biometria con una sessione o un trasferimento in corso: continuano sotto il blocco, e nessun prompt di segreti compare sopra il blocco. Test in Task 12.
- Host key di una CA rimossa: l'host torna alla fiducia al primo uso senza avvisi falsi di chiave cambiata. Test in Task 7.

---

## Blocco G: documenti

### Task 1: Piano e README allineati alla realtà

**Files:**
- Modify: `docs/PLAN.md`, `README.md`

- [ ] **Step 1:** Crea il ramo `fase-7` e includi nel primo commit `docs/superpowers/`.
- [ ] **Step 2:** In `README.md`, "A che punto è": fasi 0-6 e 8 fatte; prossime Fase 7 e Mosh. In `docs/PLAN.md`: data 30 ottobre 2026, Autenticazione senza la riga YubiKey, Funzioni e Roadmap senza YubiKey/FIDO2, tolto il rischio 2, tolta "YubiKey su Android: FIDO2 o PIV" da "Da verificare".
- [ ] **Step 3:** Verifica: `grep -n -iE 'yubikey|fido' docs/PLAN.md README.md` non restituisce righe.
- [ ] **Step 4:** Commit `Piano: Fase 8 fatta, YubiKey fuori dal progetto`.

## Blocco A: backup cifrato

### Task 2: Formato del file di backup

**Files:**
- Create: `core/vault/src/jvmSharedMain/kotlin/dev/eltermino/core/vault/BackupCipher.kt`
- Test: `core/vault/src/desktopTest/kotlin/dev/eltermino/core/vault/BackupCipherTest.kt`

**Interfaces:**
- Produces: `object BackupCipher { fun seal(plain: ByteArray, password: CharArray): ByteArray; fun open(file: ByteArray, password: CharArray): ByteArray }`. `open` lancia `VaultException` con password sbagliata, file alterato, formato sconosciuto.

- [ ] **Step 1: Test che falliscono:** `roundTrip`, `wrongPasswordThrows`, `everyFlippedByteThrows` (byte alterato in intestazione, nonce e corpo), `unknownFormatVersionThrows`, `sealTwiceDiffers`.
- [ ] **Step 2:** Esegui `./gradlew :core:vault:desktopTest --tests '*BackupCipherTest'`. Atteso: FAIL, `BackupCipher` non esiste.
- [ ] **Step 3: Implementa.** Formato: `"ETB" | versione(1) | m KiB (int) | t (int) | p (int) | salt(16) | nonce(12) | ciphertext+tag`. Argon2id con `org.bouncycastle.crypto.generators.Argon2BytesGenerator`, m=65536, t=3, p=1, chiave 32 byte. AES-256-GCM con tutta l'intestazione come AAD. In `open`, rifiuta m oltre 1048576, t oltre 16, p oltre 8: l'intestazione viene da un file non fidato. Se BouncyCastle non è ancora dipendenza di `core/vault`, aggiungi `libs.bouncycastle.prov`.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Fase 7: formato cifrato del backup`.

### Task 3: Contenuto, unione e ripristino

**Files:**
- Modify: `core/vault/.../Vault.kt`, `EncryptedFileVault.kt` e ogni altra implementazione di `Vault` (cerca `: Vault`, anche nei test)
- Create: `shared/src/commonMain/kotlin/dev/eltermino/app/Backup.kt`
- Test: `shared/src/desktopTest/kotlin/dev/eltermino/app/BackupFlowTest.kt`

**Interfaces:**
- Consumes: `BackupCipher` (dietro due funzioni `expect` in `shared`, oppure iniettato)
- Produces:
  - `Vault.snapshot(): Map<String, ByteArray>` e `Vault.restore(entries: Map<String, ByteArray>, replace: Boolean)`; `restore` scrive in un solo passo atomico.
  - `@Serializable class BackupContent(val formatVersion: Int, val appVersion: String?, val workspace: Workspace, val secrets: Map<String, String /* base64 */>)`
  - `class BackupPreview(hosts: Int, identities: Int, keys: Int, snippets: Int, collections: Int, layouts: Int, knownHostConflicts: List<KnownHost>)`
  - `class BackupService(store: WorkspaceStore, vault: Vault, safetyDir: suspend (ByteArray) -> Unit)`, con `suspend fun export(password: CharArray): ByteArray`, `suspend fun read(file: ByteArray, password: CharArray): BackupContent`, `fun preview(content: BackupContent): BackupPreview`, `suspend fun restore(content: BackupContent, replaceAll: Boolean)`.
  - `fun Workspace.mergedWith(backup: Workspace): Workspace`

- [ ] **Step 1: Test che falliscono:**
  - `exportLeavesOutDeviceSettings`: `ui` e `lastSession` non sono nel JSON esportato.
  - `mergeBackupWinsOnSameId` e `mergeKeepsLocalOnlyItems`.
  - `mergeKeepsLocalKnownHostOnConflict`: stessa terna host, porta, tipo con chiave diversa → resta la locale e compare in `knownHostConflicts`; le voci nuove si aggiungono.
  - `replaceKeepsDeviceSettings`: `ui` e `lastSession` locali intatti dopo "sostituisci tutto".
  - `restoreSavesSafetyCopyFirst`: la copia è cifrata e arriva prima di ogni scrittura.
  - `wrongPasswordChangesNothing` e `newerFormatRefused` (`formatVersion` maggiore di quello corrente → `VaultException`, nessuna modifica).
  - `failureWhileWritingWorkspaceRestoresVault`: un `WorkspaceStore` che lancia durante `update` lascia il vault come prima.
- [ ] **Step 2:** Esegui `./gradlew :shared:desktopTest --tests '*BackupFlowTest'`. Atteso: FAIL.
- [ ] **Step 3: Implementa.** `restore` in quest'ordine: copia di sicurezza, `vault.restore`, `store.update`; se `update` fallisce, rimetti lo snapshot del vault. In "unisci" i segreti entrano con lo stesso id di prima. In "sostituisci" `hosts … httpCollections, savedLayouts` vengono dal backup, `ui` e `lastSession` restano locali. Per `lastSession` dopo "sostituisci": azzeralo solo se punta a host non più esistenti. La copia di sicurezza è un backup cifrato con una password generata e tenuta nel vault, così nessun dato va in chiaro.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Fase 7: esportazione e ripristino del backup`.

### Task 4: Backup nelle Impostazioni

**Files:**
- Modify: `shared/.../ui/SettingsScreen.kt`, `shared/.../app/AppServices.kt`, `app-desktop/.../DesktopPlatform.kt`, `app-android/.../AndroidPlatform.kt`
- Test: `shared/src/desktopTest/kotlin/dev/eltermino/app/AppSnapshotTest.kt`

**Interfaces:**
- Produces: in `Platform`, `suspend fun saveFile(title: String, suggestedName: String, bytes: ByteArray): Boolean = false`. Se l'esportazione delle registrazioni ha già una funzione equivalente, riusa quella invece di crearne una nuova.

- [ ] **Step 1: Test che fallisce:** `settingsBackupSection` in `AppSnapshotTest`: compaiono "Crea backup" e "Ripristina backup".
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Crea backup: password due volte, minimo 8 caratteri, avviso "se la perdi il backup non si apre", poi `saveFile` col nome `el-termino-AAAA-MM-GG.etbackup`. Ripristina: `pickFile`, password, `BackupPreview` con i conflitti, scelta tra "Unisci" e "Sostituisci tutto". "Sostituisci tutto" chiede conferma col bottone Danger. Errori in chiaro: password sbagliata o file danneggiato hanno lo stesso messaggio, perché GCM non li distingue.
- [ ] **Step 4:** Riesegui e fai girare `:shared:desktopTest`. Atteso: PASS.
- [ ] **Step 5:** Scrivi `docs/decisions/0015-encrypted-backup.md`, aggiorna `PLAN.md`, commit `Fase 7: backup e ripristino dalle Impostazioni`.

## Blocco B: master password

### Task 5: Ripiego con master password su desktop

**Files:**
- Create: `core/vault/src/desktopMain/kotlin/dev/eltermino/core/vault/PasswordMasterKeyProvider.kt`
- Modify: `core/vault/.../KeychainMasterKeyProvider.kt`, `app-desktop/.../Main.kt`
- Test: `core/vault/src/desktopTest/kotlin/dev/eltermino/core/vault/PasswordMasterKeyProviderTest.kt`

**Interfaces:**
- Produces: `class PasswordMasterKeyProvider(saltFile: File, ask: (firstTime: Boolean) -> CharArray?) : MasterKeyProvider`. `suspend` non serve: la lettura avviene su `Dispatchers.IO`; `ask` blocca finché una finestra Swing modale risponde.

- [ ] **Step 1: Test che falliscono:** `sameSaltAndPasswordGiveSameKey`, `missingSaltWithoutCreateThrows`, `cancelThrowsVaultException`, `wrongPasswordOpensNothing` (`EncryptedFileVault` lancia `VaultException` e non riscrive `vault.bin`), `saltFileHoldsNoKeyMaterial`.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Argon2id con gli stessi parametri del backup, salt di 16 byte in `vault.salt`. In `Main.kt`: si usa il portachiavi se risponde; si usa la password solo se manca Secret Service **e** `vault.salt` esiste o `vault.bin` non esiste. Se `vault.bin` esiste senza `vault.salt` e il portachiavi non risponde, resta l'errore di oggi: non si crea una chiave nuova. Tre tentativi, poi uscita con messaggio. Finestra Swing perché Compose non è ancora partito.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Aggiorna la decisione 0004 ("Limiti noti"), `PLAN.md`, commit `Fase 7: master password dove non c'è il portachiavi`.

## Blocco C: certificati host

### Task 6: Spike e verifica del certificato in `core/ssh`

**Files:**
- Modify: `core/ssh/.../ConnectTarget.kt`, `core/ssh/.../mina/MinaSupport.kt`, `MinaSshConnector.kt`
- Create: `core/ssh/src/commonMain/kotlin/dev/eltermino/core/ssh/HostCertificate.kt`
- Test: `core/ssh/src/desktopTest/.../mina/MinaSshConnectorTest.kt`

**Interfaces:**
- Produces:
  - `data class HostCertificateInfo(val caPublicKey: String, val caFingerprint: String, val principals: List<String>, val validAfterEpoch: Long, val validBeforeEpoch: Long, val signatureValid: Boolean)`
  - `HostKeyFingerprint.certificate: HostCertificateInfo? = null`; `keyType`, `sha256`, `publicKey` restano quelli della chiave certificata, come oggi.

- [ ] **Step 1: Spike.** Con `basic` e un certificato scaduto generato in `testenv/gen-keys.sh`, verifica che cosa fa MINA con `ABORT_ON_INVALID_CERTIFICATE`. L'obiettivo è arrivare sempre a `session.serverKey` e decidere noi. Annota l'esito nel commit.
- [ ] **Step 2: Test che falliscono:** `hostCertificateReachesVerifier` (CA, principal e date di `basic`), `plainKeyHasNoCertificateInfo`, `expiredCertificateStillReachesVerifier`.
- [ ] **Step 3:** Esegui `./gradlew :core:ssh:desktopTest --tests '*MinaSshConnectorTest'`. Atteso: FAIL.
- [ ] **Step 4: Implementa** in `toHostKeyFingerprint()`, leggendo da `OpenSshCertificate` `caPubKey`, `principals`, `validAfter`, `validBefore`, e verificando la firma con `signature`/`rawData` tramite `BuiltinSignatures`. La firma va controllata qui perché il rifiuto non dipende da MINA.
- [ ] **Step 5:** Riesegui. Atteso: PASS.
- [ ] **Step 6:** Commit `Fase 7: i dati del certificato host arrivano al verificatore`.

### Task 7: Regole di fiducia della CA

**Files:**
- Modify: `core/data/.../Workspace.kt`, `core/data/.../WorkspaceStore.kt`, il verificatore in `shared/.../app/Connections.kt` (cerca `HostKeyVerifier`), `shared/.../app/Prompts.kt`
- Test: `core/data/src/desktopTest/.../HostCaTrustTest.kt`, `shared/src/desktopTest/.../ConnectionFlowTest.kt`

**Interfaces:**
- Consumes: `HostCertificateInfo`
- Produces:
  - `Workspace.trustedCas: List<TrustedCa> = emptyList()`
  - `@Serializable data class TrustedCa(val id: String, val label: String, val publicKey: String, val fingerprintSha256: String, val patterns: List<String>)`
  - `sealed interface HostTrust { Accepted; AcceptedRenewed(previous: KnownHost); Refused(reason: String); AskFirstUse; AskChanged(previous: KnownHost) }`
  - `fun Workspace.hostTrust(host: String, port: Int, fingerprint: HostKeyFingerprint, nowEpoch: Long): HostTrust`
  - `fun hostPatternMatches(pattern: String, host: String, port: Int): Boolean`: `*` come in OpenSSH, `[host]:porta` per le porte non 22, `!` di negazione.

- [ ] **Step 1: Test che falliscono** in `HostCaTrustTest`:
  - `noCaBehavesAsToday` (`AskFirstUse`, `Accepted`, `AskChanged`)
  - `validCertificateAccepted`
  - `validCertificateDifferentPinIsRenewed` → `AcceptedRenewed`
  - `expiredRefused`, `notYetValidRefused`, `badSignatureRefused`, `wrongPrincipalRefused`
  - `unknownCaOnCoveredHostRefused`
  - `plainKeyOnCoveredHostRefusedUnlessPinned`
  - `removingCaFallsBackToFirstUse`
  - `patternsMatchLikeOpenSsh` (`*.example.com`, `[10.0.0.1]:2201`, porta sbagliata, `!`)
- [ ] **Step 2: Test di flusso** in `ConnectionFlowTest`: `caTrustedHostConnectsWithoutPrompt` su `basic` con `known_hosts_ca`, nessuna finestra di conferma; `caCheckedOnEveryHop` con un jump host il cui certificato è scaduto: `HostKeyRejected`.
- [ ] **Step 3:** Esegui. Atteso: FAIL.
- [ ] **Step 4: Implementa.** Il principal va confrontato con l'indirizzo dell'host. `Refused` non offre un bottone: chiude con `reason` e il suggerimento di controllare l'elenco delle CA. `AcceptedRenewed` sostituisce la voce con `trust(...)` e mostra un avviso nell'app. `AskChanged` resta la finestra di oggi.
- [ ] **Step 5:** Riesegui. Atteso: PASS.
- [ ] **Step 6:** Commit `Fase 7: fiducia nei certificati host firmati da una CA`.

### Task 8: Elenco delle CA e import

**Files:**
- Create: `shared/.../ui/TrustedCasScreen.kt` (o sezione di `SettingsScreen.kt`, se è più coerente)
- Modify: `shared/.../app/AppServices.kt` (`Platform`), `ImportService.kt`, `DesktopPlatform.kt`
- Test: `core/importer/src/commonTest/.../KnownHostsCaParserTest.kt`, `AppSnapshotTest.kt`

**Interfaces:**
- Produces: `fun parseCertAuthorities(knownHosts: String): List<ParsedCa>` con `ParsedCa(patterns, publicKey, comment)` in `core/importer`; `Platform.readKnownHosts(): String? = null`.

- [ ] **Step 1: Test che falliscono:** `readsCertAuthorityLines`, `ignoresPlainAndCommentLines`, `ignoresMalformedKey`, `skipsDuplicateCaOnImport`, `trustedCasScreen` (snapshot).
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa** l'elenco: nome, impronta, schemi, aggiungi (incolla la chiave pubblica), modifica, rimuovi con conferma; "Importa da known_hosts" sul desktop.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Scrivi `docs/decisions/0016-host-certificate-authorities.md`, aggiorna `PLAN.md`, commit `Fase 7: elenco delle CA fidate e import da known_hosts`.

## Blocco D: SSH agent

### Task 9: Client del protocollo agent

**Files:**
- Create: `core/ssh/src/desktopMain/kotlin/dev/eltermino/core/ssh/agent/AgentProtocol.kt`, `AgentTransport.kt`
- Test: `core/ssh/src/desktopTest/.../agent/AgentProtocolTest.kt`

**Interfaces:**
- Produces:
  - `interface AgentTransport : Closeable { fun exchange(request: ByteArray): ByteArray }`
  - `fun openSystemAgent(): AgentTransport?`: Unix con `SSH_AUTH_SOCK` via `java.net.UnixDomainSocketAddress`; Windows con `\\.\pipe\openssh-ssh-agent`, poi la pipe di Pageant, via `RandomAccessFile`. Null se non c'è.
  - `class AgentClient(open: () -> AgentTransport?)` con `fun identities(): List<AgentIdentity>` e `fun sign(publicKey: ByteArray, data: ByteArray, flags: Int): ByteArray`
  - `sealed class AgentException`: `Missing`, `Empty`, `Refused`

- [ ] **Step 1: Test che falliscono** con un agent finto in memoria: `listsIdentities` (`SSH_AGENTC_REQUEST_IDENTITIES`=11, risposta 12), `signsData` (13, risposta 14, flag `rsa-sha2-256`=2 e `rsa-sha2-512`=4), `failureReplyThrowsRefused` (5), `truncatedReplyThrows`, `oversizedLengthThrows` (oltre 256 KiB), `realOpensshAgent` (lancia `ssh-agent` e `ssh-add` con la chiave di `testenv`; `assumeTrue` se mancano).
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa** il framing: lunghezza a 4 byte, messaggio, stringhe SSH. Una connessione per richiesta.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Fase 7: client del protocollo SSH agent`.

### Task 10: Login con l'agent

**Files:**
- Create: `core/ssh/src/desktopMain/kotlin/dev/eltermino/core/ssh/agent/MinaAgent.kt`
- Modify: `MinaSshConnector.kt:65` e `:153`, `MinaPlatform.kt`, `core/data/.../Workspace.kt`, `ConnectionPlanner.kt`, `IdentitiesScreen.kt`
- Test: `core/ssh/src/desktopTest/.../mina/MinaSshConnectorTest.kt`, `shared/src/commonTest/.../ConnectionPlannerTest.kt`

**Interfaces:**
- Consumes: `AgentClient`
- Produces:
  - `Identity.useAgent: Boolean = false`
  - `MinaAgentFactory : SshAgentFactory`, con `SshAgent` limitato a `getIdentities` e `sign`
  - in `commonMain`, `expect`/`actual` o parametro di `MinaSshConnector`: `agentSupported: Boolean`, false su Android
  - `SshException.AuthFailed` col testo sull'agent vuoto o assente quando era stato provato

- [ ] **Step 1: Test che falliscono:** `agentLogin` (agent reale con la chiave di `testenv`); `agentOnlyOnHopsThatAskForIt`: con due salti, l'agent è usato solo sul salto con `AuthMethod.Agent`; `emptyAgentFallsThroughToPassword`; `missingAgentFailureSaysSo`; `plannerPutsAgentFirst`: ordine Agent, PublicKey, Password, KeyboardInteractive; `plannerSkipsAgentOnAndroid`.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** La factory è unica sul client MINA condiviso: decide per sessione con un attributo di sessione impostato in `authenticate`, altrimenti le chiavi dell'agent verrebbero offerte a ogni host. Togli il rifiuto a `:65` su desktop e lascialo su Android. In `IdentitiesScreen` la spunta "Usa l'SSH agent" compare solo se `platform.isDesktop`. Il planner chiede la password nello stesso modo di oggi quando non ci sono chiave né password salvata.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Fase 7: login con l'SSH agent`.

### Task 11: Agent forwarding

**Files:**
- Modify: `core/ssh/.../ConnectTarget.kt`, `SshConnection.kt`, `MinaSshConnection.kt`, `Workspace.kt` (`Host`), `ConnectionPlanner.kt`, `HostEditor.kt`
- Test: `core/ssh/src/desktopTest/.../mina/MinaSshConnectorTest.kt`

**Interfaces:**
- Produces: `ConnectTarget.agentForwarding: Boolean = false`, `Host.agentForwarding: Boolean = false`.

- [ ] **Step 1: Test che falliscono:** `forwardedAgentVisibleOnServer` (exec `ssh-add -l` in una shell mostra la chiave), `offByDefault` (`SSH_AUTH_SOCK` vuoto sul server).
- [ ] **Step 2:** Esegui. Atteso: FAIL. Se il server di test ha `AllowAgentForwarding no`, abilitalo in `testenv/config`.
- [ ] **Step 3: Implementa** con `ChannelShell.setAgentForwarding(true)` e l'accettazione dei canali `auth-agent@openssh.com` solo per le sessioni con l'opzione attiva. Vale per il solo host di destinazione, non per i salti. In `HostEditor`, spunta spenta di default col testo: "Chi è root sul server può usare le tue chiavi finché sei collegato".
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Scrivi `docs/decisions/0017-ssh-agent.md`, aggiorna `PLAN.md`, commit `Fase 7: agent forwarding`.

## Blocco E: biometria

### Task 12: Blocco dell'interfaccia

**Files:**
- Create: `shared/.../app/AppLock.kt`, `shared/.../ui/LockScreen.kt`
- Modify: `AppServices.kt`, `Workspace.kt` (`UiSettings`), `SettingsScreen.kt`, `App.kt`, `DesktopPlatform.kt`, `AndroidPlatform.kt`, `MainActivity.kt`, `app-android/build.gradle.kts`, `gradle/libs.versions.toml`
- Create: aiutante nativo macOS sotto `app-desktop/` (per esempio un piccolo eseguibile Swift che chiama `LocalAuthentication` ed esce con 0 se riuscito), incluso nel pacchetto
- Test: `shared/src/commonTest/.../AppLockTest.kt`, `AppSnapshotTest.kt`

**Interfaces:**
- Produces:
  - `Platform.biometricAvailable: Boolean = false`; `suspend fun authenticate(reason: String): Boolean = false`; `val inactiveFlow: StateFlow<Boolean>`, vero se nessuna finestra è attiva o l'app non è visibile.
  - `UiSettings.biometricLock: Boolean = false`, `UiSettings.lockAfterMinutes: Int = 5`
  - `class AppLock(platform: Platform, settings: StateFlow<UiSettings>, clock: () -> Long)` con `val locked: StateFlow<Boolean>`, `suspend fun unlock(): Boolean`.

- [ ] **Step 1: Test che falliscono:** `lockedAtStartWhenEnabled`, `offByDefault`, `locksAfterTimeoutInBackground`, `doesNotLockBeforeTimeout`, `shortFocusLossDoesNotLock`, `sleepCountsAsElapsedTime` (usa l'orologio di parete, non un timer), `failedAuthStaysLocked`, `optionHiddenWithoutBiometrics`, `sessionsKeepRunningUnderLock`, `promptsWaitUntilUnlocked`: una richiesta di passphrase non compare finché `locked` è vero.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** `LockScreen` copre tutta l'app sopra sessioni e prompt, senza fermare nulla. Android: `BiometricPrompt` con `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`. macOS: aiutante con `.deviceOwnerAuthentication`, che ripiega sulla password del sistema. `MainActivity` diventa `FragmentActivity`, se non lo è già. Windows e Linux: `biometricAvailable = false`, l'opzione non compare. Se l'opzione è attiva ma la biometria sparisce (aiutante mancante), l'app si apre con un avviso anziché chiudere fuori l'utente: la chiave del vault non dipende dal blocco.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Verifica che l'aiutante finisca nel pacchetto: `./gradlew :app-desktop:createDistributable` e controlla il `.app`. Compila il sorgente Swift in un task Gradle solo su macOS. Se `swiftc` manca, costruisci senza aiutante.
- [ ] **Step 6:** Scrivi `docs/decisions/0018-biometric-lock.md`, aggiorna `PLAN.md`, commit `Fase 7: blocco biometrico`.

## Blocco F: punti aperti

Ogni attività ha il suo commit, `Punti aperti: …`, e aggiorna la decisione del suo argomento ("Restano fuori") o `PLAN.md`.

### Task 13: HTTP, redirect e certificati non validi

**Files:**
- Modify: `shared/src/jvmSharedMain/.../app/HttpTunnel.jvm.kt`, `HttpRequests.kt`, `Workspace.kt` (`HttpRequest`, `Workspace`), `RequestsPane.kt`
- Test: `shared/src/desktopTest/.../HttpRequestFlowTest.kt`, `testenv/` (un endpoint di redirect e uno HTTPS autofirmato)

**Interfaces:**
- Produces:
  - `HttpRequest.followRedirects: Boolean = false`
  - `HttpResponse.redirectNotFollowed: String? = null`
  - `Workspace.trustedHttpCerts: List<TrustedHttpCert> = emptyList()`, con `TrustedHttpCert(host: String, port: Int, sha256: String)`
  - errore `UntrustedCertificate(host, port, sha256, subject, reason)`, da cui la scheda offre "Accetta questo certificato"

- [ ] **Step 1: Test che falliscono:** `followsSameHostRedirect`, `stopsAtOtherHostRedirect` (la risposta è il 302, con `redirectNotFollowed`), `stopsAfterTenRedirects`, `authHeaderNotLost` su stesso host, `selfSignedRefusedByDefault`, `acceptedCertificateWorks`, `changedCertificateAsksAgain`, `httpsHostnameStillChecked`: l'accettazione vale per impronta e nessun altro certificato passa.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Redirect: ciclo manuale, OkHttp resta `followRedirects(false)`, stesso host e stessa porta dopo aver risolto l'URL relativo, massimo 10. "Esegui con curl" e "Copia come curl": `-L --proto-redir =http,https` solo con l'opzione attiva; con curl non si limita l'host, e il testo dell'opzione lo dice. Certificati: `X509TrustManager` che delega a quello di sistema e, se fallisce, accetta solo un'impronta SHA-256 presente in `trustedHttpCerts` per quell'host e porta.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: redirect e certificati HTTPS accettati a mano`.

### Task 14: Port forwarding, indirizzo di ascolto ed elenco globale

**Files:**
- Modify: `SshConnection.kt`, `MinaSshConnection.kt`, `Workspace.kt`, `PortForwards.kt`, `PortForwardsDialog.kt`, `App.kt`
- Test: `shared/src/desktopTest/.../PortForwardFlowTest.kt`

**Interfaces:**
- Produces: `PortForwardRule.bindAddress: String = "127.0.0.1"`; parametro `bindAddress` in `forwardLocal`, `forwardRemote`, `forwardDynamic`, con lo stesso default; una schermata tra gli strumenti con tutti i forward attivi di ogni host e il pulsante Ferma.

- [ ] **Step 1: Test che falliscono:** `defaultStaysLoopback`, `localBindsChosenAddress`, `remoteUsesBindAddress`, `nonLoopbackShowsWarning`, `invalidAddressRefused`, `activeForwardsListsAllHosts`, `stopFromGlobalList`, `oldRulesLoadAsLoopback`.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Il `forwardRemote` su un indirizzo non loopback dipende da `GatewayPorts` del server: se il server non obbedisce, l'errore lo dice. Avviso: "La porta diventa raggiungibile da altri computer della rete".
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: indirizzo di ascolto dei forward ed elenco globale`.

### Task 15: Editor esterno e trascinamento verso il sistema

**Files:**
- Modify: `FilesPane.kt`, `FileBrowser.kt`, `AppServices.kt`, `DesktopPlatform.kt`
- Create: `shared/.../app/ExternalEditing.kt`
- Test: `shared/src/desktopTest/.../ExternalEditFlowTest.kt`

**Interfaces:**
- Produces:
  - `Platform.openExternally(path: String): Boolean = false`, con `java.awt.Desktop.open`
  - `Platform.watchFile(path: String, onChange: suspend () -> Unit): AutoCloseable?`, con `java.nio.file.WatchService`
  - `class ExternalEdit(remote: SftpClient, remotePath: String, …)`
  - costante `DRAG_OUT_LIMIT_BYTES = 200L * 1024 * 1024`

- [ ] **Step 1: Test che falliscono:** `savesUploadOnChange`, `remoteChangeAsksBeforeOverwrite` (stesso controllo di dimensione e data dell'editor integrato), `atomicSaveKeepsPermissions`, `tempFilePrivateAndRemovedOnClose` (permessi 0600), `editorSaveViaRenameStillSeen`, `dragOverLimitRefused`, `folderDragRefused`.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Riusa il salvataggio dell'editor remoto. Il trascinamento usa un `TransferHandler` AWT con `DataFlavor.javaFileListFlavor`: il download avviene su `getTransferData`, cioè al rilascio, e il limite si controlla sulle dimensioni già note a inizio trascinamento. Oltre il limite il trascinamento non parte e un messaggio rimanda a "Copia in…".
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: editor esterno e trascinamento verso il sistema`.

### Task 16: Selettore di sistema su Android

**Files:**
- Modify: `AndroidPlatform.kt`, `shared/.../app/FileSystems.kt`, `FilesPane.kt`
- Test: `app-android/src/androidTest/.../AppFlowOnAndroidTest.kt`

**Interfaces:**
- Produces: `Platform.pickDocuments(title: String): List<DocumentHandle>` e `Platform.createDocument(name: String): DocumentHandle?`; `DocumentHandle` espone nome, dimensione, `Source` e `Sink`. Il `TransferManager` accetta gli handle come terza origine o destinazione.

- [ ] **Step 1: Test che fallisce:** `uploadFromContentUri` con un `ContentProvider` di test, compresi i casi di dimensione sconosciuta e nome assente.
- [ ] **Step 2:** Esegui `./gradlew :app-android:connectedDebugAndroidTest`. Atteso: FAIL. Serve un emulatore; se non c'è, segnalalo e non dichiarare il test passato.
- [ ] **Step 3: Implementa** con `ACTION_OPEN_DOCUMENT` e `ACTION_CREATE_DOCUMENT`, come già per i file multipart. Con dimensione sconosciuta il progresso è indeterminato e la ripresa da offset non si offre.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: file del telefono dal selettore di sistema`.

### Task 17: Monitor con i processi

**Files:**
- Modify: `ResourceMonitor.kt`, `MonitorPane.kt`
- Test: `shared/src/commonTest/.../ResourceMonitorTest.kt`, `MonitorFlowTest.kt`

**Interfaces:**
- Produces: `MonitorSample.processes: List<ProcessInfo> = emptyList()`, con `ProcessInfo(pid: Int, user: String, cpuPercent: Double, memPercent: Double, command: String)`.

- [ ] **Step 1: Test che falliscono:** `parsesTopProcesses`, `commandWithSpacesKept`, `missingPsLeavesOtherMetrics`, `busyboxPsTolerated`, e in `MonitorFlowTest` `processesFromRealHost`.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Aggiungi al comando una sezione `@` con `ps -eo pid,user,pcpu,pmem,args --sort=-pcpu | head -n 11`; se `ps` non la capisce la sezione è vuota e il resto del monitor continua.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: processi più pesanti nel monitor`.

### Task 18: Docker, rimozione, immagini, volumi, reti, Compose

**Files:**
- Modify: `DockerPanel.kt`, `DockerPane.kt`, `testenv/fake-docker`
- Test: `shared/src/commonTest/...` (parser), `DockerFlowTest.kt`

**Interfaces:**
- Produces: in `DockerPanel`, `images`, `volumes`, `networks`, `composeProjects: StateFlow<List<…>>`, lette solo con la relativa sezione aperta; `remove(kind, id)`, `composeUp/Down/Restart(project)`.

- [ ] **Step 1: Test che falliscono:** `removeContainerAsksConfirmation`, `runningContainerNotRemovable` (va fermato prima), `imageInUseErrorShownInClear`, `listsImagesVolumesNetworks`, `composeLsParsed`, `composeRestart`, `noComposeInstalledHidesSection`, `quotesProjectAndIdInCommands` (nomi con spazi o `;` non escono dall'argomento).
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** Stessa lettura di 0013: `--format '{{json .}}'`, `docker compose ls --format json`. Compose usa `--project-name` e il file di configurazione riportato da `compose ls`. Ogni rimozione e `compose down` chiede conferma col bottone Danger; le reti predefinite `bridge`, `host`, `none` non si rimuovono. Estendi `fake-docker` con questi comandi.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: Docker con Compose, immagini, volumi e reti`.

### Task 19: Registrazioni e icona Android

**Files:**
- Modify: `Replay.kt`, `shared/.../app/Recordings.kt`
- Create: `app-android/src/main/res/mipmap-*/ic_launcher*` e manifest
- Test: `shared/src/commonTest/.../RecordingsTest.kt` e `RecordingFlowTest.kt`

- [ ] **Step 1: Test che falliscono:** `replayAppliesResizeEvents`, `resizeEventOrderKept`, `malformedResizeIgnored`.
- [ ] **Step 2:** Esegui. Atteso: FAIL.
- [ ] **Step 3: Implementa.** In `Asciicast` conserva gli eventi `"r"`; `ReplayTab` ridimensiona il terminale al momento giusto invece di ignorarli.
- [ ] **Step 4:** Riesegui. Atteso: PASS.
- [ ] **Step 5:** Commit `Punti aperti: la riproduzione segue i ridimensionamenti`.
- [ ] **Step 6: Icona.** Genera un adaptive icon da El Bigote (l'icona del pacchetto, già in `docs/logo` o nelle risorse desktop), collegala con `android:icon` e `android:roundIcon`. Verifica con `./gradlew :app-android:assembleDebug`. Commit `Punti aperti: icona del launcher su Android`.

### Task 20: Chiusura

**Files:**
- Modify: `docs/PLAN.md`, `README.md`

- [ ] **Step 1:** In `PLAN.md`: Fase 7 ✓ nella Roadmap, punti aperti tolti dallo Stato, elenco delle prove a mano da spec. README aggiornato.
- [ ] **Step 2:** Esegui `./gradlew build` e `./gradlew :shared:desktopTest :core:ssh:desktopTest :core:vault:desktopTest :core:data:desktopTest`. Atteso: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `Fase 7 completata`.
