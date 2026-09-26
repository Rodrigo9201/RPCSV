'use strict';

/**
 * Tela de detalhe de um app/jogo: Iniciar, Config, Deletar e Info.
 *
 * Ela existe porque o toque na bubble da Home abria o emulador direto. Com
 * isso nao havia lugar nenhum para ver o tamanho do app, ajustar opcoes
 * daquele titulo ou remove-lo, e a unica saida era o gerenciador de arquivos
 * do Android.
 */
const GameDetail = (() => {
  let current = null;      // { titleId, title, icon, dir, kind }
  let info = null;         // appInfo() do nativo
  let busy = false;
  let cfgOverrides = null; // ajustes salvos deste titulo

  const $ = (id) => document.getElementById(id);

  /**
   * Arte de fallback a partir do TITLE_ID.
   *
   * `gameArtNode` só existe dentro dos IIFEs de Home e Installer, então não
   * dá para chamar daqui: a versão local usa o `gameArtSvg()` global de
   * icons.js e é idêntica na saída.
   */
  function artNode(seed, iconName) {
    const span = document.createElement('span');
    span.className = 'bub-art';
    span.innerHTML = gameArtSvg(seed, iconName);
    return span;
  }

  // ------------------------------ helpers ------------------------------

  function fmtBytes(b) {
    const n = Number(b) || 0;
    if (n <= 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
    return (n / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  function fmtDate(ms) {
    const n = Number(ms) || 0;
    if (n <= 0) return '';
    const d = new Date(n);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleDateString();
  }

  // ------------------------------ abas ------------------------------

  function selectTab(name) {
    const tabs = $('gd-tabs');
    if (!tabs) return;
    tabs.querySelectorAll('.gd-tab').forEach((b) => {
      b.classList.toggle('active', b.dataset.tab === name);
    });
    const screen = $('screen-gamedetail');
    if (!screen) return;
    screen.querySelectorAll('.gd-panel').forEach((p) => {
      p.classList.toggle('hidden', p.dataset.panel !== name);
    });
    try { if (window.rpcsv.mark) window.rpcsv.mark('gdtab:' + name); } catch (e) { /* ignore */ }
  }

  // ------------------------------ abrir ------------------------------

  async function open(app) {
    if (!app || !app.titleId) return;
    current = {
      titleId: app.titleId,
      title: app.label || app.title || app.titleId,
      icon: app.icon || '',
      dir: app.dir || '',
      kind: app.kind || 'vita',
    };
    info = null;           // senão a aba Info do app anterior aparece por baixo
    cfgOverrides = null;

    $('gd-title').textContent = current.title;
    $('gd-name').textContent = current.title;
    $('gd-tid').textContent = current.titleId;
    $('gd-launch-name').textContent = current.title;
    selectTab('play');

    // A tela abre antes do ícone: `renderIcon` espera a leitura do PNG e um
    // ícone corrompido não pode deixar o usuário preso na Home sem explicação.
    showScreen('gamedetail');
    renderIcon().catch(() => {});
    loadConfigForTitle();
    loadInfo();
  }

  async function renderIcon() {
    const img = await iconHref(current.icon);
    for (const elId of ['gd-icon', 'gd-launch-art']) {
      const el = $(elId);
      if (!el) continue;
      el.innerHTML = '';
      if (img) {
        const im = document.createElement('img');
        im.className = 'gd-hero-img';
        im.alt = '';
        // CSP (script-src 'self') bloqueia handler onerror inline.
        im.addEventListener('error', () => {
          im.replaceWith(artNode(current.titleId, 'games'));
        }, { once: true });
        im.src = img;
        el.appendChild(im);
      } else {
        el.appendChild(artNode(current.titleId, 'games'));
      }
    }
  }

  // ------------------------------ info ------------------------------

  async function loadInfo() {
    if (!current || !current.dir) return;
    // O tamanho é medido percorrendo a pasta no host: num app de vários GB
    // isso leva segundos. A aba Info por isso é desenhada primeiro com o que
    // já se sabe e só depois completada — senão ela abre vazia e parece quebrada.
    renderInfo();
    renderDeleteWarning();
    const res = await window.rpcsv.appInfo(current.dir);
    if (current && res && res.ok) {
      info = res;
    } else {
      info = null;
    }
    if (current) {
      renderInfo();
      renderDeleteWarning();
    }
  }

  function row(label, value) {
    const tr = document.createElement('div');
    tr.className = 'gd-info-row';
    const k = document.createElement('span');
    k.className = 'gd-info-k';
    k.textContent = label;
    const v = document.createElement('span');
    v.className = 'gd-info-v';
    v.textContent = value || '—';
    tr.appendChild(k);
    tr.appendChild(v);
    return tr;
  }

  function renderInfo() {
    const box = $('gd-info');
    if (!box) return;
    box.innerHTML = '';
    // Antes do appInfo() responder, o título e o TITLE_ID já são conhecidos:
    // vêm do nome da pasta e do param.sfo lido na listagem.
    box.appendChild(row(t('gd_info_title'), (info && info.title) || current.title));
    box.appendChild(row('TITLE_ID', (info && info.titleId) || current.titleId));
    if (info) {
      box.appendChild(row('CONTENT_ID', info.contentId));
      box.appendChild(row(t('gd_info_version'), info.version));
      box.appendChild(row(t('gd_info_publisher'), info.publisher));
      box.appendChild(row(t('gd_info_category'), info.category));
      box.appendChild(row(t('gd_info_size'), fmtBytes(info.size)));
      box.appendChild(row(t('gd_info_installed'), fmtDate(info.installed)));
      box.appendChild(row(t('gd_info_kind'), current.kind === 'psp' ? 'PSP' : 'PS Vita'));
      if (info.description) {
        const d = document.createElement('p');
        d.className = 'gd-info-desc';
        d.textContent = info.description;
        box.appendChild(d);
      }
      return;
    }
    const p = document.createElement('p');
    p.className = 'gd-info-pending';
    p.textContent = t('lib_selecting');
    box.appendChild(p);
  }

  function renderDeleteWarning() {
    const el = $('gd-del-warn');
    if (!el) return;
    // A dimensão só entra depois de medida; sem ela o texto ficaria com um
    // espaço estranho no meio.
    const size = info ? (fmtBytes(info.size) ? ' (' + fmtBytes(info.size) + ')' : '') : '';
    el.textContent = t('gd_delete_warn', current.title, size);
  }

  // ------------------------------ config ------------------------------

  // Ajustes padrão: os mesmos do config.yml embutido da engine. A aba Config
  // mexe só nisto, guardado em config.json -> titleOverrides[titleId]; o
  // global em Configurações não é tocado.
  const DEFAULTS = {
    resolutionMultiplier: 1,
    vSync: true,
    performanceOverlay: false,
    screenFilter: 'Bilinear',
    audioVolume: 100,
  };

  async function loadConfigForTitle() {
    const cfg = await window.rpcsv.getConfig();
    const all = (cfg && cfg.titleOverrides) || {};
    cfgOverrides = Object.assign({}, DEFAULTS, all[current.titleId] || {});
    paintConfig();
  }

  function paintConfig() {
    const o = cfgOverrides || DEFAULTS;
    $('gd-cfg-hd').checked = Number(o.resolutionMultiplier) > 1;
    $('gd-cfg-vsync').checked = !!o.vSync;
    $('gd-cfg-showfps').checked = !!o.performanceOverlay;
    $('gd-cfg-filter').value = o.screenFilter || 'Bilinear';
    $('gd-cfg-audio').value = String(o.audioVolume);
    $('gd-cfg-audio-val').textContent = String(o.audioVolume);
  }

  async function saveConfigForTitle() {
    if (!current) return;
    const cfg = await window.rpcsv.getConfig();
    const all = Object.assign({}, cfg.titleOverrides || {});
    all[current.titleId] = Object.assign({}, cfgOverrides);
    await window.rpcsv.setConfig({ titleOverrides: all });
  }

  async function onConfigChange() {
    if (!cfgOverrides) cfgOverrides = Object.assign({}, DEFAULTS);
    cfgOverrides.resolutionMultiplier = $('gd-cfg-hd').checked ? 2 : 1;
    cfgOverrides.vSync = $('gd-cfg-vsync').checked;
    cfgOverrides.performanceOverlay = $('gd-cfg-showfps').checked;
    cfgOverrides.screenFilter = $('gd-cfg-filter').value;
    cfgOverrides.audioVolume = Number($('gd-cfg-audio').value);
    $('gd-cfg-audio-val').textContent = String(cfgOverrides.audioVolume);
    await saveConfigForTitle();
  }

  // ------------------------------ iniciar ------------------------------

  async function play() {
    if (busy || !current) return;
    busy = true;
    const btn = $('gd-play');
    if (btn) btn.disabled = true;
    try {
      const target = current.titleId;
      const res = await window.rpcsv.launchGame(target);
      if (!res.ok) {
        toast(t('toast_error') + ': ' + (res.error || ''), true);
        return;
      }
      Games.launch(res, { onReady: () => window.rpcsv.bootTitle(res.titleId) });
    } catch (e) {
      toast(t('toast_error') + ': ' + ((e && e.message) || e), true);
    } finally {
      busy = false;
      if (btn) btn.disabled = false;
    }
  }

  // ------------------------------ deletar ------------------------------

  function confirmDelete() {
    if (!current) return;
    $('gd-confirm-title').textContent = t('gd_delete');
    $('gd-confirm-text').textContent = t('gd_delete_warn', current.title, '');
    $('gd-confirm').classList.remove('hidden');
  }

  function closeConfirm() {
    $('gd-confirm').classList.add('hidden');
  }

  async function doDelete() {
    if (busy || !current) return;
    const btn = $('gd-del');
    busy = true;
    if (btn) btn.disabled = true;
    const removed = current.titleId;
    try {
      const res = await window.rpcsv.deleteApp(current.dir);
      if (!res || !res.ok) {
        toast(t('toast_error') + ': ' + ((res && res.error) || ''), true);
        return;
      }
      closeConfirm();
      toast(t('gd_deleted', removed));
      showScreen('home');
      if (Home.refresh) Home.refresh();
    } catch (e) {
      toast(t('toast_error') + ': ' + ((e && e.message) || e), true);
    } finally {
      busy = false;
      if (btn) btn.disabled = false;
    }
  }

  // ------------------------------ wiring ------------------------------

  function bind() {
    $('gd-back').addEventListener('click', () => {
      closeConfirm();
      showScreen('home');
    });
    $('gd-play').addEventListener('click', play);

    const tabs = $('gd-tabs');
    tabs.querySelectorAll('.gd-tab').forEach((b) => {
      b.addEventListener('click', () => selectTab(b.dataset.tab));
    });

    $('gd-cfg-hd').addEventListener('change', onConfigChange);
    $('gd-cfg-vsync').addEventListener('change', onConfigChange);
    $('gd-cfg-showfps').addEventListener('change', onConfigChange);
    $('gd-cfg-filter').addEventListener('change', onConfigChange);
    $('gd-cfg-audio').addEventListener('input', () => {
      $('gd-cfg-audio-val').textContent = $('gd-cfg-audio').value;
    });
    $('gd-cfg-audio').addEventListener('change', onConfigChange);

    $('gd-cfg-reset').addEventListener('click', async () => {
      cfgOverrides = Object.assign({}, DEFAULTS);
      paintConfig();
      await saveConfigForTitle();
      toast(t('gd_cfg_reset_done'));
    });

    $('gd-del').addEventListener('click', confirmDelete);
    $('gd-confirm-cancel').addEventListener('click', closeConfirm);
    $('gd-confirm-ok').addEventListener('click', doDelete);
  }

  function init() { bind(); }

  return { init, open, selectTab };
})();
