# 0018. Blocco biometrico

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Cosa protegge.** Lo schermo, non il vault: la chiave resta nel portachiavi di sistema e le sessioni continuano sotto il blocco. Cifrare il vault con la biometria non avrebbe aggiunto molto, e avrebbe fermato riconnessioni e trasferimenti ogni volta.

**Quando.** Opzione delle Impostazioni, spenta di default. All'avvio e dopo 1, 5, 15 o 30 minuti lontano dall'app (5 di default): su Android app non visibile, sul desktop nessuna finestra dell'app attiva. Vale l'orologio di parete, così il computer in stop conta come tempo passato. Un controllo ogni 5 secondi riusa `Platform.isInForeground`.

**Dove.** Android con BiometricPrompt, compreso il ripiego sul codice del dispositivo. macOS con Touch ID e ripiego sulla password di sistema, tramite un piccolo programma Swift (`app-desktop/native/touchid.swift`) perché la JVM non arriva a LocalAuthentication. Windows e Linux: l'opzione non compare.

**Il programma Swift.** Lo compila Gradle su macOS e finisce nelle risorse dell'app. Il pacchetto perde il permesso di esecuzione e l'app installata non va modificata, quindi gira da una copia temporanea. Senza `swiftc` l'app si costruisce senza Touch ID.

**Mai chiusi fuori.** Se l'opzione è attiva ma la biometria non c'è più, l'app si apre con un avviso.

**Sotto il blocco.** Una copertura opaca prende il mouse e la tastiera, e le scorciatoie non funzionano. Le richieste di password in attesa aspettano lo sblocco. I componenti restano vivi: distruggerli avrebbe perso quello che era aperto nelle finestre di dialogo e negli editor.

## Restano fuori

- Una finestra di dialogo già aperta quando scatta il blocco resta visibile sopra la copertura.
- Touch ID e BiometricPrompt vanno provati a mano: i test coprono la logica, non l'hardware.
