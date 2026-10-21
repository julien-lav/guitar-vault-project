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
