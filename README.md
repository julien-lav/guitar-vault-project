# Guitar Vault

Application Java 21 / Spring Boot.

## Fixtures de démonstration

Depuis le dossier `guitar-vault`, lancer :

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=fixtures
```

Ouvrir <http://localhost:8082>. Le profil `fixtures` utilise la base persistante distincte `data/guitarvault-demo.mv.db` et son propre cookie de session `GUITARVAULT_DEMO_SESSION`. L’instance habituelle peut rester ouverte sur 8081 avec ses données dans `data/guitarvault.mv.db`. Le profil de démonstration écoute uniquement sur la machine locale.

| Compte fictif | Rôle |
| --- | --- |
| `superadmin@guitar-vault.test` | `ROLE_SUPER_ADMIN` |
| `admin@guitar-vault.test` | `ROLE_ADMIN` |
| `editor@guitar-vault.test` | `ROLE_EDITOR` |
| `superuser@guitar-vault.test` | `ROLE_SUPER_USER` |
| `user@guitar-vault.test` | `ROLE_USER` |

Mot de passe de démonstration commun : **`DemoGuitar2026!`**. Il est haché avec BCrypt dans la base. Pour choisir un autre mot de passe lors du **premier** chargement :

```sh
FIXTURES_PASSWORD='AutreMotDePasse!' ./mvnw spring-boot:run -Dspring-boot.run.profiles=fixtures
```

Les comptes sont déjà vérifiés ; le chargement n’envoie aucun email et ne nécessite pas Mailpit. Pour tester les parcours d’inscription et de récupération sur cette instance, démarrer Mailpit comme indiqué plus bas ; les liens pointent vers 8082.

`AppFixtures.java` prépare également cinq articles (trois publiés et deux brouillons) et sept instruments répartis entre les comptes. Le chargement entier est transactionnel. Le marqueur `guitar-vault-demo-v1` dans `fixture_loads` empêche de rejouer un chargement déjà terminé : les textes, mots de passe, rôles et suppressions modifiés pendant les essais sont conservés au redémarrage. Changer `FIXTURES_PASSWORD` après le premier chargement ne réinitialise donc pas les comptes. Aucune purge automatique n’est effectuée. Une collision avec un compte déjà présent lors du premier chargement annule l’opération, sans modifier ce compte.

Les fixtures ne s’activent qu’avec le profil `fixtures`. Le chargeur refuse les profils `prod`/`production` et toute base persistante autre que `data/guitarvault-demo` ; les tests utilisent une base H2 en mémoire indépendante. Le démarrage normal n’exécute pas les fixtures.

## Rôles

Les cinq rôles sont cumulables et enregistrés dans `user_roles`. Tous les comptes conservent `ROLE_USER`, y compris les comptes créés avant cette table. L’inscription publique attribue uniquement `ROLE_USER` et exige toujours la vérification de l’email. Les champs de rôle envoyés par le navigateur sont ignorés.

`ROLE_ADMIN` et `ROLE_SUPER_ADMIN` peuvent gérer tous les articles, les publier, les remettre en brouillon et les supprimer. `ROLE_EDITOR` peut créer, modifier et prévisualiser uniquement ses propres brouillons ; la publication passe par un administrateur. Il ne peut pas modifier un article déjà publié ni lire les brouillons d’un autre auteur. `ROLE_SUPER_USER` conserve actuellement les permissions d’un membre, en attendant la définition des fonctions avancées. Les rôles administratifs n’ouvrent pas les collections privées des autres membres.

L’attribution d’`ADMIN` par `APP_ADMIN_EMAILS` reste compatible avec les comptes existants. Elle s’ajoute aux rôles enregistrés pour les comptes vérifiés. Aucun écran d’attribution des rôles n’est ajouté par les fixtures ; les rôles de démonstration sont définis dans le chargeur. Aucun rôle `SUPER_ADMIN` n’est attribué au compte personnel : seul le compte fictif de la base de démonstration le reçoit.

## Journal et administration des articles

L’accueil affiche les trois derniers articles publiés sous le bloc de bienvenue et son lien d’inscription. `/articles` propose le journal public avec recherche par titre/chapeau, filtre par rubrique et pagination. Chaque article dispose de sa page de lecture, de son auteur, de sa date et d’une estimation du temps de lecture. Le démarrage normal n’ajoute aucun article de démonstration.

L’espace `/admin/articles` permet de créer, modifier, prévisualiser, publier, remettre en brouillon et supprimer les articles selon les permissions du compte. Il affiche les compteurs, une recherche, les filtres de statut/rubrique et une liste paginée. La suppression demande une confirmation sur une page dédiée. Les brouillons, leurs couvertures et leurs aperçus restent privés : les administrateurs y accèdent tous, les éditeurs uniquement à leurs propres brouillons. Les articles et couvertures sont persistés en base.

Pour attribuer le premier accès administrateur, configurer **l’adresse exacte d’un compte que vous contrôlez**, puis démarrer l’application :

```sh
APP_ADMIN_EMAILS='votre-adresse@example.com' ./mvnw spring-boot:run
```

Créer ce compte via `/signup` s’il n’existe pas, vérifier son email avec Mailpit, puis se connecter. Le lien « Administration » apparaît dans la navigation. Plusieurs adresses peuvent être séparées par des virgules ; la casse et les espaces autour des adresses sont ignorés. Aucun accès administrateur n’est attribué par défaut, ni depuis le formulaire d’inscription. Les administrateurs utilisent les mêmes comptes, mots de passe et vérifications que les autres membres. Redémarrer l’application puis se reconnecter après modification de cette liste (ne pas utiliser de sessions persistantes lors d’un changement de droits).

En local, vous pouvez aussi placer `app.admin-emails=${APP_ADMIN_EMAILS:votre-adresse@example.com}` dans `config/application.properties` depuis le dossier `guitar-vault`. Ce fichier est exclu de Git et chargé automatiquement au démarrage. Cette écriture permet à `APP_ADMIN_EMAILS` de remplacer l’adresse locale.

Un article contient un titre (160 caractères), une rubrique, un chapeau (500 caractères), un contenu en texte simple (50 000 caractères, retours à la ligne conservés) et une couverture JPEG/PNG facultative (5 Mo, 25 mégapixels, 10 000 pixels par côté maximum) avec sa description accessible obligatoire. Le chapeau et le contenu sont obligatoires à la publication ; un brouillon peut être incomplet. Aucun HTML saisi n’est exécuté. Une couverture peut être remplacée ou retirée ; les images sont séparées des données des listes pour éviter de les charger inutilement.

« Enregistrer en brouillon » retire un article déjà publié du site public. L’aperçu montre la dernière version enregistrée. En cas de modification concurrente, publication ou suppression à partir d’une ancienne version, l’opération est refusée pour préserver le travail récent. Les erreurs de validation conservent les textes ; un fichier doit être sélectionné à nouveau. Si la requête entière dépasse la limite d’import, rouvrir le formulaire : la version enregistrée reste inchangée.

Les libellés du journal et de l’administration sont disponibles en français et en anglais. Le contenu d’un article est affiché dans la langue de rédaction.

Un accès refusé affiche une page de récupération : elle distingue un compte sans droits administrateur d’un formulaire dont le jeton de sécurité a expiré, notamment après un redémarrage. Elle permet de changer de compte ou de rouvrir le formulaire. La requête refusée n’est pas exécutée et les protections restent actives. Après un redémarrage, recharger les formulaires déjà ouverts avant de les envoyer.

```sh
./mvnw spring-boot:run
```

Ouvrir <http://localhost:8081/signup> pour créer un compte. Le lien est également disponible dans la navigation et sur l’accueil.

L’inscription enregistre le profil dans la table `users`, normalise l’email et hache le mot de passe avec BCrypt. Les erreurs de validation conservent les informations du profil, sans conserver les mots de passe. Une inscription réussie envoie un email de vérification et redirige vers <http://localhost:8081/login> avec une confirmation. Le compte reste inactif jusqu’au clic sur le lien, valable 24 heures et utilisable une seule fois. Seule l’empreinte SHA-256 du jeton est stockée. Si l’envoi échoue, l’inscription est annulée pour permettre de réessayer.

Pour tester les emails en local :

```sh
docker compose up -d mailpit
./mvnw spring-boot:run
```

Créer un compte sur <http://localhost:8081/signup>, ouvrir l’email dans Mailpit à <http://localhost:18025>, puis suivre le lien de vérification et se connecter. Mailpit capture les emails localement ; il ne les envoie pas à la boîte réelle du destinataire. Le port SMTP exposé est `11025`.

`APP_BASE_URL` permet de configurer l’adresse de l’application dans les liens (par défaut `http://localhost:8081`) et `MAIL_FROM` l’expéditeur. Pour un déploiement réel, configurer également un serveur SMTP de livraison via les propriétés Spring Mail.

La connexion utilise l’email et le mot de passe du compte et ouvre le dashboard. Spring Security gère la session et protège les formulaires contre les requêtes CSRF. Le bouton « Se déconnecter » ferme la session. Le dashboard et les pages internes nécessitent une connexion.

Le tableau de bord à <http://localhost:8081/dashboard> affiche uniquement la collection du compte connecté : nombre total d’instruments, statuts (actif, à réparer, en vente) et cinq derniers ajouts. La collection complète se trouve dans « Mes instruments ». Le bouton « Ajouter un instrument » permet d’enregistrer un nom/modèle, une marque, une année facultative et un statut. Le propriétaire est fixé par le serveur à partir de la session connectée.

La configuration locale utilise une base H2 **persistante** dans `data/guitarvault.mv.db` : les comptes et instruments sont conservés après un redémarrage. Ce dossier est exclu de Git. Les tests Maven utilisent une base en mémoire distincte. Le pilote PostgreSQL est également disponible pour un déploiement.

Les comptes créés avec l’ancienne base en mémoire ont été perdus lors du dernier arrêt et doivent être recréés puis vérifiés. Sans compte actif, une demande de réinitialisation affiche la même confirmation mais n’envoie aucun email. En local, les nouveaux emails se consultent dans Mailpit à <http://localhost:18025>.

```sh
./mvnw test
```

Test facultatif de livraison réelle vers le Mailpit local (laisse des emails de test dans l’inbox, le compte temporaire est annulé à la fin) :

```sh
./mvnw -Dmailpit.integration=true test
```

La navigation propose un menu « Français / English » avec une préférence conservée dans un cookie pendant un an. Les pages, messages de validation et emails sont traduits. Le changement de langue conserve la page et ses paramètres, y compris les liens de récupération.

Les champs de mot de passe disposent d’un bouton œil accessible au clavier. « Mot de passe oublié ? » sur la page de connexion ouvre <http://localhost:8081/forgot-password>. Pour un compte vérifié, le lien envoyé par email permet de choisir un nouveau mot de passe pendant 30 minutes, une seule fois. Le nouveau mot de passe doit comporter au moins 8 caractères, dont un caractère spécial. Les anciennes sessions sont expirées après réinitialisation. La réponse à une demande reste identique pour les adresses inconnues ou inactives ; les demandes répétées pour un compte sont espacées d’au moins une minute.

Le renvoi de l’email de vérification est disponible depuis la connexion ou directement sur <http://localhost:8081/resend-verification>. Le serveur génère un nouveau lien d’activation pour les comptes en attente, invalide le précédent et limite l’envoi à une fois par minute. Une erreur SMTP conserve l’ancien lien. La réponse reste identique pour une adresse inconnue ou un compte déjà vérifié. L’email est envoyé dans la langue choisie. Le formulaire de récupération du mot de passe conserve également l’adresse saisie et propose de renvoyer le lien après la première demande.

Le formulaire d’ajout d’instrument propose aussi un numéro de série facultatif (100 caractères, zéros initiaux conservés) et un ancien propriétaire facultatif (200 caractères), affichés dans la fiche. Il accepte jusqu’à cinq photos JPEG ou PNG (5 Mo par photo, 25 mégapixels maximum), une histoire (10 000 caractères) et d’autres informations (5 000 caractères). Les listes de morceaux enregistrés et de concerts proposent des boutons « + » et de suppression, jusqu’à 50 entrées de 500 caractères chacune. Les entrées sont textuelles (titre, artiste, album ou salle, ville, date) et leur ordre est conservé. Les photos sont enregistrées dans la base avec l’instrument. En cas d’erreur de validation, les textes sont conservés et les photos doivent être sélectionnées à nouveau.

Cliquer sur le nom d’un instrument dans le tableau de bord ou la collection ouvre sa fiche avec les photos, l’histoire, les informations et les listes. La fiche et les photos sont réservées au propriétaire connecté.

Le formulaire est organisé en quatre sections avec des raccourcis de navigation : l’essentiel, les photos, l’histoire et la vie en musique. Un résumé suit la saisie du modèle et de la marque, les textes longs ont des compteurs, et l’action d’enregistrement reste accessible en bas de l’écran. Les photos peuvent être ajoutées en plusieurs sélections ou par glisser-déposer, puis retirées individuellement avant l’envoi. Le formulaire s’adapte aux petits écrans et signale les erreurs d’import sans supprimer les photos déjà sélectionnées.

Chaque utilisateur connecté dispose d’une liste de souhaits privée sur `/wishlist`, accessible depuis la navigation et le tableau de bord. Il peut ajouter, modifier et retirer des souhaits (modèle et marque obligatoires, année et notes facultatives). Le retrait passe par une page de confirmation. Les souhaits sont enregistrés séparément des instruments possédés et ne modifient pas les compteurs de la collection. Les textes de l’interface sont disponibles en français et en anglais.

Les profils de groupe sont disponibles sur `/bands` pour les utilisateurs connectés. Le créateur renseigne un nom, puis éventuellement un style, une ville, une présentation et des liens Spotify, Deezer et site web. Les liens doivent utiliser HTTPS ; les liens Spotify et Deezer sont vérifiés par domaine. Ils sont affichés comme liens externes, sans intégration de lecteur ni appel à leurs API.

Un groupe réunit de 1 à 50 comptes actifs. Les nouveaux musiciens sont invités par pseudo exact (un par ligne ou séparés par des virgules), puis acceptent ou refusent leur invitation. Le créateur reste automatiquement membre et est seul autorisé à modifier la fiche et les associations. Modifier la présentation du groupe ne retire pas les membres existants ; les changements de composition passent par des actions explicites. Un musicien peut appartenir à plusieurs groupes. La page `/musicians/{id}` présente son pseudo et ses groupes, sans exposer son email. Les données sont stockées dans `bands` et la table de liaison `band_members`, séparément des instruments et des listes de souhaits.

Les studios disposent d’un annuaire et de fiches sur `/studios`, accessibles aux utilisateurs connectés depuis la navigation et le tableau de bord. Tout compte actif peut ajouter plusieurs studios, sans rôle professionnel spécifique. Seul le créateur peut modifier ses fiches. Le nom est obligatoire ; la ville, le pays, la présentation, les prestations, le matériel, un site HTTPS et un email de contact sont facultatifs. Les textes longs sont limités à 5 000 caractères chacun. L’email du compte n’est jamais repris automatiquement comme contact.

La fiche du studio renvoie vers le profil de son créateur, qui présente également ses studios. Les enregistrements sont conservés dans la table `studios`, avec une relation vers le compte créateur. La gestion d’une équipe de studio et la réservation ne font pas partie de cette version.

L’annuaire `/studios` propose une recherche combinée par ville, pays et fourchette de prix. Les recherches de lieu sont partielles et insensibles à la casse ; `%`, `_` et `!` sont traités comme des caractères littéraux. Il s’agit d’une recherche textuelle par lieu, sans géolocalisation ni rayon kilométrique. Les filtres sont transmis dans l’URL et peuvent être effacés. La rubrique « Mes studios » conserve la liste des fiches gérées par le compte, indépendamment de la recherche.

Le créateur peut renseigner un tarif horaire facultatif en euros, stocké en décimal exact (`hourly_rate`, deux décimales), entre 0 et 999 999,99 €/h. La saisie serveur accepte le point ou la virgule. Les bornes de recherche sont inclusives ; un minimum supérieur au maximum affiche une erreur. Les fiches existantes sans prix restent valides et affichent « Tarif sur devis ». Elles sont exclues uniquement lorsqu’une borne de prix est renseignée. Les critères sont appliqués dans la requête en base.

Le menu « Mon profil » (`/profile`) ouvre la page du compte connecté, et `/profile/edit` permet de la modifier. Les profils existants sur `/musicians/{id}` conservent les groupes et studios associés et présentent désormais un nom affiché facultatif (le pseudo par défaut), la bio, les instruments joués (texte libre, 255 caractères), le niveau, la ville, le pays, les activités et les liens Spotify, Deezer et site web. Les informations déjà renseignées à l’inscription sont reprises. Les activités « Musicien » et « Ingénieur du son » peuvent se cumuler ; elles ne changent pas les rôles ni les autorisations du compte. Le tableau de bord utilise le nom affiché lorsqu’il est renseigné.

Les champs du profil sont facultatifs. La bio reste limitée à 1 000 caractères, le nom affiché, la ville et le pays à 100, et les liens HTTPS à 2 000 caractères (domaines Spotify et Deezer vérifiés). L’email, le nom complet du compte, le mot de passe et les rôles ne figurent pas dans les données du profil consultable et ne peuvent pas être modifiés via ce formulaire. Le pseudo reste stable. Les pages et photos sont accessibles uniquement aux utilisateurs connectés, et les comptes désactivés ne sont pas consultables.

La photo peut être ajoutée, remplacée ou supprimée depuis le formulaire. Elle est stockée dans `profile_photos`, séparément des comptes, et servie avec `Cache-Control: private, no-store` et `X-Content-Type-Options: nosniff`. Le contenu réel doit être une image JPEG ou PNG valide de 5 Mo maximum, 4 096 pixels maximum par côté et 12 mégapixels maximum. Une erreur de validation ne modifie ni les textes ni la photo enregistrés ; les textes saisis sont conservés dans le formulaire, et le fichier doit être sélectionné à nouveau. Les activités sont conservées dans `user_activities` et les liens et nom affiché dans les nouvelles colonnes nullable de `users`.

Les demandes de devis studio sont accessibles depuis « Demander un devis » sur une fiche studio et depuis « Mes demandes » (`/quotes`) dans le menu et le tableau de bord. Le demandeur renseigne un titre (150 caractères maximum), une description (5 000 caractères maximum), des dates souhaitées facultatives et un budget total facultatif en euros. Les dates doivent être à partir du jour courant en UTC ; une fin nécessite un début et ne peut pas le précéder. Le budget total est indépendant du tarif horaire du studio. Les montants acceptent le point ou la virgule, jusqu’à deux décimales, entre 0 et 999 999,99 €.

La demande est liée au studio désigné par l’URL et au compte connecté ; les identifiants fournis dans le formulaire ne peuvent pas changer ces participants. Un utilisateur ne peut pas adresser de demande à son propre studio, ni à un studio dont le responsable est désactivé. La liste sépare les demandes envoyées et celles reçues pour tous les studios gérés par le compte. Seuls le demandeur et le responsable du studio peuvent consulter les détails. Les emails de compte ne sont pas affichés aux autres participants.

Le demandeur et le responsable peuvent poursuivre une discussion privée avec des messages de 5 000 caractères maximum, présentés dans l’ordre d’envoi avec auteur et date en UTC. Seul le responsable peut joindre un montant total proposé facultatif à un message ; chaque proposition est conservée dans l’historique. Un message du musicien remet la demande en `PENDING` (en attente du studio), une réponse du studio la passe en `ANSWERED`. Les compteurs du tableau de bord suivent ce statut, sans suivi des messages non lus. Un verrou en base sérialise les envois pour une même demande. Les nouveaux messages sont stockés dans `studio_quote_messages` ; les anciennes réponses de `studio_quotes` restent visibles et sont archivées une seule fois lors du premier nouvel envoi. Les consultations ne modifient pas les données. Les messages enregistrés ne sont ni modifiables ni supprimables depuis l’application. Ce parcours reste un échange dans l’application, sans email automatique, réservation, paiement ni acceptation contractuelle du devis.

Les fiches studio peuvent désormais préciser leur capacité d’accueil (1 à 1 000 personnes), les tarifs de mixage et de mastering par titre, un forfait journée et des précisions tarifaires (1 000 caractères). Ces champs sont facultatifs et les montants sont stockés en décimal exact, comme le tarif horaire existant. La recherche par prix continue à comparer uniquement les tarifs horaires ; elle ne mélange pas les prix au titre et à la journée. Les capacités apparaissent aussi dans les cartes de l’annuaire.

Le créateur peut ajouter jusqu’à cinq photos par studio et retirer individuellement les photos existantes depuis le formulaire. Les ajouts et retraits sont appliqués uniquement à l’enregistrement, après validation de tous les champs et fichiers. Les photos doivent être des JPEG ou PNG valides, de 5 Mo maximum chacune, 10 000 pixels maximum par côté et 25 mégapixels maximum. Elles sont conservées dans `studio_photos`. Les galeries chargent uniquement les identifiants ; les données d’une image sont chargées séparément par `/studios/{id}/photos/{photoId}`, réservé aux comptes connectés, avec cache privé désactivé et `nosniff`. Les identifiants des photos à retirer doivent appartenir au studio modifié. Un verrou sur le studio protège la limite de cinq photos lors de modifications concurrentes.

La navigation distingue « Mon espace » (tableau de bord, profil, instruments, souhaits, groupes, studios et demandes) et « Découvrir » (articles, annuaire des studios, groupes). Les menus utilisent des éléments HTML `details` et restent utilisables sans JavaScript. Le JavaScript permet de fermer le menu au clic extérieur, à la sélection d’un lien ou avec Échap. Un lien d’évitement mène au contenu principal ; la page courante est signalée dans les menus et les liens d’administration restent conditionnés aux autorisations existantes.

Le tableau de bord personnalisé affiche le profil, les totaux des groupes rejoints, studios gérés, souhaits et demandes de devis envoyées, ainsi que des aperçus de trois éléments avec liens vers les listes complètes. Les « projets studio » correspondent aux demandes de devis existantes, sans nouvelle entité de projet. Les demandes reçues en attente sont mises en avant pour les responsables de studio ; les réponses disponibles sont comptées côté demandeur (ce compteur ne désigne pas des messages non lus). Les requêtes sont limitées au compte connecté et les aperçus à trois enregistrements en base. Les cinq derniers instruments et les compteurs de collection restent disponibles. Les liens « Mes groupes » et « Mes studios » ouvrent leurs sections du tableau de bord ; « Tout voir » rejoint la liste complète correspondante.


Les invitations de groupe sont disponibles sur `/invitations`, depuis « Mon espace », le tableau de bord et la fiche du groupe. Les invitations reçues et celles envoyées pour les groupes créés par le compte sont privées. Un destinataire ne devient membre qu’après acceptation ; un refus ou une annulation par le créateur n’ajoute aucun membre. Les associations déjà présentes dans `band_members` sont conservées sans demander de nouvelle acceptation.

Le champ des pseudos dans la création et la modification du groupe envoie des invitations, sans remplacer la liste des membres. Une invitation déjà en attente n’est pas dupliquée ; un membre actuel n’est pas réinvité. Un créateur peut relancer explicitement une invitation refusée ou annulée, ou réinviter une personne ayant quitté le groupe. La limite de 50 places comprend les membres et les invitations en attente. Un refus ou une annulation libère une place. Tous les pseudos sont validés avant d’enregistrer la fiche et les invitations.

Les membres peuvent quitter le groupe et le créateur peut retirer un membre après une page de confirmation. Le créateur reste membre et responsable : il ne peut pas se retirer ni être retiré. Une nouvelle invitation acceptée est nécessaire pour revenir après un départ ou un retrait. Seul le destinataire peut accepter ou refuser, seul le créateur peut inviter, annuler ou retirer. Les actions exigent une session active et une protection CSRF. Les invitations sont stockées dans `band_invitations`, avec une ligne unique par groupe et destinataire et les états `PENDING`, `ACCEPTED`, `DECLINED`, `CANCELLED`. Les mutations verrouillent le groupe en base pour sérialiser les changements de membres et de places. Les notifications restent dans l’application, sans email automatique.

Les groupes peuvent renseigner jusqu’à huit genres libres (100 caractères chacun), séparés par des virgules dans le formulaire, ainsi qu’une région facultative (100 caractères), en complément de la ville. Les genres sont affichés en pastilles sur les fiches et les cartes, y compris sur les profils des musiciens. Les doublons de casse et les espaces superflus sont regroupés. La nouvelle collection `band_genres` conserve les genres ; la colonne historique `bands.genre` reste compatible et les anciens styles sont affichés et recherchables sans migration manuelle. La région est stockée dans une nouvelle colonne nullable. La modification et l’effacement de ces champs restent réservés au créateur et ne modifient pas les membres.

L’annuaire `/bands` propose des filtres combinables `genre`, `region` et `city` : genre exact, région et ville partielles, sans distinction de casse. Les critères sont appliqués en base et les doublons de groupes supprimés. Les caractères `%`, `_` et `!` sont traités littéralement. Les genres du filtre incluent les styles historiques ; les suggestions de régions reprennent les valeurs déjà enregistrées. Les champs restent libres pour convenir aux groupes de différents pays. Les filtres restent dans l’URL, ne réduisent pas « Mes groupes » et disposent d’un état sans résultat et d’un lien de réinitialisation.

Les responsables peuvent créer des liens entre groupes depuis la fiche du groupe destinataire : « Groupes amis » ou « On a joué ensemble ». Pour un concert partagé, une date passée ou du jour (UTC) et un lieu de 200 caractères maximum sont facultatifs. Les deux types de liens peuvent coexister ; chaque type représente une relation unique par paire de groupes. Les demandes restent privées aux responsables concernés et nécessitent une acceptation du destinataire. Les liens acceptés sont affichés symétriquement sur les deux fiches, avec les éventuels détails de concert. Les simples membres ne peuvent pas engager leur groupe.

« Mon espace → Liens entre groupes » (`/band-connections`) regroupe les demandes reçues, envoyées et les liens acceptés pour les groupes gérés. Le destinataire peut accepter ou refuser, l’expéditeur peut annuler, et chacun des deux responsables peut retirer un lien accepté après un écran de confirmation. Un refus, une annulation ou un retrait permet une nouvelle demande avec un nouveau consentement. Les groupes ne peuvent pas se lier à eux-mêmes, ni envoyer de demande vers un responsable désactivé. La table `band_connections` utilise une paire ordonnée d’identifiants et une contrainte d’unicité incluant le type ; les écritures verrouillent les deux groupes dans cet ordre pour empêcher les doublons de demandes croisées. Aucun email n’est envoyé automatiquement.

Les concerts s’ajoutent depuis la fiche d’un groupe, uniquement par son responsable. Un titre (150 caractères), une date valide et un lieu (200 caractères) sont obligatoires ; la ville (100), la description (5 000) et l’affiche sont facultatives. Une date passée peut être renseignée pour compléter l’historique. Les fiches séparent les concerts à venir (aujourd’hui compris, en UTC), passés et annulés. Le responsable du groupe organisateur peut modifier un concert ou l’annuler après confirmation. Un concert annulé reste visible et ne reçoit plus de modifications ni de nouvelles participations.

Après publication, le responsable invite les autres groupes depuis la fiche du concert. Leurs responsables acceptent ou refusent depuis « Mon espace → Invitations aux concerts » ou depuis le concert. Seuls l’organisateur et les groupes ayant accepté sont affichés parmi les participants et sur leurs fiches respectives. Les invitations en attente ne sont pas publiques. Le responsable organisateur ou celui du groupe invité peut retirer une participation ou une invitation après confirmation ; une nouvelle invitation reste possible. La table `concert_participations` garantit l’unicité par concert et groupe, et les mutations verrouillent le concert. Les demandes ne modifient pas les liens d’amitié ni les relations « On a joué ensemble ».

Les affiches sont conservées séparément dans `concert_posters`, et les données textuelles dans `concerts`. La validation de l’image contrôle le contenu réel JPEG/PNG, sa taille (5 Mo), ses dimensions (4 096 pixels par côté) et sa surface (12 mégapixels) avant toute mutation. Les fichiers ne sont pas chargés lors de la consultation des listes. Le service d’images exige une session et utilise `Cache-Control: private, no-store` et `X-Content-Type-Options: nosniff`. Le formulaire permet de remplacer ou retirer une affiche ; un nouvel envoi a priorité sur la case de retrait.

Chaque groupe dispose d’un agenda privé (`/bands/{id}/calendar`) accessible au responsable et aux membres actuels du groupe, depuis sa fiche. Une invitation au groupe non acceptée, un lien d’amitié ou une participation à un concert ne donnent pas accès à cet agenda. Toutes les routes de lecture et de gestion contrôlent cette appartenance côté serveur et retournent 404 aux autres comptes ; les réponses de l’agenda utilisent `Cache-Control: private, no-store`. Seul le responsable peut créer, modifier ou supprimer un événement. Les données privées ne sont jamais ajoutées aux fiches et listes publiques des groupes.

L’agenda propose une grille mensuelle du lundi au dimanche et une liste pour le mois choisi, avec navigation et couleurs par catégorie : concerts, répétitions, studio, promotion, sorties musicales et autres événements. Les concerts de l’organisateur et ceux dont la participation est confirmée sont lus directement depuis les concerts existants, dans la période affichée ; leurs changements, annulations et retraits de participation sont donc répercutés sans duplication. La gestion des concerts reste sur leurs pages. Les autres dates sont conservées dans `band_calendar_events` avec titre (150 caractères), catégorie, date, horaires facultatifs, lieu (200 caractères) et notes privées (5 000 caractères). Une heure de fin nécessite un début et doit être plus tard le même jour. Les horaires sont des heures locales saisies, sans conversion de fuseau ; le mois courant est choisi en UTC, comme les autres classements de dates de l’application. Les sessions studio sont saisies manuellement lorsque leurs dates sont confirmées.

Les événements peuvent être uniques ou hebdomadaires, avec une date de fin obligatoire et inclusive. Les occurrences sont calculées pour la seule période consultée, y compris si la série commence avant le mois choisi ; elles ne sont pas matérialisées en base. Modifier une série modifie toutes ses occurrences ; la suppression, après confirmation explicite, supprime toute la série. Cette première version ne prévoit pas d’exception par occurrence. Les recherches de données sont limitées au groupe autorisé et à la plage de dates affichée.

La cloche des notifications est visible pour les comptes connectés et indique le nombre de notifications non lues (badge limité visuellement à `99+`, compteur exact sur la page). Elle ouvre `/notifications`, une liste privée paginée de 20 éléments, du plus récent au plus ancien, avec filtre des non lues. Ouvrir une notification la marque comme lue puis redirige vers l’élément concerné ; une action permet aussi de la marquer comme lue sans ouvrir, ou de tout marquer comme lu. Ces mutations utilisent POST avec CSRF et sont strictement limitées au destinataire. Consulter la liste ou le compteur ne change pas l’état de lecture. Les réponses sont `private, no-store`. Les liens sont construits côté serveur, limités à des destinations internes connues, et les pages de destination conservent leurs propres contrôles d’accès.

Les nouvelles invitations à rejoindre un groupe, demandes de lien entre groupes, invitations aux concerts, demandes de devis et messages de devis créent une notification dans la table `notifications`, dans la même transaction que l’action d’origine. Les doublons déjà en attente et les actions refusées ne créent rien ; une nouvelle invitation après un refus peut notifier à nouveau. Une action du compte sur ses propres groupes ne produit pas d’auto-notification et les comptes désactivés ne sont pas notifiés. Les notifications contiennent le nom du groupe, du concert ou du projet, sans le texte privé des messages. Le chargement d’une ancienne réponse de devis dans l’historique ne crée pas de fausse notification. Les demandes et messages antérieurs à cette fonctionnalité ne sont pas importés automatiquement.

Le compteur est rendu côté serveur à chaque page et vérifié toutes les 30 secondes sur les onglets visibles via `/notifications/unread-count`, ainsi qu’au retour sur l’onglet. Ce point d’accès exige une connexion et ne renvoie que le compteur du compte courant. La cloche reste utilisable sans JavaScript. Les états de lecture des notifications sont distincts des statuts des demandes et invitations : marquer comme lu n’accepte pas une invitation et ne traite pas un devis. Aucun email ni notification push externe n’est envoyé.
