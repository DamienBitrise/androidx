/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.video;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodecInfo;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Size;
import android.util.SparseArray;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.ExperimentalUseCaseGroup;
import androidx.camera.core.Logger;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.impl.MutableStateObservable;
import androidx.camera.core.impl.Observable;
import androidx.camera.core.impl.StateObservable;
import androidx.camera.core.impl.annotation.ExecutedBy;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.core.impl.utils.futures.FutureCallback;
import androidx.camera.core.impl.utils.futures.Futures;
import androidx.camera.video.internal.AudioSource;
import androidx.camera.video.internal.AudioSourceAccessException;
import androidx.camera.video.internal.BufferProvider;
import androidx.camera.video.internal.compat.Api26Impl;
import androidx.camera.video.internal.encoder.AudioEncoderConfig;
import androidx.camera.video.internal.encoder.EncodeException;
import androidx.camera.video.internal.encoder.EncodedData;
import androidx.camera.video.internal.encoder.Encoder;
import androidx.camera.video.internal.encoder.EncoderCallback;
import androidx.camera.video.internal.encoder.EncoderImpl;
import androidx.camera.video.internal.encoder.InputBuffer;
import androidx.camera.video.internal.encoder.InvalidConfigException;
import androidx.camera.video.internal.encoder.OutputConfig;
import androidx.camera.video.internal.encoder.VideoEncoderConfig;
import androidx.camera.video.internal.utils.OutputUtil;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.util.Preconditions;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides functionality to generate {@link PendingRecording} and record video to the location
 * specified by {@link OutputOptions}.
 *
 * <p>The {@link MediaSpec} associated with the Recorder can not be changed once it's created.
 * Create a new Recorder for using different {@link MediaSpec}.
 */
public final class Recorder implements VideoOutput {

    private static final String TAG = "Recorder";

    enum State {
        /**
         * The Recorder is being initialized.
         */
        INITIALIZING,
        /**
         * The Recorder is being initialized and a recording is waiting for being run.
         */
        PENDING_RECORDING,
        /**
         * The Recorder is being initialized and a recording is waiting for being paused.
         */
        PENDING_PAUSED,
        /**
         * The Recorder is idling and ready to start a new recording.
         */
        IDLING,
        /**
         * There's a running recording and the Recorder is producing output.
         */
        RECORDING,
        /**
         * There's a running recording and it's paused.
         */
        PAUSED,
        /**
         * There's a running recording and the Recorder is being released.
         */
        RELEASING,
        /**
         * The Recorder has been released and any operation attempt will throw an
         * {@link IllegalStateException}.
         */
        RELEASED,
        /**
         * The Recorder encountered errors and any operation will attempt will throw an
         * {@link IllegalStateException}. Users can handle the error by monitoring
         * {@link VideoRecordEvent}.
         */
        ERROR
    }

    private static final AudioSpec AUDIO_SPEC_DEFAULT =
            AudioSpec.builder()
                    .setSourceFormat(
                            AudioSpec.SOURCE_FORMAT_PCM_16BIT) /* Defaults to PCM_16BIT as it's
                            guaranteed supported on devices. May consider allowing users to set
                            format through AudioSpec later. */
                    .setSource(AudioSpec.SOURCE_CAMCORDER)
                    .setChannelCount(AudioSpec.CHANNEL_COUNT_MONO)
                    .build();
    private static final VideoSpec VIDEO_SPEC_DEFAULT =
            VideoSpec.builder()
                    .setAspectRatio(VideoSpec.ASPECT_RATIO_16_9)
                    .build();
    private static final MediaSpec MEDIA_SPEC_DEFAULT =
            MediaSpec.builder()
                    .setOutputFormat(MediaSpec.OUTPUT_FORMAT_MPEG_4)
                    .setAudioSpec(AUDIO_SPEC_DEFAULT)
                    .setVideoSpec(VIDEO_SPEC_DEFAULT)
                    .build();
    private static final int AUDIO_BITRATE_DEFAULT = 88200;
    // Default to 44100 for now as it's guaranteed supported on devices.
    private static final int AUDIO_SAMPLE_RATE_DEFAULT = 44100;
    private static final int VIDEO_FRAME_RATE_DEFAULT = 30;
    private static final int VIDEO_BITRATE_DEFAULT = 10 * 1024 * 1024; // 10M
    private static final int VIDEO_INTRA_FRAME_INTERVAL_DEFAULT = 1;
    @SuppressWarnings("deprecation")
    private static final String MEDIA_COLUMN = MediaStore.Video.Media.DATA;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final MutableStateObservable<State> mState =
            MutableStateObservable.withInitialState(State.INITIALIZING);
    private final MutableStateObservable<StreamState> mStreamState =
            MutableStateObservable.withInitialState(StreamState.INACTIVE);
    private final Executor mExecutor;
    private SurfaceRequest.TransformationInfo mSurfaceTransformationInfo = null;
    private Throwable mErrorCause;
    private AtomicBoolean mSurfaceRequested = new AtomicBoolean(false);

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final SparseArray<CallbackToFutureAdapter.Completer<Void>> mEncodingCompleters =
            new SparseArray<>();
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final List<ListenableFuture<Void>> mEncodingFutures = new ArrayList<>();
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    ActiveRecording mRunningRecording = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    Integer mAudioTrackIndex = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    Integer mVideoTrackIndex = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    Surface mSurface = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final Executor mSequentialExecutor;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    MediaMuxer mMediaMuxer = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final MutableStateObservable<MediaSpec> mMediaSpec;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    AudioSource mAudioSource = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    EncoderImpl mVideoEncoder = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    EncoderImpl mAudioEncoder = null;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    boolean mMuted = false;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    Uri mOutputUri = Uri.EMPTY;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    long mRecordingBytes = 0L;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    long mRecordingDurationNs = 0L;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    long mFirstRecordingVideoDataTimeUs = 0L;

    Recorder(@Nullable Executor executor, @NonNull MediaSpec mediaSpec) {
        mExecutor = executor != null ? executor : CameraXExecutors.ioExecutor();
        mSequentialExecutor = CameraXExecutors.newSequentialExecutor(mExecutor);

        mMediaSpec = MutableStateObservable.withInitialState(composeRecorderMediaSpec(mediaSpec));
        if (getObservableData(mMediaSpec).getAudioSpec().getChannelCount()
                == AudioSpec.CHANNEL_COUNT_NONE) {
            mMuted = true;
        }
    }

    /** {@inheritDoc} */
    @SuppressLint("MissingPermission")
    @Override
    @OptIn(markerClass = ExperimentalUseCaseGroup.class)
    public void onSurfaceRequested(@NonNull SurfaceRequest surfaceRequest) {
        synchronized (mLock) {
            switch (getObservableData(mState)) {
                case PENDING_RECORDING:
                    // Fall-through
                case PENDING_PAUSED:
                    // Fall-through
                case INITIALIZING:
                    // The recorder should be initialized only once until it is released.
                    if (mSurfaceRequested.compareAndSet(false, true)) {
                        mSequentialExecutor.execute(() -> initializeInternal(surfaceRequest));
                    }
                    break;
                case IDLING:
                    // Fall-through
                case RECORDING:
                    // Fall-through
                case PAUSED:
                    // Fall-through
                case ERROR:
                    throw new IllegalStateException("The Recorder has been initialized.");
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    surfaceRequest.willNotProvideSurface();
                    Logger.w(TAG, "A surface is requested while the Recorder is released.");
                    break;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public Observable<MediaSpec> getMediaSpec() {
        return mMediaSpec;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public Observable<StreamState> getStreamState() {
        return mStreamState;
    }

    /**
     * Generates a {@link PendingRecording} that is associated with this Recorder with a
     * {@link FileOutputOptions}.
     *
     * <p>The recording generated by this method will be saved to a {@link java.io.File}.
     *
     * @param fileOutputOptions the options that configures how the output will be handled.
     * @return a {@link PendingRecording} that is associated with this Recorder.
     * @throws IllegalStateException if the Recorder is released.
     */
    @NonNull
    public PendingRecording prepareRecording(@NonNull FileOutputOptions fileOutputOptions) {
        return prepareRecordingInternal(fileOutputOptions);
    }

    /**
     * Generates a {@link PendingRecording} that is associated with this Recorder with a
     * {@link FileDescriptorOutputOptions}.
     *
     * <p>The recording generated by this method will be saved to a {@link java.io.FileDescriptor}.
     *
     * <p>Currently, file descriptors as output destinations are not supported on pre-Android O
     * devices.
     *
     * @param fileDescriptorOutputOptions the options that configures how the output will be
     *                                    handled.
     * @return a {@link PendingRecording} that is associated with this Recorder.
     * @throws IllegalStateException if the Recorder is released.
     */
    @RequiresApi(26)
    @NonNull
    public PendingRecording prepareRecording(
            @NonNull FileDescriptorOutputOptions fileDescriptorOutputOptions) {
        Preconditions.checkState(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                "MediaMuxer doesn't accept FileDescriptor as output destination.");
        return prepareRecordingInternal(fileDescriptorOutputOptions);
    }

    /**
     * Generates a {@link PendingRecording} that is associated with this Recorder with a
     * {@link MediaStoreOutputOptions}.
     *
     * <p>The recording generated by this method will be saved to {@link MediaStore}.
     *
     * @param mediaStoreOutputOptions the options that configures how the output will be handled.
     * @return a {@link PendingRecording} that is associated with this Recorder.

     * @throws IllegalStateException if the Recorder is released.
     */
    @NonNull
    public PendingRecording prepareRecording(
            @NonNull MediaStoreOutputOptions mediaStoreOutputOptions) {
        return prepareRecordingInternal(mediaStoreOutputOptions);
    }

    @NonNull
    private PendingRecording prepareRecordingInternal(@NonNull OutputOptions options) {
        Preconditions.checkNotNull(options, "The OutputOptions cannot be null.");
        synchronized (mLock) {
            switch (getObservableData(mState)) {
                case INITIALIZING:
                    // Fall-through
                case PENDING_RECORDING:
                    // Fall-through
                case PENDING_PAUSED:
                    // Fall-through
                case ERROR:
                    // Fall-through, create PendingRecording as usual, but it will be instantly
                    // finalized at start().
                case IDLING:
                    // Fall-through
                case PAUSED:
                    // Fall-through
                case RECORDING:
                    break;
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    throw new IllegalStateException("The Recorder has been released.");
            }
            return new PendingRecording(this, options);
        }
    }

    /**
     * Gets the {@link QualitySelector} of this Recorder.
     */
    @NonNull
    public QualitySelector getQualitySelector() {
        return getObservableData(mMediaSpec).getVideoSpec().getQualitySelector();
    }

    /**
     * Gets the audio source of this Recorder.
     */
    public int getAudioSource() {
        return getObservableData(mMediaSpec).getAudioSpec().getSource();
    }

    /**
     * Gets the aspect ratio of this Recorder.
     */
    @VideoSpec.AspectRatio
    public int getAspectRatio() {
        return getObservableData(mMediaSpec).getVideoSpec().getAspectRatio();
    }

    /**
     * Starts a pending recording and returns an active recording instance.
     *
     * <p>If the Recorder is already running a recording, an {@link IllegalStateException} will
     * be thrown when calling this method.
     *
     * <p>If the video encoder hasn't been setup with {@link #onSurfaceRequested(SurfaceRequest)}
     * , the {@link PendingRecording} specified will be started once the video encoder setup
     * completes. The recording will be considered active, so before it's finalized, an
     * {@link IllegalStateException} will be thrown if this method is called for a second time.
     *
     * @throws IllegalStateException if there's an active recording or the Recorder has been
     * released.
     */
    @NonNull
    ActiveRecording start(@NonNull PendingRecording pendingRecording) {
        Preconditions.checkNotNull(pendingRecording, "The given PendingRecording cannot be null.");
        synchronized (mLock) {
            ActiveRecording activeRecording = ActiveRecording.from(pendingRecording);
            mRunningRecording = activeRecording;
            switch (getObservableData(mState)) {
                case INITIALIZING:
                    // The recording will automatically start once the initialization completes.
                    setState(State.PENDING_RECORDING);
                    break;
                case IDLING:
                    mSequentialExecutor.execute(this::startInternal);
                    setState(State.RECORDING);
                    break;
                case PENDING_PAUSED:
                    // Fall-through
                case PAUSED:
                    // Fall-through
                case PENDING_RECORDING:
                    // Fall-through
                case RECORDING:
                    throw new IllegalStateException("There's an active recording.");
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    throw new IllegalStateException("The Recorder has been released.");
                case ERROR:
                    finalizeRecordingWithError(VideoRecordEvent.ERROR_RECORDER_ERROR, mErrorCause);
                    break;
            }

            return activeRecording;
        }
    }

    void pause() {
        synchronized (mLock) {
            switch (getObservableData(mState)) {
                case PENDING_RECORDING:
                    // Fall-through
                case INITIALIZING:
                    // The recording will automatically pause once the initialization completes.
                    setState(State.PENDING_PAUSED);
                    break;
                case IDLING:
                    throw new IllegalStateException("Calling pause() while idling is invalid.");
                case RECORDING:
                    mSequentialExecutor.execute(this::pauseInternal);
                    setState(State.PAUSED);
                    break;
                case PENDING_PAUSED:
                    // Fall-through
                case PAUSED:
                    // No-op when the recording is already paused.
                    break;
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    throw new IllegalStateException("The Recorder has been released.");
                case ERROR:
                    finalizeRecordingWithError(VideoRecordEvent.ERROR_RECORDER_ERROR, mErrorCause);
                    break;
            }
        }
    }

    void resume() {
        synchronized (mLock) {
            switch (getObservableData(mState)) {
                case PENDING_PAUSED:
                    // Fall-through
                case INITIALIZING:
                    // The recording will automatically start once the initialization completes.
                    setState(State.PENDING_RECORDING);
                    break;
                case IDLING:
                    throw new IllegalStateException("Calling resume() while idling is invalid.");
                case PENDING_RECORDING:
                    // Fall-through
                case RECORDING:
                    // No-op when the recording is running.
                    break;
                case PAUSED:
                    mSequentialExecutor.execute(this::resumeInternal);
                    setState(State.RECORDING);
                    break;
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    throw new IllegalStateException("The Recorder has been released.");
                case ERROR:
                    finalizeRecordingWithError(VideoRecordEvent.ERROR_RECORDER_ERROR, mErrorCause);
                    break;
            }
        }
    }

    void stop() {
        synchronized (mLock) {
            switch (getObservableData(mState)) {
                case PENDING_RECORDING:
                    // Fall-through
                case PENDING_PAUSED:
                    // Fall-through
                case INITIALIZING:
                    finalizeRecordingWithError(VideoRecordEvent.ERROR_RECORDER_UNINITIALIZED,
                            new IllegalStateException("The Recorder hasn't been initialized."));
                    setState(State.INITIALIZING);
                    break;
                case IDLING:
                    throw new IllegalStateException("Calling stop() while idling is invalid.");
                case PAUSED:
                    // Fall-through
                case RECORDING:
                    mSequentialExecutor.execute(this::stopInternal);
                    break;
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    throw new IllegalStateException("The Recorder has been released.");
                case ERROR:
                    finalizeRecordingWithError(VideoRecordEvent.ERROR_RECORDER_ERROR, mErrorCause);
                    break;
            }
        }
    }

    /**
     * Releases the Recorder.
     *
     * <p>By releasing the Recorder, it will stop the running recording if there's one. Once the
     * Recorder is released, it cannot be used anymore. Any other method call after the encoder
     * is released will get {@link IllegalStateException}.
     */
    @ExecutedBy("mSequentialExecutor")
    void release() {
        synchronized (mLock) {
            switch (getObservableData(mState)) {
                case PENDING_RECORDING:
                    // Fall-through
                case PENDING_PAUSED:
                    // Fall-through
                case INITIALIZING:
                    // Fall-through
                case ERROR:
                    // Fall-through
                case IDLING:
                    releaseInternal();
                    break;
                case PAUSED:
                    // Fall-through
                case RECORDING:
                    setState(State.RELEASING);
                    // If there's an active recording, stop it first then release the resources
                    // at finalizeRecording().
                    mSequentialExecutor.execute(this::stopInternal);
                    break;
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    // No-Op, the Recorder is already released.
                    break;
            }
        }
    }

    @ExecutedBy("mSequentialExecutor")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void initializeInternal(SurfaceRequest surfaceRequest) {
        if (!mMuted) {
            // Skip setting up audio as the media spec shows there's no audio channel.
            setupAudio();
        }

        if (mSurface != null) {
            // The video encoder has already be created, providing the surface directly.
            surfaceRequest.provideSurface(mSurface, mSequentialExecutor, (result) -> {
                Surface resultSurface = result.getSurface();
                // The latest surface will be released by the encoder when encoder is released.
                if (mSurface != resultSurface) {
                    resultSurface.release();
                }
                mSurface = null;
                release();
                setState(State.INITIALIZING);
            });
            onInitialized();
        } else {
            setupVideo(surfaceRequest);
            surfaceRequest.setTransformationInfoListener(mSequentialExecutor,
                    (transformationInfo) -> mSurfaceTransformationInfo =
                            transformationInfo);
        }
    }

    @ExecutedBy("mSequentialExecutor")
    private void onInitialized() {
        synchronized (mLock) {
            State state = getObservableData(mState);
            switch (state) {
                case IDLING:
                    // Fall-through
                case RECORDING:
                    // Fall-through
                case PAUSED:
                    // Fall-through
                case RELEASING:
                    // Fall-through
                case RELEASED:
                    throw new IllegalStateException(
                            "Incorrectly invoke onInitialized() in state " + state);
                case INITIALIZING:
                    setState(State.IDLING);
                    break;
                case PENDING_PAUSED:
                    // Fall-through
                case PENDING_RECORDING:
                    // Start recording if start() has been called before video encoder is setup.
                    mSequentialExecutor.execute(this::startInternal);
                    setState(State.RECORDING);
                    if (state == State.PENDING_PAUSED) {
                        mSequentialExecutor.execute(this::pauseInternal);
                        setState(State.PAUSED);
                    }
                    break;
                case ERROR:
                    break;
            }
        }
    }

    @NonNull
    private MediaSpec composeRecorderMediaSpec(@NonNull MediaSpec mediaSpec) {
        MediaSpec.Builder mediaSpecBuilder = mediaSpec.toBuilder();
        if (mediaSpec.getOutputFormat() == MediaSpec.OUTPUT_FORMAT_AUTO) {
            mediaSpecBuilder.setOutputFormat(MEDIA_SPEC_DEFAULT.getOutputFormat());
        }

        // Append default audio configurations
        AudioSpec audioSpec = mediaSpec.getAudioSpec();
        if (audioSpec.getSourceFormat() == AudioSpec.SOURCE_FORMAT_AUTO) {
            mediaSpecBuilder.configureAudio(
                    builder -> builder.setSourceFormat(AUDIO_SPEC_DEFAULT.getSourceFormat()));
        }
        if (audioSpec.getSource() == AudioSpec.SOURCE_AUTO) {
            mediaSpecBuilder.configureAudio(
                    builder -> builder.setSource(AUDIO_SPEC_DEFAULT.getSource()));
        }
        if (audioSpec.getChannelCount() == AudioSpec.CHANNEL_COUNT_AUTO) {
            mediaSpecBuilder.configureAudio(
                    builder -> builder.setChannelCount(AUDIO_SPEC_DEFAULT.getChannelCount()));
        }

        // Append default video configurations
        VideoSpec videoSpec = mediaSpec.getVideoSpec();
        if (videoSpec.getAspectRatio() == VideoSpec.ASPECT_RATIO_AUTO) {
            mediaSpecBuilder.configureVideo(
                    builder -> builder.setAspectRatio(VIDEO_SPEC_DEFAULT.getAspectRatio()));
        }

        return mediaSpecBuilder.build();
    }

    @ExecutedBy("mSequentialExecutor")
    @NonNull
    private AudioEncoderConfig composeAudioEncoderConfig(@NonNull MediaSpec mediaSpec) {
        return AudioEncoderConfig.builder()
                .setMimeType(MediaSpec.outputFormatToAudioMime(mediaSpec.getOutputFormat()))
                .setBitrate(AUDIO_BITRATE_DEFAULT)
                .setSampleRate(selectSampleRate(mediaSpec.getAudioSpec()))
                .setChannelMask(
                        AudioSpec.channelCountToConfig(mediaSpec.getAudioSpec().getChannelCount()))
                .setChannelCount(mediaSpec.getAudioSpec().getChannelCount())
                .build();
    }

    @ExecutedBy("mSequentialExecutor")
    @NonNull
    private VideoEncoderConfig composeVideoEncoderConfig(@NonNull MediaSpec mediaSpec,
            @NonNull Size surfaceSize) {
        return VideoEncoderConfig.builder()
                .setMimeType(MediaSpec.outputFormatToVideoMime(mediaSpec.getOutputFormat()))
                .setResolution(surfaceSize)
                // TODO: Add mechanism to pick a value from the specified range and
                //  CamcorderProfile.
                .setBitrate(VIDEO_BITRATE_DEFAULT)
                .setFrameRate(VIDEO_FRAME_RATE_DEFAULT)
                .setColorFormat(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                .setIFrameInterval(VIDEO_INTRA_FRAME_INTERVAL_DEFAULT)
                .build();
    }

    @ExecutedBy("mSequentialExecutor")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void setupAudio() {
        MediaSpec mediaSpec = getObservableData(mMediaSpec);
        AudioEncoderConfig config = composeAudioEncoderConfig(mediaSpec);

        try {
            mAudioEncoder = new EncoderImpl(mExecutor, config);
        } catch (InvalidConfigException e) {
            Logger.e(TAG, "Unable to initialize audio encoder." + e);
            setState(State.ERROR);
            mErrorCause = e;
            return;
        }

        Encoder.EncoderInput bufferProvider = mAudioEncoder.getInput();
        Preconditions.checkState(
                bufferProvider instanceof Encoder.ByteBufferInput,
                "The EncoderInput of audio isn't a ByteBufferInput.");
        try {
            mAudioSource = setupAudioSource((Encoder.ByteBufferInput) bufferProvider,
                    mediaSpec.getAudioSpec());
        } catch (AudioSourceAccessException e) {
            Logger.e(TAG, "Unable to create audio source." + e);
            setState(State.ERROR);
            mErrorCause = e;
            return;
        } catch (SecurityException e) {
            Logger.e(TAG, "Missing audio recording permission." + e);
            setState(State.ERROR);
            mErrorCause = e;
            return;
        }

        mAudioEncoder.setEncoderCallback(new EncoderCallback() {
            @Override
            public void onEncodeStart() {
                // No-op.
            }

            @Override
            public void onEncodeStop() {
                mEncodingCompleters.get(mAudioTrackIndex).set(null);
            }

            @Override
            public void onEncodeError(@NonNull EncodeException e) {
                mEncodingCompleters.get(mAudioTrackIndex).setException(e);
            }

            @Override
            public void onEncodedData(@NonNull EncodedData encodedData) {
                if (!mMuted && mAudioTrackIndex == null) {
                    // Throw an exception if the data comes before the track is added.
                    throw new IllegalStateException(
                            "Audio data comes before the track is added to MediaMuxer.");
                }
                if (mVideoTrackIndex == null) {
                    Logger.d(TAG, "Drop audio data since video track hasn't been added.");
                    encodedData.close();
                    return;
                }

                mRecordingBytes += encodedData.size();

                Preconditions.checkNotNull(mMediaMuxer).writeSampleData(mAudioTrackIndex,
                        encodedData.getByteBuffer(), encodedData.getBufferInfo());
                encodedData.close();
            }

            @Override
            public void onOutputConfigUpdate(@NonNull OutputConfig outputConfig) {
                if (!mMuted && mAudioTrackIndex == null) {
                    mAudioTrackIndex = Preconditions.checkNotNull(mMediaMuxer).addTrack(
                            outputConfig.getMediaFormat());
                    mEncodingFutures.add(CallbackToFutureAdapter.getFuture(
                            completer -> {
                                mEncodingCompleters.put(mAudioTrackIndex, completer);
                                return "audioEncodingFuture";
                            }));
                }
                if (mVideoTrackIndex != null) {
                    startMediaMuxer();
                }
            }
        }, mSequentialExecutor);
    }

    @ExecutedBy("mSequentialExecutor")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Nullable
    private AudioSource setupAudioSource(@NonNull BufferProvider<InputBuffer> bufferProvider,
            @NonNull AudioSpec audioSpec) throws AudioSourceAccessException {
        int sampleRate = selectSampleRate(audioSpec);
        int channelConfig = AudioSpec.channelCountToConfig(audioSpec.getChannelCount());
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig,
                audioSpec.getSourceFormat());
        if (bufferSize <= 0) {
            if (Build.VERSION.SDK_INT >= 24) {
                Logger.i(TAG,
                        "Unable to find a available sample rate. Fallback to native sample rate.");
                // Use the native sample rate came from the audio source if the OS version is API
                // 24+.
                sampleRate = AudioFormat.SAMPLE_RATE_UNSPECIFIED;
            } else {
                setState(State.ERROR);
                mErrorCause = new IllegalArgumentException(
                        "Unable to find a available sample rate.");
                return null;
            }
        }

        return new AudioSource.Builder().setExecutor(CameraXExecutors.ioExecutor())
                .setBufferProvider(bufferProvider)
                .setAudioSource(audioSpec.getSource())
                .setSampleRate(sampleRate)
                .setChannelConfig(channelConfig)
                .setAudioFormat(AUDIO_SPEC_DEFAULT.getSourceFormat())
                .setDefaultBufferSize(bufferSize * 2)
                .build();
    }

    @ExecutedBy("mSequentialExecutor")
    private int selectSampleRate(AudioSpec audioSpec) {
        int bufferSize = 0;
        // The default sample rate should work on most devices. May consider throw an
        // exception or have other way to notify users that the specified sample rate
        // can not be satisfied.
        int selectedSampleRate = AUDIO_SAMPLE_RATE_DEFAULT;
        int channelConfig = AudioSpec.channelCountToConfig(audioSpec.getChannelCount());
        for (int sampleRate : AudioSource.COMMON_SAMPLE_RATES) {
            if (audioSpec.getSampleRate().contains(sampleRate)) {
                bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig,
                        audioSpec.getSourceFormat());
                if (bufferSize > 0) {
                    // Choose the largest valid sample rate as the list has descending order.
                    selectedSampleRate = sampleRate;
                    break;
                }
            }
        }

        return selectedSampleRate;
    }

    @ExecutedBy("mSequentialExecutor")
    private void setupVideo(@NonNull SurfaceRequest surfaceRequest) {
        MediaSpec mediaSpec = getObservableData(mMediaSpec);
        VideoEncoderConfig config = composeVideoEncoderConfig(mediaSpec,
                surfaceRequest.getResolution());

        try {
            mVideoEncoder = new EncoderImpl(mExecutor, config);
        } catch (InvalidConfigException e) {
            surfaceRequest.willNotProvideSurface();
            Logger.e(TAG, "Unable to initialize video encoder." + e);
            setState(State.ERROR);
            mErrorCause = e;
            return;
        }

        Encoder.EncoderInput encoderInput = mVideoEncoder.getInput();
        Preconditions.checkState(encoderInput instanceof Encoder.SurfaceInput,
                "The EncoderInput of video isn't a SurfaceInput.");
        ((Encoder.SurfaceInput) encoderInput).setOnSurfaceUpdateListener(
                mSequentialExecutor,
                surface -> {
                    mSurface = surface;
                    surfaceRequest.provideSurface(surface, mSequentialExecutor, (result) -> {
                        Surface resultSurface = result.getSurface();
                        // The latest surface will be released by the encoder when encoder is
                        // released.
                        if (mSurface != resultSurface) {
                            resultSurface.release();
                        }
                        mSurface = null;
                        release();
                        setState(State.INITIALIZING);
                    });
                    onInitialized();
                });

        mVideoEncoder.setEncoderCallback(new EncoderCallback() {
            @Override
            public void onEncodeStart() {
                // No-op.
            }

            @Override
            public void onEncodeStop() {
                mEncodingCompleters.get(mVideoTrackIndex).set(null);
            }

            @Override
            public void onEncodeError(@NonNull EncodeException e) {
                mEncodingCompleters.get(mVideoTrackIndex).setException(e);
            }

            @Override
            public void onEncodedData(@NonNull EncodedData encodedData) {
                if (mVideoTrackIndex == null) {
                    // Throw an exception if the data comes before the track is added.
                    throw new IllegalStateException(
                            "Video data comes before the track is added to MediaMuxer.");
                }
                if (!mMuted && mAudioTrackIndex == null) {
                    Logger.d(TAG, "Drop video data since audio track hasn't been added.");
                    encodedData.close();
                    return;
                }

                if (mFirstRecordingVideoDataTimeUs == 0L) {
                    mFirstRecordingVideoDataTimeUs = encodedData.getPresentationTimeUs();
                }
                mRecordingDurationNs = TimeUnit.MICROSECONDS.toNanos(
                        encodedData.getPresentationTimeUs() - mFirstRecordingVideoDataTimeUs);
                mRecordingBytes += encodedData.size();

                Preconditions.checkNotNull(mMediaMuxer).writeSampleData(mVideoTrackIndex,
                        encodedData.getByteBuffer(), encodedData.getBufferInfo());
                encodedData.close();

                updateVideoRecordEvent(
                        VideoRecordEvent.status(
                                Preconditions.checkNotNull(mRunningRecording).getOutputOptions(),
                                getCurrentRecordingStats()));
            }

            @Override
            public void onOutputConfigUpdate(@NonNull OutputConfig outputConfig) {
                if (mVideoTrackIndex == null) {
                    mVideoTrackIndex = Preconditions.checkNotNull(mMediaMuxer).addTrack(
                            outputConfig.getMediaFormat());
                    mEncodingFutures.add(CallbackToFutureAdapter.getFuture(
                            completer -> {
                                mEncodingCompleters.put(mVideoTrackIndex, completer);
                                return "videoEncodingFuture";
                            }));
                }
                if (!mMuted && mAudioTrackIndex != null) {
                    startMediaMuxer();
                }
            }
        }, mSequentialExecutor);
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void startMediaMuxer() {
        Futures.addCallback(Futures.allAsList(mEncodingFutures),
                new FutureCallback<List<Void>>() {
                    @Override
                    public void onSuccess(@Nullable List<Void> result) {
                        finalizeRecording();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        finalizeRecordingWithError(VideoRecordEvent.ERROR_ENCODING_FAILED, t);
                    }
                }, mSequentialExecutor);
        Preconditions.checkNotNull(mMediaMuxer).start();
    }

    @ExecutedBy("mSequentialExecutor")
    @OptIn(markerClass = ExperimentalUseCaseGroup.class)
    private void setupMediaMuxer(@NonNull OutputOptions options) throws IOException {
        int outputFormat = getObservableData(mMediaSpec).getOutputFormat();
        switch (options.getType()) {
            case FILE:
                Preconditions.checkState(options instanceof FileOutputOptions, "Invalid "
                                + "OutputOptions type");
                FileOutputOptions fileOutputOptions = (FileOutputOptions) options;
                mMediaMuxer = new MediaMuxer(
                        fileOutputOptions.getFile().getAbsolutePath(),
                        outputFormat);
                break;
            case FILE_DESCRIPTOR:
                Preconditions.checkState(options instanceof FileDescriptorOutputOptions, "Invalid "
                        + "OutputOptions type");
                FileDescriptorOutputOptions fileDescriptorOutputOptions =
                        (FileDescriptorOutputOptions) options;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mMediaMuxer = Api26Impl.createMediaMuxer(
                            fileDescriptorOutputOptions.getFileDescriptor(), outputFormat);
                } else {
                    throw new IOException(
                            "MediaMuxer doesn't accept FileDescriptor as output destination.");
                }
                break;
            case MEDIA_STORE:
                Preconditions.checkState(options instanceof MediaStoreOutputOptions, "Invalid "
                        + "OutputOptions type");
                MediaStoreOutputOptions mediaStoreOutputOptions = (MediaStoreOutputOptions) options;

                ContentValues contentValues =
                        new ContentValues(mediaStoreOutputOptions.getContentValues());
                mOutputUri = mediaStoreOutputOptions.getContentResolver().insert(
                        mediaStoreOutputOptions.getCollection(), contentValues);
                if (mOutputUri == null) {
                    finalizeRecordingWithError(VideoRecordEvent.ERROR_INVALID_OUTPUT_OPTIONS,
                            new IOException("Unable to create MediaStore entry."));
                    return;
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    String path =
                            OutputUtil.getAbsolutePathFromUri(
                                    mediaStoreOutputOptions.getContentResolver(),
                                    mOutputUri, MEDIA_COLUMN);
                    mMediaMuxer = new MediaMuxer(path, outputFormat);
                } else {
                    ParcelFileDescriptor fileDescriptor =
                            mediaStoreOutputOptions.getContentResolver().openFileDescriptor(
                                    mOutputUri, "rw");
                    mMediaMuxer = Api26Impl.createMediaMuxer(fileDescriptor.getFileDescriptor(),
                            outputFormat);
                    fileDescriptor.close();
                }
                break;
        }
        // TODO: Add more metadata to MediaMuxer, e.g. location information.
        if (mSurfaceTransformationInfo != null) {
            mMediaMuxer.setOrientationHint(mSurfaceTransformationInfo.getRotationDegrees());
        }
    }

    @ExecutedBy("mSequentialExecutor")
    private void startInternal() {
        try {
            setupMediaMuxer(Preconditions.checkNotNull(mRunningRecording).getOutputOptions());
        } catch (IOException e) {
            finalizeRecordingWithError(VideoRecordEvent.ERROR_INVALID_OUTPUT_OPTIONS, e);
            return;
        }

        mAudioSource.start();
        mAudioEncoder.start();
        mVideoEncoder.start();

        updateVideoRecordEvent(VideoRecordEvent.start(
                Preconditions.checkNotNull(mRunningRecording).getOutputOptions(),
                getCurrentRecordingStats()));
    }

    @ExecutedBy("mSequentialExecutor")
    private void pauseInternal() {
        mAudioEncoder.pause();
        mVideoEncoder.pause();

        updateVideoRecordEvent(VideoRecordEvent.pause(
                Preconditions.checkNotNull(mRunningRecording).getOutputOptions(),
                getCurrentRecordingStats()));
    }

    @ExecutedBy("mSequentialExecutor")
    private void resumeInternal() {
        mAudioEncoder.start();
        mVideoEncoder.start();

        updateVideoRecordEvent(VideoRecordEvent.resume(
                Preconditions.checkNotNull(mRunningRecording).getOutputOptions(),
                getCurrentRecordingStats()));
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @ExecutedBy("mSequentialExecutor")
    void stopInternal() {
        mAudioEncoder.stop();
        mVideoEncoder.stop();
    }

    @ExecutedBy("mSequentialExecutor")
    private void releaseInternal() {
        if (mAudioEncoder != null) {
            mAudioEncoder.release();
            mAudioSource = null;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioSource != null) {
            mAudioSource.release();
            mAudioSource = null;
        }

        mSurfaceRequested.set(false);
        setState(State.RELEASED);
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @ExecutedBy("mSequentialExecutor")
    void finalizeRecording() {
        finalizeRecordingWithError(VideoRecordEvent.ERROR_NONE, null);
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @ExecutedBy("mSequentialExecutor")
    void finalizeRecordingWithError(@VideoRecordEvent.VideoRecordError int error,
            @Nullable Throwable throwable) {
        OutputOptions outputOptions =
                Preconditions.checkNotNull(mRunningRecording).getOutputOptions();
        RecordingStats stats = getCurrentRecordingStats();
        OutputResults outputResults = OutputResults.of(mOutputUri);
        updateVideoRecordEvent(error == VideoRecordEvent.ERROR_NONE
                ? VideoRecordEvent.finalize(
                        outputOptions,
                        stats,
                        outputResults)
                : VideoRecordEvent.finalizeWithError(
                        outputOptions,
                        stats,
                        outputResults,
                        error,
                        throwable));

        if (mMediaMuxer != null) {
            mMediaMuxer.stop();
            mMediaMuxer.release();
            mMediaMuxer = null;
        }

        mAudioTrackIndex = null;
        mVideoTrackIndex = null;
        mEncodingFutures.clear();
        mEncodingCompleters.clear();
        mRunningRecording = null;
        mOutputUri = Uri.EMPTY;
        mRecordingBytes = 0L;
        mRecordingDurationNs = 0L;
        mFirstRecordingVideoDataTimeUs = 0L;

        synchronized (mLock) {
            if (getObservableData(mState) == State.RELEASING) {
                releaseInternal();
            } else {
                setState(State.IDLING);
            }
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void updateVideoRecordEvent(@NonNull VideoRecordEvent event) {
        if (mRunningRecording != null) {
            mRunningRecording.updateVideoRecordEvent(event);
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @ExecutedBy("mSequentialExecutor")
    @NonNull
    RecordingStats getCurrentRecordingStats() {
        return RecordingStats.of(mRecordingDurationNs, mRecordingBytes);
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    <T> T getObservableData(@NonNull StateObservable<T> observable) {
        ListenableFuture<T> future = observable.fetchData();
        try {
            // A StateObservable always has a state available and the future got from fetchData()
            // will complete immediately.
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void setState(@NonNull State state) {
        synchronized (mLock) {
            Logger.d(TAG,
                    "Transitioning Recorder internal state: " + getObservableData(mState) + " -->"
                            + " " + state);
            mState.setState(state);
            if (state == State.RECORDING) {
                mStreamState.setState(StreamState.ACTIVE);
            } else {
                mStreamState.setState(StreamState.INACTIVE);
            }
        }
    }

    /**
     * The builder of the Recorder.
     */
    public static final class Builder {

        private final MediaSpec.Builder mMediaSpecBuilder;
        private Executor mExecutor = null;

        public Builder() {
            mMediaSpecBuilder = MediaSpec.builder();
        }

        /**
         * Sets the {@link Executor} that runs the Recorder background task.
         *
         * <p>The executor is used to run the Recorder tasks, the audio encoding and the video
         * encoding. For the best performance, it's recommended to be a
         * {@link java.util.concurrent.ThreadPoolExecutor} and is capable of generating at lest 3
         * threads.
         */
        @NonNull
        public Builder setExecutor(@NonNull Executor executor) {
            Preconditions.checkNotNull(executor, "The specified executor can't be null.");
            mExecutor = executor;
            return this;
        }

        // Usually users can use the CameraX predefined configuration for creating a recorder. We
        // may see which options of MediaSpec to be exposed.

        /**
         * Sets the {@link QualitySelector} of this Recorder.
         */
        @NonNull
        public Builder setQualitySelector(@NonNull QualitySelector qualitySelector) {
            Preconditions.checkNotNull(qualitySelector,
                    "The specified quality selector can't be null.");
            mMediaSpecBuilder.configureVideo(
                    builder -> builder.setQualitySelector(qualitySelector));
            return this;
        }

        /**
         * Sets the aspect ratio of this Recorder.
         */
        @NonNull
        public Builder setAspectRatio(@AspectRatio.Ratio int aspectRatio) {
            mMediaSpecBuilder.configureVideo(builder -> builder.setAspectRatio(aspectRatio));
            return this;
        }

        /**
         * Builds the Recorder instance.
         */
        @NonNull
        public Recorder build() {
            return new Recorder(mExecutor, mMediaSpecBuilder.build());
        }
    }
}
