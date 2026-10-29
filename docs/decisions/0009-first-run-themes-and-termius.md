# 0009. Primo avvio, temi, avvisi e lettura dei dati di Termius

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

**Temi.**
- **Cosa definisce un tema:** un `AppTheme` è una coppia di colori dell'app (`CartaColors`) e del terminale (`TerminalTheme`), sempre scelti insieme.
- **I temi:**
  - Carta (chiaro);
  - Notte, scuro sul blu notte e non nero, come richiesto;
  - Nord;
  - Solarized chiaro;
  - Dracula;
  - Tokyo Night.
- **Scelta:** dalle Impostazioni. "Segui il sistema" usa Carta di giorno e Notte in modalità scura.
- **Barre di sistema:** la barra del titolo su macOS e le barre di sistema su Android seguono il tema, non il sistema.

**Avvisi dei trasferimenti.**
- **Raggruppamento:** `TransferNotifier` unisce i trasferimenti che finiscono a meno di 1,5 secondi l'uno dall'altro in un solo avviso ("4 trasferimenti completati"). Gli annullati non danno avviso.
- **Dentro l'app:** l'avviso compare sempre in basso a destra.
- **Notifica di sistema:** solo se l'app non è in primo piano e l'opzione è attiva. Usa Notification Center (`osascript`) su macOS, `notify-send` su Linux, il fumetto della tray su Windows e una notifica su Android.
- **Chiusura con trasferimenti in corso:** chiudere la finestra chiede conferma. Su Android il servizio in primo piano resta attivo finché un trasferimento è in corso.

**File trascinati su un terminale.** Vengono caricati nella cartella della shell. La cartella si ricava dal titolo che bash di Debian e Ubuntu impostano, "utente@host: /percorso"; se il titolo non la indica, vanno nella home.

**Benvenuto e tutorial.**
- **Primo avvio:** l'app chiede "Usi Termius su questo computer?" e, se sì, legge i suoi dati e propone l'import con le cifre: host, gruppi, utenti, password, chiavi.
- **Tutorial:** sei passi sulle funzioni, ognuno con un frammento disegnato con i componenti veri, non un'illustrazione.
- **Rivederlo:** dalle Impostazioni.

**Lettura locale di Termius** (`core/importer`, solo desktop).
- **Dove stanno i dati:** Termius è un'app Electron che tiene i dati in un IndexedDB di Chromium su LevelDB.
- **Come li leggiamo:** il lettore apre i file così come sono, senza aprire il database e senza scrivere niente, quindi Termius può restare aperto. Legge le tabelle `.ldb` (con Snappy) e i log `.log`; per ogni chiave vince la scrittura più recente.
- **Dal database ai record:** decodifica le chiavi di IndexedDB (prefisso database / object store / indice, nomi degli store dai metadati) e i valori serializzati da V8.
- **Campi cifrati:** etichette, password e chiavi sono cifrati con secretbox di NaCl (XSalsa20-Poly1305): base64 di `04 01`, nonce di 24 byte, tag e testo. La chiave è il `localKey` di Termius nel portachiavi di sistema, servizio `Termius` (o `Termius (MAS)` per l'App Store); leggerla fa chiedere il permesso al sistema.
- **Il modello:** è quello dell'API di sync di Termius: host → gruppo e configurazione SSH → identità → chiave. I gruppi sono annidati e portano i valori predefiniti.
- **Fonti:** il formato è stato ricostruito dagli strumenti open source di migrazione e dai modelli del vecchio client a riga di comando di Termius.

## Perché

- **Temi:** li hai chiesti tu, insieme a un tema scuro sul blu. La skill di design usata per il controllo vieta i temi scuri navy: qui vince la tua richiesta.
- **Termius:** non ha un export ufficiale, e per quasi 50 host l'import senza passaggi manuali è quello che serve.

## Conseguenze

- **Formato non documentato:** il formato di Termius può cambiare tra una versione e l'altra. I test usano dati costruiti a mano nel formato ricostruito, non dati veri di Termius. Quando la lettura fallisce, l'app lo dice e mostra "Dettagli tecnici" (solo nomi degli store e dei campi, mai valori), utili per adattare il lettore.
- **Versioni più recenti:** le build di Termius con IndexedDB su SQLite non sono ancora lette. L'app lo segnala e propone CSV e `~/.ssh/config`.
- **Android:** non c'è lettura locale. Si esporta un CSV dal computer.

## Controllo di design

Ho applicato le regole della skill design-taste-frontend per le schermate d'uso: lo strumento di misura della skill legge pagine web e non funziona con Compose, quindi il confronto è fatto sugli snapshot. Le modifiche:
- **Tipografia:** scala 12/13/14/16/18/22, titoli delle schermate a 22/600, didascalie non sotto i 12.
- **Raggi e controlli:** raggi a scalare (12 finestre, 8 menu e schede, 6 controlli, 4 voci); controlli alti 32.
- **Colore:** pulsanti secondari in inchiostro, con l'accento solo sui dati, sulla selezione e sull'azione principale; voce attiva della barra laterale neutra.
- **Prodotto:** marchio e nome in testa alla barra laterale.
- **Icone:** un solo stile a contorno per le icone di navigazione e i preferiti.
- **Spaziature e numeri:** margini delle schermate a 24, righe dei file più alte, cifre tabulari nei numeri in colonna.
- **Impostazioni:** etichette a sinistra e controlli a destra.
