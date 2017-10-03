(in-ns 'game.core)

(declare active? all-installed cards card-init deactivate card-flag? get-card-hosted handle-end-run has-subtype? ice?
         make-eid register-events remove-from-host remove-icon reset-card rezzed? trash trigger-event update-hosted!
         update-ice-strength unregister-events)

;;; Functions for loading card information.
(defn card-def
  "Retrieves a card's abilities definition map."
  [card]
  (when-let [title (:title card)]
    (cards (.replace title "'" ""))))

(defn find-cid
  "Return a card with specific :cid from given sequence"
  [cid from]
  (some #(when (= (:cid %) cid) %) from))

(defn get-scoring-owner
  "Returns the owner of the scoring area the card is in"
  [state {:keys [cid] :as card}]
   (if (find-cid cid (get-in @state [:corp :scored]))
      :corp
      (if (find-cid cid (get-in @state [:runner :scored]))
        :runner
        nil)))

(defn get-card
  "Returns the most recent copy of the card from the current state, as identified
  by the argument's :zone and :cid."
  [state {:keys [cid zone side host type] :as card}]
  (if (= type "Identity")
    (get-in @state [(to-keyword side) :identity])
    (if zone
      (if host
        (get-card-hosted state card)
        (some #(when (= cid (:cid %)) %)
              (let [zones (map to-keyword zone)]
                (if (= (first zones) :scored)
                  (into (get-in @state [:corp :scored]) (get-in @state [:runner :scored]))
                  (get-in @state (cons (to-keyword side) zones))))))
      card)))

;;; Functions for updating cards
(defn update!
  "Updates the state so that its copy of the given card matches the argument given."
  [state side {:keys [type zone cid host] :as card}]
  (if (= type "Identity")
    (when (= side (to-keyword (:side card)))
      (swap! state assoc-in [side :identity] card))
    (if host
      (update-hosted! state side card)
      (let [z (cons (to-keyword (or (get-scoring-owner state card) (:side card))) zone)
            [head tail] (split-with #(not= (:cid %) cid) (get-in @state z))]
        (when-not (empty? tail)
          (swap! state assoc-in z (vec (concat head [card] (rest tail)))))))))

(defn move
  "Moves the given card to the given new zone."
  ([state side card to] (move state side card to nil))
  ([state side {:keys [zone cid host installed] :as card} to {:keys [front keep-server-alive force] :as options}]
   (let [zone (if host (map to-keyword (:zone host)) zone)
         src-zone (first zone)
         target-zone (if (vector? to) (first to) to)
         same-zone? (= src-zone target-zone)]
     (when (and card (or host
                         (some #(when (= cid (:cid %)) %) (get-in @state (cons :runner (vec zone))))
                         (some #(when (= cid (:cid %)) %) (get-in @state (cons :corp (vec zone)))))
                (or (empty? (get-in @state [side :locked (-> card :zone first)]))
                    force))
       (trigger-event state side :pre-card-moved card src-zone target-zone)
       (let [dest (if (sequential? to) (vec to) [to])
             trash-hosted (fn [h]
                             (trash state side
                                    (update-in h [:zone] #(map to-keyword %))
                                    {:unpreventable true :suppress-event true})
                               ())
             update-hosted (fn [h]
                             (let [newz (flatten (list (if (vector? to) to [to])))
                                   newh (-> h
                                      (assoc-in [:zone] '(:onhost))
                                      (assoc-in [:host :zone] newz))]
                               (update! state side newh)
                               (unregister-events state side h)
                               (register-events state side (:events (card-def newh)) newh)
                               newh))
             hosted (seq (flatten (map
                      (if same-zone? update-hosted trash-hosted)
                      (:hosted card))))
             c (if (and (= side :corp) (= (first dest) :discard) (rezzed? card))
                 (assoc card :seen true) card)
             c (if (and (or installed host (#{:servers :scored :current} (first zone)))
                        (#{:hand :deck :discard} (first dest))
                        (not (:facedown c)))
                 (deactivate state side c) c)
             c (if (= dest [:rig :facedown]) (assoc c :facedown true :installed true) (dissoc c :facedown))
             moved-card (assoc c :zone dest :host nil :hosted hosted :previous-zone (:zone c))
             moved-card (if (and (:facedown moved-card) (:installed moved-card))
                          (deactivate state side moved-card) moved-card)
             moved-card (if (and (= side :corp) (#{:hand :deck} (first dest)))
                          (dissoc moved-card :seen) moved-card)
             moved-card (if (and (= (first (:zone moved-card)) :scored) (card-flag? moved-card :has-abilities-when-stolen true))
                          (merge moved-card {:abilities (:abilities (card-def moved-card))}) moved-card)]
         (if front
           (swap! state update-in (cons side dest) #(cons moved-card (vec %)))
           (swap! state update-in (cons side dest) #(conj (vec %) moved-card)))
         (doseq [s [:runner :corp]]
           (if host
             (remove-from-host state side card)
             (swap! state update-in (cons s (vec zone)) (fn [coll] (remove-once #(not= (:cid %) cid) coll)))))
         (let [z (vec (cons :corp (butlast zone)))]
           (when (and (not keep-server-alive)
                      (is-remote? z)
                      (empty? (get-in @state (conj z :content)))
                      (empty? (get-in @state (conj z :ices))))
             (when-let [run (:run @state)]
               (when (= (last (:server run)) (last z))
                 (handle-end-run state side)))
             (swap! state dissoc-in z)))
         (when-let [card-moved (:move-zone (card-def c))]
           (card-moved state side (make-eid state) moved-card card))
         (trigger-event state side :card-moved card moved-card)
         (when (#{:discard :hand} to) (reset-card state side moved-card))
         (when-let [icon-card (get-in moved-card [:icon :card])]
           ;; remove icon if card moved to :discard or :hand
           (when (#{:discard :hand} to) (remove-icon state side icon-card moved-card)))
         moved-card)))))

(defn move-zone
  "Moves all cards from one zone to another, as in Chronos Project."
  [state side server to]
  (when-not (seq (get-in @state [side :locked server]))
    (let [from-zone (cons side (if (sequential? server) server [server]))
          to-zone (cons side (if (sequential? to) to [to]))]
      (swap! state assoc-in to-zone (concat (get-in @state to-zone)
                                            (zone to (get-in @state from-zone))))
      (swap! state assoc-in from-zone []))))

(defn add-prop
  "Adds the given value n to the existing value associated with the key in the card.
  Example: (add-prop ... card :counter 1) adds one power/virus counter. Triggers events."
  ([state side card key n] (add-prop state side card key n nil))
  ([state side card key n {:keys [placed] :as args}]
   (let [updated-card (if (has-subtype? card "Virus")
                        (assoc card :added-virus-counter true)
                        card)]
     (update! state side (update-in updated-card [key] #(+ (or % 0) n)))
     (if (= key :advance-counter)
       (do (when (and (ice? updated-card) (rezzed? updated-card)) (update-ice-strength state side updated-card))
           (if-not placed
             (trigger-event state side :advance (get-card state updated-card))
             (trigger-event state side :advancement-placed (get-card state updated-card))))
       (trigger-event state side :counter-added (get-card state updated-card))))))

(defn set-prop
  "Like add-prop, but sets multiple keys to corresponding values without triggering events.
  Example: (set-prop ... card :counter 4 :current-strength 0)"
  [state side card & args]
  (update! state side (apply assoc (cons card args))))

(defn add-counter
  "Adds n counters of the specified type to a card"
  ([state side card type n] (add-counter state side card type n nil))
  ([state side card type n {:keys [placed] :as args}]
   (let [updated-card (if (= type :virus)
                        (assoc card :added-virus-counter true)
                        card)]
     (update! state side (update-in updated-card [:counter type] #(+ (or % 0) n)))
     (if (= type :advancement)
       ;; if advancement counter use existing system
       (add-prop state side card :advance-counter n args)
       (trigger-event state side :counter-added (get-card state updated-card))))))

;;; Deck-related functions
(defn shuffle!
  "Shuffles the vector in @state [side kw]."
  [state side kw]
  (when-completed (trigger-event-sync state side (keyword (str (name side) "-shuffle-deck")))
                  (swap! state update-in [side kw] shuffle)))

(defn shuffle-into-deck
  [state side & args]
  (let [player (side @state)
        zones (filter #(not (seq (get-in @state [side :locked %]))) args)
        deck (shuffle (reduce concat (:deck player) (for [p zones] (zone :deck (p player)))))]
    (swap! state assoc-in [side :deck] deck)
    (doseq [p zones]
      (swap! state assoc-in [side p] []))))

;;; Misc card functions
(defn get-virus-counters
  "Calculate the number of virus countes on the given card, taking Hivemind into account."
  [state side card]
  (let [hiveminds (filter #(= (:title %) "Hivemind") (all-installed state :runner))]
    (reduce + (map #(get-in % [:counter :virus] 0) (cons card hiveminds)))))

(defn card->server
  "Returns the server map that this card is installed in or protecting."
  [state card]
  (let [z (:zone card)]
    (get-in @state [:corp :servers (second z)])))

(defn disable-identity
  "Disables the side's identity"
  [state side]
  (let [id (assoc (:identity (side @state)) :disabled true)]
    (update! state side id)
    (unregister-events state side id)
    (when-let [leave-play (:leave-play (card-def id))]
      (leave-play state side (make-eid state) id nil))))

(defn disable-card
  "Disables a card"
  [state side card]
  (let [c (assoc card :disabled true)]
    (deactivate state side card)
    (update! state side c)))

(defn enable-identity
  "Enables the side's identity"
  [state side]
  (let [id (assoc (:identity (side @state)) :disabled false)
        cdef (card-def id)
        events (:events cdef)]
    (update! state side id)
    (when-let [eff (:effect cdef)]
      (eff state side (make-eid state) id nil))
    (when events
      (register-events state side events id))))

(defn enable-card
  "Enables a disabled card"
  [state side {:keys [disabled] :as card}]
  (when disabled
    (let [c (dissoc card :disabled)]
      (update! state side c)
      (when (active? card)
        (card-init state side c false)))))
