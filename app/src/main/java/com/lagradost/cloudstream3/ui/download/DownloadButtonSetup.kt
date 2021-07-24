package com.lagradost.cloudstream3.ui.download

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.UIHelper.popupMenuNoIcons
import com.lagradost.cloudstream3.ui.player.PlayerFragment
import com.lagradost.cloudstream3.ui.player.UriData
import com.lagradost.cloudstream3.utils.Coroutines
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.VideoDownloadHelper
import com.lagradost.cloudstream3.utils.VideoDownloadManager

object DownloadButtonSetup {
    fun handleDownloadClick(activity: Activity?, headerName: String?, click: DownloadClickEvent) {
        val id = click.data.id
        when (click.action) {
            DOWNLOAD_ACTION_DELETE_FILE -> {
                activity?.let { ctx ->
                    VideoDownloadManager.deleteFileAndUpdateSettings(ctx, id)
                }
            }
            DOWNLOAD_ACTION_PAUSE_DOWNLOAD -> {
                VideoDownloadManager.downloadEvent.invoke(
                    Pair(click.data.id, VideoDownloadManager.DownloadActionType.Pause)
                )
            }
            DOWNLOAD_ACTION_RESUME_DOWNLOAD -> {
                activity?.let { ctx ->
                    val pkg = VideoDownloadManager.getDownloadResumePackage(ctx, id)
                    if (pkg != null) {
                        VideoDownloadManager.downloadFromResume(ctx, pkg)
                    } else {
                        VideoDownloadManager.downloadEvent.invoke(
                            Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                        )
                    }
                }
            }
            DOWNLOAD_ACTION_PLAY_FILE -> {
                activity?.let { act ->
                    val info =
                        VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(act, click.data.id)
                            ?: return

                    (act as FragmentActivity).supportFragmentManager.beginTransaction()
                        .setCustomAnimations(
                            R.anim.enter_anim,
                            R.anim.exit_anim,
                            R.anim.pop_enter,
                            R.anim.pop_exit
                        )
                        .add(
                            R.id.homeRoot,
                            PlayerFragment.newInstance(
                                UriData(
                                    info.path.toString(),
                                    click.data.id,
                                    headerName ?: "null",
                                    click.data.episode,
                                    click.data.season
                                ),
                                act.getViewPos(click.data.id)?.position ?: 0
                            )
                        )
                        .commit()
                }
            }
        }
    }

    fun setUpButton(
        setupCurrentBytes: Long?,
        setupTotalBytes: Long?,
        progressBar: ContentLoadingProgressBar,
        downloadImage: ImageView,
        textView: TextView?,
        data: VideoDownloadHelper.DownloadEpisodeCached,
        clickCallback: (DownloadClickEvent) -> Unit,
    ) {
        var lastState: VideoDownloadManager.DownloadType? = null
        var currentBytes = setupCurrentBytes ?: 0
        var totalBytes = setupTotalBytes ?: 0
        var needImageUpdate = false

        fun changeDownloadImage(state: VideoDownloadManager.DownloadType) {
            Coroutines.runOnMainThread {
                lastState = state
                if (currentBytes <= 0) needImageUpdate = true
                val img = if (currentBytes > 0) when (state) {
                    VideoDownloadManager.DownloadType.IsPaused -> R.drawable.ic_baseline_play_arrow_24
                    VideoDownloadManager.DownloadType.IsDownloading -> R.drawable.netflix_pause
                    else -> R.drawable.ic_baseline_delete_outline_24
                } else R.drawable.netflix_download
                downloadImage?.setImageResource(img)
            }
        }

        @SuppressLint("SetTextI18n")
        fun fixDownloadedBytes(setCurrentBytes: Long, setTotalBytes: Long, animate: Boolean) {
            Coroutines.runOnMainThread {
                currentBytes = setCurrentBytes
                totalBytes = setTotalBytes

                if (currentBytes == 0L) {
                    changeDownloadImage(VideoDownloadManager.DownloadType.IsStopped)
                    textView?.visibility = View.GONE
                    progressBar?.visibility = View.GONE
                } else {
                    if (lastState == VideoDownloadManager.DownloadType.IsStopped) {
                        changeDownloadImage(VideoDownloadManager.getDownloadState(data.id))
                    }
                    textView?.visibility = View.VISIBLE
                    progressBar?.visibility = View.VISIBLE
                    val currentMbString = "%.1f".format(setCurrentBytes / 1000000f)
                    val totalMbString = "%.1f".format(setTotalBytes / 1000000f)

                    textView?.text =
                        "${currentMbString}MB / ${totalMbString}MB"

                    progressBar?.let { bar ->
                        bar.max = (setTotalBytes / 1000).toInt()

                        if (animate) {
                            val animation: ObjectAnimator = ObjectAnimator.ofInt(
                                bar,
                                "progress",
                                bar.progress,
                                (setCurrentBytes / 1000).toInt()
                            )
                            animation.duration = 500
                            animation.setAutoCancel(true)
                            animation.interpolator = DecelerateInterpolator()
                            animation.start()
                        } else {
                            bar.progress = (setCurrentBytes / 1000).toInt()
                        }
                    }
                }
            }
        }

        fixDownloadedBytes(currentBytes, totalBytes, false)
        changeDownloadImage(VideoDownloadManager.getDownloadState(data.id))

        VideoDownloadManager.downloadProgressEvent += { downloadData ->
            if (data.id == downloadData.first) {
                if (downloadData.second != currentBytes || downloadData.third != totalBytes) { // TO PREVENT WASTING UI TIME
                    fixDownloadedBytes(downloadData.second, downloadData.third, true)
                }
            }
        }

        VideoDownloadManager.downloadStatusEvent += { downloadData ->
            if (data.id == downloadData.first) {
                if (lastState != downloadData.second || needImageUpdate) { // TO PREVENT WASTING UI TIME
                    changeDownloadImage(downloadData.second)
                }
            }
        }

        downloadImage.setOnClickListener {
            if (currentBytes <= 0) {
                clickCallback.invoke(DownloadClickEvent(DOWNLOAD_ACTION_DOWNLOAD, data))
            } else {
                val list = arrayListOf(
                    Pair(DOWNLOAD_ACTION_DELETE_FILE, R.string.popup_delete_file),
                )

                // DON'T RESUME A DOWNLOADED FILE
                if (lastState != VideoDownloadManager.DownloadType.IsDone && ((currentBytes * 100 / totalBytes) < 98)) {
                    list.add(
                        if (lastState == VideoDownloadManager.DownloadType.IsDownloading)
                            Pair(DOWNLOAD_ACTION_PAUSE_DOWNLOAD, R.string.popup_pause_download)
                        else
                            Pair(DOWNLOAD_ACTION_RESUME_DOWNLOAD, R.string.popup_resume_download)
                    )
                }

                it.popupMenuNoIcons(
                    list
                ) {
                    clickCallback.invoke(DownloadClickEvent(itemId, data))
                }
            }
        }
    }
}