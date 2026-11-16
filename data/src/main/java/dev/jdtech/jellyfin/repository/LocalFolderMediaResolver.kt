package dev.jdtech.jellyfin.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Resolves a Jellyfin item to a video in the user-selected SAF folder.
 *
 * Only direct children are scanned intentionally: the configured folder represents one flat
 * media directory. No filesystem paths are fabricated; playback receives the persisted content URI.
 */
@Singleton
class LocalFolderMediaResolver
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    suspend fun resolve(itemId: UUID, itemName: String): FindroidSource? =
        withContext(Dispatchers.IO) {
            val tree = preferences.getValue(preferences.localMediaFolder)?.let(Uri::parse)
                ?: return@withContext null
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                val target = normalize(itemName)
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    val idColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val displayName = c.getString(nameColumn) ?: continue
                        val mime = c.getString(mimeColumn)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        if (!isVideo(displayName, mime)) continue
                        val candidate = normalize(displayName.substringBeforeLast('.', displayName))
                        val exactMatch = candidate == target
                        val yearSuffixMatch =
                            candidate.matches(Regex("${Regex.escape(target)}(19|20)\\\\d{2}"))
                        if (!exactMatch && !yearSuffixMatch) continue

                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            c.getString(idColumn),
                        )
                        return@withContext FindroidSource(
                            id = "local-folder-$itemId",
                            name = displayName,
                            type = FindroidSourceType.LOCAL,
                            path = documentUri.toString(),
                            size = 0L,
                            mediaStreams = emptyList<FindroidMediaStream>(),
                        )
                    }
                }
            } catch (e: SecurityException) {
                Timber.w(e, "Local media folder permission is no longer available")
            } catch (e: Exception) {
                Timber.w(e, "Unable to resolve local media for $itemId")
            }
            null
        }

    private fun normalize(value: String): String =
        value.substringBeforeLast('.', value)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun isVideo(name: String, mime: String?): Boolean {
        if (mime?.startsWith("video/") == true) return true
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "mpeg", "mpg", "ts")
    }
}
