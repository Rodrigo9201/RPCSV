'use strict';

const Home = (() => {
  const SYSTEM_APPS = [
    { id: 'settings', key: 'settings', icon: 'settings', action: 'settings' },
    { id: 'content', key: 'content', icon: 'content', action: 'installer' },
    { id: 'browser', key: 'browser', icon: 'browser', action: 'browser' },
    { id: 'music', key: 'music', icon: 'music', action: 'music' },
    { id: 'photos', key: 'photos', icon: 'photos', action: 'photos' },
    { id: 'videos', key: 'videos', icon: 'videos', action: 'videos' },
    { id: 'friends', key: 'friends', icon: 'friends', action: 'friends' },
    { id: 'party', key: 'party', icon: 'party', action: 'party' },
    { id: 'messages', key: 'messages', icon: 'messages', action: 'messages' },
    { id: 'email', key: 'email', icon: 'email', action: 'email' },
    { id: 'trophies', key: 'trophies', icon: 'trophy', action: 'trophies' },
    { id: 'camera', key: 'camera', icon: 'camera', action: 'camera' },
    { id: 'maps', key: 'maps', icon: 'maps', action: 'maps' },
  ];

  let lastOpenFromTap = 0;

  function appLabel(key) {
    const map = {
      content: 'Content Manager', browser: 'Browser', music: 'Music', photos: 'Photos',
      videos: 'Videos', friends: 'Friends', party: 'Party', messages: 'Messages',
      email: 'Mail', camera: 'Camera', maps: 'Maps', calendar: 'Calendar',
      store: 'PlayStation Store', settings: 'Settings', trophies: 'Trophies',
    };
    const mapPt = Object.assign({}, map, { settings: 'Configurações', store: 'PlayStation Store' });
    return currentLangCode.startsWith('pt') ? mapPt[key] : map[key];
  }

  function buildSystemApps() {
    return SYSTEM_APPS.map((app) => ({
      id: app.id,
      label: appLabel(app.id),
      icon: app.icon,
      tint: bubbleGradient(app.icon, false),
      tint2: bubbleGradient(app.icon, true),
      action: app.action,
    }));
  }

  async function buildGameApps(games) {
    const gameList = [];
    for (const g of games) {
      gameList.push({
        id: 'game:' + g.titleId,
        titleId: g.titleId,
        label: g.title || g.titleId,
        icon: 'games',
        tint: BUBBLE_TINTS.game[0],
        tint2: BUBBLE_TINTS.game[1],
        action: 'game',
        eboot: g.eboot || '',
        img: await iconHref(g.icon),
      });
    }
    if (!gameList.length) {
      gameList.push({
        id: 'add',
        label: currentLangCode.startsWith('pt') ? 'Adicionar Jogos' : 'Add Games',
        icon: 'getit',
        tint: bubbleGradient('getit', false),
        tint2: bubbleGradient('getit', true),
        action: 'installer',
      });
      for (const extra of [
        { id: 'store', icon: 'store', label: currentLangCode.startsWith('pt') ? 'Loja' : 'Store', action: 'store' },
        { id: 'calendar', icon: 'calendar', label: currentLangCode.startsWith('pt') ? 'Calendário' : 'Calendar', action: 'calendar' },
      ]) {
        gameList.push({
          ...extra,
          tint: bubbleGradient(extra.icon, false),
          tint2: bubbleGradient(extra.icon, true),
        });
      }
    }
    return gameList;
  }

  function esc(s) {
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  function sectionEl(title) {
    const sec = document.createElement('div');
    sec.className = 'home-section';
    sec.textContent = title;
    return sec;
  }

  function gridEl() {
    const grid = document.createElement('div');
    grid.className = 'home-grid';
    return grid;
  }

  function makeBubble(app) {
    const el = document.createElement('div');
    el.className = 'bubble new-bubble';
    el.style.setProperty('--bub-bg', app.tint);
    el.id = 'bub-' + app.id;
    const glyph = document.createElement('span');
    glyph.className = 'bub-glyph';
    if (app.img) {
      const im = document.createElement('img');
      im.className = 'bub-img';
      im.alt = '';
      // Handler via addEventListener: a CSP (script-src 'self') bloqueia onerror inline.
      im.addEventListener('error', () => {
        im.replaceWith(gameArtNode(app.label || '', app.icon));
      }, { once: true });
      im.src = String(app.img);
      glyph.appendChild(im);
    } else if (app.icon === 'games') {
      // Jogo sem icon0.png utilizavel: arte derivada do titulo em vez do
      // glifo "games", que e o mesmo dos apps do sistema.
      glyph.appendChild(gameArtNode(app.titleId || app.label || '', 'games'));
    } else {
      glyph.innerHTML = iconSvg(app.icon);
    }
    el.appendChild(glyph);
    const label = document.createElement('span');
    label.className = 'bub-label';
    label.textContent = app.label == null ? '' : String(app.label);
    el.appendChild(label);
    bindTap(el, app);
    return el;
  }

  /** Equivalente DOM de gameArtSvg(), sem passar por innerHTML. */
  function gameArtNode(seed, iconName) {
    const span = document.createElement('span');
    span.className = 'bub-art';
    // A arte vem do TITLE_ID, não de um glifo fixo: um jogo cujo icon0.png
    // nao pode ser lido (PKG antigo ainda cifrado, PNG invalido) continua
    // identificavel pelo titulo, em vez de virar o mesmo icone generico de
    // todos os outros. Mesma geracao usada pela biblioteca.
    span.innerHTML = gameArtSvg(seed, iconName);
    return span;
  }

  function escAttr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function bindTap(el, app) {
    let start = null;
    let moved = false;
    const down = (e) => { start = { x: e.clientX, y: e.clientY }; moved = false; };
    const move = (e) => {
      if (start && Math.hypot((e.clientX || 0) - start.x, (e.clientY || 0) - start.y) > 12) moved = true;
    };
    const up = (e) => {
      if (start && !moved) openApp(app);
      start = null;
    };
    el.addEventListener('pointerdown', down);
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', () => { start = null; });
    el.addEventListener('click', (e) => {
      if (start || moved) return;
      if (Date.now() - lastOpenFromTap < 350) return;
      openApp(app);
    });
  }

  function openApp(app) {
    lastOpenFromTap = Date.now();
    if (app.action === 'settings') return showScreen('settings');
    if (app.action === 'installer') {
      document.getElementById('pkg-menu').classList.toggle('open');
      closePsMenu();
      return;
    }
    // Tocar num jogo abre a tela de detalhe (Iniciar / Config / Deletar / Info)
    // em vez de pular direto para o emulador. Iniciar continua a um toque,
    // na aba "Iniciar".
    if (app.action === 'game') return GameDetail.open(app);
    if (['browser', 'music', 'photos', 'videos', 'trophies', 'friends',
      'party', 'messages', 'camera', 'maps', 'store', 'calendar'].includes(app.action)) {
      SystemApps.open(app.action);
      return;
    }
    if (app.action === 'email') { SystemApps.mail(); return; }
    toast(`${app.label} — ${currentLangCode.startsWith('pt') ? 'ícone de demonstração' : 'demo icon'}`);
  }

  /* Balanço dos ícones ao rolar a tela na vertical.
     O scroll só vira impulso: a mola em sway() puxa os balões de volta ao
     lugar, então eles ficam atrás do dedo durante o movimento e assentam
     depois que a tela para — em vez de ficarem colados no conteúdo.
     Cada linha da grade recebe uma profundidade diferente para o movimento
     sair em onda, não em bloco único. */
  let swayScroll = null;
  let swayRaf = 0;
  let swayValue = 0;
  let swayVel = 0;
  let swayLastTop = 0;
  let swayBubbles = [];

  function swayDepth(el, index) {
    const grid = el.parentElement;
    if (!grid) return 1;
    const top = Math.round(el.offsetTop);
    let row = 0;
    for (const sib of grid.children) {
      if (sib === el) break;
      if (Math.round(sib.offsetTop) === top) row++;
    }
    // Linhas alternadas Pesam diferente + um desvio por coluna.
    return 0.7 + (row % 2) * 0.5 + (index % 3) * 0.12;
  }

  function swayApply() {
    for (const b of swayBubbles) {
      const d = b._swayDepth;
      b.style.setProperty('--sway', (swayValue * d).toFixed(2) + 'px');
      b.style.setProperty('--rot', (swayValue * d * 0.28).toFixed(3) + 'deg');
    }
  }

  function swayStep() {
    swayVel += -swayValue * 0.11;
    swayVel *= 0.84;
    swayValue += swayVel;
    swayApply();
    if (Math.abs(swayValue) > 0.05 || Math.abs(swayVel) > 0.05) {
      swayRaf = requestAnimationFrame(swayStep);
    } else {
      swayValue = 0;
      swayVel = 0;
      swayApply();
      swayRaf = 0;
    }
  }

  function swayOnScroll() {
    if (!swayScroll) return;
    const top = swayScroll.scrollTop;
    // Limite evita que um flick longo lance o ícone para fora da tela.
    const delta = Math.max(-90, Math.min(90, top - swayLastTop));
    swayLastTop = top;
    swayVel += delta * 0.55;
    swayVel = Math.max(-26, Math.min(26, swayVel));
    if (!swayRaf) swayRaf = requestAnimationFrame(swayStep);
  }

  function bindSway() {
    const sc = document.getElementById('home-scroll');
    if (swayScroll) swayScroll.removeEventListener('scroll', swayOnScroll);
    if (swayRaf) { cancelAnimationFrame(swayRaf); swayRaf = 0; }
    swayScroll = sc;
    swayValue = 0;
    swayVel = 0;
    if (!sc) return;
    const bubbles = Array.prototype.slice.call(sc.querySelectorAll('.bubble'));
    swayBubbles = bubbles.map((el, i) => {
      el._swayDepth = swayDepth(el, i);
      return el;
    });
    swayApply();
    swayLastTop = sc.scrollTop;
    sc.addEventListener('scroll', swayOnScroll, { passive: true });
  }

  async function render() {
    const cfg = await window.rpcsv.getConfig();
    const games = await window.rpcsv.listApps();
    const sys = buildSystemApps();
    const gameApps = await buildGameApps(games.detail || games);

    const container = document.getElementById('pages');
    container.innerHTML = '';

    container.appendChild(sectionEl(t('home_section_system')));
    const sysGrid = gridEl();
    sys.forEach((app) => sysGrid.appendChild(makeBubble(app)));
    container.appendChild(sysGrid);

    container.appendChild(sectionEl(t('home_section_games')));
    const gameGrid = gridEl();
    gameGrid.classList.add('vita-bottom');
    gameApps.forEach((app) => gameGrid.appendChild(makeBubble(app)));
    container.appendChild(gameGrid);

    const sc = document.getElementById('home-scroll');
    if (sc) sc.scrollTop = 0;
    bindSway();
  }

  function goto(idx) {
    const grids = document.querySelectorAll('#pages .home-grid');
    const targets = [document.querySelector('#pages .home-section')];
    const target = grids[idx];
    if (!target) return;
    const sc = document.getElementById('home-scroll');
    if (sc) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    else target.scrollIntoView(true);
  }

  function entrance() {
    const first = document.querySelector('#pages .home-grid');
    if (first) first.style.animation = 'fadeIn 0.5s var(--ease-out) both';
  }

  function initTime() {
    document.getElementById('top-time').textContent = fmtTime(new Date(), false);
    setInterval(() => {
      document.getElementById('top-time').textContent = fmtTime(new Date(), false);
    }, 1000);
  }

  function closePsMenu() { document.getElementById('ps-menu').classList.remove('open'); }
  function closePkgMenu() { document.getElementById('pkg-menu').classList.remove('open'); }

  function bind() {
    document.body.addEventListener('click', (e) => {
      if (!e.target.closest('.pkg-menu') && !e.target.closest('#act-pkg') &&
          !e.target.closest('#bub-content') &&
          !e.target.closest('#act-zip') && !e.target.closest('#act-vpk') &&
          !e.target.closest('#act-settings')) {
        closePkgMenu();
      }
      if (!e.target.closest('#ps-menu') && !e.target.closest('#ps-menu-btn')) closePsMenu();
    });

    const sc = document.getElementById('home-scroll');

    // vertical navigation: wheel + touch are native; add keyboard
    window.addEventListener('keydown', (e) => {
      const tag = (document.activeElement && document.activeElement.tagName) || '';
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      if (!sc) return;
      const page = document.querySelector('.screen.active');
      if (!page || page.id !== 'screen-home') return;
      if (e.key === 'ArrowDown' || e.key === 'PageDown') { sc.scrollBy({ top: 260, behavior: 'smooth' }); }
      else if (e.key === 'ArrowUp' || e.key === 'PageUp') { sc.scrollBy({ top: -260, behavior: 'smooth' }); }
      else if (e.key === 'Home') sc.scrollTo({ top: 0, behavior: 'smooth' });
    });

    document.getElementById('home-hint').textContent = t('home_hint');
    initTime();

    document.getElementById('ps-menu-btn').addEventListener('click', (e) => {
      e.stopPropagation();
      closePkgMenu();
      document.getElementById('ps-menu').classList.toggle('open');
    });
    document.getElementById('ps-library').addEventListener('click', () => { closePsMenu(); showScreen('library'); });
    document.getElementById('ps-firmware').addEventListener('click', () => {
      closePsMenu();
      window.rpcsv.getConfig().then((c) => toast(t('set_fw') + ': ' + (c.fwInstalled ? (c.fwVersion || '3.74') : '—')));
    });
    document.getElementById('ps-users').addEventListener('click', () => { closePsMenu(); Wizard.renderUserPick(); showScreen('userpick'); });
    document.getElementById('ps-settings').addEventListener('click', () => { closePsMenu(); showScreen('settings'); });
    document.getElementById('ps-shutdown').addEventListener('click', () => window.rpcsv.quit());

    document.getElementById('act-pkg').addEventListener('click', () => Installer.run('pkg'));
    document.getElementById('act-zip').addEventListener('click', () => Installer.run('zip'));
    document.getElementById('act-vpk').addEventListener('click', () => Installer.run('vpk'));
    document.getElementById('act-settings').addEventListener('click', () => showScreen('settings'));
  }

  return {
    init: () => bind(),
    render,
    goto,
    refresh: render,
    entrance,
  };
})();