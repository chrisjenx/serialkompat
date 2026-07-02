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
  function resolveTheme() {
    var palette = document.body ? document.body.getAttribute("data-md-color-scheme") : null;
    return palette === "slate" ? "dark" : "default";
  }

  async function renderMermaidBlocks() {
    if (typeof mermaid === "undefined") {
      return;
    }

    var blocks = document.querySelectorAll(".mermaid-diagram:not([data-mermaid-rendered])");
    if (blocks.length === 0) {
      return;
    }

    mermaid.initialize({ startOnLoad: false, theme: resolveTheme() });

    var nodes = [];
    blocks.forEach(function (block) {
      var code = block.querySelector("code");
      var source = code ? code.textContent : block.textContent;
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
