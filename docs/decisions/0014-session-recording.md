# 0014. Registrazione delle sessioni

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Formato.** asciicast v2, quello di asciinema: una riga di intestazione JSON, poi un evento per riga, `[secondi, "o", testo]` per l'output e `[secondi, "r", "COLONNExRIGHE"]` per un ridimensionamento. È testo, si apre con `asciinema play` e si carica su asciinema.org, e il file si può scrivere un evento alla volta senza tenerlo in memoria.

**Cosa si registra.** Solo quello che il terminale mostra, non quello che si digita: una password scritta a un prompt che non fa eco non finisce nel file. Ciò che il server stampa sì, quindi un `cat` di un file di segreti resta nella registrazione. Un carattere UTF-8 diviso tra due pezzi di output si scrive intero nel pezzo dopo.

**Come si avvia.** Dal menu della scheda, "Avvia registrazione" e "Ferma la registrazione"; oppure dalle Impostazioni, "Registra tutti i terminali da quando si aprono". Mentre registra, il pallino della scheda è rosso.

**Dove stanno.** Un file `.cast` per registrazione nella cartella dei dati dell'app, con il nome della scheda e l'ora. Il file si svuota su disco ogni secondo, così una chiusura brusca perde al massimo l'ultimo secondo. Non è cifrato: lo dicono la schermata Registrazioni e le Impostazioni. Si eliminano da sole dopo 30 giorni (7, 90, 365 o mai, dalle Impostazioni), controllate all'avvio.

**Schermata Registrazioni.** Tra gli strumenti: elenco con titolo, data, durata e dimensione, letti dalla prima e dall'ultima riga senza aprire tutto il file. Riproduci, Esporta (finestra di salvataggio su desktop, "crea documento" su Android) ed Elimina.

**Riproduzione.** Una scheda che usa lo stesso terminale delle sessioni, alimentato dagli eventi del file invece che da SSH: pausa, ricomincia, velocità 1, 2, 4 e 8 volte, posizione e durata. Le pause oltre 2 secondi durano 2 secondi, come il limite di inattività di asciinema.

**Test.** `RecordingsTest` per registratore, parser, UTF-8 spezzato e pulizia; `RecordingFlowTest` registra una sessione vera su `basic`, rilegge il file e lo riproduce; `AppSnapshotTest.replayTab`.

## Restano fuori

- Saltare a un punto della registrazione.
- Registrazioni cifrate nel vault.
