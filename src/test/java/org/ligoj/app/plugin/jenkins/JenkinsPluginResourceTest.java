/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.jenkins;

import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.transaction.Transactional;
import org.apache.commons.io.IOUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.iam.model.DelegateOrg;
import org.ligoj.app.model.*;
import org.ligoj.app.plugin.build.BuildResource;
import org.ligoj.app.resource.node.ParameterValueResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test class of {@link JenkinsPluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class JenkinsPluginResourceTest extends AbstractServerTest {
	@Autowired
	private JenkinsPluginResource resource;

	@Autowired
	private ParameterValueResource pvResource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private ConfigurationResource configurationResource;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class<?>[]{Node.class, Parameter.class, Project.class, Subscription.class,
				ParameterValue.class, DelegateOrg.class}, StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter");
		configurationResource.put(JenkinsPluginResource.PARAMETER_MAX_DEPTH, "2");

		// Coverage only
		Assertions.assertEquals("service:build:jenkins", resource.getKey());
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, BuildResource.SERVICE_KEY);
	}

	@Test
	void deleteLocal() {
		resource.delete(subscription, false);
		// nothing has been done. If remote delete is done, an exception will be
		// thrown and this test will fail.
	}

	@Test
	void deleteRemote() throws IOException {
		addLoginAccess();
		addAdminAccess();

		// post delete
		final var deletePath = urlEqualTo("/job/ligoj-bootstrap/doDelete");
		httpServer.stubFor(post(deletePath).willReturn(
				aResponse().withHeader("location", "location").withStatus(HttpStatus.SC_MOVED_TEMPORARILY)));
		httpServer.start();

		resource.delete(subscription, true);

		// check that server has been called.
		httpServer.verify(1, WireMock.postRequestedFor(deletePath));
	}

	/**
	 * A nested job / folder path is deleted segment by segment: deleting the root folder created by a folder-mode
	 * subscription removes its whole tree.
	 */
	@Test
	void deleteRemoteNestedPath() throws IOException {
		addLoginAccess();
		addAdminAccess();
		em.createQuery("UPDATE ParameterValue SET data = 'team/Admin' WHERE parameter.id = 'service:build:jenkins:job' AND subscription.id = :id")
				.setParameter("id", this.subscription).executeUpdate();
		em.flush();
		cacheManager.getCache("subscription-parameters").clear();
		final var deletePath = urlEqualTo("/job/team/job/Admin/doDelete");
		httpServer.stubFor(post(deletePath).willReturn(aResponse().withHeader("location", "location").withStatus(HttpStatus.SC_MOVED_TEMPORARILY)));
		httpServer.start();

		resource.delete(subscription, true);
		httpServer.verify(1, WireMock.postRequestedFor(deletePath));
	}

	/**
	 * Nothing to delete remotely without a job.
	 */
	@Test
	void deleteRemoteNoJob() throws IOException {
		addLoginAccess();
		addAdminAccess();
		em.createQuery("DELETE ParameterValue WHERE parameter.id = 'service:build:jenkins:job' AND subscription.id = :id")
				.setParameter("id", this.subscription).executeUpdate();
		em.flush();
		cacheManager.getCache("subscription-parameters").clear();
		httpServer.start();

		resource.delete(subscription, true);
		httpServer.verify(0, WireMock.postRequestedFor(urlPathMatching(".*doDelete")));
	}

	@Test
	void deleteRemoteFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();

		// post delete
		final var deletePath = urlEqualTo("/job/ligoj-bootstrap/doDelete");
		httpServer.stubFor(post(deletePath).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();

		Assertions.assertThrows(BusinessException.class, () -> resource.delete(subscription, true));
	}

	@Test
	void getJenkinsResourceInvalidUrl() {
		resource.getResource(new HashMap<>(), null);
	}

	@Test
	void getVersion() throws Exception {
		addAdminAccess();
		httpServer.start();
		final var version = resource.getVersion(subscription);
		Assertions.assertEquals("1.574", version);
	}

	@Test
	void getLastVersion() {
		final var lastVersion = resource.getLastVersion();
		Assertions.assertNotNull(lastVersion);
		Assertions.assertTrue(lastVersion.compareTo("1.576") > 0);
	}

	@Test
	void getLastVersionFailed() {
		Assertions.assertNull(resource.getLastVersion("any:some"));
	}

	@Test
	void validateJobNotFound() {
		httpServer.stubFor(get(urlEqualTo("/job/ligoj-bootstrap/config.xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();

		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateJob(parameters)), JenkinsPluginResource.PARAMETER_JOB, "jenkins-job");
	}

	@Test
	void validateJobInvalidResult() {
		configurationResource.put(JenkinsPluginResource.PARAMETER_MAX_BRANCHES, "2");
		httpServer.stubFor(get(urlEqualTo(
				"/job/ligoj-bootstrap/api/xml?tree=displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]],jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<hudson/>")));
		httpServer.start();

		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateJob(parameters)), JenkinsPluginResource.PARAMETER_JOB, "jenkins-job");
	}

	@Test
	void link() throws IOException, ParserConfigurationException, SAXException {
		addLoginAccess();
		addAdminAccess();
		addJobAccess();
		httpServer.start();

		// Attach the Jenkins project identifier
		final var parameter = new Parameter();
		parameter.setId(JenkinsPluginResource.PARAMETER_JOB);
		final var subscription = new Subscription();
		final var source = em.find(Subscription.class, this.subscription);
		subscription.setProject(source.getProject());
		subscription.setNode(source.getNode());
		em.persist(subscription);
		final var parameterValue = new ParameterValue();
		parameterValue.setParameter(parameter);
		parameterValue.setData("ligoj-bootstrap");
		parameterValue.setSubscription(subscription);
		em.persist(parameterValue);
		em.flush();

		// Invoke create for an already created entity, since for now, there is
		// nothing but validation pour jenkins
		resource.link(subscription.getId());

		// Nothing to validate for now...
	}

	@Test
	void validateJob() throws IOException, ParserConfigurationException, SAXException {
		addJobAccess();
		httpServer.start();

		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		checkJob(resource.validateJob(parameters), false);
	}

	@Test
	void validateJobSimple() throws IOException, ParserConfigurationException, SAXException {
		httpServer.stubFor(get(urlEqualTo(
				"/job/ligoj-bootstrap/api/xml?tree=displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]],jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(new ClassPathResource(
										"mock-server/jenkins/jenkins-ligoj-bootstrap-config-simple.xml").getInputStream(),
								StandardCharsets.UTF_8))));
		httpServer.start();

		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		final var job = resource.validateJob(parameters);
		Assertions.assertEquals("ligoj-bootstrap", job.getId());
		Assertions.assertNull(job.getName());
		Assertions.assertNull(job.getDescription());
		Assertions.assertEquals("disabled", job.getStatus());
		Assertions.assertFalse(job.isBuilding());
	}

	@Test
	void validateJobBuilding() throws IOException, ParserConfigurationException, SAXException {
		addJobAccessBuilding();
		httpServer.start();

		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		checkJob(resource.validateJob(parameters), true);
	}

	private void checkJob(final Job job, final boolean building) {
		Assertions.assertEquals("ligoj-bootstrap", job.getId());
		Assertions.assertEquals("Ligoj - Bootstrap", job.getName());
		Assertions.assertEquals("Any description", job.getDescription());
		Assertions.assertEquals("yellow", job.getStatus());
		Assertions.assertEquals(building, job.isBuilding());

		// Check branches
		Assertions.assertTrue(job.getJobs().getFirst().isPullRequestBranch());
		Assertions.assertTrue(job.getJobs().getFirst().isBuilding());
		Assertions.assertEquals("PR-2", job.getJobs().getFirst().getId());
		Assertions.assertEquals("blue", job.getJobs().getFirst().getStatus());
		Assertions.assertEquals(1693000000001L, job.getJobs().getFirst().getLastBuild());

		Assertions.assertFalse(job.getJobs().get(1).isPullRequestBranch());
		Assertions.assertFalse(job.getJobs().get(1).isBuilding());
		Assertions.assertEquals("main", job.getJobs().get(1).getId());
		Assertions.assertEquals("red", job.getJobs().get(1).getStatus());
		Assertions.assertEquals(1693000000000L, job.getJobs().get(1).getLastBuild());
	}

	@Test
	void checkStatus() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();

		final var parametersNoCheck = subscriptionResource.getParametersNoCheck(subscription);
		parametersNoCheck.remove(JenkinsPluginResource.PARAMETER_JOB);
		Assertions.assertTrue(resource.checkStatus(parametersNoCheck));
	}

	@Test
	void checkSubscriptionStatus() throws IOException, ParserConfigurationException, SAXException {
		addJobAccess();
		httpServer.start();

		final var nodeStatusWithData = resource
				.checkSubscriptionStatus(subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkJob((Job) nodeStatusWithData.getData().get("job"), false);
	}

	private void addJobAccess() throws IOException {
		configurationResource.put(JenkinsPluginResource.PARAMETER_MAX_BRANCHES, "2");
		httpServer.stubFor(get(urlEqualTo(
				"/job/ligoj-bootstrap/api/xml?tree=displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]],jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-ligoj-bootstrap-config.xml")
										.getInputStream(),
								StandardCharsets.UTF_8))));
	}

	private void addJobAccessBuilding() throws IOException {
		configurationResource.put(JenkinsPluginResource.PARAMETER_MAX_BRANCHES, "2");
		httpServer.stubFor(get(urlEqualTo(
				"/job/ligoj-bootstrap/api/xml?tree=displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]],jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-ligoj-bootstrap-config-building.xml")
								.getInputStream(),
						StandardCharsets.UTF_8))));
	}

	@Test
	void validateAdminAccess() throws IOException {
		addLoginAccess();
		addAdminAccess();
		addJobAccess();
		httpServer.start();

		final var version = resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"));
		Assertions.assertEquals("1.574", version);
	}

	private void addAdminAccess() throws IOException {
		httpServer.stubFor(get(urlEqualTo("/api/json?tree=numExecutors"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withHeader("x-jenkins", "1.574")
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-version.json").getInputStream(),
								StandardCharsets.UTF_8))));
	}

	@Test
	void validateAdminAccessConnectivityFail() {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();
		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateAdminAccess(parameters)),
				JenkinsPluginResource.PARAMETER_URL, "jenkins-connection");
	}

	@Test
	void validateAdminAccessLoginFail() {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();
		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateAdminAccess(parameters)),
				JenkinsPluginResource.PARAMETER_USER, "jenkins-login");
	}

	@Test
	void validateAdminAccessNoRight() throws IOException {
		addLoginAccess();
		httpServer.stubFor(get(urlEqualTo("/computer/(master)/config.xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();
		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateAdminAccess(parameters)),
				JenkinsPluginResource.PARAMETER_USER, "jenkins-rights");
	}

	@Test
	void findAllByName() throws IOException, SAXException, ParserConfigurationException {
		httpServer.stubFor(get(urlEqualTo("/api/xml?tree=jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]," +
				"jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]]")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-api-xml-tree.xml").getInputStream(),
						StandardCharsets.UTF_8))));
		httpServer.start();
		final var jobs = resource.findAllByName("service:build:jenkins:bpr", "ligoj");
		checkAll(jobs);
	}

	private void checkAll(final List<Job> jobs) {
		Assertions.assertEquals(4, jobs.size());
		final var job = jobs.get(1);
		Assertions.assertEquals("Ligoj - Cron - RSE", job.getName());
		Assertions.assertEquals("CRON - Project RSE", job.getDescription());
		Assertions.assertEquals("ligoj-cron-rse", job.getId());
		Assertions.assertEquals("disabled", job.getStatus());
		Assertions.assertNull(job.getJobs());
	}

	@Test
	void findAllTemplateByName() throws IOException, SAXException, ParserConfigurationException {
		httpServer.stubFor(
				get(urlPathEqualTo("/view/Templates/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-api-xml-tree.xml").getInputStream(),
								StandardCharsets.UTF_8))));
		httpServer.start();
		final var jobs = resource.findAllTemplateByName("service:build:jenkins:bpr", "ligoj");
		checkAll(jobs);
	}

	/**
	 * Bad credential
	 */
	@Test
	void findAllByNameFailed() throws IOException, SAXException, ParserConfigurationException {
		httpServer.stubFor(get(urlPathEqualTo("/api/xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED).withBody("<html>FORBIDDEN</html>")));
		httpServer.start();
		Assertions.assertEquals(0, resource.findAllByName("service:build:jenkins:bpr", "ligoj").size());
	}

	@Test
	void findById() throws IOException, ParserConfigurationException, SAXException {
		addJobAccessBuilding();
		httpServer.start();
		checkJob(resource.findById("service:build:jenkins:bpr", "ligoj-bootstrap"), true);
	}

	@Test
	void findByIdFail() {
		httpServer.stubFor(get(urlEqualTo(
				"/job/ligoj-bootstrap/api/xml?tree=displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]],jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<hudson/>")));
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.findById("service:build:jenkins:bpr", "ligoj-bootstraps")), "service:build:jenkins:job", "jenkins-job");
	}

	@Test
	void findByIdFail404() {
		httpServer.stubFor(get(urlEqualTo(
				"/job/ligoj-bootstrap/api/xml?tree=displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]],jobs[displayName,fullName,description,color,lastBuild[timestamp],property[branch[head]]]"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND).withBody("<any/>")));
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.findById("service:build:jenkins:bpr", "ligoj-bootstraps")), "service:build:jenkins:job", "jenkins-job");
	}

	private void addLoginAccess() throws IOException {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-api-xml.xml").getInputStream(),
						StandardCharsets.UTF_8))));
	}

	@Test
	void create() throws IOException {
		addLoginAccess();
		addAdminAccess();

		// retrieve template config.xml
		httpServer.stubFor(get(urlEqualTo("/job/template/config.xml")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-template-config.xml").getInputStream(),
						StandardCharsets.UTF_8))));
		// post new job config.xml
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap"))
				.withRequestBody(WireMock.containing("fdaugan@sample.com"))
				.withRequestBody(WireMock.containing("<disabled>false</disabled>"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();

		// prepare new subscription
		final var subscription = em.find(Subscription.class, this.subscription);
		createParameterValueTemplateJob(subscription);
		this.resource.create(this.subscription);
	}

	@Test
	void createFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();

		// retrieve template config.xml
		httpServer.stubFor(get(urlEqualTo("/job/template/config.xml")).willReturn(aResponse()
				.withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/jenkins/jenkins-template-config.xml").getInputStream(),
						StandardCharsets.UTF_8))));
		// post new job config.xml
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)));
		httpServer.start();

		// prepare new subscription
		final var subscription = em.find(Subscription.class, this.subscription);
		createParameterValueTemplateJob(subscription);
		Assertions.assertThrows(BusinessException.class, () -> this.resource.create(this.subscription));
	}

	/**
	 * create a parameter value for template Job definition
	 *
	 * @param subscription future parameter value linked subscription
	 */
	private void createParameterValueTemplateJob(final Subscription subscription) {
		final var parameterValue = new ParameterValue();
		parameterValue.setParameter(em.find(Parameter.class, "service:build:jenkins:template-job"));
		parameterValue.setSubscription(subscription);
		parameterValue.setData("template");
		em.persist(parameterValue);
		em.flush();
	}

	private void createParameterValueFolder(final Subscription subscription, final String definition) {
		final var parameterValue = new ParameterValue();
		parameterValue.setParameter(em.find(Parameter.class, "service:build:jenkins:template-folder"));
		parameterValue.setSubscription(subscription);
		parameterValue.setData(definition);
		em.persist(parameterValue);
		em.flush();
	}

	private static final String FOLDER = "{\"description\":\"Root & co\",\"roles\":{\"dev\":{}},"
			+ "\"credentials\":[{\"id\":\"c1\",\"description\":\"d\",\"stapler-class\":\"org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl\","
			+ "\"attributes\":{\"secret\":\"s3cret\",\"$redact\":\"secret\"}}],"
			+ "\"folders\":[{\"name\":\"child.1\",\"mode\":\"jenkins.branch.OrganizationFolder\"}]}";

	@Test
	void updateRelaxTemplateJob() {
		final var parameter = em.find(Parameter.class, JenkinsPluginResource.PARAMETER_TEMPLATE_JOB);
		parameter.setMandatory(true);
		em.flush();

		final var job = em.find(Parameter.class, JenkinsPluginResource.PARAMETER_JOB);
		job.setMandatory(true);
		em.flush();

		resource.update("5.0.0");
		Assertions.assertFalse(parameter.isMandatory());
		Assertions.assertFalse(job.isMandatory());

		// Already relaxed, nothing to do
		resource.update("5.0.1");
		Assertions.assertFalse(parameter.isMandatory());
	}

	@Test
	void createFolder() throws IOException {
		addLoginAccess();
		addAdminAccess();
		// Nothing exists yet
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		// Root folder, named by the subscription job, with its escaped description
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap"))
				.withRequestBody(WireMock.equalTo("<com.cloudbees.hudson.plugins.folder.Folder><description>Root &amp; co</description></com.cloudbees.hudson.plugins.folder.Folder>"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		// Its credential: the tooling hint '$redact' is not sent, the roles are ignored
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/domain/_/createCredentials"))
				.withRequestBody(WireMock.containing("%22id%22%3A%22c1%22"))
				.withRequestBody(WireMock.containing("%22secret%22%3A%22s3cret%22"))
				.withRequestBody(WireMock.notMatching(".*redact.*"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlPathEqualTo("/pluginManager/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"plugins\":[{\"shortName\":\"cloudbees-folder\",\"active\":true},{\"shortName\":\"credentials\",\"active\":true},{\"shortName\":\"plain-credentials\",\"active\":true}]}")));
		// The credential store of the folder exists (Credentials plug-in installed)
		httpServer.stubFor(get(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/api/json?tree=id")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		// Nested organization folder
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/createItem?name=child.1"))
				.withRequestBody(WireMock.containing("<jenkins.branch.OrganizationFolder>"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), FOLDER);
		this.resource.create(this.subscription);

		httpServer.verify(1, postRequestedFor(urlEqualTo("/createItem?name=ligoj-bootstrap")));
		httpServer.verify(1, postRequestedFor(urlEqualTo("/job/ligoj-bootstrap/createItem?name=child.1")));
		httpServer.verify(1, postRequestedFor(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/domain/_/createCredentials")));
		// The template job path is not used at all
		httpServer.verify(0, getRequestedFor(urlPathMatching("/job/.*/config.xml")));
	}

	/**
	 * Jenkins answers the credential creation with a redirect to the domain page: a success.
	 */
	@Test
	void createFolderCredentialRedirect() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathEqualTo("/pluginManager/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"plugins\":[{\"shortName\":\"credentials\",\"active\":true},{\"shortName\":\"plain-credentials\",\"active\":true}]}")));
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(get(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/api/json?tree=id")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.stubFor(post(urlPathMatching(".*createItem.*")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/domain/_/createCredentials"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_MOVED_TEMPORARILY).withHeader("Location", "http://localhost:8120/job/ligoj-bootstrap/credentials/store/folder/domain/_/")));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), FOLDER);
		this.resource.create(this.subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/domain/_/createCredentials")));
	}

	@Test
	void createFolderExisting() throws IOException {
		addLoginAccess();
		addAdminAccess();
		// Every folder already exists: nothing is created again, the creation can be replayed
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), "{\"folders\":[{\"name\":\"child\"}]}");
		this.resource.create(this.subscription);
		httpServer.verify(0, postRequestedFor(urlPathMatching(".*createItem.*")));
	}

	@Test
	void createFolderFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), "{}");
		Assertions.assertThrows(BusinessException.class, () -> this.resource.create(this.subscription));
	}

	@Test
	void createFolderCredentialFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlPathEqualTo("/pluginManager/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"plugins\":[{\"shortName\":\"cloudbees-folder\",\"active\":true},{\"shortName\":\"credentials\",\"active\":true},{\"shortName\":\"plain-credentials\",\"active\":true}]}")));
		// The store exists but the creation is refused (invalid class, ...)
		httpServer.stubFor(get(urlEqualTo("/job/ligoj-bootstrap/credentials/store/folder/api/json?tree=id")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("{}")));
		httpServer.stubFor(post(urlPathMatching(".*/createCredentials")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), FOLDER);
		final var exception = Assertions.assertThrows(BusinessException.class, () -> this.resource.create(this.subscription));
		// The secret never leaks in the error
		Assertions.assertFalse(String.valueOf(exception.getMessage()).contains("s3cret"));
		// The generic creation failure, not the missing store
		Assertions.assertTrue(String.valueOf(exception.getMessage()).contains("Creating the Jenkins credential"), exception.getMessage());
	}

	/**
	 * The plug-ins required by the credentials are checked before anything is created: a missing one is a
	 * validation error of the definition naming the plug-ins.
	 */
	@Test
	void createFolderPluginMissing() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathEqualTo("/pluginManager/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody("{\"plugins\":[{\"shortName\":\"cloudbees-folder\",\"active\":true},{\"shortName\":\"credentials\",\"active\":false}]}")));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), FOLDER);
		final var error = Assertions.assertThrows(ValidationJsonException.class, () -> this.resource.create(this.subscription));
		MatcherUtil.assertThrows(error, JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-plugin");
		@SuppressWarnings("unchecked")
		final var parameters = (Map<String, Object>) error.getErrors().get(JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER).getFirst().get("parameters");
		Assertions.assertEquals("credentials, plain-credentials", parameters.get("plugins"));
		httpServer.verify(0, postRequestedFor(urlPathMatching(".*createItem.*")));
	}

	@Test
	void requiredPlugins() {
		Assertions.assertTrue(JenkinsFolderCreator.requiredPlugins(JenkinsFolderCreator.parse("{\"folders\":[{\"name\":\"a\"}]}")).isEmpty());
		final var definition = JenkinsFolderCreator.parse("{\"credentials\":[{\"id\":\"a\",\"stapler-class\":\"com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl\"}],"
				+ "\"folders\":[{\"name\":\"b\",\"credentials\":[{\"id\":\"b\",\"stapler-class\":\"com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey\"},"
				+ "{\"id\":\"c\",\"stapler-class\":\"com.acme.MyCredentials\",\"plugin\":\"acme-credentials\"},"
				+ "{\"id\":\"d\",\"stapler-class\":\"com.acme.Unknown\"}]}]}");
		Assertions.assertEquals(List.of("credentials", "ssh-credentials", "acme-credentials"), new ArrayList<>(JenkinsFolderCreator.requiredPlugins(definition)));
	}

	/**
	 * Without the Jenkins "credentials" plug-in, the folder has no credential store: a clear error names the cause
	 * before any credential is sent (plug-in list unreadable here, so the store probe is the last guard).
	 */
	@Test
	void createFolderCredentialsPluginMissing() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlEqualTo("/createItem?name=ligoj-bootstrap")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();

		createParameterValueFolder(em.find(Subscription.class, this.subscription), FOLDER);
		final var exception = Assertions.assertThrows(BusinessException.class, () -> this.resource.create(this.subscription));
		Assertions.assertTrue(String.valueOf(exception.getMessage()).contains("'credentials' plug-in"), exception.getMessage());
		httpServer.verify(0, postRequestedFor(urlPathMatching(".*/createCredentials")));
	}

	@Test
	void createFolderInvalidDefinition() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		final var subscription = em.find(Subscription.class, this.subscription);
		for (final var invalid : new String[] { "not-json", "{\"folders\":[{\"description\":\"no name\"}]}", "{\"mode\":\"hudson.model.FreeStyleProject\"}",
				"{\"credentials\":[{\"id\":\"c1\"}]}" }) {
			createParameterValueFolder(subscription, invalid);
			Assertions.assertThrows(ValidationJsonException.class, () -> this.resource.create(this.subscription));
			em.createQuery("DELETE ParameterValue WHERE parameter.id = 'service:build:jenkins:template-folder'").executeUpdate();
		}
	}

	private void deleteJobParameter() {
		em.createQuery("DELETE ParameterValue WHERE parameter.id = 'service:build:jenkins:job' AND subscription.id = :id")
				.setParameter("id", this.subscription).executeUpdate();
		em.flush();
		cacheManager.getCache("subscription-parameters").clear();
	}

	private String storedJob() {
		cacheManager.getCache("subscription-parameters").clear();
		return subscriptionResource.getParameters(this.subscription).get(JenkinsPluginResource.PARAMETER_JOB);
	}

	/**
	 * Folder mode without a job: the root folder is named by the definition root, and stored as the subscription job.
	 */
	@Test
	void createFolderBlankJobRootName() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlPathMatching(".*createItem.*")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		deleteJobParameter();
		createParameterValueFolder(em.find(Subscription.class, this.subscription),
				"{\"name\":\"Admin\",\"description\":\"Root\",\"folders\":[{\"name\":\"child\"}]}");
		this.resource.create(this.subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/createItem?name=Admin")).withRequestBody(WireMock.containing("<description>Root</description>")));
		httpServer.verify(1, postRequestedFor(urlEqualTo("/job/Admin/createItem?name=child")));
		Assertions.assertEquals("Admin", storedJob());
	}

	/**
	 * Folder mode without a job nor a root name: a single top-level folder is the root.
	 */
	@Test
	void createFolderBlankJobSingleTopFolder() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(get(urlPathMatching("/job/.*/api/json")).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
		httpServer.stubFor(post(urlPathMatching(".*createItem.*")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		deleteJobParameter();
		createParameterValueFolder(em.find(Subscription.class, this.subscription),
				"{\"folders\":[{\"name\":\"Admin\",\"folders\":[{\"name\":\"folder6\",\"description\":\"Folder6\"}]}]}");
		this.resource.create(this.subscription);
		httpServer.verify(1, postRequestedFor(urlEqualTo("/createItem?name=Admin")));
		httpServer.verify(1, postRequestedFor(urlEqualTo("/job/Admin/createItem?name=folder6")).withRequestBody(WireMock.containing("<description>Folder6</description>")));
		httpServer.verify(2, postRequestedFor(urlPathMatching(".*createItem.*")));
		Assertions.assertEquals("Admin", storedJob());
	}

	/**
	 * Folder mode without a job, a root name, and several top-level folders: the root cannot be chosen.
	 */
	@Test
	void createFolderBlankJobAmbiguousRoot() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		deleteJobParameter();
		createParameterValueFolder(em.find(Subscription.class, this.subscription),
				"{\"folders\":[{\"name\":\"Admin\"},{\"name\":\"Dev\"}]}");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> this.resource.create(this.subscription)),
				JenkinsPluginResource.PARAMETER_TEMPLATE_FOLDER, "jenkins-folder-root");
		httpServer.verify(0, postRequestedFor(urlPathMatching(".*createItem.*")));
	}

	/**
	 * Template job mode and link mode still require the job.
	 */
	@Test
	void createTemplateBlankJob() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		deleteJobParameter();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> this.resource.create(this.subscription)),
				JenkinsPluginResource.PARAMETER_JOB, "NotBlank");
	}

	@Test
	void linkBlankJob() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		deleteJobParameter();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> this.resource.link(this.subscription)),
				JenkinsPluginResource.PARAMETER_JOB, "NotBlank");
	}

	@Test
	void createNoTemplateNoFolder() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		Assertions.assertThrows(ValidationJsonException.class, () -> this.resource.create(this.subscription));
	}

	@Test
	void buildFailed() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.start();
		Assertions.assertThrows(BusinessException.class, () -> this.resource.build(subscription));
	}

	@Test
	void build() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(
				post(urlEqualTo("/job/ligoj-bootstrap/build")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		this.resource.build(subscription);
	}

	@Test
	void buildInvalidUrl() {
		@SuppressWarnings("unchecked") final Map<String, String> map = mock(Map.class);
		when(map.get(JenkinsPluginResource.PARAMETER_USER)).thenReturn("some");
		when(map.get(JenkinsPluginResource.PARAMETER_TOKEN)).thenReturn("some");
		when(map.get(JenkinsPluginResource.PARAMETER_URL)).thenThrow(new RuntimeException());
		Assertions.assertThrows(RuntimeException.class, () -> this.resource.build(map, null));
	}

	@Test
	void buildParameters() throws IOException {
		addLoginAccess();
		addAdminAccess();
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/build"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR)));
		httpServer.stubFor(post(urlEqualTo("/job/ligoj-bootstrap/buildWithParameters"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		this.resource.build(subscription);
	}

}
