# 0017. SSH agent e agent forwarding

Data: 30 ottobre 2026. Stato: accettata.

## Decisione

**Dove.** Solo sul desktop: `SSH_AUTH_SOCK` con i socket Unix del JDK su macOS e Linux, la named pipe di OpenSSH e poi quella di Pageant su Windows. Su Android non compare.

**Quanto codice.** Il protocollo è quello di MINA (`AbstractAgentProxy`); nostro è solo il trasporto, con una connessione per richiesta e un limite di 256 KiB sulle risposte.

**Login.** Nell'identità c'è la spunta "Usa l'SSH agent". Si prova per primo, poi chiave e password come sempre. Un agent assente o vuoto non è un errore: si passa al metodo dopo, compresa la richiesta di password. Se il login fallisce comunque, il messaggio dice che l'agent non c'era o era vuoto.

**Un agent per sessione.** Il client MINA è unico, quindi ogni sessione dice se può vedere l'agent. Senza questo le chiavi dell'agent verrebbero offerte a qualunque host, anche a quelli che non l'hanno chiesto.

**Forwarding.** Opzione per host, spenta di default: chi è root sul server può usare le chiavi finché la sessione è aperta. Vale solo per la destinazione, mai per i jump host, e solo per le shell. Il canale `auth-agent@openssh.com` si accetta solo sulle sessioni con l'opzione attiva: un server non può aprirlo da solo.

**Test.** `AgentClientTest` e `AgentLoginTest` contro un `ssh-agent` vero e i server di test; `AgentPlanTest` per l'ordine dei metodi.

## Restano fuori

- Windows non è stato provato: le due pipe vanno verificate a mano con l'agent di OpenSSH e con Pageant.
- Un agent con molte chiavi può esaurire `MaxAuthTries` del server, come con OpenSSH.
