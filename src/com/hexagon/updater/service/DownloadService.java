/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.hexagon.updater.service;

import android.app.DownloadManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import com.hexagon.updater.R;
import com.hexagon.updater.UpdateApplication;
import com.hexagon.updater.misc.Constants;
import com.hexagon.updater.misc.UpdateInfo;
import com.hexagon.updater.receiver.DownloadReceiver;
import com.hexagon.updater.requests.UpdatesJsonObjectRequest;
import com.hexagon.updater.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;

public class DownloadService extends IntentService
        implements Response.Listener<JSONObject>, Response.ErrorListener {
    private static final String TAG = DownloadService.class.getSimpleName();

    private static final String EXTRA_UPDATE_INFO = "update_info";

    private SharedPreferences mPrefs;
    private UpdateInfo mInfo = null;

    public static void start(Context context, UpdateInfo ui) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) ui);
        context.startService(intent);
    }

    public DownloadService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mInfo = intent.getParcelableExtra(EXTRA_UPDATE_INFO);

        if (mInfo == null) {
            Log.e(TAG, "Intent UpdateInfo extras were null");
            return;
        }

        downloadFullZip();
    }

    private String getServerUri() {
        String propertyUri = SystemProperties.get("hex.updater.uri");
        if (!TextUtils.isEmpty(propertyUri)) {
            return propertyUri;
        }

        return getString(R.string.conf_update_server_url_def);
    }

    private UpdateInfo jsonToInfo(JSONObject obj) {
        try {
            if (obj == null || obj.has("errors")) {
                return null;
            }

            return new UpdateInfo.Builder()
                    .setFileName(obj.getString("filename"))
                    .setDownloadUrl(obj.getString("download_url"))
                    .setApiLevel(mInfo.getApiLevel())
                    .setBuildDate(obj.getLong("date_created_unix"))
                    .build();
        } catch (JSONException e) {
            Log.e(TAG, "JSONException", e);
            return null;
        }
    }

    private long enqueueDownload(String downloadUrl, String localFilePath) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        String userAgent = Utils.getUserAgentString(this);
        if (userAgent != null) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.setTitle(getString(R.string.app_name));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);

        // TODO: this could/should be made configurable
        request.setAllowedOverMetered(true);

        final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        return dm.enqueue(request);
    }

    private void downloadFullZip() {
        Log.v(TAG, "Downloading full zip");

        // Build the name of the file to download, adding .partial at the end.  It will get
        // stripped off when the download completes
        String fullFilePath = "file://" + getUpdateDirectory().getAbsolutePath() +
                "/" + mInfo.getFileName() + ".partial";

        long downloadId = enqueueDownload(mInfo.getDownloadUrl(), fullFilePath);

        // Store in shared preferences
        mPrefs.edit()
                .putLong(Constants.DOWNLOAD_ID, downloadId)
                .apply();

        Utils.cancelNotification(this);

        Intent intent = new Intent(DownloadReceiver.ACTION_DOWNLOAD_STARTED);
        intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
        sendBroadcast(intent);
    }

    private File getUpdateDirectory() {
        return Utils.makeUpdateFolder(getApplicationContext());
    }

    @Override
    public void onErrorResponse(VolleyError error) {
        VolleyLog.e("Error: ", error.getMessage());
    }

    @Override
    public void onResponse(JSONObject response) {
        VolleyLog.v("Response:%n %s", response);
        downloadFullZip();
    }
}
