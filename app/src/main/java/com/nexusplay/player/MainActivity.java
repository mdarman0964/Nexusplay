package com.nexusplay.player;

import android.Manifest;
import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private final ArrayList<Video> videos = new ArrayList<>();
    private LinearLayout list;
    private EditText search;
    private TextView count;
    private String tab = "All videos";
    private final int bg = 0xFF0B0D14, panel = 0xFF151925, muted = 0xFF8B91A6, white = 0xFFF5F6FC, mint = 0xFF4DE1C1;
    private ActivityResultLauncher<String> permissionRequest;
    private ActivityResultLauncher<IntentSenderRequest> deleteRequest;
    private ActivityResultLauncher<IntentSenderRequest> renamePermissionRequest;
    private Video pendingDelete, pendingRename;
    private String pendingRenameFilename;

    static class Video {
        String title, filename, folder, duration, size;
        Uri uri;
        Video(String t, String f, String fo, String d, String s, Uri u) { title=t; filename=f; folder=fo; duration=d; size=s; uri=u; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        permissionRequest = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) loadVideos(); else emptyMessage("Allow video access in Android settings to browse your library.");
        });
        deleteRequest = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) { Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show(); loadVideos(); }
            pendingDelete = null;
        });
        renamePermissionRequest = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && pendingRename != null && pendingRenameFilename != null) {
                performRename(pendingRename, pendingRenameFilename);
            } else {
                pendingRename = null;
                pendingRenameFilename = null;
            }
        });
        buildUi();
        if (hasPermission()) loadVideos(); else requestPermission();
    }

    private boolean hasPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestPermission() {
        permissionRequest.launch(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(dp(16), dp(4), dp(16), 0);
        root.setBackgroundColor(bg);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            view.setPadding(dp(16), topInset + dp(4), dp(16), bottomInset);
            return insets;
        });
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("NEXUSPLAY", 15, mint, true);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView local = label("●  LOCAL LIBRARY", 10, muted, true);
        top.addView(local);
        root.addView(top, lp(-1, dp(52)));

        search = new EditText(this);
        search.setSingleLine(true); search.setTextSize(15); search.setTextColor(white); search.setHintTextColor(muted);
        search.setHint("Search videos or folders"); search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(round(panel, dp(14)));
        root.addView(search, lp(-1, dp(48)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(0); tabs.setPadding(0, dp(6), 0, dp(6));
        String[] tabNames = {"All videos", "Folders", "Recent"};
        for (int i = 0; i < tabNames.length; i++) {
            String name = tabNames[i];
            TextView t = label(name, 13, name.equals(tab) ? bg : muted, true);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setMinWidth(0);
            t.setBackground(round(name.equals(tab) ? mint : panel, dp(18)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i < tabNames.length - 1) p.rightMargin = dp(6);
            tabs.addView(t, p);
            t.setOnClickListener(v -> { tab = name; buildUi(); render(); });
        }
        root.addView(tabs, lp(-1, dp(42)));
        count = label("YOUR LIBRARY", 11, muted, true);
        root.addView(count, lp(-1, dp(28)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this); list.setOrientation(1); list.setPadding(0, dp(4), 0, dp(16));
        scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footer = label("PRIVATE BY DESIGN  •  NO ADS", 10, muted, true);
        footer.setGravity(Gravity.CENTER); root.addView(footer, lp(-1, dp(30)));
        setContentView(root);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int c) { render(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
    }

    private void loadVideos() {
        videos.clear();
        Uri collection = Build.VERSION.SDK_INT >= 29 ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE};
        try (Cursor c = getContentResolver().query(collection, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null) while (c.moveToNext()) {
                long id = c.getLong(0), ms = c.getLong(3), bytes = c.getLong(4);
                String filename = c.getString(2);
                String title = filename;
                if (title == null || title.trim().isEmpty()) title = c.getString(1);
                if (title == null || title.trim().isEmpty()) title = "Untitled video";
                int titleDot = title.lastIndexOf('.');
                if (titleDot > 0) title = title.substring(0, titleDot);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String folder = "Videos";
                if (Build.VERSION.SDK_INT >= 29) {
                    try (Cursor f = getContentResolver().query(uri, new String[]{MediaStore.Video.Media.RELATIVE_PATH}, null, null, null)) {
                        if (f != null && f.moveToFirst() && f.getString(0) != null) {
                            String path = f.getString(0).replaceAll("/$", "");
                            int slash = path.lastIndexOf('/');
                            folder = slash >= 0 ? path.substring(slash + 1) : path;
                        }
                    } catch (Exception ignored) {}
                }
                videos.add(new Video(title == null ? "Untitled video" : title, filename == null ? "" : filename, folder, duration(ms), size(bytes), uri));
            }
        } catch (Exception e) {
            emptyMessage("Could not read the video library. Check media access."); return;
        }
        render();
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().toLowerCase(Locale.ROOT).trim();
        ArrayList<Video> shown = new ArrayList<>();
        for (Video v : videos) {
            if (!(v.title.toLowerCase(Locale.ROOT).contains(q) || v.filename.toLowerCase(Locale.ROOT).contains(q) || v.folder.toLowerCase(Locale.ROOT).contains(q))) continue;
            if (tab.equals("Folders")) {
                boolean seen = false; for (Video old : shown) if (old.folder.equals(v.folder)) { seen = true; break; }
                if (!seen) shown.add(v);
            } else shown.add(v);
        }
        count.setText(tab.equals("Folders") ? shown.size() + " FOLDERS" : shown.size() + " VIDEOS");
        if (shown.isEmpty()) { emptyMessage(videos.isEmpty() ? "No videos found yet.\nAdd videos to your device and allow library access." : "No matches. Try another search."); return; }
        for (Video v : shown) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(9), dp(9), dp(5), dp(9)); card.setBackground(round(panel, dp(16)));
            LinearLayout.LayoutParams cp = lp(-1, dp(94)); cp.setMargins(0, 0, 0, dp(8)); list.addView(card, cp);

            FrameLayoutThumb thumb = new FrameLayoutThumb(this);
            thumb.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.image.setBackground(round(0xFF24213A, dp(11)));
            try {
                Bitmap bitmap;
                if (Build.VERSION.SDK_INT >= 29) {
                    bitmap = getContentResolver().loadThumbnail(v.uri, new Size(dp(224), dp(152)), null);
                } else {
                    bitmap = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(),
                        Long.parseLong(v.uri.getLastPathSegment()), MediaStore.Video.Thumbnails.MINI_KIND, null);
                }
                if (bitmap != null) {
                    thumb.image.setImageBitmap(bitmap);
                    thumb.fallback.setVisibility(View.GONE);
                } else {
                    thumb.image.setImageDrawable(null);
                    thumb.fallback.setVisibility(View.VISIBLE);
                }
            } catch (Exception ignored) {
                thumb.image.setImageDrawable(null);
                thumb.image.setBackground(round(0xFF24213A, dp(11)));
                thumb.fallback.setVisibility(View.VISIBLE);
            }
            card.addView(thumb, new LinearLayout.LayoutParams(dp(112), dp(76)));
            LinearLayout info = new LinearLayout(this); info.setOrientation(1); info.setGravity(Gravity.CENTER_VERTICAL); info.setPadding(dp(10), 0, dp(4), 0);
            card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
            TextView title = label(tab.equals("Folders") ? v.folder : v.title, 13, white, true);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView meta = label(tab.equals("Folders") ? "Folder" : v.duration + "  ·  " + v.size, 11, muted, false);
            meta.setSingleLine(true); meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(meta, lp(-1, dp(20)));
            TextView menu = label("⋮", 24, white, true); menu.setGravity(Gravity.CENTER);
            card.addView(menu, lp(dp(34), dp(48)));
            menu.setOnClickListener(view -> showVideoMenu(view, v));
            card.setOnClickListener(view -> {
                if (tab.equals("Folders")) { tab = "All videos"; search.setText(v.folder); render(); }
                else play(v);
            });
        }
    }

    private void showVideoMenu(View anchor, Video v) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Play");
        menu.getMenu().add("Rename");
        menu.getMenu().add("Share");
        menu.getMenu().add("Details");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "Play": play(v); return true;
                case "Rename": showRenameDialog(v); return true;
                case "Share":
                    Intent share = new Intent(Intent.ACTION_SEND); share.setType("video/*"); share.putExtra(Intent.EXTRA_STREAM, v.uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(share, "Share video")); return true;
                case "Details":
                    Toast.makeText(this, v.title + "\n" + v.duration + " · " + v.size + "\n" + v.folder, Toast.LENGTH_LONG).show(); return true;
                case "Delete": confirmDelete(v); return true;
            }
            return false;
        });
        menu.show();
    }

    private void showRenameDialog(Video video) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(video.filename);
        input.setSelection(input.length());
        input.setHint("New video filename");
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename video")
            .setMessage("Keep the file extension (for example .mp4) to preserve the video format.")
            .setView(input)
            .setNegativeButton("Cancel", (d, w) -> {})
            .setPositiveButton("Rename", (d, w) -> {
                String filename = input.getText().toString().trim();
                if (filename.isEmpty() || filename.equals(".") || filename.equals("..") ||
                    filename.contains("/") || filename.contains("\\") || filename.indexOf('\0') >= 0) {
                    Toast.makeText(this, "Enter a valid video filename", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (filename.equals(video.filename)) return;
                pendingRename = video;
                pendingRenameFilename = filename;
                requestRenamePermission(video, filename);
            }).show();
    }

    private void requestRenamePermission(Video video, String filename) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                renamePermissionRequest.launch(new IntentSenderRequest.Builder(
                    MediaStore.createWriteRequest(getContentResolver(), java.util.Collections.singletonList(video.uri)).getIntentSender()).build());
            } else {
                performRename(video, filename);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) {
                try {
                    renamePermissionRequest.launch(new IntentSenderRequest.Builder(
                        ((RecoverableSecurityException)e).getUserAction().getActionIntent().getIntentSender()).build());
                } catch (Exception ignored) {
                    pendingRename = null; pendingRenameFilename = null;
                    Toast.makeText(this, "Rename permission required", Toast.LENGTH_SHORT).show();
                }
            } else {
                pendingRename = null; pendingRenameFilename = null;
                Toast.makeText(this, "Rename permission required", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            pendingRename = null; pendingRenameFilename = null;
            Toast.makeText(this, "Could not request rename permission", Toast.LENGTH_SHORT).show();
        }
    }

    private void performRename(Video video, String filename) {
        try {
            String displayTitle = filename;
            int dot = displayTitle.lastIndexOf('.');
            if (dot > 0) displayTitle = displayTitle.substring(0, dot);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Video.Media.TITLE, displayTitle);
            int updated = getContentResolver().update(video.uri, values, null, null);
            if (updated > 0) {
                Toast.makeText(this, "Video renamed successfully", Toast.LENGTH_SHORT).show();
                loadVideos();
            } else {
                Toast.makeText(this, "Could not rename this video", Toast.LENGTH_SHORT).show();
            }
            pendingRename = null;
            pendingRenameFilename = null;
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 30 && e instanceof RecoverableSecurityException) {
                try {
                    renamePermissionRequest.launch(new IntentSenderRequest.Builder(
                        ((RecoverableSecurityException)e).getUserAction().getActionIntent().getIntentSender()).build());
                    return;
                } catch (Exception ignored) {
                    Toast.makeText(this, "Rename permission was not granted", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Rename permission was not granted", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not rename this video", Toast.LENGTH_SHORT).show();
            pendingRename = null;
            pendingRenameFilename = null;
        }
    }

    private void confirmDelete(Video v) {
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete video?")
            .setMessage("“" + v.title + "” will be removed from your device.")
            .setNegativeButton("Cancel", (d, w) -> {}).setPositiveButton("Delete", (d, w) -> deleteVideo(v)).show();
    }
    private void deleteVideo(Video v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                pendingDelete = v;
                deleteRequest.launch(new IntentSenderRequest.Builder(MediaStore.createDeleteRequest(getContentResolver(), java.util.Collections.singletonList(v.uri)).getIntentSender()).build());
            } else {
                int n = getContentResolver().delete(v.uri, null, null);
                if (n > 0) { Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show(); loadVideos(); }
                else Toast.makeText(this, "Could not delete this video", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 30 && e instanceof RecoverableSecurityException) {
                try { startIntentSenderForResult(((RecoverableSecurityException)e).getUserAction().getActionIntent().getIntentSender(), 901, null, 0, 0, 0); }
                catch (Exception ignored) { Toast.makeText(this, "Deletion permission required", Toast.LENGTH_SHORT).show(); }
            } else Toast.makeText(this, "Deletion permission required", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Could not delete this video", Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 901 && resultCode == RESULT_OK) loadVideos();
    }
    private void play(Video v) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(v.uri);
        i.putExtra("title", v.title);
        ArrayList<String> playlist = new ArrayList<>();
        int startIndex = 0;
        for (int index = 0; index < videos.size(); index++) {
            Video item = videos.get(index);
            playlist.add(item.uri.toString());
            if (item.uri.equals(v.uri)) startIndex = index;
        }
        i.putStringArrayListExtra("playlist", playlist);
        i.putExtra("start_index", startIndex);
        startActivity(i);
    }
    private void emptyMessage(String m) {
        if (list == null) return;
        list.removeAllViews(); TextView t = label(m, 14, muted, false); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(24), dp(18), dp(24)); list.addView(t, lp(-1, dp(150)));
    }
    private TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD); return t;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private String duration(long ms) { long s=ms/1000; return s>=3600 ? String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s%3600)/60,s%60) : String.format(Locale.ROOT,"%d:%02d",(s%3600)/60,s%60); }
    private String size(long b) { if(b>=1073741824L)return String.format(Locale.ROOT,"%.1f GB",b/1073741824d); if(b>=1048576L)return String.format(Locale.ROOT,"%.0f MB",b/1048576d); return Math.max(1,b/1024)+" KB"; }

    private class FrameLayoutThumb extends LinearLayout {
        final ImageView image = new ImageView(MainActivity.this);
        final TextView fallback = label("▶", 22, mint, true);
        FrameLayoutThumb(Activity activity) {
            super(activity); setOrientation(VERTICAL); setGravity(Gravity.CENTER);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
            frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1,-1));
            fallback.setGravity(Gravity.CENTER); fallback.setBackground(round(0xFF24213A, dp(11))); fallback.setVisibility(View.GONE);
            frame.addView(fallback, new android.widget.FrameLayout.LayoutParams(-1,-1));
            addView(frame, new LinearLayout.LayoutParams(-1,-1));
        }
    }
}