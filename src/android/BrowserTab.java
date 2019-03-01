/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cordova.plugin.browsertab;

import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Browser;
import android.support.customtabs.CustomTabsIntent;
import android.util.Log;
import android.app.Activity;

import java.util.Iterator;
import java.util.List;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cordova plugin which provides the ability to launch a URL in an
 * in-app browser tab. On Android, this means using the custom tabs support
 * library, if a supporting browser (e.g. Chrome) is available on the device.
 */
public class BrowserTab extends CordovaPlugin {

  public static final int RC_OPEN_URL = 101;

  private static final String LOG_TAG = "BrowserTab";
  private CallbackContext callback = null;

  /**
   * The service we expect to find on a web browser that indicates it supports custom tabs.
   */
  private static final String ACTION_CUSTOM_TABS_CONNECTION =
          "android.support.customtabs.action.CustomTabsService";

  private boolean mFindCalled = false;
  private String mCustomTabsBrowser;

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
    Log.d(LOG_TAG, "executing " + action);
    if ("isAvailable".equals(action)) {
      isAvailable(callbackContext);
    } else if ("openUrl".equals(action)) {
      openUrl(args, callbackContext);
    } else if("openUrlInBrowser".equals(action)) {
      openExternal(args, callbackContext);
    } else if ("close".equals(action)) {
      // close is a NOP on Android
      return true;
    } else {
      return false;
    }

    return true;
  }

  private void isAvailable(CallbackContext callbackContext) {
    String browserPackage = findCustomTabBrowser();
    Log.d(LOG_TAG, "browser package: " + browserPackage);
    PluginResult result = new PluginResult(PluginResult.Status.OK, browserPackage != null);
    result.setKeepCallback(true);    
    callbackContext.sendPluginResult(result);
  }

  private void openUrl(JSONArray args, CallbackContext callbackContext) {
    if (args.length() < 1) {
      Log.d(LOG_TAG, "openUrl: no url argument received");
      callbackContext.error("URL argument missing");
      return;
    }

    String urlStr;
    try {
      urlStr = args.getString(0);
    } catch (JSONException e) {
      Log.d(LOG_TAG, "openUrl: failed to parse url argument");
      callbackContext.error("URL argument is not a string");
      return;
    }

    String customTabsBrowser = findCustomTabBrowser();
    if (customTabsBrowser == null) {
      Log.d(LOG_TAG, "openUrl: no in app browser tab available");
      callbackContext.error("no in app browser tab implementation available");
    }

    Intent customTabsIntent = new CustomTabsIntent.Builder().build().intent;
    customTabsIntent.setData(Uri.parse(urlStr));
    customTabsIntent.setPackage(mCustomTabsBrowser);

    callback = callbackContext;
    
    this.cordova.setActivityResultCallback(this);
    this.cordova.startActivityForResult(this, customTabsIntent, RC_OPEN_URL);

    Log.d(LOG_TAG, "in app browser call dispatched");
  }

  public void openExternal(JSONArray args, CallbackContext callbackContext) {
    if (args.length() < 1) {
      Log.d(LOG_TAG, "openUrl: no url argument received");
      callbackContext.error("URL argument missing");
      return;
    }

    String urlStr;
    try {
      urlStr = args.getString(0);
    } catch (JSONException e) {
      Log.d(LOG_TAG, "openUrl: failed to parse url argument");
      callbackContext.error("URL argument is not a string");
      return;
    }

    try {
      Intent intent = null;
      intent = new Intent(Intent.ACTION_VIEW);
      Uri uri = Uri.parse(urlStr);
      if ("file".equals(uri.getScheme())) {
        intent.setDataAndType(uri, webView.getResourceApi().getMimeType(uri));
      } else {
        intent.setData(uri);
      }
      intent.putExtra(Browser.EXTRA_APPLICATION_ID, cordova.getActivity().getPackageName());
      this.cordova.getActivity().startActivity(intent);
      callbackContext.success();
    } catch (java.lang.RuntimeException e) {
      callbackContext.error("Error loading url "+urlStr+":"+ e.toString());
    }
  }

  private String findCustomTabBrowser() {
    if (mFindCalled) {
      return mCustomTabsBrowser;
    }

    PackageManager pm = cordova.getActivity().getPackageManager();
    Intent webIntent = new Intent(
        Intent.ACTION_VIEW,
        Uri.parse("http://www.example.com"));
    List<ResolveInfo> resolvedActivityList =
        pm.queryIntentActivities(webIntent, PackageManager.GET_RESOLVED_FILTER);

    for (ResolveInfo info : resolvedActivityList) {
      if (!isFullBrowser(info)) {
        continue;
      }

      if (hasCustomTabWarmupService(pm, info.activityInfo.packageName)) {
        mCustomTabsBrowser = info.activityInfo.packageName;
        break;
      }
    }

    mFindCalled = true;
    return mCustomTabsBrowser;
  }

  private boolean isFullBrowser(ResolveInfo resolveInfo) {
    // The filter must match ACTION_VIEW, CATEGORY_BROWSEABLE, and at least one scheme,
    if (!resolveInfo.filter.hasAction(Intent.ACTION_VIEW)
            || !resolveInfo.filter.hasCategory(Intent.CATEGORY_BROWSABLE)
            || resolveInfo.filter.schemesIterator() == null) {
        return false;
    }

    // The filter must not be restricted to any particular set of authorities
    if (resolveInfo.filter.authoritiesIterator() != null) {
        return false;
    }

    // The filter must support both HTTP and HTTPS.
    boolean supportsHttp = false;
    boolean supportsHttps = false;
    Iterator<String> schemeIter = resolveInfo.filter.schemesIterator();
    while (schemeIter.hasNext()) {
        String scheme = schemeIter.next();
        supportsHttp |= "http".equals(scheme);
        supportsHttps |= "https".equals(scheme);

        if (supportsHttp && supportsHttps) {
            return true;
        }
    }

    // at least one of HTTP or HTTPS is not supported
    return false;
  }

  private boolean hasCustomTabWarmupService(PackageManager pm, String packageName) {
    Intent serviceIntent = new Intent();
    serviceIntent.setAction(ACTION_CUSTOM_TABS_CONNECTION);
    serviceIntent.setPackage(packageName);
    return (pm.resolveService(serviceIntent, 0) != null);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) 
  {
    if(resultCode == Activity.RESULT_OK) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, intent.getData().toString());
        result.setKeepCallback(true);
        callback.sendPluginResult(result);
    }
    else {
        PluginResult result = new PluginResult(PluginResult.Status.ERROR, "no data returned");
        result.setKeepCallback(true);
        callback.sendPluginResult(result);
    }
  }  
}
