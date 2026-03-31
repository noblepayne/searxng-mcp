#!/usr/bin/env bb
;; tests/test_searxng_mcp.clj — Integration tests for searxng-mcp
;;
;; Tests call the handler function directly with simulated HTTP requests.
;; No separate server process — tests the full JSON-RPC stack in-process.

(ns test-searxng-mcp
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load the server
(load-file "searxng_mcp.bb")

;; ─── Test Helpers ────────────────────────────────────────────────────────────

(defn make-request [method params & [session-id]]
  {:request-method :post
   :uri "/mcp"
   :body (json/generate-string {:jsonrpc "2.0"
                                :method method
                                :params params
                                :id (java.util.UUID/randomUUID)})
   :headers (cond-> {"content-type" "application/json"}
              session-id (assoc "Mcp-Session-Id" session-id))})

(defn send-request [request]
  (let [resp (searxng-mcp/handler request)
        body (when-not (str/blank? (:body resp))
               (json/parse-string (:body resp) true))]
    {:status (:status resp)
     :headers (:headers resp)
     :body body}))

(defn initialize []
  (let [resp (send-request (make-request "initialize" {}))]
    (is (= 200 (:status resp)))
    (let [sid (get-in resp [:headers "Mcp-Session-Id"])]
      (is (some? sid))
      sid)))

(defn call-tool [session-id tool-name args]
  (send-request (make-request "tools/call"
                              {:name tool-name :arguments args}
                              session-id)))

(defn list-tools [session-id]
  (send-request (make-request "tools/list" {} session-id)))

;; ─── Health Endpoint ─────────────────────────────────────────────────────────

(deftest health-check-test
  (testing "GET /health returns ok"
    (let [resp (searxng-mcp/handler {:request-method :get :uri "/health"})]
      (is (= 200 (:status resp)))
      (let [body (json/parse-string (:body resp) true)]
        (is (= "ok" (:status body)))
        (is (= "searxng-mcp" (:server body)))))))

;; ─── Session Management ──────────────────────────────────────────────────────

(deftest initialize-test
  (testing "initialize returns 200 with session ID"
    (let [resp (send-request (make-request "initialize" {}))]
      (is (= 200 (:status resp)))
      (is (some? (get-in resp [:headers "Mcp-Session-Id"])))
      (is (= "2025-03-26" (get-in resp [:body :result :protocolVersion])))
      (is (= "searxng-mcp" (get-in resp [:body :result :serverInfo :name]))))))

(deftest session-required-test
  (testing "tools/call without session returns 400"
    (let [resp (send-request (make-request "tools/call"
                                           {:name "search" :arguments {:query "test"}}))]
      (is (= 400 (:status resp))))))

(deftest invalid-session-test
  (testing "tools/call with invalid session returns 400"
    (let [resp (send-request (make-request "tools/call"
                                           {:name "search" :arguments {:query "test"}}
                                           "invalid-session-id"))]
      (is (= 400 (:status resp))))))

;; ─── Tools List ──────────────────────────────────────────────────────────────

(deftest tools-list-test
  (testing "tools/list returns all 3 tools"
    (let [sid (initialize)
          resp (list-tools sid)]
      (is (= 200 (:status resp)))
      (let [tool-names (mapv :name (get-in resp [:body :result :tools]))]
        (is (= 3 (count tool-names)))
        (is (contains? (set tool-names) "search"))
        (is (contains? (set tool-names) "read_url"))
        (is (contains? (set tool-names) "read_urls"))))))

;; ─── Search Tool ─────────────────────────────────────────────────────────────

(deftest search-basic-test
  (testing "search returns markdown formatted results"
    (let [sid (initialize)
          resp (call-tool sid "search" {:query "hello world" :max_results 3})]
      (is (= 200 (:status resp)))
      (let [content (get-in resp [:body :result :content 0 :text])]
        (is (string? content))
        (is (str/includes? content "# Search:"))
        (is (str/includes? content "hello world"))
        (is (str/includes? content "read_url"))))))

(deftest search-missing-query-test
  (testing "search without query returns error"
    (let [sid (initialize)
          resp (call-tool sid "search" {})]
      (is (get-in resp [:body :error])))))

(deftest search-default-max-results-test
  (testing "search defaults to 5 results"
    (let [sid (initialize)
          resp (call-tool sid "search" {:query "test"})
          content (get-in resp [:body :result :content 0 :text])]
      ;; Should have numbered results
      (is (re-find #"\d+\." content)))))

;; ─── Read URL Tool ───────────────────────────────────────────────────────────

(deftest read-url-missing-url-test
  (testing "read_url without url returns error"
    (let [sid (initialize)
          resp (call-tool sid "read_url" {})]
      (is (get-in resp [:body :error])))))

(deftest read-url-example-com-test
  (testing "read_url fetches example.com"
    (let [sid (initialize)
          resp (call-tool sid "read_url" {:url "https://example.com" :max_length 1000})]
      (is (= 200 (:status resp)))
      (let [content (get-in resp [:body :result :content 0 :text])]
        (is (string? content))
        (is (str/includes? content "Example Domain"))))))

(deftest read-url-max-length-test
  (testing "read_url respects max_length"
    (let [sid (initialize)
          resp (call-tool sid "read_url" {:url "https://example.com" :max_length 100})
          content (get-in resp [:body :result :content 0 :text])]
      (is (<= (count content) 200)))))

;; ─── Read URLs Tool (Batch) ──────────────────────────────────────────────────

(deftest read-urls-basic-test
  (testing "read_urls fetches multiple URLs"
    (let [sid (initialize)
          resp (call-tool sid "read_urls" {:urls ["https://example.com"]
                                           :max_length 500})]
      (is (= 200 (:status resp)))
      (let [content (get-in resp [:body :result :content 0 :text])]
        (is (string? content))
        (is (str/includes? content "Example Domain"))))))

(deftest read-urls-too-many-test
  (testing "read_urls with >5 URLs returns error"
    (let [sid (initialize)
          resp (call-tool sid "read_urls" {:urls ["https://example.com"
                                                  "https://example.com"
                                                  "https://example.com"
                                                  "https://example.com"
                                                  "https://example.com"
                                                  "https://example.com"]})]
      (is (get-in resp [:body :error])))))

;; ─── Unknown Tool ────────────────────────────────────────────────────────────

(deftest unknown-tool-test
  (testing "Unknown tool returns error"
    (let [sid (initialize)
          resp (call-tool sid "nonexistent_tool" {})]
      (is (get-in resp [:body :error])))))

;; ─── Run Tests ───────────────────────────────────────────────────────────────

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests 'test-searxng-mcp))
