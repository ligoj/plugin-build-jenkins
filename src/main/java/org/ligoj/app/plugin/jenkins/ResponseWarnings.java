/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cxf.message.Message;
import org.apache.cxf.phase.PhaseInterceptorChain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Non-blocking warnings of the current REST call, reported to the caller through the {@value #HEADER} response
 * header: one percent-encoded JSON value per warning, {@code {"code": "...", "parameters": {...}}}, localized by the
 * caller (the UI resolves {@code warning.<code>} in the plug-in bundle with the parameters). Outside a REST call
 * (batch, tests) the warning is only logged.
 */
@Slf4j
@UtilityClass
public class ResponseWarnings {

	/**
	 * Response header carrying the warnings.
	 */
	public static final String HEADER = "X-Ligoj-Warning";

	/**
	 * CXF message key of the servlet response ({@code AbstractHTTPDestination.HTTP_RESPONSE}).
	 */
	static final String HTTP_RESPONSE = "HTTP.RESPONSE";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Receiver of the warnings: the response of the current call by default, a collector in tests.
	 */
	@FunctionalInterface
	public interface Sink {
		/**
		 * Report a warning.
		 *
		 * @param code       The warning code, localized by the caller.
		 * @param parameters Named parameters of the message.
		 */
		void warn(String code, Map<String, String> parameters);
	}

	/**
	 * Report a warning to the caller of the current REST call.
	 *
	 * @param code       The warning code.
	 * @param parameters Named parameters of the message.
	 */
	public static void add(final String code, final Map<String, String> parameters) {
		add(PhaseInterceptorChain.getCurrentMessage(), code, parameters);
	}

	/**
	 * Named parameters from key/value pairs, in order.
	 *
	 * @param keyValues Alternated keys and values.
	 * @return The ordered map.
	 */
	public static Map<String, String> parameters(final String... keyValues) {
		final var map = new LinkedHashMap<String, String>();
		for (var i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	/**
	 * Report a warning through the response of the given CXF message. The header is added to the servlet response
	 * right away: during the resource invocation the CXF out message does not exist yet (it is built once the
	 * resource returns), so the protocol headers map used by the hooks is not available at that time.
	 *
	 * @param current    The current CXF message, may be {@code null} outside a REST call.
	 * @param code       The warning code.
	 * @param parameters Named parameters of the message.
	 * @return {@code true} when the warning has been attached to a response.
	 */
	public static boolean add(final Message current, final String code, final Map<String, String> parameters) {
		log.warn("Warning {} {}", code, parameters);
		final var response = current == null ? null : (HttpServletResponse) current.get(HTTP_RESPONSE);
		if (response == null || response.isCommitted()) {
			return false;
		}
		response.addHeader(HEADER, encode(code, parameters));
		return true;
	}

	/**
	 * Header value of a warning: percent-encoded JSON (spaces as {@code %20}, decodable by
	 * {@code decodeURIComponent}).
	 */
	static String encode(final String code, final Map<String, String> parameters) {
		final var payload = new LinkedHashMap<String, Object>();
		payload.put("code", code);
		payload.put("parameters", parameters == null ? Map.of() : parameters);
		try {
			return URLEncoder.encode(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8).replace("+", "%20");
		} catch (final JacksonException e) {
			throw new IllegalStateException("Unserializable warning " + code, e);
		}
	}
}
