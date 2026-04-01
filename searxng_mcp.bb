#!/usr/bin/env bb
;; searxng_mcp.bb — SearXNG MCP Server (Streamable HTTP, 2025-03-26)
;;
;; Serves SearXNG search and URL-to-markdown as an MCP server.
;; Transport: Streamable HTTP (single /mcp POST endpoint)
;; Compatible with mcp-injector {:searxng {:url "http://localhost:PORT/mcp"}}
;;
;; Config: SEARXNG_URL (default: http://prism:8888)
;;         SEARXNG_MCP_PORT (default: 3009)
;;         JINA_API_KEY (optional, for authenticated Jina Reader)

(ns searxng-mcp
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ─── Configuration ──────────────────────────────────────────────────────────

(defn get-config []
  {:searxng-url (or (System/getenv "SEARXNG_URL") "http://prism:8888")})

(def protocol-version "2025-03-26")
(def server-info {:name "searxng-mcp" :version "0.1.0"})

(defn log [level message data]
  (let [output (json/generate-string
                {:timestamp (str (java.time.Instant/now))
                 :level level
                 :message message
                 :data data})]
    (if (contains? #{"error" "warn"} level)
      (binding [*out* *err*] (println output))
      (println output))))

;; ─── Session Management ─────────────────────────────────────────────────────

(def sessions (atom {}))

(defn new-session-id []
  (str (java.util.UUID/randomUUID)))

(defn create-session! []
  (let [sid (new-session-id)]
    (swap! sessions assoc sid {:created-at (System/currentTimeMillis)})
    sid))

(defn valid-session? [sid]
  (boolean (and sid (contains? @sessions sid))))

(defn find-header [request header-name]
  (let [headers (:headers request)
        low-name (str/lower-case header-name)]
    (or (get headers low-name)
        (get headers (keyword low-name))
        (some (fn [[k v]] (when (= low-name (str/lower-case (name k))) v)) headers))))

;; ─── Type Coercion ───────────────────────────────────────────────────────────
;; LLMs frequently send integers as strings ("10" instead of 10) and arrays as
;; JSON-encoded strings ("[\"a\",\"b\"]" instead of ["a","b"]). Coerce defensively.

(defn ->int
  "Coerce v to integer. Handles strings, floats, returns nil for nil or garbage."
  [v]
  (cond
    (nil? v) nil
    (integer? v) v
    (string? v) (let [trimmed (str/trim v)]
                  (or (try (Integer/parseInt trimmed)
                           (catch Exception _ nil))
                      (try (int (Double/parseDouble trimmed))
                           (catch Exception _ nil))))
    (float? v) (int v)
    :else nil))

(defn ->vector
  "Coerce v to a vector. Handles vectors, lists, JSON-encoded strings, single strings."
  [v]
  (cond
    (nil? v) nil
    (vector? v) v
    (sequential? v) (vec v)
    (string? v)
    (let [trimmed (str/trim v)]
      (cond
        (str/blank? trimmed) nil
        (str/starts-with? trimmed "[")
        (try (vec (json/parse-string trimmed true)) (catch Exception _ nil))
        :else [trimmed]))
    :else nil))

;; ─── SearXNG Client ─────────────────────────────────────────────────────────

(defn searxng-search! [{:keys [query max_results language safesearch
                               time_range categories engines pageno]}
                       config]
  (let [url (str (:searxng-url config) "/search")
        params (cond-> {"q" query
                        "format" "json"}
                 language (assoc "language" language)
                 safesearch (assoc "safesearch" (str safesearch))
                 time_range (assoc "time_range" time_range)
                 (seq categories) (assoc "categories" (str/join "," categories))
                 (seq engines) (assoc "engines" (str/join "," engines))
                 pageno (assoc "pageno" (str pageno)))
        resp (http-client/get url {:query-params params :throw false})
        status (:status resp)
        body (when-not (str/blank? (:body resp))
               (try (json/parse-string (:body resp) true)
                    (catch Exception _ {:raw (:body resp)})))]
    (if (>= status 400)
      {:error true :message (str "SearXNG returned " status)}
      (let [results (take (or max_results 5) (:results body []))]
        {:results (mapv (fn [r]
                          {:title (:title r)
                           :url (:url r)
                           :content (:content r)
                           :engines (:engines r)
                           :score (:score r)})
                        results)
         :total (count (:results body []))
         :query query
         :number_of_results (:number_of_results body)}))))

;; ─── HTML → Markdown (regex fallback) ───────────────────────────────────────

(defn html->markdown [html]
  (-> html
      (str/replace #"(?s)<script[^>]*>.*?</script>" "")
      (str/replace #"(?s)<style[^>]*>.*?</style>" "")
      (str/replace #"<br\s*/?>" "\n")
      (str/replace #"</?(?:p|div|li|tr)\b[^>]*>" "\n")
      (str/replace #"<h([1-6])[^>]*>" #(str "\n" (apply str (repeat (Integer/parseInt (second %)) "#")) " "))
      (str/replace #"</h[1-6][^>]*>" "\n")
      (str/replace #"<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>" "[$2]($1)")
      (str/replace #"<img[^>]*src=\"([^\"]*)\"[^>]*alt=\"([^\"]*)\"[^>]*>" "![$2]($1)")
      (str/replace #"<img[^>]*src=\"([^\"]*)\"[^>]*>" "![]($1)")
      (str/replace #"<[^>]+>" "")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

;; ─── URL Reader (3-tier fallback) ───────────────────────────────────────────

(defn read-url-markdown-new [url]
  (try
    (let [resp (http-client/get "https://markdown.new/api/convert"
                                {:query-params {"url" url}
                                 :throw false
                                 :timeout 15000})]
      (when (= 200 (:status resp))
        (:body resp)))
    (catch Exception _ nil)))

(defn read-url-jina [url]
  (try
    (let [headers (cond-> {"Accept" "text/markdown"}
                    (System/getenv "JINA_API_KEY")
                    (assoc "Authorization" (str "Bearer " (System/getenv "JINA_API_KEY"))))
          resp (http-client/get (str "https://r.jina.ai/" url)
                                {:headers headers
                                 :throw false
                                 :timeout 15000})]
      (when (= 200 (:status resp))
        (:body resp)))
    (catch Exception _ nil)))

(defn read-url-local [url]
  (try
    (let [resp (http-client/get url {:throw false :timeout 10000})]
      (when (= 200 (:status resp))
        (html->markdown (:body resp))))
    (catch Exception _ nil)))

(defn read-url [url {:keys [max_length start_char _section _paragraph_range _read_headings]}]
  (let [result (or (read-url-markdown-new url)
                   (read-url-jina url)
                   (read-url-local url))]
    (if-not result
      {:error true :message (str "Failed to fetch URL: " url)}
      (let [content (if (string? result) result (str result))
            truncated (if (and start_char (pos? start_char))
                        (subs content (min start_char (count content)))
                        content)
            final (if (and max_length (pos? max_length))
                    (subs truncated 0 (min max_length (count truncated)))
                    truncated)]
        {:url url
         :content final
         :length (count final)}))))

;; ─── Tool Implementations ───────────────────────────────────────────────────

(defn tool-search [args config]
  (let [{:keys [query max_results language safesearch
                time_range categories engines pageno]} args
        max_results (->int max_results)
        safesearch (->int safesearch)
        pageno (->int pageno)
        categories (->vector categories)
        engines (->vector engines)]
    (when (str/blank? query)
      (throw (ex-info "query is required and must be a non-empty string. Example: {\"query\": \"latest news\"}"
                      {:type :bad-request})))
    (let [result (searxng-search!
                  {:query query
                   :max_results (min (if (nil? max_results) 5 max_results) 20)
                   :language language
                   :safesearch (if (nil? safesearch) 1 safesearch)
                   :time_range time_range
                   :categories categories
                   :engines engines
                   :pageno (if (nil? pageno) 1 pageno)}
                  config)
          results (:results result)]
      (if (:error result)
        result
        (str/join "\n"
                  (concat
                   [(str "# Search: \"" query "\"\n")]
                   (map-indexed
                    (fn [i r]
                      (str/join "\n"
                                [(str (inc i) ". [" (:title r) "](" (:url r) ")")
                                 (str "   Engine: " (str/join ", " (:engines r))
                                      " | Score: " (format "%.1f" (or (:score r) 0)))
                                 (when (:content r)
                                   (str "   " (:content r)))
                                 ""]))
                    results)
                   ["\n> Use `read_url` to fetch full content from any result."
                    "> Use `read_urls` to fetch multiple URLs at once."]))))))

(defn tool-read-url [args _config]
  (let [{:keys [url max_length start_char section paragraph_range read_headings]} args
        max_length (->int max_length)
        start_char (->int start_char)]
    (when (str/blank? url)
      (throw (ex-info "url is required and must be a non-empty string. Example: {\"url\": \"https://example.com\"}"
                      {:type :bad-request})))
    (let [result (read-url url {:max_length (if (nil? max_length) 5000 max_length)
                                :start_char start_char
                                :section section
                                :paragraph_range paragraph_range
                                :read_headings read_headings})]
      (if (:error result)
        result
        (str "## " (:url result) "\n\n" (:content result))))))

(defn tool-read-urls [args _config]
  (let [{:keys [urls max_length start_char section paragraph_range read_headings]} args
        urls (->vector urls)
        max_length (->int max_length)
        start_char (->int start_char)]
    (when-not (and urls (seq urls))
      (throw (ex-info (str "urls must be a non-empty array of URL strings. "
                           "Pass as JSON array: [\"https://example.com\"]. "
                           "Do NOT encode as a string.")
                      {:type :bad-request})))
    (when (> (count urls) 5)
      (throw (ex-info "Maximum 5 URLs per batch" {:type :bad-request})))
    (let [results (mapv
                   (fn [url]
                     (try
                       (let [result (read-url url {:max_length (if (nil? max_length) 5000 max_length)
                                                   :start_char start_char
                                                   :section section
                                                   :paragraph_range paragraph_range
                                                   :read_headings read_headings})]
                         (if (:error result)
                           (str "## " url "\n\n**Error:** " (:message result))
                           (str "## " (:url result) "\n\n" (:content result))))
                       (catch Exception e
                         (str "## " url "\n\n**Error:** " (.getMessage e)))))
                   urls)]
      (str/join "\n\n---\n\n" results))))

;; ─── Tool Registry ──────────────────────────────────────────────────────────

(def tools
  [{:name "search"
    :description "Search the web using SearXNG metasearch engine. Returns top results with title, URL, snippet, engine, and score. Use read_url to fetch full content from any result URL. Use read_urls to fetch multiple URLs at once."
    :inputSchema {:type "object"
                  :properties {:query {:type "string" :description "Search query (required)"}
                               :max_results {:type "integer" :description "Number of results to return (default: 5, max: 20)"}
                               :language {:type "string" :description "Language code (e.g., 'en', 'de', 'all' for all languages)"}
                               :safesearch {:type "integer" :description "Safesearch level: 0=off, 1=moderate (default), 2=strict"}
                               :time_range {:type "string" :description "Filter by time: 'day', 'week', 'month', 'year'"}
                               :categories {:type "array" :items {:type "string"} :description "Search categories: general, news, it, science, images, videos, music, files, social media"}
                               :engines {:type "array" :items {:type "string"} :description "Specific engines to use: google, duckduckgo, wikipedia, bing, etc."}
                               :pageno {:type "integer" :description "Page number (default: 1)"}}
                  :required ["query"]}}

   {:name "read_url"
    :description "Fetch a URL and convert its content to clean, LLM-friendly markdown. Uses a 3-tier fallback chain: markdown.new → Jina Reader → local HTML parser. Use this to read full article content from search results."
    :inputSchema {:type "object"
                  :properties {:url {:type "string" :description "URL to fetch (required)"}
                               :max_length {:type "integer" :description "Maximum characters to return (default: 5000)"}
                               :start_char {:type "integer" :description "Character offset to start from (default: 0)"}
                               :section {:type "string" :description "Extract content under a specific heading"}
                               :paragraph_range {:type "string" :description "Range of paragraphs to extract, e.g. '1-5', '3', '10-'"}
                               :read_headings {:type "boolean" :description "If true, only return table of contents / headings"}}
                  :required ["url"]}}

   {:name "read_urls"
    :description "Fetch multiple URLs (up to 5) and convert each to markdown in a single batch call. Saves round trips compared to calling read_url multiple times. Each URL uses the same fallback chain: markdown.new → Jina Reader → local HTML parser."
    :inputSchema {:type "object"
                  :properties {:urls {:type "array" :items {:type "string"} :description "Array of URLs to fetch (max 5)"}
                               :max_length {:type "integer" :description "Maximum characters per URL (default: 5000)"}
                               :start_char {:type "integer" :description "Character offset to start from (default: 0)"}
                               :section {:type "string" :description "Extract content under a specific heading (applies to all URLs)"}
                               :paragraph_range {:type "string" :description "Range of paragraphs to extract (applies to all URLs)"}
                               :read_headings {:type "boolean" :description "If true, only return headings (applies to all URLs)"}}
                  :required ["urls"]}}])

;; ─── Tool Dispatch ──────────────────────────────────────────────────────────

(defn dispatch-tool [name args config]
  (try
    (case name
      "search" (tool-search args config)
      "read_url" (tool-read-url args config)
      "read_urls" (tool-read-urls args config)
      {:error true :message (str "Unknown tool: " name)})
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (log "warn" "Tool error" {:tool name :message (.getMessage e) :data data})
        {:error true :message (.getMessage e) :details data}))
    (catch Exception e
      (log "error" "Tool exception" {:tool name :error (.getMessage e)})
      {:error true :message (.getMessage e)})))

;; ─── JSON-RPC Handlers ──────────────────────────────────────────────────────

(defn handle-initialize [request _config]
  (let [sid (create-session!)]
    {:jsonrpc "2.0"
     :id (get request :id)
     :result {:protocolVersion protocol-version
              :capabilities {:tools {:list {} :call {}}}
              :serverInfo server-info
              :sessionId sid}}))

(defn handle-tools-list [request _config]
  {:jsonrpc "2.0"
   :id (get request :id)
   :result {:tools (mapv (fn [t]
                           {:name (:name t)
                            :description (:description t)
                            :inputSchema (:inputSchema t)})
                         tools)}})

(defn handle-tools-call [request config]
  (let [tool-name (get-in request [:params :name])
        args (get-in request [:params :arguments] {})]
    (log "info" "Tool call" {:tool tool-name :args (dissoc args :api-key)})
    (let [result (dispatch-tool tool-name args config)]
      (if (and (map? result) (:error result))
        {:jsonrpc "2.0"
         :id (get request :id)
         :error {:code -32603
                 :message (get result :message "Tool execution failed")}}
        {:jsonrpc "2.0"
         :id (get request :id)
         :result {:content [{:type "text"
                             :text (if (string? result) result (json/generate-string result {:pretty true}))}]}}))))

(defn handle-request [request config]
  (let [method (get request :method)]
    (case method
      "initialize" (handle-initialize request config)
      "tools/list" (handle-tools-list request config)
      "tools/call" (handle-tools-call request config)
      "notifications/initialized" nil
      {:jsonrpc "2.0"
       :id (get request :id)
       :error {:code -32601
               :message (str "Method not found: " method)}})))

;; ─── HTTP Server ────────────────────────────────────────────────────────────

(defn handle-mcp [request]
  (try
    (let [body-stream (:body request)
          body-raw (cond
                     (instance? java.io.InputStream body-stream) (slurp body-stream)
                     (string? body-stream) body-stream
                     :else nil)
          body (try (when body-raw (json/parse-string body-raw true))
                    (catch Exception _ nil))
          config (get-config)
          session-id (find-header request "Mcp-Session-Id")
          method (get body :method)]
      (if (= method "initialize")
        (let [result (handle-initialize body config)
              sid (get-in result [:result :sessionId])]
          {:status 200
           :headers {"Content-Type" "application/json"
                     "Mcp-Session-Id" sid}
           :body (json/generate-string result)})
        (if (valid-session? session-id)
          (let [result (handle-request body config)]
            (if result
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body (json/generate-string result)}
              {:status 204 :headers {} :body ""}))
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string
                  {:jsonrpc "2.0"
                   :id (get body :id)
                   :error {:code -32001
                           :message "Session not found. Re-initialize."}})})))
    (catch Exception e
      (log "error" "HTTP handler exception" {:error (.getMessage e)})
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:error {:code -32603
                       :message "Internal server error"}})})))

(defn handler [request]
  (cond
    (= (:request-method request) :get)
    (case (:uri request)
      "/health" {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string {:status "ok"
                                              :server (:name server-info)
                                              :version (:version server-info)})}
      {:status 404 :headers {} :body "Not found"})

    (and (= (:request-method request) :post)
         (= (:uri request) "/mcp"))
    (handle-mcp request)

    :else
    {:status 404 :headers {} :body "Not found"}))

;; ─── Entry Point ────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [port (Integer/parseInt (or (System/getenv "SEARXNG_MCP_PORT")
                                   (first args)
                                   "0"))
        server (http/run-server handler {:port port})
        actual-port (:local-port (meta server))]
    (println (json/generate-string {:status "started"
                                    :port actual-port
                                    :searxng-url (get-in (get-config) [:searxng-url])
                                    :server (:name server-info)
                                    :version (:version server-info)}))
    @(promise)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
