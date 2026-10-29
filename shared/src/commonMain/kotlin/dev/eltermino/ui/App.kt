package dev.eltermino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eltermino.ui.theme.Carta
import dev.eltermino.ui.theme.CartaTheme

@Composable
fun App() {
    CartaTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Carta.colors.background)) {
            if (maxWidth < 720.dp) {
                HostListPane(Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        HostListPane(Modifier.width(240.dp).fillMaxHeight())
                        VerticalDivider(color = Carta.colors.line)
                        EmptySessionPane(Modifier.weight(1f).fillMaxHeight())
                    }
                    HorizontalDivider(color = Carta.colors.line)
                    StatusBar()
                }
            }
        }
    }
}

@Composable
private fun HostListPane(modifier: Modifier) {
    Column(modifier.background(Carta.colors.chrome).padding(12.dp)) {
        Text("Host", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Carta.colors.text)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Carta.colors.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text("Cerca host", fontSize = 13.sp, color = Carta.colors.textDim)
        }
        Spacer(Modifier.height(24.dp))
        Text("Aggiungi il primo host", fontWeight = FontWeight.Medium, color = Carta.colors.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "Oppure importa i tuoi host da Termius o da ~/.ssh/config.",
            fontSize = 13.sp,
            color = Carta.colors.textDim,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {}) { Text("Nuovo host") }
    }
}

@Composable
private fun EmptySessionPane(modifier: Modifier) {
    Column(
        modifier.background(Carta.colors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nessuna sessione aperta", style = MaterialTheme.typography.titleMedium, color = Carta.colors.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "Scegli un host a sinistra per aprire un terminale o una sessione SFTP.",
            fontSize = 13.sp,
            color = Carta.colors.textDim,
        )
    }
}

@Composable
private fun StatusBar() {
    Row(
        Modifier.fillMaxWidth().background(Carta.colors.chrome).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Nessuna connessione attiva", fontSize = 12.sp, color = Carta.colors.textDim)
        Text("0 trasferimenti", fontSize = 12.sp, color = Carta.colors.textDim)
    }
}
