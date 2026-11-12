# Contributing

Thank you for helping to improve the SapiensML Document Viewer. Bug reports, fixes, documentation and new features are all welcome.

## Before you start

- **Security issues:** never open a public issue. Follow [SECURITY.md](SECURITY.md).
- **Larger changes** (a new feature, a new setting, a change to how access is checked): open an issue first, so we can agree on the approach before you write the code.
- **Small fixes** (typos, documentation, obvious bugs): send a pull request directly.
- **Conduct:** everyone taking part follows our [Code of Conduct](CODE_OF_CONDUCT.md).
- **Other questions:** use [www.sapiensml.com/contact](https://www.sapiensml.com/contact).

## Development setup

You need Java 21, Maven 3.9 and Node.js 22.13 or later. No Docker is needed. "Run locally" in the [README](README.md) starts a local Keycloak and the viewer.

```bash
mvn verify                        # UI tests, UI build, Java tests, 80% line coverage gate
cd frontend && npm run dev        # UI with hot reload on http://localhost:5173
./deploy/package.sh               # Linux and Windows release packages
```

## What we look for in a pull request

- **Tests** for the change. `mvn verify` must pass, including the coverage gate.
- **Security behaviour kept:**
  - every refusal stays a uniform 404;
  - node IDs are validated;
  - secrets never reach the browser or the logs;
  - error messages are fixed text, not echoed input.
- **Code that reads like the code around it,** in naming, comment density and structure.
- **Documentation** updated when behaviour or settings change (`README.md`, `DEPLOY.md`, `DEPLOY-WINDOWS.md`).
- **Synthetic test data only.** Never add real documents, personal data, real node IDs or credentials, not even in tests or screenshots.

## Sign your commits (DCO)

We use the [Developer Certificate of Origin](https://developercertificate.org). By signing off, you confirm that you wrote the change, or have the right to submit it, under the project's licence. Add a sign-off to every commit:

```bash
git commit -s -m "Explain what the change does"
```

This adds `Signed-off-by: Your Name <you@example.com>` to the commit message. We can't merge a pull request with unsigned commits.

## Licence

The project is licensed under the [Apache License 2.0](LICENSE). Your contributions are licensed the same way.
