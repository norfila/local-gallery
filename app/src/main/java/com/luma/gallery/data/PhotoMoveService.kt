package com.luma.gallery.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.StringRes
import com.luma.gallery.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhotoMoveLocation(val volume: String, val relativePath: String, val displayName: String)

sealed interface PhotoMoveResult {
    data object Moved : PhotoMoveResult
    data class NeedsPermission(val intentSender: IntentSender) : PhotoMoveResult
    data class Failure(@StringRes val messageRes: Int) : PhotoMoveResult
}

sealed interface PhotoBatchMoveResult {
    /** [firstFailure] explains why the first of the [failed] items stayed where it was. */
    data class Done(val moved: Int, val failed: Int, @StringRes val firstFailure: Int?) : PhotoBatchMoveResult
    data class NeedsPermission(val intentSender: IntentSender, val uris: Set<String>) : PhotoBatchMoveResult
}

/** A MediaStore move retains the row/URI, original bytes, metadata and direct album membership. */
class PhotoMoveService(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun location(photo: Photo): PhotoMoveLocation = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UserMessageException(R.string.move_requires_android_10)
        }
        val uri = mediaUri(photo)
        val volume = MediaStore.getVolumeName(uri)
        if (volume == MediaStore.VOLUME_INTERNAL || volume == MediaStore.VOLUME_EXTERNAL) throw UserMessageException(R.string.move_open_from_library)
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw UserMessageException(R.string.move_item_gone)
            PhotoMoveLocation(volume, cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
        } ?: throw UserMessageException(R.string.move_item_gone)
    }

    suspend fun move(photo: Photo, destination: String, allowPermissionRequest: Boolean = true): PhotoMoveResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext PhotoMoveResult.Failure(R.string.move_requires_android_10)
        }
        val target = PhotoMovePaths.normalize(destination, photo.isVideo)
            ?: return@withContext PhotoMoveResult.Failure(R.string.move_incompatible_folder)
        try {
            val current = location(photo)
            if (current.relativePath == target) return@withContext PhotoMoveResult.Moved
            val uri = mediaUri(photo)
            val changed = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, target)
            }, null, null)
            if (changed != 1) PhotoMoveResult.Failure(R.string.move_failed_removed)
            else PhotoMoveResult.Moved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: SecurityException) {
            if (!allowPermissionRequest) {
                return@withContext PhotoMoveResult.Failure(R.string.move_write_denied)
            }
            try {
                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.createWriteRequest(resolver, listOf(mediaUri(photo))).intentSender
                } else (denied as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                if (sender != null) PhotoMoveResult.NeedsPermission(sender)
                else PhotoMoveResult.Failure(R.string.move_write_unavailable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PhotoMoveResult.Failure(R.string.move_write_unavailable)
            }
        } catch (failure: UserMessageException) {
            PhotoMoveResult.Failure(failure.messageRes)
        } catch (_: IllegalArgumentException) {
            PhotoMoveResult.Failure(R.string.move_not_allowed)
        } catch (_: Exception) {
            PhotoMoveResult.Failure(R.string.move_failed_storage)
        }
    }

    /**
     * Moves every item it can and reports the rest. Items Android protects are asked for together where it allows
     * (Android 11+), otherwise one at a time; [consented] holds the items already asked for, so a refusal that
     * outlives the user's yes ends as a failure instead of a second question.
     */
    suspend fun moveAll(photos: List<Photo>, destination: String, consented: Set<String> = emptySet()): PhotoBatchMoveResult = withContext(Dispatchers.IO) {
        var moved = 0
        var failed = 0
        var firstFailure: Int? = null
        val protectedItems = mutableListOf<Photo>()
        photos.forEach { photo ->
            when (val result = move(photo, destination, allowPermissionRequest = false)) {
                PhotoMoveResult.Moved -> moved++
                is PhotoMoveResult.NeedsPermission -> Unit
                is PhotoMoveResult.Failure ->
                    if (result.messageRes == R.string.move_write_denied && photo.uri !in consented) protectedItems += photo
                    else { failed++; if (firstFailure == null) firstFailure = result.messageRes }
            }
        }
        if (protectedItems.isNotEmpty()) {
            val asking = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) protectedItems else protectedItems.take(1)
            val sender = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.createWriteRequest(resolver, asking.map(::mediaUri)).intentSender
                else (move(asking.single(), destination) as? PhotoMoveResult.NeedsPermission)?.intentSender
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (sender != null) return@withContext PhotoBatchMoveResult.NeedsPermission(sender, asking.mapTo(hashSetOf()) { it.uri })
            failed += protectedItems.size
            if (firstFailure == null) firstFailure = R.string.move_write_unavailable
        }
        PhotoBatchMoveResult.Done(moved, failed, firstFailure)
    }

    private fun mediaUri(photo: Photo): Uri {
        val uri = Uri.parse(photo.libraryUri ?: photo.uri)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY || uri.pathSegments.lastOrNull()?.toLongOrNull() == null) {
            throw UserMessageException(R.string.move_open_from_library)
        }
        return uri
    }
}
