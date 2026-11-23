/* Canonical source: shared/theme. Run scripts/sync-theme-assets.py after editing. */
(() => {
    const key = "paper-monitor.theme";
    const root = document.documentElement;
    const valid = value => value === "dark" || value === "light";
    const read = () => { try { return localStorage.getItem(key); } catch (_) { return null; } };
    const write = value => { try { localStorage.setItem(key, value); } catch (_) { /* Private/blocked storage: keep this page usable. */ } };
    const incoming = new URL(location.href).searchParams.get("theme");
    let theme = valid(incoming) ? incoming : read();
    if (!valid(theme)) theme = "light";
    if (valid(incoming)) {
        write(theme);
        const url = new URL(location.href);
        url.searchParams.delete("theme");
        history.replaceState(history.state, "", url);
    }
    function refresh() {
        document.body?.classList.toggle("dark-mode", theme === "dark");
        document.querySelectorAll("[data-theme-toggle]").forEach(button => {
            const label = button.querySelector("[data-theme-label]") || button;
            label.textContent = theme === "dark" ? "Light mode" : "Dark mode";
            button.setAttribute("aria-pressed", String(theme === "dark"));
        });
        document.querySelectorAll("a[data-pde-link]").forEach(link => {
            const url = new URL(link.href, location.href);
            url.searchParams.set("theme", theme);
            link.href = url.href;
        });
    }
    function apply(value, persist) {
        if (!valid(value)) return;
        theme = value;
        root.dataset.theme = value;
        root.dataset.bsTheme = value;
        if (persist) write(value);
        refresh();
        window.dispatchEvent(new CustomEvent("paper-theme-change", {detail: {theme}}));
    }
    window.PaperTheme = {get: () => theme, set: value => apply(value, true),
        toggle: () => apply(theme === "dark" ? "light" : "dark", true)};
    apply(theme, false);
    document.addEventListener("DOMContentLoaded", refresh);
    document.addEventListener("click", event => {
        if (event.target.closest("[data-theme-toggle]")) window.PaperTheme.toggle();
    });
    window.addEventListener("storage", event => {
        if (event.key === key || event.key === null) apply(valid(event.newValue) ? event.newValue : "light", false);
    });
    // Links may also be inserted by review/template tools after page load.
    document.addEventListener("click", event => {
        const link = event.target.closest("a[data-pde-link]");
        if (link) { const url = new URL(link.href); url.searchParams.set("theme", theme); link.href = url.href; }
    }, true);
})();
