(ns com.stronganchortech.pouchdb-fx
  (:require
   [clojure.spec.alpha :as s]
   [re-frame.core :as rf]
   ["pouchdb" :as pouchdb]
   ["pouchdb-find" :as pouchdb-find]))

(defonce ^:private dbs (atom {}))
(defonce install-plugins (pouchdb/plugin pouchdb-find))

(defn create-or-open-db!
  "Uses the PouchDB initializer to open an existing database or create a new one.
  Pouch doesn't tell us whether the database existed or not, so we just return the db.
  https://pouchdb.com/api.html#create_database"
  [db-name]
  (println "create-or-open-db!" db-name dbs)
  (let [new-dbs (swap! dbs (fn [dbs]
                             (if (get dbs db-name)
                               dbs
                               (assoc dbs db-name {:db-obj (pouchdb. db-name)}))))]
    (get-in new-dbs [db-name :db-obj])))

(defn db-obj
  "Given a database object or a database name, will return the corresponding database object."
  [db]
  (if (or (keyword? db) (string? db))
    (or (get-in @dbs [db :db-obj]) ;; grab it from the cache
        (create-or-open-db! db)) ;; or open/create a new one
    db))

(defn- make-fn
  "Internal helper function that takes in either a fn or a keyword.
  If passed a function, returns that same function.
  If passed a keyword, will return a function that dispatches that keyword
  as a re-frame event."
  [fn-or-re-frame-event]
  (if (keyword? fn-or-re-frame-event)
    #(rf/dispatch [fn-or-re-frame-event])
    fn-or-re-frame-event))

(defn- attach-success-and-failure-to-promise
  "Takes a promise and attaches optional success and failure handlers."
  [promise success failure]
  (let [success (cond
                  (keyword? success)
                  #(rf/dispatch [success (js->clj % :keywordize-keys true)])
                  (not (nil? success))
                  #(success (js->clj % :keywordize-keys true))
                  :default
                  (fn [] nil))
        failure (cond
                  (keyword? failure)
                  #(rf/dispatch [failure (js->clj % :keywordize-keys true)])
                  (not (nil? failure))    
                  #(failure (js->clj % :keywordize-keys true))
                  :default
                  (fn [] nil))
        ]
    (.catch (.then promise success) failure)))

(defn- attach-handlers
  "Takes an object and attaches various handlers specified in the handlers map.
  Keys should be keywords corresponging to the handler name (e.g. :changes for 'changes') and the values should be functions or pouchdb event keywords."
  [obj handlers]
  (reduce-kv (fn [m k v]
               (.on m (name k) (make-fn v)))
             obj
             handlers))

(defn attach-change-watcher!
  "Attaches the provided change-handler to the db specified by db-name.
   Cancels existing watchers attached to the db."
  [db-name options change-handler]
  (create-or-open-db! db-name) ;; make sure that the db is created before attaching a change handler.
  (swap! dbs (fn [dbs]
               (let [old-watcher (get-in dbs [db-name :change-watcher])
                     db-obj (get-in dbs [db-name :db-obj])
                     change-handler (if (keyword? change-handler)
                                      #(rf/dispatch [change-handler (js->clj % :keywordize-keys true)])
                                      change-handler)]
                 (when old-watcher (.cancel old-watcher))
                 (assoc-in dbs [db-name :change-watcher]
                           (.on (.changes db-obj (clj->js options)) "change" change-handler))))))

(defn cancel-watcher!
  "Cancels all existing watchers on the db specified by db-name."
  [db-name]
  (let [watcher (get-in @dbs [db-name :change-watcher])]
    (when watcher (.cancel watcher))))

(defn sync!
  ([db-name target options]
   (sync! db-name target options {}))
  ([db-name target options handlers]
   (swap! dbs (fn [dbs]
                (let [old-sync-obj (get-in dbs [db-name :sync-obj])]
                  (when old-sync-obj (.cancel old-sync-obj))
                  (let [sync-obj (.sync pouchdb db-name target (clj->js options))]
                    (assoc-in dbs [db-name :sync-obj] (attach-handlers sync-obj handlers))))))))

(defn cancel-sync!
  [db-name]
  (println "In cancel-sync for " db-name @dbs)
  (swap! dbs (fn [dbs]
               (when-let [sync-obj (get-in dbs [db-name :sync-obj])]
                 (println "Calling .cancel on sync-obj: " sync-obj)
                 (.cancel sync-obj)))))

;; For the underlying API documentation, please see https://pouchdb.com/api.html
(rf/reg-fx
 :pouchdb
 (fn [{:keys [method db doc docs doc-id attachment-id rev attachment attachment-type source target options success failure handler handlers outbound? diff index map-reduce-fn] :as request}]
   (let [db-name (when (string? db) db)
         db (or (db-obj db) ;; set db to be the actual db object
                (if db
                  (throw (js/Error. (str "PouchDB " db " not found." @dbs)))
                  (throw (js/Error. (str ":db needs to be specified in: " request)))))
         options (or options {})
         doc-id (or doc-id (:_id doc))  ;; enable devs to pass a doc instead of pulling the id out
         rev    (or rev    (:_rev doc)) ;; enable devs to pass a doc instead of pulling the rev out
         ]
     (case method
       ;;
       :destroy
       (attach-success-and-failure-to-promise
        (.destroy db)
        success failure)
       ;;
       :put
       (attach-success-and-failure-to-promise
        (.put db (clj->js doc) (clj->js options))
        success failure)
       ;;
       :post
       (attach-success-and-failure-to-promise
        (.post db (clj->js doc) (clj->js options))
        success failure)
       ;;
       :get
       (attach-success-and-failure-to-promise
        (.get db doc-id (clj->js options))
        success failure)
       ;;
       :remove
       (attach-success-and-failure-to-promise
        (.remove db (clj->js doc) (clj->js options))
        success failure)
       ;;
       :bulk-docs
       (attach-success-and-failure-to-promise
        (.bulkDocs db (clj->js docs) (clj->js options))
        success failure)
       ;;
       :all-docs
       (attach-success-and-failure-to-promise
        (.allDocs db (clj->js options))
        success failure)
       ;;
       :attach-change-watcher!
       (attach-change-watcher! db-name (clj->js options) handler)
       ;;
       :cancel-watcher!
       (cancel-watcher! db-name)
       ;; 
       :replicate
       (attach-handlers
        (pouchdb/replicate (if outbound? db target) (if outbound? target db) (clj->js options))
        handlers)
       ;;
       :sync!
       (sync! db-name target options handlers)
       ;; TODO return information on the sync objects that have been configured -- e.g. what database you are syncing to.
       ;;
       :cancel-sync!
       (cancel-sync! db-name)
       ;;
       :put-attachment
       (attach-success-and-failure-to-promise
        (.putAttachment db doc-id attachment-id rev attachment attachment-type)
        success failure)
       ;;
       :get-attachment
       (attach-success-and-failure-to-promise
        (.getAttachment db doc-id attachment-id (clj->js options))
        success failure)
       ;;
       :remove-attachment
       (attach-success-and-failure-to-promise
        (.removeAttachment db doc-id attachment-id rev)
        success failure)
       ;; 
       :createIndex
       (attach-success-and-failure-to-promise
        (.createIndex db (clj->js index))
        success failure)
       ;; 
       :find
       (attach-success-and-failure-to-promise
        (.find db (clj->js (:request request)))
        success failure)
       ;;
       :explain
       (attach-success-and-failure-to-promise
        (.explain db (clj->js (:request request)))
        success failure)
       ;; 
       :getIndexes
       (attach-success-and-failure-to-promise
        (.getIndexes db)
        success failure)
       ;;
       :deleteIndex
       (attach-success-and-failure-to-promise
        (.deleteIndex db (clj->js index))
        success failure)
       ;;
       :query
       (attach-success-and-failure-to-promise
        (.query db (clj->js map-reduce-fn) (clj->js options))
        success failure)
       ;;
       :viewCleanup
       (attach-success-and-failure-to-promise
        (.viewCleanup db)
        success failure)
       ;;
       :info
       (attach-success-and-failure-to-promise
        (.info db)
        success failure)
       ;;
       :compact
       (attach-success-and-failure-to-promise
        (.compact db (clj->js options))
        success failure)
       ;; 
       :revsDiff
       (attach-success-and-failure-to-promise
        (.revsDiff db (clj->js diff))
        success failure)
       ;; 
       :bulkGet
       (attach-success-and-failure-to-promise
        (.bulkGet db (clj->js options))
        success failure)
       ;;
       :close
       (attach-success-and-failure-to-promise
        (.close db)
        success failure)
       ;;
       (throw (js/Error. (str "The requested method: " method " is not supported by com.stronganchortech.pouchdb-fx.")))
       ))))

;; This is an event handler that wraps the effects handler.
;; That way you don't have to write your own event handlers for simple PouchDB calls.
(rf/reg-event-fx
 :pouchdb
 (fn [_ [event args]]
   {:pouchdb args}))
