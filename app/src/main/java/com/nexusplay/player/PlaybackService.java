package com.nexusplay.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class PlaybackService extends MediaSessionService {
    public static final String ACTION_STOP = "com.nexusplay.player.ACTION_STOP";
    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();
        player.setSeekParameters(SeekParameters.EXACT);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        CommandButton stopButton = new CommandButton.Builder()
                .setDisplayName("Stop & Close")
                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                .setSessionCommand(new SessionCommand(ACTION_STOP, Bundle.EMPTY))
                .build();

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(pending)
                .setCustomLayout(ImmutableList.of(stopButton))
                .setCallback(new MediaSession.Callback() {
                    @Override
                    public MediaSession.ConnectionResult onConnect(MediaSession session, MediaSession.ControllerInfo controller) {
                        MediaSession.ConnectionResult connectionResult = MediaSession.Callback.super.onConnect(session, controller);
                        SessionCommands customCommands = connectionResult.availableSessionCommands.buildUpon()
                                .add(new SessionCommand(ACTION_STOP, Bundle.EMPTY))
                                .build();
                        return MediaSession.ConnectionResult.accept(customCommands, connectionResult.availablePlayerCommands);
                    }

                    @Override
                    public ListenableFuture<SessionResult> onCustomCommand(MediaSession session, MediaSession.ControllerInfo controller, SessionCommand customCommand, Bundle args) {
                        if (ACTION_STOP.equals(customCommand.customAction)) {
                            stopPlaybackAndService();
                            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                        }
                        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
                    }
                })
                .build();
    }

    private void stopPlaybackAndService() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopPlaybackAndService();
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlaybackAndService();
        super.onTaskRemoved(rootIntent);
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return mediaSession; }

    @Override public void onDestroy() {
        if (mediaSession != null) {
            if (mediaSession.getPlayer() != null) {
                mediaSession.getPlayer().stop();
                mediaSession.getPlayer().release();
            }
            mediaSession.release();
            mediaSession = null;
        }
        player = null;
        super.onDestroy();
    }
}