/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

import java.util.Map;

import org.ligoj.app.resource.plugin.DefaultHttpResponseCallback;
import org.ligoj.app.resource.plugin.HttpResponseCallback;
import org.ligoj.app.resource.plugin.SessionAuthCurlProcessor;

/**
 * Jenkins processor.
 */
public class JenkinsCurlProcessor extends SessionAuthCurlProcessor {

	/**
	 * Constructor using parameters set.
	 * 
	 * @param parameters
	 *            the Jenkins parameters.
	 */
	public JenkinsCurlProcessor(final Map<String, String> parameters) {
		this(parameters, new DefaultHttpResponseCallback());
	}

	/**
	 * Constructor using parameters set and callback.
	 * 
	 * @param parameters
	 *            the Jenkins parameters.
	 * @param callback
	 *            Not <code>null</code> {@link HttpResponseCallback} used for each response.
	 */
	public JenkinsCurlProcessor(final Map<String, String> parameters, final HttpResponseCallback callback) {
		super(parameters.get(JenkinsPluginResource.PARAMETER_USER), parameters.get(JenkinsPluginResource.PARAMETER_TOKEN), callback);
	}

}
