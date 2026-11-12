'use strict';
document.querySelectorAll('.password-toggle').forEach(button => {
    const input = document.getElementById(button.dataset.target);
    if (!input) return;
    button.hidden = false;
    button.addEventListener('click', () => {
        const visible = input.type === 'password';
        input.type = visible ? 'text' : 'password';
        button.setAttribute('aria-pressed', String(visible));
        button.setAttribute('aria-label', visible ? button.dataset.hide : button.dataset.show);
        button.title = visible ? button.dataset.hide : button.dataset.show;
    });
});


document.querySelectorAll('[data-repeatable]').forEach(section => {
    const entries = section.querySelector('[data-entries]');
    const add = section.querySelector('[data-add]');
    let nextId = entries.children.length;
    const update = () => { add.disabled = entries.children.length >= 50; };
    section.querySelectorAll('[data-remove]').forEach(button => { button.hidden = false; });
    add.hidden = false;
    add.addEventListener('click', () => {
        if (entries.children.length >= 50) return;
        const row = section.querySelector('template').content.cloneNode(true);
        const input = row.querySelector('input');
        input.id = `${input.name}-${nextId++}`;
        row.querySelector('label').htmlFor = input.id;
        entries.append(row);
        input.focus();
        update();
    });
    entries.addEventListener('click', event => {
        const remove = event.target.closest('[data-remove]');
        if (!remove) return;
        const row = remove.closest('.entry-row');
        const target = row.nextElementSibling || row.previousElementSibling;
        row.remove();
        update();
        (target ? target.querySelector('input') : add).focus();
    });
    update();
});

const photos = document.getElementById('photos');
if (photos) {
    const uploader = photos.closest('.photo-uploader');
    const dropzone = uploader.querySelector('.upload-dropzone');
    const preview = document.getElementById('photo-preview');
    const error = document.getElementById('photos-error');
    let selected = Array.from(photos.files);
    let previewUrls = [];
    uploader.classList.add('is-enhanced');
    uploader.querySelector('[data-upload-drag]').hidden = false;
    const syncFiles = () => {
        const transfer = new DataTransfer();
        selected.forEach(file => transfer.items.add(file));
        photos.files = transfer.files;
    };
    const clearPreviews = () => {
        previewUrls.forEach(url => URL.revokeObjectURL(url));
        previewUrls = [];
        preview.replaceChildren();
    };
    const render = () => {
        clearPreviews();
        selected.forEach((file, index) => {
            const tile = document.getElementById('photo-template').content.cloneNode(true);
            const img = tile.querySelector('img');
            img.src = URL.createObjectURL(file);
            previewUrls.push(img.src);
            img.alt = file.name;
            const caption = tile.querySelector('figcaption');
            caption.textContent = file.name;
            caption.title = file.name;
            const remove = tile.querySelector('button');
            remove.setAttribute('aria-label', `${preview.dataset.removeLabel} ${file.name}`);
            remove.addEventListener('click', () => {
                selected.splice(index, 1);
                syncFiles();
                render();
                error.hidden = true;
                const buttons = preview.querySelectorAll('button');
                (buttons[Math.min(index, buttons.length - 1)] || photos).focus();
            });
            preview.append(tile);
        });
    };
    const addFiles = files => {
        const additions = Array.from(files).filter(file => !selected.some(existing =>
            existing.name === file.name && existing.size === file.size && existing.lastModified === file.lastModified));
        const valid = selected.length + additions.length <= 5 && additions.every(file =>
            file.size > 0 && file.size <= 5 * 1024 * 1024 && ['image/jpeg', 'image/png'].includes(file.type));
        error.hidden = valid;
        error.textContent = valid ? '' : error.dataset.message;
        if (valid) selected.push(...additions);
        syncFiles();
        render();
    };
    photos.addEventListener('change', () => addFiles(photos.files));
    // Keep drops from opening a file in the browser when a user misses the upload area.
    uploader.addEventListener('dragover', event => event.preventDefault());
    uploader.addEventListener('drop', event => event.preventDefault());
    dropzone.addEventListener('dragover', event => {
        event.preventDefault();
        dropzone.classList.add('is-dragging');
    });
    dropzone.addEventListener('dragleave', event => {
        if (!dropzone.contains(event.relatedTarget)) dropzone.classList.remove('is-dragging');
    });
    dropzone.addEventListener('drop', event => {
        event.preventDefault();
        dropzone.classList.remove('is-dragging');
        addFiles(event.dataTransfer.files);
    });
    // A page restored from the browser's back/forward cache still needs its previews.
    window.addEventListener('pagehide', clearPreviews);
    window.addEventListener('pageshow', render);
}

document.querySelectorAll('[data-character-count]').forEach(input => {
    const output = document.querySelector(`[data-count-for="${input.id}"]`);
    const format = new Intl.NumberFormat(document.documentElement.lang || 'fr');
    const update = () => { output.textContent = `${format.format(input.value.length)} / ${format.format(input.maxLength)}`; };
    input.addEventListener('input', update);
    update();
});

[['name', '[data-instrument-name]'], ['brand', '[data-instrument-brand]']].forEach(([id, selector]) => {
    const input = document.getElementById(id);
    const output = document.querySelector(selector);
    if (!input || !output) return;
    const update = () => { output.textContent = input.value.trim() || output.dataset.fallback; };
    input.addEventListener('input', update);
    update();
});

const editorSections = document.querySelectorAll('.editor-card');
if (editorSections.length && 'IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
        const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (!visible.length) return;
        document.querySelectorAll('.editor-outline a').forEach(link => {
            if (link.hash === `#${visible[0].target.id}`) link.setAttribute('aria-current', 'location');
            else link.removeAttribute('aria-current');
        });
    }, { rootMargin: '-5% 0px -50% 0px', threshold: 0 });
    editorSections.forEach(section => observer.observe(section));
}

document.querySelector('[data-form-error]')?.focus();

// Native disclosure menus remain usable when JavaScript is unavailable.
const vaultNavMenus = Array.from(document.querySelectorAll('.site-nav .nav-menu'));
vaultNavMenus.forEach(menu => {
    menu.addEventListener('toggle', () => {
        if (menu.open) vaultNavMenus.filter(other => other !== menu).forEach(other => { other.open = false; });
    });
    menu.addEventListener('keydown', event => {
        if (event.key === 'Escape' && menu.open) {
            menu.open = false;
            menu.querySelector('summary').focus();
        }
    });
    menu.querySelectorAll('a').forEach(link => link.addEventListener('click', () => { menu.open = false; }));
});
document.addEventListener('click', event => {
    vaultNavMenus.filter(menu => !menu.contains(event.target)).forEach(menu => { menu.open = false; });
});

const bandGenresInput = document.querySelector('[data-band-genres]');
const bandGenresPreview = document.querySelector('[data-band-genre-preview]');
if (bandGenresInput && bandGenresPreview) {
    const renderGenres = () => {
        bandGenresPreview.replaceChildren();
        const seen = new Set();
        bandGenresInput.value.split(',').forEach(value => {
            const genre = value.trim().replace(/\s+/g, ' ');
            const key = genre.toLowerCase();
            if (!genre || seen.has(key)) return;
            seen.add(key);
            const tag = document.createElement('span');
            tag.className = 'genre-tag';
            tag.setAttribute('role', 'listitem');
            tag.textContent = genre;
            bandGenresPreview.append(tag);
        });
    };
    bandGenresInput.addEventListener('input', renderGenres);
    renderGenres();
}

const connectionTypeInput = document.getElementById('connection-type');
if (connectionTypeInput) {
    const updateConcertFields = () => {
        const sharedShow = connectionTypeInput.value === 'PLAYED_TOGETHER';
        ['connection-date', 'connection-venue'].forEach(id => {
            const input = document.getElementById(id);
            if (!input) return;
            input.disabled = !sharedShow;
            input.parentElement.hidden = !sharedShow;
        });
    };
    connectionTypeInput.addEventListener('change', updateConcertFields);
    updateConcertFields();
}

const calendarRecurrence = document.getElementById('event-recurrence');
const calendarRepeatUntil = document.getElementById('event-until');
if (calendarRecurrence && calendarRepeatUntil) {
    const updateCalendarRecurrence = () => {
        const weekly = calendarRecurrence.value === 'WEEKLY';
        calendarRepeatUntil.disabled = !weekly;
        calendarRepeatUntil.required = weekly;
        calendarRepeatUntil.parentElement.hidden = !weekly;
    };
    calendarRecurrence.addEventListener('change', updateCalendarRecurrence);
    updateCalendarRecurrence();
}

const notificationBell = document.querySelector('[data-notification-bell]');
if (notificationBell) {
    const badge = notificationBell.querySelector('[data-notification-count]');
    const announcement = notificationBell.querySelector('[data-notification-announcement]');
    const numberFormat = new Intl.NumberFormat(document.documentElement.lang || 'fr');
    let stopped = false;
    let loading = false;
    const refreshNotifications = async () => {
        if (stopped || loading || document.visibilityState !== 'visible') return;
        loading = true;
        try {
            const response = await fetch('/notifications/unread-count', { headers: { Accept: 'application/json' }, cache: 'no-store' });
            if (response.redirected || response.status === 401 || response.status === 403) { stopped = true; return; }
            if (!response.ok) return;
            const data = await response.json();
            if (!Number.isSafeInteger(data.unread) || data.unread < 0) return;
            if (notificationBell.dataset.unread === String(data.unread)) return;
            notificationBell.dataset.unread = String(data.unread);
            badge.hidden = data.unread === 0;
            badge.textContent = data.unread > 99 ? '99+' : numberFormat.format(data.unread);
            const label = notificationBell.dataset.notificationLabel.replace('{count}', numberFormat.format(data.unread));
            notificationBell.setAttribute('aria-label', label);
            announcement.textContent = label;
        } catch { /* The server-rendered bell remains usable if polling fails. */ }
        finally { loading = false; }
    };
    setInterval(refreshNotifications, 30000);
    document.addEventListener('visibilitychange', refreshNotifications);
    window.addEventListener('pageshow', event => { if (event.persisted) refreshNotifications(); });
}

// Native details keep navigation usable before JavaScript loads and without it.
const workspaceNavigation = document.querySelector('[data-workspace-nav]');
if (workspaceNavigation) {
    const mobileNavigation = window.matchMedia('(max-width: 900px)');
    const resizeNavigation = () => { workspaceNavigation.open = !mobileNavigation.matches; };
    resizeNavigation();
    mobileNavigation.addEventListener('change', resizeNavigation);
    workspaceNavigation.addEventListener('keydown', event => {
        if (event.key === 'Escape' && mobileNavigation.matches && workspaceNavigation.open) {
            workspaceNavigation.open = false;
            workspaceNavigation.querySelector('summary').focus();
        }
    });
}

// Progressive enhancement: every passport section remains readable without JS.
const passport = document.querySelector('[data-passport-tabs]');
if (passport) {
    const tabs = [...passport.querySelectorAll('[data-passport-tab]')];
    const panels = [...passport.querySelectorAll('[data-passport-panel]')];
    passport.querySelector('.passport-tabs').setAttribute('role', 'tablist');
    tabs.forEach(tab => {
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-controls', tab.hash.slice(1));
    });
    panels.forEach(panel => {
        panel.setAttribute('role', 'tabpanel');
        panel.setAttribute('aria-labelledby', tabs.find(tab => tab.hash === '#' + panel.id).id);
        panel.tabIndex = 0;
    });
    const selectTab = (tab, focus = false) => {
        tabs.forEach(item => {
            const selected = item === tab;
            item.setAttribute('aria-selected', String(selected));
            item.tabIndex = selected ? 0 : -1;
        });
        panels.forEach(panel => { panel.hidden = '#' + panel.id !== tab.hash; });
        if (focus) tab.focus();
    };
    const fromHash = () => selectTab(tabs.find(tab => tab.hash === location.hash) || tabs[0]);
    tabs.forEach((tab, index) => {
        tab.addEventListener('click', event => {
            event.preventDefault();
            selectTab(tab);
            if (location.hash !== tab.hash) history.pushState(null, '', tab.hash);
        });
        tab.addEventListener('keydown', event => {
            let next;
            if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
            if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
            if (event.key === 'Home') next = 0;
            if (event.key === 'End') next = tabs.length - 1;
            if (next === undefined) return;
            event.preventDefault();
            selectTab(tabs[next], true);
            history.replaceState(null, '', tabs[next].hash);
        });
    });
    window.addEventListener('hashchange', fromHash);
    window.addEventListener('popstate', fromHash);
    fromHash();
}

const revealAnchor = () => {
    if (!location.hash) return;
    const target = document.getElementById(location.hash.slice(1));
    if (!target) return;
    let node = target;
    while (node) {
        if (node.tagName === 'DETAILS') node.open = true;
        node = node.parentElement;
    }
    if (!target.closest('[data-passport-tabs]')) target.scrollIntoView();
};
window.addEventListener('hashchange', revealAnchor);
revealAnchor();
