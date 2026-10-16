package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private static final String TAG = "FastSearchAdapter";
    private final int searchViewMode;

    public FastSearchAdapter() {
        super(Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0 ? R.layout.item_search_lite : R.layout.item_search, new ArrayList<>());
        this.searchViewMode = Hawk.get(HawkConfig.SEARCH_VIEW, 0);
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        if (searchViewMode == 0) {
            // 文字列表布局
            String siteName = "";
            try {
                if (ApiConfig.get().getSource(item.sourceKey) != null) {
                    siteName = ApiConfig.get().getSource(item.sourceKey).getName();
                }
            } catch (Exception ignored) {}
            helper.setText(R.id.tvName, String.format("[%s]  %s  %s", siteName, item.name, item.note == null ? "" : item.note));
            return;
        }

        // 缩略图模式 (4 / 5 / 6 列)
        helper.setText(R.id.tvName, item.name);
        try {
            if (ApiConfig.get().getSource(item.sourceKey) != null) {
                helper.setText(R.id.tvSite, ApiConfig.get().getSource(item.sourceKey).getName());
            }
        } catch (Exception ignored) {}
        
        helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
        if (item.note != null && !item.note.isEmpty()) {
            helper.setText(R.id.tvNote, item.note);
        }

        ImageView ivThumb = helper.getView(R.id.ivThumb);

        // 依档位动态计算宽高
        int width = 214;
        int height = 280;
        if (searchViewMode == 2) { // 中 (5列)
            width = 176;
            height = 230;
        } else if (searchViewMode == 3) { // 小 (6列)
            width = 148;
            height = 194;
        }

        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, width), AutoSizeUtils.mm2px(mContext, height), item.name);
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }

        // 调整卡片外层尺寸，防止列数变多时发生挤压错位
        if (ivThumb.getParent() instanceof ViewGroup) {
            ViewGroup container = (ViewGroup) ivThumb.getParent();
            ViewGroup.LayoutParams lp = container.getLayoutParams();
            if (lp != null) {
                lp.width = AutoSizeUtils.mm2px(mContext, width);
                lp.height = AutoSizeUtils.mm2px(mContext, height);
                container.setLayoutParams(lp);
            }
        }
    }
}
