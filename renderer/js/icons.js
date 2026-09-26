'use strict';

// Inner SVG paths (24x24) for system bubbles and menus.
const ICONS = {
  settings: '<circle cx="12" cy="12" r="3.2" fill="#fff"/><path fill="#fff" d="M19.4 13a7.6 7.6 0 0 0 .06-1 7.6 7.6 0 0 0-.06-1l2.1-1.65a.5.5 0 0 0 .12-.64l-2-3.42a.5.5 0 0 0-.6-.22l-2.5 1a7.6 7.6 0 0 0-1.73-1l-.38-2.65A.5.5 0 0 0 14 2h-4a.5.5 0 0 0-.5.42L9.1 5.07a7.6 7.6 0 0 0-1.73 1l-2.5-1a.5.5 0 0 0-.6.22l-2 3.42a.5.5 0 0 0 .12.64L4.5 11a7.6 7.6 0 0 0 0 2l-2.1 1.65a.5.5 0 0 0-.12.64l2 3.42c.13.22.38.32.6.22l2.5-1a7.6 7.6 0 0 0 1.73 1l.38 2.65c.04.24.25.42.5.42h4a.5.5 0 0 0 .5-.42l.38-2.65a7.6 7.6 0 0 0 1.73-1l2.5 1c.22.1.47 0 .6-.22l2-3.42a.5.5 0 0 0-.12-.64z"/>',
  content: '<path fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" d="M6 7h12M15 4l3 3-3 3M18 17H6M9 14l-3 3 3 3"/>',
  browser: '<path fill="#fff" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.9 7h-3.5A15 15 0 0 0 15 7.7 8.1 8.1 0 0 1 18.9 9zM12 4c1 1.1 2 3 2.4 5H9.6C10 7 11 5.1 12 4zM8.6 7.8A15 15 0 0 0 8 9H5.1A8.1 8.1 0 0 1 8.6 7.8zM4.2 11h3.5a16 16 0 0 0 0 2H4.2a8 8 0 0 1 0-2zM5.1 13h2.9c.1.8.3 1.5.6 2.2a8.1 8.1 0 0 1-3.5-2.2zM8 15h3.4a16 16 0 0 1-1.2 5 8.2 8.2 0 0 1-2.2-5zm3.4 0H12.6a16 16 0 0 1-4-5H12.6c.1.8.1 1.7 0 2.5l-1.2 2.5zM12.6 8H12.6zM12 20c-1-1.1-2-2.9-2.4-5h4.8c-.4 2.1-1.4 3.9-2.4 5zm3.6-2c-.1-.7-.2-1.4-.2-2.2h3.5a8.1 8.1 0 0 1-3.3 2.2zm.9-4h-2.9a16 16 0 0 1 1.2-5 8.2 8.2 0 0 1 1.7 5zm2.3-4h-3.5a15 15 0 0 0-.4-1.3A8.1 8.1 0 0 1 18.9 9z"/>',
  music: '<path fill="#fff" d="M9 18.5V6.7l8-2v11.8a3 3 0 0 1-2.5-.8 3 3 0 0 0-2.5-.7 3 3 0 0 0-2 1.3c-.7.9-1 1.6-1 2.2a2.6 2.6 0 0 0 2.6 2.7c1.5 0 2.6-1 2.9-2.5L15 6.5l-4.2 1.1V17a3 3 0 0 1-1.8 1.5z"/>',
  photos: '<path fill="#fff" d="M9 3l-1.8 2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-3.2L15 3zM12 18a6.5 6.5 0 1 1 0-13 6.5 6.5 0 0 1 0 13zm0-3a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z"/>',
  videos: '<path fill="#fff" d="M2 6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2zm8 3v6l5-3z"/>',
  messages: '<path fill="#fff" d="M20 4H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4l3 3 3-3h4a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-2 5a6 6 0 0 1-6 6 6 6 0 0 1-3-0.8l-3 0.8 0.8-3A6 6 0 1 1 18 9z"/>',
  email: '<path fill="#fff" d="M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm0 3.2V18h16V7.2l-7.4 5.4a1.2 1.2 0 0 1-1.2 0zM5.3 5l6.7 4.9L18.7 5z"/>',
  camera: '<path fill="#fff" d="M12 8.5a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0 2a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM9 3l-1.8 2H3a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-4.2L15 3zM12 6a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15z"/>',
  maps: '<path fill="#fff" d="M12 2a7 7 0 0 0-7 7c0 5.3 7 13 7 13s7-7.7 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/>',
  calendar: '<path fill="#fff" d="M3 5h18a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm3-3v3h2V2zm11 0v3h2V2zM4 10h16v10H4zm2 2v2h2v-2zm4 0v2h2v-2zm4 0v2h2v-2zm-8 4v2h2v-2zm4 0v2h2v-2z"/>',
  friends: '<path fill="#fff" d="M16 11a4 4 0 1 0-4-4 4 4 0 0 0 4 4zm-8 2A3.5 3.5 0 1 0 8 6a3.5 3.5 0 0 0 0 7zm0 1c-2.2 0-7 1.3-7 4v2h14v-2c0-2.7-4.8-4-7-4zm8 0c-1 0-2.2.2-3.2.5A5 5 0 0 1 17 15v4h6v-2c0-2.7-4.2-4-7-4z"/>',
  party: '<path fill="#fff" d="M4 2l3.5 3.1C5.5 6.6 3.9 8.6 3.2 11L2 2zM20 2L22 11C21.3 8.6 19.7 6.7 17.4 5.2L20 2zM12 4a8 8 0 0 0-8 8c0 4.7 3.9 8.5 8 8s8-3.4 8-8a8 8 0 0 0-8-8zm0 3.5A4.5 4.5 0 1 1 7.5 12 4.5 4.5 0 0 1 12 7.5z"/>',
  trophy: '<path fill="#fff" d="M18 2h-6v7.5A3.5 3.5 0 0 0 15.5 13H16c.4-1.9 1.6-3.4 3.8-3.9A1 1 0 0 0 21 8.1V3a1 1 0 0 0-1-1h-2zM6 2H3a1 1 0 0 0-1 1v5.1a1 1 0 0 0 1.2 1C5.4 9.6 6.6 11 7 13h.5A3.5 3.5 0 0 0 12 9.5V2H6zm14.5 11.5V13A10 10 0 0 1 13 20.9V22h5a1 1 0 0 1 0 2H6a1 1 0 0 1 0-2h5v-1.1A10 10 0 0 1 3.5 13v-1H5v1a8 8 0 0 0 4.6 7c.8-1.4.9-2.6.9-4.5h3c0 1.9.1 3.1.9 4.5a8 8 0 0 0 4.6-7v-1z"/>',
  store: '<path fill="#fff" d="M5 8l2-5h10l2 5h1a2 2 0 0 1 0 4h-1v10a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V12H4a2 2 0 0 1 0-4h1zm2 0h10l-1.4-3.5H8.4L7 8zm2 5a1 1 0 0 0 0 2h6a1 1 0 0 0 0-2z"/>',
  remote: '<path fill="#fff" d="M12 4C7 4 3 7 3 11v6a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2v-2h-2v2H5v-4c0-2.8 3-5 7-5s7 2.2 7 5v4h-2v-2h-2v2a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2v-6c0-4-4-7-9-7zM6 14h2v2H6zm10 0h2v2h-2z"/>',
  games: '<path fill="#fff" d="M7 6h10a5 5 0 0 1 5 5c0 .3 0 .5-.1.8l-.5 5a2.5 2.5 0 0 1-4.4 1.1L14.4 15H9.6l-2.6 2.9A2.5 2.5 0 0 1 2.6 16.8l-.5-5A5 5 0 0 1 7 6zm-2.5 5h1.5v1.5H6V11h1.5V9.5H6V8H4.5v1.5H3V11h1.5zM17 10a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z"/>',
  fw: '<path fill="#fff" d="M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm1 2v2h8V4zm4 14a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm-6-6h1.2v1.5H6zm0 3h1.2v1.5H6zM16 12h2v1.5h-2zm0 3h2v1.5h-2z"/>',
  power: '<path fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" d="M12 3v9M6 6a8 8 0 1 0 12 0"/>',
  box: '<path fill="#fff" d="M12 2l9 4.5v11L12 22l-9-4.5v-11zm0 2.2L5.5 7.5l6.5 3.2 6.5-3.2zM11 12.5L5 9.5v6l6 3zm2 0v6l6-3v-6z"/>',
  zip: '<path fill="#fff" d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7zm1 1.5L17.5 7H14zM9 20v-6h2v1h1v-1h1v6H9zm5 0v-3h1v-1h-1v-1h1v-1h-2v-1h3v7z"/>',
  vpk: '<path fill="#fff" d="M12 2l8 4v12l-8 4-8-4V6zm-6 6.2L11 11v7.6l-5-2.5zM13 18.6l5-2.5V11l-5 2.8zm-.5-8.2L18.5 7 13 4.4 7.5 7z"/>',
  restart: '<path fill="#fff" d="M12 5a7 7 0 0 0-6.2 10.4L4 17.6l3.6.9-.9-3.6-1.6 1.6A7 7 0 1 1 12 19l-1.5 2A9 9 0 1 0 12 3z"/>',
  home: '<path fill="#fff" d="M12 3l10 9h-3v8h-5v-6h-4v6H5v-8H2z"/>',
};

// Abstract avatar heads (silhouettes) used in the Vita user creation & lock.
const AVATARS = [
  { bg: '#ff7a59', fg: '#7c2d12' },
  { bg: '#5ab8ff', fg: '#123a63' },
  { bg: '#7ce39a', fg: '#14532d' },
  { bg: '#ffd24a', fg: '#713f12' },
  { bg: '#c79bff', fg: '#3b0764' },
  { bg: '#ff9ad5', fg: '#701a4e' },
  { bg: '#6ee7d6', fg: '#134e4a' },
  { bg: '#ffb36e', fg: '#7c2d12' },
  { bg: '#9aa8ff', fg: '#1e1b4b' },
  { bg: '#8be0e8', fg: '#164e63' },
  { bg: '#f6a3b0', fg: '#881337' },
  { bg: '#c3e88d', fg: '#1a2e05' },
];

function avatarSvg(idx, size) {
  // Math.abs(NaN) % n = NaN => AVATARS[NaN] = undefined => TypeError no render.
  // Um config.json editado a mao traz avatar como texto/objeto; normaliza aqui.
  const n = Number(idx);
  const i = Number.isFinite(n) ? Math.abs(Math.trunc(n)) % AVATARS.length : 0;
  const a = AVATARS[i];
  const s = size ? `width="${size}" height="${size}"` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96" ${s}>
    <circle cx="48" cy="48" r="48" fill="${a.bg}"/>
    <path fill="rgba(255,255,255,0.25)" d="M0 78c6-12 18-18 30-18h36c12 0 24 6 30 18v18H0z"/>
    <circle cx="48" cy="40" r="20" fill="${a.fg}"/>
    <path d="M16 50c5-18 60-18 62 4-10-8-52-8-62-4z" fill="rgba(0,0,0,0.15)"/>
  </svg>`;
}

function gameArtSvg(titleId, seed) {
  let n = 0;
  const strT = String(titleId || seed || 'GAME');
  for (let i = 0; i < strT.length; i++) n = (n * 31 + strT.charCodeAt(i)) >>> 0;
  const hue = n % 360;
  const hue2 = (hue + 40) % 360;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">
    <defs><linearGradient id="ga" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="hsl(${hue},80%,62%)"/><stop offset="1" stop-color="hsl(${hue2},80%,45%)"/>
    </linearGradient></defs>
    <circle cx="48" cy="48" r="48" fill="url(#ga)"/>
    <circle cx="48" cy="38" r="20" fill="none" stroke="rgba(255,255,255,0.9)" stroke-width="5"/>
    <rect x="20" y="62" width="56" height="16" rx="8" fill="rgba(255,255,255,0.9)"/>
    <circle cx="36" cy="70" r="3" fill="${'#' + strT.slice(0, 6)}"/>
    <circle cx="60" cy="70" r="3" fill="rgba(0,0,0,0.3)"/>
  </svg>`;
}

function iconSvg(name) {
  const inner = ICONS[name] || ICONS.games;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">${inner}</svg>`;
}

// Vita bubble color tints keyed by app id
const BUBBLE_TINTS = {
  settings: ['radial-gradient(circle at 32% 26%, #e6f2ff, #3a7bd5 55%, #1d3557)', 'radial-gradient(circle at 32% 26%, #e6f2ff, #8fb0e0 55%, #28486e)'],
  content: ['radial-gradient(circle at 32% 26%, #eafff4, #2fb57f 55%, #145238)', 'radial-gradient(circle at 32% 26%, #eafff4, #7fd4b0 55%, #1d6a46)'],
  browser: ['radial-gradient(circle at 32% 26%, #eaf7ff, #3aa0e0 55%, #173d5c)', 'radial-gradient(circle at 32% 26%, #eaf7ff, #7fc4ec 55%, #1e587c)'],
  music: ['radial-gradient(circle at 32% 26%, #fff0fa, #e06ab8 55%, #5c1d46)', 'radial-gradient(circle at 32% 26%, #fff0fa, #f0a8d8 55%, #7a3466)'],
  photos: ['radial-gradient(circle at 32% 26%, #fff3e0, #f0a94a 55%, #6b4413)', 'radial-gradient(circle at 32% 26%, #fff3e0, #f8cf8f 55%, #8a6330)'],
  videos: ['radial-gradient(circle at 32% 26%, #ffeaea, #e05a5a 55%, #5c1d1d)', 'radial-gradient(circle at 32% 26%, #ffeaea, #f09a9a 55%, #7a3434)'],
  messages: ['radial-gradient(circle at 32% 26%, #eaffff, #2fb5c0 55%, #12454a)', 'radial-gradient(circle at 32% 26%, #eaffff, #7fd4da 55%, #1d5b61)'],
  email: ['radial-gradient(circle at 32% 26%, #f0f6ff, #6a9ae0 55%, #223f6b)', 'radial-gradient(circle at 32% 26%, #f0f6ff, #a5c4f0 55%, #33588b)'],
  camera: ['radial-gradient(circle at 32% 26%, #f5f0ff, #a06ae0 55%, #3d1d5c)', 'radial-gradient(circle at 32% 26%, #f5f0ff, #c9a8f0 55%, #57347a)'],
  maps: ['radial-gradient(circle at 32% 26%, #eafff3, #45c47b 55%, #145238)', 'radial-gradient(circle at 32% 26%, #eafff3, #8be0ae 55%, #1d6a46)'],
  calendar: ['radial-gradient(circle at 32% 26%, #fff7ea, #e0a04a 55%, #6b4413)', 'radial-gradient(circle at 32% 26%, #fff7ea, #f0cf9f 55%, #8a6330)'],
  friends: ['radial-gradient(circle at 32% 26%, #ffeef4, #e06a9a 55%, #5c1d3d)', 'radial-gradient(circle at 32% 26%, #ffeef4, #f0accb 55%, #7a3464)'],
  party: ['radial-gradient(circle at 32% 26%, #fff0ff, #d06ae0 55%, #4d1d5c)', 'radial-gradient(circle at 32% 26%, #fff0ff, #e8a9f0 55%, #6a347a)'],
  trophy: ['radial-gradient(circle at 32% 26%, #fffbea, #e6b84a 55%, #5c4a13)', 'radial-gradient(circle at 32% 26%, #fffbea, #f6dd9f 55%, #7a6630)'],
  store: ['radial-gradient(circle at 32% 26%, #eaf0ff, #5a7ae0 55%, #1d2d5c)', 'radial-gradient(circle at 32% 26%, #eaf0ff, #9fb6f0 55%, #33507a)'],
  remote: ['radial-gradient(circle at 32% 26%, #f0fff5, #4ad08a 55%, #145238)', 'radial-gradient(circle at 32% 26%, #f0fff5, #8fe8b5 55%, #1d6a46)'],
  getit: ['radial-gradient(circle at 32% 26%, #e6f4ff, #3a90e0 55%, #1d3d5c)', 'radial-gradient(circle at 32% 26%, #e6f4ff, #8fc2f0 55%, #335d7a)'],
  game: ['radial-gradient(circle at 32% 26%, #f2f2ff, #6a7ad0 55%, #232d5c)', 'radial-gradient(circle at 32% 26%, #f2f2ff, #a5b2ea 55%, #3c4666)'],
};

function bubbleGradient(kind, variant) {
  const pair = BUBBLE_TINTS[kind] || BUBBLE_TINTS.game;
  return variant ? pair[1] : pair[0];
}

function brandMenuSvg(name) {
  return iconSvg(name);
}