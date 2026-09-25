package com.rpcsv.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;


public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int REQ_DIR = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_CAMERA = 3;
    private static final String DEF_PKG = "org.vita3k.emulator";
    private static final String DEF_PKG2 = "org.vita3kplus.emulator";
    /** Teto de leitura de texto via bridge: evita OOM com PKG/PUP/ISO. */
    private static final long MAX_TEXT_FILE = 16L * 1024 * 1024;
    /** Teto de base64 via bridge: so e usado para icones PNG. */
    private static final long MAX_BASE64_FILE = 8L * 1024 * 1024;

    private WebView web;
    private static final Object FW_INSTALL_LOCK = new Object();
    private static final Object NATIVE_LOCK = new Object();
    /** Resultado do bootstrap nativo, para nao repetir a cada chamada. */
    private static Boolean nativeSessionState = null;

    private final Bus bus = new Bus();
    private final List<String> pendingDirHandlers = new ArrayList<String>();
    private final List<String> pendingFileHandlers = new ArrayList<String>();
    private final List<String> pendingCameraHandlers = new ArrayList<String>();
    private Uri cameraUri;
    private Uri lastPickedUri;
    private final Map<String, String> uriByPath = new HashMap<String, String>();
    private boolean immersive = true;

    private static class Bus {
        private final List<String> out = Collections.synchronizedList(new ArrayList<String>());
        private void push(JSONObject o) { out.add(o.toString()); }
        void reply(String id, Object v) {
            JSONObject o = new JSONObject();
            try {
                o.put("k", "r");
                o.put("id", Integer.parseInt(id));
                if (v == null) o.put("v", JSONObject.NULL);
                else if (v instanceof String || v instanceof Number || v instanceof Boolean) o.put("v", v);
                else o.put("v", v);
            } catch (Exception e) {
                Log.w("RPCSV", "Bus.reply descartada (id invalido: " + id + ")");
                return;
            }
            push(o);
        }
        void event(String name, JSONObject payload) {
            JSONObject o = new JSONObject();
            try { o.put("k", "e"); o.put("n", name); o.put("v", payload == null ? JSONObject.NULL : payload); } catch (Exception e) { return; }
            push(o);
        }
        String drain() {
            if (out.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            synchronized (out) {
                for (int i = 0; i < out.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(out.get(i));
                }
                out.clear();
            }
            sb.append(']');
            return sb.toString();
        }
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignore) {}
        return o;
    }
    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", msg); } catch (Exception ignore) {}
        return o;
    }
    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignore) {}
    }
    private static String arg(JSONObject a, String k, String dflt) {
        return a == null ? dflt : a.optString(k, dflt);
    }
    private static long argLong(JSONObject a, String k, long dflt) {
        return a == null ? dflt : a.optLong(k, dflt);
    }
    private static String readStream(InputStream in, int cap) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int total = 0, n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total >= cap) break;
            }
            return bo.toString("UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * Abre a conexao na rede "certa" em vez de deixar o Android escolher.
     *
     * Sem isso o update checker falhava com "No address associated with
     * hostname" em aparelhos com duas redes ativas: a cellular veio sem
     * servidor DNS (DnsAddresses vazio) e ficou com o roteamento, enquanto o
     * shell resolvia nome normalmente pela WiFi. Aqui a rede e fixada
     * explicitamente, com fallback para qualquer rede com INTERNET e, por
     * ultimo, para o caminho padrao.
     */
    private HttpURLConnection openNet(String url) throws Exception {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                Network best = null;
                int bestScore = -1;
                Network[] all = cm.getAllNetworks();
                for (Network nw : all) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(nw);
                    if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                    int score = 0;
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 4;
                    if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) score += 1;
                    if (cm.getActiveNetwork() != null && nw.equals(cm.getActiveNetwork())) score += 2;
                    if (score > bestScore) { bestScore = score; best = nw; }
                }
                StringBuilder dbg = new StringBuilder("openNet redes=" + (all == null ? -1 : all.length) + " chosen=" + best);
                if (all != null) for (Network nw : all) {
                    NetworkCapabilities cc = cm.getNetworkCapabilities(nw);
                    android.net.LinkProperties lp = cm.getLinkProperties(nw);
                    dbg.append(" | ").append(nw).append(" caps=").append(cc).append(" dns=").append(lp == null ? "?" : lp.getDnsServers());
                }
                AppLog.i("DBG " + dbg.toString());
                if (best != null) {
                    HttpURLConnection hc = (HttpURLConnection) best.openConnection(new URL(url));
                    if (hc != null) return hc;
                }
            } catch (Throwable ignore) { AppLog.e("DBG openNet falhou", ignore); }
        }
        AppLog.i("DBG openNet usando caminho padrao");
        return (HttpURLConnection) new URL(url).openConnection();
    }

    private String enginePkg() {
        PackageManager pm = getPackageManager();
        String[] cands = new String[] { DEF_PKG, DEF_PKG2 };
        for (String p : cands) {
            try {
                pm.getPackageInfo(p, 0);
                android.content.ComponentName c =
                        new android.content.ComponentName(p, p + ".Emulator");
                if (pm.resolveActivity(new Intent().setComponent(c),
                        PackageManager.MATCH_DEFAULT_ONLY) != null) return p;
            } catch (Exception ignore) {}
        }
        for (String p : cands) {
            try {
                pm.getPackageInfo(p, 0);
                if (pm.getLaunchIntentForPackage(p) != null) return p;
            } catch (Exception ignore) {}
        }
        return null;
    }

    private File externalRoot() {
        File f = getExternalFilesDir(null);
        return f != null ? f : getFilesDir();
    }

    private String defaultInstallDir() {
        return new File(externalRoot(), "vita").getAbsolutePath();
    }

    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        AppLog.step("MainActivity.onCreate pid=" + android.os.Process.myPid());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                android.util.Log.i("RPCSV", "PAGE_LOADED " + url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                android.util.Log.i("RPCSV-WEB", cm.messageLevel() + ": " + cm.message());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) hideSystemUI();
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = web.getWindowInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            web.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = web.getWindowInsetsController();
            if (c != null) c.show(android.view.WindowInsets.Type.systemBars());
        } else {
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (!immersive) {
            immersive = true;
            hideSystemUI();
            return;
        }
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------
    private class Bridge {
        @JavascriptInterface
        public void call(final String method, final String argsJson, final String id) {
            main.post(new Runnable() {
                public void run() {
                    try {
                        JSONObject a = (argsJson == null || argsJson.isEmpty()) ? new JSONObject() : new JSONObject(argsJson);
                        dispatch(method, a, id);
                    } catch (Throwable t) {
                        bus.reply(id, err("exception: " + t.getMessage()));
                    }
                }
            });
        }

        @JavascriptInterface
        public String poll() {
            return bus.drain();
        }
    }

    @SuppressWarnings("deprecation")
    private void dispatch(String method, JSONObject a, String id) throws Exception {
        String home = getFilesDir().getAbsolutePath();
        switch (method) {
            case "homeDir":
                bus.reply(id, getFilesDir().getAbsolutePath());
                return;
            case "storageDir":
                bus.reply(id, externalRoot().getAbsolutePath());
                return;
            case "defaultDir":
                bus.reply(id, defaultInstallDir());
                return;
            case "installDir":
                bus.reply(id, defaultInstallDir());
                return;
            case "version": {
                JSONObject v = new JSONObject();
                v.put("name", "RPCSV");
                v.put("codename", "com.rpcsv.app");
                // Lido do manifesto instalado: o update checker compara este
                // valor com a tag da release, entao ele nao pode divergir do
                // que o APK realmente e (a versao era fixa e nunca subia).
                String vn = "1.0.0";
                long vc = 1;
                try {
                    android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    if (pi.versionName != null && !pi.versionName.isEmpty()) vn = pi.versionName;
                    vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Exception e) {
                    Log.w("RPCSV", "version: PackageInfo falhou: " + e);
                }
                v.put("version", vn);
                v.put("versionCode", vc);
                v.put("platform", "android");
                bus.reply(id, v);
                return;
            }
            case "readFile": {
                final String path = arg(a, "path", "");
                final String rid = id;
                // Le fora da UI thread: antes isto alocava byte[(int) f.length()]
                // na main thread (OutOfMemoryError/ANR em arquivos grandes) e
                // ainda podia ler menos bytes que o esperado num unico read().
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            File f = new File(path);
                            if (!f.isFile() || f.length() > MAX_TEXT_FILE) {
                                bus.reply(rid, null);
                                return;
                            }
                            bus.reply(rid, readAll(f));
                        } catch (Throwable e) {
                            bus.reply(rid, null);
                        }
                    }
                }, "rpcsv-readfile");
                t.setDaemon(true);
                t.start();
                return;
            }
            case "sfoTitle": {
                bus.reply(id, PkgExtractor.readParamTitle(arg(a, "path", "")));
                return;
            }
            case "base64File": {
                final String b64path = arg(a, "path", "");
                final String rid2 = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        bus.reply(rid2, readPngAsBase64(new File(b64path)));
                    }
                }, "rpcsv-b64");
                t.setDaemon(true);
                t.start();
                return;
            }
            case "writeFile": {
                final String wpath = arg(a, "path", "");
                final String content = a.optString("content", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            File f = new File(wpath);
                            if (f.getParentFile() != null) f.getParentFile().mkdirs();
                            FileOutputStream os = new FileOutputStream(f);
                            try {
                                os.write(content.getBytes("UTF-8"));
                            } finally {
                                os.close();
                            }
                            bus.reply(rid, Boolean.TRUE);
                        } catch (Throwable e) {
                            bus.reply(rid, err(String.valueOf(e.getMessage())));
                        }
                    }
                }, "rpcsv-writefile");
                t.setDaemon(true);
                t.start();
                return;
            }
            case "exists":
                bus.reply(id, new File(arg(a, "path", "")).exists());
                return;
            case "probeEboot": {
                final String dir = arg(a, "path", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            File f = new File(dir, "eboot.bin");
                            JSONObject o = new JSONObject();
                            if (!f.exists()) {
                                o.put("kind", "missing");
                                o.put("size", 0L);
                                bus.reply(rid, o);
                                return;
                            }
                            byte[] hdr = new byte[4];
                            int r;
                            try (FileInputStream in = new FileInputStream(f)) {
                                r = in.read(hdr);
                            }
                            if (r < 4) {
                                o.put("kind", "tiny");
                                o.put("size", f.length());
                            } else if (hdr[0] == 0x53 && hdr[1] == 0x43 && hdr[2] == 0x45 && hdr[3] == 0x00) {
                                o.put("kind", "self");
                                o.put("size", f.length());
                            } else if (hdr[0] == 0x7F && hdr[1] == 0x45 && hdr[2] == 0x4C && hdr[3] == 0x46) {
                                o.put("kind", "elf");
                                o.put("size", f.length());
                            } else {
                                o.put("kind", "enc");
                                o.put("size", f.length());
                            }
                            bus.reply(rid, o);
                        } catch (Throwable ex) {
                            bus.reply(rid, err("probe falhou"));
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "mkdirs":
                bus.reply(id, new File(arg(a, "path", "")).mkdirs());
                return;
            case "deleteFile": {
                File f = new File(arg(a, "path", ""));
                bus.reply(id, f.exists() && deleteRec(f));
                return;
            }
            case "listDir": {
                File d = new File(arg(a, "path", ""));
                File[] kids = d.listFiles();
                if (kids == null) { bus.reply(id, null); return; }
                JSONArray arr = new JSONArray();
                for (File k : kids) {
                    JSONObject o = new JSONObject();
                    o.put("n", k.getName());
                    o.put("d", k.isDirectory());
                    arr.put(o);
                }
                bus.reply(id, arr);
                return;
            }
            case "copy": {
                final String src = arg(a, "src", "");
                final String dst = arg(a, "dst", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        boolean ok = copyFile(new File(src), new File(dst));
                        bus.reply(rid, ok);
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "move": {
                final String src = arg(a, "src", "");
                final String dst = arg(a, "dst", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        File s = new File(src);
                        File d = new File(dst);
                        boolean ok = false;
                        if (s.exists()) {
                            if (d.getParentFile() != null) d.getParentFile().mkdirs();
                            ok = s.renameTo(d);
                            if (!ok) ok = copyFile(s, d) && deleteRec(s);
                        }
                        bus.reply(rid, ok);
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "fwInstall": {
                final String pup = arg(a, "path", "");
                final String rid = id;
                if (pup.isEmpty()) { bus.reply(rid, err("caminho do PUP vazio")); return; }
                // O par init+installFirmware roda inteiro no worker rpcsv-fw-install.
                // Uma versao anterior rodava NativeLib.init na UI thread (main.post)
                // por causa de um comentario que afirmava que JNI em Thread "crua"
                // abortava com SIGSEGV. Isso foi medido e e FALSO: reproduzindo o
                // mesmo par contra o mesmo classes.dex da engine, em processo
                // separado e sem Activity nenhuma, os dois modos funcionam
                // (main thread e worker), com progresso completo e versao 3.74.
                // Manter init na UI thread so trazia risco de ANR: init carrega
                // libVita3K.so (27 MB) e monta a arvore vita/, e pode levar
                // centenas de ms. O worker tambem evita que o MemoryService do
                // MIUI mate a Activity da UI no meio da operacao.
                File pupFile = new File(pup);
                if (!pupFile.isFile() || pupFile.length() == 0) {
                    bus.reply(rid, err("arquivo PUP nao encontrado: " + pup));
                    return;
                }
                startFirmwareInstall(pup, rid);
                return;
            }
            case "pupVersion": {
                final String p = arg(a, "path", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            RandomAccessFile f = new RandomAccessFile(p, "r");
                            byte[] m = new byte[4];
                            f.readFully(m, 0, 4);
                            if (!(m[0] == 'S' && m[1] == 'C' && m[2] == 'E')) {
                                f.close();
                                bus.reply(rid, err("arquivo nao e um PUP valido"));
                                return;
                            }
                            f.seek(0x18);
                            int count = Integer.reverseBytes(f.readInt());
                            String ver = "";
                            for (int x = 0; x < count && x < 512; x++) {
                                f.seek(0x80L + x * 0x20L);
                                long type = Long.reverseBytes(f.readLong());
                                long off = Long.reverseBytes(f.readLong());
                                long len = Long.reverseBytes(f.readLong());
                                if (type == 0x100 && len > 0 && len < 0x20000) {
                                    byte[] b = new byte[(int) len];
                                    f.seek(off);
                                    f.readFully(b);
                                    ver = new String(b, "UTF-8").trim();
                                    break;
                                }
                            }
                            f.close();
                            if (ver.isEmpty()) {
                                bus.reply(rid, err("version.txt nao encontrado"));
                                return;
                            }
                            JSONObject o = new JSONObject();
                            o.put("ok", true);
                            o.put("version", ver);
                            bus.reply(rid, o);
                        } catch (Throwable e) {
                            bus.reply(rid, err(e.getMessage()));
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "zipList": {
                final String zpath = arg(a, "path", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            ZipInputStream zin = new ZipInputStream(new FileInputStream(zpath));
                            JSONArray arr = new JSONArray();
                            ZipEntry e;
                            while ((e = zin.getNextEntry()) != null) arr.put(e.getName());
                            zin.close();
                            bus.reply(rid, arr);
                        } catch (Exception ex) {
                            bus.reply(rid, null);
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "extractZip": {
                final String zip = arg(a, "zip", "");
                final String dest = arg(a, "dest", "");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        int n = -1;
                        try {
                            n = extractZip(new File(zip), new File(dest));
                        } catch (Exception ignore) {}
                        bus.reply(rid, n >= 0 ? n : -1);
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "installVpk": {
                final String v = arg(a, "path", "");
                final String base = arg(a, "base", defaultInstallDir());
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            JSONObject r = installVpk(new File(v), base);
                            bus.reply(rid, r);
                        } catch (Throwable e) {
                            bus.reply(rid, err(e.getMessage() != null ? e.getMessage() : e.toString()));
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "fwCheck": {
                final String region = arg(a, "region", "us");
                final String rid = id;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            JSONObject info = fetchUpdateInfo(region);
                            android.util.Log.i("RPCSV", "fwCheck region=" + region
                                    + " -> " + (info == null ? "null" : info.toString()));
                            bus.reply(rid, info == null ? err("no-update") : info);
                        } catch (Throwable e) {
                            android.util.Log.e("RPCSV", "fwCheck thread falhou: " + e);
                            bus.reply(rid, err("no-update"));
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "download": {
                startDownload(arg(a, "url", ""), arg(a, "dest", home + "/fw/PSP2UPDAT.PUP"), id);
                return;
            }
            case "installPkg": {
                final String ppath = arg(a, "path", "");
                final String zrif = arg(a, "zrif", "");
                final String wbin = arg(a, "workbin", "");
                final String base = arg(a, "base", defaultInstallDir());
                final String rid = id;
Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            File pf = new File(ppath);
                            android.util.Log.i("RPCSV", "installPkg PATH=" + ppath
                                    + " exists=" + pf.exists()
                                    + " isFile=" + pf.isFile()
                                    + " len=" + pf.length()
                                    + " base=" + base);
                            Map<String, Object> m = PkgExtractor.install(ppath, zrif, wbin, base);
                            JSONObject r = new JSONObject();
                            for (Map.Entry<String, Object> e : m.entrySet()) r.put(e.getKey(), e.getValue());
                            bus.reply(rid, r);
                        } catch (Throwable e) {
                            android.util.Log.e("RPCSV", "installPkg(" + ppath + ", zrif=" + (zrif != null && !zrif.isEmpty()) + ", wbin=" + wbin + ", base=" + base + ") falhou: " + e);
                            bus.reply(rid, err((e.getMessage() != null ? e.getMessage() : e.toString()) + " [pkg=" + ppath + "]"));
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                return;
            }
            case "launch": {
                String pkg = arg(a, "pkg", DEF_PKG);
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(DEF_PKG2);
                if (i == null) {
                    bus.reply(id, err("Vita3K not installed"));
                    return;
                }
                try {
                    startActivity(i);
                    bus.reply(id, ok());
                } catch (Exception e) {
                    bus.reply(id, err(e.getMessage()));
                }
                return;
            }
            case "launchTitle": {
                String titleId = arg(a, "titleId", "");
                if (titleId.isEmpty()) { bus.reply(id, err("no titleId")); return; }
                try {
                    Intent i = new Intent(this, EngineActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    i.putExtra("AppStartParameters", new String[] { "-r", titleId });
                    startActivity(i);
                    bus.reply(id, ok());
                } catch (Exception e) {
                    bus.reply(id, err(e.getMessage()));
                }
                return;
            }
            case "findEmulators": {
                JSONArray arr = new JSONArray();
                List<String> seen = new ArrayList<String>();
                PackageManager pm = getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    String name = ai.packageName;
                    if (!name.toLowerCase().contains("vita")) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", name);
                    o.put("label", pm.getApplicationLabel(ai).toString());
                    o.put("installed", true);
                    arr.put(o);
                    seen.add(name);
                }
                if (!seen.contains(DEF_PKG)) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", DEF_PKG);
                    o.put("label", "Vita3K");
                    o.put("installed", false);
                    arr.put(0, o);
                }
                bus.reply(id, arr);
                return;
            }
            case "pickDir":
                if (pendingDirHandlers.isEmpty()) {
                    pendingDirHandlers.add(id);
                } else {
                    // Um seletor ja esta aberto: nao perca a promessa do JS,
                    // apenas cancela a nova e mantem a antiga.
                    bus.reply(id, null);
                    return;
                }
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addCategory(Intent.CATEGORY_DEFAULT), REQ_DIR);
                } catch (Exception e) {
                    dropPending(pendingDirHandlers);
                    bus.reply(id, null);
                }
                return;
            case "pickFile": {
                if (pendingFileHandlers.isEmpty()) {
                    pendingFileHandlers.add(id);
                } else {
                    bus.reply(id, null);
                    return;
                }
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    dropPending(pendingFileHandlers);
                    bus.reply(id, null);
                }
                return;
            }
            case "hasAllFiles": {
                boolean has = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
                bus.reply(id, has);
                return;
            }
            case "allFilesSettings": {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(i);
                        bus.reply(id, ok());
                        return;
                    } catch (Exception ignore) {}
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        bus.reply(id, ok());
                        return;
                    } catch (Exception ignore) {}
                }
                bus.reply(id, err("unsupported"));
                return;
            }
            case "toast":
                Toast.makeText(this, arg(a, "msg", ""), Toast.LENGTH_LONG).show();
                bus.reply(id, true);
                return;
            case "mark":
                Log.i("RPCSV", "MARK " + arg(a, "tag", ""));
                bus.reply(id, true);
                return;
            case "cameraTake":
                if (!pendingCameraHandlers.isEmpty()) {
                    bus.reply(id, null);
                    return;
                }
                pendingCameraHandlers.add(id);
                cameraUri = null;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "rpcsv_" + System.currentTimeMillis() + ".jpg");
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    if (Build.VERSION.SDK_INT >= 29) {
                        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RPCSV");
                    }
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) {
                        dropPending(pendingCameraHandlers);
                        bus.reply(id, err("no media store"));
                        return;
                    }
                    cameraUri = uri;
                    Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                    i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    startActivityForResult(i, REQ_CAMERA);
                } catch (Exception e) {
                    cameraUri = null;
                    dropPending(pendingCameraHandlers);
                    bus.reply(id, err(e.getMessage()));
                }
                return;
            case "openExternal": {
                String url = arg(a, "url", "");
                if (url.isEmpty()) { bus.reply(id, false); return; }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    bus.reply(id, true);
                } catch (Exception e) {
                    bus.reply(id, false);
                }
                return;
            }
            case "updateFetch": {
                final String url = arg(a, "url", "");
                final String rid = id;
                final int cap = Math.max(1024, Math.min(4 * 1024 * 1024, (int) argLong(a, "maxBytes", 1024 * 1024)));
                if (!url.startsWith("https://")) { bus.reply(rid, err("somente https")); return; }
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        JSONObject o = new JSONObject();
                        HttpURLConnection c = null;
                        try {
                            c = openNet(url);
                            c.setConnectTimeout(12000);
                            c.setReadTimeout(20000);
                            c.setRequestProperty("Accept", "application/vnd.github+json, application/json");
                            c.setRequestProperty("User-Agent", "RPCSV-Android");
                            int st = c.getResponseCode();
                            InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
                            String body = in == null ? "" : readStream(in, cap);
                            put(o, "ok", st >= 200 && st < 300);
                            put(o, "status", st);
                            put(o, "body", body);
                            put(o, "truncated", in != null && body.length() >= cap);
                        } catch (Exception e) {
                            put(o, "ok", false);
                            put(o, "error", String.valueOf(e.getMessage()));
                        } finally {
                            if (c != null) c.disconnect();
                        }
                        bus.reply(rid, o);
                    }
                });
                t.start();
                return;
            }
            case "updateDownload": {
                final String url = arg(a, "url", "");
                final String rid = id;
                if (!url.startsWith("https://")) { bus.reply(rid, err("somente https")); return; }
                String name = arg(a, "name", "");
                name = name.replaceAll("[^A-Za-z0-9._-]", "_");
                if (name.isEmpty() || !name.toLowerCase().endsWith(".apk")) name = "RPCSV-update.apk";
                final String fname = name;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        File dir = getExternalFilesDir("update");
                        JSONObject o = new JSONObject();
                        if (dir == null) { put(o, "ok", false); put(o, "error", "sem armazenamento externo"); bus.reply(rid, o); return; }
                        if (!dir.isDirectory() && !dir.mkdirs()) { put(o, "ok", false); put(o, "error", "mkdir falhou"); bus.reply(rid, o); return; }
                        File tmp = new File(dir, fname + ".part");
                        File dst = new File(dir, fname);
                        HttpURLConnection c = null;
                        InputStream in = null;
                        OutputStream out = null;
                        try {
                            c = openNet(url);
                            c.setConnectTimeout(20000);
                            c.setReadTimeout(60000);
                            c.setInstanceFollowRedirects(true);
                            c.setRequestProperty("User-Agent", "RPCSV-Android");
                            int st = c.getResponseCode();
                            if (st < 200 || st >= 300) throw new IOException("HTTP " + st);
                            long total = c.getContentLength();
                            in = c.getInputStream();
                            out = new FileOutputStream(tmp);
                            byte[] buf = new byte[64 * 1024];
                            long got = 0, lastPct = -1;
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                                got += n;
                                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                                if (pct >= 0 && pct != lastPct && pct % 2 == 0) {
                                    lastPct = pct;
                                    try {
                                        JSONObject p = new JSONObject();
                                        p.put("pct", pct);
                                        p.put("got", got);
                                        p.put("total", total);
                                        bus.event("update:progress", p);
                                    } catch (Exception ignore) { }
                                }
                            }
                            out.flush();
                            out.close();
                            out = null;
                            if (total > 0 && got != total) throw new IOException("download incompleto (" + got + "/" + total + ")");
                            if (tmp.length() < 1024) throw new IOException("arquivo pequeno demais");
                            if (dst.exists() && !dst.delete()) throw new IOException("nao replacei o apk antigo");
                            if (!tmp.renameTo(dst)) throw new IOException("rename falhou");
                            put(o, "ok", true);
                            put(o, "path", dst.getAbsolutePath());
                            put(o, "name", dst.getName());
                            put(o, "size", dst.length());
                        } catch (Exception e) {
                            try { if (out != null) out.close(); } catch (Exception ignore) { }
                            if (tmp.exists()) tmp.delete();
                            put(o, "ok", false);
                            put(o, "error", String.valueOf(e.getMessage()));
                        } finally {
                            if (c != null) c.disconnect();
                        }
                        bus.reply(rid, o);
                    }
                });
                t.start();
                return;
            }
            case "updateInstallPerm": {
                JSONObject o = new JSONObject();
                boolean allowed = true;
                try {
                    allowed = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
                } catch (Throwable e) {
                    allowed = true;
                }
                o.put("ok", true);
                o.put("allowed", allowed);
                bus.reply(id, o);
                return;
            }
            case "updateInstallPermAsk": {
                // A constante Settings.ACTION_MANAGE_APP_INSTALL_PACKAGES nao
                // existe no android.jar deste SDK, entao a action vai como
                // literal (mesmo valor do AOSP) e a tela global fica de reserva.
                try {
                    Intent i = new Intent("android.settings.MANAGE_APP_INSTALL_PACKAGES", Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    bus.reply(id, ok());
                } catch (Exception e) {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        bus.reply(id, ok());
                    } catch (Exception e2) {
                        bus.reply(id, err(String.valueOf(e2.getMessage())));
                    }
                }
                return;
            }
            case "updateInstall": {
                String path = arg(a, "path", "");
                String name = arg(a, "name", "");
                if (name.isEmpty() && !path.isEmpty()) {
                    int k = path.lastIndexOf('/');
                    name = k >= 0 ? path.substring(k + 1) : path;
                }
                if (name.isEmpty()) { bus.reply(id, err("sem arquivo")); return; }
                boolean allowed = true;
                try {
                    allowed = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
                } catch (Throwable e) {
                    allowed = true;
                }
                if (!allowed) { bus.reply(id, err("permissao de instalar ausente")); return; }
                try {
                    Uri u = new Uri.Builder()
                            .scheme("content")
                            .authority(UpdateProvider.AUTHORITY)
                            .appendPath(name)
                            .build();
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    bus.reply(id, ok());
                } catch (Exception e) {
                    bus.reply(id, err(String.valueOf(e.getMessage())));
                }
                return;
            }
            case "openWith": {
                String u = arg(a, "uri", "");
                String p = arg(a, "path", "");
                Uri target = null;
                if (!p.isEmpty()) {
                    String mapped = uriByPath.get(p);
                    if (mapped != null) target = Uri.parse(mapped);
                }
                if (target == null && !u.isEmpty()) target = Uri.parse(u);
                if (target == null) target = lastPickedUri;
                if (target == null) { bus.reply(id, err("no uri")); return; }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(target, "application/octet-stream");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                    bus.reply(id, ok());
                } catch (Exception e) {
                    bus.reply(id, err(e.getMessage()));
                }
                return;
            }
            case "toggleUI":
                immersive = !immersive;
                if (immersive) hideSystemUI(); else showSystemUI();
                bus.reply(id, immersive);
                return;
            case "quit":
                moveTaskToBack(true);
                finish();
                bus.reply(id, true);
                return;
            default:
                bus.reply(id, err("unknown method " + method));
        }
    }

    private static boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        return f.delete();
    }

    /**
     * Resolve com "cancelado" qualquer id de seletor que ficou pendente (o seletor
     * nem chegou a abrir, ou a Activity foi recriada). Sem isso a promessa do JS
     * ficaria pendurada para sempre e a tela travava sem nenhuma mensagem.
     */
    private void dropPending(List<String> pending) {
        while (!pending.isEmpty()) bus.reply(pending.remove(0), null);
    }

    private static boolean ensureNativeSession(String storagePath) {
        synchronized (NATIVE_LOCK) {
            if (nativeSessionState != null) return nativeSessionState.booleanValue();
            boolean ok = doEnsureNativeSession(storagePath);
            nativeSessionState = Boolean.valueOf(ok);
            return ok;
        }
    }

    private static boolean doEnsureNativeSession(String storagePath) {
        try {
            System.loadLibrary("Vita3K");
        } catch (Throwable e) {
            AppLog.w("ensureNativeSession: loadLibrary(Vita3K): " + e);
        }
        try {
            Class<?> clazz = Class.forName("org.vita3k.emulator.NativeLib");
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            if ((Boolean) clazz.getDeclaredMethod("isInitialized").invoke(instance)) {
                return true;
            }
            long t0 = System.currentTimeMillis();
            boolean ok = (Boolean) clazz.getDeclaredMethod("init", String.class).invoke(instance, storagePath);
            AppLog.step("ensureNativeSession: NativeLib.init = " + ok
                    + " em " + (System.currentTimeMillis() - t0) + "ms");
            return ok;
        } catch (Throwable error) {
            AppLog.e("ensureNativeSession falhou", error);
            return false;
        }
    }

    private void startFirmwareInstall(final String pup, final String rid) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                String ver;
                long t0 = System.currentTimeMillis();
                try {
                    synchronized (FW_INSTALL_LOCK) {
                        if (!ensureNativeSession(externalRoot().getAbsolutePath())) {
                            AppLog.e("fwInstall: ensureNativeSession falhou", null);
                            bus.reply(rid, err("falha ao iniciar a sessao nativa do emulador"));
                            return;
                        }
                        ver = installFirmwareNative(pup);
                    }
                } catch (Throwable e) {
                    AppLog.e("fwInstall: installFirmwareNative threw", e);
                    bus.reply(rid, err("erro ao extrair o firmware: " + e));
                    return;
                }
                AppLog.step("fwInstall: terminou em " + (System.currentTimeMillis() - t0)
                        + "ms versao=" + ver);
                if (ver == null || ver.isEmpty()) {
                    bus.reply(rid, err("extra\u00e7\u00e3o do firmware falhou"));
                    return;
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("version", ver);
                    bus.reply(rid, o);
                } catch (Exception e) {
                    bus.reply(rid, err("falha interna"));
                }
            }
        }, "rpcsv-fw-install");
        t.setDaemon(true);
        t.start();
    }

    private static String installFirmwareNative(String path) {
        try {
            Class<?> cbClass = Class.forName("org.vita3k.emulator.data.InstallCallback");
            Class<?> clazz = Class.forName("org.vita3k.emulator.NativeLib");
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            final java.lang.reflect.Method onProgress = cbClass.getDeclaredMethod("onProgress", int.class, String.class);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                    clazz.getClassLoader(),
                    new Class<?>[] { cbClass },
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            try {
                                if (m.equals(onProgress) && args != null && args.length >= 2) {
                                    Log.i("RPCSV", "fwInstall " + args[0] + "% " + args[1]);
                                }
                            } catch (Throwable ignore) {}
                            return null;
                        }
                    });
            java.lang.reflect.Method m = clazz.getDeclaredMethod("installFirmware", String.class, cbClass);
            Object r = m.invoke(instance, path, callback);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable error) {
            Log.e("RPCSV", "installFirmwareNative falhou", error);
            return "";
        }
    }

    private static String readAll(File f) throws Exception {
        long len = f.length();
        if (len <= 0 || len > MAX_TEXT_FILE) throw new Exception("arquivo grande demais para leitura de texto");
        byte[] b = new byte[(int) len];
        FileInputStream in = new FileInputStream(f);
        try {
            int got = 0;
            while (got < b.length) {
                int r = in.read(b, got, b.length - got);
                if (r < 0) break;
                got += r;
            }
            if (got != b.length) {
                byte[] c = new byte[got];
                System.arraycopy(b, 0, c, 0, got);
                b = c;
            }
        } finally {
            in.close();
        }
        return new String(b, "UTF-8");
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8
            && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
            && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    /** Le um PNG para base64, ou null se nao existir/nao for PNG/grandes demais. */
    private static String readPngAsBase64(File f) {
        if (!f.isFile() || f.length() <= 8 || f.length() > MAX_BASE64_FILE) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            byte[] raw = bos.toByteArray();
            if (raw.length < 8 || !isPng(raw)) return null;
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }

    private static boolean copyFile(File src, File dst) {
        try {
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            InputStream in = new FileInputStream(src);
            OutputStream os = new FileOutputStream(dst);
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) os.write(b, 0, r);
            os.close();
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int extractZip(File zip, File dest) throws Exception {
        dest.mkdirs();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        byte[] buf = new byte[65536];
        int count = 0;
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName().replace('\\', '/');
            if (name.startsWith("/") || name.contains("../")) continue;
            File out = new File(dest, name);
            if (e.isDirectory()) {
                out.mkdirs();
                continue;
            }
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(out);
            int r;
            while ((r = zin.read(buf)) > 0) os.write(buf, 0, r);
            os.close();
            count++;
        }
        zin.close();
        return count;
    }

    private static final java.util.regex.Pattern VPK_TID =
            java.util.regex.Pattern.compile("^(?:ux0:/app/|ux0:app/|app/)?([A-Z0-9]{9})[/\\\\]");

    private static void writeSmall(File f, byte[] body) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        os.write(body);
        os.close();
    }

    private JSONObject installVpk(File zip, String baseDir) throws Exception {
        if (zip == null || !zip.isFile()) throw new Exception("arquivo VPK n\u00e3o encontrado");
        if (baseDir == null || baseDir.isEmpty()) throw new Exception("diret\u00f3rio de instala\u00e7\u00e3o n\u00e3o definido");
        ZipFile zf = new ZipFile(zip);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            List<ZipEntry> all = new ArrayList<ZipEntry>();
            String strip = null;
            String titleId = null;
            String sfoEntry = null;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                all.add(e);
                if (strip == null) {
                    Matcher m = VPK_TID.matcher(n);
                    if (m.find()) {
                        titleId = m.group(1);
                        String pref = n.substring(0, m.start(1));
                        strip = pref + titleId + "/";
                    }
                }
                if (sfoEntry == null && n.endsWith("sce_sys/param.sfo")) sfoEntry = n;
            }
            String sfoTmp = null;
            if (titleId == null && sfoEntry != null) {
                ZipEntry ze = zf.getEntry(sfoEntry);
                File tmp = File.createTempFile("rpcsv_sfo", ".sfo");
                InputStream in = zf.getInputStream(ze);
                OutputStream os = new FileOutputStream(tmp);
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
                os.close();
                in.close();
                sfoTmp = tmp.getAbsolutePath();
                String tid = PkgExtractor.readParamTitleId(sfoTmp);
                if (tid != null && !tid.isEmpty()) titleId = tid;
            }
            if (titleId == null) throw new Exception("VPK sem TITLE_ID (param.sfo ausente ou inv\u00e1lido)");

            File destDir = new File(baseDir, "ux0/app/" + titleId);
            if (!destDir.isDirectory() && !destDir.mkdirs()) throw new Exception("n\u00e3o foi poss\u00edvel criar " + destDir);
            int count = 0;
            for (ZipEntry e : all) {
                String n = e.getName().replace('\\', '/');
                String rel;
                if (strip != null) {
                    if (!n.startsWith(strip)) continue;
                    rel = n.substring(strip.length());
                } else {
                    Matcher m = VPK_TID.matcher(n);
                    if (m.find()) rel = n.substring(m.end());
                    else rel = n;
                }
                if (rel == null || rel.isEmpty()) continue;
                if (rel.startsWith("/") || rel.contains("../")) continue;
                File out = new File(destDir, rel);
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
                os.close();
                in.close();
                count++;
            }
            if (count == 0) throw new Exception("VPK sem arquivos v\u00e1lidos para extrair");
            String title = "";
            try {
                if (sfoTmp != null) title = PkgExtractor.readParamTitle(sfoTmp);
                else title = PkgExtractor.readParamTitle(new File(destDir, "sce_sys/param.sfo").getAbsolutePath());
            } catch (Throwable ignore) {}
            try {
                writeSmall(new File(destDir, "sce_sys/package/_install.json"),
                        ("{\"titleId\":\"" + titleId + "\",\"kind\":\"vita\",\"mode\":\"vpk\",\"files\":" + count + "}")
                                .getBytes("UTF-8"));
            } catch (Throwable ignore) {}
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("mode", "standalone");
            r.put("kind", "vita");
            r.put("titleId", titleId);
            r.put("title", title);
            r.put("appDir", destDir.getAbsolutePath());
            r.put("files", Integer.valueOf(count));
            return r;
        } finally {
            zf.close();
        }
    }

    // ------------------------- Sony firmware check -------------------------
    private static String extractAttr(String attrs, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        if (!m.find()) return null;
        return m.group(1).replace("&amp;", "&");
    }

    private static String fwHost(String region) {
        String r = (region == null ? "us" : region).toLowerCase();
        if (r.equals("jp")) return "djp01";
        if (r.equals("eu")) return "deu01";
        if (r.equals("tw")) return "dtw01";
        if (r.equals("kr")) return "dkr01";
        if (r.equals("au")) return "dau01";
        if (r.equals("gb")) return "dgb01";
        return "dus01";
    }

    // The font package PUP (sa0 fonts) is served by Sony but not listed in the
    // updatelist; this known-good build (2019_0924) carries it (see Vita3K #2977).
    private static String fontPupUrl(String region) {
        String r = (region == null ? "us" : region).toLowerCase();
        return "http://" + fwHost(region)
                + ".psp2.update.playstation.net/update/psp2/image/2019_0924/sd_8b5f60b56c3da8365b973dba570c53a5/PSP2UPDAT.PUP?dest=" + r;
    }

    private JSONObject fetchUpdateInfo(String region) {
        String r = region == null ? "us" : region;
        String url = "http://" + fwHost(r) + ".psp2.update.playstation.net/update/psp2/list/"
                + r + "/psp2-updatelist.xml";
        try {
            HttpURLConnection c = openNet(url);
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "RPCSV/0.1");
            if (c.getResponseCode() != 200) return null;
            BufferedReader rd = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                if (sb.length() > 600000) break;
                sb.append(line).append('\n');
            }
            rd.close();
            c.disconnect();
            android.util.Log.i("RPCSV", "fwCheck http " + c.getResponseCode() + " url=" + url + " bytes=" + sb.length());
            String xml = sb.toString();

            String fullUrl = null, fullSize = null;
            Matcher full = Pattern.compile("<update_data\\b[^>]*>([\\s\\S]*?)</update_data>", Pattern.DOTALL).matcher(xml);
            while (full.find()) {
                Matcher im = Pattern.compile("<image\\b([^>]*)>([\\s\\S]*?)</image>", Pattern.DOTALL).matcher(full.group(1));
                if (im.find()) {
                    fullUrl = im.group(2).trim();
                    String sz = extractAttr(im.group(1), "size");
                    if (sz != null) fullSize = sz;
                    break;
                }
            }
            if (fullUrl == null) return null;

            String preUrl = null, preSize = null;
            Matcher rec = Pattern.compile("<recovery\\b([^>]*)>([\\s\\S]*?)</recovery>", Pattern.DOTALL).matcher(xml);
            while (rec.find()) {
                String spkg = extractAttr(rec.group(1), "spkg_type");
                if (spkg == null || !spkg.equals("preinst")) continue;
                Matcher im = Pattern.compile("<image\\b([^>]*)>([\\s\\S]*?)</image>", Pattern.DOTALL).matcher(rec.group(2));
                if (im.find()) {
                    preUrl = im.group(2).trim();
                    String sz = extractAttr(im.group(1), "size");
                    if (sz != null) preSize = sz;
                }
                break;
            }

            String ver = null;
            Matcher vm = Pattern.compile("<version\\b([^>]*)>", Pattern.DOTALL).matcher(xml);
            if (vm.find()) ver = extractAttr(vm.group(1), "label");

            JSONObject o = new JSONObject();
            o.put("ok", true);
            JSONObject info = new JSONObject();
            info.put("url", fullUrl);
            info.put("size", fullSize == null ? JSONObject.NULL : Long.parseLong(fullSize));
            info.put("version", ver == null ? "3.74" : ver);
            JSONObject pre = new JSONObject();
            pre.put("url", preUrl == null ? JSONObject.NULL : preUrl);
            pre.put("size", preSize == null ? JSONObject.NULL : Long.parseLong(preSize));
            info.put("pre", pre);
            JSONObject font = new JSONObject();
            font.put("url", fontPupUrl(r));
            font.put("size", Long.valueOf(56768512L));
            info.put("font", font);
            o.put("info", info);
            return o;
        } catch (Exception e) {
            android.util.Log.i("RPCSV", "fwCheck ERR " + e.getClass().getSimpleName() + ": " + e.getMessage() + " url=" + url);
            return null;
        }
    }

    private void startDownload(final String url, final String dest, final String id) {
        Thread t = new Thread(new Runnable() {
            public void run() { downloadRun(url, dest, id); }
        });
        t.setDaemon(true);
        t.start();
    }

    private void downloadRun(String url, String dest, String id) {
        try {
            android.util.Log.i("RPCSV", "download url=" + url + " dest=" + dest);
            HttpURLConnection c = openNet(url);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "RPCSV/0.1");
            int code = c.getResponseCode();
            if (code != 200) {
                bus.reply(id, err("HTTP " + code));
                return;
            }
            long total = c.getContentLengthLong();
            File f = new File(dest);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            InputStream in = c.getInputStream();
            FileOutputStream os = new FileOutputStream(f);
            byte[] buf = new byte[65536];
            long read = 0, lastReport = 0;
            long t0 = System.currentTimeMillis(), lastT = t0, lastB = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
                read += r;
                long now = System.currentTimeMillis();
                if (read - lastReport > 65536 * 8 || read >= total) {
                    lastReport = read;
                    double secs = (now - lastT) / 1000.0;
                    long speed = secs > 0 ? (long) ((read - lastB) / secs) : 0;
                    lastT = now;
                    lastB = read;
                    JSONObject p = new JSONObject();
                    p.put("bytes", read);
                    if (total > 0) p.put("total", total);
                    p.put("pct", total > 0 ? (long) (read * 100 / total) : 0L);
                    p.put("speed", speed);
                    bus.event("fw:progress", p);
                }
            }
            os.close();
            in.close();
            c.disconnect();
            JSONObject fin = new JSONObject();
            fin.put("bytes", read);
            fin.put("total", total);
            fin.put("pct", 100);
            bus.event("fw:progress", fin);
            bus.reply(id, dest);
        } catch (Exception e) {
            bus.reply(id, err(e.getMessage()));
        }
    }

    // ------------------------- SAF results -------------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            if (!pendingCameraHandlers.isEmpty()) {
                String id = pendingCameraHandlers.remove(0);
                if (resultCode == RESULT_OK && cameraUri != null) {
                    String path = snapshotCamera(cameraUri);
                    if (path != null) {
                        try {
                            getContentResolver().delete(cameraUri, null, null);
                        } catch (Exception ignore) {}
                        bus.reply(id, path);
                    } else {
                        bus.reply(id, err("camera failed"));
                    }
                } else {
                    bus.reply(id, null);
                }
            }
            cameraUri = null;
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_DIR && !pendingDirHandlers.isEmpty())
                bus.reply(pendingDirHandlers.remove(0), null);
            else if (requestCode == REQ_FILE && !pendingFileHandlers.isEmpty())
                bus.reply(pendingFileHandlers.remove(0), null);
            return;
        }
        if (requestCode == REQ_DIR) resolveDir(data);
        else if (requestCode == REQ_FILE) resolveFile(data);
    }

    private String snapshotCamera(Uri uri) {
        try {
            String base = defaultInstallDir();
            File dir = new File(base, "photos");
            dir.mkdirs();
            File out = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            os.close();
            in.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void resolveDir(Intent data) {
        String id = pendingDirHandlers.isEmpty() ? null : pendingDirHandlers.remove(0);
        if (id == null) return;
        try {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignore) {}
            String docId = DocumentsContract.getTreeDocumentId(uri);
            String[] parts = docId.split(":", 2);
            String base = "primary".equals(parts[0]) ? "/storage/emulated/0" : "/storage/" + parts[0];
            String rel = parts.length > 1 ? parts[1] : "";
            String path = rel.isEmpty() ? base : base + "/" + rel;
            if (probeWritable(path)) {
                bus.reply(id, path);
            } else {
                String dflt = defaultInstallDir();
                toast("Diretorio nao gravavel; usando " + dflt);
                bus.reply(id, dflt);
            }
        } catch (Exception e) {
            bus.reply(id, null);
        }
    }

    private void resolveFile(Intent data) {
        String id = pendingFileHandlers.isEmpty() ? null : pendingFileHandlers.remove(0);
        if (id == null) return;
        try {
            final Uri uri = data.getData();
            String name = queryDisplayName(uri);
            name = (name == null || name.isEmpty()) ? ("import_" + System.currentTimeMillis()) : name;
            name = name.replaceAll("[\\\\/]", "_");
            File dir = new File(getFilesDir(), "importer");
            dir.mkdirs();
            final File out = new File(dir, name);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {}
            lastPickedUri = uri;
            uriByPath.put(out.getAbsolutePath(), uri.toString());
            try {
                String docId = DocumentsContract.getDocumentId(uri);
                String[] dp = docId.split(":", 2);
                String b2 = "primary".equals(dp[0]) ? "/storage/emulated/0" : "/storage/" + dp[0];
                String rl = dp.length > 1 ? dp[1] : "";
                String real = rl.isEmpty() ? b2 : b2 + "/" + rl;
                uriByPath.put(real, uri.toString());
            } catch (Exception ignore) {}
            final String rid = id;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in == null) { bus.reply(rid, null); return; }
                        OutputStream os = new FileOutputStream(out);
                        byte[] b = new byte[65536];
                        int r;
                        while ((r = in.read(b)) > 0) os.write(b, 0, r);
                        os.close();
                        in.close();
                        bus.reply(rid, out.getAbsolutePath());
                    } catch (Exception e) {
                        bus.reply(rid, null);
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            bus.reply(id, null);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignore) {}
        return null;
    }

    private boolean probeWritable(String path) {
        try {
            File d = new File(path);
            if (!d.isDirectory() && !d.mkdirs()) return false;
            File probe = File.createTempFile("rpcsv_probe", ".tmp", d);
            boolean ok = probe.exists();
            probe.delete();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}