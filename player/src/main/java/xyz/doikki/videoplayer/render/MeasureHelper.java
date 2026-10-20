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

                if (mVideoZoom > 0 && Math.abs(mVideoZoom - 1.0f) > 0.01f) {
                    if (mVideoZoom < 1.0f) {
                        // 缩小模式（通用）：宽高完全等比缩减，四面留黑，彻底解决上下压缩变扁
                        width = Math.round(width * mVideoZoom);
                        height = Math.round(height * mVideoZoom);
                    } else {
                        // 放大模式 (zoom > 1.0f)
                        if (mHardwareCropMode) {
                            // Tx9 Pro：视口单向拓展填满黑边，由 VPP 芯片硬件裁剪两侧
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
                            // 天猫魔盒 M19：宽高同步以 zoom 放大，走居中视口裁切
                            width = Math.round(width * mVideoZoom);
                            height = Math.round(height * mVideoZoom);
                        }
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
                if (mHardwareCropMode) {
                    // 核心修复：具备芯片硬件裁剪时，视口死锁在 1920x1080 满屏安全区！
                    // 绝不允许外扩产生 -331 负坐标，彻底杜绝双重裁剪引发的 VPU 缓冲区溢出死机
                    width = View.MeasureSpec.getSize(widthMeasureSpec);
                    height = View.MeasureSpec.getSize(heightMeasureSpec);
                } else {
                    if (mVideoWidth * height > width * mVideoHeight) {
                        width = height * mVideoWidth / mVideoHeight;
                    } else {
                        height = width * mVideoHeight / mVideoWidth;
                    }
                }
                break;
        }
        return new int[]{width, height};
    }
}
