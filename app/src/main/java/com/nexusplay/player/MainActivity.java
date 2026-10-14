package com.nexusplay.player;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    // Navigation Views: Fast Folder > File hierarchy
    private static final int VIEW_FOLDERS = 0;
    private static final int VIEW_FOLDER_CONTENTS = 1;
    private static final int VIEW_ALL_FILES = 2;
    private int currentView = VIEW_FOLDERS;
    private String activeFolder = null;

    // MX Player Pro Theme Colors
    private final int bg = 0xFF000000;
    private final int panel = 0xFF0D1017;
    private final int folderIconBg = 0xFF1E2638;
    private final int muted = 0xFF8E95A5;
    private final int white = 0xFFFFFFFF;
    private final int mint = 0xFF4DE1C1;
    private final int coral = 0xFFFF3B30;

    private final ArrayList<Video> videos = new ArrayList<>();
    private FrameLayout rootFrame;
    private LinearLayout contentRoot;
    private LinearLayout list;
    private EditText search;
    private TextView searchButton;
    private TextView headerBack;
    private LinearLayout breadcrumbBar;
    private TextView crumbRoot;
    private TextView crumbSep;
    private TextView crumbFolder;
    private TextView sortButton;
    private FrameLayout fabPlay;

    // Fast Tab Switcher (Folders vs All Videos)
    private LinearLayout tabSwitcher;
    private TextView tabFolders;
    private TextView tabAllVideos;

    private int folderSortOrder = 0; // 0: Name A-Z, 1: Media Count Desc
    private int videoSortOrder = 0; // 0: Date Desc, 1: Date Asc, 2: Name A-Z, 3: Size Desc, 4: Duration Desc

    private ActivityResultLauncher<String> permissionRequest;
    private ActivityResultLauncher<IntentSenderRequest> deleteRequest;
    private ActivityResultLauncher<IntentSenderRequest> renamePermissionRequest;
    private Video pendingDelete, pendingRename;
    private String pendingRenameFilename;

    // Fast In-Memory Thumbnail Cache (32MB) & Worker Pool
    private static final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(
            (int) (Math.min(32 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8))) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static final ExecutorService thumbExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();

    static class Video {
        String title, filename, folder, duration, size;
        long durationMs, sizeBytes, dateAdded;
        Uri uri;

        Video(String t, String f, String fo, String d, String s, Uri u, long ms, long bytes, long date) {
            title = t; filename = f; folder = fo; duration = d; size = s;
            uri = u; durationMs = ms; sizeBytes = bytes; dateAdded = date;
        }
    }

    static class FolderItem {
        String name;
        ArrayList<Video> videos = new ArrayList<>();
        int unwatchedCount = 0;
        long totalBytes = 0;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        permissionRequest = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) loadVideos(); else emptyMessage("Please allow video access in settings to browse your folders.");
        });
        deleteRequest = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show();
                loadVideos();
            }
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

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermission()) {
            if (videos.isEmpty()) {
                loadVideos();
            } else {
                render();
            }
        }
    }

    private boolean hasPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        permissionRequest.launch(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private void buildUi() {
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(bg);

        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.setPadding(dp(16), dp(4), dp(16), 0);
        contentRoot.setBackgroundColor(bg);

        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            contentRoot.setPadding(dp(16), topInset + dp(4), dp(16), 0);
            if (fabPlay != null) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) fabPlay.getLayoutParams();
                flp.bottomMargin = bottomInset + dp(24);
                fabPlay.setLayoutParams(flp);
            }
            return insets;
        });

        // Top Header Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        headerBack = label("←", 24, white, false);
        headerBack.setPadding(0, 0, dp(12), 0);
        headerBack.setVisibility(View.GONE);
        headerBack.setOnClickListener(v -> closeFolder());
        topBar.addView(headerBack);

        // Fast Folder > File Breadcrumb
        breadcrumbBar = new LinearLayout(this);
        breadcrumbBar.setOrientation(LinearLayout.HORIZONTAL);
        breadcrumbBar.setGravity(Gravity.CENTER_VERTICAL);

        crumbRoot = label("Folders", 20, white, true);
        crumbRoot.setOnClickListener(v -> {
            if (currentView == VIEW_FOLDER_CONTENTS) closeFolder();
            else switchTab(VIEW_FOLDERS);
        });
        breadcrumbBar.addView(crumbRoot);

        crumbSep = label(" › ", 20, mint, true);
        crumbSep.setVisibility(View.GONE);
        breadcrumbBar.addView(crumbSep);

        crumbFolder = label("", 20, white, true);
        crumbFolder.setSingleLine(true);
        crumbFolder.setEllipsize(android.text.TextUtils.TruncateAt.END);
        crumbFolder.setVisibility(View.GONE);
        breadcrumbBar.addView(crumbFolder);

        LinearLayout.LayoutParams breadcrumbLp = new LinearLayout.LayoutParams(0, -2, 1f);
        topBar.addView(breadcrumbBar, breadcrumbLp);

        // Search Icon Button
        searchButton = label("⌕", 24, white, false);
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        searchButton.setOnClickListener(v -> toggleSearch());
        topBar.addView(searchButton);

        // Sort / View Icon Button
        sortButton = label("⇅", 20, white, false);
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setPadding(dp(10), dp(8), dp(4), dp(8));
        sortButton.setOnClickListener(v -> showSortDialog());
        topBar.addView(sortButton);

        contentRoot.addView(topBar, lp(-1, dp(48)));

        // Animated Search Input Bar
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(white);
        search.setHintTextColor(muted);
        search.setHint("Search folders or videos...");
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(createRoundDrawable(0xFF151924, dp(14)));
        search.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchLp = lp(-1, dp(44));
        searchLp.topMargin = dp(6);
        searchLp.bottomMargin = dp(4);
        contentRoot.addView(search, searchLp);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int c) { render(); }
            public void afterTextChanged(android.text.Editable e) {}
        });

        // Fast Tab Switcher: [ 📁 Folders ]  [ 🎬 All Videos ]
        tabSwitcher = new LinearLayout(this);
        tabSwitcher.setOrientation(LinearLayout.HORIZONTAL);
        tabSwitcher.setGravity(Gravity.CENTER_VERTICAL);
        tabSwitcher.setPadding(0, dp(4), 0, dp(8));

        tabFolders = label("📁 Folders", 13, mint, true);
        tabFolders.setPadding(dp(14), dp(6), dp(14), dp(6));
        tabFolders.setBackground(createRoundDrawable(0xFF1B2436, dp(16)));
        tabFolders.setOnClickListener(v -> switchTab(VIEW_FOLDERS));
        tabSwitcher.addView(tabFolders);

        View spacer = new View(this);
        tabSwitcher.addView(spacer, lp(dp(10), dp(1)));

        tabAllVideos = label("🎬 All Videos", 13, muted, false);
        tabAllVideos.setPadding(dp(14), dp(6), dp(14), dp(6));
        tabAllVideos.setBackground(createRoundDrawable(0x00000000, dp(16)));
        tabAllVideos.setOnClickListener(v -> switchTab(VIEW_ALL_FILES));
        tabSwitcher.addView(tabAllVideos);

        contentRoot.addView(tabSwitcher, lp(-1, -2));

        // Scrollable List
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setPadding(0, dp(2), 0, dp(80));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(2), 0, dp(16));
        scroll.addView(list);

        contentRoot.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        rootFrame.addView(contentRoot, new FrameLayout.LayoutParams(-1, -1));

        // MX Player Floating Action Button (White circle with Black Play icon)
        fabPlay = new FrameLayout(this);
        fabPlay.setBackground(createCircleDrawable(Color.WHITE));
        fabPlay.setElevation(dp(8));

        TextView fabIcon = label("▶", 20, Color.BLACK, true);
        fabIcon.setGravity(Gravity.CENTER);
        fabPlay.addView(fabIcon, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(24), dp(24));
        rootFrame.addView(fabPlay, flp);

        fabPlay.setOnClickListener(v -> resumeLastPlayedVideo());

        setContentView(rootFrame);
    }

    private void switchTab(int viewMode) {
        currentView = viewMode;
        activeFolder = null;
        updateHeaderAndTabs();
        render();
    }

    private void openFolder(String folderName) {
        currentView = VIEW_FOLDER_CONTENTS;
        activeFolder = folderName;
        updateHeaderAndTabs();
        render();
    }

    private void closeFolder() {
        currentView = VIEW_FOLDERS;
        activeFolder = null;
        updateHeaderAndTabs();
        render();
    }

    private void updateHeaderAndTabs() {
        if (currentView == VIEW_FOLDER_CONTENTS) {
            headerBack.setVisibility(View.VISIBLE);
            crumbRoot.setText("Folders");
            crumbRoot.setTextColor(mint);
            crumbSep.setVisibility(View.VISIBLE);
            crumbFolder.setVisibility(View.VISIBLE);
            crumbFolder.setText(activeFolder != null ? activeFolder : "");
            tabSwitcher.setVisibility(View.GONE);
        } else if (currentView == VIEW_ALL_FILES) {
            headerBack.setVisibility(View.GONE);
            crumbRoot.setText("All Videos");
            crumbRoot.setTextColor(white);
            crumbSep.setVisibility(View.GONE);
            crumbFolder.setVisibility(View.GONE);
            tabSwitcher.setVisibility(View.VISIBLE);
            tabFolders.setTextColor(muted);
            tabFolders.setTypeface(null, Typeface.NORMAL);
            tabFolders.setBackground(createRoundDrawable(0x00000000, dp(16)));
            tabAllVideos.setTextColor(mint);
            tabAllVideos.setTypeface(null, Typeface.BOLD);
            tabAllVideos.setBackground(createRoundDrawable(0xFF1B2436, dp(16)));
        } else {
            headerBack.setVisibility(View.GONE);
            crumbRoot.setText("Folders");
            crumbRoot.setTextColor(white);
            crumbSep.setVisibility(View.GONE);
            crumbFolder.setVisibility(View.GONE);
            tabSwitcher.setVisibility(View.VISIBLE);
            tabFolders.setTextColor(mint);
            tabFolders.setTypeface(null, Typeface.BOLD);
            tabFolders.setBackground(createRoundDrawable(0xFF1B2436, dp(16)));
            tabAllVideos.setTextColor(muted);
            tabAllVideos.setTypeface(null, Typeface.NORMAL);
            tabAllVideos.setBackground(createRoundDrawable(0x00000000, dp(16)));
        }
    }

    private void toggleSearch() {
        if (search.getVisibility() != View.VISIBLE) {
            search.setVisibility(View.VISIBLE);
            search.setAlpha(0f);
            search.setTranslationY(-dp(6));
            search.animate().alpha(1f).translationY(0f).setDuration(150).start();
            searchButton.setText("✕");
            searchButton.setTextSize(18);
            search.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        } else {
            search.setText("");
            search.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.animate().alpha(0f).translationY(-dp(6)).setDuration(120).withEndAction(() -> {
                search.setVisibility(View.GONE);
                searchButton.setText("⌕");
                searchButton.setTextSize(24);
                render();
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (search.getVisibility() == View.VISIBLE) {
            toggleSearch();
            return;
        }
        if (currentView == VIEW_FOLDER_CONTENTS) {
            closeFolder();
            return;
        }
        super.onBackPressed();
    }

    private void showSortDialog() {
        if (currentView == VIEW_FOLDERS) {
            String[] options = {"Folder Name (A → Z)", "Media Count (Most first)"};
            new AlertDialog.Builder(this)
                .setTitle("Sort Folders")
                .setSingleChoiceItems(options, folderSortOrder, (dialog, which) -> {
                    folderSortOrder = which;
                    render();
                    dialog.dismiss();
                })
                .show();
        } else {
            String[] options = {
                "Date Added (Newest first)",
                "Date Added (Oldest first)",
                "Name (A → Z)",
                "File Size (Largest first)",
                "Duration (Longest first)"
            };
            new AlertDialog.Builder(this)
                .setTitle("Sort Videos")
                .setSingleChoiceItems(options, videoSortOrder, (dialog, which) -> {
                    videoSortOrder = which;
                    render();
                    dialog.dismiss();
                })
                .show();
        }
    }

    private void resumeLastPlayedVideo() {
        SharedPreferences resumePrefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
        Video targetVideo = null;
        long latestPosition = 0;

        for (Video v : videos) {
            long pos = resumePrefs.getLong("position_" + v.uri.toString(), 0L);
            if (pos > latestPosition) {
                latestPosition = pos;
                targetVideo = v;
            }
        }

        if (targetVideo == null && !videos.isEmpty()) {
            targetVideo = videos.get(0);
        }

        if (targetVideo != null) {
            play(targetVideo);
        } else {
            Toast.makeText(this, "No videos to play", Toast.LENGTH_SHORT).show();
        }
    }

    // Fast Single-Query Background Loader
    private void loadVideos() {
        loaderExecutor.execute(() -> {
            ArrayList<Video> loaded = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= 29 ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] cols;
            if (Build.VERSION.SDK_INT >= 29) {
                cols = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH
                };
            } else {
                cols = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                };
            }

            try (Cursor c = getContentResolver().query(collection, cols, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) while (c.moveToNext()) {
                    long id = c.getLong(0), ms = c.getLong(3), bytes = c.getLong(4), date = c.getLong(5);
                    String filename = c.getString(2);
                    String title = filename;
                    if (title == null || title.trim().isEmpty()) title = c.getString(1);
                    if (title == null || title.trim().isEmpty()) title = "Untitled video";
                    int titleDot = title.lastIndexOf('.');
                    if (titleDot > 0) title = title.substring(0, titleDot);
                    Uri uri = ContentUris.withAppendedId(collection, id);

                    String folder = null;
                    if (c.getColumnCount() > 6) {
                        folder = c.getString(6);
                    }
                    if (folder == null || folder.trim().isEmpty()) {
                        if (Build.VERSION.SDK_INT >= 29 && c.getColumnCount() > 7) {
                            String relPath = c.getString(7);
                            if (relPath != null && !relPath.isEmpty()) {
                                String path = relPath.replaceAll("/$", "");
                                int slash = path.lastIndexOf('/');
                                folder = slash >= 0 ? path.substring(slash + 1) : path;
                            }
                        }
                    }
                    if (folder == null || folder.trim().isEmpty()) {
                        folder = "Videos";
                    }

                    loaded.add(new Video(title, filename == null ? "" : filename, folder, duration(ms), size(bytes), uri, ms, bytes, date));
                }
            } catch (Exception ignored) {}

            runOnUiThread(() -> {
                videos.clear();
                videos.addAll(loaded);
                render();
            });
        });
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().toLowerCase(Locale.ROOT).trim();

        if (currentView == VIEW_FOLDERS) {
            renderFoldersView(q);
        } else if (currentView == VIEW_FOLDER_CONTENTS) {
            renderFolderContentsView(q);
        } else {
            renderAllFilesView(q);
        }
    }

    // 1. FOLDERS VIEW (Fast Folder list with media count and unwatched badges)
    private void renderFoldersView(String query) {
        SharedPreferences resumePrefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
        HashMap<String, FolderItem> folderMap = new HashMap<>();

        for (Video v : videos) {
            if (!query.isEmpty() && !(v.folder.toLowerCase(Locale.ROOT).contains(query) || v.title.toLowerCase(Locale.ROOT).contains(query))) continue;
            FolderItem item = folderMap.get(v.folder);
            if (item == null) {
                item = new FolderItem();
                item.name = v.folder;
                folderMap.put(v.folder, item);
            }
            item.videos.add(v);
            item.totalBytes += v.sizeBytes;
            long pos = resumePrefs.getLong("position_" + v.uri.toString(), 0L);
            if (pos == 0) {
                item.unwatchedCount++;
            }
        }

        if (folderMap.isEmpty()) {
            emptyMessage(videos.isEmpty() ? "No video folders found." : "No folders match \"" + query + "\"");
            return;
        }

        ArrayList<FolderItem> folderList = new ArrayList<>(folderMap.values());
        if (folderSortOrder == 0) {
            Collections.sort(folderList, (a, b) -> a.name.compareToIgnoreCase(b.name));
        } else {
            Collections.sort(folderList, (a, b) -> Integer.compare(b.videos.size(), a.videos.size()));
        }

        for (FolderItem f : folderList) {
            list.addView(buildMxFolderRow(f));
        }
    }

    private View buildMxFolderRow(FolderItem f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout.LayoutParams rlp = lp(-1, -2);
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        // MX Player Style Folder Icon Container
        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setBackground(createRoundDrawable(folderIconBg, dp(12)));
        boolean isCamera = f.name.toLowerCase(Locale.ROOT).contains("camera");

        TextView iconGlyph = label(isCamera ? "📷" : "📁", isCamera ? 20 : 22, 0xFFA0AEC0, false);
        iconGlyph.setGravity(Gravity.CENTER);
        iconFrame.addView(iconGlyph, new FrameLayout.LayoutParams(-1, -1));

        // Red badge for unwatched/new media
        if (f.unwatchedCount > 0) {
            TextView badge = label(f.unwatchedCount > 99 ? "99+" : String.valueOf(f.unwatchedCount), 10, Color.WHITE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(createCircleDrawable(coral));
            badge.setPadding(dp(4), 0, dp(4), 0);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
            blp.setMargins(0, dp(2), dp(2), 0);
            iconFrame.addView(badge, blp);
        }

        row.addView(iconFrame, lp(dp(54), dp(44)));

        // Folder Details
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        textCol.setPadding(dp(16), 0, dp(4), 0);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView nameText = label(f.name, 16, white, false);
        nameText.setSingleLine(true);
        nameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(nameText);

        TextView countText = label(f.videos.size() + " media files", 13, muted, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(3);
        textCol.addView(countText, clp);

        // Fast tap: opens folder contents immediately
        row.setOnClickListener(v -> openFolder(f.name));
        return row;
    }

    // 2. FOLDER CONTENTS VIEW (Videos in current folder with breadcrumb)
    private void renderFolderContentsView(String query) {
        ArrayList<Video> folderVideos = new ArrayList<>();
        for (Video v : videos) {
            if (!v.folder.equals(activeFolder)) continue;
            if (!query.isEmpty() && !v.title.toLowerCase(Locale.ROOT).contains(query)) continue;
            folderVideos.add(v);
        }

        sortVideos(folderVideos);

        if (folderVideos.isEmpty()) {
            emptyMessage("No videos found in \"" + activeFolder + "\"");
            return;
        }

        SharedPreferences resumePrefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
        for (Video v : folderVideos) {
            list.addView(buildMxVideoRow(v, resumePrefs, folderVideos));
        }
    }

    // 3. ALL FILES VIEW (All videos across all folders)
    private void renderAllFilesView(String query) {
        ArrayList<Video> allVideos = new ArrayList<>();
        for (Video v : videos) {
            if (!query.isEmpty() && !(v.title.toLowerCase(Locale.ROOT).contains(query) || v.folder.toLowerCase(Locale.ROOT).contains(query))) continue;
            allVideos.add(v);
        }

        sortVideos(allVideos);

        if (allVideos.isEmpty()) {
            emptyMessage(videos.isEmpty() ? "No videos found on device." : "No videos match \"" + query + "\"");
            return;
        }

        SharedPreferences resumePrefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
        for (Video v : allVideos) {
            list.addView(buildMxVideoRow(v, resumePrefs, allVideos));
        }
    }

    private void sortVideos(ArrayList<Video> videoList) {
        switch (videoSortOrder) {
            case 0: Collections.sort(videoList, (a, b) -> Long.compare(b.dateAdded, a.dateAdded)); break;
            case 1: Collections.sort(videoList, (a, b) -> Long.compare(a.dateAdded, b.dateAdded)); break;
            case 2: Collections.sort(videoList, (a, b) -> a.title.compareToIgnoreCase(b.title)); break;
            case 3: Collections.sort(videoList, (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes)); break;
            case 4: Collections.sort(videoList, (a, b) -> Long.compare(b.durationMs, a.durationMs)); break;
        }
    }

    private View buildMxVideoRow(Video v, SharedPreferences resumePrefs, ArrayList<Video> folderPlaylist) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams rlp = lp(-1, -2);
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        // 16:9 Thumbnail Frame with Duration Pill & Progress Bar
        FrameLayout thumbFrame = new FrameLayout(this);
        thumbFrame.setBackground(createRoundDrawable(0xFF1E222D, dp(6)));
        thumbFrame.setClipToOutline(true);

        ImageView thumbImage = new ImageView(this);
        thumbImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbFrame.addView(thumbImage, new FrameLayout.LayoutParams(-1, -1));

        TextView fallback = label("▶", 18, 0xFFA0AEC0, true);
        fallback.setGravity(Gravity.CENTER);
        thumbFrame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

        // Duration Pill (Bottom-Right)
        TextView durationPill = label(v.duration, 10, white, true);
        durationPill.setPadding(dp(6), dp(2), dp(6), dp(2));
        durationPill.setBackground(createRoundDrawable(0xDD000000, dp(4)));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        dlp.setMargins(0, 0, dp(4), dp(4));
        thumbFrame.addView(durationPill, dlp);

        // Watch Progress Bar along bottom edge
        ProgressBar watchBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        watchBar.setMax(1000);
        watchBar.getProgressDrawable().setColorFilter(coral, PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
        thumbFrame.addView(watchBar, plp);

        long resumePos = resumePrefs.getLong("position_" + v.uri.toString(), 0L);
        long resumeDur = resumePrefs.getLong("duration_" + v.uri.toString(), v.durationMs);
        if (resumePos > 3000L && resumeDur > 0L) {
            watchBar.setVisibility(View.VISIBLE);
            watchBar.setProgress((int) Math.min(1000L, resumePos * 1000L / resumeDur));
        } else {
            watchBar.setVisibility(View.GONE);
        }

        loadThumbnailAsync(v.uri, thumbImage, fallback);
        row.addView(thumbFrame, lp(dp(112), dp(66)));

        // Video Title & Details
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(12), 0, dp(4), 0);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView title = label(v.title, 14, white, false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);

        String metaStr = (currentView == VIEW_ALL_FILES ? (v.folder + " • ") : "") + v.size;
        TextView meta = label(metaStr, 11, muted, false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = dp(4);
        info.addView(meta, mlp);

        // Overflow Menu (⋮)
        TextView menu = label("⋮", 20, white, false);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(menu, lp(dp(36), dp(48)));
        menu.setOnClickListener(view -> showVideoMenu(view, v));

        row.setOnClickListener(view -> playWithPlaylist(v, folderPlaylist));
        return row;
    }

    private void loadThumbnailAsync(Uri uri, ImageView image, TextView fallback) {
        String key = uri.toString();
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            image.setImageBitmap(cached);
            image.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
            return;
        }

        image.setImageDrawable(null);
        fallback.setVisibility(View.VISIBLE);
        image.setTag(key);

        thumbExecutor.execute(() -> {
            try {
                Bitmap bmp;
                if (Build.VERSION.SDK_INT >= 29) {
                    bmp = getContentResolver().loadThumbnail(uri, new Size(dp(160), dp(100)), null);
                } else {
                    bmp = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(),
                            Long.parseLong(uri.getLastPathSegment()), MediaStore.Video.Thumbnails.MINI_KIND, null);
                }
                if (bmp != null) {
                    thumbCache.put(key, bmp);
                    runOnUiThread(() -> {
                        if (key.equals(image.getTag())) {
                            image.setImageBitmap(bmp);
                            image.setVisibility(View.VISIBLE);
                            fallback.setVisibility(View.GONE);
                        }
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void playWithPlaylist(Video v, ArrayList<Video> playlistVideos) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(v.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra("title", v.title);
        ArrayList<String> playlist = new ArrayList<>();
        int startIndex = 0;
        for (int index = 0; index < playlistVideos.size(); index++) {
            Video item = playlistVideos.get(index);
            playlist.add(item.uri.toString());
            if (item.uri.equals(v.uri)) startIndex = index;
        }
        i.putStringArrayListExtra("playlist", playlist);
        i.putExtra("start_index", startIndex);
        startActivity(i);
    }

    private void play(Video v) {
        playWithPlaylist(v, videos);
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
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("video/*");
                    share.putExtra(Intent.EXTRA_STREAM, v.uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "Share video"));
                    return true;
                case "Details":
                    new AlertDialog.Builder(this)
                        .setTitle("Video Details")
                        .setMessage("Title: " + v.title + "\nFilename: " + v.filename + "\nFolder: " + v.folder + "\nDuration: " + v.duration + "\nSize: " + v.size)
                        .setPositiveButton("OK", null)
                        .show();
                    return true;
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
        input.setHint("New filename");
        input.setTextColor(white);
        input.setBackground(createRoundDrawable(0xFF151924, dp(12)));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));

        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(20), dp(10), dp(20), 0);
        container.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("Rename Video")
            .setMessage("Keep the file extension (.mp4, .mkv) to preserve video format.")
            .setView(container)
            .setNegativeButton("Cancel", (d, w) -> {})
            .setPositiveButton("Rename", (d, w) -> {
                String filename = input.getText().toString().trim();
                if (filename.isEmpty() || filename.equals(".") || filename.equals("..") ||
                    filename.contains("/") || filename.contains("\\") || filename.indexOf('\0') >= 0) {
                    Toast.makeText(this, "Enter a valid filename", Toast.LENGTH_SHORT).show();
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
                    MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(video.uri)).getIntentSender()).build());
            } else {
                performRename(video, filename);
            }
        } catch (Exception e) {
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
        } catch (Exception e) {
            Toast.makeText(this, "Rename permission was not granted", Toast.LENGTH_SHORT).show();
        } finally {
            pendingRename = null;
            pendingRenameFilename = null;
        }
    }

    private void confirmDelete(Video v) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Video?")
            .setMessage("“" + v.title + "” will be permanently removed from your device.")
            .setNegativeButton("Cancel", (d, w) -> {})
            .setPositiveButton("Delete", (d, w) -> deleteVideo(v))
            .show();
    }

    private void deleteVideo(Video v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                pendingDelete = v;
                deleteRequest.launch(new IntentSenderRequest.Builder(
                    MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(v.uri)).getIntentSender()).build());
            } else {
                int n = getContentResolver().delete(v.uri, null, null);
                if (n > 0) {
                    Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show();
                    loadVideos();
                } else {
                    Toast.makeText(this, "Could not delete this video", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Deletion permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void emptyMessage(String m) {
        if (list == null) return;
        list.removeAllViews();
        LinearLayout emptyCard = new LinearLayout(this);
        emptyCard.setOrientation(LinearLayout.VERTICAL);
        emptyCard.setGravity(Gravity.CENTER);
        emptyCard.setPadding(dp(24), dp(60), dp(24), dp(60));

        TextView icon = label("📁", 38, 0xFFA0AEC0, false);
        icon.setGravity(Gravity.CENTER);
        emptyCard.addView(icon);

        TextView t = label(m, 14, muted, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, 0);
        emptyCard.addView(t);

        list.addView(emptyCard, lp(-1, -2));
    }

    private TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable createRoundDrawable(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable createCircleDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setShape(GradientDrawable.OVAL);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    private String duration(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String.format(Locale.ROOT, "%02d:%02d", (s % 3600) / 60, s % 60);
    }

    private String size(long b) {
        if (b >= 1073741824L) return String.format(Locale.ROOT, "%.1f GB", b / 1073741824d);
        if (b >= 1048576L) return String.format(Locale.ROOT, "%.0f MB", b / 1048576d);
        return Math.max(1, b / 1024) + " KB";
    }
}