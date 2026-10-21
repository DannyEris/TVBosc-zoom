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

    // 日志文件最大体积限制：2MB
    private static final long MAX_LOG_FILE_SIZE = 2 * 1024 * 1024;

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

    // 活跃出帧监控心跳
    private volatile long mLastRenderedFrameTimeRealtime = 0;
    // 实时物理出帧统计 (FPS)
    private volatile int mRenderedFrameCount = 0;
    private int mLastFpsFrameCount = 0;
    private long mLastFpsCalcTime = 0;
    private volatile float mCurrentRealFps = 0f;
    private int mStallRescueStep = 0;
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

            // 文件尺寸达标清空机制：超过 2MB 时清空重置
            boolean append = true;
            if (sDebugLogFile.exists() && sDebugLogFile.length() > MAX_LOG_FILE_SIZE) {
                append = false;
            }

            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            FileWriter writer = new FileWriter(sDebugLogFile, append);
            if (!append) {
                writer.write("--- [日志达到 2MB 上限，已自动清空循环轮转] ---\n");
            }
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
                        writeDebugLog(context, "[热切换广播触发] " + tip);

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

    // 优化的无感避障看门狗哨兵
    private void initWatchdog() {
        mWatchdogHandler = new Handler(Looper.getMainLooper());
        mWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (mInternalPlayer != null && isPlaying()) {
                    long now = SystemClock.elapsedRealtime();
                    // 门槛 1200ms：快速捕捉底层卡顿
                    if (mLastRenderedFrameTimeRealtime > 0 
                            && (now - mLastRenderedFrameTimeRealtime > 1200) 
                            && getCurrentPosition() > 3000) {
                        
                        long currentPos = getCurrentPosition();
                        long duration = getDuration();

                        if (mStallRescueStep == 0) {
                            // 第一顺位：尝试原地 Flush 解锁显存拥塞
                            writeDebugLog(mAppContext, "[自愈哨兵] 检测到视频硬件停滞，执行温和【原地 Flush】自愈！");
                            mStallRescueStep = 1;
                            // 将二次判定等待压缩至 600ms（若 600ms 内芯片未能恢复出帧，立即执行快进脱困）
                            mLastRenderedFrameTimeRealtime = now - 600;
                            mInternalPlayer.seekTo(currentPos);
                        } else {
                            // 第二顺位：维持 3.5 秒固定步长，彻底跨过当前损坏的 GOP 坏块区
                            long targetPos = (duration > 0) ? Math.min(currentPos + 3500, duration) : currentPos + 3500;
                            writeDebugLog(mAppContext, "[自愈哨兵] 原地 Flush 后仍受阻(损坏帧)，执行【跨 GOP 快进 3.5 秒】彻底跨过坏块！目标点: " + targetPos);
                            mStallRescueStep = 0;
                            mLastRenderedFrameTimeRealtime = now + 2500; // 跨过坏帧后给予 2.5 秒正常缓冲恢复期
                            mInternalPlayer.seekTo(targetPos);
                        }
                    }
                }
                if (mWatchdogHandler != null) {
                    mWatchdogHandler.postDelayed(this, 300); // 300ms 高频巡检
                }
            }
        };
    }

    @Override
    public void initPlayer() {
        registerModeReceiver();
        checkFfmpegExtensionStatus();

        boolean multiChannel = isMultiChannelModeEnabled(mAppContext);
        writeDebugLog(mAppContext, "[initPlayer] 当前音频输出模式: " + (multiChannel ? "【功放模式 5.1 直通】" : "【电视模式 2.0 下混】"));

        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
        mStallRescueStep = 0;

        // 4K 60fps 专属大水池配置：大幅提高后台预读水位，彻底消除断续卡顿感
        mLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 60_000, 2_500, 10_000)
                .setTargetBufferBytes(96 * 1024 * 1024) // 显存缓冲扩容至 96MB
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
        return new DefaultRenderersFactory(mAppContext) {
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

                writeDebugLog(context, "[buildVideoRenderers] 挂载稳态渲染器与心跳监控...");

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

                        // 扩大输入缓冲至 4MB，容纳 4K 高码率大 I 帧
                        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);

                        // 算力拉满：强制提升 4K 60fps 芯片解码频率和最高优先级，杜绝底层掉帧
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            mediaFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, 120.0f);
                            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
                        }
                        return mediaFormat;
                    }

                    // 1. 恢复 GOP 级安全追赶（容忍门槛放宽回 500ms，杜绝破坏参考帧队列）
                    @Override
                    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
                        return earlyUs < -500_000;
                    }

                    // 2. 核心：在单帧渲染层积极跳显（落后超过 1 帧 16ms 即跳过显示，死死跟紧音频）
                    @Override
                    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
                        // 对于 60fps 视频，每帧间隔仅 16.6ms；落后超过 25ms 立即丢弃当前过期帧直接呈现下一帧
                        return earlyUs < -25_000;
                    }

                    @Override
                    protected void renderOutputBufferV21(
                            MediaCodecAdapter codec,
                            int index,
                            long presentationTimeUs,
                            long releaseTimeNs) {
                        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
                        mRenderedFrameCount++;
                        mStallRescueStep = 0;
                        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
                    }

                    @Override
                    protected void renderOutputBuffer(
                            MediaCodecAdapter codec,
                            int index,
                            long presentationTimeUs) {
                        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
                        mRenderedFrameCount++;
                        mStallRescueStep = 0;
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

                writeDebugLog(context, "[buildAudioRenderers] 构建最优智能分流音频管线(支持隧道硬件直通+特殊音轨兜底)...");

                // 核心：智能音频解码选择器
                // 对芯片无授权的 EAC3/AC3/DTS/TrueHD 主动拦截返回空，迫使硬解器声明“不支持”，安全分流至 FFmpeg 软解！
                MediaCodecSelector smartAudioCodecSelector = new MediaCodecSelector() {
                    @Override
                    public java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> getDecoderInfos(
                            String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                            throws com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
                        if (com.google.android.exoplayer2.util.MimeTypes.AUDIO_E_AC3.equalsIgnoreCase(mimeType)
                                || com.google.android.exoplayer2.util.MimeTypes.AUDIO_AC3.equalsIgnoreCase(mimeType)
                                || com.google.android.exoplayer2.util.MimeTypes.AUDIO_DTS.equalsIgnoreCase(mimeType)
                                || com.google.android.exoplayer2.util.MimeTypes.AUDIO_DTS_HD.equalsIgnoreCase(mimeType)
                                || com.google.android.exoplayer2.util.MimeTypes.AUDIO_TRUEHD.equalsIgnoreCase(mimeType)) {
                            return java.util.Collections.emptyList();
                        }
                        return mediaCodecSelector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                    }
                };

                // 1. 第一顺位：智能硬件音频渲染器 (AAC/MP3 硬解直通，完美激活真·隧道模式 HW-Sync，声画绝对同步)
                out.add(new MediaCodecAudioRenderer(
                        context, smartAudioCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink));

                // 2. 第二顺位：FFmpeg 软解保底 (EAC3 6声道、DTS 自动在此出声，百分之百不静音)
                try {
                    Class<?> clazz = Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegAudioRenderer");
                    Constructor<?> constructor = clazz.getConstructor(
                            Handler.class, AudioRendererEventListener.class, AudioSink.class);
                    Renderer ffmpegRenderer = (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
                    out.add(ffmpegRenderer);
                    writeDebugLog(context, "[buildAudioRenderers] FFmpeg 软解兜底就绪！");
                } catch (Throwable e) {
                    writeDebugLog(context, "[buildAudioRenderers] FFmpeg 异常: " + e.getMessage());
                }
            }

            @Nullable
            @Override
            protected AudioSink buildAudioSink(
                    Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams,
                    boolean enableOffload) {

                AudioCapabilities pcmCapabilities = new AudioCapabilities(new int[]{C.ENCODING_PCM_16BIT}, 8);

                return new DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(pcmCapabilities)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        // 注入 150ms 音频延时处理器，强制音频等待电视画质芯片后处理，声画精准咬合
                        .setAudioProcessors(new AudioProcessor[]{
                                new AudioDelayOffsetProcessor(150),
                                new SwitchableDownmixAudioProcessor(context, enableMultiChannel)
                        })
                        .build();
            }
        };
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
        mStallRescueStep = 0;
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
            mStallRescueStep = 0;
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
        // 用户主动 Seek 赋予 2.5 秒正常蓄水保护
        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime() + 2500;
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
                // 保留中央贪食蛇/加载菊花圈
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
    // 获取当前播放视频的 Format 格式对象
    public Format getVideoFormat() {
        if (mInternalPlayer != null) {
            return mInternalPlayer.getVideoFormat();
        }
        return null;
    }

    // 获取当前真实物理渲染帧率 (FPS)
    public float getRealFps() {
        if (mInternalPlayer == null || !isPlaying()) {
            return 0f;
        }
        // 1. 如果片源流头标注了静态帧率且大于0，优先参考
        Format format = mInternalPlayer.getVideoFormat();
        if (format != null && format.frameRate > 0) {
            return format.frameRate;
        }

        // 2. 依据硬件实际送显出帧频率进行测算
        long now = SystemClock.elapsedRealtime();
        if (mLastFpsCalcTime > 0) {
            long duration = now - mLastFpsCalcTime;
            if (duration >= 800) { // 约每秒计算一次
                int frames = mRenderedFrameCount - mLastFpsFrameCount;
                if (frames > 0) {
                    mCurrentRealFps = (frames * 1000f) / duration;
                }
                mLastFpsFrameCount = mRenderedFrameCount;
                mLastFpsCalcTime = now;
            }
        } else {
            mLastFpsCalcTime = now;
            mLastFpsFrameCount = mRenderedFrameCount;
        }
        return mCurrentRealFps;
    }
    // 动态调整 ExoPlayer 底层解码器硬件裁切模式
    public void setVideoScalingMode(int videoScalingMode) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoScalingMode(videoScalingMode);
        }
    }

    // 动态配置 ExoPlayer 硬件音频时钟直通（隧道模式）
    public void setTunneling(boolean enable) {
        if (trackSelector != null) {
            trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                            .setTunnelingEnabled(enable));
            writeDebugLog(mAppContext, "[隧道模式] 硬件直通参数已写入: " + (enable ? "开启" : "关闭"));
        }
    }

    public boolean isTunneling() {
        if (trackSelector != null) {
            return trackSelector.getParameters().tunnelingEnabled;
        }
        return false;
    }
    /**
     * 硬件延时对齐处理器：解决大屏电视画质芯片（MEMC/降噪）带来的画面滞后问题
     */
    public static class AudioDelayOffsetProcessor extends BaseAudioProcessor {
        private final int mDelayMs;
        private byte[] mDelayBuffer;
        private int mDelayBytes;
        private boolean mIsInitialized;

        public AudioDelayOffsetProcessor(int delayMs) {
            this.mDelayMs = delayMs;
        }

        @Override
        public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || mDelayMs <= 0) {
                return AudioFormat.NOT_SET;
            }
            // 计算延时对应的静音字节数：延迟毫秒 * 采样率 * 通道数 * 2 字节
            int bytesPerMs = (inputAudioFormat.sampleRate * inputAudioFormat.channelCount * 2) / 1000;
            mDelayBytes = mDelayMs * bytesPerMs;
            mDelayBuffer = new byte[mDelayBytes];
            mIsInitialized = false;
            return inputAudioFormat;
        }

        @Override
        public void queueInput(ByteBuffer inputBuffer) {
            int remaining = inputBuffer.remaining();
            if (remaining == 0) return;

            // 首次起播注入一段静音填充，使声音延迟 mDelayMs 产生，等待电视画质芯片完成帧处理
            if (!mIsInitialized && mDelayBytes > 0) {
                ByteBuffer silenceBuffer = replaceOutputBuffer(mDelayBytes + remaining);
                silenceBuffer.put(mDelayBuffer);
                silenceBuffer.put(inputBuffer);
                silenceBuffer.flip();
                mIsInitialized = true;
            } else {
                ByteBuffer output = replaceOutputBuffer(remaining);
                output.put(inputBuffer);
                output.flip();
            }
        }

        @Override
        protected void onReset() {
            mIsInitialized = false;
        }
    }
}
