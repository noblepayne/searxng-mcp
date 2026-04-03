# SearXNG MCP Server

Babashka Streamable HTTP MCP server for [SearXNG](https://github.com/searxng/searxng) web search and URL-to-markdown conversion.

[SearXNG](https://searxng.org) is a free, privacy-respecting metasearch engine that aggregates results from up to 250 search services without tracking or profiling users.

## Quick Start

```bash
# Run (OS-assigned port)
bb run

# Or use bb.edn tasks
bb start     # Start on port 3009
bb test      # Run tests
bb lint      # Lint with clj-kondo
bb health    # Health check
```

## Configuration

| Env Var | Default | Description |
|---------|---------|-------------|
| `SEARXNG_URL` | `http://prism:8888` | Your SearXNG instance URL |
| `SEARXNG_MCP_PORT` | `3009` | Server port |
| `SEARXNG_MCP_HOST` | `127.0.0.1` | Host to bind to |
| `JINA_API_KEY` | _(none)_ | Optional Jina Reader API key for authenticated URL fetching |

## Available Tools (4)

| Tool | Description |
|------|-------------|
| `search` | Search the web via SearXNG, returns formatted markdown results with title, URL, snippet, engine, and score |
| `read_url` | Fetch any URL and convert to LLM-friendly markdown (4-tier fallback: markdown.new → Jina Reader → skim/Mozilla Readability → local HTML parser) |
| `read_urls` | Batch fetch up to 5 URLs in one call, saving agent round trips |
| `http_request` | Raw HTTP GET — returns status, content-type, and raw body (no markdown conversion). Use for APIs, JSON endpoints, source files |

## URL Reader Fallback Chain

1. **markdown.new** — `GET https://markdown.new/api/convert?url=<url>` (500/day per IP, no key)
1. **Jina Reader** — `GET https://r.jina.ai/<url>` (free tier, optional API key for higher limits)
1. **skim (Mozilla Readability)** — jsoup → hickory → markdown via [skim](https://github.com/noblepayne/skim) (bb-native, unlimited)
1. **Local regex stripper** — strips script/style, converts basic HTML to markdown (unlimited, always works)

## mcp-injector Integration

Add to `mcp-servers.edn`:

```clojure
{:servers
 {:searxng
  {:url "http://127.0.0.1:3009/mcp"
   :tools ["search" "read_url" "read_urls" "http_request"]}}}
```

## Nix

### Run directly

```bash
nix run github:JupiterBroadcasting/searxng-mcp
```

### NixOS Module

```nix
services.searxng-mcp = {
  enable = true;
  port = 3009;
  host = "127.0.0.1";
  searxngUrl = "http://prism:8888";
  # jinaApiKeyFile = "/run/secrets/jina-api-key";  # optional
};
```

### Dev shell

```bash
nix develop
```

## Testing

```bash
bb test
```

Tests run a real in-process server with simulated HTTP requests — no mocks, no external dependencies.

## Architecture

Everything lives in `searxng_mcp.bb` — single file, intentionally:

```
Configuration / env vars
│
SearXNG HTTP client (searxng-search!)
│
HTML → Markdown (html->markdown, regex fallback)
│
URL Reader (4-tier: markdown.new → Jina → skim → local)
│
Tool implementations (tool-search, tool-read-url, tool-read-urls)
│
Tool registry (tools vector — schemas the LLM sees)
│
Tool dispatch (case on name → tool-*)
│
JSON-RPC handlers (handle-initialize, handle-tools-list, handle-tools-call)
│
HTTP server (http-kit, handler, handle-mcp)
│
Entry point (-main)
```

## v1.5 (Planned)

- Add `read_urls` concurrency controls (max parallel, per-URL timeouts)
- Consider `search_and_read` composite tool (search + auto-read top N results)

## Inspiration

The URL reader's multi-tier fallback chain (markdown.new → Jina Reader → skim → local) was
inspired by the approach in [ihor-sokoliuk/mcp-searxng](https://github.com/ihor-sokoliuk/mcp-searxng).

## License

MIT
