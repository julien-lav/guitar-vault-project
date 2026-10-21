# Guitar Vault

Application Java 21 / Spring Boot.

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

La configuration actuelle utilise une base H2 **en mémoire** : les comptes disparaissent à l’arrêt de l’application. Le pilote PostgreSQL est également disponible pour une configuration persistante.

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
