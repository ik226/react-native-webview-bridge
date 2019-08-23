package com.github.alinz.reactnativewebviewbridge;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.os.Build;
import android.webkit.CookieManager;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.views.webview.ReactWebViewManager;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class WebViewBridgeManager extends ReactWebViewManager {
    private static final String REACT_CLASS = "RCTWebViewBridge";

    public static final int COMMAND_SEND_TO_BRIDGE = 101;
    private static final String INTENT_URL_PREFIX = "intent://";

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected ReactWebView createReactWebViewInstance(ThemedReactContext reactContext) {
        return new CustomWebView(reactContext);
    }

    @Override
    protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
        view.setWebViewClient(new CustomWebViewClient());
    }

    @Override
    @Nullable
    public Map<String, Integer> getCommandsMap() {
        Map<String, Integer> commandsMap = super.getCommandsMap();

        commandsMap.put("sendToBridge", COMMAND_SEND_TO_BRIDGE);

        return commandsMap;
    }

    @Override
    protected WebView createViewInstance(ThemedReactContext reactContext) {
        WebView root = super.createViewInstance(reactContext);
        root.addJavascriptInterface(new JavascriptBridge(root), "ctandroid");

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(root, true);
        }

        return root;
    }

    @Override
    public void receiveCommand(WebView root, int commandId, @Nullable ReadableArray args) {
        super.receiveCommand(root, commandId, args);

        switch (commandId) {
            case COMMAND_SEND_TO_BRIDGE:
                sendToBridge(root, args.getString(0));
                break;
            default:
                //do nothing!!!!
        }
    }

    private void sendToBridge(WebView root, String message) {
        String script = message;
        WebViewBridgeManager.evaluateJavascript(root, script);
    }

    static private void evaluateJavascript(WebView root, String javascript) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            root.evaluateJavascript(javascript, null);
        } else {
            root.loadUrl("javascript:" + javascript);
        }
    }

    @ReactProp(name = "allowFileAccessFromFileURLs")
    public void setAllowFileAccessFromFileURLs(WebView root, boolean allows) {
        root.getSettings().setAllowFileAccessFromFileURLs(allows);
    }

    @ReactProp(name = "allowUniversalAccessFromFileURLs")
    public void setAllowUniversalAccessFromFileURLs(WebView root, boolean allows) {
        root.getSettings().setAllowUniversalAccessFromFileURLs(allows);
    }

    @ReactProp(name = "finalUrl")
    public void setFinalUrl(WebView view, String url) {
        ((CustomWebView) view).setFinalUrl(url);
    }

    protected static class CustomWebView extends ReactWebView {
        public CustomWebView(ThemedReactContext reactContext) {
            super(reactContext);
        }

        protected @Nullable String mFinalUrl;

        public void setFinalUrl(String url) {
            mFinalUrl = url;
        }

        public String getFinalUrl() {
            return mFinalUrl;
        }
    }

    public static class CustomWebViewClient extends ReactWebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            boolean shouldOverride = getShouldOverrideUrlLoading(view, url);
            String finalUrl = ((CustomWebView) view).getFinalUrl();

            if (!shouldOverride && url != null && finalUrl != null && new String(url).equals(finalUrl)) {
                final WritableMap params = Arguments.createMap();
                dispatchEvent(view, new NavigationCompletedEvent(view.getId(), params));
            }

            return shouldOverride;
        }

        public boolean getShouldOverrideUrlLoading(WebView view, String url) {
            if (url.equals(BLANK_URL)) return false;

            // url blacklisting
            if (mUrlPrefixesForDefaultIntent != null && mUrlPrefixesForDefaultIntent.size() > 0) {
                ArrayList<Object> urlPrefixesForDefaultIntent = mUrlPrefixesForDefaultIntent.toArrayList();
                for (Object urlPrefix : urlPrefixesForDefaultIntent) {
                    if (url.startsWith((String) urlPrefix)) {
                        launchIntent(view.getContext(), view, url);
                        return true;
                    }
                }
            }

            if (mOriginWhitelist != null && shouldHandleURL(mOriginWhitelist, url)) {
                return false;
            }

            launchIntent(view.getContext(), view, url);
            return true;
        }

        private void launchIntent(Context context, WebView view, String url) {
            Intent intent = null;

            // URLs starting with 'intent://' require special handling.
            if (url.startsWith(INTENT_URL_PREFIX)) {
                try {
                    intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                } catch (URISyntaxException e) {
                    FLog.e(ReactConstants.TAG, "Can't parse intent:// URI", e);
                }
            }

            if (intent != null) {
                // This is needed to prevent security issue where non-exported activities from the same process can be started with intent:// URIs.
                // See: T10607927/S136245
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);

                PackageManager packageManager = context.getPackageManager();
                ResolveInfo info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (info != null) {
                    // App is installed.
                    context.startActivity(intent);
                } else {
                    String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
                }
            } else {
                // intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                view.loadUrl(url);
                return;
            }

            try {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                context.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                FLog.w(ReactConstants.TAG, "activity not found to handle uri scheme for: " + url, e);
            }
        }

        private boolean shouldHandleURL(List<Pattern> originWhitelist, String url) {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "";
            String authority = uri.getAuthority() != null ? uri.getAuthority() : "";
            String urlToCheck = scheme + "://" + authority;
            for (Pattern pattern : originWhitelist) {
                if (pattern.matcher(urlToCheck).matches()) {
                    return true;
                }
            }
            return false;
        }
    }
}