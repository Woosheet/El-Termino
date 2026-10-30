# 0015. Backup cifrato

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Cosa contiene.** Un file `.etbackup` con il workspace e tutti i segreti del vault. Serve a spostare la configurazione tra dispositivi, dato che non c'è sync. Restano fuori le impostazioni del dispositivo (`ui`, `lastSession`) e le registrazioni.

**Cifratura.** Argon2id (64 MiB, 3 passaggi, 1 thread) e AES-256-GCM, con tutta l'intestazione autenticata. BouncyCastle era già tra le dipendenze. I parametri stanno nell'intestazione, ma chi apre il file rifiuta valori oltre 1 GiB, 16 passaggi e 8 thread: un file altrui non deve poter bloccare l'app. Il formato è lo stesso su Mac, Windows, Linux e Android.

**Ripristino.** Prima un'anteprima, poi "Unisci" o "Sostituisci tutto".
- **Unisci:** gli elementi con lo stesso id prendono la versione del backup, gli altri restano. Per i known hosts si aggiungono solo le voci nuove: una chiave diversa per lo stesso host resta quella locale e l'anteprima la segnala, così un backup vecchio non cambia una chiave in silenzio.
- **Sostituisci tutto:** chiede conferma. Le impostazioni del dispositivo restano; l'ultima sessione si tiene solo se i suoi host esistono ancora.

**Niente stati a metà.** Prima di cambiare qualcosa, l'app salva in `backups/` una copia cifrata con la stessa password appena digitata, che si riapre come un backup normale. I segreti entrano sopra i vecchi, poi si salva il workspace, e solo dopo i vecchi escono: un crash non lascia mai riferimenti a segreti mancanti. Se il salvataggio del workspace fallisce, il vault torna com'era.

## Restano fuori

- Le copie di sicurezza in `backups/` non si eliminano da sole.
- Password sbagliata e file danneggiato hanno lo stesso messaggio: GCM non li distingue.
