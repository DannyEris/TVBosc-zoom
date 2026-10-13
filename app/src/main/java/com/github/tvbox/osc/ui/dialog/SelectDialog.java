package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.owen.tvrecyclerview.widget.GridLayoutManager;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SelectDialog<T> extends BaseDialog {

    private String mTip = "";
    private SelectDialogAdapter<T> mAdapter;

    public SelectDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_select);
    }

    public SelectDialog(@NonNull @NotNull Context context, int resId) {
        super(context);
        setContentView(resId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void setTip(String tip) {
        this.mTip = tip;
        TextView titleTv = findViewById(R.id.title);
        if (titleTv != null) {
            titleTv.setText(tip);
        }
        if (mAdapter != null) {
            mAdapter.setIsAudioTrack(isAudioTrackDialog(tip));
        }
    }

    private boolean isAudioTrackDialog(String tip) {
        if (tip != null && tip.contains("音轨")) {
            return true;
        }
        try {
            TextView tv = findViewById(R.id.title);
            if (tv != null && tv.getText() != null && tv.getText().toString().contains("音轨")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public void setAdapter(SelectDialogAdapter.SelectDialogInterface<T> sourceBeanSelectDialogInterface,
                           DiffUtil.ItemCallback<T> sourceBeanItemCallback,
                           List<T> data, int select) {
        final int selectIdx = select;
        mAdapter = new SelectDialogAdapter<>(sourceBeanSelectDialogInterface, sourceBeanItemCallback);
        mAdapter.setIsAudioTrack(isAudioTrackDialog(mTip));
        mAdapter.setData(data, select);

        TvRecyclerView tvRecyclerView = findViewById(R.id.list);
        tvRecyclerView.setAdapter(mAdapter);
        tvRecyclerView.setSelectedPosition(select);
        if (select < 10) {
            tvRecyclerView.setSelection(select);
        }
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                if (selectIdx >= 10) {
                    tvRecyclerView.smoothScrollToPosition(selectIdx);
                    tvRecyclerView.setSelectionWithSmooth(selectIdx);
                }
            }
        });
    }

}
