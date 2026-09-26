(function () {
  'use strict';

  if (window.rpcsv && window.rpcsv.platform !== undefined) return;

  const B = window.AndroidBridge;
  if (!B) {
    console.error('AndroidBridge n\u00e3o dispon\u00edvel');
    return;
  }

  const pending = {};
  const events = {};
  let seq = 0;

  function call(method, args) {
    return new Promise(function (resolve) {
      const id = String(++seq);
      pending[id] = resolve;
      try {
        B.call(method, JSON.stringify(args || {}), id);
      } catch (e) {
        delete pending[id];
        resolve(null);
      }
    });
  }

  function toastMsg(msg) {
    call('toast', { msg: String(msg) });
  }

  let fwChain = Promise.resolve();
  function fwQueue(fn) {
    const run = fwChain.then(fn);
    fwChain = run.then(function () {}, function () {});
    return run;
  }

  function nativeFwInstall(path) {
    return fwQueue(function () {
      return call('fwInstall', { path: path }).then(function (r) {
        if (r && r.ok) return { ok: true, version: String(r.version || '') };
        return { ok: false, error: (r && r.error) || 'Extra\u00e7\u00e3o do firmware falhou' };
      });
    });
  }

  window.__np_poll = function () {
    let items;
    try {
      items = JSON.parse(B.poll());
    } catch (e) {
      return;
    }
    if (!Array.isArray(items)) return;
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (it.k === 'r') {
        const cb = pending[it.id];
        if (cb) {
          delete pending[it.id];
          cb(it.v);
        } else {
          // Resposta orfa: sem este aviso uma divergencia de id vira promessa
          // pendurada para sempre, sem nenhum sintoma visivel.
          console.warn('RPCSV: resposta sem pedido correspondente, id=' + it.id);
        }
      } else if (it.k === 'e') {
        const f = events[it.n];
        if (f) {
          try { f(it.v); } catch (e) { /* ignore */ }
        }
      }
    }
  };
  setInterval(window.__np_poll, 90);

  const SETTINGS = {
    resolution: 'native',
    renderer: 'Vulkan',
    customDriverName: '',
    gpuIdx: 0,
    bufferRendering: true,
    textureFiltering: false,
    vSync: true,
    skipIntro: false,
    forceGLES: false,
    screenScale: 'pcm',
    background: 'default',
    readMemCards: true,
    importMemCards: true,
    delayVideo: false,
    colorspace: 'srgb',
    showGpuStats: false,
    dumpOnCrash: true,
    resolutionMultiplier: 1,
    anisotropicFiltering: 1,
    highAccuracy: false,
    disableSurfaceSync: true,
    screenFilter: 'Nearest',
    memoryMapping: 'Double buffer',
    asyncPipelineCompilation: true,
    textureCache: true,
    hashlessTextureCache: true,
    importTextures: false,
    exportTextures: false,
    exportAsPng: true,
    shaderCache: true,
    spirvShader: false,
    fpsHack: false,
    cpuPoolSize: 10,
    audioBackend: 'SDL',
    audioVolume: 100,
    ngsEnable: true,
    themeMusic: false,
    themeMusicVolume: 50,
    frontCamType: 2,
    frontCamColor: '#000000',
    frontCamImage: '',
    frontCamId: '',
    backCamType: 2,
    backCamColor: '#000000',
    backCamImage: '',
    backCamId: '',
    sysButton: 1,
    pstvMode: false,
    showMode: false,
    demoMode: false,
    sysLang: 1,
    userLang: '',
    currentImeLang: 4,
    imeLangs: [4],
    sysDateFormat: 0,
    sysTimeFormat: 0,
    analogMultiplier: 100,
    disableMotion: false,
    ledColor: '',
    keyboard: {},
    controllerBinds: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14],
    controllerAxisBinds: [0, 1, 2, 3, 4, 5, 6],
    stylesheet: 'classic',
    backgroundAlpha: 30,
    appsListGrid: false,
    logBufferSize: 0,
    logFontFamily: '',
    showWelcome: true,
    warnMissingFirmware: true,
    confirmExit: false,
    bootAppsFullScreen: false,
    showLiveAreaScreen: true,
    showCompileShaders: true,
    turboMode: false,
    checkForUpdatesMode: 'prompt',
    discordRichPresence: false,
    performanceOverlay: false,
    performanceOverlayDetail: 0,
    performanceOverlayPosition: 0,
    fullscreenHdResPixelPerfect: true,
    stretchDisplayArea: true,
    screenshotFormat: 0,
    fileLoadingDelay: 0,
    delayStart: 0,
    delayBackground: 0,
    logLevel: 2,
    logExports: false,
    logImports: false,
    logActiveShaders: false,
    logUniforms: false,
    logCompatWarn: false,
    archiveLog: false,
    gdbstub: false,
    waitForDebugger: false,
    tracyPrimitiveImpl: false,
    tracyModules: [],
    httpEnable: true,
    psnSignedIn: false,
    userAutoConnect: true,
    httpTimeoutAttempts: 3,
    httpTimeoutSleepMs: 500,
    httpReadEndAttempts: 5,
    httpReadEndSleepMs: 500,
    adhocAddr: '',
    validationLayer: false,
    logColorSurface: false,
    dumpElfs: false,
    watchMemory: false,
    watchImportCalls: false,
  };


  let cfg = null;

  // ---------------------------------------------------------------
  // Update checker (GitHub Releases)
  // ---------------------------------------------------------------
  // O owner entra no build via sed (build.sh substitui o placeholder); sem
  // isso o token continua no fonte e nao da para trocar de conta sem rebuild.
  const UPDATE_OWNER = '__RPCSV_UPDATE_OWNER__';
  const UPDATE_REPO = 'RPCSV';
  const RELEASE_API = 'https://api.github.com/repos/' + UPDATE_OWNER + '/' + UPDATE_REPO + '/releases/latest';
  const RELEASE_PAGE = 'https://github.com/' + UPDATE_OWNER + '/' + UPDATE_REPO + '/releases/latest';

  function updateConfigured() {
    return UPDATE_OWNER.charAt(0) !== '_' && UPDATE_OWNER.indexOf('__') !== 0;
  }

  function parseVersion(v) {
    const s = String(v == null ? '' : v).trim().replace(/^v/i, '');
    const m = s.match(/^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+.](.*))?$/);
    if (!m) return null;
    return {
      major: parseInt(m[1], 10) || 0,
      minor: parseInt(m[2] || '0', 10),
      patch: parseInt(m[3] || '0', 10),
      pre: m[4] ? String(m[4]) : '',
    };
  }

  function isNewer(latest, current) {
    const a = parseVersion(latest), b = parseVersion(current);
    if (!a) return false;
    if (!b) return true;
    for (const k of ['major', 'minor', 'patch']) {
      if (a[k] > b[k]) return true;
      if (a[k] < b[k]) return false;
    }
    // 1.2.0-beta1 < 1.2.0: a release estavel sempre ganha da pre-release.
    if (a.pre && !b.pre) return false;
    if (!a.pre && b.pre) return true;
    return false;
  }

  const Update = {
    repoPage: function () {
      return updateConfigured() ? RELEASE_PAGE : 'https://github.com/';
    },

    check: function () {
      if (!updateConfigured()) {
        return Promise.resolve({ ok: false, configured: false, error: 'repositorio de updates nao configurado neste build' });
      }
      return Promise.all([call('version'), call('updateFetch', { url: RELEASE_API, maxBytes: 512 * 1024 })])
        .then(function (r) {
          const ver = r[0] || {};
          const http = r[1] || {};
          const current = String(ver.version || '0');
          if (!http.ok) {
            return { ok: false, configured: true, current: current, error: http.error || ('HTTP ' + (http.status || '?')) };
          }
          let rel;
          try {
            rel = JSON.parse(String(http.body || ''));
          } catch (e) {
            return { ok: false, configured: true, current: current, error: 'resposta da API invalida' };
          }
          if (!rel || rel.draft || !rel.tag_name) {
            return { ok: false, configured: true, current: current, error: 'nenhuma release publicada' };
          }
          const assets = Array.isArray(rel.assets) ? rel.assets : [];
          let apk = null;
          for (const a of assets) {
            if (/\.apk$/i.test(String(a.name || ''))) { apk = a; break; }
          }
          const latest = String(rel.tag_name).replace(/^v/i, '');
          return {
            ok: true,
            configured: true,
            current: current,
            currentCode: Number(ver.versionCode || 0),
            latest: latest,
            tag: String(rel.tag_name),
            hasUpdate: isNewer(latest, current),
            name: String(rel.name || rel.tag_name),
            notes: String(rel.body || ''),
            url: String(rel.html_url || RELEASE_PAGE),
            publishedAt: String(rel.published_at || ''),
            apkUrl: apk ? String(apk.browser_download_url || '') : '',
            apkName: apk ? String(apk.name || 'RPCSV.apk') : '',
            apkSize: apk ? Number(apk.size || 0) : 0,
          };
        })
        .catch(function (e) {
          return { ok: false, configured: true, error: String((e && e.message) || e) };
        });
    },

    download: function (url, name) {
      return call('updateDownload', { url: String(url || ''), name: String(name || 'RPCSV.apk') });
    },

    install: function (path, name) {
      return call('updateInstall', { path: String(path || ''), name: String(name || '') });
    },

    canInstall: function () {
      return call('updateInstallPerm', {}).then(function (r) {
        return !!(r && r.allowed);
      });
    },

    askInstallPerm: function () {
      return call('updateInstallPermAsk', {});
    },

    onProgress: function (cb) { if (cb) events['update:progress'] = cb; },
  };

  async function defaultConfig() {
    const home = (await call('homeDir')) || '';
    const dfltDir = await call('defaultDir');
    return {
      user: 'RPCSV',
      codename: 'com.rpcsv.app',
      lang: 'pt-BR',
      fwInstalled: false,
      fwVersion: null,
      installDir: dfltDir || home + '/RPCSV',
      overwrite: false,
      wizardDone: false,
      avatar: 0,
      settings: Object.assign({}, SETTINGS),
    };
  }

  async function loadConfig() {
    if (cfg) return cfg;
    const home = (await call('homeDir')) || '';
    const raw = await call('readFile', { path: home + '/config.json' });
    const d = await defaultConfig();
    try {
      cfg = raw ? Object.assign({}, d, JSON.parse(raw)) : d;
    } catch (e) {
      cfg = d;
    }
    cfg.settings = Object.assign({}, SETTINGS, cfg.settings && typeof cfg.settings === 'object' ? cfg.settings : {});
    return cfg;
  }

  async function saveConfig() {
    const home = (await call('homeDir')) || '';
    return call('writeFile', { path: home + '/config.json', content: JSON.stringify(cfg, null, 2) });
  }

  function detectTitleId(entries) {
    if (!entries) return null;
    for (const e of entries) {
      const n = String(e).replace(/\\/g, '/');
      const m1 = n.split('ux0:/app/')[1];
      if (m1) {
        const t = m1.split('/')[0];
        if (/^[A-Z0-9]{9}$/.test(t)) return t;
      }
      if (n.indexOf('app/') === 0) {
        const t = n.slice(4, 13);
        if (/^[A-Z0-9]{9}$/.test(t)) return t;
      }
      if (/^[A-Z]{4}[0-9]{5}/.test(n)) return n.slice(0, 9);
    }
    return null;
  }

  window.rpcsv = {
    platform: 'android',

    getConfig: function () { return loadConfig(); },

    setConfig: function (partial) {
      return loadConfig().then(function () {
        if (partial && typeof partial === 'object') {
          Object.assign(cfg, partial);
          if (partial.settings) cfg.settings = Object.assign({}, SETTINGS, cfg.settings, partial.settings);
        }
        return saveConfig().then(function () { return loadConfig(); });
      });
    },

    resetConfig: function () {
      cfg = null;
      return call('homeDir').then(function (home) {
        return call('deleteFile', { path: home + '/config.json' });
      });
    },

    pickDirectory: function () {
      // Normaliza aqui: um erro do host chega como {ok:false,error} e os
      // chamadores so testam truthiness, o que salvava o objeto como caminho.
      return call('pickDir').then(function (r) {
        return typeof r === 'string' && r ? r : null;
      });
    },

    pickFile: function () {
      return call('pickFile', {}).then(function (r) {
        return typeof r === 'string' && r ? r : null;
      });
    },

    version: function () { return call('version'); },
    update: Update,

    launchGame: function (target) {
      const self = this;
      return self.listApps().then(function (apps) {
        const t = String(target || '');
        const g = apps.find(function (a) { return a.titleId === t; })
          || (t ? apps.find(function (a) { return t.indexOf(a.titleId) === 0; }) : null)
          || null;
if (!g) return { ok: false, error: t ? 'Jogo não encontrado: ' + t : 'Sem jogo instalado' };
        return self.installDir().then(async function (base) {
          const dir = g.dir || (base + '/ux0/app/' + g.titleId);
          const boot = g.kind === 'psp' ? 'EBOOT.PBP' : 'eboot.bin';
          const ex = await call('exists', { path: dir + '/' + boot });
          if (!ex) return { ok: false, error: boot + ' n\u00e3o encontrado' };
          if (g.kind !== 'psp') {
            const prob = await call('probeEboot', { path: dir });
            if (prob && prob.kind === 'enc') {
              let hasPfs = false, kt = null, it = null, pf = null, pfErr = '';
              try {
                const sf = await call('listDir', { path: dir + '/sce_pfs' });
                hasPfs = Array.isArray(sf) && sf.length > 0;
              } catch (e) {}
              try {
                const db64 = await call('base64File', { path: dir + '/sce_sys/package/_install.json' });
                if (db64) {
                  const o = JSON.parse(atob(String(db64)));
                  kt = o.keyType; it = o.itemCount; pf = o.pfsFiles; pfErr = o.pfsError || '';
                }
              } catch (e) {}
              let diag = ' (' + (prob.size || 0) + 'B' +
                (kt != null ? ', keyType ' + kt + ', ' + it + ' item(s)' : '') +
                (hasPfs ? ', PFS presente no disco' : ', sem PFS') +
                (pf != null ? ', PFS decriptou ' + pf + ' arquivo(s)' : '') +
                (pfErr ? ', PFS: ' + pfErr : '') + ')';
              const hint = hasPfs
                ? 'aplica\u00e7\u00e3o Sony selada (PFS): reinstale o mesmo PKG com a licen\u00e7a certa (work.bin/zRIF da sua conta PSN) para o eboot virar um SELF execut\u00e1vel.'
                : 'o eboot est\u00e1 selado/cifrado nesse direto\u00f3rio: instale o jogo pelo instalador de PKG com sua licen\u00e7a (gera o eboot SELF, que a engine descodifica em runtime) ou use um VPK/dump do seu pr\u00f3prio jogo.';
              return { ok: false, error: 'eboot.bin ainda criptografado' + diag + '. ' + hint };
            }
            if (prob && prob.kind === 'missing') return { ok: false, error: 'eboot.bin ausente' };
          }
          return {
            ok: true,
            mode: 'embedded',
            titleId: g.titleId,
            title: g.title || '',
            icon: g.icon || '',
            kind: g.kind || 'vita',
          };
        });
      });
    },

    bootTitle: function (titleId) {
      return call('launchTitle', { titleId: String(titleId || '') }).then(function (r) {
        if (r && r.ok) return { ok: true };
        return { ok: false, error: (r && r.error) || 'Vita3K não instalado' };
      });
    },

checkFirmware: function (region) {
      return call('fwCheck', { region: region || 'us' }).then(function (res) {
        if (res && res.ok && res.info && res.info.url) return res;
        return { ok: false, error: 'Servidores da Sony indisponíveis' };
      });
    },

    downloadFirmware: function (payload) {
      return call('homeDir').then(function (home) {
        const dest = home + '/fw/' + ((payload && payload.name) || 'PSP2UPDAT.PUP');
        return call('download', { url: payload && payload.url, dest: dest }).then(function (r) {
          if (r && typeof r === 'string') return { ok: true, dest: r };
          return { ok: false, error: (r && r.error) || 'Download falhou' };
        });
      });
    },

    installFirmware: function (pup) {
      return loadConfig().then(async function (c) {
        if (!pup) return { ok: false, error: 'arquivo n\u00e3o encontrado' };
        const storage = (await call('storageDir')) || '';
        const dest = storage + '/fw/PSP2UPDAT.PUP';
        const okC = await call('copy', { src: pup, dst: dest });
        if (!okC) return { ok: false, error: 'n\u00e3o foi poss\u00edvel salvar o firmware' };
        const r = await nativeFwInstall(dest);
        if (!(r && r.ok)) return { ok: false, error: (r && r.error) || 'extra\u00e7\u00e3o do firmware falhou' };
        c.fwInstalled = true;
        c.fwVersion = String(r.version || '');
        await saveConfig();
        toastMsg('Firmware ' + c.fwVersion + ' instalado com sucesso');
        return { ok: true, version: c.fwVersion };
      });
    },

    saveOptionalPup: function (payload) {
      return loadConfig().then(async function (c) {
        const kind = payload && (payload.kind === 'font' ? 'font' : payload.kind === 'pre' ? 'pre' : null);
        if (!kind) return { ok: false, error: 'pacote opcional desconhecido' };
        const pup = payload && payload.path;
        if (!pup) return { ok: false, error: 'arquivo não encontrado' };
        const storage = (await call('storageDir')) || '';
        const fname = kind === 'pre' ? 'PSP2UPDPRE.PUP' : 'PSP2UPDATFont.PUP';
        const dest = storage + '/fw/' + fname;
        const okC = await call('copy', { src: pup, dst: dest });
        if (!okC) return { ok: false, error: 'não foi possível salvar o pacote' };
        const r = await call('pupVersion', { path: dest });
        const version = r && r.ok ? String(r.version || '') : '';
        const engineRoot = storage + '/vita';
        const pub = engineRoot + '/ux0/app/PCSF00001/sys/RELEASE/PUB';
        await call('mkdirs', { path: pub });
        const staged = pub + '/' + fname;
        const okS = await call('copy', { src: pup, dst: staged });
        if (kind === 'pre') { c.fwPrePath = dest; c.fwPreVersion = version; }
        else { c.fwFontPath = dest; c.fwFontVersion = version; }
        await saveConfig();
        toastMsg((kind === 'pre' ? 'Pré-instalação' : 'Fontes') + ' instalado' + (version ? ' (' + version + ')' : '') + ' com sucesso');
        return { ok: true, kind: kind, path: dest, version: version, staged: okS ? staged : null };
      });
    },

    setFirmwareManual: function (pup) {
      return this.installFirmware(pup);
    },

    installApp: function (payload) {
      const self = this;
      return call('storageDir').then(async function (storage) {
        try {
          const kind = payload.kind;
          const file = payload.file;
          if (!file) return { ok: false, error: 'sem arquivo' };
          if (kind === 'pkg') {
              let zrifText = payload.zRif || '';
              let workbinPath = '';
              if (!zrifText && payload.key) {
                if (payload.keyKind === 'workbin') {
                  workbinPath = payload.key;
                } else {
                  const keyText = await call('readFile', { path: payload.key });
                  // Em caso de falha o host devolve {ok:false,error}; sem este
                  // typeof o "[object Object]" ia como zRIf e a instalacao
                  // falhava com um erro opaco em vez de "nao consegui ler".
                  if (typeof keyText === 'string' && keyText.trim()) {
                    zrifText = keyText.trim();
                  } else if (keyText && typeof keyText === 'object') {
                    throw new Error(String(keyText.error || 'nao foi possivel ler o arquivo de chave'));
                  }
                }
              }
              const installBase = await self.installDir();
              const r = await call('installPkg', { path: file, zrif: zrifText, workbin: workbinPath, base: installBase });
              if (r && r.ok) {
                const vitaBoot = installBase + '/ux0/app/' + r.titleId + '/eboot.bin';
                const pspBoot = installBase + '/pspemu/PSP/GAME/' + r.titleId + '/EBOOT.PBP';
                const hasVita = await call('exists', { path: vitaBoot });
                const hasPsp = await call('exists', { path: pspBoot });
                if (!hasVita && !hasPsp) {
                  const why = r.pfsError ? ('PFS: ' + r.pfsError) : 'nenhum arquivo foi extraído';
                  return { ok: false, error: 'Instalação vazia — ' + why + ' (verifique o zRIF/work.bin, ' + r.titleId + ')' };
                }
                try {
                  await call('writeFile', {
                    path: r.appDir + '/sce_sys/package/_install.json',
                    content: JSON.stringify({ titleId: r.titleId, kind: r.kind || kind, keyType: r.keyType || 0, itemCount: r.itemCount || 0, pfsFiles: r.pfsFiles != null ? r.pfsFiles : 0, pfsError: r.pfsError || '' }),
                  });
                } catch (e) {}
                toastMsg('Jogo instalado: ' + r.titleId);
                return { ok: true, mode: 'standalone', titleId: r.titleId, title: r.title, kind: hasPsp ? 'psp' : 'vita' };
              }
              if (r && r.error) {
                console.error('installPkg falhou: ' + r.error);
                return { ok: false, error: String(r.error) };
              }
              return { ok: false, error: 'Falha ao instalar o PKG' };
            }
          const base = (await self.installDir()) || storage + '/RPCSV';
          if (kind === 'vpk') {
            const r = await call('installVpk', { path: file, base: base });
            if (r && r.ok) {
              toastMsg('Jogo instalado: ' + ((r.title || r.titleId) || ''));
              return { ok: true, mode: 'standalone', titleId: r.titleId || '', title: r.title || '', kind: r.kind || 'vita' };
            }
            if (r && r.error) {
              console.error('installVpk falhou: ' + r.error);
              return { ok: false, error: String(r.error) };
            }
            return { ok: false, error: 'Falha ao instalar o VPK' };
          }
          const entries = await call('zipList', { path: file });
          const titleId = detectTitleId(entries);
          const dest = base + '/ux0/app/' + (titleId || 'UNKNOWN');
          const n = await call('extractZip', { zip: file, dest: dest });
          if (n === -1 || n === null) {
            return { ok: false, error: 'Extra\u00e7\u00e3o falhou \u2014 use a pasta padr\u00e3o do app' };
          }
          return { ok: true, mode: 'standalone', titleId: titleId || '' };
        } catch (e) {
          console.error('installApp exceção: ' + ((e && e.message) || e));
          return { ok: false, error: String((e && e.message) || e || 'erro de instala\u00e7\u00e3o') };
        }
      });
    },

    listApps: function () {
      const self = this;
      return self.installDir().then(async function (base) {
        const out = [];
        const scan = async function (rel, kind, boot) {
          const apps = await call('listDir', { path: base + '/' + rel });
          if (!Array.isArray(apps)) return;
          for (const entry of apps) {
            if (!entry.d) continue;
            const dir = base + '/' + rel + '/' + entry.n;
            if (!(await call('exists', { path: dir + '/' + boot }))) continue;
            let title = '';
            if (kind === 'vita') {
              title = String((await call('sfoTitle', { path: dir + '/sce_sys/param.sfo' })) || '');
            }
            if (title) {
              out.push({ titleId: entry.n, icon: dir + '/sce_sys/icon0.png', title: title, kind: kind, dir: dir });
            } else {
              out.push({ titleId: entry.n, icon: '', title: '', kind: kind, dir: dir });
            }
          }
        };
        await scan('ux0/app', 'vita', 'eboot.bin');
        await scan('pspemu/PSP/GAME', 'psp', 'EBOOT.PBP');
        return out;
      });
    },

    installDir: function () {
      return loadConfig().then(async function (c) {
        // O host responde com {ok:false,error} quando o seletor de pasta falha;
        // se esse objeto for salvo como installDir, todo lancamento de jogo passa
        // a montar "[object Object]/ux0/app/..." e nenhum jogo e achado.
        if (typeof c.installDir === 'string' && c.installDir) return c.installDir;
        const d = await call('defaultDir');
        return d || (await call('storageDir')) + '/RPCSV';
      });
    },

    defaultDir: function () { return call('defaultDir'); },

    listDir: function (dirPath) { return call('listDir', { path: dirPath || '' }); },
    copyFile: function (src, dst) { return call('copy', { src: src || '', dst: dst || '' }); },
    move: function (src, dst) { return call('move', { src: src || '', dst: dst || '' }); },
    engineRoot: function () {
      return call('storageDir').then(function (storage) { return (storage || '') + '/vita'; });
    },
    fwInstall: function (path) {
      return nativeFwInstall(path || '');
    },
    migrate: function () {
      const self = this;
      return loadConfig().then(async function (c) {
        const storage = (await call('storageDir')) || '';
        const engineRoot = storage + '/vita';
        const dest = engineRoot + '/ux0/app';
        const out = { moved: 0, skipped: 0, fw: null, engineRoot: engineRoot };
        const legacyRoots = [];
        if (c.installDir && c.installDir !== engineRoot) legacyRoots.push(c.installDir);
        const sdcardLegacy = storage + '/RPCSV';
        if (legacyRoots.indexOf(sdcardLegacy) === -1) legacyRoots.push(sdcardLegacy);
        if (legacyRoots.length) {
          await call('mkdirs', { path: dest });
          for (const root of legacyRoots) {
            const apps = await call('listDir', { path: root + '/ux0/app' });
            if (!Array.isArray(apps)) continue;
            for (const a of apps) {
              if (!a.d) continue;
              const from = root + '/ux0/app/' + a.n;
              const to = dest + '/' + a.n;
              // Destino ja ocupado:_nao_ apaga a origem. A versao anterior
              // resolvia o conflito com deleteFile(from), destruindo a copia do
              // usuario no diretorio escolhido sem nenhum aviso.
              if (await call('exists', { path: to })) {
                out.skipped++;
                continue;
              }
              if (await call('move', { src: from, dst: to })) out.moved++;
            }
          }
          if (out.moved > 0 || (c.installDir && c.installDir !== engineRoot)) {
            c.installDir = engineRoot;
            await saveConfig();
          }
        }
        const pup = storage + '/fw/PSP2UPDAT.PUP';
        const fwReal = await call('exists', { path: engineRoot + '/vs0/sys' });
        if (!fwReal && (c.fwInstalled || (await call('exists', { path: pup })))) {
          out.fw = await self.fwInstall(pup);
          if (out.fw && out.fw.ok) {
            c.fwInstalled = true;
            c.fwVersion = String(out.fw.version || '');
            await saveConfig();
          }
        }
        return out;
      });
    },
    base64File: function (p) { return call('base64File', { path: p || '' }); },

    appInfo: function (dir) { return call('appInfo', { path: dir || '' }); },

    deleteApp: function (dir) { return call('deleteApp', { path: dir || '' }); },

    /**
     * Progresso da instalacao de PKG. O host emite o evento durante a
     * extracao; aqui ele vira um objeto com fracao e porcentagem ja
     * arredondada, para a UI nao ter que repetir essa conta.
     * `onProgress(null)` desliga o ouvinte.
     */
    onInstallProgress: function (cb) { events['install:progress'] = cb; },
    openExternal: function (url) { return call('openExternal', { url: url || '' }); },
    openWith: function (uri) { return call('openWith', { uri: uri || '' }); },
    openPkg: function (path) { return call('openWith', { path: path || '' }); },
    mark: function (tag) { return call('mark', { tag: String(tag || '') }); },
    cameraTake: function () { return call('cameraTake', {}); },

    toggleFullscreen: function () { return call('toggleUI'); },
    setCursor: function () { return Promise.resolve(true); },
    quit: function () { return call('quit'); },

    onFwProgress: function (cb) { if (cb) events['fw:progress'] = cb; },
    onCursor: function () {},
    onFullscreen: function () {},
  };
})();