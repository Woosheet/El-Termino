# 0010. Richieste HTTP attraverso la connessione SSH

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

**Il tunnel.**
- **Nel nucleo SSH:** `SshConnection.forwardLocal(host, porta)` apre un forward locale sulla connessione già aperta (MINA `startLocalPortForwarding`). Ascolta su `127.0.0.1` con una porta scelta dal sistema e porta ogni connessione a `host:porta` visto dal server. È lo stesso meccanismo che servirà al port forwarding della Fase 8.
- **Una richiesta, un tunnel:** il forward si apre all'invio e si chiude con la risposta. Costa pochi millisecondi e non lascia porte aperte.
- **Il client:** OkHttp 5 sul JVM (Android e desktop). Una `SocketFactory` manda ogni socket all'estremo locale del tunnel e un DNS finto evita di risolvere i nomi sul computer: li risolve il server. HTTPS quindi verifica il certificato con il nome scritto nell'URL.
- **Proxy e redirect:** il proxy di sistema è disattivato, altrimenti vedrebbe la richiesta prima del tunnel. I redirect non vengono seguiti: potrebbero puntare a un altro host, che il tunnel non raggiunge. La risposta 3xx si mostra com'è.
- **Tempi:** "connessione" va dall'invio alla connessione attraverso il tunnel, poi primo byte e totale.

**curl sul server.** Ogni richiesta può partire con `curl` lanciato via exec, per vedere esattamente quello che vede la macchina (DNS, proxy, certificati). Le opzioni `-D - -o - -w` danno header, corpo e tempi in un solo output. "Copia come curl" usa lo stesso comando, quotato per una shell POSIX.

**Collezioni.**
- **Indipendenti dagli host:** una collezione si usa da qualunque host. Le variabili `{{nome}}` hanno un valore predefinito nella collezione e uno per host, che vince.
- **Dove valgono le variabili:** URL, header, corpo, utente di Basic, nome dell'header della API key e il segreto stesso, perché le collezioni di Postman scrivono spesso `{{token}}`.
- **Segreti:** token, password e chiavi stanno nel vault; il workspace tiene solo il riferimento. Un `Authorization: Bearer` trovato in un comando curl importato diventa un segreto nel vault.
- **Salvataggio:** l'editor salva da solo poco dopo l'ultima modifica.
- **Form multipart:** campi di testo e file di questo dispositivo. Del file si salva il riferimento, non il contenuto: un percorso sul desktop, un URI `content://` con permesso di lettura persistente su Android. Il file si rilegge a ogni invio; se non c'è più, l'errore lo dice. Con curl sul server i file non si possono mandare, perché il server non li vede; "Copia come curl" li scrive come `-F nome=@percorso`.
- **Cronologia:** le ultime 10 risposte di ogni richiesta, per host, restano in memoria. Non si salvano su disco: i corpi possono contenere dati riservati e il file del workspace non è cifrato.

**Verifiche ed esecuzione su un gruppo.**
- **Verifiche senza codice:** stato uguale a, il corpo contiene, campo JSON (percorso tipo `data.items[0].id`) uguale a, tempo sotto N ms.
- **Esecuzione:** su questo host o su un gruppo con i suoi sottogruppi. Gli host vanno in parallelo, ognuno sul proprio link SSH; le richieste di un host vanno in ordine. Un host che non si connette fa fallire tutte le sue celle con il motivo.
- **Esito:** una tabella host × richiesta che si riempie mentre arrivano le risposte; una cella aperta mostra stato, tempo e verifiche.

**Import.** Collezioni di Postman v2.1 (cartelle come "Cartella / richiesta", auth della collezione e delle cartelle ereditata, variabili della collezione) e comandi curl incollati. I campi file di Postman e i `-F nome=@file` di curl diventano campi file del form, con il percorso salvato. Restano fuori gli script di test di Postman e le API key passate nella query.

**La scheda.** "Richieste" è un tipo di scheda come terminale e file: si apre dal menu dell'host, sta nella split view accanto a un terminale e si salva negli spazi di lavoro.

## Alternative scartate

- **SOCKS dinamico con `HttpURLConnection`:** niente dipendenze, ma `HttpURLConnection` non supporta PATCH e su Android il supporto SOCKS non è garantito.
- **Un client HTTP scritto da noi sopra il canale `direct-tcpip`:** pieno controllo, ma TLS, chunked e gzip da rifare a mano.
- **Cronologia salvata su disco:** utile tra un avvio e l'altro, ma metterebbe corpi e header delle risposte in chiaro nel workspace.

## Verifica

- `MinaSshConnectorTest`: il forward raggiunge il servizio HTTP sul loopback del server, anche dietro il bastion.
- `HttpRequestFlowTest`: GET e POST attraverso il tunnel e con curl sul server; un form multipart con testo e un file del computer; errore chiaro su una porta chiusa.
- `HttpRunFlowTest`: criterio della fase. Una collezione con verifiche gira su tre host di un gruppo (uno dietro il bastion, uno con una porta sbagliata di proposito) e dà l'esito per host. Più una scheda che invia dal suo host e tiene le risposte.
- `HttpRequestsTest`, `HttpImportTest`: variabili, auth, corpi, curl, verifiche, import da Postman e da curl.
