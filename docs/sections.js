/*
 * Collapsible sections for the docs pages: every <h2> inside <main> (and inside
 * the glossary's rendered root) becomes the summary of a native <details>
 * element wrapping the section's content, up to the next <h2> or the footer.
 * Authors keep writing flat h2-delimited pages; this script adds the folding.
 *
 * Sections start expanded. Headings get stable ids (for deep links), navigating
 * to an anchor inside a collapsed section re-expands it, and printing expands
 * everything. Must be included AFTER glossary.js, so the glossary page's groups
 * are already rendered when this runs.
 */
"use strict";

function soctSectionSlug(text) {
  const base = "s-" + text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  let id = base;
  for (let n = 2; document.getElementById(id); n++) id = base + "-" + n;
  return id;
}

/** Wrap each h2-delimited section among `host`'s direct children in <details>. */
function soctSectionizeHost(host) {
  const sections = [];
  let current = null;
  for (const child of [...host.children]) {
    if (child.tagName === "H2") {
      current = { h2: child, body: [] };
      sections.push(current);
    } else if (child.tagName === "FOOTER" || child.tagName === "SCRIPT") {
      current = null;
    } else if (current) {
      current.body.push(child);
    }
  }
  for (const s of sections) {
    if (!s.h2.id) s.h2.id = soctSectionSlug(s.h2.textContent);
    const details = document.createElement("details");
    details.className = "sec";
    details.open = true;
    const summary = document.createElement("summary");
    host.insertBefore(details, s.h2);
    summary.appendChild(s.h2);
    details.appendChild(summary);
    for (const el of s.body) details.appendChild(el);
  }
  return sections.length;
}

/** Expand every collapsed ancestor section of the current hash target. */
function soctExpandForHash() {
  if (!location.hash) return;
  const el = document.getElementById(decodeURIComponent(location.hash.slice(1)));
  if (!el) return;
  let expanded = false;
  for (let d = el.closest("details.sec"); d; d = d.parentElement && d.parentElement.closest("details.sec")) {
    if (!d.open) { d.open = true; expanded = true; }
  }
  if (expanded) el.scrollIntoView();
}

document.addEventListener("DOMContentLoaded", () => {
  const hosts = [document.querySelector("main"), document.getElementById("glossary-root")]
    .filter(Boolean);
  let count = 0;
  for (const host of hosts) count += soctSectionizeHost(host);
  if (count === 0) return;

  // Expand/collapse-all control, only where there is enough to fold.
  if (count >= 3) {
    const controls = document.createElement("p");
    controls.className = "sec-controls";
    const mk = (label, open) => {
      const a = document.createElement("a");
      a.href = "#";
      a.textContent = label;
      a.addEventListener("click", (e) => {
        e.preventDefault();
        document.querySelectorAll("details.sec").forEach((d) => { d.open = open; });
      });
      return a;
    };
    controls.appendChild(mk("Expand all", true));
    controls.appendChild(document.createTextNode(" · "));
    controls.appendChild(mk("Collapse all", false));
    const h1 = document.querySelector("main h1");
    if (h1) h1.insertAdjacentElement("afterend", controls);
  }

  window.addEventListener("hashchange", soctExpandForHash);
  soctExpandForHash();

  // Print with everything visible, then restore the reader's fold state.
  let folded = [];
  window.addEventListener("beforeprint", () => {
    folded = [...document.querySelectorAll("details.sec:not([open])")];
    folded.forEach((d) => { d.open = true; });
  });
  window.addEventListener("afterprint", () => {
    folded.forEach((d) => { d.open = false; });
    folded = [];
  });
});
