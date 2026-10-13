package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

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
import com.google.android.exoplayer2.audio.AudioCapabilities;
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

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    private static final String TAG = "EXO-AUDIO";

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

    private static void writeDebugLog(Context context, String msg) {
        Log.e(TAG, msg);
        try {
            File logFile = new File(context.getExternalFilesDir(null), "exo_debug.log");
            if (!logFile.exists()) {
                File sdLog = new File("/sdcard/exo_debug.log");
                if (sdLog.canWrite() || !sdLog.exists()) {
                    logFile = sdLog;
                }
            }
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            FileWriter writer = new FileWriter(logFile, true);
            writer.write(time + " " + msg + "\n");
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    private void showToast(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(mAppContext, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        checkFfmpegExtensionStatus();

        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 30_000, 1_000, 3_000)
                    .setTargetBufferBytes(32 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .setBackBuffer(0, false)
                    .build();
        }

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

        if (mRenderersFactory == null) {
            mRenderersFactory = new DefaultRenderersFactory(mAppContext) {
                @Nullable
                @Override
                protected AudioSink buildAudioSink(
                        Context context,
                        boolean enableFloatOutput,
                        boolean enableAudioTrackPlaybackParams,
                        boolean enableOffload) {
                    
                    // 关键修复：显式锁定仅接收 PCM 16-bit，彻底关闭硬件伪透传，强制触发 FFmpeg 软解
                    AudioCapabilities pcmOnlyCapabilities = new AudioCapabilities(new int[]{C.ENCODING_PCM_16BIT}, 8);

                    return new DefaultAudioSink.Builder(context)
                            .setAudioCapabilities(pcmOnlyCapabilities) // 彻底杜绝透传，强制解码为 PCM
                            .setEnableFloatOutput(false)               // 锁死 16-bit 整型 PCM
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .setAudioProcessors(new AudioProcessor[]{new AdaptiveDownmixAudioProcessor(context)})
                            .build();
                }
            }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
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

    private void checkFfmpegExtensionStatus() {
        try {
            System.loadLibrary("ffmpegJNI");
            writeDebugLog(mAppContext, "[原生加载] System.loadLibrary(\"ffmpegJNI\") 成功！");
        } catch (Throwable t) {
            writeDebugLog(mAppContext, "[原生加载失败] dlopen 报错详情: " + t.getMessage());
            Log.e(TAG, "Native loadLibrary failed", t);
        }

        try {
            Class<?> ffmpegLibClass = Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary");
            Method isAvailableMethod = ffmpegLibClass.getMethod("isAvailable");
            boolean isAvailable = (Boolean) isAvailableMethod.invoke(null);
            String info = "[自检] FfmpegLibrary.isAvailable = " + isAvailable;
            if (isAvailable) {
                try {
                    Method getVersionMethod = ffmpegLibClass.getMethod("getVersion");
                    info += " (FFmpeg 版本: " + getVersionMethod.invoke(null) + ")";
                } catch (Throwable ignored) {
                }
                try {
                    Method supportsFormatMethod = ffmpegLibClass.getMethod("supportsFormat", String.class);
                    boolean supEac3 = (Boolean) supportsFormatMethod.invoke(null, "audio/eac3");
                    boolean supDts = (Boolean) supportsFormatMethod.invoke(null, "audio/vnd.dts");
                    info += " | 格式支持: EAC3=" + supEac3 + ", DTS=" + supDts;
                } catch (Throwable ignored) {
                }
            }
            writeDebugLog(mAppContext, info);
        } catch (Throwable e) {
            writeDebugLog(mAppContext, "[自检异常] 无法调用 FfmpegLibrary: " + e.getMessage());
        }
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
        writeDebugLog(mAppContext, "setDataSource: " + path);
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
        if (mInternalPlayer == null) return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null) return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null) return;
        mInternalPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null || mMediaSource == null) return;
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
        if (mInternalPlayer == null) return false;
        int state = mInternalPlayer.getPlaybackState();
        return (state == Player.STATE_BUFFERING || state == Player.STATE_READY) && mInternalPlayer.getPlayWhenReady();
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null) return;
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
        return mInternalPlayer == null ? 0 : mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getDuration();
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
        setSurface(holder == null ? null : holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
        }
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        }
    }

    @Override
    public void setOptions() {
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        mSpeedPlaybackParameters = new PlaybackParameters(speed);
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
    }

    @Override
    public float getSpeed() {
        return mSpeedPlaybackParameters != null ? mSpeedPlaybackParameters.speed : 1f;
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
        String errDetail = "errorCode=" + error.errorCode + " (" + error.getErrorCodeName() + "), msg=" + error.getMessage();
        Throwable cause = error.getCause();
        if (cause != null) {
            errDetail += " | cause: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        writeDebugLog(mAppContext, "[播放报错] " + errDetail);

        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) {

            showToast("音频解码异常: " + (cause != null ? cause.getMessage() : error.getErrorCodeName()) + " -> 正在回退音轨");

            if (trackSelector != null && mInternalPlayer != null) {
                writeDebugLog(mAppContext, "[容灾生效] 重置音轨到默认");
                trackSelector.setParameters(trackSelector.buildUponParameters().clearSelectionOverrides(C.TRACK_TYPE_AUDIO));
                mInternalPlayer.prepare();
                mInternalPlayer.setPlayWhenReady(true);
                return;
            }
        }

        if (retryAsHls(error)) {
            return;
        }
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    private boolean retryAsHls(PlaybackException error) {
        if (mRetriedAsHls || mInternalPlayer == null || currentPlayPath == null) return false;
        int errorCode = error.errorCode;
        boolean isParsingError = errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
        if (!isParsingError) return false;
        mRetriedAsHls = true;
        writeDebugLog(mAppContext, "retryAsHls: " + currentPlayPath);
        mMediaSource = mMediaSourceHelper.getHlsMediaSource(currentPlayPath, copyHeaders(currentHeaders));
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource, getStartPosition());
        markStartPositionApplied();
        mInternalPlayer.prepare();
        mInternalPlayer.setPlayWhenReady(true);
        return true;
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

    public static class AdaptiveDownmixAudioProcessor extends BaseAudioProcessor {
        private final Context mContext;

        public AdaptiveDownmixAudioProcessor(Context context) {
            this.mContext = context.getApplicationContext();
        }

        @Override
        public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
            writeDebugLog(mContext, "[AudioProcessor] 输入格式: sampleRate=" + inputAudioFormat.sampleRate
                    + ", channels=" + inputAudioFormat.channelCount + ", encoding=" + inputAudioFormat.encoding);

            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                writeDebugLog(mContext, "[AudioProcessor] 非 16-bit PCM，直通输出");
                return AudioFormat.NOT_SET;
            }
            if (inputAudioFormat.channelCount <= 2) {
                writeDebugLog(mContext, "[AudioProcessor] <=2 声道，直通输出");
                return AudioFormat.NOT_SET;
            }

            int maxSupportedChannels = getMaxSupportedOutputChannels();
            writeDebugLog(mContext, "[AudioProcessor] 终端最大支持声道数: " + maxSupportedChannels);

            if (inputAudioFormat.channelCount <= maxSupportedChannels) {
                writeDebugLog(mContext, "[AudioProcessor] 终端支持多声道，直通 5.1/7.1 输出");
                return AudioFormat.NOT_SET;
            }

            writeDebugLog(mContext, "[AudioProcessor] 触发立体声降混 (6/8 -> 2)");
            return new AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT);
        }

        private int getMaxSupportedOutputChannels() {
            try {
                Intent intent = mContext.registerReceiver(null, new IntentFilter("android.media.action.HDMI_AUDIO_PLUG"));
                if (intent != null && intent.getIntExtra("android.media.extra.AUDIO_PLUG_STATE", 0) == 1) {
                    int maxChannels = intent.getIntExtra("android.media.extra.MAX_CHANNEL_COUNT", 2);
                    if (maxChannels > 0) return maxChannels;
                }
            } catch (Throwable ignored) {
            }
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
