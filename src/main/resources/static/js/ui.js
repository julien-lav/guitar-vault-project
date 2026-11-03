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
