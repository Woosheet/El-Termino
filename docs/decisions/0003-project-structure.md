# 0003. Struttura del progetto e toolchain

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

| Modulo | Tipo | Contenuto |
|---|---|---|
| `core/ssh` | KMP (Android + JVM desktop) | Interfacce comuni: `SshConnection`, `ShellChannel`, `SftpClient`, `ConnectTarget`, `AuthMethod` |
| `shared` | KMP + Compose Multiplatform | UI, tema Carta, feature |
| `app-android` | Applicazione Android | `MainActivity`, in futuro il Foreground Service |
| `app-desktop` | JVM + Compose Desktop | Finestra e pacchetti dmg / msi / deb |
| `spikes/*` | JVM, solo test | Prove della fase 0, da cancellare quando non servono più |

Versioni: Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.5.0, Gradle 9.7.1, coroutines 1.11.0.

- I moduli KMP usano il plugin `com.android.kotlin.multiplatform.library` di AGP 9, configurato con `kotlin { android { … } }`. L'app Android è un modulo separato perché quel plugin serve solo per le librerie.
- Target JVM 21 ovunque: la libreria del terminale è compilata in Java 21 (vedi 0002). Il desktop porta con sé il suo runtime, su Android ci pensa D8.
- `compileSdk = 37`, imposto dalle dipendenze androidx di Compose 1.12. `targetSdk = 36`, `minSdk = 26`.
- Il codice SSH vero andrà in un source set condiviso fra Android e desktop, dato che entrambi girano su JVM (vedi 0001).

## Ambiente di test

`testenv/` avvia quattro server OpenSSH 10 in Docker: password e chiavi, OTP via PAM, bastion e un host interno raggiungibile solo dal bastion. Istruzioni e credenziali di test sono in `testenv/README.md`. Dall'emulatore Android il server `basic` si raggiunge su `10.0.2.2:2201`.
