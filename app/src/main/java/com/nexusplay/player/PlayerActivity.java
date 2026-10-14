package com.nexusplay.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
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

    private LinearLayout gestureHud;
    private TextView hudIcon;
    private TextView hudMainText;
    private TextView hudSubText;
    private ProgressBar hudProgress;

    private LinearLayout topBar;
    private TextView backButton;
    private TextView titleText;
    private TextView resizeButton;
    private TextView rotateButton;

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
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        initHud(root);
        initTopBar(root, videoTitle);
        setContentView(root);

        initGestures();
        connectPlaybackService(uri);
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
        hudMainText.setTextSize(26);
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
        hudProgress.getProgressDrawable().setColorFilter(0xFF4DE1C1, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(dp(160), dp(6));
        progLp.topMargin = dp(10);
        gestureHud.addView(hudProgress, progLp);

        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(gestureHud, hudParams);
    }

    private void initTopBar(FrameLayout root, String title) {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        topBar.setBackgroundColor(0x99000000);

        backButton = new TextView(this);
        backButton.setText("‹  BACK");
        backButton.setTextColor(Color.WHITE);
        backButton.setTextSize(13);
        backButton.setTypeface(null, Typeface.BOLD);
        backButton.setPadding(dp(14), dp(8), dp(14), dp(8));
        backButton.setBackground(createRoundDrawable(0x55FFFFFF, dp(10)));
        backButton.setOnClickListener(v -> exitPlayer());
        topBar.addView(backButton, new LinearLayout.LayoutParams(-2, -2));

        titleText = new TextView(this);
        titleText.setText(title != null && !title.trim().isEmpty() ? title : "Nexusplay Video");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(14);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleText.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        topBar.addView(titleText, titleLp);

        resizeButton = new TextView(this);
        resizeButton.setText("FIT");
        resizeButton.setTextColor(0xFF4DE1C1);
        resizeButton.setTextSize(12);
        resizeButton.setTypeface(null, Typeface.BOLD);
        resizeButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        resizeButton.setBackground(createRoundDrawable(0x334DE1C1, dp(10)));
        resizeButton.setOnClickListener(v -> cycleResizeMode());
        LinearLayout.LayoutParams resizeLp = new LinearLayout.LayoutParams(-2, -2);
        resizeLp.rightMargin = dp(8);
        topBar.addView(resizeButton, resizeLp);

        rotateButton = new TextView(this);
        rotateButton.setText("⛶ ROTATE");
        rotateButton.setTextColor(Color.WHITE);
        rotateButton.setTextSize(12);
        rotateButton.setTypeface(null, Typeface.BOLD);
        rotateButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        rotateButton.setBackground(createRoundDrawable(0x55FFFFFF, dp(10)));
        rotateButton.setOnClickListener(v -> toggleOrientation());
        topBar.addView(rotateButton, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(topBar, topBarParams);

        ViewCompat.setOnApplyWindowInsetsListener(topBar, (view, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            view.setPadding(dp(16), topInset + dp(8), dp(16), dp(12));
            return insets;
        });

        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (topBar != null) {
                topBar.animate().cancel();
                if (visibility == View.VISIBLE) {
                    topBar.setVisibility(View.VISIBLE);
                    topBar.animate().alpha(1f).setDuration(200).start();
                } else {
                    topBar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        if (topBar != null) topBar.setVisibility(View.GONE);
                    }).start();
                }
            }
        });
    }

    private void initGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
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
                if (controller == null) return false;
                float width = playerView.getWidth();
                if (width <= 0) return false;
                float x = e.getX();
                if (x < width * 0.35f) {
                    long currentPos = controller.getCurrentPosition();
                    long newPos = Math.max(0, currentPos - 10000);
                    controller.seekTo(newPos);
                    showQuickHint("⏪ −10s");
                    return true;
                } else if (x > width * 0.65f) {
                    long currentPos = controller.getCurrentPosition();
                    long duration = controller.getDuration();
                    long newPos = currentPos + 10000;
                    if (duration > 0) newPos = Math.min(duration, newPos);
                    controller.seekTo(newPos);
                    showQuickHint("⏩ +10s");
                    return true;
                } else {
                    cycleResizeMode();
                    return true;
                }
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (playerView.isControllerFullyVisible()) {
                    playerView.hideController();
                } else {
                    playerView.showController();
                }
                return true;
            }
        });

        View gestureSurface = playerView.getVideoSurfaceView();
        if (gestureSurface == null) gestureSurface = playerView;

        gestureSurface.setOnTouchListener((v, event) -> {
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
                        } else if (Math.abs(dy) > dp(16)) {
                            if (startX > playerView.getWidth() / 2f) {
                                gestureMode = GESTURE_VOLUME;
                            } else {
                                gestureMode = GESTURE_BRIGHTNESS;
                            }
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
                        return true;
                    } else if (gestureMode == GESTURE_VOLUME || gestureMode == GESTURE_BRIGHTNESS) {
                        gestureHud.removeCallbacks(hideHudRunnable);
                        gestureHud.postDelayed(hideHudRunnable, 600);
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
            if (resizeButton != null) resizeButton.setText("ZOOM");
            showQuickHint("Crop to Fill");
        } else if (current == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            if (resizeButton != null) resizeButton.setText("FILL");
            showQuickHint("Stretch 100%");
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (resizeButton != null) resizeButton.setText("FIT");
            showQuickHint("Fit Screen");
        }
    }

    private void connectPlaybackService(Uri uri) {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                playerView.setPlayer(controller);

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
        getSharedPreferences("nexusplay_resume", MODE_PRIVATE).edit()
            .putLong("position_" + currentUri.toString(), position)
            .apply();
    }

    @Override
    protected void onDestroy() {
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

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
}
