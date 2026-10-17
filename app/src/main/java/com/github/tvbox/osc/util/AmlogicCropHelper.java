package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileOutputStream;

public class AmlogicCropHelper {
    private static final String CROP_PATH = "/sys/class/video/crop";
    private static Boolean sIsSupported = null;
    private static int sWriteMode = 0; // 1: FileOutputStream, 2: su命令直接写

    /**
     * 智能提权与可写性探测
     */
    public static boolean isSupported() {
        if (sIsSupported == null) {
            File file = new File(CROP_PATH);
            if (!file.exists()) {
                sIsSupported = false;
                return false;
            }

            // 1. 若当前可写，优先使用高效的文件流直写
            if (tryDirectWrite()) {
                sWriteMode = 1;
                sIsSupported = true;
                return true;
            }

            // 2. 若无写入权限，尝试借助系统 su 将节点一次性提权为 666
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 " + CROP_PATH});
                p.waitFor();
                // 提权后再测一次直接写入
                if (tryDirectWrite()) {
                    sWriteMode = 1;
                    sIsSupported = true;
                    return true;
                }
            } catch (Throwable ignored) {}

            // 3. 若 chmod 受限，尝试通过 su 管道直接写入测试
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
     * 写入硬件裁剪参数 (top left bottom right)
     */
    public static void setCrop(final int top, final int left, final int bottom, final int right) {
        if (!isSupported()) return;
        final String cmd = top + " " + left + " " + bottom + " " + right;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 模式 1：高能效零延迟直写（chmod 666 成功后走此通道）
                    if (sWriteMode == 1) {
                        File file = new File(CROP_PATH);
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            fos.write(cmd.getBytes());
                            fos.flush();
                            return;
                        } catch (Throwable th) {
                            sWriteMode = 2; // 异常时回退到 su 管道
                        }
                    }

                    // 模式 2：su 命令行兜底通道
                    if (sWriteMode == 2) {
                        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo " + cmd + " > " + CROP_PATH});
                        p.waitFor();
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /**
     * 清除硬件裁剪，恢复原始全幅
     */
    public static void resetCrop() {
        if (!isSupported()) return;
        setCrop(0, 0, 0, 0);
    }
}
