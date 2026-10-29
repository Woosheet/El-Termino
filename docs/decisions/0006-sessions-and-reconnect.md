# 0006. Sessioni: una connessione per host e riconnessione automatica

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

Le schede non aprono più una connessione SSH ciascuna. `HostLinks` (in `shared/.../app/HostLinks.kt`) tiene un `HostLink` per host: una connessione autenticata su cui ogni scheda apre il proprio canale shell.

- **Seconda scheda sullo stesso host:** si apre subito, senza un nuovo login né domande. Due schede aperte insieme aspettano lo stesso login, quindi la password o l'OTP si chiedono una volta sola.
- **Host modificato mentre è connesso:** ottiene un link nuovo. La chiave del link comprende indirizzo, porta, utente, identità e jump host, mentre le schede già aperte restano sul vecchio.
- **Limite di sessioni:** OpenSSH accetta 10 sessioni per connessione (`MaxSessions`). Oltre 8 utenti, un host riceve una seconda connessione.
- **Chiusura:** il link si chiude quando lo lascia l'ultima scheda. `exit` in una shell chiude solo quella scheda.

La riconnessione è compito del link, non della scheda.

- **Connessione caduta:** se la connessione cade senza che l'abbiamo chiusa noi (rete, server riavviato, keepalive senza risposta), il link riprova con le attese di `ReconnectPolicy`: 1, 2, 4, 8, 15 e poi 30 secondi, per 10 tentativi, circa tre minuti in tutto. "Riprova ora" salta l'attesa, "Annulla" ferma la riconnessione per quella scheda.
- **Errori senza nuovi tentativi:** host key rifiutata, autenticazione fallita, dialogo annullato e vault illeggibile fermano subito i tentativi. Riprovare non li risolve.
- **Primo login fallito:** non viene ripetuto in automatico. La scheda mostra l'errore e il pulsante "Riprova".

A ogni riconnessione la scheda apre una shell nuova, ma nello stesso `TerminalSession`. `TerminalSession.attach()` collega la nuova shell e conserva schermo e scrollback. Prima azzera i modi lasciati attivi dal programma precedente (schermo alternativo, mouse, bracketed paste, tasti cursore in modalità applicazione), poi scrive una riga "Riconnesso alle 14:32". I tasti premuti mentre la connessione era giù vengono scartati.

Il broadcast inoltra eventi (`TerminalInput`), non byte. Ogni sessione che li riceve li codifica secondo i propri modi: una freccia arriva corretta sia a vim sia alla shell. L'input ricevuto con `receive()` non viene inoltrato di nuovo, quindi non ci sono cicli.

## Perché

- È quello che fa Termius, ed è il modo in cui il protocollo SSH è pensato: una connessione, più canali. Con 48 host e più schede per host, evita login ripetuti e prompt OTP a raffica.
- I pannelli SFTP (Fase 3), le richieste HTTP (Fase 5) e gli strumenti (Fase 8: monitor, Docker, port forwarding) useranno gli stessi link: un pannello SFTP su un host già aperto non richiede un altro login.
- Una riconnessione per link, invece che per scheda, significa un solo tentativo e un solo prompt per host anche con cinque schede aperte.

## Conseguenze

- Uno stesso link serve tutte le schede di un host: un problema della connessione le colpisce tutte. È voluto, ed è anche ciò che OpenSSH fa con `ControlMaster`.
- Su Android le sessioni restano vive in background grazie a `SessionService`, un Foreground Service di tipo `specialUse`. Il tipo `dataSync` sarebbe fermato da Android 15 dopo 6 ore. La notifica conta le sessioni e ha "Chiudi tutte".
- Da verificare su un dispositivo vero:
  - il comportamento in Doze a schermo spento;
  - la riconnessione dopo il cambio Wi-Fi ↔ rete mobile.
