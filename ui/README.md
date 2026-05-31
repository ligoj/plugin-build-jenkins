# plugin-build-jenkins — Vue UI

Vue source for the **build-jenkins** tool plugin (`service:build:jenkins`),
the Jenkins implementation of the `build` (CI) service. Compiled by Vite
into the Maven plugin JAR at
`../src/main/resources/META-INF/resources/webjars/build-jenkins/vue/`,
served by the host at `/main/build-jenkins/vue/index.js`.

Tool-level plugin — see the host's `app-ui/REWRITE_VUEJS.md`. It ships:

- **i18n** — Jenkins parameter labels (`service:build:jenkins:*`) for the
  subscribe wizard's auto-rendered parameter form.
- **`renderFeatures`** — a link to the Jenkins job (`url/job/<job>`).
- **`renderDetailsKey`** — the job-name chip.

It declares `requires: ['build']`; the parent `plugin-build` merges the
row features above via its delegation hook (`subPluginIdFor` maps
`service:build:jenkins:*` → `build-jenkins`).

## Commands

```bash
npm install
npm run build   # → ../src/main/resources/.../webjars/build-jenkins/vue/
npm run lint
npm test        # vitest — manifest + feature contract tests
npm run dev     # standalone dev harness on :5183
```
