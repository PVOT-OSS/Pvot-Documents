package org.prauga.documents.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.net.toUri
import com.prauga.pvot.coreui.PvotEmptyContent
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.R

/** Shown in place of an encrypted PDF until its password is entered. [wrongPassword] when the last
 * one tried, [attempt], didn't open it. The password isn't stored anywhere; it lives as long as the viewer. */
@Composable
internal fun PdfPasswordPrompt(
    wrongPassword: Boolean,
    attempt: Int,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the attempt, so a failed try clears the field and the next one starts fresh.
    var password by remember(attempt) { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun submit() {
        if (password.isNotEmpty()) onSubmit(password)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime)
            .verticalScroll(rememberScrollState())
            .padding(PvotSpacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PvotSpacing.L, Alignment.CenterVertically),
    ) {
        PvotEmptyContent(
            message = stringResource(R.string.viewer_pdf_password_message),
            icon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
        )
        PvotTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.viewer_pdf_password_label),
            supportingText = if (wrongPassword) stringResource(R.string.viewer_pdf_password_wrong) else null,
            isError = wrongPassword,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        PvotButton(
            text = stringResource(R.string.viewer_pdf_password_open),
            onClick = ::submit,
            enabled = password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Asks before leaving the app for a link in the PDF, showing where it goes: a document can label
 * a link one thing and point it somewhere else. Only web, mail and phone links are offered. */
@Composable
internal fun OpenLinkDialog(link: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(link) { mutableStateOf(false) }
    PvotDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.viewer_pdf_link_title),
        message = if (failed) stringResource(R.string.viewer_pdf_link_failed) else link,
    ) {
        PvotDialogAction(label = stringResource(R.string.viewer_pdf_cancel), onClick = onDismiss)
        PvotDialogAction(
            label = stringResource(R.string.viewer_pdf_link_open),
            style = PvotDialogActionStyle.Primary,
            enabled = !failed,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, link.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                try {
                    context.startActivity(intent)
                    onDismiss()
                } catch (_: ActivityNotFoundException) {
                    failed = true
                }
            },
        )
    }
}

/** Whether a link from a PDF is one we'll offer to open: other schemes (`file:`, `content:`,
 * `intent:`, `javascript:`) could reach into the device rather than out to the web. */
internal fun isOpenableLink(uri: String): Boolean =
    uri.toUri().scheme?.lowercase() in setOf("http", "https", "mailto", "tel")
