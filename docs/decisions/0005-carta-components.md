# 0005. Componenti UI Carta al posto di Material

Data: 29 ottobre 2026. Stato: accettata.

## Decisione

L'interfaccia non usa i componenti di Material3. Ha una sua piccola libreria in `shared/src/commonMain/kotlin/dev/eltermino/ui/components/`, costruita solo su `compose.foundation` e `compose.ui`:

| Componente | Note |
|---|---|
| `Text`, `Icon` | Ereditano `LocalTextStyle` e `LocalContentColor` dal tema Carta |
| `Button` (`Primary`, `Secondary`, `Ghost`, `Danger`), `IconButton`, `Fab` | Tutti sopra `Pressable` |
| `Pressable` | Base comune: sfondo per hover e pressione, anello di focus da tastiera, cursore a mano sul desktop |
| `Field`, `SearchField`, `Checkbox` | `BasicTextField` con etichetta sopra, bordo accent al focus e rosso in caso di errore |
| `Dialog`, `Menu`, `MenuItem`, `Picker` | Finestre e popup di `compose.ui.window`, con posizionamento sotto il campo che li apre |
| `Divider`, `Spinner`, `StatusDot`, `Badge` | |

Il tema (`CartaTheme`) fornisce colori e tipografia tramite `Carta.colors` e `Carta.type`, senza `MaterialTheme`. Nuove dimensioni o colori si aggiungono come token, non nei singoli componenti.

## Perché

Lo stile Carta deve somigliare a un'app nativa sobria, non a un'app Material. Con componenti nostri ogni stato (hover, focus, errore, disabilitato) è sotto controllo, e l'aspetto è uguale su desktop e Android.

## Conseguenze

- `compose-material3` non è più una dipendenza diretta di `shared`. Arriva comunque indirettamente tramite `terminal-ui` (il renderer della libreria del terminale), ma il nostro codice non lo importa.
- Le icone vengono ancora da `material-icons-core`: sono solo vettori, non componenti, e si possono sostituire con un set nostro.
- I componenti vanno tenuti verificati con gli snapshot di `:shared:desktopTest` (`shared/build/snapshots/`).
