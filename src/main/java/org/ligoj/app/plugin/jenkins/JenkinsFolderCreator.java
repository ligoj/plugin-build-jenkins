/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.text.StringEscapeUtils;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import jakarta.ws.rs.HttpMethod;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Creates a tree of Jenkins folders with their credentials, from a JSON definition. Existing folders are kept, so a
 * creation can be replayed; credentials are always submitted.
 */
@Slf4j
class JenkinsFolderCreator {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String baseUrl;
	private final CurlProcessor curl;

	/**
	 * @param baseUrl Jenkins base URL.
	 * @param curl    Authenticated processor.
	 */
	JenkinsFolderCreator(final String baseUrl, final CurlProcessor curl) {
		this.baseUrl = Strings.CS.appendIfMissing(baseUrl, "/");
		this.curl = curl;
	}

	/**
	 * Parse a folder definition.
	 *
	 * @param json The JSON definition of the root folder.
	 * @return The parsed definition.
	 */
	static JenkinsFolder parse(final String json) {
		try {
			final var folder = MAPPER.readValue(json, JenkinsFolder.class);
			validate(folder, true);
			return folder;
		} catch (final JacksonException e) {
			log.info("Invalid Jenkins folder definition: {}", e.getOriginalMessage());
			throw new ValidationJsonException(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-json");
		}
	}

	private static void validate(final JenkinsFolder folder, final boolean root) {
		if (!root && StringUtils.isBlank(folder.getName())) {
			throw new ValidationJsonException(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-name");
		}
		if (folder.getMode() != null && !JenkinsFolder.MODE_FOLDER.equals(folder.getMode())
				&& !JenkinsFolder.MODE_ORGANIZATION.equals(folder.getMode())) {
			throw new ValidationJsonException(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-mode", folder.getMode());
		}
		CollectionUtils.emptyIfNull(folder.getCredentials()).forEach(c -> {
			if (StringUtils.isBlank(c.getId()) || StringUtils.isBlank(c.getStaplerClass())) {
				throw new ValidationJsonException(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-credential");
			}
		});
		CollectionUtils.emptyIfNull(folder.getFolders()).forEach(f -> validate(f, false));
	}

	/**
	 * Create the root folder at the given path, then its credentials and nested folders. Missing parent folders of the
	 * path are created empty.
	 *
	 * @param path       Path of the root folder, segments separated by <code>/</code>.
	 * @param definition The root folder definition.
	 */
	void create(final String path, final JenkinsFolder definition) {
		final var parents = new ArrayList<String>();
		final var segments = StringUtils.split(path, '/');
		for (var i = 0; i < segments.length - 1; i++) {
			createFolder(parents, segments[i], new JenkinsFolder());
			parents.add(segments[i]);
		}
		createRecursive(parents, segments[segments.length - 1], definition);
	}

	private void createRecursive(final List<String> parents, final String name, final JenkinsFolder definition) {
		createFolder(parents, name, definition);
		final var path = new ArrayList<>(parents);
		path.add(name);
		CollectionUtils.emptyIfNull(definition.getCredentials()).forEach(c -> createCredential(path, c));
		CollectionUtils.emptyIfNull(definition.getFolders()).forEach(f -> createRecursive(path, f.getName(), f));
	}

	private String toUrl(final List<String> path) {
		return baseUrl + path.stream().map(p -> "job/" + UriUtils.encode(p, StandardCharsets.UTF_8) + "/").collect(Collectors.joining());
	}

	private void createFolder(final List<String> parents, final String name, final JenkinsFolder definition) {
		final var path = new ArrayList<>(parents);
		path.add(name);
		if (curl.process(new CurlRequest(HttpMethod.GET, toUrl(path) + "api/json?tree=name", null))) {
			log.info("Jenkins folder {} already exists", String.join("/", path));
			return;
		}
		final var mode = StringUtils.defaultIfBlank(definition.getMode(), JenkinsFolder.MODE_FOLDER);
		final var configXml = "<" + mode + "><description>"
				+ StringEscapeUtils.escapeXml10(StringUtils.defaultString(definition.getDescription())) + "</description></" + mode + ">";
		final var request = new CurlRequest(HttpMethod.POST,
				toUrl(parents) + "createItem?name=" + UriUtils.encode(name, StandardCharsets.UTF_8), configXml, "Content-Type:application/xml");
		if (!curl.process(request)) {
			throw new BusinessException("Creating the Jenkins folder {} failed.", String.join("/", path));
		}
	}

	private void createCredential(final List<String> path, final JenkinsCredential credential) {
		final var content = new LinkedHashMap<String, Object>();
		content.put("id", credential.getId());
		content.put("description", StringUtils.defaultString(credential.getDescription()));
		content.put("stapler-class", credential.getStaplerClass());
		content.put("$class", credential.getStaplerClass());
		if (credential.getAttributes() != null) {
			// Keys starting with '$' are hints of the provisioning tools ('$redact'), not Jenkins attributes
			credential.getAttributes().forEach((k, v) -> {
				if (!k.startsWith("$")) {
					content.put(k, v);
				}
			});
		}
		final var payload = new LinkedHashMap<String, Object>();
		payload.put("", "0");
		payload.put("credentials", content);
		if (credential.getScope() != null) {
			payload.put("scope", credential.getScope());
		}
		final var request = new CurlRequest(HttpMethod.POST, toUrl(path) + "credentials/store/folder/domain/_/createCredentials",
				"json=" + UriUtils.encode(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8),
				"Content-Type:application/x-www-form-urlencoded");
		if (!curl.process(request)) {
			// The secret values are never logged
			throw new BusinessException("Creating the Jenkins credential {} in folder {} failed.", credential.getId(), String.join("/", path));
		}
	}
}
