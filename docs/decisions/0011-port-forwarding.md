# 0011. Port forwarding salvato sugli host

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Tre tipi, nel nucleo SSH.**
- **Locale:** `forwardLocal(host, porta, portaLocale)`. Ascolta su `127.0.0.1` di questo dispositivo e porta ogni connessione a `host:porta` visto dal server. Con porta 0 la sceglie il sistema, come fanno le richieste HTTP.
- **Remota:** `forwardRemote(portaServer, host, porta)`. Il server ascolta sul suo loopback e rimanda ogni connessione a `host:porta` visto da questo dispositivo. Il client MINA rifiuta di default quello che torna indietro, quindi il filtro dei forward accetta tutto: il server può usare solo le porte che abbiamo chiesto noi.
- **Dinamica:** `forwardDynamic(porta)`, un proxy SOCKS su `127.0.0.1` che esce dal server. I nomi li risolve il server.
- **Solo loopback:** le porte non si aprono sulla rete, né qui né sul server (niente `GatewayPorts`). Una porta aperta sulla rete esporrebbe il server a chiunque nella stessa LAN.

**Le regole.** Stanno sull'host (`Host.forwards`): nome, tipo, porta in ascolto, destinazione, "avvia quando mi collego". Si salvano nel workspace; non contengono segreti.

**L'esecuzione (`PortForwards`).**
- Ogni regola attiva tiene una quota del link condiviso dell'host, come una scheda. La connessione resta aperta finché la regola gira, anche senza schede aperte.
- Dopo una caduta il link si ricollega da solo e il forward si riapre sulla nuova connessione. Se il link è giù per sempre, la regola diventa "errore" con il motivo.
- Se la porta non si apre (già in uso, rifiutata dal server) la regola si ferma e libera la connessione, con il motivo in italiano.
- L'avvio automatico scatta a ogni login riuscito sull'host, riconnessioni comprese; le regole già attive restano come sono.
- Lo stato è solo in memoria: alla chiusura dell'app tutti i forward si fermano.

**Interfaccia.** "Port forwarding…" nel menu dell'host apre l'elenco delle regole con stato, Avvia/Ferma, modifica ed eliminazione. Nella lista host un'etichetta dice quante porte sono attive. Modificare una regola attiva la riavvia con i nuovi valori; eliminare un host ferma le sue regole.

## Aggiornamento del 30 ottobre 2026

**Indirizzo di ascolto.** Ogni regola ha il suo, 127.0.0.1 di default. Un indirizzo che non è loopback mostra un avviso, perché la porta diventa raggiungibile dalla rete; per i forward remoti decide anche `GatewayPorts` sul server.

**Elenco unico.** Gli strumenti hanno la schermata "Forward attivi", con tutte le regole in corso su ogni host e il bottone Ferma.
