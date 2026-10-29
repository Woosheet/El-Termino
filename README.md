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

## Test

Molti test girano contro i server OpenSSH di `testenv/`:

```bash
./testenv/gen-keys.sh
docker compose -f testenv/docker-compose.yml up -d --build
./gradlew :core:ssh:desktopTest :core:terminal:desktopTest :core:importer:desktopTest :core:data:desktopTest :core:vault:desktopTest :shared:desktopTest
./gradlew :app-android:connectedDebugAndroidTest   # con un emulatore acceso
```

- `:shared:desktopTest` prova il flusso di connessione completo (host key, password, OTP, chiavi, jump host, import) e salva gli snapshot dell'interfaccia in `shared/build/snapshots/`.
- `spikes/` contiene le prove della Fase 0 (`./gradlew :spikes:mina:test` e simili).
