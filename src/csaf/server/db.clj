(ns csaf.server.db
  (:require
   [clojure.java.io :as io]
   [clojure.math :as math]
   [clojure.string :as string]
   [com.rpl.specter :as x]
   [next.jdbc :as jdbc]
   [next.jdbc.prepare :as jdbc.prepare]
   [next.jdbc.result-set :as jdbc.rs]
   [hikari-cp.core :as hikari]
   [csaf.config :as config]
   [jsonista.core :as json]
   [camel-snake-kebab.core :refer [->kebab-case]]
   [crypto.password.bcrypt :as bcrypt]
   [csaf.client.results :as results])
  (:import
   (org.postgresql.util PGobject)
   (java.sql PreparedStatement)))

;;; Configuration

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(def ->json json/write-value-as-string)
(def <-json #(json/read-value % mapper))

(defn ->pgobject
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (->json x)))))

(defn <-pgobject
  [^org.postgresql.util.PGobject v]
  (let [type (.getType v)
        value (.getValue v)]
    (if (#{"json" "jsonb"} type)
      (when value
        (with-meta (<-json value) {:pgtype type}))
      value)))

(extend-protocol jdbc.prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (.setObject s i (->pgobject m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v))))

(extend-protocol jdbc.rs/ReadableColumn
  org.postgresql.util.PGobject
  (read-column-by-label [^org.postgresql.util.PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^org.postgresql.util.PGobject v _2 _3]
    (<-pgobject v)))

(defonce datasource
  (delay (hikari/make-datasource
           {:jdbc-url
            (str "jdbc:"
                 (config/conf :csaf/database-url))
            #_#_:re-write-batched-inserts true})))

(comment
  (hikari/close-datasource @datasource)
  (jdbc/execute! @datasource ["drop table game_results_placing"])
  )

;;; Init

(defn init-db!
  []
  (jdbc/execute!
    @datasource
    [(slurp (io/resource "sql/create_tables.sql"))]))

;;; Migrations

(defn migrate-member-passwords!
  "Hash user passwords"
  []
  (jdbc/execute! @datasource ["alter table members add column old_password text"])
  (jdbc/execute! @datasource ["update members set old_password = password_hash"])
  (->> (jdbc/plan @datasource ["select id, old_password from members"])
       (run! (fn [row]
               (jdbc/execute!
                 @datasource
                 ["update members set password_hash = ? where id = ?"
                  (bcrypt/encrypt (:old_password row))
                  (:id row)]))))
  (jdbc/execute! @datasource ["alter table members drop column old_password"]))

(defn migrate-membership-enum!
  "Add 'admin' variant membership_site_code enum"
  []
  (jdbc/execute!
    @datasource
    ["alter type membership_site_code add value if not exists 'admin'"]))

(defn migrate-member-roles!
  []
  (jdbc/execute!
    @datasource
    ["insert into members_roles (member_id, role)
      select id, site_code from members"]))

(defn migrate-add-youth-classes
  []
  (jdbc/execute!
    @datasource
    ["alter type membership_class_code add value 'womensyouth'"]))

(defn migrate-add-game-instance-source
  []
  (jdbc/execute!
    @datasource
    ["alter table game_instances add column source_sheet_id integer references score_sheets(id)"]))

(declare generate-password)
(defn migrate-randomize-old-member-passwords
  []
  (->> (jdbc/plan @datasource ["select id, password_hash from members"])
       (run! (fn [row]
               (when (bcrypt/check "newpass" (:password_hash row))
                 (jdbc/execute!
                   @datasource
                   ["update members set password_hash = ? where id = ?"
                    (bcrypt/encrypt (generate-password))
                    (:id row)]))))))

(defn migrate-game-result-pkey
  []
  (jdbc/execute!
    @datasource
    ["alter table game_results_placing drop constraint game_results_placing_pkey;
      alter table game_results_placing add primary key (member_id, game_instance_id, class)"]))

(defn migrate-add-more-youth-juinor-masters-classes
  []
  (jdbc/execute!
    @datasource
    ["alter type membership_class_code add value 'womensjunior';
      alter type membership_class_code add value 'youth';
      alter type membership_class_code add value 'masters50+';
      alter type membership_class_code add value 'masters60+';
      alter type membership_class_code add value 'womensmaster50+';
      alter type membership_class_code add value 'womensmaster60+';
"]))

;;; member queries

(defn authenticate-user
  [{:keys [login password]}]
  (when-let [{:members/keys [id password-hash]}
             (jdbc/execute-one!
               @datasource
               ["select id, password_hash from members where login = ? and status = 'active'"
                login]
               jdbc/snake-kebab-opts)]
       (when (bcrypt/check password password-hash)
         id)))

(defn all-members
  []
  (jdbc/execute!
    @datasource
    ["select id, first_name, last_name from members
      where status = 'active' and site_code = 'member'
      order by last_name asc, first_name asc"]
    jdbc/snake-kebab-opts))

(defn all-members-regardless-of-class
  []
  (jdbc/execute!
    @datasource
    ["select * from members where status = 'active'
      order by last_name asc, first_name asc"]
    jdbc/snake-kebab-opts))

(defn member
  [id]
  (-> (jdbc/execute-one!
        @datasource
        ["select members.*, jsonb_agg(members_roles.role) as roles from members
     left join members_roles on members.id = members_roles.member_id
     where id = ? group by members.id" id]
        jdbc/snake-kebab-opts)
      (update :roles (comp #(disj % nil) set))))

(comment
  (member 1631)
  )

(defn generate-password
  []
  (with-open [f (io/reader (io/file "/usr/share/dict/words"))]
    (->> (line-seq f)
         (sequence
           (comp (filter (fn [s] (< 4 (count s) 8)))
                 (filter (fn [s] (re-matches #"[a-z]+" s)))))
         ((juxt rand-nth rand-nth rand-nth))
         (string/join "-"))))

(defn reset-member-password!
  [member-id]
  (let [new-pass (generate-password)]
    (jdbc/execute!
      @datasource
      ["update members set password_hash = ? where id = ?"
       (bcrypt/encrypt new-pass) member-id])
    new-pass))

(comment
  (member 1442)
  #_(jdbc/execute!
    @datasource
    ["update members set site_code = 'admin'
      where id = any('{1442, 962}')"])

  (jdbc/execute!
    @datasource
    ["insert into members_roles (member_id, role)
      values (1442, 'admin'), (962, 'admin'), (1250, 'admin')"])

  (jdbc/execute!
    @datasource
    ["insert into members_roles (member_id, role)
      values (1473, 'admin')"])

  (jdbc/execute!
    @datasource
    ["select * from members where site_code = 'admin'"])
  )

(defn create-admin!
  [{:keys [login password first-name last-name]}]
  (jdbc/execute!
    @datasource
    ["with new_member as (
       insert into members (first_name, last_name, login, password_hash, class, status)
       values (?, ?, ?, ?, 'unknown', 'inactive') returning *)
      insert into members_roles (member_id, role) select id, 'admin' from new_member;"
     first-name last-name login (bcrypt/encrypt password)]))

(comment
  (create-admin!
    {:first-name "James"
     :last-name "Cash"
     :login "jamesnvc"
     :password "foobar"})
  (member 958)
  (jdbc/execute!
    @datasource
    ["select * from members where first_name = 'Cash'"]
    )

  (jdbc/execute!
    @datasource
    ["select login, id from members where login = 'AThompson'"])

  (jdbc/execute!
    @datasource
    ["select login from members where site_code = 'admin'"])

  (jdbc/execute!
    @datasource
    ["select members.login from members join members_roles on member_id = id
      where role = 'admin'"])
  )

(defn member-roles
  [member-id]
  (->> (jdbc/execute!
         @datasource
         ["select \"role\" from members_roles where \"member_id\" = ?"
          member-id])
       (into #{} (map (comp keyword :members_roles/role)))))

(defn member-game-results
  [member-id]
  (->> (jdbc/execute!
         @datasource
         ["select json_agg(row_to_json(game_member_results.*)) as result,
        game_instances.\"date\",
        min(game_results_placing.placing) as placing,
        max(games.name) as game_name
      from game_member_results
      join game_instances on game_instances.id = game_member_results.game_instance
      join games on games.id = game_instances.game_id
      join game_results_placing on game_member_results.member_id = game_results_placing.member_id and game_results_placing.game_instance_id = game_instances.id
      where game_member_results.member_id = ?
      group by game_instances.id
      order by game_instances.date desc" member-id]
         jdbc/snake-kebab-opts)
       (x/transform
         [x/ALL :result x/ALL x/MAP-KEYS]
         ->kebab-case)
       (x/transform
         [x/ALL
          :result]
         (fn [results]
           (into {} (map (fn [r] [(:event r) r]))
                 results)))))

(defn update-should-be-masters
  []
  (jdbc/execute!
    @datasource
    ["update members set master_age = true where master_age is false
    and exists (select true from game_member_results
      where member_id = members.id and
        class = any(ARRAY['masters', 'womensmaster', 'masters50+', 'masters60+',
                          'womensmaster50+', 'womensmaster60+'
                         ]::membership_class_code[]))"]))

(comment
  (first (member-game-results 958))
  )

(defn member-pr-results
  [member-id]
  ;; TODO: switch to plan/transduce
  (->> (jdbc/execute!
         @datasource
         ["with prs as (select distinct on (event)
             event,
             last_value(class) over wnd as class,
             last_value(game_instance) over wnd as game_id,
             last_value(distance_inches) over wnd as distance_inches,
             last_value(clock_minutes) over wnd as clock_minutes,
             last_value(weight) over wnd as weight,
             last_value(score) over wnd as score
      from game_member_results
      where member_id = ? and event <> 'caber' and score > 0
      window wnd as (
        partition by event order by distance_inches, weight
        rows between unbounded preceding and unbounded following
      )
      union all
      select distinct on (event)
             event,
             last_value(class) over wnd as class,
             last_value(game_instance) over wnd as game_id,
             last_value(distance_inches) over wnd as distance_inches,
             last_value(clock_minutes) over wnd as clock_minutes,
             last_value(weight) over wnd as weight,
             last_value(score) over wnd as score
      from game_member_results
      where member_id = ? and event = 'caber' and score > 0
      window wnd as (
        partition by event order by score
        rows between unbounded preceding and unbounded following
      )
      )
      select prs.event, prs.class, prs.distance_inches, prs.clock_minutes, prs.weight,
             prs.score, games.name, game_instances.date
        from prs prs
        join game_instances on game_instances.id = prs.game_id
        join games on games.id = game_instances.game_id
      "
          member-id member-id]
         jdbc/snake-kebab-opts)
       (into {} (map (fn [pr] [(:event pr) pr])))))

(comment
  (member-pr-results 958)
  (-> (member-pr-results 6)
      (get "caber") :game-instances/date class)

  )

(defn maybe-colliding-members
  []
  (jdbc/execute!
    @datasource
    ["select a.id as a_id, a.first_name as a_first, a.last_name as a_last,
             b.id as b_id, b.first_name as b_first, b.last_name as b_last
         from members a, members b
      where a.id < b.id
       and (a.first_name = b.first_name or starts_with(a.first_name, b.first_name)
             or starts_with(b.first_name, a.first_name))
       and a.last_name = b.last_name
      order by a_last
"]))

(comment
  (clojure.pprint/pprint (maybe-colliding-members))
  (maybe-colliding-members)
  [#:members{:a_id 64, :a_first "Dale", :a_last "Andrew", :b_id 1442, :b_first "D", :b_last "Andrew"}
   #:members{:a_id 1127, :a_first "Benjamin", :a_last "Arthur", :b_id 1225, :b_first "Benjamin", :b_last "Arthur"}
   #:members{:a_id 80, :a_first "Lance", :a_last "Barusch", :b_id 612, :b_first "L", :b_last "Barusch"}
   #:members{:a_id 373, :a_first "Paul", :a_last "Boundy", :b_id 782, :b_first "P", :b_last "Boundy"}
   #:members{:a_id 372, :a_first "Heather", :a_last "Boundy", :b_id 820, :b_first "H", :b_last "Boundy"}
   #:members{:a_id 1099, :a_first "Russell", :a_last "Campbell", :b_id 1163, :b_first "Russ", :b_last "Campbell"}
   #:members{:a_id 213, :a_first "Gord", :a_last "Charters", :b_id 385, :b_first "Gordon", :b_last "Charters"}
   #:members{:a_id 58, :a_first "Steve", :a_last "Clark", :b_id 1266, :b_first "Steven", :b_last "Clark"}
   #:members{:a_id 1426, :a_first "K", :a_last "Clark", :b_id 1602, :b_first "Katilyn", :b_last "Clark"}
   #:members{:a_id 1266, :a_first "Steven", :a_last "Clark", :b_id 1278, :b_first "S", :b_last "Clark"}
   #:members{:a_id 58, :a_first "Steve", :a_last "Clark", :b_id 1278, :b_first "S", :b_last "Clark"}
   #:members{:a_id 1160, :a_first "Kaitlyn", :a_last "Clark", :b_id 1426, :b_first "K", :b_last "Clark"}
   #:members{:a_id 189, :a_first "Tiffany", :a_last "DiRico", :b_id 705, :b_first "T", :b_last "DiRico"}
   #:members{:a_id 52, :a_first "Adam", :a_last "Drummond", :b_id 920, :b_first "A", :b_last "Drummond"}
   #:members{:a_id 114, :a_first "Danny", :a_last "Frame", :b_id 1480, :b_first "D", :b_last "Frame"}
   #:members{:a_id 1561, :a_first "Alison", :a_last "Gaul", :b_id 1577, :b_first "Ali", :b_last "Gaul"}
   #:members{:a_id 433, :a_first "Joe", :a_last "Hall", :b_id 1045, :b_first "Joe", :b_last "Hall"}
   #:members{:a_id 1095, :a_first "Karl", :a_last "Hren", :b_id 1315, :b_first "K", :b_last "Hren"}
   #:members{:a_id 841, :a_first "Robert", :a_last "Kingswood", :b_id 1189, :b_first "Rob", :b_last "Kingswood"}
   #:members{:a_id 82, :a_first "Susie", :a_last "Lajoie", :b_id 1407, :b_first "S", :b_last "Lajoie"}
   #:members{:a_id 131, :a_first "Norm", :a_last "Little", :b_id 1344, :b_first "N", :b_last "Little"}
   #:members{:a_id 48, :a_first "Natasha", :a_last "Little", :b_id 1344, :b_first "N", :b_last "Little"}
   #:members{:a_id 1, :a_first "George", :a_last "Loney", :b_id 1044, :b_first "George", :b_last "Loney"}
   #:members{:a_id 1, :a_first "George", :a_last "Loney", :b_id 5, :b_first "G", :b_last "Loney"}
   #:members{:a_id 5, :a_first "G", :a_last "Loney", :b_id 1044, :b_first "George", :b_last "Loney"}
   #:members{:a_id 1379, :a_first "Erin", :a_last "Marte", :b_id 1528, :b_first "E", :b_last "Marte"}
   #:members{:a_id 469, :a_first "Andre", :a_last "Mazerolle", :b_id 819, :b_first "A", :b_last "Mazerolle"}
   #:members{:a_id 1586, :a_first "Alex ", :a_last "McCormick", :b_id 1587, :b_first "Alex ", :b_last "McCormick"}
   #:members{:a_id 1319, :a_first "Alex", :a_last "McCormick", :b_id 1587, :b_first "Alex ", :b_last "McCormick"}
   #:members{:a_id 1319, :a_first "Alex", :a_last "McCormick", :b_id 1586, :b_first "Alex ", :b_last "McCormick"}
   #:members{:a_id 994, :a_first "Jason", :a_last "McDonald", :b_id 1428, :b_first "J", :b_last "McDonald"}
   #:members{:a_id 1122, :a_first "Unknown", :a_last "Missing", :b_id 1217, :b_first "Unknown", :b_last "Missing"}
   #:members{:a_id 1050, :a_first "Rob", :a_last "Moody", :b_id 1105, :b_first "Robert", :b_last "Moody"}
   #:members{:a_id 1234, :a_first "Douglas", :a_last "Murphy", :b_id 1312, :b_first "Doug", :b_last "Murphy"}
   #:members{:a_id 918, :a_first "Erinn", :a_last "Quinn", :b_id 1441, :b_first "E", :b_last "Quinn"}
   #:members{:a_id 789, :a_first "Chris", :a_last "Racknor", :b_id 1254, :b_first "C", :b_last "Racknor"}
   #:members{:a_id 1019, :a_first "Matthew", :a_last "Reilly", :b_id 1288, :b_first "Matt", :b_last "Reilly"}
   #:members{:a_id 1092, :a_first "Matt", :a_last "Reling", :b_id 1110, :b_first "M", :b_last "Reling"}
   #:members{:a_id 1460, :a_first "Nicholas", :a_last "Renyard", :b_id 1557, :b_first "Nic", :b_last "Renyard"}
   #:members{:a_id 16, :a_first "Ray", :a_last "Siochowicz", :b_id 1177, :b_first "Ray", :b_last "Siochowicz"}
   #:members{:a_id 511, :a_first "Gord", :a_last "Stalker", :b_id 1093, :b_first "Gordon", :b_last "Stalker"}
   #:members{:a_id 1308, :a_first "Pamela", :a_last "Staples", :b_id 1380, :b_first "Pam", :b_last "Staples"}
   #:members{:a_id 13, :a_first "Joel", :a_last "Thiessen", :b_id 990, :b_first "J", :b_last "Thiessen"}
   #:members{:a_id 1250, :a_first "Alisha", :a_last "Thompson", :b_id 1473, :b_first "A", :b_last "Thompson"}
   #:members{:a_id 630, :a_first "Jesse", :a_last "Trask", :b_id 1279, :b_first "J", :b_last "Trask"}
   #:members{:a_id 629, :a_first "Jamie", :a_last "Trask", :b_id 1279, :b_first "J", :b_last "Trask"}
   #:members{:a_id 614, :a_first "Owen", :a_last "Willems", :b_id 1352, :b_first "O", :b_last "Willems"}
   #:members{:a_id 12, :a_first "Rob", :a_last "Young", :b_id 1600, :b_first "Robert", :b_last "Young"}
   #:members{:a_id 12, :a_first "Rob", :a_last "Young", :b_id 551, :b_first "R", :b_last "Young"}
   #:members{:a_id 551, :a_first "R", :a_last "Young", :b_id 1600, :b_first "Robert", :b_last "Young"}
   #:members{:a_id 551, :a_first "R", :a_last "Young", :b_id 1020, :b_first "Richard", :b_last "Young"}
   ]
  )

(defn- ->pg-ints
  [coll]
  (doto (PGobject.)
    (.setType "int4[]")
    (.setValue (str "{" (string/join "," coll) "}"))))

;; we have Rob Young, Rob Young, and Robert Young
(defn merge-members!
  [[primary-member-id & other-member-ids]]
  (jdbc/execute!
    @datasource
    ["begin;
      update game_member_results set member_id = ?
        where member_id = any(ARRAY[?]);
      update game_results_placing set member_id = ?
        where member_id = any(ARRAY[?]);
      update members set status = 'inactive' where id = any(ARRAY[?]);
      commit;"
     primary-member-id (->pg-ints other-member-ids)
     primary-member-id (->pg-ints other-member-ids)
     (->pg-ints other-member-ids)
     ]))

(comment
  (let [robs [12 #_551 1600]]
    (jdbc/execute!
      @datasource
      ["select id, member_id from game_member_results
        where member_id = any(ARRAY[?])"
       (doto (PGobject.)
         (.setType "int4[]")
         (.setValue (str "{" (string/join "," (rest robs)) "}")))]
      ))
  ;; "other rob" results
  [#:game_member_results{:id 143663, :member_id 1600} #:game_member_results{:id 143661, :member_id 1600} #:game_member_results{:id 143662, :member_id 1600} #:game_member_results{:id 143660, :member_id 1600} #:game_member_results{:id 143658, :member_id 1600} #:game_member_results{:id 143659, :member_id 1600} #:game_member_results{:id 143656, :member_id 1600} #:game_member_results{:id 143657, :member_id 1600}]
  (let [robs [12 551 1600]]
    (jdbc/execute!
      @datasource
      ["select game_instance_id, member_id from game_results_placing
        where member_id = any(ARRAY[?])"
       (doto (PGobject.)
         (.setType "int4[]")
         (.setValue (str "{" (string/join "," (rest robs)) "}")))]
      ))
  [#:game_results_placing{:game_instance_id 1653, :member_id 1600}]

  (merge-members! [12 #_551 1600])

  ;; Theresa Siochowicz/Soichowicwz
  (merge-members! [700 1562])
  (jdbc/execute! @datasource ["update members set status = 'inactive' where id = 1562"])

  ;; Ray Siochowicz
  (merge-members! [16 1177])
  (jdbc/execute! @datasource ["update members set status = 'inactive' where id = 1177"])

  ;; Kaitlyn Clark
  (merge-members! [1160 1602])

  ;; Ali Gaul
  (merge-members! [1577 1425 1561])

  ;; Amanda F. Walsh
  (merge-members! [1203 1567 ])

  ;; Nic Renyard
  (merge-members! [1557 1460])


  ;; Test users
  (jdbc/execute!
    @datasource
    ["update members set status = 'inactive' where id = any('{1531, 1532}')"])
  )

;;; Games queries

(defn available-years-for-records
  []
  (jdbc/execute!
    @datasource
    ["select distinct extract(year from \"date\") as year
      from game_instances order by year desc"]))

(declare event-records-for-year-by-class)
(declare event-top-weights-for-year-by-class)
(declare score-for-result)

(defn games-history
  [{:keys [year classes event]}]
  (let [#_#_#_#_bests (event-records-for-year-by-class year)
        weights (event-top-weights-for-year-by-class year)]
    (->>
      (jdbc/plan
        @datasource
        (cond->
            [(str "select games.id as games_id,
                   games.name,
                   to_char(game_instances.date, 'YYYY-MM-DD') as date,
                   game_results_placing.placing,
                   members.id, members.first_name, members.last_name,
                   game_member_results.event, game_member_results.distance_inches,
                      game_member_results.clock_minutes, game_member_results.weight,
                      game_member_results.class, game_member_results.score
                  from game_instances
                  join games on games.id = game_instances.game_id
                  join game_member_results on game_member_results.game_instance = game_instances.id
                  join game_results_placing on game_results_placing.game_instance_id = game_instances.id and game_results_placing.member_id = game_member_results.member_id
                  join members on game_member_results.member_id = members.id
                  where true"
               (when year
                 " and extract(year from \"date\") = ? ")
               (when (seq classes)
                 " and game_member_results.class = any(cast(? as membership_class_code[]))")
               (when event
                 " and game_member_results.event = cast(? as game_event_type)"))]
          (some? year) (conj year)
          (seq classes) (conj (into-array java.lang.String classes))
          (some? event) (conj event)))
      (reduce
        (fn [acc row]
          (cond-> acc
            (not (contains? (get acc (:date row)) (:games_id row)))
            (assoc-in [(:date row) (:games_id row)]
                      {:games/name (:name row) :results {}})

            (not (contains? (get-in acc [(:date row) (:games_id row) :results])
                            (:id row)))
            (assoc-in [(:date row) (:games_id row) :results (:id row)]
                      {:members/id (:id row)
                       :members/first-name (:first_name row)
                       :members/last-name (:last_name row)
                       :game-results-placing/placing (:placing row)
                       :game-member-results/class (:class row)
                       :events {}})

            true
            (assoc-in [(:date row) (:games_id row) :results (:id row) :events (:event row)]
                      {:game-member-results/event (:event row)
                       :game-member-results/class (:class row)
                       :game-member-results/score (:score row)
                       :game-member-results/clock-minutes (:clock_minutes row)
                       :game-member-results/distance-inches (:distance_inches row)
                       :game-member-results/weight (:weight row)})

            ;; debugging
            #_#_true (update-in [(:date row) (:games_id row) :results (:id row) :events (:event row)]
                            (fn [result]
                              (assoc result :calculated-score
                                     (score-for-result
                                       (:class row)
                                       year
                                       result
                                       bests
                                       weights))))))
        {}))))

(comment

  (require '[clj-async-profiler.core :as prof])
  (prof/profile (games-history {:year 2023}))
  (prof/serve-ui 8080)

  (time (count (games-history {:year 2023})))

  (jdbc/execute!
    @datasource
    ["create index if not exists game_results_placing_pkey2 on game_results_placing (game_instance_id, member_id)"])

  (jdbc/execute!
    @datasource
    ["create index if not exists game_instances_date_year_idx on game_instances (extract(\"year\" from \"date\"))"])

  (->> (jdbc/execute!
    @datasource
    [(str "explain analyze ("
          "select games.id as games_id,
                   games.name,
                   to_char(game_instances.date, 'YYYY-MM-DD') as date,
                   game_results_placing.placing,
                   members.id, members.first_name, members.last_name,
                   game_member_results.event, game_member_results.distance_inches,
                      game_member_results.clock_minutes, game_member_results.weight,
                      game_member_results.class, game_member_results.score
                  from game_instances
                  join games on games.id = game_instances.game_id
                  join game_member_results on game_member_results.game_instance = game_instances.id
                  join game_results_placing on game_results_placing.game_instance_id = game_instances.id and game_results_placing.member_id = game_member_results.member_id
                  join members on game_member_results.member_id = members.id
                  where true and extract(\"year\" from date) = 2023"
          ")")])
       (mapcat vals)
       (string/join "\n")
       println)

  )

(defn add-new-game!
  [game-name]
  (jdbc/execute-one!
    @datasource
    ["insert into games (name) values (?) returning *" game-name]
    jdbc/snake-kebab-opts))

;;; Score sheets

(defn all-games-names
  []
  (jdbc/execute!
    @datasource
    ["select id, name from games order by name"]
    jdbc/snake-kebab-opts))

(defn member-score-sheets
  [member-id]
  (jdbc/execute!
    @datasource
    ["select * from score_sheets where submitted_by = ?"
     member-id]
    jdbc/snake-kebab-opts))

(defn add-new-score-sheet!
  [member-id]
  (jdbc/execute-one!
    @datasource
    ["insert into score_sheets (submitted_by) values (?)
      returning *"
     member-id]
    jdbc/snake-kebab-opts))

(defn delete-sheet!
  [sheet-id]
  (jdbc/execute!
    @datasource
    ["delete from score_sheets where id = ?" sheet-id]))

(defn update-sheet-for-user
  [{:keys [user-id sheet-id sheet]}]
  (let [res (jdbc/execute-one!
              @datasource
              ["update score_sheets set games_id = ?, games_date = ?, data = ?
                where id = ? and submitted_by = ?"
               (:score-sheets/games-id sheet)
               (some-> (:score-sheets/games-date sheet)
                       (.getTime) (java.sql.Date.))
               (:score-sheets/data sheet)
               sheet-id user-id])]
    (if (and (zero? (::jdbc/update-count res))
             ((member-roles user-id) :admin))
      (jdbc/execute-one!
        @datasource
        ["update score_sheets set games_id = ?, games_date = ?, data = ?
          where id = ?"
         (:score-sheets/games-id sheet)
         (some-> (:score-sheets/games-date sheet)
                 (.getTime) (java.sql.Date.))
         (:score-sheets/data sheet)
         sheet-id])
      res)))

(comment
  (java.sql.Date. (.getTime (java.util.Date.)))
  (update-sheet-for-user {:user-id 1 :sheet-id 123344 :sheet []})
  )

(defn submit-sheet-for-approval
  [{:keys [sheet-id user-id]}]
  (jdbc/execute!
    @datasource
    ["update score_sheets
      set status = 'complete'
      where id = ? and submitted_by = ? and status = 'pending'"
     sheet-id user-id]))

(defn cancel-sheet-approval
  [{:keys [sheet-id user-id]}]
  (jdbc/execute!
    @datasource
    ["update score_sheets
      set status = 'pending'
      where id = ? and submitted_by = ? and status = 'complete'"
     sheet-id user-id]))

(defn submitted-score-sheets
  []
  (jdbc/execute!
    @datasource
    ["select members.first_name, members.last_name, score_sheets.*
      from score_sheets
      join members on members.id = score_sheets.\"submitted_by\"
      where score_sheets.status = any('{complete, approved}')"]
    jdbc/snake-kebab-opts))

(defn create-games-instance!
  [{:keys [game-id date]}]
  (jdbc/execute-one!
    @datasource
    ["insert into game_instances (game_id, date) values (?, ?) returning *"
     game-id date]
    jdbc/snake-kebab-opts))

(comment
  (let [sheet (jdbc/execute-one!
                @datasource
                ["select * from score_sheets where id = ? and status = 'complete'"
                 7]
                jdbc/snake-kebab-opts)
       [headers & content] (:score-sheets/data sheet)
          ;; TODO validate all results are reasonable
        results (map (fn [row] (results/result-row->game-results headers row)) content) ]
    headers
    #_results)
  )

(defn approve-sheet!
  [{:keys [sheet-id]}]
  (let [sheet (jdbc/execute-one!
                @datasource
                ["select * from score_sheets where id = ? and status = 'complete'"
                 sheet-id]
                jdbc/snake-kebab-opts)]
    (when-not sheet
      (throw (ex-info "Nonexistant or incorrect state for sheet"
                      {:sheet-id sheet-id})))

    (let [[headers & content] (:score-sheets/data sheet)
          ;; TODO validate all results are reasonable
          results (map (fn [row] (results/result-row->game-results headers row)) content)
          member-names (map :name results)
          member-names->id (->>
                             (jdbc/execute!
                               @datasource
                               ["with names as (select jsonb_array_elements_text(?) as name)
                               select id, names.name
                               from members
                               join names on names.name = last_name || ', ' || first_name"
                                (vec member-names)])
                             (into {} (map (fn [r] [(:name r) (:members/id r)]))))
          member-names->id
          (reduce
            (fn [member-names result]
              (if (contains? member-names (:name result))
                member-names
                (let [[lname fname] (string/split (:name result) #",\s*" 2)
                      {:members/keys [id]}
                      (jdbc/execute-one!
                        @datasource
                        ;; todo what else gets inserted?
                        ["insert into members (first_name, last_name, login, password_hash, country,
                          class, status)
                          values (?, ?, ?, ?, ?, 'unknown', 'active') returning *"
                         fname lname (str lname "." fname)
                         (bcrypt/encrypt (generate-password))
                         (get result :country "Canada")])]
                  (assoc member-names (:name result) id))))
            member-names->id
            results)
          results (x/transform
                    [x/ALL (x/collect-one :name) :members/id]
                    (fn [member-name _] (member-names->id member-name))
                    results)
          year (+ 1900 (.getYear (:score-sheets/games-date sheet)))
          year-bests (event-records-for-year-by-class year)
          year-weights (event-top-weights-for-year-by-class year)]
      (let [result (jdbc/execute!
                     @datasource
                     ["with new_game_instance as (insert into game_instances (game_id, date, source_sheet_id)
                                     values (?, ?, ?) returning *),

          new_results as (
           insert into game_member_results
           (game_instance, member_id, event, distance_inches, clock_minutes, weight, score, class)
           select new_game_instance.id, x.*
              from jsonb_to_recordset(?) as x(
                 member_id integer,
                 event game_event_type, distance_inches numeric(8, 1),
                 clock_minutes integer, weight numeric(8, 2), score numeric(11, 4),
                 class membership_class_code)
               join new_game_instance on true
             ),

           new_placings as (
            insert into game_results_placing (game_instance_id, member_id, \"placing\", class)
            select new_game_instance.id, x.*
              from jsonb_to_recordset(?) as x(member_id integer,
               \"placing\" integer, class membership_class_code)
              join new_game_instance on true)

            update score_sheets set status = 'approved' where id = ?"
                      (:score-sheets/games-id sheet)
                      (:score-sheets/games-date sheet)
                      (:score-sheets/id sheet)

                      (vec
                        (for [result results
                              [event event-result] (:events result)
                              :when (and (some? (:distance-inches event-result))
                                         (some? (:weight event-result)))]
                          {:member_id (:members/id result)
                           :event event
                           :distance_inches (:distance-inches event-result)
                           :clock_minutes (:clock-minutes event-result)
                           :weight (:weight event-result)
                           :score (score-for-result
                                    (:class result)
                                    year
                                    #:game-member-results{:event event
                                                          :weight (:weight event-result)
                                                          :distance-inches (:distance-inches event-result)
                                                          :clock-minutes (:clock-minutes event-result)}
                                    year-bests
                                    year-weights)
                           :class (:class result)}))

                      (vec (for [result results]
                             {:member_id (:members/id result)
                              :placing (:placing result)
                              :class (:class result)}))

                      sheet-id])]
        (update-should-be-masters)
        result))))

(comment

  (jdbc/execute!
    @datasource
    ["select game_instances.id, score_sheets.id, games.name from game_instances join games on game_id = games.id join score_sheets on score_sheets.games_id = game_instances.game_id where extract(\"year\" from game_instances.date) = 2024 and game_instances.source_sheet_id is null and score_sheets.status = 'approved'"])


  (doseq [r [{:game_instances/id 1660, :score_sheets/id 12, :games/name "Kinmount Highland Games"}
             {:game_instances/id 1661, :score_sheets/id 11, :games/name "IHGF Canadian Amateur Championship - Roseneath "}
             {:game_instances/id 1662, :score_sheets/id 7, :games/name "Pugwash Highland Games"}
             {:game_instances/id 1663, :score_sheets/id 16, :games/name "Bressuire Highland Games"}
             {:game_instances/id 1665, :score_sheets/id 20, :games/name "Canada Day at Craigflower"}]]
    (jdbc/execute!
      @datasource
      ["update game_instances set source_sheet_id = ? where id = ?"
       (:score_sheets/id r) (:game_instances/id r)]))


  (jdbc/execute!
    @datasource
    ["select score_sheets.id, games.name from score_sheets join games on games_id = games.id where extract(\"year\" from score_sheets.games_date) = 2024"])

  (approve-sheet! {:sheet-id 1})
  (jdbc/execute!
    @datasource
    ["select * from jsonb_to_recordset(?) as x(a text, b text, c integer)"
     [{:a "foo" :b "bar" :c 2}
      {:a "baz" :b "quux" :c 3}]])

  (jdbc/execute!
    @datasource
    ["begin;
      delete from game_results_placing where game_instance_id in (select id from game_instances where extract(\"year\" from date) = 2024);
      delete from game_member_results where game_instance in (select id from game_instances where extract(\"year\" from date) = 2024);
      delete from game_instances where extract(\"year\" from date) = 2024;
      update score_sheets set status = 'complete' where status = 'approved' and extract(\"year\" from games_date) = 2024;
      commit;"])

  ;; two users with the same login
  (jdbc/execute!
    @datasource
    ["update members set login = 'Wilson.Adriane' where id = 856"])
  (jdbc/execute!
    @datasource
    ["update members set login = 'Test.' || random() where login = 'Test.Test'"])

  ;; do the multiple inserts as CTEs work transactionally?
  (jdbc/execute!
    @datasource
    ["with a as (insert into games (name) values ('foobar'))
      insert into members_roles (member_id, role) values (?, 'admin')"
     9999])
  ;; looks like they do

  )


(defn retract-sheet!
  [sheet-id]
  (let [sheet (jdbc/execute-one!
                @datasource
                ["select status from score_sheets where id = ?" sheet-id]
                jdbc/snake-kebab-opts)]
    (when (not= "approved" (:score-sheets/status sheet))
      (throw (ex-info "Sheet not approved, can't retract" {:sheet-id sheet-id
                                                           :sheet-status sheet})))

    (jdbc/execute!
      @datasource
      [" delete from game_member_results
         where game_instance in (select id from game_instances where source_sheet_id = ?);

        delete from game_results_placing
         where game_instance_id in (select id from game_instances where source_sheet_id = ?);

        update score_sheets set status = 'complete' where id = ?;"
       sheet-id
       sheet-id
       sheet-id])))

;; Records

(defn current-records
  []
  (->> (jdbc/plan
         @datasource
         ["select distinct last_value(id) over wnd as id,
             last_value(canadian) over wnd as canadian,
             last_value(class) over wnd as class,
             last_value(event) over wnd as event,
             last_value(athlete_name) over wnd as athlete_name,
             last_value(distance_inches) over wnd as distance_inches,
             last_value(weight) over wnd as weight,
             last_value(year) over wnd as year,
             last_value(comment) over wnd as comment,
             last_value(status) over wnd as status
           from event_records where should_display
           window wnd as (partition by class, event, weight order by year
            rows between unbounded preceding and unbounded following)"])
       (reduce
         (fn [acc row]
           (->> #:event-record{:id (:id row)
                               :canadian? (:canadian row)
                               :class (:class row)
                               :event (:event row)
                               :athlete-name (:athlete_name row)
                               :distance-inches (:distance_inches row)
                               :weight (:weight row)
                               :year (:year row)
                               :comment (:comment row)
                               :status (:status row)}
                (update-in acc [(:class row) (:event row)]
                           (fnil conj []))))
         {})))

(defn current-best-throws
  []
  (let [top-results (jdbc/execute!
                      @datasource
                      ["select distinct first_value(id) over wnd as id,
                        first_value(class) over wnd as class,
                        first_value(event) over wnd as event,
                        first_value(score) over wnd as score
                       from game_member_results
                         where event <> 'caber'
                         window wnd as (partition by class, event order by score desc
                           rows between unbounded preceding and unbounded following)"])]
    (->> (jdbc/plan
           @datasource
           ["select members.id, members.first_name, members.last_name,
            game_member_results.class, game_member_results.event,
            game_member_results.score, game_member_results.distance_inches,
            game_member_results.weight,
            games.name, game_instances.date
        from game_member_results
         join members on members.id = game_member_results.member_id
         join game_instances on game_instances.id = game_member_results.game_instance
         join games on games.id = game_instances.game_id
        where game_member_results.id = any(ARRAY[?])"
            (->pg-ints (map :id top-results))])
         (reduce
           (fn [acc row]
             (->> {:members/id (:id row)
                   :members/first-name (:first_name row)
                   :members/last-name (:last_name row)
                   :game-member-results/class (:class row)
                   :game-member-results/event (:event row)
                   :game-member-results/score (:score row)
                   :game-member-results/distance-inches (:distance_inches row)
                   :game-member-results/weight (:weight row)
                   :games/name (:name row)
                   :game-instances/date (:date row)}
                  (update-in acc [(:class row) (:event row)] (fnil conj []))))
           {}))))

(defn submit-new-record-for-approval
  [{:keys [class event athlete-name distance-inches weight year comment]}]
  (jdbc/execute!
    @datasource
    ["insert into event_record_submissions
      (\"class\", event, athlete_name, distance_inches, weight, year, comment)
      values (
          ?::membership_class_code, ?::game_event_type, ?, ?, ?, ?, ?)"
     class event athlete-name distance-inches weight year comment]))

(defn record-submissions
  []
  (jdbc/execute!
    @datasource
    ["select * from event_record_submissions where record_approved is null"]
    jdbc/snake-kebab-opts))

(defn record-submission
  [id]
  (jdbc/execute-one!
    @datasource
    ["select * from event_record_submissions where id = ?" id]
    jdbc/snake-kebab-opts))

(defn approve-submission!
  [id]
  (jdbc/execute!
    @datasource
    ["update event_record_submissions set record_approved = true where id = ?"
     id]))

(defn submit-new-record!
  [{:keys [class event athlete-name distance-inches weight year comment]}]
  (jdbc/execute!
    @datasource
    ["insert into event_records
      (\"canadian\", \"status\", \"should_display\",
       \"class\", event, athlete_name, distance_inches, weight, year, comment)
      values (true, 'verified', true,
          ?::membership_class_code, ?::game_event_type, ?, ?, ?, ?, ?)"
     class event athlete-name distance-inches weight year comment]))

(comment
  (jdbc/execute!
    @datasource
    ["select distinct status from event_records"])

  (jdbc/execute!
    @datasource
    ["select * from event_records where status = 'unverified'"])
  )


(defn- event-records-for-year-by-class
  [year]
  (jdbc/execute!
    @datasource
    ["(select distinct first_value(class) over wnd as class,
        first_value(event) over wnd as event,
        first_value(distance_inches) over wnd as distance_inches,
        first_value(weight) over wnd as weight,
        first_value(year) over wnd as year
      from event_records
      where status = 'verified' and event <> 'open'
      window wnd as (partition by class, event order by
         (case when year < ? then year else -year end) desc, distance_inches desc
        rows between unbounded preceding and unbounded following))
      union all
      (
      -- open needs multiple weights to score, annoying
      select distinct first_value(class) over wnd as class,
        first_value(event) over wnd as event,
        first_value(distance_inches) over wnd as distance_inches,
        first_value(weight) over wnd as weight,
        first_value(year) over wnd as year
      from event_records
      where status = 'verified' and event = 'open'
      window wnd as (partition by class, event, weight order by
         (case when year < ? then year else -year end) desc, distance_inches desc
        rows between unbounded preceding and unbounded following)
      order by distance_inches desc)"
     year year]
    jdbc/snake-kebab-opts))

(defn- event-top-weights-for-year-by-class
  [year]
  (jdbc/execute!
    @datasource
    ["select distinct first_value(class) over wnd as class,
        first_value(event) over wnd as event,
        first_value(weight) over wnd as weight,
        first_value(year) over wnd as year
      from event_records
      where status = 'verified'
      window wnd as (partition by class, event order by
         (case when year < ? then year else -year end) desc, weight desc
        rows between unbounded preceding and unbounded following) "
     year]
    jdbc/snake-kebab-opts))

(comment
  (->> (event-records-for-year-by-class 2010)
       (x/select
         [x/ALL
          (x/if-path [:event (x/pred= "braemar")] x/STAY)
          (x/if-path [:class (x/pred= "open")] x/STAY)
          ])
       prn)
  (event-records-for-year-by-class 1960)
  )

(def event-weight-limits
  "Minimum weights for events by class"
  {"braemar" { "open" 22 "masters" 22 "amateurs" 22 "juniors" 22 "womens" 12 "womensmaster" 12 "lightweight" 22 "womensyouth" 6 "womensjunior" 8 "youth" 11 "masters50+" 18 "masters60+" 14 "womensmaster50+" 12 "womensmaster60+" 12}
   ;; there's a bug in the old database, where juinor's weight limit wasn't being read properly.
   ;; it's apparently supposed to be 12lb, but setting that breaks all prior scores...
   "open"    { "open" 16 "masters" 16 "amateurs" 16 "juniors" 12 "womens" 8 "womensmaster" 8 "lightweight" 16 "womensyouth" 6 "womensjunior" 8 "youth" 11 "masters50+" 14 "masters60+" 12 "womensmaster50+" 6 "womensmaster60+" 6}
   "wob"     { "open" 56 "masters" 42 "amateurs" 56 "juniors" 42 "womens" 28 "womensmaster" 21 "lightweight" 42 "womensyouth" 14 "womensjunior" 21 "youth" 28 "masters50+" 42 "masters60+" 35 "womensmaster50+" 21 "womensmaster60+" 14}
   "hwfd"    { "open" 56 "masters" 42 "amateurs" 56 "juniors" 42 "womens" 28 "womensmaster" 21 "lightweight" 42 "womensyouth" 14 "womensjunior" 21 "youth" 28 "masters50+" 42 "masters60+" 35 "womensmaster50+" 21 "womensmaster60+" 14}
   "lwfd"    { "open" 28 "masters" 28 "amateurs" 28 "juniors" 28 "womens" 14 "womensmaster" 14 "lightweight" 28 "womensyouth" 9 "womensjunior" 14 "youth" 21 "masters50+" 28 "masters60+" 21 "womensmaster50+" 14 "womensmaster60+" 9}
   "lhmr"    { "open" 16 "masters" 16 "amateurs" 16 "juniors" 16 "womens" 12 "womensmaster" 12 "lightweight" 16 "womensyouth" 8 "womensjunior" 12 "youth" 12 "masters50+" 16 "masters60+" 12 "womensmaster50+" 12 "womensmaster60+" 8}
   "hhmr"    { "open" 22 "masters" 22 "amateurs" 22 "womens" 16 "womensmaster" 16 "lightweight" 22 "womensyouth" 12 "womensjunior" 16 "youth" 16 "masters50+" 16 "masters60+" 12 "womensmaster50+" 16 "womensmaster60+" 12}
   "sheaf"   { "open" 16 "masters" 16 "amateurs" 16 "womens" 10 "womensmaster" 10 "lightweight" 16 "womensyouth" 8 "womensjunior" 10 "youth" 10 "masters50+" 16 "masters60+" 14 "womensmaster50+" 8 "womensmaster60+" 8}})

(def event-class-standards
  "Womens master (and the other master's classes other than \"base\"
  masters) have a set standard, rather than being based on a record."
  {"braemar" {"womensmaster" {:distance (+ (* 36 12) 9) :weight 12}
              "womensyouth" {:distance (+ (* 36 12) 9) :weight 6}
              "womensmaster50+" {:distance (+ (* 26 12) 1.5) :weight 12}
              "womensmaster60+" {:distance (+ (* 23 12) 1) :weight 12}
              "masters50+" {:distance (+ (* 43 12) 6.5) :weight 22}
              "masters60+" {:distance (+ (* 40 12) 0.5) :weight 16}
              }
   "open" {"womensmaster" {:distance (+ (* 47 12) 7) :weight 8}
           "womensyouth" {:distance (+ (* 47 12) 7) :weight 8}
           "womensmaster50+" {:distance (+ (* 33 12) 5.5) :weight 8}
           "womensmaster60+" {:distance (+ (* 30 12) 3) :weight 8}
           "masters50+" {:distance (+ (* 49 12) 7) :weight 16}
           "masters60+" {:distance (+ (* 40 12) 0.5) :weight 16}
           }
   "wob" {"womensmaster" {:distance (+ (* 17 12) 6) :weight 28}
          "womensyouth" {:distance (+ (* 17 12) 6) :weight 14}
          "womensmaster50+" {:distance (+ (* 18 12) 3) :weight 21}
          "womensmaster60+" {:distance (+ (* 18 12) 6) :weight 14}
          "masters50+" {:distance (+ (* 20 12) 1) :weight 42}
          "masters60+" {:distance (+ (* 17 12) 3) :weight 35}
          }
   "hwfd" {"womensmaster" {:distance (+ (* 42 12) 7) :weight 28}
           "womensyouth" {:distance (+ (* 42 12) 7) :weight 14}
           "womensmaster50+" {:distance (+ (* 51 12) 5) :weight 21}
           "womensmaster60+" {:distance (+ (* 51 12) 11.5) :weight 14}
           "masters50+" {:distance (+ (* 56 12) 10) :weight 42}
           "masters60+" {:distance (+ (* 42 12) 3) :weight 35}
           }
   "lwfd" {"womensmaster" {:distance (+ (* 85 12) 2.5) :weight 14}
           "womensyouth" {:distance (+ (* 85 12) 2.5) :weight 9}
           "womensmaster50+" {:distance (+ (* 66 12) 3) :weight 14}
           "womensmaster60+" {:distance (+ (* 68 12) 0) :weight 9}
           "masters50+" {:distance (+ (* 79 12) 11) :weight 28}
           "masters60+" {:distance (+ (* 63 12) 6) :weight 21}
           }
   "lhmr" {"womensmaster" {:distance (+ (* 100 12) 5) :weight 12}
           "womensyouth" {:distance (+ (* 100 12) 5) :weight 8}
           "womensmaster50+" {:distance (+ (* 85 12) 11) :weight 12}
           "womensmaster60+" {:distance (+ (* 83 12) 2) :weight 8}
           "masters50+" {:distance (+ (* 132 12) 10) :weight 16}
           "masters60+" {:distance (+ (* 105 12) 4.5) :weight 12}
           }
   "hhmr" {"womensmaster" {:distance (+ (* 85 12) 7.5) :weight 16}
           "womensyouth" {:distance (+ (* 85 12) 7.5) :weight 12}
           "womensmaster50+" {:distance (+ (* 69 12) 7.5) :weight 16}
           "womensmaster60+" {:distance (+ (* 63 12) 8.5) :weight 12}
           "masters50+" {:distance (+ (* 99 12) 8.5) :weight 16}
           "masters60+" {:distance (+ (* 105 12) 4.5) :weight 12}
           }
   "sheaf" {"womensmaster" {:distance (+ (* 34 12) 2) :weight 10}
            "womensyouth" {:distance (+ (* 34 12) 2) :weight 8}
            "womensmaster50+" {:distance (+ (* 29 12) 0) :weight 10}
            "womensmaster60+" {:distance (+ (* 22 12) 0) :weight 10}
            "masters50+" {:distance (+ (* 37 12) 6) :weight 16}
            "masters60+" {:distance (+ (* 31 12) 0) :weight 12}
            }})

(def event-class-fallback
  {"braemar" {"womensjunior" {:distance (+ (* 12 23) 1) :weight 8}
              "youth" {:distance (+ (* 12 40) 9) :weight 11}}
   "open" {"womensjunior" {:distance (+ (* 12 30) 3) :weight 8}
           "youth" {:distance (+ (* 12 40) 0.5) :weight 11}}
   "wob" {"womensjunior" {:distance (+ (* 12 18) 6) :weight 21}
          "youth" {:distance (+ (* 12 17) 3) :weight 28}}
   "hwfd" {"womensjunior" {:distance (+ (* 12 51) 11.5) :weight 21}
           "youth" {:distance (+ (* 12 42) 3) :weight 28}}
   "lwfd" {"womensjunior" {:distance (+ (* 12 68) 0) :weight 14}
           "youth" {:distance (+ (* 12 63) 6) :weight 21}}
   "lhmr" {"womensjunior" {:distance (+ (* 12 83) 2) :weight 12}
           "youth" {:distance (+ (* 12 105) 4.5) :weight 12}}
   "hhmr" {"womensjunior" {:distance (+ (* 12 63) 8.5) :weight 16}
           "youth" {:distance (+ (* 12 105) 4.5) :weight 16}}
   "sheaf" {"womensjunior" {:distance (+ (* 12 22) 0) :weight 10}
            "youth" {:distance (+ (* 12 31) 0) :weight 10}}})

(defn score-for-result
  ([class {:keys [year result]}]
   (score-for-result
     class year
     result
     (event-records-for-year-by-class year)
     (event-top-weights-for-year-by-class year)))
  ([class year {:game-member-results/keys [event weight distance-inches clock-minutes]}
    event-records event-top-weights]
   (let [class (case class
                 "amateurs" "open"
                 class)
         class-weight-limit (if (and (= event "open") (= class "juniors")
                                     (<= 2022 year 2023))
                              0
                              (get-in event-weight-limits [event class]))]
     (cond
       (or (nil? distance-inches) (nil? weight)
           (and (= "caber" event) (nil? clock-minutes))
           (zero? distance-inches) (zero? weight))
       0

       (= "caber" event)
       (max
         0
         (+ (* 500 (/ (float weight) 160))
            (* 500 (/ (float distance-inches) 258))
            (* -1/2
               ;; to make this loop around, so 11:30 = 1:30
               ;; surely a better way of expressing this...
               (let [mins (mod (float clock-minutes) (* 12 60))]
                 (if (< mins (* 6 60))
                   mins
                   (- (* 12 60) mins))))))

       (and weight (< (float weight) (or class-weight-limit ##Inf)))
       0

       (and (= "open" class) (= "open" event))
       (let [light-best (x/select-first
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           (x/if-path [:weight (x/view float) (x/pred= 17.0)] x/STAY)]
                          event-records)
             heavy-best (x/select-first
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           (x/if-path [:weight (x/view float) (x/pred= 20.0)] x/STAY)]
                          event-records)]
         (if (and light-best heavy-best
                  weight (<= class-weight-limit (float weight)))
           (let [factor (/ (- (float (:distance-inches light-best))
                              (float (:distance-inches heavy-best)))
                           (- 20 17))
                 weight (min weight 21)]
             (-> (/ (+ (float distance-inches) (* (- factor 1) (- weight 17)))
                    (float (:distance-inches light-best)))
                 (* 1000)))
           0))

       (and (not= "masters" class) (= "open" event))
       (let [best-dist (or (get-in event-class-standards [event class :distance])
                           (x/select-first
                             [x/ALL
                              (x/if-path [:class (x/pred= class)] x/STAY)
                              (x/if-path [:event (x/pred= event)] x/STAY)
                              :distance-inches]
                             event-records))
             top-weight (or (get-in event-class-standards [event class :weight])
                            (x/select-one
                              [x/ALL
                               (x/if-path [:class (x/pred= class)] x/STAY)
                               (x/if-path [:event (x/pred= event)] x/STAY)
                               :weight]
                              event-top-weights))
             [best-dist top-weight] (if (and best-dist top-weight)
                                      [best-dist top-weight]
                                      ((juxt :distance :weight)
                                       (get-in event-class-fallback [event class])))]
         (if (and best-dist top-weight class-weight-limit)
           (* 1000 (/ (+ (* 12 (- (float weight) class-weight-limit))
                         (float distance-inches))
                      (+ (* 12 (- (float top-weight) class-weight-limit))
                         (float best-dist))))
           0))

       ;; the old code has conditions for if the scoring method is
       ;; "stone" and the event is braemar, but in the database,
       ;; braemar is set to scoring method "distance", so it just uses
       ;; the basic. Confusing.
       #_#_(= "braemar" event)
       (let [best (x/select-first
                    [x/ALL
                     (x/if-path [:class (x/pred= class)] x/STAY)
                     (x/if-path [:event (x/pred= event)] x/STAY)
                     :distance-inches]
                    event-records)
             top-weight (x/select-one
                          [x/ALL
                           (x/if-path [:class (x/pred= class)] x/STAY)
                           (x/if-path [:event (x/pred= event)] x/STAY)
                           :weight]
                          event-top-weights)
             event-weight-limit (case class ("womens" "womensmaster") 18 22)]
         (if (and best top-weight weight distance-inches
                  #_(<= event-weight-limit weight))
           (* 1000 (/ (+ (* 12 (- weight event-weight-limit))
                         (float distance-inches))
                      (+ (* 12 (- top-weight event-weight-limit))
                         (float best))))
           0))

       (and (= class "womensmaster")
            (#{"lwfd" "hwfd" "lhmr" "hhmr" "wob"} event)
            (> weight (get-in event-weight-limits [event class])))
       0

       :else
       (-> (/ (float distance-inches)
              (or (get-in event-class-standards [event class :distance])
                  (some-> (x/select-first
                            [x/ALL
                             (x/if-path [:class (x/pred= class)] x/STAY)
                             (x/if-path [:event (x/pred= event)]
                                        x/STAY)
                             :distance-inches]
                            event-records)
                          float)
                  (get-in event-class-fallback [event class :distance])
                  ##Inf))
           (* 1000)
           (max 0))))))

(comment
  (let [clock-minutes (+ (* 60 3) 12)]
    (+ (* 16 (math/floor (/ (float clock-minutes) 60)))
       (mod (float clock-minutes) 60)))

  (jdbc/execute!
    @datasource
    ["select * from game_member_results where event = 'caber' and score <> 0 limit 1"]
    jdbc/snake-kebab-opts)


  (jdbc/execute! @datasource ["select * from game_instances where id = 639"])

  (score-for-result
    "womens"
    {:result #:game-member-results{:member-id 175, :distance-inches 226.0M, :weight 18.00M, :class "womens", :score 812.9500M, :game-instance 831, :clock-minutes nil, :event "braemar", :id 33}
     :year 2005})

  (score-for-result
    "open"
    {:result #:game-member-results{:distance-inches 200M :weight 18M :class "open"
                                   :event "open"}})

  (score-for-result
    "womens"
    {:result #:game-member-results{:member-id 410, :distance-inches 192.0M, :weight 50.00M, :class "womens", :score 528.3430M, :game-instance 639, :clock-minutes 0, :event "caber", :id 20899}
     :year 2009})

(jdbc/execute!
  @datasource
  ["select * from game_member_results where event = 'caber' and score <> 0 limit 1"]
  jdbc/snake-kebab-opts)
  )

;; Rankings

(defn supplemental-results-for-masters
  [member-id]
  (->>
    (jdbc/execute!
      @datasource
      ["select game_member_results.class, score, event, member_id,
               distance_inches, clock_minutes, game_member_results.weight
        from game_member_results
        join game_instances on game_instance = game_instances.id
        join members on game_member_results.member_id = members.id
        where member_id = ?
          and game_instances.date >=
             coalesce(members.master_first_date, members.birth_date + '40 years'::interval)
          and members.country = any(ARRAY['Canada', ''])
          and members.status = 'active'
          and game_member_results.event <> 'sheaf'
          and game_member_results.event <> 'braemar'
          and game_member_results.score > 0
          and game_member_results.class = any('{juniors, amateurs, womens, masters, open, lightweight}')"
        member-id])))

(defn rankings-for-year
  [year]
  (let [bests (event-records-for-year-by-class year)
        weights (event-top-weights-for-year-by-class year)
        process-row
        (fn [acc cls row]
          (-> acc
              (assoc-in [cls (:member_id row) :members/id] (:member_id row))
              (assoc-in [cls (:member_id row) :members/first-name] (:first_name row))
              (assoc-in [cls (:member_id row) :members/last-name] (:last_name row))
              (cond->
                  (< (get-in acc [cls (:member_id row) :events (:event row) :score] 0)
                     (:score row))
                (->
                  (assoc-in [cls (:member_id row) :events (:event row)]
                            {:distance-inches (:distance_inches row)
                             :weight (:weight row)
                             :clock-minutes (:clock_minutes row)
                             :score (:score row)})))))]
    (->>
      (jdbc/plan
        @datasource
        ["(select
        game_member_results.class as class,
        game_member_results.score as score,
        game_member_results.event as event,
        game_member_results.member_id as member_id,
        game_member_results.distance_inches as distance_inches,
        game_member_results.clock_minutes as clock_minutes,
        game_member_results.weight as weight,
        members.first_name as first_name,
        members.last_name as last_name,
        (members.master_age
          or (extract(\"year\" from members.birth_date + '40 years'::interval) <= ? or
               extract(\"year\" from members.master_first_date) <= ?)) as masters_age,
        extract(\"year\" from members.birth_date + '50 years'::interval) <= ? as masters50_age,
        extract(\"year\" from members.birth_date + '60 years'::interval) <= ? as masters60_age
     from game_member_results
     join members on game_member_results.member_id = members.id
     join game_instances on game_member_results.game_instance = game_instances.id
     where extract(\"year\" from game_instances.\"date\") = ?
       and members.country = any(ARRAY['Canada', ''])
       and members.status = 'active'
       and game_member_results.event <> all('{sheaf,braemar}')
       and game_member_results.score > 0
       and game_member_results.class <> all('{N/A, unknown}')) union all (
       -- get open results for masters that have just reached 40 this year
       select
        game_member_results.class as class,
        game_member_results.score as score,
        game_member_results.event as event,
        game_member_results.member_id as member_id,
        game_member_results.distance_inches as distance_inches,
        game_member_results.clock_minutes as clock_minutes,
        game_member_results.weight as weight,
        members.first_name as first_name,
        members.last_name as last_name,
        true as masters_age,
        extract(\"year\" from members.birth_date + '50 years'::interval) <= ? as masters50_age,
        extract(\"year\" from members.birth_date + '60 years'::interval) <= ? as masters60_age
     from game_member_results
     join members on game_member_results.member_id = members.id
     join game_instances on game_member_results.game_instance = game_instances.id
     where (extract(\"year\" from members.birth_date + '40 years'::interval) = ? or
            extract(\"year\" from members.master_first_date) = ?)
       and extract(\"year\" from game_instances.date) = ?
       and members.country = any(ARRAY['Canada', ''])
       and members.status = 'active'
       and game_member_results.event <> all('{sheaf,braemar}')
       and game_member_results.score > 0
       and game_member_results.class = any('{open, womens}'))"
         year year year year year year year year year year])
      (reduce
        (fn [acc row]
          (let [cls (:class row)
                cls (if (= cls "amateurs") "open" cls)]
            (cond-> (process-row acc cls row)

              ;; for the masters class, if the athlete is masters
              ;; age, get best open result, calculate as masters,
              ;; use if better
              (or (and (= cls "open") (:masters_age row))
                  (= cls "masters50+"))
              (process-row
                "masters"
                (assoc
                  row :score
                  (score-for-result
                    "masters" year
                    #:game-member-results{:event (:event row)
                                          :weight (:weight row)
                                          :distance-inches (:distance_inches row)
                                          :clock-minutes (:clock_minutes row)}
                    bests weights)))

              ;; for the open class, if athlete is also
              ;; masters-age, get the best masters result
              ;; & re-score it as open, use if better
              (or (= cls "masters") (= cls "masters50+") (= cls "masters60+"))
              (process-row
                "open"
                (assoc
                  row :score
                  (score-for-result
                    "open" year
                    #:game-member-results{:event (:event row)
                                          :weight (:weight row)
                                          :distance-inches (:distance_inches row)
                                          :clock-minutes (:clock_minutes row)}
                    bests weights)))

              (and (or (= cls "open") (= cls "masters"))
                   (:masters50_age row))
              (process-row
                "masters50+"
                (assoc
                  row :score
                  (score-for-result
                    "masters50+" year
                    #:game-member-results{:event (:event row)
                                          :weight (:weight row)
                                          :distance-inches (:distance_inches row)
                                          :clock-minutes (:clock_minutes row)}
                    bests weights)))

              (or (and (= cls "womens") (:masters_age row))
                  (= cls "womensmaster50+"))
              (process-row
                "womensmaster"
                (assoc
                  row :score
                  (score-for-result
                    "womensmaster" year
                    #:game-member-results{:event (:event row)
                                          :weight (:weight row)
                                          :distance-inches (:distance_inches row)
                                          :clock-minutes (:clock_minutes row)}
                    bests weights)))

              (or (= cls "womensmaster") (= cls "womensmaster50+") (= cls "womensmaster60+"))
              (process-row
                "womens"
                (assoc
                  row :score
                  (score-for-result
                    "womens" year
                    #:game-member-results{:event (:event row)
                                          :weight (:weight row)
                                          :distance-inches (:distance_inches row)
                                          :clock-minutes (:clock_minutes row)}
                    bests weights)))

              (and (or (= cls "womens") (= cls "womensmaster"))
                   (:masters50_age row))
              (process-row
                "womensmaster50+"
                (assoc
                  row :score
                  (score-for-result
                    "womensmaster50+" year
                    #:game-member-results{:event (:event row)
                                          :weight (:weight row)
                                          :distance-inches (:distance_inches row)
                                          :clock-minutes (:clock_minutes row)}
                    bests weights)))

              )))
        {})
      (x/transform
        [x/MAP-VALS
         x/MAP-VALS
         (x/collect :events x/MAP-VALS :score)
         :score]
        (fn [scores _]
          (apply + scores))))))

(comment

  (jdbc/execute!
    @datasource
    ["select distinct(country) from members order by country"])
  [#:members{:country "American"} #:members{:country "Australia"} #:members{:country "Canada"} #:members{:country "Czech"} #:members{:country "England"} #:members{:country "France"} #:members{:country "Germany"} #:members{:country "Holland"} #:members{:country "New Zealand"} #:members{:country "Norway"} #:members{:country "Poland"} #:members{:country "SC"} #:members{:country "Scotland"} #:members{:country "U.S."} #:members{:country "USA"} #:members{:country "United States"} #:members{:country "United Status"} #:members{:country "Usa"} #:members{:country "usa"} #:members{:country nil}]

  (jdbc/execute!
    @datasource
    ["update members set country = 'Canada' where country = any('{CA, CAN, canada}')"])
  (jdbc/execute!
    @datasource
    ["update members set country = 'United States' where country = any('{American, U.S., USA, United Status, Usa, usa}')"])

  (jdbc/execute! @datasource ["select 'june 7 2024'::date + '14 weeks'::interval "])

  (x/select
    [(x/keypath "masters" 12)
     ]
    (rankings-for-year 2023))

  (keys (rankings-for-year 2024))

  (supplemental-results-for-masters 12)
  (get-in (rankings-for-year 2023)
          ["open" 958])

  (prn (map :score (vals (get-in (rankings-for-year 2023)
                      [:open 958 :events]))))

  (jdbc/execute!
    @datasource
    ["select * from members where id = 1203"])
  ;; Amanda needs masters first date set
  (jdbc/execute!
    @datasource
    ["select min(date) from game_instances
join game_member_results on game_instance = game_instances.id
where member_id = 1203 and class = 'womensmaster'"])

  (jdbc/execute!
    @datasource
    ["select status from members where last_name = 'Test'"])

  (jdbc/execute!
    @datasource
    ["update members set status = 'inactive' where last_name = 'Test'"])

  (jdbc/execute!
    @datasource
    ["select game_instances.date from game_instances join games on game_id = games.id where name like '%Pleasanton%'"])
  )

(defn set-first-masters-date!
  [member-id]
  (let [{:keys [first-date]} (jdbc/execute-one!
                               @datasource
                               ["select min(date) as \"first-date\" from game_instances
                                 join game_member_results on game_instance = game_instances.id
                                 where member_id = ? and class = any('{masters, womensmaster}')"
                                member-id])]
    (prn "Setting first masters date to" first-date)
    (jdbc/execute!
      @datasource
      ["update members set master_first_date = ? where id = ? and master_first_date is null"
       first-date member-id])))

(comment
  (set-first-masters-date! 1203)
  )

;; pages

(defn create-page!
  [title]
  (jdbc/execute!
    @datasource
    ["insert into pages (title) values (?)" title]))

(defn load-page
  [title]
  (jdbc/execute-one!
    @datasource
    ["select * from pages where title = ?" title]))

(defn set-page-content!
  [title content]
  (jdbc/execute!
    @datasource
    ["update pages set content = ? where title = ?"
     content title]))

(defn all-page-titles
  []
  (jdbc/execute!
    @datasource
    ["select title from pages"]))

(comment
  (load-page "foo")
  (create-page! "foo")
  )
