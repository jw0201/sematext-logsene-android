package com.sematext.logseneandroid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

/**
 * Logs messages to Logsene from Android applications.
 */
public class Logsene {
  public static final String KEY_RECEIVERURL = "LOGSENE_RECEIVERURL";
  public static final String KEY_APPTOKEN = "LOGSENE_APPTOKEN";
  public static final String KEY_TYPE = "LOGSENE_TYPE";

  private final String TAG = getClass().getSimpleName();
  private final String FLUSH_WORKER_TAG = "com.sematext.android.LogWorker.unconstrained";
  private final String INTERVAL_WORKER_TAG = "com.sematext.android.LogWorker.interval";
  private final String ONQUEUE_WORKER_TAG = "com.sematext.android.LogWorker.onqueue";

  private final String RECEIVER_URL = "https://logsene-receiver.sematext.com";

  /**
   * Maximum number of messages to cache when offline.
   */
  private final int DEFAULT_MAX_OFFLINE_MESSAGES = 5000;

  /**
   * Minimum number of messages before sending request.
   */
  private final int DEFAULT_MIN_BATCH_SIZE = 10;

  /**
   * Minimum time between sending requests.
   */
  private final int DEFAULT_MIN_TIME_DELAY = 60 * 1000;

  /**
   * Max time between sending requests.
   */
  private static final int DEFAULT_TIME_INTERVAL = 15 * 60 * 1000;


  private static JSONObject defaultMeta;
  private final Context context;
  private String versionName;
  private Integer versionCode;
  private String uuid;

  /**
   * Persistent queue for storing messages to be sent to Logsene.
   *
   * Messages are persisted as they are acknowledged.
   * Messages are dequeued from LogWorker, and only removed when they are successfully sent.
   */
  private SqliteObjectQueue preflightQueue;
  private long lastScheduled = -1;

  private String appToken;
  private String type;
  private String receiverUrl;
  private int maxOfflineMessages;
  private long timeInterval;
  private long minTimeDelay;
  private boolean sendRequiresUnmeteredNetwork;
  private boolean sendRequiresDeviceIdle;
  private boolean sendRequiresBatteryNotLow;
  private boolean isActive;
  private LogseneLocationListener locationListener;

  public Logsene(Context context) {
    this(context, false);
  }

  public Logsene(Context context, boolean automaticLocationEnabled) {
    Utils.requireNonNull(context);
    this.context = context;
    this.uuid = Installation.id(context);
    config();
    this.preflightQueue = new SqliteObjectQueue(Logsene.this.context, maxOfflineMessages);
    this.lastScheduled = SystemClock.elapsedRealtime();
    if (automaticLocationEnabled) {
      this.locationListener = new LogseneLocationListener(context);
    }
    this.isActive = true;
    schedulePeriodicWorker();
  }

  public long getQueueSize() {
    return preflightQueue.size();
  }

  public LogseneLocationListener getLocationListener() {
    return locationListener;
  }

  private void config() {
    Bundle data = null;
    try {
      data = context.getPackageManager().getApplicationInfo(context.getPackageName(),
            PackageManager.GET_META_DATA).metaData;
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }

    // required fields
    if (!data.containsKey("LogseneAppToken")) {
      throw new RuntimeException("Please provide <meta-data name=\"LogseneAppToken\" value=\"yourapptoken\">");
    } else if (!data.containsKey("LogseneType")) {
      throw new RuntimeException("Please provide <meta-data name=\"LogseneType\" value=\"example\">");
    }
    appToken = data.getString("LogseneAppToken");
    type = data.getString("LogseneType");

    // optional fields
    receiverUrl = data.getString("LogseneReceiverUrl", RECEIVER_URL);
    maxOfflineMessages = data.getInt("LogseneMaxOfflineMessages", DEFAULT_MAX_OFFLINE_MESSAGES);
    minTimeDelay = (long)(data.getInt("LogseneMinTimeDelay", DEFAULT_MIN_TIME_DELAY));
    timeInterval = (long)(data.getInt("LogseneInterval", DEFAULT_TIME_INTERVAL));
    sendRequiresUnmeteredNetwork = data.getBoolean("LogseneSendRequiresUnmeteredNetwork", false);
    sendRequiresDeviceIdle = data.getBoolean("LogseneSendRequiresDeviceIdle", false);
    sendRequiresBatteryNotLow = data.getBoolean("LogseneSendRequiresBatteryNotLow", false);

    Log.d(TAG, String.format("Logsene is configured:\n"
        + "  Type:                                   %s\n"
        + "  Receiver URL:                           %s\n"
        + "  Max Offline Messages:                   %d\n"
        + "  Min Time Trigger:                       %d\n"
        + "  Max Time Trigger:                       %d\n"
        + "  Send logs only on unmetered network:    %s\n"
        + "  Send logs only when device is idle:     %s\n"
        + "  Send logs only when battery is not low: %s",
        type, receiverUrl, maxOfflineMessages, minTimeDelay, timeInterval,
        sendRequiresUnmeteredNetwork, sendRequiresDeviceIdle, sendRequiresBatteryNotLow));
  }

  public void pause() {
    this.isActive = false;
  }

  public void resume() {
    this.isActive = true;
  }

  /**
   * Logs a simple message.
   *
   * @param level value of the log `level` field
   * @param message message text
     */
  public void log(String level, String message) {
    log(level, message, null, null);
  }

  /**
   * Logs a simple message with location.
   *
   * @param level value of the log `level` field
   * @param message message text
   * @param lat latitude
   * @param lon longitude
   */
  public void log(String level, String message, Double lat, Double lon) {
    Utils.requireNonNull(message);
    JSONObject obj = new JSONObject();
    try {
      obj.put("level", level);
      obj.put("message", message);
      enrichWithLocation(obj);
      addLocationToObject(obj, lat, lon);
      addToQueue(obj);
    } catch (JSONException e) {
      // thrown when key is null in put(), so should never happen
      Log.e(TAG, "Failed to construct json object", e);
    }
  }

  /**
   * Logs debug message.
   * @param message the message text
   */
  public void debug(String message) {
    log("debug", message);
  }

  /**
   * Logs debug message.
   * @param message the message text
   * @param lat latitude
   * @param lon longitude
   */
  public void debug(String message, Double lat, Double lon) {
    log("debug", message, lat, lon);
  }

  /**
   * Logs debug message.
   * @param message the message text
   */
  public void info(String message) {
    log("info", message);
  }

  /**
   * Logs debug message.
   * @param message the message text
   * @param lat latitude
   * @param lon longitude
   */
  public void info(String message, Double lat, Double lon) {
    log("info", message, lat, lon);
  }

  /**
   * Logs debug message.
   * @param message the message text
   */
  public void warn(String message) {
    log("warn", message);
  }

  /**
   * Logs debug message.
   * @param message the message text
   * @param lat latitude
   * @param lon longitude
   */
  public void warn(String message, Double lat, Double lon) {
    log("warn", message, lat, lon);
  }

  /**
   * Logs exception with `warn` level.
   * @param error the exception
   */
  public void warn(Throwable error) {
    log("warn", error);
  }

  /**
   * Logs exception with `warn` level.
   * @param error the exception
   * @param lat latitude
   * @param lon longitude
   */
  public void warn(Throwable error, Double lat, Double lon) {
    log("warn", error, lat, lon);
  }

  /**
   * Logs error message.
   * @param message the message text
   */
  public void error(String message) {
    log("error", message);
  }

  /**
   * Logs error message.
   * @param message the message text
   * @param lat latitude
   * @param lon longitude
   */
  public void error(String message, Double lat, Double lon) {
    log("error", message, lat, lon);
  }

  /**
   * Logs exception with `error` level.
   * @param error the exception
   */
  public void error(Throwable error) {
    log("error", error);
  }

  /**
   * Logs exception with `error` level.
   * @param error the exception
   * @param lat latitude
   * @param lon longitude
   */
  public void error(Throwable error, Double lat, Double lon) {
    log("error", error, lat, lon);
  }

  /**
   * Logs an exception.
   * @param level value of log `level` field
   * @param error any throwable
   */
  public void log(String level, Throwable error) {
    log(level, error, null, null);
  }

  /**
   * Log an exception with location.
   * @param level value of log `level` field
   * @param error any throwable
   * @param lat latitude
   * @param lon longitude
   */
  public void log(String level, Throwable error, Double lat, Double lon) {
    Utils.requireNonNull(error);
    JSONObject obj = new JSONObject();
    try {
      obj.put("level", level);
      obj.put("exception", error.getClass().toString());
      obj.put("message", error.getMessage());
      obj.put("stacktrace", Utils.getStackTrace(error));
      enrichWithLocation(obj);
      addLocationToObject(obj, lat, lon);
      addToQueue(obj);
    } catch (JSONException e) {
      // thrown when key is null in put(), so should never happen
      Log.e(TAG, "Failed to construct json object", e);
    }
  }

  /**
   * Send a custom event.
   * @param object the event data.
   */
  public void event(JSONObject object) {
    Utils.requireNonNull(object);
    addToQueue(object);
  }

  /**
   * Sets the default meta properties.
   *
   * These will be included with every request.
   *
   * @param metadata the default meta properties, use null to disable.
   */
  public static void setDefaultMeta(JSONObject metadata) {
    defaultMeta = metadata;
  }

  /**
   * Flush the message queue.
   *
   * This call is optional, but it is recommended to be called when application is destroyed. This
   * call will try to send logs regardless of LogseneSendRequiresUnmeteredNetwork,
   * LogseneSendRequiresDeviceIdle or LogseneSendRequiresBatteryNotLow.
   */
  public void flushMessageQueue() {
    Log.d(TAG, "Flushing message queue, message queue size = " + preflightQueue.size());
    scheduleUnconstrainedWorker();
  }

  private Data getWorkerData() {
    return new Data.Builder()
            .putString(KEY_RECEIVERURL, receiverUrl)
            .putString(KEY_APPTOKEN, appToken)
            .putString(KEY_TYPE, type)
            .build();
  }


  private Constraints getWorkerConstraints() {
    return new Constraints.Builder()
            .setRequiredNetworkType(
                    sendRequiresUnmeteredNetwork ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresDeviceIdle(sendRequiresDeviceIdle)
            .setRequiresBatteryNotLow(sendRequiresBatteryNotLow)
            .build();
  }

  private void scheduleUnconstrainedWorker() {
    OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(LogWorker.class)
        .addTag(FLUSH_WORKER_TAG)
        .setInputData(getWorkerData())
        .build();

    WorkManager.getInstance().enqueueUniqueWork(FLUSH_WORKER_TAG,
        ExistingWorkPolicy.KEEP, workRequest);
  }

  private void scheduleConstrainedWorker() {
    OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(LogWorker.class)
            .addTag(FLUSH_WORKER_TAG)
            .setInputData(getWorkerData())
            .setConstraints(getWorkerConstraints())
            .build();

    WorkManager.getInstance().enqueueUniqueWork(ONQUEUE_WORKER_TAG,
            ExistingWorkPolicy.KEEP, workRequest);
  }

  private void schedulePeriodicWorker() {
    Constraints workerConstraints = getWorkerConstraints();

    PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            LogWorker.class, timeInterval, TimeUnit.MILLISECONDS)
        .addTag(INTERVAL_WORKER_TAG)
        .setInputData(getWorkerData())
        .setConstraints(workerConstraints)
        .build();

    WorkManager.getInstance().enqueueUniquePeriodicWork(INTERVAL_WORKER_TAG,
        ExistingPeriodicWorkPolicy.KEEP, workRequest);
  }

  private void addToQueue(JSONObject obj) {
    if (preflightQueue == null) {
      Log.e(TAG, "Message queue has not been initialized, message dropped.");
      return;
    }

    assert obj != null;
    enrich(obj);

    preflightQueue.add(obj);

    if (preflightQueue.size() == maxOfflineMessages) {
      Log.d(TAG, "Message queue overflowing (" + preflightQueue.size() + " > "
          + maxOfflineMessages + "), some logs might be lost.");
    }

    boolean canSend = lastScheduled == -1 || SystemClock.elapsedRealtime() - lastScheduled > minTimeDelay;
    if (preflightQueue.size() >= DEFAULT_MIN_BATCH_SIZE && canSend && isActive) {
      scheduleConstrainedWorker();
      lastScheduled = SystemClock.elapsedRealtime();
    }
  }

  private String getVersionName() {
    if (versionName == null) {
      PackageInfo pInfo = null;
      try {
        pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        versionName = pInfo.versionName;
      } catch (PackageManager.NameNotFoundException e) {
        Log.e(TAG, e.getMessage(), e);
        // set to n/a so we don't try again
        versionName = "n/a";
      }
    }

    return versionName;
  }

  private int getVersionCode() {
    if (versionCode == null) {
      PackageInfo pInfo = null;
      try {
        pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        versionCode = pInfo.versionCode;
      } catch (PackageManager.NameNotFoundException e) {
        Log.e(TAG, e.getMessage(), e);
        // set to n/a so we don't try again
        versionCode = -1;
      }
    }

    return versionCode;
  }

  private void enrichWithLocation(JSONObject obj) throws JSONException {
    if (locationListener != null) {
      obj.put("location", locationListener.getLocationAsString());
    }
  }

  private void addLocationToObject(JSONObject obj, Double lat, Double lon) throws JSONException {
    if (lat != null && lon != null) {
      obj.put("lat", lat);
      obj.put("lon", lon);
    }
  }

  private void enrich(JSONObject obj) {
    assert obj != null;
    try {
      if (!obj.has("@timestamp")) {
        obj.put("@timestamp", Utils.iso8601());
      }

      // if user has specified some value for this field, we don't touch it
      if (!obj.has("meta")) {
        JSONObject metadata = new JSONObject();
        metadata.put("versionName", getVersionName());
        metadata.put("versionCode", getVersionCode());
        metadata.put("osRelease", Build.VERSION.RELEASE);
        metadata.put("osType", "Android");
        metadata.put("uuid", uuid);
        if (defaultMeta != null) {
          Iterator<String> keys = defaultMeta.keys();
          while (keys.hasNext()) {
            String key = keys.next();
            metadata.put(key, defaultMeta.get(key));
          }
        }
        obj.put("meta", metadata);
      }

      // create a location out of lat and lon fields
      if (obj.has("lat") && obj.has("lon")) {
        JSONObject geo = new JSONObject();
        geo.put("location", String.format("%.2f,%.2f",
                obj.getDouble("lat"), obj.getDouble("lon")));
        obj.remove("lat");
        obj.remove("lon");
        obj.put("geo", geo);
      }
    } catch (JSONException e) {
      // thrown when key is null in put(), so should never happen
      Log.e(TAG, "Failed to construct json object", e);
    }
  }
}
