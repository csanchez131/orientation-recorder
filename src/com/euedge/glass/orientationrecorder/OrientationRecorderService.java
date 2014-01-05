/*
 * Copyright (C) 2014 EU Edge LLC, http://euedge.com/
 *
 * This code is modification of a work of The Android Open Source Project,
 * see the original license statement below.
 *
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.euedge.glass.orientationrecorder;

import java.io.File;
import java.io.FileNotFoundException;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import com.euedge.glass.orientation.RecordingOrientationManager;
import com.euedge.glass.orientation.ReplayingOrientationManager;
import com.euedge.glass.orientation.ReplayingOrientationManager.ReplayListener;
import com.euedge.glass.orientation.SensorsOrientationManager;
import com.euedge.glass.orientationrecorder.util.MathUtils;
import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.timeline.LiveCard.PublishMode;
import com.google.android.glass.timeline.TimelineManager;

/**
 * The main application service that manages the lifetime of the speed HUD live card.
 */
public class OrientationRecorderService extends Service {

    private static final String LIVE_CARD_ID = "speed_hud";
    
    private static final String PREFERENCES_NAME = OrientationRecorderService.class.toString();
    private static final String PREFS_UOM_KEY = "key_uom";

    public static final String ORIENTATIONS_DIR = "orientations";
    
    /**
     * A binder that gives other components access to the speech capabilities provided by the
     * service.
     */
    public class OrientationRecorderBinder extends Binder {
        /**
         * Read the current heading aloud using the text-to-speech engine.
         */
        public void readHeadingAloud() {
            float heading = recordingOrientationManager.getHeading();

            Resources res = getResources();
            String[] spokenDirections = res.getStringArray(R.array.spoken_directions);
            String directionName = spokenDirections[MathUtils.getHalfWindIndex(heading)];

            int roundedHeading = Math.round(heading);
            int headingFormat;
            if (roundedHeading == 1) {
                headingFormat = R.string.spoken_heading_format_one;
            } else {
                headingFormat = R.string.spoken_heading_format;
            }

            String headingText = res.getString(headingFormat, roundedHeading, directionName);
            mSpeech.speak(headingText, TextToSpeech.QUEUE_FLUSH, null);
        }
        
        public OrientationRecorderService getOrientationRecorderService() {
            return OrientationRecorderService.this;
        }
    }
    
    private ReplayListener replayListener = new ReplayListener() {
        @Override
        public void onReplayFinsihed() {
            mRenderer.setOrientationManager(recordingOrientationManager);
            recordingOrientationManager.start();
            
            replayingOrientationManager.stop();
        }
    };

    private final OrientationRecorderBinder mBinder = new OrientationRecorderBinder();

    private RecordingOrientationManager recordingOrientationManager;
    private ReplayingOrientationManager replayingOrientationManager;
    private TextToSpeech mSpeech;

    private TimelineManager mTimelineManager;
    private LiveCard mLiveCard;
    private OrientationRecorderRenderer mRenderer;
    
    @Override
    public void onCreate() {
        super.onCreate();

        mTimelineManager = TimelineManager.from(this);

        // Even though the text-to-speech engine is only used in response to a menu action, we
        // initialize it when the application starts so that we avoid delays that could occur
        // if we waited until it was needed to start it up.
        mSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                // Do nothing.
            }
        });

        SensorManager sensorManager =
                (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        LocationManager locationManager =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        File omDir = new File(getApplicationContext().getExternalFilesDir(null),
                              ORIENTATIONS_DIR);
        if (!omDir.exists()) {
            omDir.mkdirs();
        }
        recordingOrientationManager = new RecordingOrientationManager( 
                new SensorsOrientationManager(sensorManager, locationManager),
                omDir, false);
        
        replayingOrientationManager = new ReplayingOrientationManager();
        replayingOrientationManager.addReplayListener(replayListener);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLiveCard == null) {
            mLiveCard = mTimelineManager.createLiveCard(LIVE_CARD_ID);
            mRenderer = new OrientationRecorderRenderer(this, recordingOrientationManager);

            LiveCard direct = mLiveCard.setDirectRenderingEnabled(true);
            direct.getSurfaceHolder().addCallback(mRenderer);
            direct.getSurfaceHolder().setKeepScreenOn(true);

            // Display the options menu when the live card is tapped.
            Intent menuIntent = new Intent(this, OrientationRecorderMenuActivity.class);
            menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));
            
            mLiveCard.publish(PublishMode.REVEAL);
        }
        
        SharedPreferences prefs =
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        int uom = prefs.getInt(PREFS_UOM_KEY, OrientationRecorderView.UOM_DEFAULT);
        mRenderer.setUom(uom);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SharedPreferences prefs =
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        final SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(PREFS_UOM_KEY, mRenderer.getUom());
        edit.commit();

        if (mLiveCard != null && mLiveCard.isPublished()) {
            mLiveCard.unpublish();
            mLiveCard.getSurfaceHolder().removeCallback(mRenderer);
            mLiveCard = null;
        }

        mSpeech.shutdown();
        mSpeech = null;
        
        if (recordingOrientationManager != null) {
            recordingOrientationManager.stop();
            recordingOrientationManager = null;
        }
        if (replayingOrientationManager != null) {
            replayingOrientationManager.removeReplayListener(replayListener);
            replayingOrientationManager.stop();
            replayingOrientationManager = null;
        }

        super.onDestroy();
    }
    
    public void setUom(int uom) {
        mRenderer.setUom(uom);
    }
    
    public void startRecording() {
        recordingOrientationManager.startRecording();
    }
    
    public void stopRecording() {
        recordingOrientationManager.stopRecording();
    }

    public boolean isRecording() {
        return recordingOrientationManager.isRecording();
    }
    
    public void startReplaying(String filename) {
        try {
            File omDir = new File(getApplicationContext().getExternalFilesDir(null),
                                  ORIENTATIONS_DIR);
            replayingOrientationManager.setFile(new File(omDir, filename));
            mRenderer.setOrientationManager(replayingOrientationManager);
            replayingOrientationManager.start();
            
            recordingOrientationManager.stop();
        } catch (FileNotFoundException e) {
        }
    }
    
    public void stopReplaying() {
        mRenderer.setOrientationManager(recordingOrientationManager);
        recordingOrientationManager.start();
        
        replayingOrientationManager.stop();
    }

    public boolean isReplaying() {
        return replayingOrientationManager.isReplaying();
    }

}
