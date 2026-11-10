package com.kkorpse.less.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun loadLibrary(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            val images = queryImages()
            val videos = queryVideos()
            val items = (images + videos).sortedWith(
                compareByDescending<MediaItem> { it.dateMillis }
                    .thenByDescending { it.id }
            )
            Result.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadPicked(uris: List<Uri>): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            Result.success(uris.mapNotNull { queryUri(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun queryImages(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE
        )
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            baseSelection(),
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor -> readMediaStoreRows(cursor, isVideo = false) } ?: emptyList()
    }

    private fun queryVideos(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION
        )
        return context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            baseSelection(),
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor -> readMediaStoreRows(cursor, isVideo = true) } ?: emptyList()
    }

    private fun baseSelection(): String {
        val clauses = mutableListOf("${MediaStore.MediaColumns.SIZE} > 0")
        clauses += "${MediaStore.MediaColumns.IS_PENDING} = 0"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            clauses += "${MediaStore.MediaColumns.IS_TRASHED} = 0"
        }
        return clauses.joinToString(" AND ")
    }

    private fun readMediaStoreRows(
        cursor: android.database.Cursor,
        isVideo: Boolean
    ): List<MediaItem> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val durationColumn = if (isVideo) {
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        } else {
            -1
        }

        val result = ArrayList<MediaItem>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val dateTaken = cursor.getLong(takenColumn)
            val dateModified = cursor.getLong(modifiedColumn) * 1000L
            val uri = if (isVideo) {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            } else {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            result += MediaItem(
                id = id,
                uri = uri,
                name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }
                    ?: context.getString(com.kkorpse.less.R.string.unnamed),
                mimeType = cursor.getString(mimeColumn)
                    ?: if (isVideo) "video/*" else "image/*",
                isVideo = isVideo,
                dateMillis = if (dateTaken > 0L) dateTaken else dateModified,
                durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L,
                width = cursor.getInt(widthColumn),
                height = cursor.getInt(heightColumn),
                sizeBytes = cursor.getLong(sizeColumn)
            )
        }
        return result
    }

    private fun queryUri(uri: Uri): MediaItem? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "image/*"
        var name: String? = null
        var size = 0L

        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }

        val isVideo = mimeType.startsWith("video/")
        val displayName = name?.takeIf { it.isNotBlank() }
        // Photo Picker 出于隐私会隐藏原始文件名，只返回数字 ID；这时用中性名称代替。
        val nameIsRedacted = displayName != null &&
            displayName.substringBeforeLast('.').all { it.isDigit() }
        val resolvedName = if (displayName == null || nameIsRedacted) {
            context.getString(
                if (isVideo) com.kkorpse.less.R.string.picked_video
                else com.kkorpse.less.R.string.picked_photo
            )
        } else {
            displayName
        }
        return MediaItem(
            id = 0L,
            uri = uri,
            name = resolvedName,
            mimeType = mimeType,
            isVideo = isVideo,
            dateMillis = System.currentTimeMillis(),
            durationMs = 0L,
            width = 0,
            height = 0,
            sizeBytes = size
        )
    }
}
