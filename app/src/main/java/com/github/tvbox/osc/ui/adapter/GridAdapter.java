package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private boolean mShowList;
    private int defaultWidth;
    public ImgUtil.Style style;

    public GridAdapter(boolean showList, ImgUtil.Style style) {
        super(showList ? R.layout.item_list : R.layout.item_grid, new ArrayList<>());
        this.mShowList = showList;
        if (style != null) {
            if (style.type.equals("list")) this.mShowList = true;
            this.defaultWidth = ImgUtil.getStyleDefaultWidth(style);
        }
        this.style = style;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        if (this.mShowList) {
            helper.setText(R.id.tvNote, item.note);
            helper.setText(R.id.tvName, item.name);
            ImageView ivThumb = helper.getView(R.id.ivThumb);
            String pic = item.pic == null ? "" : item.pic.trim();
            if (!TextUtils.isEmpty(pic)) {
                if (ImgUtil.isBase64Image(pic)) {
                    ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
                } else {
                    int roundingRadius = AutoSizeUtils.mm2px(mContext, 10);
                    int imageWidth = AutoSizeUtils.mm2px(mContext, 240);
                    int imageHeight = AutoSizeUtils.mm2px(mContext, 336);
                    if (style != null) {
                        ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name, ImageView.ScaleType.FIT_XY);
                    } else {
                        ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name);
                    }
                }
            } else {
                ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
            }
            return;
        }

        TextView tvYear = helper.getView(R.id.tvYear);
        if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }
        TextView tvLang = helper.getView(R.id.tvLang);
        tvLang.setVisibility(View.GONE);
        TextView tvArea = helper.getView(R.id.tvArea);
        tvArea.setVisibility(View.GONE);
        if (TextUtils.isEmpty(item.note)) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvActor, item.actor);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        
        int newWidth = ImgUtil.defaultWidth;
        int newHeight = ImgUtil.defaultHeight;
        if (style != null) {
            newWidth = defaultWidth;
            newHeight = (int) (newWidth / style.ratio);
        } else {
            // 根据大(5列)、中(6列)、小(7列)动态匹配卡片采样尺寸
            int posterSize = Hawk.get(HawkConfig.POSTER_SIZE, 0);
            if (posterSize == 1) { // 中 (6列)
                newWidth = 176;
                newHeight = 230;
            } else if (posterSize == 2) { // 小图 (7列)
                newWidth = 148;
                newHeight = 194;
            } else { // 大图 (5列，默认)
                newWidth = 214;
                newHeight = 280;
            }
        }

        String pic = item.pic == null ? "" : item.pic.trim();
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
            } else {
                int roundingRadius = AutoSizeUtils.mm2px(mContext, 10);
                int imageWidth = AutoSizeUtils.mm2px(mContext, newWidth);
                int imageHeight = AutoSizeUtils.mm2px(mContext, newHeight);
                if (style != null) {
                    ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name, ImageView.ScaleType.FIT_XY);
                } else {
                    ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name);
                }
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
        applyStyleToImage(ivThumb);
    }

    private void applyStyleToImage(final ImageView ivThumb) {
        ViewGroup container = (ViewGroup) ivThumb.getParent();
        if (container == null) return;

        if (style != null) {
            int width = defaultWidth;
            int height = (int) (width / style.ratio);
            ViewGroup.LayoutParams containerParams = container.getLayoutParams();
            containerParams.height = AutoSizeUtils.mm2px(mContext, height);
            containerParams.width = AutoSizeUtils.mm2px(mContext, width);
            container.setLayoutParams(containerParams);
        } else {
            // 依据设置动态拉伸/收缩海报 FrameLayout 容器
            int posterSize = Hawk.get(HawkConfig.POSTER_SIZE, 0);
            int width = 214;
            int height = 280;
            if (posterSize == 1) { // 中 (6列)
                width = 176;
                height = 230;
            } else if (posterSize == 2) { // 小图 (7列)
                width = 148;
                height = 194;
            }
            ViewGroup.LayoutParams containerParams = container.getLayoutParams();
            containerParams.width = AutoSizeUtils.mm2px(mContext, width);
            containerParams.height = AutoSizeUtils.mm2px(mContext, height);
            container.setLayoutParams(containerParams);
        }
    }
}
