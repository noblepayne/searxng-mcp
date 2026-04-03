(ns hickory.zip
  (:require [clojure.zip :as zip]))

;;
;; Hickory
;;

(defn hickory-zip
  "Returns a zipper for html dom maps (as from as-hickory),
  given a root element."
  [root]
  (zip/zipper (complement string?)
              (comp seq :content)
              (fn [node children]
                (assoc node :content (and children (apply vector children))))
              root))

;;
;; Hiccup
;;

;; Just to make things easier, we go ahead and do the work here to
;; make hiccup zippers work on both normalized (all items have tag,
;; attrs map, and any children) and unnormalized hiccup forms.

(defn- children
  "Takes a hiccup node (normalized or not) and returns its children nodes."
  [node]
  (if (vector? node)
    ;; It's a hiccup node vector.
    (if (map? (second node)) ;; There is an attr map in second slot.
      (seq (subvec node 2))  ;; So skip tag and attr vec.
      (seq (subvec node 1))) ;; Otherwise, just skip tag.
    ;; Otherwise, must have a been a node list
    node))

;; Note, it's not made clear at all in the docs for clojure.zip, but as far as
;; I can tell, you are given a node potentially with existing children and
;; the sequence of children that should totally replace the existing children.
(defn- make
  "Takes a hiccup node (normalized or not) and a sequence of children nodes,
   and returns a new node that has the the children argument as its children."
  [node children]
  ;; The node might be either a vector (hiccup form) or a seq (which is like a
  ;; node-list).
  (if (vector? node)
    (if (map? (second node))                 ;; Again, check for normalized vec.
      (into (subvec node 0 2) children)      ;; Attach children after tag&attrs.
      (apply vector (first node) children))  ;; Otherwise, attach after tag.
    children))   ;; We were given a list for node, so just return the new list.

(defn hiccup-zip
  "Returns a zipper for Hiccup forms, given a root form."
  [root]
  (zip/zipper sequential?
              children
              make
              root))
(ns hickory.utils
  "Miscellaneous utilities used internally."
  (:require [clojure.string :as string]
            #?(:cljs [goog.string :as gstring])))

;;
;; Data
;;

(def void-element
  "Elements that don't have a meaningful <tag></tag> form."
  #{:area :base :br :col :command :embed :hr :img :input :keygen :link :meta
    :param :source :track :wbr})

(def unescapable-content
  "Elements whose content should never have html-escape codes."
  #{:script :style})

;;
;; String utils
;;

(defn clj-html-escape-without-quoin
  "Actually copy pasted from quoin: https://github.com/davidsantiago/quoin/blob/develop/src/quoin/text.clj"
  [^String s]
  ;; This method is "Java in Clojure" for serious speedups.
  #?(:clj (let [sb (StringBuilder.)
                slength (long (count s))]
            (loop [idx (long 0)]
              (if (>= idx slength)
                (.toString sb)
                (let [c (char (.charAt s idx))]
                  (case c
                    \& (.append sb "&amp;")
                    \< (.append sb "&lt;")
                    \> (.append sb "&gt;")
                    \" (.append sb "&quot;")
                    (.append sb c))
                  (recur (inc idx))))))
     ;; This shouldn't be called directly in cljs, but if it is, we use the same implementation as the html-escape function
     :cljs (gstring/htmlEscape s)))

(defn html-escape
  [s]
  #?(:clj  (clj-html-escape-without-quoin s)
     :cljs (gstring/htmlEscape s)))

(defn lower-case-keyword
  "Converts its string argument into a lowercase keyword."
  [s]
  (-> s string/lower-case keyword))

(defn render-doctype
  "Returns a string containing the HTML source for the doctype with given args.
   The second and third arguments can be nil or empty strings."
  [name publicid systemid]
  (str "<!DOCTYPE " name
       (when (not-empty publicid)
         (str " PUBLIC \"" publicid "\""))
       (when (not-empty systemid)
         (str " \"" systemid "\""))
       ">"))
(ns hickory.core
  (:require [hickory.utils :as utils]
            [hickory.zip :as hzip]
            [clojure.zip :as zip])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Attributes Comment DataNode Document
            DocumentType Element TextNode XmlDeclaration]
           [org.jsoup.parser Tag Parser]))

(set! *warn-on-reflection* true)

(defn- end-or-recur [as-fn loc data & [skip-child?]]
  (let [new-loc (-> loc (zip/replace data) zip/next (cond-> skip-child? zip/next))]
    (if (zip/end? new-loc)
      (zip/root new-loc)
      #(as-fn (zip/node new-loc) new-loc))))

;;
;; Protocols
;;

(defprotocol HiccupRepresentable
  "Objects that can be represented as Hiccup nodes implement this protocol in
   order to make the conversion."
  (as-hiccup [this] [this zip-loc]
    "Converts the node given into a hiccup-format data structure. The
     node must have an implementation of the HiccupRepresentable
     protocol; nodes created by parse or parse-fragment already do."))

(defprotocol HickoryRepresentable
  "Objects that can be represented as HTML DOM node maps, similar to
   clojure.xml, implement this protocol to make the conversion.

   Each DOM node will be a map or string (for Text/CDATASections). Nodes that
   are maps have the appropriate subset of the keys

     :type     - [:comment, :document, :document-type, :element]
     :tag      - node's tag, check :type to see if applicable
     :attrs    - node's attributes as a map, check :type to see if applicable
     :content  - node's child nodes, in a vector, check :type to see if
                 applicable"
  (as-hickory [this] [this zip-loc]
    "Converts the node given into a hickory-format data structure. The
     node must have an implementation of the HickoryRepresentable protocol;
     nodes created by parse or parse-fragment already do."))

(extend-protocol HiccupRepresentable
  Attribute
  ;; Note the attribute value is not html-escaped; see comment for Element.
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this _] [(utils/lower-case-keyword (.getKey this)) (.getValue this)]))
  Attributes
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this _] (into {} (map as-hiccup this))))
  Comment
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (str "<!--" (.getData this) "-->"))))
  DataNode
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (str this))))
  Document
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (apply list (.childNodes this)))))
  DocumentType
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc]
     (end-or-recur as-hiccup loc (utils/render-doctype (.name this)
                                                       (.publicId this)
                                                       (.systemId this)))))
  Element
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc]
     ;; There is an issue with the hiccup format, which is that it
     ;; can't quite cover all the pieces of HTML, so anything it
     ;; doesn't cover is thrown into a string containing the raw
     ;; HTML. This presents a problem because it is then never the case
     ;; that a string in a hiccup form should be html-escaped (except
     ;; in an attribute value) when rendering; it should already have
     ;; any escaping. Since the HTML parser quite properly un-escapes
     ;; HTML where it should, we have to go back and un-un-escape it
     ;; wherever text would have been un-escaped. We do this by
     ;; html-escaping the parsed contents of text nodes, and not
     ;; html-escaping comments, data-nodes, and the contents of
     ;; unescapable nodes.
     (let [tag (utils/lower-case-keyword (.tagName this))
           children (cond->> (.childNodes this) (utils/unescapable-content tag) (map str))
           data (into [] (concat [tag (trampoline as-hiccup (.attributes this))] children))]
       (end-or-recur as-hiccup loc data (utils/unescapable-content tag)))))
  TextNode
  ;; See comment for Element re: html escaping.
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (utils/html-escape (.getWholeText this)))))
  XmlDeclaration
  (as-hiccup
    ([this] (trampoline as-hiccup this (hzip/hiccup-zip this)))
    ([this loc] (end-or-recur as-hiccup loc (str this)))))

(extend-protocol HickoryRepresentable
  Attribute
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this _] [(utils/lower-case-keyword (.getKey this)) (.getValue this)]))
  Attributes
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this _] (not-empty (into {} (map as-hickory this)))))
  Comment
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :comment
                                              :content [(.getData this)]} true)))
  DataNode
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc (str this))))
  Document
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :document
                                              :content (or (seq (.childNodes this)) nil)})))
  DocumentType
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :document-type
                                              :attrs (trampoline as-hickory (.attributes this))})))
  Element
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc {:type :element
                                              :attrs (trampoline as-hickory (.attributes this))
                                              :tag (utils/lower-case-keyword (.tagName this))
                                              :content (or (seq (.childNodes this)) nil)})))
  TextNode
  (as-hickory
    ([this] (trampoline as-hickory this (hzip/hickory-zip this)))
    ([this loc] (end-or-recur as-hickory loc (.getWholeText this)))))

;; Jsoup/parse is polymorphic, we'll let reflection handle it for now
(set! *warn-on-reflection* false)

(defn parse
  "Parse an entire HTML document into a DOM structure that can be
   used as input to as-hiccup or as-hickory."
  [s]
  (Jsoup/parse s))

(set! *warn-on-reflection* true)

(ns hickory.select
  "Functions to query hickory-format HTML data.

   See clojure.zip for more information on zippers, locs, nodes, next, etc."
  (:require [clojure.zip :as zip]
            [clojure.string :as string]
            [hickory.zip :as hzip])
  #?(:clj
     (:import clojure.lang.IFn))
  (:refer-clojure :exclude [and or not class]))

;;
;; Utilities
;;

;;
;; Select
;;

;;
;; Selectors
;;
;; Mostly based off the spec at http://www.w3.org/TR/selectors/#selectors
;; Some selectors are simply not possible outside a browser (active,
;; visited, etc).
;;

;;
;; Selector combinators
;;

(ns hickory.hiccup-utils
  "Utilities for working with hiccup forms."
  (:require [clojure.string :as str]))

(defn- first-idx
  "Given two possible indexes, returns the lesser that is not -1. If both
   are -1, then -1 is returned. Useful for searching strings for multiple
   markers, as many routines will return -1 for not found.

   Examples: (first-idx -1 -1) => -1
             (first-idx -1 2) => 2
             (first-idx 5 -1) => 5
             (first-idx 5 3) => 3"
  #?(:clj  [^long a ^long b]
     :cljs [a b])
  (if (== a -1)
    b
    (if (== b -1)
      a
      (min a b))))

(defn- index-of
  ([^String s c]
   #?(:clj  (.indexOf s (int c))
      :cljs (.indexOf s c)))
  ([^String s c idx]
   #?(:clj  (.indexOf s (int c) (int idx))
      :cljs (.indexOf s c idx))))

(defn- split-keep-trailing-empty
  "clojure.string/split is a wrapper on java.lang.String/split with the limit
   parameter equal to 0, which keeps leading empty strings, but discards
   trailing empty strings. This makes no sense, so we have to write our own
   to keep the trailing empty strings."
  [s re]
  (str/split s re -1))

(defn tag-well-formed?
  "Given a hiccup tag element, returns true iff the tag is in 'valid' hiccup
   format. Which in this function means:
      1. Tag name is non-empty.
      2. If there is an id, there is only one.
      3. If there is an id, it is nonempty.
      4. If there is an id, it comes before any classes.
      5. Any class name is nonempty."
  [tag-elem]
  (let [tag-elem (name tag-elem)
        hash-idx (int (index-of tag-elem \#))
        dot-idx (int (index-of tag-elem \.))
        tag-cutoff (first-idx hash-idx dot-idx)]
    (and (< 0 (count tag-elem)) ;; 1.
         (if (== tag-cutoff -1) true (> tag-cutoff 0)) ;; 1.
         (if (== hash-idx -1)
           true
           (and (== -1 (index-of tag-elem \# (inc hash-idx))) ;; 2.
                (< (inc hash-idx) (first-idx (index-of tag-elem \. ;; 3.
                                                       (inc hash-idx))
                                             (count tag-elem)))))
         (if (and (not= hash-idx -1) (not= dot-idx -1)) ;; 4.
           (< hash-idx dot-idx)
           true)
         (if (== dot-idx -1) ;; 5.
           true
           (let [classes (.substring tag-elem (inc dot-idx))]
             (every? #(< 0 (count %))
                     (split-keep-trailing-empty classes #"\.")))))))

(defn tag-name
  "Given a well-formed hiccup tag element, return just the tag name as
  a string."
  [tag-elem]
  (let [tag-elem (name tag-elem)
        hash-idx (int (index-of tag-elem \#))
        dot-idx (int (index-of tag-elem \.))
        cutoff (first-idx hash-idx dot-idx)]
    (if (== cutoff -1)
      ;; No classes or ids, so the entire tag-element is the name.
      tag-elem
      ;; There was a class or id, so the tag name ends at the first
      ;; of those.
      (.substring tag-elem 0 cutoff))))

(defn class-names
  "Given a well-formed hiccup tag element, return a vector containing
   any class names included in the tag, as strings. Ignores the hiccup
   requirement that any id on the tag must come
   first. Example: :div.foo.bar => [\"foo\" \"bar\"]."
  [tag-elem]
  (let [tag-elem (name tag-elem)]
    (loop [curr-dot (index-of tag-elem \.)
           classes (transient [])]
      (if (== curr-dot -1)
        ;; Didn't find another dot, so no more classes.
        (persistent! classes)
        ;; There's another dot, so there's another class.
        (let [next-dot (index-of tag-elem \. (inc curr-dot))
              next-hash (index-of tag-elem \# (inc curr-dot))
              cutoff (first-idx next-dot next-hash)]
          (if (== cutoff -1)
            ;; Rest of the tag element is the last class.
            (recur next-dot
                   (conj! classes (.substring tag-elem (inc curr-dot))))
            ;; The current class name is terminated by another element.
            (recur next-dot
                   (conj! classes
                          (.substring tag-elem (inc curr-dot) cutoff)))))))))

(defn id
  "Given a well-formed hiccup tag element, return a string containing
   the id, or nil if there isn't one."
  [tag-elem]
  (let [tag-elem (name tag-elem)
        hash-idx (int (index-of tag-elem \#))
        next-dot-idx (int (index-of tag-elem \. hash-idx))]
    (if (== hash-idx -1)
      nil
      (if (== next-dot-idx -1)
        (.substring tag-elem (inc hash-idx))
        (.substring tag-elem (inc hash-idx) next-dot-idx)))))

(defn- expand-content-seqs
  "Given a sequence of hiccup forms, presumably the content forms of another
   hiccup element, return a new sequence with any sequence elements expanded
   into the main sequence. This logic does not apply recursively, so sequences
   inside sequences won't be expanded out. Also note that this really only
   applies to sequences; things that seq? returns true on. So this excludes
   vectors.
     (expand-content-seqs [1 '(2 3) (for [x [1 2 3]] (* x 2)) [5]])
     ==> (1 2 3 2 4 6 [5])"
  [content]
  (loop [remaining-content content
         result (transient [])]
    (if (nil? remaining-content)
      (persistent! result)
      (if (seq? (first remaining-content))
        (recur (next remaining-content)
               ;; Fairly unhappy with this nested loop, but it seems
               ;; necessary to continue the handling of transient vector.
               (loop [remaining-seq (first remaining-content)
                      result result]
                 (if (nil? remaining-seq)
                   result
                   (recur (next remaining-seq)
                          (conj! result (first remaining-seq))))))
        (recur (next remaining-content)
               (conj! result (first remaining-content)))))))

(defn- normalize-element
  "Given a well-formed hiccup form, ensure that it is in the form
     [tag attributes content1 ... contentN].
   That is, an unadorned tag name (keyword, lowercase), all attributes in the
   attribute map in the second element, and then any children. Note that this
   does not happen recursively; content is not modified."
  [hiccup-form]
  (let [[tag-elem & content] hiccup-form]
    (when (not (tag-well-formed? tag-elem))
      (throw (ex-info (str "Invalid input: Tag element"
                           tag-elem "is not well-formed.")
                      {})))
    (let [tag-name (keyword (str/lower-case (tag-name tag-elem)))
          tag-classes (class-names tag-elem)
          tag-id (id tag-elem)
          tag-attrs {:id tag-id
                     :class (if (not (empty? tag-classes))
                              (str/join " " tag-classes))}
          [map-attrs content] (if (map? (first content))
                                [(first content) (rest content)]
                                [nil content])
          ;; Note that we replace tag attributes with map attributes, without
          ;; merging them. This is to match hiccup's behavior.
          attrs (merge tag-attrs map-attrs)]
      (apply vector tag-name attrs content))))

(defn normalize-form
  "Given a well-formed hiccup form, recursively normalizes it, so that it and
   all children elements will also be normalized. A normalized form is in the
   form
     [tag attributes content1 ... contentN].
   That is, an unadorned tag name (keyword, lowercase), all attributes in the
   attribute map in the second element, and then any children. Any content
   that is a sequence is also expanded out into the main sequence of content
   items."
  [form]
  (if (string? form)
    form
    ;; Do a pre-order walk and save the first two items, then do the children,
    ;; then glue them back together.
    (let [[tag attrs & contents] (normalize-element form)]
      (apply vector tag attrs (map #(if (vector? %)
                                      ;; Recurse only on vec children.
                                      (normalize-form %)
                                      %)
                                   (expand-content-seqs contents))))))
(ns hickory.render
  (:require [hickory.hiccup-utils :as hu]
            [hickory.utils :as utils]
            [clojure.string :as str]))

;;
;; Hickory to HTML
;;

;;
;; Hiccup to HTML
;;

(defn- render-hiccup-attrs
  "Given a hiccup attribute map, returns a string containing the attributes
   rendered as they should appear in an HTML tag, right after the tag (including
   a leading space to separate from the tag, if any attributes present)."
  [attrs]
  ;; Hiccup normally does not html-escape strings, but it does for attribute
  ;; values.
  (let [attrs-str (->> (for [[k v] attrs]
                         (cond (true? v)
                               (str (name k))
                               (nil? v)
                               ""
                               :else
                               (str (name k) "=" "\"" (utils/html-escape v) "\"")))
                       (filter #(not (empty? %)))
                       sort
                       (str/join " "))]
    (if (not (empty? attrs-str))
      ;; If the attrs-str is not "", we need to pad the front so that the
      ;; tag will separate from the attributes. Otherwise, "" is fine to return.
      (str " " attrs-str)
      attrs-str)))

(declare hiccup-to-html)
(defn- render-hiccup-element
  "Given a normalized hiccup element (such as the output of
   hickory.hiccup-utils/normalize-form; see this function's docstring
   for more detailed definition of a normalized hiccup element), renders
   it to HTML and returns it as a string."
  [n-element]
  (let [[tag attrs & content] n-element]
    (if (utils/void-element tag)
      (str "<" (name tag) (render-hiccup-attrs attrs) ">")
      (str "<" (name tag) (render-hiccup-attrs attrs) ">"
           (hiccup-to-html content)
           "</" (name tag) ">"))))

(defn- render-hiccup-form
  "Given a normalized hiccup form (such as the output of
   hickory.hiccup-utils/normalize-form; see this function's docstring
   for more detailed definition of a normalized hiccup form), renders
   it to HTML and returns it as a string."
  [n-form]
  (if (vector? n-form)
    (render-hiccup-element n-form)
    n-form))

(defn hiccup-to-html
  "Given a sequence of hiccup forms (as returned by as-hiccup), returns a
   string containing HTML it represents. Keep in mind this function is not super
   fast or heavy-duty, and definitely not a replacement for dedicated hiccup
   renderers, like hiccup itself, which *is* fast and heavy-duty.

```klipse
  (hiccup-to-html '([:html {} [:head {}] [:body {} [:a {} \"foo\"]]]))
```

   Note that it will NOT in general be the case that

     (= my-html-src (hiccup-to-html (as-hiccup (parse my-html-src))))

   as we do not keep any letter case or whitespace information, any
   \"tag-soupy\" elements, attribute quote characters used, etc. It will also
   not generally be the case that this function's output will exactly match
   hiccup's.
   For instance:

```klipse
(hiccup-to-html (as-hiccup (parse \"<A href=\\\"foo\\\">foo</A>\")))
```
  "
  [hiccup-forms]
  (apply str (map #(render-hiccup-form (hu/normalize-form %)) hiccup-forms)))

(ns hickory.convert
  "Functions to convert from one representation to another."
  (:require [hickory.render :as render]
            [hickory.core :as core]
            [hickory.utils :as utils]))

(ns hickory-bundle
  (:require [hickory.core]))
(def as-hickory hickory.core/as-hickory)
(def parse hickory.core/parse)
(ns skim.core
  "Mozilla Readability algorithm + hickory to markdown conversion.
   Reads HTML, extracts main content, converts to markdown."
  (:require [hickory-bundle :as h]
            [clojure.zip :as zip]
            [clojure.string :as str]
            [babashka.http-client :as http]))

;; -- Regex patterns from Mozilla Readability ---------------------------------

(def unlikely-candidates
  #"(?i)-ad-|ai2html|banner|breadcrumbs|ccpa|combx|comment|community|cover-wrap|disqus|echobox|extra|footer|gdpr|gtag|googletag|header|legends|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|advertising|agegate|pagination|pager|popup|yom-remote")

(def ok-maybe-candidate
  #"(?i)and|article|body|column|content|main|mathjax|shadow")

(def positive-pattern
  #"(?i)article|body|content|entry|hentry|h-entry|main|page|pagination|post|text|blog|story")

(def negative-pattern
  #"(?i)-ad-|hidden|^hid$| hid$| hid |^hid |banner|ccpa|combx|comment|com-|contact|echobox|footer|gdpr|gtag|googletag|masthead|media|meta|outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|widget")

(def unlikely-roles #{"menu" "menubar" "complementary" "navigation" "alert" "alertdialog" "dialog"})

(def tags-to-score #{:section :h2 :h3 :h4 :h5 :h6 :p :td :pre})

(def tag-score-bonuses
  {:div 5, :pre 3, :td 3, :blockquote 3,
   :address -3, :ol -3, :ul -3, :dl -3, :dd -3, :dt -3, :li -3, :form -3,
   :h1 -5, :h2 -5, :h3 -5, :h4 -5, :h5 -5, :h6 -5, :th -5})

(def default-n-top-candidates 5)
(def min-paragraph-chars 25)
;; -- Node predicates ---------------------------------------------------------

(defn element-node?
  [node]
  (and (map? node) (= :element (:type node))))

(defn get-text
  "Recursively extract all text content from a hickory node."
  [node]
  (cond
    (string? node) node
    (map? node)
    (->> (:content node)
         (map get-text)
         (str/join))
    (coll? node)
    (->> node (map get-text) (str/join))
    :else ""))

(defn get-inner-text
  "Get text content, normalized (collapsed whitespace, trimmed)."
  [node]
  (str/replace (str/trim (get-text node)) #"\s+" " "))

(defn get-class-weight
  "Calculate class/id weight for a node (+25 for positive, -25 for negative)."
  [node]
  (let [attrs (:attrs node)
        class (:class attrs "")
        id (:id attrs "")
        match-str (str class " " id)
        score 0
        score (if (re-find negative-pattern match-str) (- score 25) score)
        score (if (re-find positive-pattern match-str) (+ score 25) score)]
    score))

(defn count-commas
  [text]
  (count (re-seq #"\u002C|\u060C|\uFE50|\uFE10|\uFE11|\u2E41|\u2E34|\u2E32|\uFF0C" text)))

(defn link-density
  "Calculate the ratio of link text to total text in a node."
  [node]
  (let [total-text (get-inner-text node)
        total-len (count total-text)]
    (if (zero? total-len)
      0.0
      (let [link-text-len
            (->> (tree-seq map? :content node)
                 (filter (fn [n] (and (element-node? n) (= :a (:tag n)))))
                 (map get-inner-text)
                 (map count)
                 (reduce + 0))]
        (double (/ link-text-len total-len))))))

;; -- Scoring helpers ---------------------------------------------------------

(defn- direct-text
  "Get only the direct text children of a node (not recursive)."
  [node]
  (->> (:content node)
       (filter string?)
       (map str/trim)
       (remove str/blank?)
       (str/join " ")))

(defn score-element-paragraph
  "Calculate paragraph-level content score for an element.
   Only scores elements in tags-to-score (p, td, pre, section, h2-h6).
   Uses direct text content only (not recursive) so container elements
   with no direct text don't get inflated scores."
  [node]
  (when (contains? tags-to-score (:tag node))
    (let [text (direct-text node)]
      (when (>= (count text) min-paragraph-chars)
        (let [base 1
              commas (count-commas text)
              length-bonus (min (quot (count text) 100) 3)]
          (+ base commas length-bonus))))))

(defn initialize-node-score
  "Initial tag + class weight score."
  [node]
  (let [tag (:tag node)
        tag-bonus (get tag-score-bonuses tag 0)
        class-weight (get-class-weight node)]
    (+ tag-bonus class-weight)))

;; -- Scoring: bottom-up recursive pass -----------------------------------------

(defn score-tree
  "Walk the tree bottom-up, compute readability scores, annotate each element
   with :readability/contentScore. Returns the annotated tree."
  [tree]
  (letfn [(walk [node]
            (cond
              (string? node)
              [node 0]

              (element-node? node)
              (let [content (:content node)
                    [new-content child-scores]
                    (if (seq content)
                      (let [results (mapv walk content)]
                        [(mapv first results)
                         (mapv second results)])
                      [[] []])
                    init-score (initialize-node-score node)
                    elem-score (score-element-paragraph node)
                    propagated (reduce + 0 (remove nil? child-scores))
                    total (+ init-score (or elem-score 0) propagated)
                    annotated (assoc node
                                     :readability/contentScore total
                                     :readability/elemScore (or elem-score 0)
                                     :content new-content)]
                [annotated elem-score])

              (map? node)
              (let [content (:content node)
                    [new-content _]
                    (if (seq content)
                      (let [results (mapv walk content)]
                        [(mapv first results)
                         (mapv second results)])
                      [[] []])]
                [(assoc node :content new-content) 0])

              :else
              [node 0]))]
    (first (walk tree))))

;; -- Pre-processing: remove unlikely candidates ------------------------------

(defn unlikely-candidate?
  [node]
  (when (element-node? node)
    (let [attrs (:attrs node)
          class (:class attrs "")
          id (:id attrs "")
          match-str (str class " " id)
          role (some-> attrs :role str/lower-case)]
      (and (or (and (re-find unlikely-candidates match-str)
                    (not (re-find ok-maybe-candidate match-str)))
               (and role (contains? unlikely-roles role)))
           (not= (:tag node) :body)
           (not= (:tag node) :a)))))

(defn remove-unlikely
  "Remove unlikely candidate subtrees from a hickory tree."
  [tree]
  (let [z (zip/zipper
           (fn [n] (or (string? n) (and (map? n) (seq (:content n)))))
           (fn [n] (if (string? n) [] (:content n)))
           (fn [n children]
             (if (string? n) n (assoc n :content (vec children))))
           tree)]
    (loop [loc z]
      (if (zip/end? loc)
        (zip/root loc)
        (let [node (zip/node loc)]
          (if (and (element-node? node) (unlikely-candidate? node))
            (recur (zip/remove loc))
            (recur (zip/next loc))))))))

;; -- Strip non-content tags --------------------------------------------------

(defn strip-tags
  "Remove script, style, and noscript elements from the tree."
  [tree]
  (let [z (zip/zipper
           (fn [n] (or (string? n) (and (map? n) (seq (:content n)))))
           (fn [n] (if (string? n) [] (:content n)))
           (fn [n children]
             (if (string? n) n (assoc n :content (vec children))))
           tree)]
    (loop [loc z]
      (if (zip/end? loc)
        (zip/root loc)
        (let [node (zip/node loc)]
          (if (and (element-node? node)
                   (#{:script :style :noscript} (:tag node)))
            (recur (zip/remove loc))
            (recur (zip/next loc))))))))

;; -- Candidate Selection ----------------------------------------------------- -----------------------------------------------------

(defn find-top-candidates
  ([tree] (find-top-candidates tree default-n-top-candidates))
  ([tree n]
   (let [scored (score-tree tree)
         elements (->> (tree-seq map? :content scored)
                       (filter element-node?))]
     (->> elements
          (map (fn [node]
                 (let [score (get node :readability/elemScore 0)
                       ld (link-density node)]
                   {:node node
                    :score score
                    :link-density ld
                    :adjusted-score (* score (- 1 ld))})))
          (sort-by :adjusted-score >)
          (take n)))))

(defn find-best-candidate
  "Returns {:node :score :adjusted-score :link-density}."
  [tree]
  (first (find-top-candidates tree)))

(defn- narrow-element?
  "Check if a tag is a leaf/narrow content element."
  [tag]
  (#{:p :li :td :pre :h1 :h2 :h3 :h4 :h5 :h6 :a :span :img :br :strong :em :b :i :code} tag))

(defn- climb-to-container
  "If node is a narrow element, walk up the tree to find a container
   with multiple meaningful children."
  [node tree]
  (if-not (narrow-element? (:tag node))
    node
    (let [z (zip/zipper
             (fn [n] (or (string? n) (and (map? n) (seq (:content n)))))
             (fn [n] (if (string? n) [] (:content n)))
             (fn [n children]
               (if (string? n) n (assoc n :content (vec children))))
             tree)
          target-text (get-inner-text node)
          target-tag (:tag node)
          prefix (subs target-text 0 (min 30 (count target-text)))
          found (loop [loc z]
                  (if (zip/end? loc)
                    nil
                    (let [n (zip/node loc)]
                      (if (and (element-node? n)
                               (= target-tag (:tag n))
                               (let [t (get-inner-text n)]
                                 (and (> (count t) 10)
                                      (str/includes? t prefix))))
                        loc
                        (recur (zip/next loc))))))]
      (if found
        (loop [loc found]
          (if-let [up (zip/up loc)]
            (let [parent (zip/node up)
                  elem-children (count (filter element-node? (:content parent)))]
              (if (and (> elem-children 1)
                       (not (narrow-element? (:tag parent)))
                       (not= (:tag parent) :body)
                       (not= (:tag parent) :html))
                (recur up)
                (zip/node loc)))
            (zip/node loc)))
        node))))

(defn- container-score
  "Score a container by the total text content of its paragraph-level
   descendants (p, td, pre) weighted by their elemScore. For containers
   with no scored descendants (e.g., br-separated text), fall back to
   total text length."
  [container]
  (let [scored-paragraphs
        (->> (tree-seq map? :content container)
             (filter element-node?)
             (filter #(contains? tags-to-score (:tag %)))
             (map #(get % :readability/elemScore 0))
             (reduce + 0))
        text-len (count (get-inner-text container))]
    (if (pos? scored-paragraphs)
      scored-paragraphs
      ;; Fallback: use text length scaled down to be comparable to elemScores
      (quot text-len 50))))

(defn- find-content-root
  "Find the best content container by looking at all elements and picking
   the one with the highest container-score. Prefers article > section > div > body."
  [tree]
  (let [scored (score-tree tree)
        elements (->> (tree-seq map? :content scored)
                      (filter element-node?)
                      (filter #(not (#{:html :body :head} (:tag %)))))
        scored (map (fn [el]
                      {:element el
                       :score (container-score el)
                       :tag (:tag el)})
                    elements)
        ;; Sort by score, then by tag preference
        tag-preference {:article 0, :section 1, :div 2, :main 3, :body 4}
        best (first (sort-by (fn [s] [(- (:score s))
                                      (get tag-preference (:tag s) 99)])
                             scored))]
    (when (and best (pos? (:score best)))
      (:element best))))

(defn extract-content
  "Extract the main content subtree as a hickory node.
   Finds the container element with the highest cumulative paragraph score,
   which naturally filters out isolated high-scoring elements like author bios."
  [tree]
  (or (find-content-root tree)
      ;; Fallback: use the old single-candidate approach
      (let [best (find-best-candidate tree)]
        (when-let [node (:node best)]
          (climb-to-container node tree)))))

;; -- Metadata Extraction -----------------------------------------------------

(defn extract-metadata
  [tree]
  (let [nodes (tree-seq map? :content tree)
        elements (filter element-node? nodes)
        title (->> elements (filter #(= :title (:tag %))) first get-text str/trim)
        h1-title (->> elements (filter #(= :h1 (:tag %))) first get-text str/trim)
        byline (->> elements
                    (filter #(and (= :meta (:tag %))
                                  (re-find #"(?i)(author|creator)"
                                           (str (get-in % [:attrs :name])
                                                (get-in % [:attrs :property])))))
                    first :attrs :content)
        description (->> elements
                         (filter #(and (= :meta (:tag %))
                                       (re-find #"(?i)(description)"
                                                (str (get-in % [:attrs :name])
                                                     (get-in % [:attrs :property])))))
                         first :attrs :content)
        first-p (->> elements (filter #(= :p (:tag %))) first get-inner-text)
        site-name (->> elements
                       (filter #(and (= :meta (:tag %))
                                     (re-find #"(?i)og:site_name"
                                              (str (get-in % [:attrs :property])))))
                       first :attrs :content)]
    {:title (or title h1-title)
     :byline byline
     :excerpt (or description first-p)
     :site-name site-name}))

;; -- Hickory to Markdown -----------------------------------------------------

(defn- md-link
  [text url]
  (if (and url (not= url "") (not (str/starts-with? url "javascript:")))
    (str "[" text "](" url ")")
    text))

(defn- md-image
  [alt url]
  (str "![" (or alt "") "](" (or url "") ")"))

(defn- heading-level
  [tag]
  (case tag
    :h1 1 :h2 2 :h3 3 :h4 4 :h5 5 :h6 6
    nil))

(defn- node-to-md
  "Convert a hickory node to markdown string."
  [node]
  (cond
    (string? node)
    (let [t (str/trim node)]
      (if (= t "") "" t))

    (not (element-node? node))
    ""

    :else
    (let [tag (:tag node)
          attrs (:attrs node)
          content (:content node)
          children-md (->> content
                           (map node-to-md)
                           (remove str/blank?)
                           (str/join "\n"))
          children-md (str/trim children-md)]
      (case tag
        ;; Headings
        (:h1 :h2 :h3 :h4 :h5 :h6)
        (let [level (heading-level tag)]
          (str (apply str (repeat level "#")) " " children-md "\n\n"))

        ;; Paragraphs
        :p
        (if (str/blank? children-md)
          ""
          (str children-md "\n\n"))

        ;; Line breaks
        :br
        "\n\n"

        ;; Bold
        (:b :strong)
        (if (str/blank? children-md) "" (str "**" children-md "**"))

        ;; Italic
        (:i :em)
        (if (str/blank? children-md) "" (str "*" children-md "*"))

        ;; Strikethrough
        (:s :strike :del)
        (if (str/blank? children-md) "" (str "~~" children-md "~~"))

        ;; Code
        :code
        (if (str/blank? children-md) "" (str "`" children-md "`"))

        ;; Links
        :a
        (let [href (:href attrs)]
          (md-link children-md href))

        ;; Images
        :img
        (md-image (:alt attrs "") (:src attrs ""))

        ;; Blockquote
        :blockquote
        (if (str/blank? children-md)
          ""
          (str (->> (str/split-lines children-md)
                    (map #(str "> " %))
                    (str/join "\n"))
               "\n\n"))

        ;; Unordered list
        :ul
        (if (str/blank? children-md)
          ""
          (str children-md "\n"))

        ;; Ordered list
        :ol
        (if (str/blank? children-md)
          ""
          (str children-md "\n"))

        ;; List item
        :li
        (str "- " children-md "\n")

        ;; Pre/code blocks
        :pre
        (if (str/blank? children-md)
          ""
          (str "```\n" children-md "\n```\n\n"))

        ;; Horizontal rule
        :hr
        "---\n\n"

        ;; Structural elements - just pass through children
        (:div :section :article :main :header :footer :nav :aside :figure :figcaption :span :time :address)
        (if (str/blank? children-md)
          ""
          (str children-md "\n\n"))

        ;; Skip these entirely
        (:script :style :noscript :svg :link :meta :template :slot :iframe
                 :form :input :textarea :select :button :canvas :video :audio :source)
        ""

        ;; Default: pass through children
        children-md))))

(defn hickory-to-markdown
  "Convert a hickory content subtree to markdown."
  [node]
  (-> (node-to-md node)
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

;; -- Main pipeline -----------------------------------------------------------

(defn html->markdown
  "Full pipeline: HTML string to readability extraction to markdown.
   Returns {:title :byline :excerpt :content :site-name :markdown}."
  [html]
  (let [tree (-> html h/parse h/as-hickory)
        cleaned (-> tree strip-tags remove-unlikely)
        content (extract-content cleaned)
        metadata (extract-metadata tree)]
    (assoc metadata
           :content content
           :markdown (hickory-to-markdown content))))

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
            [clojure.string :as str]
            [skim.core :as skim]))

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

(defn read-url-skim [url]
  (try
    (let [resp (http-client/get url {:throw false :timeout 10000})]
      (when (= 200 (:status resp))
        (:markdown (skim/html->markdown (:body resp)))))
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
                   (read-url-skim url)
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

(defn tool-http-request [args _config]
  (let [{:keys [url max_length]} args
        max_length (->int max_length)]
    (when (str/blank? url)
      (throw (ex-info "url is required and must be a non-empty string. Example: {\"url\": \"https://api.example.com/data\"}"
                      {:type :bad-request})))
    (try
      (let [resp (http-client/get url {:throw false :timeout 15000})
            status (:status resp)
            headers (:headers resp)
            content-type (or (get headers "content-type")
                             (get headers :content-type)
                             "application/octet-stream")
            raw-body (:body resp)
            truncated (if (and max_length (pos? max_length))
                        (subs raw-body 0 (min max_length (count raw-body)))
                        raw-body)]
        {:status status
         :content-type content-type
         :body truncated})
      (catch Exception e
        {:error true :message (str "Failed to fetch URL: " (.getMessage e))}))))

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
                   :required ["urls"]}}

    {:name "http_request"
     :description "Make a raw HTTP GET request and return the response as-is (status code, content-type header, and raw body). Use for APIs, JSON endpoints, source files, or any content where you need the raw response instead of markdown. NOT for reading webpages — use read_url for that."
     :inputSchema {:type "object"
                   :properties {:url {:type "string" :description "URL to fetch (required)"}
                                :max_length {:type "integer" :description "Maximum characters to return for body (default: 50000)"}}
                   :required ["url"]}}])

;; ─── Tool Dispatch ──────────────────────────────────────────────────────────

(defn dispatch-tool [name args config]
  (try
    (case name
      "search" (tool-search args config)
      "read_url" (tool-read-url args config)
      "read_urls" (tool-read-urls args config)
      "http_request" (tool-http-request args config)
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
(ns user (:require [searxng-mcp])) (apply searxng-mcp/-main *command-line-args*)