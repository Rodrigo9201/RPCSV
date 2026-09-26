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
import java.util.Arrays;
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
 *   4. aplica os ajustes por titulo (aba "Config" da tela de detalhe do app);
 *   5. mantem a tela acesa (FLAG_KEEP_SCREEN_ON) para o jogo nunca "apagar".
 */
public class EngineActivity extends SDLActivity {

    private static final String TAG = "RpcSVO";

    private volatile boolean memSampling;
    private Thread memSampler;

    static final String APP_RESTART_PARAMETERS = "AppStartParameters";

    /** Titulo em execucao, usado para achar os ajustes daquela aba "Config". */
    static final String EXTRA_TITLE_ID = "rpcsv.titleId";

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
            + "memory-mapping: Double buffer\n"
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
            scalars.put("show-live-area-screen", "false");
            scalars.put("stretch_the_display_area", "true");
            scalars.put("fullscreen_hd_res_pixel_perfect", "true");

            readUiOverrides(scalars, lists);

            // Chaves que a build embarcada da engine nao consegue converter.
            // Enquanto uma delas estiver no arquivo o yaml-cpp aborta o
            // config.yml inteiro ("bad conversion") e nenhum ajuste do app
            // chega na engine — sem nenhum aviso na tela. O patch so reescreve
            // chaves, entao a remocao e explicita.
            String original = text;
            text = dropUnparsableKeys(text);

            String next = patchYaml(text, scalars, lists);
            // Compara com o texto lido do disco, e nao com o ja filtrado: se
            // o patch nao mudasse nada, a versao sem a chave ruim seria
            // descartada e o poison key continuaria no arquivo para sempre.
            boolean changed = !cfg.exists() || !next.equals(original);
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

                readGraphicsOverrides(settings, scalars);

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

                readSystemOverrides(settings, scalars, lists);
                readControlsOverrides(settings, scalars, lists);
                readCameraOverrides(settings, scalars);
                readInterfaceOverrides(settings, scalars);
                readEmulatorOverrides(settings, scalars);
                readNetworkOverrides(settings, scalars);
                readDebugOverrides(settings, scalars, lists);
            }

            readEngineOverrides(root, scalars);
            readTitleOverrides(root, scalars);
        } catch (Throwable t) {
            Log.e(TAG, "config.json ignorado", t);
        }
    }

    /**
     * Aba Sistema: identificadores de language/data/hora e o teclado em tela
     * (IME). sys-lang e current-ime-lang usam a mesma enum de 0..19
     * (SCE_SYSTEM_LANG / SCE_ImeLanguage).
     */
    private void readSystemOverrides(JSONObject settings, Map<String, String> scalars,
                                     Map<String, String[]> lists) {
        putInt(scalars, settings, "sys-button", "sysButton", 1, 0, 1);
        putLang(scalars, settings, "sys-lang", "sysLang");
        putLang(scalars, settings, "current-ime-lang", "currentImeLang");
        putInt(scalars, settings, "sys-date-format", "sysDateFormat", 0, 0, 2);
        putInt(scalars, settings, "sys-time-format", "sysTimeFormat", 0, 0, 1);
        putBool(scalars, settings, "pstv-mode", "pstvMode", false);
        putBool(scalars, settings, "show-mode", "showMode", false);
        putBool(scalars, settings, "demo-mode", "demoMode", false);

        if (settings.has("userLang")) {
            // Vazio = a engine escolhe pelo idioma do sistema.
            scalars.put("user-lang", settings.optString("userLang", "").trim());
        }
        if (settings.has("imeLangs")) {
            // Campo vazio nao pode apagar a lista do template (ime-langs: [4]).
            String[] l = intArray(settings.opt("imeLangs"), 32);
            if (l.length > 0) lists.put("ime-langs", l);
        }
    }

    /**
     * Aba Controles: multiplicador analogico, LED, binds de teclado (scancodes
     * do SDL) e o mapeamento de botoes/eixos do gamepad externo.
     */
    private void readControlsOverrides(JSONObject settings, Map<String, String> scalars,
                                      Map<String, String[]> lists) {
        putBool(scalars, settings, "disable-motion", "disableMotion", false);

        // O slider vai de 50 a 200; a engine le o multiplicador como float.
        if (settings.has("analogMultiplier")) {
            double m = settings.optDouble("analogMultiplier", 1.0d);
            if (m < 0.1d) m = 0.1d;
            if (m > 5.0d) m = 5.0d;
            double v = Math.round(m) / 100.0d;
            scalars.put("controller-analog-multiplier", String.valueOf(v));
        }

        if (settings.has("ledColor")) {
            String[] rgb = rgbList(settings.optString("ledColor", ""));
            if (rgb.length == 3) lists.put("controller-led-color", rgb);
        }

        JSONObject kbd = settings.optJSONObject("keyboard");
        if (kbd != null) {
            Iterator<String> it = kbd.keys();
            while (it.hasNext()) {
                String raw = it.next();
                String action = kbdAction(raw);
                if (action == null) continue;
                // config.json guarda "button-cross"; o config.yml usa
                // "keyboard-button-cross".
                scalars.put("keyboard-" + action, scancode(kbd.optString(raw, "")));
            }
        }

        if (settings.has("controllerBinds")) {
            String[] b = intArray(settings.opt("controllerBinds"), 15);
            if (b.length == 15) lists.put("controller-binds", b);
        }
        if (settings.has("controllerAxisBinds")) {
            String[] a = intArray(settings.opt("controllerAxisBinds"), 7);
            if (a.length == 7) lists.put("controller-axis-binds", a);
        }
    }

    /** Aba Camera: tipo, cor, imagem e id do dispositivo. */
    private void readCameraOverrides(JSONObject settings, Map<String, String> scalars) {
        putInt(scalars, settings, "front-camera-type", "frontCamType", 2, 0, 2);
        putInt(scalars, settings, "back-camera-type", "backCamType", 2, 0, 2);
        // A engine guarda a cor como inteiro 0xRRGGBB, a tela usa #rrggbb.
        if (settings.has("frontCamColor")) {
            scalars.put("front-camera-color", String.valueOf(colorInt(settings.optString("frontCamColor", ""))));
        }
        if (settings.has("backCamColor")) {
            scalars.put("back-camera-color", String.valueOf(colorInt(settings.optString("backCamColor", ""))));
        }
        if (settings.has("frontCamImage")) {
            scalars.put("front-camera-image", settings.optString("frontCamImage", ""));
        }
        if (settings.has("backCamImage")) {
            scalars.put("back-camera-image", settings.optString("backCamImage", ""));
        }
        if (settings.has("frontCamId")) {
            scalars.put("front-camera-id", settings.optString("frontCamId", ""));
        }
        if (settings.has("backCamId")) {
            scalars.put("back-camera-id", settings.optString("backCamId", ""));
        }
    }

    /** Aba Interface: fundo, grade da lista de apps e avisos do sistema. */
    private void readInterfaceOverrides(JSONObject settings, Map<String, String> scalars) {
        // O slider vai de 0 a 100; a engine usa float de 0 a 1.
        if (settings.has("backgroundAlpha")) {
            double a = settings.optDouble("backgroundAlpha", 0.3d);
            if (a < 0.0d) a = 0.0d;
            if (a > 1.0d) a = 1.0d;
            scalars.put("background-alpha", String.valueOf(Math.round(a * 100.0d) / 100.0d));
        }
        putBool(scalars, settings, "apps-list-grid", "appsListGrid", false);
        putBool(scalars, settings, "show-welcome", "showWelcome", true);
        putBool(scalars, settings, "warn-missing-firmware", "warnMissingFirmware", true);
    }

    /** Aba Emulador: boot, sobreposicao, captura, atrasos e turbo. */
    private void readEmulatorOverrides(JSONObject settings, Map<String, String> scalars) {
        putBool(scalars, settings, "boot-apps-full-screen", "bootAppsFullScreen", true);
        putBool(scalars, settings, "show-live-area-screen", "showLiveAreaScreen", false);
        putBool(scalars, settings, "show-compile-shaders", "showCompileShaders", true);
        putBool(scalars, settings, "turbo-mode", "turboMode", false);
        putBool(scalars, settings, "discord-rich-presence", "discordRichPresence", false);
        putBool(scalars, settings, "performance-overlay", "performanceOverlay", false);
        putInt(scalars, settings, "performance-overlay-detail", "performanceOverlayDetail", 0, 0, 3);
        putInt(scalars, settings, "performance-overlay-position", "performanceOverlayPosition", 0, 0, 5);
        putInt(scalars, settings, "screenshot-format", "screenshotFormat", 0, 0, 2);
        putInt(scalars, settings, "file-loading-delay", "fileLoadingDelay", 0, 0, 30000);
        putInt(scalars, settings, "delay-start", "delayStart", 0, 0, 3600);
        putInt(scalars, settings, "delay-background", "delayBackground", 0, 0, 3600);
    }

    /** Aba Rede: HTTP, login automatico, tempos limite e endereco ad-hoc. */
    private void readNetworkOverrides(JSONObject settings, Map<String, String> scalars) {
        putBool(scalars, settings, "http-enable", "httpEnable", true);
        putBool(scalars, settings, "user-auto-connect", "userAutoConnect", false);
        putInt(scalars, settings, "check-for-updates-mode", "checkForUpdatesMode", 0, 0, 2);
        putInt(scalars, settings, "http-timeout-attempts", "httpTimeoutAttempts", 50, 0, 1000);
        putInt(scalars, settings, "http-timeout-sleep-ms", "httpTimeoutSleepMs", 100, 0, 60000);
        putInt(scalars, settings, "http-read-end-attempts", "httpReadEndAttempts", 10, 0, 1000);
        putInt(scalars, settings, "http-read-end-sleep-ms", "httpReadEndSleepMs", 250, 0, 60000);
        // NÃO escrever "adhoc-addr" no config.yml, mesmo com adhocAddr no
        // config.json. A build da engine embarcada rejeita essa chave com
        // "yaml-cpp: bad conversion" para qualquer valor (int, string, lista e
        // nulo), e o erro é fatal para o arquivo inteiro: o config.yml é
        // descartado e TODOS os ajustes do app passam a ser ignorados pela
        // engine, sem aviso. Como patchYaml() reinsere no fim toda chave pedida
        // que não exista no arquivo, incluir a chave aqui a trazia de volta a
        // cada boot. A remoção de instalações antigas é feita por
        // dropUnparsableKeys(), em onConfigureEngine().
        // psn-signed-in fica por conta do app: e um estado de sessao, nao uma
        // preferencia, e a engine reescreve esse valor sozinha.
    }

    /** Aba Depuracao: registro, depurador remoto e profiler. */
    private void readDebugOverrides(JSONObject settings, Map<String, String> scalars,
                                    Map<String, String[]> lists) {
        putInt(scalars, settings, "log-level", "logLevel", 0, 0, 6);
        putBool(scalars, settings, "log-active-shaders", "logActiveShaders", false);
        putBool(scalars, settings, "log-uniforms", "logUniforms", false);
        putBool(scalars, settings, "log-compat-warn", "logCompatWarn", false);
        putBool(scalars, settings, "archive-log", "archiveLog", false);
        putBool(scalars, settings, "gdbstub", "gdbstub", false);
        putBool(scalars, settings, "wait-for-debugger", "waitForDebugger", false);
        putBool(scalars, settings, "color-surface-debug", "logColorSurface", false);
        putBool(scalars, settings, "tracy-primitive-impl", "tracyPrimitiveImpl", false);
        if (settings.has("tracyModules")) {
            String[] m = strArray(settings.opt("tracyModules"));
            if (m.length > 0) lists.put("tracy-advanced-profiling-modules", m);
        }
        // log-exports, log-imports, dump-elfs, watch-memory e watch-import-calls
        // nao existem no config.yml desta build: as chaves da tela so ficam no
        // config.json.
    }

    /**
     * Fora do bloco "settings": cpu-pool-size (bloco cpu) e pref-path (pasta
     * do emulador, que o app tambem usa como installDir).
     */
    private void readEngineOverrides(JSONObject root, Map<String, String> scalars) {
        JSONObject cpu = root.optJSONObject("cpu");
        JSONObject settings = root.optJSONObject("settings");
        int pool = -1;
        if (cpu != null && cpu.has("poolSize")) pool = cpu.optInt("poolSize", 10);
        if (pool < 0 && settings != null && settings.has("cpuPoolSize")) {
            pool = settings.optInt("cpuPoolSize", 10);
        }
        if (pool > 0) {
            if (pool > 256) pool = 256;
            scalars.put("cpu-pool-size", String.valueOf(pool));
        }
        String pref = root.optString("installDir", "");
        if (pref != null && pref.length() > 0) scalars.put("pref-path", pref);
    }

    // ------------------------- auxiliares de traducao -------------------------

    private static void putInt(Map<String, String> scalars, JSONObject settings,
                               String ymlKey, String jsonKey, int dflt, int min, int max) {
        if (!settings.has(jsonKey)) return;
        int v = settings.optInt(jsonKey, dflt);
        if (v < min) v = min;
        if (v > max) v = max;
        scalars.put(ymlKey, String.valueOf(v));
    }

    /** sys-lang/current-ime-lang compartilham a enum de 0..19. */
    private static void putLang(Map<String, String> scalars, JSONObject settings,
                                String ymlKey, String jsonKey) {
        putInt(scalars, settings, ymlKey, jsonKey, 1, 0, 19);
    }

    /**
     * Acoes do teclado gravadas por versoes antigas do app, que usavam o nome
     * curto ("cross"). O config.yml de hoje usa "keyboard-button-cross". Devolve
     * null para uma acao que nao existe no config.yml, para nao criar chaves
     * invalidas no arquivo da engine.
     */
    private static String kbdAction(String raw) {
        if (raw == null || raw.length() == 0) return null;
        String a = raw;
        if (a.startsWith("keyboard-")) a = a.substring("keyboard-".length());
        if (a.indexOf("button-") == 0 || a.indexOf("leftstick-") == 0 || a.indexOf("rightstick-") == 0
                || a.indexOf("gui-") == 0 || a.indexOf("toggle-") == 0 || a.indexOf("take-") == 0
                || a.indexOf("pinch-") == 0 || a.indexOf("alternate-") == 0) {
            return a;
        }
        // Nome curto: buttons, sticks e os atalhos que existiam na versao antiga.
        String[] buttons = { "cross", "circle", "square", "triangle", "up", "down", "left", "right",
            "l1", "r1", "l2", "r2", "l3", "r3", "start", "select", "psbutton" };
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].equals(a)) return "button-" + a;
        }
        String[] others = { "leftstick-left", "leftstick-right", "leftstick-up", "leftstick-down",
            "rightstick-left", "rightstick-right", "rightstick-up", "rightstick-down",
            "gui-fullscreen", "gui-toggle-touch", "toggle-texture-replacement", "take-screenshot",
            "pinch-modifier", "alternate-pinch-in", "alternate-pinch-out" };
        for (int i = 0; i < others.length; i++) {
            if (others[i].equals(a)) return a;
        }
        return null;
    }

    /**
     * A engine le scancodes do SDL pelo nome ("KeyQ", "ShiftRight") e "Unbound"
     * quando nao ha tecla. O app gravava nomes de teclado de PC ("X",
     * "Right Shift"), que a engine nao reconhece.
     */
    private static String scancode(String raw) {
        if (raw == null) return "Unbound";
        String v = raw.trim();
        if (v.length() == 0) return "Unbound";
        if ("None".equals(v)) return "Unbound";
        if ("Enter".equals(v)) return "Return";
        if ("Shift".equals(v)) return "ShiftLeft";
        if ("Right Shift".equals(v)) return "ShiftRight";
        if ("Left Shift".equals(v)) return "ShiftLeft";
        if ("Control".equals(v)) return "ControlLeft";
        if ("Right Control".equals(v)) return "ControlRight";
        if ("Left Control".equals(v)) return "ControlLeft";
        if ("Alt".equals(v)) return "AltLeft";
        if ("Right Alt".equals(v)) return "AltRight";
        if ("Left Alt".equals(v)) return "AltLeft";
        if (v.length() == 1) {
            char c = v.charAt(0);
            if (c >= 'A' && c <= 'Z') return "Key" + c;
            if (c >= 'a' && c <= 'z') return "Key" + Character.toUpperCase(c);
            if (c >= '0' && c <= '9') return v;
        }
        return v;
    }

    /** "#rrggbb" (ou "rrggbb") para o inteiro que a engine le. */
    private static long colorInt(String hex) {
        if (hex == null) return 0L;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6) return 0L;
        try {
            return Long.parseLong(h, 16) & 0xFFFFFFL;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** "255,0,0" (ou uma lista) para o bloco YAML [r, g, b]. */
    private static String[] rgbList(String raw) {
        if (raw == null) return new String[0];
        String[] parts = raw.split(",");
        String[] out = new String[3];
        int n = 0;
        for (int i = 0; i < parts.length && n < 3; i++) {
            try {
                int v = Integer.parseInt(parts[i].trim());
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                out[n++] = String.valueOf(v);
            } catch (NumberFormatException e) {
                // ignora o campo invalido e segue
            }
        }
        if (n == 0) return new String[0];
        String[] sized = new String[n];
        System.arraycopy(out, 0, sized, 0, n);
        return sized;
    }

    /** JSON array (ou texto separado por virgula) para o bloco de lista. */
    private static String[] intArray(Object raw, int max) {
        if (raw == null) return new String[0];
        List<Integer> vals = new ArrayList<Integer>();
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length() && vals.size() < max; i++) {
                int v = a.optInt(i, 0);
                if (v < 0) v = 0;
                vals.add(v);
            }
        } else {
            String s = String.valueOf(raw);
            String[] parts = s.split(",");
            for (int i = 0; i < parts.length && vals.size() < max; i++) {
                try {
                    int v = Integer.parseInt(parts[i].trim());
                    if (v < 0) v = 0;
                    vals.add(v);
                } catch (NumberFormatException e) {
                    // ignora
                }
            }
        }
        return toStringArray(vals);
    }

    private static String[] strArray(Object raw) {
        if (raw == null) return new String[0];
        List<String> vals = new ArrayList<String>();
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length(); i++) {
                String v = a.optString(i, "").trim();
                if (v.length() > 0) vals.add(v);
            }
        } else {
            String[] parts = String.valueOf(raw).split(",");
            for (int i = 0; i < parts.length; i++) {
                String v = parts[i].trim();
                if (v.length() > 0) vals.add(v);
            }
        }
        return vals.toArray(new String[vals.size()]);
    }

    private static String[] toStringArray(List<Integer> vals) {
        String[] out = new String[vals.size()];
        for (int i = 0; i < vals.size(); i++) out[i] = String.valueOf(vals.get(i));
        return out;
    }

    /**
     * Aba Grafico: traduz as opcoes dos baloes da tela de configuracoes para as
     * chaves do config.yml da engine. Sem esta traducao os botoes gravavam no
     * config.json e a engine continuava booting com o valor do template.
     *
     * So entram chaves que ja existem no config.yml (o patchYaml() reescreve
     * linhas existentes e nao insere novas) e valores sao filtrados antes: um
     * valor invalido faria a engine cair no padrao sem avisar.
     */
    private void readGraphicsOverrides(JSONObject settings, Map<String, String> scalars) {
        // Baloes com chave liga/desliga (async pipeline, surface sync, caches,
        // texturas, SPIR-V, FPS hack).
        putBool(scalars, settings, "disable-surface-sync", "disableSurfaceSync", false);
        putBool(scalars, settings, "async-pipeline-compilation", "asyncPipelineCompilation", true);
        putBool(scalars, settings, "texture-cache", "textureCache", true);
        putBool(scalars, settings, "hashless-texture-cache", "hashlessTextureCache", false);
        putBool(scalars, settings, "import-textures", "importTextures", false);
        putBool(scalars, settings, "export-textures", "exportTextures", false);
        putBool(scalars, settings, "export-as-png", "exportAsPng", true);
        putBool(scalars, settings, "shader-cache", "shaderCache", true);
        putBool(scalars, settings, "spirv-shader", "spirvShader", false);
        putBool(scalars, settings, "fps-hack", "fpsHack", false);
        putBool(scalars, settings, "v-sync", "vSync", true);
        putInt(scalars, settings, "gpu-idx", "gpuIdx", 0, 0, 15);
        putBool(scalars, settings, "validation-layer", "validationLayer", false);

        // Driver Vulkan especifico. Vazio = deixa a engine escolher.
        if (settings.has("customDriverName")) {
            scalars.put("custom-driver-name", settings.optString("customDriverName", "").trim());
        }

        // Internal Resolution Upscaling (slider 1x..4x): a engine le float.
        if (settings.has("resolutionMultiplier")) {
            double m = settings.optDouble("resolutionMultiplier", 1.0d);
            if (m < 1.0d) m = 1.0d;
            if (m > 4.0d) m = 4.0d;
            scalars.put("resolution-multiplier", String.valueOf(Math.round(m * 100.0d) / 100.0d));
        }

        // Filtragem anisotropica (abas 1x 2x 4x 8x 16x).
        if (settings.has("anisotropicFiltering")) {
            int a = settings.optInt("anisotropicFiltering", 1);
            if (a != 2 && a != 4 && a != 8 && a != 16) a = 1;
            scalars.put("anisotropic-filtering", String.valueOf(a));
        }

        // Memory Mapping (abas Disable / Double Buffer / Page Table / Native Buffer).
        if (settings.has("memoryMapping")) {
            String mapping = normalizeMemoryMapping(settings.optString("memoryMapping", ""));
            if (mapping != null) scalars.put("memory-mapping", mapping);
        }
    }

    private static void putBool(Map<String, String> scalars, JSONObject settings,
                                String ymlKey, String jsonKey, boolean dflt) {
        if (!settings.has(jsonKey)) return;
        boolean v;
        Object raw = settings.opt(jsonKey);
        if (raw instanceof Boolean) {
            v = ((Boolean) raw).booleanValue();
        } else if (raw instanceof String) {
            v = Boolean.parseBoolean((String) raw);
        } else {
            v = settings.optBoolean(jsonKey, dflt);
        }
        scalars.put(ymlKey, v ? "true" : "false");
    }

    /**
     * A engine compara memory-mapping por nome. config.json pode ter os nomes
     * antigos (double-buffer, triple-buffer, streaming) e esses nao existem
     * mais: caem no primeiro metodo valido em vez de sumirem do balao.
     */
    private static String normalizeMemoryMapping(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        if (v.equals("disabled") || v.equals("disable") || v.equals("none")
                || v.equals("off") || v.equals("false") || v.isEmpty()) {
            return "Disabled";
        }
        if (v.equals("page-table") || v.equals("page table") || v.equals("pagetable")) {
            return "Page Table";
        }
        if (v.equals("native-buffer") || v.equals("native buffer") || v.equals("nativebuffer")) {
            return "Native Buffer";
        }
        // double-buffer, triple-buffer, streaming e qualquer valor desconhecido.
        return "Double buffer";
    }

    /**
     * das globais de proposito: quem manda e o ajuste especifico do jogo.
     *
     * Só entram chaves que ja existem no config.yml, porque o patchYaml()
     * reescreve linhas existentes e nao insere chaves novas. Uma chave
     * inventada aqui seria aceita em silencio e nunca teria efeito.
     */
    private void readTitleOverrides(JSONObject root, Map<String, String> scalars) {
        try {
            JSONObject all = root.optJSONObject("titleOverrides");
            if (all == null) return;
            String tid = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TITLE_ID);
            if (tid == null || tid.isEmpty()) tid = currentGameId;
            if (tid == null || tid.isEmpty()) return;
            JSONObject ov = all.optJSONObject(tid);
            if (ov == null) return;

            if (ov.has("resolutionMultiplier")) {
                int m = ov.optInt("resolutionMultiplier", 1);
                if (m < 1) m = 1;
                if (m > 2) m = 2;
                scalars.put("resolution-multiplier", String.valueOf(m));
            }
            if (ov.has("vSync")) {
                scalars.put("v-sync", ov.optBoolean("vSync", true) ? "true" : "false");
            }
            if (ov.has("performanceOverlay")) {
                scalars.put("performance-overlay", ov.optBoolean("performanceOverlay", false) ? "true" : "false");
            }
            if (ov.has("screenFilter")) {
                String sf = ov.optString("screenFilter", "");
                if ("Nearest".equals(sf) || "Bilinear".equals(sf) || "Bicubic".equals(sf)
                        || "FXAA".equals(sf) || "FSR".equals(sf)) {
                    scalars.put("screen-filter", sf);
                }
            }
            if (ov.has("audioVolume")) {
                int vol = ov.optInt("audioVolume", 100);
                if (vol < 0) vol = 0;
                if (vol > 150) vol = 150;
                scalars.put("audio-volume", String.valueOf(vol));
            }
        } catch (Throwable t) {
            Log.e(TAG, "titleOverrides ignorado", t);
        }
    }

    // ------------------------- YAML basico (patch idempotente) -------------------------

    /**
     * Chaves que a build da engine embarcada nao consegue ler. Qualquer valor
     * delas derruba o config.yml inteiro, entao sao removidas do arquivo a cada
     * boot (inclusive de quem ja tinha o app instalado antes desta correcao).
     *
     * "adhoc-addr" e o caso conhecido: a engine loga
     * "yaml-cpp: error at line N, column 13: bad conversion" para int, string,
     * lista e nulo. O upstream declara a chave como int, mas a build Android
     * embarcada nao converte. Como o app nunca usa ad-hoc, basta nao escrever.
     */
    private static final Set<String> UNPARSABLE_KEYS = new HashSet<String>(
            Arrays.asList("adhoc-addr"));

    private static String dropUnparsableKeys(String text) {
        List<String> lines = new ArrayList<String>();
        for (String l : text.split("\n", -1)) lines.add(l);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isKeyLine(line) && UNPARSABLE_KEYS.contains(keyOf(line))) {
                // Pula tambem o bloco indentado que possa vir abaixo.
                i++;
                while (i < lines.size()) {
                    String n = lines.get(i);
                    if (n.length() > 0 && (n.charAt(0) == ' ' || n.charAt(0) == '\t')) i++;
                    else break;
                }
                i--;
                continue;
            }
            out.add(line);
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < out.size(); k++) {
            if (k > 0) sb.append('\n');
            sb.append(out.get(k));
        }
        return sb.toString();
    }

    /**
     * A engine le strings com aspas; sem elas um campo apagado na tela viraria
     * um null do YAML, que a engine nao converte em string vazia.
     */
    private static String yamlValue(String val) {
        if (val == null || val.length() == 0) return "\"\"";
        return val;
    }

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
                if (scalars.containsKey(key) && !lists.containsKey(key) && !doneSa.contains(key)) {
                    String val = yamlValue(scalars.get(key));
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
            if (lists.containsKey(key)) continue;
            if (!doneSa.contains(key)) { out.add(key + ": " + yamlValue(scalars.get(key))); changed = true; }
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
                int tick = 0;
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
                        // Mapa por regiao: mostra se o processo cresce por um
                        // unico bloco gigante (leitura de PFS/psarc) ou por
                        // milhares de alocacoes pequenas (vazamento em container).
                        if (tick == 2 || tick == 6) AppLog.step(dumpMappings());
                    } catch (Throwable ignore) {
                    }
                    tick++;
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

    /**
     * Le /proc/self/smaps e devolve as maiores regiaoes por Rss, com o nome do
     * objeto quando houver. So para diagnostico: some em varios minutos de log.
     */
    private static String dumpMappings() {
        StringBuilder sb = new StringBuilder("  smaps: ");
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/smaps"), 65536);
            String name = "";
            long rss = 0;
            int shown = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 0 && Character.digit(line.charAt(0), 16) >= 0
                        && line.indexOf('-') > 0) {
                    if (rss > 32L * 1024 * 1024 && shown < 12) {
                        sb.append('[').append(name.length() > 40 ? name.substring(0, 40) : name)
                                .append(" rss=").append(rss >> 20).append("MB] ");
                        shown++;
                    }
                    int sp = line.indexOf(' ');
                    name = line.length() > 0 ? line.substring(Math.max(0, line.indexOf(' ') + 5)) : "";
                    if (name.length() > 0) name = name.trim();
                    if (name.length() == 0) name = "anon";
                    rss = 0;
                } else if (line.startsWith("Rss:")) {
                    rss += Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
            r.close();
            try {
                sb.append(" nativeHeap=").append(android.os.Debug.getNativeHeapAllocatedSize() >> 20).append("MB");
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            sb.append("falhou: ").append(t);
        }
        return sb.toString();
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
        AppLog.step("getArguments -> " + java.util.Arrays.toString(args)
                + " extras=" + (intent == null || intent.getExtras() == null ? "null"
                        : intent.getExtras().keySet().toString()));
        return args;
    }

    public void setCurrentGameId(String gameId) {
        currentGameId = gameId;
    }

    public void showFileDialog() {
        // sem dialogo de arquivos neste fluxo; o emulador segue so com o jogo
    }
}