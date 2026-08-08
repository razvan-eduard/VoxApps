#!/usr/bin/env python3
"""Check the shipped schemas against the rules the apps rely on.

The apps assert these in Kotlin, against the copies they ship with. That protects a *build* — it
cannot protect an edit pushed to the repository, which reaches every install on the next check
without a compiler or a test suite in between. This script is that missing step, and it is mirrored
into the schemas repository alongside the files so the rules and the files travel together.

Run it over a folder of schemas:

    python3 scripts/validate_schemas.py remote-schemas

Exit code is 1 if anything failed, so it works as a CI gate. Warnings do not fail the run: they are
things worth knowing that a deliberate author may have meant.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.parse import urlsplit

AUTH_STYLES = {"bearer", "query", "oauth2", "none"}
OAUTH_FLOWS = {"pkce", "authorization_code"}

problems: list[str] = []
warnings: list[str] = []


def fail(where: str, message: str) -> None:
    problems.append(f"{where}: {message}")


def warn(where: str, message: str) -> None:
    warnings.append(f"{where}: {message}")


def load(path: Path):
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError as e:
        fail(path.name, f"not valid JSON — {e}")
        return None


def probe_url(endpoint: str, probe: str | None, where: str) -> str | None:
    """Resolve a probe the way ProbeSpec.from does, or report why it cannot be resolved.

    A probe is always a path, never a URL: it can only ever reach the host the endpoint already
    names, because the request carries that service's credential.
    """
    if probe is None or probe == "":
        return endpoint
    if probe.startswith("http://") or probe.startswith("https://"):
        fail(where, f"probe_url must be a path, not an absolute URL: {probe}")
        return None
    base = endpoint.rstrip("/")
    if probe.startswith("?"):
        return base + probe
    if probe.startswith("/"):
        parts = urlsplit(base)
        return f"{parts.scheme}://{parts.netloc}{probe}"
    return f"{base}/{probe}"


def check_endpoint(url: str, where: str, field: str = "endpoint") -> None:
    resolved = url.replace("{lang}", "en")
    if not resolved.startswith("https://"):
        fail(where, f"{field} is not https: {url}")


def check_probe(endpoint: str, probe: str | None, where: str) -> None:
    resolved = probe_url(endpoint.replace("{lang}", "en"), probe, where)
    if resolved is None:
        return
    if urlsplit(resolved).netloc != urlsplit(endpoint.replace("{lang}", "en")).netloc:
        fail(where, f"probe_url leaves the service's own host: {resolved}")


def auth_style(auth: dict | None) -> str:
    if not auth:
        return "none"
    return str(auth.get("style") or auth.get("type") or "none").lower()


def check_auth(auth: dict | None, where: str) -> None:
    if not auth:
        return
    style = auth_style(auth)
    family = style.split("_")[0] if style.startswith("oauth2") else style
    if family not in AUTH_STYLES:
        fail(where, f"unknown auth style '{style}' — the app implements {sorted(AUTH_STYLES)}")
    if family == "oauth2":
        flow = auth.get("flow") or style.replace("oauth2_", "")
        if flow not in OAUTH_FLOWS:
            fail(where, f"OAuth service declares no flow — expected one of {sorted(OAUTH_FLOWS)}")
        for required in ("authorize_url", "token_url", "redirect_uri"):
            if not auth.get(required):
                fail(where, f"OAuth service is missing {required}")


# --- per-schema rules ------------------------------------------------------------------------


def check_search_definitions(doc, name, engine_keys):
    seen_categories = set()
    for category in doc.get("categories", []):
        cat = category.get("category", "")
        where = f"{name} › {cat or '(unnamed)'}"
        if not cat:
            fail(where, "a category with no name can never be asked for")
        elif cat in seen_categories:
            fail(where, "two categories share a name; the second replaces the first")
        seen_categories.add(cat)

        names = set()
        for provider in category.get("providers", []):
            pname = provider.get("name", "")
            pwhere = f"{where} › {pname or '(unnamed)'}"
            if not pname:
                fail(pwhere, "a provider with no name cannot be selected")
            elif pname in names:
                fail(pwhere, "two providers share a name in one category")
            names.add(pname)

            endpoint = provider.get("endpoint", "")
            if not endpoint:
                fail(pwhere, "a provider with no endpoint has nothing to call")
                continue
            check_endpoint(endpoint, pwhere)
            check_probe(endpoint, provider.get("probe_url"), pwhere)
            check_auth(provider.get("auth"), pwhere)

            if provider.get("requiresApiKey") and auth_style(provider.get("auth")) == "none":
                if not provider.get("shared_key_engine") and "{apiKey}" not in (provider.get("queryTemplate") or ""):
                    fail(pwhere, "requires an API key but never says how it travels")

            borrowed = provider.get("shared_key_engine")
            if borrowed and engine_keys and borrowed not in engine_keys:
                fail(pwhere, f"borrows a credential from '{borrowed}', which no schema declares")

        default = category.get("defaultProvider")
        if default and default not in names:
            fail(where, f"defaultProvider '{default}' is not one of its providers")


def check_engines(doc, name):
    keys = set()
    for key, engine in (doc.get("engines") or {}).items():
        keys.add(key)
        where = f"{name} › {key}"
        endpoint = engine.get("endpoint")
        if endpoint:
            check_endpoint(endpoint, where)
            check_probe(endpoint, engine.get("probe_url"), where)
        check_auth(engine.get("auth"), where)
        if "requires_api_key" in (engine.get("capabilities") or []) and not engine.get("api_key_url"):
            warn(where, "needs an API key but does not say where to get one")
        for model in engine.get("models") or []:
            path = model.get("path") or ""
            if path.startswith("http://"):
                fail(where, f"model '{model.get('id')}' downloads over http: {path}")
    return keys


def check_api_integrations(doc, name):
    for integration in doc.get("integrations", []):
        where = f"{name} › {integration.get('id') or '(no id)'}"
        endpoint = integration.get("endpoint") or integration.get("base_url")
        if not endpoint:
            fail(where, "an integration with no endpoint cannot be reached")
            continue
        check_endpoint(endpoint, where)
        check_probe(endpoint, integration.get("probe_url"), where)
        check_auth(integration.get("auth"), where)


def check_media_services(doc, name):
    defaults = 0
    for backend in doc.get("backends", []):
        where = f"{name} › {backend.get('id') or '(no id)'}"
        defaults += 1 if backend.get("default") else 0
        built_in = backend.get("runtime") == "device_builtin"
        endpoints = backend.get("endpoints") or []
        if built_in and endpoints:
            fail(where, "a built-in backend declares endpoints")
        if not built_in and not endpoints:
            fail(where, "a backend with no endpoints has nothing to reach")
        for endpoint in endpoints:
            check_endpoint(endpoint, where)
            check_probe(endpoint, backend.get("probe_url"), where)
    if doc.get("backends") and defaults != 1:
        fail(name, f"exactly one backend should be the default, found {defaults}")


def check_external_services(doc, name):
    for service in doc.get("services", []):
        where = f"{name} › {service.get('id') or '(no id)'}"
        endpoint = service.get("endpoint") or service.get("baseEndpoint")
        if not endpoint:
            fail(where, "a service with no endpoint cannot be reached")
            continue
        check_endpoint(endpoint, where)
        check_probe(endpoint, service.get("probe_url"), where)
        check_auth(service.get("auth"), where)
        needs_key = service.get("requires_api_key") or service.get("requiresApiKey")
        if needs_key and not (service.get("api_key_url") or service.get("docsUrl")):
            warn(where, "needs an API key but does not say where to get one")


def main(root: Path) -> int:
    files = sorted(root.rglob("*.json"))
    if not files:
        print(f"No schemas found under {root}")
        return 1

    docs = {path: load(path) for path in files}
    docs = {path: doc for path, doc in docs.items() if doc is not None}

    # Engine keys first: a search provider may borrow a credential from one of them.
    engine_keys: set[str] = set()
    for path, doc in docs.items():
        if "engines" in doc:
            engine_keys |= check_engines(doc, path.name)

    for path, doc in docs.items():
        name = path.name
        if "categories" in doc:
            check_search_definitions(doc, name, engine_keys)
        elif "integrations" in doc:
            check_api_integrations(doc, name)
        elif "backends" in doc:
            check_media_services(doc, name)
        elif "services" in doc:
            check_external_services(doc, name)

    for message in warnings:
        print(f"warning  {message}")
    for message in problems:
        print(f"FAIL     {message}")

    print(f"\n{len(docs)} schema(s) checked · {len(problems)} problem(s) · {len(warnings)} warning(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "remote-schemas")
    sys.exit(main(target))
