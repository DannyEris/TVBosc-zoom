package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AudioTrackMemory;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
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

    private static final String TAG = "Tvbox-runtime";
    private IJKCode codec = null;
    protected String currentPlayPath;
    private static AudioTrackMemory memory;
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final String DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/json;q=0.9";

    // 强化版硬核自愈哨兵变量
    private android.os.Handler mIjkWatchdogHandler;
    private Runnable mIjkWatchdogRunnable;
    private long mLastActiveTime = 0;
    private long mLastCachedBytes = -1;
    private int mIjkStallStep = 0;
    private boolean mIsUserPaused = false;

    public IjkMediaPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
        memory = AudioTrackMemory.getInstance(context);
    }

    @Override
    public void start() {
        mIsUserPaused = false;
        mLastActiveTime = android.os.SystemClock.elapsedRealtime();
        mLastCachedBytes = -1;
        super.start();
    }

    @Override
    public void pause() {
        mIsUserPaused = true;
        super.pause();
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

        // 1. 解除帧率限制至 60fps
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 60);

        // 2. 晶晨 MediaCodec 硬解防死锁核心参数
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-sync", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0);

        // 3. 声卡保护：Android 标准 AudioTrack 输出
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 0);

        // 4. 水位控制：扩充显存缓冲池至 64MB，并加入再次缓冲高水位门槛
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        // 小米电视 4A 拥有 2GB 物理内存，给 18Mbps 高码流分配 64MB 原生缓冲完全安全，可存储约 28 秒画面
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 64 * 1024 * 1024);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 60);
        // 核心参数：当网络波动导致缓冲耗尽时，强制至少积攒 3 秒数据（3000ms）再恢复渲染，杜绝毫秒级反复断水
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "next-high-water-mark-ms", 3000);

        // 5. 基础网络与字幕参数
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);

        if (Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)) {
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 300);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "1");
        } else {
            // 点播高码率专属网络参数：将蓄水池上限从 15 秒大幅拉大至 30 秒
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 1024);
            // 扩充至 30 秒 (配合 64MB 缓冲)，让本地代理始终能匀速多线程蓄水，避免代理被网盘掐断
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 30000);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0);
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000); // 放宽至 15 秒超时
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "2");
        }

        initIjkWatchdog();
    }

    // 补齐右上角真实物理帧率获取接口（支持硬解元数据保底提取）
    public float getRealFps() {
        if (mMediaPlayer != null) {
            float vfps = mMediaPlayer.getVideoOutputFramesPerSecond();
            if (vfps > 0) return vfps;
            float dfps = mMediaPlayer.getVideoDecodeFramesPerSecond();
            if (dfps > 0) return dfps;
            try {
                tv.danmaku.ijk.media.player.MediaInfo mi = mMediaPlayer.getMediaInfo();
                if (mi != null && mi.mMeta != null) {
                    int fpsNum = mi.mMeta.getInt(IjkMediaMeta.IJKM_KEY_FPS_NUM);
                    int fpsDen = mi.mMeta.getInt(IjkMediaMeta.IJKM_KEY_FPS_DEN);
                    if (fpsDen > 0 && fpsNum > 0) {
                        return (float) fpsNum / fpsDen;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return 0f;
    }

    // 供控制层提取媒体元数据 (HDR/HLG 色彩信息)
    public tv.danmaku.ijk.media.player.MediaInfo getMediaInfo() {
        return mMediaPlayer != null ? mMediaPlayer.getMediaInfo() : null;
    }

    // 供控制层提取底层码率
    public long getBitRate() {
        return mMediaPlayer != null ? mMediaPlayer.getBitRate() : 0;
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
                    if (Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)) {
                        URI uri = new URI(path);
                        String host = uri.getHost();
                        if (ITV_TARGET_DOMAIN.equalsIgnoreCase(host)) {
                            path = ControlManager.get().getAddress(true) + "proxy?go=live&type=m3u8&url=" + URLEncoder.encode(path, "UTF-8");
                        }
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

    private static final int RTSP_UDP_RTP = 1;
    private static final int CACHE_VIDEO = 2;
    private static final int M3U8 = 3;
    private static final int OTHER = 0;

    private int getStreamType(String path) {
        if (TextUtils.isEmpty(path)) return OTHER;
        String lowerPath = path.toLowerCase();
        if (lowerPath.startsWith("rtsp://") || lowerPath.startsWith("udp://") || lowerPath.startsWith("rtp://")) {
            return RTSP_UDP_RTP;
        }
        String cleanUrl = path.split("\\?")[0];
        if (cleanUrl.endsWith(".m3u8")) return M3U8;
        if (cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".avi")) return CACHE_VIDEO;
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
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) continue;
                if (ExoMediaSourceHelper.HEADER_FORMAT.equalsIgnoreCase(key)) continue;
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    if ("Accept".equalsIgnoreCase(key)) hasAccept = true;
                    playHeaders.put(key, value.trim());
                }
            }
        }
        if (TextUtils.isEmpty(userAgent)) userAgent = DEFAULT_USER_AGENT;
        if (!hasAccept) playHeaders.put("Accept", DEFAULT_ACCEPT);
        if (!TextUtils.isEmpty(userAgent)) {
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
        }
        if (playHeaders.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : playHeaders.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
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
            } else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                TrackInfoBean a = new TrackInfoBean();
                String name = processAudioName(info.getInfoInline());
                a.language = info.getLanguage();
                if (name.startsWith("aac")) a.language = "中文";
                a.name = name;
                String language = getFriendlyLanguage(a.language, info.getInfoInline());
                a.language = language;
                a.name = buildDisplayName("音轨", data.getAudio().size() + 1, language, name);
                a.trackId = index;
                a.index = index;
                a.selected = index == audioSelected;
                data.addAudio(a);
            } else if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                if (!isTextSubtitle(info.getInfoInline())) {
                    index++;
                    continue;
                }
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                String language = getFriendlyLanguage(t.language, t.name);
                t.language = language;
                t.name = buildDisplayName("字幕", data.getSubtitle().size() + 1, language, "");
                t.trackId = index;
                t.index = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }

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
        if (text.contains("yue") || text.contains("cantonese") || text.contains("粤") || text.contains("广东")) return "粤语";
        if (text.contains("zh") || text.contains("chi") || text.contains("zho") || text.contains("chs")
                || text.contains("cht") || text.contains("cmn") || text.contains("中")
                || text.contains("国语") || text.contains("普通话")) return "国语";
        if (text.contains("en") || text.contains("eng") || text.contains("english") || text.contains("英")) return "英语";
        if (text.contains("ja") || text.contains("jpn") || text.contains("japanese") || text.contains("日")) return "日语";
        if (text.contains("ko") || text.contains("kor") || text.contains("korean") || text.contains("韩")) return "韩语";
        if (text.contains("tha") || text.contains("thai") || text.contains("th")) return "泰语";
        return "";
    }

    private String buildDisplayName(String prefix, int number, String language, String detail) {
        StringBuilder builder = new StringBuilder(prefix).append(number);
        if (language != null && !language.isEmpty()) builder.append(" - ").append(language);
        if (detail != null && !detail.isEmpty()) builder.append(" ").append(detail);
        return builder.toString();
    }

    public void setTrack(int trackIndex) {
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        if (trackIndex != audioSelected && trackIndex != subtitleSelected) {
            mMediaPlayer.selectTrack(trackIndex);
        }
    }

    public void setTrack(int trackIndex, String playKey) {
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        if (trackIndex != audioSelected) {
            if (!playKey.isEmpty()) {
                memory.save(playKey, trackIndex);
            }
            mMediaPlayer.selectTrack(trackIndex);
        }
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

    public void loadDefaultTrack(TrackInfo trackInfo, String playKey) {
        if (trackInfo != null && trackInfo.getAudio().size() > 1) {
            Integer trackIndex = memory.ijkLoad(playKey);
            if (trackIndex == -1) {
                int firsIndex = trackInfo.getAudio().get(0).index;
                setTrack(firsIndex);
                return;
            }
            setTrack(trackIndex);
        }
    }

    // ================= 终极哨兵：基于硬件消费与网络吞吐的双重判定 =================
    private void initIjkWatchdog() {
        if (mIjkWatchdogHandler == null) {
            mIjkWatchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        } else {
            mIjkWatchdogHandler.removeCallbacksAndMessages(null);
        }
        mLastActiveTime = android.os.SystemClock.elapsedRealtime();
        mLastCachedBytes = -1;
        mIjkStallStep = 0;

        mIjkWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (mMediaPlayer != null && !mIsUserPaused) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        long currentPos = getCurrentPosition();
                        long tcpSpeed = getTcpSpeed();
                        long cachedBytes = mMediaPlayer.getVideoCachedBytes();

                        // 正常播放特征：网络有吞吐数据，或者底层显存队列有进出消耗
                        boolean isActivelyDecoding = (tcpSpeed > 0) || (mLastCachedBytes != -1 && cachedBytes != mLastCachedBytes);

                        if (isActivelyDecoding) {
                            mLastActiveTime = now;
                            mLastCachedBytes = cachedBytes;
                            mIjkStallStep = 0;
                        } else if (currentPos > 3000) {
                            // 核心触发：当网络吞吐彻底归零，且显存数据连续 1800ms 纹丝不动（解码器死锁拒收）
                            if (mLastActiveTime > 0 && (now - mLastActiveTime > 1800)) {
                                long duration = getDuration();
                                if (mIjkStallStep == 0) {
                                    Log.e(TAG, "[IJK自愈哨兵] 捕获硬件锁死(200003f)与网络断流！执行【原地 Flush】重置硬件！当前点: " + currentPos);
                                    mIjkStallStep = 1;
                                    mLastActiveTime = now - 600;
                                    seekTo(currentPos); // 强制底层下发 flush 清空错误状态
                                } else {
                                    long targetPos = (duration > 0) ? Math.min(currentPos + 3500, duration) : currentPos + 3500;
                                    Log.e(TAG, "[IJK自愈哨兵] 原地 Flush 未脱困，执行【快进 3.5 秒跳过坏块】！目标: " + targetPos);
                                    mIjkStallStep = 0;
                                    mLastActiveTime = now + 2500;
                                    seekTo(targetPos);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (mIjkWatchdogHandler != null) {
                    mIjkWatchdogHandler.postDelayed(this, 400); // 400ms 高频扫描
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
