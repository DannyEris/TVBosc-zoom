package com.github.tvbox.osc.ui.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

public class SelectDialogAdapter<T> extends ListAdapter<T, SelectDialogAdapter.SelectViewHolder> {

    class SelectViewHolder extends RecyclerView.ViewHolder {

        public SelectViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
        }
    }

    public interface SelectDialogInterface<T> {
        void click(T value, int pos);

        String getDisplay(T val);
    }


    public static DiffUtil.ItemCallback<String> stringDiff = new DiffUtil.ItemCallback<String>() {

        @Override
        public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }
    };


    private ArrayList<T> data = new ArrayList<>();
    private int select = 0;
    private SelectDialogInterface dialogInterface;

    // 是否为“切换音轨”弹窗专属模式
    private boolean isAudioTrack = false;

    public void setIsAudioTrack(boolean isAudioTrack) {
        this.isAudioTrack = isAudioTrack;
        notifyDataSetChanged();
    }

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback) {
        super(diffCallback);
        this.dialogInterface = dialogInterface;
    }

    public void setData(List<T> newData, int defaultSelect) {
        data.clear();
        data.addAll(newData);
        select = defaultSelect;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        // 如果是音轨弹窗，在尾部多放一个切换按钮
        return isAudioTrack ? data.size() + 1 : data.size();
    }

    @Override
    public SelectDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new SelectDialogAdapter.SelectViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull SelectDialogAdapter.SelectViewHolder holder, @SuppressLint("RecyclerView") int position) {
        // 渲染底部的【音频输出模式】切换按钮
        if (isAudioTrack && position == data.size()) {
            TextView view = holder.itemView.findViewById(R.id.tvName);
            final Context ctx = holder.itemView.getContext();
            boolean isMulti = ExoMediaPlayer.isMultiChannelModeEnabled(ctx);

            if (isMulti) {
                view.setText("【音频输出: 5.1功放直通】(按OK切2.0)");
                view.setTextColor(0xff00e5ff); // 青蓝色高亮
            } else {
                view.setText("【音频输出: 电视立体声】(按OK切5.1)");
                view.setTextColor(0xffffaa00); // 暖黄色高亮
            }
            view.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean current = ExoMediaPlayer.isMultiChannelModeEnabled(ctx);
                    boolean next = !current;
                    // 立即更新 UI 呈现零延迟反馈
                    if (next) {
                        view.setText("【音频输出: 5.1功放直通】(按OK切2.0)");
                        view.setTextColor(0xff00e5ff);
                    } else {
                        view.setText("【音频输出: 电视立体声】(按OK切5.1)");
                        view.setTextColor(0xffffaa00);
                    }
                    // 发送广播触发底层播放器即时重载声道策略
                    ctx.sendBroadcast(new Intent(ExoMediaPlayer.ACTION_TOGGLE_AUDIO_MODE));
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            notifyItemChanged(position);
                        }
                    }, 200);
                }
            });
            return;
        }

        // 常规音轨 Item 渲染
        T value = data.get(position);
        String name = dialogInterface.getDisplay(value);
        TextView view = holder.itemView.findViewById(R.id.tvName);
        if (position == select) {
            view.setTextColor(0xff02f8e1);
            view.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        } else {
            view.setTextColor(Color.WHITE);
            view.setTypeface(Typeface.defaultFromStyle(Typeface.NORMAL));
        }
        view.setText(name);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (position == select)
                    return;
                notifyItemChanged(select);
                select = position;
                notifyItemChanged(select);
                dialogInterface.click(value, position);
            }
        });
    }
}
