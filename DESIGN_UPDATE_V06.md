# CineSays v0.6 — Approved home reference and launcher icon

- Home banner: removed cream/off-white overlays, replacing them with navy/black layered gradients over live TMDB backdrop artwork; all hero title and synopsis text now white. Preserves live catalog and existing title interactions.
- The reference is a composition guide. The banner is dynamic, so its cast, title treatment, exact placement and imagery will vary with the TMDB title and TV aspect ratio; the reference's Dune-specific title artwork cannot be automatically replicated for unrelated movies.
- Launcher icon: **first icon (upper-left)** cropped from the user's approved 2×2 image. Android manifest now points to the icon and TV banner image; no Android robot fallback.
- Friends and Recommendations: beta UI and optional cloud repository included. See SOCIAL_SETUP.md; do not use open Firestore security rules.
- Preserves `applicationId=com.cineglass.tv` and existing watchlist/regions. Rename only changed product-facing labels.
