// Jenkins-specific i18n for the build-jenkins tool plugin: subscribe-
// wizard parameter labels (from src/main/resources/csv/parameter.csv)
// plus the runtime labels the legacy nls bundle shipped. Flat keys to
// match the host's vue-i18n resolver.
export default {
  'service:build:jenkins': 'Jenkins',
  'service:build:jenkins:url': 'Jenkins base URL',
  'service:build:jenkins:user': 'User',
  'service:build:jenkins:api-token': 'API token',
  'service:build:jenkins:job': 'Job',
  'service:build:jenkins:template-job': 'Template job',
  'service:build:jenkins:template-job-description': 'Job copied to create the new one. Leave empty when a folder definition is given.',
  'service:build:jenkins:template-folder': 'Folder definition',
  'service:build:jenkins:template-folder-description': 'Alternative to the template job: JSON of the folder to create, with its description, credentials and nested folders. The folder is named by the job.',
  'service:build:jenkins:template-folder-invalid-json': 'The definition must be a JSON object',
  'service:build:jenkins:template-folder-invalid-name': 'Every nested folder needs a name',
  'service:build:jenkins:template-folder-invalid-credential': 'Every credential needs an id and a stapler-class',
  // Backend validation rules of the folder definition (rendered by the host error store as rule.<name>)
  'rule.jenkins-folder-json': 'The folder definition must be a JSON object',
  'rule.jenkins-folder-name': 'Every nested folder needs a name',
  'rule.jenkins-folder-mode': 'Unsupported folder type',
  'rule.jenkins-folder-credential': 'Every credential needs an id and a stapler-class',
  'service:build:jenkins:build': 'Build',
  'service:build:jenkins:building': 'Building',
  'service:build:jenkins:status': 'Status',
  'service:build:jenkins:status-blue': 'Success',
  'service:build:jenkins:status-red': 'Failure',
  'service:build:jenkins:status-yellow': 'Unstable',
  'service:build:jenkins:status-disabled': 'Unknown',
  'service:build:jenkins:branch': 'Branch',
  'service:build:jenkins:pull-request': 'Pull request',
  'service:build:jenkins:job-search': 'Search for an existing job…',
  'service:build:jenkins:template-job-search': 'Search for a template job…',
  'service:build:jenkins:job-invalid': 'The job name must match the project key (e.g. {pkey} or {pkey}-*).',
  'service:build:jenkins:job-exists': 'A job named "{name}" already exists.',
  'service:build:help': 'Help',
}
