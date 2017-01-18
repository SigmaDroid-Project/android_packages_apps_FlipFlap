/*
 * Copyright (c) 2017 The LineageOS Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * Also add information on how to contact you by electronic and paper mail.
 *
 */

package org.lineageos.flipflap;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FlipFlapView extends FrameLayout {
    private static final String TAG = "FlipFlapView";

    private static final int COVER_CLOSED_MSG = 0;

    private GestureDetector mDetector;
    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private TelecomManager mTelecomManager;
    private boolean mAlarmActive;
    private boolean mRinging;
    private boolean mProximityNear;

    public FlipFlapView(Context context) {
        super(context);

        setBackgroundColor(Color.BLACK);
        setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_FULLSCREEN);

        mDetector = new GestureDetector(context, mGestureListener);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mTelecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    }

    protected boolean canUseProximitySensor() {
        return false;
    }

    protected float getScreenBrightness() {
        return 0.5F;
    }

    protected boolean supportsAlarmActions() {
        return false;
    }

    protected boolean supportsCallActions() {
        return false;
    }

    protected boolean supportsNotifications() {
        return false;
    }

    protected void updateNotifications(List<StatusBarNotification> notifications) {
    }

    protected void updateAlarmState(boolean active) {
        mAlarmActive = active;
    }

    protected void updateRingingState(boolean ringing, String name, String number) {
        mRinging = ringing;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(FlipFlapUtils.ACTION_ALARM_ALERT);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        getContext().registerReceiver(mReceiver, filter);

        if (supportsNotifications()) {
            try {
                mNotificationListener.registerAsSystemService(getContext(),
                        new ComponentName(getContext(), getClass()), UserHandle.USER_ALL);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to register notification listener", e);
            }
        }
        if (canUseProximitySensor()) {
            mSensorManager.registerListener(mSensorEventListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY),
                    SensorManager.SENSOR_DELAY_NORMAL);
        }

        postScreenOff();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mHandler.removeCallbacksAndMessages(null);
        getContext().unregisterReceiver(mReceiver);

        if (supportsNotifications()) {
            try {
                mNotificationListener.unregisterAsSystemService();
            } catch (RemoteException e) {
                // Ignore.
            }
        }
        if (canUseProximitySensor()) {
            try {
                mSensorManager.unregisterListener(mSensorEventListener);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to unregister listener", e);
            }
        }

        mPowerManager.wakeUp(SystemClock.uptimeMillis(), "Cover Opened");
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mProximityNear) {
            postScreenOff();
            mDetector.onTouchEvent(event);
            return super.onTouchEvent(event);
        } else {
            // Say that we handled this event so nobody else does
            return true;
        }
    }

    private final SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                mProximityNear = event.values[0] < event.sensor.getMaximumRange();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Do nothing
        }
    };

    private static String normalize(String str) {
        return Normalizer.normalize(str.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("æ", "ae")
                .replaceAll("ð", "d")
                .replaceAll("ø", "o")
                .replaceAll("þ", "th")
                .replaceAll("ß", "ss")
                .replaceAll("œ", "oe");
    }

    private final GestureDetector.SimpleOnGestureListener mGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (mPowerManager.isInteractive()) {
                mPowerManager.goToSleep(SystemClock.uptimeMillis());
            }
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (Math.abs(distanceY) < 60) {
                // Did not meet the threshold for a scroll
                return true;
            }

            if (supportsCallActions() && mRinging) {
                if (distanceY < 60) {
                    mTelecomManager.endCall();
                } else if (distanceY > 60) {
                    mTelecomManager.acceptRingingCall();
                }
            } else if (supportsAlarmActions() && mAlarmActive) {
                if (distanceY < 60) {
                    getContext().sendBroadcast(new Intent(FlipFlapUtils.ACTION_ALARM_DISMISS));
                    updateAlarmState(false);
                } else if (distanceY > 60) {
                    getContext().sendBroadcast(new Intent(FlipFlapUtils.ACTION_ALARM_SNOOZE));
                    updateAlarmState(false);
                }
            }
            return true;
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action) &&
                    supportsCallActions()) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (state.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                    String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                    Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                            Uri.encode(number));
                    Cursor cursor = context.getContentResolver().query(uri,
                            new String[] { ContactsContract.PhoneLookup.DISPLAY_NAME },
                            number, null, null);
                    String name = cursor != null && cursor.moveToFirst()
                            ? cursor.getString(0) : "";
                    if (cursor != null) {
                        cursor.close();
                    }

                    if (number.equalsIgnoreCase("restricted")) {
                        // If call is restricted, don't show a number
                        name = number;
                        number = "";
                    }

                    updateRingingState(true, normalize(name), number);
                } else {
                    updateRingingState(false, null, null);
                }
            } else if (FlipFlapUtils.ACTION_ALARM_ALERT.equals(action) && supportsAlarmActions()) {
                // add other alarm apps here
                updateAlarmState(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                postScreenOff();
            }
        }
    };

    private final NotificationListenerService mNotificationListener =
            new NotificationListenerService() {
        private RankingMap mRankingMap;
        private final Comparator<StatusBarNotification> mRankingComparator =
                new Comparator<StatusBarNotification>() {

            private final Ranking mLhsRanking = new Ranking();
            private final Ranking mRhsRanking = new Ranking();

            @Override
            public int compare(StatusBarNotification lhs, StatusBarNotification rhs) {
                mRankingMap.getRanking(lhs.getKey(), mLhsRanking);
                mRankingMap.getRanking(rhs.getKey(), mRhsRanking);
                return Integer.compare(mLhsRanking.getRank(), mRhsRanking.getRank());
            }
        };

        @Override
        public void onListenerConnected() {
            handleNotificationUpdate(getCurrentRanking());
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap ranking) {
            handleNotificationUpdate(ranking);
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking) {
            handleNotificationUpdate(ranking);
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap ranking) {
            handleNotificationUpdate(ranking);
        }

        private void handleNotificationUpdate(RankingMap ranking) {
            mRankingMap = ranking;

            List<StatusBarNotification> notifications = Arrays.asList(getActiveNotifications());
            Collections.sort(notifications, mRankingComparator);
            updateNotifications(notifications);
        }
    };

    private void postScreenOff() {
        mHandler.removeCallbacksAndMessages(null);
        if (mPowerManager.isInteractive()) {
            Message msg = Message.obtain();
            msg.what = COVER_CLOSED_MSG;
            mHandler.sendMessageDelayed(msg, FlipFlapUtils.DELAYED_SCREEN_OFF_MS);
        }
    }

    private final Handler mHandler = new Handler(true /*async*/) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COVER_CLOSED_MSG:
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                    break;
            }
        }
    };
}