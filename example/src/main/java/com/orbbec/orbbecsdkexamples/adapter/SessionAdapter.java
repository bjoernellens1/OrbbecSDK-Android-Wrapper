package com.orbbec.orbbecsdkexamples.adapter;

import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.orbbec.orbbecsdkexamples.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the list of recorded sessions.
 */
public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {

    public interface SessionListener {
        void onShare(File session);
        void onDelete(File session);
    }

    private final List<File> mSessions;
    private final SessionListener mListener;
    private final SimpleDateFormat mDateFormat =
            new SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault());

    public SessionAdapter(List<File> sessions, SessionListener listener) {
        mSessions = sessions;
        mListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File session = mSessions.get(position);
        holder.tvName.setText(session.getName());
        holder.tvDate.setText(mDateFormat.format(new Date(session.lastModified())));

        // Compute directory size
        long size = getDirSize(session);
        holder.tvSize.setText(Formatter.formatShortFileSize(holder.itemView.getContext(), size));

        // Stream info from metadata.json if available, otherwise detect from files
        String streams = detectStreams(session);
        holder.tvStreams.setText(streams);

        holder.btnShare.setOnClickListener(v -> mListener.onShare(session));
        holder.btnDelete.setOnClickListener(v -> mListener.onDelete(session));
    }

    @Override
    public int getItemCount() {
        return mSessions.size();
    }

    private static long getDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? getDirSize(f) : f.length();
            }
        }
        return size;
    }

    private static String detectStreams(File session) {
        StringBuilder sb = new StringBuilder();
        if (new File(session, "recording.bag").exists()) sb.append("Color · Depth · IR");
        if (new File(session, "imu.csv").exists()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("IMU");
        }
        return sb.length() > 0 ? sb.toString() : "—";
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvDate, tvSize, tvStreams;
        ImageButton btnShare, btnDelete;

        ViewHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_session_name);
            tvDate = v.findViewById(R.id.tv_session_date);
            tvSize = v.findViewById(R.id.tv_session_size);
            tvStreams = v.findViewById(R.id.tv_session_streams);
            btnShare = v.findViewById(R.id.btn_share);
            btnDelete = v.findViewById(R.id.btn_delete);
        }
    }
}
