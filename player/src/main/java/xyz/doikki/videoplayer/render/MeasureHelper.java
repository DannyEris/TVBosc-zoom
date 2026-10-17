package xyz.doikki.videoplayer.render;

import android.view.View;
import xyz.doikki.videoplayer.player.VideoView;

public class MeasureHelper {

    private int mVideoWidth;
    private int mVideoHeight;
    private int mCurrentScreenScale;
    private int mVideoRotationDegree;
    private float mVideoZoom = 1.0f;

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
        if (zoom <= 0.1f) zoom = 1.0f;
        mVideoZoom = zoom;
    }

    public float getVideoZoom() {
        return mVideoZoom;
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
                int containerWidth = View.MeasureSpec.getSize(widthMeasureSpec);
                int containerHeight = View.MeasureSpec.getSize(heightMeasureSpec);
                float videoRatio = (float) mVideoWidth / (float) mVideoHeight;
                float containerRatio = (float) containerWidth / (float) containerHeight;

                if (Math.abs(mVideoZoom - 1.0f) < 0.01f) {
                    // 标准 1.0x 默认自适应黑边排版
                    if (mVideoWidth * height < width * mVideoHeight) {
                        width = height * mVideoWidth / mVideoHeight;
                    } else if (mVideoWidth * height > width * mVideoHeight) {
                        height = width * mVideoHeight / mVideoWidth;
                    }
                } else if (mVideoZoom < 1.0f) {
                    // 缩小：整体等比收缩，四周黑边扩大，坐标完全位于屏幕内部
                    if (videoRatio > containerRatio) {
                        width = Math.round(containerWidth * mVideoZoom);
                        height = Math.round((containerWidth / videoRatio) * mVideoZoom);
                    } else {
                        height = Math.round(containerHeight * mVideoZoom);
                        width = Math.round((containerHeight * videoRatio) * mVideoZoom);
                    }
                } else {
                    // 放大：对标 Amlogic 芯片物理边界。
                    // 宽荧幕(2.35:1)宽度锁定屏幕物理上限(1920)，高度平滑扩大(消除黑边)，配合硬解裁切实现绝对等比
                    if (videoRatio > containerRatio) {
                        width = containerWidth;
                        int baseHeight = Math.round(containerWidth / videoRatio);
                        height = Math.min(Math.round(baseHeight * mVideoZoom), containerHeight);
                    } else {
                        height = containerHeight;
                        int baseWidth = Math.round(containerHeight * videoRatio);
                        width = Math.min(Math.round(baseWidth * mVideoZoom), containerWidth);
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
