'use strict';

window.__errs = [];
window.addEventListener('error', (e) => {
  const msg = `${e.message} @${(e.filename || '').split('/').pop()}:${e.lineno}`;
  window.__errs.push(msg);
  try { if (window.rpcsv && window.rpcsv.mark) window.rpcsv.mark('ERR:' + msg); } catch (ignore) {}
});
window.addEventListener('unhandledrejection', (e) => {
  const msg = 'PROMISE ' + ((e.reason && e.reason.message) || e.reason);
  window.__errs.push(msg);
  try { if (window.rpcsv && window.rpcsv.mark) window.rpcsv.mark('ERR:' + msg); } catch (ignore) {}
});

let showing = null;

/* ------------------------------------------------------------------
   Boot
   "Travou ao iniciar" é o pior sintoma possível, então o boot tem duas
   exigências opostas: ser curto o bastante para não atrasar e nunca poder
   prender a interface. A solução é separar as duas coisas:
     - a tela real aparece por baixo assim que fica pronta (showScreen);
     - a camada de boot é só uma sobreposição, e o que decide quando ela
       some é o relógio, não a prontidão do app.
   Assim o boot tem uma duração mínima previsível (para a marca e o som
   serem vistos/ouvidos) e ainda um watchdog de 6s que derruba tudo se
   algo travar. `withTimeout` cuida dos `await` da ponte que não resolvem.
   ------------------------------------------------------------------ */
const BOOT_WATCHDOG_MS = 6000;
const BOOT_MIN_MS = 2600;   // ~= a duracao de brand/boot.wav (2.45s)
const BOOT_START = Date.now();

function bootAudio() {
  const a = document.getElementById('boot-audio');
  if (!a) return;
  try {
    a.volume = 0.55;
    // a.play() só resolve com gesto do usuario em alguns WebViews; por isso
    // o boot nao depende do audio — falhar aqui é normal e silencioso.
    const p = a.play();
    if (p && p.catch) p.catch(() => {});
  } catch (ignore) {}
}

let bootDismissed = false;
let bootTimer = null;

function hideBootLayer() {
  if (bootDismissed) return;
  bootDismissed = true;
  const el = document.getElementById('screen-boot');
  if (!el) return;
  el.classList.add('boot-out');
  setTimeout(() => { el.classList.remove('active'); el.style.display = 'none'; }, 200);
  const a = document.getElementById('boot-audio');
  if (a) { try { a.pause(); } catch (ignore) {} }
}

/**
 * Pede a saída do boot. Não sai antes de BOOT_MIN_MS (para a marca e o
 * som aparecerem), mas o watchdog sempre vence.
 */
function dismissBoot() {
  const wait = BOOT_MIN_MS - (Date.now() - BOOT_START);
  if (wait <= 0) { hideBootLayer(); return; }
  if (bootTimer) return;
  bootTimer = setTimeout(() => { bootTimer = null; hideBootLayer(); }, wait);
}

/** Promise que nunca pode segurar a interface para sempre. */
function withTimeout(promise, ms, label) {
  return Promise.race([
    Promise.resolve(promise),
    new Promise((_, rej) => setTimeout(() => rej(new Error('timeout: ' + label)), ms)),
  ]);
}

// Duração precisa casar com screenEnter/screenExit em css/vita.css; o
// animationend é o gatilho principal, o timeout é só a rede de segurança
// para quando a animação não dispara (aba oculta, motion reduzido).
const SCREEN_ENTER_MS = 300;
const SCREEN_EXIT_MS = 240;

function clearExit(screen) {
  if (!screen) return;
  if (screen._exitTimer) { clearTimeout(screen._exitTimer); screen._exitTimer = 0; }
  screen.classList.remove('screen-exit');
}

function showScreen(id) {
  if (showing === id) return;
  showing = id;
  if (id !== 'boot') dismissBoot();
  if (id !== 'game' && window.Games) window.Games.hide();

  const target = document.getElementById('screen-' + id);

  // Durante a saída a tela antiga continua com .active, então um
  // querySelector('.screen.active') simples pode escolher justamente a que já
  // está saindo. "Saindo" aqui = .active, diferente do destino e sem
  // .screen-exit — senão um toque rápido deixaria duas telas empilhadas.
  let outgoing = null;
  document.querySelectorAll('.screen.active').forEach((s) => {
    if (s !== target && !s.classList.contains('screen-exit')) outgoing = s;
  });

  if (outgoing && outgoing !== target) {
    clearExit(outgoing);
    // A tela que sai só perde .active no fim da animação. O boot já tem a
    // própria saída (.boot-out) e some na hora para não piscar.
    if (outgoing.classList.contains('boot')) {
      outgoing.classList.remove('active');
      outgoing.classList.add('inactive');
    } else {
      outgoing.classList.remove('screen-enter');
      outgoing.classList.add('screen-exit');
      outgoing._exitTimer = setTimeout(() => {
        outgoing._exitTimer = 0;
        outgoing.classList.remove('screen-exit', 'active');
        outgoing.classList.add('inactive');
      }, SCREEN_EXIT_MS);
    }
  }

  document.querySelectorAll('.screen').forEach((s) => {
    if (s === outgoing || s === target) return;
    clearExit(s);
    s.classList.remove('active', 'screen-enter');
    s.classList.add('inactive');
  });

  if (target) {
    clearExit(target);
    target.classList.add('active');
    target.classList.remove('inactive');
    // A classe é removida no fim para a próxima entrada repetir a animação;
    // deixar ela presa faria a segunda visita entrar sem movimento.
    target.classList.add('screen-enter');
    if (target._enterTimer) clearTimeout(target._enterTimer);
    target._enterTimer = setTimeout(() => {
      target._enterTimer = 0;
      target.classList.remove('screen-enter');
    }, SCREEN_ENTER_MS);
    if (!target._enterBound) {
      target._enterBound = true;
      // Uma única ligação por tela: showScreen() pode ser chamado dezenas de
      // vezes e um listener por chamada vira vazamento.
      target.addEventListener('animationend', (e) => {
        if (e.target === target) {
          target.classList.remove('screen-enter');
          if (target._enterTimer) { clearTimeout(target._enterTimer); target._enterTimer = 0; }
        }
      });
    }
    if (id === 'library') Installer.refreshLibrary();
    if (id === 'home') Home.refresh();
    document.body.classList.remove('cursor-hidden');
    window.rpcsv.setCursor(true);
    try { if (window.rpcsv.mark) window.rpcsv.mark('screen:' + id); } catch (ignore) {}
  }
}

function toast(msg, isErr) {
  const el = document.getElementById('toast');
  el.textContent = msg || '';
  el.classList.toggle('err', !!isErr);
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('show'), 3200);
}

async function iconHref(iconPath) {
  if (!iconPath) return '';
  try {
    if (window.AndroidBridge && window.rpcsv && window.rpcsv.base64File) {
      const b64 = await window.rpcsv.base64File(iconPath);
      return b64 ? 'data:image/png;base64,' + b64 : '';
    }
    return String(iconPath);
  } catch (ignore) {
    return '';
  }
}

const Updater = {
  rel: null,
  busy: false,
  bannerClosed: false,
  modalShown: false,
  downloaded: null,

  $: (id) => document.getElementById(id),

  init() {
    const x = this.$('update-banner-x');
    if (x) x.addEventListener('click', () => { this.bannerClosed = true; this.hideBanner(); });
    const b = this.$('update-banner-btn');
    if (b) b.addEventListener('click', () => this.showModal());
    const now = this.$('update-now');
    if (now) now.addEventListener('click', () => this.start());
    const web = this.$('update-web');
    if (web) web.addEventListener('click', () => this.openWeb());
    const later = this.$('update-later');
    if (later) later.addEventListener('click', () => this.hideModal());
    const chk = this.$('set-about-update-btn');
    if (chk) chk.addEventListener('click', () => this.check(true));
    if (window.rpcsv.update) window.rpcsv.update.onProgress((p) => this.onProgress(p));
  },

  fmtSize(n) {
    const b = Number(n || 0);
    if (!b) return '';
    if (b >= 1024 * 1024) return (b / 1048576).toFixed(1) + ' MB';
    if (b >= 1024) return Math.round(b / 1024) + ' KB';
    return b + ' B';
  },

  async check(manual) {
    if (this.busy) return null;
    this.busy = true;
    const st = this.$('set-about-update');
    if (st && manual) st.textContent = t('upd_checking');
    let r = null;
    try {
      r = await window.rpcsv.update.check();
    } catch (e) {
      r = { ok: false, error: String((e && e.message) || e) };
    }
    this.busy = false;
    if (st) {
      st.textContent = !r || !r.configured
        ? t('upd_not_configured')
        : (r.ok
            ? (r.hasUpdate ? t('upd_available_short') + ' ' + r.latest : t('upd_up_to_date') + ' (' + r.latest + ')')
            : t('upd_check_failed'));
    }
    if (!r || !r.ok || !r.hasUpdate) {
      if (manual) toast(r && r.ok ? t('upd_up_to_date') : (t('upd_check_failed') + (r && r.error ? ': ' + r.error : '')), true);
      if (r && r.ok) this.rel = null;
      return r;
    }
    this.rel = r;
    this.downloaded = null;
    this.showBanner(r);
    if (manual || !this.modalShown) {
      this.modalShown = true;
      this.showModal();
    }
    return r;
  },

  showBanner(r) {
    if (this.bannerClosed) return;
    const b = this.$('update-banner');
    if (!b) return;
    const txt = this.$('update-banner-text');
    if (txt) {
      const size = r.apkSize ? ' · ' + this.fmtSize(r.apkSize) : '';
      txt.textContent = t('upd_banner') + ' ' + r.current + ' → ' + r.latest + size;
    }
    b.classList.remove('hidden');
  },

  hideBanner() {
    const b = this.$('update-banner');
    if (b) b.classList.add('hidden');
  },

  showModal() {
    const m = this.$('update-modal');
    if (!m || !this.rel) return;
    this.$('update-modal-cur').textContent = this.rel.current;
    this.$('update-modal-new').textContent = this.rel.latest;
    const notes = this.$('update-modal-notes');
    if (this.rel.notes) {
      notes.textContent = this.rel.notes.slice(0, 4000);
      notes.classList.remove('hidden');
    } else {
      notes.classList.add('hidden');
    }
    this.$('update-modal-err').classList.add('hidden');
    this.$('update-bar').classList.add('hidden');
    m.classList.remove('hidden');
  },

  hideModal() {
    const m = this.$('update-modal');
    if (m) m.classList.add('hidden');
  },

  openWeb() {
    if (!this.rel) return;
    window.rpcsv.openExternal(this.rel.url);
  },

  setErr(msg) {
    const e = this.$('update-modal-err');
    if (!e) return;
    e.textContent = msg;
    e.classList.remove('hidden');
  },

  onProgress(p) {
    const bar = this.$('update-bar');
    if (!bar) return;
    bar.classList.remove('hidden');
    const pct = Math.max(0, Math.min(100, Number(p && p.pct) || 0));
    this.$('update-bar-fill').style.right = (100 - pct) + '%';
    const got = Number(p && p.got) || 0;
    this.$('update-bar-label').textContent = pct + '% · ' + this.fmtSize(got);
  },

  async start() {
    if (!this.rel || this.busy) return;
    if (!this.rel.apkUrl) {
      this.openWeb();
      return;
    }
    if (this.downloaded) return this.install(this.downloaded);

    this.busy = true;
    this.$('update-now').disabled = true;
    this.setErr('');
    this.$('update-bar').classList.remove('hidden');
    this.$('update-bar-fill').style.right = '100%';
    this.$('update-bar-label').textContent = t('upd_preparing');
    try {
      const can = await window.rpcsv.update.canInstall();
      if (!can) {
        this.busy = false;
        this.$('update-now').disabled = false;
        this.setErr(t('upd_need_perm'));
        await window.rpcsv.update.askInstallPerm();
        return;
      }
      const res = await window.rpcsv.update.download(this.rel.apkUrl, this.rel.apkName || 'RPCSV.apk');
      if (!res || !res.ok) {
        this.busy = false;
        this.$('update-now').disabled = false;
        this.setErr(t('upd_download_failed') + ((res && res.error) ? ': ' + res.error : ''));
        return;
      }
      this.downloaded = res;
      this.busy = false;
      this.$('update-now').disabled = false;
      await this.install(res);
    } catch (e) {
      this.busy = false;
      this.$('update-now').disabled = false;
      this.setErr(String((e && e.message) || e));
    }
  },

  async install(res) {
    this.$('update-bar-label').textContent = t('upd_installing');
    const r = await window.rpcsv.update.install(res.path, res.name);
    if (!r || !r.ok) {
      this.setErr(t('upd_install_failed') + ((r && r.error) ? ': ' + r.error : ''));
    }
  },
};

async function boot() {
  bootAudio();
  // Watchdog: derruba a camada de boot mesmo que tudo abaixo trave, e sem
  // respeitar a duração mínima. É o caminho que garante que a interface
  // sempre aparece.
  setTimeout(hideBootLayer, BOOT_WATCHDOG_MS);
  // Tocar pula o boot: quem já conhece o aparelho não quer esperar.
  const skip = () => { hideBootLayer(); };
  const bootEl = document.getElementById('screen-boot');
  if (bootEl) {
    bootEl.addEventListener('pointerdown', skip, { once: true });
    bootEl.addEventListener('keydown', skip, { once: true });
  }

  try {
    const ver = await withTimeout(window.rpcsv.version(), 4000, 'version');
    const plat = ver.platform === 'android' ? 'Android' : ('Electron ' + (ver.electron || '?'));
    document.getElementById('set-about-ver').textContent =
      `${ver.name} v${ver.version} · codename ${ver.codename} · ${plat}`;
  } catch (e) { console.error('version:', e && e.message); }

  // getConfig sem retorno = sem wizard possível, e o app precisa sair do boot
  // mesmo assim. Sem isso uma falha aqui deixava a tela preta para sempre.
  let cfg = null;
  try {
    cfg = await withTimeout(window.rpcsv.getConfig(), 4000, 'getConfig');
  } catch (e) { console.error('getConfig:', e && e.message); }
  if (!cfg) cfg = { lang: 'pt-BR', users: [] };
  window._cfg = cfg;
  currentLangCode = cfg.lang || 'pt-BR';

  // Uma exceção em qualquer init não pode derrubar o boot inteiro — o
  // usuário ficaria preso na tela de boot sem nenhuma pista de qual
  // módulo falhou.
  const inits = [
    ['Wizard', () => Wizard.init()], ['Installer', () => Installer.init()],
    ['SystemApps', () => SystemApps.init()], ['Games', () => Games.init()],
    ['Home', () => Home.init()], ['Settings', () => Settings.init()],
    ['GameDetail', () => GameDetail.init()],
    ['Updater', () => Updater.init()],
  ];
  inits.forEach(([name, fn]) => {
    try { fn(); } catch (e) { console.error('init ' + name + ':', e && e.message); }
  });
  try {
    await withTimeout(Lock.init(), 4000, 'Lock.init');
  } catch (e) { console.error('Lock.init:', e && e.message); }

  try {
    applyI18n();
  } catch (e) { console.error('applyI18n:', e && e.message); }

  const mig = window.rpcsv.migrate().catch(function (e) {
    console.error('migrate:', (e && e.message) || e);
  });

  if (cfg.wizardDone && (cfg.user || '').length) {
    if (!Array.isArray(cfg.users) || !cfg.users.length) {
      const legacy = cfg.user ? [{ name: cfg.user, avatar: cfg.avatar || 0 }] : [];
      cfg.users = legacy;
      await window.rpcsv.setConfig({ users: legacy });
    }
    try {
      await withTimeout(Home.render(), 5000, 'Home.render');
    } catch (e) { console.error('Home.render:', e && e.message); }
    Wizard.renderUserPick();
    showScreen('userpick');
  } else {
    showScreen('wizard');
    Wizard.go('welcome');
  }

  Promise.resolve(mig).then(async function () {
    try {
      const cfg2 = await window.rpcsv.getConfig();
      window._cfg = cfg2;
      applyI18n();
      if (showing === 'home') await Home.render();
      if (showing === 'library') Installer.refreshLibrary();
    } catch (e) {
      console.error('refresh:', (e && e.message) || e);
    }
  });

  // update checker: avisa a cada boot enquanto existir release nova
  if ((cfg.settings && cfg.settings.checkForUpdatesMode) !== 'off') {
    setTimeout(function () {
      Updater.check(false).catch(function () { /* sem rede: silencioso */ });
    }, 1800);
  }

  // cursor idle-hide + tray events
  let idleTimer = null;  window.addEventListener('mousemove', () => {
    window.rpcsv.setCursor(true);
    document.body.classList.remove('cursor-hidden');
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      document.body.classList.add('cursor-hidden');
      window.rpcsv.setCursor(false);
    }, 3500);
  });
  window.rpcsv.onCursor((show) => {
    document.body.classList.toggle('cursor-hidden', !show);
  });

  window.rpcsv.onFullscreen((fs) => {
    if (!fs) window.rpcsv.toggleFullscreen();
  });

  // ESC key returns to home (like the Vita PS button)
  window.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    // A tela do instalador tem um `await` pendente (escolha de chave/arquivo).
    // Sair dela sem resolver deixava o fluxo travado para sempre.
    if (showing === 'installer') { Installer.cancelFlow(); return; }
    if (['home', 'lock', 'userpick', 'wizard'].includes(showing)) return;
    showScreen('home');
  });
}

window.addEventListener('DOMContentLoaded', boot);

window.mod = { Lock, Home, Wizard, Settings, Installer, SystemApps };

document.addEventListener('contextmenu', (e) => e.preventDefault());

// Vita-style touch behaviour for a mouse-driven console
window.addEventListener('dblclick', async () => {
  try { await window.rpcsv.toggleFullscreen(); } catch { /* noop */ }
});