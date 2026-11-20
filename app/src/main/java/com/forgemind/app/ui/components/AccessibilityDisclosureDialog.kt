package com.forgemind.app.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.forgemind.app.R
import com.forgemind.app.ui.theme.ForgeColors

/**
 * Prominent disclosure and explicit consent dialog shown before launching
 * Android's system Accessibility Settings, per Google Play User Data Policy.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = ForgeColors.SurfaceCard,
        titleContentColor = ForgeColors.TextPrimary,
        textContentColor = ForgeColors.TextSecondary,
        title = {
            Text(
                text = stringResource(R.string.a11y_disclosure_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = ForgeColors.TextPrimary
            )
        },
        text = {
            Text(
                text = stringResource(R.string.a11y_disclosure_body),
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = ForgeColors.TextSecondary,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentEmerald)
            ) {
                Text(
                    text = stringResource(R.string.a11y_disclosure_accept),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(
                    text = stringResource(R.string.a11y_disclosure_decline),
                    color = ForgeColors.TextSecondary
                )
            }
        }
    )
}
