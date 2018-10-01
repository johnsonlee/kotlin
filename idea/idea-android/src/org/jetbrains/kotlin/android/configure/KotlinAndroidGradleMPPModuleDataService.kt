/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.android.configure

import com.android.tools.idea.gradle.project.model.AndroidModuleModel
import com.android.tools.idea.gradle.util.ContentEntries.findParentContentEntry
import com.android.tools.idea.io.FilePaths
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.util.SmartList
import com.intellij.util.containers.stream
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import org.jetbrains.kotlin.gradle.KotlinCompilation
import org.jetbrains.kotlin.gradle.KotlinPlatform
import org.jetbrains.kotlin.gradle.KotlinSourceSet
import org.jetbrains.kotlin.idea.configuration.*
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import java.io.File

class KotlinAndroidGradleMPPModuleDataService : AbstractProjectDataService<ModuleData, Void>() {
    override fun getTargetDataKey() = ProjectKeys.MODULE

    override fun postProcess(
        toImport: MutableCollection<DataNode<ModuleData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider
    ) {
        for (nodeToImport in toImport) {
            val projectNode = ExternalSystemApiUtil.findParent(nodeToImport, ProjectKeys.PROJECT) ?: continue
            val moduleData = nodeToImport.data
            val module = modelsProvider.findIdeModule(moduleData) ?: continue
            val rootModel = modelsProvider.getModifiableRootModel(module)
            for (sourceSetInfo in nodeToImport.kotlinAndroidSourceSets ?: emptyList()) {
                val compilation = sourceSetInfo.kotlinModule as? KotlinCompilation ?: continue
                for (sourceSet in compilation.sourceSets) {
                    if (sourceSet.platform == KotlinPlatform.ANDROID) {
                        val sourceType = if (sourceSet.isTestModule) JavaSourceRootType.TEST_SOURCE else JavaSourceRootType.SOURCE
                        val resourceType = if (sourceSet.isTestModule) JavaResourceRootType.TEST_RESOURCE else JavaResourceRootType.RESOURCE
                        sourceSet.sourceDirs.forEach { addSourceRoot(it, sourceType, rootModel) }
                        sourceSet.resourceDirs.forEach { addSourceRoot(it, resourceType, rootModel) }
                    }
                }
            }
            val androidModel = AndroidModuleModel.get(module) ?: continue
            val variantName = androidModel.selectedVariant.name
            val activeSourceSetInfos = nodeToImport.kotlinAndroidSourceSets?.filter { it.kotlinModule.name.startsWith(variantName) } ?: emptyList()
            for (activeSourceSetInfo in activeSourceSetInfos) {
                val activeCompilation = activeSourceSetInfo.kotlinModule as? KotlinCompilation ?: continue
                for (sourceSet in activeCompilation.sourceSets) {
                    if (sourceSet.platform != KotlinPlatform.ANDROID) {
                        val sourceSetId = activeSourceSetInfo.sourceSetIdsByName[sourceSet.name] ?: continue
                        val sourceSetData = ExternalSystemApiUtil.findFirstRecursively(projectNode) {
                            (it.data as? ModuleData)?.id == sourceSetId
                        }?.data as? ModuleData ?: continue
                        val sourceSetModule = modelsProvider.findIdeModule(sourceSetData) ?: continue
                        addModuleDependencyIfNeeded(rootModel, sourceSetModule, activeSourceSetInfo.isTestModule)
                    }
                }
            }
            addExtraDependeeModules(androidModel, projectNode, modelsProvider, rootModel, false)
            addExtraDependeeModules(androidModel, projectNode, modelsProvider, rootModel, true)

            val mainSourceSetInfo = activeSourceSetInfos.firstOrNull { it.kotlinModule.name == variantName }
            if (mainSourceSetInfo != null) {
                KotlinSourceSetDataService.configureFacet(moduleData, mainSourceSetInfo, nodeToImport, module, modelsProvider)
            }

            val kotlinFacet = KotlinFacet.get(module)
            if (kotlinFacet != null) {
                GradleProjectImportHandler.getInstances(project).forEach { it.importByModule(kotlinFacet, nodeToImport) }
            }
        }
    }

    private fun addModuleDependencyIfNeeded(
        rootModel: ModifiableRootModel,
        dependeeModule: Module,
        testScope: Boolean
    ) {
        val dependencyScope = if (testScope) DependencyScope.TEST else DependencyScope.COMPILE
        val existingEntry = rootModel.findModuleOrderEntry(dependeeModule)
        if (existingEntry != null && existingEntry.scope == dependencyScope) return
        rootModel.addModuleOrderEntry(dependeeModule).also { it.scope = dependencyScope }
    }

    private fun addExtraDependeeModules(
        androidModel: AndroidModuleModel,
        projectNode: DataNode<ProjectData>,
        modelsProvider: IdeModifiableModelsProvider,
        rootModel: ModifiableRootModel,
        testScope: Boolean
    ) {
        val dependencies = if (testScope) {
            androidModel.selectedAndroidTestCompileDependencies
        } else {
            androidModel.selectedMainCompileLevel2Dependencies
        } ?: return
        val commonSourceSetName = KotlinSourceSet.commonName(testScope)
        val relevantNodes = dependencies
            .moduleDependencies
            .mapNotNull { projectNode.findChildModuleById(it.projectPath) }
            .flatMap { ExternalSystemApiUtil.getChildren(it, GradleSourceSetData.KEY) }
            .filter {
                val ktModule = it.kotlinSourceSet?.kotlinModule
                ktModule != null && ktModule.isTestModule == testScope
            }
        SmartList<DataNode<GradleSourceSetData>>()
            .apply {
                addIfNotNull(
                    relevantNodes.firstOrNull { it.kotlinSourceSet?.platform == KotlinPlatform.ANDROID }
                        ?: relevantNodes.firstOrNull { it.kotlinSourceSet?.platform == KotlinPlatform.JVM }
                )
                addIfNotNull(
                    relevantNodes.firstOrNull {
                        it.kotlinSourceSet?.platform == KotlinPlatform.COMMON && it.kotlinSourceSet?.kotlinModule?.name == commonSourceSetName
                    }
                )
            }
            .mapNotNull { modelsProvider.findIdeModule(it.data) }
            .forEach {
                addModuleDependencyIfNeeded(rootModel, it, testScope)
                val dependeeRootModel = modelsProvider.getModifiableRootModel(it)
                dependeeRootModel.getModuleDependencies(testScope).forEach { transitiveDependee ->
                    addModuleDependencyIfNeeded(rootModel, transitiveDependee, testScope)
                }
            }
    }

    private fun addSourceRoot(
        sourceRoot: File,
        type: JpsModuleSourceRootType<*>,
        rootModel: ModifiableRootModel
    ) {
        val parent = findParentContentEntry(sourceRoot, rootModel.contentEntries.stream()) ?: return
        val url = FilePaths.pathToIdeaUrl(sourceRoot)
        parent.addSourceFolder(url, type)
    }
}