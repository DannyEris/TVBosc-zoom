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
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HistoryAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {
    public HistoryAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo item) {
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        tvDel.setVisibility(HawkConfig.hotVodDelete ? View.VISIBLE : View.GONE);

        TextView tvYear = helper.getView(R.id.tvYear);
        SourceBean bean = ApiConfig.get().getSource(item.sourceKey);
        tvYear.setText(bean != null ? bean.getName() : "搜");
        helper.setVisible(R.id.tvLang, false);
        helper.setVisible(R.id.tvArea, false);
        if (item.note == null || item.note.isEmpty()) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);

        // 依据图片尺寸设置：0-大(5列), 1-中(6列), 2-小(7列)
        int posterSize = Hawk.get(HawkConfig.POSTER_SIZE, 0);
        int width = 214;
        int height = 280;
        if (posterSize == 1) { // 中 (6列)
            width = 176;
            height = 230;
        } else if (posterSize == 2) { // 小 (7列)
            width = 148;
            height = 194;
        }

        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, width), AutoSizeUtils.mm2px(mContext, height), item.name);
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }

        // 动态调整卡片外层容器尺寸，防止列数变多时发生挤压错位
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
