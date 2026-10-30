# 0013. Pannello Docker

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Come legge.** Una scheda "Docker" per host, dal menu dell'host. Ogni 5 secondi, e subito dopo un'azione, lancia via exec sul link condiviso `docker ps -a` e `docker stats --no-stream` con `--format '{{json .}}'`: una riga JSON per container, stabile tra le versioni di Docker. Il JSON si legge ignorando i campi che non conosce. Legge solo mentre la scheda è aperta.

**Cosa mostra.** Prima i container attivi, poi gli altri, in ordine di nome. Per ognuno: stato, immagine, porte, e per quelli attivi CPU e memoria come le scrive Docker.

**Azioni.**
- **Avvia, ferma, riavvia:** `docker start|stop|restart` con l'ID del container, che non cambia se il container viene rinominato. Mentre l'azione va, al posto dei bottoni salta il sombrero.
- **Errori:** quelli dell'elenco spariscono da soli quando l'elenco torna leggibile; quelli di un'azione restano finché non ne parte un'altra, altrimenti l'aggiornamento successivo li cancellerebbe prima che si possano leggere.
- **Log e shell:** si aprono in una scheda terminale nuova sullo stesso host, con `docker logs -f --tail 200` o `docker exec -it … sh -c 'bash o sh'`. Così scorrimento, ricerca, copia e riconnessione sono quelli del terminale, e uscendo si torna alla shell dell'host.

**Errori comuni in chiaro.** Docker non installato, demone non avviato, utente fuori dal gruppo `docker`: il messaggio dice cosa fare invece di riportare l'errore del socket.

**Test.** Nei server di test c'è un finto comando `docker` (`testenv/fake-docker`): su `basic` tre container il cui stato cambia davvero, sugli altri host il permesso negato. Docker dentro Docker sarebbe stato più fedele ma molto più pesante da far girare.

## Aggiunti dopo

Rimozione di container fermi, immagini, volumi e reti (non `bridge`, `host`, `none`), sezioni lette solo se aperte, e Compose (`docker compose ls -a`, up, down, restart). Ogni rimozione e `compose down` chiede conferma.

## Restano fuori

- Docker Desktop su macOS e Windows come host (il comando è lo stesso, ma non è provato).
