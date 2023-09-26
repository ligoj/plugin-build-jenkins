/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.stream.Streams;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.iam.IamProvider;
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
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Jenkins resource.
 */
@Path(JenkinsPluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
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
	 * Default maximum returned branches.
	 */
	public static final int DEFAULT_MAX_BRANCHES = 10;

	/**
	 * Jenkins username able to connect to instance.
	 */
	public static final String PARAMETER_USER = KEY + ":user";

	/**
	 * Jenkins' user api-token able to connect to instance.
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
	 * Maximum returned branches.
	 */
	public static final String PARAMETER_MAX_BRANCHES = KEY + ":max-branches";

	/**
	 * Jenkins version callback to extract the header.
	 */
	private static final HeaderHttpResponseCallback VERSION_CALLBACK = new HeaderHttpResponseCallback("x-jenkins");

	/**
	 * Maximum depth for job searches.
	 */
	public static final String PARAMETER_MAX_DEPTH = KEY + ":max-depth";

	/**
	 * Maximum Jenkins depth.
	 */
	private static final int MAX_DEPTH = 5;

	/**
	 * Marker of recursive query text.
	 */
	private static final String XML_RECURRING_MARKER = "__XML_RECURRING__";

	/**
	 * Template query for Jenkins XML tree.
	 */
	private static final String XML_TEMPLATE_QUERY = "displayName,fullName,color,lastBuild[timestamp],property[branch[head]]" + XML_RECURRING_MARKER;

	/**
	 * Public server URL used to fetch the last available version of the product.
	 */
	@Value("${service-build-jenkins-server:https://mirrors.jenkins-ci.org}")
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
		final var parameters = subscriptionResource.getParameters(subscription);

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
		try (var processor = new JenkinsCurlProcessor(parameters)) {
			final var jenkinsBaseUrl = StringUtils.appendIfMissing(parameters.get(PARAMETER_URL), "/");
			final var jobName = parameters.get(PARAMETER_JOB);
			return processor.process(new CurlRequest("POST", jenkinsBaseUrl + "job/" + jobName + "/" + url, null));
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
			throws IOException, ParserConfigurationException, SAXException {
		final var nodeStatusWithData = new SubscriptionStatusWithData();
		nodeStatusWithData.put("job", validateJob(parameters));
		return nodeStatusWithData;
	}

	@Override
	public void create(final int subscription) throws IOException {
		final var parameters = subscriptionResource.getParameters(subscription);
		// Validate the node settings
		validateAdminAccess(parameters);

		// Get Template configuration
		final var templateJob = parameters.get(PARAMETER_TEMPLATE_JOB);
		final var templateConfigXml = getResource(parameters, "job/" + encode(templateJob) + "/config.xml");

		// update template
		final var project = subscriptionRepository.findOneExpected(subscription).getProject();
		final var teamLeader = iamProvider[0].getConfiguration().getUserRepository()
				.findById(project.getTeamLeader());
		final String configXml = templateConfigXml
				.replaceFirst("<disabled>true</disabled>", "<disabled>false</disabled>")
				.replace("ligoj-saas", project.getPkey())
				.replaceAll("someone@sample.org", teamLeader.getMails().get(0))
				.replaceFirst("(<displayName>).*?(</displayName>)", "$1" + project.getName() + "$2")
				.replaceFirst("(<description>).*?(</description>)", "$1" + project.getDescription() + "$2");

		// create new job
		final var job = parameters.get(PARAMETER_JOB);
		final var jenkinsBaseUrl = StringUtils.appendIfMissing(parameters.get(PARAMETER_URL), "/");
		final var curlRequest = new CurlRequest(HttpMethod.POST,
				jenkinsBaseUrl + "createItem?name=" + encode(job), configXml, "Content-Type:application/xml");
		try (var curl = new JenkinsCurlProcessor(parameters)) {
			if (!curl.process(curlRequest)) {
				throw new BusinessException("Creating the job for the subscription {} failed.", subscription);
			}
		}
	}

	@Override
	public void delete(final int subscription, final boolean deleteRemoteData) {
		if (deleteRemoteData) {
			final var parameters = subscriptionResource.getParameters(subscription);
			// Validate the node settings
			validateAdminAccess(parameters);

			// delete the job
			final var job = parameters.get(PARAMETER_JOB);
			final var jenkinsBaseUrl = StringUtils.appendIfMissing(parameters.get(PARAMETER_URL), "/");
			final var curlRequest = new CurlRequest(HttpMethod.POST,
					jenkinsBaseUrl + "job/" + encode(job) + "/doDelete", StringUtils.EMPTY);
			try (var curl = new JenkinsCurlProcessor(parameters, new OnlyRedirectHttpResponseCallback())) {
				if (!curl.process(curlRequest)) {
					throw new BusinessException("Deleting the job for the subscription {} failed.", subscription);
				}
			}
		}
	}

	private String encode(final String job) {
		return UriUtils.encode(job, "UTF-8");
	}

	/**
	 * Search the Jenkins's jobs matching to the given criteria. Name, display name and description are considered.
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
	 * Search the Jenkins's jobs matching to the given criteria. Name, display name and description are considered.
	 *
	 * @param node     the node to be tested with given parameters.
	 * @param criteria the search criteria.
	 * @param view     The optional view URL.
	 * @return job names matching the criteria.
	 */
	private List<Job> findAllByName(final String node, final String criteria, final String view)
			throws SAXException, IOException, ParserConfigurationException {
		// Build Jenkins query
		var query = "jobs[" + XML_TEMPLATE_QUERY + "]";
		final int maxDepth = configuration.get(PARAMETER_MAX_DEPTH, MAX_DEPTH);

		for (var depth = 1; depth < maxDepth; depth++) {
			query = query.replace(XML_RECURRING_MARKER, ",jobs[" + XML_TEMPLATE_QUERY + "]");
		}
		// End of the recursion
		query = query.replace(XML_RECURRING_MARKER, "");

		// Prepare the context, an ordered set of jobs
		final var format = new NormalizeFormat();
		final var formatCriteria = format.format(criteria);
		final var parameters = pvResource.getNodeParameters(node);

		// Get the jobs and parse them
		final var url = StringUtils.trimToEmpty(view) + "api/xml?tree=" + query;
		final var jobsAsXml = Objects.toString(getResource(parameters, url), "<a/>");
		final var jobsAsInput = IOUtils.toInputStream(jobsAsXml, StandardCharsets.UTF_8);
		final var hudson = xml.parse(jobsAsInput).getDocumentElement();
		final var result = new TreeMap<String, Job>();
		getRecursiveJobs(hudson)
				.filter(job ->
						format.format(Objects.toString(job.getId(), "")).contains(formatCriteria)
								|| format.format(Objects.toString(job.getName(), "")).contains(formatCriteria)
								|| format.format(Objects.toString(job.getDescription(), "")).contains(formatCriteria))
				.forEach(job -> result.put(format.format(ObjectUtils.defaultIfNull(job.getName(), job.getId())), job));
		return new ArrayList<>(result.values());
	}

	private Stream<Job> getRecursiveJobs(Element e) {
		return Stream.concat(Stream.of(newJob(e)), DomUtils.getChildElementsByTagName(e, "job").stream().flatMap(this::getRecursiveJobs));
	}

	/**
	 * Search the Jenkins's template jobs matching to the given criteria. Name, display name and description are
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
	 * @throws IOException When the Jenkins base URL is malformed.
	 * @throws ParserConfigurationException When the XML content is malformed.
	 * @throws SAXException When the XML content is malformed.
	 */
	@GET
	@Path("{node}/job/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Job findById(@PathParam("node") final String node, @PathParam("id") final String id)
			throws IOException, ParserConfigurationException, SAXException {
		// Prepare the context, an ordered set of jobs
		final var parameters = pvResource.getNodeParameters(node);
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
		try (var curl = new CurlProcessor()) {
			final var downloadPage = ObjectUtils.defaultIfNull(curl.get(repo), "");

			// Find the last download link
			final var matcher = Pattern.compile("href=\"([\\d.]+)/\"").matcher(downloadPage);
			String lastVersion = null;
			while (matcher.find()) {
				final var cVersion = matcher.group(1);
				if (lastVersion == null || cVersion.compareTo(lastVersion) > 0) {
					lastVersion = cVersion;
				}
			}

			// Return the last read version
			return lastVersion;
		}
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
	public void link(final int subscription) throws IOException, ParserConfigurationException, SAXException {
		final var parameters = subscriptionResource.getParameters(subscription);

		// Validate the node settings
		validateAdminAccess(parameters);

		// Validate the job settings
		validateJob(parameters);
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

		// Check the user can log in to Jenkins with the preempted
		// authentication processor
		if (getResource(parameters, "api/xml") == null) {
			throw new ValidationJsonException(PARAMETER_USER, "jenkins-login");
		}

		// Check the user has enough rights to get the master configuration and
		// return the version
		final var version = getVersion(parameters);
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
	 */
	protected Job validateJob(final Map<String, String> parameters) throws IOException, ParserConfigurationException, SAXException {
		final var job = parameters.get(PARAMETER_JOB);
		final var jobAsXml = getResource(parameters,
				"job/" + Streams.of(job.split("/")).map(this::encode).collect(Collectors.joining("/job/"))
						+ "/api/xml?tree=" + XML_TEMPLATE_QUERY.replace(XML_RECURRING_MARKER, ",jobs[" + XML_TEMPLATE_QUERY).replace(XML_RECURRING_MARKER, "]"));
		if (jobAsXml == null || "<hudson/>".equals(jobAsXml)) {
			// Invalid couple PKEY and id
			throw new ValidationJsonException(PARAMETER_JOB, "jenkins-job", job);
		}

		final var jobsAsInput = IOUtils.toInputStream(jobAsXml, StandardCharsets.UTF_8);
		final var root = xml.parse(jobsAsInput).getDocumentElement();
		final var result = newJob(root);
		final int maxBranches = NumberUtils.toInt(getParameter(parameters, PARAMETER_MAX_BRANCHES, String.valueOf(DEFAULT_MAX_BRANCHES)));
		result.setJobs(DomUtils.getChildElementsByTagName(root, "job").stream()
				.map(this::newJob)
				.filter(j -> !"disabled".equals(j.getStatus()))
				.sorted((b1, b2) -> {
					// Sort the branches by their activities
					if (b1.getLastBuild() == null) {
						return 1;
					}
					if (b2.getLastBuild() == null) {
						return -1;
					}
					return (int) (b2.getLastBuild() - b1.getLastBuild());
				})
				.limit(maxBranches)
				.collect(Collectors.toList()));
		return result;
	}

	private String getNodeContent(final Element root, final String tag) {
		return StringUtils.trimToNull(DomUtils.getChildElementValueByTagName(root, tag));
	}

	private Job newJob(final Element root) {
		final var result = new Job();

		// Extract string data from this job
		result.setId(Optional.ofNullable(getNodeContent(root, "fullName")).orElseGet(() -> getNodeContent(root, "name")));
		result.setName(getNodeContent(root, "displayName"));
		result.setDescription(getNodeContent(root, "description"));
		result.setLastBuild(Optional.ofNullable(DomUtils.getChildElementByTagName(root, "lastBuild"))
				.map(l -> getNodeContent(l, "timestamp"))
				.map(Long::valueOf).orElse(null));

		// Retrieve description, status, display name and branch type
		final var statusNode = Objects.toString(getNodeContent(root, "color"), "disabled");
		result.setStatus(StringUtils.removeEnd(statusNode, "_anime"));
		result.setBuilding(statusNode.endsWith("_anime"));
		result.setPullRequestBranch(DomUtils.getChildElementsByTagName(root, "property").stream()
				.filter(p -> "org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty".equals(p.getAttribute("_class")))
				.flatMap(p -> DomUtils.getChildElementsByTagName(p, "branch").stream())
				.flatMap(b -> DomUtils.getChildElementsByTagName(b, "head").stream())
				.anyMatch(h -> "org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead".equals(h.getAttribute("_class"))));
		return result;
	}

}
