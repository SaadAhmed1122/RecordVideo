package com.example.recordvideo;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresPermission;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VideoEncoder {
    private Context context;
    private int width, height, frameRate;
    private int bitRate = 4 * 1024 * 1024;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private Surface inputSurface;
    private MediaMuxer muxer;

    private boolean isEncoderStarted = false;
    private boolean isStopped = false;
    private boolean muxerStarted = false;

    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;

    private AudioRecord audioRecord;
    private Thread audioThread;
    private boolean isAudioRecording = false;

    public VideoEncoder(Context context, int width, int height, int frameRate) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public void start() {
        try {
            // 1. Setup video encoder
            MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            videoEncoder = MediaCodec.createEncoderByType("video/avc");
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            // 2. Setup audio encoder
            MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1); // Mono
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            // 3. Setup muxer
            File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "RecVideo");
            outputDir.mkdirs();
            String fileName = "VideoRec" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            File outputFile = new File(outputDir, fileName);
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // 4. Start audio recording
            startAudioRecording();

            isEncoderStarted = true;
            isStopped = false;

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void drawBitmap(Bitmap bitmap) {
        if (!isEncoderStarted || isStopped) return;
        Canvas canvas = inputSurface.lockCanvas(null);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(bitmap, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
        inputSurface.unlockCanvasAndPost(canvas);
        drainEncoder(videoEncoder, true);
    }

    public void stop() {
        if (!isEncoderStarted || isStopped) return;
        try {
            videoEncoder.signalEndOfInputStream();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }

        // Stop audio recording
        stopAudioRecording();

        drainEncoder(videoEncoder, true);
        drainEncoder(audioEncoder, true);

        try {
            videoEncoder.stop();
            audioEncoder.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }

        videoEncoder.release();
        audioEncoder.release();

        try {
            muxer.stop();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
        muxer.release();

        isStopped = true;
    }

    private void drainEncoder(MediaCodec encoder, boolean isVideo) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = encoder.getOutputFormat();
                if (isVideo) {
                    videoTrackIndex = muxer.addTrack(newFormat);
                } else {
                    audioTrackIndex = muxer.addTrack(newFormat);
                }
                if (videoTrackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (outputBufferId >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferId);
                if (encodedData == null) throw new RuntimeException("null buffer");

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    int trackIndex = isVideo ? videoTrackIndex : audioTrackIndex;
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                }

                encoder.releaseOutputBuffer(outputBufferId, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void startAudioRecording() {
        int bufferSize = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        isAudioRecording = true;
        audioRecord.startRecording();

        audioThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isAudioRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    encodeAudio(buffer, read);
                }
            }
        });
        audioThread.start();
    }

    private void encodeAudio(byte[] data, int length) {
        int inputBufferIndex = audioEncoder.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
            inputBuffer.clear();

            int safeLength = Math.min(length, inputBuffer.remaining());
            inputBuffer.put(data, 0, safeLength);

            long presentationTimeUs = System.nanoTime() / 1000;
            audioEncoder.queueInputBuffer(inputBufferIndex, 0, safeLength, presentationTimeUs, 0);
        }

        drainEncoder(audioEncoder, false);
    }


    private void stopAudioRecording() {
        isAudioRecording = false;
        if (audioThread != null) {
            try {
                audioThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
    }
}
