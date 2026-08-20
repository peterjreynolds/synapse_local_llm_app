# Synapse Private Module Instructions

These instructions narrow the repository-level `AGENTS.md` for everything under
`privatechat/`.

## Product Boundary

- This module builds a separate Android application named **Synapse Private**.
- Its package and application ID are `app.synapse.privatechat`; do not reuse the
  installed identity, storage, services, or application graph from `:app`.
- Synapse Private is a human-to-human messenger. It must not contain or depend on
  local or remote assistant participants, model inference, model memory, Cinder,
  llama.cpp, GGUF tooling, Termux, SMS auto-reply, or native model libraries.
- Do not depend on the `:app` module. Share visual language deliberately by
  copying the small branded token/vector sources that this module owns.
- Supabase is the future transport owner. Keep UI and domain contracts vendor
  neutral, and do not add network behavior until that adapter is implemented.

## Security Boundary

- Treat usernames, passwords, invite codes, session material, and message
  content as sensitive external input.
- Validate and normalize account input before it reaches a transport adapter.
- Never log credentials, invitation codes, tokens, or message content.
- Clear password and invitation-code inputs when onboarding leaves the
  foreground.
- Fail closed while a transport is unavailable. Never simulate registration or
  claim that an account or message mutation succeeded without a durable receipt.
- Do not claim end-to-end encryption until the audited cryptographic transport
  is implemented and verified.

## Module Gates

- Keep the manifest permission-minimal. Add a permission only when an owned
  feature requires it and extend the boundary test with the reason.
- Keep native build blocks, JNI artifacts, model files, and model/runtime
  dependencies out of this module.
- Run `:privatechat:test`, `:privatechat:ktlintCheck`, `:privatechat:lintDebug`,
  and `:privatechat:assembleDebug` for every retained module change.
