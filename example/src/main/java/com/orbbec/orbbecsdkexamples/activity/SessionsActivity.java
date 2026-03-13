package com.orbbec.orbbecsdkexamples.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.adapter.SessionAdapter;
import com.orbbec.orbbecsdkexamples.utils.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Sessions Activity
 *
 * Displays all recorded sessions, allowing the user to share or delete them.
 */
public class SessionsActivity extends AppCompatActivity implements SessionAdapter.SessionListener {

    private RecyclerView mRvSessions;
    private LinearLayout mLayoutEmpty;
    private SessionAdapter mAdapter;
    private final List<File> mSessionList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sessions);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        toolbar.setOnMenuItemClickListener(this::onMenuItemSelected);

        mRvSessions = findViewById(R.id.rv_sessions);
        mLayoutEmpty = findViewById(R.id.layout_empty);

        mAdapter = new SessionAdapter(mSessionList, this);
        mRvSessions.setLayoutManager(new LinearLayoutManager(this));
        mRvSessions.setAdapter(mAdapter);

        loadSessions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
    }

    private void loadSessions() {
        mSessionList.clear();
        String rootDir = FileUtils.getExternalSaveDir();
        if (rootDir == null) {
            updateEmptyState();
            return;
        }
        File root = new File(rootDir);
        if (!root.exists()) {
            updateEmptyState();
            return;
        }
        File[] dirs = root.listFiles(file ->
                file.isDirectory() && new File(file, "recording.bag").exists());
        if (dirs != null && dirs.length > 0) {
            Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
            mSessionList.addAll(Arrays.asList(dirs));
        }
        mAdapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (mSessionList.isEmpty()) {
            mLayoutEmpty.setVisibility(View.VISIBLE);
            mRvSessions.setVisibility(View.GONE);
        } else {
            mLayoutEmpty.setVisibility(View.GONE);
            mRvSessions.setVisibility(View.VISIBLE);
        }
    }

    private boolean onMenuItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_delete_all) {
            confirmDeleteAll();
            return true;
        }
        return false;
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_all_confirm)
                .setPositiveButton(R.string.session_delete, (dialog, which) -> {
                    for (File session : new ArrayList<>(mSessionList)) {
                        deleteRecursive(session);
                    }
                    loadSessions();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // SessionAdapter.SessionListener

    @Override
    public void onShare(File session) {
        File bagFile = new File(session, "recording.bag");
        if (!bagFile.exists()) {
            Toast.makeText(this, "Recording file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", bagFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/octet-stream");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, session.getName()));
        } catch (Exception e) {
            // Fallback: share the directory path as text
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, session.getAbsolutePath());
            startActivity(Intent.createChooser(shareIntent, session.getName()));
        }
    }

    @Override
    public void onDelete(File session) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.session_delete_confirm)
                .setPositiveButton(R.string.session_delete, (dialog, which) -> {
                    deleteRecursive(session);
                    loadSessions();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
