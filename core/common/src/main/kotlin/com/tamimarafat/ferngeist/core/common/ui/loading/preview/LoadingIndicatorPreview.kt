package com.tamimarafat.ferngeist.core.common.ui.loading.preview

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tamimarafat.ferngeist.core.common.ui.loading.ContainedLoadingIndicator
import com.tamimarafat.ferngeist.core.common.ui.loading.LoadingIndicator

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(name = "Loading indicators · light", showBackground = true)
@Preview(name = "Loading indicators · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoadingIndicatorPreview() {
    MaterialExpressiveTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Morph and pop", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(28.dp, 48.dp, 96.dp).forEach { size ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            LoadingIndicator(modifier = Modifier.size(size))
                            ContainedLoadingIndicator(modifier = Modifier.size(size))
                            Text("${size.value.toInt()} dp", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
