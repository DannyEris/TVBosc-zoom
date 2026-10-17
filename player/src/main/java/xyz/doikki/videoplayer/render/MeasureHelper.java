package xyz.doikki.videoplayer.render;

import android.view.View;
import xyz.doikki.videoplayer.player.VideoView;

public class MeasureHelper {

    private int mVideoWidth;
    private int mVideoHeight;
    private int mCurrentScreenScale;
    private int mVideoRotationDegree;

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
                // 严格维持视频原生的物理宽高比，绝不在度量层产生畸变视口
                if (mVideoWidth * height < width * mVideoHeight) {
                    width = height * mVideoWidth / mVideoHeight;
                } else if (mVideoWidth * height > width * mVideoHeight) {
                    height = width * mVideoHeight / mVideoWidth;
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
