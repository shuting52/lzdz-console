package com.yuntai;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 云台-懒得找了 · 云端总控台（WebView 壳应用）
 * 加载内置的 console/index.html，通过 GitHub API 完全对接仓库，
 * 在手机上即可后台维护更新项目全部内容。
 *
 * 1. WebChromeClient.onShowFileChooser：页面 <input type="file"> 打开系统文件选择器（本地上传）
 * 2. JS 桥 Android.download(url, name)：页面调用原生系统下载管理器下载 APK/文件
 * 3. JS 桥 Android.downloadAndInstall(url, name)：控制台自更新——下载新版本 APK 并自动安装替换老版本
 *    （控制台程序更新与本体软件完全分离）
 * 4. JS 桥 Android.getVersionCode() / getVersionName()：把「真实已安装版本号」暴露给页面，
 *    控制台据此判断是否需要更新，避免依赖容易漏改的硬编码常量。
 *
 * ── v1.0.12 修复「控制台新版本推不到老版本」──
 *   · 下载改到应用私有目录（cacheDir），不再写公共 Download 目录，无需任何存储权限，
 *     彻底解决 Android 10+ 上 FileOutputStream 抛 EACCES 导致「控制台更新下载失败」。
 *   · 本地版本以真实安装包 versionCode 为准（getVersionCode），不再依赖过期的内置常量。
 *   · 安装优先走 PackageInstaller 系统会话（原子替换、保留数据），失败回退 FileProvider。
 *   · 签名不一致（INSTALL_FAILED_UPDATE_INCOMPATIBLE）时明确提示并引导卸载旧版本。
 *   · 下载失败自动尝试 jsDelivr 镜像，避免 raw.githubusercontent.com 被墙导致更新失败。
 */
public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_RESULT_CODE = 101;
    private static final String ACTION_INSTALL_RESULT = "com.yuntai.CONSOLE_INSTALL_RESULT";
    private static final int INSTALL_RESULT_REQUEST_CODE = 200;

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private BroadcastReceiver installReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        registerInstallReceiver();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());

        // 关键：WebChromeClient 支持文件选择（本地上传 APK/MD/ZIP/图片/视频）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView wv,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (ActivityNotFoundException e) {
                    uploadMessage = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                try {
                    runOnUiThread(() -> request.grant(request.getResources()));
                } catch (Exception e) {
                    request.deny();
                }
            }
        });

        // JS 桥：Android.download(url, name) -> 系统下载管理器下载 APK/文件
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void download(String url, String name) {
                try {
                    String safeName = (name == null || name.trim().isEmpty())
                            ? "yuntai_download.bin"
                            : name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setTitle("云台-懒得找了 · 文件下载");
                    req.setDescription(safeName);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setAllowedOverMetered(true);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName);
                    dm.enqueue(req);
                } catch (Exception e) {
                    // 下载器失败时退回浏览器打开
                    runOnUiThread(() -> {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }

            // 控制台自更新：下载新版本 APK 到应用私有目录，然后自动触发安装（替换老版本）
            @JavascriptInterface
            public void downloadAndInstall(final String url, final String name) {
                final String safeName = (name == null || name.trim().isEmpty())
                        ? "console-update.apk"
                        : name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                if (!ensureInstallPermission()) return;
                new Thread(() -> {
                    File apk = null;
                    String lastError = "";
                    for (String candidate : mirrorCandidates(url)) {
                        try {
                            apk = downloadToPrivate(candidate, safeName);
                            lastError = "";
                            break;
                        } catch (Exception e) {
                            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                        }
                    }
                    final File result = apk;
                    final String err = lastError;
                    runOnUiThread(() -> {
                        if (result != null && isValidApk(result)) {
                            installApk(result);
                        } else if (result != null) {
                            toast("控制台安装包不完整，请稍后重试");
                        } else {
                            toast("控制台更新下载失败：" + err + "（请检查网络后重试）");
                        }
                    });
                }).start();
            }

            // 真实已安装版本号（versionCode）——控制台据此判断是否需要更新
            @JavascriptInterface
            public String getVersionCode() {
                try {
                    PackageManager pm = getPackageManager();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        return String.valueOf(pm.getPackageInfo(getPackageName(), 0).getLongVersionCode());
                    }
                    return String.valueOf(pm.getPackageInfo(getPackageName(), 0).versionCode);
                } catch (Exception e) {
                    return "0";
                }
            }

            // 真实已安装版本名（versionName）
            @JavascriptInterface
            public String getVersionName() {
                try {
                    return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                } catch (Exception e) {
                    return "";
                }
            }

            @JavascriptInterface
            public void toast(String msg) {
                runOnUiThread(() -> toastInternal(msg));
            }
        }, "Android");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/console/index.html");
    }

    // ==================== 控制台自更新（安装） ====================

    /** 下载到应用私有目录（无需任何存储权限，避免 Android 10+ 公共目录写入失败） */
    private File downloadToPrivate(String url, String safeName) throws IOException {
        File dir = new File(getCacheDir(), "console_update");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        File apkFile = new File(dir, safeName);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) YuntaiUpdater/1.1");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        long total = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(apkFile)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
        if (total <= 0) {
            throw new IOException("下载内容为空");
        }
        return apkFile;
    }

    /** 备用镜像：raw.githubusercontent.com -> jsDelivr CDN 多节点 + 国内镜像（v1.0.15 增强） */
    private List<String> mirrorCandidates(String url) {
        List<String> list = new ArrayList<>();
        if (url != null && !url.isEmpty()) list.add(url);
        try {
            String marker = "raw.githubusercontent.com/";
            if (url != null && url.contains(marker)) {
                String rest = url.substring(url.indexOf(marker) + marker.length());
                String[] parts = rest.split("/", 4);
                if (parts.length == 4) {
                    String gh = parts[0] + "/" + parts[1] + "@" + parts[2] + "/" + parts[3];
                    // jsDelivr 多节点（主站被 DNS 污染时尝试备用节点）
                    list.add("https://testingcf.jsdelivr.net/gh/" + gh);
                    list.add("https://cdn.jsdelivr.net/gh/" + gh);
                    list.add("https://fastly.jsdelivr.net/gh/" + gh);
                    list.add("https://gcore.jsdelivr.net/gh/" + gh);
                    // 国内加速镜像（raw 代理）
                    list.add("https://ghfast.top/https://raw.githubusercontent.com/" + rest);
                    list.add("https://ghproxy.net/https://raw.githubusercontent.com/" + rest);
                    list.add("https://raw.gitmirror.com/" + rest);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 校验下载产物是否为有效 APK（ZIP 魔数 + 最小体积） */
    private boolean isValidApk(File f) {
        try {
            if (f == null || !f.exists() || f.length() < 100 * 1024) return false;
            try (InputStream in = new FileInputStream(f)) {
                byte[] h = new byte[2];
                if (in.read(h) != 2) return false;
                return h[0] == 'P' && h[1] == 'K';
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void installApk(File apkFile) {
        // 优先 PackageInstaller 系统会话（原子替换旧版本、保留数据），失败回退 FileProvider
        if (installViaPackageInstaller(apkFile)) return;
        installViaFileProvider(apkFile);
    }

    /** PackageInstaller 会话安装（参照本体软件 AppUpdater 实现） */
    private boolean installViaPackageInstaller(File apkFile) {
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int sessionId = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sessionId);
            try {
                try (OutputStream out = session.openWrite("console_update.apk", 0, apkFile.length());
                     InputStream in = new FileInputStream(apkFile)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
            } finally {
                session.close();
            }
            Intent resultIntent = new Intent(ACTION_INSTALL_RESULT).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(
                    this,
                    INSTALL_RESULT_REQUEST_CODE,
                    resultIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            session.commit(pending.getIntentSender());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** FileProvider + 系统安装器（通用兜底方案） */
    private void installViaFileProvider(File file) {
        try {
            Uri apkUri = FileProvider.getUriForFile(this, "com.yuntai.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toastInternal("控制台新版本已下载，请点击安装完成更新");
        } catch (Exception e) {
            toastInternal("安装被拦截，请到系统设置允许安装未知应用后重试");
        }
    }

    /** 安装结果接收器：驱动「安装中 → 完成 / 失败（含签名冲突引导卸载）」 */
    private void registerInstallReceiver() {
        installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(confirm);
                        } catch (Exception ignored) {
                        }
                    }
                    return;
                }
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    toastInternal("控制台已更新到新版本，请重新打开");
                    return;
                }
                String msg = message == null ? "" : message;
                boolean signatureConflict = msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                        || msg.contains("signatures do not match")
                        || msg.contains("signature");
                if (signatureConflict) {
                    toastInternal("新版本与已安装版本签名不一致，需先卸载旧版控制台再安装");
                    openUninstall();
                } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    toastInternal("已取消安装");
                } else {
                    toastInternal("安装失败，请稍后重试");
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_RESULT);
        ContextCompat.registerReceiver(this, installReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** Android 8+ 需要「允许安装未知应用」权限 */
    private boolean ensureInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    toastInternal("请先允许「安装未知应用」，返回后重试");
                    return false;
                }
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    private void openUninstall() {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void toastInternal(String msg) {
        try {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (installReceiver != null) {
            try {
                unregisterReceiver(installReceiver);
            } catch (Exception ignored) {
            }
            installReceiver = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
