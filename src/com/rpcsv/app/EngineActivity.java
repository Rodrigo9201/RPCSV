package com.rpcsv.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.libsdl.app.SDLActivity;
import org.vita3k.emulator.EmuSurface;

/**
 * Activity do motor Vita3K embutido.
 *
 * Herda o org.libsdl.app.SDLActivity embarcado (classes2.dex da engine) para
 * manter a colagem JNI/nativa intacta. Nao herda org.vita3k.emulator.Emulator de
 * proposito: o onCreate dele inicializa o OverlayStore, que le recursos da engine
 * (R.integer 0x7f...) inexistentes no resources.arsc do RPCSV e quebraria.
 *
 * Ao iniciar cada jogo o onConfigureEngine():
 *   1. garante o config.yml (template embutido/asset se ainda nao existir);
 *   2. forca tela cheia no boot (boot-apps-full-screen: true);
 *   3. aplica a aba Core do app (modules-mode + lle-modules + cpu-opt) e o
 *      renderer escolhido nas configuracoes, lendo o config.json do app;
 *   4. mantem a tela acesa (FLAG_KEEP_SCREEN_ON) para o jogo nunca "apagar".
 */
public class EngineActivity extends SDLActivity {

    private static final String TAG = "RpcSVO";

    private volatile boolean memSampling;
    private Thread memSampler;

    static final String APP_RESTART_PARAMETERS = "AppStartParameters";

    private static final String ASSET_TEMPLATE = "templates/config.yml";

    /** Fallback minimo: usado apenas se nem o asset nem o config.yml existirem. */
    private static final String EMBEDDED_CONFIG =
            "---\n"
            + "initial-setup: false\n"
            + "gdbstub: false\n"
            + "log-active-shaders: false\n"
            + "log-uniforms: false\n"
            + "log-compat-warn: false\n"
            + "validation-layer: false\n"
            + "pstv-mode: false\n"
            + "show-mode: false\n"
            + "demo-mode: false\n"
            + "apps-list-grid: false\n"
            + "stretch_the_display_area: false\n"
            + "fullscreen_hd_res_pixel_perfect: false\n"
            + "archive-log: false\n"
            + "backend-renderer: Vulkan\n"
            + "custom-driver-name: \"\"\n"
            + "turbo-mode: false\n"
            + "gpu-idx: 0\n"
            + "high-accuracy: false\n"
            + "resolution-multiplier: 1\n"
            + "disable-surface-sync: false\n"
            + "screen-filter: Bilinear\n"
            + "v-sync: true\n"
            + "anisotropic-filtering: 1\n"
            + "texture-cache: true\n"
            + "async-pipeline-compilation: true\n"
            + "show-compile-shaders: true\n"
            + "hashless-texture-cache: false\n"
            + "import-textures: false\n"
            + "export-textures: false\n"
            + "export-as-png: true\n"
            + "memory-mapping: double-buffer\n"
            + "boot-apps-full-screen: true\n"
            + "show-live-area-screen: false\n"
            + "audio-backend: SDL\n"
            + "audio-volume: 100\n"
            + "ngs-enable: true\n"
            + "sys-button: 1\n"
            + "sys-lang: 1\n"
            + "sys-date-format: 2\n"
            + "sys-time-format: 0\n"
            + "cpu-pool-size: 10\n"
            + "modules-mode: 0\n"
            + "delay-background: 4\n"
            + "delay-start: 30\n"
            + "background-alpha: 0.3\n"
            + "log-level: 0\n"
            + "cpu-opt: true\n"
            + "pref-path: /storage/emulated/0/Android/data/com.rpcsv.app/files/vita\n"
            + "discord-rich-presence: true\n"
            + "wait-for-debugger: false\n"
            + "color-surface-debug: false\n"
            + "performance-overlay: false\n"
            + "performance-overlay-detail: 0\n"
            + "performance-overlay-position: 0\n"
            + "screenshot-format: 1\n"
            + "disable-motion: false\n"
            + "controller-analog-multiplier: 1\n"
            + "keyboard-button-select: ShiftRight\n"
            + "keyboard-button-start: Enter\n"
            + "keyboard-button-up: ArrowUp\n"
            + "keyboard-button-right: ArrowRight\n"
            + "keyboard-button-down: ArrowDown\n"
            + "keyboard-button-left: ArrowLeft\n"
            + "keyboard-button-l1: KeyQ\n"
            + "keyboard-button-r1: KeyE\n"
            + "keyboard-button-l2: KeyU\n"
            + "keyboard-button-r2: KeyO\n"
            + "keyboard-button-l3: KeyF\n"
            + "keyboard-button-r3: KeyH\n"
            + "keyboard-button-triangle: KeyV\n"
            + "keyboard-button-circle: KeyC\n"
            + "keyboard-button-cross: KeyX\n"
            + "keyboard-button-square: KeyZ\n"
            + "keyboard-leftstick-left: KeyA\n"
            + "keyboard-leftstick-right: KeyD\n"
            + "keyboard-leftstick-up: KeyW\n"
            + "keyboard-leftstick-down: KeyS\n"
            + "keyboard-rightstick-left: KeyJ\n"
            + "keyboard-rightstick-right: KeyL\n"
            + "keyboard-rightstick-up: KeyI\n"
            + "keyboard-rightstick-down: KeyK\n"
            + "keyboard-button-psbutton: KeyP\n"
            + "keyboard-gui-fullscreen: F11\n"
            + "keyboard-gui-toggle-touch: KeyT\n"
            + "keyboard-toggle-texture-replacement: Unbound\n"
            + "keyboard-take-screenshot: Unbound\n"
            + "keyboard-pinch-modifier: Unbound\n"
            + "keyboard-alternate-pinch-in: Unbound\n"
            + "keyboard-alternate-pinch-out: Unbound\n"
            + "keyboard-button-select-alt: Unbound\n"
            + "keyboard-button-start-alt: Unbound\n"
            + "keyboard-button-up-alt: Unbound\n"
            + "keyboard-button-right-alt: Unbound\n"
            + "keyboard-button-down-alt: Unbound\n"
            + "keyboard-button-left-alt: Unbound\n"
            + "keyboard-button-l1-alt: Unbound\n"
            + "keyboard-button-r1-alt: Unbound\n"
            + "keyboard-button-l2-alt: Unbound\n"
            + "keyboard-button-r2-alt: Unbound\n"
            + "keyboard-button-l3-alt: Unbound\n"
            + "keyboard-button-r3-alt: Unbound\n"
            + "keyboard-button-triangle-alt: Unbound\n"
            + "keyboard-button-circle-alt: Unbound\n"
            + "keyboard-button-cross-alt: Unbound\n"
            + "keyboard-button-square-alt: Unbound\n"
            + "keyboard-leftstick-left-alt: Unbound\n"
            + "keyboard-leftstick-right-alt: Unbound\n"
            + "keyboard-leftstick-up-alt: Unbound\n"
            + "keyboard-leftstick-down-alt: Unbound\n"
            + "keyboard-rightstick-left-alt: Unbound\n"
            + "keyboard-rightstick-right-alt: Unbound\n"
            + "keyboard-rightstick-up-alt: Unbound\n"
            + "keyboard-rightstick-down-alt: Unbound\n"
            + "keyboard-button-psbutton-alt: Unbound\n"
            + "keyboard-gui-fullscreen-alt: Unbound\n"
            + "keyboard-gui-toggle-touch-alt: Unbound\n"
            + "keyboard-toggle-texture-replacement-alt: Unbound\n"
            + "keyboard-take-screenshot-alt: Unbound\n"
            + "keyboard-pinch-modifier-alt: Unbound\n"
            + "keyboard-alternate-pinch-in-alt: Unbound\n"
            + "keyboard-alternate-pinch-out-alt: Unbound\n"
            + "user-id: 00\n"
            + "user-auto-connect: false\n"
            + "user-lang: \"\"\n"
            + "show-welcome: true\n"
            + "warn-missing-firmware: true\n"
            + "check-for-updates-mode: 1\n"
            + "file-loading-delay: 0\n"
            + "shader-cache: true\n"
            + "spirv-shader: false\n"
            + "fps-hack: false\n"
            + "current-ime-lang: 4\n"
            + "psn-signed-in: 0\n"
            + "http-enable: true\n"
            + "http-timeout-attempts: 50\n"
            + "http-timeout-sleep-ms: 100\n"
            + "http-read-end-attempts: 10\n"
            + "http-read-end-sleep-ms: 250\n"
            + "adhoc-addr: 0\n"
            + "front-camera-type: 2\n"
            + "front-camera-id: \"\"\n"
            + "front-camera-image: \"\"\n"
            + "front-camera-color: 0\n"
            + "back-camera-type: 2\n"
            + "back-camera-id: \"\"\n"
            + "back-camera-image: \"\"\n"
            + "back-camera-color: 0\n"
            + "tracy-primitive-impl: false\n"
            + "controller-binds:\n"
            + "  - 0\n"
            + "  - 1\n"
            + "  - 2\n"
            + "  - 3\n"
            + "  - 4\n"
            + "  - 5\n"
            + "  - 6\n"
            + "  - 7\n"
            + "  - 8\n"
            + "  - 9\n"
            + "  - 10\n"
            + "  - 11\n"
            + "  - 12\n"
            + "  - 13\n"
            + "  - 14\n"
            + "controller-axis-binds:\n"
            + "  - 0\n"
            + "  - 1\n"
            + "  - 2\n"
            + "  - 3\n"
            + "  - 4\n"
            + "  - 5\n"
            + "controller-led-color:\n"
            + "  []\n"
            + "lle-modules:\n"
            + "  []\n"
            + "ime-langs:\n"
            + "  - 4\n"
            + "tracy-advanced-profiling-modules:\n"
            + "  []\n"
            + "...\n";

    private String currentGameId = "";
    private View splash;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean splashGone = false;

    /**
     * Padroniza o config.yml da engine a cada boot: tela cheia + controle nativo
     * + imagem imediata, e aplica o que estiver salvo na aba Core do app.
     * Idempotente: so corrige/altera as chaves, nunca apaga o resto.
     */
    private void onConfigureEngine() {
        try {
            File cfg = new File(getExternalFilesDir(null), "config.yml");
            String text = null;
            if (cfg.exists()) {
                byte[] body = readSmallFile(cfg);
                if (body != null) text = new String(body, "UTF-8");
            }
            if (text == null) {
                byte[] tpl = readAssetConfigTemplate();
                if (tpl != null) text = new String(tpl, "UTF-8");
            }
            if (text == null && !cfg.exists()) text = EMBEDDED_CONFIG;
            if (text == null) return;

            Map<String, String> scalars = new LinkedHashMap<String, String>();
            Map<String, String[]> lists = new LinkedHashMap<String, String[]>();
            scalars.put("boot-apps-full-screen", "true");
            scalars.put("validation-layer", "false");
            scalars.put("disable-surface-sync", "false");
            scalars.put("show-live-area-screen", "false");
            scalars.put("stretch_the_display_area", "true");
            scalars.put("fullscreen_hd_res_pixel_perfect", "true");

            readUiOverrides(scalars, lists);

            String next = patchYaml(text, scalars, lists);
            boolean changed = !cfg.exists() || !next.equals(text);
            if (changed) {
                writeFile(cfg, next.getBytes("UTF-8"));
                Log.i(TAG, "config.yml ajustado (tela cheia + aba Core aplicada)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Falha ao ajustar config.yml", t);
        }
    }

    /**
     * Le o config.json do app (o mesmo salvo pela tela de Configuracoes / aba
     * Core) e traduz para as chaves da engine:
     *   Core.loadingMode  -> modules-mode (automatic=0, auto_manual=1, manual=2)
     *   Core.modules[]    -> lle-modules
     *   Core/cpu          -> cpu-opt
     *   Settings.renderer -> backend-renderer
     */
    private void readUiOverrides(Map<String, String> scalars, Map<String, String[]> lists) {
        try {
            File ui = new File(getFilesDir(), "config.json");
            if (!ui.exists()) return;
            byte[] body = readSmallFile(ui);
            if (body == null) return;
            JSONObject root = new JSONObject(new String(body, "UTF-8"));

            JSONObject core = root.optJSONObject("core");
            if (core != null) {
                String mode = core.optString("loadingMode", "automatic");
                if ("manual".equals(mode)) scalars.put("modules-mode", "2");
                else if ("auto_manual".equals(mode)) scalars.put("modules-mode", "1");
                else scalars.put("modules-mode", "0");

                List<String> lle = new ArrayList<String>();
                JSONObject mods = core.optJSONObject("modules");
                if (mods != null) {
                    Iterator<String> it = mods.keys();
                    while (it.hasNext()) {
                        String name = it.next();
                        if (mods.optBoolean(name, false)) lle.add(name);
                    }
                }
                lists.put("lle-modules", lle.toArray(new String[lle.size()]));

                JSONObject cpu = root.optJSONObject("cpu");
                if (cpu != null && !cpu.optBoolean("optimizations", true)) {
                    scalars.put("cpu-opt", "false");
                }
            }

            JSONObject settings = root.optJSONObject("settings");
            if (settings != null) {
                String renderer = settings.optString("renderer", "");
                if ("OpenGL".equalsIgnoreCase(renderer) || "Vulkan".equalsIgnoreCase(renderer)) {
                    renderer = "OpenGL".equalsIgnoreCase(renderer) ? "OpenGL" : "Vulkan";
                    scalars.put("backend-renderer", renderer);
                } else {
                    renderer = "";
                }

                if (settings.has("screenFilter")) {
                    String screenFilter = settings.optString("screenFilter", "");
                    if ("Nearest".equals(screenFilter)
                            || "Bilinear".equals(screenFilter)
                            || "Bicubic".equals(screenFilter)
                            || "FXAA".equals(screenFilter)) {
                        scalars.put("screen-filter", screenFilter);
                    } else if ("FSR".equals(screenFilter)) {
                        scalars.put("screen-filter", "Vulkan".equals(renderer) ? "FSR" : "Bilinear");
                    }
                }

                if (settings.has("highAccuracy")) {
                    scalars.put("high-accuracy", settings.optBoolean("highAccuracy", false) ? "true" : "false");
                }

                if (settings.has("stretchDisplayArea")) {
                    scalars.put("stretch_the_display_area", settings.optBoolean("stretchDisplayArea", true) ? "true" : "false");
                }
                if (settings.has("fullscreenHdResPixelPerfect")) {
                    scalars.put("fullscreen_hd_res_pixel_perfect", settings.optBoolean("fullscreenHdResPixelPerfect", true) ? "true" : "false");
                }

                // Aba Audio: sem isto o seletor de volume/backend/NGS da tela de
                // configuracoes nao mudava nada (o valor ficava fixo no template).
                if (settings.has("audioBackend")) {
                    String backend = settings.optString("audioBackend", "");
                    if ("SDL".equals(backend) || "Cubeb".equals(backend)) {
                        scalars.put("audio-backend", backend);
                    } else {
                        scalars.put("audio-backend", "");
                    }
                }
                if (settings.has("audioVolume")) {
                    int vol = settings.optInt("audioVolume", 100);
                    if (vol < 0) vol = 0;
                    if (vol > 150) vol = 150;
                    scalars.put("audio-volume", String.valueOf(vol));
                }
                if (settings.has("ngsEnable")) {
                    scalars.put("ngs-enable", settings.optBoolean("ngsEnable", false) ? "true" : "false");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "config.json ignorado", t);
        }
    }

    // ------------------------- YAML basico (patch idempotente) -------------------------

    private static boolean isKeyLine(String line) {
        return line.length() > 0
                && line.charAt(0) != ' ' && line.charAt(0) != '\t'
                && line.indexOf(':') > 0;
    }

    private static String keyOf(String line) {
        int p = line.indexOf(':');
        return p > 0 ? line.substring(0, p).trim() : "";
    }

    private static String listBlock(String key, String[] items) {
        StringBuilder sb = new StringBuilder();
        sb.append(key).append(":\n");
        if (items == null || items.length == 0) {
            sb.append("  []");
        } else {
            for (String it : items) sb.append("  - ").append(it).append('\n');
            if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String blockOf(List<String> lines, int start) {
        StringBuilder sb = new StringBuilder(lines.get(start));
        int i = start + 1;
        while (i < lines.size()) {
            String l = lines.get(i);
            if (l.length() > 0 && (l.charAt(0) == ' ' || l.charAt(0) == '\t')) {
                sb.append('\n').append(l);
                i++;
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Reaplica chaves escalares e listas em um config.yml estilo yaml-cpp,
     * preservando todas as demais linhas.
     */
    private static String patchYaml(String text,
                                    Map<String, String> scalars,
                                    Map<String, String[]> lists) {
        List<String> lines = new ArrayList<String>();
        String[] raw = text.split("\n", -1);
        for (String l : raw) lines.add(l);

        Set<String> doneSa = new HashSet<String>();
        Set<String> doneLi = new HashSet<String>();
        boolean changed = false;
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (isKeyLine(line)) {
                String key = keyOf(line);
                if (lists.containsKey(key) && !doneLi.contains(key)) {
                    String cur = blockOf(lines, i);
                    String target = listBlock(key, lists.get(key));
                    out.add(target);
                    if (!cur.equals(target)) changed = true;
                    doneLi.add(key);
                    i = i + 1;
                    while (i < lines.size() && (lines.get(i).length() == 0
                            || lines.get(i).charAt(0) == ' '
                            || lines.get(i).charAt(0) == '\t')) i++;
                    continue;
                }
                if (scalars.containsKey(key) && !doneSa.contains(key)) {
                    String val = scalars.get(key);
                    String target = key + ": " + val;
                    out.add(target);
                    if (!line.trim().equals(key + ": " + val)) changed = true;
                    doneSa.add(key);
                    i = i + 1;
                    while (i < lines.size() && lines.get(i).length() > 0
                            && (lines.get(i).charAt(0) == ' '
                            || lines.get(i).charAt(0) == '\t')) i++;
                    continue;
                }
            }
            out.add(line);
            i++;
        }

        // chaves pedidas que nao existiam no arquivo vao apensadas
        for (String key : scalars.keySet()) {
            if (!doneSa.contains(key)) { out.add(key + ": " + scalars.get(key)); changed = true; }
        }
        for (String key : lists.keySet()) {
            if (!doneLi.contains(key)) { out.add(listBlock(key, lists.get(key))); changed = true; }
        }

        if (!changed) return text;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < out.size(); k++) {
            if (k > 0) sb.append('\n');
            sb.append(out.get(k));
        }
        return sb.toString();
    }

    // ------------------------- IO -------------------------

    private static byte[] readSmallFile(File f) {
        try {
            if (!f.exists() || f.length() > 2L * 1024 * 1024) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] readAssetConfigTemplate() {
        try {
            File cached = new File(getCacheDir(), "config.yml.template");
            if (cached.exists()) return readSmallFile(cached);
            InputStream in = getAssets().open(ASSET_TEMPLATE);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            byte[] body = bos.toByteArray();
            try {
                writeFile(cached, body);
            } catch (Throwable ignore) {}
            return body;
        } catch (Throwable t) {
            return readSmallFile(new File(getCacheDir(), "config.yml.template"));
        }
    }

    private static void writeFile(File f, byte[] body) {
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream os = new FileOutputStream(f);
            os.write(body);
            os.close();
        } catch (Throwable t) {
            Log.e(TAG, "Falha ao gravar " + f, t);
        }
    }

    /**
     * Inicializa a sessao do emulador nativo na engine embarcada, espelhando o
     * ensureNativeSessionInitialized() do Emulator oficial:
     *   storagePath = getExternalFilesDir(null).getAbsolutePath()
     *   NativeLib.init(storagePath)  (se !NativeLib.isInitialized())
     */
    private boolean ensureNativeSessionInitialized() {
        try {
            Class<?> clazz = Class.forName("org.vita3k.emulator.NativeLib");
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();

            if ((Boolean) clazz.getDeclaredMethod("isInitialized").invoke(instance)) {
                AppLog.step("EngineActivity: NativeLib ja inicializado");
                return true;
            }

            String storagePath = getExternalFilesDir(null).getAbsolutePath();
            AppLog.step("EngineActivity: NativeLib.init(" + storagePath + ")...");
            long t0 = System.currentTimeMillis();
            startMemSampler();
            boolean ok;
            try {
                ok = (Boolean) clazz
                        .getDeclaredMethod("init", String.class)
                        .invoke(instance, storagePath);
            } finally {
                stopMemSampler();
            }
            AppLog.step("EngineActivity: NativeLib.init = " + ok
                    + " em " + (System.currentTimeMillis() - t0) + "ms");
            if (!ok) {
                Log.e(TAG, "NativeLib.init(" + storagePath + ") retornou false");
            }
            return ok;
        } catch (Throwable error) {
            AppLog.e("EngineActivity: falha ao inicializar sessao nativa", error);
            return false;
        }
    }

    /**
     * Amostra a memoria do processo durante o init nativo. O init passa por
     * load_cached_apps, que reconstrui o cache de apps; nessa etapa o processo
     * chego a ser morto pelo MemoryService do MIUI ("The system loading is too
     * high") e nao ha ultimo log. Sem esta amostra nao da para saber se o
     * processo cresce de forma continua (vazamento/estouraco) ou se estoura num
     * unico pico.
     */
    private void startMemSampler() {
        memSampling = true;
        memSampler = new Thread(new Runnable() {
            public void run() {
                while (memSampling) {
                    try {
                        android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
                        android.os.Debug.getMemoryInfo(mi);
                        Runtime rt = Runtime.getRuntime();
                        AppLog.step("  mem pss=" + (mi.getTotalPss() >> 10) + "MB"
                                + " privDirty=" + (mi.getTotalPrivateDirty() >> 10) + "MB"
                                + " privClean=" + (mi.getTotalPrivateClean() >> 10) + "MB"
                                + " swapPss=" + (mi.getTotalSwappablePss() >> 10) + "MB"
                                + " javaUsed=" + ((rt.totalMemory() - rt.freeMemory()) >> 20) + "MB"
                                + " javaMax=" + (rt.maxMemory() >> 20) + "MB");
                    } catch (Throwable ignore) {
                    }
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "rpcsv-mem-sampler");
        memSampler.start();
    }

    private void stopMemSampler() {
        memSampling = false;
        if (memSampler != null) {
            memSampler.interrupt();
            memSampler = null;
        }
    }

    /**
     * Chamado pelo nativo (SDL_ANDROID_GetDisplayRotation) via GetMethodID.
     * Necessario: ausencia de getNativeDisplayRotation()I derruba o .so.
     */
    public int getNativeDisplayRotation() {
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppLog.init(this);
        AppLog.step("EngineActivity.onCreate: start pid=" + android.os.Process.myPid()
                + " params=" + String.valueOf(getIntent() == null ? null
                        : getIntent().getStringArrayExtra(APP_RESTART_PARAMETERS)));
        // Flags de janela ANTES de super.onCreate(): o SDLActivity cria a SurfaceView
        // e sobe o SDLThread dentro de super.onCreate(). Tocar nas flags da window
        // depois disso forca um relayout, a Surface e destruida/recriada e o
        // renderer Vulkan aborta o processo com
        // "vk::SurfaceLostKHRError: getSurfaceCapabilitiesKHR: ErrorSurfaceLostKHR".
        prepareWindow();
        AppLog.step("EngineActivity: prepareWindow ok");
        super.onCreate(savedInstanceState);
        AppLog.step("EngineActivity: super.onCreate ok");
        onConfigureEngine();
        AppLog.step("EngineActivity: onConfigureEngine ok");
        if (!ensureNativeSessionInitialized()) {
            AppLog.e("EngineActivity: sessao nativa indisponivel; finish()", null);
            finish();
            return;
        }
        AppLog.step("EngineActivity: sessao nativa ok");
        startSplashWatcher();
        AppLog.step("EngineActivity: startSplashWatcher ok");
    }

    /**
     * Deixa a janela imersiva e sem restricoes de layout. Precisa rodar antes de
     * super.onCreate() para nao invalidar a Surface que o SDL acabou de criar.
     */
    private void prepareWindow() {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        } catch (Throwable ignore) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignore) {}
        // FLAG_LAYOUT_NO_LIMITS foi removido de proposito: ele faz a Window
        //-manager destruir e recriar a Surface, quebrando o Vulkan swapchain.
        try {
            if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        } catch (Throwable ignore) {}
        hideSystemUi();
    }

    private void hideSystemUi() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignore) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "Vita3K" };
    }

    @Override
    protected EmuSurface createSDLSurface(Context context) {
        // Precisa ser EmuSurface: e ela que chama o nativo setSurfaceStatus()
        // quando a SurfaceHolder fica pronta. Sem isso a engine nao apresenta
        // nada na tela (imagem preta com o jogo rodando).
        return new EmuSurface(context);
    }

    @Override
    protected void setupLayout(ViewGroup layout) {
        // Nao adiciona mSurface de novo: o SDLActivity.onCreate() ja faz
        // createSDLSurface() e layout.addView(mSurface). Um segundo addView com a
        // MESMA instancia deixa a SurfaceView referenciada duas vezes na hierarquia,
        // o que faz o Vulkan perder a Surface (ErrorSurfaceLostKHR) e o app fechar.
        splash = buildSplash();
        if (splash != null) layout.addView(splash);
    }

    private View buildSplash() {
        try {
            FrameLayout splash = new FrameLayout(this);
            splash.setBackgroundColor(0xFF000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            splash.setLayoutParams(lp);

            FrameLayout box = new FrameLayout(this);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            box.setLayoutParams(blp);

            TextView logo = new TextView(this);
            logo.setText("RPCSV");
            logo.setTextColor(0xFF8B5CF6);
            logo.setTextSize(34);
            logo.setTypeface(Typeface.DEFAULT_BOLD);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
            llp.setMargins(0, 0, 0, 130);
            logo.setLayoutParams(llp);

            ProgressBar bar = new ProgressBar(this);
            FrameLayout.LayoutParams blp2 = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
            bar.setLayoutParams(blp2);

            TextView hint = new TextView(this);
            hint.setText("Iniciando jogo… compilando shaders do Vita3K.");
            hint.setTextColor(0xFFAAAAAA);
            hint.setTextSize(15);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            hlp.setMargins(32, 0, 32, 170);
            hint.setLayoutParams(hlp);

            FrameLayout barWrap = new FrameLayout(this);
            FrameLayout.LayoutParams bwrap = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            barWrap.addView(bar);
            barWrap.setLayoutParams(bwrap);

            box.addView(logo);
            box.addView(barWrap);
            box.addView(hint);
            splash.addView(box);
            return splash;
        } catch (Throwable t) {
            Log.e(TAG, "splash nao criado", t);
            return null;
        }
    }

    private void startSplashWatcher() {
        final long baseline = logLen();
        final Runnable check = new Runnable() {
            @Override
            public void run() {
                if (splashGone || isFinishing()) return;
                try {
                    File log = new File(getExternalFilesDir(null), "vita3k.log");
                    if (log.exists() && log.length() > baseline && log.length() < 64L * 1024 * 1024) {
                        RandomAccessFile raf = new RandomAccessFile(log, "r");
                        raf.seek(Math.max(0, baseline));
                        byte[] b = new byte[(int) Math.min(log.length() - baseline, 65536)];
                        int r = raf.read(b);
                        raf.close();
                        String tail = r > 0 ? new String(b, 0, r, "UTF-8") : "";
                        if (tail.contains("Launching -> Running")) {
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() { dismissSplash(); }
                            }, 10000);
                            return;
                        }
                    }
                    ui.postDelayed(this, 1500);
                } catch (Throwable t) {
                    ui.postDelayed(this, 1500);
                }
            }
        };
        // safety: nunca deixar o splash prender a tela para sempre
        ui.postDelayed(new Runnable() {
            @Override
            public void run() { dismissSplash(); }
        }, 45000);
        ui.post(check);
    }

    private long logLen() {
        try {
            File log = new File(getExternalFilesDir(null), "vita3k.log");
            return log.exists() ? log.length() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void dismissSplash() {
        if (splashGone) return;
        splashGone = true;
        if (splash == null) return;
        try {
            AlphaAnimation a = new AlphaAnimation(1f, 0f);
            a.setDuration(600);
            a.setFillAfter(true);
            splash.startAnimation(a);
        } catch (Throwable ignore) {}
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup p = (ViewGroup) splash.getParent();
                    if (p != null) p.removeView(splash);
                } catch (Throwable ignore) {}
            }
        }, 650);
    }

    @Override
    protected void onResume() {
        AppLog.step("EngineActivity.onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        AppLog.step("EngineActivity.onPause");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        AppLog.step("EngineActivity.onDestroy (isFinishing=" + isFinishing() + ")");
        super.onDestroy();
    }

    @Override
    protected String[] getArguments() {
        Intent intent = getIntent();
        String[] args = intent != null ? intent.getStringArrayExtra(APP_RESTART_PARAMETERS) : null;
        if (args == null) args = new String[0];
        return args;
    }

    public void setCurrentGameId(String gameId) {
        currentGameId = gameId;
    }

    public void showFileDialog() {
        // sem dialogo de arquivos neste fluxo; o emulador segue so com o jogo
    }
}