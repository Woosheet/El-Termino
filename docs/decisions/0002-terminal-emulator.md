# 0002. Emulatore di terminale

Data: 29 ottobre 2026. Stato: accettata.

## Contesto

Serve un emulatore VT/xterm che giri su Android e desktop e che si disegni con Compose. Deve reggere vim, htop, tmux e mc senza difetti visibili.

## Prova

Lo spike in `spikes/terminal/` registra output PTY reale dal server `basic` del testenv, lo passa a `io.github.johnan:terminal-core` 1.0.6 e disegna lo schermo in PNG senza finestra (`spikes/terminal/build/snapshots/`).

| Scenario | Esito |
|---|---|
| Colori e Unicode | 256 colori, truecolor, accenti, emoji e CJK a doppia larghezza corretti. Corsivo e "dim" vengono letti ma non disegnati. |
| htop | Corretto, tranne lo sfondo delle righe intestazione e dei tasti F |
| vim | Corretto: sintassi, numeri di riga, barra di stato. Le risposte CPR tornano al server. |
| tmux | Corretto: divisione verticale, barra di stato, modalità mouse 1000/1002/1003/1006 |
| mc | Corretto, ma la barra dei menu non si colora fino in fondo |
| UTF-8 spezzato | Corretto anche con un byte per volta |
| Resize | Le righe lunghe si ricompongono |

Il difetto comune a htop e mc è la mancanza di BCE (background color erase): `ScreenBuffer.clearCells` scrive sempre celle con lo sfondo predefinito, quindi EL, ECH e lo scroll ignorano lo sfondo corrente. `xterm-256color` dichiara `bce`, quindi molti programmi ne dipendono.

Prestazioni su Mac M: 29,5 MB/s di parsing con colori, circa 8 ms per disegnare uno schermo 100×30.

API: accetta byte (`processOutput(ByteArray)`), espone lo stato come `StateFlow`, può lavorare fuori dal thread UI, supporta bracketed paste, scrollback e ricerca. Mancano le risposte a DA2, XTVERSION e OSC 10/11, gli eventi di focus (1004) e il mouse 1005. La gestione della tastiera su JVM è incompleta: Tab, Esc, Ctrl+C e i tasti F non arrivano al terminale e Invio manda `\n` invece di `\r`.

Licenza Apache 2.0, un solo maintainer, rilasci frequenti (l'ultimo il 28 ottobre 2026).

## Decisione

Adottiamo `terminal-core` dietro una nostra facciata `core/terminal`, così il resto dell'app non dipende dalla libreria.

- Prepariamo un fork per correggere BCE, corsivo, dim e DA2. La licenza lo permette; proviamo prima a mandare le correzioni a monte.
- Scriviamo noi la codifica dei tasti (frecce in modalità applicazione, Ctrl, Alt, tasti F) e l'input su desktop e Android, senza usare `TerminalInputCapture`.
- Il renderer `terminal-ui` va bene per partire, ma lo consideriamo sostituibile.

## Conseguenze

- La libreria è compilata in bytecode Java 21, quindi tutto il progetto passa a `jvm-target = 21` (vedi 0003).
- Il rischio è il bus factor di un solo maintainer: la facciata e il fork lo contengono.
- JediTerm non è stato valutato, perché questo candidato è sufficiente.
