# Cog Town 🏘️

[![Clojars Project](https://img.shields.io/clojars/v/com.github.brianium/cog-town.svg)](https://clojars.org/com.github.brianium/cog-town)

Build **agentic workflows** in Clojure with the ergonomics of `core.async`.

`cog.town` gives you a tiny set of composable primitives—**cogs**—stateful, concurrent agents that pass messages over channels.  
Think of them as Lego® bricks for conversational or multimodal AI systems.

```clojure
(ns my.ns
  (require [clojure.core.async :as a]
           [clojure.string :as string]
           [cog.town :as cogs]))

;;; 1. Create some cogs
(def echo
  (cogs/cog [] (fn [ctx msg]
                 (let [resp (str "👋 you said: "  msg)]
                   (-> (conj ctx msg)
                       (conj resp)
                       (vector resp))))))

(def shout
    (cogs/cog [] (fn [ctx msg]
                   (let [resp (clojure.string/upper-case msg)]
                     (-> (conj ctx msg)
                         (conj resp)
                         (vector resp))))))

;;; cogs can be dereferenced to get a live snapshot of their context
@shout
;; => ["hello!", "HELLO!"]

;;; 2. Wire cogs into a flow

(def shout-flow (cogs/flow [echo shout]))
(a/put! shout-flow "hello!")
(a/take! shout-flow println)
(a/close! shout-flow)

;;; 3. Let two cogs talk

(def shout-convo (cogs/dialogue echo shout))
(a/put! shout-convo "hello!")
(a/go-loop []
  (when-some [msg (a/<! shout-convo)]
    (println msg)
    (recur)))
```

You write **pure**[*](#a-note-on-purity) business logic; Cog Town handles state threading, back‑pressure, and blocking work on dedicated threads.

---

## Core concepts

| Primitive      | What it is                                                   | When to reach for it                                           |
| -------------- | ------------------------------------------------------------ | -------------------------------------------------------------- |
| **`cog`**      | Bidirectional channel **plus** private immutable **context** and a **pure transition**. Implements `ReadPort`, `WritePort`, `Channel`, `Mult`, **`IDeref`**. | Anytime you need an agent that remembers and evolves. |
| **`extend`**   | Light‑weight wrapper around a cog that **adds** or **re‑routes** its I/O without touching core logic. | Enrich output (TTS, embeddings) or translate input. |
| **`fork`**     | Clones a cog, optionally transforming its context, IO pair, or transition. | Re‑use behaviours with tweaks; create read‑only taps; testing. |
| **`flow`**     | Sequentially connects N channels so each output becomes the next input. | Pipelines (ETL, request → AI → TTS, etc.). |
| **`fanout`**   | Broadcasts an input value to many channels, gathers ordered results. | Scatter‑gather, multi‑tool calls. |
| **`gate`**     | Releases the value of a latched channel when triggered by input. | Throttling, deferred fetches. |
| **`dialogue`** | Ping‑pong messages between two cogs forever. | Multi‑turn agent duets or self‑conversation. |

---

## Transition functions

A cog’s transition function has the following signature:

```clojure
transition :: (context, input) → [new‑context, output]
```

* Runs on its own **real** thread (via `async/thread`) so it is safe to block on HTTP or disk I/O.  
* Throwing emits `{:type ::error :throwable th :input input}` as output.

### A note on purity

Transition functions are **pure** in the sense that they do not force you to mutate the context directly. Instead, they return a new context value. This can be useful for time travel, debugging, testing, etc. 

However, you can still use side effects (we are talking to LLMs after all) like logging, HTTP requests, or database writes within the transition function. In fact this may be necessary when moving beyond stateful atoms (storing context in a database, for example).

```clojure
(defn gpt-4o
  "More pure. Just a Clojure data structure as context. Still need to talk to OpenAI."
  [context input]
  (let [log-entries  (conj context input)
        response     (openai/create-response :model :gpt-4o :easy-input-messages log-entries)
        output-entry {:role    :assistant
                      :content (-> (:output response) first :message :content first :output-text :text)
                      :format  (when-some [fmt (some-> (:text response) :format)]
                                 (if (some? (:json-schema fmt))
                                   :json-schema
                                   :text))}]
    [(conj log-entries output-entry) output-entry]))
```

vs.

```clojure
(defn gpt-4o
  "Less pure. Context is backed by some protocol using next.jdbc or similar"
  [context input]
  (let [log-entries  (ctx/insert! context input)
        response     (openai/create-response :model :gpt-4o :easy-input-messages log-entries)
        output-entry {:role    :assistant
                      :content (-> (:output response) first :message :content first :output-text :text)
                      :format  (when-some [fmt (some-> (:text response) :format)]
                                 (if (some? (:json-schema fmt))
                                   :json-schema
                                   :text))}]
    (ctx/insert! context output-entry)
    [context output-entry]))
```

Clojure is freedom.

---

## Observing & time‑travel

* **Live snapshot** – since a cog implements `IDeref`, you can simply do `(@my-cog)` to get the latest context value.  
* **Audit / replay** – Optionally collect the pair `[ctx out]` in transition functions, storing it in an atom or log.  
* **Forking** – Pick any historical `ctx` value and feed it back into a new cog for “what‑if” exploration.

---

## Performance & GC notes

* Contexts **≤ 2 MB** updated a few times per second are generally safe.  
* Watch `-Xlog:gc*` or `jstat -gcutil` during dev; full GCs should be rare.  
* If context grows (large transcripts, embeddings), store bulky data off‑heap or behind an ID reference and keep lightweight keys in `ctx`.  
* Cap the length of any time‑travel timeline vector or store *deltas* to avoid memory blow‑up.

---

## API reference (cheatsheet)

```clojure
(cog     ctx transition & [buf-or-n xf ex-handler])            => Cog
(fork    cog)                                                  => Cog
(fork    cog ctx-fn)                                           => Cog
(fork    cog ctx-fn io)                                        => Cog
(fork    cog ctx-fn io transition-fn)                          => Cog
(extend  cog io & [transition-fn])                             => Cog

(flow    [ch1 ch2 …] & opts)                                   => IoChannel
(fanout  chs & opts)                                           => IoChannel
(gate    trigger-ch & opts)                                    => IoChannel
(dialogue cogA cogB & opts)                                    => IoChannel

(context cog)   ;; => same as @cog
(:*context cog) ;; => access the context without dereferencing
```

For detailed doc‑strings run:

```clojure
(clojure.repl/doc cog.town/cog)
```

---

## License

MIT © 2025 Brian Scaturro
