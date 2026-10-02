/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * A project role of a folder (Role-based Authorization Strategy plug-in), keyed by the group it is assigned to in
 * the folder definition.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JenkinsRole {

	/**
	 * Jenkins permission identifiers, e.g. {@code hudson.model.Item.Build}. Exclusive with {@link #template}.
	 */
	private List<String> permissions;

	/**
	 * Name of a permission template of the Role-based Authorization Strategy plug-in providing the permissions of
	 * this role. The role then follows the template changes. Exclusive with {@link #permissions}.
	 */
	private String template;

	/**
	 * When not {@code false}, the role also covers the sub-folders and jobs of the folder.
	 */
	private Boolean recursive;
}
