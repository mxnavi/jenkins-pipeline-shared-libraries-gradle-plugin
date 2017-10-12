package com.mkobit.jenkins.pipelines

import mu.KotlinLogging
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.HasConvention
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.GroovySourceSet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.javadoc.Groovydoc
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin
import javax.inject.Inject

open class SharedLibraryPlugin @Inject constructor(
  private val projectLayout: ProjectLayout
) : Plugin<Project> {

  companion object {
    private val logger = KotlinLogging.logger {}
    val JENKINS_REPOSITORY_NAME = "JenkinsPublic"
    val JENKINS_REPOSITORY_URL = "https://repo.jenkins-ci.org/public/"
    private val SHARED_LIBRARY_EXTENSION_NAME = "sharedLibrary"
    private val TEST_ROOT_PATH = "test"
    private val DEFAULT_JENKINS_PIPELINE_UNIT_VERSION = "1.1"
    private val DEFAULT_GROOVY_VERSION = "2.4.11"
    private val DEFAULT_CORE_VERSION = "2.73.1"
    private val DEFAULT_TEST_HARNESS_VERSION = "2.28"
    private val DEFAULT_GIT_PLUGIN_VERSION = "3.5.1"
    private val DEFAULT_WORKFLOW_API_PLUGIN_VERSION = "2.22"
    private val DEFAULT_WORKFLOW_BASIC_STEPS_PLUGIN_VERSION = "2.6"
    private val DEFAULT_WORKFLOW_CPS_PLUGIN_VERSION = "2.40"
    private val DEFAULT_WORKFLOW_DURABLE_TASK_STEP_PLUGIN_VERSION = "2.15"
    private val DEFAULT_WORKFLOW_GLOBAL_CPS_LIBRARY_PLUGIN_VERSION = "2.9"
    private val DEFAULT_WORKFLOW_JOB_PLUGIN_VERSION = "2.14.1"
    private val DEFAULT_WORKFLOW_MULTIBRANCH_PLUGIN_VERSION = "2.16"
    private val DEFAULT_WORKFLOW_STEP_API_PLUGIN_VERSION = "2.13"
    private val DEFAULT_WORKFLOW_SCM_STEP_PLUGIN_VERSION = "2.6"
    private val DEFAULT_WORKFLOW_SUPPORT_PLUGIN_VERSION = "2.15"

    // This configuration is used for an initial resolution to get the required dependencies
    private val JENKINS_PLUGINS_CONFIGURATION = "jenkinsPlugins"

    // These are internal configurations used in the compilation and runtime
    private val UNIT_TESTING_LIBRARY_CONFIGURATION = "jenkinsPipelineUnitTestLibraries"
    private val PLUGIN_HPI_JPI_CONFIGURATION = "jenkinsPluginHpisAndJpis"
    private val PLUGIN_LIBRARY_CONFIGURATION = "jenkinsPluginLibraries"
    private val CORE_LIBRARY_CONFIGURATION = "jenkinsCoreLibraries"
    private val TEST_LIBRARY_CONFIGURATION = "jenkinsTestLibraries"
    private val TEST_LIBRARY_RUNTIME_ONLY_CONFIGURATION = "jenkinsTestLibrariesRuntimeOnly"
  }

  override fun apply(project: Project) {
    project.pluginManager.apply(GroovyPlugin::class.java)
    setupJenkinsRepository(project.repositories)
    val (main, test, integrationTest) = setupJava(project.convention.getPlugin(JavaPluginConvention::class.java))
    val sharedLibraryExtension = setupSharedLibraryExtension(project)
    setupIntegrationTestTask(project.tasks, main, integrationTest)
    setupDocumentationTasks(project.tasks, main)
    setupConfigurations(
      project.configurations,
      main,
      test,
      integrationTest
    )
    setupDependencyManagement(
      project.dependencies,
      project.configurations
    )
    project.afterEvaluate {
      setupGroovyDependency(project.dependencies, sharedLibraryExtension, main)
      addExtensionDependencies(
        project.dependencies,
        sharedLibraryExtension
      )
    }
  }

  private fun setupDependencyManagement(
    dependencies: DependencyHandler,
    configurations: ConfigurationContainer
  ) {
    // TODO: Come up with a better way to collect all the transitive dependencies and HPI/JAR versions of each plugin.
    // Also, forcing dependencies through the extensions does not feel right and is not that intuitive.
    // Instead, it would probably make sense to introduce configurations that users can add additional configuratoin and dependencies to.
    val pluginDeclarations = configurations.getByName(JENKINS_PLUGINS_CONFIGURATION)

    configurations.getByName(PLUGIN_HPI_JPI_CONFIGURATION).let { pluginHpiAndJpi ->
      pluginHpiAndJpi.incoming.beforeResolve {
        pluginDeclarations.resolvedConfiguration.resolvedArtifacts
          .filter { it.extension in setOf("hpi", "jpi") }
          .map { "${it.moduleVersion.id}@${it.extension}" }
          .forEach { dependencies.add(pluginHpiAndJpi.name, it) }
      }
    }
    configurations.getByName(PLUGIN_LIBRARY_CONFIGURATION).let { pluginLibraries ->
      pluginLibraries.incoming.beforeResolve {
        pluginDeclarations.resolvedConfiguration.resolvedArtifacts
          .filter { it.extension in setOf("hpi", "jpi") }
          .map { "${it.moduleVersion.id}@jar" } // Use the published JAR libraries for each plugin
          .forEach { dependencies.add(pluginLibraries.name, it) }
      }
    }
//    val coreLibraries = configurations.getByName(CORE_LIBRARY_CONFIGURATION)
//    val testLibrary = configurations.getByName(TEST_LIBRARY_CONFIGURATION)
//    val testLibraryRuntimeOnly = configurations.getByName(TEST_LIBRARY_RUNTIME_ONLY_CONFIGURATION)
//    val unitTestingLibraries = configurations.getByName(UNIT_TESTING_LIBRARY_CONFIGURATION)
  }

  private fun addExtensionDependencies(
    dependencies: DependencyHandler,
    sharedLibraryExtension: SharedLibraryExtension
  ) {
    dependencies.add(
      TEST_LIBRARY_CONFIGURATION,
      sharedLibraryExtension.testHarnessDependency()
    )

    dependencies.add(
      TEST_LIBRARY_RUNTIME_ONLY_CONFIGURATION,
      "${sharedLibraryExtension.jenkinsWar()}@war"
    )

    dependencies.add(
      CORE_LIBRARY_CONFIGURATION,
      sharedLibraryExtension.coreDependency()
    )

    sharedLibraryExtension.pluginDependencies()
      .pluginDependencies()
      .map { dependencies.createExternal(it.asStringNotation()) }
      .forEach { dependencies.add(JENKINS_PLUGINS_CONFIGURATION, it) }

    dependencies.add(
      UNIT_TESTING_LIBRARY_CONFIGURATION,
      sharedLibraryExtension.pipelineUnitDependency()
    )
  }

  private fun setupDocumentationTasks(tasks: TaskContainer, main: SourceSet) {
    tasks.create("sourcesJar", Jar::class.java) {
      it.apply {
        description = "Assemble the sources JAR"
        classifier = "sources"
        from(main.allSource)
      }
    }

    tasks.create("groovydocJar", Jar::class.java) {
      it.apply {
        val groovydoc = tasks.getByName(GroovyPlugin.GROOVYDOC_TASK_NAME) as Groovydoc
        dependsOn(groovydoc)
        description = "Assemble the Groovydoc JAR"
        classifier = "javadoc"
      }
    }

  }
  private fun setupIntegrationTestTask(
    tasks: TaskContainer,
    main: SourceSet,
    integrationTest: SourceSet
  ) {
    tasks.create("integrationTest", Test::class.java) {
      it.apply {
        dependsOn(main.classesTaskName)
        mustRunAfter("test")
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs tests with the jenkins-test-harness"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        // Set the build directory for Jenkins test harness.
        // See https://issues.jenkins-ci.org/browse/JENKINS-26331
        systemProperty("buildDirectory", projectLayout.buildDirectory.get().asFile.absolutePath)
      }
    }
  }

  private fun setupConfigurations(
    configurations: ConfigurationContainer,
    main: SourceSet,
    test: SourceSet,
    integrationTest: SourceSet
  ) {
    val configurationAction: (Configuration) -> Unit = {
      it.apply {
        isCanBeResolved = true
        isVisible = false
      }
    }
    configurations.create(JENKINS_PLUGINS_CONFIGURATION) {
      it.apply {
        isCanBeResolved = true
      }
    }
    val pluginHpiAndJpi = configurations.create(PLUGIN_HPI_JPI_CONFIGURATION, configurationAction)
    val pluginLibraries = configurations.create(PLUGIN_LIBRARY_CONFIGURATION, configurationAction)
    val coreLibraries = configurations.create(CORE_LIBRARY_CONFIGURATION, configurationAction)
    val testLibrary = configurations.create(TEST_LIBRARY_CONFIGURATION, configurationAction)
    val testLibraryRuntimeOnly = configurations.create(TEST_LIBRARY_RUNTIME_ONLY_CONFIGURATION, configurationAction)
    val unitTestingLibraries = configurations.create(UNIT_TESTING_LIBRARY_CONFIGURATION, configurationAction)

    configurations.getByName(test.implementationConfigurationName).extendsFrom(unitTestingLibraries)

    configurations.getByName(integrationTest.implementationConfigurationName).extendsFrom(
      configurations.getByName(main.implementationConfigurationName),
      configurations.getByName(test.implementationConfigurationName),
      coreLibraries,
      pluginLibraries,
      testLibrary
    )
    configurations.getByName(integrationTest.runtimeOnlyConfigurationName).extendsFrom(
      pluginHpiAndJpi,
      testLibraryRuntimeOnly
    )
  }

  private fun setupGroovyDependency(
    dependencies: DependencyHandler,
    sharedLibrary: SharedLibraryExtension,
    main: SourceSet
  ) {
    logger.debug { "Adding ${sharedLibrary.groovyDependency()} to ${main.implementationConfigurationName}" }
    dependencies.add(
      main.implementationConfigurationName,
      sharedLibrary.groovyDependency()
    )
  }

  private fun setupJenkinsRepository(repositoryHandler: RepositoryHandler) {
    logger.debug { "Adding repository named $JENKINS_REPOSITORY_NAME with URL $JENKINS_REPOSITORY_URL" }
    repositoryHandler.maven {
      it.name = JENKINS_REPOSITORY_NAME
      it.setUrl(JENKINS_REPOSITORY_URL)
    }
  }

  private fun setupJava(
    javaPluginConvention: JavaPluginConvention
  ): Triple<SourceSet, SourceSet, SourceSet> {
    javaPluginConvention.sourceCompatibility = JavaVersion.VERSION_1_8
    javaPluginConvention.targetCompatibility = JavaVersion.VERSION_1_8
    val main = javaPluginConvention.sourceSets.getByName("main").apply {
      java.setSrcDirs(emptyList<String>())
      (this as HasConvention).convention.getPlugin(GroovySourceSet::class.java).groovy.setSrcDirs(listOf("src", "vars"))
      resources.setSrcDirs(listOf("resources"))
    }
    val test = javaPluginConvention.sourceSets.getByName("test").apply {
      val unitTestDirectory = "$TEST_ROOT_PATH/unit"
      java.setSrcDirs(listOf("$unitTestDirectory/java"))
      (this as HasConvention).convention.getPlugin(GroovySourceSet::class.java).groovy.setSrcDirs(listOf("$unitTestDirectory/groovy"))
      resources.setSrcDirs(listOf("$unitTestDirectory/resources"))
    }
    val integrationTest = javaPluginConvention.sourceSets.create("integrationTest").apply {
      val integrationTestDirectory = "$TEST_ROOT_PATH/integration"
      java.setSrcDirs(listOf("$integrationTestDirectory/java"))
      (this as HasConvention).convention.getPlugin(GroovySourceSet::class.java).groovy.setSrcDirs(listOf("$integrationTestDirectory/groovy"))
      resources.setSrcDirs(listOf("$integrationTestDirectory/resources"))
    }

    return Triple(main, test, integrationTest)
  }

  private fun setupSharedLibraryExtension(project: Project): SharedLibraryExtension {
    val groovyVersion = project.initializedProperty(DEFAULT_GROOVY_VERSION)
    val coreVersion = project.initializedProperty(DEFAULT_CORE_VERSION)
    val pipelineTestUnitVersion = project.initializedProperty(DEFAULT_JENKINS_PIPELINE_UNIT_VERSION)
    val testHarnessVersion = project.initializedProperty(DEFAULT_TEST_HARNESS_VERSION)
    val gitPluginVersion = project.initializedProperty(DEFAULT_GIT_PLUGIN_VERSION)
    // TODO: find a better DSL for managing these dependencies, possibly by using aggregator plugin because we are still missing some
    val workflowApiPluginVersion = project.initializedProperty(DEFAULT_WORKFLOW_API_PLUGIN_VERSION)
    val workflowBasicStepsPluginVersion = project.initializedProperty(
      DEFAULT_WORKFLOW_BASIC_STEPS_PLUGIN_VERSION)
    val workflowCpsPluginVersion = project.initializedProperty(DEFAULT_WORKFLOW_CPS_PLUGIN_VERSION)
    val workflowDurableTaskStepPluginVersion = project.initializedProperty(
      DEFAULT_WORKFLOW_DURABLE_TASK_STEP_PLUGIN_VERSION)
    val workflowGlobalCpsLibraryPluginVersion = project.initializedProperty(
      DEFAULT_WORKFLOW_GLOBAL_CPS_LIBRARY_PLUGIN_VERSION)
    val workflowJobPluginVersion = project.initializedProperty(DEFAULT_WORKFLOW_JOB_PLUGIN_VERSION)
    val workflowMultibranchPluginVersion = project.initializedProperty(
      DEFAULT_WORKFLOW_MULTIBRANCH_PLUGIN_VERSION)
    val workflowStepApiPluginVersion = project.initializedProperty(
      DEFAULT_WORKFLOW_STEP_API_PLUGIN_VERSION)
    val workflowScmStepPluginVersion = project.initializedProperty(DEFAULT_WORKFLOW_SCM_STEP_PLUGIN_VERSION)
    val workflowSupportPluginVersion = project.initializedProperty(
      DEFAULT_WORKFLOW_SUPPORT_PLUGIN_VERSION)

    val pluginDependencySpec = PluginDependencySpec(
      gitPluginVersion,
      workflowApiPluginVersion,
      workflowBasicStepsPluginVersion,
      workflowCpsPluginVersion,
      workflowDurableTaskStepPluginVersion,
      workflowGlobalCpsLibraryPluginVersion,
      workflowJobPluginVersion,
      workflowMultibranchPluginVersion,
      workflowScmStepPluginVersion,
      workflowStepApiPluginVersion,
      workflowSupportPluginVersion
    )
    return project.extensions.create(
      SHARED_LIBRARY_EXTENSION_NAME,
      SharedLibraryExtension::class.java,
      groovyVersion,
      coreVersion,
      pipelineTestUnitVersion,
      testHarnessVersion,
      pluginDependencySpec
    )
  }

  private fun DependencyHandler.createExternal(
    dependencyNotation: Any,
    configuration: ExternalModuleDependency.() -> Unit = {}
  ): ExternalModuleDependency = (this.create(
    dependencyNotation) as ExternalModuleDependency).apply(configuration)
}
