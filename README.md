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
