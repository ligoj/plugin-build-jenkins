# :link: Ligoj Jenkins CI plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-build-jenkins/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-build-jenkins)

[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=org.ligoj.plugin%3Aplugin-build-jenkins&metric=coverage)](https://sonarcloud.io/dashboard?id=org.ligoj.plugin%3Aplugin-build-jenkins)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?metric=alert_status&project=org.ligoj.plugin:plugin-build-jenkins)](https://sonarcloud.io/dashboard/index/org.ligoj.plugin:plugin-build-jenkins)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/773ae77ebb1d47a08ad7cb3ff255741a)](https://www.codacy.com/gh/ligoj/plugin-build-jenkins?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-build-jenkins&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-build-jenkins/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-build-jenkins)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) Jenkins CI plugin, an
extending [Build plugin](https://github.com/ligoj/plugin-build)
Provides the following features :

- Job status
- Start a job
- List of branches for [Multi-branch job](https://www.jenkins.io/doc/book/pipeline/multibranch/)
- Compatible with Jenkins `1.x` and `2.x`

# Plugin parameters

| Parameter                          | Default | Note                                                              |                     
|------------------------------------|---------|-------------------------------------------------------------------|
| service:build:jenkins:max-branches | `10`    | Maximum displayed branches. Branches are sorted by last activity. |
| service:build:jenkins:user         |         | Jenkins' username.                                                |
| service:build:jenkins:api-token    |         | Jenkins' token. This parameter is encrypted in database.          |
| service:build:jenkins:job          |         | Linked job identifier.                                            |
| service:build:jenkins:url          |         | Jenkins base URL. For sample `http://localhost:9190`.             |
