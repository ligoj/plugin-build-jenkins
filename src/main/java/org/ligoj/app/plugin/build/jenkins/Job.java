/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

import lombok.Getter;
import lombok.Setter;
import org.ligoj.bootstrap.core.DescribedBean;

import java.util.List;

/**
 * Jenkins job definition.
 */
@Getter
@Setter
public class Job extends DescribedBean<String> {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	private String status;
	private boolean building;

	/**
	 * Optional sub-jobs, may be <code>null</code>.
	 */
	private List<Job> jobs;

	/**
	 * When <code>true</code>, this branch is related to a PR.
	 */
	private boolean pullRequestBranch;

	private Long lastBuild;
}
