package xyz.doikki.videoplayer.render;

import android.view.View;
import xyz.doikki.videoplayer.player.VideoView;

public class MeasureHelper {

    private int mVideoWidth;
    private int mVideoHeight;
    private int mCurrentScreenScale;
    private int mVideoRotationDegree;
    private float mVideoZoom = 1.0f;
    private boolean mHardwareCropMode = false;

    public void setVideoRotation(int videoRotationDegree) {
        mVideoRotationDegree = videoRotationDegree;
    }

    public void setVideoSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
    }

    public void setScreenScale(int screenScale) {
        mCurrentScreenScale = screenScale;
    }

    public void setVideoZoom(float zoom) {
        mVideoZoom = zoom;
    }

    public void setHardwareCropMode(boolean hardwareCropMode) {
        mHardwareCropMode = hardwareCropMode;
    }

    public int[] doMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mVideoRotationDegree == 90 || mVideoRotationDegree == 270) {
            widthMeasureSpec = widthMeasureSpec + heightMeasureSpec;
            heightMeasureSpec = widthMeasureSpec - heightMeasureSpec;
            widthMeasureSpec = widthMeasureSpec - heightMeasureSpec;
        }

        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = View.MeasureSpec.getSize(heightMeasureSpec);

        if (mVideoHeight == 0 || mVideoWidth == 0) {
            return new int[]{width, height};
        }

        switch (mCurrentScreenScale) {
            case VideoView.SCREEN_SCALE_DEFAULT:
            default:
                if (mVideoWidth * height < width * mVideoHeight) {
                    width = height * mVideoWidth / mVideoHeight;
                } else if (mVideoWidth * height > width * mVideoHeight) {
                    height = width * mVideoHeight / mVideoWidth;
                }

                // 核心修复：支持双向缩放，并区分硬件芯片裁切与视口外扩
                if (mVideoZoom > 0 && Math.abs(mVideoZoom - 1.0f) > 0.01f) {
                    if (mHardwareCropMode) {
                        // 通路 1（Tx9 Pro 等具备芯片寄存器控制权的设备）：
                        // 视口单向拓展填满黑边，由 VPP 硬件寄存器执行裁切
                        float videoRatio = (float) mVideoWidth / (float) mVideoHeight;
                        float containerRatio = (float) View.MeasureSpec.getSize(widthMeasureSpec) / (float) View.MeasureSpec.getSize(heightMeasureSpec);
                        if (videoRatio > containerRatio) {
                            int maxH = View.MeasureSpec.getSize(heightMeasureSpec);
                            height = Math.min((int) Math.round(height * mVideoZoom), maxH);
                        } else {
                            int maxW = View.MeasureSpec.getSize(widthMeasureSpec);
                            width = Math.min((int) Math.round(width * mVideoZoom), maxW);
                        }
                    } else {
                        // 通路 2（天猫魔盒 M19 等无 Root 节点设备，完美还原 827f78a 机制）：
                        // 宽高严格同步以 zoom 缩放，保持原本 2.39:1 等比，支持放大与缩小
                        width = Math.round(width * mVideoZoom);
                        height = Math.round(height * mVideoZoom);
                    }
                }
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                width = mVideoWidth;
                height = mVideoHeight;
                break;
            case VideoView.SCREEN_SCALE_16_9:
                if (height > width * 9 / 16) {
                    height = width * 9 / 16;
                } else {
                    width = height * 16 / 9;
                }
                break;
            case VideoView.SCREEN_SCALE_4_3:
                if (height > width * 3 / 4) {
                    height = width * 3 / 4;
                } else {
                    width = height * 4 / 3;
                }
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                width = View.MeasureSpec.getSize(widthMeasureSpec);
                height = View.MeasureSpec.getSize(heightMeasureSpec);
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                if (mVideoWidth * height > width * mVideoHeight) {
                    width = height * mVideoWidth / mVideoHeight;
                } else {
                    height = width * mVideoHeight / mVideoWidth;
                }
                break;
        }
        return new int[]{width, height};
    }
}
