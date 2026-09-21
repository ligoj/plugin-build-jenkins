/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * A Jenkins credential created inside a folder. The type is the Jenkins implementation class, and the attributes are
 * the ones this class expects: <code>username</code> and <code>password</code> for
 * <code>UsernamePasswordCredentialsImpl</code>, <code>secret</code> for <code>StringCredentialsImpl</code>, ...
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JenkinsCredential {

	/**
	 * Credential identifier, unique within the folder.
	 */
	private String id;

	/**
	 * Optional description.
	 */
	private String description;

	/**
	 * Jenkins credential implementation class.
	 */
	@JsonProperty("stapler-class")
	private String staplerClass;

	/**
	 * Optional scope: <code>GLOBAL</code> or <code>SYSTEM</code>.
	 */
	private String scope;

	/**
	 * Attributes of the implementation class. Keys starting with <code>$</code> are tooling hints and are not sent.
	 */
	private Map<String, Object> attributes;
}
