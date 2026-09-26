'use strict';

const Settings = (() => {
  let bound = false;
  const map = {
    // Graphics
    'set-res': 'resolution',
    'set-renderer': 'renderer',
    'set-vsync': 'vSync',
    'set-gles': 'forceGLES',
    'set-buffer': 'bufferRendering',
    'set-texfilter': 'textureFiltering',
    'set-gpustats': 'showGpuStats',
    'set-fhd-pixel': 'fullscreenHdResPixelPerfect',
    'set-stretch-display': 'stretchDisplayArea',
    'set-async-pipeline': 'asyncPipelineCompilation',
    'set-texture-cache': 'textureCache',
    'set-hashless-tc': 'hashlessTextureCache',
    'set-import-tex': 'importTextures',
    'set-export-tex': 'exportTextures',
    'set-shader-cache': 'shaderCache',
    'set-spirv': 'spirvShader',
    'set-fps-hack': 'fpsHack',
    // Audio
    'set-audio-backend': 'audioBackend',
    'set-ngs': 'ngsEnable',
    'set-theme-music': 'themeMusic',
    // Camera
    'set-front-cam-type': 'frontCamType',
    'set-back-cam-type': 'backCamType',
    // System
    'set-enter-btn': 'sysButton',
    'set-pstv': 'pstvMode',
    'set-show-mode': 'showMode',
    'set-demo-mode': 'demoMode',
    'set-sys-lang': 'sysLang',
    'set-ime-lang': 'currentImeLang',
    'set-date-format': 'sysDateFormat',
    'set-time-format': 'sysTimeFormat',
    // Controls
    'set-disable-motion': 'disableMotion',
    // Interface
    'set-stylesheet': 'stylesheet',
    'set-apps-grid': 'appsListGrid',
    'set-show-welcome': 'showWelcome',
    'set-warn-fw': 'warnMissingFirmware',
    'set-confirm-exit': 'confirmExit',
    // Emulator
    'set-boot-fullscreen': 'bootAppsFullScreen',
    'set-show-livearea': 'showLiveAreaScreen',
    'set-show-shaderhint': 'showCompileShaders',
    'set-turbo-mode': 'turboMode',
    'set-update-mode': 'checkForUpdatesMode',
    'set-discord': 'discordRichPresence',
    'set-perf-overlay': 'performanceOverlay',
    'set-perf-detail': 'performanceOverlayDetail',
    'set-perf-position': 'performanceOverlayPosition',
    'set-screenshot-format': 'screenshotFormat',
    // Network
    'set-region': 'region',
    'set-delay': 'delayVideo',
    'set-http-enable': 'httpEnable',
    'set-np-signed': 'psnSignedIn',
    'set-auto-connect': 'userAutoConnect',
    // Debug
    'set-log-level': 'logLevel',
    'set-log-exports': 'logExports',
    'set-log-imports': 'logImports',
    'set-log-active-shaders': 'logActiveShaders',
    'set-log-uniforms': 'logUniforms',
    'set-log-compat-warn': 'logCompatWarn',
    'set-archive-log': 'archiveLog',
    'set-gdbstub': 'gdbstub',
    'set-wait-debugger': 'waitForDebugger',
    'set-val-layer': 'validationLayer',
    'set-color-surface': 'logColorSurface',
    'set-tracy-primitives': 'tracyPrimitiveImpl',
    'set-dump-elfs': 'dumpElfs',
    'set-watch-mem': 'watchMemory',
    'set-watch-imports': 'watchImportCalls',
  };

  // resolutionMultiplier e anisotropicFiltering nao ficam aqui: os dois viraram
  // controle proprio (slider em RANGES e abas em graphics-tab).
  const NUMERIC = new Set([
    'frontCamType', 'backCamType',
    'sysButton', 'sysLang', 'currentImeLang', 'sysDateFormat', 'sysTimeFormat',
    'performanceOverlayDetail',
    'performanceOverlayPosition', 'screenshotFormat', 'logLevel',
  ]);

  const SCREEN_FILTERS = new Set(['Nearest', 'Bilinear', 'Bicubic', 'FXAA', 'FSR']);

  const RANGES = {
    'set-audio-volume': { key: 'audioVolume', label: 'set-audio-vol-val', fmt: (v) => v + '%' },
    'set-theme-volume': { key: 'themeMusicVolume', label: 'set-theme-vol-val', fmt: (v) => v + '%' },
    'set-analog-mult': { key: 'analogMultiplier', label: 'set-analog-mult-val', fmt: (v) => (v / 100).toFixed(1) + 'x' },
    'set-bg-alpha': { key: 'backgroundAlpha', label: 'set-bg-alpha-val', fmt: (v) => v + '%' },
    'set-file-delay': { key: 'fileLoadingDelay', label: 'set-file-delay-val', fmt: (v) => v + ' ms' },
    'set-cpu-pool': { key: 'cpuPoolSize', label: 'set-cpu-pool-val', fmt: (v) => String(v) },
    'set-delay-start': { key: 'delayStart', label: 'set-delay-start-val', fmt: (v) => v + ' s' },
    'set-delay-background': { key: 'delayBackground', label: 'set-delay-background-val', fmt: (v) => v + ' s' },
    'set-res-mult': {
      key: 'resolutionMultiplier',
      label: 'set-res-mult-val',
      fmt: (v) => trimNum(v) + 'x',
      parse: (v) => trimNum(parseFloat(v)) || 1,
    },
  };

  function trimNum(v) {
    const n = Number(v);
    if (!isFinite(n)) return 0;
    return Math.round(n * 100) / 100;
  }

  // Listas do config.yml (ime-langs, tracy-advanced-profiling-modules) aparecem
  // na tela como texto separado por virgula e voltam para o config.json como
  // array, que e o que a engine le.
  function intList(v) {
    if (Array.isArray(v)) return v;
    return String(v == null ? '' : v).split(',')
      .map((s) => parseInt(s.trim(), 10))
      .filter((n) => !isNaN(n));
  }

  function strList(v) {
    if (Array.isArray(v)) return v;
    return String(v == null ? '' : v).split(',')
      .map((s) => s.trim())
      .filter((s) => s.length);
  }

  function rangeValue(def, raw) {
    const v = def.parse ? def.parse(raw) : (parseInt(raw, 10) || 0);
    return v;
  }

  const TEXT_FIELDS = {
    'set-controller-led': 'ledColor',
    'set-adhoc-addr': 'adhocAddr',
    'set-custom-driver': 'customDriverName',
    'set-user-lang': 'userLang',
    'set-ime-langs': 'imeLangs',
    'set-tracy-modules': 'tracyModules',
    'set-front-cam-id': 'frontCamId',
    'set-back-cam-id': 'backCamId',
    'set-http-attempts': 'httpTimeoutAttempts',
    'set-http-sleep': 'httpTimeoutSleepMs',
    'set-http-read-attempts': 'httpReadEndAttempts',
    'set-http-read-sleep': 'httpReadEndSleepMs',
    'set-gpu-idx': 'gpuIdx',
    'set-log-buffer': 'logBufferSize',
    'set-log-font': 'logFontFamily',
  };
  const NUMERIC_FIELDS = new Set([
    'httpTimeoutAttempts', 'httpTimeoutSleepMs', 'httpReadEndAttempts', 'httpReadEndSleepMs',
    'logBufferSize', 'gpuIdx',
  ]);

  const COLOR_FIELDS = {
    'set-front-cam-color': 'frontCamColor',
    'set-back-cam-color': 'backCamColor',
  };
  const CAM_IMG = {
    'set-front-cam-path': 'frontCamImage',
    'set-back-cam-path': 'backCamImage',
  };

  // A engine le os scancodes do SDL pelo nome ("KeyQ", "ArrowUp", "ShiftRight")
  // e "Unbound" quando a acao nao tem tecla. A lista antiga gravava nomes de
  // teclado de PC ("X", "Right Shift"), que a engine nao reconhecia.
  const KBD_KEYS = ['Unbound',
    'KeyA', 'KeyB', 'KeyC', 'KeyD', 'KeyE', 'KeyF', 'KeyG', 'KeyH', 'KeyI', 'KeyJ',
    'KeyK', 'KeyL', 'KeyM', 'KeyN', 'KeyO', 'KeyP', 'KeyQ', 'KeyR', 'KeyS', 'KeyT',
    'KeyU', 'KeyV', 'KeyW', 'KeyX', 'KeyY', 'KeyZ',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12',
    'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight',
    'Return', 'Space', 'Escape', 'Backspace', 'Tab', 'Delete', 'Insert',
    'Home', 'End', 'PageUp', 'PageDown',
    'ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight',
    'AltLeft', 'AltRight', 'GUILeft', 'GUIRight', 'CapsLock',
    'Minus', 'Equals', 'LeftBracket', 'RightBracket', 'Backslash',
    'Semicolon', 'Apostrophe', 'Grave', 'Comma', 'Period', 'Slash',
    'KP_0', 'KP_1', 'KP_2', 'KP_3', 'KP_4', 'KP_5', 'KP_6', 'KP_7', 'KP_8', 'KP_9',
    'KP_PERIOD', 'KP_DIVIDE', 'KP_MULTIPLY', 'KP_MINUS', 'KP_PLUS', 'KP_ENTER',
  ];

  // Teclas do config.json antigo -> scancode. Sem isto um config ja salvo
  // mostrava o seletor vazio depois da migration.
  const KBD_LEGACY = {
    None: 'Unbound',
    Enter: 'Return',
    Space: 'Space',
    Escape: 'Escape',
    Backspace: 'Backspace',
    Tab: 'Tab',
    Shift: 'ShiftLeft',
    'Right Shift': 'ShiftRight',
    'Left Shift': 'ShiftLeft',
    Control: 'ControlLeft',
    'Right Control': 'ControlRight',
    'Left Control': 'ControlLeft',
    Alt: 'AltLeft',
    'Right Alt': 'AltRight',
    'Left Alt': 'AltLeft',
    ArrowUp: 'ArrowUp', ArrowDown: 'ArrowDown', ArrowLeft: 'ArrowLeft', ArrowRight: 'ArrowRight',
  };
  Array.from({ length: 26 }, (_, i) => {
    const letter = String.fromCharCode(65 + i);
    KBD_LEGACY[letter] = 'Key' + letter;
  });
  Array.from({ length: 10 }, (_, i) => { KBD_LEGACY[String(i)] = String(i); });

  // A acao e a chave do config.yml sem o prefixo "keyboard-": o app grava
  // "button-cross" e a engine le "keyboard-button-cross".
  const KBD_BUTTONS = [
    { key: 'button-cross', label: 'kbd_cross', dflt: 'KeyX' },
    { key: 'button-circle', label: 'kbd_circle', dflt: 'KeyC' },
    { key: 'button-square', label: 'kbd_square', dflt: 'KeyZ' },
    { key: 'button-triangle', label: 'kbd_triangle', dflt: 'KeyV' },
    { key: 'button-up', label: 'kbd_up', dflt: 'ArrowUp' },
    { key: 'button-down', label: 'kbd_down', dflt: 'ArrowDown' },
    { key: 'button-left', label: 'kbd_left', dflt: 'ArrowLeft' },
    { key: 'button-right', label: 'kbd_right', dflt: 'ArrowRight' },
    { key: 'button-l1', label: 'kbd_l1', dflt: 'KeyQ' },
    { key: 'button-r1', label: 'kbd_r1', dflt: 'KeyE' },
    { key: 'button-l2', label: 'kbd_l2', dflt: 'KeyU' },
    { key: 'button-r2', label: 'kbd_r2', dflt: 'KeyO' },
    { key: 'button-l3', label: 'kbd_l3', dflt: 'KeyF' },
    { key: 'button-r3', label: 'kbd_r3', dflt: 'KeyH' },
    { key: 'button-start', label: 'kbd_start', dflt: 'Return' },
    { key: 'button-select', label: 'kbd_select', dflt: 'ShiftRight' },
    { key: 'button-psbutton', label: 'kbd_psbutton', dflt: 'KeyP' },
  ];
  const KBD_STICKS = [
    { key: 'leftstick-up', label: 'kbd_lsup', dflt: 'KeyW' },
    { key: 'leftstick-down', label: 'kbd_lsdown', dflt: 'KeyS' },
    { key: 'leftstick-left', label: 'kbd_lsleft', dflt: 'KeyA' },
    { key: 'leftstick-right', label: 'kbd_lsright', dflt: 'KeyD' },
    { key: 'rightstick-up', label: 'kbd_rsup', dflt: 'KeyI' },
    { key: 'rightstick-down', label: 'kbd_rsdown', dflt: 'KeyK' },
    { key: 'rightstick-left', label: 'kbd_rsleft', dflt: 'KeyJ' },
    { key: 'rightstick-right', label: 'kbd_rsright', dflt: 'KeyL' },
  ];
  // Atalhos do proprio emulador (tela cheia, textura alternativa, screenshot...).
  const KBD_SHORTCUTS = [
    { key: 'gui-fullscreen', label: 'kbd_fullscreen', dflt: 'F11' },
    { key: 'gui-toggle-touch', label: 'kbd_toggle_touch', dflt: 'KeyT' },
    { key: 'toggle-texture-replacement', label: 'kbd_tex_repl', dflt: 'Unbound' },
    { key: 'take-screenshot', label: 'kbd_screenshot', dflt: 'Unbound' },
    { key: 'pinch-modifier', label: 'kbd_pinch', dflt: 'Unbound' },
    { key: 'alternate-pinch-in', label: 'kbd_pinch_in', dflt: 'Unbound' },
    { key: 'alternate-pinch-out', label: 'kbd_pinch_out', dflt: 'Unbound' },
  ];
  // Teclas alternativas: mesma acao com o modificador ligado. O config.yml
  // define "-alt" para todas as 32 acoes (64 bindings no total).
  const KBD_ALT = KBD_BUTTONS.concat(KBD_STICKS).concat(KBD_SHORTCUTS)
    .map((b) => Object.assign({}, b, { key: b.key + '-alt', alt: true, dflt: 'Unbound' }));

  const KBD_BINDS = []
    .concat(KBD_BUTTONS.map((b) => Object.assign({ group: 'kbd_buttons' }, b)))
    .concat(KBD_STICKS.map((b) => Object.assign({ group: 'kbd_sticks' }, b)))
    .concat(KBD_ALT.map((b) => Object.assign({ group: 'kbd_alt' }, b)))
    .concat(KBD_SHORTCUTS.map((b) => Object.assign({ group: 'kbd_shortcuts' }, b)));

  // controller-binds (15) e controller-axis-binds (7): indice de cada botao/eixo
  // do Vita dentro do gamepad pareado por Bluetooth. A engine nao expoe nomes
  // para os slots, entao a tela mostra o indice.
  const PAD_BUTTONS = Array.from({ length: 15 }, (_, i) => ({ key: String(i), label: 'pad_button' }));
  const PAD_AXES = Array.from({ length: 7 }, (_, i) => ({ key: String(i), label: 'pad_axis' }));

  const CORE_MODULES = [
    'activity_db', 'adhoc_matching', 'apputil', 'apputil_ext',
    'audiocodec', 'avcdec_for_player', 'bXCe', 'bgapputil',
    'common_gui_dialog', 'dbrecovery_utility', 'dbutil', 'friend_select',
    'incoming_dialog', 'ini_file_processor', 'libSceBeisobmf', 'libSceBemp2sys',
    'libSceCompanionUtil', 'libSceDtcpIp', 'libSceFt2', 'libSceJson',
    'libSceMp4Rec', 'libSceMusicExport', 'libSceNearDialogUtil', 'libSceNearUtil',
    'libScePhotoExport', 'libScePromoterUtil', 'libSceScreenShot', 'libSceShutterSound',
    'libSceSqlite', 'libSceTelephonyUtil', 'libSceTeleportClient', 'libSceTeleportServer',
    'libSceVideoExport', 'libSceVideoSearchEmpr', 'libSceXml', 'libatrac',
    'libc', 'libcdlg', 'libcdlg_calendar_review', 'libcdlg_cameraimport',
    'libcdlg_checkout', 'libcdlg_companion', 'libcdlg_compat', 'libcdlg_cross_controller',
    'libcdlg_friendlist', 'libcdlg_friendlist2', 'libcdlg_game_custom_data', 'libcdlg_game_custom_data_impl',
    'libcdlg_ime', 'libcdlg_invitation', 'libcdlg_invitation_impl', 'libcdlg_main',
    'libcdlg_msg', 'libcdlg_near', 'libcdlg_netcheck', 'libcdlg_np_message',
    'libcdlg_np_sns_fb', 'libcdlg_np_trophy_setup', 'libcdlg_npeula', 'libcdlg_npprofile2',
    'libcdlg_photoimport', 'libcdlg_photoreview', 'libcdlg_pocketstation', 'libcdlg_remote_osk',
    'libcdlg_savedata', 'libcdlg_tw_login', 'libcdlg_twitter', 'libcdlg_videoimport',
    'libclipboard', 'libdbg', 'libfiber', 'libfios2',
    'libg729', 'libgameupdate', 'libhandwriting', 'libhttp',
    'libime', 'libipmi_nongame', 'liblocation', 'liblocation_extension',
    'liblocation_factory', 'liblocation_internal', 'libmln', 'libmlnapplib',
    'libmlndownloader', 'libnaac', 'libnet', 'libnetctl',
    'libngs', 'libpaf', 'libpaf_web_map_view', 'libperf',
    'libpgf', 'libpvf', 'librudp', 'libsas',
    'libsceavplayer', 'libscejpegarm', 'libscejpegencarm', 'libscemp4',
    'libshellsvc', 'libssl', 'libsulpha', 'libsystemgesture',
    'libult', 'libvoice', 'libvoiceqos', 'livearea_util',
    'mail_api_for_local_libc', 'near_profile', 'notification_util', 'np_activity',
    'np_activity_sdk', 'np_basic', 'np_commerce2', 'np_common',
    'np_common_ps4', 'np_friend_privacylevel', 'np_kdc', 'np_manager',
    'np_matching2', 'np_message', 'np_message_contacts', 'np_message_dialog_impl',
    'np_message_padding', 'np_party', 'np_ranking', 'np_signaling',
    'np_sns_facebook', 'np_trophy', 'np_tus', 'np_utility',
    'np_webapi', 'party_member_list', 'psmkdc', 'pspnet_adhoc',
    'signin_ext', 'sqlite', 'store_checkout_plugin', 'trigger_util',
    'web_ui_plugin',
  ];

  function defaultCore() {
    const modules = {};
    CORE_MODULES.forEach((m) => { modules[m] = false; });
    return { loadingMode: 'automatic', modules };
  }

  function buildLangSelect(cfg) {
    const sel = document.getElementById('set-lang');
    sel.innerHTML = '';
    LANGUAGES.forEach((l) => {
      const opt = document.createElement('option');
      opt.value = l.code;
      opt.textContent = l.native || l.name;
      if (l.code === cfg.lang) opt.selected = true;
      sel.appendChild(opt);
    });
  }

  function baseName(p) {
    return String(p || '').replace(/\\/g, '/').split('/').pop() || p;
  }

  function safeSelectValue(el, val) {
    if (!el || val === undefined || val === null) return;
    const v = String(val);
    if (Array.from(el.options).some((o) => o.value === v)) el.value = v;
  }

  // Valores de "memory-mapping" aceitos pela engine (Vita3K). O config.yml
  // antigo do app trazia nomes diferentes ("double-buffer", "triple-buffer",
  // "streaming"); sem isto um config ja salvo ficava sem aba marcada e o
  // balão mostrava "nenhum selecionado".
  const MEMORY_MAPPING = [
    { value: 'Disabled', keys: ['disabled', 'disable', 'none', 'off', 'false', ''] },
    { value: 'Double buffer', keys: ['double-buffer', 'double buffer', 'doublebuffer', 'triple-buffer', 'streaming'] },
    { value: 'Page Table', keys: ['page-table', 'page table', 'pagetable'] },
    { value: 'Native Buffer', keys: ['native-buffer', 'native buffer', 'nativebuffer'] },
  ];
  const ANISO_VALUES = ['1', '2', '4', '8', '16'];

  function normalizeMemoryMapping(v) {
    const raw = String(v == null ? '' : v).trim().toLowerCase();
    const hit = MEMORY_MAPPING.find((m) => m.keys.indexOf(raw) !== -1);
    return hit ? hit.value : 'Double buffer';
  }

  function setGraphicsTabs(key, value) {
    const expected = String(value);
    document.querySelectorAll(`.graphics-tab[data-setting="${key}"]`).forEach((btn) => {
      const active = btn.dataset.value === expected;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-checked', active ? 'true' : 'false');
    });
  }

  // valor que vai para o config.json: as abas usam data-value, e aqui o tipo
  // e convertido (anisotropicFiltering e numero, highAccuracy e booleano).
  function tabValue(key, rawValue) {
    if (key === 'highAccuracy') return rawValue === 'true';
    if (key === 'anisotropicFiltering') return Number(rawValue) || 1;
    return rawValue;
  }

  function updateGraphicsCapabilities(renderer) {
    const vulkan = String(renderer || '').toLowerCase() === 'vulkan';
    const fsr = document.querySelector('.graphics-tab[data-setting="screenFilter"][data-value="FSR"]');
    if (fsr) fsr.disabled = !vulkan;
    document.querySelectorAll('.graphics-tab[data-setting="highAccuracy"]').forEach((btn) => {
      btn.disabled = !vulkan;
    });
    const filterHint = document.getElementById('screen-filter-hint');
    if (filterHint) {
      filterHint.textContent = t(vulkan ? 'set_screen_filter_hint' : 'set_screen_filter_opengl_hint');
    }
    const accuracyHint = document.getElementById('rendering-accuracy-hint');
    if (accuracyHint) {
      accuracyHint.textContent = t(vulkan ? 'set_rendering_accuracy_hint' : 'set_rendering_accuracy_opengl_hint');
    }
  }

  function setStateButton(btn, on) {
    if (!btn) return;
    btn.dataset.state = on ? 'on' : 'off';
    btn.textContent = t(on ? 'set_state_on' : 'set_state_off');
  }

  function fillGraphicsControls(cfg) {
    const settings = cfg.settings || {};
    const filter = SCREEN_FILTERS.has(settings.screenFilter) ? settings.screenFilter : 'Nearest';
    const highAccuracy = settings.highAccuracy === true || settings.highAccuracy === 'true';
    const aniso = String(settings.anisotropicFiltering == null ? 1 : settings.anisotropicFiltering);
    setGraphicsTabs('screenFilter', filter);
    setGraphicsTabs('highAccuracy', highAccuracy);
    setGraphicsTabs('anisotropicFiltering', ANISO_VALUES.indexOf(aniso) === -1 ? '1' : aniso);
    setGraphicsTabs('memoryMapping', normalizeMemoryMapping(settings.memoryMapping));
    setStateButton(document.getElementById('set-surface-sync-btn'),
      settings.disableSurfaceSync === true || settings.disableSurfaceSync === 'true');
    updateGraphicsCapabilities(settings.renderer || 'Vulkan');
  }

  function fillRanges(cfg) {
    Object.keys(RANGES).forEach((id) => {
      const def = RANGES[id];
      const el = document.getElementById(id);
      const lab = document.getElementById(def.label);
      if (!el) return;
      const v = (cfg.settings || {})[def.key];
      const nv = (v === undefined || v === null || isNaN(Number(v)))
        ? rangeValue(def, el.value)
        : rangeValue(def, v);
      el.value = nv;
      if (lab) lab.textContent = def.fmt(nv);
    });
  }

  function fillTextFields(cfg) {
    Object.keys(TEXT_FIELDS).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      const v = (cfg.settings || {})[TEXT_FIELDS[id]];
      el.value = (v === undefined || v === null) ? ''
        : (Array.isArray(v) ? v.join(', ') : String(v));
    });
  }

  function fillColors(cfg) {
    Object.keys(COLOR_FIELDS).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      const v = (cfg.settings || {})[COLOR_FIELDS[id]];
      el.value = (v && /^#[0-9a-fA-F]{6}$/.test(v)) ? v : '#000000';
    });
  }

  function fillCamImages(cfg) {
    Object.keys(CAM_IMG).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      const v = (cfg.settings || {})[CAM_IMG[id]];
      el.textContent = v ? baseName(v) : '\u2014';
      el.title = v || '';
    });
  }

  // config.json antigo guardava as teclas como "X"/"Right Shift" e as acoes
  // sem o prefixo do config.yml ("cross"). Normaliza para o formato atual
  // (scancode do SDL + "button-cross"), senao um config ja salvo aparecia com
  // o seletor vazio e a engine recebia uma tecla que ela nao reconhece.
  // Scancode do SDL: "KeyQ", "F11", "ShiftRight", "KP_0". Um valor fora da
  // lista da tela continua valido (engine mais nova, config editado a mao) e
  // precisa sobreviver ao salvamento; so lixo e normalizado para Unbound.
  function isScancode(v) {
    return typeof v === 'string' && /^[A-Za-z][A-Za-z0-9_]*$/.test(v);
  }

  function normalizeKbd(raw) {
    const out = {};
    const kbd = (raw && typeof raw === 'object') ? raw : {};
    Object.keys(kbd).forEach((k) => {
      let key = k;
      if (key.indexOf('button-') !== 0 && key.indexOf('leftstick-') !== 0
        && key.indexOf('rightstick-') !== 0 && key.indexOf('gui-') !== 0
        && key.indexOf('toggle-') !== 0 && key.indexOf('take-') !== 0
        && key.indexOf('pinch-') !== 0 && key.indexOf('alternate-') !== 0) {
        key = 'button-' + key;
      }
      const v = kbd[k];
      out[key] = KBD_LEGACY[v] || (isScancode(v) ? v : 'Unbound');
    });
    return out;
  }

  function kbdRow(name) {
    const row = document.createElement('div');
    row.className = 'kbd-row';
    row.appendChild(name);
    return row;
  }

  // O rotulo das teclas alternativas e o mesmo da acao + "(alt)"; a parte
  // traduzida fica num <span> proprio para o applyI18n() nao apagar o sufixo.
  function bindName(bind) {
    const name = document.createElement('span');
    name.className = 'kbd-name';
    if (!bind.alt) {
      name.setAttribute('data-i18n', bind.label);
      return name;
    }
    const base = document.createElement('span');
    base.setAttribute('data-i18n', bind.label);
    const suf = document.createElement('span');
    suf.className = 'kbd-alt';
    suf.textContent = ' ' + t('kbd_alt_suffix');
    name.appendChild(base);
    name.appendChild(suf);
    return name;
  }

  function keySelect(id, bindKey, current) {
    const sel = document.createElement('select');
    sel.id = id;
    sel.dataset.kbd = bindKey;
    const known = KBD_KEYS.indexOf(current) !== -1;
    // Um valor que a tela nao conhece (engine mais nova, config editado a mao)
    // precisa continuar selecionavel: senao o salvamento apagaria a tecla.
    if (!known && current) {
      const extra = document.createElement('option');
      extra.textContent = current;
      extra.selected = true;
      sel.appendChild(extra);
    }
    KBD_KEYS.forEach((k) => {
      const opt = document.createElement('option');
      opt.textContent = k;
      if (k === current) opt.selected = true;
      sel.appendChild(opt);
    });
    return sel;
  }

  function buildKbdList(cfg) {
    const list = document.getElementById('kbd-bind-list');
    if (!list) return;
    list.innerHTML = '';
    const kbd = normalizeKbd((cfg.settings || {}).keyboard);
    let group = '';
    KBD_BINDS.forEach((bind) => {
      if (bind.group !== group) {
        group = bind.group;
        const title = document.createElement('div');
        title.className = 'set-block-title set-subblock';
        title.setAttribute('data-i18n', group);
        list.appendChild(title);
      }
      const row = kbdRow(bindName(bind));
      row.appendChild(keySelect('kbd-' + bind.key, bind.key, kbd[bind.key] || bind.dflt));
      list.appendChild(row);
    });
    applyI18n();
  }

  function buildPadList(listId, defs, storeKey, max) {
    const list = document.getElementById(listId);
    if (!list) return;
    list.innerHTML = '';
    const saved = (window._cfg || {}).settings || {};
    const arr = Array.isArray(saved[storeKey]) ? saved[storeKey] : [];
    defs.forEach((def, i) => {
      const cur = arr[i] === undefined || arr[i] === null ? i : arr[i];
      const name = document.createElement('span');
      name.className = 'kbd-name';
      name.textContent = t(def.label) + ' ' + i;
      const row = kbdRow(name);
      const inp = document.createElement('input');
      inp.type = 'number';
      inp.className = 'dir-input';
      inp.id = listId + '-' + i;
      inp.min = 0;
      inp.max = max;
      inp.value = String(cur);
      row.appendChild(inp);
      list.appendChild(row);
    });
  }

  function collectPad(listId, defs) {
    const out = [];
    defs.forEach((def, i) => {
      const el = document.getElementById(listId + '-' + i);
      const v = el ? parseInt(el.value, 10) : NaN;
      out.push(isNaN(v) || v < 0 ? 0 : v);
    });
    return out;
  }

  function fillControls(cfg) {
    if (!cfg.lang) cfg.lang = 'pt-BR';
    window._cfg = cfg;
    document.querySelectorAll('#screen-settings select, #screen-settings input[type=checkbox]').forEach((el) => {
      const key = map[el.id];
      if (!key) return;
      const val = (cfg.settings || {})[key];
      if (el.type === 'checkbox') el.checked = !!val;
      else safeSelectValue(el, val);
    });
    const texFmt = document.getElementById('set-tex-format');
    if (texFmt) safeSelectValue(texFmt, (cfg.settings || {}).exportAsPng ? 'png' : 'dds');
    document.getElementById('set-overwrite').checked = !!cfg.overwrite;
    document.getElementById('set-install-dir').textContent = cfg.installDir || '';
    document.getElementById('set-emu-path').textContent = cfg.installDir || '\u2014';
    document.getElementById('set-fw').textContent = cfg.fwInstalled ? (cfg.fwVersion || '3.74') : '—';
    document.getElementById('set-user').textContent = cfg.user || 'RPCSV';
    buildLangSelect(cfg);
    fillRanges(cfg);
    fillTextFields(cfg);
    fillColors(cfg);
    fillCamImages(cfg);
    fillGraphicsControls(cfg);
    buildKbdList(cfg);
    buildPadList('pad-bind-list', PAD_BUTTONS, 'controllerBinds', 127);
    buildPadList('pad-axis-list', PAD_AXES, 'controllerAxisBinds', 15);
    fillCoreControls(cfg);
  }

  function fillCoreControls(cfg) {
    const core = Object.assign(defaultCore(), cfg.core || {});
    core.modules = Object.assign({}, defaultCore().modules, core.modules || {});
    const countEl = document.getElementById('core-count');
    if (countEl) {
      const on = CORE_MODULES.filter((n) => !!core.modules[n]).length;
      countEl.textContent = CORE_MODULES.length + ' · ' + on + (currentLangCode.startsWith('pt') ? ' ativos' : ' active');
    }
    const list = document.getElementById('core-modules');
    list.innerHTML = '';
    CORE_MODULES.forEach((name) => {
      const row = document.createElement('div');
      row.className = 'core-module';
      row.dataset.mod = name;
      const label = document.createElement('span');
      label.className = 'core-mod-name';
      label.textContent = name;
      const sw = document.createElement('label');
      sw.className = 'switch';
      sw.innerHTML = '<input type="checkbox" /><span class="slider"></span>';
      sw.firstElementChild.checked = !!core.modules[name];
      sw.firstElementChild.dataset.mod = name;
      row.appendChild(label);
      row.appendChild(sw);
      list.appendChild(row);
    });
    document.querySelectorAll('.core-mode').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.mode === (core.loadingMode || 'automatic'));
    });
    document.getElementById('cpu-opt-toggle').textContent =
      (cfg.cpu && cfg.cpu.optimizations === false) ? t('cpu_enable') : t('cpu_disable');
    const search = document.getElementById('core-search');
    if (search) search.value = '';
  }

  function collectCore(cfg) {
    const core = Object.assign(defaultCore(), cfg.core || {});
    core.loadingMode = (document.querySelector('.core-mode.active') || {}).dataset
      ? document.querySelector('.core-mode.active').dataset.mode : core.loadingMode;
    CORE_MODULES.forEach((name) => {
      const input = document.querySelector(`#core-modules input[data-mod="${name}"]`);
      if (input) core.modules[name] = input.checked;
    });
    const optimizations = document.getElementById('cpu-opt-toggle');
    return {
      core,
      cpu: { optimizations: optimizations ? optimizations.textContent !== t('cpu_enable') : true },
    };
  }

  function collect() {
    const cfg = window._cfg || {};
    const settings = Object.assign({}, cfg.settings);
    Object.keys(map).forEach((elId) => {
      const el = document.getElementById(elId);
      if (!el) return;
      const key = map[elId];
      if (el.type === 'checkbox') settings[key] = el.checked;
      else if (NUMERIC.has(key)) settings[key] = el.value === '' ? null : Number(el.value);
      else settings[key] = el.value;
    });
    Object.keys(RANGES).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      settings[RANGES[id].key] = rangeValue(RANGES[id], el.value);
    });
    Object.keys(TEXT_FIELDS).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      const key = TEXT_FIELDS[id];
      const v = el.value.trim();
      if (NUMERIC_FIELDS.has(key)) settings[key] = v === '' ? 0 : Number(v);
      else settings[key] = v;
    });
    Object.keys(COLOR_FIELDS).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      settings[COLOR_FIELDS[id]] = el.value;
    });
    const texFmt = document.getElementById('set-tex-format');
    if (texFmt) settings.exportAsPng = texFmt.value === 'png';
    const kbd = {};
    KBD_BINDS.forEach((bind) => {
      const sel = document.getElementById('kbd-' + bind.key);
      if (sel) kbd[bind.key] = sel.value;
    });
    settings.keyboard = kbd;
    settings.controllerBinds = collectPad('pad-bind-list', PAD_BUTTONS);
    settings.controllerAxisBinds = collectPad('pad-axis-list', PAD_AXES);
    // ime-langs e tracyModules sao listas no config.yml; na tela ficam em um
    // campo de texto separado por virgula.
    settings.imeLangs = intList(settings.imeLangs);
    settings.tracyModules = strList(settings.tracyModules);
    const partial = { settings };
    partial.overwrite = document.getElementById('set-overwrite').checked;
    partial.lang = document.getElementById('set-lang').value;
    return partial;
  }

  async function save(quiet) {
    const partial = collect();
    const cfg = await window.rpcsv.setConfig(partial);
    window._cfg = cfg;
    if (partial.lang) { currentLangCode = partial.lang; applyI18n(); }
    if (!quiet) toast(t('saved_ok', ''));
  }

  async function load() {
    const cfg = await window.rpcsv.getConfig();
    window._cfg = cfg;
    currentLangCode = cfg.lang || 'pt-BR';
    applyI18n();
    fillControls(cfg);
  }

  async function saveCore() {
    const cfg = await window.rpcsv.getConfig();
    const next = collectCore(cfg);
    const saved = await window.rpcsv.setConfig(next);
    window._cfg = saved;
    toast(t('saved_ok', ''));
  }

  function filterModules() {
    const q = (document.getElementById('core-search').value || '').trim().toLowerCase();
    document.querySelectorAll('.core-module').forEach((row) => {
      row.classList.toggle('hide', !!q && !(row.dataset.mod || '').toLowerCase().includes(q));
    });
  }

  function bind() {
    document.querySelectorAll('.set-tab').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.set-tab').forEach((b) => b.classList.remove('active'));
        document.querySelectorAll('.set-panel').forEach((p) => p.classList.remove('active'));
        btn.classList.add('active');
        document.querySelector(`.set-panel[data-panel="${btn.dataset.tab}"]`).classList.add('active');
      });
    });
    document.getElementById('settings-back').addEventListener('click', () => showScreen('home'));
    document.getElementById('set-fullscreen').addEventListener('click', async () => {
      const fs = await window.rpcsv.toggleFullscreen();
      toast(fs ? t('set_fullscreen') + ' ✓' : t('set_fullscreen'));
    });

    ['change'].forEach((evt) => {
      const targets = document.querySelectorAll('#screen-settings select, #screen-settings input[type=checkbox]');
      targets.forEach((el) => el.addEventListener(evt, () => save()));
    });

    document.getElementById('set-renderer').addEventListener('change', (event) => {
      updateGraphicsCapabilities(event.target.value);
    });

    document.querySelectorAll('.graphics-tab').forEach((btn) => {
      btn.addEventListener('click', async () => {
        if (btn.disabled) return;
        const key = btn.dataset.setting;
        const value = tabValue(key, btn.dataset.value);
        setGraphicsTabs(key, value);
        const cfg = await window.rpcsv.getConfig();
        const settings = Object.assign({}, cfg.settings, { [key]: value });
        window._cfg = await window.rpcsv.setConfig({ settings });
        toast(t('saved_ok', ''));
      });
    });

    // "Disable Surface Sync" e botao (nao chave): o balao nao tem checkbox,
    // entao o valor vive so no config.json e o rotulo mostra o estado atual.
    const syncBtn = document.getElementById('set-surface-sync-btn');
    if (syncBtn) {
      syncBtn.addEventListener('click', async () => {
        const cfg = await window.rpcsv.getConfig();
        const cur = (cfg.settings || {}).disableSurfaceSync === true
          || (cfg.settings || {}).disableSurfaceSync === 'true';
        const next = !cur;
        setStateButton(syncBtn, next);
        const settings = Object.assign({}, cfg.settings, { disableSurfaceSync: next });
        window._cfg = await window.rpcsv.setConfig({ settings });
        toast(t('saved_ok', ''));
      });
    }

    document.querySelectorAll('#screen-settings input[type=color]').forEach((el) => {
      el.addEventListener('change', () => save(true));
    });

    Object.keys(RANGES).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      const def = RANGES[id];
      el.addEventListener('input', () => {
        const lab = document.getElementById(def.label);
        if (lab) lab.textContent = def.fmt(rangeValue(def, el.value));
      });
      el.addEventListener('change', () => save(true));
    });

    Object.keys(TEXT_FIELDS).forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('change', () => save(true));
    });

    document.getElementById('kbd-bind-list').addEventListener('change', () => save(true));

    // Listas de indice do gamepad: os inputs sao criados em buildPadList(),
    // entao o listener fica no container.
    ['pad-bind-list', 'pad-axis-list'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.addEventListener('change', () => save(true));
    });

    Object.keys(CAM_IMG).forEach((id) => {
      const btnId = id.replace('-path', '-browse');
      const btn = document.getElementById(btnId);
      if (!btn) return;
      btn.addEventListener('click', async () => {
        const p = await window.rpcsv.pickFile({});
        if (p) {
          const cfg = window._cfg || (await window.rpcsv.getConfig());
          const s = Object.assign({}, cfg.settings, { [CAM_IMG[id]]: p });
          const saved = await window.rpcsv.setConfig({ settings: s });
          window._cfg = saved;
          document.getElementById(id).textContent = baseName(p);
          document.getElementById(id).title = p;
        }
      });
    });

    document.getElementById('set-clean-shaders').addEventListener('click', () => {
      toast(t('set_clean_done', ''));
    });

    document.getElementById('set-emu-browse').addEventListener('click', async () => {
      const cfg = await window.rpcsv.getConfig();
      const p = await window.rpcsv.pickDirectory(cfg.installDir);
      if (p) {
        await window.rpcsv.setConfig({ installDir: p });
        window._cfg.installDir = p;
        document.getElementById('set-emu-path').textContent = p;
        document.getElementById('set-install-dir').textContent = p;
      }
    });

    document.getElementById('set-emu-reset').addEventListener('click', async () => {
      let d = '';
      if (window.rpcsv.defaultDir) {
        const r = await window.rpcsv.defaultDir();
        if (r) d = r;
      }
      await window.rpcsv.setConfig({ installDir: d });
      window._cfg.installDir = d;
      document.getElementById('set-emu-path').textContent = d || '\u2014';
      document.getElementById('set-install-dir').textContent = d || '\u2014';
      toast(t('set_emu_reset', ''));
    });

    document.getElementById('set-lang').addEventListener('change', async () => {
      await save(true);
      applyI18n();
      const cfg = await window.rpcsv.getConfig();
      updateGraphicsCapabilities(cfg.settings.renderer);
      setStateButton(document.getElementById('set-surface-sync-btn'),
        cfg.settings.disableSurfaceSync === true || cfg.settings.disableSurfaceSync === 'true');
      document.getElementById('cpu-opt-toggle').textContent =
        (cfg.cpu && cfg.cpu.optimizations === false) ? t('cpu_enable') : t('cpu_disable');
    });

    document.querySelectorAll('.core-mode').forEach((btn) => {
      btn.addEventListener('click', async () => {
        document.querySelectorAll('.core-mode').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        await saveCore();
      });
    });

    document.getElementById('core-search').addEventListener('input', () => filterModules());

    document.getElementById('core-modules').addEventListener('change', async (e) => {
      const input = e.target;
      if (input && input.type === 'checkbox' && input.dataset.mod) await saveCore();
    });

    document.getElementById('cpu-opt-toggle').addEventListener('click', async () => {
      const btn = document.getElementById('cpu-opt-toggle');
      const isOn = btn.textContent !== t('cpu_enable');
      btn.textContent = isOn ? t('cpu_enable') : t('cpu_disable');
      await saveCore();
    });

    document.getElementById('set-install-browse').addEventListener('click', async () => {
      const cfg = await window.rpcsv.getConfig();
      const p = await window.rpcsv.pickDirectory(cfg.installDir);
      if (p) {
        await window.rpcsv.setConfig({ installDir: p });
        window._cfg.installDir = p;
        document.getElementById('set-install-dir').textContent = p;
        document.getElementById('set-emu-path').textContent = p;
      }
    });
  }

  return {
    init: async () => {
      if (!bound) { bind(); bound = true; }
      await load();
    },
  };
})();