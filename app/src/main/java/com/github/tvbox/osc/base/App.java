package com.github.tvbox.osc.base;

import android.app.Activity;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
    }

    private void initParams() {
        // Hawk 键值存储初始化
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);

        // 默认播放器: ExoPlayer (2)
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        } else if (Hawk.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        }

        // ==================== 定制默认设置（首次安装开箱即用）====================
        // 1. 默认配置地址
        if (!Hawk.contains(HawkConfig.API_URL) || Hawk.get(HawkConfig.API_URL, "").isEmpty()) {
            Hawk.put(HawkConfig.API_URL, "http://new.王二小放牛娃.top");
        }

        // 2. 默认首页多行：是 (true)
        if (!Hawk.contains(HawkConfig.HOME_REC_STYLE)) {
            Hawk.put(HawkConfig.HOME_REC_STYLE, true);
        }

        // 3. 默认弹幕：关闭 (false)
        if (!Hawk.contains(HawkConfig.DANMU_OPEN)) {
            Hawk.put(HawkConfig.DANMU_OPEN, false);
        }

        // 4. 默认渲染方式：SurfaceView (1)，带一次性纠偏逻辑
        // 仅在首次运行本版本时将默认值纠正为 1 (SurfaceView)，之后完全尊重用户手动切换的设置
        if (!Hawk.get("init_render_v2", false)) {
            Hawk.put(HawkConfig.PLAY_RENDER, 1);      // 设为 SurfaceView (1)
            Hawk.put("init_render_v2", true);         // 写入完成标记，以后启动绝不再次覆盖
        }

        // 5. 默认图片尺寸：大图 (0 - 5列)
        if (!Hawk.contains(HawkConfig.POSTER_SIZE)) {
            Hawk.put(HawkConfig.POSTER_SIZE, 0);
        }
        // ==================== 预置常用配置历史列表 ====================
        if (!Hawk.get("init_history_v1", false)) {
            ArrayList<String> defaultHistory = new ArrayList<>();
            defaultHistory.add("http://new.王二小放牛娃.top");
            defaultHistory.add("http://new.999888987.xyz");
            defaultHistory.add("https://9280.kstore.vip/newwex.json");
            defaultHistory.add("http://www.饭太硬.cc/tv");
            defaultHistory.add("http://fty.xxooo.cf/tv");
            defaultHistory.add("http://www.影视仓.com");
            defaultHistory.add("https://700sjro44343.vicp.fun/240731/one/");
            defaultHistory.add("http://肥猫.net/tv");
            defaultHistory.add("http://hello.肥猫.net");
            defaultHistory.add("http://枫斗.top/tv");

            // 读取已有的历史列表并合并去重，防止冲掉用户自己曾经输入的
            ArrayList<String> existingHistory = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
            for (String url : existingHistory) {
                if (!defaultHistory.contains(url)) {
                    defaultHistory.add(url);
                }
            }

            Hawk.put(HawkConfig.API_HISTORY, defaultHistory);
            Hawk.put("init_history_v1", true); // 标记已初始化，避免后续启动覆盖用户自己的删除
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }

    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
