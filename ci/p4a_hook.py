"""p4a post-assemble hook: preserve the signed debug APK on the session branch."""
from pathlib import Path
import os
import shutil
import subprocess


def after_apk_assemble(toolchain):
    dist = Path(toolchain._dist.dist_dir)
    candidates = sorted(dist.glob("build/outputs/apk/debug/*.apk"))
    if not candidates:
        print("[nabz-hook] no assembled debug APK found yet")
        return

    root = Path(__file__).resolve().parent.parent
    release_dir = root / "releases"
    release_dir.mkdir(parents=True, exist_ok=True)
    destination = release_dir / "NabzCrypto-8.0.0.apk"
    shutil.copy2(candidates[-1], destination)
    print("[nabz-hook] copied installable APK to", destination)

    # A workflow artifact is still uploaded by the existing workflow. Committing the APK also
    # makes it retrievable through ordinary git in environments that cannot follow artifact CDN URLs.
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    try:
        subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email",
                        "41898282+github-actions[bot]@users.noreply.github.com"], cwd=root, check=True)
        subprocess.run(["git", "add", "-f", str(destination.relative_to(root))], cwd=root, check=True)
        committed = subprocess.run(
            ["git", "commit", "-m", "build: add installable NabzCrypto 8.0.0 APK [skip ci]"],
            cwd=root, check=False).returncode == 0
        if committed:
            branch = os.environ.get("GITHUB_REF_NAME", "")
            if branch:
                subprocess.run(["git", "push", "origin", "HEAD:" + branch], cwd=root, check=True)
                print("[nabz-hook] APK committed to", branch)
    except Exception as error:
        # Do not invalidate a valid APK merely because branch persistence is read-only.
        print("[nabz-hook] branch persistence skipped:", error)
