(ns csaf.client.pages-editor)

(defn pages-editor-view
  [pages]
  [:div
   [:h2 "Pages"]

   [:ul
    (doall
      (for [{:pages/keys [title]} pages]
        [:li [:a {:tw "block hover:bg-gray-200"
                  :href (str "/admin/pages/" title)} title]]))]

   [:form {:method "post" :action "/admin/pages"}
    [:input {:type "text" :name "title" :required true :placeholder "title"
             :tw "border-black border-solid border-1px"}]
    [:input {:type "submit" :value "Create"}]]])

(defn page-editor-view
  [{:keys [title content]}]
  [:div
   [:a {:href "/admin/pages"} "Back"]
   [:h2 (str "Editing " title)]

   [:div#editor
    [:hiccup/raw-html content]]

   [:form#editor-save-form {:method "post" :action (str "/admin/pages/" title)}
    [:input {:type "hidden" :name "content" :value ""}]
    [:input {:type "submit" :value "Save"}]]

   [:script {:src "/js/quill-2.0.2.js"}]
   [:script "
const Link = Quill.import('formats/link');
class FixedLink extends Link {
  static create(value) {
    let node = super.create(value);
    value = this.sanitize(value);
    node.setAttribute('href', value);
    node.removeAttribute('target');
    if (!value.startsWith('http')) {
      node.removeAttribute('rel');
    }
    console.log('creating link', value, node);
    return node;
  }
}
Quill.register(FixedLink);
const quill = new Quill('#editor', {
 theme: 'snow',
 modules: {toolbar:  [
  [{ header: ['1', '2', '3', false] }],
  ['bold', 'italic', 'underline', 'link'],
  [{ list: 'ordered' }, { list: 'bullet' }],
  ['clean', 'image'],
]}});
const form = document.getElementById('editor-save-form');
form.onsubmit = function(e) {
  e.preventDefault();
  form.elements['content'].value = quill.getSemanticHTML();
  form.submit();
};
"]])
