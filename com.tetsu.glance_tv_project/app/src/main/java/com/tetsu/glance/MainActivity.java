package com.tetsu.glance;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "http://192.168.1.144:6278";
    private static final String PREFS_NAME = "glance_config";
    private static final String KEY_TARGET_URL = "target_url";
    private static final int HTTP_PORT = 10121;

    // 伪装为 Windows 10 桌面版 Chrome 浏览器 User-Agent
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String currentTargetUrl = DEFAULT_URL;
    private ConfigHttpServer httpServer;

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

        // 读取持久化的目标网址，默认 http://192.168.1.144:6278
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentTargetUrl = prefs.getString(KEY_TARGET_URL, DEFAULT_URL);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);

        // 禁用焦点与遥控器交互，纯展示模式
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.setClickable(false);
        webView.setLongClickable(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        // Android TV 的逻辑分辨率通常为 960x540 (200% 缩放)，
        // 这里按 1920px 桌面基准计算初始缩放比，并注入 viewport 覆盖脚本，确保呈现 1920x1080 完整桌面多列视图。
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
                String js = "(function(){" +
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
                    mainHandler.postDelayed(() -> {
                        if (webView != null) {
                            webView.loadUrl(currentTargetUrl);
                        }
                    }, 5000);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        setContentView(webView);
        hideSystemUI();

        webView.loadUrl(currentTargetUrl);

        // 启动端口 10121 的本地配置 HTTP 服务
        httpServer = new ConfigHttpServer(HTTP_PORT);
        httpServer.startServer();
    }

    private void updateTargetUrl(String rawUrl) {
        if (rawUrl == null) return;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return;
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        final String finalUrl = trimmed;
        currentTargetUrl = finalUrl;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_TARGET_URL, finalUrl).apply();

        mainHandler.post(() -> {
            mainHandler.removeCallbacksAndMessages(null);
            if (webView != null) {
                webView.loadUrl(finalUrl);
            }
        });
    }

    private void reloadWebView() {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.loadUrl(currentTargetUrl);
            }
        });
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
        mainHandler.removeCallbacksAndMessages(null);
        if (httpServer != null) {
            httpServer.stopServer();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /**
     * 内置轻量级 HTTP 配置服务 (监听端口 10121)
     * 1. 浏览器直接访问 http://<电视IP>:10121/ 即可打开可视化配置页面修改网址或刷新
     * 2. GET/POST 接口: http://<电视IP>:10121/set?url=http://192.168.1.144:6278
     */
    private class ConfigHttpServer {
        private final int port;
        private volatile boolean running = false;
        private ServerSocket serverSocket;

        ConfigHttpServer(int port) {
            this.port = port;
        }

        void startServer() {
            running = true;
            Thread serverThread = new Thread(() -> {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    while (running) {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    }
                } catch (IOException ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
        }

        void stopServer() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void handleClient(Socket client) {
            new Thread(() -> {
                try (Socket socket = client;
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     OutputStream out = socket.getOutputStream()) {

                    socket.setSoTimeout(5000);
                    String requestLine = reader.readLine();
                    if (requestLine == null || requestLine.isEmpty()) return;

                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) return;
                    String method = parts[0].toUpperCase();
                    String fullPath = parts[1];

                    int contentLength = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        String lower = line.toLowerCase();
                        if (lower.startsWith("content-length:")) {
                            try {
                                contentLength = Integer.parseInt(line.substring(15).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    String body = "";
                    if (contentLength > 0 && contentLength < 65536) {
                        char[] buf = new char[contentLength];
                        int readTotal = 0;
                        while (readTotal < contentLength) {
                            int r = reader.read(buf, readTotal, contentLength - readTotal);
                            if (r == -1) break;
                            readTotal += r;
                        }
                        body = new String(buf, 0, readTotal);
                    }

                    String path = fullPath;
                    String query = "";
                    int qIdx = fullPath.indexOf('?');
                    if (qIdx >= 0) {
                        path = fullPath.substring(0, qIdx);
                        query = fullPath.substring(qIdx + 1);
                    }

                    String newUrl = extractUrlParam(query);
                    if (newUrl == null && "POST".equals(method)) {
                        newUrl = extractUrlParam(body);
                    }

                    if ("/reload".equals(path)) {
                        reloadWebView();
                        sendHtmlResponse(out, buildConfigHtml("已触发电视页面刷新！"));
                    } else if (newUrl != null && !newUrl.trim().isEmpty()) {
                        updateTargetUrl(newUrl);
                        if (path.startsWith("/api")) {
                            sendJsonResponse(out, "{\"status\":\"ok\",\"url\":\"" + escapeJson(currentTargetUrl) + "\"}");
                        } else {
                            sendHtmlResponse(out, buildConfigHtml("网址已更新并立即生效！"));
                        }
                    } else if ("/api/url".equals(path)) {
                        sendJsonResponse(out, "{\"status\":\"ok\",\"url\":\"" + escapeJson(currentTargetUrl) + "\"}");
                    } else {
                        sendHtmlResponse(out, buildConfigHtml(null));
                    }
                } catch (Exception ignored) {
                }
            }).start();
        }

        private String extractUrlParam(String paramStr) {
            if (paramStr == null || paramStr.isEmpty()) return null;
            String trimmed = paramStr.trim();
            // 简单 JSON 解析支持: {"url": "http://..."}
            if (trimmed.startsWith("{") && trimmed.contains("\"url\"")) {
                int idx = trimmed.indexOf("\"url\"");
                int colon = trimmed.indexOf(':', idx);
                if (colon > 0) {
                    int firstQuote = trimmed.indexOf('"', colon + 1);
                    int secondQuote = trimmed.indexOf('"', firstQuote + 1);
                    if (firstQuote > 0 && secondQuote > firstQuote) {
                        return trimmed.substring(firstQuote + 1, secondQuote);
                    }
                }
            }
            // Query / Form 表单解析: url=...
            String[] pairs = paramStr.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    if ("url".equals(k)) {
                        try {
                            return URLDecoder.decode(v, "UTF-8");
                        } catch (Exception e) {
                            return v;
                        }
                    }
                }
            }
            return null;
        }

        private String escapeJson(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        private String buildConfigHtml(String message) {
            String msgHtml = "";
            if (message != null && !message.isEmpty()) {
                msgHtml = "<div class='msg'>" + escapeHtml(message) + "</div>";
            }
            return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                    "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<title>Glance TV 配置</title>" +
                    "<style>" +
                    "body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#12151c;color:#e4e8f0;display:flex;justify-content:center;align-items:center;min-height:92vh;margin:0;padding:16px;box-sizing:border-box;}" +
                    ".card{background:#1c212c;padding:28px;border-radius:14px;width:100%;max-width:460px;box-shadow:0 8px 28px rgba(0,0,0,0.45);border:1px solid #2b3242;}" +
                    "h2{margin:0 0 8px 0;font-size:20px;color:#fff;}" +
                    "p{margin:0 0 18px 0;font-size:13px;color:#98a2b3;word-break:break-all;}" +
                    "label{display:block;font-size:13px;margin-bottom:8px;color:#cbd5e1;}" +
                    "input[type=text]{width:100%;padding:12px;border-radius:8px;border:1px solid #364152;background:#11141b;color:#fff;font-size:15px;box-sizing:border-box;margin-bottom:16px;outline:none;}" +
                    "input[type=text]:focus{border-color:#4caf50;}" +
                    ".btns{display:flex;gap:10px;}" +
                    "button,.btn{flex:1;padding:12px;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer;text-align:center;text-decoration:none;}" +
                    ".primary{background:#4caf50;color:#fff;}" +
                    ".secondary{background:#2d3648;color:#e2e8f0;}" +
                    ".msg{background:rgba(76,175,80,0.18);border:1px solid #4caf50;color:#81c784;padding:10px 12px;border-radius:8px;font-size:13px;margin-bottom:16px;}" +
                    "</style></head><body>" +
                    "<div class='card'>" +
                    "<h2>Glance TV 网页地址配置</h2>" +
                    "<p>当前加载地址：<strong>" + escapeHtml(currentTargetUrl) + "</strong></p>" +
                    msgHtml +
                    "<form method='POST' action='/set'>" +
                    "<label>新的目标网址 (URL)：</label>" +
                    "<input type='text' name='url' value='" + escapeHtml(currentTargetUrl) + "' placeholder='http://192.168.1.144:6278' required />" +
                    "<div class='btns'>" +
                    "<button type='submit' class='primary'>保存并立即切换</button>" +
                    "<a href='/reload' class='btn secondary'>仅刷新当前页</a>" +
                    "</div>" +
                    "</form>" +
                    "</div></body></html>";
        }

        private void sendHtmlResponse(OutputStream out, String html) throws IOException {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }

        private void sendJsonResponse(OutputStream out, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }
}
