package com.luma.gallery.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** A real AVC/MP4 clip generated on-device: no network, canned thumbnail, or fake media row. */
fun createVideoFixture(context: Context): Uri {
    val file = File(context.cacheDir, "gallery-video-${UUID.randomUUID()}.mp4")
    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxerStarted = false
    try {
        val width = 64
        val height = 48
        encoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        muxer.setOrientationHint(90)
        muxer.setLocation(48.137f, 11.575f)
        encoder.start()
        val bufferInfo = MediaCodec.BufferInfo()
        var frame = 0
        var track = -1
        var inputEnded = false
        var outputEnded = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!outputEnded && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!inputEnded) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = encoder.getInputBuffer(index)!!
                    input.clear()
                    if (frame < 50) {
                        repeat(width * height) { input.put((50 + frame * 3).toByte()) }
                        repeat(width * height / 4) { input.put(100.toByte()) }
                        repeat(width * height / 4) { input.put(150.toByte()) }
                        encoder.queueInputBuffer(index, 0, width * height * 3 / 2, frame * 100_000L, 0)
                        frame++
                    } else {
                        encoder.queueInputBuffer(index, 0, 0, frame * 100_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    }
                }
            }
            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val output = encoder.getOutputBuffer(index)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                        check(muxerStarted)
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, output, bufferInfo)
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
        check(outputEnded) { "Timed out encoding the test video" }
    } finally {
        runCatching { encoder.stop() }
        encoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }
    try {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GalleryVideoQA/")
            put(MediaStore.Video.Media.DATE_TAKEN, 1_700_000_000_000L)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    } finally { file.delete() }
}
