package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileOutputStream;

private static volatile boolean sHasOptimized = false;

    /**
     * 针对 Amlogic 高刷4K片源的底层总线与功耗优化 (仅在 Root 环境执行一次)
     */
    public static void optimizePerformance() {
        if (sHasOptimized || !isSupported()) return;
        sHasOptimized = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 拼接优化指令：旁路去隔行、关闭重负荷滤镜、开启音画同步保护、限制 CPU 虚高发热
                    String[] cmds = new String[]{
                        "echo 1 > /sys/module/di/parameters/bypass_all",
                        "echo 0 > /sys/class/amvecm/dnlp",
                        "echo 0 > /sys/class/amvecm/pic_play",
                        "echo 1 > /sys/class/tsync/enable",
                        "echo ondemand > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
                        "echo 1000000 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
                    };

                    StringBuilder sb = new StringBuilder();
                    for (String c : cmds) {
                        sb.append(c).append("\n");
                    }

                    Process p = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream dos = new java.io.DataOutputStream(p.getOutputStream());
                    dos.writeBytes(sb.toString());
                    dos.writeBytes("exit\n");
                    dos.flush();
                    p.waitFor();
                } catch (Throwable ignored) {}
            }
        }).start();
    }

public class AmlogicCropHelper {
    private static final String CROP_PATH = "/sys/class/video/crop";
    private static Boolean sIsSupported = null;
    private static int sWriteMode = 0;
    private static volatile String sLastCropCmd = "";

    public static boolean isSupported() {
        if (sIsSupported == null) {
            File file = new File(CROP_PATH);
            if (!file.exists()) {
                sIsSupported = false;
                return false;
            }

            if (tryDirectWrite()) {
                sWriteMode = 1;
                sIsSupported = true;
                return true;
            }

            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 " + CROP_PATH});
                p.waitFor();
                if (tryDirectWrite()) {
                    sWriteMode = 1;
                    sIsSupported = true;
                    return true;
                }
            } catch (Throwable ignored) {}

            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo 0 0 0 0 > " + CROP_PATH});
                if (p.waitFor() == 0) {
                    sWriteMode = 2;
                    sIsSupported = true;
                    return true;
                }
            } catch (Throwable ignored) {}

            sIsSupported = false;
        }
        return sIsSupported;
    }

    private static boolean tryDirectWrite() {
        File file = new File(CROP_PATH);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 写入硬件裁剪参数 (强制进行 2 像素偶数对齐，杜绝 DMA 色度抽样寻址卡顿)
     */
    public static void setCrop(int top, int left, int bottom, int right) {
        if (!isSupported()) return;

        top = (top / 2) * 2;
        left = (left / 2) * 2;
        bottom = (bottom / 2) * 2;
        right = (right / 2) * 2;

        final String cmd = top + " " + left + " " + bottom + " " + right;
        if (cmd.equals(sLastCropCmd)) return; // 指令未变直接拦截，防止高频读写触发 VPP 死锁
        sLastCropCmd = cmd;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sWriteMode == 1) {
                        File file = new File(CROP_PATH);
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            fos.write(cmd.getBytes());
                            fos.flush();
                            return;
                        } catch (Throwable th) {
                            sWriteMode = 2;
                        }
                    }

                    if (sWriteMode == 2) {
                        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo " + cmd + " > " + CROP_PATH});
                        p.waitFor();
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    public static void resetCrop() {
        if (sIsSupported == null || !sIsSupported) return;
        sLastCropCmd = "";
        setCrop(0, 0, 0, 0);
    }
}
