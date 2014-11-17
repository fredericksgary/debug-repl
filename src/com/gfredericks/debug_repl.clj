(ns com.gfredericks.debug-repl
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [com.gfredericks.debug-repl.util :as util])
  (:import (java.util.concurrent ArrayBlockingQueue)))

;; TODO:
;;   - Close nrepl sessions after unbreak!
;;   - Report the correct ns so the repl switches back & forth?
;;   - Avoid reporting :done multiple times
;;   - Suppress the return value from (unbreak!)? this would avoid
;;     the command returning two results...
;;   - Detect when (break!) is called but the middleware is missing?
;;     And give a helpful error message.
;;   - Better reporting on how many nested repls there are, etc

(defonce
  ^{:doc
    "A map from nrepl session IDs to a stack of debug repl maps, each of which
     contain:

    :unbreak -- a 0-arg function which will cause the thread of
                execution to resume when it is called
    :nested-session-id -- the nrepl session ID being used to evaluate code
                          for this repl
    :eval -- a function that takes a code string and returns the result of
             evaling in this repl."}
  active-debug-repls
  (atom {}))

(defmacro current-locals
  "Returns a map from symbols of locals in the lexical scope to their
  values."
  []
  (into {}
        (for [name (keys &env)]
          [(list 'quote name) name])))


(defn break
  [locals breakpoint-name ns]
  (let [{:keys [transport],
         session-id ::orig-session-id
         nest-session-fn ::nest-session}
        *msg*

        unbreak-p (promise)
        ;; probably never need more than 1 here
        eval-requests (ArrayBlockingQueue. 2)]
    (when-not (-> @active-debug-repls
                  (get session-id)
                  (meta)
                  ::no-more-breaks?)
     (swap! active-debug-repls update-in [session-id] conj
            {:unbreak           (fn [] (deliver unbreak-p nil))
             :nested-session-id (nest-session-fn)
             :eval              (fn [code]
                                  (let [result-p (promise)]
                                    (.put eval-requests [code result-p])
                                    (util/uncatch @result-p)))})
     (transport/send transport
                     (response-for *msg*
                                   {:out (str "Hijacking repl for breakpoint: "
                                              breakpoint-name)}))
     (transport/send transport
                     (response-for *msg*
                                   {:status #{:done}}))
     (loop []
       (when-not (realized? unbreak-p)
         (if-let [[code result-p] (.poll eval-requests)]
           (let [code' (format "(fn [{:syms [%s]}]\n%s\n)"
                               (clojure.string/join " " (keys locals))
                               code)]
             (deliver result-p
                      (util/catchingly
                       ((binding [*ns* ns] (eval (read-string code'))) locals))))
           (Thread/sleep 50))
         (recur))))
    nil))

(defmacro break!
  "Use only with the com.gfredericks.debug-repl/wrap-debug-repl middleware.

  Causes execution to stop and the repl switches to evaluating code in the
  context of the breakpoint. Resume exeution by calling (unbreak!). REPL
  code can result in a nested call to break! which will work in a reasonable
  way. Nested breaks require multiple calls to (unbreak!) to undo."
  ([]
     `(break! "unnamed"))
  ([breakpoint-name]
     `(break (current-locals)
             ~breakpoint-name
             ~*ns*)))

(defn unbreak!
  "Causes the latest breakpoint to resume execution; the repl returns to the
  state it was in prior to the breakpoint."
  []
  (let [{session-id ::orig-session-id} *msg*
        f (-> @active-debug-repls
              (get session-id)
              (peek)
              (:unbreak))]
    (when-not f
      (throw (Exception. "No debug-repl to unbreak from!")))
    ;; TODO: dissoc as well? (minor memory leak)
    (swap! active-debug-repls update-in [session-id] pop)
    (f)
    nil))

(defn unbreak-for-good!
  []
  (unbreak!)
  (let [{session-id ::orig-session-id
         orig-handler ::orig-handler}
        *msg*]
    (swap! active-debug-repls
           update-in
           [session-id] vary-meta assoc ::no-more-breaks? true)
    (orig-handler
     {:op "eval"
      :code (pr-str
             `(swap! active-debug-repls
                     update-in [~session-id] vary-meta
                     dissoc ::no-more-breaks?))}))

  nil)

(defn ^:private wrap-transport-sub-session
  [t from-session to-session]
  (reify transport/Transport
    (recv [this] (transport/recv t))
    (recv [this timeout] (transport/recv t timeout))
    (send [this msg]
      (let [msg' (cond-> msg (= from-session (:session msg)) (assoc :session to-session))]
        (transport/send t msg')))))

(defn ^:private wrap-eval
  [{:keys [op code session] :as msg}]
  (let [nested-session-id (-> @active-debug-repls
                              (get session)
                              (peek)
                              (:nested-session-id))]
    (cond-> msg
            nested-session-id
            (-> (assoc :session nested-session-id)
                (update-in [:transport] wrap-transport-sub-session nested-session-id session))


            (and nested-session-id (= "eval" op))
            (assoc :code
              (pr-str
               `((-> @active-debug-repls
                     (get ~session)
                     (peek)
                     (:eval))
                 ~code))))))

(defn ^:private handle-debug
  [handler {:keys [transport op code session] :as msg}]
  (-> msg
      (assoc ::orig-session-id session
             ::orig-handler handler
             ::nest-session (fn []
                              {:post [%]}
                              (let [p (promise)]
                                (handler {:session session
                                          :op "clone"
                                          :transport (reify transport/Transport
                                                       (send [_ msg]
                                                         (deliver p msg)))})
                                (:new-session @p))))
      (wrap-eval)
      (handler)))

(defn wrap-debug-repl
  [handler]
  ;; having handle-debug as a separate function makes it easier to do
  ;; interactive development on this middleware
  (fn [msg] (handle-debug handler msg)))

(set-descriptor! #'wrap-debug-repl
                 {:expects #{"eval" "clone"}})
