/**
 * CricRelay marketing shell — minimal JS for fast first paint.
 */
(function () {
  "use strict";

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

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
    panel.querySelectorAll("a").forEach(function (a) {
      a.addEventListener("click", function () {
        panel.classList.remove("cr-nav-drawer--open");
        toggle.setAttribute("aria-expanded", "false");
        panel.setAttribute("aria-hidden", "true");
        document.body.classList.remove("cr-nav-open");
      });
    });
  }
})();
