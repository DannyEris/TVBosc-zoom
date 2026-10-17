package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileOutputStream;

public class AmlogicCropHelper {
    private static final String CROP_PATH = "/sys/class/video/crop";
    private static Boolean sCanWriteCrop = null;

    /**
     * 动态探测当前系统是否具备向晶晨硬件裁切节点写入的权限
     */
    public static boolean isSupported() {
        if (sCanWriteCrop == null) {
            File file = new File(CROP_PATH);
            if (!file.exists()) {
                sCanWriteCrop = false;
            } else if (file.canWrite()) {
                sCanWriteCrop = true;
            } else {
                // 部分系统 canWrite 判定不准，通过安全试探写判定真实权限
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    sCanWriteCrop = true;
                } catch (Throwable th) {
                    sCanWriteCrop = false;
                }
            }
        }
        return sCanWriteCrop;
    }

    /**
     * 写入硬件裁剪参数 (top left bottom right)
     */
    public static void setCrop(final int top, final int left, final int bottom, final int right) {
        if (!isSupported()) return;
        final String cmd = top + " " + left + " " + bottom + " " + right;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File file = new File(CROP_PATH);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(cmd.getBytes());
                    fos.flush();
                    return;
                } catch (Throwable ignored) {}

                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "echo " + cmd + " > " + CROP_PATH});
                    p.waitFor();
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /**
     * 清除硬件裁剪，恢复原始比例
     */
    public static void resetCrop() {
        if (!isSupported()) return;
        setCrop(0, 0, 0, 0);
    }
}
