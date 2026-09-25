/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import org.ligoj.bootstrap.core.curl.DefaultHttpResponseCallback;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Response callback of the Jenkins write requests: besides the 2xx answers, Jenkins acknowledges some form
 * submissions ({@code createCredentials}, ...) with a redirect to the created resource, which is a success too.
 */
public class JenkinsWriteCallback extends DefaultHttpResponseCallback {

	@Override
	protected boolean acceptStatus(final int status) {
		return super.acceptStatus(status) || status == HttpServletResponse.SC_MOVED_TEMPORARILY
				|| status == HttpServletResponse.SC_SEE_OTHER;
	}
}
