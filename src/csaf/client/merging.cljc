(ns csaf.client.merging)

(defn merge-members-view
  [members]
  [:div
   [:form {:method "POST" :action "/admin/users/merge"}
    [:datalist {:id "member-names"}
     (for [{:members/keys [id first-name last-name]} members]
       [:option {:value (str first-name " " last-name " (#" id ")")}])]
    [:label {:class "block"} "Correct User: "
     [:input {:type "text" :name "correct-member-name"
              :placeholder "Real Thrower"
              :required true :list "member-names"
              :id "correct-member-input"}]]

    [:details [:summary "Correct Member Page"]
     [:div {:id "correct-member-frame" :class "hidden"}]]

    [:label {:class "block"} "User to merge: "
     [:input {:type "text" :name "wrong-member-name"
              :placeholder "Wrong Thrower"
              :required true :list "member-names"
              :id "wrong-member-input"}]]

    [:details [:summary "Incorrect Member Page"]
     [:div {:id "incorrect-member-frame" :class "hidden"}]]

    [:button "Merge"]]

   [:script
    [:hiccup/raw-html
     "
const makeHandler = (frameTarget) =>
   (e) => {
    const memberNameId = e.target.value;
    const id = memberNameId.match(/^.*\\(#(\\d+)\\)$/)[1]
    const frameWrapper = document.querySelector(frameTarget);
    frameWrapper.classList.add('hidden');
    fetch(`/athletes/${id}/embed`).then((resp) => resp.text()).then((body) => {
      frameWrapper.innerHTML = body;
      frameWrapper.classList.remove('hidden');
    });
};
document.querySelector('#correct-member-input').addEventListener('blur', makeHandler('#correct-member-frame'));
document.querySelector('#wrong-member-input').addEventListener('blur', makeHandler('#incorrect-member-frame'));
"]]])
