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
  'service:build:jenkins:last-build': 'Last build',
}
