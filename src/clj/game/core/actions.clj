(in-ns 'game.core)

;; These functions are called by main.clj in response to commands sent by users.

(declare card-str can-rez? can-advance? corp-install effect-as-handler enforce-msg gain-agenda-point get-remote-names
         get-run-ices jack-out move name-zone play-instant purge resolve-select run has-subtype?
         runner-install trash update-breaker-strength update-ice-in-server update-run-ice win can-run?
         can-run-server? can-score? play-sfx)

;;; Neutral actions
(defn play
  "Called when the player clicks a card from hand."
  [state side {:keys [card server]}]
  (let [card (get-card state card)]
    (case (:type card)
      ("Event" "Operation") (play-instant state side card {:extra-cost [:click 1]})
      ("Hardware" "Resource" "Program") (runner-install state side (make-eid state) card {:extra-cost [:click 1]})
      ("ICE" "Upgrade" "Asset" "Agenda") (corp-install state side card server {:extra-cost [:click 1] :action :corp-click-install}))
    (trigger-event state side :play card)))

(defn shuffle-deck
  "Shuffle R&D/Stack."
  [state side {:keys [close] :as args}]
  (swap! state update-in [side :deck] shuffle)
  (if close
    (do
      (swap! state update-in [side] dissoc :view-deck)
      (system-msg state side "stops looking at their deck and shuffles it"))
    (system-msg state side "shuffles their deck")))

(defn click-draw
  "Click to draw."
  [state side args]
  (when (and (not (get-in @state [side :register :cannot-draw]))
             (pay state side nil :click 1 {:action :corp-click-draw}))
    (system-msg state side "spends [Click] to draw a card")
    (trigger-event state side (if (= side :corp) :corp-click-draw :runner-click-draw) (->> @state side :deck (take 1)))
    (draw state side)
    (play-sfx state side "click-card")))

(defn click-credit
  "Click to gain 1 credit."
  [state side args]
  (when (pay state side nil :click 1 {:action :corp-click-credit})
    (system-msg state side "spends [Click] to gain 1 [Credits]")
    (gain state side :credit 1)
    (trigger-event state side (if (= side :corp) :corp-click-credit :runner-click-credit))
    (play-sfx state side "click-credit")))

(defn change
  "Increase/decrease a player's property (clicks, credits, MU, etc.) by delta."
  [state side {:keys [key delta]}]
  (let [kw (to-keyword key)]
    (if (neg? delta)
      (deduce state side [kw (- delta)])
      (swap! state update-in [side kw] (partial + delta)))
    (system-msg state side
                (str "sets " (.replace key "-" " ") " to " (get-in @state [side kw])
                     " (" (if (pos? delta) (str "+" delta) delta) ")"))))

(defn move-card
  "Called when the user drags a card from one zone to another."
  [state side {:keys [card server]}]
  (let [c (get-card state card)
        ;; hack: if dragging opponent's card from play-area (Indexing), the previous line will fail
        ;; to find the card. the next line will search in the other player's play-area.
        c (or c (get-card state (assoc card :side (other-side (to-keyword (:side card))))))
        last-zone (last (:zone c))
        src (name-zone (:side c) (:zone c))
        from-str (when-not (nil? src) (str " from their " src))
        label (if (and (not= last-zone :play-area)
                       (not (and (= (:side c) "Runner")
                                 (= last-zone :hand)
                                 (= server "Grip")))
                       (or (and (= (:side c) "Runner")
                                (not (:facedown c)))
                           (rezzed? c)
                           (:seen c)
                           (= last-zone :deck)))
                (:title c)
                "a card")
        s (if (#{"HQ" "R&D" "Archives"} server) :corp :runner)]
    ;; allow moving from play-area always, otherwise only when same side, and to valid zone
    (when (and (not= src server)
               (same-side? s (:side card))
               (or (= last-zone :play-area)
                   (same-side? side (:side card))))
      (case server
        ("Heap" "Archives")
        (do (let [action-str (if (= (first (:zone c)) :hand) "discards " "trashes ")]
              (trash state s c {:unpreventable true})
              (system-msg state side (str action-str label from-str))))
        ("Grip" "HQ")
        (do (move state s (dissoc c :seen :rezzed) :hand {:force true})
            (system-msg state side (str "moves " label from-str " to " server)))
        ("Stack" "R&D")
        (do (move state s (dissoc c :seen :rezzed) :deck {:front true :force true})
            (system-msg state side (str "moves " label from-str " to the top of " server)))
        nil))))

(defn concede [state side args]
  (system-msg state side "concedes")
  (win state (if (= side :corp) :runner :corp) "Concede"))

(defn- finish-prompt [state side prompt card]
  (when-let [end-effect (:end-effect prompt)]
    (end-effect state side (make-eid state) card nil))
  ;; remove the prompt from the queue
  (swap! state update-in [side :prompt] (fn [pr] (filter #(not= % prompt) pr)))
  ;; This is a dirty hack to end the run when the last access prompt is resolved.
  (when (empty? (get-in @state [:runner :prompt]))
    (when-let [run (:run @state)]
      (when (:ended run)
        (handle-end-run state :runner)))
    (swap! state dissoc :access)))

(defn resolve-prompt
  "Resolves a prompt by invoking its effect funtion with the selected target of the prompt.
  Triggered by a selection of a prompt choice button in the UI."
  [state side {:keys [choice card] :as args}]
  (let [servercard (get-card state card)
        card (if (not= (:title card) (:title servercard))
               (@all-cards (:title card))
               servercard)
        prompt (first (get-in @state [side :prompt]))
        choices (:choices prompt)
        choice (if (= (:choices prompt) :credit)
                 (min choice (get-in @state [side :credit]))
                 choice)]
    (if (not= choice "Cancel")
      (if (:card-title choices) ; check the card has a :card-title function
        (let [title-fn (:card-title choices)
              found (some #(when (= (lower-case choice) (lower-case (:title % ""))) %) (vals @all-cards))]
          (if found
            (if (title-fn state side (make-eid state) (:card prompt) [found])
              (do ((:effect prompt) (or choice card))
                  (finish-prompt state side prompt card))
              (toast state side (str "You cannot choose " choice " for this effect.") "warning"))
            (toast state side (str "Could not find a card named " choice ".") "warning")))
        (do (when (= choices :credit) ; :credit prompts require payment
              (pay state side card :credit choice))
            (when (and (map? choices)
                       (:counter choices))
              ;; :Counter prompts deduct counters from the card
              (add-counter state side (:card prompt) (:counter choices) (- choice)))
            ;; trigger the prompt's effect function
            ((:effect prompt) (or choice card))
            (finish-prompt state side prompt card)))
      (do (if-let [cancel-effect (:cancel-effect prompt)]
            ;; trigger the cancel effect
            (cancel-effect choice)
            (effect-completed state side (:eid prompt) nil))
          (finish-prompt state side prompt card)))))

(defn select
  "Attempt to select the given card to satisfy the current select prompt. Calls resolve-select
  if the max number of cards has been selected."
  [state side {:keys [card] :as args}]
  (let [card (get-card state card)
        r (get-in @state [side :selected 0 :req])]
    (when (or (not r) (r card))
      (let [c (update-in card [:selected] not)]
        (update! state side c)
        (if (:selected c)
          (swap! state update-in [side :selected 0 :cards] #(conj % c))
          (swap! state update-in [side :selected 0 :cards]
                 (fn [coll] (remove-once #(not= (:cid %) (:cid card)) coll))))
        (let [selected (get-in @state [side :selected 0])]
          (when (= (count (:cards selected)) (or (:max selected) 1))
            (resolve-select state side)))))))

(defn- do-play-ability [state side card ability targets]
  (let [cost (:cost ability)]
    (when (or (nil? cost)
              (if (has-subtype? card "Run")
                (apply can-pay? state side (:title card) cost (get-in @state [:bonus :run-cost]))
                (apply can-pay? state side (:title card) cost)))
      (when-let [activatemsg (:activatemsg ability)]
        (system-msg state side activatemsg))
      (resolve-ability state side ability card targets))))

(defn play-ability
  "Triggers a card's ability using its zero-based index into the card's card-def :abilities vector."
  [state side {:keys [card ability targets] :as args}]
  (let [card (get-card state card)
        cdef (card-def card)
        abilities (:abilities cdef)
        ab (if (= ability (count abilities))
             ;; recurring credit abilities are not in the :abilities map and are implicit
             {:msg "take 1 [Recurring Credits]" :req (req (> (:rec-counter card) 0))
              :effect (req (add-prop state side card :rec-counter -1)
                             (gain state side :credit 1)
                           (when (has-subtype? card "Stealth")
                             (trigger-event state side :spent-stealth-credit card)))}
             (get-in cdef [:abilities ability]))]
    (when-not (:disabled card)
      (do-play-ability state side card ab targets))))

(defn play-auto-pump
  "Use the 'match strength with ice' function of icebreakers."
  [state side args]
  (let [run (:run @state) card (get-card state (:card args))
        current-ice (when (and run (> (or (:position run) 0) 0)) (get-card state ((get-run-ices state) (dec (:position run)))))
        pumpabi (some #(when (:pump %) %) (:abilities (card-def card)))
        pumpcst (when pumpabi (second (drop-while #(and (not= % :credit) (not= % "credit")) (:cost pumpabi))))
        strdif (when current-ice (max 0 (- (or (:current-strength current-ice) (:strength current-ice))
                                           (or (:current-strength card) (:strength card)))))
        pumpnum (when strdif (int (Math/ceil (/ strdif (:pump pumpabi)))))]
    (when (and pumpnum pumpcst (>= (get-in @state [:runner :credit]) (* pumpnum pumpcst)))
      (dotimes [n pumpnum] (resolve-ability state side (dissoc pumpabi :msg) (get-card state card) nil))
      (system-msg state side (str "spends " (* pumpnum pumpcst) " [Credits] to increase the strength of "
                                  (:title card) " to " (:current-strength (get-card state card)))))))

(defn play-copy-ability
  "Play an ability from another card's definition."
  [state side {:keys [card source index] :as args}]
  (let [card (get-card state card)
        source-abis (:abilities (cards (.replace source "'" "")))
        abi (when (< -1 index (count source-abis))
              (nth source-abis index))]
    (when abi
      (do-play-ability state side card abi nil))))

(def dynamic-abilities
  {"auto-pump" play-auto-pump
   "copy" play-copy-ability})

(defn play-dynamic-ability
  "Triggers an ability that was dynamically added to a card's data but is not necessarily present in its
  :abilities vector."
  [state side args]
  ((dynamic-abilities (:dynamic args)) state (keyword side) args))

(defn play-runner-ability
  "Triggers a corp card's runner-ability using its zero-based index into the card's card-def :runner-abilities vector."
  [state side {:keys [card ability targets] :as args}]
  (let [cdef (card-def card)
        ab (get-in cdef [:runner-abilities ability])]
    (do-play-ability state side card ab targets)))

(defn play-subroutine
  "Triggers a card's subroutine using its zero-based index into the card's card-def :subroutines vector."
  ([state side args] (play-subroutine state side (make-eid state) args))
  ([state side eid {:keys [card subroutine targets] :as args}]
   (let [cdef (card-def card)
         sub (get-in cdef [:subroutines subroutine])
         cost (:cost sub)]
     (when (or (nil? cost)
               (apply can-pay? state side (:title card) cost))
       (when-let [activatemsg (:activatemsg sub)] (system-msg state side activatemsg))
       (resolve-ability state side eid sub card targets)))))

;;; Corp actions
(defn trash-resource
  "Click to trash a resource."
  [state side args]
  (let [trash-cost (max 0 (- 2 (or (get-in @state [:corp :trash-cost-bonus]) 0)))]
    (when-let [cost-str (pay state side nil :click 1 :credit trash-cost {:action :corp-trash-resource})]
      (resolve-ability state side
                       {:prompt  "Choose a resource to trash"
                        :choices {:req (fn [card]
                                         (if (and (seq (filter (fn [c] (untrashable-while-resources? c)) (all-installed state :runner)))
                                                  (> (count (filter #(is-type? % "Resource") (all-installed state :runner))) 1))
                                           (and (is-type? card "Resource") (not (untrashable-while-resources? card)))
                                           (is-type? card "Resource")))}
                        :effect  (effect (trash target)
                                         (system-msg (str (build-spend-msg cost-str "trash")
                                                          (:title target))))} nil nil))))

(defn do-purge
  "Purge viruses."
  [state side args]
  (when-let [cost (pay state side nil :click 3 {:action :corp-click-purge})]
    (purge state side)
    (let [spent (build-spend-msg cost "purge")
          message (str spent "all virus counters")]
      (system-msg state side message))
    (play-sfx state side "virus-purge")))

(defn rez
  "Rez a corp card."
  ([state side card] (rez state side (make-eid state) card nil))
  ([state side card args]
   (rez state side (make-eid state) card args))
  ([state side eid {:keys [disabled] :as card} {:keys [ignore-cost no-warning force no-get-card paid-alt] :as args}]
   (let [card (if no-get-card
                card
                (get-card state card))
         altcost (when-not no-get-card
                   (:alternative-cost (card-def card)))]
     (if (or force (can-rez? state side card))
       (do
         (trigger-event state side :pre-rez card)
         (if (or (#{"Asset" "ICE" "Upgrade"} (:type card))
                   (:install-rezzed (card-def card)))
           (do (trigger-event state side :pre-rez-cost card)
               (if (and altcost (can-pay? state side nil altcost)(not ignore-cost))
                 (prompt! state side card (str "Pay the alternative Rez cost?") ["Yes" "No"]
                          {:delayed-completion true
                           :effect (req (if (and (= target "Yes")
                                                 (can-pay? state side (:title card) altcost))
                                          (do (pay state side card altcost)
                                              (rez state side (-> card (dissoc :alternative-cost))
                                                   {:ignore-cost true
                                                    :no-get-card true
                                                    :paid-alt true}))
                                          (rez state side (-> card (dissoc :alternative-cost))
                                               {:no-get-card true})))})
                 (let [cdef (card-def card)
                       cost (rez-cost state side card)
                       costs (concat (when-not ignore-cost [:credit cost])
                                     (when (and (not= ignore-cost :all-costs)
                                                (not (:disabled card)))
                                       (:additional-cost cdef)))]
                   (when-let [cost-str (apply pay state side card costs)]
                     ;; Deregister the derezzed-events before rezzing card
                     (when (:derezzed-events cdef)
                       (unregister-events state side card))
                     (if (not disabled)
                       (card-init state side (assoc card :rezzed :this-turn))
                       (update! state side (assoc card :rezzed :this-turn)))
                     (doseq [h (:hosted card)]
                       (update! state side (-> h
                                               (update-in [:zone] #(map to-keyword %))
                                               (update-in [:host :zone] #(map to-keyword %)))))
                     (system-msg state side (str (build-spend-msg cost-str "rez" "rezzes")
                                                 (:title card)
                                                 (cond
                                                   paid-alt
                                                   " by paying its alternative cost"

                                                   ignore-cost
                                                   " at no cost")))
                     (when (and (not no-warning) (:corp-phase-12 @state))
                       (toast state :corp "You are not allowed to rez cards between Start of Turn and Mandatory Draw.
                        Please rez prior to clicking Start Turn in the future." "warning"
                              {:time-out 0 :close-button true}))
                     (if (ice? card)
                       (do (update-ice-strength state side card)
                           (play-sfx state side "rez-ice"))
                       (play-sfx state side "rez-other"))
                     (trigger-event-sync state side eid :rez card)))))
           (effect-completed state side eid))
         (swap! state update-in [:bonus] dissoc :cost))
       (effect-completed state side eid)))))

(defn derez
  "Derez a corp card."
  [state side card]
  (let [card (get-card state card)]
    (system-msg state side (str "derezzes " (:title card)))
    (update! state :corp (deactivate state :corp card true))
    (let [cdef (card-def card)]
      (when-let [derez-effect (:derez-effect cdef)]
        (resolve-ability state side derez-effect (get-card state card) nil))
      (when-let [dre (:derezzed-events cdef)]
        (register-events state side dre card)))
    (trigger-event state side :derez card side)))

(defn advance
  "Advance a corp card that can be advanced.
   If you pass in a truthy value as the 4th parameter, it will advance at no cost (for the card Success)."
  ([state side {:keys [card]}] (advance state side card nil))
  ([state side card no-cost]
   (let [card (get-card state card)]
     (when (can-advance? state side card)
       (when-let [cost (pay state side card :click (if-not no-cost 1 0)
                            :credit (if-not no-cost 1 0) {:action :corp-advance})]
         (let [spent   (build-spend-msg cost "advance")
               card    (card-str state card)
               message (str spent card)]
           (system-msg state side message))
         (update-advancement-cost state side card)
         (add-prop state side (get-card state card) :advance-counter 1)
         (play-sfx state side "click-advance"))))))

(defn score
  "Score an agenda. It trusts the card data passed to it."
  [state side args]
  (let [card (or (:card args) args)]
    (when (and (can-score? state side card)
               (empty? (filter #(= (:cid card) (:cid %)) (get-in @state [:corp :register :cannot-score])))
               (>= (:advance-counter card 0) (or (:current-cost card) (:advancementcost card))))

      ;; do not card-init necessarily. if card-def has :effect, wrap a fake event
      (let [moved-card (move state :corp card :scored)
            c (card-init state :corp moved-card false)
            points (get-agenda-points state :corp c)]
        (trigger-event-simult
          state :corp (make-eid state) :agenda-scored
          {:first-ability {:effect (req (when-let [current (first (get-in @state [:runner :current]))]
                                          (say state side {:user "__system__" :text (str (:title current) " is trashed.")})
                                          ; This is to handle Employee Strike with damage IDs #2688
                                          (when (:disable-id (card-def current))
                                            (swap! state assoc-in [:corp :disable-id] true))
                                          (trash state side current)))}
           :card-ability (card-as-handler c)
           :after-active-player {:effect (req (let [c (get-card state c)
                                                    points (or (get-agenda-points state :corp c) points)]
                                                (set-prop state :corp (get-card state moved-card) :advance-counter 0)

                                                (system-msg state :corp (str "scores " (:title c) " and gains " points
                                                                             " agenda point" (when (> points 1) "s")))
                                                (swap! state update-in [:corp :register :scored-agenda] #(+ (or % 0) points))
                                                (swap! state dissoc [:corp :disable-id])
                                                (gain-agenda-point state :corp points)
                                                (play-sfx state side "agenda-score")))}}
          c)))))

(defn no-action
  "The corp indicates they have no more actions for the encounter."
  [state side args]
  (swap! state assoc-in [:run :no-action] true)
  (system-msg state side "has no further action")
  (trigger-event state side :no-action)
  (let [run-ice (get-run-ices state)
        pos (get-in @state [:run :position])
        ice (when (and pos (pos? pos) (<= pos (count run-ice)))
              (get-card state (nth run-ice (dec pos))))]
    (when (rezzed? ice)
      (trigger-event state side :encounter-ice ice)
      (update-ice-strength state side ice))))

;;; Runner actions
(defn click-run
  "Click to start a run."
  [state side {:keys [server] :as args}]
  (let [cost-bonus (get-in @state [:bonus :run-cost])
        click-cost-bonus (get-in @state [:bonus :click-run-cost])]
    (when (and (can-run? state side)
               (can-run-server? state server)
               (can-pay? state :runner "a run" :click 1 cost-bonus click-cost-bonus))
      (swap! state assoc-in [:runner :register :made-click-run] true)
      (run state side server)
      (when-let [cost-str (pay state :runner nil :click 1 cost-bonus click-cost-bonus)]
        (system-msg state :runner
                    (str (build-spend-msg cost-str "make a run on") server))
        (play-sfx state side "click-run")))))

(defn remove-tag
  "Click to remove a tag."
  [state side args]
  (let [remove-cost (max 0 (- 2 (or (get-in @state [:runner :tag-remove-bonus]) 0)))]
    (when-let [cost-str (pay state side nil :click 1 :credit remove-cost)]
      (lose state side :tag 1)
      (system-msg state side (build-spend-msg cost-str "remove 1 tag"))
      (play-sfx state side "click-remove-tag"))))

(defn continue
  "The runner decides to approach the next ice, or the server itself."
  [state side args]
  (when (get-in @state [:run :no-action])
    (let [run-ice (get-run-ices state)
          pos (get-in @state [:run :position])
          cur-ice (when (and pos (pos? pos) (<= pos (count run-ice)))
                    (get-card state (nth run-ice (dec pos))))
          next-ice (when (and pos (< 1 pos) (<= (dec pos) (count run-ice)))
                     (get-card state (nth run-ice (- pos 2))))]
      (when-completed (trigger-event-sync state side :pass-ice cur-ice)
                      (do (update-ice-in-server
                            state side (get-in @state (concat [:corp :servers] (get-in @state [:run :server]))))
                          (swap! state update-in [:run :position] dec)
                          (swap! state assoc-in [:run :no-action] false)
                          (system-msg state side "continues the run")
                          (when cur-ice
                            (update-ice-strength state side cur-ice))
                          (when next-ice
                            (trigger-event-sync state side (make-eid state) :approach-ice next-ice))
                          (doseq [p (filter #(has-subtype? % "Icebreaker") (all-installed state :runner))]
                            (update! state side (update-in (get-card state p) [:pump] dissoc :encounter))
                            (update-breaker-strength state side p)))))))

(defn view-deck
  "Allows the player to view their deck by making the cards in the deck public."
  [state side args]
  (system-msg state side "looks at their deck")
  (swap! state assoc-in [side :view-deck] true))

(defn close-deck
  "Closes the deck view and makes cards in deck private again."
  [state side args]
  (system-msg state side "stops looking at their deck")
  (swap! state update-in [side] dissoc :view-deck))
