// Self-hosted Mermaid rendering for the serialkompat docs site.
//
// Material's built-in Mermaid integration loads mermaid at runtime from
// unpkg and auto-renders `.mermaid` blocks. In practice that glue replaces
// `<pre class="mermaid"><code>...</code></pre>` with an empty
// `<div class="mermaid"></div>` before mermaid ever sees the source, so
// nothing renders. To avoid both that bug and the runtime CDN dependency,
// we vendor mermaid ourselves (mermaid.min.js) and drive rendering here.
//
// mkdocs.yml renames the superfences custom_fence class to
// "mermaid-diagram" so Material's own auto-init (which only looks for
// class="mermaid") never touches these blocks and the source text is
// preserved untouched in the built HTML as:
//   <pre class="mermaid-diagram"><code>...diagram source...</code></pre>
(function () {
  // Mermaid's own "dark" theme draws grey clusters and grey edge-label boxes
  // with light text, which reads poorly on slate. Under slate, use "base" with
  // the site's slate tokens (see pipeline.css): text #eceef2 is >= 11:1 on
  // every fill below.
  var SLATE = {
    darkMode: true,
    background: "#1e2129",
    fontFamily: "Inter, -apple-system, BlinkMacSystemFont, Helvetica, Arial, sans-serif",
    fontSize: "15px",
    primaryColor: "#2b2e3b",
    primaryTextColor: "#eceef2",
    primaryBorderColor: "#7b8296",
    secondaryColor: "#2b2e3b",
    tertiaryColor: "#262833",
    lineColor: "#c4c8d2",
    textColor: "#eceef2",
    clusterBkg: "#23262f",
    clusterBorder: "#4b5063",
    titleColor: "#eceef2",
    edgeLabelBackground: "#1e2129",
  };

  function mermaidConfig() {
    var palette = document.body ? document.body.getAttribute("data-md-color-scheme") : null;
    return palette === "slate"
      ? { startOnLoad: false, theme: "base", themeVariables: SLATE }
      : { startOnLoad: false, theme: "default" };
  }

  async function renderMermaidBlocks() {
    if (typeof mermaid === "undefined") {
      return;
    }

    var blocks = document.querySelectorAll(".mermaid-diagram:not([data-mermaid-rendered])");
    if (blocks.length === 0) {
      return;
    }

    mermaid.initialize(mermaidConfig());

    var nodes = [];
    blocks.forEach(function (block) {
      var code = block.querySelector("code");
      var source = (code ? code.textContent : block.textContent) || "";
      if (!source.trim()) return; // nothing to render; leave it alone (no console noise)
      block.textContent = source;
      block.classList.add("mermaid");
      block.setAttribute("data-mermaid-rendered", "true");
      nodes.push(block);
    });

    if (nodes.length > 0) {
      await mermaid.run({ nodes: nodes });
    }
  }

  document.addEventListener("DOMContentLoaded", renderMermaidBlocks);

  // Material's instant-navigation feature (if ever enabled) swaps page
  // content without a full reload; document$ fires on every page change.
  if (typeof document$ !== "undefined" && document$.subscribe) {
    document$.subscribe(renderMermaidBlocks);
  }
})();
