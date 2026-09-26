# :link: Ligoj Jenkins CI plugin ![Maven Central](https://img.shields.io/maven-central/v/org.ligoj.plugin/plugin-build-jenkins)

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
| service:build:jenkins:template-job | | Create mode: name (or path) of an existing job copied to create the project job, see [Template job](#template-job). |
| service:build:jenkins:template-folder | | Alternative to the template job (create mode): JSON definition of the folder tree to create, with descriptions, nested folders and credentials. Credentials require the Jenkins **credentials** plug-in (the Folders plug-in then exposes a store on each folder) plus the plug-in of each credential type: `plain-credentials` (secret text), `ssh-credentials` (SSH keys), `aws-credentials` (AWS keys). These plug-ins are checked against the Jenkins plug-in list before any folder is created; a credential of another plug-in declares it with `"plugin": "<short name>"`. |
| service:build:jenkins:job          |         | Linked job identifier (required to link, and to create from a template job). In folder mode (`template-folder`), the optional path of the root folder created by the subscription, e.g. `Admin` or `team/Admin`; when empty, the root is the definition `name`, or its single top-level folder, and is stored as the job. |
| service:build:jenkins:url          |         | Jenkins base URL. For sample `http://localhost:9190`.             |

# Create mode

A subscription in create mode creates something on Jenkins, then links the project to it. Two exclusive ways, chosen by
the parameters: a template job (`service:build:jenkins:template-job`) or a folder definition
(`service:build:jenkins:template-folder`). Deleting the subscription with the "remote data" option deletes the created
job, or the created root folder with its whole tree.

## Template job

The job named by `service:build:jenkins:job` is created from the `config.xml` of the template job, after these
substitutions:

| In the template            | Replaced by                                        |
|----------------------------|----------------------------------------------------|
| `<disabled>true</disabled>` | `<disabled>false</disabled>` (the template stays disabled, the copy is enabled) |
| `ligoj-saas`               | the project key, everywhere (URLs, branches, ...)  |
| `someone@sample.org`       | the mail of the project's team leader              |
| `<displayName>...</displayName>` | the project name                             |
| `<description>...</description>` | the project description                      |

Sample template job (`config.xml` of a disabled freestyle job named `template-project`):

```xml
<?xml version='1.1' encoding='UTF-8'?>
<project>
  <displayName>Template project</displayName>
  <description>Replaced by the project description</description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <scm class="hudson.plugins.git.GitSCM" plugin="git">
    <configVersion>2</configVersion>
    <userRemoteConfigs>
      <hudson.plugins.git.UserRemoteConfig>
        <url>https://git.example.org/ligoj-saas/ligoj-saas.git</url>
        <credentialsId>git-deploy</credentialsId>
      </hudson.plugins.git.UserRemoteConfig>
    </userRemoteConfigs>
    <branches>
      <hudson.plugins.git.BranchSpec>
        <name>*/main</name>
      </hudson.plugins.git.BranchSpec>
    </branches>
  </scm>
  <canRoam>true</canRoam>
  <disabled>true</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers/>
  <concurrentBuild>false</concurrentBuild>
  <builders>
    <hudson.tasks.Shell>
      <command>mvn -B verify</command>
    </hudson.tasks.Shell>
  </builders>
  <publishers>
    <hudson.tasks.Mailer plugin="mailer">
      <recipients>someone@sample.org</recipients>
      <dontNotifyEveryUnstableBuild>false</dontNotifyEveryUnstableBuild>
      <sendToIndividuals>false</sendToIndividuals>
    </hudson.tasks.Mailer>
  </publishers>
  <buildWrappers/>
</project>
```

For the project `demo-2` led by `jane@corp.org`, the created job `demo-2` clones `https://git.example.org/demo-2/demo-2.git`,
mails `jane@corp.org` and is enabled.

## Folder mode

`service:build:jenkins:template-folder` holds a JSON definition of the folder tree to create, with descriptions,
credentials and nested folders. `service:build:jenkins:job` names the root folder (path allowed, e.g. `team/Admin`);
when empty, the root is the definition `name`, or its single top-level folder, and is stored as the job. Existing
folders are kept (the creation can be replayed), credentials are created in the store of the folder declaring them.

Every credential needs an `id` and a `stapler-class`; the `attributes` are the Jenkins fields of that class, copied
as is; `scope` is optional (`GLOBAL` by default). Keys starting with `$` (e.g. `$redact`, a hint for the tooling
managing the definition) are never sent to Jenkins, and secret values are never logged. The Jenkins plug-ins required
by the credentials are checked before anything is created: `credentials`, plus the plug-in of each class, inferred
from its package (`plain-credentials`, `ssh-credentials`, `aws-credentials`, `docker-commons`, ...) or declared with
`"plugin": "<short name>"`.

```json
{
  "folders": [
    {
      "name": "folder6",
      "description": "Folder6 description",
      "credentials": [
        {
          "id": "git-deploy",
          "description": "Git deploy account",
          "stapler-class": "com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl",
          "scope": "GLOBAL",
          "attributes": {
            "username": "deploy",
            "password": "s3cret-password",
            "$redact": "password"
          }
        },
        {
          "id": "sonar-token",
          "description": "SonarQube analysis token",
          "stapler-class": "org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl",
          "attributes": {
            "secret": "squ_0123456789abcdef",
            "$redact": "secret"
          }
        },
        {
          "id": "ssh-build",
          "description": "SSH key of the build agent",
          "stapler-class": "com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey",
          "attributes": {
            "username": "jenkins",
            "passphrase": "",
            "privateKeySource": {
              "stapler-class": "com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey$DirectEntryPrivateKeySource",
              "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----\n"
            },
            "$redact": "privateKeySource"
          }
        }
      ],
      "folders": [
        {
          "name": "folder6.1",
          "description": "Folder6.1 description",
          "credentials": [
            {
              "id": "aws-folder6-1",
              "description": "AWS account of folder6.1",
              "stapler-class": "com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl",
              "attributes": {
                "accessKey": "AKIAXXXXXXXXXXXXXXXX",
                "secretKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "$redact": "secretKey"
              }
            }
          ]
        },
        { "name": "folder6.2", "description": "Folder6.2 description" }
      ]
    }
  ]
}
```

With an empty job, this definition creates `folder6` (stored as the subscription job) with its three credentials,
then `folder6/folder6.1` with its AWS credential and `folder6/folder6.2`. A folder may also set
`"mode": "jenkins.branch.OrganizationFolder"` to create an organization folder instead of a plain one.
