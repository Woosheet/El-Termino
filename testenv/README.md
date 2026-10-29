# Server di test

Quattro server OpenSSH in Docker, uno per ogni scenario di autenticazione. Servono solo in locale: tutte le credenziali qui sotto sono finte e non vanno usate altrove.

```bash
./testenv/gen-keys.sh
docker compose -f testenv/docker-compose.yml up -d --build
```

| Servizio | Indirizzo | Autenticazione |
|---|---|---|
| `basic` | `127.0.0.1:2201` | password, chiavi Ed25519 / RSA / ECDSA, chiave con passphrase, certificato utente. Host key firmata da una CA. |
| `otp` | `127.0.0.1:2202` | keyboard-interactive: password e poi codice TOTP |
| `bastion` | `127.0.0.1:2203` | solo chiavi. Fa da jump host verso `internal`. |
| `internal` | `internal:22`, solo tramite `bastion` | solo chiavi |

Credenziali di test:

- utente `dev`, password `devpass`
- passphrase di `keys/id_ed25519_enc`: `testpass`
- segreto TOTP (base32): `JBSWY3DPEHPK3PXP`
- `keys/id_cert` + `keys/id_cert-cert.pub`: certificato firmato da `keys/user_ca` per l'utente `dev`
- `keys/known_hosts_ca`: riga `@cert-authority` per verificare la host key di `basic`

Le chiavi in `keys/` vengono generate da `gen-keys.sh` e sono escluse da git.

Sugli host sono installati vim, htop, tmux e mc per provare l'emulatore di terminale.

Ogni host ha anche un piccolo servizio HTTP su `127.0.0.1:8080`, raggiungibile solo dall'interno della macchina, per la scheda Richieste:

- `GET /health.json` risponde `{"status":"ok","host":"<nome dell'host>"}`;
- `/cgi-bin/echo` risponde con metodo, query e corpo ricevuti.

Sugli host c'è anche `curl`, per "Esegui con curl sul server".
