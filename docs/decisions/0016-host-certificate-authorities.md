# 0016. CA per i certificati host

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Elenco.** Le CA fidate stanno nel workspace, con chiave pubblica e host coperti, scritti come in `known_hosts` (`*.example.com`, `[10.0.0.1]:2222`, `!escluso.example.com`). Si aggiungono a mano dalle Impostazioni o dalle righe `@cert-authority` di `~/.ssh/known_hosts`. Viaggiano nel backup.

**Regole.** Se nessuna CA copre l'host non cambia niente: fiducia al primo uso sulla chiave certificata. Se una CA lo copre:

| Il server presenta | Esito |
|---|---|
| Certificato valido di quella CA | Si collega senza chiedere |
| Certificato valido, chiave diversa da quella salvata | Si collega, sostituisce la chiave e mostra un avviso: è un rinnovo |
| Certificato scaduto, non ancora valido, firma errata, nome sbagliato o CA diversa | Blocco |
| Chiave semplice già fidata a mano | Si collega |
| Chiave semplice sconosciuta o cambiata | Blocco |

Il blocco non ha un bottone per passare oltre: l'uscita è togliere o cambiare la CA nell'elenco, che è una scelta deliberata. Altrimenti offrire una chiave senza certificato basterebbe a evitare il controllo.

**Il certificato arriva sempre a noi.** MINA, se un certificato non è valido, lo scarta senza dirlo e passa la chiave semplice. Controlla anche i nomi contro l'indirizzo a cui si è collegata, che dietro un jump host è il capo locale del tunnel. Per questo `CertificateKeepingClient` lascia passare ogni certificato e il giudizio sta tutto in `hostTrust`, che conosce il nome vero dell'host. La firma della CA si verifica in `MinaSupport`.

**Test.** `HostCaTrustTest` per le regole; `ConnectionFlowTest` contro `basic` e contro il nuovo server `expired`, anche come jump host; `MinaSshConnectorTest` per i dati del certificato.

## Restano fuori

- Elenchi di revoca (`@revoked`, KRL).
- Gli scambi di chiavi group-exchange usano ancora il controllo di MINA. OpenSSH preferisce le curve, quindi non capita con i server di test.
