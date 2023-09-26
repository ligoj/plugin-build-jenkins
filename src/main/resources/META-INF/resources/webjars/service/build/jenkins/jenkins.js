/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
define(function () {
	var current = {

		/**
		 * Job status to color class
		 * @type {Object} {String} to {String}
		 */
		jobStatusColor: {
			blue: 'text-success',
			red: 'text-danger',
			disabled: 'text-muted',
			yellow: 'text-warning'
		},
		/**
		 * Job status to style for not building mode
		 * @type {Object} {String} to {String}
		 */
		jobStatusTypo: {
			blue: 'fas fa-circle',
			red: 'fas fa-exclamation-circle',
			disabled: 'fas fa-ban',
			yellow: 'fas fa-exclamation-triangle'
		},

		initialize: function () {
			current.$super('$view').off('click.service-build-jenkins-build').on('click.service-build-jenkins-build', '.service-build-jenkins-build', current.serviceBuildJenkinsBuild);
		},

		/**
		 * Render Jenkins project name.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:build:jenkins:job');
		},

		/**
		 * Render Build Jenkins data.
		 */
		renderFeatures: function (subscription) {
		    const encodedJob = (subscription.parameters['service:build:jenkins:job']||'').split('/').map(encodeURIComponent).join('/');
			return `
			    ${current.$super('renderServiceLink')('home',
			        `${subscription.parameters['service:build:jenkins:url'].replace(/\/$/,'')}/job/${encodedJob}`,
			        'service:build:jenkins:job', undefined, ' target="_blank"')}
			    <button type="button" class="service-build-jenkins-build btn-link hidden">
                    <i class="fas fa-play" data-toggle="tooltip" title="${current.$messages['service:build:jenkins:build']}"></i>
                </button>
                ${current.$super('renderServiceHelpLink')(subscription.parameters, 'service:build:help')}
            `;
		},

		/**
		 * Render Jenkins details : name and display name.
		 */
		renderDetailsKey: function (subscription) {
			return current.$super('generateCarousel')(subscription, [
				['service:build:jenkins:job', current.renderKey(subscription)],
				['name', subscription.data.job.name || subscription.parameters['service:build:jenkins:job']]
			], 1);
		},


        toStatusHtml: function(subscription, job) {
 			let title = (current.$messages['service:build:jenkins:status-' + job.status] || job.status) + (job.building ? ' (' + current.$messages['service:build:jenkins:building'] + ')' : '');
 			let clazz = (current.jobStatusColor[job.status] || 'text-muted') + ' fa-fw ' + (job.building ? 'fas fa-sync-alt fa-spin' : current.jobStatusTypo[job.status] || 'fas fa-circle');
 			return `<div class="status-wrapper"><i data-toggle="tooltip" title="${title}" class="status ${clazz}"></i></div>`;
        },

		/**
		 * Display the status of the job, including the building state
		 */
		renderDetailsFeatures: function (subscription, _$tr ,$feature) {
            let job = subscription.data.job;
            const branches = subscription.data.job?.jobs;
			if (branches?.length) {
		        const encodedJob = job.id.split('/').map(encodeURIComponent).join('/job/');
			    const baseUrl = `${subscription.parameters['service:build:jenkins:url'].replace(/\/$/,'')}/job/${encodedJob}`;
			    $feature.addClass('has-branches');

                // Multi-branch mode
                const branchesHtml = branches.map(b=> {
                     let additionalPath = '';
                     const branchName = (typeof b.name === 'string' && b.name !== b.id) ? ` (${b.name})`:''
                     let tooltip=`${b.pullRequestBranch?'PullRequest':'Branch'}<br>${current.$messages.name}: ${b.id}${branchName}`
                     if (b.pullRequestBranch) {
                         additionalPath=`/view/change-requests/job/${encodeURIComponent(b.name)}/`;
                     } else {
                         additionalPath=`/job/${encodeURIComponent(b.name)}/`;
                     }
                     const branchLink = current.$super('renderServiceLink')( (b.pullRequestBranch?'hashtag':'code-branch') + ' fa-fw', `${baseUrl}${additionalPath}`, tooltip, b.id, ' target=\'_blank\'', 'link')
                     return `
                     <div class="branch">
                        <button type="button" class="service-build-jenkins-build btn-link">
                            <i class="fas fa-play" data-toggle="tooltip" title="${current.$messages['service:build:jenkins:build']}"></i>
                        </button>
                        ${branchLink}
                        ${current.toStatusHtml(subscription, job)}
                     </div>`;
                }).join(' ');
                return `<div class="branches">${branchesHtml}</div>`;
            } else {
                $feature.find('.service-build-jenkins-build').removeClass('hidden');
            }
            // Single mode
            return current.toStatusHtml(subscription, job);
		},

		configureSubscriptionParameters: function (configuration) {
			if (configuration.mode === 'create') {
				current.$super('registerXServiceSelect2')(configuration, 'service:build:jenkins:template-job', 'service/build/jenkins/template/');
				configuration.validators['service:build:jenkins:job'] = current.validateJobCreateMode;
			} else {
				current.$super('registerXServiceSelect2')(configuration, 'service:build:jenkins:job', 'service/build/jenkins/');
			}
		},

		/**
		 * Live validation of job name.
		 */
		validateJobCreateMode: function () {
			validationManager.reset(_('service:build:jenkins:job'));
			var $input = _('service:build:jenkins:job');
			var jobName = $input.val();
			$input.closest('.form-group').find('.form-control-feedback').remove().end().addClass('has-feedback');
			var pkey = current.$super('model').pkey;
			if(jobName.match('^(?:'+ pkey + '|' + pkey + '-[a-z0-9]*)$') === null) {
				validationManager.addError($input, {
					rule: 'validation-job-name',
					parameters: current.$super('model').pkey
				}, 'job', true);
				return false;
			}
			// Live validation to check the group does not exists
			validationManager.addMessage($input, null, [], null, 'fas fa-sync-alt fa-spin');
			$.ajax({
				dataType: 'json',
				url: REST_PATH + 'service/build/jenkins/' + current.$super('getSelectedNode')() + '/job/' + jobName,
				type: 'GET',
				global: false,
				success: function () {
					// Existing project
					validationManager.addError(_('service:build:jenkins:job'), {
						rule: 'already-exist',
						parameters: [current.$messages['service:build:jenkins:job'], jobName]
					}, 'job', true);
				},
				error: function() {
					// Succeed, not existing project
					validationManager.addSuccess(_('service:build:jenkins:job'), [], null, true);
				}
			});

			// For now return true for the immediate validation system, even if the Ajax call may fail
			return true;
		},

		/**
		 * Launch the jenkins's job for the associated subscription's id
		 */
		serviceBuildJenkinsBuild: function () {
			const $button = $(this);
			const subscription = $button.closest('tr').attr('data-id');
			const job = current.$super('subscriptions').fnGetData($button.closest('tr')[0]);
			$button.attr('disabled', 'disabled').find('.fa').addClass('faa-flash animated');
			$.ajax({
				dataType: 'json',
				url: REST_PATH + 'service/build/jenkins/build/' + subscription,
				type: 'POST',
				success: () => notifyManager.notify(Handlebars.compile(current.$messages['jenkins-build-job-success'])(job.parameters?.['service:build:jenkins:job'] || subscription)),
				complete: () => $button.removeAttr('disabled').find('.fa').removeClass('faa-flash animated'),
			});
		}
	};
	return current;
});