/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lineageos.glimpse.ext.*
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.MediaUri
import org.lineageos.glimpse.recyclerview.MediaViewerAdapter
import org.lineageos.glimpse.ui.MediaInfoBottomSheetDialog
import org.lineageos.glimpse.utils.MediaStoreBuckets
import org.lineageos.glimpse.utils.PermissionsGatedCallback
import org.lineageos.glimpse.viewmodels.MediaViewerViewModel
import org.lineageos.glimpse.viewmodels.QueryResult.Data
import org.lineageos.glimpse.viewmodels.QueryResult.Empty
import java.text.SimpleDateFormat

/**
 * An activity used to view one or mode medias.
 */
class ViewActivity : AppCompatActivity() {
    // View models
    private val mediaViewModel: MediaViewerViewModel by viewModels {
        albumId?.let {
            assert(it != MediaStoreBuckets.MEDIA_STORE_BUCKET_PLACEHOLDER.id) {
                "MEDIA_STORE_BUCKET_PLACEHOLDER found, view model initialized too early"
            }

            MediaViewerViewModel.factory(application, it)
        } ?: MediaViewerViewModel.factory(application)
    }

    // Views
    private val adjustButton by lazy { findViewById<ImageButton>(R.id.adjustButton) }
    private val backButton by lazy { findViewById<ImageButton>(R.id.backButton) }
    private val bottomSheetLinearLayout by lazy { findViewById<LinearLayout>(R.id.bottomSheetLinearLayout) }
    private val contentView by lazy { findViewById<View>(android.R.id.content) }
    private val dateTextView by lazy { findViewById<TextView>(R.id.dateTextView) }
    private val deleteButton by lazy { findViewById<ImageButton>(R.id.deleteButton) }
    private val favoriteButton by lazy { findViewById<ImageButton>(R.id.favoriteButton) }
    private val infoButton by lazy { findViewById<ImageButton>(R.id.infoButton) }
    private val shareButton by lazy { findViewById<ImageButton>(R.id.shareButton) }
    private val timeTextView by lazy { findViewById<TextView>(R.id.timeTextView) }
    private val topSheetConstraintLayout by lazy { findViewById<ConstraintLayout>(R.id.topSheetConstraintLayout) }
    private val viewPager by lazy { findViewById<ViewPager2>(R.id.viewPager) }

    // System services
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    // Player
    private val exoPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)

            contentView.keepScreenOn = isPlaying
        }
    }

    private val exoPlayerLazy = lazy {
        ExoPlayer.Builder(this).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE

            addListener(exoPlayerListener)
        }
    }

    private val exoPlayer
        get() = if (exoPlayerLazy.isInitialized()) {
            exoPlayerLazy.value
        } else {
            null
        }

    private var lastVideoUriPlayed: Uri? = null

    // Adapter
    private val mediaViewerAdapter by lazy {
        MediaViewerAdapter(exoPlayerLazy, mediaViewModel)
    }

    // okhttp
    private val httpClient = OkHttpClient()

    // Media
    private var media: Media? = null
    private var albumId: Int? = MediaStoreBuckets.MEDIA_STORE_BUCKET_PLACEHOLDER.id
    private var additionalMedias: Array<Media>? = null
    private var mediaUri: MediaUri? = null
    private var secure = false

    private var lastTrashedMedia: Media? = null
    private var undoTrashSnackbar: Snackbar? = null

    /**
     * Check if we're showing a static set of medias.
     */
    private val readOnly
        get() = mediaUri != null || additionalMedias != null || albumId == null || secure

    // Contracts
    private val deleteUriContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            Snackbar.make(
                bottomSheetLinearLayout,
                resources.getQuantityString(
                    if (it.resultCode == Activity.RESULT_CANCELED) {
                        R.plurals.file_deletion_unsuccessful
                    } else {
                        R.plurals.file_deletion_successful
                    },
                    1, 1
                ),
                Snackbar.LENGTH_LONG,
            ).setAnchorView(bottomSheetLinearLayout).show()
        }

    private val trashUriContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != Activity.RESULT_CANCELED

            Snackbar.make(
                bottomSheetLinearLayout,
                resources.getQuantityString(
                    if (succeeded) {
                        R.plurals.file_trashing_successful
                    } else {
                        R.plurals.file_trashing_unsuccessful
                    },
                    1, 1
                ),
                Snackbar.LENGTH_LONG,
            ).apply {
                anchorView = bottomSheetLinearLayout
                lastTrashedMedia?.takeIf { succeeded }?.let { trashedMedia ->
                    setAction(R.string.file_trashing_undo) {
                        trashMedia(trashedMedia, false)
                    }
                }
                undoTrashSnackbar = this
            }.show()

            lastTrashedMedia = null
        }

    private val restoreUriFromTrashContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            Snackbar.make(
                bottomSheetLinearLayout,
                resources.getQuantityString(
                    if (it.resultCode == Activity.RESULT_CANCELED) {
                        R.plurals.file_restoring_from_trash_unsuccessful
                    } else {
                        R.plurals.file_restoring_from_trash_successful
                    },
                    1, 1
                ),
                Snackbar.LENGTH_LONG,
            ).setAnchorView(bottomSheetLinearLayout).show()
        }

    private val favoriteContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            mediaViewerAdapter.getItemAtPosition(viewPager.currentItem).let {
                favoriteButton.isSelected = it.isFavorite
            }
        }

    private val onPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)

            if (mediaViewerAdapter.itemCount <= 0) {
                // No medias, bail out
                finish()
                return
            }

            this@ViewActivity.mediaViewModel.mediaPosition = position

            mediaUri?.also {
                updateExoPlayer(it.mediaType, it.externalContentUri)
            } ?: run {
                val media = mediaViewerAdapter.getItemAtPosition(position)

                dateTextView.text = dateFormatter.format(media.dateAdded)
                timeTextView.text = timeFormatter.format(media.dateAdded)
                favoriteButton.isSelected = media.isFavorite
                deleteButton.setImageResource(
                    when (media.isTrashed) {
                        true -> R.drawable.ic_restore_from_trash
                        false -> R.drawable.ic_delete
                    }
                )

                updateExoPlayer(media.mediaType, media.externalContentUri)
            }
        }
    }

    private val mediaInfoBottomSheetDialogCallbacks = MediaInfoBottomSheetDialog.Callbacks(this)

    // Permissions
    private val permissionsGatedCallback = PermissionsGatedCallback(this) {
        lifecycleScope.launch {
            val intentHandled = handleIntent(intent)

            if (!intentHandled) {
                finish()

                return@launch
            }

            // Update UI
            updateUI()

            // Here we now do a bunch of view model related stuff because we can now initialize it
            // with the now correctly defined album ID

            // Attach the adapter to the view pager
            viewPager.adapter = mediaViewerAdapter

            // Observe fullscreen mode
            mediaViewModel.fullscreenModeLiveData.observe(this@ViewActivity) { fullscreenMode ->
                topSheetConstraintLayout.fade(!fullscreenMode)
                bottomSheetLinearLayout.fade(!fullscreenMode)

                window.setBarsVisibility(systemBars = !fullscreenMode)

                // If the sheets are being made visible again, update the values
                if (!fullscreenMode) {
                    updateSheetsHeight()
                }
            }

            mediaUri?.also {
                initData(it)
            } ?: additionalMedias?.also { additionalMedias ->
                val medias = media?.let {
                    arrayOf(it) + additionalMedias
                } ?: additionalMedias

                initData(medias.toSet().sortedByDescending { it.dateAdded })
            } ?: albumId?.also {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    mediaViewModel.media.collectLatest { data ->
                        when (data) {
                            is Data -> initData(data.values)
                            is Empty -> Unit
                        }
                    }
                }
            } ?: media?.also {
                initData(listOf(it))
            } ?: initData(listOf())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_view)

        // Setup edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // We only want to show this activity on top of the keyguard if we're being launched with
        // the ACTION_REVIEW_SECURE intent and the system is currently locked.
        if (keyguardManager.isKeyguardLocked && intent.action == MediaStore.ACTION_REVIEW_SECURE) {
            setShowWhenLocked(true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Avoid updating the sheets height when they're hidden.
            // Once the system bars will be made visible again, this function
            // will be called again.
            if (mediaViewModel.fullscreenModeLiveData.value != true) {
                topSheetConstraintLayout.updatePadding(
                    left = insets.left,
                    right = insets.right,
                    top = insets.top,
                )
                bottomSheetLinearLayout.updatePadding(
                    bottom = insets.bottom,
                    left = insets.left,
                    right = insets.right,
                )

                updateSheetsHeight()
            }

            windowInsets
        }

        backButton.setOnClickListener {
            finish()
        }

        favoriteButton.setOnClickListener {
            mediaViewerAdapter.getItemAtPosition(viewPager.currentItem).let {
                favoriteContract.launch(
                    contentResolver.createFavoriteRequest(
                        !it.isFavorite, it.externalContentUri
                    )
                )
            }
        }

        shareButton.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    mediaUri?.let {
                        buildShareIntent(it)
                    } ?: buildShareIntent(
                        mediaViewerAdapter.getItemAtPosition(viewPager.currentItem)
                    ),
                    null
                )
            )
        }

        infoButton.setOnClickListener {
            mediaViewerAdapter.getItemAtPosition(viewPager.currentItem).let {
                MediaInfoBottomSheetDialog(
                    this, it, mediaInfoBottomSheetDialogCallbacks, secure
                ).show()
            }
        }

        adjustButton.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    buildEditIntent(
                        mediaViewerAdapter.getItemAtPosition(viewPager.currentItem)
                    ),
                    null
                )
            )
        }

        deleteButton.setOnClickListener {
            trashMedia(mediaViewerAdapter.getItemAtPosition(viewPager.currentItem))
        }

        deleteButton.setOnLongClickListener {
            mediaViewerAdapter.getItemAtPosition(viewPager.currentItem).let {
                MaterialAlertDialogBuilder(this).setTitle(R.string.file_deletion_confirm_title)
                    .setMessage(
                        resources.getQuantityString(
                            R.plurals.file_deletion_confirm_message, 1, 1
                        )
                    ).setPositiveButton(android.R.string.ok) { _, _ ->
                        deleteUriContract.launch(
                            contentResolver.createDeleteRequest(
                                it.externalContentUri
                            )
                        )
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        // Do nothing
                    }
                    .show()

                true
            }

            false
        }

        viewPager.offscreenPageLimit = 2
        viewPager.registerOnPageChangeCallback(onPageChangeCallback)

        permissionsGatedCallback.runAfterPermissionsCheck()
    }

    override fun onResume() {
        super.onResume()

        exoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()

        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()

        exoPlayer?.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        updateSheetsHeight()
    }

    private fun initData(data: List<Media>) {
        mediaViewerAdapter.data = data.toTypedArray()

        // If we already have a position, keep that, else get one from
        // the passed media, else go to the first one
        val mediaPosition = mediaViewModel.mediaPosition ?: media?.let { media ->
            mediaViewerAdapter.data.indexOfFirst {
                it.id == media.id
            }.takeUnless {
                it == -1
            }
        } ?: 0

        mediaViewModel.mediaPosition = mediaPosition

        viewPager.setCurrentItem(mediaPosition, false)
        onPageChangeCallback.onPageSelected(mediaPosition)
    }

    private fun initData(mediaUri: MediaUri) {
        mediaViewerAdapter.mediaUri = mediaUri

        // We have a single element
        val mediaPosition = 0

        mediaViewModel.mediaPosition = mediaPosition

        viewPager.setCurrentItem(mediaPosition, false)
        onPageChangeCallback.onPageSelected(mediaPosition)
    }

    /**
     * Update the UI elements (such as buttons) based on the current setup.
     * Must be run on UI thread.
     */
    private fun updateUI() {
        // Set UI elements visibility based on initial arguments
        val readOnly = readOnly
        val shouldShowMediaButtons = mediaUri == null

        dateTextView.isVisible = shouldShowMediaButtons
        timeTextView.isVisible = shouldShowMediaButtons

        favoriteButton.isVisible = !readOnly
        shareButton.isVisible = !secure
        infoButton.isVisible = shouldShowMediaButtons
        adjustButton.isVisible = !readOnly
        deleteButton.isVisible = !readOnly

        // Update paddings
        updateSheetsHeight()
    }

    /**
     * Update [exoPlayer]'s status.
     * @param mediaType The currently displayed media's [MediaType]
     * @param uri The The currently displayed media's [Uri]
     */
    private fun updateExoPlayer(mediaType: MediaType, uri: Uri) {
        if (mediaType == MediaType.VIDEO) {
            with(exoPlayerLazy.value) {
                if (uri != lastVideoUriPlayed) {
                    lastVideoUriPlayed = uri
                    setMediaItem(MediaItem.fromUri(uri))
                    seekTo(C.TIME_UNSET)
                    prepare()
                    playWhenReady = true
                }
            }
        } else {
            exoPlayer?.stop()

            // Make sure we will forcefully reload and restart the video
            lastVideoUriPlayed = null
        }
    }

    private fun trashMedia(media: Media, trash: Boolean = !media.isTrashed) {
        if (trash) {
            lastTrashedMedia = media
        }

        val contract = when (trash) {
            true -> trashUriContract
            false -> restoreUriFromTrashContract
        }

        contract.launch(
            contentResolver.createTrashRequest(
                trash, media.externalContentUri
            )
        )
    }

    private fun updateSheetsHeight() {
        topSheetConstraintLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        bottomSheetLinearLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        mediaViewModel.sheetsHeightLiveData.value = Pair(
            topSheetConstraintLayout.measuredHeight,
            bottomSheetLinearLayout.measuredHeight,
        )
    }

    /**
     * Handle the received [Intent], parse it and set variables accordingly.
     * Must not be executed on main thread.
     * @param intent The received intent
     * @return true if the intent has been handled, false otherwise
     */
    private fun handleIntent(intent: Intent) = when (intent.action) {
        Intent.ACTION_VIEW -> handleView(intent)
        MediaStore.ACTION_REVIEW -> handleReview(intent)
        MediaStore.ACTION_REVIEW_SECURE -> handleReview(intent, true)
        else -> run {
            runOnUiThread {
                Toast.makeText(
                    this,
                    R.string.intent_action_not_supported,
                    Toast.LENGTH_SHORT
                ).show()
            }

            false
        }
    }

    /**
     * Handle a [Intent.ACTION_VIEW] intent (view a single media, controls also read-only).
     * Must not be executed on main thread.
     * @param intent The received intent
     * @param secure Whether we should show this media in a secure manner
     * @return true if the intent has been handled, false otherwise
     */
    private fun handleView(intent: Intent, secure: Boolean = false): Boolean {
        val uri = intent.data ?: run {
            runOnUiThread {
                Toast.makeText(
                    this,
                    R.string.intent_media_not_found,
                    Toast.LENGTH_SHORT
                ).show()
            }

            return false
        }

        val dataType = intent.type ?: getContentType(uri) ?: run {
            runOnUiThread {
                Toast.makeText(
                    this,
                    R.string.intent_media_type_not_found,
                    Toast.LENGTH_SHORT
                ).show()
            }

            return false
        }

        val uriType = MediaType.fromMimeType(dataType) ?: run {
            runOnUiThread {
                Toast.makeText(
                    this,
                    R.string.intent_media_type_not_supported,
                    Toast.LENGTH_SHORT
                ).show()
            }

            return false
        }

        updateArguments(
            mediaUri = MediaUri(uri, uriType, dataType),
            secure = secure,
        )

        return true
    }

    /**
     * Handle a [MediaStore.ACTION_REVIEW] / [MediaStore.ACTION_REVIEW_SECURE] intent
     * (view a media together with medias from the same bucket ID).
     * If uri parsing from [MediaStore] fails, fallback to [handleView].
     * Must not be executed on main thread.
     * @param intent The received intent
     * @param secure Whether we should review this media in a secure manner
     * @return true if the intent has been handled, false otherwise
     */
    private fun handleReview(intent: Intent, secure: Boolean = false) = intent.data?.let {
        getMediaStoreMedia(it)
    }?.let { media ->
        val additionalMedias = intent.clipData?.asArray()?.mapNotNull {
            getMediaStoreMedia(it.uri)
        }

        updateArguments(
            media = media,
            albumId = intent.extras?.getInt(KEY_ALBUM_ID, -1)?.takeUnless {
                it == -1
            } ?: media.bucketId.takeUnless { secure },
            additionalMedias = additionalMedias?.toTypedArray()?.takeIf { it.isNotEmpty() },
            secure = secure,
        )

        true
    } ?: handleView(intent, secure)

    /**
     * Update all media arguments and make sure stuff is set properly.
     * @param media The first media to show
     * @param albumId Album ID, if null [additionalMedias] will be used
     * @param additionalMedias The additional medias to show alongside [media]
     * @param mediaUri The [MediaUri] to show
     * @param secure Whether this should be considered a secure session
     */
    private fun updateArguments(
        media: Media? = null,
        albumId: Int? = null,
        additionalMedias: Array<Media>? = null,
        mediaUri: MediaUri? = null,
        secure: Boolean = false,
    ) {
        this.media = media
        this.albumId = albumId
        this.additionalMedias = additionalMedias
        this.mediaUri = mediaUri
        this.secure = secure
    }

    /**
     * Given a [MediaStore] [Uri], parse its information and get a [Media] object.
     * Must not be executed on main thread.
     * @param uri The [MediaStore] [Uri]
     */
    private fun getMediaStoreMedia(uri: Uri) = runCatching {
        contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_FAVORITE,
                MediaStore.MediaColumns.IS_TRASHED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.ORIENTATION,
            ),
            bundleOf(),
            null,
        )?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketIdIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val displayNameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val isFavoriteIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
            val isTrashedIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
            val mimeTypeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateAddedIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val dateModifiedIndex =
                it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val orientationIndex =
                it.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)

            if (it.count != 1) {
                return@use null
            }

            it.moveToFirst()

            val id = it.getLong(idIndex)
            val bucketId = it.getInt(bucketIdIndex)
            val displayName = it.getString(displayNameIndex)
            val isFavorite = it.getInt(isFavoriteIndex)
            val isTrashed = it.getInt(isTrashedIndex)
            val mediaType = contentResolver.getType(uri)?.let { type ->
                MediaType.fromMimeType(type)
            } ?: return@use null
            val mimeType = it.getString(mimeTypeIndex)
            val dateAdded = it.getLong(dateAddedIndex)
            val dateModified = it.getLong(dateModifiedIndex)
            val width = it.getInt(widthIndex)
            val height = it.getInt(heightIndex)
            val orientation = it.getInt(orientationIndex)

            Media.fromMediaStore(
                id,
                bucketId,
                displayName,
                isFavorite,
                isTrashed,
                mediaType.mediaStoreValue,
                mimeType,
                dateAdded,
                dateModified,
                width,
                height,
                orientation,
            )
        }
    }.getOrNull()

    /**
     * Given a [Uri], figure out it's MIME type.
     * Must not be executed on main thread.
     * @param uri The [Uri] to parse
     */
    private fun getContentType(uri: Uri) = when (uri.scheme) {
        "content" -> contentResolver.getType(uri)

        "file" -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        )

        "http", "https" -> {
            val request = Request.Builder()
                .head()
                .url(uri.toString())
                .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    response.header("content-type")
                }
            }.getOrNull()
        }

        "rtsp" -> "video/rtsp" // Made up MIME type, just to get video type

        else -> null
    }

    companion object {
        /**
         * The album to show, defaults to [media]'s bucket ID.
         */
        const val KEY_ALBUM_ID = "album_id"

        private val dateFormatter = SimpleDateFormat.getDateInstance()
        private val timeFormatter = SimpleDateFormat.getTimeInstance()
    }
}
