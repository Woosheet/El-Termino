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
./gradlew :app-desktop:test                # snapshot della finestra in app-desktop/build/snapshots/
```

Server di test e spike della fase 0:

```bash
./testenv/gen-keys.sh
docker compose -f testenv/docker-compose.yml up -d --build
./gradlew :spikes:mina:test :spikes:cbssh:test :spikes:terminal:test
./gradlew :app-android:connectedDebugAndroidTest   # MINA su Android, con un emulatore acceso
```
