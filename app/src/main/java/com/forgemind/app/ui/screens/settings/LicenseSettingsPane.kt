package com.forgemind.app.ui.screens.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.R
import com.forgemind.app.license.LicenseRejectReason
import com.forgemind.app.license.LicenseStatus
import com.forgemind.app.license.LicenseVerificationResult
import com.forgemind.app.license.OnlineVerifyResult
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType
import com.forgemind.app.ui.theme.MonoFont
import java.text.DateFormat
import java.util.Date

@Composable
fun LicenseSettingsPane(vm: MainViewModel) {
    val state by vm.licenseState.collectAsState()
    val context = LocalContext.current
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var lastErrorReason by remember { mutableStateOf<LicenseRejectReason?>(null) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            containerColor = ForgeColors.SurfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.license_remove_confirm_title),
                    fontSize = ForgeType.Title,
                    fontWeight = FontWeight.SemiBold,
                    color = ForgeColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.license_remove_confirm_body),
                    fontSize = ForgeType.Body,
                    color = ForgeColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        lastErrorReason = null
                        vm.clearLicense()
                    }
                ) {
                    Text(stringResource(R.string.license_remove_action), color = ForgeColors.DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = ForgeColors.TextSecondary)
                }
            }
        )
    }

    // Status card
    val (statusTint, statusIcon, statusTitle) = when (state.status) {
        LicenseStatus.ACTIVE -> Triple(
            ForgeColors.AccentEmerald,
            Icons.Rounded.CheckCircle,
            stringResource(R.string.license_status_active)
        )
        LicenseStatus.EXPIRED -> Triple(
            ForgeColors.AccentAmber,
            Icons.Rounded.Schedule,
            stringResource(R.string.license_status_expired)
        )
        LicenseStatus.INVALID -> Triple(
            ForgeColors.DangerRed,
            Icons.Rounded.ErrorOutline,
            stringResource(R.string.license_status_invalid)
        )
        LicenseStatus.NONE -> Triple(
            ForgeColors.TextSecondary,
            Icons.Rounded.Key,
            stringResource(R.string.license_status_none)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ForgeColors.SurfaceCard)
            .border(1.dp, ForgeColors.BorderSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusTitle,
                    fontSize = ForgeType.Title,
                    fontWeight = FontWeight.SemiBold,
                    color = ForgeColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.license_offline_note),
                    fontSize = ForgeType.Caption,
                    color = ForgeColors.TextSecondary
                )
            }
        }

        val payload = state.payload
        if (payload != null) {
            HorizontalDivider(thickness = 1.dp, color = ForgeColors.Divider)
            LicenseInfoRow(
                label = stringResource(R.string.license_field_sku),
                value = payload.sku.ifBlank { "—" }
            )
            if (payload.email.isNotBlank()) {
                LicenseInfoRow(
                    label = stringResource(R.string.license_field_email),
                    value = payload.email
                )
            }
            if (payload.id.isNotBlank()) {
                LicenseInfoRow(
                    label = stringResource(R.string.license_field_id),
                    value = payload.id,
                    mono = true
                )
            }
            LicenseInfoRow(
                label = stringResource(R.string.license_field_features),
                value = if ("*" in payload.features) {
                    stringResource(R.string.license_features_all)
                } else {
                    payload.features.joinToString(", ")
                }
            )
            LicenseInfoRow(
                label = stringResource(R.string.license_field_expires),
                value = payload.exp?.let { epochSec ->
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(epochSec * 1000L))
                } ?: stringResource(R.string.license_no_expiry)
            )
            payload.seats?.let { seats ->
                LicenseInfoRow(
                    label = stringResource(R.string.license_field_seats),
                    value = seats.toString()
                )
            }
            LicenseInfoRow(
                label = stringResource(R.string.license_field_kid),
                value = payload.kid,
                mono = true
            )
            state.maskedToken?.let { masked ->
                LicenseInfoRow(
                    label = stringResource(R.string.license_field_token),
                    value = masked,
                    mono = true
                )
            }
        } else if (state.rejectReason != null) {
            Text(
                text = licenseRejectReasonText(state.rejectReason!!),
                fontSize = ForgeType.Meta,
                color = ForgeColors.DangerRed
            )
        }

        state.onlineCheckResult?.let { online ->
            val (onlineColor, onlineText) = when (online) {
                is OnlineVerifyResult.Verified -> ForgeColors.AccentEmerald to stringResource(R.string.license_online_ok)
                is OnlineVerifyResult.Rejected -> ForgeColors.AccentAmber to stringResource(
                    R.string.license_online_rejected_fmt,
                    online.message
                )
                is OnlineVerifyResult.NetworkError -> ForgeColors.TextSecondary to stringResource(
                    R.string.license_online_error_fmt,
                    online.message
                )
            }
            Text(
                text = onlineText,
                fontSize = ForgeType.Meta,
                color = onlineColor
            )
        }

        if (state.status != LicenseStatus.NONE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isActive) {
                    OutlinedButton(
                        onClick = { vm.verifyLicenseOnline() },
                        enabled = !state.onlineCheckInProgress,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (state.onlineCheckInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ForgeColors.AccentText
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.license_verify_online),
                            fontSize = ForgeType.Meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    modifier = if (state.isActive) Modifier else Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeColors.DangerRed)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.license_remove_action),
                        fontSize = ForgeType.Meta
                    )
                }
            }
        }
    }

    // Activation input card
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ForgeColors.SurfaceCard)
            .border(1.dp, ForgeColors.BorderSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.license_enter_title),
            fontSize = ForgeType.Title,
            fontWeight = FontWeight.SemiBold,
            color = ForgeColors.TextPrimary
        )
        Text(
            text = stringResource(R.string.license_enter_hint),
            fontSize = ForgeType.Meta,
            color = ForgeColors.TextSecondary
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = {
                tokenInput = it
                lastErrorReason = null
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5,
            placeholder = {
                Text(
                    text = "SF1.eyJ2IjoxLCJraWQiOiJzZi0yMDI2LTExIi4uLn0.signature",
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                    color = ForgeColors.TextTertiary
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = MonoFont,
                fontSize = 12.sp,
                color = ForgeColors.TextPrimary
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ForgeColors.AccentFill,
                unfocusedBorderColor = ForgeColors.BorderSubtle,
                focusedContainerColor = ForgeColors.BgDeep,
                unfocusedContainerColor = ForgeColors.BgDeep
            ),
            shape = RoundedCornerShape(10.dp)
        )

        lastErrorReason?.let { reason ->
            Text(
                text = licenseRejectReasonText(reason),
                fontSize = ForgeType.Meta,
                color = ForgeColors.DangerRed
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (pasted.isNotBlank()) {
                        tokenInput = pasted.trim()
                        lastErrorReason = null
                    }
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.license_paste), fontSize = ForgeType.Meta)
            }

            Button(
                onClick = {
                    when (val result = vm.activateLicense(tokenInput)) {
                        is LicenseVerificationResult.Valid -> {
                            tokenInput = ""
                            lastErrorReason = null
                        }
                        is LicenseVerificationResult.Invalid -> {
                            lastErrorReason = result.reason
                        }
                    }
                },
                enabled = tokenInput.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForgeColors.AccentFill,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = stringResource(R.string.license_activate),
                    fontSize = ForgeType.Body,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun LicenseInfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = ForgeType.Meta,
            color = ForgeColors.TextSecondary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            fontSize = ForgeType.Meta,
            fontFamily = if (mono) MonoFont else null,
            fontWeight = FontWeight.Medium,
            color = ForgeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun licenseRejectReasonText(reason: LicenseRejectReason): String = when (reason) {
    LicenseRejectReason.EMPTY -> stringResource(R.string.license_err_empty)
    LicenseRejectReason.MALFORMED_FORMAT,
    LicenseRejectReason.BAD_PREFIX,
    LicenseRejectReason.BAD_ENCODING -> stringResource(R.string.license_err_format)
    LicenseRejectReason.INVALID_SIGNATURE -> stringResource(R.string.license_err_signature)
    LicenseRejectReason.INVALID_PAYLOAD -> stringResource(R.string.license_err_payload)
    LicenseRejectReason.UNSUPPORTED_VERSION -> stringResource(R.string.license_err_version)
    LicenseRejectReason.WRONG_PRODUCT -> stringResource(R.string.license_err_product)
    LicenseRejectReason.UNKNOWN_KEY_ID -> stringResource(R.string.license_err_kid)
    LicenseRejectReason.EMPTY_FEATURES -> stringResource(R.string.license_err_features)
    LicenseRejectReason.EXPIRED -> stringResource(R.string.license_err_expired)
}
