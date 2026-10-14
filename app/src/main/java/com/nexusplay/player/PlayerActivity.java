package com.nexusplay.player;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.google.common.util.concurrent.ListenableFuture;

public class PlayerActivity extends AppCompatActivity {
    private PlayerView playerView;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private ScaleGestureDetector scaleDetector;
    private float zoom = 1f, startX, startY;
    private boolean scaling = false;
    private TextView gestureHint;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000); getWindow().setNavigationBarColor(0xFF000000);
        Uri uri = getIntent().getData(); if (uri == null) { finish(); return; }
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        playerView = new PlayerView(this); playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(-1,-1));
        TextView back = new TextView(this); back.setText("‹  BACK"); back.setTextColor(0xFFFFFFFF); back.setTextSize(13);
        back.setPadding(dp(24),dp(14),dp(20),dp(14));
        back.setMinWidth(dp(84));
        back.setGravity(Gravity.CENTER); back.setBackgroundColor(0x66000000);
        root.addView(back,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START)); back.setOnClickListener(v->finish());
        gestureHint = new TextView(this); gestureHint.setTextColor(-1); gestureHint.setTextSize(14);
        gestureHint.setPadding(18,12,18,12); gestureHint.setBackgroundColor(0x99000000); gestureHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER); root.addView(gestureHint,hp);
        setContentView(root);

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling=true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(3f, zoom * d.getScaleFactor()));
                playerView.setResizeMode(zoom > 1.03f ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
                showHint("Zoom " + Math.round(zoom*100) + "%"); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scaling=false; }
        });
        View gestureSurface = playerView.getVideoSurfaceView();
        if (gestureSurface == null) gestureSurface = playerView;
        gestureSurface.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1 || scaling) return true;
            if (event.getAction() == MotionEvent.ACTION_DOWN) { startX=event.getX(); startY=event.getY(); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx=event.getX()-startX, dy=event.getY()-startY;
                if (Math.abs(dx)>dp(65) && Math.abs(dx)>Math.abs(dy)) {
                    if (controller != null) {
                        long jump = dx > 0 ? 10000 : -10000;
                        controller.seekTo(Math.max(0, controller.getCurrentPosition()+jump));
                        showHint(dx>0 ? "+10 seconds" : "−10 seconds");
                    }
                } else if (Math.abs(dy)>dp(55)) {
                    float delta = -dy / Math.max(1f, playerView.getHeight()) * 1.5f;
                    if (startX > playerView.getWidth()/2f) adjustVolume(delta); else adjustBrightness(delta);
                } else {
                    playerView.performClick();
                }
                return true;
            }
            return true;
        });
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get(); playerView.setPlayer(controller);
                if (controller.getMediaItemCount()==0 || !uri.equals(controller.getCurrentMediaItem()==null ? null : controller.getCurrentMediaItem().localConfiguration.uri)) {
                    java.util.ArrayList<String> playlist = getIntent().getStringArrayListExtra("playlist");
                    int startIndex = getIntent().getIntExtra("start_index", 0);
                    java.util.ArrayList<MediaItem> items = new java.util.ArrayList<>();
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
            } catch (Exception e) { Toast.makeText(this,"Unable to start playback",Toast.LENGTH_LONG).show(); }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }

    private void adjustVolume(float delta) {
        AudioManager am=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), current=am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int value=Math.max(0,Math.min(max,current+Math.round(delta*max)));
        am.setStreamVolume(AudioManager.STREAM_MUSIC,value,0); showHint("Volume " + Math.round(value*100f/max) + "%");
    }
    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams p=getWindow().getAttributes();
        float current=p.screenBrightness<0 ? 0.5f : p.screenBrightness;
        p.screenBrightness=Math.max(0.05f,Math.min(1f,current+delta)); getWindow().setAttributes(p);
        showHint("Brightness " + Math.round(p.screenBrightness*100) + "%");
    }
    private void showHint(String text) { gestureHint.setText(text); gestureHint.setVisibility(View.VISIBLE); gestureHint.removeCallbacks(hideHint); gestureHint.postDelayed(hideHint,1000); }
    private final Runnable hideHint=()->{if(gestureHint!=null)gestureHint.setVisibility(View.GONE);};
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onStop() {
        saveResumePosition();
        super.onStop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
    @Override protected void onDestroy() {
        if (playerView!=null) playerView.setPlayer(null);
        if (controllerFuture!=null) MediaController.releaseFuture(controllerFuture);
        controller=null; super.onDestroy();
    }
}
