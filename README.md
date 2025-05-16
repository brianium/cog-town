# Cog Town 🏘️

Build **agentic workflows** in Clojure with the ergonomics of `core.async`.

`cog.town` gives you a tiny set of composable primitives—**cogs**, **flows**, and **dialogues**—for wiring together stateful, concurrent agents that pass messages over channels.
Think of it as Lego® bricks for conversational or multimodal AI systems.

---

## 5‑minute tour

The goal is core.async semantics for agent workflows.

```clojure
(ns my.ns
  (require [clojure.core.async :as a]
           [clojure.string :as string]
           [cog.town :as cogs]))

;;; 1. Create some cogs
(def echo
  (cogs/cog (atom [])          ; <- stateful context
            (fn [*ctx msg]     ; ctx-atom is the same atom each turn
              (swap! *ctx conj msg)   ; mutate in place
              (last (swap! *ctx conj (str "👋 you said: "  msg))))))

(def shout
  (cogs/cog (atom [])
            (fn [*ctx msg]
              (let [uc (string/upper-case (last (swap! *ctx conj msg)))]
                (last (swap! *ctx conj uc))))))

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

That’s the whole mental model: **channels in, channels out, context in the middle.**

---

## Example workflows

Cog Town doesn't make any assumptions about how these constructs will be used.
The only real assumption is that some blocking work will be performed in order
to interact with a platform.

The example workflows all use Open AI via the [oai-clj library](https://github.com/brianium/oai-clj). All
contexts are simple atoms. I recommend firing up the ol REPL and trying these workflows out (see comment sections in each
sample workflow)

- [Have a real conversation with a person (mic input, speaker output)](dev/workflows/conversation.clj)
- [Listen in on a debate between two agents (speaker output)](dev/workflows/debate.clj)
- [Have a conversation with a sketch artist. He'll draw you a picture when you're done! (mic input, speaker output, visual output)](dev/workflows/multimodal.clj)

Note: These are all using synchronous Open AI services. If interested in building more realtime experiences, check out [reelthyme](https://github.com/brianium/reelthyme).

There are also a handful of helpful examples in [dev.clj](dev/dev.clj)

---

## Core concepts

| Primitive      | What it is                                                                                                                                                                                     | When to reach for it                                           |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| **`cog`**      | A bidirectional channel with private **context** (usually an `atom`) and a **transition** fn that updates that context. Implements `ReadPort`, `WritePort` *and* `Mult` so you can tap extras. | Anytime you want stateful, concurrent behaviour.               |
| **`extend`**   | Light‑weight wrapper around `cog` that **adds or transforms modalities** (e.g. pipe TTS over an existing text cog). Internally calls `fork`.                                                   | Enrich I/O without touching core logic.                        |
| **`flow`**     | Sequentially connects N channels so that the output of each becomes the input of the next.                                                                                                     | Pipelines (ETL, request → AI → TTS, …).                        |
| **`fork`**     | Clones a cog, optionally swapping its context, IO channels or transition fn.                                                                                                                   | Re‑use behaviours with tweaks; create read‑only taps; testing. |
| **`fanout`**   | Sends each value to many channels and gathers their results in order.                                                                                                                          | Scatter–gather, parallel calls.                                |
| **`gate`**     | Releases a stored value once *another* channel yields.                                                                                                                                         | Back‑pressure, synchronising triggers.                         |
| **`dialogue`** | Lets two cogs volley messages ad infinitum.                                                                                                                                                    | Chatbots talking to themselves or staged debates.              |

Every helper returns a **channel** so you can compose them with the usual `core.async` tool‑belt.

---

## API quick‑reference

```clojure
(cog  context transition-fn & [buf-or-n xf ex-handler])            => Cog

;; Fork variants -------------------------------------------------------------
(fork cog)                                                         => Cog
(fork cog context-fn)                                              => Cog
(fork cog context-fn io-chan)                                      => Cog
(fork cog context-fn io-chan transition-fn)                        => Cog

(extend cog io-chan & [transition-fn])                             => Cog

(flow [ch1 ch2 …] & [buf-or-n xf ex-handler])                      => IoChannel
(fanout chs & {:keys [xf buf-or-n ex-handler]})                    => IoChannel
(gate trigger-ch & [buf-or-n xf ex-handler])                       => IoChannel
(dialogue cogA cogB & [buf-or-n xf ex-handler])                    => IoChannel

(context cog)   ;→ whatever context implementation was given to the cog
(cog? x)        ;→ boolean
```

For detailed doc‑strings see the source or run

```clojure
(clojure.repl/doc cog.town/cog)
```

---

## License

MIT © 2025 Brian Scaturro
