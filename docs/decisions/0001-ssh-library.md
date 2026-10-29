# 0001. Libreria SSH

Data: 29 ottobre 2026. Stato: accettata.

## Contesto

Tutte le piattaforme (Android, macOS, Windows, Linux) girano su JVM, quindi basta una libreria SSH per JVM. Deve coprire ogni metodo di autenticazione del piano e permettere più canali su una sola connessione.

## Prova

Gli spike in `spikes/mina/` e `spikes/cbssh/` eseguono gli stessi controlli contro i server di `testenv/` (OpenSSH 10.0). `app-android/src/androidTest/` prova MINA su un emulatore Android 11.

| Controllo | Apache MINA SSHD 2.20.1 | connectbot/cbssh 0.6.6 |
|---|---|---|
| Password | ok | ok |
| Chiavi Ed25519 / RSA (rsa-sha2) / ECDSA | ok | ok |
| Chiave Ed25519 con passphrase (bcrypt) | ok | ok |
| Keyboard-interactive (password + TOTP) | ok | ok |
| Jump host | ok, `HostConfigEntry` con `proxyJump` | ok, `openDirectTcpipTransport` passato al secondo client |
| Certificato utente | ok | ok |
| Certificato host con `@cert-authority` | ok, verifica firma e principal durante il KEX | ok, va attivato con `enableCertificateHostKeys()` |
| SSH agent per l'autenticazione | ok con un nostro client agent (~50 righe); quello incluso richiede la libreria nativa APR | ok con un nostro `AuthHandler` (~60 righe); non c'è un client agent incluso |
| PTY e resize | ok | ok |
| Exec con exit code | ok | ok |
| SFTP: elenco e 100 MB con checksum | ok | ok, SFTP v3 con le estensioni OpenSSH |
| SFTP: 4 canali paralleli su una connessione | ok | ok |
| Più shell + SFTP sulla stessa connessione | ok | ok |
| Port forwarding locale | ok | ok; remoto e SOCKS5 nell'API |
| Callback host key con fingerprint SHA256 | ok | ok, `suspend` |
| Scambio di chiavi post-quantistico | mlkem768x25519, sntrup761x25519 | mlkem768x25519 (negoziato con OpenSSH 10) |
| Chiavi FIDO2 (`sk-*`) | parziale: legge le chiavi ma non firma lato client | aggancio previsto in `AuthHandler` (`SkSignatureBlob`), non provato con hardware |
| Android | **verificato** su emulatore Android 11 | non verificato; il codice ha percorsi specifici per Android |
| API | sincrona con future, da avvolgere in coroutine | `suspend` e risultati tipizzati |
| Peso delle dipendenze | 11,2 MB (con Bouncy Castle) | 10,2 MB (Tink e protobuf-java sono la parte più pesante) |
| Maturità | progetto Apache, anni di uso in produzione (per esempio in JGit) | versione 0.x, poca diffusione |

Velocità SFTP con 100 MB, i due spike eseguiti uno dopo l'altro: MINA 21,9 MB/s in upload e 23,4 in download, cbssh 21,9 e 30,5. Il client `sftp` di OpenSSH misurato prima: 12,7 e 19,9. Il limite è la rete di Docker Desktop, quindi le due librerie sono equivalenti.

## Decisione

Usiamo **Apache MINA SSHD** dietro le interfacce di `core/ssh`.

- È l'unica delle due provata davvero su Android, ed è molto più matura. Per il cuore dell'app conta più della comodità dell'API.
- L'API sincrona si avvolge in coroutine dentro l'implementazione di `core/ssh`, così il resto dell'app vede solo `suspend` e `Flow`.
- L'agent si collega con il nostro `UnixSocketAgent` (socket Unix del JDK su macOS e Linux). Su Windows servirà la named pipe di OpenSSH.
- Per FIDO2 useremo lo stesso meccanismo: un "agent" interno all'app che firma con la chiave hardware. Su desktop basta anche l'agent di sistema.

Setup obbligatorio su Android:

```kotlin
Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
Security.insertProviderAt(BouncyCastleProvider(), 1)
OsUtils.setAndroid(true)
PathUtils.setUserHomeFolderResolver { context.filesDir.toPath() }
```

Configurazione obbligatoria sul client: `HostConfigEntryResolver.EMPTY` e `KeyIdentityProvider.EMPTY_KEYS_PROVIDER`. Altrimenti MINA legge da solo `~/.ssh/config` e le chiavi di default.

## Da rivalutare

cbssh resta l'alternativa. Ha senso riprenderla nella Fase 6 (autenticazione avanzata) se il firmatario FIDO2 con MINA risulta complicato, oppure quando arriverà alla 1.0. Grazie all'interfaccia `core/ssh`, cambiare libreria tocca solo l'implementazione.
