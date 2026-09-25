/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.curl.HttpResponseCallback;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Response callback for probes whose failure is an expected outcome (does this folder exist? is this store
 * available?): a non 2xx answer is reported to the caller as {@code false} without dumping the response body
 * (typically a whole Jenkins HTML error page) in the log.
 */
@Slf4j
public class QuietHttpResponseCallback implements HttpResponseCallback {

	@Override
	public boolean onResponse(final CurlRequest request, final ClassicHttpResponse response) throws IOException {
		final var accepted = response.getCode() <= HttpServletResponse.SC_NO_CONTENT;
		log.debug("{} {}", response.getCode(), request.getUrl());
		final var entity = response.getEntity();
		if (entity != null) {
			try {
				if (accepted && request.isSaveResponse()) {
					request.setResponse(EntityUtils.toString(entity, StandardCharsets.UTF_8));
				}
			} catch (final ParseException pe) {
				throw new IOException("Unable to parse the response", pe);
			} finally {
				entity.getContent().close();
			}
		}
		return accepted;
	}
}
