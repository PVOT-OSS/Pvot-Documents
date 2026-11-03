package org.prauga.documents.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import com.prauga.pvot.designsystem.platform.PvotBaseActivity
import com.prauga.pvot.designsystem.theme.PvotAppTheme
import org.prauga.documents.util.ViewerFormat

/**
 * Hosts one document's viewer, full screen. [start] is the only way in: it carries the URI, name
 * and [ViewerFormat] the caller already resolved — this activity renders, it doesn't decide what
 * it's able to open.
 */
class DocumentViewerActivity : PvotBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = requireNotNull(intent.getStringExtra(ExtraUri)).toUri()
        val name = intent.getStringExtra(ExtraName).orEmpty()
        val format = ViewerFormat.valueOf(requireNotNull(intent.getStringExtra(ExtraFormat)))
        setPvotContent {
            PvotAppTheme {
                DocumentViewerScreen(
                    uri = uri,
                    name = name,
                    format = format,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        private const val ExtraUri = "uri"
        private const val ExtraName = "name"
        private const val ExtraFormat = "format"

        fun start(context: Context, uri: Uri, name: String, format: ViewerFormat) {
            context.startActivity(
                Intent(context, DocumentViewerActivity::class.java).apply {
                    putExtra(ExtraUri, uri.toString())
                    putExtra(ExtraName, name)
                    putExtra(ExtraFormat, format.name)
                }
            )
        }
    }
}
