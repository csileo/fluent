# Fluent

Fluent est un partenaire de conversation vocale pour pratiquer une langue étrangère. Vous parlez, Claude vous répond à voix haute et relance la discussion comme un vrai interlocuteur, en corrigeant une seule erreur à la fois. Les corrections s'enregistrent dans un carnet à revoir plus tard.

Langues proposées : anglais (américain, britannique), espagnol (Espagne, Mexique), allemand, italien, portugais (Brésil, Portugal) et néerlandais. L'interface et les explications sont en français.

## Comment ça marche

- **Mot de fin** : Fluent vous écoute tant que vous n'avez pas dit votre mot de fin, même si vous marquez des pauses. Chaque langue en propose un par défaut, modifiable dans **Réglages**. Laissé vide, l'envoi se fait après un silence.
- **Accent** : Claude reçoit la transcription et les autres hypothèses de la reconnaissance vocale. Il reconstruit ce que vous vouliez dire et l'affiche sous votre message (« Compris : … »). Il ne corrige jamais la prononciation.
- **Mains libres** : après chaque réponse lue, Fluent se remet à écouter.
- **Commandes vocales** : demandez à Claude de répéter, de parler plus lentement ou plus vite, de changer de sujet ou de faire une pause, dans la langue pratiquée ou en français.

## Mode voiture

Activez-le dans **Réglages**. L'affichage est plus grand, les réponses plus courtes et l'écoute dure plus longtemps sans parole. L'écran reste allumé pendant la conversation. Après une coupure réseau, Fluent réessaie tout seul et vous prévient à voix haute, en français.

Gardez Chrome au premier plan : si l'écran se verrouille ou si vous changez d'appli, Android coupe le micro.

## Installer sur Android

1. Ouvrez la page publiée (GitHub Pages) dans **Chrome**. Le micro n'est autorisé que sur une page en HTTPS.
2. Dans **Réglages**, collez une clé API Anthropic (console.anthropic.com). Elle reste dans le navigateur et les appels partent directement vers l'API Anthropic.
3. Choisissez la langue, le niveau et une voix, puis touchez le micro.
4. Le menu ⋮ → « Ajouter à l'écran d'accueil » donne une icône comme une appli.

Chrome Android émet un petit bip à chaque relance de l'écoute : c'est le comportement du système.

## Développer

`app.html` est la seule source. Après une modification, régénérez la page autonome :

```sh
./build.sh
```
