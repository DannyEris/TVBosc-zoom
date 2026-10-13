package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.DefaultAnalyticsCollector;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat;
import com.google.android.exoplayer2.audio.AudioProcessor.UnhandledAudioFormatException;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.video.VideoSize;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    protected Context mAppContext;
    protected SimpleExoPlayer mInternalPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;

    private PlaybackParameters mSpeedPlaybackParameters;

    private boolean mIsPreparing;

    private LoadControl mLoadControl;
    private RenderersFactory mRenderersFactory;
    protected TrackSelector mTrackSelector;

    protected DefaultTrackSelector trackSelector;

    protected String currentPlayPath;
    protected Map<String, String> currentHeaders;
    private boolean mRetriedAsHls;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 30_000, 1_000, 3_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .setBackBuffer(0, false)
                    .build();
        }

        // 配置轨道选择器：优先选用 AAC / MP3 音轨，同时放行 4K 10-bit 视频
        if (mTrackSelector == null) {
            DefaultTrackSelector defaultTrackSelector = new DefaultTrackSelector(mAppContext);
            defaultTrackSelector.setParameters(
                    defaultTrackSelector.buildUponParameters()
                            .setPreferredAudioMimeTypes("audio/mp4a-latm", "audio/mpeg")
                            .setExceedRendererCapabilitiesIfNecessary(true)
                            .setExceedVideoConstraintsIfNecessary(true)
                            .setAllowVideoMixedMimeTypeAdaptiveness(true)
                            .setAllowVideoNonSeamlessAdaptiveness(true)
            );
            mTrackSelector = defaultTrackSelector;
        }

        // 渲染器工厂：硬件优先，不支持时降级 FFmpeg 软解，并挂载自适应声道降混处理器
        if (mRenderersFactory == null) {
            mRenderersFactory = new DefaultRenderersFactory(mAppContext) {
                @Nullable
                @Override
                protected AudioSink buildAudioSink(
                        Context context,
                        boolean enableFloatOutput,
                        boolean enableAudioTrackPlaybackParams,
                        boolean enableOffload) {
                    return new DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .setEnableAudioOffload(enableOffload)
                            .setAudioProcessors(new AudioProcessor[]{new AdaptiveDownmixAudioProcessor(context)})
                            .build();
                }
            }
            .setEnableDecoderFallback(true) // 硬解失败自动回退软解
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON); // 优先硬件解码，硬件不支持时调用 FFmpeg 软解
        }

        mInternalPlayer = new SimpleExoPlayer.Builder(
                mAppContext,
                mRenderersFactory,
                mTrackSelector,
                new DefaultMediaSourceFactory(mAppContext),
                mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new DefaultAnalyticsCollector(Clock.DEFAULT))
                .build();
        setOptions();

        if (VideoViewManager.getConfig().mIsEnableLog && mTrackSelector instanceof MappingTrackSelector) {
            mInternalPlayer.addAnalyticsListener(new EventLogger((MappingTrackSelector) mTrackSelector, "ExoPlayer"));
        }
        if (trackSelector == null && mTrackSelector instanceof DefaultTrackSelector) {
            trackSelector = (DefaultTrackSelector) mTrackSelector;
        }
        mInternalPlayer.addListener(this);
    }

    public void setTrackSelector(TrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    public void setRenderersFactory(RenderersFactory renderersFactory) {
        mRenderersFactory = renderersFactory;
    }

    public void setLoadControl(LoadControl loadControl) {
        mLoadControl = loadControl;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        Log.i("Tvbox-runtime", "echo-setDataSource:" + path);
        currentPlayPath = path;
        currentHeaders = copyHeaders(headers);
        mRetriedAsHls = false;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, copyHeaders(currentHeaders));
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
    }

    @Override
    public void start() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource, getStartPosition());
        markStartPositionApplied();
        mInternalPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.stop();
            mInternalPlayer.clearMediaItems();
            mIsPreparing = false;
            mRetriedAsHls = false;
        }
    }

    @Override
    public boolean keepRenderViewOnReset() {
        return true;
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            mInternalPlayer.removeListener(this);
            mInternalPlayer.release();
            mInternalPlayer = null;
        }
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null)
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null)
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        return PlayerUtils.getNetSpeed(mAppContext);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        Log.e("Tvbox-runtime", "echo-Exo player error: " + currentPlayPath, error);
        if (retryAsHls(error)) {
            return;
        }
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    private boolean retryAsHls(PlaybackException error) {
        if (mRetriedAsHls || mInternalPlayer == null || currentPlayPath == null) {
            return false;
        }
        if (!isParsingError(error)) {
            return false;
        }
        mRetriedAsHls = true;
        Log.i("Tvbox-runtime", "echo-Exo retry as HLS: " + currentPlayPath);
        mMediaSource = mMediaSourceHelper.getHlsMediaSource(currentPlayPath, copyHeaders(currentHeaders));
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource, getStartPosition());
        markStartPositionApplied();
        mInternalPlayer.prepare();
        mInternalPlayer.setPlayWhenReady(true);
        return true;
    }

    private boolean isParsingError(PlaybackException error) {
        int errorCode = error.errorCode;
        return errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? null : new HashMap<>(headers);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }

    /**
     * 自适应多声道降混音频处理器：
     * 1. 动态探测当前 HDMI / 终端设备实际支持的最大声道数；
     * 2. 若终端支持 5.1/7.1（如连接功放），直接直通输出原生环绕声；
     * 3. 若终端仅支持 2 声道（如直连普通电视），自动按照 ITU-R 标准将多声道降混为立体声，防止 Android 5.1 AudioTrack 报错；
     * 4. 非 PCM 16-bit 格式（如透传流）或 <=2 声道常规音频直接直通。
     */
    public static class AdaptiveDownmixAudioProcessor extends BaseAudioProcessor {

        private final Context mContext;

        public AdaptiveDownmixAudioProcessor(Context context) {
            this.mContext = context.getApplicationContext();
        }

        @Override
        public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                return AudioFormat.NOT_SET;
            }
            if (inputAudioFormat.channelCount <= 2) {
                return AudioFormat.NOT_SET;
            }

            // 动态读取当前音频终端最大支持的物理声道数
            int maxSupportedChannels = getMaxSupportedOutputChannels();
            if (inputAudioFormat.channelCount <= maxSupportedChannels) {
                // 外接 5.1/7.1 音响或回音壁时，直通多声道，保留原生环绕效果
                return AudioFormat.NOT_SET;
            }

            // 设备仅支持 2 声道（如直连电视喇叭），降混为 2 声道立体声输出
            return new AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT);
        }

        private int getMaxSupportedOutputChannels() {
            try {
                Intent intent = mContext.registerReceiver(null, new IntentFilter("android.media.action.HDMI_AUDIO_PLUG"));
                if (intent != null && intent.getIntExtra("android.media.extra.AUDIO_PLUG_STATE", 0) == 1) {
                    int maxChannels = intent.getIntExtra("android.media.extra.MAX_CHANNEL_COUNT", 2);
                    if (maxChannels > 0) {
                        return maxChannels;
                    }
                }
            } catch (Throwable ignored) {
            }
            // Android 7.0 (API 24) 及以上系统原生自带底层混合管道；Android 5.1 及以下未探测到外设时保守返回 2
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? 8 : 2;
        }

        @Override
        public void queueInput(ByteBuffer inputBuffer) {
            int position = inputBuffer.position();
            int limit = inputBuffer.limit();
            int channelCount = inputAudioFormat.channelCount;
            int frameSize = channelCount * 2;
            int frames = (limit - position) / frameSize;
            int outputSize = frames * 4;

            ByteBuffer buffer = replaceOutputBuffer(outputSize);

            while (position < limit) {
                short left = inputBuffer.getShort(position);
                short right = inputBuffer.getShort(position + 2);
                short center = channelCount > 2 ? inputBuffer.getShort(position + 4) : 0;
                short lfe = channelCount > 3 ? inputBuffer.getShort(position + 6) : 0;
                short surroundLeft = channelCount > 4 ? inputBuffer.getShort(position + 8) : 0;
                short surroundRight = channelCount > 5 ? inputBuffer.getShort(position + 10) : 0;
                short backLeft = channelCount > 6 ? inputBuffer.getShort(position + 12) : 0;
                short backRight = channelCount > 7 ? inputBuffer.getShort(position + 14) : 0;

                // ITU-R BS.775 混音模型：中置与环绕声道按 0.707 (181/256) 混入，低频 LFE 按 0.5 (128/256) 补偿保留
                int cContrib = (center * 181) >> 8;
                int lfeContrib = (lfe * 128) >> 8;
                int outL = left + cContrib + lfeContrib + ((surroundLeft * 181) >> 8) + ((backLeft * 181) >> 8);
                int outR = right + cContrib + lfeContrib + ((surroundRight * 181) >> 8) + ((backRight * 181) >> 8);

                if (outL > 32767) outL = 32767;
                else if (outL < -32768) outL = -32768;

                if (outR > 32767) outR = 32767;
                else if (outR < -32768) outR = -32768;

                buffer.putShort((short) outL);
                buffer.putShort((short) outR);

                position += frameSize;
            }

            inputBuffer.position(limit);
            buffer.flip();
        }
    }
}
