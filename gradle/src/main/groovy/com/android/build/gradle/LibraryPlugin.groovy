/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.build.gradle

import com.android.build.gradle.internal.BuildTypeData
import com.android.build.gradle.internal.DefaultBuildVariant
import com.android.build.gradle.internal.ProductFlavorData
import com.android.build.gradle.internal.ProductionAppVariant
import com.android.build.gradle.internal.TestAppVariant
import com.android.build.gradle.internal.dependency.ConfigurationDependencies
import com.android.builder.AndroidDependency
import com.android.builder.BuildType
import com.android.builder.BuilderConstants
import com.android.builder.BundleDependency
import com.android.builder.JarDependency
import com.android.builder.ManifestDependency
import com.android.builder.VariantConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.MavenPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.internal.reflect.Instantiator

import javax.inject.Inject

/**
 * Gradle plugin class for 'library' projects.
 */
public class LibraryPlugin extends BasePlugin implements Plugin<Project> {

    private final static String DIR_BUNDLES = "bundles";

    LibraryExtension extension
    BuildTypeData debugBuildTypeData
    BuildTypeData releaseBuildTypeData

    @Inject
    public LibraryPlugin(Instantiator instantiator) {
        super(instantiator)
    }

    @Override
    void apply(Project project) {
        super.apply(project)

        extension = project.extensions.create('android', LibraryExtension,
                this, (ProjectInternal) project, instantiator)
        setDefaultConfig(extension.defaultConfig, extension.sourceSetsContainer)

        // create the source sets for the build type.
        // the ones for the main product flavors are handled by the base plugin.
        def debugSourceSet = extension.sourceSetsContainer.create(BuildType.DEBUG)
        def releaseSourceSet = extension.sourceSetsContainer.create(BuildType.RELEASE)

        debugBuildTypeData = new BuildTypeData(extension.debug, debugSourceSet, project)
        releaseBuildTypeData = new BuildTypeData(extension.release, releaseSourceSet, project)
        project.tasks.assemble.dependsOn debugBuildTypeData.assembleTask
        project.tasks.assemble.dependsOn releaseBuildTypeData.assembleTask

        createConfigurations()
    }

    void createConfigurations() {
        def debugConfig = project.configurations.add(BuildType.DEBUG)
        def releaseConfig = project.configurations.add(BuildType.RELEASE)
        debugConfig.extendsFrom(project.configurations["package"])
        releaseConfig.extendsFrom(project.configurations["package"])
        project.configurations["default"].extendsFrom(releaseConfig)

        // Adjust the pom scope mappings
        // TODO - this should be part of JavaBase plugin. Fix this in Gradle
        project.plugins.withType(MavenPlugin) {
            project.conf2ScopeMappings.addMapping(300, project.configurations.compile, "runtime")
            project.conf2ScopeMappings.addMapping(300, project.configurations["package"], "runtime")
            project.conf2ScopeMappings.addMapping(300, releaseConfig, "runtime")
        }
    }

    @Override
    protected void doCreateAndroidTasks() {
        // resolve dependencies for all config
        List<ConfigurationDependencies> dependencies = []
        dependencies.add(debugBuildTypeData)
        dependencies.add(releaseBuildTypeData)
        resolveDependencies(dependencies)

        ProductionAppVariant testedVariant = createLibraryTasks(debugBuildTypeData)
        ProductionAppVariant nonTestedVariant = createLibraryTasks(releaseBuildTypeData)
        TestAppVariant testVariant = createTestTasks(testedVariant)

        // add the not-tested build variant.
        extension.buildVariants.add(
                instantiator.newInstance(DefaultBuildVariant.class, nonTestedVariant))

        // and add the test variant
        DefaultBuildVariant testBuildVariant = instantiator.newInstance(
                DefaultBuildVariant.class, testVariant)
        extension.testBuildVariants.add(testBuildVariant)

        // and finally the tested variant
        extension.buildVariants.add(
                instantiator.newInstance(DefaultBuildVariant.class,
                        testedVariant, testBuildVariant))
    }

    private ProductionAppVariant createLibraryTasks(BuildTypeData buildTypeData) {
        ProductFlavorData defaultConfigData = getDefaultConfigData();

        List<ConfigurationDependencies> configDependencies = []
        configDependencies.add(defaultConfigData)
        configDependencies.add(buildTypeData)

        // list of dependency to set on the variantConfig
        List<JarDependency> jars = []
        jars.addAll(defaultConfigData.jars)
        jars.addAll(buildTypeData.jars)

        // the order of the libraries is important. In descending order:
        // build types, defaultConfig.
        List<AndroidDependency> libs = []
        libs.addAll(buildTypeData.libraries)
        libs.addAll(defaultConfigData.libraries)

        def variantConfig = new VariantConfiguration(
                defaultConfigData.productFlavor, defaultConfigData.sourceSet,
                buildTypeData.buildType, buildTypeData.sourceSet,
                VariantConfiguration.Type.LIBRARY)

        variantConfig.setJarDependencies(jars)
        variantConfig.setAndroidDependencies(libs)

        ProductionAppVariant variant = new ProductionAppVariant(variantConfig)
        variants.add(variant)

        createPrepareDependenciesTask(variant, configDependencies)

        // Add a task to process the manifest(s)
        createProcessManifestTask(variant, DIR_BUNDLES)

        // Add a task to merge the resource folders
        createMergeResourcesTask(variant, "$project.buildDir/$DIR_BUNDLES/${variant.dirName}/res",
                false /*process9Patch*/)

        // Add a task to create the BuildConfig class
        createBuildConfigTask(variant)

        // Add a task to generate resource source files
        createProcessResTask(variant)

        // process java resources
        createProcessJavaResTask(variant)

        createAidlTask(variant)

        // Add a compile task
        createCompileTask(variant, null/*testedVariant*/)

        // jar the classes.
        Jar jar = project.tasks.add("package${buildTypeData.buildType.name.capitalize()}Jar", Jar);
        jar.dependsOn variant.javaCompileTask, variant.processJavaResources
        jar.from(variant.javaCompileTask.outputs);
        jar.from(variant.processJavaResources.destinationDir)

        jar.destinationDir = project.file("$project.buildDir/$DIR_BUNDLES/${variant.dirName}")
        jar.archiveName = "classes.jar"
        String packageName = variantConfig.getPackageFromManifest().replace('.', '/');
        jar.exclude(packageName + "/R.class")
        jar.exclude(packageName + "/R\$*.class")

        // package the aidl files into the bundle folder
        Copy packageAidl = project.tasks.add("package${variant.name}Aidl", Copy)
        // packageAidl from 3 sources. the order is important to make sure the override works well.
        packageAidl.from(defaultConfigData.sourceSet.aidl.directories,
                buildTypeData.sourceSet.aidl.directories)
        packageAidl.into(project.file("$project.buildDir/$DIR_BUNDLES/${variant.dirName}/aidl"))

        // package the R symbol text file into the bundle folder
        Copy packageSymbol = project.tasks.add("package${variant.name}Symbols", Copy)
        packageSymbol.dependsOn variant.processResourcesTask
        packageSymbol.from(variant.processResourcesTask.textSymbolDir)
        packageSymbol.into(project.file("$project.buildDir/$DIR_BUNDLES/${variant.dirName}"))

        Zip bundle = project.tasks.add("bundle${variant.name}", Zip)
        bundle.dependsOn jar, packageAidl, packageSymbol
        bundle.setDescription("Assembles a bundle containing the library in ${variant.name}.");
        bundle.destinationDir = project.file("$project.buildDir/libs")
        bundle.extension = BuilderConstants.EXT_LIB_ARCHIVE
        if (variant.baseName != BuildType.RELEASE) {
            bundle.classifier = variant.baseName
        }
        bundle.from(project.file("$project.buildDir/$DIR_BUNDLES/${variant.dirName}"))

        variant.packageLibTask = bundle
        variant.outputFile = bundle.archivePath

        project.artifacts.add(buildTypeData.buildType.name, bundle)

        buildTypeData.assembleTask.dependsOn bundle
        variant.assembleTask = bundle

        // configure the variant to be testable.
        variantConfig.output = new BundleDependency(
                project.file("$project.buildDir/$DIR_BUNDLES/${variant.dirName}"),
                variant.getName()) {

            @Override
            List<AndroidDependency> getDependencies() {
                return variantConfig.directLibraries
            }

            @Override
            List<ManifestDependency> getManifestDependencies() {
                return variantConfig.directLibraries
            }
        };

        return variant
    }

    private TestAppVariant createTestTasks(ProductionAppVariant testedVariant) {
        ProductFlavorData defaultConfigData = getDefaultConfigData();

        List<ConfigurationDependencies> configDependencies = []
        configDependencies.add(defaultConfigData.testConfigDependencies)

        // list of dependency to set on the variantConfig
        List<JarDependency> jars = []
        jars.addAll(defaultConfigData.testConfigDependencies.jars)

        // the order of the libraries is important. In descending order:
        // build types, defaultConfig.
        List<AndroidDependency> libs = []
        libs.addAll(defaultConfigData.testConfigDependencies.libraries)

        def testVariantConfig = new VariantConfiguration(
                defaultConfigData.productFlavor, defaultConfigData.testSourceSet,
                debugBuildTypeData.buildType, null,
                VariantConfiguration.Type.TEST, testedVariant.config)

        testVariantConfig.setJarDependencies(jars)
        testVariantConfig.setAndroidDependencies(libs)

        def testVariant = new TestAppVariant(testVariantConfig,)
        variants.add(testVariant)
        createTestTasks(testVariant, testedVariant, configDependencies, true /*mainTestTask*/)

        return testVariant
    }

    @Override
    protected String getTarget() {
        return extension.target
    }
}
