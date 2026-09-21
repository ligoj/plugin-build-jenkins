/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * A Jenkins folder to create with its credentials and nested folders. Unknown properties, such as the roles of the
 * provisioning files sharing this format, are ignored.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JenkinsFolder {

	/**
	 * Plain folder.
	 */
	public static final String MODE_FOLDER = "com.cloudbees.hudson.plugins.folder.Folder";

	/**
	 * Organization folder.
	 */
	public static final String MODE_ORGANIZATION = "jenkins.branch.OrganizationFolder";

	/**
	 * Folder name. Ignored for the root folder, named by the subscription.
	 */
	private String name;

	/**
	 * Optional description.
	 */
	private String description;

	/**
	 * Folder type: {@link #MODE_FOLDER} by default, or {@link #MODE_ORGANIZATION}.
	 */
	private String mode;

	/**
	 * Credentials stored in this folder.
	 */
	private List<JenkinsCredential> credentials;

	/**
	 * Nested folders.
	 */
	private List<JenkinsFolder> folders;
}
