# 0004. Archivio degli host e vault dei segreti

Data: 29 ottobre 2026. Stato: accettata.

## Archivio

Host, gruppi, identità, chiavi pubbliche e known_hosts stanno in un unico documento JSON, `workspace.json` (`core/data`, `FileWorkspaceStore`).

- Con circa 50 host il documento intero sta in memoria e si riscrive in pochi millisecondi. Un database (Room o SQLDelight) aggiungerebbe generazione di codice e KSP da far convivere con AGP 9 e Kotlin 2.4, senza un vantaggio reale.
- Ogni scrittura va su un file temporaneo poi spostato sopra il vecchio, quindi un crash non lascia mai il file a metà. La versione precedente resta come `workspace.json.bak`.
- Il campo `version` e `ignoreUnknownKeys` permettono di aggiungere campi senza migrazioni. Un file scritto da una versione più nuova dell'app viene rifiutato.
- L'accesso passa dall'interfaccia `WorkspaceStore`: se un giorno servisse un database, cambia solo l'implementazione.

## Vault

Password, chiavi private e passphrase stanno in `vault.bin` (`core/vault`, `EncryptedFileVault`), cifrato per intero con AES-256-GCM. Il JSON contiene solo gli id dei segreti.

La chiave principale non è mai in un file in chiaro:

| Piattaforma | Dove sta la chiave |
|---|---|
| macOS | Keychain |
| Windows | Credential Manager |
| Linux | Secret Service (GNOME Keyring, KWallet) |
| Android | generata a caso e cifrata con una chiave AES dell'Android Keystore, che non si può esportare |

Per il desktop usiamo java-keyring. Se il vault esiste ma la chiave manca, l'app si ferma con un errore invece di crearne una nuova: una chiave nuova renderebbe il vecchio vault illeggibile per sempre.

## Quando si chiedono i segreti

- Password e passphrase non salvate si chiedono solo quando il connettore le legge, cioè dopo la verifica della host key. Così controlli l'impronta del server prima di scrivere la password.
- Il segreto finisce nel vault solo se hai spuntato "Salva nel vault" e il login è riuscito. Viene salvato prima di mostrare la sessione, così una riconnessione immediata lo trova già.
- Ogni salto di un jump host ha la sua verifica della host key e le sue credenziali.

## Limiti noti

- MINA vuole la password come `String`, e anche i campi di testo di Compose lavorano con stringhe: queste copie non si possono azzerare. Chiavi e passphrase in `ByteArray` invece vengono azzerate dopo l'uso.
- Linux senza Secret Service non ha ancora un ripiego con master password (previsto in Fase 4).
