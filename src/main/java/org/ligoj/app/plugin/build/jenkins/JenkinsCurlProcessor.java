/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.curl.HttpResponseCallback;
import org.ligoj.bootstrap.core.curl.SessionAuthCurlProcessor;

import java.util.Map;

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
		this(parameters, CurlProcessor.DEFAULT_CALLBACK);
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
