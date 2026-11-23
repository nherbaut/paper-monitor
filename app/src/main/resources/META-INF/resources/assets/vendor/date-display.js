(() => {
    const timeZone = document.querySelector('meta[name="application-time-zone"]').content;
    const formatter = new Intl.DateTimeFormat("en-GB", {
        timeZone, year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", hourCycle: "h23"
    });
    window.formatApplicationDate = (value) => {
        if (!value) return "";
        // Date-only metadata has no time or timezone to convert.
        if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
        // External Scholar history can omit an offset. Keep that wall time in the
        // application zone instead of interpreting it in the browser's zone.
        const local = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/.exec(value);
        if (local) return `${local[1]} ${local[2]}-${local[3]}`;
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        const parts = Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
        return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}-${parts.minute}`;
    };
})();
