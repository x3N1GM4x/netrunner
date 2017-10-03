(in-ns 'game.core)

(declare close-access-prompt)

(defn- genetics-trigger?
  "Returns true if Genetics card should trigger - does not work with Adjusted Chronotype"
  [state side event]
  (or (first-event? state side event)
      (and (has-flag? state side :persistent :genetics-trigger-twice)
           (second-event? state side event))))

;;; Card definitions
(def cards-resources
  {"Aaron Marrón"
   (let [am {:effect (effect (add-counter card :power 2)
                             (system-msg :runner (str "places 2 power counters on Aaron Marrón")))}]
     {:abilities [{:counter-cost [:power 1]
                   :msg "remove 1 tag and draw 1 card"
                   :effect (effect (lose :tag 1) (draw))}]
      :events {:agenda-scored am :agenda-stolen am}})

   "Access to Globalsec"
   {:in-play [:link 1]}

   "Activist Support"
   {:events
    {:corp-turn-begins {:req (req (= 0 (:tag runner)))
                        :msg "take 1 tag"
                        :delayed-completion true
                        :effect (effect (tag-runner :runner eid 1))}
     :runner-turn-begins {:req (req (not has-bad-pub))
                          :msg "give the Corp 1 bad publicity"
                          :effect (effect (gain :corp :bad-publicity 1))}}}

   "Adjusted Chronotype"
   {:events {:runner-loss {:req (req (and (some #{:click} target)
                                          (let [click-losses (filter #(= :click %) (mapcat first (turn-events state side :runner-loss)))]
                                            (or (empty? click-losses)
                                                (and (= (count click-losses) 1)
                                                     (has-flag? state side :persistent :genetics-trigger-twice))))))
                           :msg "gain [Click]" :effect (effect (gain :runner :click 1))}}}

   "Aeneas Informant"
   {:events {:no-trash {:req (req (and (:trash target) (req (not= (first (:zone target)) :discard))))
                        :optional {:prompt (msg "Use Aeneas Informant?")
                                   :yes-ability {:msg (msg (str "gain 1 [Credits] and reveal " (:title target)))
                                                 :effect (effect (gain :credit 1))}}}}}

   "Aesops Pawnshop"
   {:flags {:runner-phase-12 (req (>= 2 (count (all-installed state :runner))))}
    :abilities [{:effect (req (resolve-ability
                                state side
                                {:msg (msg "trash " (:title target) " and gain 3 [Credits]")
                                 :choices {:req #(and (card-is? % :side :runner) (installed? %) (not (card-is? % :cid (:cid card))))}
                                 :effect (effect (gain :credit 3) (trash target {:unpreventable true}))}
                                card nil))}]}

   "Akshara Sareen"
   {:in-play [:click 1 :click-per-turn 1]
    :msg "give each player 1 additional [Click] to spend during their turn"
    :effect (effect (gain :corp :click-per-turn 1))
    :leave-play (effect (lose :corp :click-per-turn 1))}

   "Algo Trading"
   {:flags {:runner-phase-12 (req (> (:credit runner) 0))}
    :abilities [{:label "Move up to 3 [Credit] from credit pool to Algo Trading"
                 :prompt "Choose how many [Credit] to move" :once :per-turn
                 :choices {:number (req (min (:credit runner) 3))}
                 :effect (effect (lose :credit target)
                                 (add-counter card :credit target))
                 :msg (msg "move " target " [Credit] to Algo Trading")}
                {:label "Take all credits from Algo Trading"
                 :cost [:click 1]
                 :msg (msg "trash it and gain " (get-in card [:counter :credit] 0) " [Credits]")
                 :effect (effect (gain :credit (get-in card [:counter :credit] 0))
                                 (trash card {:cause :ability-cost}))}]
    :events {:runner-turn-begins {:req (req (>= (get-in card [:counter :credit] 0) 6))
                                  :effect (effect (add-counter card :credit 2)
                                                  (system-msg (str "adds 2 [Credit] to Algo Trading")))}}}

   "Always Be Running"
   {:implementation "Run requirement not enforced"
    :abilities [{:once :per-turn
                 :cost [:click 2]
                 :msg (msg "break 1 subroutine")}]}

   "All-nighter"
   {:abilities [{:cost [:click 1] :effect (effect (trash card {:cause :ability-cost}) (gain :click 2))
                 :msg "gain [Click][Click]"}]}

   "Angel Arena"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :abilities [{:counter-cost [:power 1]
                 :msg "look at the top card of Stack"
                 :effect (req (when (zero? (get-in card [:counter :power] 0))
                                (trash state :runner card {:unpreventable true})))
                 :optional {:prompt (msg "Add " (:title (first (:deck runner))) " to bottom of Stack?")
                            :yes-ability {:msg "add the top card of Stack to the bottom"
                                          :effect (req (move state side (first (:deck runner)) :deck))}}}]}

   "Armitage Codebusting"
   {:data {:counter {:credit 12}}
    :abilities [{:cost [:click 1]
                 :counter-cost [:credit 2]
                 :msg "gain 2 [Credits]"
                 :effect (req (gain state :runner :credit 2)
                              (when (zero? (get-in card [:counter :credit] 0))
                                (trash state :runner card {:unpreventable true})))}]}

   "Artist Colony"
   {:abilities [{:prompt "Choose a card to install"
                 :msg (msg "install " (:title target))
                 :req (req (not (install-locked? state side)))
                 :cost [:forfeit]
                 :choices (req (cancellable (filter #(not (is-type? % "Event")) (:deck runner)) :sorted))
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (runner-install target))}]}

   "Bhagat"
   {:events {:successful-run {:req (req (and (= target :hq)
                                             (first-successful-run-on-server? state :hq)))
                              :msg "force the Corp to trash the top card of R&D"
                              :effect (effect (mill :corp))}}}

   "Bank Job"
   {:data {:counter {:credit 8}}
    :events {:successful-run
             {:silent (req true)
              :req (req (is-remote? (:server run)))
              :effect (req (let [bj card]
                             (when-not (:replace-access (get-in @state [:run :run-effect]))
                               (swap! state assoc-in [:run :run-effect :replace-access]
                                      {:effect (req (if (> (count (filter #(= (:title %) "Bank Job") (all-installed state :runner))) 1)
                                                      (resolve-ability state side
                                                        {:prompt "Choose a copy of Bank Job to use"
                                                         :choices {:req #(and installed? (= (:title %) "Bank Job"))}
                                                         :effect (req (let [c target
                                                                            creds (get-in c [:counter :credit])]
                                                                        (resolve-ability state side
                                                                          {:prompt "How many Bank Job credits?"
                                                                           :choices {:number (req (get-in c [:counter :credit]))}
                                                                           :msg (msg "gain " target " [Credits]")
                                                                           :effect (req (gain state side :credit target)
                                                                                        (set-prop state side c :counter {:credit (- creds target)})
                                                                                        (when (= target creds)
                                                                                          (trash state side c {:unpreventable true})))}
                                                                         card nil)))}
                                                       bj nil)
                                                      (resolve-ability state side
                                                        {:prompt "How many Bank Job credits?"
                                                         :choices {:counter :credit}
                                                         :msg (msg "gain " target " [Credits]")
                                                         :effect (req (gain state side :credit target)
                                                                      (when (= target (get-in card [:counter :credit]))
                                                                        (trash state side card {:unpreventable true})))}
                                                       bj nil)))}))))}}}

   "Bazaar"
   {:events
    {:runner-install
     {:interactive (req (and (is-type? target "Hardware")
                             (some #(= (:title %) (:title target)) (:hand runner))))
      :silent (req (not (and (is-type? target "Hardware")
                             (some #(= (:title %) (:title target)) (:hand runner)))))
      :delayed-completion true
      :req (req (and (is-type? target "Hardware") (= [:hand] (:previous-zone target))))
      :effect (req (let [hw (:title target)]
                     (continue-ability state side
                       {:optional {:req (req (some #(when (= (:title %) hw) %) (:hand runner)))
                                   :prompt (msg "Install another copy of " hw "?")
                                   :msg (msg "install another copy of " hw)
                                   :yes-ability {:delayed-completion true
                                                 :effect (req (if-let [c (some #(when (= (:title %) hw) %)
                                                                               (:hand runner))]
                                                                (runner-install state side eid c nil)))}}} card nil)))}}}

   "Beach Party"
   {:in-play [:hand-size-modification 5]
    :events {:runner-turn-begins {:msg "lose [Click]" :effect (effect (lose :click 1))}}}

   "Beth Kilrain-Chang"
   (let [ability {:once :per-turn
                  :label "Gain 1 [Credits], draw 1 card, or gain [Click] (start of turn)"
                  :req (req (:runner-phase-12 @state))
                  :effect (req (let [c (:credit corp)
                                     b (:title card)]
                                 (cond
                                   ;; gain 1 credit
                                   (<= 5 c 9)
                                   (do (gain state side :credit 1)
                                       (system-msg state side (str "uses " b " to gain 1 [Credits]")))
                                   ;; draw 1 card
                                   (<= 10 c 14)
                                   (do (draw state side 1)
                                       (system-msg state side (str "uses " b " to draw 1 card")))
                                   ;; gain 1 click
                                   (<= 15 c)
                                   (do (gain state side :click 1)
                                       (system-msg state side (str "uses " b " to gain [Click]"))))))}]
     {:flags {:drip-economy true}
      :abilities [ability]
      :events {:runner-turn-begins ability}})

   "Biometric Spoofing"
   {:prevent {:damage [:net :meat :brain]}
    :abilities [{:label "[Trash]: Prevent 2 damage"
                 :msg "prevent 2 damage"
                 :effect (effect (trash card {:cause :ability-cost})
                                 (damage-prevent :brain 2)
                                 (damage-prevent :net 2)
                                 (damage-prevent :meat 2))}]}

   "Bio-Modeled Network"
   {:prevent {:damage [:net]}
    :events {:pre-damage {:req (req (= target :net))
                          :effect (effect (update! (assoc card :dmg-amount (nth targets 2))))}}
    :abilities [{:msg (msg "prevent " (dec (:dmg-amount card)) " net damage")
                 :effect (effect (damage-prevent :net (dec (:dmg-amount card)))
                                 (trash card {:cause :ability-cost}))}]}

   "Blockade Runner"
   {:abilities [{:cost [:click 2]
                 :msg "draw 3 cards and shuffle 1 card from their Grip back into their Stack"
                 :effect (effect (draw 3)
                                 (resolve-ability
                                   {:prompt "Choose a card in your Grip to shuffle back into your Stack"
                                    :choices {:req #(and (in-hand? %)
                                                         (= (:side %) "Runner"))}
                                    :effect (effect (move target :deck)
                                                    (shuffle! :deck))}
                                  card nil))}]}

   "Bloo Moose"
   {:flags {:runner-phase-12 (req true)}
    :abilities [{:req (req (and (:runner-phase-12 @state)
                                (not (seq (get-in @state [:runner :locked :discard])))))
                 :once :per-turn
                 :prompt "Choose a card in the Heap to remove from the game and gain 2 [Credits]"
                 :show-discard true
                 :choices {:req #(and (in-discard? %) (= (:side %) "Runner"))}
                 :msg (msg "remove " (:title target) " from the game and gain 2 [Credits]")
                 :effect (effect (gain :credit 2)
                                 (move target :rfg))}]}

   "Borrowed Satellite"
   {:in-play [:hand-size-modification 1 :link 1]}

   "Bug Out Bag"
   {:prompt "How many power counters?"
    :choices :credit
    :msg (msg "add " target " power counters")
    :effect (effect (add-counter card :power target))
    :events {:runner-turn-ends {:req (req (zero? (count (:hand runner))))
                                :msg (msg "draw " (get-in card [:counter :power] 0) " cards. Bug Out Bag is trashed")
                                :effect (effect (draw (get-in card [:counter :power] 0))
                                                (trash card))}}}

   "Caldera"
   {:prevent {:damage [:net :brain]}
    :abilities [{:cost [:credit 3] :msg "prevent 1 net damage" :effect (effect (damage-prevent :net 1))}
                {:cost [:credit 3] :msg "prevent 1 brain damage" :effect (effect (damage-prevent :brain 1))}]}

   "Charlatan"
   {:abilities [{:cost [:click 2]
                 :label "Make a run"
                 :prompt "Choose a server"
                 :choices (req runnable-servers)
                 :msg (msg "make a run on " target)
                 :effect (effect (run target nil card))}
                {:label "Pay credits equal to strength of approached rezzed ICE to bypass it"
                 :once :per-run
                 :req (req (and (:run @state) (rezzed? current-ice)))
                 :msg (msg "pay " (:current-strength current-ice) " [Credits] and bypass " (:title current-ice))
                 :effect (effect (pay :runner card :credit (:current-strength current-ice)))}]}

   "Chatterjee University"
   {:abilities [{:cost [:click 1]
                 :label "Place 1 power counter"
                 :msg "place 1 power counter on it"
                 :effect (effect (add-counter card :power 1))}
                {:cost [:click 1]
                 :label "Install a program from your Grip"
                 :prompt "Choose a program to install from your Grip"
                 :choices {:req #(and (is-type? % "Program") (in-hand? %))}
                 :msg (msg "install " (:title target))
                 :effect (req (install-cost-bonus state side [:credit (* -1 (get-in card [:counter :power] 0))])
                              (runner-install state side target)
                              (when (pos? (get-in card [:counter :power] 0))
                                (add-counter state side card :power -1)))}]}

   "Chrome Parlor"
   {:events
    {:pre-damage {:req (req (has-subtype? (second targets) "Cybernetic"))
                  :effect (effect (damage-prevent target Integer/MAX_VALUE))}}}

   "Citadel Sanctuary"
   {:prevent {:damage [:meat]}
    :abilities [{:label "[Trash] and trash all cards in Grip to prevent all meat damage"
                 :msg "trash all cards in their Grip and prevent all meat damage"
                 :effect (req (trash state side card {:cause :ability-cost})
                              (doseq [c (:hand runner)]
                                (trash state side c {:unpreventable true}))
                              (damage-prevent state side :meat Integer/MAX_VALUE))}]
    :events {:runner-turn-ends
             {:req (req (pos? (:tag runner)))
              :msg "force the Corp to initiate a trace"
              :label "Trace 1 - If unsuccessful, Runner removes 1 tag"
              :trace {:base 1 :unsuccessful {:effect (effect (lose :runner :tag 1))
                                             :msg "remove 1 tag"}}}}}

   "Clan Vengeance"
   {:events {:pre-resolve-damage {:req (req (pos? (last targets)))
                                  :effect (effect (add-counter card :power 1)
                                                  (system-msg :runner (str "places 1 power counter on Clan Vengeance")))}}
    :abilities [{:label "[Trash]: Trash 1 random card from HQ for each power counter"
                 :req (req (pos? (get-in card [:counter :power] 0)))
                 :msg (msg "trash " (min (get-in card [:counter :power] 0) (count (:hand corp))) " cards from HQ")
                 :effect (effect (trash-cards (take (min (get-in card [:counter :power] 0) (count (:hand corp)))
                                              (shuffle (:hand corp))))
                                 (trash card {:cause :ability-cost}))}]}

   "Compromised Employee"
   {:recurring 1
    :events {:rez {:req (req (ice? target))
                   :msg "gain 1 [Credits]"
                   :effect (effect (gain :runner :credit 1))}}}

   "Corporate Defector"
   {:events {:corp-click-draw {:msg (msg "reveal " (-> target first :title))}}}

   "Councilman"
   {:implementation "Does not restrict Runner to Asset / Upgrade just rezzed"
    :events {:rez {:req (req (and (#{"Asset" "Upgrade"} (:type target))
                                  (can-pay? state :runner nil [:credit (rez-cost state :corp target)])))
                   :effect (req (toast state :runner (str "Click Councilman to derez " (card-str state target {:visible true})
                                                          " that was just rezzed") "info")
                                (toast state :corp (str "Runner has the opportunity to derez with Councilman.") "error"))}}
    :abilities [{:prompt "Choose an asset or upgrade that was just rezzed"
                 :choices {:req #(and (rezzed? %)
                                      (or (is-type? % "Asset") (is-type? % "Upgrade")))}
                 :effect (req (let [c target
                                    creds (rez-cost state :corp c)]
                                (when (can-pay? state side nil [:credit creds])
                                  (resolve-ability
                                    state :runner
                                    {:msg (msg "pay " creds " [Credit] and derez " (:title c) ". Councilman is trashed")
                                     :effect (req (lose state :runner :credit creds)
                                                  (derez state :corp c)
                                                  (register-turn-flag!
                                                    state side card :can-rez
                                                    (fn [state side card]
                                                      (if (= (:cid card) (:cid c))
                                                        ((constantly false)
                                                         (toast state :corp "Cannot rez the rest of this turn due to Councilman"))
                                                        true)))
                                                  (trash state side card {:unpreventable true}))}
                                   card nil))))}]}

   "Counter Surveillance"
   {:implementation "Does not prevent access of cards installed in the root of a server"
    :abilities [{:cost [:click 1]
                 :makes-run true
                 :prompt "Choose a server to run with Counter Surveillance"
                 :msg (msg "run " target " and trashes Counter Surveillance")
                 :choices (req (cancellable runnable-servers))
                 :effect (req (trash state side card {:cause :ability-cost})
                              (game.core/run state side target nil card)
                              (register-events state side (:events (card-def card))
                                               (assoc card :zone '(:discard))))}]
    :events {:successful-run {:silent (req true)
                              :effect (req (if (>= (:credit runner) (:tag runner))
                                             ;; Can pay, do access
                                             (do (system-msg state side (str "uses Counter Surveillance to access up to "
                                                                             (:tag runner) " cards by paying "
                                                                             (:tag runner) " [Credit]"))
                                                 (pay state side card :credit (:tag runner))
                                                 (access-bonus state side (- (:tag runner) 1)))
                                             ;; Can't pay, don't access cards
                                             (do (system-msg state side "could not afford to use Counter Surveillance")
                                                 ;; Cannot access any cards
                                                 (max-access state side 0))))}
             :run-ends {:effect (effect (unregister-events card))}}}

   "Crash Space"
   {:prevent {:damage [:meat]}
    :recurring 2
    :abilities [{:label "Trash to prevent up to 3 meat damage"
                 :msg "prevent up to 3 meat damage"
                 :effect (effect (trash card {:cause :ability-cost}) (damage-prevent :meat 3))}]}

   "Dadiana Chacon"
   (let [ability {:once :per-turn
                  :msg "gain 1 [Credits]"
                  :req (req (< (get-in @state [:runner :credit]) 6))
                  :effect (req (gain state :runner :credit 1))}]
     {:effect (req (add-watch state :dadiana
                              (fn [k ref old new]
                                (when (and (not (zero? (get-in old [:runner :credit])))
                                           (zero? (get-in new [:runner :credit])))
                                  (resolve-ability ref side {:effect (effect (system-msg "trashes Dadiana Chacon and suffers 3 meat damage")
                                                                             (damage eid :meat 3 {:unboostable true :card card})
                                                                             (trash card {:cause :ability-cost}))} card nil)))))
      :leave-play (req (remove-watch state :dadiana))
      :flags {:drip-economy true}
      :events {:runner-turn-begins ability}})

   "Daily Casts"
   (let [ability {:once :per-turn
                  :label "Take 2 [Credits] (start of turn)"
                  :msg "gain 2 [Credits]"
                  :req (req (:runner-phase-12 @state))
                  :counter-cost [:credit 2]
                  :effect (req (gain state :runner :credit 2)
                               (when (zero? (get-in card [:counter :credit] 0))
                                 (trash state :runner card {:unpreventable true})))}]
   {:data {:counter {:credit 8}}
    :flags {:drip-economy true}
    :abilities [ability]
    :events {:runner-turn-begins ability}})

   "Data Dealer"
   {:abilities [{:cost [:click 1 :forfeit] :effect (effect (gain :credit 9))
                 :msg (msg "gain 9 [Credits]")}]}

   "Data Folding"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :msg "gain 1 [Credits]"
                  :once :per-turn
                  :req (req (and (>= (:memory runner) 2) (:runner-phase-12 @state)))
                  :effect (effect (gain :credit 1))}]
    {:flags {:drip-economy true}
    :abilities [ability]
    :events {:runner-turn-begins ability}})

   "Data Leak Reversal"
   {:req (req (some #{:hq :rd :archives} (:successful-run runner-reg)))
    :abilities [{:req (req tagged) :cost [:click 1] :effect (effect (mill :corp))
                 :msg "force the Corp to trash the top card of R&D"}]}

   "DDoS"
   {:abilities [{:msg "prevent the corp from rezzing the outermost piece of ice during a run on any server this turn"
                 :effect (effect
                           (register-turn-flag!
                             card :can-rez
                             (fn [state side card]
                               (if (and (ice? card)
                                        (= (count (get-in @state (concat [:corp :servers] (:server (:run @state)) [:ices])))
                                           (inc (ice-index state card))))
                                 ((constantly false) (toast state :corp "Cannot rez any outermost ICE due to DDoS." "warning"))
                                 true)))
                           (trash card {:cause :ability-cost}))}]}

   "Dean Lister"
   {:abilities [{:req (req (:run @state))
                 :msg (msg "add +1 strength for each card in their Grip to " (:title target) " until the end of the run")
                 :choices {:req #(and (has-subtype? % "Icebreaker")
                                      (installed? %))}
                 :effect (effect (update! (assoc card :dean-target target))
                                 (trash (get-card state card) {:cause :ability-cost})
                                 (update-breaker-strength target))}]
    :events {:run-ends nil :pre-breaker-strength nil}
    :trash-effect {:effect
                   (effect (register-events
                             (let [dean {:effect (effect (unregister-events card)
                                                         (update! (dissoc card :dean-target))
                                                         (update-breaker-strength (:dean-target card)))}]
                               {:run-ends dean
                                :pre-breaker-strength {:req (req (= (:cid target)(:cid (:dean-target card))))
                                                       :effect (effect (breaker-strength-bonus (count (:hand runner))))}}) card))}}

   "Decoy"
   {:prevent {:tag [:all]}
    :abilities [{:msg "avoid 1 tag" :effect (effect (tag-prevent 1) (trash card {:cause :ability-cost}))}]}

   "Donut Taganes"
   {:msg "increase the play cost of operations and events by 1 [Credits]"
    :events {:pre-play-instant
             {:effect (effect (play-cost-bonus [:credit 1]))}}}

   "Dr. Lovegood"
   {:flags {:runner-phase-12 (req (> (count (all-installed state :runner)) 1))}
    :abilities [{:req (req (:runner-phase-12 @state))
                 :prompt "Choose an installed card to make its text box blank for the remainder of the turn" :once :per-turn
                 :choices {:req installed?}
                 :msg (msg "make the text box of " (:title target) " blank for the remainder of the turn")
                 :effect (req (let [c target]
                                (disable-card state side c)
                                (register-events state side
                                                 {:runner-turn-ends
                                                  {:effect (effect (enable-card (get-card state c))
                                                                   (unregister-events card))}} card)))}]
    :events {:runner-turn-ends nil}}

   "Drug Dealer"
   {:flags {:runner-phase-12 (req (some #(card-flag? % :drip-economy true) (all-installed state :runner)))}
    :abilities [{:label "Lose 1 [Credits] (start of turn)"
                 :msg (msg (if (= (get-in @state [:runner :credit]) 0) "lose 0 [Credits] (runner has no credits to lose)" "lose 1 [Credits]"))
                 :req (req (:runner-phase-12 @state))
                 :once :per-turn
                 :effect (effect (lose :credit 1))}]
    :events {:corp-turn-begins {:msg (msg "draw " (if (= (count (get-in @state [:runner :deck])) 0)
                                                   "0 cards (runner's stack is empty)"
                                                   "1 card"))
                                :effect (effect (draw :runner 1))}
             :runner-turn-begins {:msg (msg "lose " (if (= (get-in @state [:runner :credit]) 0)
                                                             "0 [Credits] (runner has no credits to lose)"
                                                             "1 [Credits]"))
                                  :once :per-turn
                                  :effect (effect (lose :credit 1))}}}

   "Duggars"
   {:abilities [{:cost [:click 4] :effect (effect (draw 10)) :msg "draw 10 cards"}]}

   "Dummy Box"
   {:prevent {:trash [:hardware :resource :program]}
    :abilities [{:msg "prevent a hardware from being trashed"
                 :delayed-completion true
                 :priority 15
                 :prompt "Choose a hardware in your Grip"
                 :choices {:req #(and (is-type? % "Hardware")
                                      (in-hand? %))}
                 :effect (effect (move target :discard)
                                 (trash-prevent :hardware 1))}
                {:msg "prevent a resource from being trashed"
                 :delayed-completion true
                 :priority 15
                 :prompt "Choose a resource in your Grip"
                 :choices {:req #(and (is-type? % "Resource")
                                      (in-hand? %))}
                 :effect (effect (move target :discard)
                                 (trash-prevent :resource 1))}
                {:msg "prevent a program from being trashed"
                 :delayed-completion true
                 :priority 15
                 :prompt "Choose a program in your Grip"
                 :choices {:req #(and (is-type? % "Program")
                                      (in-hand? %))}
                 :effect (effect (move target :discard)
                                 (trash-prevent :program 1))}]}

   "Earthrise Hotel"
   (let [ability {:msg "draw 2 cards"
                  :once :per-turn
                  :counter-cost [:power 1]
                  :req (req (:runner-phase-12 @state))
                  :effect (req (draw state :runner 2)
                               (when (zero? (get-in card [:counter :power] 0))
                                 (trash state :runner card {:unpreventable true})))}]
   {:flags {:runner-turn-draw true
            :runner-phase-12 (req (< 1 (count (filter #(card-flag? % :runner-turn-draw true)
                                                      (cons (get-in @state [:runner :identity])
                                                            (all-installed state :runner))))))}
    :data {:counter {:power  3}}
    :events {:runner-turn-begins ability}
    :abilities [ability]})

   "Eden Shard"
   {:abilities [{:effect (effect (trash card {:cause :ability-cost}) (draw :corp 2))
                 :msg "force the Corp to draw 2 cards"}]
    :install-cost-bonus (req (if (and run (= (:server run) [:rd]) (zero? (:position run)))
                               [:credit -15 :click -1] nil))
    :effect (req (when (and run (= (:server run) [:rd]) (zero? (:position run)))
                   (when-completed (register-successful-run state side (:server run))
                                   (do (swap! state update-in [:runner :prompt] rest)
                                       (handle-end-run state side)))))}

   "Emptied Mind"
   (let [ability {:req (req (= 0 (count (:hand runner))))
                  :msg "gain [Click]"
                  :label "Gain [Click] (start of turn)"
                  :once :per-turn
                  :effect (effect (gain :click 1))}]
     {:events {:runner-turn-begins ability}
      :abilities [ability]})

   "Enhanced Vision"
   {:events {:successful-run {:silent (req true)
                              :msg (msg "force the Corp to reveal " (:title (first (shuffle (:hand corp)))))
                              :req (req (genetics-trigger? state side :successful-run))}}}

   "Fall Guy"
   {:prevent {:trash [:resource]}
    :abilities [{:label "[Trash]: Prevent another installed resource from being trashed"
                 :effect (effect (trash-prevent :resource 1) (trash card {:unpreventable true :cause :ability-cost}))}
                {:label "[Trash]: Gain 2 [Credits]"
                 :effect (effect (trash card {:cause :ability-cost}) (gain :credit 2)) :msg "gain 2 [Credits]"}]}

   "Fan Site"
   {:events {:agenda-scored {:msg "add it to their score area as an agenda worth 0 agenda points"
                             :req (req (installed? card))
                             :effect (effect (as-agenda :runner card 0))}}}

   "Fester"
   {:events {:purge {:msg "force the Corp to lose 2 [Credits] if able"
                     :effect (effect (pay :corp card :credit 2))}}}

   "Film Critic"
   (letfn [(get-agenda [card] (first (filter #(= "Agenda" (:type %)) (:hosted card))))]
   {:abilities [{:req (req (and (empty? (filter #(= "Agenda" (:type %)) (:hosted card)))
                                (is-type? (:card (first (get-in @state [side :prompt]))) "Agenda")))
                 :label "Host an agenda being accessed"
                 :effect (req (when-let [agenda (:card (first (get-in @state [side :prompt])))]
                                (host state side card (move state side agenda :play-area))
                                (trigger-event state side :no-steal agenda)
                                (close-access-prompt state side)
                                (effect-completed state side eid nil)
                                (when-not (:run @state)
                                  (swap! state dissoc :access))))
                 :msg (msg "host " (:title (:card (first (get-in @state [side :prompt])))) " instead of accessing it")}
                {:cost [:click 2] :label "Add hosted agenda to your score area"
                 :req (req (not (empty? (:hosted card))))
                 :effect (req (let [c (move state :runner (get-agenda card) :scored)]
                                (gain-agenda-point state :runner (get-agenda-points state :runner c))))
                 :msg (msg (let [c (get-agenda card)]
                             (str "add " (:title c) " to their score area and gain " (get-agenda-points state :runner c)
                                  " agenda point" (when (> (get-agenda-points state :runner c) 1) "s"))))}]})

   "Find the Truth"
   {:events {:post-runner-draw {:msg (msg "reveal that they drew: "
                                          (join ", " (map :title (get-in @state [:runner :register :most-recent-drawn]))))}
             :successful-run {:interactive (req true)
                              :optional
                              {:delayed-completion true
                               :req (req (= 1 (count (get-in @state [:runner :register :successful-run]))))
                               :prompt "Use Find the Truth to look at the top card of R&D?"
                               :yes-ability {:msg "look at the top card of R&D"
                                             :effect (req (prompt! state :runner card (str "The top card of R&D is "
                                                                                           (:title (first (:deck corp)))) ["OK"] {})
                                                          (effect-completed state side eid))}
                               :no-ability {:effect (req (effect-completed state side eid))}}}}}

   "First Responders"
   {:abilities [{:cost [:credit 2]
                 :req (req (some #(= (:side %) "Corp") (map second (turn-events state :runner :damage))))
                 :msg "draw 1 card"
                 :effect (effect (draw))}]}

   "Gang Sign"
   {:events {:agenda-scored
             {:delayed-completion true
              :interactive (req true)
              :msg (msg "access " (get-in @state [:runner :hq-access]) " card"
                        (when (< 1 (get-in @state [:runner :hq-access])) "s") " from HQ")
              :effect (req (when-completed
                             ; manually trigger the pre-access event to alert Nerve Agent.
                             (trigger-event-sync state side :pre-access :hq)
                             (let [from-hq (access-count state side :hq-access)]
                               (continue-ability
                                 state :runner
                                 (access-helper-hq
                                   state from-hq
                                   ; access-helper-hq uses a set to keep track of which cards have already
                                   ; been accessed. by adding HQ root's contents to this set, we make the runner
                                   ; unable to access those cards, as Gang Sign intends.
                                   (set (get-in @state [:corp :servers :hq :content])))
                                 card nil))))}}}

   "Gene Conditioning Shoppe"
   {:msg "make Genetics trigger a second time each turn"
    :effect (effect (register-persistent-flag! card :genetics-trigger-twice (constantly true)))
    :leave-play (effect (clear-persistent-flag! card :genetics-trigger-twice))}

   "Ghost Runner"
   {:data {:counter {:credit 3}}
    :abilities [{:counter-cost [:credit 1]
                 :msg "gain 1 [Credits]"
                 :req (req (:run @state))
                 :effect (req (gain state side :credit 1)
                              (trigger-event state side :spent-stealth-credit card)
                              (when (zero? (get-in card [:counter :credit] 0))
                                (trash state :runner card {:unpreventable true})))}]}

   "Globalsec Security Clearance"
   {:req (req (> (:link runner) 1))
    :flags {:runner-phase-12 (req true)}
    :abilities [{:msg "lose [Click] and look at the top card of R&D"
                 :once :per-turn
                 :effect (effect (prompt! card (str "The top card of R&D is "
                                                    (:title (first (:deck corp)))) ["OK"] {}))}]
    :events {:runner-turn-begins {:req (req (get-in @state [:per-turn (:cid card)]))
                                  :effect (effect (lose :click 1))}}}

   "Grifter"
   {:events {:runner-turn-ends
             {:effect (req (let [ab (if (get-in @state [:runner :register :successful-run])
                                      {:effect (effect (gain :credit 1)) :msg "gain 1 [Credits]"}
                                      {:effect (effect (trash card)) :msg "trash Grifter"})]
                             (resolve-ability state side ab card targets)))}}}

   "Guru Davinder"
   {:events {:pre-damage
             {:req (req (or (= target :meat) (= target :net)))
              :msg (msg "prevent all " (if (= target :meat) "meat" "net") " damage")
              :effect (req (damage-prevent state side :meat Integer/MAX_VALUE)
                           (damage-prevent state side :net Integer/MAX_VALUE)
                           (if (< (:credit runner) 4)
                             (trash state side card)
                             (resolve-ability
                               state :runner
                               {:optional
                                {:prompt "Pay 4 [Credits] to prevent trashing Guru Davinder?"
                                 :player :runner
                                 :yes-ability {:effect (effect (lose :runner :credit 4)
                                                               (system-msg (str "pays 4 [Credits] to prevent Guru Davinder "
                                                                                "from being trashed")))}
                                 :no-ability {:effect (effect (trash card))}}}
                              card nil)))}}}

   "Hades Shard"
   {:abilities [{:delayed-completion true
                 :msg "access all cards in Archives"
                 :effect (req (trash state side card {:cause :ability-cost})
                              (swap! state update-in [:corp :discard] #(map (fn [c] (assoc c :seen true)) %))
                              (when (:run @state)
                                (swap! state update-in [:run :cards-accessed] (fnil #(+ % (count (:discard corp))) 0)))
                              (when-completed (trigger-event-sync state side :pre-access :archives)
                                              (resolve-ability state :runner
                                                               (choose-access (get-in @state [:corp :discard]) '(:archives)) card nil)))}]
    :install-cost-bonus (req (if (and run (= (:server run) [:archives]) (= 0 (:position run)))
                               [:credit -15 :click -1] nil))
    :effect (req (when (and run (= (:server run) [:archives]) (= 0 (:position run)))
                   (when-completed (register-successful-run state side (:server run))
                                   (do (swap! state update-in [:runner :prompt] rest)
                                       (handle-end-run state side)))))}

   "Hard at Work"
   (let [ability {:msg "gain 2 [Credits] and lose [Click]"
                  :once :per-turn
                  :effect (effect (lose :click 1) (gain :credit 2))}]
   {:flags {:drip-economy true}
    :events {:runner-turn-begins ability}
    :abilities [ability]})

   "Human First"
   {:events {:agenda-scored {:msg (msg "gain " (get-agenda-points state :corp target) " [Credits]")
                             :effect (effect (gain :runner :credit (get-agenda-points state :corp target)))}
             :agenda-stolen {:msg (msg "gain " (get-agenda-points state :runner target) " [Credits]")
                             :effect (effect (gain :credit (get-agenda-points state :runner target)))}}}

   "Hunting Grounds"
   {:abilities [{:label "Prevent a \"when encountered\" ability on a piece of ICE"
                 :msg "prevent a \"when encountered\" ability on a piece of ICE"
                 :once :per-turn}
                 {:label "[Trash]: Install the top 3 cards of your Stack facedown"
                  :msg "install the top 3 cards of their Stack facedown"
                  :effect (req (trash state side card {:cause :ability-cost})
                               (doseq [c (take 3 (get-in @state [:runner :deck]))]
                                 (runner-install state side c {:facedown true})))}]}

   "Ice Analyzer"
   {:implementation "Credit use restriction is not enforced"
    :events {:rez {:req (req (ice? target))
                   :msg "place 1 [Credits] on Ice Analyzer"
                   :effect (effect (add-counter :runner card :credit 1))}}
    :abilities [{:counter-cost [:credit 1]
                 :effect (effect (gain :credit 1))
                 :msg "take 1 [Credits] to install programs"}]}

   "Ice Carver"
   {:events {:pre-ice-strength
             {:req (req (and (= (:cid target) (:cid current-ice)) (:rezzed target)))
              :effect (effect (ice-strength-bonus -1 target))}}}

   "Inside Man"
   {:recurring 2}

   "Investigative Journalism"
   {:req (req has-bad-pub)
    :abilities [{:cost [:click 4] :msg "give the Corp 1 bad publicity"
                 :effect (effect (gain :corp :bad-publicity 1) (trash card {:cause :ability-cost}))}]}

   "Jak Sinclair"
   (let [ability {:label "Make a run (start of turn)"
                  :prompt "Choose a server to run with Jak Sinclair"
                  :once :per-turn
                  :req (req (:runner-phase-12 @state))
                  :choices (req runnable-servers)
                  :msg (msg "make a run on " target " during which no programs can be used")
                  :makes-run true
                  :effect (effect (run target))}]
   {:implementation "Doesn't prevent program use"
    :flags {:runner-phase-12 (req true)}
    :install-cost-bonus (req [:credit (* -1 (:link runner))])
    :events {:runner-turn-begins
              {:optional {:req (req (not (get-in @state [:per-turn (:cid card)])))
                          :prompt "Use Jak Sinclair to make a run?"
                          :yes-ability ability}}}
    :abilities [ability]})

   "Jarogniew Mercs"
   {:effect (effect (tag-runner :runner eid 1)
                    (add-counter card :power (-> @state :runner :tag (+ 3))))
    :flags {:untrashable-while-resources true}
    :prevent {:damage [:meat]}
    :abilities [{:label "Prevent 1 meat damage"
                 :counter-cost [:power 1]
                 :effect (req (damage-prevent state side :meat 1)
                              (when (<= (get-in card [:counter :power]) 0)
                                (trash state :runner card {:unpreventable true})))}]}

   "John Masanori"
   {:events {:successful-run {:req (req (= 1 (count (get-in @state [:runner :register :successful-run]))))
                              :msg "draw 1 card" :once-key :john-masanori-draw
                              :effect (effect (draw))}
             :unsuccessful-run {:req (req (= 1 (count (get-in @state [:runner :register :unsuccessful-run]))))
                                :delayed-completion true
                                :msg "take 1 tag" :once-key :john-masanori-tag
                                :effect (effect (tag-runner :runner eid 1))}}}

   "Joshua B."
   (let [ability {:msg "gain [Click]"
                  :once :per-turn
                  :label "Gain [Click] (start of turn)"
                  :effect (effect (gain :click 1))
                  :end-turn {:delayed-completion true
                             :effect (effect (tag-runner eid 1))
                             :msg "gain 1 tag"}}]
     {:flags {:runner-phase-12 (req true)}
      :events {:runner-turn-begins
               {:optional {:prompt "Use Joshua B. to gain [Click]?"
                           :once :per-turn
                           :yes-ability ability}}}
      :abilities [ability]})

   "Kati Jones"
   {:abilities [{:cost [:click 1]
                 :msg "store 3 [Credits]"
                 :once :per-turn
                 :effect (effect (add-counter card :credit 3))}
                {:cost [:click 1]
                 :msg (msg "gain " (get-in card [:counter :credit] 0) " [Credits]")
                 :once :per-turn
                 :label "Take all credits"
                 :effect (req (gain state side :credit (get-in card [:counter :credit] 0))
                              (add-counter state side card :credit (- (get-in card [:counter :credit] 0))))}]}

   "Keros Mcintyre"
   {:events
    {:derez
     {:req (req (and (first-event? state side :derez)
                     (= (second targets) :runner)))
      :once :per-turn
      :msg "gain 2 [Credits]"
      :effect (effect (gain :credit 2))}}}

   "Laguna Velasco District"
   {:events {:runner-click-draw {:msg "draw 1 card" :effect (effect (draw))}}}

   "Levy Advanced Research Lab"
   (letfn [(lab-keep [cards]
             {:prompt "Choose a Program to keep"
              :choices (cons "None" (filter #(= "Program" (:type %)) cards))
              :delayed-completion true
              :msg (msg (if (= target "None") "take no card to their Grip" (str "take " (-> target :title) " to their Grip")))
              :effect (req (when (not= target "None")
                             (move state side target :hand))
                           (if (not-empty cards)
                             (let [tobottom (remove #(= % target) cards)]
                               (continue-ability state side (reorder-choice :runner :corp tobottom '()
                                                                            (count tobottom) tobottom "bottom") card nil))
                             (do (clear-wait-prompt state :corp)
                                 (effect-completed state side eid card))))})]
   {:abilities [{:cost [:click 1]
                 :msg (msg "draw 4 cards: " (join ", " (map :title (take 4 (:deck runner)))))
                 :delayed-completion true
                 :effect (req (show-wait-prompt state :corp "Runner to choose card to keep")
                              (let [from (take 4 (:deck runner))]
                                (continue-ability state side (lab-keep from) card nil)))}]})

   "Liberated Account"
   {:data {:counter {:credit 16}}
    :abilities [{:cost [:click 1]
                 :counter-cost [:credit 4]
                 :msg "gain 4 [Credits]"
                 :effect (req (gain state :runner :credit 4)
                              (when (<= (get-in card [:counter :credit] 0) 0)
                                (trash state :runner card {:unpreventable true})))}]}

   "Liberated Chela"
   {:abilities [{:cost [:click 5 :forfeit]
                 :msg "add it to their score area"
                 :effect (req (if (not (empty? (:scored corp)))
                                (do (show-wait-prompt state :runner "Corp to decide whether or not to prevent Liberated Chela")
                                    (resolve-ability
                                      state side
                                      {:prompt (msg "Forfeit an agenda to prevent Liberated Chela from being added to Runner's score area?")
                                       :choices ["Yes" "No"] :player :corp
                                       :effect (final-effect (resolve-ability
                                                               (if (= target "Yes")
                                                                 {:prompt "Choose an agenda to forfeit" :player :corp
                                                                  :choices {:req #(in-corp-scored? state side %)}
                                                                  :effect (effect (forfeit target)
                                                                                  (move :runner card :rfg)
                                                                                  (clear-wait-prompt :runner))}
                                                                 {:effect (effect (as-agenda :runner card 2)
                                                                                  (clear-wait-prompt :runner))
                                                                  :msg "add it to their score area as an agenda worth 2 points"})
                                                              card nil))} card nil))
                                (resolve-ability
                                  state side
                                  {:effect (effect (as-agenda :runner card 2))
                                   :msg "add it to their score area as an agenda worth 2 points"} card nil)))}]}

   "London Library"
   {:abilities [{:label "Install a non-virus program on London Library"
                 :cost [:click 1]
                 :prompt "Choose a non-virus program to install on London Library from your grip"
                 :choices {:req #(and (is-type? % "Program")
                                      (not (has-subtype? % "Virus"))
                                      (in-hand? %))}
                 :msg (msg "host " (:title target))
                 :effect (effect (runner-install target {:host-card card :no-cost true}))}
                {:label "Add a program hosted on London Library to your Grip"
                 :cost [:click 1]
                 :choices {:req #(:host %)}
                 :msg (msg "add " (:title target) " to their Grip")
                 :effect (effect (move target :hand))}]
    :events {:runner-turn-ends {:effect (req (doseq [c (:hosted card)]
                                               (when (is-type? c "Program")
                                                 (trash state side c))))}}}

   "Maxwell James"
   {:in-play [:link 1]
    :abilities [{:req (req (some #{:hq} (:successful-run runner-reg)))
                 :prompt "Choose a piece of ICE protecting a remote server"
                 :choices {:req #(and (ice? %) (rezzed? %) (is-remote? (second (:zone %))))}
                 :msg "derez a piece of ICE protecting a remote server"
                 :effect (effect (derez target)
                                 (trash card {:cause :ability-cost}))}]}

   "Motivation"
   (let [ability {:msg "look at the top card of their Stack"
                  :label "Look at the top card of Stack (start of turn)"
                  :once :per-turn
                  :req (req (:runner-phase-12 @state))
                  :effect (effect (prompt! card (str "The top card of your Stack is "
                                                     (:title (first (:deck runner)))) ["OK"] {}))}]
   {:flags {:runner-turn-draw true
            :runner-phase-12 (req (some #(card-flag? % :runner-turn-draw true) (all-installed state :runner)))}
    :events {:runner-turn-begins ability}
    :abilities [ability]})

   "Mr. Li"
   {:abilities [{:cost [:click 1]
                 :msg (msg "draw 2 cards")
                 :effect (req (draw state side 2)
                              (let [drawn (get-in @state [:runner :register :most-recent-drawn])]
                                (resolve-ability
                                  state side
                                  {:prompt (str "Choose 1 card to add to the bottom of the Stack")
                                   :msg (msg "add 1 card to the bottom of the Stack")
                                   :choices {:req #(and (in-hand? %)
                                                        (some (fn [c] (= (:cid c) (:cid %))) drawn))}
                                   :effect (req (move state side target :deck))} card nil)))}]}

   "Muertos Gang Member"
   {:effect (req (resolve-ability
                   state :corp
                   {:prompt "Choose a card to derez"
                    :choices {:req #(and (= (:side %) "Corp") (:rezzed %))}
                    :effect (req (derez state side target))}
                  card nil))
    :leave-play (req (resolve-ability
                       state :corp
                       {:prompt "Choose a card to rez, ignoring the rez cost"
                        :choices {:req #(not (:rezzed %))}
                        :effect (req (rez state side target {:ignore-cost :rez-cost})
                                     (system-msg state side (str "rezzes " (:title target) " at no cost")))}
                      card nil))
    :abilities [{:msg "draw 1 card"
                 :effect (effect (trash card {:cause :ability-cost}) (draw))}]}

   "Net Mercur"
   {:abilities [{:counter-cost [:credit 1]
                 :msg "gain 1 [Credits]"
                 :effect (effect (gain :credit 1)
                                 (trigger-event :spent-stealth-credit card))}]
    :events {:spent-stealth-credit
             {:req (req (and (:run @state)
                             (has-subtype? target "Stealth")))
              :once :per-run
              :delayed-completion true
              :effect (effect (show-wait-prompt :corp "Runner to use Net Mercur")
                              (continue-ability
                                {:prompt "Place 1 [Credits] on Net Mercur or draw 1 card?"
                                 :player :runner
                                 :choices ["Place 1 [Credits]" "Draw 1 card"]
                                 :effect (req (if (= target "Draw 1 card")
                                                (do (draw state side)
                                                    (clear-wait-prompt state :corp)
                                                    (system-msg state :runner (str "uses Net Mercur to draw 1 card")))
                                                (do (add-counter state :runner card :credit 1)
                                                    (clear-wait-prompt state :corp)
                                                    (system-msg state :runner (str "places 1 [Credits] on Net Mercur")))))}
                               card nil))}}}

   "Network Exchange"
   {:msg "increase the install cost of non-innermost ICE by 1"
    :events {:pre-corp-install {:req (req (is-type? target "ICE"))
                                :effect (req (when (pos? (count (:dest-zone (second targets))))
                                               (install-cost-bonus state :corp [:credit 1])))}}}

   "Neutralize All Threats"
   {:in-play [:hq-access 1]
    :events {:pre-access {:req (req (and (= target :archives)
                                         (seq (filter #(:trash %) (:discard corp)))))
                          :effect (req (swap! state assoc-in [:per-turn (:cid card)] true))}
             :access {:effect (req (swap! state assoc-in [:runner :register :force-trash] false))}
             :pre-trash {:req (req (let [cards (map first (turn-events state side :pre-trash))]
                                     (and (empty? (filter #(:trash %) cards))
                                          (number? (:trash target)))))
                         :once :per-turn
                         :effect (req (swap! state assoc-in [:runner :register :force-trash] true))}}}

   "New Angeles City Hall"
   {:prevent {:tag [:all]}
    :events {:agenda-stolen {:msg "trash itself" :effect (effect (trash card))}}
    :abilities [{:cost [:credit 2] :msg "avoid 1 tag" :effect (effect (tag-prevent 1))}]}

   "Off-Campus Apartment"
   {:abilities [{:label "Install and host a connection on Off-Campus Apartment"
                 :effect (effect (resolve-ability
                                   {:cost [:click 1]
                                    :prompt "Choose a connection in your Grip to install on Off-Campus Apartment"
                                    :choices {:req #(and (has-subtype? % "Connection")
                                                         (can-pay? state side nil :credit (:cost %))
                                                         (in-hand? %))}
                                    :msg (msg "host " (:title target) " and draw 1 card")
                                    :effect (effect (runner-install target {:host-card card}) (draw))}
                                  card nil))}
                {:label "Host an installed connection"
                 :prompt "Choose a connection to host on Off-Campus Apartment"
                 :choices {:req #(and (has-subtype? % "Connection")
                                      (installed? %))}
                 :msg (msg "host " (:title target) " and draw 1 card")
                 :effect (effect (host card target) (draw))}]}

   "Officer Frank"
   {:abilities [{:cost [:credit 1]
                 :req (req (some #(= :meat %) (map first (turn-events state :runner :damage))))
                 :msg "force the Corp to trash 2 random cards from HQ"
                 :effect (effect (trash-cards :corp (take 2 (shuffle (:hand corp))))
                                 (trash card {:cause :ability-cost}))}]}

   "Oracle May"
   {:abilities [{:cost [:click 1]
                 :once :per-turn
                 :prompt "Choose card type"
                 :choices ["Event" "Hardware" "Program" "Resource"]
                 :effect (req (let [c (first (get-in @state [:runner :deck]))]
                                (system-msg state side (str "spends [Click] to use Oracle May, names " target
                                                            " and reveals " (:title c)))
                                (if (is-type? c target)
                                  (do (system-msg state side (str "gains 2 [Credits] and draws " (:title c)))
                                      (gain state side :credit 2) (draw state side))
                                  (do (system-msg state side (str "trashes " (:title c))) (mill state side)))))}]}

   "Order of Sol"
   {:effect (req (add-watch state :order-of-sol
                            (fn [k ref old new]
                              (when (and (not (zero? (get-in old [:runner :credit])))
                                         (zero? (get-in new [:runner :credit])))
                                (resolve-ability ref side {:msg "gain 1 [Credits]" :once :per-turn
                                                           :effect (effect (gain :credit 1))} card nil)))))
    :events {:runner-turn-begins {:req (req (= (:credit runner) 0)) :msg "gain 1 [Credits]"
                                  :effect (req (gain state :runner :credit 1)
                                               (swap! state assoc-in [:per-turn (:cid card)] true))}
             :corp-turn-begins {:req (req (= (:credit runner) 0)) :msg "gain 1 [Credits]"
                                :effect (req (gain state :runner :credit 1)
                                             (swap! state assoc-in [:per-turn (:cid card)] true))}
             :runner-install {:silent (req (pos? (:credit runner)))
                              :req (req (and (= target card) (= (:credit runner) 0))) :msg "gain 1 [Credits]"
                              :effect (req (gain state :runner :credit 1)
                                           (swap! state assoc-in [:per-turn (:cid card)] true))}}
    :leave-play (req (remove-watch state :order-of-sol))}

   "Paige Piper"
   (let [pphelper (fn [title cards]
                    (let [num (count cards)]
                      {:optional
                       {:prompt (str "Use Paige Piper to trash copies of " title "?")
                        :yes-ability {:prompt "How many would you like to trash?"
                                      :choices (take (inc num) ["0" "1" "2" "3" "4" "5"])
                                      :msg "shuffle their Stack"
                                      :effect (req (let [target (Integer/parseInt target)]
                                                     (trigger-event state side :searched-stack nil)
                                                     (shuffle! state :runner :deck)
                                                     (doseq [c (take target cards)]
                                                       (trash state side c {:unpreventable true}))
                                                     (when (> (int target) 0)
                                                       (system-msg state side (str "trashes " target
                                                                                   " cop" (if (not= target 1) "ies" "y")
                                                                                   " of " title)))))}}}))]
     {:events {:runner-install {:req (req (first-event? state side :runner-install))
                                :delayed-completion true
                                :effect (effect (continue-ability
                                                 (pphelper (:title target)
                                                           (->> (:deck runner)
                                                                (filter #(has? % :title (:title target)))
                                                                (vec)))
                                                 card nil))}}})
   "Patron"
   (let [ability {:prompt "Choose a server for Patron" :choices (req (conj servers "No server"))
                  :req (req (and (not (click-spent? :runner state)) (not (used-this-turn? (:cid card) state))))
                  :msg (msg "target " target)
                  :effect (req (when (not= target "No server")
                                 (update! state side (assoc card :server-target target))))}]
     {:events {:runner-turn-begins ability
               :successful-run
               {:req (req (= (zone->name (get-in @state [:run :server])) (:server-target (get-card state card))))
                :once :per-turn
                :effect (req (let [st card]
                               (swap! state assoc-in [:run :run-effect :replace-access]
                                      {:mandatory true
                                       :effect (effect (resolve-ability
                                                         {:msg "draw 2 cards instead of accessing"
                                                          :effect (effect (draw 2)
                                                                          (update! (dissoc st :server-target)))}
                                                         st nil))})))}
               :runner-turn-ends {:effect (effect (update! (dissoc card :server-target)))}}
      :abilities [ability]})

   "Paparazzi"
   {:effect (req (swap! state update-in [:runner :tagged] inc))
    :events {:pre-damage {:req (req (= target :meat)) :msg "prevent all meat damage"
                          :effect (effect (damage-prevent :meat Integer/MAX_VALUE))}}
    :leave-play (req (swap! state update-in [:runner :tagged] dec))}

   "Personal Workshop"
   (let [remove-counter
         {:req (req (not (empty? (:hosted card))))
          :once :per-turn
          :msg (msg "remove 1 counter from " (:title target)) :choices {:req #(:host %)}
          :effect (req (if (<= (get-in target [:counter :power]) 1)
                         (runner-install state side (dissoc target :counter) {:no-cost true})
                         (add-counter state side target :power -1)))}]
     {:flags {:drip-economy true}
      :abilities [{:label "Host a program or piece of hardware" :cost [:click 1]
                   :prompt "Choose a card to host on Personal Workshop"
                   :choices {:req #(and (#{"Program" "Hardware"} (:type %))
                                        (in-hand? %)
                                        (= (:side %) "Runner"))}
                   :effect (req (if (zero? (:cost target))
                                  (runner-install state side target)
                                  (host state side card
                                        (assoc target :counter {:power (:cost target)}))))
                   :msg (msg "host " (:title target) "")}
                  (assoc remove-counter
                    :label "Remove 1 counter from a hosted card (start of turn)"
                    :cost [:credit 1])
                  {:label "X[Credit]: Remove counters from a hosted card"
                   :choices {:req #(:host %)}
                   :req (req (not (empty? (:hosted card))))
                   :effect (req (let [paydowntarget target
                                      num-counters (get-in paydowntarget [:counter :power] 0)]
                                  (resolve-ability
                                    state side
                                    {:prompt "How many counters to remove?"
                                     :choices {:number (req (min (:credit runner)
                                                                 num-counters))}
                                     :msg (msg "remove " target " counters from " (:title paydowntarget))
                                     :effect (req (do
                                                    (lose state side :credit target)
                                                    (if (= num-counters target)
                                                      (runner-install state side (dissoc paydowntarget :counter) {:no-cost true})
                                                      (add-counter state side paydowntarget :power (- target)))))}
                                    card nil)))}]
      :events {:runner-turn-begins remove-counter}})

   "Political Operative"
   {:req (req (some #{:hq} (:successful-run runner-reg)))
    :abilities [{:prompt "Choose a rezzed card with a trash cost"
                 :choices {:req #(and (:trash %) (rezzed? %))}
                 :effect (req (let [c target]
                                (trigger-event state side :pre-trash c)
                                (let [cost (trash-cost state :runner c)]
                                  (when (can-pay? state side nil [:credit cost])
                                    (resolve-ability
                                      state side
                                      {:msg (msg "pay " cost " [Credit] and trash " (:title c))
                                       :effect (effect (lose :credit cost)
                                                       (trash card {:cause :ability-cost})
                                                       (trash c))}
                                     card nil)))
                                (swap! state update-in [:bonus] dissoc :trash)))}]}

   "Power Tap"
   {:events {:trace {:msg "gain 1 [Credits]" :effect (effect (gain :runner :credit 1))}}}

   "Professional Contacts"
   {:abilities [{:cost [:click 1] :effect (effect (gain :credit 1) (draw))
                 :msg "gain 1 [Credits] and draw 1 card"}]}

   "Public Sympathy"
   {:in-play [:hand-size-modification 2]}

   "Rachel Beckman"
   {:in-play [:click 1 :click-per-turn 1]
    :effect (req (add-watch state :rachel-beckman
                            (fn [k ref old new]
                              (when (is-tagged? new)
                                (remove-watch ref :rachel-beckman)
                                (trash ref :runner card)
                                (system-msg ref side "trashes Rachel Beckman for being tagged")))))}

   "Raymond Flint"
   {:effect (req (add-watch state :raymond-flint
                            (fn [k ref old new]
                              (when (< (get-in old [:corp :bad-publicity]) (get-in new [:corp :bad-publicity]))
                                (when-completed
                                  ; manually trigger the pre-access event to alert Nerve Agent.
                                  (trigger-event-sync ref side :pre-access :hq)
                                  (let [from-hq (access-count state side :hq-access)]
                                    (resolve-ability
                                      ref side
                                      (access-helper-hq
                                        state from-hq
                                        ; see note in Gang Sign
                                        (set (get-in @state [:corp :servers :hq :content])))
                                      card nil)))))))
    :leave-play (req (remove-watch state :raymond-flint))
    :abilities [{:msg "expose 1 card"
                 :choices {:req installed?}
                 :delayed-completion true
                 :effect (effect (expose eid target) (trash card {:cause :ability-cost}))}]}

   "Rolodex"
   {:delayed-completion true
    :msg "look at the top 5 cards of their Stack"
    :effect (req (show-wait-prompt state :corp "Runner to rearrange the top cards of their Stack")
                 (let [from (take 5 (:deck runner))]
                   (if (pos? (count from))
                     (continue-ability state side (reorder-choice :runner :corp from '()
                                                                  (count from) from) card nil)
                     (do (clear-wait-prompt state :corp)
                         (effect-completed state side eid card)))))
    :trash-effect {:effect (effect (system-msg :runner (str "trashes "
                                               (join ", " (map :title (take 3 (:deck runner))))
                                               " from their Stack due to Rolodex being trashed"))
                                       (mill :runner 3))}}

   "Rosetta 2.0"
   {:abilities [{:req (req (and (not (install-locked? state side))
                                (some #(is-type? % "Program") (all-installed state :runner))))
                 :cost [:click 1]
                 :prompt "Choose an installed program to remove from the game"
                 :choices {:req #(and installed? (is-type? % "Program"))}
                 :effect (req (let [n (:cost target)
                                    t (:title target)]
                                (move state side target :rfg)
                                (gain state side :memory (:memoryunits target))
                                (resolve-ability state side
                                  {:prompt "Choose a non-virus program to install"
                                   :msg (req (if (not= target "No install")
                                               (str "remove " t " from the game and install " (:title target) ", lowering its cost by " n)
                                               (str "shuffle their Stack")))
                                   :priority true
                                   :choices (req (cancellable
                                                   (conj (vec (sort-by :title (filter #(and (is-type? % "Program")
                                                                                            (not (has-subtype? % "Virus")))
                                                                                      (:deck runner))))
                                                                       "No install")))
                                   :effect (req (trigger-event state side :searched-stack nil)
                                                (shuffle! state side :deck)
                                                (when (not= target "No install")
                                                  (install-cost-bonus state side [:credit (- n)])
                                                  (runner-install state side target)))} card nil)))}]}

   "Sacrificial Clone"
   {:prevent {:damage [:meat :net :brain]}
    :abilities [{:effect (req (doseq [c (concat (get-in runner [:rig :hardware])
                                                (filter #(not (has-subtype? % "Virtual"))
                                                        (get-in runner [:rig :resource]))
                                                (:hand runner))]
                                (trash state side c {:cause :ability-cost}))
                              (lose state side :credit :all :tag :all :run-credit :all)
                              (damage-prevent state side :net Integer/MAX_VALUE)
                              (damage-prevent state side :meat Integer/MAX_VALUE)
                              (damage-prevent state side :brain Integer/MAX_VALUE))}]}

   "Sacrificial Construct"
   {:prevent {:trash [:program :hardware]}
    :abilities [{:effect (effect (trash-prevent :program 1) (trash-prevent :hardware 1)
                                 (trash card {:cause :ability-cost}))}]}

   "Safety First"
   {:in-play [:hand-size-modification -2]
    :events {:runner-turn-ends {:req (req (< (count (:hand runner)) (hand-size state :runner)))
                                :msg (msg "draw a card")
                                :effect (effect (draw 1))}}}

   "Salvaged Vanadis Armory"
   {:events {:damage
             {:effect (req (show-wait-prompt state :corp "Runner to use Salvaged Vanadis Armory")
                           (resolve-ability state :runner
                                            {:optional
                                             {:prompt "Use Salvaged Vanadis Armory?"
                                              :yes-ability {:msg (msg "force the Corp to trash the top " (get-turn-damage state :runner) " cards of R&D and trash itself")
                                                            :effect (effect (mill :corp (get-turn-damage state :runner))
                                                                            (clear-wait-prompt :corp)
                                                                            (trash card {:unpreventable true}))}
                                              :no-ability {:effect (effect (clear-wait-prompt :corp))}}}
                                            card nil))}}}

   "Salsette Slums"
   {:flags {:slow-trash (req true)}
    :events {:runner-install
             {:req (req (= card target))
              :silent (req true)
              :effect (effect (update! (assoc card :slums-active true)))}
             :runner-turn-begins
             {:effect (effect (update! (assoc card :slums-active true)))}
             :pre-trash
             {:req (req (and (:slums-active card)
                             (:trash target)
                             (= (:side target) "Corp")))
              :effect (req (toast state :runner (str "Click Salsette Slums to remove " (:title target)
                                                     " from the game") "info" {:prevent-duplicates true}))}}
    :abilities [{:label "Remove the currently accessed card from the game instead of trashing it"
                 :req (req (let [c (:card (first (get-in @state [:runner :prompt])))]
                             (if-let [trash-cost (trash-cost state side c)]
                               (if (can-pay? state :runner nil :credit trash-cost)
                                 (if (:slums-active card)
                                   true
                                   ((toast state :runner "Can only use a copy of Salsette Slums once per turn.") false))
                                 ((toast state :runner (str "Unable to pay for " (:title c) ".")) false))
                               ((toast state :runner "Not currently accessing a card with a trash cost.") false))))
                 :msg (msg (let [c (:card (first (get-in @state [:runner :prompt])))]
                             (str "pay " (trash-cost state side c) " [Credits] and remove " (:title c) " from the game")))
                 :effect (req (let [c (:card (first (get-in @state [:runner :prompt])))]
                                (deactivate state side c)
                                (move state :corp c :rfg)
                                (pay state :runner card :credit (trash-cost state side c))
                                (swap! state update-in [:turn-events] #(cons [:runner-trash c] %))
                                (update! state side (dissoc card :slums-active))
                                (close-access-prompt state side)
                                (when-not (:run @state)
                                  (swap! state dissoc :access))))}
                {:label "Remove a card trashed this turn from the game"
                 :req (req (if (:slums-active card)
                             true
                             ((toast state :runner "Can only use a copy of Salsette Slums once per turn.") false)))
                 :effect (effect (resolve-ability
                                   {; only allow targeting cards that were trashed this turn -- not perfect, but good enough?
                                    :choices {:req #(some (fn [c] (= (:cid %) (:cid c)))
                                                          (map first (turn-events state side :runner-trash)))}
                                    :msg (msg "remove " (:title target) " from the game")
                                    :effect (req (deactivate state side target)
                                                 (move state :corp target :rfg)
                                                 (swap! state update-in [:turn-events] #(cons [:runner-trash target] %))
                                                 (update! state side (dissoc card :slums-active)))}
                                   card nil))}]}

   "Same Old Thing"
   {:abilities [{:cost [:click 2]
                 :req (req (and (not (seq (get-in @state [:runner :locked :discard])))
                                (< 0 (count (filter #(is-type? % "Event") (:discard runner))))))
                 :prompt "Choose an event to play"
                 :msg (msg "play " (:title target))
                 :show-discard true
                 :choices {:req #(and (is-type? % "Event")
                                      (= (:zone %) [:discard]))}
                 :effect (effect (trash card {:cause :ability-cost}) (play-instant target))}]}

   "Scrubber"
   {:recurring 2}

   "Security Testing"
   (let [ability {:prompt "Choose a server for Security Testing" :choices (req (conj servers "No server"))
                  :msg (msg "target " target)
                  :req (req (and (not (click-spent? :runner state)) (not (used-this-turn? (:cid card) state))))
                  :effect (req (when (not= target "No server")
                                 (update! state side (assoc card :server-target target))))}]
     {:events {:runner-turn-begins ability
               :successful-run
               {:req (req (= (zone->name (get-in @state [:run :server])) (:server-target (get-card state card))))
                :once :per-turn
                :effect (req (let [st card]
                               (swap! state assoc-in [:run :run-effect :replace-access]
                                      {:mandatory true
                                       :effect (effect (resolve-ability
                                                         {:msg "gain 2 [Credits] instead of accessing"
                                                          :effect (effect (gain :credit 2)
                                                                          (update! (dissoc st :server-target)))}
                                                         st nil))})))}
               :runner-turn-ends {:effect (effect (update! (dissoc card :server-target)))}}
      :abilities [ability]})

   "Spoilers"
   {:events {:agenda-scored {:interactive (req true)
                             :msg "trash the top card of R&D" :effect (effect (mill :corp))}}}

   "Starlight Crusade Funding"
   {:msg "ignore additional costs on Double events"
    :effect (req (swap! state assoc-in [:runner :register :double-ignore-additional] true))
    :events {:runner-turn-begins
             {:msg "lose [Click] and ignore additional costs on Double events"
              :effect (req (lose state :runner :click 1)
                           (swap! state assoc-in [:runner :register :double-ignore-additional] true))}}
    :leave-play (req (swap! state update-in [:runner :register] dissoc :double-ignore-additional))}

   "Stim Dealer"
   {:events {:runner-turn-begins
             {:effect (req (if (>= (get-in card [:counter :power] 0) 2)
                             (do (add-counter state side card :power (- (get-in card [:counter :power] 0)))
                                 (damage state side eid :brain 1 {:unpreventable true :card card})
                                 (system-msg state side "takes 1 brain damage from Stim Dealer"))
                             (do (add-counter state side card :power 1)
                                 (gain state side :click 1)
                                 (system-msg state side "uses Stim Dealer to gain [Click]"))))}}}

   "Street Peddler"
   {:effect (req (doseq [c (take 3 (:deck runner))]
                   (host state side (get-card state card) c {:facedown true})))
    :abilities [{:req (req (not (install-locked? state side)))
                 :prompt "Choose a card on Street Peddler to install"
                 :choices (req (cancellable (filter #(and (not (is-type? % "Event"))
                                                          (can-pay? state side nil (modified-install-cost state side % [:credit -1])))
                                                    (:hosted card))))
                 :msg (msg "install " (:title target) " lowering its install cost by 1 [Credits]")
                 :effect (req
                           (when (can-pay? state side nil (modified-install-cost state side target [:credit -1]))
                             (install-cost-bonus state side [:credit -1])
                             (trash state side (update-in card [:hosted]
                                                          (fn [coll]
                                                            (remove-once #(not= (:cid %) (:cid target)) coll)))
                                    {:cause :ability-cost})
                             (runner-install state side (dissoc target :facedown))))}]}

   "Symmetrical Visage"
   {:events {:runner-click-draw {:req (req (genetics-trigger? state side :runner-click-draw))
                                 :msg "gain 1 [Credits]"
                                 :effect (effect (gain :credit 1))}}}

   "Synthetic Blood"
   {:events {:damage {:req (req (genetics-trigger? state side :damage))
                      :msg "draw 1 card"
                      :effect (effect (draw :runner))}}}

   "Tallie Perrault"
   {:abilities [{:label "Draw 1 card for each Corp bad publicity"
                 :effect (effect (trash card {:cause :ability-cost})
                                 (draw (+ (:bad-publicity corp) (:has-bad-pub corp))))
                 :msg (msg "draw " (:bad-publicity corp) " cards")}]
    :events {:play-operation
             {:req (req (or (has-subtype? target "Black Ops")
                            (has-subtype? target "Gray Ops")))
              :effect (req (show-wait-prompt state :corp "Runner to use Tallie Perrault")
                           (resolve-ability
                             state :runner
                             {:optional
                              {:prompt "Use Tallie Perrault to give the Corp 1 bad publicity and take 1 tag?"
                               :player :runner
                               :yes-ability {:msg "give the Corp 1 bad publicity and take 1 tag"
                                             :delayed-completion true
                                             :effect (effect (gain :corp :bad-publicity 1)
                                                             (tag-runner :runner eid 1)
                                                             (clear-wait-prompt :corp))}
                               :no-ability {:effect (effect (clear-wait-prompt :corp))}}}
                            card nil))}}}

   "Tech Trader"
   {:events {:runner-trash {:req (req (and (= side :runner) (= (second targets) :ability-cost)))
                            :msg "gain 1 [Credits]"
                            :effect (effect (gain :credit 1))}}}

   "Technical Writer"
   {:events {:runner-install {:silent (req true)
                              :req (req (some #(= % (:type target)) '("Hardware" "Program")))
                              :effect (effect (add-counter :runner card :credit 1)
                                              (system-msg (str "places 1 [Credits] on Technical Writer")))}}
    :abilities [{:cost [:click 1]
                 :msg (msg "gain " (get-in card [:counter :credit] 0) " [Credits]")
                 :effect (effect (gain :credit (get-in card [:counter :credit] 0))
                                 (trash card {:cause :ability-cost}))}]}

   "Temple of the Liberated Mind"
   {:abilities [{:cost [:click 1]
                 :label "Place 1 power counter"
                 :msg "place 1 power counter on it"
                 :effect (effect (add-counter card :power 1))}
                {:label "Gain [Click]"
                 :counter-cost [:power 1]
                 :req (req (= (:active-player @state) :runner))
                 :msg "gain [Click]" :once :per-turn
                 :effect (effect (gain :click 1))}]}

   "Temüjin Contract"
   {:data {:counter {:credit 20}}
    :prompt "Choose a server for Temüjin Contract" :choices (req servers)
    :msg (msg "target " target)
    :req (req (not (:server-target card)))
    :effect (effect (update! (assoc card :server-target target)))
    :events {:successful-run
             {:req (req (= (zone->name (get-in @state [:run :server])) (:server-target (get-card state card))))
              :msg "gain 4 [Credits]"
              :effect (req (let [creds (get-in card [:counter :credit])]
                             (gain state side :credit 4)
                             (set-prop state side card :counter {:credit (- creds 4)})
                             (when (= 0 (get-in (get-card state card) [:counter :credit]))
                               (trash state side card {:unpreventable true}))))}}}

   "The Archivist"
   {:in-play [:link 1]
    :events {:agenda-scored {:req (req (or (has-subtype? target "Initiative")
                                           (has-subtype? target "Security")))
                             :delayed-completion true
                             :msg "force the Corp to initiate a trace"
                             :label "Trace 1 - If unsuccessful, take 1 bad publicity"
                             :trace {:base 1
                                     :unsuccessful {:effect (effect (gain :corp :bad-publicity 1)
                                                                    (system-msg :corp (str "takes 1 bad publicity")))}}}}}

   "The Black File"
   {:msg "prevent the Corp from winning the game unless they are flatlined"
    :effect (req (swap! state assoc-in [:corp :cannot-win-on-points] true))
    :events {:runner-turn-begins
             {:effect (req (if (>= (get-in card [:counter :power] 0) 2)
                             (do (move state side (dissoc card :counter) :rfg)
                                 (swap! state update-in [:corp] dissoc :cannot-win-on-points)
                                 (system-msg state side "removes The Black File from the game")
                                 (gain-agenda-point state :corp 0))
                             (add-counter state side card :power 1)))}}
    :trash-effect {:effect (req (swap! state update-in [:corp] dissoc :cannot-win-on-points)
                                (gain-agenda-point state :corp 0))}
    :leave-play (req (swap! state update-in [:corp] dissoc :cannot-win-on-points)
                     (gain-agenda-point state :corp 0))}

   "The Helpful AI"
   {:in-play [:link 1]
    :abilities [{:msg (msg "give +2 strength to " (:title target))
                 :choices {:req #(and (has-subtype? % "Icebreaker")
                                      (installed? %))}
                 :effect (effect (update! (assoc card :hai-target target))
                                 (trash (get-card state card) {:cause :ability-cost})
                                 (update-breaker-strength target))}]
    :events {:runner-turn-ends nil :corp-turn-ends nil :pre-breaker-strength nil}
    :trash-effect {:effect
                   (effect (register-events
                             (let [hai {:effect (effect (unregister-events card)
                                                        (update! (dissoc card :hai-target))
                                                        (update-breaker-strength (:hai-target card)))}]
                               {:runner-turn-ends hai :corp-turn-ends hai
                                :pre-breaker-strength {:req (req (= (:cid target)(:cid (:hai-target card))))
                                                       :effect (effect (breaker-strength-bonus 2))}}) card))}}

   "The Shadow Net"
   {:abilities [{:cost [:click 1 :forfeit]
                 :req (req (< 0 (count (filter #(is-type? % "Event") (:discard runner)))))
                 :label "Play an event from your Heap, ignoring all costs"
                 :prompt "Choose an event to play"
                 :msg (msg "play " (:title target) " from the Heap, ignoring all costs")
                 :choices (req (cancellable (filter #(is-type? % "Event") (:discard runner)) :sorted))
                 :effect (effect (play-instant nil target {:ignore-cost true}))}]}

   "The Supplier"
   (let [ability  {:label "Install a hosted card (start of turn)"
                   :prompt "Choose a card hosted on The Supplier to install"
                   :req (req (some #(can-pay? state side nil (modified-install-cost state side % [:credit -2]))
                                        (:hosted card)))
                   :choices {:req #(= "The Supplier" (:title (:host %)))}
                   :effect (req
                             (runner-can-install? state side target nil)
                             (when (and (can-pay? state side nil (modified-install-cost state side target [:credit -2]))
                                           (not (and (:uniqueness target) (in-play? state target))))
                                  (install-cost-bonus state side [:credit -2])
                                  (runner-install state side target)
                                  :once :per-turn
                                  (system-msg state side (str "uses The Supplier to install " (:title target) " lowering its install cost by 2"))
                                  (update! state side (-> card
                                                          (assoc :supplier-installed (:cid target))
                                                          (update-in [:hosted]
                                                                     (fn [coll]
                                                                       (remove-once #(not= (:cid %) (:cid target)) coll)))))))}]
   {:flags {:drip-economy true}  ; not technically drip economy, but has an interaction with Drug Dealer
    :abilities [{:label "Host a resource or piece of hardware" :cost [:click 1]
                 :prompt "Choose a card to host on The Supplier"
                 :choices {:req #(and (#{"Resource" "Hardware"} (:type %))
                                      (in-hand? %))}
                 :effect (effect (host card target)) :msg (msg "host " (:title target) "")}
                ability]

    ; A card installed by The Supplier is ineligible to receive the turn-begins event for this turn.
    :suppress {:runner-turn-begins {:req (req (= (:cid target) (:supplier-installed (get-card state card))))}}
    :events {:runner-turn-begins ability
             :runner-turn-ends {:req (req (:supplier-installed card))
                                :effect (effect (update! (dissoc card :supplier-installed)))}}})

   "The Source"
   {:effect (effect (update-all-advancement-costs))
    :leave-play (effect (update-all-advancement-costs))
    :events {:agenda-scored {:effect (effect (trash card))}
             :agenda-stolen {:effect (effect (trash card))}
             :pre-advancement-cost {:effect (effect (advancement-cost-bonus 1))}
             :pre-steal-cost {:effect (effect (steal-cost-bonus [:credit 3]))}}}

   "The Turning Wheel"
   {:events {:run {:effect (effect (update! (dissoc card :agenda-stolen :counters-spent)))}
             :agenda-stolen {:effect (effect (update! (assoc card :agenda-stolen true)))
                             :silent (req true)}
             :pre-access {:req (req (and (:counters-spent card) (#{:hq :rd} target)))
                          :effect (effect (access-bonus (:counters-spent card 0)))
                          :silent (req true)}
             :run-ends {:req (req (and (not (:agenda-stolen card))
                                       (#{:hq :rd} target)))
                        :effect (effect (add-counter card :power 1)
                                        (system-msg :runner (str "places a power counter on " (:title card))))
                        :silent (req true)}}
    :abilities [{:counter-cost [:power 2]
                 :req (req (:run @state))
                 :msg "access 1 additional card from HQ or R&D for the remainder of the run"
                 :effect (effect (update! (update-in card [:counters-spent] #(inc (or % 0)))))}]}

   "Theophilius Bagbiter"
   {:effect (req (lose state :runner :credit :all :run-credit :all)
                 (add-watch state :theophilius-bagbiter
                            (fn [k ref old new]
                              (let [credit (get-in new [:runner :credit])]
                                (when (not= (get-in old [:runner :credit]) credit)
                                  (swap! ref assoc-in [:runner :hand-size-base] credit))))))
    :leave-play (req (remove-watch state :theophilius-bagbiter)
                     (swap! state assoc-in [:runner :hand-size-base] 5))}

   "Tri-maf Contact"
   {:abilities [{:cost [:click 1] :msg "gain 2 [Credits]" :once :per-turn
                 :effect (effect (gain :credit 2))}]
    :trash-effect {:effect (effect (damage eid :meat 3 {:unboostable true :card card}))}}

   "Tyson Observatory"
   {:abilities [{:prompt "Choose a piece of Hardware" :msg (msg "add " (:title target) " to their Grip")
                 :choices (req (cancellable (filter #(is-type? % "Hardware") (:deck runner)) :sorted))
                 :cost [:click 2]
                 :effect (effect (trigger-event :searched-stack nil)
                                 (shuffle! :deck)
                                 (move target :hand))}]}

   "Underworld Contact"
   (let [ability {:label "Gain 1 [Credits] (start of turn)"
                  :msg "gain 1 [Credits]"
                  :once :per-turn
                  :req (req (and (>= (:link runner) 2) (:runner-phase-12 @state)))
                  :effect (effect (gain :credit 1))}]
   {:flags {:drip-economy true}
    :abilities [ability]
    :events {:runner-turn-begins ability}})

   "Utopia Shard"
   {:abilities [{:effect (effect (trash-cards :corp (take 2 (shuffle (:hand corp))))
                                 (trash card {:cause :ability-cost}))
                 :msg "force the Corp to discard 2 cards from HQ at random"}]
    :install-cost-bonus (req (if (and run (= (:server run) [:hq]) (zero? (:position run)))
                               [:credit -15 :click -1] nil))
    :effect (req (when (and run (= (:server run) [:hq]) (zero? (:position run)))
                   (when-completed (register-successful-run state side (:server run))
                                   (do (swap! state update-in [:runner :prompt] rest)
                                       (handle-end-run state side)))))}

   "Virus Breeding Ground"
   {:events {:runner-turn-begins {:effect (effect (add-counter card :virus 1))}}
    :abilities [{:cost [:click 1]
                 :msg (msg "move 1 virus counter to " (:title target))
                 :req (req (pos? (get-in card [:counter :virus] 0)))
                 :choices {:req #(and (has-subtype? % "Virus")
                                      (pos? (get-in % [:counter :virus] 0)))}
                 :effect (req (when (pos? (get-virus-counters state side target))
                                (add-counter state side card :virus -1)
                                (add-counter state side target :virus 1)))}]}

   "Wasteland"
   {:events {:runner-trash {:req (req (and (first-installed-trash-own? state :runner)
                                           (installed? target)
                                           (= (:side target) "Runner")))
                            :effect (effect (gain :credit 1))
                            :msg "gain 1 [Credits]"}}}

   "Wireless Net Pavilion"
   {:effect (effect (trash-resource-bonus -2))
    :leave-play (effect (trash-resource-bonus 2))}

   "Woman in the Red Dress"
   (let [ability {:msg (msg "reveal " (:title (first (:deck corp))) " on the top of R&D")
                  :label "Reveal the top card of R&D (start of turn)"
                  :once :per-turn
                  :req (req (:runner-phase-12 @state))
                  :effect (effect (show-wait-prompt :runner "Corp to decide whether or not to draw with Woman in the Red Dress")
                                  (resolve-ability
                                    {:optional
                                     {:player :corp
                                      :prompt (msg "Draw " (:title (first (:deck corp))) "?")
                                      :yes-ability {:effect (effect (clear-wait-prompt :runner)
                                                                    (system-msg (str "draws " (:title (first (:deck corp)))))
                                                                    (draw))}
                                      :no-ability {:effect (effect (clear-wait-prompt :runner)
                                                                   (system-msg "doesn't draw with Woman in the Red Dress"))}}}
                                    card nil))}]
   {:events {:runner-turn-begins ability}
    :abilities [ability]})

   "Wyldside"
   {:flags {:runner-turn-draw true
            :runner-phase-12 (req (< 1 (count (filter #(card-flag? % :runner-turn-draw true)
                                                      (cons (get-in @state [:runner :identity])
                                                            (all-installed state :runner))))))}

    :events {:runner-turn-begins {:effect (req (lose state side :click 1)
                                               (when-not (get-in @state [:per-turn (:cid card)])
                                                 (system-msg state side "uses Wyldside to draw 2 cards and lose [Click]")
                                                 (draw state side 2)))}}
    :abilities [{:msg "draw 2 cards and lose [Click]"
                 :once :per-turn
                 :effect (effect (draw 2))}]}

   "Xanadu"
   {:events {:pre-rez-cost {:req (req (ice? target))
                            :effect (effect (rez-cost-bonus 1))}}}

   "Zona Sul Shipping"
   {:events {:runner-turn-begins {:effect (effect (add-counter card :credit 1))}}
    :abilities [{:cost [:click 1]
                 :msg (msg "gain " (get-in card [:counter :credit] 0) " [Credits]")
                 :label "Take all credits"
                 :effect (effect (gain :credit (get-in card [:counter :credit] 0))
                                 (add-counter card :credit
                                              (- (get-in card [:counter :credit] 0))))}]
    :effect (req (add-watch state (keyword (str "zona-sul-shipping" (:cid card)))
                            (fn [k ref old new]
                              (when (is-tagged? new)
                                (remove-watch ref (keyword (str "zona-sul-shipping" (:cid card))))
                                (trash ref :runner card)
                                (system-msg ref side "trashes Zona Sul Shipping for being tagged")))))}})
