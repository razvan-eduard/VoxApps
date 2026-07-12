package com.voxapps.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A single permission row's presentation state — the caller owns the actual Android permission APIs. */
data class OnboardingPermission(
    val title: String,
    val description: String,
    val granted: Boolean,
    val onRequest: () -> Unit
)

/**
 * First-launch permissions screen shared by the satellite apps (mirrors vox-commander's
 * PermissionsOnboardingScreen row pattern, generalized). Continue is always enabled regardless of
 * grant state — same no-block policy as Commander's screen.
 */
@Composable
fun OnboardingPermissionsScreen(
    title: String,
    intro: String,
    permissions: List<OnboardingPermission>,
    grantedLabel: String,
    requiredLabel: String,
    continueLabel: String,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = intro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        permissions.forEach { permission ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = permission.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = permission.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Button(
                        onClick = permission.onRequest,
                        enabled = !permission.granted,
                        colors = if (permission.granted) {
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32),
                                disabledContainerColor = Color(0xFF2E7D32),
                                disabledContentColor = Color.White
                            )
                        } else {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        }
                    ) {
                        Text(if (permission.granted) grantedLabel else requiredLabel)
                    }
                }
            }
        }
        Button(
            onClick = onContinue,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(continueLabel)
        }
    }
}
