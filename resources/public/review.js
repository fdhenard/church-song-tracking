// Resolve titles from the app's cached catalog, including on an already-running server.
const importVideoTitles = new Map();
let libraryDuplicateCache = null;
let libraryDuplicateRequest = null;
let importTitleScan = null;
let importTitlesScanned = false;
function collectImportTitles(root) {
  root.querySelectorAll('a[href^="https://vimeo.com/"]').forEach(link => {
    const text = link.textContent.trim();
    if (text && text !== link.href && /^https:\/\/vimeo\.com\/\d+$/.test(link.href)) {
      importVideoTitles.set(link.href, text);
    }
  });
}
function sortImportVideoTables() {
  if (!document.getElementById('import-queue')) return;
  document.querySelectorAll('main .table-wrap table tbody').forEach(body => {
    const rows = Array.from(body.children);
    const key = row => {
      const link = row.querySelector('a[href^="https://vimeo.com/"]');
      const title = link ? (importVideoTitles.get(link.href) || link.textContent.trim()) : '';
      const id = link && link.href.match(/\/(\d+)$/);
      return {date: reviewServiceDate(title), id: id ? BigInt(id[1]) : 0n};
    };
    rows.sort((a, b) => {
      const left = key(a), right = key(b);
      return right.date.localeCompare(left.date) || (right.id > left.id ? 1 : right.id < left.id ? -1 : 0);
    });
    rows.forEach(row => body.append(row));
  });
}

function applyImportTitles() {
  document.querySelectorAll('#import-queue td:first-child a[href^="https://vimeo.com/"]').forEach(link => {
    const title = importVideoTitles.get(link.href);
    if (title) { link.textContent = title; link.title = link.href; }
  });
  markQueuedTitleAlternatives();
  promoteMergeActions();
  flagLibraryDuplicateServices();
  sortImportVideoTables();
}
// Compare service dates with the H2-backed library, including spreadsheet imports without Vimeo links.
async function flagLibraryDuplicateServices() {
  if (!document.getElementById('import-queue')) return;
  try {
    if (!libraryDuplicateCache || Date.now() - libraryDuplicateCache.loadedAt > 30000) {
      if (!libraryDuplicateRequest) {
        libraryDuplicateRequest = (async () => {
          const response = await fetch('/services', {cache: 'no-store'});
          if (!response.ok) throw new Error('Library unavailable');
          const page = new DOMParser().parseFromString(await response.text(), 'text/html');
          const byDate = new Map();
          page.querySelectorAll('main .table-wrap table tbody > tr').forEach(row => {
            if (row.querySelector('td[colspan]')) return;
            const headings = Array.from(row.closest('table').querySelectorAll('thead th'),
              cell => cell.textContent.trim().toLocaleLowerCase());
            const values = Object.fromEntries(Array.from(row.querySelectorAll('td'),
              (cell, index) => [headings[index], cell.textContent.trim()]));
            const link = row.querySelector('a[href^="/services/"]');
            const path = link && link.getAttribute('href');
            if (!/^\d{4}-\d{2}-\d{2}$/.test(values.date || '') || !/^\/services\/\d+$/.test(path || '')) return;
            const matches = byDate.get(values.date) || [];
            matches.push({url: path, title: values.sermon || values.date});
            byDate.set(values.date, matches);
          });
          libraryDuplicateCache = {byDate, loadedAt: Date.now()};
        })().finally(() => { libraryDuplicateRequest = null; });
      }
      await libraryDuplicateRequest;
    }
    document.querySelectorAll('#import-queue [data-queue-group] tbody > tr').forEach(row => {
      const cell = row.firstElementChild;
      const video = cell && cell.querySelector('a[href^="https://vimeo.com/"]');
      if (!video) return;
      const date = reviewServiceDate(importVideoTitles.get(video.href) || video.textContent.trim());
      const matches = libraryDuplicateCache.byDate.get(date) || [];
      let notice = cell.querySelector('[data-library-duplicate]');
      if (!matches.length) { if (notice) notice.remove(); return; }
      if (!notice) {
        notice = document.createElement('div'); notice.className = 'notice';
        notice.dataset.libraryDuplicate = 'true'; cell.append(notice);
      }
      notice.replaceChildren();
      const label = document.createElement('strong');
      label.textContent = `Possible duplicate — service already in H2 for ${date}.`;
      notice.append(label);
      matches.forEach(match => {
        const line = document.createElement('p');
        const link = document.createElement('a'); link.href = match.url;
        link.textContent = `View existing service: ${match.title}`;
        line.append(link); notice.append(line);
      });
      const selection = row.querySelector('input[name="selection"]');
      if (selection) {
        const merge = document.createElement('a'); merge.className = 'button secondary';
        merge.href = `/services/merge?selection=${encodeURIComponent(selection.value)}&date=${date}`;
        merge.textContent = 'Compare and merge service'; notice.append(merge);
      }
    });
  } catch (_) {
    // Keep the queue usable and retry the read-only comparison on its next refresh.
  }
}

// Keep merge actions beside Review / Continue import rather than buried in the duplicate notice.
function promoteMergeActions() {
  document.querySelectorAll('#import-queue tbody tr').forEach(row => {
    const link = row.querySelector('a[href^="/imports/merge?"]');
    const actions = row.lastElementChild;
    if (!link || !actions) return;
    link.classList.add('button', 'secondary');
    link.textContent = 'Merge recordings';
    if (link.parentElement !== actions) actions.prepend(link);
  });
}

async function resolveImportTitles() {
  if (!document.getElementById('import-queue')) return;
  collectImportTitles(document);
  applyImportTitles();
  const missing = () => Array.from(document.querySelectorAll('#import-queue td:first-child a[href^="https://vimeo.com/"]'))
    .some(link => !importVideoTitles.has(link.href));
  if (!missing() || importTitlesScanned || importTitleScan) return;
  importTitleScan = (async () => {
    let url = '/imports?page=1';
    const visited = new Set();
    while (url && !visited.has(url) && missing()) {
      visited.add(url);
      const response = await fetch(url, {cache: 'no-store'});
      if (!response.ok) throw new Error('Catalog unavailable');
      const page = new DOMParser().parseFromString(await response.text(), 'text/html');
      collectImportTitles(page);
      applyImportTitles();
      const next = Array.from(page.querySelectorAll('a[href*="/imports?page="]'))
        .find(link => link.textContent.includes('Older services'));
      url = next ? next.getAttribute('href') : null;
    }
    importTitlesScanned = true;
  })();
  try { await importTitleScan; } catch (_) { /* Keep the URL when no title is available. */ }
  finally { importTitleScan = null; }
}

function groupImportQueue() {
  const queue = document.getElementById('import-queue');
  if (!queue || queue.querySelector('[data-queue-group]')) return;
  const wrapper = queue.querySelector('.table-wrap');
  const table = wrapper && wrapper.querySelector('table');
  if (!table) return;
  const rows = Array.from(table.querySelectorAll('tbody > tr'));
  if (!rows.length || !rows.some(row => row.querySelector('td:first-child a'))) return;
  const activeStatuses = new Set(['queued', 'downloading', 'extracting', 'recognizing', 'merging']);
  const isProcessing = row => row.dataset.jobStatus
    ? activeStatuses.has(row.dataset.jobStatus)
    : Boolean(row.querySelector('button[name="action"][value="cancel-import"]'));
  const ready = rows.filter(row => !isProcessing(row));
  const processing = rows.filter(isProcessing);
  const groups = document.createDocumentFragment();
  for (const [label, items, empty] of [
    ['Ready for your next action', ready, 'No services need your action right now.'],
    ['Queued or processing', processing, 'No services are queued or processing right now.']
  ]) {
    const section = document.createElement('section');
    section.dataset.queueGroup = 'true';
    const heading = document.createElement('h3');
    heading.textContent = `${label} · ${items.length}`;
    section.append(heading);
    if (items.length) {
      const groupWrapper = document.createElement('div'); groupWrapper.className = 'table-wrap';
      const groupTable = document.createElement('table');
      groupTable.append(table.querySelector('thead').cloneNode(true));
      const body = document.createElement('tbody');
      items.forEach(row => body.append(row));
      groupTable.append(body); groupWrapper.append(groupTable); section.append(groupWrapper);
    } else {
      const message = document.createElement('p'); message.className = 'muted'; message.textContent = empty; section.append(message);
    }
    groups.append(section);
  }
  wrapper.replaceWith(groups);
}

function markQueuedTitleAlternatives() {
  const key = value => value.toLocaleLowerCase().replace(/\s+/g, ' ').trim();
  const queued = Array.from(document.querySelectorAll('#import-queue td:first-child a[href^="https://vimeo.com/"]'))
    .map(link => ({url: link.href, title: importVideoTitles.get(link.href) || link.textContent.trim()}))
    .filter(item => item.title && item.title !== item.url);
  document.querySelectorAll('input[type="checkbox"][name="video"]').forEach(input => {
    const row = input.closest('tr');
    const link = row && row.querySelector('a[href^="https://vimeo.com/"]');
    if (!link) return;
    const existing = queued.find(item => item.url !== input.value && key(item.title) === key(link.textContent.trim()));
    if (existing) {
      input.title = `Duplicate title: another recording of ${existing.title} is selected. You may download both.`;
      if (row.cells.length >= 5 && !queued.some(item => item.url === input.value)) row.cells[4].textContent = 'Duplicate title · can select';
    }
  });
}

function markQueuedVideos() {
  groupImportQueue();
  resolveImportTitles();
  const queue = document.getElementById('import-queue');
  if (!queue) return;
  const queuedUrls = new Set(Array.from(queue.querySelectorAll('a[href^="https://vimeo.com/"]'), a => a.href));
  document.querySelectorAll('input[type="checkbox"][name="video"]').forEach(input => {
    if (queuedUrls.has(input.value)) {
      input.checked = true;
      input.disabled = true;
    }
  });
  markQueuedTitleAlternatives();
  sortImportVideoTables();
}
markQueuedVideos();

document.querySelectorAll('[data-add-review-variation]').forEach(button => {
  const panel = document.getElementById(button.getAttribute('aria-controls'));
  button.addEventListener('click', () => {
    const opening = panel.hidden;
    panel.hidden = !opening;
    button.setAttribute('aria-expanded', String(opening));
    button.textContent = opening ? 'Cancel new variation' : 'Add variation';
    if (opening) {
      panel.querySelector('input').focus();
    } else {
      panel.querySelectorAll('select').forEach(select => { select.value = select.name.endsWith('-variation-guitar') ? 'review' : ''; });
      panel.querySelectorAll('input').forEach(input => {
        if (input.type === 'checkbox') input.checked = false;
        else input.value = '';
        input.dispatchEvent(new Event('input', {bubbles: true}));
      });
    }
  });
});

const draftForm = document.getElementById('review-draft');
const approveButton = document.getElementById('approve-draft');
if (draftForm) {
  const scrollKey = `review-draft-scroll:${location.pathname}`;
  try {
    const saved = JSON.parse(sessionStorage.getItem(scrollKey) || 'null');
    sessionStorage.removeItem(scrollKey);
    if (saved && Date.now() - saved.time < 60000 &&
        new URLSearchParams(location.search).get('notice') === 'saved') {
      const details = Array.from(draftForm.querySelectorAll('details'));
      saved.openDetails.forEach(index => { if (details[index]) details[index].open = true; });
      const restore = () => window.scrollTo({top: saved.y, left: saved.x, behavior: 'instant'});
      // Restore immediately to load nearby lazy images, then again after layout settles.
      restore();
      if (document.readyState === 'complete') requestAnimationFrame(restore);
      else window.addEventListener('load', () => requestAnimationFrame(restore), {once: true});
    }
  } catch (_) {
    // Saving still works when browser storage is unavailable.
  }
  draftForm.addEventListener('submit', event => {
    try {
      sessionStorage.removeItem(scrollKey);
      if (event.submitter?.name === 'action' && event.submitter.value === 'save-draft') {
        sessionStorage.setItem(scrollKey, JSON.stringify({
          x: window.scrollX, y: window.scrollY, time: Date.now(),
          openDetails: Array.from(draftForm.querySelectorAll('details'))
            .flatMap((details, index) => details.open ? [index] : [])
        }));
      }
    } catch (_) {
      // Keep the normal form submission available without browser storage.
    }
  });
}

if (draftForm && approveButton) {
  draftForm.addEventListener('input', () => {
    approveButton.disabled = true;
    document.getElementById('approval-hint').textContent = 'Save your edits before approving this service.';
  });
}

let importProgressTimer = null;
let importProgressInFlight = false;
async function refreshImportProgress() {
  const queue = document.getElementById('import-queue');
  if (!queue || queue.dataset.processing !== 'true' || importProgressInFlight) return;
  importProgressInFlight = true;
  try {
    const response = await fetch(`/imports${location.search}`, {cache: 'no-store', headers: {'X-Import-Progress': 'true'}});
    if (response.ok) {
      const html = new DOMParser().parseFromString(await response.text(), 'text/html');
      const updated = html.getElementById('import-queue');
      if (updated && !queue.querySelector('form input[name="action"][value="select"]')?.form.contains(document.activeElement)) {
        preserveImportLinkEntry(queue, updated);
        queue.replaceChildren(...updated.childNodes);
        queue.dataset.processing = updated.dataset.processing;
        markQueuedVideos();
      }
    }
  } catch (_) {
    // Keep the current status and retry if the local server is restarting.
  } finally {
    importProgressInFlight = false;
    importProgressTimer = window.setTimeout(refreshImportProgress, 4000);
  }
}
importProgressTimer = window.setTimeout(refreshImportProgress, 4000);

const trimVideo = document.getElementById('trim-preview');
if (trimVideo) {
  const startInput = document.getElementById('trim-start');
  const endInput = document.getElementById('trim-end');
  const status = document.getElementById('trim-player-status');
  const formatTime = seconds => {
    seconds = Math.floor(seconds);
    return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
      .map((n, i) => i ? String(n).padStart(2, '0') : String(n)).join(':');
  };
  const parseTime = value => {
    if (!value.trim()) return null;
    const parts = value.trim().split(':');
    if (parts.length > 3 || parts.some(p => !/^\d+$/.test(p))) return NaN;
    return parts.reduce((total, n) => total * 60 + Number(n), 0);
  };
  document.querySelectorAll('[data-time-target]').forEach(button => {
    button.addEventListener('click', () => {
      if (Number.isFinite(trimVideo.currentTime)) {
        document.getElementById(button.dataset.timeTarget).value = formatTime(trimVideo.currentTime);
      }
    });
  });
  let previewEnd = null;
  document.getElementById('preview-range').addEventListener('click', () => {
    const start = parseTime(startInput.value) ?? 0;
    const end = parseTime(endInput.value) ?? trimVideo.duration;
    if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start || end > trimVideo.duration) {
      status.textContent = 'Wait for the video to load, then enter a start and end within the video.';
      return;
    }
    previewEnd = end;
    trimVideo.currentTime = start;
    trimVideo.play().catch(() => { status.textContent = 'Press Play to preview the selected range.'; });
    status.textContent = `Previewing ${formatTime(start)} to ${formatTime(end)}.`;
  });
  trimVideo.addEventListener('timeupdate', () => {
    if (previewEnd !== null && trimVideo.currentTime >= previewEnd) {
      trimVideo.pause(); previewEnd = null;
    }
  });
  trimVideo.addEventListener('error', () => { status.textContent = 'This video could not be previewed in the browser. You can still enter timestamps from Vimeo.'; });
  document.getElementById('whole-video-range').addEventListener('click', () => {
    startInput.value = '0:00:00'; endInput.value = ''; previewEnd = null;
  });
}

document.querySelectorAll('[data-evidence-select]').forEach(select => {
  select.addEventListener('change', async () => {
    const pair = select.closest('.review-source-pair');
    const image = pair.querySelector('.source-slide-image');
    const text = pair.querySelector('.source-ocr-text');
    const caption = pair.querySelector('.source-slide-caption');
    const link = pair.querySelector('.source-video-link');
    const index = select.value;
    if (!/^\d+$/.test(index)) {
      image.hidden = true; text.textContent = 'No source selected.'; caption.textContent = 'Select a slide to check the adjacent fields.'; return;
    }
    image.hidden = false;
    image.src = `/imports/${select.dataset.serviceId}/frame/${index}?revision=${select.dataset.revision}`;
    link.href = `${pair.dataset.sourceVideo}#t=${Number(index) * 3}s`;
    caption.textContent = `Source time · ${Number(index) * 3} seconds`;
    text.textContent = 'Loading source OCR…';
    try {
      const response = await fetch(`/imports/${select.dataset.serviceId}/ocr/${index}`, {cache: 'no-store'});
      if (!response.ok) throw new Error('Source not available');
      const source = await response.json();
      if (select.value === index) text.textContent = source.ocr;
    } catch (_) {
      if (select.value === index) text.textContent = 'Source OCR could not be loaded. Check the slide image or refresh the page.';
    }
  });
});

// Also supports an admin process started before automatic download queueing was added.
const selectionAction = document.querySelector('form input[name="action"][value="select"]');
if (selectionAction) {
  const selectionForm = selectionAction.form;
  const prepareHeading = Array.from(document.querySelectorAll('h2')).find(h => h.textContent === 'Prepare your next import');
  const prepareText = prepareHeading && prepareHeading.parentElement.querySelector('p');
  if (prepareText) prepareText.textContent = 'Save the services you want to process to queue their video downloads. Up to three download at once; review and OCR can follow when each download finishes.';
  if (new URLSearchParams(window.location.search).get('notice') === 'downloads-queued') {
    const notice = document.createElement('p'); notice.className = 'notice'; notice.setAttribute('role', 'status');
    notice.textContent = 'Selection saved and downloads queued. Up to three videos download at once.';
    selectionForm.prepend(notice);
  }
  const saveButton = selectionForm.querySelector('button');
  const selectionButtonLabel = selectionForm.querySelector('input[type="checkbox"][name="video"]') ? 'Save selection and queue downloads' : 'Download pasted videos';
  if (saveButton) saveButton.textContent = selectionButtonLabel;
  selectionForm.addEventListener('submit', async event => {
    event.preventDefault();
    if (selectionForm.dataset.submitting === 'true') return;
    selectionForm.dataset.submitting = 'true';
    const data = new FormData(selectionForm);
    const urls = new Set(data.getAll('video'));
    String(data.get('links') || '').split(/\r?\n/).forEach(value => {
      const match = value.trim().match(/^https:\/\/(?:www\.)?vimeo\.com\/(\d+)(?:[/?#].*)?$/);
      if (match) urls.add(`https://vimeo.com/${match[1]}`);
    });
    if (saveButton) { saveButton.disabled = true; saveButton.textContent = 'Saving and queueing…'; }
    try {
      let response = await fetch('/imports', {method: 'POST', body: new URLSearchParams(data)});
      if (response.status === 403) {
        // A backend restart invalidates old form tokens. Refresh it once without losing the user's selection.
        const fresh = await fetch('/imports', {cache: 'no-store'});
        if (fresh.ok) {
          const page = new DOMParser().parseFromString(await fresh.text(), 'text/html');
          const token = page.querySelector('input[name="__anti-forgery-token"]')?.value;
          if (token) {
            data.set('__anti-forgery-token', token);
            document.querySelectorAll('input[name="__anti-forgery-token"]').forEach(input => { input.value = token; });
            response = await fetch('/imports', {method: 'POST', body: new URLSearchParams(data)});
          }
        }
      }
      if (!response.ok) {
        const page = new DOMParser().parseFromString(await response.text(), 'text/html');
        let reason = page.querySelector('.error')?.textContent.trim();
        if (reason && reason.includes('same video title')) {
          const key = title => title.toLocaleLowerCase().replace(/\s+/g, ' ').trim();
          const candidates = Array.from(urls).map(url => ({url, title: importVideoTitles.get(url)})).filter(item => item.title);
          const known = Array.from(document.querySelectorAll('#import-queue td:first-child a[href^="https://vimeo.com/"]'))
            .map(link => ({url: link.href, title: importVideoTitles.get(link.href) || link.textContent.trim()}));
          const conflicts = candidates.filter(item => [...candidates, ...known].some(other => other.url !== item.url && key(other.title) === key(item.title)));
          const titles = Array.from(new Set(conflicts.map(item => item.title)));
          if (titles.length) reason += ` Conflicting title${titles.length === 1 ? '' : 's'}: ${titles.join('; ')}.`;
        }
        throw new Error(reason || (response.status === 403
          ? 'The form session expired. Refresh the page and try again.'
          : `Selection could not be saved (HTTP ${response.status}). Your choices are still selected; try again or check the server status.`));
      }
      if (!response.url.includes('notice=downloads-queued')) {
        const page = new DOMParser().parseFromString(await response.text(), 'text/html');
        for (const row of page.querySelectorAll('#import-queue tbody tr')) {
          const link = row.querySelector('td:first-child a');
          if (!link || !urls.has(link.href)) continue;
          const downloadButton = row.querySelector('button[name="action"][value="download-video"]');
          if (!downloadButton) continue; // Active jobs and completed downloads already have no such button.
          const job = new FormData(downloadButton.form);
          job.set('action', 'download-video');
          const queued = await fetch('/imports', {method: 'POST', body: new URLSearchParams(job)});
          if (!queued.ok) throw new Error('Selection was saved, but a download could not be queued. Check its status and use Download video to retry.');
        }
      }
      window.location.assign('/imports?notice=downloads-queued');
    } catch (error) {
      let message = selectionForm.querySelector('[data-selection-error]');
      if (!message) { message = document.createElement('p'); message.className = 'error'; message.dataset.selectionError = 'true'; message.setAttribute('role', 'alert'); selectionForm.prepend(message); }
      message.textContent = error.message;
      message.scrollIntoView({block: 'center', behavior: 'smooth'});
      selectionForm.dataset.submitting = 'false';
      if (saveButton) { saveButton.disabled = false; saveButton.textContent = selectionButtonLabel; }
    }
  });
}

function reviewCombobox(input, values, id) {
  const wrapper = document.createElement('div'); wrapper.className = 'review-combobox';
  input.replaceWith(wrapper); wrapper.append(input);
  input.removeAttribute('list'); input.setAttribute('autocomplete', 'off');
  input.setAttribute('role', 'combobox'); input.setAttribute('aria-autocomplete', 'list');
  input.setAttribute('aria-expanded', 'false'); input.setAttribute('aria-controls', id);
  const toggle = document.createElement('button'); toggle.type = 'button'; toggle.className = 'review-combobox-toggle';
  toggle.textContent = '▾'; toggle.setAttribute('aria-label', `Show choices for ${input.closest('label').querySelector('span').textContent}`);
  const menu = document.createElement('div'); menu.id = id; menu.className = 'review-combobox-menu';
  menu.setAttribute('role', 'listbox'); menu.hidden = true;
  wrapper.append(toggle, menu);
  let choices = []; let active = -1;
  const close = () => { menu.hidden = true; input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); active = -1; };
  const choose = value => {
    input.value = value; close(); input.focus(); close();
    input.dispatchEvent(new Event('input', {bubbles: true}));
    input.dispatchEvent(new Event('change', {bubbles: true})); close();
  };
  const show = (filter = '') => {
    menu.replaceChildren(); active = -1; input.removeAttribute('aria-activedescendant');
    choices = values.filter(value => value.toLocaleLowerCase().includes(filter.toLocaleLowerCase()));
    choices.forEach((value, index) => {
      const option = document.createElement('div'); option.id = `${id}-${index}`;
      option.setAttribute('role', 'option'); option.setAttribute('aria-selected', String(input.value === value));
      option.textContent = value; option.addEventListener('pointerdown', event => event.preventDefault());
      option.addEventListener('click', event => { event.preventDefault(); choose(value); }); menu.append(option);
    });
    if (!choices.length) { const empty = document.createElement('p'); empty.className = 'muted'; empty.textContent = 'No matching library values. You can enter a new value.'; menu.append(empty); }
    menu.hidden = false; input.setAttribute('aria-expanded', 'true');
  };
  input.addEventListener('focus', () => show());
  input.addEventListener('click', () => { if (menu.hidden) show(); });
  input.addEventListener('input', () => show(input.value));
  toggle.addEventListener('pointerdown', event => event.preventDefault());
  toggle.addEventListener('click', event => { event.preventDefault(); if (menu.hidden) { input.focus(); show(); } else close(); });
  input.addEventListener('keydown', event => {
    if (event.key === 'Escape') { close(); event.preventDefault(); }
    if (event.key === 'Tab') close();
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); if (menu.hidden) show();
      if (!choices.length) return;
      active = (active + (event.key === 'ArrowDown' ? 1 : -1) + choices.length) % choices.length;
      Array.from(menu.children).forEach((option, index) => option.classList.toggle('active', index === active));
      input.setAttribute('aria-activedescendant', menu.children[active].id);
      menu.children[active].scrollIntoView({block: 'nearest'});
    } else if (event.key === 'Enter' && !menu.hidden && active >= 0) {
      event.preventDefault(); choose(choices[active]);
    }
  });
  wrapper.addEventListener('focusout', event => { if (!wrapper.contains(event.relatedTarget)) close(); });
  document.addEventListener('pointerdown', event => { if (!wrapper.contains(event.target)) close(); });
}

async function enableReviewAutocomplete() {
  const form = document.getElementById('review-draft');
  if (!form) return;
  const hint = document.createElement('p'); hint.className = 'muted';
  hint.textContent = 'People, holidays, sermon series, song titles and authors: type to search existing library values, or enter a new value.';
  form.querySelector('h2').after(hint);
  const loadRows = async path => {
    const response = await fetch(path, {cache: 'no-store'});
    if (!response.ok) throw new Error('Library unavailable');
    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
    return Array.from(page.querySelectorAll('main .table-wrap table tbody > tr'))
      .filter(row => !row.querySelector('td[colspan]'))
      .map(row => {
        const headings = Array.from(row.closest('table').querySelectorAll('thead th'),
          cell => cell.textContent.trim().toLocaleLowerCase());
        return Object.fromEntries(Array.from(row.querySelectorAll('td'),
          (cell, index) => [headings[index], cell.textContent.trim()]));
      });
  };
  const attach = (id, values, selector) => {
    const unique = Array.from(new Set(values.filter(value => value && value !== '—')))
      .sort((a, b) => a.localeCompare(b, undefined, {sensitivity: 'base'}));
    form.querySelectorAll(selector).forEach(input => reviewCombobox(input, unique, `${id}-${input.name}`));
  };
  const results = await Promise.allSettled([
    loadRows('/services').then(rows => {
      attach('review-library-leaders', rows.map(row => row['song leader']), 'input[name="leader"]');
      attach('review-library-preachers', rows.map(row => row['preacher']), 'input[name="preacher"]');
    }),
    loadRows('/series').then(rows => attach('review-library-series', rows.map(row => row['name']), 'input[name="sermon-series"]')),
    loadRows('/holidays').then(rows => attach('review-library-holidays', rows.map(row => row['name']), 'input[name="holiday"]')),
    loadRows('/songs').then(rows => {
      attach('review-library-songs', rows.map(row => row['song title']), 'input[name^="song-"][name$="-title"]');
      attach('review-library-authors', rows.map(row => row['artist']), 'input[name^="song-"][name$="-author"]');
    })
  ]);
  if (results.some(result => result.status === 'rejected')) {
    hint.textContent += ' Some suggestions could not be loaded; you can still type values or reload the page to retry.';
  }
}
enableReviewAutocomplete();

function reviewServiceDate(title) {
  const match = title.match(/^\s*livestream\s*[:\-]?\s*(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)/i);
  if (!match) return '';
  const month = Number(match[1]), day = Number(match[2]);
  const year = Number(match[3]) + (match[3].length === 2 ? 2000 : 0);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return '';
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}
async function suggestReviewServiceDate() {
  const form = document.getElementById('review-draft');
  const input = form && form.querySelector('input[name="date"]');
  if (!input || input.value) return;
  let edited = false;
  input.addEventListener('input', () => { edited = true; }, {once: true});
  try {
    const source = Array.from(document.querySelectorAll('main > p a[href^="https://vimeo.com/"]'))
      .find(link => link.textContent.includes('Watch source video'));
    if (!source) return;
    const response = await fetch('/imports', {cache: 'no-store'});
    if (!response.ok) return;
    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
    const video = Array.from(page.querySelectorAll('#import-queue td:first-child a')).find(link => link.href === source.href);
    const date = video && reviewServiceDate(video.textContent.trim());
    if (date && !input.value && !edited) {
      input.value = date;
      const note = document.createElement('small'); note.className = 'muted'; note.textContent = `Suggested from video title: ${video.textContent.trim()}. Verify before saving.`;
      input.closest('label').append(note);
      input.dispatchEvent(new Event('input', {bubbles: true}));
    }
  } catch (_) { /* Leave manual entry available if the catalog cannot be read. */ }
}
suggestReviewServiceDate();

function songHistoryKey(value) {
  return (value || '').normalize('NFKC').toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}
async function enableSongHistoryStatus() {
  const form = document.getElementById('review-draft');
  if (!form) return;
  const sections = Array.from(form.querySelectorAll('.song-review'));
  const historyCache = new Map();
  const loadPage = async url => {
    const response = await fetch(url, {cache: 'no-store'});
    if (!response.ok) throw new Error('History unavailable');
    return new DOMParser().parseFromString(await response.text(), 'text/html');
  };
  const library = loadPage('/songs').then(page => {
    const songs = Array.from(page.querySelectorAll('main .table-wrap tbody > tr')).flatMap(row => {
      const cells = row.querySelectorAll('td');
      const link = row.querySelector('a.row-link');
      const path = link && link.getAttribute('href');
      return cells.length >= 3 && /^\/songs\/\d+$/.test(path || '')
        ? [{title: cells[0].textContent.trim(), author: cells[1].textContent.trim(), path}] : [];
    });
    const aliases = Array.from(page.querySelectorAll('#song-identity-aliases [data-song-path]'), alias => ({
      title: alias.dataset.name, author: alias.dataset.artist, path: alias.dataset.songPath
    }));
    return songs.concat(aliases);
  });
  library.catch(() => {}); // Empty song rows may not await the library until the user types.
  const history = song => {
    if (!historyCache.has(song.path)) {
      const request = loadPage(song.path).then(page => {
        const heading = Array.from(page.querySelectorAll('h2')).find(h => h.textContent.trim() === 'Performance history');
        const table = heading && heading.nextElementSibling;
        if (!table || !table.querySelector('tbody')) throw new Error('History unavailable');
        return Array.from(table.querySelectorAll('tbody > tr')).filter(row => !row.querySelector('td[colspan]'))
          .map(row => {
            const match = row.querySelector('td').textContent.trim().match(/^\d{4}-\d{2}-\d{2}/);
            if (!match) throw new Error('History date unavailable');
            return match[0];
          });
      }).catch(error => { historyCache.delete(song.path); throw error; });
      historyCache.set(song.path, request);
    }
    return historyCache.get(song.path);
  };
  for (const section of sections) {
    const title = section.querySelector('input[name$="-title"]');
    const author = section.querySelector('input[name$="-author"]');
    const choice = section.querySelector('select[name$="-choice"]');
    const variationChoice = section.querySelector('[data-review-variation]');
    const variationDetails = section.querySelector('[data-review-variation-details]');
    const variationName = section.querySelector('[data-review-variation-name]');
    const variationRows = Array.from(document.querySelectorAll('#review-variation-library [data-variation-id]'), el => ({...el.dataset}));
    const showVariationDetails = () => {
      if (!variationChoice || !variationDetails) return;
      if (variationName?.value.trim()) {
        variationDetails.textContent = `On approval, use variation “${variationName.value.trim()}” for this song. A matching name reuses its existing settings.`;
        return;
      }
      const row = variationRows.find(row => row.variationId === variationChoice.value);
      variationDetails.textContent = row
        ? `Example link: ${row.example || 'none'} · key: ${row.musicalKey || '—'} · prominent electric guitar: ${row.guitar === 'true' ? 'Yes' : row.guitar === 'false' ? 'No' : 'Review'}. These settings will be preserved.`
        : 'Default will be reused or created for the selected song. Choose an existing song above to select one of its variations.';
    };
    const updateVariations = songs => {
      if (!variationChoice) return;
      let songId = /^\d+$/.test(choice.value) ? choice.value : null;
      if (choice.value === 'auto' && author.value.trim()) {
        const matches = new Set(songs.filter(song => songHistoryKey(song.title) === songHistoryKey(title.value) && songHistoryKey(song.author) === songHistoryKey(author.value)).map(song => song.path.split('/').pop()));
        if (matches.size === 1) songId = Array.from(matches)[0];
      }
      const previous = variationChoice.value;
      const allowed = variationRows.filter(row => row.songId === songId);
      variationChoice.replaceChildren(new Option('Use Default (existing or create)', 'auto'),
        ...allowed.map(row => new Option(row.name, row.variationId)));
      variationChoice.value = allowed.some(row => row.variationId === previous) ? previous : 'auto';
      showVariationDetails();
      if (previous && previous !== 'auto' && variationChoice.value === 'auto') {
        variationChoice.dispatchEvent(new Event('change', {bubbles: true}));
        variationDetails.textContent = 'The previous variation does not belong to the current song. Choose a variation and save the draft again.';
      }
    };
    variationChoice?.addEventListener('change', showVariationDetails);
    variationName?.addEventListener('input', showVariationDetails);

    const status = document.createElement('div'); status.className = 'song-history-status notice';
    status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    section.querySelector('h2').after(status);
    let version = 0, timer;
    const update = async () => {
      const current = ++version;
      status.replaceChildren();
      if (/^\d+$/.test(choice.value) || choice.value === 'new') updateVariations([]);
      if (!title.value.trim() && !/^\d+$/.test(choice.value)) { updateVariations([]); status.textContent = 'Enter a song title to check previous performances.'; return; }
      status.textContent = 'Checking song history…';
      try {
        const songs = await library;
        if (version !== current) return;
        updateVariations(songs);
        const candidates = songs.filter(song => songHistoryKey(song.title) === songHistoryKey(title.value));
        const selected = choice.value;
        const retained = Array.from(choice.options).find(option => option.value === selected);
        choice.querySelectorAll('option[data-existing-song]').forEach(option => option.remove());
        const uniqueCandidates = Array.from(new Map(candidates.map(song => [song.path, song])).values());
        uniqueCandidates.forEach(song => {
          const id = song.path.split('/').pop();
          if (!Array.from(choice.options).some(option => option.value === id)) {
            const option = new Option(`Use existing: ${song.title} · ${song.author}`, id);
            option.dataset.existingSong = 'true'; choice.append(option);
          }
        });
        if (retained && !Array.from(choice.options).some(option => option.value === selected)) choice.append(retained);
        choice.value = selected;
        if (selected === 'new') { status.textContent = 'Create a new song using the reviewed title and author.'; return; }
        if (/^\d+$/.test(selected)) {
          const existing = songs.find(song => song.path.split('/').pop() === selected);
          status.textContent = existing
            ? `Import using the existing song: ${existing.title} · ${existing.author || 'author not recorded'}. Blank OCR author is allowed for this selection. The fields below remain slide evidence.`
            : 'Use the selected existing song. Its library title and author will be used; blank OCR author is allowed. The fields below remain slide evidence.';
          return;
        }
        if (!candidates.length) { status.textContent = 'New song — no matching title in the library.'; return; }
        const matches = Array.from(new Map(candidates.filter(song => author.value.trim() && songHistoryKey(song.author) === songHistoryKey(author.value))
          .map(song => [song.path, song])).values());
        const addLinks = items => items.forEach(song => {
          const link = document.createElement('a'); link.href = song.path; link.target = '_blank'; link.rel = 'noopener noreferrer';
          link.textContent = `View history: ${song.title} · ${song.author}`; status.append(document.createElement('br'), link);
        });
        if (!matches.length) {
          status.textContent = author.value.trim()
            ? 'New song — the title matches an existing song, but the author is different. Choose an existing song above, or create a new song.'
            : 'The title matches an existing song. Enter an author to determine whether this is a new song.';
          addLinks(candidates); return;
        }
        const dates = (await Promise.all(matches.map(history))).flat();
        if (version !== current) return;
        const serviceDate = form.querySelector('input[name="date"]').value;
        const earlier = dates.filter(date => !serviceDate || date < serviceDate).sort().reverse();
        if (earlier.length) {
          status.textContent = `Performed before — ${earlier.length} recorded performance${earlier.length === 1 ? '' : 's'}${serviceDate ? ' before this service' : ''}; most recent ${earlier[0]}.`;
        } else if (dates.length) {
          status.textContent = 'No earlier performance recorded for this service date. The song has later or same-day performances in the library.';
        } else {
          status.textContent = 'New performance — the song is in the library, but has no recorded performances.';
        }
        addLinks(matches);
      } catch (_) {
        if (version === current) status.textContent = 'Song history could not be checked. Reload to retry; no new-song determination was made.';
      }
    };
    const schedule = () => { ++version; clearTimeout(timer); timer = setTimeout(update, 250); };
    title.addEventListener('input', schedule); author.addEventListener('input', schedule);
    choice.addEventListener('change', schedule);
    form.querySelector('input[name="date"]').addEventListener('input', schedule);
    update();
  }
}
enableSongHistoryStatus();

async function enableReviewVideoPlayer() {
  const form = document.getElementById('review-draft');
  const match = location.pathname.match(/^\/imports\/(\d+)$/);
  if (!form || !match) return;
  const id = match[1];
  const card = document.createElement('section'); card.className = 'card review-video'; card.id = 'review-video';
  const heading = document.createElement('h2'); heading.textContent = 'Review the source video';
  const description = document.createElement('p'); description.className = 'muted';
  description.textContent = 'Watch the selected OCR range or switch to the whole downloaded video. All times refer to the full video. Watching does not change your OCR range or saved fields.';
  const status = document.createElement('p'); status.className = 'muted'; status.setAttribute('role', 'status');
  const rangeLink = document.createElement('a'); rangeLink.href = `/imports/${id}/trim`; rangeLink.textContent = 'Change the OCR range →';
  card.append(heading, description, status, rangeLink); form.before(card);
  const stamp = value => {
    const n = Math.max(0, Math.floor(value));
    return `${Math.floor(n / 3600)}:${String(Math.floor(n / 60) % 60).padStart(2, '0')}:${String(n % 60).padStart(2, '0')}`;
  };
  const seconds = value => value.trim() ? value.trim().split(':').reduce((n, part) => n * 60 + Number(part), 0) : null;
  try {
    const response = await fetch(`/imports/${id}/trim`, {cache: 'no-store'});
    if (!response.ok) throw new Error('Video unavailable');
    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
    const start = seconds(page.querySelector('#trim-start').value) || 0;
    const requestedEnd = seconds(page.querySelector('#trim-end').value);
    const video = document.createElement('video'); video.controls = true; video.preload = 'metadata';
    video.src = `/imports/${id}/preview`; video.setAttribute('aria-label', 'Downloaded service video');
    const modeLabel = document.createElement('label'); modeLabel.className = 'field review-video-mode';
    const caption = document.createElement('span'); caption.textContent = 'Playback section';
    const mode = document.createElement('select'); mode.setAttribute('aria-label', 'Playback section');
    for (const [value, text] of [['range', `Selected OCR range · ${stamp(start)} to ${requestedEnd === null ? 'end' : stamp(requestedEnd)}`], ['whole', 'Whole downloaded video']]) {
      const option = document.createElement('option'); option.value = value; option.textContent = text; mode.append(option);
    }
    modeLabel.append(caption, mode);
    const seekLabel = document.createElement('label'); seekLabel.className = 'field';
    const seekCaption = document.createElement('span'); seekCaption.textContent = 'Seek within playback section';
    const seek = document.createElement('input'); seek.type = 'range'; seek.step = '1'; seek.disabled = true;
    seek.setAttribute('aria-label', 'Seek within playback section'); seekLabel.append(seekCaption, seek);
    card.insertBefore(modeLabel, status); card.insertBefore(video, status); card.insertBefore(seekLabel, status);
    const nominate = document.createElement('button'); nominate.type = 'button'; nominate.className = 'secondary';
    nominate.textContent = 'Use this frame as the sermon slide';
    const nominationHint = document.createElement('p'); nominationHint.className = 'muted';
    nominationHint.textContent = 'Pause on the sermon slide, then use this button to read it locally. The selected time is aligned to the nearest 3-second OCR frame. Existing field edits are kept; save the draft after reviewing suggestions.';
    card.append(nominationHint, nominate);
    const addSongFrame = document.createElement('button');
    addSongFrame.type = 'submit'; addSongFrame.setAttribute('form', 'review-draft');
    addSongFrame.name = 'action'; addSongFrame.value = 'add-song-frame';
    addSongFrame.textContent = 'Add song from this frame';
    addSongFrame.addEventListener('click', event => {
      if (readingSermonFrame || !Number.isFinite(video.duration)) {
        event.preventDefault(); status.textContent = 'Wait for the video or frame OCR to finish loading first.'; return;
      }
      video.pause();
      let frameTime = form.querySelector('input[name="frame-seconds"]');
      if (!frameTime) { frameTime = document.createElement('input'); frameTime.type = 'hidden'; frameTime.name = 'frame-seconds'; form.append(frameTime); }
      frameTime.value = String(Math.max(0, Math.min(Math.round(video.currentTime / 3), Math.floor((video.duration - .001) / 3))) * 3);
      status.textContent = 'Reading the selected frame and saving your edits with a new song entry…';
    });
    const songFrameHint = document.createElement('p'); songFrameHint.className = 'muted';
    songFrameHint.textContent = 'Pause on a song slide, then add it to the review. This saves your current edits and reads the nearest 3-second frame locally. Choose an existing song or create a new song in the added entry.';
    card.append(songFrameHint, addSongFrame);

    const sermonSource = form.querySelector('select[name="sermon-evidence-index"]');
    let applyingSermonFrame = false;
    let readingSermonFrame = false;
    form.querySelectorAll('input[name="preacher"], input[name="sermon-title"], input[name="sermon-scripture"], input[name="sermon-series"], input[name="sermon-topic"]').forEach(input => {
      input.addEventListener('input', () => { delete input.dataset.sermonFrameSuggestion; });
    });
    const readSermonFrame = async time => {
      if (readingSermonFrame) return;
      readingSermonFrame = true; sermonSource.disabled = true;
      nominate.disabled = true; nominate.textContent = `Reading sermon frame at ${stamp(time)}…`;
      const saveButtons = Array.from(form.querySelectorAll('button[type="submit"], button[name="action"]'));
      const savedDisabled = saveButtons.map(button => button.disabled);
      saveButtons.forEach(button => { button.disabled = true; });
      let feedback = form.querySelector('[data-nominated-sermon-notice]');
      if (!feedback) { feedback = document.createElement('p'); feedback.className = 'notice'; feedback.dataset.nominatedSermonNotice = 'true'; sermonSource.closest('.review-source-pair').append(feedback); }
      feedback.setAttribute('role', 'status'); feedback.textContent = `Reading selected sermon frame at ${stamp(time)} with local OCR…`;
      try {
        const request = new URLSearchParams({action: 'suggest-sermon-frame',
          '__anti-forgery-token': form.querySelector('input[name="__anti-forgery-token"]').value,
          revision: form.querySelector('input[name="revision"]').value, 'frame-seconds': String(time)});
        const response = await fetch(`/imports/${id}`, {method: 'POST', body: request});
        if (!response.ok) {
          const errorPage = new DOMParser().parseFromString(await response.text(), 'text/html');
          throw new Error(errorPage.querySelector('.error')?.textContent || 'Could not read this sermon frame. Reload if the draft changed, then retry.');
        }
        const result = await response.json();
        document.querySelectorAll('input[name="revision"]').forEach(input => { input.value = String(result.revision); });
        document.querySelectorAll('[data-evidence-select]').forEach(select => { select.dataset.revision = String(result.revision); });
        for (const name of ['preacher', 'sermon-title', 'sermon-scripture', 'sermon-series', 'sermon-topic']) {
          const input = form.querySelector(`input[name="${name}"]`);
          if (input && (!input.value.trim() || input.value === input.dataset.sermonFrameSuggestion)) {
            input.value = result.details[name] || '';
            input.dataset.sermonFrameSuggestion = input.value;
          }
        }
        form.querySelector('input[name="sermon-time"]').value = result.details['sermon-time'];
        const source = form.querySelector('select[name="sermon-evidence-index"]');
        let option = Array.from(source.options).find(option => option.value === String(result.frame.index));
        if (!option) { option = document.createElement('option'); option.value = String(result.frame.index); source.append(option); }
        option.textContent = `${stamp(time)} · ${result.frame.ocr.replace(/\s+/g, ' ').slice(0, 85)}`;
        source.value = option.value;
        applyingSermonFrame = true; source.dispatchEvent(new Event('change', {bubbles: true})); applyingSermonFrame = false;
        form.dispatchEvent(new Event('input', {bubbles: true}));
        let notice = form.querySelector('[data-nominated-sermon-notice]');
        if (!notice) { notice = document.createElement('p'); notice.className = 'notice'; notice.dataset.nominatedSermonNotice = 'true'; source.closest('.review-source-pair').append(notice); }
        notice.textContent = `Sermon frame read at ${stamp(time)}. ${result.details['sermon-reference'] ? 'Scripture: ' + result.details['sermon-reference'] + '. ' : ''}Blank fields were filled where readable; existing edits were kept. Verify the adjacent image and save your draft. Missing details can be entered manually.`;
        source.closest('.review-source-pair').scrollIntoView({block: 'start', behavior: 'smooth'});
      } catch (error) { feedback.textContent = error.message; }
      finally {
        readingSermonFrame = false; applyingSermonFrame = false; sermonSource.disabled = false;
        nominate.disabled = false; nominate.textContent = 'Use this frame as the sermon slide';
        saveButtons.forEach((button, index) => { button.disabled = savedDisabled[index]; });
      }
    };
    sermonSource.addEventListener('change', () => {
      if (!applyingSermonFrame && /^\d+$/.test(sermonSource.value)) readSermonFrame(Number(sermonSource.value) * 3);
    });
    nominate.addEventListener('click', () => {
      if (!Number.isFinite(video.duration)) { status.textContent = 'Wait for the video to load first.'; return; }
      video.pause();
      const index = Math.max(0, Math.min(Math.round(video.currentTime / 3), Math.floor((video.duration - 0.001) / 3)));
      const time = index * 3; video.currentTime = time; readSermonFrame(time);
    });
    const bounds = () => mode.value === 'range' ? [start, Math.min(requestedEnd ?? video.duration, video.duration)] : [0, video.duration];
    const update = () => {
      if (!Number.isFinite(video.duration)) return;
      const [low, high] = bounds(); seek.min = low; seek.max = high; seek.value = video.currentTime; seek.disabled = false;
      seek.setAttribute('aria-valuetext', stamp(video.currentTime));
      status.textContent = `${mode.value === 'range' ? 'OCR range' : 'Whole video'} · ${stamp(low)}–${stamp(high)} · current ${stamp(video.currentTime)}`;
    };
    video.addEventListener('loadedmetadata', () => { video.currentTime = start; update(); });
    video.addEventListener('seeking', () => {
      const [low, high] = bounds();
      if (video.currentTime < low) video.currentTime = low;
      else if (video.currentTime > high) video.currentTime = high;
      update();
    });
    video.addEventListener('play', () => {
      const [low, high] = bounds(); if (video.currentTime >= high || video.currentTime < low) video.currentTime = low;
    });
    video.addEventListener('timeupdate', () => {
      const [, high] = bounds();
      if (mode.value === 'range' && video.currentTime >= high) { video.pause(); if (video.currentTime > high) video.currentTime = high; }
      update();
    });
    video.addEventListener('error', () => { status.textContent = 'The local video could not be played. Open the source Vimeo video or try the OCR range page.'; });
    mode.addEventListener('change', () => { video.pause(); if (mode.value === 'range') video.currentTime = start; update(); });
    seek.addEventListener('input', () => { video.currentTime = Number(seek.value); update(); });
    document.querySelectorAll('[data-evidence-select]').forEach(select => {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'secondary'; button.textContent = 'Show this slide in video';
      button.addEventListener('click', () => {
        if (!/^\d+$/.test(select.value)) { status.textContent = 'Choose a source slide first.'; return; }
        const time = Number(select.value) * 3;
        const jump = () => {
          video.pause(); if (time < start || time > (requestedEnd ?? video.duration)) mode.value = 'whole';
          video.currentTime = time; update(); card.scrollIntoView({block: 'start', behavior: 'smooth'});
        };
        if (video.readyState >= 1) jump(); else { video.addEventListener('loadedmetadata', jump, {once: true}); video.load(); }
      });
      select.closest('.source-preview').append(button);
    });
  } catch (_) {
    status.textContent = 'The downloaded video is not available yet. Download it before reviewing playback here.';
  }
}
enableReviewVideoPlayer();


function requestDiscardReason(title) {
  return new Promise(resolve => {
    const dialog = document.createElement('dialog'); dialog.className = 'discard-service-dialog';
    dialog.innerHTML = '<form><h2 id="discard-service-heading">Discard service</h2><p data-discard-title></p><p class="muted">This removes the video from available downloads. You can restore it from Discarded services later.</p><label class="field" for="discard-service-reason"><span>Discard reason (required)</span><textarea id="discard-service-reason" name="discard-reason" rows="4" maxlength="2000" required></textarea></label><div class="actions"><button type="button" class="secondary" data-discard-cancel>Cancel</button><button type="submit">Discard service</button></div></form>';
    dialog.setAttribute('aria-labelledby', 'discard-service-heading');
    dialog.querySelector('[data-discard-title]').textContent = title;
    const input = dialog.querySelector('textarea');
    const finish = reason => { dialog.close(); dialog.remove(); resolve(reason); };
    dialog.querySelector('[data-discard-cancel]').addEventListener('click', () => finish(null));
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); });
    input.addEventListener('input', () => input.setCustomValidity(''));
    dialog.querySelector('form').addEventListener('submit', event => {
      event.preventDefault();
      const reason = input.value.trim();
      if (!reason) { input.setCustomValidity('Enter a discard reason.'); input.reportValidity(); return; }
      finish(reason);
    });
    document.body.append(dialog); dialog.showModal(); input.focus();
  });
}

function preserveImportLinkEntry(current, updated) {
  const discardedOpen = current.querySelector('#discarded-services')?.open;
  if (discardedOpen && updated.querySelector('#discarded-services')) updated.querySelector('#discarded-services').open = true;
  const value = current.querySelector('#links')?.value;
  const replacement = updated.querySelector('#links');
  if (replacement && value) {
    replacement.value = value;
    replacement.closest('details').open = true;
  }
}
document.addEventListener('submit', async event => {
  const form = event.target;
  if (!(form instanceof HTMLFormElement)) return;
  const action = form.querySelector('input[name="action"]')?.value;
  if (!['download-available', 'discard-available', 'restore-discarded'].includes(action)) return;
  event.preventDefault();
  if (form.dataset.submitting === 'true') return;
  form.dataset.submitting = 'true';
  const data = new FormData(form), button = form.querySelector('button');
  if (action === 'discard-available') {
    const title = form.closest('tr')?.querySelector('td:first-child a')?.textContent || 'this service';
    const reason = await requestDiscardReason(title);
    if (reason === null) { form.dataset.submitting = 'false'; return; }
    data.set('discard-reason', reason);
  }
  const label = button.textContent;
  button.disabled = true; button.textContent = action === 'download-available' ? 'Queueing…' : action === 'discard-available' ? 'Discarding…' : 'Restoring…';
  try {
    let response = await fetch('/imports', {method: 'POST', body: new URLSearchParams(data)});
    if (response.status === 403) {
      const fresh = await fetch(`/imports${location.search}`, {cache: 'no-store'});
      const page = new DOMParser().parseFromString(await fresh.text(), 'text/html');
      const token = page.querySelector('input[name="__anti-forgery-token"]')?.value;
      if (token) { data.set('__anti-forgery-token', token); response = await fetch('/imports', {method: 'POST', body: new URLSearchParams(data)}); }
    }
    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
    if (!response.ok) throw new Error(page.querySelector('.error')?.textContent || `The action could not be completed (HTTP ${response.status}). Refresh and retry.`);
    const queue = document.getElementById('import-queue'), updated = page.getElementById('import-queue');
    if (!updated) throw new Error('The action was requested, but its status could not be refreshed. Refresh the page to check.');
    preserveImportLinkEntry(queue, updated);
    queue.replaceChildren(...updated.childNodes); queue.dataset.processing = updated.dataset.processing;
    markQueuedVideos();
    if (!importProgressInFlight) { clearTimeout(importProgressTimer); importProgressTimer = setTimeout(refreshImportProgress, 0); }
  } catch (error) {
    let message = form.querySelector('[role="alert"]');
    if (!message) { message = document.createElement('p'); message.className = 'error'; message.setAttribute('role', 'alert'); form.append(message); }
    message.textContent = error.message; form.dataset.submitting = 'false'; button.disabled = false; button.textContent = label;
  }
});

// Merge cards determine form submission order, including externally associated inputs.
document.addEventListener('click', event => {
  const button = event.target.closest('[data-merge-move]');
  if (!button) return;
  const card = button.closest('[data-merge-part]');
  if (!card) return;
  const parent = card.parentElement;
  if (button.dataset.mergeMove === 'up' && card.previousElementSibling) {
    parent.insertBefore(card, card.previousElementSibling);
  } else if (button.dataset.mergeMove === 'down' && card.nextElementSibling) {
    parent.insertBefore(card.nextElementSibling, card);
  }
});

// Keep the editable final value in sync with the explicitly chosen source row.
document.querySelectorAll('#song-merge-form select[data-merge-field]').forEach(select => {
  const input = select.form.querySelector(`input[name="${select.dataset.mergeField}"]`);
  const update = () => {
    const option = select.selectedOptions[0];
    if (select.value !== 'custom') input.value = option.dataset.fieldValue;
    input.readOnly = select.value !== 'custom';
  };
  select.addEventListener('change', update);
  update();
});

// Excluded service rows cannot contribute performances to the final merge.
const serviceMergeForm = document.getElementById('service-merge-form');
if (serviceMergeForm) {
  const updateSources = () => {
    const included = new Set(Array.from(serviceMergeForm.querySelectorAll('input[name="source"]:checked'), input => input.value));
    serviceMergeForm.querySelectorAll('tr[data-service-source]').forEach(row => {
      const excluded = !included.has(row.dataset.serviceSource);
      row.querySelectorAll('input, select').forEach(input => { input.disabled = excluded; });
      row.hidden = excluded;
    });
  };
  serviceMergeForm.querySelectorAll('input[name="source"]').forEach(input => input.addEventListener('change', updateSources));
  updateSources();
}

if (serviceMergeForm) {
  const merges = Array.from(serviceMergeForm.querySelectorAll('select[data-performance-merge]'));
  const fieldOptions = new Map(Array.from(serviceMergeForm.querySelectorAll('select[data-performance-field]'), select => [select, Array.from(select.options)]));
  const updatePerformanceFields = () => {
    const destinations = new Map(merges.filter(select => !select.disabled && select.value).map(select => [select.dataset.performanceMerge, select.value]));
    // Move an entire merge group when its destination is merged into another row.
    destinations.forEach((target, source) => {
      const seen = new Set([source]);
      while (destinations.has(target) && !seen.has(target)) {
        seen.add(target);
        target = destinations.get(target);
      }
      if (!seen.has(target)) destinations.set(source, target);
    });
    merges.forEach(select => {
      const source = select.dataset.performanceMerge;
      if (destinations.has(source)) select.value = destinations.get(source);
      const keep = select.closest('tr').querySelector('input[name="performance"]');
      if (!select.disabled && destinations.has(source)) keep.checked = false;
      if (!select.disabled && Array.from(destinations.values()).includes(source)) keep.checked = true;
      // A merged row cannot be a destination; choose the final kept row instead.
      Array.from(select.options).forEach(option => {
        const targetSelect = merges.find(other => other.dataset.performanceMerge === option.value);
        option.disabled = !!option.value && (!targetSelect || targetSelect.disabled || destinations.has(option.value));
      });
    });
    serviceMergeForm.querySelectorAll('[data-performance-fields]').forEach(card => {
      const target = card.dataset.performanceFields;
      card.hidden = destinations.has(target) || !Array.from(destinations.values()).includes(target);
    });
    serviceMergeForm.querySelectorAll('select[data-performance-field]').forEach(select => {
      const target = select.dataset.performanceField;
      const card = select.closest('[data-performance-fields]');
      select.disabled = card.hidden;
      const included = new Set(Array.from(serviceMergeForm.querySelectorAll('input[name="source"]:checked'), input => input.value));
      const selected = select.value;
      const allowed = fieldOptions.get(select).filter(option =>
        (option.value === target || destinations.get(option.value) === target) &&
        (!option.dataset.serviceSource || included.has(option.dataset.serviceSource)));
      select.replaceChildren(...allowed);
      select.value = allowed.some(option => option.value === selected) ? selected : target;
      const output = select.closest('label').querySelector('[data-picked-value]');
      if (output) output.textContent = select.selectedOptions[0]?.textContent || '';
    });
    merges.forEach(select => {
      const row = select.closest('tr');
      const order = row.querySelector('input[type="number"]');
      order.disabled = row.hidden || destinations.has(select.dataset.performanceMerge);
    });
  };
  serviceMergeForm.querySelectorAll('select[data-performance-field]').forEach(select => select.addEventListener('change', updatePerformanceFields));
  merges.forEach(select => select.addEventListener('change', () => {
    if (select.value) {
      select.closest('tr').querySelector('input[name="performance"]').checked = false;
      const target = Array.from(serviceMergeForm.querySelectorAll('input[name="performance"]')).find(input => input.value === select.value);
      if (target) target.checked = true;
    }
    updatePerformanceFields();
  }));
  serviceMergeForm.querySelectorAll('input[name="performance"]').forEach(input => input.addEventListener('change', updatePerformanceFields));
  serviceMergeForm.querySelectorAll('input[name="source"]').forEach(input => input.addEventListener('change', updatePerformanceFields));
  updatePerformanceFields();
}

// Bulk selection applies only to eligible cleanup rows.
document.querySelectorAll('[data-unused-select-all]').forEach(all => {
  const rows = Array.from(all.form.querySelectorAll('input[name="selected"]:not(:disabled)'));
  all.addEventListener('change', () => rows.forEach(row => { row.checked = all.checked; }));
  rows.forEach(row => row.addEventListener('change', () => {
    all.checked = rows.length > 0 && rows.every(input => input.checked);
    all.indeterminate = rows.some(input => input.checked) && !all.checked;
  }));
});
