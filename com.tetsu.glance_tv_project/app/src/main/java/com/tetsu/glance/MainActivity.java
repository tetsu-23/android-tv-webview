package com.tetsu.glance;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final String TARGET_URL = "http://192.168.1.144:6278";
    // 伪装为 Windows 10 桌面版 Chrome 浏览器 User-Agent
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private WebView webView;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        // 保持电视屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);

        // 禁用焦点与遥控器交互，纯展示模式
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setClickable(false);
        webView.setLongClickable(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        // 【关键修复】Android TV 的逻辑分辨率通常为 960x540 (200% 缩放)，
        // 导致带有 <meta name="viewport" content="width=device-width"> 的网页（如 Glance）
        // 识别宽度只有 960px，从而强制触发手机端单列/双列布局及网页底部的移动导航栏。
        // 这里通过屏幕物理宽度自动按 1920px 桌面基准计算初始缩放比（例如 960px 屏幕自动设为 50%），
        // 并注入 viewport 覆盖脚本，确保在任何电视上都 100% 呈现 1920x1080 完整桌面多列视图。
        int screenWidthPx = getResources().getDisplayMetrics().widthPixels;
        int initialScale = Math.round((screenWidthPx / 1920f) * 100f);
        if (initialScale <= 0 || initialScale > 100) {
            initialScale = 100;
        }
        webView.setInitialScale(initialScale);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // 设置桌面端 UA 并启用宽视口模式
        settings.setUserAgentString(DESKTOP_UA);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        // 固定文本缩放为 100%，防止电视系统字体放大破坏网页排版
        settings.setTextZoom(100);

        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 强制将网页 meta viewport 宽度改写为 1920，彻底阻止网页因为 width=device-width 进入移动端/底部导航栏模式
                String js = "javascript:(function(){" +
                        "var meta = document.querySelector('meta[name=viewport]');" +
                        "if(meta){ meta.setAttribute('content', 'width=1920, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'); }" +
                        "else { var m = document.createElement('meta'); m.name = 'viewport'; m.content = 'width=1920, initial-scale=1.0'; document.head.appendChild(m); }" +
                        "})();";
                view.evaluateJavascript(js, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request.isForMainFrame()) {
                    retryHandler.postDelayed(() -> {
                        if (webView != null) {
                            webView.loadUrl(TARGET_URL);
                        }
                    }, 5000);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        setContentView(webView);
        hideSystemUI();

        webView.loadUrl(TARGET_URL);
    }

    private void hideSystemUI() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        }
        final View decorView = window.getDecorView();
        final int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(flags);
        decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                decorView.setSystemUiVisibility(flags);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onDestroy() {
        retryHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
