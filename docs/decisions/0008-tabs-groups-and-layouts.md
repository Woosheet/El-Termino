# 0008. Schede con riquadri propri, gruppi e spazi di lavoro

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

**La disposizione dei riquadri appartiene alla scheda.** Con la Fase 2 la split view era una sola per tutta la finestra. Ora la barra (`TabBar`) è una lista di schede (`Frame`), ognuna con i suoi `Panes`: fino a quattro riquadri, ciascuno con una sessione (terminale, file o editor). Le operazioni sono funzioni pure su `TabBar`, provate in `TabBarTest`, e restituiscono sempre una barra nuova.

- **Aprire:** una sessione nuova va nel riquadro libero della scheda attiva, se ce n'è uno, altrimenti in una scheda sua subito dopo.
- **Unire:** si trascina una scheda sopra l'area delle sessioni; il lato più vicino al puntatore (sinistra, destra, sopra, sotto) decide dove va. Si fa anche dal menu della scheda, con "Affianca a". Il totale non supera quattro riquadri.
- **Separare:** "Separa i riquadri" dà a ogni sessione una scheda sua; "Sposta in una scheda nuova" toglie un solo riquadro.
- **Chiudere:** una divisione che perde sessioni si restringe. Con una sessione sola resta una scheda semplice, e una griglia con due sessioni diventa due riquadri affiancati.
- **Cambiare disposizione:** le sessioni che non ci stanno più passano in schede nuove, non vengono nascoste.

**Gruppi di schede**, come in Chrome.
- **Aspetto:** nome e uno di sette colori. Le schede di un gruppo restano sempre vicine; un gruppo compresso mostra solo l'etichetta con il numero di schede, e selezionare una sua scheda lo riapre.
- **Azioni dall'etichetta:**
  - comprimi;
  - rinomina;
  - colore;
  - broadcast a tutto il gruppo;
  - snippet sul gruppo;
  - "Apri in griglia" (unisce le prime quattro sessioni in una scheda);
  - salva come spazio di lavoro;
  - separa;
  - chiudi.
- **Raggruppa per host:** crea un gruppo per ogni host che ha almeno due schede.

**Spazi di lavoro.**
- **Cosa salvano:** `SavedLayout` salva schede, disposizioni, riquadri e gruppi. Per ogni riquadro tiene il tipo di sessione, l'host e la cartella o il file, mai le connessioni. Riaprire uno spazio rifà i login, con lo stesso meccanismo condiviso di sempre.
- **Ultima sessione:** viene salvata da sola un secondo dopo ogni modifica, mai vuota, e offerta all'avvio.
- **Host eliminati:** il loro riquadro sparisce e la divisione si restringe.

## Perché

- È quello che hai chiesto: due sessioni nella stessa scheda a schermo diviso, e gruppi di schede, per host o liberi.
- Con la divisione per scheda, la Fase 5 potrà mettere una scheda di richieste HTTP accanto al terminale con i log senza toccare le altre schede.
- Il criterio della fase, "uno spazio di lavoro con due gruppi e una scheda divisa si riapre uguale dopo un riavvio", è verificato da `TabLayoutFlowTest`: salva, ricrea l'app sugli stessi file e confronta la struttura.

## Conseguenze

- **Trascinamento:** sul desktop le schede si trascinano. Su Android si usa il menu, con una pressione lunga: il trascinamento lì scorrerebbe la barra.
- **Password non salvate:** riaprire uno spazio di lavoro con host che chiedono la password fa comparire i soliti dialoghi, uno alla volta.
