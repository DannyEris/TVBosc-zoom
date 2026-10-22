package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AudioTrackMemory;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.misc.IMediaFormat;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;
import xyz.doikki.videoplayer.ijk.IjkPlayer;

public class IjkMediaPlayer extends IjkPlayer {

    private IJKCode codec = null;
    protected String currentPlayPath;
    private static AudioTrackMemory memory;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/json;q=0.9";

    // IJK 硬件防死锁自愈哨兵变量
    private android.os.Handler mIjkWatchdogHandler;
    private Runnable mIjkWatchdogRunnable;
    private long mLastIjkPos = 0;
    private long mLastIjkAdvanceTime = 0;
    private int mIjkStallStep = 0;
    private boolean mIsUserPaused = false; // 区分用户主动暂停与异常假死

    @Override
    public void start() {
        mIsUserPaused = false;
        mLastIjkAdvanceTime = android.os.SystemClock.elapsedRealtime(); // 唤醒时重置基准时钟，防止暂停恢复后误触发自愈
        mIjkStallStep = 0;
        super.start();
    }

    @Override
    public void pause() {
        mIsUserPaused = true;
        super.pause();
    }

    public IjkMediaPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
        memory = AudioTrackMemory.getInstance(context);
    }

    @Override
    public void setOptions() {
        super.setOptions();
        IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
        LinkedHashMap<String, String> options = codecTmp.getOption();
        if (options != null) {
            for (String key : options.keySet()) {
                String value = options.get(key);
                String[] opt = key.split("\\|");
                int category = Integer.parseInt(opt[0].trim());
                String name = opt[1].trim();
                try {
                    assert value != null;
                    long valLong = Long.parseLong(value);
                    mMediaPlayer.setOption(category, name, valLong);
                } catch (Exception e) {
                    mMediaPlayer.setOption(category, name, value);
                }
            }
        }

        // ================= 核心修复：解除 30fps 封印，释放 60fps 满血帧率 =================
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 60);

        // ================= 晶晨 S912/S912H/小米电视 4A 专属硬件对齐参数 =================
        // 1. 规避晶晨芯片时间戳死锁（核心：禁用 IJK 内部的 MediaCodec 同步封装，由音频主时钟弹性追赶）
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-sync", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        
        // 2. 启用平滑跳显：画面落后音频超过阈值时轻度跳帧，死死咬合音画同步
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5);

        // 3. 声卡保护：强制使用 Android 标准 AudioTrack 输出，禁用 OpenSL ES (防止天猫魔盒 M19 无声或报错)
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 0);

        // 4. 内存保护：限制缓冲队列最大占用为 40MB，杜绝 2GB 内存设备(M19/小米4A)发生 OOM 与频繁 GC 顿挫
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 40 * 1024 * 1024);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);

        // 开启内置字幕与网络基础优化
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);

        if (Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)) {
            LOG.i("echo-type-直播");
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 300);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 1);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "1");
        } else {
            LOG.i("echo-type-点播");
            // 针对百度网盘等高码率流：水池扩充至 15 秒 (约 33MB)，并设置 10 秒超时重连，彻底消除 3ms 假复苏断流
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 1024);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 15000);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 10000000); // 10秒超时重试
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "2");
        }

        // 启动/重置自愈哨兵
        initIjkWatchdog();
    }

    private static final String ITV_TARGET_DOMAIN = "gslbserv.itv.cmvideo.cn";
    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            switch (getStreamType(path)) {
                case RTSP_UDP_RTP:
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp");
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1000);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2 * 1000 * 1000);
                    break;

                case CACHE_VIDEO:
                    if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) {
                        String cachePath = FileUtils.getCachePath() + "/ijkcaches/";
                        File cacheFile = new File(cachePath);
                        if (!cacheFile.exists()) cacheFile.mkdirs();
                        String tmpMd5 = MD5.string2MD5(path);
                        String cacheFilePath = cachePath + tmpMd5 + ".file";
                        String cacheMapPath = cachePath + tmpMd5 + ".map";

                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cacheFilePath);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cacheMapPath);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", 60 * 1024 * 1024);
                        path = "ijkio:cache:ffio:" + path;
                    }
                    break;

                case M3U8:
                    // 直播且是ijk的时候自动自动走代理解决DNS
                    if (Hawk.get(HawkConfig.PLAYER_IS_LIVE, false) ) {
                        URI uri = new URI(path);
                        String host = uri.getHost();
                        if(ITV_TARGET_DOMAIN.equalsIgnoreCase(host))path = ControlManager.get().getAddress(true) + "proxy?go=live&type=m3u8&url="+ URLEncoder.encode(path,"UTF-8");
                    }
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        setDataSourceHeader(headers);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        currentPlayPath = path;
        super.setDataSource(path, null);
    }

    /**
     * 解析 URL
     */
    private static final int RTSP_UDP_RTP = 1;
    private static final int CACHE_VIDEO = 2;
    private static final int M3U8 = 3;
    private static final int OTHER = 0;

    private int getStreamType(String path) {
        if (TextUtils.isEmpty(path)) {
            return OTHER;
        }
        // 低成本检查 RTSP/UDP/RTP 类型
        String lowerPath = path.toLowerCase();
        if (lowerPath.startsWith("rtsp://") || lowerPath.startsWith("udp://") || lowerPath.startsWith("rtp://")) {
            return RTSP_UDP_RTP;
        }
        String cleanUrl = path.split("\\?")[0];
        if (cleanUrl.endsWith(".m3u8")) {
            return M3U8;
        }
        if (cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".avi")) {
            return CACHE_VIDEO;
        }
        return OTHER;
    }

    private void setDataSourceHeader(Map<String, String> headers) {
        LinkedHashMap<String, String> playHeaders = new LinkedHashMap<>();
        String userAgent = null;
        boolean hasAccept = false;
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (ExoMediaSourceHelper.HEADER_FORMAT.equalsIgnoreCase(key)) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    if ("Accept".equalsIgnoreCase(key)) {
                        hasAccept = true;
                    }
                    playHeaders.put(key, value.trim());
                }
            }
        }
        if (TextUtils.isEmpty(userAgent)) {
            userAgent = DEFAULT_USER_AGENT;
        }
        if (!hasAccept) {
            playHeaders.put("Accept", DEFAULT_ACCEPT);
        }
        if (!TextUtils.isEmpty(userAgent)) {
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
        }
        if (playHeaders.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : playHeaders.entrySet()) {
                sb.append(entry.getKey());
                sb.append(": ");
                sb.append(entry.getValue());
                sb.append("\r\n");
            }
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
        }
    }

    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int videoSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                if (isAttachedPicture(info)) {
                    LOG.i("echo-ijk-skip-attached-picture:" + info.getInfoInline());
                    index++;
                    continue;
                }
                TrackInfoBean v = new TrackInfoBean();
                String name = processVideoName(info.getInfoInline());
                String language = getFriendlyLanguage(info.getLanguage(), info.getInfoInline());
                v.language = language;
                v.name = buildDisplayName("视轨", data.getVideo().size() + 1, language, name);
                v.trackId = index;
                v.index = index;
                v.selected = index == videoSelected;
                data.addVideo(v);
            }
            else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {//音轨信息
                TrackInfoBean a = new TrackInfoBean();
                String name = processAudioName(info.getInfoInline());
                a.language = info.getLanguage();
                if(name.startsWith("aac"))a.language="中文";
                a.name = name;
                String language = getFriendlyLanguage(a.language, info.getInfoInline());
                a.language = language;
                a.name = buildDisplayName("\u97f3\u8f68", data.getAudio().size() + 1, language, name);
                a.trackId = index;
                a.index = index;
                a.selected = index == audioSelected;
                // 如果需要，还可以检查轨道的描述或标题以获取更多信息
                data.addAudio(a);
            }
            else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {//内置字幕
                if (!isTextSubtitle(info.getInfoInline())) {
                    LOG.i("echo-ijk-skip-bitmap-subtitle:" + info.getInfoInline());
                    index++;
                    continue;
                }
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                String language = getFriendlyLanguage(t.language, t.name);
                t.language = language;
                t.name = buildDisplayName("\u5b57\u5e55", data.getSubtitle().size() + 1, language, "");
                t.trackId = index;
                t.index = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }
    // 处理音轨名称格式
    private String processAudioName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace("AUDIO,", "")
                .replace("N/A,", "")
                .replace(" ", "")
                .replaceAll("^,+|,+$", "")
                .replace(",", " / ");
    }

    private String processVideoName(String rawName) {
        if (rawName == null) return "";
        return rawName.replace("VIDEO,", "")
                .replace("N/A,", "")
                .replace(" ", "")
                .replaceAll("^,+|,+$", "")
                .replace(",", " / ");
    }

    private boolean isAttachedPicture(IjkTrackInfo info) {
        IMediaFormat format = info.getFormat();
        if (format == null) return false;
        String codecName = format.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
        return "mjpeg".equalsIgnoreCase(codecName)
                && format.getInteger(IjkMediaMeta.IJKM_KEY_BITRATE) <= 0
                && format.getInteger(IjkMediaMeta.IJKM_KEY_FPS_NUM) <= 0;
    }

    private boolean isTextSubtitle(String rawName) {
        String value = rawName == null ? "" : rawName.toLowerCase();
        return !value.contains("pgs")
                && !value.contains("hdmv")
                && !value.contains("dvd subtitle")
                && !value.contains("dvd_subtitle")
                && !value.contains("dvb subtitle")
                && !value.contains("dvb_subtitle")
                && !value.contains("xsub")
                && !value.contains("vobsub")
                && !value.contains("bitmap");
    }

    private String getFriendlyLanguage(String language, String rawInfo) {
        String text = ((language == null ? "" : language) + " " + (rawInfo == null ? "" : rawInfo)).toLowerCase();
        if (text.contains("yue") || text.contains("cantonese") || text.contains("\u7ca4") || text.contains("\u5e7f\u4e1c")) {
            return "\u7ca4\u8bed";
        }
        if (text.contains("zh") || text.contains("chi") || text.contains("zho") || text.contains("chs")
                || text.contains("cht") || text.contains("cmn") || text.contains("\u4e2d")
                || text.contains("\u56fd\u8bed") || text.contains("\u666e\u901a\u8bdd")) {
            return "\u56fd\u8bed";
        }
        if (text.contains("en") || text.contains("eng") || text.contains("english") || text.contains("\u82f1")) {
            return "\u82f1\u8bed";
        }
        if (text.contains("ja") || text.contains("jpn") || text.contains("japanese") || text.contains("\u65e5")) {
            return "\u65e5\u8bed";
        }
        if (text.contains("ko") || text.contains("kor") || text.contains("korean") || text.contains("\u97e9")) {
            return "\u97e9\u8bed";
        }
        if (text.contains("tha") || text.contains("thai") || text.contains("th")) {
            return "\u6cf0\u8bed";
        }
        return "";
    }

    private String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(number);
        if (language != null && !language.isEmpty()) {
            builder.append(" - ").append(language);
        }
        if (detail != null && !detail.isEmpty()) {
            builder.append(" ").append(detail);
        }
        return builder.toString();
    }

    public void setTrack(int trackIndex) {
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        if (trackIndex!=audioSelected && trackIndex!=subtitleSelected){
            mMediaPlayer.selectTrack(trackIndex);
        }
    }
    public void setTrack(int trackIndex,String playKey) {
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        if (trackIndex!=audioSelected){
            if (!playKey.isEmpty()) {
                memory.save(playKey, trackIndex);
            }
            mMediaPlayer.selectTrack(trackIndex);
        }
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

    public void loadDefaultTrack(TrackInfo trackInfo,String playKey) {
        if(trackInfo!=null && trackInfo.getAudio().size()>1){
            Integer trackIndex = memory.ijkLoad(playKey);
            if (trackIndex == -1) {
                int firsIndex=trackInfo.getAudio().get(0).index;
                setTrack(firsIndex);
                return;
            };
            setTrack(trackIndex);
        }
    }
    // ================= 基于物理送显帧率的硬核自愈哨兵 =================
    private void initIjkWatchdog() {
        if (mIjkWatchdogHandler == null) {
            mIjkWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        } else {
            mIjkWatchdogHandler.removeCallbacksAndMessages(null);
        }
        mLastIjkAdvanceTime = android.os.SystemClock.elapsedRealtime();
        mIjkStallStep = 0;

        mIjkWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // 只要用户没有主动点暂停，就属于活跃播放受检状态
                    if (mMediaPlayer != null && !mIsUserPaused) {
                        long currentPos = getCurrentPosition();
                        long now = android.os.SystemClock.elapsedRealtime();

                        // 读取底层物理真实输出帧率 (核心：绕过虚拟音频时钟递增陷阱)
                        float fps = mMediaPlayer.getVideoOutputFramesPerSecond();

                        // 正常播放时 4K 60fps 真实帧率通常 > 20fps；如果 fps >= 1.0f 判定为正在健康出帧
                        if (fps >= 1.0f) {
                            mLastIjkAdvanceTime = now;
                            mIjkStallStep = 0;
                        } else if (currentPos > 3000) {
                            // 物理出帧率归零持续超过 1500ms，无视虚拟进度走动，坚决判定为定格卡死！
                            if (mLastIjkAdvanceTime > 0 && (now - mLastIjkAdvanceTime > 1500)) {
                                long duration = getDuration();
                                if (mIjkStallStep == 0) {
                                    LOG.i("[IJK自愈哨兵] 物理出帧中断(FPS=0)，自动执行【原地 Flush 重连】！当前点: " + currentPos);
                                    mIjkStallStep = 1;
                                    mLastIjkAdvanceTime = now - 600;
                                    seekTo(currentPos); // 等同于手动快进动作，强制重建 Socket 与清空解码器
                                } else {
                                    long targetPos = (duration > 0) ? Math.min(currentPos + 3000, duration) : currentPos + 3000;
                                    LOG.i("[IJK自愈哨兵] 原地重连未果，执行【轻度跨帧 3 秒】脱困！目标: " + targetPos);
                                    mIjkStallStep = 0;
                                    mLastIjkAdvanceTime = now + 2500;
                                    seekTo(targetPos);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (mIjkWatchdogHandler != null) {
                    mIjkWatchdogHandler.postDelayed(this, 400); // 400ms 高频巡检
                }
            }
        };
        mIjkWatchdogHandler.postDelayed(mIjkWatchdogRunnable, 2000);
    }

    @Override
    public void release() {
        if (mIjkWatchdogHandler != null) {
            mIjkWatchdogHandler.removeCallbacksAndMessages(null);
            mIjkWatchdogHandler = null;
        }
        super.release();
    }

    @Override
    public void reset() {
        if (mIjkWatchdogHandler != null) {
            mIjkWatchdogHandler.removeCallbacksAndMessages(null);
        }
        super.reset();
    }
}
