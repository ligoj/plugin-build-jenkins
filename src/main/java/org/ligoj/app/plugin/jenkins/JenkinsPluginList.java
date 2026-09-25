/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Answer of {@code pluginManager/api/json?depth=1&tree=plugins[shortName,active]}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JenkinsPluginList {

	private List<Plugin> plugins = new ArrayList<>();

	/**
	 * One installed plug-in.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Plugin {
		private String shortName;
		private boolean active;
	}
}
