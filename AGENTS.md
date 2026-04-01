# AGENTS.md — searxng-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

A Babashka Streamable HTTP MCP server wrapping SearXNG search and URL-to-markdown conversion. Provides web search and content extraction tools for AI agents.

**Transport:** MCP Streamable HTTP (spec `2025-03-26`)
**Endpoint:** Single `/mcp` POST + `/health`
**Compatible with:** mcp-injector `{:searxng {:url "http://127.0.0.1:PORT/mcp"}}`

## Project Structure

```
searxng-mcp/
├── searxng_mcp.bb              # Single-file MCP server (the whole thing)
├── bb.edn                      # Tasks: run, start, test, lint, health
├── flake.nix                   # Nix package + NixOS service module
├── README.md                   # This file
├── AGENTS.md                   # This file (philosophy & workflows)
└── tests/
    └── test_searxng_mcp.clj    # Integration tests (real in-process server)
```

## Workflow

- **Test-driven** — tests guide development, write tests that verify real client usage
- **Integration tests only** — real in-process http-kit server, no mocks
- **Clean lint** — no warnings tolerated (`clj-kondo`)
- **Formatting** — uniform across all types:
  - Clojure/Babashka: `nix run nixpkgs#cljfmt -- fix <file>`
  - Markdown: `nix run nixpkgs#mdformat -- <file>`
  - Nix: `nix fmt .`
  - EDN: `clojure.pprint`
- **Feature branches** — commit often as snapshots, rewrite history later
- **Docs up to date** — update before commit
- **Keep bb.edn current** — tasks mirror actual commands

## Running

```bash
# Dev — OS-assigned port, logs JSON startup line
bb run

# Dev — fixed port 3009 (or SEARXNG_MCP_PORT)
bb start

# Health check
bb health
```

Config via env vars:

```bash
export SEARXNG_URL="http://prism:8888"   # your SearXNG instance
export SEARXNG_MCP_PORT="3009"           # server port (default: 3009)
export JINA_API_KEY="..."                # optional, for authenticated Jina Reader
```

## NixOS Deployment

This is managed as a NixOS service.

```nix
# In hosts/<hostname>/default.nix
services.searxng-mcp = {
  enable = true;
  port = 3009;
  searxngUrl = "http://prism:8888";
};
```

## mcp-injector Config

Add to `mcp-servers.edn`:

```clojure
{:servers
 {:searxng
  {:url   "http://127.0.0.1:3009/mcp"
   :tools ["search" "read_url" "read_urls" "http_request"]}}}
```

## Tools

### `search`

Query SearXNG metasearch engine. Returns results as formatted markdown (agent-optimized, not raw JSON).

| Param | Type | Default | Description |
|---|---|---|---|
| `query` | string | required | Search query |
| `max_results` | int | 5 | Number of results (1-20) |
| `language` | string | "all" | Language code |
| `safesearch` | int | 1 | 0=off, 1=moderate, 2=strict |
| `time_range` | string | — | "day", "week", "month", "year" |
| `categories` | array | — | "general", "news", "it", "science", etc. |
| `engines` | array | — | "google", "duckduckgo", "wikipedia", etc. |
| `pageno` | int | 1 | Page number |

### `read_url`

Fetch a URL and convert to LLM-friendly markdown. Uses 3-tier fallback: markdown.new → Jina Reader → local HTML parser.

| Param | Type | Default | Description |
|---|---|---|---|
| `url` | string | required | URL to fetch |
| `max_length` | int | 5000 | Max characters to return |
| `start_char` | int | 0 | Character offset |
| `section` | string | — | Extract under specific heading |
| `paragraph_range` | string | — | "1-5", "3", "10-" |
| `read_headings` | bool | false | Return only headings/TOC |

### `read_urls` (batch)

Fetch multiple URLs (up to 5) in one call. Saves agent round trips. All URLs share the same optional params.

| Param | Type | Default | Description |
|---|---|---|---|
| `urls` | array | required | URLs to fetch (max 5) |
| `max_length` | int | 5000 | Max characters per URL |
| `start_char` | int | 0 | Character offset |
| `section` | string | — | Apply to all URLs |
| `paragraph_range` | string | — | Apply to all URLs |
| `read_headings` | bool | false | Apply to all URLs |

### `http_request`

Make a raw HTTP GET request. Returns status code, content-type, and raw body as-is — no markdown conversion. Use for APIs, JSON endpoints, source files, or any content where you need the raw response. For reading webpages, use `read_url` instead.

| Param | Type | Default | Description |
|---|---|---|---|
| `url` | string | required | URL to fetch |
| `max_length` | int | 50000 | Max characters to return for body |

## Architecture

### MCP Transport (Streamable HTTP, 2025-03-26)

Single `/mcp` POST endpoint. Session lifecycle:

1. Client sends `initialize` → server creates session, returns `Mcp-Session-Id` header
1. Client sends `notifications/initialized` (no response needed, 204)
1. All subsequent requests include `Mcp-Session-Id` header
1. Server validates session on every non-initialize request

### Code Structure

Everything lives in `searxng_mcp.bb`:

```
Configuration / env vars
│
SearXNG HTTP client (searxng-search!)
│
HTML → Markdown (html->markdown, regex fallback)
│
URL Reader (3-tier: markdown.new → Jina → local)
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

### URL Reader Fallback Chain

1. **markdown.new** — `GET https://markdown.new/api/convert?url=<url>` (500/day per IP, no key)
1. **Jina Reader** — `GET https://r.jina.ai/<url>` (free tier, optional API key)
1. **Local regex stripper** — strips script/style, converts basic HTML elements to markdown (unlimited, always works)

### Error Handling

Tool errors return `{:error true :message "..."}` which dispatch-tool wraps in an MCP error response. The LLM sees the error message and can reason about it.

## Development

### Adding a Tool

1. Write `tool-<name> [args config]` function that returns data or `{:error ...}`
1. Add entry to `tools` vector with `:name`, `:description`, `:inputSchema`
1. Add case branch in `dispatch-tool`
1. Add test in `tests/test_searxng_mcp.clj`

### Testing

```bash
# Run integration tests (real in-process server)
bb test
```

### v1.5 (Planned)

- Swap regex HTML stripper for hickory + jsoup (jsoup is built into babashka since 1.12.195)
- Add `read_urls` concurrency controls (max parallel, per-URL timeouts)
- Consider `search_and_read` composite tool (search + auto-read top N results)

## Philosophy

Follow the same grumpy pragmatism as the rest of J.O.E.:

- **Actions, Calculations, Data** — tool functions are actions, keep them thin
- **One file is fine** — don't split into namespaces until you genuinely need to
- **No abstractions until they hurt** — the dispatch `case` is fine
- **Test against real services** — mock drift kills confidence
- **YAGNI** — resources/prompts MCP extensions not implemented because they're not needed yet
- **Agent-native** — every tool response guides the model's next action
