/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
define({
	'root': {
		'service:build:jenkins:job': 'Job',
		'service:build:jenkins:job': 'Job name. You type name or display name to search it',
		'service:build:jenkins:url': 'URL',
		'service:build:jenkins:user': 'User',
		'service:build:jenkins:api-token': 'API Token',
		'service:build:jenkins:build': 'Build',
		'service:build:jenkins:status-blue': 'Success',
		'service:build:jenkins:status-yellow': 'Unstable',
		'service:build:jenkins:status-disabled': 'Unknown',
		'service:build:jenkins:status-red': 'Failure',
		'service:build:jenkins:building': 'Building',
		'service:build:jenkins:template-job': 'Template job',
		'service:build:jenkins:template-job-description': 'Name of job used as a template to create the new job',
		'jenkins-build-job-success': 'Launching the job {{this}} succeed',
		'error': {
			'jenkins-job': 'Job not found',
			'jenkins-connection': 'Unreachable server',
			'jenkins-login': 'Authentication failed',
			'jenkins-rights': 'No right to read jobs'
		},
		'validation-job-name' : 'Must start with {{this}}-, contain only lower case characters, without special characters'
	},
	'fr': true
});
