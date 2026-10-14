package com.nexusplay.player;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.SeekParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession mediaSession;
    @Override public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();
        player.setSeekParameters(SeekParameters.EXACT);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession = new MediaSession.Builder(this, player).setSessionActivity(pending).build();
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return mediaSession; }
    @Override public void onDestroy() {
        if (mediaSession != null) { mediaSession.getPlayer().release(); mediaSession.release(); mediaSession = null; }
        player = null;
        super.onDestroy();
    }
}