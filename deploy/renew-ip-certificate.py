"""Root-operated certificate renewal for a dedicated private TLS directory; no credential output."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone


def run(command, **options):
    return subprocess.run(command, check=True, capture_output=True, timeout=180, **options)


def publish(root, manifest):
    """Validate the new pair before switching the directory symlink and reloading only this TLS edge."""
    lineage = root / "config-production/live/koko-nexus-ip"
    cert = lineage / "cert.pem"
    key = lineage / "privkey.pem"
    run(["openssl", "verify", "-CApath", "/etc/ssl/certs", "-untrusted", str(lineage / "chain.pem"), str(cert)])
    run(["openssl", "x509", "-in", str(cert), "-noout", "-checkip", manifest["serverIp"]])
    run(["openssl", "x509", "-in", str(cert), "-noout", "-checkend", "86400"])
    public_pem = run(["openssl", "x509", "-in", str(cert), "-pubkey", "-noout"]).stdout
    certificate_key = run(["openssl", "pkey", "-pubin", "-outform", "DER"], input=public_pem).stdout
    private_public = run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"]).stdout
    if certificate_key != private_public:
        raise RuntimeError("Certificate pair differs")
    serve = root / "serve"
    version = "cert-" + hashlib.sha256(cert.read_bytes()).hexdigest()[:20]
    current = serve / "current"
    previous = os.readlink(current)
    if previous == version:
        return False
    folder = serve / version
    if not folder.exists():
        folder.mkdir(mode=0o700)
        for name in ["fullchain.pem", "privkey.pem"]:
            shutil.copyfile(lineage / name, folder / name)
            os.chmod(folder / name, 0o600)
    temporary = serve / "current.next"
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError("Pending certificate switch requires inspection")
    temporary.symlink_to(version)
    temporary.replace(current)
    try:
        run(["docker", "exec", "koko-nexus-tls-edge", "nginx", "-t"])
        run(["docker", "exec", "koko-nexus-tls-edge", "nginx", "-s", "reload"])
    except Exception:
        temporary.symlink_to(previous)
        temporary.replace(current)
        raise
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, help="Already prepared private TLS directory")
    parser.add_argument("--dry-run", action="store_true", help="Verify renewal through staging without replacing the public certificate")
    args = parser.parse_args()
    root = Path(args.root).resolve(strict=True)
    manifest = json.loads((root / "manifest.json").read_text())
    ipaddress.ip_address(manifest["serverIp"])
    if not manifest.get("chainVerified") or not manifest.get("tlsEdgeRunning"):
        raise RuntimeError("TLS deployment not established")
    # User acceptance is for the recorded agreement, not silent acceptance of future terms.
    with urllib.request.urlopen("https://acme-v02.api.letsencrypt.org/directory", timeout=15) as response:
        directory = json.load(response)
    if directory["meta"]["termsOfService"] != manifest["acceptedTermsOfService"]:
        raise RuntimeError("Changed CA terms require owner approval")
    if "shortlived" not in directory["meta"]["profiles"]:
        raise RuntimeError("Approved short-lived profile unavailable")
    image = manifest["certbotImageId"]
    if not image.startswith("sha256:") or len(image) != 71:
        raise RuntimeError("Pinned Certbot image identifier invalid")
    command = [
        "docker", "run", "--rm", "--name", "koko-nexus-certbot-renew",
        "--read-only", "--tmpfs", "/tmp", "--memory", "192m", "--security-opt", "no-new-privileges:true",
        "-v", str(root / "config-production") + ":/etc/letsencrypt",
        "-v", str(root / "work-production") + ":/var/lib/letsencrypt",
        "-v", str(root / "logs") + ":/var/log/letsencrypt",
        # Expose only the HTTP challenge directory, not the old application's complete static files.
        "-v", str(Path(manifest["webroot"]) / ".well-known/acme-challenge") + ":/var/www/html/.well-known/acme-challenge",
        image, "renew", "--cert-name", "koko-nexus-ip", "--no-random-sleep-on-renew", "--quiet", "--non-interactive",
    ]
    if args.dry_run:
        command.append("--dry-run")
    completed = subprocess.run(command, capture_output=True, timeout=180)
    (root / "logs/last-renewal.private.log").write_bytes(completed.stdout + completed.stderr)
    if completed.returncode:
        raise RuntimeError("Renewal not confirmed; inspect private log")
    changed = False if args.dry_run else publish(root, manifest)
    result = {"checkedAt": datetime.now(timezone.utc).isoformat(), "renewalChecked": True, "dryRun": args.dry_run, "servingCertificateChanged": changed}
    (root / "logs/renewal-state.json").write_text(json.dumps(result))
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # Do not let an old success JSON hide the most recent failed renewal check.
        try:
            position = sys.argv.index("--root") + 1
            root = Path(sys.argv[position]).resolve(strict=True)
            (root / "logs/renewal-state.json").write_text(json.dumps({"checkedAt": datetime.now(timezone.utc).isoformat(), "renewalChecked": False, "failureType": type(failure).__name__}))
        except Exception:
            pass
        print("CERTIFICATE_RENEWAL_FAILED " + type(failure).__name__ + "; private details withheld", file=sys.stderr)
        raise SystemExit(1)
