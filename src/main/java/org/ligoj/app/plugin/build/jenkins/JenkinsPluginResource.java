/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.Format;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.iam.IamProvider;
import org.ligoj.app.iam.UserOrg;
import org.ligoj.app.model.Project;
import org.ligoj.app.plugin.build.BuildResource;
import org.ligoj.app.plugin.build.BuildServicePlugin;
import org.ligoj.app.resource.NormalizeFormat;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.plugin.XmlUtils;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.curl.HeaderHttpResponseCallback;
import org.ligoj.bootstrap.core.curl.OnlyRedirectHttpResponseCallback;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Jenkins resource.
 */
@Path(JenkinsPluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
public class JenkinsPluginResource extends AbstractToolPluginResource implements BuildServicePlugin {

	/**
	 * Plug-in key.
	 */
	public static final String URL = BuildResource.SERVICE_URL + "/jenkins";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * Jenkins user name able to connect to instance.
	 */
	public static final String PARAMETER_USER = KEY + ":user";

	/**
	 * Jenkins user api-token able to connect to instance.
	 */
	public static final String PARAMETER_TOKEN = KEY + ":api-token";

	/**
	 * Jenkins job's name.
	 */
	public static final String PARAMETER_JOB = KEY + ":job";

	/**
	 * Jenkins job's name.
	 */
	public static final String PARAMETER_TEMPLATE_JOB = KEY + ":template-job";

	/**
	 * Web site URL
	 */
	public static final String PARAMETER_URL = KEY + ":url";

	/**
	 * Jenkins version callback to extract the header.
	 */
	private static final HeaderHttpResponseCallback VERSION_CALLBACK = new HeaderHttpResponseCallback("x-jenkins");

	/**
	 * Public server URL used to fetch the last available version of the product.
	 */
	@Value("${service-build-jenkins-server:http://mirrors.jenkins-ci.org}")
	private String publicServer;

	@Autowired
	protected IamProvider[] iamProvider;

	@Autowired
	protected XmlUtils xml;

	/**
	 * Used to launch the job for the subscription.
	 *
	 * @param subscription the subscription to use to locate the Jenkins instance.
	 */
	@POST
	@Path("build/{subscription:\\d+}")
	public void build(@PathParam("subscription") final int subscription) {
		final Map<String, String> parameters = subscriptionResource.getParameters(subscription);

		// Check the instance is available
		validateAdminAccess(parameters);
		if (!build(parameters, "build") && !build(parameters, "buildWithParameters")) {
			throw new BusinessException("Launching the job for the subscription {} failed.", subscription);
		}
	}

	/**
	 * Launch the job with the URL.
	 *
	 * @param parameters Parameters used to define the job
	 * @param url        URL added to the jenkins's URL to launch the job (can be build or buildWithParameters)
	 * @return The result of the processing.
	 */
	protected boolean build(final Map<String, String> parameters, final String url) {
		final CurlProcessor processor = new JenkinsCurlProcessor(parameters);
		try {
			final String jenkinsBaseUrl = parameters.get(PARAMETER_URL);
			final String jobName = parameters.get(PARAMETER_JOB);
			return processor.process(new CurlRequest("POST", jenkinsBaseUrl + "/job/" + jobName + "/" + url, null));
		} finally {
			processor.close();
		}
	}

	@Override
	public boolean checkStatus(final Map<String, String> parameters) {
		// Status is UP <=> Administration access is UP
		validateAdminAccess(parameters);
		return true;
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final Map<String, String> parameters)
			throws MalformedURLException, URISyntaxException {
		final SubscriptionStatusWithData nodeStatusWithData = new SubscriptionStatusWithData();
		nodeStatusWithData.put("job", validateJob(parameters));
		return nodeStatusWithData;
	}

	@Override
	public void create(final int subscription) throws IOException, URISyntaxException {
		final Map<String, String> parameters = subscriptionResource.getParameters(subscription);
		// Validate the node settings
		validateAdminAccess(parameters);

		// Get Template configuration
		final String templateJob = parameters.get(PARAMETER_TEMPLATE_JOB);
		final String templateConfigXml = getResource(parameters, "job/" + encode(templateJob) + "/config.xml");

		// update template
		final Project project = subscriptionRepository.findOneExpected(subscription).getProject();
		final UserOrg teamLeader = iamProvider[0].getConfiguration().getUserRepository()
				.findById(project.getTeamLeader());
		final String configXml = templateConfigXml
				.replaceFirst("<disabled>true</disabled>", "<disabled>false</disabled>")
				.replaceAll("ligoj-saas", project.getPkey())
				.replaceAll("someone@sample.org", teamLeader.getMails().get(0))
				.replaceFirst("(<displayName>).*?(</displayName>)", "$1" + project.getName() + "$2")
				.replaceFirst("(<description>).*?(</description>)", "$1" + project.getDescription() + "$2");

		// create new job
		final String job = parameters.get(PARAMETER_JOB);
		final String jenkinsBaseUrl = parameters.get(PARAMETER_URL);
		final CurlRequest curlRequest = new CurlRequest(HttpMethod.POST,
				jenkinsBaseUrl + "/createItem?name=" + encode(job), configXml, "Content-Type:application/xml");
		try (CurlProcessor curl = new JenkinsCurlProcessor(parameters)) {
			if (!curl.process(curlRequest)) {
				throw new BusinessException("Creating the job for the subscription {} failed.", subscription);
			}
		}
	}

	@Override
	public void delete(final int subscription, final boolean deleteRemoteData)
			throws MalformedURLException, URISyntaxException {
		if (deleteRemoteData) {
			final Map<String, String> parameters = subscriptionResource.getParameters(subscription);
			// Validate the node settings
			validateAdminAccess(parameters);

			// delete the job
			final String job = parameters.get(PARAMETER_JOB);
			final String jenkinsBaseUrl = parameters.get(PARAMETER_URL);
			final CurlRequest curlRequest = new CurlRequest(HttpMethod.POST,
					jenkinsBaseUrl + "/job/" + encode(job) + "/doDelete", StringUtils.EMPTY);
			try (CurlProcessor curl = new JenkinsCurlProcessor(parameters, new OnlyRedirectHttpResponseCallback())) {
				if (!curl.process(curlRequest)) {
					throw new BusinessException("Deleting the job for the subscription {} failed.", subscription);
				}
			}
		}
	}

	private String encode(final String job) throws MalformedURLException, URISyntaxException {
		return new URI("http", job, "").toURL().getPath();
	}

	/**
	 * Search the Jenkin's jobs matching to the given criteria. Name, display name and description are considered.
	 *
	 * @param node     the node to be tested with given parameters.
	 * @param criteria the search criteria.
	 * @return job names matching the criteria.
	 * @throws SAXException                 When Jenkins project cannot be validated.
	 * @throws IOException                  When Jenkins project cannot be read.
	 * @throws ParserConfigurationException When Jenkins project cannot be parsed.
	 */
	@GET
	@Path("{node}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<Job> findAllByName(@PathParam("node") final String node, @PathParam("criteria") final String criteria)
			throws SAXException, IOException, ParserConfigurationException {
		return findAllByName(node, criteria, null);
	}

	/**
	 * Search the Jenkin's jobs matching to the given criteria. Name, display name and description are considered.
	 *
	 * @param node     the node to be tested with given parameters.
	 * @param criteria the search criteria.
	 * @param view     The optional view URL.
	 * @return job names matching the criteria.
	 */
	private List<Job> findAllByName(final String node, final String criteria, final String view)
			throws SAXException, IOException, ParserConfigurationException {

		// Prepare the context, an ordered set of jobs
		final Format format = new NormalizeFormat();
		final String formatCriteria = format.format(criteria);
		final Map<String, String> parameters = pvResource.getNodeParameters(node);

		// Get the jobs and parse them
		final String url = StringUtils.trimToEmpty(view) + "api/xml?tree=jobs[name,displayName,description,color]";
		final String jobsAsXml = StringUtils.defaultString(getResource(parameters, url), "<a/>");
		final InputStream jobsAsInput = IOUtils.toInputStream(jobsAsXml, StandardCharsets.UTF_8);
		final Element hudson = (Element) xml.parse(jobsAsInput).getFirstChild();
		final Map<String, Job> result = new TreeMap<>();
		for (final Element jobNode : DomUtils.getChildElementsByTagName(hudson, "job")) {

			// Extract string data from this job
			final String name = StringUtils.trimToEmpty(DomUtils.getChildElementValueByTagName(jobNode, "name"));
			final String displayName = StringUtils
					.trimToEmpty(DomUtils.getChildElementValueByTagName(jobNode, "displayName"));
			final String description = StringUtils
					.trimToEmpty(DomUtils.getChildElementValueByTagName(jobNode, "description"));

			// Check the values of this job
			if (format.format(name).contains(formatCriteria) || format.format(displayName).contains(formatCriteria)
					|| format.format(description).contains(formatCriteria)) {

				// Retrieve description and display name
				final Job job = new Job();
				job.setName(StringUtils.trimToNull(displayName));
				job.setDescription(StringUtils.trimToNull(description));
				job.setId(name);
				job.setStatus(toStatus(DomUtils.getChildElementValueByTagName(jobNode, "color")));
				result.put(format.format(ObjectUtils.defaultIfNull(job.getName(), job.getId())), job);
			}
		}
		return new ArrayList<>(result.values());
	}

	/**
	 * Search the Jenkin's template jobs matching to the given criteria. Name, display name and description are
	 * considered.
	 *
	 * @param node     the node to be tested with given parameters.
	 * @param criteria the search criteria.
	 * @return template job names matching the criteria.
	 * @throws SAXException                 When Jenkins project cannot be validated.
	 * @throws IOException                  When Jenkins project cannot be read.
	 * @throws ParserConfigurationException When Jenkins project cannot be parsed.
	 */
	@GET
	@Path("template/{node}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<Job> findAllTemplateByName(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria)
			throws SAXException, IOException, ParserConfigurationException {
		return findAllByName(node, criteria, "view/Templates/");
	}

	/**
	 * Get Jenkins job name by id.
	 *
	 * @param node the node to be tested with given parameters.
	 * @param id   The job name/identifier.
	 * @return job names matching the criteria.
	 * @throws MalformedURLException When the Jenkins base URL is malformed.
	 * @throws URISyntaxException    When the built Jenkins base URL is malformed.
	 */
	@GET
	@Path("{node}/job/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Job findById(@PathParam("node") final String node, @PathParam("id") final String id)
			throws MalformedURLException, URISyntaxException {
		// Prepare the context, an ordered set of jobs
		final Map<String, String> parameters = pvResource.getNodeParameters(node);
		parameters.put(PARAMETER_JOB, id);
		return validateJob(parameters);
	}

	@Override
	public String getKey() {
		return KEY;
	}

	@Override
	public String getLastVersion() {
		// Get the download index from the default repository
		return getLastVersion(publicServer + "/war/");
	}

	/**
	 * Return the last version available for Jenkins for the given repository URL.
	 * 
	 * @param repo The path of the index containing the available versions.
	 * @return The last Jenkins version.
	 */
	protected String getLastVersion(final String repo) {
		// Get the download index
		try (CurlProcessor curl = new CurlProcessor()) {
			final String downloadPage = ObjectUtils.defaultIfNull(curl.get(repo), "");

			// Find the last download link
			final Matcher matcher = Pattern.compile("href=\"([\\d.]+)/\"").matcher(downloadPage);
			String lastVersion = null;
			while (matcher.find()) {
				lastVersion = matcher.group(1);
			}

			// Return the last read version
			return lastVersion;
		}
	}

	/**
	 * Return the node text without using document parser.
	 *
	 * @param xmlContent XML content.
	 * @param node       the node name.
	 * @return trimmed node text or <code>null</code>.
	 */
	private String getNodeText(final String xmlContent, final String node) {
		final Matcher matcher = Pattern.compile("<" + node + ">([^<]*)</" + node + ">")
				.matcher(ObjectUtils.defaultIfNull(xmlContent, ""));
		if (matcher.find()) {
			return StringUtils.trimToNull(matcher.group(1));
		}
		return null;
	}

	/**
	 * Return a Jenkins's resource. Return <code>null</code> when the resource is not found.
	 */
	private String getResource(final CurlProcessor processor, final String url, final String resource) {
		// Get the resource using the preempted authentication
		return processor.get(StringUtils.appendIfMissing(url, "/") + resource);
	}

	/**
	 * Return a Jenkins's resource. Return <code>null</code> when the resource is not found.
	 * 
	 * @param parameters The subscription parameters.
	 * @param resource   The requested Jenkins resource.
	 * @return The Jenkins resource's content.
	 */
	protected String getResource(final Map<String, String> parameters, final String resource) {
		return getResource(new JenkinsCurlProcessor(parameters), parameters.get(PARAMETER_URL), resource);
	}

	@Override
	public String getVersion(final Map<String, String> parameters) {
		// Check the user has enough rights to get the master configuration and
		// get the master configuration and
		return getResource(new JenkinsCurlProcessor(parameters, VERSION_CALLBACK), parameters.get(PARAMETER_URL),
				"api/json?tree=numExecutors");
	}

	@Override
	public void link(final int subscription) throws MalformedURLException, URISyntaxException {
		final Map<String, String> parameters = subscriptionResource.getParameters(subscription);

		// Validate the node settings
		validateAdminAccess(parameters);

		// Validate the job settings
		validateJob(parameters);
	}

	/**
	 * Return the color from the raw color of the job.
	 *
	 * @param color Raw color node from the job status.
	 * @return The color without 'anime' flag.
	 */
	private String toStatus(final String color) {
		return StringUtils.removeEnd(StringUtils.defaultString(color, "disabled"), "_anime");
	}

	/**
	 * Validate the basic REST connectivity to Jenkins.
	 *
	 * @param parameters the server parameters.
	 * @return the detected Jenkins version.
	 */
	protected String validateAdminAccess(final Map<String, String> parameters) {
		CurlProcessor.validateAndClose(StringUtils.appendIfMissing(parameters.get(PARAMETER_URL), "/") + "login",
				PARAMETER_URL, "jenkins-connection");

		// Check the user can log-in to Jenkins with the preempted
		// authentication processor
		if (getResource(parameters, "api/xml") == null) {
			throw new ValidationJsonException(PARAMETER_USER, "jenkins-login");
		}

		// Check the user has enough rights to get the master configuration and
		// return the version
		final String version = getVersion(parameters);
		if (version == null) {
			throw new ValidationJsonException(PARAMETER_USER, "jenkins-rights");
		}
		return version;
	}

	/**
	 * Validate the administration connectivity.
	 *
	 * @param parameters the administration parameters.
	 * @return job name.
	 * @throws MalformedURLException When the Jenkins base URL is malformed.
	 * @throws URISyntaxException    When the built Jenkins base URL is malformed.
	 */
	protected Job validateJob(final Map<String, String> parameters) throws MalformedURLException, URISyntaxException {
		// Get job's configuration
		final String job = parameters.get(PARAMETER_JOB);
		final String jobXml = getResource(parameters,
				"api/xml?depth=1&tree=jobs[displayName,name,color]&xpath=hudson/job[name='" + encode(job)
						+ "']&wrapper=hudson");
		if (jobXml == null || "<hudson/>".equals(jobXml)) {
			// Invalid couple PKEY and id
			throw new ValidationJsonException(PARAMETER_JOB, "jenkins-job", job);
		}

		// Retrieve description, status and display name
		final Job result = new Job();
		result.setName(getNodeText(jobXml, "displayName"));
		result.setDescription(getNodeText(jobXml, "description"));
		final String statusNode = StringUtils.defaultString(getNodeText(jobXml, "color"), "disabled");
		result.setStatus(toStatus(statusNode));
		result.setBuilding(statusNode.endsWith("_anime"));
		result.setId(job);
		return result;
	}

}
