(ns swaggerator.handlers
  "Tools for creating handlers from presenters.

  Presenters are functions that process data from the database
  before sending it to the client. The simplest presenter is
  clojure.core/identity - ie. changing nothing.

  Handlers are functions that produce Ring responses from
  Liberator contexts. You pass handlers to resource parameters,
  usually :handle-ok.
  
  Handlers are composed like Ring middleware, but
  THEY ARE NOT RING MIDDLEWARE. They take a Liberator
  context as an argument, not a Ring request.
  When you create your own, follow the naming convention:
  wrap-handler-*, not wrap-*."
  (:use [swaggerator json link util]))

(def ^:dynamic *handled-content-types* (atom []))

(defn wrap-handler-json
  "Wraps a handler with a JSON handler."
  [handler]
  (swap! *handled-content-types* conj "application/json")
  (fn [ctx]
    (case (-> ctx :representation :media-type)
      "application/json" (let [result (handler ctx)
                               k (:data-key result)]
                           (-> result k jsonify))
      (handler ctx))))

; hal is implemented through a ring middleware
; because it needs to capture links that are not from liberator
(defn hal-links [rsp]
  (into {}
    (concatv
      (map (fn [x] [(:rel x) (-> x (dissoc :rel))]) (:links rsp))
      (map (fn [x] [(:rel x) (-> x (dissoc :rel) (assoc :templated true))]) (:link-templates rsp)))))

(defn add-self-hal-link [ctx dk x]
  (let [lm (or ((-> ctx :resource :link-mapping)) {})
        tpl (uri-template-for-rel ctx (dk lm))
        href (expand-uri-template tpl x)]
    (-> x
        (assoc :_links {:self {:href href}}))))

(defn wrap-handler-hal-json
  "Wraps handler with a HAL+JSON handler. Note: consumes links;
  requires wrapping the Ring handler with swaggerator.handlers/wrap-hal-json."
  [handler]
  (swap! *handled-content-types* conj "application/hal+json")
  (fn [ctx]
    (case (-> ctx :representation :media-type)
      "application/hal+json" (let [result (-> ctx handler)
                                   dk (:data-key result)
                                   result (dk result)
                                   result (if (map? result)
                                            result
                                            {:_embedded {dk (map (partial add-self-hal-link ctx dk) result)}})]
                               {:_hal result})
      (handler ctx))))

(defn wrap-hal-json
  "Ring middleware for supporting the HAL+JSON handler wrapper."
  [handler]
  (fn [req]
    (let [rsp (handler req)]
      (if-let [hal (:_hal rsp)]
        (-> rsp
            (assoc :body
                   (-> hal
                       (assoc :_links (hal-links rsp))
                       jsonify))
            (dissoc :link-templates)
            (dissoc :links)
            (dissoc :_hal))
        rsp))))
; /hal

(defn wrap-handler-link
  "Wraps a handler with a function that passes :links and :link-templates
  to the response for consumption by swaggerator.handlers/wrap-hal-json,
  swaggerator.link/wrap-link-header or any other middleware."
  [handler]
  (fn [ctx]
    (let [result (handler ctx)]
      (if (map? result)
        (-> result
            (assoc :links (:links ctx))
            (assoc :link-templates (:link-templates ctx)))
        {:body result
         :links (:links ctx)
         :link-templates (:link-templates ctx)}))))

(defn wrap-default-handler
  "Wraps a handler with wrap-handler-hal-json, wrap-handler-json and wrap-handler-link."
  [handler]
  (-> handler
      wrap-handler-hal-json
      wrap-handler-json
      wrap-handler-link ; last!!
      ))

(defn list-handler
  "Makes a handler that maps a presenter over data that is retrieved
  from the Liberator context by given data key (by default :data)."
  ([presenter] (list-handler presenter :data))
  ([presenter k]
   (fn [ctx]
     (-> ctx
         (assoc :data-key k)
         (assoc k (mapv presenter (k ctx)))))))

(defn default-list-handler
  "list-handler wrapped in wrap-default-handler."
  ([presenter] (default-list-handler presenter :data))
  ([presenter k] (-> (list-handler presenter k)
                     wrap-default-handler)))

(defn entry-handler
  "Makes a handler that applies a presenter to data that is retrieved
  from the Liberator context by given data key (by default :data)."
  ([presenter] (entry-handler presenter :data))
  ([presenter k]
   (fn [ctx]
     (-> ctx
         (assoc :data-key k)
         (assoc k (presenter (k ctx)))))))

(defn default-entry-handler
  "entry-handler wrapped in wrap-default-handler."
  ([presenter] (default-entry-handler presenter :data))
  ([presenter k] (-> (entry-handler presenter k)
                     wrap-default-handler)))