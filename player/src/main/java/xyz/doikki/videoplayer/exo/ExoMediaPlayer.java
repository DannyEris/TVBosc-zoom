package xyz.doikki.videoplayer.exo;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
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
import com.google.android.exoplayer2.ExoPlaybackException;
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
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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

    // 活跃出帧监控与音画联动检测变量
    private volatile long mLastRenderedFrameTimeRealtime = 0;
    private volatile int mRenderedFrameCount = 0;
    private int mLastFpsFrameCount = 0;
    private long mLastFpsCalcTime = 0;
    private volatile float mCurrentRealFps = 0f;
    private int mStallRescueStep = 0;
    private Handler mWatchdogHandler;
    private Runnable mWatchdogRunnable;

    // 音画联动监测锚点
    private long mLastTrackedPosition = -1;
    private long mLastPositionAdvanceTime = 0;

    // 格式标识：是否为 HEVC/H.265
    private volatile boolean mIsHevcStream = false;

    // 设备画像缓存 (静态一次性探测)
    private static int sTotalRamMb = -1;
    private static int sTotalCmaKb = -1;

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
        detectDeviceProfile(mAppContext);
        registerModeReceiver();
        initWatchdog();
    }

    // ==================== 1. 设备环境与硬件画像自适应探测 ====================

    private static synchronized void detectDeviceProfile(Context context) {
        if (sTotalRamMb > 0) return;

        // 探测系统物理总 RAM
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                sTotalRamMb = (int) (mi.totalMem / (1024 * 1024));
            }
        } catch (Throwable ignored) {
        }
        if (sTotalRamMb <= 0) sTotalRamMb = 2048; // 保守兜底 2GB

        // 探测 Linux 底层真实 TotalCMA 大小 (/proc/meminfo)
        File meminfo = new File("/proc/meminfo");
        if (meminfo.exists() && meminfo.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(meminfo))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.US);
                    if (lower.contains("cma") && lower.contains("total")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            sTotalCmaKb = Integer.parseInt(parts[1]);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        writeDebugLog(context, "[硬件画像] 探测就绪: 物理运存 = " + sTotalRamMb + " MB, CMA总量 = " 
                + (sTotalCmaKb > 0 ? (sTotalCmaKb / 1024 + " MB") : "未开放读取(按运存策略自适应)"));
    }

    private static boolean isConstrainedMemoryDevice() {
        if (sTotalCmaKb > 0 && sTotalCmaKb <= 200 * 1024) {
            return true;
        }
        return sTotalRamMb <= 2048;
    }

    private static long calculateFrameDurationUs(Format format) {
        if (format != null && format.frameRate > 0) {
            return (long) (1_000_000.0f / format.frameRate);
        }
        return 16_666L; // 默认 60fps (16.6ms) 极限场景
    }

    private static int calculateDynamicMaxInputSize(Format format, boolean isConstrainedDevice) {
        int width = (format != null && format.width != Format.NO_VALUE) ? format.width : 1920;
        int height = (format != null && format.height != Format.NO_VALUE) ? format.height : 1080;

        int baseSize;
        if (width >= 3840 || height >= 2160) {
            baseSize = isConstrainedDevice ? (2560 * 1024) : (4 * 1024 * 1024);
        } else {
            baseSize = 1024 * 1024;
        }

        if (format != null && format.maxInputSize != Format.NO_VALUE && format.maxInputSize > 0) {
            return Math.max(baseSize, format.maxInputSize);
        }
        return baseSize;
    }

    // ==================== 2. 核心方案 A：H.265 NAL 35 (AUD) 内存实时拦截剥离 ====================

    /**
     * 实时检测并切除 H.265 (HEVC) 码流中的 NAL Unit Type 35 (AUD 访问单元分隔符)
     * 彻底根除晶晨 S912 旧微码遇 NAL 35 硬件溢出崩溃（H265 dec err local reset）的致命缺陷
     */
    private static void stripH265AudNal(ByteBuffer data) {
        try {
            if (data == null || data.remaining() < 6) {
                return;
            }

            int pos = data.position();
            int limit = data.limit();

            // 识别前导起始码 (00 00 01 或 00 00 00 01)
            int startCodeLen = 0;
            if (limit - pos >= 4 && data.get(pos) == 0 && data.get(pos + 1) == 0) {
                if (data.get(pos + 2) == 1) {
                    startCodeLen = 3;
                } else if (data.get(pos + 2) == 0 && data.get(pos + 3) == 1) {
                    startCodeLen = 4;
                }
            }

            if (startCodeLen == 0) {
                return;
            }

            int nalHeaderPos = pos + startCodeLen;
            if (nalHeaderPos >= limit) {
                return;
            }

            // H.265 NAL Unit Type 位于 Header 第一个字节的第 1~6 位: (byte & 0x7E) >> 1
            byte nalByte0 = data.get(nalHeaderPos);
            int nalType = (nalByte0 & 0x7E) >> 1;

            // 命中 NAL 35 (AUD)
            if (nalType == 35) {
                // 搜索下一个 NALU 的起始码
                int nextStartCodePos = -1;
                int searchEnd = Math.min(nalHeaderPos + 32, limit - 3);
                for (int i = nalHeaderPos + 2; i <= searchEnd; i++) {
                    if (data.get(i) == 0 && data.get(i + 1) == 0) {
                        if (data.get(i + 2) == 1) {
                            nextStartCodePos = i;
                            break;
                        } else if (i + 3 < limit && data.get(i + 2) == 0 && data.get(i + 3) == 1) {
                            nextStartCodePos = i;
                            break;
                        }
                    }
                }

                if (nextStartCodePos != -1) {
                    // 原生零拷贝内存前移：直接抹掉前导 AUD，使后续的实际帧（SPS/PPS/SEI/Slice）前移至索引 0
                    data.position(nextStartCodePos);
                    data.compact();
                    int newLength = data.position();
                    data.position(0);
                    data.limit(newLength);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 3. 广播与保底看门狗 ====================

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

    private void initWatchdog() {
        mWatchdogHandler = new Handler(Looper.getMainLooper());
        mWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (mInternalPlayer != null && mInternalPlayer.getPlayWhenReady()) {
                    int playbackState = mInternalPlayer.getPlaybackState();
                    long now = SystemClock.elapsedRealtime();

                    // 缓冲期或尚未出第一帧前绝对静默
                    if (playbackState == Player.STATE_BUFFERING || mRenderedFrameCount == 0) {
                        mLastRenderedFrameTimeRealtime = now;
                        mLastPositionAdvanceTime = now;
                        return;
                    }

                    if (playbackState == Player.STATE_READY) {
                        long currentPos = getCurrentPosition();

                        if (currentPos != mLastTrackedPosition) {
                            mLastTrackedPosition = currentPos;
                            mLastPositionAdvanceTime = now;
                        }

                        boolean isAudioProgressing = (now - mLastPositionAdvanceTime) < 1500;
                        long videoStallDuration = (mLastRenderedFrameTimeRealtime > 0) ? (now - mLastRenderedFrameTimeRealtime) : 0;

                        // 门槛宽放至 8 秒真死锁，且仅原地轻柔 Flush，绝对不跳跃时间戳快进
                        if (isAudioProgressing && videoStallDuration > 8000 && currentPos > 3000) {
                            writeDebugLog(mAppContext, "[自愈哨兵] 确认画面停滞超 8 秒，原地执行平滑解冻");
                            mLastRenderedFrameTimeRealtime = now + 4000;
                            mLastPositionAdvanceTime = now + 4000;
                            mInternalPlayer.seekTo(currentPos);
                        }
                    }
                }
                if (mWatchdogHandler != null) {
                    mWatchdogHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    // ==================== 4. 播放器初始化与核心渲染装配 ====================

    @Override
    public void initPlayer() {
        registerModeReceiver();
        checkFfmpegExtensionStatus();

        boolean multiChannel = isMultiChannelModeEnabled(mAppContext);
        writeDebugLog(mAppContext, "[initPlayer] 当前音频输出模式: " + (multiChannel ? "【功放模式 5.1 直通】" : "【电视模式 2.0 下混】"));

        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
        mLastPositionAdvanceTime = SystemClock.elapsedRealtime();
        mLastTrackedPosition = -1;
        mStallRescueStep = 0;
        mIsHevcStream = false;

        // ---------- 1. 紧凑型 LoadControl 构建 ----------
        boolean constrained = isConstrainedMemoryDevice();
        int minBufferMs;
        int maxBufferMs;
        int targetBufferBytes;
        boolean prioritizeTime;

        if (constrained) {
            minBufferMs = 8_000;
            maxBufferMs = 18_000;
            targetBufferBytes = 36 * 1024 * 1024; // 36MB 严格限额
            prioritizeTime = false;
        } else {
            minBufferMs = 15_000;
            maxBufferMs = 50_000;
            targetBufferBytes = 64 * 1024 * 1024;
            prioritizeTime = true;
        }

        mLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, maxBufferMs, 1_200, 2_000)
                .setTargetBufferBytes(targetBufferBytes)
                .setPrioritizeTimeOverSizeThresholds(prioritizeTime)
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

        // ---------- 2. 视频渲染器工厂自适应装配 ----------
        mRenderersFactory = createCustomRenderersFactory(multiChannel, constrained);

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

    private RenderersFactory createCustomRenderersFactory(final boolean enableMultiChannel, final boolean isConstrainedDevice) {
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

                writeDebugLog(context, "[buildVideoRenderers] 挂载注入了 NAL 35 过滤器的硬解渲染器...");

                MediaCodecVideoRenderer videoRenderer = new MediaCodecVideoRenderer(
                        context,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        50) {

                    private long mDynamicFrameDurationUs = 16_666L;

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

                        mDynamicFrameDurationUs = calculateFrameDurationUs(format);

                        // 标识当前流是否为 HEVC
                        mIsHevcStream = "video/hevc".equalsIgnoreCase(codecMimeType)
                                || (format != null && "video/hevc".equalsIgnoreCase(format.sampleMimeType));

                        writeDebugLog(context, "[规格感知] 格式: " + codecMimeType 
                                + ", 帧率: " + (format != null ? format.frameRate : 0) 
                                + ", 单帧周期: " + (mDynamicFrameDurationUs / 1000f) + " ms, HEVC=" + mIsHevcStream);

                        int dynamicInputSize = calculateDynamicMaxInputSize(format, isConstrainedDevice);
                        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, dynamicInputSize);
                        return mediaFormat;
                    }

                    // 关键核心：在排队送入芯片硬件解码器前，将 NAL 35 (AUD) 瞬间剥离
                    @Override
                    protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
                        super.onQueueInputBuffer(buffer);
                        if (mIsHevcStream && buffer != null && buffer.data != null) {
                            stripH265AudNal(buffer.data);
                        }
                    }

                    @Override
                    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
                        return super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastBuffer);
                    }

                    @Override
                    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
                        // 允许容忍 150ms 解码缓冲延时
                        return earlyUs < -150_000;
                    }

                    @Override
                    protected void dropOutputBuffer(MediaCodecAdapter codec, int index, long presentationTimeUs) {
                        // 丢帧属于正常的追赶行为，刷新心跳防止误杀
                        mLastRenderedFrameTimeRealtime = SystemClock.elapsedRealtime();
                        super.dropOutputBuffer(codec, index, presentationTimeUs);
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

                writeDebugLog(context, "[buildAudioRenderers] 挂载音频渲染器...");

                try {
                    Class<?> clazz = Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegAudioRenderer");
                    Constructor<?> constructor = clazz.getConstructor(
                            Handler.class, AudioRendererEventListener.class, AudioSink.class);
                    Renderer ffmpegRenderer = (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
                    out.add(ffmpegRenderer);
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
            if (path.contains("thread=")) {
                path = path.replaceAll("thread=\\d+", "thread=3");
                writeDebugLog(mAppContext, "[并发优化] 锁定代理并发为 3 线程，防范网盘限速");
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
        mStallRescueStep = 0;
        mRenderedFrameCount = 0;
        mLastTrackedPosition = -1;
        long now = SystemClock.elapsedRealtime();
        mLastPositionAdvanceTime = now;
        mLastRenderedFrameTimeRealtime = now;
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
            mRenderedFrameCount = 0;
            mLastTrackedPosition = -1;
            mLastPositionAdvanceTime = 0;
            mLastRenderedFrameTimeRealtime = 0;
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
        long now = SystemClock.elapsedRealtime();
        mLastRenderedFrameTimeRealtime = now + 3000;
        mLastPositionAdvanceTime = now + 3000;
        mLastTrackedPosition = -1;
        mRenderedFrameCount = 0;
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

    public Format getVideoFormat() {
        if (mInternalPlayer != null) {
            return mInternalPlayer.getVideoFormat();
        }
        return null;
    }

    public float getRealFps() {
        if (mInternalPlayer == null || !isPlaying()) {
            return 0f;
        }
        Format format = mInternalPlayer.getVideoFormat();
        if (format != null && format.frameRate > 0) {
            return format.frameRate;
        }

        long now = SystemClock.elapsedRealtime();
        if (mLastFpsCalcTime > 0) {
            long duration = now - mLastFpsCalcTime;
            if (duration >= 800) {
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

    public void setVideoScalingMode(int videoScalingMode) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoScalingMode(videoScalingMode);
        }
    }
}
