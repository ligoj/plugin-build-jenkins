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
