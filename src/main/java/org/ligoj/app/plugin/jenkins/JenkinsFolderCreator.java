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
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
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
	 * Processor of the probes (folder existence, credential store availability): a failure is an expected answer,
	 * not logged as an error.
	 */
	private final CurlProcessor probe;

	/**
	 * @param baseUrl Jenkins base URL.
	 * @param curl    Authenticated processor.
	 */
	JenkinsFolderCreator(final String baseUrl, final CurlProcessor curl, final CurlProcessor probe) {
		this.baseUrl = Strings.CS.appendIfMissing(baseUrl, "/");
		this.curl = curl;
		this.probe = probe;
	}

	/**
	 * Jenkins plug-in (short name) providing the credential classes of a package prefix. Every credential also needs
	 * the "credentials" plug-in itself.
	 */
	private static final Map<String, String> CREDENTIAL_PLUGINS = new LinkedHashMap<>();

	static {
		CREDENTIAL_PLUGINS.put("com.cloudbees.plugins.credentials.", "credentials");
		CREDENTIAL_PLUGINS.put("org.jenkinsci.plugins.plaincredentials.", "plain-credentials");
		CREDENTIAL_PLUGINS.put("com.cloudbees.jenkins.plugins.sshcredentials.", "ssh-credentials");
		CREDENTIAL_PLUGINS.put("com.cloudbees.jenkins.plugins.awscredentials.", "aws-credentials");
		CREDENTIAL_PLUGINS.put("org.jenkinsci.plugins.docker.commons.credentials.", "docker-commons");
		CREDENTIAL_PLUGINS.put("org.jenkinsci.plugins.github_branch_source.", "github-branch-source");
		CREDENTIAL_PLUGINS.put("com.datapipe.jenkins.vault.", "hashicorp-vault-plugin");
		CREDENTIAL_PLUGINS.put("org.jenkinsci.plugins.kubernetes.credentials.", "kubernetes-credentials");
		CREDENTIAL_PLUGINS.put("com.microsoft.azure.util.", "azure-credentials");
	}

	/**
	 * The Jenkins plug-ins required by the credentials of a definition: "credentials" as soon as there is one, plus
	 * the plug-in of each credential class (explicit {@code plugin}, else inferred from the class package).
	 *
	 * @param definition The parsed definition.
	 * @return The required plug-in short names, ordered.
	 */
	static Set<String> requiredPlugins(final JenkinsFolder definition) {
		final var result = new LinkedHashSet<String>();
		collectPlugins(definition, result);
		return result;
	}

	private static void collectPlugins(final JenkinsFolder folder, final Set<String> result) {
		CollectionUtils.emptyIfNull(folder.getCredentials()).forEach(c -> {
			result.add("credentials");
			if (StringUtils.isNotBlank(c.getPlugin())) {
				result.add(c.getPlugin().trim());
			} else {
				CREDENTIAL_PLUGINS.entrySet().stream().filter(e -> c.getStaplerClass().startsWith(e.getKey()))
						.map(Map.Entry::getValue).findFirst().ifPresent(result::add);
			}
		});
		CollectionUtils.emptyIfNull(folder.getFolders()).forEach(f -> collectPlugins(f, result));
	}

	/**
	 * Check the plug-ins required by the credentials are installed and active in Jenkins, before creating anything.
	 * When the plug-in list cannot be read (older Jenkins, restricted token), the check is skipped with a warning:
	 * the credential store probe still catches a missing "credentials" plug-in later.
	 *
	 * @param definition The parsed definition.
	 */
	void checkPlugins(final JenkinsFolder definition) {
		final var required = requiredPlugins(definition);
		if (required.isEmpty()) {
			return;
		}
		final var request = new CurlRequest(HttpMethod.GET, baseUrl + "pluginManager/api/json?depth=1&tree=plugins[shortName,active]", null);
		request.setSaveResponse(true);
		if (!probe.process(request) || request.getResponse() == null) {
			log.warn("Unable to read the Jenkins plug-in list, the credential plug-ins {} are not checked", required);
			return;
		}
		final Set<String> installed;
		try {
			installed = MAPPER.readValue(request.getResponse(), JenkinsPluginList.class).getPlugins().stream()
					.filter(JenkinsPluginList.Plugin::isActive).map(JenkinsPluginList.Plugin::getShortName)
					.collect(Collectors.toSet());
		} catch (final JacksonException e) {
			log.warn("Unreadable Jenkins plug-in list, the credential plug-ins {} are not checked: {}", required, e.getOriginalMessage());
			return;
		}
		final var missing = required.stream().filter(pl -> !installed.contains(pl)).toList();
		if (!missing.isEmpty()) {
			log.info("Jenkins plug-ins {} required by the folder credentials are not installed", missing);
			throw new ValidationJsonException(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-plugin",
					"plugins", String.join(", ", missing));
		}
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
	 * Root folder of a definition when the subscription has no job: the root {@code name} when defined, otherwise
	 * the single top-level folder (which then becomes the definition). Several top-level folders without a root
	 * name cannot be created without a job naming their parent.
	 *
	 * @param definition The parsed definition.
	 * @return The root folder name and the definition to create there.
	 */
	static Map.Entry<String, JenkinsFolder> resolveRoot(final JenkinsFolder definition) {
		if (StringUtils.isNotBlank(definition.getName())) {
			return Map.entry(definition.getName().trim(), definition);
		}
		final var folders = CollectionUtils.emptyIfNull(definition.getFolders());
		if (folders.size() == 1) {
			final var root = folders.iterator().next();
			return Map.entry(root.getName().trim(), root);
		}
		throw new ValidationJsonException(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-root");
	}

	/**
	 * Create the root folder at the given path, then its credentials and nested folders. Missing parent folders of the
	 * path are created empty.
	 *
	 * @param path       Path of the root folder, segments separated by <code>/</code>.
	 * @param definition The root folder definition.
	 */
	void create(final String path, final JenkinsFolder definition) {
		checkPlugins(definition);
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
		final var credentials = CollectionUtils.emptyIfNull(definition.getCredentials());
		if (!credentials.isEmpty()) {
			checkCredentialStore(path);
			credentials.forEach(c -> createCredential(path, c));
		}
		CollectionUtils.emptyIfNull(definition.getFolders()).forEach(f -> createRecursive(path, f.getName(), f));
	}

	private String toUrl(final List<String> path) {
		return baseUrl + path.stream().map(p -> "job/" + UriUtils.encode(p, StandardCharsets.UTF_8) + "/").collect(Collectors.joining());
	}

	private void createFolder(final List<String> parents, final String name, final JenkinsFolder definition) {
		final var path = new ArrayList<>(parents);
		path.add(name);
		if (probe.process(new CurlRequest(HttpMethod.GET, toUrl(path) + "api/json?tree=name", null))) {
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

	/**
	 * Ensure the folder exposes a credential store: it exists only when the Jenkins "credentials" plug-in is
	 * installed (the Folders plug-in then registers its folder store). Without it, the creation would fail with a
	 * bare 404 that says nothing about the cause.
	 */
	private void checkCredentialStore(final List<String> path) {
		if (!probe.process(new CurlRequest(HttpMethod.GET, toUrl(path) + "credentials/store/folder/api/json?tree=id", null))) {
			throw new BusinessException("No credential store on the Jenkins folder {}: the 'credentials' plug-in (and the"
					+ " plug-ins of the credential types, e.g. 'plain-credentials', 'ssh-credentials') must be installed in Jenkins.",
					String.join("/", path));
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
