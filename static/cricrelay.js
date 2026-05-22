/**
 * CricRelay marketing shell — minimal JS for fast first paint.
 */
(function () {
  "use strict";

  var STORAGE_KEY = "cricrelay-color-mode";
  var THEME_META = "#cr-theme-color-meta";
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function getStoredColorMode() {
    try {
      var m = localStorage.getItem(STORAGE_KEY);
      if (m === "light" || m === "dark") return m;
    } catch (e) {}
    return null;
  }

  function resolveColorMode() {
    var stored = getStoredColorMode();
    if (stored) return stored;
    if (document.body && document.body.classList.contains("cr-theme-light")) {
      return "light";
    }
    return "dark";
  }

  function applyColorMode(mode, persist) {
    var light = mode === "light";
    document.documentElement.setAttribute("data-color-mode", light ? "light" : "dark");
    document.body.classList.toggle("cr-theme-light", light);
    var meta = document.querySelector(THEME_META);
    if (meta) meta.setAttribute("content", light ? "#f3f7ff" : "#070b14");
    if (persist) {
      try {
        localStorage.setItem(STORAGE_KEY, light ? "light" : "dark");
      } catch (e) {}
    }
    document.querySelectorAll("[data-cr-theme-toggle]").forEach(function (btn) {
      btn.setAttribute("aria-pressed", light ? "true" : "false");
      btn.setAttribute("aria-label", light ? "Switch to dark mode" : "Switch to light mode");
      btn.setAttribute("title", light ? "Dark mode" : "Light mode");
    });
  }

  applyColorMode(resolveColorMode(), false);

  document.querySelectorAll("[data-cr-theme-toggle]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var next = document.documentElement.getAttribute("data-color-mode") === "light" ? "dark" : "light";
      applyColorMode(next, true);
    });
  });

  /* Scroll reveal */
  if (!reduceMotion && "IntersectionObserver" in window) {
    var reveals = document.querySelectorAll(".cr-reveal");
    if (reveals.length) {
      var io = new IntersectionObserver(
        function (entries) {
          entries.forEach(function (e) {
            if (e.isIntersecting) {
              e.target.classList.add("cr-reveal--visible");
              io.unobserve(e.target);
            }
          });
        },
        { rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
      );
      reveals.forEach(function (el) {
        io.observe(el);
      });
    }
  } else {
    document.querySelectorAll(".cr-reveal").forEach(function (el) {
      el.classList.add("cr-reveal--visible");
    });
  }

  /* Header shadow on scroll */
  var header = document.querySelector(".cr-header");
  if (header && !reduceMotion) {
    var onScroll = function () {
      header.classList.toggle("cr-header--raised", window.scrollY > 8);
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  /* Mobile nav drawer */
  var toggle = document.querySelector("[data-cr-nav-toggle]");
  var panel = document.getElementById("cr-nav-drawer");
  if (toggle && panel) {
    toggle.addEventListener("click", function () {
      var open = panel.classList.toggle("cr-nav-drawer--open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      panel.setAttribute("aria-hidden", open ? "false" : "true");
      document.body.classList.toggle("cr-nav-open", open);
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && panel.classList.contains("cr-nav-drawer--open")) {
        panel.classList.remove("cr-nav-drawer--open");
        toggle.setAttribute("aria-expanded", "false");
        panel.setAttribute("aria-hidden", "true");
        document.body.classList.remove("cr-nav-open");
      }
    });
    panel.querySelectorAll("a").forEach(function (a) {
      a.addEventListener("click", function () {
        panel.classList.remove("cr-nav-drawer--open");
        toggle.setAttribute("aria-expanded", "false");
        panel.setAttribute("aria-hidden", "true");
        document.body.classList.remove("cr-nav-open");
      });
    });
  }

  /* Generic copy button: <button data-copy-text=\"...\">Copy</button> */
  document.querySelectorAll("[data-copy-text]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var text = btn.getAttribute("data-copy-text") || "";
      if (!text) return;
      var original = btn.textContent;
      var done = function (ok) {
        btn.textContent = ok ? "Copied" : "Copy failed";
        setTimeout(function () {
          btn.textContent = original;
        }, 1300);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(
          function () { done(true); },
          function () { done(false); }
        );
        return;
      }
      done(false);
    });
  });
})();
