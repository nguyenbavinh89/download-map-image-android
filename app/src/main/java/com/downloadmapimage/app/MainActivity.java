package com.downloadmapimage.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final String HOME_URL = "https://downloadmapimage.com/";
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WavyHeaderView headerBar = findViewById(R.id.header_bar);

        // Pass the real status-bar height to the wavy header so it covers the notch/camera area
        ViewCompat.setOnApplyWindowInsetsListener(headerBar, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ((WavyHeaderView) v).setStatusBarHeight(top);
            return insets;
        });

        setupWebView();
        setupDownloadListener();
        registerDownloadReceiver();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }

        // Handle URL shared from Google Maps or other apps
        handleSharedIntent(getIntent());
    }

    /**
     * Called on first launch (onCreate) and on subsequent shares (onNewIntent).
     * - Nếu trang downloadmapimage.com đã load sẵn → inject URL + click Get Images ngay lập tức.
     * - Nếu trang chưa load → chờ onPageFinished rồi inject.
     * Luôn xóa textarea cũ trước khi điền URL mới.
     */
    private void handleSharedIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText == null || sharedText.trim().isEmpty()) return;

        String url = extractUrl(sharedText);
        if (url == null) return;

        final String finalUrl = url;

        // Kiểm tra xem trang đã ở downloadmapimage.com chưa
        String currentUrl = webView.getUrl();
        boolean pageReady = currentUrl != null && currentUrl.contains("downloadmapimage.com");

        if (pageReady) {
            // Trang đã sẵn sàng — inject ngay (cơ chế retry/poll trong injectUrlAndSubmit
            // sẽ tự chờ cho tới khi textarea + button thực sự sẵn sàng)
            mainHandler.postDelayed(() -> injectUrlAndSubmit(webView, finalUrl), 100);
        } else {
            // Trang chưa load — set WebViewClient chờ onPageFinished
            webView.setWebViewClient(new WebViewClient() {
                private boolean injected = false;

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return handleUrlRouting(request.getUrl().toString());
                }

                @Override
                public void onPageStarted(WebView view, String u, Bitmap favicon) {
                    if (handleUrlRouting(u)) { view.stopLoading(); return; }
                    super.onPageStarted(view, u, favicon);
                }

                @Override
                public void onPageFinished(WebView view, String pageUrl) {
                    super.onPageFinished(view, pageUrl);
                    injectNewTabInterceptor(view);
                    if (!injected && pageUrl != null && pageUrl.contains("downloadmapimage.com")) {
                        injected = true;
                        mainHandler.postDelayed(() -> injectUrlAndSubmit(view, finalUrl), 300);
                    }
                }
            });
        }
    }

    // =========================================================================
    // WEBVIEW
    // =========================================================================

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MapDownloaderApp/1.1.1");
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrlRouting(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Bắt các scheme lạ bị load qua JS redirect (intent://, etc.)
                if (handleUrlRouting(url)) {
                    view.stopLoading();
                    return;
                }
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectNewTabInterceptor(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                            openInBrowser(url);
                            v.stopLoading();
                        }
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private void injectNewTabInterceptor(WebView view) {
        String js =
            "(function(){" +
            "  document.querySelectorAll('a[target]').forEach(function(a){a.removeAttribute('target');});" +
            "  document.addEventListener('click',function(e){" +
            "    var a=e.target.closest('a'); if(!a) return;" +
            "    var h=a.href||'';" +
            "    if(h.startsWith('blob:')||h.startsWith('data:')){" +
            "      e.preventDefault();" +
            "      window.location.href='androidapp://openbrowser?url='+encodeURIComponent(h);" +
            "    }" +
            "  },true);" +
            "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(js, null);
        } else {
            view.loadUrl("javascript:" + js);
        }
    }

    /**
     * Extract the first http/https URL from a string.
     * Google Maps shares text like: "Jefferson Hotel\nhttps://maps.app.goo.gl/xxx"
     */
    private String extractUrl(String text) {
        if (text == null) return null;
        for (String token : text.split("\\s+")) {
            if (token.startsWith("http://") || token.startsWith("https://")) {
                return token.trim();
            }
        }
        return null;
    }

    /**
     * Inject the shared URL into the textarea on downloadmapimage.com and click Get Images.
     * Xóa nội dung cũ trước, cuộn lên đầu trang, rồi điền URL mới và submit.
     *
     * Để khắc phục lỗi "thi thoảng" (đã điền URL nhưng không tự nhấn Get Images):
     * - Poll/retry cho tới khi cả textarea và button đều tồn tại trong DOM (tối đa ~3s),
     *   tránh trường hợp evaluateJavascript chạy trước khi trang/JS của plugin sẵn sàng.
     * - Dùng "native setter" của HTMLTextAreaElement để set value, để các framework
     *   kiểu React/Vue (theo dõi value qua descriptor riêng) nhận diện được thay đổi.
     * - Dispatch đầy đủ chuỗi sự kiện pointerdown/mousedown/mouseup/click khi bấm nút,
     *   vì một số UI framework không lắng nghe click "giả" đơn thuần.
     */
    private void injectUrlAndSubmit(WebView view, String url) {
        injectUrlAndSubmit(view, url, 0);
    }

    private static final int INJECT_MAX_ATTEMPTS = 10;   // ~10 * 300ms = 3s timeout
    private static final int INJECT_RETRY_DELAY_MS = 300;

    private void injectUrlAndSubmit(WebView view, String url, int attempt) {
        if (view == null) return;

        String safeUrl = url.replace("\\", "\\\\").replace("'", "\\'");
        String js =
            "(function(){" +
            "  try{" +
            "    var ta = document.querySelector('textarea');" +
            "    var btn = document.querySelector('button[type=submit], input[type=submit]');" +
            "    if(!btn){" +
            "      var btns = document.querySelectorAll('button');" +
            "      for(var i=0;i<btns.length;i++){" +
            "        if(/get.?image/i.test(btns[i].textContent)){btn=btns[i];break;}" +
            "      }" +
            "    }" +
            // Nếu chưa thấy đủ textarea + button thì báo 'retry' để Java thử lại
            "    if(!ta || !btn){ return 'retry'; }" +
            // Cuộn lên đầu trang để user thấy quá trình
            "    window.scrollTo(0, 0);" +
            // Dùng native setter để framework (React/Vue) nhận diện thay đổi value
            "    var proto = window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype;" +
            "    var nativeSetter = proto && Object.getOwnPropertyDescriptor(proto, 'value') && Object.getOwnPropertyDescriptor(proto, 'value').set;" +
            "    function setVal(v){" +
            "      if(nativeSetter){ nativeSetter.call(ta, v); } else { ta.value = v; }" +
            "      ta.dispatchEvent(new Event('input', {bubbles:true}));" +
            "    }" +
            // Xóa nội dung cũ hoàn toàn trước khi điền mới
            "    setVal('');" +
            "    setVal('" + safeUrl + "');" +
            "    ta.dispatchEvent(new Event('change', {bubbles:true}));" +
            "    ta.dispatchEvent(new Event('blur', {bubbles:true}));" +
            // Bấm nút bằng chuỗi sự kiện đầy đủ (pointer + mouse + click)
            "    var rect = btn.getBoundingClientRect();" +
            "    var cx = rect.left + rect.width/2, cy = rect.top + rect.height/2;" +
            "    ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(type){" +
            "      var EventCtor = (type.indexOf('pointer')===0 && window.PointerEvent) ? window.PointerEvent : window.MouseEvent;" +
            "      try{" +
            "        btn.dispatchEvent(new EventCtor(type, {bubbles:true, cancelable:true, view:window, clientX:cx, clientY:cy}));" +
            "      }catch(e){" +
            "        btn.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window}));" +
            "      }" +
            "    });" +
            "    btn.click();" +
            "    return 'done';" +
            "  }catch(e){ return 'error:' + (e && e.message); }" +
            "})();";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(js, result -> {
                // result là chuỗi JSON, ví dụ "\"retry\"" hoặc "\"done\""
                boolean needsRetry = result == null || result.contains("retry") || result.equals("null");
                if (needsRetry && attempt < INJECT_MAX_ATTEMPTS) {
                    mainHandler.postDelayed(
                            () -> injectUrlAndSubmit(view, url, attempt + 1),
                            INJECT_RETRY_DELAY_MS);
                }
            });
        } else {
            // API < 19: không có evaluateJavascript callback nên không biết kết quả 'retry'/'done'.
            // Chạy một lần; nếu trang chưa sẵn sàng thì JS tự return 'retry' nhưng Java không đọc được.
            // Thiết bị API < 19 (Android < 4.4) hiện rất hiếm nên chấp nhận hạn chế này.
            view.loadUrl("javascript:" + js);
        }
    }

    // =========================================================================
    // DOWNLOAD — tải ảnh bằng HttpURLConnection thẳng vào file, không phụ thuộc
    // vào DownloadManager hay MediaStore path (tránh lỗi /mnt/shared/ của giả lập)
    // =========================================================================

    private void setupDownloadListener() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            showToast("downloading", "Downloading image...");
            String fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType));

            // Thử lấy thư mục Pictures chuẩn — nếu lỗi thì fallback về app-private cache
            File destDir = getSafePicturesDir();
            File destFile = new File(destDir, fileName);

            // Tải trên background thread
            executor.execute(() -> downloadFile(url, userAgent, destFile));
        });
    }

    /**
     * Trả về thư mục lưu ảnh — chỉ dùng cho API ≤ 28 (cần WRITE_EXTERNAL_STORAGE).
     * API 29+ dùng MediaStore thay thế, không cần thư mục này.
     */
    private File getSafePicturesDir() {
        File ext = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        if (ext != null) {
            File dir = new File(ext, "MapDownloader");
            if (mkdirs(dir)) return dir;
        }
        File extFiles = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (extFiles != null && mkdirs(extFiles)) return extFiles;
        return getCacheDir();
    }

    private boolean mkdirs(File dir) {
        try {
            if (!dir.exists()) dir.mkdirs();
            return dir.exists() && dir.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Tải file từ URL và lưu vào Gallery.
     * - API 29+ (Android 10+): dùng MediaStore.Images — KHÔNG cần bất kỳ storage permission nào.
     * - API 28-  (Android 9-): ghi file ra Pictures/ với WRITE_EXTERNAL_STORAGE.
     */
    private void downloadFile(String url, String userAgent, File destFileLegacy) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Referer", HOME_URL);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP error: " + code);
            }

            in = conn.getInputStream();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // ── API 29+ : MediaStore ─────────────────────────────────────────
                // Hệ thống tự cấp quyền ghi, không cần WRITE_EXTERNAL_STORAGE.
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, destFileLegacy.getName());
                cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/MapDownloader");
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);

                android.content.ContentResolver resolver = getContentResolver();
                android.net.Uri imgUri = resolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (imgUri == null) throw new Exception("MediaStore insert failed");

                try (OutputStream out = resolver.openOutputStream(imgUri)) {
                    if (out == null) throw new Exception("Cannot open output stream");
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                    out.flush();
                }

                // Đánh dấu hoàn thành — ảnh hiện ngay trong Gallery
                cv.clear();
                cv.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(imgUri, cv, null, null);

            } else {
                // ── API 28- : ghi file trực tiếp ────────────────────────────────
                OutputStream out = new java.io.FileOutputStream(destFileLegacy);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                out.flush();
                out.close();
                scanFileToGallery(destFileLegacy);
            }

            mainHandler.post(() -> showToast("success", "Saved to gallery"));

        } catch (Exception e) {
            if (destFileLegacy.exists()) destFileLegacy.delete();
            final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            mainHandler.post(() -> showToast("error", "Download failed: " + msg));
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Thông báo MediaScanner để ảnh xuất hiện ngay trong Gallery mà không cần reboot.
     */
    private void scanFileToGallery(File file) {
        try {
            Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scanIntent.setData(Uri.fromFile(file));
            sendBroadcast(scanIntent);
        } catch (Exception ignored) {}

        // Android 10+ dùng thêm MediaScannerConnection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.media.MediaScannerConnection.scanFile(
                this,
                new String[]{file.getAbsolutePath()},
                null,
                (path, uri) -> {
                    // scan done
                }
            );
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Làm sạch tên file: bỏ ký tự lạ, giới hạn 60 ký tự, đảm bảo có đuôi .jpg
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "map_" + System.currentTimeMillis() + ".jpg";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        String ext = ".jpg";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            ext = name.substring(dot);
            name = name.substring(0, dot);
        }
        if (name.length() > 50) name = name.substring(0, 50);
        return name + ext;
    }

    /**
     * Central URL routing: returns true if the URL was handled externally.
     * Used by both the default and share-mode WebViewClients.
     */
    private boolean handleUrlRouting(String url) {
        if (url == null) return false;

        // Custom scheme — open decoded URL in external browser
        if (url.startsWith("androidapp://openbrowser")) {
            try {
                String encoded = url.split("\\?url=")[1];
                String decoded = java.net.URLDecoder.decode(encoded, "UTF-8");
                openInBrowser(decoded);
            } catch (Exception ignored) {}
            return true;
        }

        // Android Intent scheme (intent://...) — WebView không xử lý được, parse và mở browser
        if (url.startsWith("intent://")) {
            try {
                Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                // Ưu tiên browser_fallback_url nếu có (Google thường nhúng sẵn)
                String fallback = parsed.getStringExtra("browser_fallback_url");
                if (fallback != null && !fallback.isEmpty()) {
                    openInBrowser(fallback);
                } else {
                    // Tự dựng URL: thay intent:// → https://, bỏ phần #Intent;...;end
                    String dataUrl = url.replaceFirst("^intent://", "https://")
                                       .replaceAll("#Intent;.*", "");
                    openInBrowser(dataUrl);
                }
            } catch (Exception e) {
                openInBrowser("https://maps.google.com");
            }
            return true;
        }

        // google.com/maps — mở trong browser thay vì WebView
        if (url.contains("google.com/maps") || url.contains("maps.google.com")) {
            openInBrowser(url);
            return true;
        }

        // Google Maps short links — avoid ERR_UNKNOWN_URL_SCHEME in WebView
        if (url.contains("maps.app.goo.gl") || url.contains("goo.gl/maps")) {
            openInBrowser(url);
            return true;
        }

        // Google image/API URLs — open externally so Back doesn't lose the results page
        // Covers: googleusercontent.com, ggpht.com, streetviewpixels-pa.googleapis.com, lh*.google.com
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")
                || url.contains("googleapis.com") || url.contains("lh3.google.com")
                || url.contains("lh4.google.com") || url.contains("lh5.google.com")) {
            openInBrowser(url);
            return true;
        }

        // PayPal and external payment/donation links — open in default browser
        if (url.contains("paypal.com") || url.contains("paypal.me")) {
            openInBrowser(url);
            return true;
        }

        // Social / messaging app schemes — open in the respective app
        // whatsapp://, tg:, fb://, twitter://, mailto:, tel:, sms:, etc.
        if (url.startsWith("whatsapp://") || url.startsWith("tg:")
                || url.startsWith("mailto:") || url.startsWith("tel:")
                || url.startsWith("sms:") || url.startsWith("fb://")
                || url.startsWith("twitter://") || url.startsWith("instagram://")
                || url.startsWith("market://")) {
            openInBrowser(url);  // openInBrowser dùng ACTION_VIEW — hệ thống tự chọn đúng app
            return true;
        }

        // Bất kỳ scheme nào không phải http/https/about/javascript đều mở ngoài
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("about:") && !url.startsWith("javascript:")) {
            openInBrowser(url);
            return true;
        }

        return false;
    }

    private void openInBrowser(String url) {
        if (url == null || url.isEmpty() || url.startsWith("blob:") || url.startsWith("data:")) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            showToast("error", "Cannot open browser");
        }
    }

    /**
     * Show a styled custom toast.
     * - downloading : nền đỏ (#C62828), emoji ⏳ quay tròn liên tục
     * - success     : nền trắng, chữ đậm tối, dấu tích xanh ✅ (tránh trùng nền)
     * - error       : nền đỏ đậm, ⚠️
     */
    private void showToast(String type, String message) {
        int bgColor;
        int textColor;
        String icon;
        boolean spin = false;

        switch (type) {
            case "success":
                // Nền tối đậm để dấu tích xanh ✅ nổi bật
                bgColor    = Color.parseColor("#1A237E");  // xanh navy đậm
                textColor  = Color.WHITE;
                icon       = "✅";
                break;
            case "error":
                bgColor    = Color.parseColor("#B71C1C");
                textColor  = Color.WHITE;
                icon       = "⚠️";
                break;
            default: // downloading
                bgColor    = Color.parseColor("#C62828"); // đỏ brand
                textColor  = Color.WHITE;
                icon       = "⏳";
                spin       = true;
                break;
        }

        // Root layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(13), dpToPx(20), dpToPx(13));

        // Background pill
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dpToPx(40));
        // Stroke trắng cho tất cả toast
        bg.setStroke(dpToPx(2), Color.WHITE);
        layout.setBackground(bg);

        // Emoji icon
        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(28);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40));
        iconParams.setMarginEnd(dpToPx(4));
        iconView.setLayoutParams(iconParams);
        iconView.setGravity(Gravity.CENTER);
        // Pivot ở giữa để xoay đúng tâm
        iconView.setPivotX(dpToPx(20));
        iconView.setPivotY(dpToPx(20));

        // Divider
        android.view.View divider = new android.view.View(this);
        int divColor = Color.argb(80, 255, 255, 255);
        divider.setBackgroundColor(divColor);
        LinearLayout.LayoutParams divParams =
                new LinearLayout.LayoutParams(dpToPx(1), dpToPx(22));
        divParams.setMarginStart(dpToPx(6));
        divParams.setMarginEnd(dpToPx(12));
        divider.setLayoutParams(divParams);

        // Message text
        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setTextColor(textColor);
        msgView.setTextSize(15);
        msgView.setTypeface(null, android.graphics.Typeface.BOLD);
        msgView.setMaxLines(2);

        layout.addView(iconView);
        layout.addView(divider);
        layout.addView(msgView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            layout.setElevation(dpToPx(10));
        }

        Toast toast = new Toast(getApplicationContext());
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setView(layout);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dpToPx(90));
        toast.show();

        // Spin animation cho ⏳ (downloading)
        if (spin && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            ObjectAnimator rotator = ObjectAnimator.ofFloat(iconView, "rotation", 0f, 360f);
            rotator.setDuration(900);
            rotator.setRepeatCount(ValueAnimator.INFINITE);
            rotator.setInterpolator(new android.view.animation.LinearInterpolator());
            // Delay nhỏ để View đã được attach trước khi animate
            mainHandler.postDelayed(rotator::start, 80);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // =========================================================================
    // BROADCAST RECEIVER (chỉ để tương thích cũ)
    // =========================================================================

    private void registerDownloadReceiver() {
        try {
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(downloadReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            receiverRegistered = false;
        }
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { /* handled in downloadFile */ }
    };

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    /**
     * Called instead of onCreate when app is already running (singleTask) and
     * receives a new share intent. Routes it through the same handleSharedIntent logic.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // update getIntent() for future calls
        handleSharedIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (webView != null) webView.saveState(out);
    }

    @Override protected void onResume()  { super.onResume();  if (webView != null) webView.onResume(); }
    @Override protected void onPause()   { super.onPause();   if (webView != null) webView.onPause(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }
}
