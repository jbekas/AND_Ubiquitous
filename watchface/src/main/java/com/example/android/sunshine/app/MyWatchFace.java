package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class MyWatchFace extends CanvasWatchFaceService {

    public static final String LOG_TAG = MyWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC);

    private static int TEMPERATURE_IMAGE_HEIGHT = 75;
    private static int TEMPERATURE_IMAGE_WIDTH = 75;

    private static final long UPDATE_RATE_MS = 1000;
    private static final int MSG_UPDATE_TIME = 0;

    private static SimpleDateFormat dateFormat = new SimpleDateFormat("EEE - MMM d, yyyy", Locale.US);

    private String highTemp;
    private String lowTemp;
    private Bitmap bitmap;
    private int roundOffset;

    private GoogleApiClient googleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        final Handler updateTimeHandler = new EngineHandler(this);

        private boolean registeredTimeZoneReceiver;

        private Paint backgroundPaint;
        private Paint textPaint;
        private Paint textPaintDate;
        private Paint temperaturePaint;

        private boolean mAmbient;
        private Calendar mCalendar;

        private float timeWidth;
        private float timeWidthAmbient;

        private float yOffset;
        private float yOffsetDate;
        private float yOffsetTemperature;

        boolean lowBitAmbient;

        //Receiver that's notified of the time zone changes.
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            Log.i(LOG_TAG, "Engine::onCreate()");

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = MyWatchFace.this.getResources();

            yOffset = resources.getDimension(R.dimen.digital_y_offset);
            yOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            yOffsetTemperature = resources.getDimension(R.dimen.digital_y_offset_temperature);

            backgroundPaint = new Paint();
            backgroundPaint.setColor(resources.getColor(R.color.sunshine_blue));

            textPaint = new Paint();
            textPaint.setColor(resources.getColor(R.color.digital_text));
            textPaint.setTypeface(BOLD_TYPEFACE);
            textPaint.setAntiAlias(true);

            textPaintDate = new Paint();
            textPaintDate.setColor(resources.getColor(R.color.light_blue));
            textPaintDate.setTypeface(NORMAL_TYPEFACE);
            textPaintDate.setAntiAlias(true);

            temperaturePaint = new Paint();
            temperaturePaint.setTypeface(NORMAL_TYPEFACE);
            temperaturePaint.setColor(resources.getColor(R.color.digital_text));
            temperaturePaint.setAntiAlias(true);

            googleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            //googleApiClient.connect();

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                googleApiClient.connect();
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MyWatchFace.this.getResources();
            float textSize;
            float dateTextSize;
            float tempTextSize;

            if (insets.isRound()) {
                textSize = resources.getDimension(R.dimen.digital_text_size_round);
                dateTextSize = resources.getDimension(R.dimen.digital_date_text_size_round);
                tempTextSize = resources.getDimension(R.dimen.digital_temp_text_size_round);

                roundOffset = 20;
            } else {
                textSize = resources.getDimension(R.dimen.digital_text_size);
                dateTextSize = resources.getDimension(R.dimen.digital_date_text_size);
                tempTextSize = resources.getDimension(R.dimen.digital_temp_text_size);

                roundOffset = 0;
            }

            textPaint.setTextSize(textSize);
            textPaintDate.setTextSize(dateTextSize);
            temperaturePaint.setTextSize(tempTextSize);

            // Use standard widths for time to prevent jitter.
            Rect textBounds = new Rect();
            String timeString = "12:55:55";
            textPaint.getTextBounds(timeString, 0, timeString.length(), textBounds);
            timeWidth = textBounds.width();

            timeString = "55:55";
            textPaint.getTextBounds(timeString, 0, timeString.length(), textBounds);
            timeWidthAmbient = textBounds.width();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (lowBitAmbient) {
                    textPaint.setAntiAlias(!inAmbientMode);
                    temperaturePaint.setAntiAlias(!inAmbientMode);
                    textPaintDate.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            int width = bounds.width();
            int height = bounds.height();

            Rect textBounds = new Rect();

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, width, height, backgroundPaint);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // Time
            String timeString;
            if (!isInAmbientMode()) {
                timeString = String.format(Locale.US, "%02d:%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                        mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
                canvas.drawText(timeString, (width - timeWidth)/2, yOffset + roundOffset, textPaint);
            } else {
                timeString = String.format(Locale.US, "%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                        mCalendar.get(Calendar.MINUTE));
                canvas.drawText(timeString, (width - timeWidthAmbient)/2, yOffset + roundOffset, textPaint);
            }

            //textPaint.getTextBounds(timeString, 0, timeString.length(), textBounds);

            // Date
            String dateString = dateFormat.format(now);

            textPaintDate.getTextBounds(dateString, 0, dateString.length(), textBounds);
            canvas.drawText(dateString, (width - textBounds.width())/2, yOffsetDate + roundOffset, textPaintDate);

            // Temperature
            if (highTemp == null || "".equals(highTemp)) {
                highTemp = "--";
            }
            if (lowTemp == null || "".equals(lowTemp)) {
                lowTemp = "--";
            }
            String tempString = String.format(Locale.getDefault(), "%s | %s", highTemp, lowTemp);

            temperaturePaint.getTextBounds(tempString, 0, tempString.length(), textBounds);
            canvas.drawText(tempString, (width - textBounds.width()) / 2, yOffsetTemperature + roundOffset, temperaturePaint);

            if (!isInAmbientMode()) {
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, (width - TEMPERATURE_IMAGE_WIDTH)/2, yOffsetTemperature + roundOffset - textBounds.height() - TEMPERATURE_IMAGE_HEIGHT, temperaturePaint);
                }
            }
        }

        private void updateTimer() {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "updateTimer");
            }

            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void handleUpdateTimeMessage() {
            //Log.d(LOG_TAG, "handleUpdateTimeMessage()");
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = UPDATE_RATE_MS
                        - (timeMs % UPDATE_RATE_MS);
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) { }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) { }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {

            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals("/weather")) {
                        final DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        highTemp = dataMap.getString("high");
                        lowTemp = dataMap.getString("low");

                        Asset weatherIcon = dataMap.getAsset("icon");
                        DownloadAssetTask task = new DownloadAssetTask();
                        task.execute(weatherIcon);

                        if (!isInAmbientMode()) {
                            invalidate();
                        }
                    }
                }
            }

            invalidate();
        }
    }

    private class DownloadAssetTask extends AsyncTask<Asset, Void, Bitmap> {
        @Override
        protected Bitmap doInBackground(Asset... params) {
            return loadBitmapFromAsset(params[0]);
        }

        @Override
        protected void onPostExecute(Bitmap b) {
            bitmap = Bitmap.createScaledBitmap(b, TEMPERATURE_IMAGE_WIDTH, TEMPERATURE_IMAGE_HEIGHT, false);
        }

        Bitmap loadBitmapFromAsset(Asset asset) {
            if (googleApiClient == null) {
                throw new IllegalStateException("Google API client is null.");
            }

            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            if (!googleApiClient.isConnected()) {
                //Log.d(LOG_TAG, "Connecting to Google api client.");
                ConnectionResult result =
                        googleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);

                if (!result.isSuccess()) {
                    return null;
                }
            }

            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    googleApiClient, asset).await().getInputStream();

            if (assetInputStream == null) {
                //Log.d(LOG_TAG, "Asset not found.");
                return null;
            }

            // decode the bitmap asset and display
            //Log.d(LOG_TAG, "Done loading asset.");
            return BitmapFactory.decodeStream(assetInputStream);
        }
    }
}