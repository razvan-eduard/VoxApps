# VoxApps schemas

**Fork this to own the schemas your apps use.**

These are the declarations the VoxApps family reads at runtime: which engines exist, which search
providers answer which category, which media backends can play a video, which services an app
integrates with. Every app ships with its own copy and can be pointed at a repository serving
different ones — this repository, or yours.

Forking the apps to change a provider means maintaining a fork of an Android project. Forking this
means editing JSON.

Nothing here is code, and nothing here can make an app run something it did not ship with. But
"naming services and how to reach them" is not nothing: an endpoint is where a request goes, and the
NLU prompt is what gets sent with it. That is exactly why the repository the apps follow by default
is **signed**, and why a fork is treated differently — see [Signing](#signing) below.

## Using your own

1. **Fork this repository** and edit whatever you want to change.
2. In Vox Commander, open **Settings → General → Schema updates** and set the repository URL to your
   fork: `https://github.com/<you>/<repo>`.
3. Leave *Check for updates at startup* on, or press the refresh button beside the URL.

**Turn Actions on first.** GitHub disables them in a new fork, and they are what checks your JSON
before your phone does. Without that, a stray comma means your change simply never arrives — the app
keeps the last good copy and carries on.

### Things that make a change not arrive

All of these look identical from the phone, which is why the app now names the file and the reason
under *Settings → General → Schema updates*:

| | |
|---|---|
| the fork is **private** | raw file access needs no login, so it reads as missing |
| your default branch is not `main` | append it to the URL: `https://github.com/you/repo@yourbranch` |
| a folder or file was renamed | the path must stay `remote-schemas/<app>/<file>.json`, exactly, including case |
| you committed to a new branch or a PR | the app reads the branch, not the pull request |
| you emptied a list | an empty `engines`, `intents`, `categories`, `integrations` or `backends` reads as a broken download and is refused — remove entries, don't remove all of them |
| you deleted a file | the app falls back to its bundled copy; a fork can change schemas, not remove them |

The app compares what you serve with what it already has, by content — an unchanged file costs
nothing. A file that differs and still parses is adopted and kept until you change it again.

Your fork's schemas are marked **unverified** in the app. That is expected and not a warning about
you: see below for what it means.

**The way back is always there.** *Settings → Advanced → System maintenance → Reset schemas to the
shipped ones* deletes everything downloaded from a repository, and the copies that came with the app
apply again. Those are the ones the app was built and tested against, so a broken edit here can
never leave an install stranded.

## Signing

The apps embed a public key and refuse a changed schema from **their default repository** unless its
hash appears in `remote-schemas/manifest.json` and that manifest carries a valid signature from the
matching private key. Without that, anyone who could serve those files could change where every
install sends speech, at the next launch, with no app update and nothing for a user to accept.

A fork cannot sign with that key — only the maintainer holds the private half. So the rule bends
rather than breaking the feature:

| repository | changed schema is |
|---|---|
| the app's default | adopted **only** if the signed manifest covers it |
| an exact mirror serving the original manifest and signature | adopted, and counts as signed — the signature travels with the files, not the host |
| your fork, with your own edits | **adopted, and marked unverified** — you chose that URL |

So your edits work. The app simply distinguishes "the maintainer published this" from "the user
pointed me somewhere and I did as I was told", and says which one it is on the schema screen.

If you fork this, delete `remote-schemas/manifest.json` and `manifest.json.sig` or leave them —
either way, edited files will not match them, and your schemas are accepted as unverified.

### If you also build your own apps

Forking the *app* repository gives you a build that trusts the original key, because the public key
is compiled in — a trust anchor belongs inside the APK, not loaded from beside it. To become your
own signing authority:

1. `./scripts/vox schemas keygen` — writes a new `remote-schemas/signing-key.pub` and a private key
   you keep offline.
2. Update `PUBLIC_KEY_B64` in `core/services/.../SchemaSignature.kt` to the new public key.
3. Add your repository to `SchemaRepo.KNOWN_BASE_URLS` so your build treats it as its own default
   rather than as somebody's fork.

Step 2 is not optional and not silent: `SchemaSigningKeyTest` fails the build if the compiled key and
`signing-key.pub` disagree, and says which line to change. Without it your signing would be
decorative — you would sign with one key and verify with another.

## Layout

    remote-schemas/
      commander/   read by Vox Commander
      expenses/    read by Vox Expenses
      shared/      read by more than one app

The folder is the list. An app copies its own folder plus `shared/`, and fetches each file from
`<your repo>/main/remote-schemas/<folder>/<file>`. Adding a file ships it; no name is written down
anywhere else.

## The vocabulary

Every service, wherever it is declared, describes itself the same way:

| field | meaning |
|---|---|
| `endpoint` | where the service lives |
| `probe_url` | a cheap URL proving it answers and accepts the credential, **relative to `endpoint`** |
| `auth.style` | `bearer` · `query` · `oauth2` · `none` |
| `auth.param` | the query parameter carrying the key, when `style: query` |
| `auth.flow` | `pkce` · `authorization_code`, for OAuth services |
| `requires_api_key` | the service needs a credential |
| `api_key_url` | where a user obtains one |

`probe_url` resolves like an ordinary relative URL: `models` hangs off the endpoint, `/v1/models`
starts at the host root, `?q=London` adds arguments to the endpoint itself. It is **always a path** —
an absolute URL is refused, because the probe carries that service's credential and a path can only
ever reach the host the endpoint already names.

`{key}` anywhere in a URL is replaced by the credential, for a service that takes its key in the
path. Its presence also means "this needs a credential", so nothing is asked without one.

## Before you push

    python3 scripts/validate_schemas.py remote-schemas

The same check runs here on every push. It is worth caring about: an edit in this repository reaches
every install that follows it on their next check, with no compiler and no test suite in between —
unlike a change to the apps, where the same rules are asserted before a build exists. It catches
non-https endpoints, a `probe_url` that would leave its own host, auth styles the apps do not
implement, an OAuth service that never says which flow it speaks, duplicate provider names, a
`defaultProvider` that is not in its category, a borrowed credential naming an engine nothing
declares, and a built-in media backend pretending to have an endpoint.

`schema_version` in these files is documentation for whoever edits them. The apps no longer read it:
what is in force is what you served, until someone resets it.

## Your fork is yours

You will see a `mirror.yml` workflow in your fork. It cannot run there: GitHub does not run scheduled
workflows in forks, Actions are off in a new fork until you enable them, and the job is guarded to
this repository anyway. It keeps *this* copy in step with the app repository, and nothing in it
reaches into yours.

Taking a later version is a decision you make, and you can see exactly what it would be first:

    https://github.com/<you>/<your-fork>/compare/main...razvan-eduard:VoxApps-schemas:main

or locally, once:

    git remote add upstream https://github.com/razvan-eduard/VoxApps-schemas.git
    git fetch upstream
    git diff upstream/main -- remote-schemas

Then **Sync fork** on GitHub — or `git merge upstream/main` — which surfaces the conflicts where you
and I changed the same file rather than silently picking one.

To stop following your fork altogether, you do not need this repository at all: *Settings → Advanced
→ System maintenance → Reset schemas to the shipped ones* puts the app back on the copies it was
built with.
