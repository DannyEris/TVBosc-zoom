package xyz.doikki.videoplayer.exo;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.DefaultAnalyticsCollector;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat;
import com.google.android.exoplayer2.audio.AudioProcessor.UnhandledAudioFormatException;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.video.VideoSize;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    private static final String TAG = "Tvbox-runtime";
    private static final String SP_NAME = "tvbox_exo_audio_config";
    private static final String KEY_MULTICHANNEL_MODE = "key_multichannel_mode";
    public static final String ACTION_TOGGLE_AUDIO_MODE = "action.TVBOX_TOGGLE_AUDIO_MODE";

    public static final boolean DEFAULT_ENABLE_MULTICHANNEL_PCM = false;

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

    private BroadcastReceiver mModeToggleReceiver;
    private static File sDebugLogFile = null;

    // 自愈哨兵状态与冷却锁
    private volatile long mLastRenderedFrameTimeRealtime = 0;
    private volatile boolean mIsRecovering = false;
    private Handler mWatchdogHandler;
    private Runnable mWatchdogRunnable;

    public static boolean isMultiChannelModeEnabled(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_MULTICHANNEL_MODE, DEFAULT_ENABLE_MULTICHANNEL_PCM);
    }

    public static void setMultiChannelModeEnabled(Context context, boolean enabled) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_MULTICHANNEL_MODE, enabled).apply();
    }

    private static synchronized void writeDebugLog(Context context, String msg) {
        Log.i(TAG, msg);
        try {
            if (sDebugLogFile == null) {
                File sdLog = new File("/sdcard/exo_debug.log");
                if (sdLog.canWrite() || !sdLog.exists()) {
                    sDebugLogFile = sdLog;
                } else {
                    sDebugLogFile = new File(context.getFilesDir(), "exo_debug.log");
                }
            }
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            FileWriter writer = new FileWriter(sDebugLogFile, true);
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
                    Toast.makeText(mAppContext, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
        registerModeReceiver();
        initWatchdog();
    }

    private void registerModeReceiver() {
        if (mModeToggleReceiver != null) {
            return;
        }
        try {
            mModeToggleReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_TOGGLE_AUDIO_MODE.equals(intent.getAction())) {
                        boolean current = isMultiChannelModeEnabled(context);
                        boolean next = !current;
                        setMultiChannelModeEnabled(context, next);
                        String tip = next ? "已切换至: 功放模式 (原生 5.1/7.1 PCM 直通)" 
                                          : "已切换至: 电视模式 (2.0 立体声下混)";
                        showToast(tip);
                        writeDebugLog(context, "[热切换触发] " + tip);

                        if (mInternalPlayer != null && currentPlayPath != null) {
                            long pos = getCurrentPosition();
                            mInternalPlayer.removeListener(ExoMediaPlayer.this);
                            mInternalPlayer.release();
                            mInternalPlayer = null;
                            initPlayer();
                            setDataSource(currentPlayPath, currentHeaders);
                            seekTo(pos);
                            prepareAsync();
                        }
                    }
                }
            };
            mAppContext.registerReceiver(mModeToggleReceiver, new IntentFilter(ACTION_TOGGLE_AUDIO_MODE));
        } catch (Throwable ignored) {
        }
    }

    // 借鉴 Kodi 机制并加入防误触冷却锁的自愈哨兵
    private void initWatchdog() {
        mWatchdogHandler = new Handler(Looper.getMainLooper());
        mWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (mInternalPlayer != null && isPlaying()) {
                    long now = SystemClock.elapsedRealtime();

                    if (mIsRecovering) {
                        // 处于脱困刷新状态中，3 秒内不重复判定，防止界面进度反复跳跃
                        if (now - mLastRenderedFrameTimeRealtime > 3000) {
                            mIsRecovering = false;
                        }
                    } else {
                        // 正常播放时，若画面超过 1200ms 未吐出新帧且播放已正常起步，判定为显存耗尽挂起
                        if (mLastRenderedFrameTimeRealtime > 0 
                                && (now - mLastRenderedFrameTimeRealtime > 1200) 
                                && getCurrentPosition() > 2500) {
                            
                            writeDebugLog(mAppContext, "[自愈哨兵] 检测到视频硬件帧停滞，触发精准 Flush 脱困！");
                            mIsRecovering = true; // 上锁
                            mLastRenderedFrameTimeRealtime = now;
                            
                            // 强制触发底层 OMX_CommandFlush 与 VPU 物理显存释放重置
                            long currentPos = getCurrentPosition();
                            mInternalPlayer.seekTo(currentPos);
                        }
                    }
                }
                if (mWatchdogHandler != null) {
                    mWatchdogHandler.postDelayed(this, 500);
                }
            }
        };
    }

    @Override
    public void initPlayer() {
        registerModeReceiver();
        checkFfmpegExtensionStatus();

        boolean multiChannel = isMultiChannelModeEnabled(mAppContext);
        writeDebugLog(mAppContext, "[initPlayer] 音频模式: " + (multiChannel ? "【功放 5.1/7.1 直通】" : "【电视 2.0 下混】"));

        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();

        // 借鉴原版保护结构 + 融合控温防断连的最佳 4K 缓冲配比
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 35_000, 1_500, 3_000) // 1.5s 秒开，15s~35s 蓄水池
                    .setTargetBufferBytes(48 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
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

        // 借鉴原版：强制开启 DecoderFallback (解码器回退能力)
        if (mRenderersFactory == null) {
            mRenderersFactory = createCustomRenderersFactory(multiChannel);
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

        if (mWatchdogHandler != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogHandler.postDelayed(mWatchdogRunnable, 1000);
        }
    }

    private RenderersFactory createCustomRenderersFactory(final boolean enableMultiChannel) {
        // 借鉴原版：setEnableDecoderFallback(true) 全局生效
        DefaultRenderersFactory factory = new DefaultRenderersFactory(mAppContext) {
            @Override
            protected void buildVideoRenderers(
                    Context context,
                    int extensionRendererMode,
                    MediaCodecSelector mediaCodecSelector,
                    boolean enableDecoderFallback,
                    Handler eventHandler,
                    VideoRendererEventListener eventListener,
                    long allowedVideoJoiningTimeMs,
                    ArrayList<Renderer> out) {

                writeDebugLog(context, "[buildVideoRenderers] 挂载晶晨专属 4K 稳态硬解渲染器...");

                MediaCodecVideoRenderer videoRenderer = new MediaCodecVideoRenderer(
                        context,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        50) {

                    @Override
                    @SuppressLint("InlinedApi")
                    protected MediaFormat getMediaFormat(
                            Format format,
                            String codecMimeType,
                            CodecMaxValues codecMaxValues,
                            float codecOperatingRate,
                            boolean deviceNeedsNoPostProcessWorkaround,
                            int tunnelingAudioSessionId) {
                        MediaFormat mediaFormat = super.getMediaFormat(
                                format, codecMimeType, codecMaxValues, codecOperatingRate,
                                deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId);

                        // 扩大输入缓冲至 4MB，确保 4K 码流中的大 I 帧完整喂入
                        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
                        return mediaFormat;
                    }

                    @Override
                    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
                        return false;
                    }

                    @Override
                    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
                        return false;
                    }

                    @Override
                    protected void renderOutputBufferV21(
                            MediaCodecAdapter codec,
                            int index,
                            long presentationTimeUs,
                            long releaseTimeNs) {
                        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
                        mIsRecovering = false; // 画面正常吐出新帧，解除哨兵锁定
                        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
                    }

                    @Override
                    protected void renderOutputBuffer(
                            MediaCodecAdapter codec,
                            int index,
                            long presentationTimeUs) {
                        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
                        mIsRecovering = false;
                        super.renderOutputBuffer(codec, index, presentationTimeUs);
                    }
                };

                out.add(videoRenderer);
            }

            @Override
            protected void buildAudioRenderers(
                    Context context,
                    int extensionRendererMode,
                    MediaCodecSelector mediaCodecSelector,
                    boolean enableDecoderFallback,
                    AudioSink audioSink,
                    Handler eventHandler,
                    AudioRendererEventListener eventListener,
                    ArrayList<Renderer> out) {

                writeDebugLog(context, "[buildAudioRenderers] 锁定 FFmpeg 第一顺位软解...");

                boolean ffmpegAdded = false;
                try {
                    Class<?> clazz = Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegAudioRenderer");
                    Constructor<?> constructor = clazz.getConstructor(
                            Handler.class, AudioRendererEventListener.class, AudioSink.class);
                    Renderer ffmpegRenderer = (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
                    out.add(ffmpegRenderer);
                    ffmpegAdded = true;
                    writeDebugLog(context, "[buildAudioRenderers] FFmpegAudioRenderer 挂载成功！");
                } catch (Throwable e) {
                    writeDebugLog(context, "[buildAudioRenderers] FFmpegAudioRenderer 异常: " + e.getMessage());
                }

                out.add(new MediaCodecAudioRenderer(
                        context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink));
            }

            @Nullable
            @Override
            protected AudioSink buildAudioSink(
                    Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams,
                    boolean enableOffload) {

                int maxChannels = enableMultiChannel ? 8 : 2;
                AudioCapabilities pcmCapabilities = new AudioCapabilities(new int[]{C.ENCODING_PCM_16BIT}, maxChannels);

                return new DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(pcmCapabilities)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[]{new SwitchableDownmixAudioProcessor(context, enableMultiChannel)})
                        .build();
            }
        };

        factory.setEnableDecoderFallback(true);
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        return factory;
    }

    private void checkFfmpegExtensionStatus() {
        try {
            System.loadLibrary("ffmpegJNI");
            writeDebugLog(mAppContext, "[原生库自检] System.loadLibrary(\"ffmpegJNI\") 成功！");
        } catch (Throwable t) {
            writeDebugLog(mAppContext, "[原生库自检] dlopen 报错: " + t.getMessage());
        }

        try {
            Class<?> ffmpegLibClass = Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary");
            Method isAvailableMethod = ffmpegLibClass.getMethod("isAvailable");
            boolean isAvailable = (Boolean) isAvailableMethod.invoke(null);
            String info = "[自检] FfmpegLibrary.isAvailable = " + isAvailable;
            if (isAvailable) {
                try {
                    Method getVersionMethod = ffmpegLibClass.getMethod("getVersion");
                    info += " (版本: " + getVersionMethod.invoke(null) + ")";
                } catch (Throwable ignored) {
                }
            }
            writeDebugLog(mAppContext, info);
        } catch (Throwable e) {
            writeDebugLog(mAppContext, "[自检] 无法调用 FfmpegLibrary: " + e.getMessage());
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
        if (path != null) {
            // 平滑并发：收敛为 2 线程，消除 85°C 强制拔核风险，且实测 10MB/s 带宽足够 4K 播放
            if (path.contains("thread=")) {
                path = path.replaceAll("thread=\\d+", "thread=2");
                writeDebugLog(mAppContext, "[控温优化] 自动收敛代理并发为 2 线程，平滑保护 CPU 温度与总线");
            }
        }
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
        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mWatchdogHandler != null) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogHandler = null;
        }
        if (mModeToggleReceiver != null) {
            try {
                mAppContext.unregisterReceiver(mModeToggleReceiver);
                mModeToggleReceiver = null;
            } catch (Throwable ignored) {
            }
        }
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
        writeDebugLog(mAppContext, "[播放异常] " + errDetail);

        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) {

            if (isMultiChannelModeEnabled(mAppContext)) {
                writeDebugLog(mAppContext, "[声卡保护] 自动降级为电视立体声模式");
                setMultiChannelModeEnabled(mAppContext, false);
                showToast("声卡不支持多声道，已自动切换为电视立体声模式");
                if (mInternalPlayer != null) {
                    long pos = getCurrentPosition();
                    mInternalPlayer.removeListener(ExoMediaPlayer.this);
                    mInternalPlayer.release();
                    mInternalPlayer = null;
                    initPlayer();
                    setDataSource(currentPlayPath, currentHeaders);
                    seekTo(pos);
                    prepareAsync();
                    return;
                }
            }

            if (trackSelector != null && mInternalPlayer != null) {
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

    public static class SwitchableDownmixAudioProcessor extends BaseAudioProcessor {
        private final Context mContext;
        private final boolean mEnableMultiChannel;

        public SwitchableDownmixAudioProcessor(Context context, boolean enableMultiChannel) {
            this.mContext = context.getApplicationContext();
            this.mEnableMultiChannel = enableMultiChannel;
        }

        @Override
        public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 2) {
                return AudioFormat.NOT_SET;
            }

            if (mEnableMultiChannel) {
                writeDebugLog(mContext, "[AudioProcessor] 【功放直通】放行原生 " + inputAudioFormat.channelCount + " 声道 PCM");
                return AudioFormat.NOT_SET;
            }

            writeDebugLog(mContext, "[AudioProcessor] 【立体声下混】激活 ITU-R BS.775 算法: "
                    + inputAudioFormat.channelCount + " 声道 -> 2 声道");
            return new AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT);
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
