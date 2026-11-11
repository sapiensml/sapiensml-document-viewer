# Security policy

The viewer sits between your users and your document repository, so we treat security reports as our first priority.

## Supported versions

| Version | Security fixes |
|---|---|
| 1.x (latest release) | Yes |
| Earlier releases | No: upgrade to the latest 1.x |

## Reporting a vulnerability

**Please do not open a public issue.** Report privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. Only the maintainers can see the report. If you can't use GitHub, email [sapiensmltech@gmail.com](mailto:sapiensmltech@gmail.com) with "Security" in the subject.

Please include:

- the viewer version (shown at `/actuator/info` on the management port, or the `VERSION` file of the release package);
- the identity provider and the Alfresco edition and version;
- the steps to reproduce, and what an attacker could gain.

Never include passwords, client secrets, tokens, real documents or personal data. Use made-up values and test documents.

Customers with a SapiensML support plan can also report through their support channel.

## What to expect

- We acknowledge a report within 3 business days.
- We aim to confirm and assess it within 10 business days, and we keep you informed until it is fixed.
- We fix confirmed issues in a new release and publish a GitHub security advisory. We credit you in the advisory unless you prefer otherwise.

## Scope

In scope: the viewer application (`src/`, `frontend/`) and the install scripts (`deploy/`).

Out of scope: vulnerabilities in Alfresco, Keycloak, Okta or other products the viewer connects to. Please report those to their vendors. If a weakness in the viewer's use of them makes your installation unsafe, that is in scope.
