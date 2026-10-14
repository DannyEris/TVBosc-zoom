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

    // 终极出帧心跳监控哨兵（基于送显帧物理计数，绝不哑火）
    private volatile long mTotalRenderedFrameCount = 0;
    private long mLastCheckFrameCount = 0;
    private long mLastCheckTimeRealtime = 0;
    private int mConsecutiveStallCount = 0;
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

    private void initWatchdog() {
        mWatchdogHandler = new Handler(Looper.getMainLooper());
        mWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    checkPlaybackHealth();
                } catch (Throwable t) {
                    writeDebugLog(mAppContext, "[自愈哨兵] 巡检异常: " + t.getMessage());
                }
                if (mWatchdogHandler != null) {
                    mWatchdogHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    private void checkPlaybackHealth() {
        if (mInternalPlayer == null || mIsPreparing) {
            return;
        }

        boolean playWhenReady = mInternalPlayer.getPlayWhenReady();
        if (!playWhenReady) {
            mLastCheckTimeRealtime = SystemClock.elapsedRealtime();
            mConsecutiveStallCount = 0;
            return;
        }

        long duration = mInternalPlayer.getDuration();
        if (duration <= 0) {
            mLastCheckTimeRealtime = SystemClock.elapsedRealtime();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (mLastCheckTimeRealtime == 0) {
            mLastCheckTimeRealtime = now;
            mLastCheckFrameCount = mTotalRenderedFrameCount;
            return;
        }

        long currentRenderedCount = mTotalRenderedFrameCount;
        if (currentRenderedCount > mLastCheckFrameCount) {
            // 真实物理帧在持续上屏流动，播放完全健康！
            mLastCheckFrameCount = currentRenderedCount;
            mLastCheckTimeRealtime = now;
            mConsecutiveStallCount = 0;
            return;
        }

        // 帧计数未增长，计算画面静止毫秒数
        long stallDuration = now - mLastCheckTimeRealtime;
        int state = mInternalPlayer.getPlaybackState();
        long currentPos = getCurrentPosition();
        long bufferedPos = mInternalPlayer.getBufferedPosition();
        long bufferAheadMs = bufferedPos - currentPos;

        // 核心解脱逻辑：
        // 1. 就绪状态下 2 秒无帧，或缓冲蓄水充足（>=1500ms）却停滞 2 秒，判定为硬件卡死；
        // 2. 无论播放器汇报任何状态，只要绝对定格超过 5 秒，强制判定为底层死锁！
        boolean isHardwareStuck = (state == Player.STATE_READY && stallDuration >= 2000)
                || (state == Player.STATE_BUFFERING && bufferAheadMs >= 1500 && stallDuration >= 2000)
                || (stallDuration >= 5000);

        if (isHardwareStuck) {
            if (mConsecutiveStallCount == 0) {
                // 一级自愈：微跃 300ms 刷新硬件并跨过损坏帧
                writeDebugLog(mAppContext, "[自愈哨兵] 检测到视频硬件停滞 " + stallDuration + "ms (缓存充沛=" + bufferAheadMs + "ms)，执行一级微跃脱困！");
                mConsecutiveStallCount++;
                mLastCheckTimeRealtime = now;
                long targetPos = Math.min(currentPos + 300, duration);
                mInternalPlayer.seekTo(targetPos);
            } else {
                // 二级自愈：一级脱困未奏效，驱动陷入 200003f 死循环，彻底重构解码器与数据管道！
                writeDebugLog(mAppContext, "[自愈哨兵] 一级脱困无效，底层硬件深度死锁，执行二级硬核管道重建！");
                mConsecutiveStallCount = 0;
                mLastCheckTimeRealtime = now;
                long targetPos = Math.min(currentPos + 1000, duration); // 跨过卡顿点 1 秒
                mInternalPlayer.stop();
                mInternalPlayer.setMediaSource(mMediaSource, targetPos);
                mInternalPlayer.prepare();
                mInternalPlayer.setPlayWhenReady(true);
            }
        }
    }

    @Override
    public void initPlayer() {
        registerModeReceiver();
        checkFfmpegExtensionStatus();

        boolean multiChannel = isMultiChannelModeEnabled(mAppContext);
        writeDebugLog(mAppContext, "[initPlayer] 音频模式: " + (multiChannel ? "【功放 5.1/7.1 直通】" : "【电视 2.0 下混】"));

        mTotalRenderedFrameCount = 0;
        mLastCheckFrameCount = 0;
        mLastCheckTimeRealtime = 0;
        mConsecutiveStallCount = 0;

        mLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 35_000, 1_500, 3_000)
                .setTargetBufferBytes(64 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build();

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

        mRenderersFactory = createCustomRenderersFactory(multiChannel);

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

                writeDebugLog(context, "[buildVideoRenderers] 成功装载晶晨 4K 稳态渲染器与帧心跳监控！");

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

                    // 核心关键修复：完全移除 shouldDropOutputBuffer 与 shouldDropBuffersToKeyframe 的强制 false 拦截！
                    // 允许 ExoPlayer 在 4K 60fps 晚到时释放 CMA 缓冲块，彻底根绝晶晨驱动显存池枯竭死锁！

                    @Override
                    protected void renderOutputBufferV21(
                            MediaCodecAdapter codec,
                            int index,
                            long presentationTimeUs,
                            long releaseTimeNs) {
                        mTotalRenderedFrameCount++;
                        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
                    }

                    @Override
                    protected void renderOutputBuffer(
                            MediaCodecAdapter codec,
                            int index,
                            long presentationTimeUs) {
                        mTotalRenderedFrameCount++;
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
        // 屏蔽外部注入，锁定专属定制渲染器工厂
    }

    public void setLoadControl(LoadControl loadControl) {
        // 屏蔽外部覆盖，确保 4K 缓冲配比生效
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        if (path != null) {
            if (path.contains("thread=")) {
                path = path.replaceAll("thread=\\d+", "thread=4");
                writeDebugLog(mAppContext, "[并发优化] 锁定代理并发为 4 线程，全力保障 4K 高码率吞吐");
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
        mLastCheckTimeRealtime = SystemClock.elapsedRealtime();
        mConsecutiveStallCount = 0;
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
            mLastCheckTimeRealtime = 0;
            mConsecutiveStallCount = 0;
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
        mLastCheckTimeRealtime = SystemClock.elapsedRealtime();
        mConsecutiveStallCount = 0;
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
