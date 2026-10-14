package com.nexusplay.player;

import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_SEEK = 1;
    private static final int GESTURE_VOLUME = 2;
    private static final int GESTURE_BRIGHTNESS = 3;

    private PlayerView playerView;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private ScaleGestureDetector scaleDetector;
    private float zoom = 1f;
    private float startX, startY;
    private boolean scaling = false;

    private int gestureMode = GESTURE_NONE;
    private long startSeekPosition = 0L;
    private long targetSeekPosition = 0L;
    private long totalDuration = 0L;
    private int startVolume = 0;
    private int maxVolume = 15;
    private float startBrightness = 0.5f;

    // MX Player Pro UI Components
    private FrameLayout controlsOverlay;
    private LinearLayout topContainer;
    private LinearLayout bottomContainer;

    // Top Bar 1
    private TextView backButton;
    private TextView titleView;
    private TextView hwDecoderBadge;
    private TextView menuButton;

    // Top Bar 2 (Secondary toolbar)
    private TextView sleepTimerBtn;
    private TextView abRepeatBtn;
    private TextView equalizerBtn;
    private TextView speedBtn;
    private TextView audioModeBtn;
    private TextView moreToolsBtn;

    // Audio Player Mode Banner
    private LinearLayout audioPlayerBanner;

    // Bottom Bar
    private TextView currentTimeView;
    private TextView totalTimeView;
    private SeekBar timeSeekBar;
    private boolean isUserSeeking = false;

    // Bottom Controls Row
    private TextView lockBtn;
    private TextView prevBtn;
    private TextView playPauseBtn;
    private TextView nextBtn;
    private TextView aspectBtn;

    // Screen Lock
    private boolean isScreenLocked = false;
    private TextView lockFloatingButton;

    // A-B Repeat
    private long abStartMs = -1L;
    private long abEndMs = -1L;

    // Sleep Timer
    private Handler sleepTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable sleepTimerRunnable = null;

    // Gesture HUD
    private LinearLayout gestureHud;
    private TextView hudIcon;
    private TextView hudMainText;
    private TextView hudSubText;
    private ProgressBar hudProgress;

    // Auto-hide controls handler
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean areControlsVisible = true;
    private final Runnable hideControlsRunnable = () -> setControlsVisibility(false);

    private final Runnable updateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (controller != null && !isUserSeeking) {
                long pos = controller.getCurrentPosition();
                long dur = controller.getDuration();
                currentTimeView.setText(formatTime(pos));
                totalTimeView.setText(formatTime(dur));
                if (dur > 0) {
                    timeSeekBar.setProgress((int) (pos * 1000L / dur));
                }
                playPauseBtn.setText(controller.isPlaying() ? "❚❚" : "▶");

                // Check A-B loop
                if (abStartMs >= 0 && abEndMs > abStartMs && pos >= abEndMs) {
                    controller.seekTo(abStartMs);
                }
            }
            uiHandler.postDelayed(this, 300);
        }
    };

    private final Runnable hideHudRunnable = () -> {
        if (gestureHud != null) {
            gestureHud.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (gestureHud != null) gestureHud.setVisibility(View.GONE);
            }).start();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        String videoTitle = getIntent().getStringExtra("title");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        playerView = new PlayerView(this);
        playerView.setUseController(false); // Using our custom MX Player Pro controls
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        initHud(root);
        initMxPlayerControls(root, videoTitle);
        initLockFloatingButton(root);
        setContentView(root);

        initGestures();
        connectPlaybackService(uri);

        uiHandler.post(updateProgressRunnable);
        scheduleControlsAutoHide();
    }

    private void initHud(FrameLayout root) {
        gestureHud = new LinearLayout(this);
        gestureHud.setOrientation(LinearLayout.VERTICAL);
        gestureHud.setGravity(Gravity.CENTER);
        gestureHud.setBackground(createRoundDrawable(0xDD151925, dp(18)));
        gestureHud.setPadding(dp(28), dp(18), dp(28), dp(18));
        gestureHud.setVisibility(View.GONE);

        hudIcon = new TextView(this);
        hudIcon.setTextSize(26);
        hudIcon.setTextColor(Color.WHITE);
        hudIcon.setGravity(Gravity.CENTER);
        gestureHud.addView(hudIcon, new LinearLayout.LayoutParams(-2, -2));

        hudMainText = new TextView(this);
        hudMainText.setTextSize(28);
        hudMainText.setTextColor(Color.WHITE);
        hudMainText.setTypeface(null, Typeface.BOLD);
        hudMainText.setGravity(Gravity.CENTER);
        gestureHud.addView(hudMainText, new LinearLayout.LayoutParams(-2, -2));

        hudSubText = new TextView(this);
        hudSubText.setTextSize(16);
        hudSubText.setTextColor(0xFF4DE1C1);
        hudSubText.setTypeface(null, Typeface.BOLD);
        hudSubText.setGravity(Gravity.CENTER);
        gestureHud.addView(hudSubText, new LinearLayout.LayoutParams(-2, -2));

        hudProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        hudProgress.setMax(1000);
        hudProgress.getProgressDrawable().setColorFilter(0xFF4DE1C1, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(dp(160), dp(6));
        progLp.topMargin = dp(10);
        gestureHud.addView(hudProgress, progLp);

        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(gestureHud, hudParams);
    }

    private void initLockFloatingButton(FrameLayout root) {
        lockFloatingButton = new TextView(this);
        lockFloatingButton.setText("🔒 TAP TO UNLOCK");
        lockFloatingButton.setTextColor(Color.WHITE);
        lockFloatingButton.setTextSize(13);
        lockFloatingButton.setTypeface(null, Typeface.BOLD);
        lockFloatingButton.setBackground(createRoundDrawable(0xDD121622, dp(24)));
        lockFloatingButton.setPadding(dp(18), dp(12), dp(18), dp(12));
        lockFloatingButton.setGravity(Gravity.CENTER);
        lockFloatingButton.setVisibility(View.GONE);
        lockFloatingButton.setOnClickListener(v -> unlockScreen());

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        flp.leftMargin = dp(24);
        root.addView(lockFloatingButton, flp);
    }

    private void initMxPlayerControls(FrameLayout root, String title) {
        controlsOverlay = new FrameLayout(this);

        // 1. TOP CONTAINER WITH GRADIENT
        topContainer = new LinearLayout(this);
        topContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable topGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEE000000, 0xAA000000, 0x44000000, Color.TRANSPARENT});
        topContainer.setBackground(topGradient);
        topContainer.setPadding(dp(16), dp(10), dp(16), dp(20));

        // Top Row 1: Back + Title + HW + Menu
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        backButton = new TextView(this);
        backButton.setText("←");
        backButton.setTextColor(Color.WHITE);
        backButton.setTextSize(24);
        backButton.setPadding(0, 0, dp(14), 0);
        backButton.setOnClickListener(v -> exitPlayer());
        row1.addView(backButton, new LinearLayout.LayoutParams(-2, -2));

        titleView = new TextView(this);
        titleView.setText(title != null && !title.trim().isEmpty() ? title : "Video Playback");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(0, 0, dp(10), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        row1.addView(titleView, tlp);

        hwDecoderBadge = new TextView(this);
        android.content.SharedPreferences resumePrefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
        boolean isSw = resumePrefs.getBoolean("prefer_sw_decoder", false) || isEmulatorDevice();
        hwDecoderBadge.setText(isSw ? "SW" : "HW");
        hwDecoderBadge.setTextColor(isSw ? 0xFF9B8CFF : Color.WHITE);
        hwDecoderBadge.setTextSize(13);
        hwDecoderBadge.setTypeface(null, Typeface.BOLD);
        hwDecoderBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        hwDecoderBadge.setOnClickListener(v -> toggleDecoderMode());
        row1.addView(hwDecoderBadge, new LinearLayout.LayoutParams(-2, -2));

        menuButton = new TextView(this);
        menuButton.setText("⋮");
        menuButton.setTextColor(Color.WHITE);
        menuButton.setTextSize(24);
        menuButton.setPadding(dp(16), 0, dp(4), 0);
        menuButton.setOnClickListener(this::showOverflowMenu);
        row1.addView(menuButton, new LinearLayout.LayoutParams(-2, -2));

        topContainer.addView(row1, lp(-1, -2));

        // Top Row 2: Secondary Toolbar (Timer, A-B, Equalizer, 1X, Headphones, Aspect)
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(-1, -2);
        r2Lp.topMargin = dp(14);
        row2.setLayoutParams(r2Lp);

        sleepTimerBtn = createToolButton("⏱", 20, v -> showSleepTimerDialog());
        row2.addView(sleepTimerBtn);

        abRepeatBtn = createToolButton("A⇄B", 13, v -> toggleAbRepeat());
        row2.addView(abRepeatBtn);

        equalizerBtn = createToolButton("🎚", 18, v -> showAudioTracksDialog());
        row2.addView(equalizerBtn);

        speedBtn = createToolButton("1X", 13, v -> showSpeedDialog());
        row2.addView(speedBtn);

        // Headphone audio mode icon button
        audioModeBtn = new TextView(this);
        audioModeBtn.setText("🎧");
        audioModeBtn.setTextSize(18);
        audioModeBtn.setTextColor(Color.WHITE);
        audioModeBtn.setGravity(Gravity.CENTER);
        audioModeBtn.setBackground(createRoundDrawable(0x553B82F6, dp(22)));
        LinearLayout.LayoutParams amLp = lp(dp(44), dp(44));
        amLp.rightMargin = dp(14);
        audioModeBtn.setLayoutParams(amLp);
        audioModeBtn.setOnClickListener(v -> toggleAudioModeBanner());
        row2.addView(audioModeBtn);

        moreToolsBtn = createToolButton(">", 18, v -> cycleResizeMode());
        row2.addView(moreToolsBtn);

        topContainer.addView(row2);

        // Audio Player mode notification banner (MX Player Pro style blue banner)
        audioPlayerBanner = new LinearLayout(this);
        audioPlayerBanner.setOrientation(LinearLayout.HORIZONTAL);
        audioPlayerBanner.setGravity(Gravity.CENTER_VERTICAL);
        audioPlayerBanner.setBackground(createRoundDrawable(0xFF2563EB, dp(20)));
        audioPlayerBanner.setPadding(dp(16), dp(8), dp(16), dp(8));
        audioPlayerBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(-2, -2);
        abLp.topMargin = dp(12);
        audioPlayerBanner.setLayoutParams(abLp);

        TextView bannerText = new TextView(this);
        bannerText.setText("Switch to New Audio Player");
        bannerText.setTextColor(Color.WHITE);
        bannerText.setTextSize(13);
        bannerText.setTypeface(null, Typeface.BOLD);
        audioPlayerBanner.addView(bannerText);

        TextView bannerClose = new TextView(this);
        bannerClose.setText("   ✕");
        bannerClose.setTextColor(Color.WHITE);
        bannerClose.setTextSize(13);
        bannerClose.setOnClickListener(v -> audioPlayerBanner.setVisibility(View.GONE));
        audioPlayerBanner.addView(bannerClose);

        audioPlayerBanner.setOnClickListener(v -> {
            enterPipMode();
            audioPlayerBanner.setVisibility(View.GONE);
        });

        topContainer.addView(audioPlayerBanner);

        controlsOverlay.addView(topContainer, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // 2. BOTTOM CONTAINER WITH GRADIENT
        bottomContainer = new LinearLayout(this);
        bottomContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bottomGradient = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xEE000000, 0xAA000000, 0x44000000, Color.TRANSPARENT});
        bottomContainer.setBackground(bottomGradient);
        bottomContainer.setPadding(dp(18), dp(24), dp(18), dp(16));

        // Bottom Row 1: Time Row (Current Time + Timeline SeekBar + Total Duration)
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);

        currentTimeView = new TextView(this);
        currentTimeView.setText("00:00");
        currentTimeView.setTextColor(Color.WHITE);
        currentTimeView.setTextSize(13);
        currentTimeView.setTypeface(null, Typeface.BOLD);
        timeRow.addView(currentTimeView, new LinearLayout.LayoutParams(-2, -2));

        timeSeekBar = new SeekBar(this);
        timeSeekBar.setMax(1000);
        timeSeekBar.setProgress(0);
        timeSeekBar.getProgressDrawable().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        timeSeekBar.getThumb().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, -2, 1f);
        sbLp.leftMargin = dp(8);
        sbLp.rightMargin = dp(8);
        timeRow.addView(timeSeekBar, sbLp);

        timeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && controller != null) {
                    long dur = controller.getDuration();
                    if (dur > 0) {
                        long target = progress * dur / 1000L;
                        currentTimeView.setText(formatTime(target));
                        showSeekHud(target, (target - controller.getCurrentPosition()) / 1000L, dur);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
                cancelControlsAutoHide();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (controller != null) {
                    long dur = controller.getDuration();
                    if (dur > 0) {
                        controller.seekTo(seekBar.getProgress() * dur / 1000L);
                    }
                }
                isUserSeeking = false;
                scheduleControlsAutoHide();
            }
        });

        totalTimeView = new TextView(this);
        totalTimeView.setText("00:00");
        totalTimeView.setTextColor(Color.WHITE);
        totalTimeView.setTextSize(13);
        totalTimeView.setTypeface(null, Typeface.BOLD);
        timeRow.addView(totalTimeView, new LinearLayout.LayoutParams(-2, -2));

        bottomContainer.addView(timeRow, lp(-1, -2));

        // Bottom Row 2: Control Buttons Row (Lock, Previous, Play/Pause, Next, Fullscreen/Aspect)
        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(-1, -2);
        crLp.topMargin = dp(16);
        controlsRow.setLayoutParams(crLp);

        lockBtn = createBottomControlBtn("🔒", 22, v -> lockScreen());
        controlsRow.addView(lockBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        prevBtn = createBottomControlBtn("|◀", 20, v -> seekRelative(-10000));
        controlsRow.addView(prevBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        playPauseBtn = createBottomControlBtn("❚❚", 28, v -> togglePlayPause());
        controlsRow.addView(playPauseBtn, new LinearLayout.LayoutParams(0, dp(48), 1.2f));

        nextBtn = createBottomControlBtn("▶|", 20, v -> seekRelative(10000));
        controlsRow.addView(nextBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        aspectBtn = createBottomControlBtn("⛶", 24, v -> toggleOrientation());
        controlsRow.addView(aspectBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        bottomContainer.addView(controlsRow, lp(-1, -2));

        controlsOverlay.addView(bottomContainer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        root.addView(controlsOverlay, new FrameLayout.LayoutParams(-1, -1));

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            topContainer.setPadding(dp(16), topInset + dp(8), dp(16), dp(16));
            bottomContainer.setPadding(dp(18), dp(16), dp(18), bottomInset + dp(12));
            return insets;
        });
    }

    private TextView createToolButton(String text, float size, View.OnClickListener onClick) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(size);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(16);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private TextView createBottomControlBtn(String text, float size, View.OnClickListener onClick) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(size);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private void toggleAudioModeBanner() {
        if (audioPlayerBanner.getVisibility() == View.VISIBLE) {
            audioPlayerBanner.setVisibility(View.GONE);
        } else {
            audioPlayerBanner.setVisibility(View.VISIBLE);
        }
    }

    private boolean isEmulatorDevice() {
        String fingerprint = Build.FINGERPRINT;
        String model = Build.MODEL;
        String hardware = Build.HARDWARE;
        String product = Build.PRODUCT;
        return (fingerprint != null && (fingerprint.startsWith("generic") || fingerprint.startsWith("unknown")))
                || (model != null && (model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for x86")))
                || (hardware != null && (hardware.contains("goldfish") || hardware.contains("ranchu")))
                || (product != null && (product.contains("sdk") || product.contains("google_sdk") || product.contains("emulator")));
    }

    private void toggleDecoderMode() {
        android.content.SharedPreferences prefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
        boolean currentSw = "SW".equals(hwDecoderBadge.getText().toString());
        boolean newSw = !currentSw;
        prefs.edit().putBoolean("prefer_sw_decoder", newSw).apply();

        hwDecoderBadge.setText(newSw ? "SW" : "HW");
        hwDecoderBadge.setTextColor(newSw ? 0xFF9B8CFF : Color.WHITE);
        showQuickHint(newSw ? "Software Decoder (SW)" : "Hardware Decoder (HW)");
    }

    private void toggleAbRepeat() {
        if (controller == null) return;
        long currentPos = controller.getCurrentPosition();
        if (abStartMs < 0) {
            abStartMs = currentPos;
            abRepeatBtn.setTextColor(0xFF4DE1C1);
            showQuickHint("Point A set at " + formatTime(abStartMs));
        } else if (abEndMs < 0) {
            if (currentPos > abStartMs) {
                abEndMs = currentPos;
                showQuickHint("Point B set: Looping A-B (" + formatTime(abStartMs) + " - " + formatTime(abEndMs) + ")");
            } else {
                showQuickHint("Point B must be after Point A");
            }
        } else {
            abStartMs = -1L;
            abEndMs = -1L;
            abRepeatBtn.setTextColor(Color.WHITE);
            showQuickHint("A-B Loop Cleared");
        }
    }

    private void showSleepTimerDialog() {
        String[] options = {"15 minutes", "30 minutes", "45 minutes", "60 minutes", "Turn off timer"};
        int[] minutes = {15, 30, 45, 60, 0};

        new AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setItems(options, (dialog, which) -> {
                int min = minutes[which];
                if (sleepTimerRunnable != null) {
                    sleepTimerHandler.removeCallbacks(sleepTimerRunnable);
                    sleepTimerRunnable = null;
                }
                if (min > 0) {
                    sleepTimerRunnable = () -> {
                        if (controller != null) controller.pause();
                        showQuickHint("Playback paused by Sleep Timer");
                    };
                    sleepTimerHandler.postDelayed(sleepTimerRunnable, min * 60 * 1000L);
                    sleepTimerBtn.setTextColor(0xFF4DE1C1);
                    showQuickHint("Sleep Timer set for " + min + " mins");
                } else {
                    sleepTimerBtn.setTextColor(Color.WHITE);
                    showQuickHint("Sleep Timer turned off");
                }
            })
            .show();
    }

    private void showOverflowMenu(View v) {
        PopupMenu menu = new PopupMenu(this, v);
        menu.getMenu().add("Subtitles (Captions)");
        menu.getMenu().add("Audio Tracks");
        menu.getMenu().add("Picture-in-Picture (PiP)");
        menu.getMenu().add("Aspect Ratio Modes");
        menu.getMenu().add("Video Details");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "Subtitles (Captions)": showSubtitleDialog(); return true;
                case "Audio Tracks": showAudioTracksDialog(); return true;
                case "Picture-in-Picture (PiP)": enterPipMode(); return true;
                case "Aspect Ratio Modes": cycleResizeMode(); return true;
                case "Video Details":
                    if (controller != null) {
                        Toast.makeText(this, titleView.getText() + "\nDuration: " + formatTime(controller.getDuration()), Toast.LENGTH_LONG).show();
                    }
                    return true;
            }
            return false;
        });
        menu.show();
    }

    private void togglePlayPause() {
        if (controller == null) return;
        if (controller.isPlaying()) {
            controller.pause();
            playPauseBtn.setText("▶");
            cancelControlsAutoHide();
        } else {
            controller.play();
            playPauseBtn.setText("❚❚");
            scheduleControlsAutoHide();
        }
    }

    private void seekRelative(long deltaMs) {
        if (controller == null) return;
        long newPos = Math.max(0, Math.min(controller.getDuration(), controller.getCurrentPosition() + deltaMs));
        controller.seekTo(newPos);
        showQuickHint(deltaMs > 0 ? "⏩ +10s" : "⏪ −10s");
        scheduleControlsAutoHide();
    }

    private void setControlsVisibility(boolean visible) {
        if (isScreenLocked) {
            controlsOverlay.setVisibility(View.GONE);
            return;
        }
        areControlsVisible = visible;
        controlsOverlay.animate().cancel();
        if (visible) {
            controlsOverlay.setVisibility(View.VISIBLE);
            controlsOverlay.animate().alpha(1f).setDuration(200).start();
            scheduleControlsAutoHide();
        } else {
            controlsOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                controlsOverlay.setVisibility(View.GONE);
            }).start();
        }
    }

    private void scheduleControlsAutoHide() {
        cancelControlsAutoHide();
        uiHandler.postDelayed(hideControlsRunnable, 4000);
    }

    private void cancelControlsAutoHide() {
        uiHandler.removeCallbacks(hideControlsRunnable);
    }

    private void lockScreen() {
        isScreenLocked = true;
        setControlsVisibility(false);
        if (lockFloatingButton != null) {
            lockFloatingButton.setVisibility(View.VISIBLE);
            lockFloatingButton.setAlpha(1f);
        }
        showQuickHint("Screen Locked 🔒");
    }

    private void unlockScreen() {
        isScreenLocked = false;
        if (lockFloatingButton != null) {
            lockFloatingButton.setVisibility(View.GONE);
        }
        setControlsVisibility(true);
        showQuickHint("Screen Unlocked 🔓");
    }

    private void showSpeedDialog() {
        String[] speeds = {"0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "1.75x", "2.0x"};
        float[] speedValues = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        int currentIdx = 2;
        if (controller != null) {
            float cur = controller.getPlaybackParameters().speed;
            for (int i = 0; i < speedValues.length; i++) {
                if (Math.abs(cur - speedValues[i]) < 0.05f) {
                    currentIdx = i;
                    break;
                }
            }
        }
        new AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, currentIdx, (dialog, which) -> {
                if (controller != null) {
                    float chosen = speedValues[which];
                    controller.setPlaybackSpeed(chosen);
                    speedBtn.setText(chosen == 1.0f ? "1X" : chosen + "X");
                    showQuickHint("Speed: " + speeds[which]);
                }
                dialog.dismiss();
            })
            .show();
    }

    private void showAudioTracksDialog() {
        if (controller == null) return;
        Tracks tracks = controller.getCurrentTracks();
        ArrayList<Tracks.Group> audioGroups = new ArrayList<>();
        ArrayList<Integer> trackIndices = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        int selectedItem = -1;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    audioGroups.add(group);
                    trackIndices.add(i);
                    String lang = format.language != null ? format.language.toUpperCase(Locale.ROOT) : "Audio " + (names.size() + 1);
                    String label = format.label != null ? format.label : lang;
                    if (group.isTrackSelected(i)) selectedItem = names.size();
                    String details = format.sampleRate > 0 ? " (" + (format.sampleRate / 1000) + " kHz)" : "";
                    names.add(label + details);
                }
            }
        }

        if (names.isEmpty()) {
            Toast.makeText(this, "No additional audio tracks found", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Select Audio Track")
            .setSingleChoiceItems(names.toArray(new String[0]), selectedItem, (dialog, which) -> {
                Tracks.Group chosenGroup = audioGroups.get(which);
                int trackIdx = trackIndices.get(which);
                controller.setTrackSelectionParameters(
                    controller.getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(chosenGroup.getMediaTrackGroup(), trackIdx))
                        .build()
                );
                showQuickHint("Audio: " + names.get(which));
                dialog.dismiss();
            })
            .show();
    }

    private void showSubtitleDialog() {
        if (controller == null) return;
        Tracks tracks = controller.getCurrentTracks();
        ArrayList<Tracks.Group> textGroups = new ArrayList<>();
        ArrayList<Integer> trackIndices = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        names.add("Off (No Subtitles)");
        int selectedItem = 0;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    textGroups.add(group);
                    trackIndices.add(i);
                    String lang = format.language != null ? format.language.toUpperCase(Locale.ROOT) : "Subtitle " + names.size();
                    String label = format.label != null ? format.label : lang;
                    if (group.isTrackSelected(i)) selectedItem = names.size();
                    names.add(label);
                }
            }
        }

        new AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(names.toArray(new String[0]), selectedItem, (dialog, which) -> {
                if (which == 0) {
                    controller.setTrackSelectionParameters(
                        controller.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    );
                    showQuickHint("Subtitles Off");
                } else {
                    Tracks.Group chosen = textGroups.get(which - 1);
                    int trackIdx = trackIndices.get(which - 1);
                    controller.setTrackSelectionParameters(
                        controller.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(chosen.getMediaTrackGroup(), trackIdx))
                            .build()
                    );
                    showQuickHint("Subtitle: " + names.get(which));
                }
                dialog.dismiss();
            })
            .show();
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                if (controller != null && controller.getVideoSize().width > 0 && controller.getVideoSize().height > 0) {
                    int w = controller.getVideoSize().width;
                    int h = controller.getVideoSize().height;
                    float ratio = (float) w / h;
                    if (ratio >= 0.41841f && ratio <= 2.39f) {
                        pipBuilder.setAspectRatio(new Rational(w, h));
                    }
                }
                enterPictureInPictureMode(pipBuilder.build());
            } catch (Exception e) {
                Toast.makeText(this, "Picture-in-Picture unavailable", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (controller != null && controller.isPlaying()) {
            enterPipMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            setControlsVisibility(false);
            if (lockFloatingButton != null) lockFloatingButton.setVisibility(View.GONE);
            if (gestureHud != null) gestureHud.setVisibility(View.GONE);
        } else {
            setControlsVisibility(true);
        }
    }

    private void initGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { if (isScreenLocked) return false; scaling = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (isScreenLocked) return false;
                zoom = Math.max(1f, Math.min(3f, zoom * d.getScaleFactor()));
                playerView.setResizeMode(zoom > 1.03f ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
                showQuickHint("Zoom " + Math.round(zoom * 100) + "%");
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; }
        });

        GestureDetector doubleTapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isScreenLocked || controller == null) return false;
                float width = playerView.getWidth();
                if (width <= 0) return false;
                float x = e.getX();
                if (x < width * 0.35f) {
                    seekRelative(-10000);
                    return true;
                } else if (x > width * 0.65f) {
                    seekRelative(10000);
                    return true;
                } else {
                    togglePlayPause();
                    return true;
                }
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isScreenLocked) {
                    if (lockFloatingButton != null) {
                        lockFloatingButton.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction(() -> {
                            lockFloatingButton.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        }).start();
                    }
                    showQuickHint("Screen Locked 🔒");
                    return true;
                }
                setControlsVisibility(!areControlsVisible);
                return true;
            }
        });

        View gestureSurface = playerView.getVideoSurfaceView();
        if (gestureSurface == null) gestureSurface = playerView;

        gestureSurface.setOnTouchListener((v, event) -> {
            if (isScreenLocked) {
                doubleTapDetector.onTouchEvent(event);
                return true;
            }

            doubleTapDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1 || scaling) return true;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    gestureMode = GESTURE_NONE;
                    startSeekPosition = controller != null ? controller.getCurrentPosition() : 0L;
                    totalDuration = controller != null ? controller.getDuration() : 0L;
                    targetSeekPosition = startSeekPosition;

                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    startVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    startBrightness = getWindow().getAttributes().screenBrightness;
                    if (startBrightness < 0) startBrightness = 0.5f;
                    break;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;

                    if (gestureMode == GESTURE_NONE) {
                        if (Math.abs(dx) > dp(16) && Math.abs(dx) > Math.abs(dy)) {
                            gestureMode = GESTURE_SEEK;
                            cancelControlsAutoHide();
                        } else if (Math.abs(dy) > dp(16)) {
                            if (startX > playerView.getWidth() / 2f) {
                                gestureMode = GESTURE_VOLUME;
                            } else {
                                gestureMode = GESTURE_BRIGHTNESS;
                            }
                            cancelControlsAutoHide();
                        }
                    }

                    if (gestureMode == GESTURE_SEEK) {
                        float screenW = Math.max(1, playerView.getWidth());
                        long span = (totalDuration > 0 && totalDuration < 180_000L) ? totalDuration : 90_000L;
                        long deltaMs = (long) ((dx / screenW) * span);
                        long target = startSeekPosition + deltaMs;
                        if (totalDuration > 0) {
                            target = Math.max(0L, Math.min(totalDuration, target));
                        } else {
                            target = Math.max(0L, target);
                        }
                        targetSeekPosition = target;
                        long diffSec = (targetSeekPosition - startSeekPosition) / 1000L;
                        showSeekHud(targetSeekPosition, diffSec, totalDuration);
                        return true;
                    } else if (gestureMode == GESTURE_VOLUME) {
                        float delta = -dy / Math.max(1f, playerView.getHeight()) * 1.5f;
                        int targetVol = Math.max(0, Math.min(maxVolume, startVolume + Math.round(delta * maxVolume)));
                        AudioManager amVol = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        amVol.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                        int percent = Math.round(targetVol * 100f / maxVolume);
                        showVolumeHud(percent);
                        return true;
                    } else if (gestureMode == GESTURE_BRIGHTNESS) {
                        float delta = -dy / Math.max(1f, playerView.getHeight()) * 1.5f;
                        float targetBrightness = Math.max(0.05f, Math.min(1.0f, startBrightness + delta));
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = targetBrightness;
                        getWindow().setAttributes(lp);
                        int percent = Math.round(targetBrightness * 100f);
                        showBrightnessHud(percent);
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (gestureMode == GESTURE_SEEK) {
                        if (controller != null) {
                            controller.seekTo(targetSeekPosition);
                        }
                        gestureHud.removeCallbacks(hideHudRunnable);
                        gestureHud.postDelayed(hideHudRunnable, 600);
                        scheduleControlsAutoHide();
                        return true;
                    } else if (gestureMode == GESTURE_VOLUME || gestureMode == GESTURE_BRIGHTNESS) {
                        gestureHud.removeCallbacks(hideHudRunnable);
                        gestureHud.postDelayed(hideHudRunnable, 600);
                        scheduleControlsAutoHide();
                        return true;
                    }
                    break;
            }
            return true;
        });
    }

    private void showSeekHud(long targetMs, long diffSec, long duration) {
        if (gestureHud == null) return;
        gestureHud.removeCallbacks(hideHudRunnable);
        gestureHud.animate().cancel();
        gestureHud.setAlpha(1f);
        gestureHud.setVisibility(View.VISIBLE);

        hudIcon.setText(diffSec >= 0 ? "⏩" : "⏪");
        hudIcon.setVisibility(View.VISIBLE);

        hudMainText.setText(formatTime(targetMs));
        hudMainText.setTextSize(28);
        hudMainText.setVisibility(View.VISIBLE);

        String sign = diffSec >= 0 ? "[+" : "[-";
        hudSubText.setText(sign + formatDelta(diffSec) + "]");
        hudSubText.setTextColor(diffSec >= 0 ? 0xFF4DE1C1 : 0xFFFF6B6B);
        hudSubText.setVisibility(View.VISIBLE);

        if (duration > 0) {
            hudProgress.setVisibility(View.VISIBLE);
            hudProgress.setProgress((int) (targetMs * 1000L / duration));
        } else {
            hudProgress.setVisibility(View.GONE);
        }
    }

    private void showVolumeHud(int percent) {
        if (gestureHud == null) return;
        gestureHud.removeCallbacks(hideHudRunnable);
        gestureHud.animate().cancel();
        gestureHud.setAlpha(1f);
        gestureHud.setVisibility(View.VISIBLE);

        hudIcon.setText(percent == 0 ? "🔇" : "🔊");
        hudIcon.setVisibility(View.VISIBLE);

        hudMainText.setText("Volume " + percent + "%");
        hudMainText.setTextSize(22);
        hudMainText.setVisibility(View.VISIBLE);

        hudSubText.setVisibility(View.GONE);
        hudProgress.setVisibility(View.VISIBLE);
        hudProgress.setProgress(percent * 10);
    }

    private void showBrightnessHud(int percent) {
        if (gestureHud == null) return;
        gestureHud.removeCallbacks(hideHudRunnable);
        gestureHud.animate().cancel();
        gestureHud.setAlpha(1f);
        gestureHud.setVisibility(View.VISIBLE);

        hudIcon.setText("☀️");
        hudIcon.setVisibility(View.VISIBLE);

        hudMainText.setText("Brightness " + percent + "%");
        hudMainText.setTextSize(22);
        hudMainText.setVisibility(View.VISIBLE);

        hudSubText.setVisibility(View.GONE);
        hudProgress.setVisibility(View.VISIBLE);
        hudProgress.setProgress(percent * 10);
    }

    private void showQuickHint(String text) {
        if (gestureHud == null) return;
        gestureHud.removeCallbacks(hideHudRunnable);
        gestureHud.animate().cancel();
        gestureHud.setAlpha(1f);
        gestureHud.setVisibility(View.VISIBLE);

        hudIcon.setVisibility(View.GONE);
        hudMainText.setText(text);
        hudMainText.setTextSize(18);
        hudMainText.setVisibility(View.VISIBLE);

        hudSubText.setVisibility(View.GONE);
        hudProgress.setVisibility(View.GONE);

        gestureHud.postDelayed(hideHudRunnable, 900);
    }

    private void toggleOrientation() {
        int currentOrientation = getResources().getConfiguration().orientation;
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            showQuickHint("Portrait Mode");
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            showQuickHint("Landscape Mode");
        }
    }

    private void cycleResizeMode() {
        int current = playerView.getResizeMode();
        if (current == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            showQuickHint("Crop to Fill (Zoom)");
        } else if (current == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            showQuickHint("Stretch 100%");
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            showQuickHint("Fit Screen (Original)");
        }
    }

    private void connectPlaybackService(Uri uri) {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                playerView.setPlayer(controller);
                controller.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (controller != null) {
                            playPauseBtn.setText(controller.isPlaying() ? "❚❚" : "▶");
                        }
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        playPauseBtn.setText(isPlaying ? "❚❚" : "▶");
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        try {
                            android.content.SharedPreferences prefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
                            if (!prefs.getBoolean("prefer_sw_decoder", false)) {
                                prefs.edit().putBoolean("prefer_sw_decoder", true).apply();
                                if (hwDecoderBadge != null) {
                                    hwDecoderBadge.setText("SW");
                                    hwDecoderBadge.setTextColor(0xFF9B8CFF);
                                }
                                if (controller != null) {
                                    controller.prepare();
                                    controller.play();
                                }
                                showQuickHint("Switched to Software Decoder (SW)");
                            }
                        } catch (Exception ignored) {}
                    }
                });

                if (controller.getMediaItemCount() == 0 || !uri.equals(controller.getCurrentMediaItem() == null ? null : controller.getCurrentMediaItem().localConfiguration == null ? null : controller.getCurrentMediaItem().localConfiguration.uri)) {
                    ArrayList<String> playlist = getIntent().getStringArrayListExtra("playlist");
                    int startIndex = getIntent().getIntExtra("start_index", 0);
                    ArrayList<MediaItem> items = new ArrayList<>();
                    int selectedIndex = -1;
                    if (playlist != null) {
                        for (int i = 0; i < playlist.size(); i++) {
                            Uri itemUri = Uri.parse(playlist.get(i));
                            items.add(MediaItem.fromUri(itemUri));
                            if (uri.equals(itemUri)) selectedIndex = i;
                        }
                    }
                    if (items.isEmpty()) {
                        items.add(MediaItem.fromUri(uri));
                        selectedIndex = 0;
                    } else if (selectedIndex < 0) {
                        selectedIndex = Math.max(0, Math.min(startIndex, items.size() - 1));
                    }
                    android.content.SharedPreferences resumePrefs = getSharedPreferences("nexusplay_resume", MODE_PRIVATE);
                    String resumeKey = "position_" + uri.toString();
                    long resumePosition = Math.max(0L, resumePrefs.getLong(resumeKey, 0L));
                    controller.setMediaItems(items, selectedIndex, resumePosition);
                    controller.prepare();
                    controller.play();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Unable to start playback", Toast.LENGTH_LONG).show();
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }

    private void exitPlayer() {
        if (controller != null) {
            controller.stop();
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (isScreenLocked) {
            showQuickHint("Screen Locked 🔒");
            return;
        }
        exitPlayer();
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        saveResumePosition();
        super.onStop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (isFinishing() && controller != null) {
            controller.stop();
        }
    }

    private void saveResumePosition() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        Uri currentUri = controller.getCurrentMediaItem().localConfiguration == null
            ? null : controller.getCurrentMediaItem().localConfiguration.uri;
        if (currentUri == null) return;
        long position = Math.max(0L, controller.getCurrentPosition());
        long duration = Math.max(0L, controller.getDuration());
        getSharedPreferences("nexusplay_resume", MODE_PRIVATE).edit()
            .putLong("position_" + currentUri.toString(), position)
            .putLong("duration_" + currentUri.toString(), duration)
            .apply();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(updateProgressRunnable);
        uiHandler.removeCallbacks(hideControlsRunnable);
        if (sleepTimerRunnable != null) sleepTimerHandler.removeCallbacks(sleepTimerRunnable);
        if (isFinishing() && controller != null) {
            controller.stop();
        }
        if (playerView != null) playerView.setPlayer(null);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        super.onDestroy();
    }

    private String formatTime(long ms) {
        long s = Math.max(0, ms / 1000);
        long hours = s / 3600;
        long minutes = (s % 3600) / 60;
        long seconds = s % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
        }
    }

    private String formatDelta(long sec) {
        long s = Math.abs(sec);
        long m = s / 60;
        s = s % 60;
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    private GradientDrawable createRoundDrawable(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
}
