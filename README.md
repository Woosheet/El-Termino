# El Termino

Client SSH e SFTP personale in Kotlin Multiplatform per Android, macOS, Windows e Linux. Il piano completo è in [docs/PLAN.md](docs/PLAN.md), le scelte tecniche in [docs/decisions/](docs/decisions/).

## Requisiti

- JDK 21
- Android SDK con la piattaforma 37 (percorso in `local.properties`, `sdk.dir=…`)
- Docker, per i server di test

## Comandi

```bash
./gradlew :app-desktop:run                 # app desktop
./gradlew :app-android:installDebug        # app Android su emulatore o dispositivo
```

I dati del desktop stanno in `~/Library/Application Support/El Termino` (macOS), `%APPDATA%\El Termino` (Windows) o `~/.local/share/el-termino` (Linux). Si possono spostare con `-Deltermino.dataDir=…`. La chiave del vault va nel portachiavi di sistema.

## Pacchetti desktop

Il DMG va creato con un JDK 21 che non sia quello di Homebrew: quello si porta dietro librerie di `/opt/homebrew` che sugli altri Mac mancano. Per esempio con Amazon Corretto:

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-21.0.13/Contents/Home ./gradlew :app-desktop:packageDmg
```

Il file esce in `app-desktop/build/compose/binaries/main/dmg/`. Funziona sui Mac con Apple Silicon e non è firmato con un Developer ID, quindi chi lo scarica deve aprirlo la prima volta da Impostazioni di Sistema → Privacy e sicurezza → «Apri comunque». MSI e DEB si creano allo stesso modo su Windows e Linux (`packageMsi`, `packageDeb`). L'icona si rigenera da `app-desktop/packaging/` con `java MakeIcons.java` e poi `iconutil -c icns icon.iconset`.

## Test

Molti test girano contro i server OpenSSH di `testenv/`:

```bash
./testenv/gen-keys.sh
docker compose -f testenv/docker-compose.yml up -d --build
./gradlew :core:ssh:desktopTest :core:terminal:desktopTest :core:importer:desktopTest :core:data:desktopTest :core:vault:desktopTest :shared:desktopTest
./gradlew :app-android:connectedDebugAndroidTest   # con un emulatore acceso
```

- `:shared:desktopTest` prova il flusso di connessione completo e salva gli snapshot dell'interfaccia in `shared/build/snapshots/`. Copre host key, password, OTP, chiavi, jump host e import, ma anche connessioni condivise, riconnessione dopo una caduta di rete simulata, broadcast e snippet.
- `TransferFlowTest` copia file e cartelle tra basic, bastion, internal e una cartella locale in una sola coda. Prova anche pausa e ripresa, i conflitti, una caduta di rete a metà, l'editor remoto e le operazioni del pannello file.
- `TabLayoutFlowTest` salva una disposizione con gruppi e una scheda divisa, ricrea l'app sugli stessi file e la riapre.
- `SessionSoakTest` tiene 10 sessioni occupate per 20 secondi. Per la prova lunga della Fase 2:

  ```bash
  ./gradlew :shared:desktopTest --tests '*SessionSoakTest*' -PsoakSeconds=14400
  ```
- `spikes/` contiene le prove della Fase 0 (`./gradlew :spikes:mina:test` e simili).
