/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.build.jenkins;

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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

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
		persistEntities("csv", new Class[]{Node.class, Parameter.class, Project.class, Subscription.class,
				ParameterValue.class, DelegateOrg.class}, StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter");

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
	void deleteLocal() throws MalformedURLException, URISyntaxException {
		resource.delete(subscription, false);
		// nothing has been done. If remote delete is done, an exception will be
		// thrown and this test will fail.
	}

	@Test
	void deleteRemote() throws IOException, URISyntaxException {
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
	void link() throws IOException, URISyntaxException, ParserConfigurationException, SAXException {
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
	void validateJob() throws IOException, URISyntaxException, ParserConfigurationException, SAXException {
		addJobAccess();
		httpServer.start();

		final var parameters = pvResource.getNodeParameters("service:build:jenkins:bpr");
		parameters.put(JenkinsPluginResource.PARAMETER_JOB, "ligoj-bootstrap");
		checkJob(resource.validateJob(parameters), false);
	}

	@Test
	void validateJobSimple() throws IOException, URISyntaxException, ParserConfigurationException, SAXException {
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?tree=jobs[displayName,name,color,lastBuild[timestamp],jobs[displayName,name,color,lastBuild[timestamp],property[branch[head]]]]&xpath=hudson/job[name='ligoj-bootstrap']"))
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
	void validateJobBuilding() throws IOException, URISyntaxException, ParserConfigurationException, SAXException {
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
		Assertions.assertTrue(job.getJobs().get(0).isPullRequestBranch());
		Assertions.assertTrue(job.getJobs().get(0).isBuilding());
		Assertions.assertEquals("PR-2", job.getJobs().get(0).getId());
		Assertions.assertEquals("blue", job.getJobs().get(0).getStatus());
		Assertions.assertEquals(1693000000001L, job.getJobs().get(0).getLastBuild());

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
	void checkSubscriptionStatus() throws IOException, URISyntaxException, ParserConfigurationException, SAXException {
		addJobAccess();
		httpServer.start();

		final var nodeStatusWithData = resource
				.checkSubscriptionStatus(subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkJob((Job) nodeStatusWithData.getData().get("job"), false);
	}

	private void addJobAccess() throws IOException {
		configurationResource.put(JenkinsPluginResource.PARAMETER_MAX_BRANCHES,"2");
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?tree=jobs[displayName,name,color,lastBuild[timestamp],jobs[displayName,name,color,lastBuild[timestamp],property[branch[head]]]]&xpath=hudson/job[name='ligoj-bootstrap']"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/jenkins/jenkins-ligoj-bootstrap-config.xml")
										.getInputStream(),
								StandardCharsets.UTF_8))));
	}

	private void addJobAccessBuilding() throws IOException {
		configurationResource.put(JenkinsPluginResource.PARAMETER_MAX_BRANCHES,"2");
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?tree=jobs[displayName,name,color,lastBuild[timestamp],jobs[displayName,name,color,lastBuild[timestamp],property[branch[head]]]]&xpath=hudson/job[name='ligoj-bootstrap']"))
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

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"))), JenkinsPluginResource.PARAMETER_URL, "jenkins-connection");
	}

	@Test
	void validateAdminAccessLoginFail() {
		httpServer.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.stubFor(get(urlEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"))), JenkinsPluginResource.PARAMETER_USER, "jenkins-login");
	}

	@Test
	void validateAdminAccessNoRight() throws IOException {
		addLoginAccess();
		httpServer.stubFor(get(urlEqualTo("/computer/(master)/config.xml"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_BAD_GATEWAY)));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> resource.validateAdminAccess(pvResource.getNodeParameters("service:build:jenkins:bpr"))), JenkinsPluginResource.PARAMETER_USER, "jenkins-rights");
	}

	@Test
	void findAllByName() throws IOException, SAXException, ParserConfigurationException {
		httpServer.stubFor(get(urlPathEqualTo("/api/xml")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
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
	void findById() throws IOException, URISyntaxException, ParserConfigurationException, SAXException {
		addJobAccessBuilding();
		httpServer.start();
		checkJob(resource.findById("service:build:jenkins:bpr", "ligoj-bootstrap"), true);
	}

	@Test
	void findByIdFail() {
		httpServer.stubFor(get(urlEqualTo(
				"/api/xml?tree=jobs[displayName,name,color,lastBuild[timestamp],jobs[displayName,name,color,lastBuild[timestamp],property[branch[head]]]]&xpath=hudson/job[name='ligoj-bootstraps']"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<hudson/>")));
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
	void create() throws IOException, URISyntaxException {
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
		@SuppressWarnings("unchecked") final Map<String, String> map = Mockito.mock(Map.class);
		Mockito.when(map.get(JenkinsPluginResource.PARAMETER_USER)).thenReturn("some");
		Mockito.when(map.get(JenkinsPluginResource.PARAMETER_TOKEN)).thenReturn("some");
		Mockito.when(map.get(JenkinsPluginResource.PARAMETER_URL)).thenThrow(new RuntimeException());
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
