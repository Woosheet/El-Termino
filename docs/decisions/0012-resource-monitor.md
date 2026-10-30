# 0012. Monitor delle risorse

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Come legge.** Una scheda "Risorse" per host, aperta dal menu dell'host. Ogni 3 secondi lancia un solo comando via exec sul link condiviso: `/proc/uptime`, `/proc/loadavg`, `/proc/meminfo`, la prima riga di `/proc/stat` con il numero di CPU, `/proc/net/dev` e `df -kP`, separati da righe `@`. Niente agenti da installare, niente da tenere aperto sul server tra una lettura e l'altra. Legge solo mentre la scheda è aperta.

**Cosa mostra.**
- **CPU:** percentuale tra due letture (tutto meno idle e iowait), più il carico a 1, 5 e 15 minuti.
- **Memoria:** in uso = totale meno `MemAvailable` (o free + buffer + cache sui kernel vecchi), più lo swap.
- **Rete:** byte al secondo ricevuti e inviati, sommati su tutte le interfacce tranne `lo`.
- **Dischi:** la radice e i dispositivi `/dev/...`; lo stesso disco montato due volte (come i bind di Docker) compare una volta sola. Oltre il 90% la barra diventa rossa.
- **Andamento:** le ultime 60 letture (3 minuti) in memoria, come linee sotto ogni valore.

**Riconnessione.** Le velocità ripartono da zero con ogni nuova connessione, così un rientro non mostra un picco inventato. Un contatore che scende (interfaccia riavviata) non dà velocità per quella lettura.

**Host non Linux.** Senza `/proc` la scheda lo dice: il monitor funziona sugli host Linux.

## Restano fuori

- I processi più pesanti (`top`/`ps`).
- macOS e BSD come host monitorati.
