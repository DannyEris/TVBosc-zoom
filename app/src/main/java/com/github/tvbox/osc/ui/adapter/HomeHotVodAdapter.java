package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeHotVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private int defaultWidth;
    private final ImgUtil.Style style;
    private String tvRateValue;

    public HomeHotVodAdapter(ImgUtil.Style style, String tvRate) {
        super(R.layout.item_user_hot_vod, new ArrayList<>());
        if (style != null) {
            this.defaultWidth = ImgUtil.getStyleDefaultWidth(style);
        }
        this.style = style;
        this.tvRateValue = tvRate;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        tvDel.setVisibility(HawkConfig.hotVodDelete ? View.VISIBLE : View.GONE);

        TextView tvRate = helper.getView(R.id.tvRate);
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 2) {
            SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
            tvRateValue = bean != null ? bean.getName() : "";
        }
        tvRate.setText(tvRateValue);

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);

        int newWidth = ImgUtil.defaultWidth;
        int newHeight = ImgUtil.defaultHeight;
        if (style != null) {
            newWidth = defaultWidth;
            newHeight = (int) (newWidth / style.ratio);
        } else {
            // 依据首页海报设置：0-大(5列), 1-中(6列), 2-小(7列)
            int homePosterSize = Hawk.get(HawkConfig.HOME_POSTER_SIZE, 0);
            if (homePosterSize == 1) { // 中 (6列)
                newWidth = 176;
                newHeight = 230;
            } else if (homePosterSize == 2) { // 小 (7列)
                newWidth = 148;
                newHeight = 194;
            } else { // 大 (5列)
                newWidth = 214;
                newHeight = 280;
            }
        }

        String pic = item.pic == null ? "" : item.pic.trim();
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
            } else {
                ImgUtil.load(pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, newWidth), AutoSizeUtils.mm2px(mContext, newHeight), item.name);
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
            int homePosterSize = Hawk.get(HawkConfig.HOME_POSTER_SIZE, 0);
            int width = 214;
            int height = 280;
            if (homePosterSize == 1) { // 中 (6列)
                width = 176;
                height = 230;
            } else if (homePosterSize == 2) { // 小 (7列)
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
