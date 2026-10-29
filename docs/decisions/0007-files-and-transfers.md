# 0007. File e trasferimenti: una coda per tutto, sopra le connessioni condivise

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

**File locali e remoti hanno la stessa interfaccia.** `FileSystem` ha due implementazioni:
- `RemoteFileSystem`: SFTP sul `HostLink` dell'host, lo stesso che usano i terminali;
- `LocalFileSystem`: `java.nio` sotto una cartella radice.

I percorsi usano sempre `/`, anche su Windows (`C:/Users/…`). Le voci sono `RemoteFile` da entrambe le parti.

**Le schede non sono più solo terminali.** `Tab` ha tre tipi: `SessionTab` (terminale), `FilesTab` (pannello file) ed `EditorTab`. Tutti stanno nei riquadri della split view, quindi "locale | remoto" o "web-01 | web-02" sono due pannelli file affiancati. Login e link condivisi sono passati in `Connections`, usato da schede, pannelli e trasferimenti. Un pannello o un editor tiene la propria quota del link; chiuderlo non tocca le altre schede sull'host.

**Un solo `TransferManager` per tutti i trasferimenti**, qualunque siano gli host.

- **Streaming:** i dati passano dal client senza file temporanei, anche da remoto a remoto. MINA tiene più richieste SFTP in volo, quindi la latenza non limita la velocità. Lettura e scrittura partono da un offset senza riscaricare l'inizio del file (`skip` sullo stream asincrono, `setOffset` in scrittura).
- **Parallelismo:** al massimo `maxParallel` trasferimenti insieme (3, modificabile dalla barra) e `maxPerHost` per host (2). Ogni host usa un'unica sessione SFTP per tutti i suoi trasferimenti, per non superare il `MaxSessions` di OpenSSH.
- **Pausa e ripresa:** la pausa ferma il trasferimento e tiene il piano (cartelle da creare, file da copiare) con il punto raggiunto. La ripresa riparte dal file a metà, dalla dimensione che ha già a destinazione.
- **Conflitti:** quattro scelte, chieste una volta per ogni copia, valgono anche per i file dentro le cartelle.
  - Sovrascrivi.
  - Riprendi: continua i file più corti, considera fatti quelli uguali.
  - Rinomina: `file (1).txt`.
  - Salta.
- **Connessione caduta:** il trasferimento passa a "In attesa", aspetta che il link si riconnetta e riprende dall'offset, fino a 5 volte. Altri errori, come permesso negato, lo segnano come non riuscito, con "Riprova".
- **Collegamenti simbolici:** dentro le cartelle vengono saltati e contati. Seguirli può creare cicli o uscire dalla cartella.
- **Annulla:** cancella il file rimasto a metà.

**Editor remoto.**
- **Limiti:** file fino a 2 MB, rifiutati se sembrano binari.
- **Salvataggio atomico:** scrive un file temporaneo nella stessa cartella, gli dà i permessi dell'originale e lo rinomina sopra con `posix-rename@openssh.com`. Una connessione che cade non lascia mai mezzo file.
- **Modifiche altrui:** prima di salvare confronta dimensione e data di modifica con quelle del momento in cui il file era stato aperto. Se sono cambiate, chiede se sovrascrivere o ricaricare.
- **Evidenziazione:** con espressioni regolari per YAML, JSON, shell e file di configurazione; non è un parser.

## Perché

- La coda unica è il requisito della Fase 3: "trasferimenti paralleli tra 3 o più host in un'unica coda". `TransferFlowTest` lo prova con basic, bastion e internal (dietro il bastion) più il computer locale.
- Appoggiarsi ai link condivisi significa niente login in più per i trasferimenti e la riconnessione della Fase 2 gratis.
- Il rename atomico è come lavorano vim ed editor simili: nessuno vede mai il file a metà.

## Conseguenze

- **Proprietario del file:** il rename sostituisce il file con uno nuovo. Il proprietario diventa l'utente SSH e i link fisici all'originale si staccano. Per i file di sistema modificati con sudo serve ancora il terminale.
- **Android:** il pannello locale mostra la cartella dell'app, perché lo Storage Access Framework non dà percorsi veri. Caricare dal telefono e salvare fuori dall'app con il selettore di sistema restano da fare.
- **Da fare:** "Apri con editor esterno" sul desktop (scarica, apri, ricarica a ogni salvataggio) e il trascinamento dall'app verso il Finder.
